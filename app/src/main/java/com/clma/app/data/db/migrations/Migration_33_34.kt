package com.clma.app.data.db.migrations

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

private const val TAG = "Migration_33_34"

/** Rewrites only cells whose storage class is blob. CAST leaves TEXT untouched. */
internal const val SSH_HOST_BLOB_TO_TEXT_SQL = """
    UPDATE ssh_hosts
    SET password = CAST(password AS TEXT),
        privateKey = CAST(privateKey AS TEXT),
        passphrase = CAST(passphrase AS TEXT)
    WHERE typeof(password) = 'blob'
       OR typeof(privateKey) = 'blob'
       OR typeof(passphrase) = 'blob'
""".trimIndent()

/**
 * v33 → v34: rewrite ssh_hosts secret cells whose storage class is BLOB.
 *
 * The columns are declared TEXT and Room reads them with getString(). requery throws
 * "Unable to convert BLOB to string" when a cell was stored as BLOB (SQLite types are
 * per-value; affinity does not rewrite existing rows). CAST(x AS TEXT) yields the UTF-8
 * text of a blob and leaves real TEXT unchanged. No schema change, so identity hash stays.
 */
val Migration_33_34 = object : Migration(33, 34) {
    override fun migrate(db: SupportSQLiteDatabase) {
        Log.i(TAG, "migrate: rewrite ssh_hosts BLOB secrets as TEXT")
        db.execSQL(SSH_HOST_BLOB_TO_TEXT_SQL)
    }
}
