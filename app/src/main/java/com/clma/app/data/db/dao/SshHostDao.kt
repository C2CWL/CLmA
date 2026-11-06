package com.clma.app.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.clma.app.data.db.entity.SshHostEntity

@Dao
interface SshHostDao {
    // CAST: a TEXT column can still hold a BLOB (SQLite stores types per value).
    // requery's getString throws "Unable to convert BLOB to string" on those cells.
    // CAST(... AS TEXT) decodes UTF-8 bytes before Room reads the cursor.
    @Query(
        """
        SELECT name, host, port, user,
               CAST(password AS TEXT) AS password,
               CAST(privateKey AS TEXT) AS privateKey,
               CAST(passphrase AS TEXT) AS passphrase,
               createdAtMs
        FROM ssh_hosts
        ORDER BY name ASC
        """
    )
    suspend fun getAll(): List<SshHostEntity>

    @Query(
        """
        SELECT name, host, port, user,
               CAST(password AS TEXT) AS password,
               CAST(privateKey AS TEXT) AS privateKey,
               CAST(passphrase AS TEXT) AS passphrase,
               createdAtMs
        FROM ssh_hosts
        WHERE name = :name
        LIMIT 1
        """
    )
    suspend fun getByName(name: String): SshHostEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(host: SshHostEntity)

    @Delete
    suspend fun delete(host: SshHostEntity)

    @Query("DELETE FROM ssh_hosts WHERE name = :name")
    suspend fun deleteByName(name: String)
}
