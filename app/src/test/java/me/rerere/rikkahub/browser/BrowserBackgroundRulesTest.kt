package me.rerere.rikkahub.browser

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserBackgroundRulesTest {

    @Test fun `shizuku ready always picks virtual display`() {
        assertEquals(BackgroundHostKind.VIRTUAL_DISPLAY, decideBackgroundHost(true, true))
        assertEquals(BackgroundHostKind.VIRTUAL_DISPLAY, decideBackgroundHost(true, false))
    }

    @Test fun `no shizuku with conversation falls back to headless`() {
        assertEquals(BackgroundHostKind.HEADLESS, decideBackgroundHost(false, true))
    }

    @Test fun `no shizuku and no conversation is unavailable`() {
        assertNull(decideBackgroundHost(false, false))
    }

    @Test fun `shell quote escapes embedded single quotes`() {
        assertEquals("''", shellQuoteSingle(""))
        assertEquals("'hello'", shellQuoteSingle("hello"))
        // The POSIX idiom: close quote, escaped quote, reopen quote.
        assertEquals("'it'\\''s'", shellQuoteSingle("it's"))
        assertEquals("'a'\\''b'\\''c'", shellQuoteSingle("a'b'c"))
    }

    @Test fun `launch command includes display and extras`() {
        val cmd = buildBrowserLaunchCommand("excp.rikkahub.debug", 7, "https://x.com/q?a=1", "conv-1")
        assertTrue(cmd.startsWith("am start -n excp.rikkahub.debug/me.rerere.rikkahub.browser.BrowserActivity "))
        assertTrue(cmd.contains(" --display 7 "))
        assertTrue(cmd.contains(" -f 268435456"))
        assertTrue(cmd.contains("--es me.rerere.rikkahub.browser.EXTRA_INITIAL_URL 'https://x.com/q?a=1'"))
        assertTrue(cmd.contains("--es me.rerere.rikkahub.browser.EXTRA_CONVERSATION_ID 'conv-1'"))
    }

    @Test fun `launch command omits blank extras`() {
        val cmd = buildBrowserLaunchCommand("com.moxw.agent", 3, null, "  ")
        assertFalse(cmd.contains("EXTRA_INITIAL_URL"))
        assertFalse(cmd.contains("EXTRA_CONVERSATION_ID"))
        assertTrue(cmd.contains(" --display 3"))
    }

    @Test fun `launch command shell-quotes urls with single quotes`() {
        val cmd = buildBrowserLaunchCommand("excp.rikkahub.debug", 1, "https://x.com/it's", null)
        assertTrue(cmd.contains(" 'https://x.com/it'\\''s'"))
    }

    @Test fun `am success requires exit code zero and no error`() {
        assertTrue(amCommandSucceeded(buildJsonObject { put("exit_code", 0); put("stdout", "Starting: Intent") }))
    }

    @Test fun `am failure on non-zero exit`() {
        assertFalse(amCommandSucceeded(buildJsonObject { put("exit_code", 1); put("stderr", "Error: bad") }))
    }

    @Test fun `am failure on error envelope`() {
        assertFalse(amCommandSucceeded(buildJsonObject { put("error", "shizuku_bind_failed"); put("exit_code", 0) }))
    }

    @Test fun `am failure when exit code missing`() {
        assertFalse(amCommandSucceeded(buildJsonObject { put("stdout", "...") }))
    }

    @Test fun `background unavailable envelope carries recovery`() {
        val env = backgroundUnavailableEnvelope()
        assertEquals("background_unavailable", env["error"]!!.toString().trim('"'))
        assertTrue(env["recovery"]!!.toString().contains("Shizuku"))
    }
}
