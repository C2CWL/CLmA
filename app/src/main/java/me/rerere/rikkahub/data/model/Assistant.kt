package me.rerere.rikkahub.data.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.core.ReasoningLevel
import me.rerere.rikkahub.data.ai.tools.LenientLocalToolListSerializer
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.ai.tools.ToolSurfaceMode
import me.rerere.rikkahub.utils.SimpleCache
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

@Serializable
data class Assistant(
    val id: Uuid = Uuid.random(),
    val chatModelId: Uuid? = null, // 如果为null, 使用全局默认模型
    val name: String = "",
    val avatar: Avatar = Avatar.Dummy,
    val useAssistantAvatar: Boolean = false, // 使用助手头像替代模型头像
    val tags: List<Uuid> = emptyList(),
    val systemPrompt: String = "",
    val temperature: Float? = null,
    val topP: Float? = null,
    // 上下文消息条数上限, 超出后阶梯式截断; 0 表示不限制
    val contextMessageLimit: Int = 0,
    val streamOutput: Boolean = true,
    val enableMemory: Boolean = false,
    val useGlobalMemory: Boolean = false, // 使用全局共享记忆而非助手隔离记忆
    val enableRecentChatsReference: Boolean = false,
    val messageTemplate: String = "{{ message }}",
    val presetMessages: List<UIMessage> = emptyList(),
    val quickMessageIds: Set<Uuid> = emptySet(),
    val regexes: List<AssistantRegex> = emptyList(),
    val reasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
    val maxTokens: Int? = null,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBodies: List<CustomBody> = emptyList(),
    val mcpServers: Set<Uuid> = emptySet(),
    @Serializable(with = LenientLocalToolListSerializer::class)
    val localTools: List<LocalToolOption> = listOf(LocalToolOption.TimeInfo),
    val enableWebSearch: Boolean = false, // 网络搜索开关(每个助手独立)
    val workspaceId: Uuid? = null,
    val background: String? = null, // 聊天页背景图地址(本地文件 URI 或网络 URL), 为 null 时无背景
    val backgroundOpacity: Float = 1.0f, // 背景图不透明度(0~1)
    val useGradientBackground: Boolean = false, // 开启后聊天页使用动态渐变背景
    val modeInjectionIds: Set<Uuid> = emptySet(),      // 关联的模式注入 ID
    val lorebookIds: Set<Uuid> = emptySet(),            // 关联的 Lorebook ID
    val enabledSkills: Set<String> = emptySet(),        // 启用的 skill 名称列表
    val enableTimeReminder: Boolean = false,            // 时间间隔提醒注入
    val timeReminderIntervalMinutes: Int = 60,          // 时间提醒间隔（分钟，至少 1 分钟）
    // Phase 11 — Sub-agents settings. Defaults to "inherit from main" (null model id +
    // empty system prompt → built-in focused-sub-agent prompt). Each assistant has its
    // own concurrency cap; we hard-cap globally at 16 across all assistants in the engine.
    val subAgentModelId: Uuid? = null,
    val subAgentSystemPrompt: String = "",
    val maxConcurrentSubAgents: Int = 3,
    // Phase 15 — Per-task token budget. Both null = no budget enforcement. The LLM
    // checks via `check_token_usage`; auto-stop integration into GenerationHandler is
    // Phase 15.5 follow-up.
    val tokenBudgetSoftCap: Int? = null,
    val tokenBudgetHardCap: Int? = null,
    // Phase 16 — Fast-path router. Off by default per spec. When ON, ChatService runs
    // FastPathRouter.route() on the user's message before firing the LLM; matched intents
    // execute the matching tool directly and skip the LLM. Conservative matching — falls
    // through to the LLM whenever in doubt. Per-tool HARDLINE / approval still apply at
    // the dispatch level; v1 only matches read-only tools so approval is a non-issue.
    val fastPathRouterEnabled: Boolean = false,
    val allowConversationSystemPrompt: Boolean = false, // 允许对话单独重写 system prompt
    val allowConversationPromptInjection: Boolean = false, // 允许对话单独绑定提示词注入
    // Phase 17 (⑥) — Optional per-tool-result token budget. null = upstream behaviour unchanged
    // (the fixed 32 KB character gate in GenerationLoop.maybeTruncateToolOutput). When set,
    // oversized tool results are trimmed to roughly this many tokens keeping head + tail, and
    // the full text is still spilled to /tool_outputs/ for on-demand re-reading.
    // Appended last on purpose: a new field may never shift the position of an existing one.
    val toolResultMaxTokens: Int? = null,
    // Phase 17 (②) — Model-initiated context compaction. Off by default. When ON, this
    // assistant gets a `compact_context` tool that runs the SAME summariser as the manual
    // "compress context" action (ChatService.compressConversation → ContextCompactionPlanner),
    // so the compaction logic itself lives in exactly one place. The shortened context applies
    // from the next turn; the turn that triggered it keeps its raw history.
    // Appended last on purpose: a new field may never shift the position of an existing one.
    val enableCompactContextTool: Boolean = false,
    // Phase 17 (①) — Progressive tool exposure. DIRECT (default) keeps the historical behaviour:
    // every enabled MCP tool's schema is injected on every request. PROGRESSIVE_CATALOG replaces
    // the MCP portion of the tool list with the small `tool_search` / `tool_open` pair, and only
    // activated schemas get injected (from the following turn onwards). Local tools are never
    // catalogued — only MCP tools — so the blast radius of this phase stays small.
    // Appended last on purpose: a new field may never shift the position of an existing one.
    val toolSurfaceMode: ToolSurfaceMode = ToolSurfaceMode.DIRECT,
    // T-04 / (4) - let `subagent_dispatch` carry a slice of THIS conversation to the
    // sub-agent. Off by default, and the flag gates the tool SCHEMA as well as the
    // behaviour: with it off, the subagent_dispatch definition is byte-identical to the
    // pre-T-04 one (prompt-cache safe), and the model has no parameter to fill in.
    val enableSubAgentContextRefs: Boolean = false,
    // T-09 / (8) — freeze the tool surface of sub-agents this assistant dispatches. Off by
    // default. When ON, every sub-agent conversation is filtered down to the headless-safe
    // surface (no `subagent_*` handles, no per-call-approval tools such as eval_javascript /
    // mcp_add / keystore_*, no device-UI-bound tools), and `subagent_dispatch`'s `tools`
    // parameter finally does something: it narrows that surface further. This matters because a
    // headless run auto-approves every tool it is handed, so an unfrozen child could run a tool
    // that is documented to require a per-call user confirmation.
    // With the flag off no freeze record is ever written and ChatService hands every
    // conversation the exact same List<Tool> it built before T-09 (identity filter).
    // Appended last on purpose: a new field may never shift the position of an existing one.
    val enableSubAgentToolSurface: Boolean = false,
    // T-05 / (3) - retry a transient failure of an idempotent READ-ONLY tool at the execution
    // layer (socket timeout, IO error, retryable HTTP status, or a `{"error":"timeout"}`
    // result envelope) with a short exponential backoff, instead of handing the failure back
    // to the model for a whole extra round-trip. Only tools whose every call is a pure read
    // are eligible, so a retry can never duplicate a side effect. Off by default: while it is
    // off the tool-execution path is byte-identical to before - one attempt, no delay.
    // Appended last on purpose: a new field may never shift the position of an existing one.
    val enableToolExecutionRetry: Boolean = false,
    // T-06 / (7) - cold memory: a Markdown knowledge base on disk that the model searches and
    // reads on demand (memory_index / memory_read / memory_write), instead of the always-injected
    // short records kept by `memory_tool`. Off by default; the two tools are only registered when
    // this is on AND workspaceId is bound AND coldMemoryDir is a usable directory inside it.
    // Appended last on purpose: a new field may never shift the position of an existing one.
    val coldMemoryEnabled: Boolean = false,
    // T-06 / (7) - the cold-memory directory INSIDE the bound workspace, in the same form the
    // working-directory picker writes: "/workspace/notes/memory". Null means "not configured",
    // which registers no tools at all.
    // Appended last on purpose: a new field may never shift the position of an existing one.
    val coldMemoryDir: String? = null,
    // P2-02 - per-tool opt-out. `localTools` toggles a whole LocalToolOption group; this set
    // removes individual tools (matched on their exact `Tool.name`) from an otherwise-enabled
    // group, at the single assembly point (`LocalTools.getTools`). Empty (the default) is a
    // strict no-op: the assembled surface AND the persisted JSON stay exactly as they were,
    // which is why the field is `@EncodeDefault(NEVER)` under the store's `encodeDefaults`.
    // Appended last on purpose: a new field may never shift the position of an existing one.
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val disabledLocalTools: Set<String> = emptySet(),
    // P2-07 — per-orchestration token budget for THIS assistant's dispatch trees.
    // One "orchestration" is a parent turn plus every sub-agent run it fans out; the ledger
    // sums all of them by `usage_records.parent_run_id` and P2-13 refuses a further dispatch
    // once the total reaches this ceiling. Null (the default) = unlimited (D8); an expert
    // overrides it for its own run via `AgentDefinition.tokenBudget`. @EncodeDefault(NEVER)
    // keeps an assistant that never touched this setting byte-identical in the store.
    // Appended last on purpose: a new field may never shift the position of an existing one.
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val orchestrationTokenBudget: Long? = null,
    // 上下文预算提醒阈值: 占**有效模型窗口**的百分比(解析见 Settings.getEffectiveContextLength)。
    // 当本助手启用了 compact_context 工具时, 上下文估算达到该比例就在请求里注入 <context_reminder>,
    // 让模型赶在自动压缩之前自行决定是否压缩。默认 70% —— 刻意低于自动压缩的默认 80%, 否则同一个
    // 阈值下客户端会抢先强制压缩, 提醒就没有意义了 (@EncodeDefault(NEVER) 让没动过该设置的助手在
    // store 里保持字节不变)。
    // Appended last on purpose: a new field may never shift the position of an existing one.
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val contextBudgetReminderPercent: Int = 70,
)

