package com.clma.app.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class RestoreQuotaTest {
    private fun quota(entries: Int = 2, entry: Long = 4, total: Long = 6, space: Long = 100) =
        RestoreQuota(entries, entry, total) { space }

    private fun copy(q: RestoreQuota, size: Int): Int {
        val out = ByteArrayOutputStream()
        q.copy(ByteArrayInputStream(ByteArray(size)), out) { }
        return out.size()
    }

    @Test fun acceptsWithinLimits() { assertEquals(4, copy(quota(), 4)) }
    @Test fun rejectsTooManyEntries() {
        val q = quota(entries = 1)
        q.countEntry()
        assertThrows(IllegalArgumentException::class.java) { q.countEntry() }
    }
    @Test fun rejectsOversizedEntry() {
        assertThrows(IllegalArgumentException::class.java) { copy(quota(), 5) }
    }
    @Test fun rejectsTotalAcrossEntries() {
        val q = quota()
        copy(q, 4)
        assertThrows(IllegalArgumentException::class.java) { copy(q, 3) }
    }
    @Test fun rejectsInsufficientSpace() {
        assertThrows(IllegalArgumentException::class.java) { copy(quota(space = 3), 4) }
    }
    @Test fun cancellationPropagates() {
        assertThrows(IllegalStateException::class.java) {
            quota().copy(ByteArrayInputStream(byteArrayOf(1)), ByteArrayOutputStream()) { error("cancelled") }
        }
    }
}
