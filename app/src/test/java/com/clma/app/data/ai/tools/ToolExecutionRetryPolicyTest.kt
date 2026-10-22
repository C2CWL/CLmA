package me.rerere.rikkahub.data.ai.tools

import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.HttpException
import me.rerere.rikkahub.data.ai.tools.ToolExecutionRetryPolicy.RetryOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the T-05 / (3) execution-layer retry policy:
 *
 *  - only idempotent READ-ONLY calls are ever eligible (a retried write would duplicate its
 *    side effect) — usually decided by the tool name, except for `web_fetch`, whose HTTP verb
 *    lives in its arguments (T-11),
 *  - only transient failures are retried (a deterministic one would fail identically forever),
 *  - both failure shapes count — a thrown exception AND the `{"error":"timeout"}` envelope that
 *    most local network tools return instead of throwing,
 *  - the loop respects the attempt cap, the wall-clock budget and cancellation.
 */
class ToolExecutionRetryPolicyTest {

    private fun text(value: String) = listOf(UIMessagePart.Text(value))

    // ---------------------------------------------------------------- eligibility

    @Test
    fun `read only tools carrying a safe prefix are eligible`() {
        val names = listOf(
            "get_battery_status", "get_wifi_info", "get_location", "get_volume",
            "list_files", "list_installed_apps", "list_contacts", "list_zip_contents",
            "read_file", "read_sensor", "read_window_tree",
            "search_web", "search_contacts", "search_sms",
            "find_files", "find_node",
            "scrape_web",
            // T-11: `web_extract` is GET-only, so it belongs here. `web_fetch` was REMOVED from
            // this list — its verb is an argument, see the `web_fetch` tests below.
            "web_extract",
        )
        names.forEach { name ->
            assertTrue("$name should be retry-eligible", ToolExecutionRetryPolicy.isIdempotentReadOnly(name))
        }
    }

    @Test
    fun `read only tools without a safe prefix are eligible`() {
        val names = listOf(
            "conversation_search", "recent_chats", "whisper_status", "tool_search",
            "notification_status", "keyboard_editor_info", "keyboard_read_field",
            "file_info", "keystore_list_keys", "keystore_verify",
            "workspace_read_file", "workspace_read_folder", "workspace_background_status",
        )
        names.forEach { name ->
            assertTrue("$name should be retry-eligible", ToolExecutionRetryPolicy.isIdempotentReadOnly(name))
        }
    }

    @Test
    fun `write and side effecting tools are never eligible`() {
        val names = listOf(
            "write_text_file", "write_binary_file", "delete_file", "move_file", "copy_file",
            "create_directory", "batch_copy", "batch_move", "batch_delete", "zip_files",
            "unzip_file", "open_file", "share", "show_image",
            "workspace_shell", "workspace_write_file", "workspace_edit_file",
            "workspace_run_background", "workspace_background_kill", "workspace_create_folder",
            "memory_tool", "clipboard_tool", "compact_context", "use_skill", "tool_open",
            "notification_reply", "notification_action_click", "dismiss_notification",
            "post_notification", "telegram_send_message", "telegram_send_photo",
            "telegram_set_token", "record_audio", "speech_to_text", "take_photo",
            "take_screenshot", "verify_fingerprint", "nfc_write_tag", "launch_app",
            "keystore_generate_key", "keystore_decrypt", "keystore_encrypt", "keystore_sign",
            "keystore_delete_key", "termux_run_command", "shizuku_exec", "eval_javascript",
            "ssh_exec", "ssh_upload", "schedule_job", "trigger_job_now", "set_volume",
        )
        names.forEach { name ->
            assertFalse("$name must not be retry-eligible", ToolExecutionRetryPolicy.isIdempotentReadOnly(name))
        }
    }

    @Test
    fun `mcp relayed tools are never eligible even when the leaf name reads like a read`() {
        listOf(
            "mcp__github__get_file_contents",
            "mcp__github__list_commits",
            "mcp__weather__search_locations",
            "mcp_raw",
        ).forEach { name ->
            assertFalse("$name must not be retry-eligible", ToolExecutionRetryPolicy.isIdempotentReadOnly(name))
        }
    }

