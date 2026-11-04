package com.clma.app.ui.pages.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import com.clma.app.data.datastore.SettingsStore
import com.clma.app.data.datastore.getCurrentAssistant
import com.clma.app.data.db.fts.MessageSearchResult
import com.clma.app.data.db.fts.MessageSearchSort
import com.clma.app.data.repository.ConversationRepository
import com.clma.app.ui.hooks.readStringPreference
import com.clma.app.ui.hooks.writeStringPreference
import kotlin.uuid.Uuid

private const val SearchPageSize = 50

private const val SORT_ORDER_PREF_KEY = "search_page_sort_order"

enum class MessageSearchScope {
    CURRENT_ASSISTANT,
    ALL_ASSISTANTS,
}

private data class SearchRequest(
    val query: String,
    val sort: MessageSearchSort,
    val scope: MessageSearchScope,
    val assistantId: Uuid?,
    val debounce: Boolean,
    val offset: Int = 0,
)

class SearchVM(
    private val context: Application,
    private val conversationRepo: ConversationRepository,
    private val settingsStore: SettingsStore,
) : ViewModel() {
    private val searchRequests = Channel<SearchRequest>(Channel.CONFLATED)
    private var currentAssistantId: Uuid? = null
    private var currentRequest: SearchRequest? = null
    private var nextOffset = 0

    var hasMore by mutableStateOf(false)
        private set
    var searchFailed by mutableStateOf(false)
        private set

    var searchQuery by mutableStateOf("")
        private set
    var searchScope by mutableStateOf(MessageSearchScope.CURRENT_ASSISTANT)
        private set
    var sortOrder by mutableStateOf(
        runCatching {
            MessageSearchSort.valueOf(
                context.readStringPreference(SORT_ORDER_PREF_KEY, MessageSearchSort.RELEVANCE.name)!!
            )
        }.getOrDefault(MessageSearchSort.RELEVANCE)
    )
        private set
    var results by mutableStateOf<List<MessageSearchResult>>(emptyList())
        private set
    var isLoading by mutableStateOf(false)
        private set
    var isRebuilding by mutableStateOf(false)
        private set
    var rebuildProgress by mutableStateOf(0 to 0)
        private set

    init {
        viewModelScope.launch {
            searchRequests.receiveAsFlow().collectLatest { request -> performSearch(request) }
        }
        viewModelScope.launch {
            settingsStore.settingsFlow
                .map { it.getCurrentAssistant().id }
                .distinctUntilChanged()
                .collect { assistantId ->
                    currentAssistantId = assistantId
                    if (searchScope == MessageSearchScope.CURRENT_ASSISTANT) {
                        search()
                    }
                }
        }
    }

    fun onQueryChange(query: String) {
        searchQuery = query
        requestSearch(debounce = true)
    }

    fun onScopeChange(scope: MessageSearchScope) {
        if (searchScope == scope) return
        searchScope = scope
        search()
    }

    fun onSortChange(sort: MessageSearchSort) {
        if (sortOrder == sort) return
        sortOrder = sort
        context.writeStringPreference(SORT_ORDER_PREF_KEY, sort.name)
        search()
    }

    fun search() {
        requestSearch()
    }

    private fun requestSearch(debounce: Boolean = false) {
        val assistantId = when (searchScope) {
            MessageSearchScope.CURRENT_ASSISTANT -> currentAssistantId
            MessageSearchScope.ALL_ASSISTANTS -> null
        }
        results = emptyList()
        hasMore = false
        searchFailed = false
        nextOffset = 0
        val request = SearchRequest(
            query = searchQuery,
            sort = sortOrder,
            scope = searchScope,
            assistantId = assistantId,
            debounce = debounce,
        )
        currentRequest = request
        isLoading = true
        searchRequests.trySend(request)
    }

    fun loadMore() {
        val request = currentRequest ?: return
        if (isLoading || (!hasMore && !searchFailed)) return
        isLoading = true
        searchRequests.trySend(request.copy(offset = nextOffset, debounce = false))
    }

    fun rebuildIndex() {
        viewModelScope.launch {
            isRebuilding = true
            rebuildProgress = 0 to 0
            try {
                conversationRepo.rebuildAllIndexes { current, total ->
                    rebuildProgress = current to total
                }
            } finally {
                isRebuilding = false
                search()
            }
        }
    }

    private suspend fun performSearch(request: SearchRequest) {
        if (request.query.isBlank() ||
            (request.scope == MessageSearchScope.CURRENT_ASSISTANT && request.assistantId == null)
        ) {
            isLoading = false
            return
        }
        isLoading = true
        searchFailed = false
        try {
            if (request.debounce) delay(300L)
            val page = conversationRepo.searchMessages(
                request.query, request.sort, request.assistantId,
                limit = SearchPageSize + 1, offset = request.offset,
            )
            val loaded = page.take(SearchPageSize)
            results = if (request.offset == 0) loaded else
                (results + loaded).distinctBy { it.messageId }
            nextOffset = request.offset + loaded.size
            hasMore = page.size > SearchPageSize
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            searchFailed = true
        } finally {
            isLoading = false
        }
    }
}
