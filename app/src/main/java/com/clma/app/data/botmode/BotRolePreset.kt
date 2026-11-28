// Adapted from OpenMausBot (https://github.com/milind-soni/OpenMausBot), docs/presets.md, server/routes/bot-presets.ts, server/share-zip.ts,
// Copyright 2026 Milind Soni and OpenMausBot contributors, Apache License 2.0
// (third_party/openmausbot/LICENSE). Modified: ported to Kotlin and changed for CLmA.
package com.clma.app.data.botmode

import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal const val BOT_ROLE_NAME_LIMIT = 200
internal const val BOT_ROLE_PROMPT_LIMIT = 72_000
private const val MAX_PRESET_BYTES = 512 * 1024

@Serializable
internal data class BotRolePreset(
    val version: Int,
    val name: String,
    val prompt: String,
    val skills: List<String> = emptyList(),
)

internal object BotRolePresetFile {
    private val json = Json { prettyPrint = true }

    private fun validate(preset: BotRolePreset): BotRolePreset {
        require(preset.version == 1)
        require(preset.name.isNotBlank() && preset.name.length <= BOT_ROLE_NAME_LIMIT)
        require(preset.prompt.length <= BOT_ROLE_PROMPT_LIMIT)
        require(preset.skills.size <= 200 && preset.skills.all { it.isNotBlank() && it.length <= 200 })
        return preset
    }

    fun encode(preset: BotRolePreset): ByteArray =
        json.encodeToString(validate(preset)).encodeToByteArray().also { require(it.size <= MAX_PRESET_BYTES) }

    fun read(input: InputStream): BotRolePreset {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count == -1) break
            require(output.size() + count <= MAX_PRESET_BYTES)
            output.write(buffer, 0, count)
        }
        val text = output.toByteArray().decodeToString(throwOnInvalidSequence = true).removePrefix("\uFEFF")
        return validate(json.decodeFromString<BotRolePreset>(text))
    }
}
