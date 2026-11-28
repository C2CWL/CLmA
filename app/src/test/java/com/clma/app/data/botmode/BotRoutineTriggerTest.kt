package com.clma.app.data.botmode

import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BotRoutineTriggerTest {
    private val owner = Uuid.parse("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
    private val form = BotRoutineForm(name = "Inbox", prompt = "Summarize this event")

    @Test
    fun `notification requires exact package and event kind`() {
        val trigger = BotRoutineTrigger(BotRoutineEventKind.EMAIL, "com.example.mail")
        assertTrue(trigger.validFor(owner.toString()))
        assertTrue(trigger.matches(BotRoutineEventKind.EMAIL, "com.example.mail"))
        assertFalse(trigger.matches(BotRoutineEventKind.NOTIFICATION, "com.example.mail"))
        assertFalse(trigger.matches(BotRoutineEventKind.EMAIL, "com.example.mail.other"))
        assertFalse(trigger.copy(source = "").validFor(owner.toString()))
    }

    @Test
    fun `completion requires a different bot id`() {
        val trigger = BotRoutineTrigger(BotRoutineEventKind.BOT_FINISHED, owner.toString())
        assertFalse(trigger.validFor(owner.toString()))
        assertFalse(trigger.copy(source = "another bot").validFor(owner.toString()))
        assertTrue(trigger.copy(source = Uuid.random().toString()).validFor(owner.toString()))
    }

    @Test
    fun `event form round trips and clears timed scheduling`() {
        val scheduled = form.toJob(owner, null, 0L).copy(nextRunAtMs = 42L)
        val configured = form.copy(trigger = BotRoutineTrigger(BotRoutineEventKind.WEBHOOK))
        val event = configured.toJob(owner, scheduled, 100L)
        val trigger = BotRoutineTrigger.from(event)
        assertNotNull(trigger)
        assertEquals("event", event.scheduleType)
        assertEquals(scheduled.id, event.id)
        assertNull(event.nextRunAtMs)
        assertEquals(trigger, botRoutineFormFrom(event).trigger)
        assertEquals(BotRoutineSchedule.Custom(""), parseBotRoutineSchedule(event))
        val edited = BotRoutineTrigger.from(configured.copy(prompt = "Changed").toJob(owner, event, 101L))
        assertEquals(trigger?.webhookSecret, edited?.webhookSecret)
        assertNotEquals(trigger?.revision, edited?.revision)
        assertNull(BotRoutineTrigger.from(event.copy(cronExpression = "invalid")))
        assertNull(BotRoutineTrigger.from(event.copy(mode = "direct")))
        assertNull(BotRoutineTrigger.from(form.toJob(owner, event, 102L)))
    }

    @Test
    fun `converting a paused one time job does not enable the event`() {
        val once = form.copy(kind = BotRoutineKind.ONCE)
        val paused = once.toJob(owner, null, 0L).copy(enabled = false, lastRunAtMs = 1L)
        val changed = once.copy(trigger = BotRoutineTrigger(BotRoutineEventKind.SMS))
            .toJob(owner, paused, 2L)
        assertFalse(changed.enabled)
    }

    @Test
    fun `switching event source rejects the old configuration revision`() {
        val notification = form.copy(trigger = BotRoutineTrigger(BotRoutineEventKind.NOTIFICATION, "com.example.old"))
        val original = notification.toJob(owner, null, 0L)
        val updated = notification.copy(trigger = notification.trigger?.copy(source = "com.example.new"))
            .toJob(owner, original, 1L)
        assertNotEquals(BotRoutineTrigger.from(original)?.revision, BotRoutineTrigger.from(updated)?.revision)
    }
}
