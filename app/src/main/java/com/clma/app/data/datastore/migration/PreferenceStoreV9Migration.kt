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
 * Turns the user avatar OFF everywhere for installs that never asked for it.
 *
 * `showUserAvatar` used to default to on, and settings are saved WITH their defaults, so every
 * existing install carries `showUserAvatar: true` even when nobody ever touched the switch. The
 * Gemini-style chat no longer shows an avatar on either side of the conversation, and the drawer
 * follows the same switch, so a stored `true` is rewritten to `false` once. Turning the switch
 * back on afterwards sticks: the version stamp keeps this from running twice.
 */
class PreferenceStoreV9Migration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean {
        val version = currentData[SettingsStore.VERSION]
        return version == null || version < 9
    }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val prefs = currentData.toMutablePreferences()
        prefs[SettingsStore.DISPLAY_SETTING]?.let { json ->
            prefs[SettingsStore.DISPLAY_SETTING] = migrateUserAvatarHiddenJson(json)
        }
        prefs[SettingsStore.VERSION] = 9
        return prefs.toPreferences()
    }

    override suspend fun cleanUp() {}
}

/**
 * Rewrites `showUserAvatar: true` in a serialized DisplaySetting to `false`. A missing key means
 * the field is absent from older JSON and the (already false) default applies, so nothing is
 * written; every other field is passed through untouched. Returns [json] unchanged when it is not
 * a JSON object or nothing needs rewriting.
 */
internal fun migrateUserAvatarHiddenJson(json: String): String {
    val root = runCatching { JsonInstant.parseToJsonElement(json).jsonObject }.getOrNull() ?: return json
    val current = (root["showUserAvatar"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
    if (current != true) return json
    val updated = root.toMutableMap().apply { put("showUserAvatar", JsonPrimitive(false)) }
    return JsonInstant.encodeToString(JsonObject(updated))
}
