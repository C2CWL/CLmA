package me.rerere.rikkahub.workflow.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.workflow.execution.WorkflowEngine
import me.rerere.rikkahub.workflow.model.WorkflowRun
import me.rerere.rikkahub.workflow.repository.WorkflowRepository
import me.rerere.rikkahub.workflow.repository.WorkflowRepository.Loaded
import me.rerere.rikkahub.workflow.secrets.WorkflowSecretsStore

class WorkflowsViewModel(
    private val repository: WorkflowRepository,
    private val engine: WorkflowEngine,
    private val secretsStore: WorkflowSecretsStore,
) : ViewModel() {

    val workflows: StateFlow<List<Loaded>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * T-07 — secret names, refreshed after every mutation. Only names are ever held here; a
     * value is read from the store inside the fire path and never enters a UI state object.
     */
    private val _secretNames = MutableStateFlow<List<String>>(emptyList())
    val secretNames: StateFlow<List<String>> = _secretNames

    fun setEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) { repository.setEnabled(id, enabled) }
    }

    fun delete(id: String, onDone: () -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteCascading(id)
            onDone()
        }
    }

    suspend fun runNow(id: String): WorkflowEngine.FireOutcome = engine.fire(id)

    suspend fun history(id: String, limit: Int = 20): List<WorkflowRun> =
        repository.lastRuns(id, limit)

    suspend fun get(id: String): Loaded? = repository.getById(id)

    /**
     * T-07 — flip the per-workflow dynamic-argument switch. Suspends so the caller can
     * re-read the workflow afterwards without racing the write (the UI's switch is driven by
     * the freshly loaded definition, not by optimistic local state).
     */
    suspend fun setUseActionTemplates(id: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        val loaded = repository.getById(id) ?: return@withContext
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

    /** Returns false when [name] is not storable (1..64 of [A-Za-z0-9_]). */
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
