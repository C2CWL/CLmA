// Adapted from OpenMausBot (https://github.com/milind-soni/OpenMausBot), server/data/engine.ts, server/data/sql-statements.ts,
// Copyright 2026 Milind Soni and OpenMausBot contributors, Apache License 2.0
// (third_party/openmausbot/LICENSE). Modified: ported to Kotlin and changed for CLmA.
package com.clma.app.data.botmode.data

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.os.CancellationSignal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal object BotDataSql {
    private const val MAX_ROWS = 2_000
    private val watchdog = Executors.newSingleThreadScheduledExecutor()
    private val selectStart = Regex("^(SELECT|WITH)\\b", RegexOption.IGNORE_CASE)

    suspend fun query(table: BotDataTable, sql: String): BotDataTable = suspendCancellableCoroutine { continuation ->
        val signal = CancellationSignal()
        continuation.invokeOnCancellation { signal.cancel() }
        Dispatchers.IO.dispatch(continuation.context, Runnable {
            if (!continuation.isActive) return@Runnable
            val timeout = watchdog.schedule({ signal.cancel() }, 3, TimeUnit.SECONDS)
            try {
                continuation.resume(runQuery(table, sql, signal))
            } catch (failure: Exception) {
                continuation.resumeWithException(failure)
            } finally {
                timeout.cancel(false)
            }
        })
    }

    private fun runQuery(table: BotDataTable, sql: String, signal: CancellationSignal): BotDataTable {
        val statement = sql.trim().removeSuffix(";")
        require(statement.length <= 8_000 && selectStart.containsMatchIn(statement))
        return SQLiteDatabase.create(null).use { db ->
            val columns = table.columns.joinToString(", ") { "\"${it.replace("\"", "\"\"")}\"" }
            db.execSQL("CREATE TABLE data ($columns)")
            val placeholders = table.columns.joinToString(", ") { "?" }
            db.compileStatement("INSERT INTO data VALUES ($placeholders)").use { insert ->
                db.beginTransaction()
                try {
                    for (row in table.rows) {
                        signal.throwIfCanceled()
                        insert.clearBindings()
                        row.forEachIndexed { index, cell ->
                            val parameter = index + 1
                            when (cell) {
                                null -> insert.bindNull(parameter)
                                is Long -> insert.bindLong(parameter, cell)
                                is Double -> insert.bindDouble(parameter, cell)
                                else -> insert.bindString(parameter, cell.toString())
                            }
                        }
                        insert.executeInsert()
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
            db.execSQL("PRAGMA query_only = ON")
            db.rawQuery("SELECT * FROM (\n$statement\n) LIMIT ${MAX_ROWS + 1}", null, signal).use queryResult@ { cursor ->
                require(cursor.columnCount <= 48)
                val rows = mutableListOf<List<Any?>>()
                var remainingChars = 350_000
                while (cursor.moveToNext()) {
                    signal.throwIfCanceled()
                    if (rows.size == MAX_ROWS || remainingChars <= 0) {
                        return@queryResult BotDataTable(cursor.columnNames.toList(), rows, truncated = true)
                    }
                    rows += List(cursor.columnCount) { index ->
                        when (cursor.getType(index)) {
                            Cursor.FIELD_TYPE_NULL -> null
                            Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                            Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
                            Cursor.FIELD_TYPE_BLOB -> "BLOB"
                            else -> cursor.getString(index).take(2_048)
                        }.also { remainingChars -= it?.toString()?.length ?: 0 }
                    }
                }
                BotDataTable(cursor.columnNames.toList(), rows)
            }
        }
    }
}
