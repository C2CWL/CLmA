package me.rerere.rikkahub.data.usage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsagePeakWindowTextTest {

    @Test
    fun `an empty line means no peak windows`() {
        val parsed = UsagePeakWindowText.parse("   ")
        assertEquals(emptyList<PriceWindowSpec>(), parsed.windows)
        assertNull(parsed.error)
    }

    @Test
    fun `the documented deepseek style line parses into two windows on one clock`() {
        val parsed = UsagePeakWindowText.parse("1-5 09:00-12:00, 14:00-18:00 @+08:00")
        assertNull(parsed.error)
        assertEquals(2, parsed.windows.size)
        assertEquals(listOf(1, 2, 3, 4, 5), parsed.windows[0].daysOfWeek)
        assertEquals(540, parsed.windows[0].startMinute)
        assertEquals(720, parsed.windows[0].endMinute)
        assertEquals(840, parsed.windows[1].startMinute)
        assertEquals(1080, parsed.windows[1].endMinute)
        assertEquals(480, parsed.windows[1].zoneOffsetMinutes)
    }

    @Test
    fun `no day list means every day and no offset means UTC`() {
        val parsed = UsagePeakWindowText.parse("09:00-12:00")
        assertNull(parsed.error)
        val window = parsed.windows.single()
        assertEquals(emptyList<Int>(), window.daysOfWeek)
        assertEquals(0, window.zoneOffsetMinutes)
    }

    @Test
    fun `an asterisk also means every day`() {
        assertNull(UsagePeakWindowText.parse("* 09:00-12:00").error)
        assertEquals(emptyList<Int>(), UsagePeakWindowText.parse("* 09:00-12:00").windows.single().daysOfWeek)
    }

    @Test
    fun `a window may wrap past midnight`() {
        val window = UsagePeakWindowText.parse("22:00-02:00").windows.single()
        assertEquals(1320, window.startMinute)
        assertEquals(120, window.endMinute)
    }

    @Test
    fun `a day range may wrap through the week`() {
        assertEquals(
            listOf(1, 5, 6, 7),
            UsagePeakWindowText.parse("5-1 09:00-10:00").windows.single().daysOfWeek,
        )
    }

    @Test
    fun `every malformed piece is named in the error`() {
        assertEquals(
            "segment needs a time range: 1-5",
            UsagePeakWindowText.parse("1-5").error,
        )
        assertEquals(
            "time range must look like 09:00-12:00 (got: 09:00)",
            UsagePeakWindowText.parse("09:00").error,
        )
        assertEquals(
            "start time must be HH:MM within a day (got: 25:00)",
            UsagePeakWindowText.parse("25:00-26:00").error,
        )
        assertEquals(
            "days must be 1..7 like 1-5 (got: 1-9)",
            UsagePeakWindowText.parse("1-9 09:00-10:00").error,
        )
        assertEquals(
            "offset must look like +08:00 (got: +99:00)",
            UsagePeakWindowText.parse("09:00-12:00 @+99:00").error,
        )
        assertEquals(
            "a window cannot start and end at the same minute: 09:00-09:00",
            UsagePeakWindowText.parse("09:00-09:00").error,
        )
    }

    @Test
    fun `formatting is the inverse of parsing`() {
        val line = "1-5 09:00-12:00, 14:00-18:00 @+08:00"
        assertEquals(line, UsagePeakWindowText.format(UsagePeakWindowText.parse(line).windows))
    }

    @Test
    fun `formatting compresses whole weeks and runs`() {
        assertEquals("", UsagePeakWindowText.format(emptyList()))
        assertEquals(
            "09:00-10:00",
            UsagePeakWindowText.format(listOf(PriceWindowSpec(540, 600, 0, (1..7).toList()))),
        )
        assertEquals(
            "1-3,5 09:00-10:00",
            UsagePeakWindowText.format(listOf(PriceWindowSpec(540, 600, 0, listOf(1, 2, 3, 5)))),
        )
    }

    @Test
    fun `clock and offset formatting is fixed width`() {
        assertEquals("09:00", UsagePeakWindowText.formatMinute(540))
        assertEquals("00:00", UsagePeakWindowText.formatMinute(0))
        assertEquals("23:59", UsagePeakWindowText.formatMinute(1439))
        assertEquals("+08:00", UsagePeakWindowText.formatOffset(480))
        assertEquals("-05:30", UsagePeakWindowText.formatOffset(-330))
        assertEquals("+00:00", UsagePeakWindowText.formatOffset(0))
        assertEquals(480, UsagePeakWindowText.parseOffset("+08:00"))
        assertEquals(-330, UsagePeakWindowText.parseOffset("-5:30"))
        assertEquals(0, UsagePeakWindowText.parseOffset("+0"))
        assertNull(UsagePeakWindowText.parseOffset("+08:60"))
        assertNull(UsagePeakWindowText.parseOffset("08:00"))
    }
}