    @Test
    fun `eligibility trims surrounding whitespace and rejects blank names`() {
        assertTrue(ToolExecutionRetryPolicy.isIdempotentReadOnly("  read_file  "))
        assertFalse(ToolExecutionRetryPolicy.isIdempotentReadOnly(""))
        assertFalse(ToolExecutionRetryPolicy.isIdempotentReadOnly("   "))
    }

    // ------------------------------------------ T-11: web_fetch is decided by its arguments

    private fun args(json: String): JsonElement = Json.parseToJsonElement(json)

    @Test
    fun `a web_fetch call without a method defaults to GET and stays eligible`() {
        assertTrue(ToolExecutionRetryPolicy.isIdempotentReadOnly("web_fetch", args("{}")))
        assertTrue(
            ToolExecutionRetryPolicy.isIdempotentReadOnly(
                "web_fetch",
                args("""{"url":"https://example.com"}"""),
            ),
        )
    }

    @Test
    fun `a web_fetch read verb is eligible whatever the casing or padding`() {
        listOf("GET", "get", "Head", " HEAD ").forEach { verb ->
            assertTrue(
                "method=$verb must stay retry-eligible",
                ToolExecutionRetryPolicy.isIdempotentReadOnly(
                    "web_fetch",
                    args("""{"url":"https://example.com","method":"$verb"}"""),
                ),
            )
        }
    }

    @Test
    fun `a web_fetch write verb is never eligible`() {
        listOf("POST", "post", "Put", "PATCH", "delete", "TRACE").forEach { verb ->
            assertFalse(
                "method=$verb must never be retry-eligible",
                ToolExecutionRetryPolicy.isIdempotentReadOnly(
                    "web_fetch",
                    args("""{"url":"https://example.com","method":"$verb"}"""),
                ),
            )
        }
    }

    @Test
    fun `a web_fetch call whose verb cannot be proven is not eligible`() {
        // No arguments, a non-object payload, an explicit null verb, a blank verb, a number and
        // an object all leave the verb unknown. Refusing is the cheap side of the asymmetry: a
        // skipped retry costs one round-trip, a re-sent POST duplicates whatever it did.
        assertFalse(ToolExecutionRetryPolicy.isIdempotentReadOnly("web_fetch"))
        assertFalse(ToolExecutionRetryPolicy.isIdempotentReadOnly("web_fetch", JsonNull))
        assertFalse(ToolExecutionRetryPolicy.isIdempotentReadOnly("web_fetch", args("[]")))
        assertFalse(ToolExecutionRetryPolicy.isIdempotentReadOnly("web_fetch", args("\"GET\"")))
        assertFalse(ToolExecutionRetryPolicy.isIdempotentReadOnly("web_fetch", args("""{"method":null}""")))
        assertFalse(ToolExecutionRetryPolicy.isIdempotentReadOnly("web_fetch", args("""{"method":""}""")))
        assertFalse(ToolExecutionRetryPolicy.isIdempotentReadOnly("web_fetch", args("""{"method":5}""")))
        assertFalse(ToolExecutionRetryPolicy.isIdempotentReadOnly("web_fetch", args("""{"method":{"a":1}}""")))
    }

    @Test
    fun `web_extract stays eligible without any argument inspection`() {
        assertTrue(ToolExecutionRetryPolicy.isIdempotentReadOnly("web_extract"))
        assertTrue(
            ToolExecutionRetryPolicy.isIdempotentReadOnly(
                "web_extract",
                args("""{"url":"https://example.com"}"""),
            ),
        )
        // Its sibling's gate must not leak onto it, even for nonsense arguments.
        assertTrue(
            ToolExecutionRetryPolicy.isIdempotentReadOnly(
                "web_extract",
                args("""{"method":"DELETE"}"""),
            ),
        )
    }

    @Test
    fun `the argument gate is scoped to web_fetch and leaves every other tool alone`() {
        // A `method` argument means nothing to these tools — their eligibility comes from the
        // name, and they must not silently lose their retry because a caller passed one.
        listOf("read_file", "list_files", "search_web", "scrape_web", "file_info").forEach { name ->
            assertTrue(
                "$name must stay retry-eligible",
                ToolExecutionRetryPolicy.isIdempotentReadOnly(name, args("""{"method":"POST"}""")),
            )
        }
    }