@Serializable
data class QuickMessage(
    val id: Uuid = Uuid.random(),
    val title: String = "",
    val content: String = "",
)

@Serializable
data class AssistantMemory(
    val id: Int,
    val content: String = "",
)

@Serializable
enum class AssistantAffectScope {
    USER,
    ASSISTANT,
}

@Serializable
data class AssistantRegex(
    val id: Uuid,
    val name: String = "",
    val enabled: Boolean = true,
    val findRegex: String = "", // 正则表达式
    val replaceString: String = "", // 替换字符串
    val affectingScope: Set<AssistantAffectScope> = setOf(),
    val visualOnly: Boolean = false, // 是否仅在视觉上影响
)

// 流式输出时每个chunk都会调用replaceRegexes，正则必须缓存编译结果，
// 否则长回复期间会重复编译上万次；编译失败也缓存，避免反复构造异常
private val regexCache = SimpleCache.builder<String, Result<Regex>>()
    .expireAfterWrite(10, TimeUnit.MINUTES)
    .build()

private fun compileRegexCached(pattern: String): Regex? {
    regexCache.getIfPresent(pattern)?.let { return it.getOrNull() }
    val result = runCatching { Regex(pattern) }.onFailure { it.printStackTrace() }
    regexCache.put(pattern, result)
    return result.getOrNull()
}

