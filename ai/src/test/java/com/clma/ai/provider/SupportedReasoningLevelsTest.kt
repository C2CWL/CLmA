package com.clma.ai.provider

import com.clma.ai.core.ReasoningLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportedReasoningLevelsTest {

    @Test
    fun nullProvider_returnsAllLevels() {
        assertEquals(ReasoningLevel.entries.toList(), supportedReasoningLevels(null))
    }

    @Test
    fun autoAlwaysPresent() {
        val providers = listOf(
            ProviderSetting.Codex(),
            ProviderSetting.Grok(),
            ProviderSetting.Claude(),
            ProviderSetting.Google(),
            ProviderSetting.OpenAI(),
            ProviderSetting.OpenAI(baseUrl = "https://api.deepseek.com/v1"),
            ProviderSetting.OpenAI(baseUrl = "https://api.moonshot.cn/v1"),
        )
        providers.forEach { provider ->
            assertTrue(
                "AUTO missing for ${provider::class.simpleName}",
                ReasoningLevel.AUTO in supportedReasoningLevels(provider),
            )
        }
    }

    @Test
    fun codex_dropsMaxBecauseItCollapsesToXhigh() {
        val levels = supportedReasoningLevels(ProviderSetting.Codex())
        assertTrue(ReasoningLevel.OFF in levels)
        assertTrue(ReasoningLevel.XHIGH in levels)
        assertFalse(ReasoningLevel.MAX in levels)
        assertEquals(
            listOf(
                ReasoningLevel.OFF,
                ReasoningLevel.AUTO,
                ReasoningLevel.LOW,
                ReasoningLevel.MEDIUM,
                ReasoningLevel.HIGH,
                ReasoningLevel.XHIGH,
            ),
            levels,
        )
    }

    @Test
    fun grok_noOff_andCollapsesXhighMaxToHigh() {
        val levels = supportedReasoningLevels(ProviderSetting.Grok())
        assertFalse(ReasoningLevel.OFF in levels)
        assertFalse(ReasoningLevel.XHIGH in levels)
        assertFalse(ReasoningLevel.MAX in levels)
        assertEquals(
            listOf(
                ReasoningLevel.AUTO,
                ReasoningLevel.LOW,
                ReasoningLevel.MEDIUM,
                ReasoningLevel.HIGH,
            ),
            levels,
        )
    }

    @Test
    fun claude_keepsAllDistinctEfforts() {
        assertEquals(
            ReasoningLevel.entries.toList(),
            supportedReasoningLevels(ProviderSetting.Claude()),
        )
    }

    @Test
    fun googleGemini3_collapsesAboveHigh_andKeepsOff() {
        val model = Model(modelId = "gemini-3-pro-preview")
        val levels = supportedReasoningLevels(ProviderSetting.Google(), model)
        assertEquals(
            listOf(
                ReasoningLevel.OFF,
                ReasoningLevel.AUTO,
                ReasoningLevel.LOW,
                ReasoningLevel.MEDIUM,
                ReasoningLevel.HIGH,
            ),
            levels,
        )
    }

    @Test
    fun googleGemini25Pro_cannotDisable_keepsBudgets() {
        val model = Model(modelId = "gemini-2.5-pro")
        val levels = supportedReasoningLevels(ProviderSetting.Google(), model)
        assertFalse(ReasoningLevel.OFF in levels)
        assertTrue(ReasoningLevel.MAX in levels)
        assertTrue(ReasoningLevel.AUTO in levels)
    }

    @Test
    fun geminiOauth_matchesGoogleMapping() {
        val model = Model(modelId = "gemini-3-flash-preview")
        assertEquals(
            supportedReasoningLevels(ProviderSetting.Google(), model),
            supportedReasoningLevels(ProviderSetting.GeminiOAuth(), model),
        )
    }

    @Test
    fun openAiResponseApi_keepsAll() {
        val provider = ProviderSetting.OpenAI(useResponseApi = true)
        assertEquals(ReasoningLevel.entries.toList(), supportedReasoningLevels(provider))
    }

    @Test
    fun moonshot_booleanThinking_onlyOffAndAuto() {
        val provider = ProviderSetting.OpenAI(baseUrl = "https://api.moonshot.cn/v1")
        assertEquals(
            listOf(ReasoningLevel.OFF, ReasoningLevel.AUTO),
            supportedReasoningLevels(provider),
        )
    }

    @Test
    fun deepseek_collapsesHighIntoMedium() {
        val provider = ProviderSetting.OpenAI(baseUrl = "https://api.deepseek.com")
        val levels = supportedReasoningLevels(provider)
        assertTrue(ReasoningLevel.MEDIUM in levels)
        assertFalse(ReasoningLevel.HIGH in levels)
        assertTrue(ReasoningLevel.MAX in levels)
    }

    @Test
    fun nvidiaDeepseekV4_collapsesMidToLow_andMaxToXhigh() {
        val provider = ProviderSetting.OpenAI(baseUrl = "https://integrate.api.nvidia.com/v1")
        val levels = supportedReasoningLevels(provider, Model(modelId = "deepseek-v4-pro"))
        assertEquals(
            listOf(
                ReasoningLevel.OFF,
                ReasoningLevel.AUTO,
                ReasoningLevel.LOW,
                ReasoningLevel.XHIGH,
            ),
            levels,
        )
    }

    @Test
    fun officialOpenAiCompletions_cannotDisable() {
        val provider = ProviderSetting.OpenAI(baseUrl = "https://api.openai.com/v1")
        val levels = supportedReasoningLevels(provider)
        assertFalse(ReasoningLevel.OFF in levels)
        assertTrue(ReasoningLevel.LOW in levels)
        assertTrue(ReasoningLevel.AUTO in levels)
    }

    @Test
    fun nearest_returnsCurrentWhenSupported() {
        val supported = listOf(ReasoningLevel.AUTO, ReasoningLevel.LOW, ReasoningLevel.HIGH)
        assertEquals(
            ReasoningLevel.LOW,
            nearestSupportedReasoningLevel(ReasoningLevel.LOW, supported),
        )
    }

    @Test
    fun nearest_snapsToClosestOrdinal() {
        val supported = listOf(ReasoningLevel.AUTO, ReasoningLevel.LOW, ReasoningLevel.HIGH)
        // MEDIUM (ordinal between LOW and HIGH) → closer to LOW? 
        // OFF=0 AUTO=1 LOW=2 MEDIUM=3 HIGH=4 → MEDIUM closer to LOW (diff1) and HIGH (diff1) — minBy stable picks first
        assertEquals(
            ReasoningLevel.LOW,
            nearestSupportedReasoningLevel(ReasoningLevel.MEDIUM, supported),
        )
        assertEquals(
            ReasoningLevel.HIGH,
            nearestSupportedReasoningLevel(ReasoningLevel.MAX, supported),
        )
        assertEquals(
            ReasoningLevel.AUTO,
            nearestSupportedReasoningLevel(ReasoningLevel.OFF, supported),
        )
    }
}
