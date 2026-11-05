package com.clma.app.data.datastore

import com.clma.ai.provider.Model
import com.clma.ai.provider.ProviderSetting
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportsSpeedTierTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun `Codex provider always supports speed tiers`() {
        val model = Model(modelId = "gpt-5.1-codex", displayName = "Codex")
        val providers = listOf(ProviderSetting.Codex(models = listOf(model)))
        assertTrue(model.supportsSpeedTier(providers))
    }

    @Test
    fun `non-Codex defaults to false`() {
        val model = Model(modelId = "relay-model", displayName = "Relay")
        val providers = listOf(ProviderSetting.OpenAI(models = listOf(model)))
        assertFalse(model.supportsSpeedTier(providers))
    }

    @Test
    fun `non-Codex with supportsSpeedTiers flag is true`() {
        val model = Model(
            modelId = "relay-model",
            displayName = "Relay",
            supportsSpeedTiers = true,
        )
        val providers = listOf(ProviderSetting.OpenAI(models = listOf(model)))
        assertTrue(model.supportsSpeedTier(providers))
    }

    @Test
    fun `flag false is omitted from encoded JSON`() {
        val encoded = json.encodeToString(Model.serializer(), Model(modelId = "m"))
        assertFalse(encoded.contains("supportsSpeedTiers"))
    }

    @Test
    fun `flag true round-trips`() {
        val model = Model(modelId = "m", supportsSpeedTiers = true)
        val encoded = json.encodeToString(Model.serializer(), model)
        assertTrue(encoded.contains("\"supportsSpeedTiers\":true"))
        val decoded = json.decodeFromString(Model.serializer(), encoded)
        assertTrue(decoded.supportsSpeedTiers)
    }

    @Test
    fun `legacy JSON without the field deserializes as false`() {
        val decoded = json.decodeFromString(
            Model.serializer(),
            """{"modelId":"m","displayName":"M"}""",
        )
        assertEquals(false, decoded.supportsSpeedTiers)
    }
}
