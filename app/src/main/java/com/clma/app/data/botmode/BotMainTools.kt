package com.clma.app.data.botmode

import com.clma.ai.core.InputSchema
import com.clma.ai.core.Tool
import com.clma.ai.provider.ModelType
import com.clma.ai.ui.UIMessagePart
import com.clma.app.connectors.ConnectorWriteApproval
import com.clma.app.data.datastore.Settings
import com.clma.app.data.model.Assistant
import com.clma.app.data.model.AssistantLibrary
import com.clma.app.data.model.AssistantMemory
import com.clma.app.data.model.isBot
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.uuid.Uuid

/**
 * Where the main assistant's bot-management tools read and write. Production wiring
 * (ChatService) backs it with SettingsStore + MemoryRepository; tests use an in-memory fake.
 */
interface BotMainToolStore {
    suspend fun settings(): Settings

    /** Atomically transforms the stored settings and returns the result. */
    suspend fun updateSettings(transform: (Settings) -> Settings): Settings

    suspend fun memories(botId: Uuid): List<AssistantMemory>
    suspend fun addMemory(botId: Uuid, content: String): AssistantMemory
    suspend fun deleteMemory(memoryId: Int)
}

/**
 * Bot-mode main assistant tools: list bots, create a bot, change a bot's system prompt, and
 * write / delete a bot's memory.
 *
 * Rules, all checked again at execution time (the main pointer can move between the turn that
 * proposed a call and the moment the user approves it):
 * - Only the CURRENT main bot may run them ([isMainAssistant]); when the main pointer moves to
 *   another bot, the permission moves with it.
 * - Only the Bot library is visible or writable. Classic assistants are never listed and every
 *   lookup of a classic id answers `bot_not_found`, exactly as for an unknown id.
 * - Every write ([WRITE_TOOL_NAMES]) is a per-call approval: it is in
 *   [ConnectorWriteApproval.WRITE_TOOL_NAMES], so GenerationLoop always parks it as Pending,
 *   Auto mode / chat-wide grants / headless runs cannot approve it, and the tool body itself
 *   refuses to write without the single-use grant the user's 「仅此一次」 tap creates. A denied
 *   call never reaches the body.
 */
object BotMainTools {
    const val LIST = "bot_list"
    const val CREATE = "bot_create"
    const val UPDATE_PROMPT = "bot_update_prompt"
    const val MEMORY_WRITE = "bot_memory_write"
    const val MEMORY_DELETE = "bot_memory_delete"

    val WRITE_TOOL_NAMES: Set<String> = setOf(CREATE, UPDATE_PROMPT, MEMORY_WRITE, MEMORY_DELETE)
    val ALL_TOOL_NAMES: Set<String> = WRITE_TOOL_NAMES + LIST

    const val MAX_NAME_CHARS = 60
    const val MAX_PROMPT_CHARS = 20_000
    const val MAX_MEMORY_CHARS = 2_000

    /** True when [assistant] is the Bot library's current main bot in [settings]. */
    fun isMainAssistant(settings: Settings, assistant: Assistant?): Boolean =
        assistant != null && assistant.isBot && settings.mainBot()?.id == assistant.id

    /** Short guide added to the main bot's system prompt (only when it has the tools). */
    val SYSTEM_PROMPT: String = """
        <bot_management>
        You are the user's MAIN assistant in CLmA Bot mode. You can manage the other bots in the
        Bot library with bot_list, bot_create, bot_update_prompt, bot_memory_write and
        bot_memory_delete.
        - Call bot_list first to get bot ids, their memory ids and the available model ids.
        - Every create / prompt change / memory write / memory delete shows the user an approval
          card and runs only if they approve that one call. If it comes back denied, do not retry
          the same call; ask the user what they want instead.
        - Only Bot-mode bots exist for these tools. Classic assistants cannot be seen or changed.
        - Write a complete system prompt when you create a bot or change one: the new prompt
          replaces the old one entirely.
        </bot_management>
    """.trimIndent()