    @Test
    fun `a transient envelope no longer buys a write a retry`() {
        // The envelope track lives inside `runWithRetries`, which is only entered when the gate
        // above passes. So these two facts together are what stop a timed-out POST from being
        // re-sent: the envelope IS classified transient, and the call is NOT eligible.
        val transientEnvelope = text("""{"error":"network_error","detail":"socket closed"}""")
        assertTrue(ToolExecutionRetryPolicy.isTransientToolOutput(transientEnvelope))
        assertTrue(
            ToolExecutionRetryPolicy.isIdempotentReadOnly(
                "web_fetch",
                args("""{"url":"https://x","method":"GET"}"""),
            ),
        )
        assertFalse(
            ToolExecutionRetryPolicy.isIdempotentReadOnly(
                "web_fetch",
                args("""{"url":"https://x","method":"POST"}"""),
            ),
        )
    }

    // ------------------------------------------------------- transient failures

    @Test
    fun `timeouts and plain io failures are transient`() {
        assertTrue(ToolExecutionRetryPolicy.isTransientFailure(SocketTimeoutException("read timed out")))
        assertTrue(ToolExecutionRetryPolicy.isTransientFailure(IOException("Software caused connection abort")))
    }

    @Test
    fun `dns and connect failures are transient`() {
        assertTrue(ToolExecutionRetryPolicy.isTransientFailure(UnknownHostException("api.example.com")))
        assertTrue(ToolExecutionRetryPolicy.isTransientFailure(ConnectException("Connection refused")))
    }

    @Test
    fun `a missing file is never transient`() {
        assertFalse(ToolExecutionRetryPolicy.isTransientFailure(FileNotFoundException("/nope.txt")))
    }

    @Test
    fun `retryable http statuses are transient`() {
        listOf(408, 409, 425, 429, 500, 502, 503, 504).forEach { status ->
            assertTrue(
                "HTTP $status should be transient",
                ToolExecutionRetryPolicy.isTransientFailure(HttpException("boom", status)),
            )
        }
    }

    @Test
    fun `deterministic http statuses are not transient`() {
        listOf(400, 401, 403, 404, 422, 451).forEach { status ->
            assertFalse(
                "HTTP $status should not be transient",
                ToolExecutionRetryPolicy.isTransientFailure(HttpException("nope", status)),
            )
        }
    }

    @Test
    fun `an http exception with no status code is treated as transient`() {
        assertTrue(ToolExecutionRetryPolicy.isTransientFailure(HttpException("unknown")))
    }

    @Test
    fun `validation exceptions are not transient`() {
        assertFalse(ToolExecutionRetryPolicy.isTransientFailure(IllegalStateException("name is required")))
        assertFalse(ToolExecutionRetryPolicy.isTransientFailure(IllegalArgumentException("bad args")))
    }

    @Test
    fun `cancellation is never transient`() {
        assertFalse(ToolExecutionRetryPolicy.isTransientFailure(CancellationException("user pressed stop")))
    }

    @Test
    fun `a transient cause under a wrapper is detected`() {
        val wrapped = RuntimeException("tool blew up", IOException("connection reset by peer"))
        assertTrue(ToolExecutionRetryPolicy.isTransientFailure(wrapped))
    }

    @Test
    fun `a quota exhausted 429 is not transient`() {
        val quota = HttpException("429 Resource has been exhausted (e.g. check quota).", 429)
        assertFalse(ToolExecutionRetryPolicy.isTransientFailure(quota))
    }

    @Test
    fun `a plain 429 stays transient`() {
        assertTrue(ToolExecutionRetryPolicy.isTransientFailure(HttpException("Too many requests", 429)))
    }

    @Test
    fun `context limit failures are not transient`() {
        val context = RuntimeException("This model's maximum context length is 128000 tokens")
        assertFalse(ToolExecutionRetryPolicy.isTransientFailure(context))
    }

    // -------------------------------------------------------- transient output

    @Test
    fun `transient error envelopes are detected`() {
        listOf(
            "timeout", "tool_timeout", "command_timeout", "network_error",
            "connect_failed", "tcp_unreachable", "browser_task_timeout",
            "browser_session_lost", "browser_busy",
        ).forEach { code ->
            assertTrue(
                "$code envelope should be transient",
                ToolExecutionRetryPolicy.isTransientToolOutput(text("""{"error":"$code"}""")),
            )
        }
    }

