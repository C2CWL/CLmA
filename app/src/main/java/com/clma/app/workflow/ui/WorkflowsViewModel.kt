package com.clma.app.workflow.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.combine
import com.clma.app.data.botmode.activeLibraryScope
import com.clma.app.data.datastore.SettingsStore
import com.clma.app.workflow.model.WorkflowRunStatus
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.clma.app.workflow.execution.WorkflowEngine
import com.clma.app.workflow.model.WorkflowRun
import com.clma.app.workflow.repository.WorkflowRepository
import com.clma.app.workflow.repository.WorkflowRepository.Loaded
import com.clma.app.workflow.secrets.WorkflowSecretsStore

class WorkflowsViewModel(
    private val repository: WorkflowRepository,
    private val engine: WorkflowEngine,
    private val secretsStore: WorkflowSecretsStore,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    val workflows: StateFlow<List<Loaded>> = combine(repository.observeAll(), settingsStore.settingsFlow) { workflows, settings ->
        val scope = settings.activeLibraryScope()
        workflows.filter { scope.allows(it.definition.authoringAssistantId.orEmpty()) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * T-07 — secret names, refreshed after every mutation. Only names are ever held here; a
     * value is read from the store inside the fire path and never enters a UI state object.
     */
    private val _secretNames = MutableStateFlow<List<String>>(emptyList())
    val secretNames: StateFlow<List<String>> = _secretNames

    fun setEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) { if (get(id) != null) repository.setEnabled(id, enabled) }
    }

    fun delete(id: String, onDone: () -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            get(id) ?: return@launch
            repository.deleteCascading(id)
            onDone()
        }
    }

    suspend fun runNow(id: String): WorkflowEngine.FireOutcome =
        if (get(id) != null) engine.fire(id) else WorkflowEngine.FireOutcome(WorkflowRunStatus.FAILED, null, "")

    suspend fun history(id: String, limit: Int = 20): List<WorkflowRun> =
        if (get(id) == null) emptyList() else repository.lastRuns(id, limit)

    suspend fun get(id: String): Loaded? = repository.getById(id)?.takeIf {
        settingsStore.settingsFlow.value.activeLibraryScope().allows(it.definition.authoringAssistantId.orEmpty())
    }

    /**
     * T-07 — flip the per-workflow dynamic-argument switch. Suspends so the caller can
     * re-read the workflow afterwards without racing the write (the UI's switch is driven by
     * the freshly loaded definition, not by optimistic local state).
     */
    suspend fun setUseActionTemplates(id: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        val loaded = repository.getById(id)?.takeIf {
        settingsStore.settingsFlow.value.activeLibraryScope().allows(it.definition.authoringAssistantId.orEmpty())
    } ?: return@withContext
        repository.upsert(
            loaded.definition.copy(
                useActionTemplates = enabled,
                updatedAtMs = System.currentTimeMillis(),
            )
        )
    }

    fun refreshSecretNames() {
        viewModelScope.launch(Dispatchers.IO) { _secretNames.value = secretsStore.list() }
    }

    /** Returns false for an invalid name or unavailable secure encryption. */
    suspend fun setSecret(name: String, value: String): Boolean = withContext(Dispatchers.IO) {
        val stored = secretsStore.set(name.trim(), value)
        if (stored) _secretNames.value = secretsStore.list()
        stored
    }

    suspend fun deleteSecret(name: String) = withContext(Dispatchers.IO) {
        secretsStore.remove(name)
        _secretNames.value = secretsStore.list()
    }
}
