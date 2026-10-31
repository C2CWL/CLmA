package com.clma.app.ui.pages.setting

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import com.clma.app.AppScope
import com.clma.app.data.datastore.Settings
import com.clma.app.data.datastore.SettingsStore
import com.clma.app.data.ai.mcp.McpManager

class SettingVM(
    private val settingsStore: SettingsStore,
    private val mcpManager: McpManager,
    private val appScope: AppScope,
) :
    ViewModel() {
    val settings: StateFlow<Settings> = settingsStore.settingsFlow

    fun updateSettings(settings: Settings) {
        // Settings edits must survive leaving the settings screen immediately. Using the
        // application scope prevents a ViewModel cancellation from dropping the DataStore edit
        // while the user exits or navigates away after typing.
        appScope.launch(Dispatchers.IO) {
            settingsStore.update(settings)
        }
    }

    fun updateSettings(transform: (Settings) -> Settings) {
        appScope.launch(Dispatchers.IO) {
            settingsStore.update(transform)
        }
    }
}