    @Test
    fun `deterministic error envelopes are not transient`() {
        listOf(
            "bad_url", "bad_request", "missing_path", "missing_required_arg", "blocked_address",
            "readability_failed", "empty_extraction", "not_found", "permission_denied",
            "directory_not_granted", "browser_not_open", "no_active_window", "invalid_tool_args",
            "tool_failed", "string_not_found",
        ).forEach { code ->
            assertFalse(
                "$code envelope must not be transient",
                ToolExecutionRetryPolicy.isTransientToolOutput(text("""{"error":"$code"}""")),
            )
        }
    }

    @Test
    fun `a transient error envelope is detected regardless of casing and whitespace`() {
        assertTrue(ToolExecutionRetryPolicy.isTransientToolOutput(text("""  {"error":"TIMEOUT"}  """)))
    }

    @Test
    fun `a 5xx fetch envelope without an error code is transient`() {
        assertTrue(ToolExecutionRetryPolicy.isTransientToolOutput(text("""{"status":503,"ok":false,"body":""}""")))
        assertTrue(ToolExecutionRetryPolicy.isTransientToolOutput(text("""{"status":429,"ok":false}""")))
        assertTrue(ToolExecutionRetryPolicy.isTransientToolOutput(text("""{"status":500,"ok":false}""")))
    }

    @Test
    fun `a successful fetch envelope is not transient`() {
        assertFalse(ToolExecutionRetryPolicy.isTransientToolOutput(text("""{"status":200,"ok":true,"body":"hi"}""")))
        assertFalse(ToolExecutionRetryPolicy.isTransientToolOutput(text("""{"status":404,"ok":false}""")))
    }

    @Test
    fun `non envelope output is not transient`() {
        assertFalse(ToolExecutionRetryPolicy.isTransientToolOutput(text("just some plain text")))
        assertFalse(ToolExecutionRetryPolicy.isTransientToolOutput(text("[1,2,3]")))
        assertFalse(ToolExecutionRetryPolicy.isTransientToolOutput(text("{\"error\"")))
        assertFalse(ToolExecutionRetryPolicy.isTransientToolOutput(emptyList()))
        assertFalse(ToolExecutionRetryPolicy.isTransientToolOutput(listOf(UIMessagePart.Image("https://example.com/a.png"))))
    }

    @Test
    fun `one transient part among many is enough`() {
        val mixed = listOf(
            UIMessagePart.Text("""{"error":"bad_url"}"""),
            UIMessagePart.Text("""{"error":"network_error"}"""),
        )
        assertTrue(ToolExecutionRetryPolicy.isTransientToolOutput(mixed))
    }

    // ------------------------------------------------------------ backoff

    @Test
    fun `retry delay backs off exponentially and caps`() {
        assertEquals(500L, ToolExecutionRetryPolicy.retryDelayMs(1))
        assertEquals(1_000L, ToolExecutionRetryPolicy.retryDelayMs(2))
        assertEquals(2_000L, ToolExecutionRetryPolicy.retryDelayMs(3))
        assertEquals(4_000L, ToolExecutionRetryPolicy.retryDelayMs(4))
        assertEquals(4_000L, ToolExecutionRetryPolicy.retryDelayMs(9))
    }

    // --------------------------------------------------------------- loop

    @Test
    fun `a transient failure is retried and then succeeds`() = runBlocking {
        var attempts = 0
        val slept = mutableListOf<Long>()
        val outcome = ToolExecutionRetryPolicy.runWithRetries(
            firstAttemptRemainingMs = 10_000L,
            remainingMsProvider = { 10_000L },
            execute = {
                attempts++
                if (attempts == 1) throw IOException("connection reset by peer") else text("""{"ok":true}""")
            },
            sleep = { slept += it },
        )

        assertEquals(2, attempts)
        assertEquals(listOf(500L), slept)
        assertTrue(outcome is RetryOutcome.Completed)
        assertEquals(2, (outcome as RetryOutcome.Completed).attempts)
    }

    @Test
    fun `a deterministic failure is not retried`() = runBlocking {
        var attempts = 0
        val outcome = ToolExecutionRetryPolicy.runWithRetries(
            firstAttemptRemainingMs = 10_000L,
            remainingMsProvider = { 10_000L },
            execute = {
                attempts++
                throw IllegalStateException("name is required")
            },
            sleep = { },
        )

        assertEquals(1, attempts)
        assertTrue(outcome is RetryOutcome.Failed)
        assertEquals(1, (outcome as RetryOutcome.Failed).attempts)
    }

