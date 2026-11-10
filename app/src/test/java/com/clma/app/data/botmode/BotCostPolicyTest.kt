package com.clma.app.data.botmode

import com.clma.ai.provider.ProviderSetting
import com.clma.app.data.datastore.Settings
import com.clma.app.data.model.Assistant
import com.clma.app.data.model.AssistantLibrary
import com.clma.app.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class BotCostPolicyTest {
    private val classic = Assistant(name = "Classic")
    private val bot = Assistant(name = "Bot", library = AssistantLibrary.BOT)
    private val botOff = bot.copy(botCostSaving = false)
    private val conversationId = Uuid.parse("44444444-4444-4444-4444-444444444444")

    @Test
    fun `only bots without an explicit off are active`() {
        assertFalse(BotCostPolicy.isActive(null))
        assertFalse(BotCostPolicy.isActive(classic))
        // A classic assistant cannot opt in through the bot switch.
        assertFalse(BotCostPolicy.isActive(classic.copy(botCostSaving = true)))
        assertTrue(BotCostPolicy.isActive(bot))
        assertTrue(BotCostPolicy.isActive(bot.copy(botCostSaving = true)))
        assertFalse(BotCostPolicy.isActive(botOff))
    }

    @Test
    fun `classic settings are returned untouched`() {
        val settings = Settings(enableAutoCompaction = false, enablePrecisionCompaction = false)
        assertSame(settings, BotCostPolicy.effectiveSettings(settings, classic))
        assertSame(settings, BotCostPolicy.effectiveSettings(settings, botOff))
        assertSame(settings, BotCostPolicy.effectiveSettings(settings, null))
    }

    @Test
    fun `a bot gets automatic and precision compaction and keeps every other setting`() {
        val settings = Settings(
            enableAutoCompaction = false,
            enablePrecisionCompaction = false,
            autoCompactionThresholdPercent = 70,
            autoCompactionKeepRecentToolCalls = 3,
        )
        val effective = BotCostPolicy.effectiveSettings(settings, bot)
        assertTrue(effective.enableAutoCompaction)
        assertTrue(effective.enablePrecisionCompaction)
        assertEquals(settings.copy(enableAutoCompaction = true, enablePrecisionCompaction = true), effective)
        // Idempotent: applying it again changes nothing.
        assertSame(effective, BotCostPolicy.effectiveSettings(effective, bot))
    }

    @Test
    fun `the default tool budget fills only an unset bot budget`() {
        assertSame(classic, BotCostPolicy.effectiveAssistant(classic))
        assertSame(botOff, BotCostPolicy.effectiveAssistant(botOff))
        assertEquals(
            BotCostPolicy.DEFAULT_TOOL_RESULT_MAX_TOKENS,
            BotCostPolicy.effectiveAssistant(bot).toolResultMaxTokens,
        )
        val own = bot.copy(toolResultMaxTokens = 500)
        assertSame(own, BotCostPolicy.effectiveAssistant(own))
    }

    @Test
    fun `batch one is the classic sliding window`() {
        for (total in 0..12) {
            assertEquals(maxOf(0, total - 2), BotCostPolicy.batchedAgedCount(total, keep = 2, batch = 1))
        }
    }

    @Test
    fun `batched ageing only moves every batch items`() {
        val counts = (0..12).map { BotCostPolicy.batchedAgedCount(it, keep = 2, batch = 4) }
        assertEquals(listOf(0, 0, 0, 0, 0, 0, 4, 4, 4, 4, 8, 8, 8), counts)
        // Never ages one of the newest `keep`, never keeps more than keep + batch - 1.
        (0..40).forEach { total ->
            val aged = BotCostPolicy.batchedAgedCount(total, keep = 2, batch = 4)
            assertTrue(total - aged >= minOf(total, 2))
            assertTrue(total - aged <= 2 + 3)
        }
    }

    @Test
    fun `prompt cache key only for an active bot on the official OpenAI host`() {
        val openAi = ProviderSetting.OpenAI()
        assertEquals("clma-bot:$conversationId", BotCostPolicy.promptCacheKey(bot, openAi, conversationId))
        // Stable for the conversation.
        assertEquals(
            BotCostPolicy.promptCacheKey(bot, openAi, conversationId),
            BotCostPolicy.promptCacheKey(bot, openAi, conversationId),
        )
        assertNull(BotCostPolicy.promptCacheKey(classic, openAi, conversationId))
        assertNull(BotCostPolicy.promptCacheKey(botOff, openAi, conversationId))
        assertNull(BotCostPolicy.promptCacheKey(bot, openAi, null))
        assertNull(BotCostPolicy.promptCacheKey(bot, openAi.copy(promptCaching = false), conversationId))
        // OpenAI-compatible gateways never receive the OpenAI-only field.
        assertNull(BotCostPolicy.promptCacheKey(bot, openAi.copy(baseUrl = "https://openrouter.ai/api/v1"), conversationId))
        assertNull(BotCostPolicy.promptCacheKey(bot, openAi.copy(baseUrl = "https://api.deepseek.com/v1"), conversationId))
        assertNull(BotCostPolicy.promptCacheKey(bot, openAi.copy(baseUrl = "not a url"), conversationId))
        assertNull(BotCostPolicy.promptCacheKey(bot, ProviderSetting.Claude(), conversationId))
    }

    @Test
    fun `idle compaction arms only for a normal persisted bot turn with auto compaction`() {
        assertTrue(BotCostPolicy.shouldArmIdleCompaction(bot, true, regeneratingRange = false, temporaryConversation = false))
        assertFalse(BotCostPolicy.shouldArmIdleCompaction(classic, true, regeneratingRange = false, temporaryConversation = false))
        assertFalse(BotCostPolicy.shouldArmIdleCompaction(botOff, true, regeneratingRange = false, temporaryConversation = false))
        assertFalse(BotCostPolicy.shouldArmIdleCompaction(bot, false, regeneratingRange = false, temporaryConversation = false))
        assertFalse(BotCostPolicy.shouldArmIdleCompaction(bot, true, regeneratingRange = true, temporaryConversation = false))
        assertFalse(BotCostPolicy.shouldArmIdleCompaction(bot, true, regeneratingRange = false, temporaryConversation = true))
    }

    @Test
    fun `idle compaction is due only at or above the automatic trigger`() {
        assertFalse(BotCostPolicy.idleCompactionDue(79_999, 80_000))
        assertTrue(BotCostPolicy.idleCompactionDue(80_000, 80_000))
        assertTrue(BotCostPolicy.idleCompactionDue(120_000, 80_000))
        assertFalse(BotCostPolicy.idleCompactionDue(120_000, null))
        assertFalse(BotCostPolicy.idleCompactionDue(120_000, 0))
    }

    @Test
    fun `the switch is never encoded by default and decodes from old data as null`() {
        val json = JsonInstant.encodeToString(Assistant.serializer(), bot)
        assertFalse(json.contains("botCostSaving"))
        val decoded = JsonInstant.decodeFromString(Assistant.serializer(), json)
        assertNull(decoded.botCostSaving)
        val off = JsonInstant.encodeToString(Assistant.serializer(), botOff)
        assertTrue(off.contains("\"botCostSaving\":false"))
        assertEquals(false, JsonInstant.decodeFromString(Assistant.serializer(), off).botCostSaving)
    }
}
