package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the T-10 / (9) headless refusal policy:
 *
 *  - every tool the user reserved for a per-call confirmation (`NO_ALWAYS_ALLOW`) is refused,
 *  - the microphone group is refused too (nobody present to consent),
 *  - an ordinary tool is never refused — the policy only ever subtracts five named groups,
 *  - a conversation WITH an approval channel is untouched,
 *  - the refusal is a deterministic envelope, so it can never be retried.
 *
 * The wiring (`GenerationLoop` / `WorkflowEngine` calling this) is not reachable from a JVM
 * unit test — `GenerationLoop` needs Android and `WorkflowEngine` needs a repository — so what
 * is pinned here is the decision table both call sites read. CI covers the call sites.
 */
class HeadlessToolApprovalPolicyTest {

    // ---------------------------------------------------------------- refusals

    @Test
    fun `every per-call-confirmation tool is refused`() {
        assertTrue(
            "the upstream set must not be empty, or this test proves nothing",
            ToolApprovalDefaults.NO_ALWAYS_ALLOW.isNotEmpty(),
        )
        HeadlessToolApprovalPolicy.PER_CALL_CONFIRM_TOOL_NAMES.forEach { name ->
            assertEquals(
                "$name must be refused in a headless run",
                true,
                HeadlessToolApprovalPolicy.isRefused(name),
            )
        }
    }

    @Test
    fun `the install tools run headless but land disabled`() {
        // D5 - a sub-agent may install, but nothing it installs is live until a human
        // enables it (mcp_add writes disabled; skill install no longer auto-enables).
        assertTrue(HeadlessToolApprovalPolicy.INSTALL_TOOL_NAMES.isNotEmpty())
        HeadlessToolApprovalPolicy.INSTALL_TOOL_NAMES.forEach { name ->
            assertFalse("$name must be allowed headless since D5", HeadlessToolApprovalPolicy.isRefused(name))
            assertNull(HeadlessToolApprovalPolicy.refusalEnvelope(name))
        }
        assertFalse(
            HeadlessToolApprovalPolicy.REFUSED_TOOL_NAMES.containsAll(
                HeadlessToolApprovalPolicy.INSTALL_TOOL_NAMES,
            ),
        )
    }

    @Test
    fun `the named privilege escalation surfaces are refused`() {
        // The card's examples, spelled out so a reshuffle of NO_ALWAYS_ALLOW cannot quietly
        // drop one without this test going red.
        listOf(
            "eval_javascript",
            "browser_eval_js",
            "keystore_generate_key",
            "keystore_decrypt",
            "nfc_write_tag",
            "grant_directory_access",
        ).forEach { name ->
            assertTrue("$name must be refused", HeadlessToolApprovalPolicy.isRefused(name))
        }
    }

    @Test
    fun `the microphone group is refused with nobody present to consent`() {
        assertTrue(HeadlessToolApprovalPolicy.isRefused("record_audio"))
        assertTrue(HeadlessToolApprovalPolicy.isRefused("speech_to_text"))
    }

    @Test
    fun `the expert-library writes are refused, and only in a headless run`() {
        // P2-06b — a headless run has no approval channel, and rewriting the roster changes what
        // every later dispatch resolves to. The read-only sibling stays available.
        assertEquals(
            setOf("subagent_create", "subagent_update", "subagent_delete"),
            HeadlessToolApprovalPolicy.EXPERT_WRITE_TOOL_NAMES,
        )
        HeadlessToolApprovalPolicy.EXPERT_WRITE_TOOL_NAMES.forEach { name ->
            assertTrue("$name must be refused", HeadlessToolApprovalPolicy.isRefused(name))
            assertTrue(
                "$name detail must point at the roster",
                HeadlessToolApprovalPolicy.refusalDetail(name)!!.contains("expert library"),
            )
            assertNull(
                "$name must still prompt normally in the foreground",
                HeadlessToolApprovalPolicy.refusalEnvelopeFor(name, headless = false),
            )
            assertNotNull(HeadlessToolApprovalPolicy.refusalEnvelopeFor(name, headless = true))
        }
        // The read-only sibling and the dispatch handle are untouched.
        assertNull(HeadlessToolApprovalPolicy.refusalDetail("subagent_list"))
        assertNull(HeadlessToolApprovalPolicy.refusalDetail("subagent_dispatch"))
    }

