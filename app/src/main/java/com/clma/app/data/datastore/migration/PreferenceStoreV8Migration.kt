package com.clma.app.data.datastore.migration

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import com.clma.app.data.datastore.SettingsStore
import com.clma.app.utils.JsonInstant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * One-time soft migration: pre-split WebDAV/S3 backup selections that included WORKSPACES
 * also covered assistant cold memory (then lumped into that chip). After ASSISTANT_MEMORY was
 * split out, those persisted item lists would silently stop backing up cold memory. Append
 * ASSISTANT_MEMORY once when WORKSPACES is present and the new value is absent.
 *
 * Deliberately NOT in [com.clma.app.data.sync.BackupItem.normalize] — that would permanently
 * re-add cold memory whenever WORKSPACES is selected and block a workspaces-only cloud backup.
 * The version stamp keeps this a one-off: deselecting ASSISTANT_MEMORY afterwards sticks.
 */
class PreferenceStoreV8Migration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean {
        val version = currentData[SettingsStore.VERSION]
        return version == null || version < 8
    }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val prefs = currentData.toMutablePreferences()
        prefs[SettingsStore.WEBDAV_CONFIG]?.let { json ->
            prefs[SettingsStore.WEBDAV_CONFIG] = migrateBackupConfigItemsJson(json)
        }
        prefs[SettingsStore.S3_CONFIG]?.let { json ->
            prefs[SettingsStore.S3_CONFIG] = migrateBackupConfigItemsJson(json)
        }
        prefs[SettingsStore.VERSION] = 8
        return prefs.toPreferences()
    }

    override suspend fun cleanUp() {}
}

private const val WORKSPACES = "WORKSPACES"
private const val ASSISTANT_MEMORY = "ASSISTANT_MEMORY"

/**
 * If a serialized WebDavConfig / S3Config `items` array contains WORKSPACES but not
 * ASSISTANT_MEMORY, append ASSISTANT_MEMORY. Other fields pass through untouched.
 */
internal fun migrateBackupConfigItemsJson(configJson: String): String {
    val root = runCatching { JsonInstant.parseToJsonElement(configJson) as? JsonObject }
        .getOrNull() ?: return configJson
    val itemsElement = root["items"] as? JsonArray ?: return configJson
    val names = itemsElement.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    if (WORKSPACES !in names || ASSISTANT_MEMORY in names) return configJson
    val updatedItems = JsonArray(itemsElement + JsonPrimitive(ASSISTANT_MEMORY))
    val updated = JsonObject(root.toMutableMap().apply { put("items", updatedItems) })
    return JsonInstant.encodeToString(updated)
}
