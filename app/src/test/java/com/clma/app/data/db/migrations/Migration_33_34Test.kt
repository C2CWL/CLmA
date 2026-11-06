package com.clma.app.data.db.migrations

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rewrite itself needs a SQLite connection (Room migration tests are androidTest and
 * this tree is not built locally). Lock the statement so a future edit cannot drop a
 * column or the typeof filter.
 */
class Migration_33_34Test {
    @Test
    fun sqlRewritesOnlyBlobSecretCells() {
        val sql = SSH_HOST_BLOB_TO_TEXT_SQL
        assertTrue(sql.contains("UPDATE ssh_hosts"))
        assertTrue(sql.contains("password = CAST(password AS TEXT)"))
        assertTrue(sql.contains("privateKey = CAST(privateKey AS TEXT)"))
        assertTrue(sql.contains("passphrase = CAST(passphrase AS TEXT)"))
        assertTrue(sql.contains("typeof(password) = 'blob'"))
        assertTrue(sql.contains("typeof(privateKey) = 'blob'"))
        assertTrue(sql.contains("typeof(passphrase) = 'blob'"))
    }
}
