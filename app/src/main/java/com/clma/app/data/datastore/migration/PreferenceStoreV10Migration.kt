package com.clma.app.data.datastore.migration

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import com.clma.app.data.datastore.SettingsStore

/**
 * Raises the untouched Token-mode compaction threshold from 8k to 32k.
 *
 * The field is stored with its default, so every install that never edited it already has 8.
 * That 8k value is also the effective context window while Token mode is selected, which made
 * automatic compaction fire far too early. Rewrite 8 to 32 once. Any other stored value was
 * chosen by the user and is kept. Setting it back to 8 afterwards sticks, because the version
 * stamp keeps this from running twice.
 */
class PreferenceStoreV10Migration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean {
        val version = currentData[SettingsStore.VERSION]
        return version == null || version < 10
    }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val prefs = currentData.toMutablePreferences()
        if (prefs[SettingsStore.AUTO_COMPACTION_THRESHOLD_TOKENS_K] == LEGACY_DEFAULT_TOKENS_K) {
            prefs[SettingsStore.AUTO_COMPACTION_THRESHOLD_TOKENS_K] = NEW_DEFAULT_TOKENS_K
        }
        prefs[SettingsStore.VERSION] = 10
        return prefs.toPreferences()
    }

    override suspend fun cleanUp() {}

    private companion object {
        const val LEGACY_DEFAULT_TOKENS_K = 8
        const val NEW_DEFAULT_TOKENS_K = 32
    }
}