fun String.replaceRegexes(
    assistant: Assistant?,
    scope: AssistantAffectScope,
    visual: Boolean = false
): String {
    if (assistant == null) return this
    if (assistant.regexes.isEmpty()) return this
    return assistant.regexes.fold(this) { acc, regex ->
        if (regex.enabled && regex.visualOnly == visual && regex.affectingScope.contains(scope)) {
            val compiled = compileRegexCached(regex.findRegex) ?: return@fold acc
            try {
                acc.replace(
                    regex = compiled,
                    replacement = regex.replaceString,
                )
            } catch (e: Exception) {
                e.printStackTrace()
                // 替换字符串可能引用不存在的分组，失败时返回原字符串
                acc
            }
        } else {
            acc
        }
    }
}

/**
 * 注入位置
 */
@Serializable
enum class InjectionPosition {
    @SerialName("before_system_prompt")
    BEFORE_SYSTEM_PROMPT,   // 系统提示词之前

    @SerialName("after_system_prompt")
    AFTER_SYSTEM_PROMPT,    // 系统提示词之后（最常用）

    @SerialName("top_of_chat")
    TOP_OF_CHAT,            // 对话最开头（第一条用户消息之前）

    @SerialName("bottom_of_chat")
    BOTTOM_OF_CHAT,         // 最新消息之前（当前用户输入之前）

