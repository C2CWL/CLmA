package com.clma.app.service

import com.clma.app.diagnostics.FunctionalDiagnostics
import android.app.Application
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.completeWith
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.jsonObject
import com.clma.ai.core.MessageRole
import com.clma.ai.core.ReasoningLevel
import com.clma.ai.core.Tool
import com.clma.ai.provider.Model
import com.clma.ai.provider.ModelAbility
import com.clma.ai.provider.ProviderManager
import com.clma.ai.provider.TextGenerationParams
import com.clma.ai.ui.ToolApprovalState
import com.clma.ai.ui.UIMessage
import com.clma.ai.ui.UIMessagePart
import com.clma.ai.ui.canResumeToolExecution
import com.clma.ai.ui.finishPendingTools
import com.clma.ai.ui.finishReasoning
import com.clma.ai.ui.isEmptyInputMessage
import com.clma.ai.ui.limitContext
import com.clma.common.android.Logging
import com.clma.app.AppScope
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.clma.app.CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID
import com.clma.app.CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID
import com.clma.app.RouteActivity
import com.clma.app.utils.cancelNotification
import com.clma.app.utils.sendNotification
import com.clma.app.R
import com.clma.app.data.ai.AssistantResolver
import com.clma.app.data.ai.AutomationRecorder
import com.clma.app.data.ai.tools.AppPlaybookFile
import com.clma.app.data.ai.tools.AppPlaybookRules
import com.clma.app.data.ai.GenerationChunk
import com.clma.app.data.ai.GenerationLoop
import com.clma.app.data.ai.ModelRouter
import com.clma.app.data.ai.ContextBudgetPlanner
import com.clma.app.data.ai.PreSendContextDecision
import com.clma.app.data.ai.PreSendContextGuard
import com.clma.app.data.ai.shouldCompact
import com.clma.app.data.ai.document.DocumentSizeEstimator
import com.clma.ai.util.MediaTooLargeException
import com.clma.ai.util.RequestTooLargeException
import com.clma.ai.util.isRequestTooLarge
import com.clma.app.data.ai.ContextCompactionPlanner
import com.clma.app.data.ai.ContextCompactionPresentation
import com.clma.app.data.ai.compaction.tomc.TomcSummaryLedger
import com.clma.app.data.ai.compaction.tomc.TomcTurn
import com.clma.app.data.ai.compaction.tomc.TomcAgentCompiler
import com.clma.app.data.ai.compaction.tomc.TomcAgent
import com.clma.app.data.datastore.CompactionEngine
import com.clma.app.data.ai.CompactedMessageView
import com.clma.app.data.ai.ContextCompactionView
import com.clma.app.data.ai.ContentPart
import com.clma.app.data.ai.DocumentEmbeddingProgress
import com.clma.app.data.ai.DocumentEmbeddingQueue
import com.clma.app.data.ai.DocumentEmbeddingService
import com.clma.app.data.ai.DocumentRagDecision
import com.clma.app.data.ai.DocumentRagService
import com.clma.app.data.ai.EmbeddedDocument
import com.clma.app.data.ai.EmbeddingResolution
import com.clma.app.data.ai.GraphRagService
import com.clma.app.data.ai.GraphRagDecision
import com.clma.app.data.ai.GraphRagBuildStatus
import com.clma.app.data.ai.GraphRagTriggerPolicy
import com.clma.app.data.ai.MemoryExtractionResult
import com.clma.app.data.ai.MemoryExtractionProgress
import com.clma.app.data.ai.MemoryExtractionService
import com.clma.app.data.ai.PreparedUserMessage
import com.clma.app.data.ai.RagResolution
import com.clma.app.data.ai.resolveEmbeddingModel
import com.clma.app.data.ai.resolveRagModel
import com.clma.app.data.ai.resolveModelWithFallback
import com.clma.app.data.ai.transformers.MessageTransformer
import com.clma.app.data.ai.transformers.transformMessage
import com.clma.app.data.datastore.SettingsStore
import com.clma.app.data.datastore.PromptsStore
import com.clma.app.data.db.entity.MessageNodeEntity
import com.clma.app.data.files.FileFolders
import com.clma.app.data.files.FilesManager
import com.clma.app.data.files.deleteRecursivelyIfExists
import com.clma.app.data.model.Assistant
import com.clma.app.data.model.Conversation
import com.clma.app.data.model.DEFAULT_CONVERSATION_ID
import com.clma.app.data.model.MessageNode
import com.clma.app.data.model.AutopilotBudget
import com.clma.app.data.model.DEFAULT_AUTOPILOT_BUDGET
import com.clma.app.data.model.MessageSelection
import com.clma.app.data.model.AutopilotLaunchMode
import com.clma.app.data.model.autopilotModeToken
import com.clma.app.data.model.effectiveAutopilotModeToken
import com.clma.app.data.model.parseAutopilotModeToken
import com.clma.app.data.repository.ConversationRepository
import com.clma.app.data.repository.MessageRepository
import com.clma.app.data.repository.ModelRepository
import com.clma.app.di.AutopilotMemoryExtractionTrigger
import com.clma.app.di.AutopilotGraphRagTrigger
import com.clma.app.di.AutopilotModeBridge
import com.clma.app.di.MessageGraphRagTrigger
import com.clma.app.di.MessageMemoryExtractionTrigger
import com.clma.app.di.AgenticGraphRagTrigger
import com.clma.app.di.ScheduledTaskGraphRagTrigger
import com.clma.app.ui.hooks.AttachmentProcessingProgress
import com.clma.app.ui.hooks.ChatInputState
import com.clma.app.ui.hooks.ConversationState
import com.clma.app.ui.hooks.IdAndState
import com.clma.app.ui.components.ui.AgenticSendPolicyDialog
import com.clma.app.ui.components.ui.AgenticSendPolicyDecision
import com.clma.app.ui.pages.setting.LoopConfirmationMemoryPolicy
import com.clma.app.ui.pages.setting.ManagedMcpServer
import com.clma.app.utils.ChatAttachmentKind
import com.clma.app.utils.ConnectionState
import com.clma.app.utils.toJson
import com.clma.app.utils.classifyChatAttachment
import com.clma.app.utils.toMessageContents
import com.clma.app.utils.validateMessageList
import com.clma.app.utils.getConfiguredMcps
import com.clma.app.utils.warn
import com.clma.app.RagIndexConflictException
import com.clma.app.localindex.RagIndexStore
import com.clma.app.localindex.GraphRagStore
import com.clma.app.localindex.RagIndexStats
import com.clma.app.localindex.RagCorpusReconcileResult
import com.clma.app.localindex.GraphRagBuildResult
import com.clma.app.localindex.StructuredFactsService
import com.clma.app.localindex.EvidenceDigestService
import com.clma.app.localindex.applyTo
import com.clma.app.localindex.indexDirForConversation
import com.clma.app.localindex.parseFactsFromText
import com.clma.app.RagIndexMissingException
import com.clma.app.AgenticRagConfirmationRequiredException
import com.clma.app.AgenticRagIndexRequiredException
import com.clma.app.AgenticRagPolicyConfirmationRequiredException
import com.clma.app.AgenticRagQuotaConfirmationRequiredException
import com.clma.app.AgenticRagUsageEstimate
import com.clma.app.GraphRagIndexMissingException
import com.clma.app.MemoryExtractionConfirmationRequiredException
import com.clma.app.AgenticLoopConfirmationRequiredException
import com.clma.app.GraphRagBuildConfirmationRequiredException
import com.clma.app.GraphRagBuildResultRequiredException
import com.clma.app.GraphRagSameModelConfirmationRequiredException
import com.clma.app.RagVectorizationConfirmationRequiredException
import com.clma.app.RagBuildConfirmationRequiredException
import com.clma.app.McpServerRuntimeState
import com.clma.app.ScheduledTaskExecutionContext
import com.clma.app.ScheduledTaskGraphRagQuotaConfirmationRequiredException
import com.clma.app.ScheduledTaskGraphRagQuotaUsage
import com.clma.app.ScheduledTaskMemoryExtractionConfirmationRequiredException
import com.clma.app.ScheduledTaskDeliveryExecutor
import com.clma.app.mcp.AvailableClient
import com.clma.app.mcp.ConnectedClient
import com.clma.app.mcp.McpClientResult
import com.clma.app.mcp.McpConnectionResult
import com.clma.app.mcp.McpManager
import com.clma.app.mcp.McpRuntimeStatus
import com.clma.app.mcp.McpServerStatus
import com.clma.app.mcp.McpToolDescriptor
import com.clma.app.mcp.McpToolExecutionException
import com.clma.app.mcp.connectManagedServer
import com.clma.app.mcp.shutdownIdle
import com.clma.app.mcp.withTimeoutCancellationSafe
import com.clma.app.mcp.local.LocalMcpServerManager
import com.clma.app.mcp.local.VirtualDisplayLocalServerManager
import com.clma.app.ssh.SshHost
import com.clma.app.ssh.SshManager
import com.clma.app.ssh.SshRuntimeState
import com.clma.app.ssh.SshStatus
import com.clma.app.shizuku.ShizukuManager
import com.clma.app.shizuku.ShizukuStatus
import com.clma.app.trime.TrimeController
import com.clma.app.trime.TrimeStatus
import com.clma.app.shizuku.ShizukuLocalMcpServerManager
import com.clma.app.virtualdisplay.VirtualDisplayManager
import com.clma.app.virtualdisplay.VirtualDisplaySession
import com.clma.app.virtualdisplay.VirtualDisplaySourceMode
import com.clma.app.virtualdisplay.VirtualDisplayStatus
import com.clma.app.virtualdisplay.VirtualDisplayRuntime
import com.clma.app.virtualdisplay.VirtualDisplayProjectionState
import com.clma.app.virtualdisplay.VirtualDisplayRecorder
import com.clma.app.virtualdisplay.diagnostics.VirtualDisplayDiagnosticsRecorder
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