    fun build(
        callerId: Uuid,
        store: BotMainToolStore,
        requireGrant: suspend (name: String, args: JsonElement) -> Boolean = ConnectorWriteApproval::isGranted,
    ): List<Tool> = listOf(
        Tool(
            name = LIST,
            description = "List the bots in the Bot-mode library (never classic assistants): id, name, " +
                "whether it is the main bot, its model, a preview of its system prompt and its memory " +
                "records with ids. Also returns the chat models a new bot can use. Read-only.",
            parameters = { InputSchema.Obj(properties = buildJsonObject { }, required = emptyList()) },
            systemPrompt = { _, _ -> SYSTEM_PROMPT },
            execute = { guarded(callerId, store, LIST, it, requireGrant) { s, _ -> listBots(s, store) } },
        ),
        Tool(
            name = CREATE,
            description = "Create a new bot in the Bot-mode library. Needs the user's approval for this " +
                "call. `name` is required; `system_prompt` is the bot's full system prompt; `model_id` " +
                "is optional (an id from bot_list's `models`, else the default model is used).",
            parameters = {
                schema(required = listOf("name")) {
                    str("name", "Display name of the new bot (max $MAX_NAME_CHARS characters)")
                    str("system_prompt", "The bot's full system prompt")
                    str("model_id", "Optional chat model id from bot_list's models")
                }
            },
            needsApproval = { true },
            execute = { guarded(callerId, store, CREATE, it, requireGrant) { _, args -> createBot(store, args) } },
        ),
        Tool(
            name = UPDATE_PROMPT,
            description = "Replace a bot's system prompt with `system_prompt` (the whole new text). " +
                "Needs the user's approval for this call. `bot_id` comes from bot_list.",
            parameters = {
                schema(required = listOf("bot_id", "system_prompt")) {
                    str("bot_id", "Id of the bot to change, from bot_list")
                    str("system_prompt", "The complete new system prompt")
                }
            },
            needsApproval = { true },
            execute = { guarded(callerId, store, UPDATE_PROMPT, it, requireGrant) { _, args -> updatePrompt(store, args) } },
        ),
        Tool(
            name = MEMORY_WRITE,
            description = "Add one memory record to a bot's own memory (shown to that bot in later " +
                "chats when its memory is on). Needs the user's approval for this call.",
            parameters = {
                schema(required = listOf("bot_id", "content")) {
                    str("bot_id", "Id of the bot, from bot_list")
                    str("content", "The memory text (max $MAX_MEMORY_CHARS characters)")
                }
            },
            needsApproval = { true },
            execute = { guarded(callerId, store, MEMORY_WRITE, it, requireGrant) { _, args -> writeMemory(store, args) } },
        ),
        Tool(
            name = MEMORY_DELETE,
            description = "Delete one memory record of a bot. `memory_id` must be one of that bot's " +
                "memory ids from bot_list. Needs the user's approval for this call.",
            parameters = {
                schema(required = listOf("bot_id", "memory_id")) {
                    str("bot_id", "Id of the bot, from bot_list")
                    put("memory_id", buildJsonObject {
                        put("type", "integer")
                        put("description", "Memory record id from bot_list")
                    })
                }
            },
            needsApproval = { true },
            execute = { guarded(callerId, store, MEMORY_DELETE, it, requireGrant) { _, args -> deleteMemory(store, args) } },
        ),
    )

    private suspend fun guarded(
        callerId: Uuid,
        store: BotMainToolStore,
        name: String,
        args: JsonElement,
        requireGrant: suspend (String, JsonElement) -> Boolean,
        body: suspend (Settings, JsonObject) -> JsonObject,
    ): List<UIMessagePart> {
        val settings = store.settings()
        val caller = settings.assistants.find { it.id == callerId }
        val result = when {
            !isMainAssistant(settings, caller) -> error(
                "not_main_assistant",
                "Only the current main bot can manage bots. The user may have made another bot the main one.",
            )
            name in WRITE_TOOL_NAMES && !requireGrant(name, args) -> error(
                "approval_required",
                "Nothing was changed. This call needs the user's approval for this one call.",
            )
            else -> runCatching { body(settings, args as? JsonObject ?: JsonObject(emptyMap())) }
                .getOrElse { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    error("invalid_tool_args", e.message ?: "invalid arguments")
                }
        }
        return listOf(UIMessagePart.Text(result.toString()))
    }

    private suspend fun listBots(settings: Settings, store: BotMainToolStore): JsonObject = buildJsonObject {
        val mainId = settings.mainBot()?.id
        put("bots", buildJsonArray {
            settings.botAssistants().forEach { bot ->
                add(buildJsonObject {
                    put("id", bot.id.toString())
                    put("name", bot.name)
                    put("is_main", bot.id == mainId)
                    put("model_id", bot.chatModelId?.toString())
                    put("system_prompt_preview", bot.systemPrompt.take(300))
                    put("memory_enabled", bot.enableMemory && !bot.useGlobalMemory)
                    put("memories", buildJsonArray {
                        store.memories(bot.id).forEach { m ->
                            add(buildJsonObject {
                                put("id", m.id)
                                put("content", m.content)
                            })
                        }
                    })
                })
            }
        })
        put("models", buildJsonArray {
            chatModels(settings).forEach { (id, label) ->
                add(buildJsonObject {
                    put("id", id.toString())
                    put("name", label)
                })
            }
        })
    }

    private fun chatModels(settings: Settings): List<Pair<Uuid, String>> =
        settings.providers.filter { it.enabled }.flatMap { p ->
            p.models.filter { it.type == ModelType.CHAT }.map { m -> m.id to m.displayName.ifBlank { m.modelId } }
        }

