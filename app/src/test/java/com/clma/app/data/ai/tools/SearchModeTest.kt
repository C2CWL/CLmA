package com.clma.app.data.ai.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.clma.ai.provider.Model
import com.clma.ai.provider.ProviderSetting
import com.clma.ai.provider.BuiltInTools
import com.clma.app.data.model.Assistant

class SearchModeTest {
    @Test
    fun `model search requires provider support`() {
        val model = Model(tools = setOf(BuiltInTools.Search))
        assertTrue(model.effectiveBuiltInSearch(ProviderSetting.Google()))
        assertFalse(model.effectiveBuiltInSearch(ProviderSetting.OpenAI(useResponseApi = false)))
        assertFalse(model.effectiveBuiltInSearch(null))
    }

    @Test
    fun `unsupported residual search does not suppress external search`() {
        val assistant = Assistant(enableWebSearch = true)
        val model = Model(tools = setOf(BuiltInTools.Search))
        assertTrue(shouldUseExternalWebSearch(assistant, model, ProviderSetting.OpenAI(useResponseApi = false)))
        assertFalse(shouldUseExternalWebSearch(assistant, model, ProviderSetting.Google()))
    }
}
