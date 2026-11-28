package com.clma.app.data.repository

import com.clma.app.data.botmode.routine.RoutineStore
import com.clma.app.data.db.dao.ScheduledJobDao
import com.clma.app.data.db.entity.ScheduledJobEntity
import kotlinx.coroutines.flow.Flow

class ScheduledJobRepository(
    private val dao: ScheduledJobDao,
    private val routineStore: RoutineStore? = null,
) {
    suspend fun getAll(): List<ScheduledJobEntity> = dao.getAll()
    fun observeAll(): Flow<List<ScheduledJobEntity>> = dao.observeAll()
    suspend fun getById(id: String): ScheduledJobEntity? = dao.getById(id)
    suspend fun getEnabled(): List<ScheduledJobEntity> = dao.getEnabled()
    suspend fun upsert(job: ScheduledJobEntity) = dao.upsert(job)
    suspend fun update(job: ScheduledJobEntity) = dao.update(job)
    suspend fun updateIfSameScheduleType(job: ScheduledJobEntity): Boolean = dao.updateIfSameScheduleType(job)
    suspend fun deleteById(id: String) {
        dao.deleteById(id)
        routineStore?.delete(id)
    }
    suspend fun listFiltered(tag: String?, mode: String?, enabled: Boolean?): List<ScheduledJobEntity> =
        dao.listFiltered(tag, mode, enabled)
}