    private suspend fun createBot(store: BotMainToolStore, args: JsonObject): JsonObject {
        val name = args.text("name")?.trim().orEmpty()
        require(name.isNotEmpty()) { "name is required" }
        require(name.length <= MAX_NAME_CHARS) { "name is longer than $MAX_NAME_CHARS characters" }
        val prompt = args.text("system_prompt")?.trim().orEmpty()
        require(prompt.length <= MAX_PROMPT_CHARS) { "system_prompt is longer than $MAX_PROMPT_CHARS characters" }
        val modelArg = args.text("model_id")?.trim()?.takeIf { it.isNotEmpty() }
        val modelId = modelArg?.let { raw ->
            val id = runCatching { Uuid.parse(raw) }.getOrNull()
            requireNotNull(id?.takeIf { wanted -> chatModels(store.settings()).any { it.first == wanted } }) {
                "unknown model_id; use an id from bot_list's models"
            }
        }
        val bot = newBot(name = name, systemPrompt = prompt, chatModelId = modelId)
        val saved = store.updateSettings { s -> s.copy(assistants = s.assistants + bot) }
        if (saved.assistants.none { it.id == bot.id }) return error("save_failed", "The bot was not saved. Try again later.")
        return buildJsonObject {
            put("created", true)
            put("id", bot.id.toString())
            put("name", bot.name)
        }
    }

    private suspend fun updatePrompt(store: BotMainToolStore, args: JsonObject): JsonObject {
        val botId = args.botId()
        val prompt = args.text("system_prompt")?.trim() ?: throw IllegalArgumentException("system_prompt is required")
        require(prompt.length <= MAX_PROMPT_CHARS) { "system_prompt is longer than $MAX_PROMPT_CHARS characters" }
        if (store.settings().botAssistants().none { it.id == botId }) return notFound()
        val saved = store.updateSettings { s ->
            s.copy(assistants = s.assistants.map { if (it.id == botId && it.isBot) it.copy(systemPrompt = prompt) else it })
        }
        val stored = saved.botAssistants().find { it.id == botId } ?: return notFound()
        if (stored.systemPrompt != prompt) return error("save_failed", "The prompt was not saved. Try again later.")
        return buildJsonObject {
            put("updated", true)
            put("id", botId.toString())
        }
    }

    private suspend fun writeMemory(store: BotMainToolStore, args: JsonObject): JsonObject {
        val botId = args.botId()
        val content = args.text("content")?.trim().orEmpty()
        require(content.isNotEmpty()) { "content is required" }
        require(content.length <= MAX_MEMORY_CHARS) { "content is longer than $MAX_MEMORY_CHARS characters" }
        val bot = store.settings().botAssistants().find { it.id == botId } ?: return notFound()
        val memory = store.addMemory(bot.id, content)
        return buildJsonObject {
            put("created", true)
            put("bot_id", bot.id.toString())
            put("memory_id", memory.id)
            if (!bot.enableMemory || bot.useGlobalMemory) {
                put("note", "Stored in the bot's own memory. That bot's memory switch is off, so it will see it only after the user turns its memory on.")
            }
        }
    }

    private suspend fun deleteMemory(store: BotMainToolStore, args: JsonObject): JsonObject {
        val botId = args.botId()
        val memoryId = args["memory_id"]?.jsonPrimitive?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }
            ?: throw IllegalArgumentException("memory_id is required")
        val bot = store.settings().botAssistants().find { it.id == botId } ?: return notFound()
        if (store.memories(bot.id).none { it.id == memoryId }) {
            return error("memory_not_found", "That memory id does not belong to this bot. Call bot_list for its ids.")
        }
        store.deleteMemory(memoryId)
        return buildJsonObject {
            put("deleted", true)
            put("bot_id", bot.id.toString())
            put("memory_id", memoryId)
        }
    }

    /** A new Bot-library assistant; shared by the bot list's + button and [CREATE]. */
    fun newBot(name: String, systemPrompt: String, chatModelId: Uuid? = null): Assistant = Assistant(
        name = name.trim(),
        systemPrompt = systemPrompt.trim(),
        chatModelId = chatModelId,
        library = AssistantLibrary.BOT,
    )

    private fun notFound() = error("bot_not_found", "No Bot-mode bot has that id. Call bot_list for ids.")

    private fun error(code: String, detail: String) = buildJsonObject {
        put("error", code)
        put("detail", detail)
    }

    private fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.botId(): Uuid {
        val raw = text("bot_id")?.trim() ?: throw IllegalArgumentException("bot_id is required")
        return runCatching { Uuid.parse(raw) }.getOrNull() ?: throw IllegalArgumentException("bot_id is not a valid id")
    }

    private fun schema(required: List<String>, block: JsonObjectBuilder.() -> Unit) =
        InputSchema.Obj(properties = buildJsonObject(block), required = required)

    private fun JsonObjectBuilder.str(name: String, description: String) {
        put(name, buildJsonObject {
            put("type", "string")
            put("description", description)
        })
    }
}
