package com.clma.app.data.datastore.migration

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import com.clma.app.data.datastore.SettingsStore
import com.clma.app.utils.JsonInstant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Replaces the fast-streaming switch with a three-way pace.
 *
 * `fastStreamingOutput` was a boolean, and settings are saved with their defaults, so existing
 * installs carry `true` (publish every token) or `false` (batch). The field is now
 * [com.clma.app.data.datastore.StreamingPace], which a stored boolean cannot decode, so the JSON
 * is rewritten once: `true` becomes `immediate`, `false` becomes `balanced` (the 32 ms batching
 * that switch already selected). A missing key means the field was never stored and the default
 * applies, so nothing is written. Choosing a pace afterwards sticks, because the version stamp
 * keeps this from running twice.
 */
class PreferenceStoreV12Migration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean {
        val version = currentData[SettingsStore.VERSION]
        return version == null || version < 12
    }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val prefs = currentData.toMutablePreferences()
        prefs[SettingsStore.DISPLAY_SETTING]?.let { json ->
            prefs[SettingsStore.DISPLAY_SETTING] = migrateStreamingPaceJson(json)
        }
        prefs[SettingsStore.VERSION] = 12
        return prefs.toPreferences()
    }

    override suspend fun cleanUp() {}
}

/** Serialized names of [com.clma.app.data.datastore.StreamingPace], kept as literals so this
 *  migration keeps compiling even if the enum is renamed. */
internal const val STREAMING_PACE_IMMEDIATE = "immediate"
internal const val STREAMING_PACE_BALANCED = "balanced"

/**
 * Rewrites `fastStreamingOutput` (a boolean) to `streamingPace` (a string) in a serialized
 * DisplaySetting. Every other field is passed through untouched. Returns [json] unchanged when
 * it is not a JSON object, the key is absent, or its value is not a boolean.
 */
internal fun migrateStreamingPaceJson(json: String): String {
    val root = runCatching { JsonInstant.parseToJsonElement(json).jsonObject }.getOrNull() ?: return json
    val current = (root["fastStreamingOutput"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
        ?: return json
    val pace = if (current) STREAMING_PACE_IMMEDIATE else STREAMING_PACE_BALANCED
    val updated = root.toMutableMap().apply {
        remove("fastStreamingOutput")
        put("streamingPace", JsonPrimitive(pace))
    }
    return JsonInstant.encodeToString(JsonObject(updated))
}
