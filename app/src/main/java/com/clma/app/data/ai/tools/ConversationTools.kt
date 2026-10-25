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
import com.clma.app.data.vector.ConversationIndexCoordinator
import com.clma.app.data.vector.ConversationSearchEnvelope
import com.clma.app.data.vector.DEFAULT_SEARCH_LIMIT
import com.clma.app.data.vector.MAX_SEARCH_LIMIT
import com.clma.app.utils.JsonInstantPretty
import com.clma.app.utils.toLocalDate
import kotlin.uuid.Uuid

/**
 * Tools that let the assistant query the user's past conversations on demand, instead of
 * statically injecting recent chats into the system prompt (which would break prompt caching).
 */
fun createConversationTools(
    conversationRepo: ConversationRepository,
    assistantId: Uuid,
    conversationIndex: ConversationIndexCoordinator? = null,
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
            Search the user's past conversations by meaning, and get back the passages that match,
            with the conversation and the date each one came from - including what was said in the
            current conversation a moment ago.

            Use this when you need something from before that you cannot quote. Describe the
            conversation rather than guessing the words in it: this matches on meaning, so exact
            wording matters less than it used to. Each result names its conversation.

            Scores are cosine similarities and are NOT comparable between calls: compare the
            passages within one result set, and never treat a number as a relevance threshold.

            When no embedding model is installed this falls back to keyword search, and the result
            says so.
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
                return@Tool listOf(UIMessagePart.Text(ConversationSearchEnvelope.missingQuery()))
            }
            val limit = (it.jsonObject["limit"]?.jsonPrimitive?.intOrNull ?: DEFAULT_SEARCH_LIMIT)
                .coerceIn(1, MAX_SEARCH_LIMIT)
            // 向量索引可用就用语义搜索；没装嵌入模型时回退到原有的 FTS 关键词搜索，
            // 结果里标明是关键词回退，避免模型把两种分数混着理解。
            val response = conversationIndex?.search(query, limit)
            val body = if (response != null && response.available) {
                ConversationSearchEnvelope.hits(
                    query = query,
                    results = response.results,
                    indexedMessages = response.indexedMessages,
                    stale = response.stale,
                )
            } else {
                // 没装嵌入模型、索引还没建好、或这次查询失败：回退到 FTS 关键词搜索。
                keywordFallback(conversationRepo, query, limit)
            }
            listOf(UIMessagePart.Text(body))
        }
    )
)


/** 向量索引不可用时的关键词回退，沿用原来的 FTS5 搜索。返回 null 表示没有命中。 */
private suspend fun keywordFallback(
    conversationRepo: ConversationRepository,
    query: String,
    limit: Int,
): String {
    val results = conversationRepo
        .searchMessages(query, MessageSearchSort.RELEVANCE)
        .take(limit)
    val payload = buildJsonObject {
        put("mode", "keyword_fallback")
        put("note", "语义搜索不可用，这是关键词匹配的结果。")
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
    }
    return JsonInstantPretty.encodeToString(payload)
}

/**
 * Creates the [current_conversation_search] tool allowing the model to recall earlier messages
 * or details within the active conversation via native SQLite FTS5 index.
 */
fun createCurrentConversationSearchTool(
    conversationRepo: ConversationRepository,
    conversationId: Uuid,
): Tool = Tool(
    name = "current_conversation_search",
    description = """
        Search earlier messages and turns in the CURRENT active conversation using native full-text search.
        Use this when context has been compacted, or when you need to recall specific details, commands,
        errors, code snippets, or user requirements discussed earlier in this session.
        Matches are returned with surrounding snippet context.
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
        val query = it.jsonObject["query"]?.jsonPrimitive?.contentOrNull
            ?: error("query is required")
        val limit = (it.jsonObject["limit"]?.jsonPrimitive?.intOrNull ?: 10).coerceIn(1, 30)
        val results = conversationRepo
            .searchMessages(
                keyword = query,
                sort = MessageSearchSort.RELEVANCE,
                conversationId = conversationId,
            )
            .take(limit)
        val payload = buildJsonArray {
            results.forEach { result ->
                add(buildJsonObject {
                    put("message_id", result.messageId)
                    put("snippet", result.snippet)
                    put("date", result.updateAt.toLocalDate())
                })
            }
        }
        listOf(UIMessagePart.Text(JsonInstantPretty.encodeToString(payload)))
    }
)