    @Test
    fun `retries stop after the configured maximum`() = runBlocking {
        var attempts = 0
        val slept = mutableListOf<Long>()
        val outcome = ToolExecutionRetryPolicy.runWithRetries(
            firstAttemptRemainingMs = 10_000L,
            remainingMsProvider = { 10_000L },
            execute = {
                attempts++
                throw SocketTimeoutException("read timed out")
            },
            sleep = { slept += it },
        )

        assertEquals(ToolExecutionRetryPolicy.MAX_RETRIES + 1, attempts)
        assertEquals(listOf(500L, 1_000L), slept)
        assertTrue(outcome is RetryOutcome.Failed)
        assertEquals(3, (outcome as RetryOutcome.Failed).attempts)
    }

    @Test
    fun `a transient error envelope triggers a retry`() = runBlocking {
        var attempts = 0
        val outcome = ToolExecutionRetryPolicy.runWithRetries(
            firstAttemptRemainingMs = 10_000L,
            remainingMsProvider = { 10_000L },
            execute = {
                attempts++
                if (attempts == 1) text("""{"error":"network_error"}""") else text("""{"status":200,"ok":true}""")
            },
            sleep = { },
        )

        assertEquals(2, attempts)
        assertTrue(outcome is RetryOutcome.Completed)
    }

    @Test
    fun `a deterministic error envelope is returned without a retry`() = runBlocking {
        var attempts = 0
        val outcome = ToolExecutionRetryPolicy.runWithRetries(
            firstAttemptRemainingMs = 10_000L,
            remainingMsProvider = { 10_000L },
            execute = {
                attempts++
                text("""{"error":"blocked_address"}""")
            },
            sleep = { },
        )

        assertEquals(1, attempts)
        assertTrue(outcome is RetryOutcome.Completed)
        assertEquals(1, (outcome as RetryOutcome.Completed).attempts)
    }

    @Test
    fun `a mid execution timeout is terminal and never retried`() = runBlocking {
        var attempts = 0
        val outcome = ToolExecutionRetryPolicy.runWithRetries(
            firstAttemptRemainingMs = 10_000L,
            remainingMsProvider = { 10_000L },
            execute = {
                attempts++
                null
            },
            sleep = { },
        )

        assertEquals(1, attempts)
        assertTrue(outcome is RetryOutcome.TimedOut)
        assertEquals(1, (outcome as RetryOutcome.TimedOut).attempts)
    }

    @Test
    fun `a spent budget stops before the first attempt`() = runBlocking {
        var attempts = 0
        val outcome = ToolExecutionRetryPolicy.runWithRetries(
            firstAttemptRemainingMs = 0L,
            remainingMsProvider = { 0L },
            execute = {
                attempts++
                text("""{"ok":true}""")
            },
            sleep = { },
        )

        assertEquals(0, attempts)
        assertTrue(outcome is RetryOutcome.BudgetExhausted)
    }

    @Test
    fun `the budget is re-read between attempts and can cut the retry short`() = runBlocking {
        var attempts = 0
        var budgetReads = 0
        val outcome = ToolExecutionRetryPolicy.runWithRetries(
            firstAttemptRemainingMs = 10_000L,
            remainingMsProvider = {
                budgetReads++
                if (budgetReads >= 2) 0L else 10_000L
            },
            execute = {
                attempts++
                throw IOException("connection reset by peer")
            },
            sleep = { },
        )

        assertEquals(2, attempts)
        assertTrue(outcome is RetryOutcome.BudgetExhausted)
        assertEquals(2, (outcome as RetryOutcome.BudgetExhausted).attempts)
    }

    @Test
    fun `cancellation propagates out of the retry loop`() {
        var attempts = 0
        val thrown = runCatching {
            runBlocking {
                ToolExecutionRetryPolicy.runWithRetries(
                    firstAttemptRemainingMs = 10_000L,
                    remainingMsProvider = { 10_000L },
                    execute = {
                        attempts++
                        throw CancellationException("user pressed stop")
                    },
                    sleep = { },
                )
            }
        }.exceptionOrNull()

        assertEquals(1, attempts)
        assertTrue("cancellation must not be swallowed", thrown is CancellationException)
    }
}
