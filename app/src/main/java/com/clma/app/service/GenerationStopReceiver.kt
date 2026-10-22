package com.clma.app.service

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.clma.app.AppScope
import com.clma.app.R
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import kotlin.uuid.Uuid

/**
 * The "停止" action on the generation notifications. With a conversation id it stops that
 * conversation; without one (the foreground-service notification, which is shared by every
 * running generation) it stops every conversation that is currently generating.
 */
class GenerationStopReceiver : BroadcastReceiver(), KoinComponent {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_STOP_GENERATION) return
        val chatService: ChatService = get()
        val appScope: AppScope = get()
        val conversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID)
            ?.let { runCatching { Uuid.parse(it) }.getOrNull() }
        val pending = goAsync()
        appScope.launch {
            try {
                val ids = if (conversationId != null) {
                    listOf(conversationId)
                } else {
                    chatService.getConversationJobs().first().filterValues { it != null }.keys.toList()
                }
                ids.forEach { id ->
                    runCatching { chatService.stopGeneration(id) }
                        .onFailure { Log.w(TAG, "stopGeneration($id) failed", it) }
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "GenerationStopReceiver"
        const val ACTION_STOP_GENERATION = "com.clma.app.action.STOP_GENERATION"
        const val EXTRA_CONVERSATION_ID = "conversationId"

        /** Request codes stay clear of ChatService's (+1_000_000) and NotificationTool's ranges. */
        private const val REQUEST_CODE_ALL = 2_000_000

        fun stopPendingIntent(context: Context, conversationId: Uuid?, requestCode: Int = REQUEST_CODE_ALL): PendingIntent {
            val intent = Intent(context, GenerationStopReceiver::class.java).apply {
                action = ACTION_STOP_GENERATION
                setPackage(context.packageName)
                conversationId?.let { putExtra(EXTRA_CONVERSATION_ID, it.toString()) }
            }
            return PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        fun stopAction(context: Context, conversationId: Uuid?, requestCode: Int = REQUEST_CODE_ALL): NotificationCompat.Action =
            NotificationCompat.Action.Builder(
                0,
                context.getString(R.string.top_island_notification_stop),
                stopPendingIntent(context, conversationId, requestCode),
            ).build()
    }
}
