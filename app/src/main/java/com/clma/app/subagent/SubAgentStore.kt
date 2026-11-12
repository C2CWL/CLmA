package com.clma.app.subagent

import com.clma.app.utils.JsonInstant
import kotlinx.serialization.Serializable
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

@Serializable
internal data class SubAgentSnapshot(
    val runs: List<SubAgentRun> = emptyList(),
    val acknowledged: Set<String> = emptySet(),
)

/** App-private atomic snapshot, following LocalSnapshotStore's fsync + rename workflow.
 * No Room/schema changes. Read/write failures propagate rather than silently erase history.
 * One process owns the store through the singleton registry.
 */
class SubAgentStore(private val file: File) {
    internal fun read(): SubAgentSnapshot = if (file.exists()) {
        JsonInstant.decodeFromString(SubAgentSnapshot.serializer(), file.readText())
    } else SubAgentSnapshot()

    internal fun write(snapshot: SubAgentSnapshot) {
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        val temp = File(file.parentFile, "${file.name}.tmp")
        FileOutputStream(temp).use {
            it.write(JsonInstant.encodeToString(SubAgentSnapshot.serializer(), snapshot).toByteArray(Charsets.UTF_8))
            it.fd.sync()
        }
        Files.move(temp.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
    }
}
