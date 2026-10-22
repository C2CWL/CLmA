package com.clma.app.data.ai.prompts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompressPromptTest {
    @Test
    fun `legacy default is upgraded to new default`() {
        assertNotEquals(LEGACY_DEFAULT_COMPRESS_PROMPT_V1, DEFAULT_COMPRESS_PROMPT)
        assertEquals(DEFAULT_COMPRESS_PROMPT, upgradeLegacyCompressPrompt(LEGACY_DEFAULT_COMPRESS_PROMPT_V1))
    }

    @Test
    fun `legacy default with CRLF and surrounding whitespace is upgraded`() {
        val crlf = LEGACY_DEFAULT_COMPRESS_PROMPT_V1.replace("\n", "\r\n") + "\r\n"
        assertEquals(DEFAULT_COMPRESS_PROMPT, upgradeLegacyCompressPrompt(crlf))
        assertEquals(DEFAULT_COMPRESS_PROMPT, upgradeLegacyCompressPrompt(LEGACY_DEFAULT_COMPRESS_PROMPT_V1 + "\n"))
        assertEquals(DEFAULT_COMPRESS_PROMPT, upgradeLegacyCompressPrompt("  \n" + LEGACY_DEFAULT_COMPRESS_PROMPT_V1 + "\n\n"))
    }

    @Test
    fun `custom prompt is left unchanged`() {
        val custom = "Summarize briefly in {locale}.\n<conversation>\n{content}\n</conversation>"
        assertEquals(custom, upgradeLegacyCompressPrompt(custom))
        val tweakedLegacy = LEGACY_DEFAULT_COMPRESS_PROMPT_V1.replace("concise summary", "short summary")
        assertEquals(tweakedLegacy, upgradeLegacyCompressPrompt(tweakedLegacy))
        assertEquals(DEFAULT_COMPRESS_PROMPT, upgradeLegacyCompressPrompt(DEFAULT_COMPRESS_PROMPT))
    }

    @Test
    fun `null maps to new default`() {
        assertEquals(DEFAULT_COMPRESS_PROMPT, upgradeLegacyCompressPrompt(null))
    }

    @Test
    fun `new default contains every placeholder and content exactly once`() {
        listOf("{target_tokens}", "{locale}", "{additional_context}", "{content}").forEach { placeholder ->
            assertTrue("missing $placeholder", DEFAULT_COMPRESS_PROMPT.contains(placeholder))
        }
        assertEquals(1, DEFAULT_COMPRESS_PROMPT.split("{content}").size - 1)
    }
}