    @SerialName("at_depth")
    AT_DEPTH,               // 在指定深度位置插入（从最新消息往前数）
}

/**
 * 提示词注入
 *
 * - ModeInjection: 基于模式开关的注入（如学习模式）
 * - RegexInjection: 基于正则匹配的注入（Lorebook）
 */
@Serializable
sealed class PromptInjection {
    abstract val id: Uuid
    abstract val name: String
    abstract val enabled: Boolean
    abstract val priority: Int
    abstract val position: InjectionPosition
    abstract val content: String
    abstract val injectDepth: Int  // 当 position 为 AT_DEPTH 时使用，表示从最新消息往前数的位置
    abstract val role: MessageRole  // 注入角色：USER 或 ASSISTANT

    /**
     * 模式注入 - 基于开关状态触发
     */
    @Serializable
    @SerialName("mode")
    data class ModeInjection(
        override val id: Uuid = Uuid.random(),
        override val name: String = "",
        override val enabled: Boolean = true,
        override val priority: Int = 0,
        override val position: InjectionPosition = InjectionPosition.AFTER_SYSTEM_PROMPT,
        override val content: String = "",
        override val injectDepth: Int = 4,
        override val role: MessageRole = MessageRole.USER,
    ) : PromptInjection()

    /**
     * 正则注入 - 基于内容匹配触发（世界书）
     */
    @Serializable
    @SerialName("regex")
    data class RegexInjection(
        override val id: Uuid = Uuid.random(),
        override val name: String = "",
        override val enabled: Boolean = true,
        override val priority: Int = 0,
        override val position: InjectionPosition = InjectionPosition.AFTER_SYSTEM_PROMPT,
        override val content: String = "",
        override val injectDepth: Int = 4,
        override val role: MessageRole = MessageRole.USER,
        val keywords: List<String> = emptyList(),  // 触发关键词
        val useRegex: Boolean = false,             // 是否使用正则匹配
        val caseSensitive: Boolean = false,        // 大小写敏感
        val scanDepth: Int = 4,                    // 扫描最近N条消息
        val constantActive: Boolean = false,       // 常驻激活（无需匹配）
    ) : PromptInjection()
}

/**
 * Lorebook - 组织管理多个 RegexInjection
 */
@Serializable
data class Lorebook(
    val id: Uuid = Uuid.random(),
    val name: String = "",
    val description: String = "",
    val enabled: Boolean = true,
    val entries: List<PromptInjection.RegexInjection> = emptyList(),
)

/**
 * 检查 RegexInjection 是否被触发
 *
 * @param context 要扫描的上下文文本
 * @return 是否触发
 */
fun PromptInjection.RegexInjection.isTriggered(context: String): Boolean {
    if (!enabled) return false
    if (constantActive) return true
    if (keywords.isEmpty()) return false

    return keywords.any { keyword ->
        if (useRegex) {
            try {
                val options = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
                Regex(keyword, options).containsMatchIn(context)
            } catch (e: Exception) {
                false
            }
        } else {
            if (caseSensitive) {
                context.contains(keyword)
            } else {
                context.contains(keyword, ignoreCase = true)
            }
        }
    }
}

/**
 * 从消息列表中提取用于匹配的上下文文本
 *
 * @param messages 消息列表
 * @param scanDepth 扫描深度（最近N条消息）
 * @return 拼接的文本内容
 */
fun extractContextForMatching(
    messages: List<UIMessage>,
    scanDepth: Int
): String {
    return messages
        .takeLast(scanDepth)
        .joinToString("\n") { it.toText() }
}

/**
 * 获取所有被触发的注入，按优先级排序
 *
 * @param injections 所有注入规则
 * @param context 上下文文本
 * @return 被触发的注入列表，按优先级降序排列
 */
fun getTriggeredInjections(
    injections: List<PromptInjection.RegexInjection>,
    context: String
): List<PromptInjection.RegexInjection> {
    return injections
        .filter { it.isTriggered(context) }
        .sortedByDescending { it.priority }
}
