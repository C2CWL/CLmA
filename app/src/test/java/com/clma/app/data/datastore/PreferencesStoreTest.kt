package com.clma.app.data.datastore

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import com.clma.ai.core.ReasoningLevel
import com.clma.app.data.model.Assistant
import com.clma.app.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreferencesStoreTest {
    @Test
    fun `stale settings snapshot does not replace a newer write`() {
        val current = Settings(settingsRevision = 4, autoCompactionThresholdPercent = 70)
        val stale = Settings(settingsRevision = 3, autoCompactionThresholdPercent = 80)
        val sameGeneration = current.copy(launchCount = current.launchCount + 1)
        val newer = current.copy(settingsRevision = 5, autoCompactionThresholdPercent = 60)

        assertEquals(false, shouldApplySettingsSnapshot(current, stale))
        assertEquals(true, shouldApplySettingsSnapshot(current, sameGeneration))
        assertEquals(true, shouldApplySettingsSnapshot(current, newer))
        assertEquals(true, shouldApplySettingsSnapshot(Settings.dummy(), stale))
    }

    @Test
    fun `compression target defaults to one percent of the active context`() {
        val settings = Settings()

        assertEquals(3_720, settings.getContextCompactionTargetTokens(372_000))
    }

    @Test
    fun `compression target uses a safe fallback without model metadata`() {
        val settings = Settings(contextCompactionTargetTokensK = null)

        assertEquals(4_000, settings.getContextCompactionTargetTokens(null))
        assertEquals(4_000, settings.getContextCompactionTargetTokens(0))
    }

    @Test
    fun `compression target respects lower and upper bounds`() {
        val settings = Settings()

        // 100k context * 1% = 1,000, clamped to min 2,000
        assertEquals(2_000, settings.getContextCompactionTargetTokens(100_000))
        // 1,000k context * 1% = 10,000, clamped to max 8,000
        assertEquals(8_000, settings.getContextCompactionTargetTokens(1_000_000))
    }

    @Test
    fun `token threshold supplies context metadata for the one percent default`() {
        val settings = Settings(
            contextCompactionTargetTokensK = null,
            autoCompactionThresholdMode = AutoCompactionThresholdMode.TOKENS,
            autoCompactionThresholdTokensK = 372,
        )

        assertEquals(372_000, settings.getEffectiveContextLength(null))
        assertEquals(3_720, settings.getContextCompactionTargetTokens(
            settings.getEffectiveContextLength(null),
        ))
    }

    @Test
    fun `explicit compression target overrides the fixed default`() {
        val settings = Settings(contextCompactionTargetTokensK = 30)

        assertEquals(30_000, settings.getContextCompactionTargetTokens(372_000))
    }

    /**
     * Upstream RikkaHub added `MAX` (@SerialName "max") to `ReasoningLevel` on 2026-08-14. An
     * assistant JSON exported by upstream (or by this app after the level was added) carries
     * that literal string, so it MUST decode back to [ReasoningLevel.MAX] instead of failing
     * with an unknown-enum-constant error.
     */
    @Test
    fun `assistant decodes a reasoningLevel of max, as exported by upstream RikkaHub`() {
        val fullJson = JsonInstant.encodeToString(Assistant())
        val withMaxReasoning = JsonObject(
            JsonInstant.parseToJsonElement(fullJson).jsonObject.toMutableMap().apply {
                put("reasoningLevel", JsonPrimitive("max"))
            }
        )

        val decoded = JsonInstant.decodeFromString<Assistant>(withMaxReasoning.toString())

        assertEquals(ReasoningLevel.MAX, decoded.reasoningLevel)
    }
}
