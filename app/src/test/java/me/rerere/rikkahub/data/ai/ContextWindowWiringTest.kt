package me.rerere.rikkahub.data.ai

import me.rerere.ai.provider.Model
import me.rerere.rikkahub.data.datastore.AutoCompactionThresholdMode
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.getEffectiveContextLength
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Wire-level coverage for the window resolution callers actually use
 * ([Settings.getEffectiveContextLength]).
 *
 * The reported bug: DeepSeek publishes no `context_length`, so `Model.contextLength` is null and
 * the reminder transformer's window lookup came back empty — on model ids whose window the static
 * registry has known all along. The reminder then never fired, while automatic compaction (which
 * had a fallback) still did.
 */
class ContextWindowWiringTest {

    @Test
    fun `a provider that publishes no window still resolves through the registry`() {
        val settings = Settings(autoCompactionThresholdMode = AutoCompactionThresholdMode.PERCENT)
        val model = Model(modelId = "deepseek-v4-pro", contextLength = null)

        assertEquals(1_000_000, settings.getEffectiveContextLength(model))
    }

    @Test
    fun `an explicit token ceiling still wins over the registry`() {
        val settings = Settings(
            autoCompactionThresholdMode = AutoCompactionThresholdMode.TOKENS,
            autoCompactionThresholdTokensK = 372,
        )
        val model = Model(modelId = "deepseek-v4-pro", contextLength = null)

        assertEquals(372_000, settings.getEffectiveContextLength(model))
    }

    @Test
    fun `an advertised window is kept as is`() {
        val settings = Settings(autoCompactionThresholdMode = AutoCompactionThresholdMode.PERCENT)
        val model = Model(modelId = "some-gateway-model-x", contextLength = 200_000)

        assertEquals(200_000, settings.getEffectiveContextLength(model))
    }

    /** A window nobody knows stays unknown: percent-based features must switch off, not guess. */
    @Test
    fun `an unknown model without metadata resolves to no window`() {
        val settings = Settings(autoCompactionThresholdMode = AutoCompactionThresholdMode.PERCENT)

        assertNull(settings.getEffectiveContextLength(Model(modelId = "some-gateway-model-x")))
        assertNull(settings.getEffectiveContextLength(null))
    }
}