    @Test
    fun `the refused set is exactly the union of the five groups`() {
        assertEquals(
            HeadlessToolApprovalPolicy.PER_CALL_CONFIRM_TOOL_NAMES +
                HeadlessToolApprovalPolicy.PRIVACY_SENSITIVE_TOOL_NAMES +
                HeadlessToolApprovalPolicy.PRIVATE_DATA_TOOL_NAMES +
                HeadlessToolApprovalPolicy.EXPERT_WRITE_TOOL_NAMES +
                HeadlessToolApprovalPolicy.MODEL_WRITE_TOOL_NAMES,
            HeadlessToolApprovalPolicy.REFUSED_TOOL_NAMES,
        )
        HeadlessToolApprovalPolicy.REFUSED_TOOL_NAMES.forEach { name ->
            assertNotNull("$name is in the set but has no detail", HeadlessToolApprovalPolicy.refusalDetail(name))
        }
    }

    @Test
    fun `the five groups do not overlap`() {
        val upstream = ToolApprovalDefaults.NO_ALWAYS_ALLOW
        val mic = HeadlessToolApprovalPolicy.PRIVACY_SENSITIVE_TOOL_NAMES
        val private = HeadlessToolApprovalPolicy.PRIVATE_DATA_TOOL_NAMES
        val expertWrites = HeadlessToolApprovalPolicy.EXPERT_WRITE_TOOL_NAMES
        val modelWrites = HeadlessToolApprovalPolicy.MODEL_WRITE_TOOL_NAMES
        val groups = listOf(upstream, mic, private, expertWrites, modelWrites)
        groups.forEachIndexed { index, left ->
            groups.drop(index + 1).forEach { right ->
                assertTrue("two headless-refusal groups overlap", (left intersect right).isEmpty())
            }
        }
    }

    @Test
    fun `the model-roster writes are refused, and only in a headless run`() {
        // P2-37 — a headless run has no approval channel, and the model roster is what every
        // assistant's model picker and every later generation resolves against. model_list
        // stays available so a schedule can still see what exists.
        assertEquals(
            setOf("model_add", "model_update", "model_delete"),
            HeadlessToolApprovalPolicy.MODEL_WRITE_TOOL_NAMES,
        )
        HeadlessToolApprovalPolicy.MODEL_WRITE_TOOL_NAMES.forEach { name ->
            assertTrue("$name must be refused", HeadlessToolApprovalPolicy.isRefused(name))
            assertNotNull(HeadlessToolApprovalPolicy.refusalEnvelopeFor(name, headless = true))
            assertNull(
                "$name must still prompt normally in the foreground",
                HeadlessToolApprovalPolicy.refusalEnvelopeFor(name, headless = false),
            )
        }
        assertNull(HeadlessToolApprovalPolicy.refusalDetail("model_list"))
    }

    // ------------------------------------------------------------- non-refusals

    @Test
    fun `ordinary tools are never refused`() {
        // Everything a cron job or a workflow legitimately does during the night. A false
        // positive here breaks a working schedule, which is why the policy is a closed set
        // rather than a heuristic.
        listOf(
            "read_file", "list_files", "write_text_file", "workspace_shell", "web_fetch",
            "web_extract", "search_web", "termux_run_command", "ssh_exec", "shizuku_exec",
            "get_battery_status", "get_location", "share",
            "open_file", "launch_app", "post_notification", "telegram_send_message",
            "subagent_dispatch", "ask_user", "mcp__github__get_file_contents",
            "keystore_encrypt", "keystore_verify", "keystore_list_keys",
        ).forEach { name ->
            assertFalse("$name must stay runnable headless", HeadlessToolApprovalPolicy.isRefused(name))
            assertNull("$name must produce no envelope", HeadlessToolApprovalPolicy.refusalEnvelope(name))
        }
    }

