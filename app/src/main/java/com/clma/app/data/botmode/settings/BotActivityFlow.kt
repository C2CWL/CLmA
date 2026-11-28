package com.clma.app.data.botmode.settings

import com.clma.app.data.botmode.BotRoutineApprovals
import com.clma.app.data.botmode.hasPendingApproval
import com.clma.app.data.model.Conversation
import com.clma.app.data.repository.ConversationRepository
import com.clma.app.service.ChatService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
internal fun observeBotActivity(
    id: Uuid,
    repository: ConversationRepository,
    service: ChatService,
): Flow<BotActivitySnapshot> {
    val live = service.observeLiveBotConversations(id)
    val summaries = repository.getConversationsOfAssistant(id).distinctUntilChanged()
    val liveIds = live.map { active -> active.mapTo(mutableSetOf()) { it.id } }.distinctUntilChanged()
    val savedPending = flow {
        // Cache by summary revision. Re-read a conversation when its live session goes away.
        var cached = emptyMap<Uuid, Pair<Conversation, Boolean>>()
        combine(summaries, liveIds) { saved, active -> saved.filterNot { it.id in active } }
            .distinctUntilChanged()
            .mapLatest { inactive ->
                inactive.associate { summary ->
                    val previous = cached[summary.id]
                    val pending = if (previous?.first == summary) previous.second
                    else hasPendingApproval(repository.getConversationById(summary.id))
                    summary.id to (summary to pending)
                }
            }
            .collect { statuses ->
                cached = statuses
                emit(statuses.filterValues { it.second }.keys)
            }
    }.onStart { emit(emptySet()) }
    val parked = BotRoutineApprovals.parked
        .map { runs ->
            runs.lastOrNull { it.assistantId == id.toString() && !it.resuming }
                ?.conversationId?.let { runCatching { Uuid.parse(it) }.getOrNull() }
        }
        .distinctUntilChanged()
    return combine(savedPending, live, parked, ::botActivitySnapshot).distinctUntilChanged()
}
