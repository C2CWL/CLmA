package com.clma.app.virtualdisplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualDisplayInputRulesTest {
    private fun client(uid: Int = 10123, display: Int = 7) =
        "ClientState{abc mUid=$uid mPid=123 mSelfReportedDisplayId=$display}"

    private fun dump(current: String = client(), focused: String = client()) = """
        mCurClient=$current mCurSeq=2
        mCurFocusedWindow=android.os.BinderProxy@123 softInputMode=STATE_UNSPECIFIED client=$focused
        mCurTokenDisplayId=0
    """.trimIndent()

    @Test fun `accept matching VD client even if keyboard is on main display`() {
        assertEquals(InputTarget(10123, 7), parseInputTarget(dump()))
    }

    @Test fun `reject main display`() {
        assertNull(parseInputTarget(dump(client(display = 0), client(display = 0))))
    }

    @Test fun `reject different focused display`() {
        assertNull(parseInputTarget(dump(focused = client(display = 0))))
    }

    @Test fun `reject different focused uid`() {
        assertNull(parseInputTarget(dump(focused = client(uid = 10124))))
    }

    @Test fun `reject absent inactive malformed and overflow clients`() {
        listOf("", "null", "ClientState{abc mUid=10123}",
            "ClientState{abc mUid=99999999999999999 mSelfReportedDisplayId=7}",
            "ClientState{abc mUid=10123 mSelfReportedDisplayId=-1}").forEach {
            assertNull(parseInputTarget(dump(current = it)))
            assertNull(parseInputTarget(dump(focused = it)))
        }
        assertNull(parseInputTarget("mCurTokenDisplayId=7"))
    }

    @Test fun `reject ambiguous multiple user or display dumps`() {
        assertNull(parseInputTarget(dump() + "\n" + dump()))
        assertNull(parseInputTarget(dump() + "\nmCurClient=" + client()))
        assertNull(parseInputTarget(dump() + "\nmCurFocusedWindow=x client=" + client()))
    }

    @Test fun `ease out clamps and reaches exact endpoints`() {
        assertEquals(0f, swipeFraction(-1f), 0f)
        assertEquals(0f, swipeFraction(0f), 0f)
        assertEquals(1f, swipeFraction(1f), 0f)
        assertEquals(1f, swipeFraction(2f), 0f)
        assertEquals(0.875f, swipeFraction(0.5f), 0.000001f)
    }

    @Test fun `ease out is monotonic and decelerates toward release`() {
        val samples = (0..100).map { swipeFraction(it / 100f) }
        samples.zipWithNext().forEach { (a, b) -> assertTrue(b >= a) }
        val deltas = samples.zipWithNext { a, b -> b - a }
        deltas.zipWithNext().forEach { (a, b) -> assertTrue(b <= a + 0.000001f) }
        assertTrue(deltas.last() < 0.00001f)
    }
}
