package com.clma.app.data.repository

import com.clma.app.data.db.dao.ScheduledJobRunDao
import com.clma.app.data.db.entity.ScheduledJobRunEntity

class ScheduledJobRunRepository(private val dao: ScheduledJobRunDao) {
    fun observeRecent(jobId: String, limit: Int) = dao.observeRecent(jobId, limit)
    suspend fun getById(id: String) = dao.getById(id)
    suspend fun getRecent(jobId: String, limit: Int) = dao.getRecent(jobId, limit)
    suspend fun lastFinished(jobId: String, since: Long, until: Long) = dao.lastFinished(jobId, since, until)
    suspend fun getStranded(stalenessMs: Long) = dao.getStranded(stalenessMs)
    suspend fun insert(row: ScheduledJobRunEntity) = dao.insert(row)
    suspend fun update(row: ScheduledJobRunEntity) = dao.update(row)
    suspend fun trim(jobId: String, keep: Int) = dao.trim(jobId, keep)
    suspend fun deleteAllForJob(jobId: String) = dao.deleteAllForJob(jobId)
    suspend fun getForSlot(jobId: String, slotMs: Long) = dao.getForSlot(jobId, slotMs)
    suspend fun countSuccessful(jobId: String) = dao.countSuccessful(jobId)
}
