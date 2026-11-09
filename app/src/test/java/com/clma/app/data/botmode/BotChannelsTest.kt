package com.clma.app.data.botmode

import com.clma.app.data.datastore.BotChannel
import com.clma.app.data.datastore.DisplaySetting
import com.clma.app.data.model.Assistant
import com.clma.app.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class BotChannelsTest {
    @Test
    fun `channels round-trip on display setting`() {
        val id = Uuid.parse("11111111-1111-1111-1111-111111111111")
        val channel = BotChannel(id = id, name = "群", description = "@甲 你好", memberAssistantIds = listOf(id))
        val ds = DisplaySetting(botChannels = listOf(channel))
        val decoded = JsonInstant.decodeFromString<DisplaySetting>(JsonInstant.encodeToString(ds))
        assertEquals(1, decoded.botChannels.size)
        assertEquals("群", decoded.botChannels.single().name)
    }

    @Test
    fun `upsert and remove channel`() {
        val a = BotChannel(name = "A")
        val b = BotChannel(name = "B")
        var ds = DisplaySetting().withBotChannelUpserted(a).withBotChannelUpserted(b)
        assertEquals(2, ds.botChannels.size)
        ds = ds.withBotChannelRemoved(a.id)
        assertEquals(listOf(b.id), ds.botChannels.map { it.id })
    }

    @Test
    fun `parse mentions by assistant name`() {
        val a = Assistant(id = Uuid.parse("11111111-1111-1111-1111-111111111111"), name = "研究员")
        val b = Assistant(id = Uuid.parse("22222222-2222-2222-2222-222222222222"), name = "写手")
        val ids = parseBotMentions("请 @研究员 先查，再交给 @写手", listOf(a, b))
        assertEquals(listOf(a.id, b.id), ids)
        assertTrue(parseBotMentions("没有点名", listOf(a, b)).isEmpty())
    }
}
