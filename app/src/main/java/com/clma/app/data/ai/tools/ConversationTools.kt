package com.clma.app.data.ai.tools

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import com.clma.ai.core.InputSchema
import com.clma.ai.core.Tool
import com.clma.ai.ui.UIMessagePart
import com.clma.app.data.db.fts.MessageSearchSort
import com.clma.app.data.repository.ConversationRepository
import com.clma.app.utils.JsonInstantPretty
import com.clma.app.utils.toLocalDate
import kotlin.uuid.Uuid

/**
 * Tools that let the assistant query the user's past conversations on demand, instead of
 * statically injecting recent chats into the system prompt (which would break prompt caching).
 *
 * Searching is native SQLite FTS5: the message table already carries a full-text index, so a
 * query is a `MATCH` and nothing else. There is no second index to keep in step with it — a
 * message becomes searchable the moment it is written, and keyword behaviour is exactly what the
 * user sees in the conversations UI's own search box.
 */
fun createConversationTools(
    conversationRepo: ConversationRepository,
    assistantId: Uuid,
): List<Tool> = listOf(
    Tool(
        name = "recent_chats",
        description = """
            List the user's recent conversations with you to understand their preferences and ongoing topics.
            Returns conversation titles and the date of last activity, ordered by pinned first then most recently updated.
            Use this when you need quick context about what the user has been discussing lately.
            Only titles and dates are returned; use `conversation_search` to look up the actual content.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("limit", buildJsonObject {
                        put("type", "integer")
                        put(
                            "description",
                            "Maximum number of recent conversations to return (default: 10, max: 30)"
                        )
                    })
                }
            )
        },
        execute = {
            val limit = (it.jsonObject["limit"]?.jsonPrimitive?.intOrNull ?: 10).coerceIn(1, 30)
            val recent = conversationRepo.getRecentConversations(
                assistantId = assistantId,
                limit = limit,
            )
            val payload = buildJsonArray {
                recent.forEach { conversation ->
                    add(buildJsonObject {
                        put("id", conversation.id.toString())
                        put("title", conversation.title.ifBlank { "Untitled" })
                        put("last_chat", conversation.updateAt.toLocalDate())
                    })
                }
            }
            listOf(UIMessagePart.Text(JsonInstantPretty.encodeToString(payload)))
        }
    ),
    Tool(
        name = "conversation_search",
        description = """
            Search the user's past conversations by keyword, and get back the messages that match,
            with the conversation and the date each one came from - including what was said in the
            current conversation a moment ago.

            Matching is full-text keyword search (SQLite FTS5): the query must share words with the
            message text. Use the terms that were actually said - command names, error strings,
            project names - rather than a paraphrase of the topic. English words match
            case-insensitively; quotes and operators follow the FTS5 query syntax.

            Each result names its conversation and carries a snippet with the match highlighted.
            When nothing matches, try fewer or more distinctive words instead of repeating the same
            query.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("query", buildJsonObject {
                        put("type", "string")
                        put("description", "Keywords to search for in past conversation messages")
                    })
                    put("limit", buildJsonObject {
                        put("type", "integer")
                        put(
                            "description",
                            "Maximum number of results to return (default: 15, max: 50)"
                        )
                    })
                },
                required = listOf("query")
            )
        },
        execute = {
            val query = it.jsonObject["query"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (query.isEmpty()) {
                return@Tool listOf(UIMessagePart.Text(missingQuery()))
            }
            val limit = (it.jsonObject["limit"]?.jsonPrimitive?.intOrNull ?: DEFAULT_MESSAGE_LIMIT)
                .coerceIn(1, MAX_MESSAGE_LIMIT)
            val results = conversationRepo
                .searchMessages(query, MessageSearchSort.RELEVANCE)
                .take(limit)
            val payload = buildJsonObject {
                put("mode", "fts_keyword")
                put("query", query)
                put("results", buildJsonArray {
                    results.forEach { result ->
                        add(buildJsonObject {
                            put("conversation_id", result.conversationId)
                            put("title", result.title.ifBlank { "Untitled" })
                            put("snippet", result.snippet)
                            put("date", result.updateAt.toLocalDate())
                        })
                    }
                })
                if (results.isEmpty()) {
                    put(
                        "hint",
                        "没有匹配的会话文本。请换更有辨识度的关键词，或先用 recent_chats 确认会话标题。",
                    )
                }
            }
            listOf(UIMessagePart.Text(JsonInstantPretty.encodeToString(payload)))
        }
    )
)

/**
 * Creates the [current_conversation_search] tool allowing the model to recall earlier messages
 * or details within the active conversation via the native SQLite FTS5 index.
 *
 * Deliberately scoped to one conversation: the whole-history tool above answers "where did we
 * talk about X", while this one answers "what did we decide earlier in this session" without the
 * model having to filter other conversations out of the results itself.
 */
fun createCurrentConversationSearchTool(
    conversationRepo: ConversationRepository,
    conversationId: Uuid,
): Tool = Tool(
    name = "current_conversation_search",
    description = """
        Search earlier messages and turns in the CURRENT active conversation.

        Use this when context has been compacted, or when you need to recall specific details, commands,
        errors, code snippets, or user requirements discussed earlier in this session.
        Matching is full-text keyword search: use the exact terms that would appear in the message
        (identifiers, file paths, error strings), not a paraphrase. Matches are returned with
        surrounding snippet context.

        NOTE: This tool only matches text and code logs. If the user previously sent an image/screenshot,
        its visual contents CANNOT be found via text search. Do not continuously repeat or vary search
        keywords if no matches are found; ask the user or proceed with available information.
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "Keywords or search phrase to look up in the current conversation")
                })
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put("description", "Maximum number of search results to return (default: 10, max: 30)")
                })
            },
            required = listOf("query")
        )
    },
    execute = {
        val query = it.jsonObject["query"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (query.isEmpty()) {
            return@Tool listOf(UIMessagePart.Text(missingQuery()))
        }
        val limit = (it.jsonObject["limit"]?.jsonPrimitive?.intOrNull ?: 10).coerceIn(1, 30)

        val results = conversationRepo
            .searchMessages(
                keyword = query,
                sort = MessageSearchSort.RELEVANCE,
                conversationId = conversationId,
            )
            .take(limit)

        val payload = buildJsonObject {
            put("mode", "fts_keyword")
            put("query", query)
            put("results", buildJsonArray {
                results.forEach { result ->
                    add(buildJsonObject {
                        put("message_id", result.messageId)
                        put("snippet", result.snippet)
                        put("date", result.updateAt.toLocalDate().toString())
                    })
                }
            })
            if (results.isEmpty()) {
                put(
                    "hint",
                    "未在当前会话文本中找到匹配结果。请注意：若用户指的是图片、截图或未记录的外部信息，" +
                        "文本检索无法获取其视觉内容；请勿连续更换关键词盲搜，应直接向用户提问或结合现有上下文回答。"
                )
            }
        }
        listOf(UIMessagePart.Text(JsonInstantPretty.encodeToString(payload)))
    }
)

/** Passages one `conversation_search` call returns unless the model asks for a different number. */
internal const val DEFAULT_MESSAGE_LIMIT = 15

/** Upper bound on `limit`: a caller asking for a hundred messages is asking for a context blowup. */
internal const val MAX_MESSAGE_LIMIT = 50

private fun missingQuery(): String = buildJsonObject {
    put("error", "missing_argument")
    put("detail", "query is required")
}.toString()