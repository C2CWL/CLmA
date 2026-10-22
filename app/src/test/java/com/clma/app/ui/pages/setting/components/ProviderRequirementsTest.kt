package com.clma.app.ui.pages.setting.components

import com.clma.ai.provider.ProviderSetting
import com.clma.app.ui.components.ui.TagType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderRequirementsTest {
    @Test
    fun `llama cpp provider reports an on-device CPU-only requirement`() {
        val requirements = ProviderRequirement.from(ProviderSetting.LlamaCppLocal())

        assertEquals(1, requirements.size)
        assertEquals(TagType.INFO, requirements.single().severity)
    }

    @Test
    fun `openai provider has no special requirements`() {
        assertTrue(ProviderRequirement.from(ProviderSetting.OpenAI()).isEmpty())
    }
}