    @Test
    fun `a conversation with an approval channel is never refused`() {
        // The "non-headless behaviour is unchanged" property, as a test rather than a claim:
        // the flag is the caller's, and the policy is an identity function on it.
        HeadlessToolApprovalPolicy.REFUSED_TOOL_NAMES.forEach { name ->
            assertNull(
                "$name must not be pre-refused for a foreground conversation",
                HeadlessToolApprovalPolicy.refusalEnvelopeFor(name, headless = false),
            )
        }
        // ...and the same call with the flag on does produce one, so the null above is caused
        // by the flag and not by an empty policy.
        assertNotNull(
            HeadlessToolApprovalPolicy.refusalEnvelopeFor("eval_javascript", headless = true),
        )
    }

    @Test
    fun `the false branch of the flag is what suppresses the envelope`() {
        assertNull(HeadlessToolApprovalPolicy.refusalEnvelopeFor("eval_javascript", false))
        assertNotNull(HeadlessToolApprovalPolicy.refusalEnvelopeFor("eval_javascript", true))
    }

    // ------------------------------------------------------------------ envelope

    @Test
    fun `the envelope is JSON carrying the code, the tool and a detail`() {
        val raw = HeadlessToolApprovalPolicy.refusalEnvelope("eval_javascript")
        assertNotNull(raw)
        val obj = Json.parseToJsonElement(raw!!).jsonObject
        assertEquals(HeadlessToolApprovalPolicy.ERROR_CODE, obj["error"]?.jsonPrimitive?.contentOrNull)
        assertEquals("eval_javascript", obj["tool"]?.jsonPrimitive?.contentOrNull)
        val detail = obj["detail"]?.jsonPrimitive?.contentOrNull
        assertNotNull(detail)
        assertTrue("the detail should name the tool", detail!!.contains("eval_javascript"))
    }

    @Test
    fun `the error code matches the vocabulary the sub-agent surface uses`() {
        // T-09 rejects at the surface, T-10 rejects at execution; a model should read the same
        // code from both, so the string is pinned rather than free to drift. (The T-09 side is
        // `SubAgentToolSurface.denialReason`, which returns this same literal for a
        // NO_ALWAYS_ALLOW tool — verified by reading it 2026-10-01, not restated here because
        // that file pulls in the whole `ai` module and would drag Android into this JVM test.)
        assertEquals("tool_not_authorized", HeadlessToolApprovalPolicy.ERROR_CODE)
    }

    @Test
    fun `a refusal envelope is never classified as a transient failure`() {
        // ToolExecutionRetryPolicy retries transient envelopes. A refusal is deterministic —
        // retrying it would spend attempts on a decision that cannot change.
        HeadlessToolApprovalPolicy.REFUSED_TOOL_NAMES.forEach { name ->
            val raw = HeadlessToolApprovalPolicy.refusalEnvelope(name)!!
            assertFalse(
                "the $name refusal must not look retryable",
                ToolExecutionRetryPolicy.isTransientToolOutput(listOf(UIMessagePart.Text(raw))),
            )
        }
    }

    // -------------------------------------------------------------- edge inputs

    @Test
    fun `the tool name is trimmed and a blank name is not a refusal`() {
        assertTrue(HeadlessToolApprovalPolicy.isRefused("  eval_javascript  "))
        assertEquals(
            "eval_javascript",
            Json.parseToJsonElement(HeadlessToolApprovalPolicy.refusalEnvelope(" eval_javascript ")!!)
                .jsonObject["tool"]?.jsonPrimitive?.contentOrNull,
        )
        assertFalse(HeadlessToolApprovalPolicy.isRefused(""))
        assertFalse(HeadlessToolApprovalPolicy.isRefused("   "))
        assertNull(HeadlessToolApprovalPolicy.refusalEnvelope(""))
    }

