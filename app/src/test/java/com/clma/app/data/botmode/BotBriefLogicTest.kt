package com.clma.app.data.botmode

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class BotBriefLogicTest {
    @Test
    fun `late delivery uses the latest due local date`() {
        val zone = ZoneId.of("Asia/Shanghai")
        val time = LocalTime.of(8, 30)
        val before = botBriefWindow(Instant.parse("2026-11-28T00:29:59Z"), time, zone)
        val due = botBriefWindow(Instant.parse("2026-11-28T00:30:00Z"), time, zone)
        assertEquals(LocalDate.parse("2026-11-27"), before.day)
        assertEquals(LocalDate.parse("2026-11-28"), due.day)
        assertEquals(before.due, due.since)
    }

    @Test
    fun `spring daylight saving moves the slot forward across the gap`() {
        val window = botBriefWindow(
            Instant.parse("2026-03-08T07:30:00Z"), LocalTime.of(2, 30),
            ZoneId.of("America/New_York"),
        )
        assertEquals(Instant.parse("2026-03-08T07:30:00Z"), window.due)
        assertEquals(Instant.parse("2026-03-07T07:30:00Z"), window.since)
    }

    @Test
    fun `autumn overlap keeps only the first occurrence of the local slot`() {
        val window = botBriefWindow(
            Instant.parse("2026-11-01T06:30:00Z"), LocalTime.of(1, 30),
            ZoneId.of("America/New_York"),
        )
        assertEquals(Instant.parse("2026-11-01T05:30:00Z"), window.due)
    }

    @Test
    fun `brief ids are stable per day and channel ids are scoped to a member`() {
        val day = LocalDate.parse("2026-11-28")
        val brief = dailyBriefTopicId(day)
        val channel = Uuid.random()
        val bot = Uuid.random()
        val topic = channelConversationId(channel, bot)
        assertEquals(brief, dailyBriefTopicId(day))
        assertNotEquals(brief, dailyBriefTopicId(day.plusDays(1)))
        assertTrue(isDailyBriefTopic(brief))
        assertFalse(isDailyBriefTopic(topic))
        assertEquals(topic, channelConversationId(channel, bot))
        assertNotEquals(topic, channelConversationId(channel, Uuid.random()))
        assertNotEquals(topic, channelConversationId(Uuid.random(), bot))
    }

    @Test
    fun `progress requires a declared update and brief excerpts escape markdown`() {
        assertNull(goalProgress("I drafted a reply."))
        assertNull(goalProgress("Goal progress:   "))
        assertEquals("Drafted a reply.", goalProgress("Done.\nGoal progress: Drafted a reply."))
        assertEquals("\\!\\[image\\](https://host.test/p)", briefExcerpt("![image](https://host.test/p)"))
        assertEquals("one two…", briefExcerpt("one\n  two three", 8))
    }
}
