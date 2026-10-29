package com.clma.app.ui.hooks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExternalTextSyncTest {

    /** Simulates a field: local edits are pushed, persisted values come back later. */
    private class Field(initial: String) {
        val sync = ExternalTextSync(initial)
        var text = initial

        fun type(newText: String, echo: String = newText) {
            text = newText
            sync.onLocalEdit(echo)
        }

        fun receive(external: String): String? =
            sync.onExternal(external, text)?.also { text = it }
    }

    @Test
    fun `stale echo during IME composition does not overwrite local text`() {
        // The reported bug: typing after 服务器助手 with a pinyin IME produced 服务器助手手66我去.
        val field = Field("服务器助手")
        field.type("服务器助手6")
        field.type("服务器助手66")
        field.type("服务器助手66我")
        field.type("服务器助手66我去")

        // Persistence lags behind and echoes the older values.
        assertNull(field.receive("服务器助手6"))
        assertNull(field.receive("服务器助手66"))
        assertEquals("服务器助手66我去", field.text)

        // Eventually it catches up.
        assertNull(field.receive("服务器助手66我去"))
        assertEquals("服务器助手66我去", field.text)
        assertEquals(0, field.sync.pendingCount)
    }

    @Test
    fun `conflated echoes are ignored and pending is cleared once caught up`() {
        val field = Field("")
        field.type("a")
        field.type("ab")
        field.type("abc")
        // StateFlow may skip intermediate values.
        assertNull(field.receive("a"))
        assertEquals(2, field.sync.pendingCount)
        assertNull(field.receive("abc"))
        assertEquals("abc", field.text)
        assertEquals(0, field.sync.pendingCount)
    }

    @Test
    fun `typing then deleting back keeps local text through all echoes`() {
        val field = Field("")
        field.type("a")
        field.type("ab")
        field.type("a")
        assertNull(field.receive("a"))
        assertNull(field.receive("ab"))
        assertEquals("a", field.text)
        assertNull(field.receive("a"))
        assertEquals("a", field.text)
    }

    @Test
    fun `genuine external change is adopted`() {
        val field = Field("old")
        assertEquals("restored", field.receive("restored"))
        assertEquals("restored", field.text)
    }

    @Test
    fun `external change while local edits are in flight is adopted and pending reset`() {
        val field = Field("x")
        field.type("xy")
        assertEquals("from backup", field.receive("from backup"))
        assertEquals(0, field.sync.pendingCount)
        // A late echo of the overwritten edit is now an outside change too.
        assertEquals("xy", field.receive("xy"))
    }

    @Test
    fun `repeated identical external value is a no-op`() {
        val field = Field("same")
        assertNull(field.receive("same"))
        assertNull(field.receive("same"))
        assertEquals("changed", field.receive("changed"))
        field.type("mine")
        assertNull(field.receive("changed"))
        assertEquals("mine", field.text)
    }

    @Test
    fun `normalised echoes are recognised`() {
        // Caller trims before persisting.
        val field = Field("")
        field.type(" ab", echo = "ab")
        field.type(" ab ", echo = "ab")
        assertEquals(1, field.sync.pendingCount) // consecutive duplicate echo collapsed
        assertNull(field.receive("ab"))
        assertEquals(" ab ", field.text)

        // Caller parses a number: "007" is stored as 7.
        val number = Field("1")
        number.type("007", echo = "7")
        assertNull(number.receive("7"))
        assertEquals("007", number.text)
    }

    @Test
    fun `pending queue is capped`() {
        val sync = ExternalTextSync("")
        repeat(ExternalTextSync.MAX_PENDING + 36) { sync.onLocalEdit("v$it") }
        assertEquals(ExternalTextSync.MAX_PENDING, sync.pendingCount)
        // The newest echoes are still recognised.
        assertNull(sync.onExternal("v${ExternalTextSync.MAX_PENDING + 35}", "local"))
    }

    @Test
    fun `positive number echoes`() {
        assertEquals("", positiveIntEcho(""))
        assertEquals("", positiveIntEcho("  "))
        assertEquals("", positiveIntEcho("0"))
        assertEquals("", positiveIntEcho("-3"))
        assertEquals("", positiveIntEcho("abc"))
        assertEquals("12", positiveIntEcho("12"))
        assertEquals("7", positiveIntEcho("007"))
        assertEquals("", positiveIntEcho("99999999999"))

        assertEquals("", positiveLongEcho(""))
        assertEquals("", positiveLongEcho("0"))
        assertEquals("99999999999", positiveLongEcho("99999999999"))
        assertEquals("5", positiveLongEcho("05"))
    }
}
