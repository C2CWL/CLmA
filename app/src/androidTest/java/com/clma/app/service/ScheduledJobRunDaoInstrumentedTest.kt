package com.clma.app.service

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.clma.app.data.db.AppDatabase
import com.clma.app.data.db.dao.ScheduledJobRunDao
import com.clma.app.data.db.entity.ScheduledJobRunEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScheduledJobRunDaoInstrumentedTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: ScheduledJobRunDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.scheduledJobRunDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun countSuccessful_excludesInFlightRowsUntilCompletion() = runBlocking {
        val inFlight = row("running", slotMs = 100L, startedAtMs = 100L).copy(finishedAtMs = null)
        dao.insert(inFlight)
        assertEquals(0, dao.countSuccessful("job"))

        dao.update(inFlight.copy(finishedAtMs = 200L))
        assertEquals(1, dao.countSuccessful("job"))

        dao.insert(row("failed", 200L, 200L, outcome = "failed"))
        dao.insert(row("other-job", 100L, 100L).copy(jobId = "other"))
        assertEquals(1, dao.countSuccessful("job"))
    }

    @Test
    fun getForSlot_findsAnOlderAttemptDespiteNewerManualAndSkipRows() = runBlocking {
        dao.insert(row("attempt", 100L, 100L))
        dao.insert(row("manual", 150L, 150L))
        dao.insert(row("concurrent", 100L, 200L, outcome = "concurrent_skip"))
        dao.insert(row("catchup", 100L, 300L, outcome = "skipped_catchup"))
        dao.insert(row("other-job", 100L, 400L).copy(jobId = "other"))

        assertEquals("attempt", dao.getForSlot("job", 100L)?.id)
        assertNull(dao.getForSlot("job", 999L))
    }

    @Test
    fun getForSlot_returnsLatestReplayButNotSkipOnlyHistory() = runBlocking {
        dao.insert(row("attempt", 100L, 100L))
        dao.insert(row("replay", 100L, 200L, outcome = "process_killed_replay"))
        dao.insert(row("only-skip", 300L, 300L, outcome = "skipped_catchup"))

        assertEquals("replay", dao.getForSlot("job", 100L)?.id)
        assertNull(dao.getForSlot("job", 300L))
    }

    private fun row(
        id: String,
        slotMs: Long,
        startedAtMs: Long,
        outcome: String = "success",
    ) = ScheduledJobRunEntity(
        id = id,
        jobId = "job",
        mode = "direct",
        scheduledAtMs = slotMs,
        startedAtMs = startedAtMs,
        finishedAtMs = startedAtMs + 1L,
        outcome = outcome,
    )
}