class ChatService(
    private val context: Context,
    private val application: Application,
    private val settingsStore: SettingsStore,
    private val promptsStore: PromptsStore,
    private val filesManager: FilesManager,
    private val messagesRepository: MessageRepository,
    private val conversationRepository: ConversationRepository,
    private val modelRepository: ModelRepository,
    private val messageTransformer: MessageTransformer,
    private val documentEmbeddingQueue: DocumentEmbeddingQueue,
    private val documentEmbeddingService: DocumentEmbeddingService,
    private val documentRagService: DocumentRagService,
    private val memoryExtractionService: MemoryExtractionService,
    private val graphRagService: GraphRagService,
    private val structuredFactsService: StructuredFactsService,
    private val evidenceDigestService: EvidenceDigestService,
    private val ragIndexStore: RagIndexStore,
    private val graphRagStore: GraphRagStore,
    private val mcpManager: McpManager,
    private val mcpLocalServerManager: LocalMcpServerManager,
    private val sshManager: SshManager,
    private val shizukuManager: ShizukuManager,
    private val shizukuLocalServerManager: ShizukuLocalMcpServerManager,
    private val virtualDisplayManager: VirtualDisplayManager,
    private val virtualDisplayLocalServerManager: VirtualDisplayLocalServerManager,
    private val trimeController: TrimeController,
    private val functionalDiagnostics: FunctionalDiagnostics,
    private val appScope: AppScope,
) {
    companion object {
        private const val TAG = "ChatService"
        const val MAX_TOOL_CALL_ITERATIONS = 80
        private const val MAX_MCP_CONNECTION_RETRIES = 3
        private const val MCP_STATUS_RETRY_DELAY_MS = 1_500L
        private const val MCP_TOOL_RESULT_PREVIEW_LIMIT = 20_000
        private const val MCP_TOOL_RESULT_TIMEOUT_SECONDS = 300L
        private val MCP_TOOL_RESULT_TIMEOUT = MCP_TOOL_RESULT_TIMEOUT_SECONDS.seconds
        private const val MCP_DISPLAY_CALL_TIMEOUT_SECONDS = 60L
        private const val MCP_DISPLAY_RETENTION_MINUTES = 10L
        private const val MCP_DISPLAY_RETENTION_CLEANUP_INTERVAL_MS = 30_000L
        private val MCP_DISPLAY_RETENTION = MCP_DISPLAY_RETENTION_MINUTES.minutes
        private const val MCP_MAX_CONCURRENT_CONNECTIONS = 4
        private const val PRE_SEND_COMPACTION_MAX_ATTEMPTS = 2
        private const val PRE_SEND_COMPACTION_TOMC_ATTEMPTS = 3
        private const val PRE_SEND_COMPACTION_RETRY_SLEEP_MS = 350L
        private const val MOMO_PROACTIVE_ACTIVE_START_MINUTE = 8 * 60
        private const val MOMO_PROACTIVE_ACTIVE_END_MINUTE = 23 * 60
        private const val MOMO_PROACTIVE_MAX_TOKENS = 96
        private const val MOMO_PROACTIVE_MAX_MESSAGE_CHARS = 220
        private const val MOMO_PROACTIVE_FORCE_REPLY_MAX_MESSAGE_CHARS = 300
        private const val MOMO_PROACTIVE_CANDIDATE_ASSISTANT_LIMIT = 6
        private const val MOMO_PROACTIVE_CONTEXT_TURN_LIMIT = 8
        private const val MOMO_PROACTIVE_DEFAULT_INTERVAL_MS = 10 * 60 * 1_000L
        private const val MOMO_PROACTIVE_OFFLINE_TIMEOUT_MS = 4_000L
        private const val AGENTIC_RAG_MODE_OFF = "off"
        private const val AGENTIC_RAG_MODE_ASK = "ask"
        private const val AGENTIC_RAG_MODE_AUTO = "auto"
        private const val DEFAULT_MAX_RAG_USAGE_PER_TURN = 4
        private const val DEFAULT_MAX_DOCUMENT_READ_CHARS_PER_TURN = 24_000
        private const val DEFAULT_MAX_RAG_TOKEN_BUDGET_PER_TURN = 12_000
        private const val AGENTIC_RAG_FORBIDDEN_REPLY = "当前会话禁止向量化检索，本轮不会自动检索附件。"
        private const val AGENTIC_RAG_QUOTA_FORBIDDEN_REPLY = "本轮附件检索已达到使用上限。"
        private const val AGENTIC_LOOP_MAX_ITERATIONS = 20
        private const val AGENTIC_LOOP_ERROR_FEEDBACK_LIMIT = 3_000
        private const val AGENTIC_LOOP_MAX_INLINE_STRUCTURED_FACTS = 40
        private const val AGENTIC_LOOP_MAX_INLINE_EVIDENCE_DIGESTS = 8
        private const val AGENTIC_LOOP_STRUCTURAL_HEAVY_TOOL_RESULT_THRESHOLD = 8_000
        private const val AGENTIC_LOOP_CONTEXT_CACHE_TTL_MS = 5 * 60 * 1000L
        private const val AGENTIC_LOOP_COMPLETION_CONFIRMATION = "任务已按要求完成，没有待执行的后续步骤。"
        private const val GRAPH_RAG_DEFAULT_MIN_SHARED_DOCUMENTS = 2
        private const val GRAPH_RAG_MIN_ENTITIES = 3
        private const val GRAPH_RAG_COMPLETION_STAGE = "final_generation"
        private const val GRAPH_RAG_STAGE_BUDGET = 600_000
        private const val DEFAULT_COMPLETION_MAX_CONTEXT_PERCENT = 80
        private const val MIN_COMPLETION_MAX_CONTEXT_PERCENT = 40
        private const val MAX_COMPLETION_MAX_CONTEXT_PERCENT = 95
        private const val MAX_HISTORY_VISIBLE_CHARS = 60_000
        private const val MAX_HISTORY_VISIBLE_PART_CHARS = 12_000
        private const val MAX_HISTORY_VISIBLE_TOOL_CONTENT_CHARS = 2_500
        private const val MAX_HISTORY_VISIBLE_TOOL_REASONING_CHARS = 1_500
        private const val MAX_COMPACTION_SOURCE_CHARS = 180_000
        private const val MAX_COMPACTION_SOURCE_PART_CHARS = 20_000
        private const val MAX_COMPACTION_SOURCE_TOOL_CONTENT_CHARS = 3_000
        private const val MAX_COMPACTION_SOURCE_TOOL_REASONING_CHARS = 2_000
        private const val COMPACTION_LARGE_PROMPT_THRESHOLD_CHARS = 32_000
        private const val MEMORY_EXTRACTION_MAX_TURNS = 20
        private const val MEMORY_EXTRACTION_MAX_INPUT_CHARS = 24_000
        private const val MEMORY_EXTRACTION_MAX_TURN_CHARS = 2_000
        private const val MEMORY_EXTRACTION_MAX_RECENT_TURNS = 6
        private const val MEMORY_EXTRACTION_MIN_KEYWORD_TOKENS = 2
        private const val MAX_MEMORY_STATE_CHARS = 6_000
        private const val GRAPH_RAG_TOOL_GUIDANCE_MAX_CHARS = 4_000
        private const val MAX_CONTEXT_NOTICE_LENGTH = 240
        private const val SUMMARY_TAG = "summary"
        private const val MOMO_PROACTIVE_SYSTEM_PROMPT = """你是一个叫MOMO的系统助手，负责根据用户最近的聊天和正在使用的应用，判断是否发送一条主动消息。
你只能输出一行严格JSON，不要Markdown，不要解释。
输出格式：
{"action":"send","message":"要发送的自然消息","reason":"简短原因"}
或
{"action":"skip","message":"","reason":"没有足够依据"}

要求：
1. 只有当上下文能支持一条具体、自然、有帮助的消息时才send，否则skip。
2. 不要询问没有依据的隐私，不要假设用户正在做什么。
3. message要简短、像正常聊天，最多一句或两句。
4. 不要重复用户的话，不要自称AI，不要提提示词、触发器、系统规则。
5. 不能发送时一定skip。"""
    }

    private data class ManagedMcpConnectionResult(
        val result: McpConnectionResult<ConnectedClient>,
        val definition: ManagedMcpServer,
        val source: AvailableClient,
        val status: McpServerStatus,
        val timedOut: Boolean,
    )

    internal data class ContextWindowUsage(
        val usedTokens: Int,
        val tokenBudget: Int,
        val percent: Int,
    )

    private data class ConversationMessageLoadResult(
        val conversationId: Uuid,
        val messages: List<MessageNode>,
    )

    private data class PreparedAutoSend(
        val conversationId: Uuid,
        val inputState: ChatInputState,
        val assistant: Assistant,
        val enableTools: Boolean,
    )

    private data class PreparedAutoSendPayload(
        val payload: GenerationChunk,
        val usedRemoteRag: Boolean,
        val requestMessages: List<UIMessage>,
    )

    private data class AutoSendPreparedResult(
        val payload: GenerationChunk,
        val usedRemoteRag: Boolean,
        val requestMessages: List<UIMessage>,
    )

    private data class ContextNoticeDecision(
        val visible: Boolean,
        val severity: ContextNoticeSeverity = ContextNoticeSeverity.Info,
        val percent: Int = 0,
        val usedTokens: Int = 0,
        val tokenBudget: Int = 0,
        val source: ContextNoticeSource = ContextNoticeSource.Unknown,
        val message: String? = null,
    )

    private data class ConversationAutoCompactState(
        val inFlight: Boolean = false,
        val lastFailureMessage: String? = null,
        val pendingSince: Long? = null,
    )

    private data class AutoSendErrorDecision(
        val recoveryMessage: String? = null,
        val usage: ContextWindowUsage? = null,
    )

    private data class AgenticSendPolicyState(
        val showDialog: Boolean = false,
        val pendingMessage: String? = null,
        val lastDecision: AgenticSendPolicyDecision? = null,
        val confirmedModeToken: String? = null,
        val confirmedPolicyMode: String? = null,
        val confirmedForce: Boolean = false,
        val latestPolicyReason: String? = null,
    )

    private data class PendingRagUserAction(
        val conversationId: Uuid,
        val inputState: ChatInputState,
        val assistant: Assistant,
        val enableTools: Boolean,
        val userMessage: UIMessage,
        val graphRagDocumentCount: Int,
        val graphRagMinSharedDocuments: Int,
        val graphRagTriggerPolicy: GraphRagTriggerPolicy,
        val ragUsageEstimate: AgenticRagUsageEstimate? = null,
    )

    private data class GraphRagIndexBuildRequest(
        val conversationId: Uuid,
        val assistantId: Uuid,
        val modelId: String,
        val documentCount: Int,
        val minSharedDocuments: Int,
        val tokenBudget: Int,
        val memoryPolicy: String,
        val triggerPolicy: GraphRagTriggerPolicy,
    )

    private data class MemoryExtractionRequest(
        val conversationId: Uuid,
        val assistantId: Uuid,
        val assistantName: String,
        val modelId: String,
        val turns: List<Pair<String, String>>,
        val memoryPolicy: String,
    )

    private data class AgenticLoopExecutionResult(
        val responseMessage: UIMessage,
        val usedRemoteRag: Boolean,
        val toolObservations: List<ToolCallExecution> = emptyList(),
    )

    private data class AutoCompactionResult(
        val conversationId: Uuid,
        val selectedNodeId: Uuid,
        val reason: String,
        val compactedTurns: Int,
        val compactedTokens: Int,
        val compactedUntilNodeId: Uuid,
        val sourceMessages: List<UIMessage>,
    )

    private data class AutoCompactionTrigger(
        val conversationId: Uuid,
        val selectedNodeId: Uuid,
        val reason: String,
    )

    private data class LoopConfirmationRequest(
        val conversationId: Uuid,
        val reason: String,
        val iteration: Int,
        val maxIterations: Int,
        val toolCalls: Int,
        val toolSuccesses: Int,
        val toolFailures: Int,
        val task: String,
        val currentState: String,
        val remainingPlan: String,
        val nextStep: String,
    )

    private data class AgenticLoopContextCache(
        val conversationId: Uuid,
        val lastTurn: Int,
        val facts: List<String>,
        val digests: List<String>,
        val toolSummary: String,
        val updatedAt: Long,
    )

    private data class PreparedMessageContext(
        val messages: List<UIMessage>,
        val ragDecision: DocumentRagDecision,
    )

    private data class MomoProactiveAssistantCandidate(
        val assistant: Assistant,
        val conversationId: Uuid,
        val recentMessages: List<UIMessage>,
        val lastMessageAt: Long,
    )

    private data class MomoProactiveTriggerDecision(
        val candidate: MomoProactiveAssistantCandidate,
        val message: String,
        val triggerReason: String,
    )

    private data class LocalToolApprovalRequest(
        val assistant: Assistant,
        val conversationId: Uuid,
        val scope: CoroutineScope,
        val ownerJob: Job,
        val toolCallId: String,
        val toolName: String,
        val arguments: String,
        val approval: ToolApprovalState.Pending,
    )

    private data class ToolCallExecution(
        val index: Int,
        val toolCallId: String,
        val toolName: String,
        val toolArguments: String,
        val content: String,
        val reasoning: String? = null,
        val isLocalExecution: Boolean = false,
        val isSuccess: Boolean = true,
    )

    private data class MomoProactiveModelOutput(
        val action: String,
        val message: String,
        val reason: String,
    )

    private data class ToolCallLoopExecutionResult(
        val message: UIMessage,
        val hasToolCalls: Boolean,
        val usedRemoteRag: Boolean,
        val toolObservations: List<ToolCallExecution>,
    )

    private data class LocalToolExecutionResult(
        val execution: ToolCallExecution,
        val keepDisplay: Boolean,
    )

    private val _conversations = MutableStateFlow<List<IdAndState>>(emptyList())
    val conversations: StateFlow<List<IdAndState>> = _conversations.asStateFlow()

    private val mcpStatusSyncMutex = Mutex()
    private val conversationJobs = mutableMapOf<Uuid, Job>()
    private val loadingConversationIds = mutableSetOf<Uuid>()
    private val conversationLoadingMutex = Mutex()
    private val conversationLoadMutex = Mutex()
    private val autoCompactionMutex = Mutex()
    private val autoCompactionJobs = mutableMapOf<Uuid, Job>()
    private val autoCompactStates = mutableMapOf<Uuid, ConversationAutoCompactState>()
    private val _autoCompactState = MutableStateFlow<Map<Uuid, ConversationAutoCompactState>>(emptyMap())
    private val agenticLoopJobs = mutableMapOf<Uuid, Job>()
    private val agenticLoopContinuationMutex = Mutex()
    private val pendingAgenticLoopContinuationRequests = mutableMapOf<Uuid, LoopConfirmationRequest>()
    private val agenticLoopContextCaches = mutableMapOf<Uuid, AgenticLoopContextCache>()
    private val _pendingRagUserAction = MutableStateFlow<PendingRagUserAction?>(null)
    val pendingRagUserAction: StateFlow<PendingRagUserAction?> = _pendingRagUserAction.asStateFlow()
    private val _agenticSendPolicyState = MutableStateFlow(AgenticSendPolicyState())
    val agenticSendPolicyState: StateFlow<AgenticSendPolicyState> = _agenticSendPolicyState.asStateFlow()
    private val _agenticRagConfirmationMessage = MutableStateFlow<String?>(null)
    val agenticRagConfirmationMessage: StateFlow<String?> = _agenticRagConfirmationMessage.asStateFlow()
    private val _agenticRagQuotaMessage = MutableStateFlow<String?>(null)
    val agenticRagQuotaMessage: StateFlow<String?> = _agenticRagQuotaMessage.asStateFlow()
    private val _agenticRagVectorizationMessage = MutableStateFlow<String?>(null)
    val agenticRagVectorizationMessage: StateFlow<String?> = _agenticRagVectorizationMessage.asStateFlow()
    private val _agenticRagIndexBuildMessage = MutableStateFlow<String?>(null)
    val agenticRagIndexBuildMessage: StateFlow<String?> = _agenticRagIndexBuildMessage.asStateFlow()
    private val _memoryExtractionConfirmationMessage = MutableStateFlow<String?>(null)
    val memoryExtractionConfirmationMessage: StateFlow<String?> = _memoryExtractionConfirmationMessage.asStateFlow()
    private val _scheduledTaskMemoryExtractionConfirmationMessage = MutableStateFlow<String?>(null)
    val scheduledTaskMemoryExtractionConfirmationMessage: StateFlow<String?> = _scheduledTaskMemoryExtractionConfirmationMessage.asStateFlow()
    private val _scheduledTaskGraphRagQuotaMessage = MutableStateFlow<String?>(null)
    val scheduledTaskGraphRagQuotaMessage: StateFlow<String?> = _scheduledTaskGraphRagQuotaMessage.asStateFlow()
    private val _agenticRagIndexBuildResult = MutableStateFlow<GraphRagBuildResult?>(null)
    val agenticRagIndexBuildResult: StateFlow<GraphRagBuildResult?> = _agenticRagIndexBuildResult.asStateFlow()
    private val _agenticRagSameModelMessage = MutableStateFlow<String?>(null)
    val agenticRagSameModelMessage: StateFlow<String?> = _agenticRagSameModelMessage.asStateFlow()
    private val _agenticLoopConfirmationMessage = MutableStateFlow<LoopConfirmationRequest?>(null)
    val agenticLoopConfirmationMessage: StateFlow<LoopConfirmationRequest?> = _agenticLoopConfirmationMessage.asStateFlow()
    private val _graphRagBuildConfirmationMessage = MutableStateFlow<GraphRagIndexBuildRequest?>(null)
    val graphRagBuildConfirmationMessage: StateFlow<GraphRagIndexBuildRequest?> = _graphRagBuildConfirmationMessage.asStateFlow()
    private val _ragIndexBuildConfirmationMessage = MutableStateFlow<GraphRagIndexBuildRequest?>(null)
    val ragIndexBuildConfirmationMessage: StateFlow<GraphRagIndexBuildRequest?> = _ragIndexBuildConfirmationMessage.asStateFlow()
    private val _contextNotices = MutableStateFlow<Map<Uuid, ContextNoticeDecision>>(emptyMap())
    val contextNotices: StateFlow<Map<Uuid, ContextNoticeDecision>> = _contextNotices.asStateFlow()
    private val _contextCompactionProgress = MutableStateFlow<Map<Uuid, Float>>(emptyMap())
    val contextCompactionProgress: StateFlow<Map<Uuid, Float>> = _contextCompactionProgress.asStateFlow()
    private val _autoCompactStates = MutableStateFlow<Map<Uuid, ConversationAutoCompactState>>(emptyMap())
    val autoCompactStates: StateFlow<Map<Uuid, ConversationAutoCompactState>> = _autoCompactStates.asStateFlow()
    private val momoProactiveJobs = mutableMapOf<Uuid, Job>()
    private val momoProactiveLastTriggeredAt = mutableMapOf<Uuid, Long>()
    private val momoProactiveLastFingerprint = mutableMapOf<Uuid, String>()
    private val momoProactiveLastForegroundApp = MutableStateFlow("")
    private val momoProactiveLastUserInteractionAt = AtomicLong(System.currentTimeMillis())
    private val momoProactiveEnabled = AtomicBoolean(false)
    private val localDisplayCleanupStarted = AtomicBoolean(false)
    private val localDisplayRecorder = VirtualDisplayRecorder(context)
    private val mcpConnectionSemaphore = kotlinx.coroutines.sync.Semaphore(MAX_MCP_CONNECTIONS)
    private val localToolApprovalMutex = Mutex()
    private val localToolApprovalRequests = mutableMapOf<String, LocalToolApprovalRequest>()
    private val _localToolApprovalRequest = MutableStateFlow<LocalToolApprovalRequest?>(null)
    val localToolApprovalRequest: StateFlow<LocalToolApprovalRequest?> = _localToolApprovalRequest.asStateFlow()
    private val _localToolApprovalMessage = MutableStateFlow<String?>(null)
    val localToolApprovalMessage: StateFlow<String?> = _localToolApprovalMessage.asStateFlow()
    private val _mcpServerStatus = MutableStateFlow<Map<Uuid, McpServerStatus>>(emptyMap())
    val mcpServerStatus: StateFlow<Map<Uuid, McpServerStatus>> = _mcpServerStatus.asStateFlow()
    private val _mcpServerConnectionState = MutableStateFlow<Map<Uuid, ConnectionState>>(emptyMap())
    val mcpServerConnectionState: StateFlow<Map<Uuid, ConnectionState>> = _mcpServerConnectionState.asStateFlow()
    private val _mcpServerToolDescriptors = MutableStateFlow<Map<Uuid, List<McpToolDescriptor>>>(emptyMap())
    val mcpServerToolDescriptors: StateFlow<Map<Uuid, List<McpToolDescriptor>>> = _mcpServerToolDescriptors.asStateFlow()
    private val _mcpServerErrors = MutableStateFlow<Map<Uuid, String?>>(emptyMap())
    val mcpServerErrors: StateFlow<Map<Uuid, String?>> = _mcpServerErrors.asStateFlow()
    private val _sshStatus = MutableStateFlow(SshStatus())
    val sshStatus: StateFlow<SshStatus> = _sshStatus.asStateFlow()
    private val _shizukuStatus = MutableStateFlow(ShizukuStatus())
    val shizukuStatus: StateFlow<ShizukuStatus> = _shizukuStatus.asStateFlow()
    private val _trimeStatus = MutableStateFlow(TrimeStatus())
    val trimeStatus: StateFlow<TrimeStatus> = _trimeStatus.asStateFlow()
    private val _virtualDisplayStatus = MutableStateFlow(VirtualDisplayStatus())
    val virtualDisplayStatus: StateFlow<VirtualDisplayStatus> = _virtualDisplayStatus.asStateFlow()
    private val _localMcpServerStatus = MutableStateFlow<Map<Uuid, McpServerRuntimeState>>(emptyMap())
    val localMcpServerStatus: StateFlow<Map<Uuid, McpServerRuntimeState>> = _localMcpServerStatus.asStateFlow()
    private val _localToolApprovalPending = MutableStateFlow(false)
    val localToolApprovalPending: StateFlow<Boolean> = _localToolApprovalPending.asStateFlow()

    init {
        appScope.launch {
            initializeConversations()
        }
        appScope.launch {
            settingsStore.settingsFlow.collect { settings ->
                momoProactiveEnabled.set(settings.momoProactiveEnabled)
            }
        }
        appScope.launch {
            sshManager.status.collect { _sshStatus.value = it }
        }
        appScope.launch {
            shizukuManager.status.collect { _shizukuStatus.value = it }
        }
        appScope.launch {
            trimeController.status.collect { _trimeStatus.value = it }
        }
        appScope.launch {
            virtualDisplayManager.status.collect { _virtualDisplayStatus.value = it }
        }
        appScope.launch {
            mcpManager.serverStatus.collect { _mcpServerStatus.value = it }
        }
        appScope.launch {
            mcpManager.serverConnectionState.collect { _mcpServerConnectionState.value = it }
        }
        appScope.launch {
            mcpManager.serverToolDescriptors.collect { _mcpServerToolDescriptors.value = it }
        }
        appScope.launch {
            mcpManager.serverErrors.collect { _mcpServerErrors.value = it }
        }
        appScope.launch {
            localMcpServerManager.serverStatus.collect { states ->
                _localMcpServerStatus.value = states
            }
        }
    }

    suspend fun getConversation(id: Uuid): Conversation? = conversationRepository.getConversation(id)

    fun observeConversation(id: Uuid): Flow<Conversation?> = conversationRepository.observeConversation(id)

    fun observeMessages(conversationId: Uuid): Flow<List<MessageNode>> = messagesRepository.observeMessages(conversationId)

    suspend fun selectMessage(conversationId: Uuid, messageId: Uuid) {
        conversationRepository.updateSelectedMessage(conversationId, messageId)
    }

    suspend fun createConversation(assistantId: Uuid): Uuid = conversationRepository.createConversation(assistantId)

    suspend fun deleteConversation(id: Uuid) {
        conversationJobs[id]?.cancel()
        autoCompactionJobs[id]?.cancel()
        agenticLoopJobs[id]?.cancel()
        ragIndexStore.indexDirForConversation(id).deleteRecursivelyIfExists()
        graphRagStore.indexDirForConversation(id).deleteRecursivelyIfExists()
        conversationRepository.deleteConversation(id)
        _conversations.update { list -> list.filterNot { it.id == id } }
    }

    suspend fun deleteMessage(conversationId: Uuid, messageId: Uuid) {
        messagesRepository.deleteMessage(conversationId, messageId)
    }

    suspend fun regenerateMessage(conversationId: Uuid, messageId: Uuid, assistant: Assistant, enableTools: Boolean) {
        // implementation intentionally left unchanged
    }
}
