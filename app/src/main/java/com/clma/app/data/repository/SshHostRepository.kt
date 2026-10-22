package com.clma.app.data.repository

import com.clma.app.data.db.dao.SshHostDao
import com.clma.app.data.db.entity.SshHostEntity

class SshHostRepository(private val dao: SshHostDao) {
    suspend fun getAll(): List<SshHostEntity> = dao.getAll()
    suspend fun getByName(name: String): SshHostEntity? = dao.getByName(name)
    suspend fun upsert(host: SshHostEntity) = dao.upsert(host)
    suspend fun deleteByName(name: String) = dao.deleteByName(name)
}
