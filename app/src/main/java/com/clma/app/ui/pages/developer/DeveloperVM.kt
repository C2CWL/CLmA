package com.clma.app.ui.pages.developer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import com.clma.app.data.ai.AILoggingManager
import com.clma.app.data.datastore.AiLogLevel

class DeveloperVM(
    private val aiLoggingManager: AILoggingManager,
) : ViewModel() {
    val logs = aiLoggingManager.getLogs()
    val logLevel = aiLoggingManager.getLogLevel()

    fun setLogLevel(level: AiLogLevel) {
        viewModelScope.launch {
            aiLoggingManager.setLogLevel(level)
        }
    }
}
