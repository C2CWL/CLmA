package com.clma.app.data.datastore.migration

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import com.clma.app.data.datastore.SettingsStore
import com.clma.app.utils.JsonInstant
import com.clma.app.utils.jsonPrimitiveOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Turns the progressive tool catalog OFF for every assistant that already exists.
 *
 * V7 flipped the product default to PROGRESSIVE_CATALOG and rewrote every stored assistant
 * once. Long multi-tool sessions then lost prompt-cache hits because the tool list changed
 * between turns, which raised input cost. The Kotlin default is DIRECT again; settings are
 * saved WITH their defaults, so existing installs still carry `toolSurfaceMode:
 * "PROGRESSIVE_CATALOG"` even when nobody touched the switch. Changing the default alone
 * would only reach assistants created from here on, so the stored value is rewritten once
 * instead. The version stamp keeps this a one-off: switching the tool surface back to
 * PROGRESSIVE_CATALOG afterwards sticks.
 */
class PreferenceStoreV11Migration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean {
        val version = currentData[SettingsStore.VERSION]
        return version == null || version < 11
    }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val prefs = currentData.toMutablePreferences()
        prefs[SettingsStore.ASSISTANTS] = prefs[SettingsStore.ASSISTANTS]?.let { json ->
            migrateAssistantToolSurfaceToDirectJson(json)
        } ?: "[]"
        prefs[SettingsStore.VERSION] = 11
        return prefs.toPreferences()
    }

    override suspend fun cleanUp() {}
}

/** Serialized name of `ToolSurfaceMode.DIRECT`, kept as a literal so this migration keeps
 *  compiling even if the enum is ever renamed again. */
internal const val TOOL_SURFACE_DIRECT = "DIRECT"

/**
 * Rewrites `toolSurfaceMode` to [TOOL_SURFACE_DIRECT] on every assistant in the serialized
 * assistants array. Every other field is passed through untouched; returns [assistantsJson]
 * unchanged when it is not a JSON array or nothing needed rewriting.
 */
internal fun migrateAssistantToolSurfaceToDirectJson(assistantsJson: String): String {
    val root = runCatching { JsonInstant.parseToJsonElement(assistantsJson) as? JsonArray }
        .getOrNull() ?: return assistantsJson
    var changed = false
    val updated = JsonArray(
        root.map { assistant ->
            val assistantObj = assistant as? JsonObject ?: return@map assistant
            val current = assistantObj["toolSurfaceMode"]?.jsonPrimitiveOrNull?.contentOrNull
            if (current == TOOL_SURFACE_DIRECT) return@map assistant
            changed = true
            JsonObject(
                assistantObj.toMutableMap().apply {
                    put("toolSurfaceMode", JsonPrimitive(TOOL_SURFACE_DIRECT))
                }
            )
        }
    )
    return if (changed) JsonInstant.encodeToString(updated) else assistantsJson
}