    @Test
    fun `a prefix of a refused name is not refused`() {
        // Membership, not prefix matching: a future `eval_javascript_v2` must be added to the
        // set on purpose rather than inheriting a refusal from its name.
        assertFalse(HeadlessToolApprovalPolicy.isRefused("eval_javascript_v2"))
        assertFalse(HeadlessToolApprovalPolicy.isRefused("mcp_add_server"))
        assertFalse(HeadlessToolApprovalPolicy.isRefused("record_audio_file"))
    }

    @Test
    fun `the groups read differently because the operator fix differs`() {
        val confirmation = HeadlessToolApprovalPolicy.refusalDetail("eval_javascript")!!
        val mic = HeadlessToolApprovalPolicy.refusalDetail("record_audio")!!
        val private = HeadlessToolApprovalPolicy.refusalDetail("list_sms_inbox")!!
        assertTrue(confirmation.contains("confirm"))
        assertTrue(mic.contains("consent"))
        assertTrue(private.contains("consent"))
        assertNotEquals(confirmation, mic)
        assertNotEquals(confirmation, private)
        assertNotEquals(mic, private)
    }

    // --------------------------------------------- T-12 private-data group

    @Test
    fun `the private-data group is exactly the reviewed names`() {
        // Pinned so a name cannot be dropped (or quietly added) without this going red. Every
        // entry was reviewed against the 132-name ALWAYS_ASK set on 2026-10-01.
        assertEquals(
            setOf(
                "list_contacts", "search_contacts", "create_contact",
                "list_sms_inbox", "search_sms", "send_sms", "send_sms_intent", "list_call_log",
                "take_photo", "take_screenshot",
                "notification_action_click", "notification_reply", "dismiss_notification",
                "send_email_intent",
                "memory_write",
            ),
            HeadlessToolApprovalPolicy.PRIVATE_DATA_TOOL_NAMES,
        )
    }

    @Test
    fun `every private-data tool is refused`() {
        assertTrue(HeadlessToolApprovalPolicy.PRIVATE_DATA_TOOL_NAMES.isNotEmpty())
        HeadlessToolApprovalPolicy.PRIVATE_DATA_TOOL_NAMES.forEach { name ->
            assertTrue("$name must be refused in a headless run", HeadlessToolApprovalPolicy.isRefused(name))
        }
    }

    @Test
    fun `the private-data group is refused only in a headless run`() {
        HeadlessToolApprovalPolicy.PRIVATE_DATA_TOOL_NAMES.forEach { name ->
            assertNull(
                "$name must still prompt normally in a foreground conversation",
                HeadlessToolApprovalPolicy.refusalEnvelopeFor(name, headless = false),
            )
            assertNotNull(HeadlessToolApprovalPolicy.refusalEnvelopeFor(name, headless = true))
        }
    }

    @Test
    fun `T-06 memory_write is covered`() {
        // T-06s delivery note called this out and handed it to the headless-approval card;
        // T-10s scope did not reach it, so it is pinned here.
        assertTrue(HeadlessToolApprovalPolicy.isRefused("memory_write"))
        assertFalse(
            "memory_write must stay usable in the foreground",
            HeadlessToolApprovalPolicy.isRefused("memory_read"),
        )
    }

    @Test
    fun `get_location is deliberately out of scope`() {
        // Not an oversight: its tool definition carries no `needsApproval`, so it never
        // reaches this policy in any mode. Pinned so a future gating change is noticed.
        assertFalse(HeadlessToolApprovalPolicy.isRefused("get_location"))
    }

    @Test
    fun `a schedule can still do its job`() {
        // The whole point of the narrow scope: these must never be refused, or cron and
        // sub-agent runs lose the ability to work at all.
        listOf(
            "read_file", "list_files", "find_files", "write_text_file", "workspace_shell",
            "web_fetch", "web_extract", "search_web", "download_file",
            "termux_run_command", "ssh_exec", "subagent_dispatch", "schedule_job",
            "workflow_run", "trigger_job_now", "get_job_history", "generate_bug_report",
        ).forEach { name ->
            assertFalse("$name must stay runnable headless", HeadlessToolApprovalPolicy.isRefused(name))
        }
    }
}
