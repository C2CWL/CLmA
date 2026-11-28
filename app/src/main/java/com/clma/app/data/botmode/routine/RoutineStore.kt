package com.clma.app.data.botmode.routine

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class RoutineStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "botmode/routines-v2.json"))
    private val lock = Mutex()
    private var initialized = false
    private val state = MutableStateFlow(RoutineSnapshot())
    val snapshots = state.asStateFlow()

    private fun load() {
        if (initialized) return
        state.value = try {
            RoutineCodec.decode(file.readFully().decodeToString())
        } catch (_: FileNotFoundException) {
            RoutineSnapshot()
        }
        initialized = true
    }

    suspend fun read(): RoutineSnapshot = withContext(Dispatchers.IO) {
        lock.withLock {
            load()
            state.value
        }
    }

    suspend fun update(transform: (RoutineSnapshot) -> RoutineSnapshot) = withContext(Dispatchers.IO) {
        lock.withLock {
            load()
            val next = transform(state.value)
            if (next != state.value) {
                val stream = file.startWrite()
                try {
                    stream.write(RoutineCodec.encode(next).encodeToByteArray())
                    file.finishWrite(stream)
                } catch (error: Throwable) {
                    file.failWrite(stream)
                    throw error
                }
                state.value = next
            }
        }
    }

    suspend fun edit(id: String, transform: (RoutineState) -> RoutineState) {
        update { it.copy(routines = it.routines + (id to transform(it.routines[id] ?: RoutineState()))) }
    }

    suspend fun editExisting(id: String, transform: (RoutineState) -> RoutineState) {
        update { snapshot ->
            val current = snapshot.routines[id] ?: return@update snapshot
            snapshot.copy(routines = snapshot.routines + (id to transform(current)))
        }
    }

    suspend fun delete(id: String) = update { it.copy(routines = it.routines - id) }
}
