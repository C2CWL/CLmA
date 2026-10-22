package com.clma.app.data.repository

import com.clma.app.data.db.dao.ScheduledJobRunDao
import com.clma.app.data.db.entity.ScheduledJobRunEntity

class ScheduledJobRunRepository(private val dao: ScheduledJobRunDao) {
    suspend fun getRecent(jobId: String, limit: Int) = dao.getRecent(jobId, limit)
    suspend fun getStranded(stalenessMs: Long) = dao.getStranded(stalenessMs)
    suspend fun insert(row: ScheduledJobRunEntity) = dao.insert(row)
    suspend fun update(row: ScheduledJobRunEntity) = dao.update(row)
    suspend fun trim(jobId: String, keep: Int) = dao.trim(jobId, keep)
    suspend fun deleteAllForJob(jobId: String) = dao.deleteAllForJob(jobId)
    suspend fun getMostRecent(jobId: String) = dao.getMostRecent(jobId)
    suspend fun countSuccessful(jobId: String) = dao.countSuccessful(jobId)
}
