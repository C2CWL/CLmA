package com.clma.app.data.memory

import java.io.File

/** Assistant-owned files. Legacy Markdown is copied once, never moved or overwritten. */
internal class AssistantColdMemoryStore(private val directory: File, legacyDirectory: File?) {
    init {
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create cold memory directory" }
        val marker = File(directory, ".migrated")
        if (!marker.exists()) {
            legacyDirectory?.listFiles()?.filter { it.isFile && it.extension.equals("md", true) }
                ?.forEach { source ->
                    val target = child(source.name)
                    if (!target.exists()) source.copyTo(target)
                }
            marker.writeText("1")
        }
    }

    fun listFiles(): List<File> = directory.listFiles().orEmpty().filter {
        it.isFile && it.extension.equals("md", true) && it.canonicalFile.parentFile == directory.canonicalFile
    }

    fun readText(name: String): String? = child(name).takeIf { it.isFile }?.readText()

    fun writeText(name: String, content: String) = child(name).writeText(content)

    fun delete(name: String): Boolean = child(name).delete()

    private fun child(name: String): File {
        require(name.isNotBlank() && '/' !in name && '\\' !in name && name != "." && name != "..")
        return File(directory, name).also {
            require(it.canonicalFile.parentFile == directory.canonicalFile) { "Cold memory path escapes its directory" }
        }
    }
}
