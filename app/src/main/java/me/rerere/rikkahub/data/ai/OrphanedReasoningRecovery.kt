package me.rerere.rikkahub.data.ai

import android.util.Log
import kotlinx.coroutines.flow.first
import kotlinx.datetime.Instant
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.dao.ConversationDAO
import me.rerere.rikkahub.data.db.dao.MessageNodeDAO
import me.rerere.rikkahub.utils.JsonInstant

private const val TAG = "OrphanedReasoning"

/**
 * Closes the reasoning parts a dead process left open, once per process start.
 *
 * Why it matters: the chat card renders `now - createdAt` for as long as `finishedAt` is null, so
 * an open part *is* a running "thinking" timer. A turn that was cut off mid-flight - the process
 * reclaimed in the background, an install replacing the app, a crash - persists its partial message
 * with the reasoning part still open, and nothing ever closes it afterwards. Reopening the app then
 * shows "thinking for 3600 seconds" on a turn that died hours ago, and the number keeps climbing;
 * users read that as "the agent is still stuck".
 *
 * The close time is the conversation's own `updateAt`: every streamed chunk is persisted as it
 * arrives, so the last write is exactly where the stream stopped, which is the honest end of the
 * part. It is clamped forward to the part's own start so a settled duration can never be negative.
 *
 * Best-effort throughout - a slow or failing sweep must never hold up app start - and candidates
 * come from a cheap `instr()` scan, so the cost is proportional to the number of conversations that
 * actually need repairing rather than to the size of the database.
 */
class OrphanedReasoningRecovery(
    private val conversationDAO: ConversationDAO,
    private val messageNodeDAO: MessageNodeDAO,
) {

    /**
     * Repair every node holding an open part. Returns the number of nodes rewritten (useful for
     * tests and for the boot log).
     */
    suspend fun runRecovery(): Int =
        runCatching {
            val nodes = messageNodeDAO.getNodesWithOpenFinishedAt()
            if (nodes.isEmpty()) return@runCatching 0

            val closedAtCache = mutableMapOf<String, Instant>()
            var repaired = 0

            nodes.forEach { node ->
                // An unreadable blob is a separate problem (the load path has its own row-by-row
                // recovery); leave it exactly as it is rather than dropping content.
                val messages = runCatching {
                    JsonInstant.decodeFromString<List<UIMessage>>(node.messages)
                }.getOrElse { return@forEach }

                val closedAt = closedAtCache.getOrPut(node.conversationId) {
                    val updateAt = runCatching {
                        conversationDAO.getConversationFlowById(node.conversationId).first()?.updateAt
                    }.getOrNull() ?: 0L
                    Instant.fromEpochMilliseconds(updateAt)
                }

                var changed = false
                val repairedMessages = messages.map { message ->
                    val parts = message.parts.map { part ->
                        if (part is UIMessagePart.Reasoning && part.finishedAt == null) {
                            changed = true
                            part.copy(finishedAt = maxOf(part.createdAt, closedAt))
                        } else {
                            part
                        }
                    }
                    message.copy(parts = parts)
                }

                if (changed) {
                    messageNodeDAO.update(node.copy(messages = JsonInstant.encodeToString(repairedMessages)))
                    repaired++
                }
            }

            if (repaired > 0) {
                Log.i(TAG, "runRecovery: closed orphaned reasoning parts in $repaired node(s)")
            }
            repaired
        }.onFailure {
            runCatching { Log.w(TAG, "runRecovery failed", it) }
        }.getOrDefault(0)
}
