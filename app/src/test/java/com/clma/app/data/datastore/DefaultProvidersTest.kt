package com.clma.app.data.datastore

import com.clma.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultProvidersTest {
    @Test
    fun `default providers should include vercel ai gateway with expected balance config`() {
        val vercelProviders = DEFAULT_PROVIDERS
            .filterIsInstance<ProviderSetting.OpenAI>()
            .filter { it.name == "Vercel AI Gateway" }

        assertEquals(1, vercelProviders.size)

        val provider = vercelProviders.single()
        assertEquals("https://ai-gateway.vercel.sh/v1", provider.baseUrl)
        assertFalse(provider.enabled)
        assertTrue(provider.builtIn)
        assertTrue(provider.balanceOption.enabled)
        assertEquals("/credits", provider.balanceOption.apiPath)
        assertEquals("balance", provider.balanceOption.resultPath)
    }

    @Test
    fun `every default provider is assigned an explicit category`() {
        // Guard for the grouped provider page: a built-in that nobody mapped would silently
        // fall into the "其他" bucket, which is reserved for user-added providers.
        val uncategorized = DEFAULT_PROVIDERS.filter { it.category() == ProviderCategory.OTHER }
        assertTrue(
            "uncategorized built-ins: ${uncategorized.map { it.name }}",
            uncategorized.isEmpty(),
        )
    }
    @Test
    fun `restore local provider removes tombstone and does not duplicate existing provider`() {
        val id = com.clma.ai.provider.LLAMACPP_PROVIDER_ID
        val missing = Settings(providers = emptyList(), deletedBuiltInProviderIds = setOf(id))
        val restored = missing.restoreLocalLlamaProvider()
        assertEquals(1, restored.providers.count { it.id == id })
        assertFalse(id in restored.deletedBuiltInProviderIds)
        assertFalse(restored.providers.single().enabled)
        assertEquals(restored, restored.restoreLocalLlamaProvider())
    }

    @Test
    fun `restore local provider preserves configured models and enabled state`() {
        val configured = ProviderSetting.LlamaCppLocal(enabled = true, name = "My GGUF")
        val original = Settings(providers = listOf(configured))
        assertEquals(original, original.restoreLocalLlamaProvider())
    }
}
