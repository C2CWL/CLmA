package me.rerere.rikkahub.data.ai.model

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * P2-37 — the pure half of the `model_*` tools: selector resolution (provider and model, by
 * UUID or by name/id) plus the type / provider-kind wire conversions.
 *
 * The four tool bodies themselves are not reachable from a JVM test — they take a concrete
 * [me.rerere.rikkahub.data.datastore.SettingsStore] and a Room-backed
 * [me.rerere.rikkahub.data.agentrun.AgentRunRepository] — so what is pinned here is the
 * resolution logic the bodies delegate to, which is where a wrong match would bite. CI covers
 * the wiring.
 */
class ModelControlToolsTest {

    private val chat = Model(modelId = "deepseek-chat", displayName = "DeepSeek Chat")
    private val embed = Model(modelId = "bge-m3", displayName = "BGE M3", type = ModelType.EMBEDDING)
    private val openai = ProviderSetting.OpenAI(name = "OpenAI", models = listOf(chat, embed))
    private val claude = ProviderSetting.Claude(name = "Claude", models = emptyList())
    private val providers = listOf(openai, claude)

    // ------------------------------------------------------------ provider resolution

    @Test
    fun `provider resolves by exact name`() {
        assertSame(openai, findProviderForControl(providers, "OpenAI"))
    }

    @Test
    fun `provider name match ignores case and surrounding whitespace`() {
        assertSame(openai, findProviderForControl(providers, "  openai "))
    }

    @Test
    fun `provider resolves by uuid`() {
        assertSame(claude, findProviderForControl(providers, claude.id.toString()))
    }

    @Test
    fun `unknown or blank provider selector resolves to null`() {
        assertNull(findProviderForControl(providers, "Nope"))
        assertNull(findProviderForControl(providers, ""))
        assertNull(findProviderForControl(providers, "   "))
    }

    // --------------------------------------------------------------- model resolution

    @Test
    fun `model resolves by uuid`() {
        assertSame(embed, findModelForControl(openai, embed.id.toString()))
    }

    @Test
    fun `model resolves by model_id ignoring case`() {
        assertSame(chat, findModelForControl(openai, "DeepSeek-Chat"))
    }

    @Test
    fun `unknown or blank model selector resolves to null`() {
        assertNull(findModelForControl(openai, "gpt-4o"))
        assertNull(findModelForControl(openai, ""))
    }

    @Test
    fun `a model is only found inside its own provider`() {
        assertNull(findModelForControl(claude, "deepseek-chat"))
    }

    // ----------------------------------------------------------------------- type wire

    @Test
    fun `parseModelType accepts the wire names and both embedding spellings`() {
        assertEquals(ModelType.CHAT, parseModelType("chat"))
        assertEquals(ModelType.IMAGE, parseModelType(" IMAGE "))
        assertEquals(ModelType.VIDEO, parseModelType("video"))
        assertEquals(ModelType.EMBEDDING, parseModelType("embedding"))
        assertEquals(ModelType.EMBEDDING, parseModelType("embed"))
    }

    @Test
    fun `parseModelType rejects an unknown token`() {
        assertNull(parseModelType("audio"))
        assertNull(parseModelType(""))
    }

    @Test
    fun `modelTypeWire round-trips through parseModelType`() {
        ModelType.entries.forEach { type ->
            assertEquals(type, parseModelType(modelTypeWire(type)))
        }
    }

    // ------------------------------------------------------------------ provider kinds

    @Test
    fun `providerKind names every provider subclass`() {
        assertEquals("openai", providerKind(ProviderSetting.OpenAI()))
        assertEquals("google", providerKind(ProviderSetting.Google()))
        assertEquals("claude", providerKind(ProviderSetting.Claude()))
        assertEquals("aicore", providerKind(ProviderSetting.AICore()))
        assertEquals("local_litert", providerKind(ProviderSetting.LiteRtLocal()))
        assertEquals("local_llamacpp", providerKind(ProviderSetting.LlamaCppLocal()))
        assertEquals("codex", providerKind(ProviderSetting.Codex()))
        assertEquals("grok", providerKind(ProviderSetting.Grok()))
        assertEquals("gemini_oauth", providerKind(ProviderSetting.GeminiOAuth()))
    }
}
