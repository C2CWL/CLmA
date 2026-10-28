package com.clma.app.subagent

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.clma.app.R
import com.clma.app.RouteActivity

/**
 * Option C — surface a finished BACKGROUND sub-agent to the user without waking the parent up.
 *
 * [SubAgentEngine.notifyParentIfBackground] only queues a notice in
 * [com.clma.app.data.ai.tools.ConversationInjections], and GenerationLoop drains that queue
 * immediately before the parent's NEXT model request. A parent that is idle never makes one,
 * so before this the completion stayed invisible until the user happened to speak again.
 *
 * The notification is the whole fix on purpose: it starts no generation, writes no message and
 * spends no tokens. Whether the parent should carry on is the user's call — they tap through to
 * the conversation and decide. Called only when the parent is NOT generating; a parent
 * mid-turn already picks the notice up on its next loop step, so a notification there would
 * just be noise.
 *
 * Best-effort: a missing POST_NOTIFICATIONS grant or a restricted channel is not an error, and
 * a completion must never break because a notification could not be posted.
 */
internal object SubAgentCompletionNotifier {

    private const val CHANNEL_ID = "rikkahub_subagent_completion"

    fun post(context: Context, parentChatId: String, run: SubAgentRun) {
        runCatching {
            val manager = NotificationManagerCompat.from(context)
            if (!manager.areNotificationsEnabled()) return
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.subagent_notification_channel),
                    NotificationManager.IMPORTANCE_DEFAULT,
                )
            )
            val status = context.getString(
                when (run.status) {
                    SubAgentStatus.FAILED -> R.string.subagent_notification_status_failed
                    SubAgentStatus.TIMED_OUT -> R.string.subagent_notification_status_timed_out
                    else -> R.string.subagent_notification_status_succeeded
                }
            )
            val text = context.getString(R.string.subagent_notification_text, run.label, status)
            val intent = Intent(context, RouteActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("conversationId", parentChatId)
            }
            val pending = PendingIntent.getActivity(
                context,
                // Stable per conversation: a second completion replaces the first instead of stacking.
                parentChatId.hashCode(),
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(context.getString(R.string.subagent_notification_title))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setAutoCancel(true)
                .setContentIntent(pending)
            manager.notify(NOTIFICATION_ID_BASE + (parentChatId.hashCode() and 0x0FFFFFFF), builder.build())
        }
    }

    private const val NOTIFICATION_ID_BASE = 0x5100_0000
}
