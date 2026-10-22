package com.clma.app.data.datastore.migration

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import com.clma.app.data.datastore.DEFAULT_CLEAN_LAYOUT_OPACITY
import com.clma.app.data.datastore.SettingsStore
import com.clma.app.utils.JsonInstant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Moves the clean layout's panel strengths off the old 100% default.
 *
 * Until now 100% was the default and, because settings are saved with their defaults, every
 * install has `1.0` stored even if the user never touched the sliders. 100% now means a near-solid
 * card (the glass panels gained a real fill), so an untouched `1.0` is rewritten to the new
 * default once. Any other value (e.g. 0.62, 0.33, 0) was picked by the user and is kept. The
 * version stamp makes this run only once, so a later deliberate 100% stays 100%.
 */
class PreferenceStoreV6Migration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean {
        val version = currentData[SettingsStore.VERSION]
        return version == null || version < 6
    }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val prefs = currentData.toMutablePreferences()
        prefs[SettingsStore.DISPLAY_SETTING]?.let { json ->
            prefs[SettingsStore.DISPLAY_SETTING] = migrateCleanLayoutOpacityJson(json)
        }
        prefs[SettingsStore.VERSION] = 6
        return prefs.toPreferences()
    }

    override suspend fun cleanUp() {}
}

/** Display-setting keys whose old default (1.0) is rewritten by [migrateCleanLayoutOpacityJson]. */
internal val CLEAN_LAYOUT_OPACITY_KEYS = listOf("cleanLayoutTopOpacity", "cleanLayoutBottomOpacity")

/**
 * Rewrites an untouched `1.0` in [CLEAN_LAYOUT_OPACITY_KEYS] of a serialized DisplaySetting to
 * [DEFAULT_CLEAN_LAYOUT_OPACITY]. Every other field and value is left as it is. Returns [json]
 * unchanged when it is not a JSON object or nothing needs rewriting.
 */
internal fun migrateCleanLayoutOpacityJson(json: String): String {
    val root = runCatching { JsonInstant.parseToJsonElement(json).jsonObject }.getOrNull() ?: return json
    var changed = false
    val updated = root.toMutableMap()
    for (key in CLEAN_LAYOUT_OPACITY_KEYS) {
        val value = (root[key] as? JsonPrimitive)?.takeIf { !it.isString }?.floatOrNull
        if (value == 1f) {
            updated[key] = JsonPrimitive(DEFAULT_CLEAN_LAYOUT_OPACITY)
            changed = true
        }
    }
    return if (changed) JsonInstant.encodeToString(JsonObject(updated)) else json
}
