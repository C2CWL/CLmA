package me.rerere.rikkahub.data.datastore

import me.rerere.ai.provider.ProviderSetting
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
}
