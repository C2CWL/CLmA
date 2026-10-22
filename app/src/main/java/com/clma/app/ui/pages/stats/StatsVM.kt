package me.rerere.rikkahub.ui.pages.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.db.dao.ConversationDAO
import me.rerere.rikkahub.data.db.dao.MessageNodeDAO
import me.rerere.rikkahub.data.db.dao.getTokenStats
import me.rerere.rikkahub.data.agentrun.AgentRunRepository
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.usage.LedgerStatsView
import me.rerere.rikkahub.data.usage.OrchestrationTree
import me.rerere.rikkahub.data.usage.OrchestrationTreeFactory
import me.rerere.rikkahub.data.usage.UsageLedger
import me.rerere.rikkahub.data.usage.UsageLedgerDefaults
import me.rerere.rikkahub.data.usage.UsageStatsFactory

data class AppStats(
    val isLoading: Boolean = true,
    val totalConversations: Int = 0,
    val totalMessages: Int = 0,
    val totalPromptTokens: Long = 0L,
    val totalCompletionTokens: Long = 0L,
    val totalCachedTokens: Long = 0L,
    val launchCount: Int = 0,
)

class StatsVM(
    private val conversationDAO: ConversationDAO,
    private val messageNodeDAO: MessageNodeDAO,
    private val settingsStore: SettingsStore,
    private val usageLedger: UsageLedger,
    private val agentRunRepository: AgentRunRepository,
) : ViewModel() {

    private val _stats = MutableStateFlow(AppStats())
    val stats = _stats.asStateFlow()

    /**
     * P2-12c - the ledger window, or null before it loads (and if the read fails). Keeping it a
     * separate flow from [stats] is deliberate: [AppStats] counts the whole message history, the
     * ledger only covers the 90-day retention window, so the two must never be added together.
     */
    private val _ledgerStats = MutableStateFlow<LedgerStatsView?>(null)
    val ledgerStats = _ledgerStats.asStateFlow()

    /** How far back the ledger section reads; [setRange] reloads it. */
    private val _range = MutableStateFlow(StatsRange.LAST_30)
    val range = _range.asStateFlow()

    /** assistant id -> display name, so the "by assistant" ranking shows names, not UUIDs. */
    private val _assistantNames = MutableStateFlow<Map<String, String>>(emptyMap())
    val assistantNames = _assistantNames.asStateFlow()

    /**
     * P2-12d - the parent→child dispatches, or null before they load (and if the read fails,
     * matching [ledgerStats]). An empty list means "loaded, but no sub-agent runs recorded".
     */
    private val _orchestrationTrees = MutableStateFlow<List<OrchestrationTree>?>(null)
    val orchestrationTrees = _orchestrationTrees.asStateFlow()

    /** conversation id -> title, so a dispatch is headed by the conversation that made it. */
    private val _conversationTitles = MutableStateFlow<Map<String, String>>(emptyMap())
    val conversationTitles = _conversationTitles.asStateFlow()

    init {
        viewModelScope.launch { loadStats() }
    }

    /** Switches the ledger window and re-reads it. A no-op when the value is unchanged. */
    fun setRange(value: StatsRange) {
        if (_range.value == value) return
        _range.value = value
        viewModelScope.launch { loadLedger() }
    }

    private suspend fun loadStats() {
        delay(50)

        // D10 - the 52-week conversation heatmap is gone. The page's daily series now comes
        // from the ledger's `byDay` buckets, so the message-per-day scan is no longer read
        // here (it was the heatmap's only consumer).
        val totalConversations = conversationDAO.countAll()

        // json_each() + json_extract() 在 SQLite 侧聚合，不再加载完整 JSON 到 Kotlin
        val tokenStats = messageNodeDAO.getTokenStats()

        val launchCount = settingsStore.settingsFlow.value.launchCount

        _stats.value = AppStats(
            isLoading = false,
            totalConversations = totalConversations,
            totalMessages = tokenStats.totalMessages,
            totalPromptTokens = tokenStats.promptTokens,
            totalCompletionTokens = tokenStats.completionTokens,
            totalCachedTokens = tokenStats.cachedTokens,
            launchCount = launchCount,
        )

        loadLedger()
        loadOrchestration()
    }

    /**
     * P2-12c - read the ledger's last 90 days and bucket it along the four axes the page shows.
     *
     * Best effort on purpose: the older cards must keep working even if the ledger read fails, so a
     * failure leaves [ledgerStats] null and the section simply does not render.
     *
     * The ledger lives in its own database file, so it cannot be joined to `conversationentity` in
     * SQL. The auxiliary calls (TITLE / SUGGESTION / COMPACTION) run under a conversation but do not
     * set an assistant id on their row, so the conversation -> assistant map is built here and passed
     * in as the fallback resolver.
     */
    private suspend fun loadLedger() {
        _assistantNames.value = settingsStore.settingsFlow.value.assistants
            .associate { it.id.toString() to it.name }

        runCatching {
            val sinceMs = System.currentTimeMillis() -
                _range.value.days * 24L * 60L * 60L * 1000L
            val rows = usageLedger.recordsSince(sinceMs)
            val conversationToAssistant = withContext(Dispatchers.IO) {
                conversationDAO.getAll().first().associate { it.id to it.assistantId }
            }
            UsageStatsFactory.build(
                records = rows,
                assistantOfConversation = conversationToAssistant::get,
            )
        }.onSuccess { _ledgerStats.value = it }
    }

    /**
     * P2-12d - read the newest runs and price each sub-agent dispatch with the ledger rows that
     * carry its run id.
     *
     * The tree's shape comes from `agent_runs`, which only ever holds a sub-agent row once a
     * dispatch happened; the numbers come from `usage_records`, which only carries a run id from
     * P2-12d onwards. Best effort, exactly like [loadLedger]: a failed read leaves
     * [orchestrationTrees] null and the section stays hidden.
     *
     * The ledger read starts at the oldest run in the window, so a run whose calls predate the
     * 50-row window still gets its numbers without reading the whole 90-day ledger.
     */
    private suspend fun loadOrchestration() {
        runCatching {
            val runs = agentRunRepository.getRecent(ORCHESTRATION_RUN_LIMIT)
            if (runs.isEmpty()) return@runCatching emptyList<OrchestrationTree>()

            val sinceMs = runs.minOf { it.createdAtMs }
            val rows = usageLedger.recordsSince(sinceMs, UsageLedgerDefaults.STATS_QUERY_LIMIT)
            val conversations = withContext(Dispatchers.IO) { conversationDAO.getAll().first() }
            _conversationTitles.value = conversations.associate { it.id to it.title }
            // P2-14d — the ceiling lives on the assistant, the tree is keyed by the conversation that
            // dispatched, so the two are joined here (the same shape as the ledger's
            // conversation -> assistant fallback above). Null stays null: an assistant with no
            // configured budget leaves the tree's footer off.
            val budgetByAssistant = settingsStore.settingsFlow.value.assistants
                .associate { it.id.toString() to it.orchestrationTokenBudget }
            val budgetByConversation = conversations.associate {
                it.id.toString() to budgetByAssistant[it.assistantId.toString()]
            }
            OrchestrationTreeFactory.build(
                runs = runs,
                records = rows,
                budgetOfConversation = { conversationId ->
                    conversationId?.let { budgetByConversation[it] }
                },
            )
        }.onSuccess { _orchestrationTrees.value = it }
    }
}

/**
 * P2-12d - how many of the newest `agent_runs` rows the orchestration tree reads. 50 matches the
 * default window [AgentRunRepository.getRecent] offers, so the tree stays in step with the rest
 * of the ledger UI.
 */
private const val ORCHESTRATION_RUN_LIMIT = 50

/** The window the statistics page's ledger section covers. */
enum class StatsRange(val days: Int) {
    LAST_7(7),
    LAST_30(30),
    LAST_90(90),
}
