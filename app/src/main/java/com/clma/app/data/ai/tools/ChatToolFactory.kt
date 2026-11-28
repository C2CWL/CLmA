package com.clma.app.data.ai.tools

import com.clma.app.data.repository.memoryScopeId

import com.clma.app.data.botmode.libraryScopeOf
import com.clma.app.data.plugins.PluginRepository
import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import com.clma.ai.core.InputSchema
import com.clma.ai.core.Tool
import com.clma.ai.ui.UIMessagePart
import com.clma.ai.provider.Model
import com.clma.ai.provider.ProviderSetting
import com.clma.ai.provider.effectiveBuiltInSearch
import com.clma.app.data.ai.mcp.McpManager
import com.clma.app.data.ai.ToolSurfaceResolver
import com.clma.app.data.ai.mcp.McpTool
import com.clma.app.data.datastore.Settings
import com.clma.app.data.datastore.findProvider
import com.clma.app.data.files.SkillManager
import com.clma.app.data.model.Assistant
import com.clma.app.data.repository.ConversationRepository
import com.clma.app.data.repository.MemoryRepository
import com.clma.app.data.repository.WorkspaceRepository
import com.clma.workspace.WorkspaceShellStatus
import kotlin.uuid.Uuid

private const val TAG = "ChatToolFactory"

internal fun shouldUseExternalWebSearch(assistant: Assistant, model: Model, provider: ProviderSetting?): Boolean =
    assistant.enableWebSearch && !model.effectiveBuiltInSearch(provider)

class InvalidMcpServerNamesException(val names: List<String>) :
    IllegalStateException("Invalid MCP server names: ${names.joinToString(", ")}")

/** Creates the complete tool set for one generation run, including approval resumption. */
class ChatToolFactory(
    private val json: Json,
    private val memoryRepository: MemoryRepository,
    private val conversationRepository: ConversationRepository,
    private val localTools: LocalTools,
    private val mcpManager: McpManager,
    private val skillManager: SkillManager,
    private val workspaceRepository: WorkspaceRepository,
    private val pluginRepository: PluginRepository,
) {
    suspend fun createTools(
        settings: Settings,
        assistant: Assistant,
        model: Model,
        workspaceCwd: String? = null,
    ): List<Tool> = buildList {
        if (assistant.enableMemory) {
            val memoryAssistantId = assistant.memoryScopeId()
            addAll(
                buildMemoryTools(
                    json = json,
                    existing = { memoryRepository.getMemoriesOfAssistant(memoryAssistantId) },
                    onCreation = { content -> memoryRepository.addMemory(memoryAssistantId, content) },
                    onUpdate = { id, content -> memoryRepository.updateContent(id, content) },
                    onDelete = { id -> memoryRepository.deleteMemory(id) },
                )
            )
        }
        if (shouldUseExternalWebSearch(assistant, model, model.findProvider(settings.providers))) {
            addAll(createSearchTools(settings))
        }
        addAll(ToolSurfaceResolver.resolve(localTools, assistant, ToolSurfaceResolver.contextless))
        if (assistant.enableRecentChatsReference) {
            addAll(createConversationTools(conversationRepository, assistant.id) { settings.libraryScopeOf(assistant.id) })
        }
        addAll(createWorkspaceToolsIfReady(assistant.workspaceId?.toString(), workspaceCwd))
        if (assistant.enabledSkills.isNotEmpty()) {
            addAll(
                createSkillTools(
                    enabledSkills = assistant.enabledSkills,
                    allSkills = skillManager.listSkills(),
                )
            )
        }

        val mcpTools = validateMcpServerNames()
        mcpTools.forEach { (serverId, serverName, tool) ->
            add(
                Tool(
                    name = "mcp__${serverName}__${tool.name}",
                    description = tool.description ?: "",
                    parameters = { tool.inputSchema },
                    needsApproval = { tool.needsApproval },
                    execute = { mcpManager.callTool(serverId, tool.name, it.jsonObject) },
                )
            )
        }

        // Native Android plugins: every enabled plugin tool is namespaced by plugin id.
        pluginRepository.enabledPluginsWithTools().forEach { (pluginEntry, pluginTool) ->
            val descriptor = pluginTool.descriptor
            add(
                Tool(
                    name = "plugin__${toolSegment(pluginEntry.id)}__${toolSegment(descriptor.name)}",
                    description = "[${pluginEntry.name}] ${descriptor.description}",
                    parameters = {
                        runCatching {
                            val schema = json.parseToJsonElement(descriptor.inputSchemaJson).jsonObject
                            InputSchema.Obj(
                                properties = schema["properties"]?.jsonObject ?: JsonObject(emptyMap()),
                                required = schema["required"]?.jsonArray
                                    ?.mapNotNull { (it as? JsonPrimitive)?.content }
                                    ?.takeIf { it.isNotEmpty() },
                            )
                        }.getOrElse { InputSchema.Obj(properties = JsonObject(emptyMap())) }
                    },
                    needsApproval = { descriptor.needsApproval },
                    execute = { input ->
                        val result = runCatching { pluginTool.execute(input.toString()) }
                            .getOrElse { error ->
                                "Plugin tool '${descriptor.name}' failed: ${error.message ?: "unknown error"}"
                            }
                        listOf(UIMessagePart.Text(result))
                    },
                )
            )
        }
    }

    private fun toolSegment(value: String): String =
        value.map { ch ->
            if (ch.isLetterOrDigit() || ch == '_' || ch == '-') ch else '_'
        }.joinToString("").ifEmpty { "unnamed" }

    /**
     * Validates that every enabled MCP server's name can form a valid `mcp__<name>__tool`
     * surface, throwing [InvalidMcpServerNamesException] otherwise. Returns the available
     * tools so callers that need them (like [createTools]) don't have to re-fetch them.
     */
    suspend fun validateMcpServerNames(): List<Triple<Uuid, String, McpTool>> {
        val mcpTools = mcpManager.getAllAvailableTools()
        val invalidNames = mcpTools
            .map { it.second }
            .distinct()
            .filter { name -> name.isEmpty() || !name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' } }
        if (invalidNames.isNotEmpty()) {
            throw InvalidMcpServerNamesException(invalidNames)
        }
        return mcpTools
    }

    private suspend fun createWorkspaceToolsIfReady(workspaceId: String?, cwd: String?): List<Tool> {
        if (workspaceId.isNullOrBlank()) return emptyList()
        val workspace = workspaceRepository.getById(workspaceId) ?: return emptyList()
        if (workspace.shellStatus != WorkspaceShellStatus.READY.name) {
            Log.d(
                TAG,
                "createWorkspaceToolsIfReady: skip workspace tools, workspace=$workspaceId, status=${workspace.shellStatus}"
            )
            return emptyList()
        }
        return createWorkspaceTools(workspaceId, workspaceRepository, cwd)
    }
}
