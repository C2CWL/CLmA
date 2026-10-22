package com.clma.app.data.repository

import kotlinx.coroutines.flow.Flow
import com.clma.app.data.db.dao.ScheduledJobDao
import com.clma.app.data.db.entity.ScheduledJobEntity

class ScheduledJobRepository(private val dao: ScheduledJobDao) {
    suspend fun getAll(): List<ScheduledJobEntity> = dao.getAll()
    fun observeAll(): Flow<List<ScheduledJobEntity>> = dao.observeAll()
    suspend fun getById(id: String): ScheduledJobEntity? = dao.getById(id)
    suspend fun getEnabled(): List<ScheduledJobEntity> = dao.getEnabled()
    suspend fun upsert(job: ScheduledJobEntity) = dao.upsert(job)
    suspend fun update(job: ScheduledJobEntity) = dao.update(job)
    suspend fun deleteById(id: String) = dao.deleteById(id)
    suspend fun listFiltered(tag: String?, mode: String?, enabled: Boolean?): List<ScheduledJobEntity> =
        dao.listFiltered(tag, mode, enabled)
}
