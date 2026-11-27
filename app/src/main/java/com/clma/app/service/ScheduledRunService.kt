package com.clma.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.clma.app.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "ScheduledRunService"
private const val CHANNEL_ID = "scheduled_tasks_running"
private const val NOTIFICATION_ID = 2004
private const val WAKE_TIMEOUT_MS = 20 * 60_000L

class ScheduledRunService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val runs = ScheduledRunStarts()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        runs.start(startId)
        try {
            // Every startForegroundService() call must be answered with startForeground(),
            // even while the service is already in the foreground for an earlier run.
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                scheduledRunNotification(this),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
            )
        } catch (error: Exception) {
            Log.w(TAG, "Unable to promote scheduled run service", error)
            intent?.let(::scheduledAlarmInput)?.let { enqueueAlarmFallback(this, it) }
            finishRun()
            return START_NOT_STICKY
        }

        val input = intent?.let(::scheduledAlarmInput)
        if (input == null) {
            finishRun()
            return START_NOT_STICKY
        }

        scope.launch {
            try {
                val power = getSystemService(PowerManager::class.java)
                val wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "clma:scheduled_run")
                wakeLock.setReferenceCounted(false)
                try {
                    wakeLock.acquire(WAKE_TIMEOUT_MS)
                    withContext(Dispatchers.IO) {
                        CronJobExecution(applicationContext, fromWorker = false).execute(input)
                    }
                } finally {
                    if (wakeLock.isHeld) wakeLock.release()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(TAG, "Scheduled run failed", error)
                enqueueAlarmFallback(this@ScheduledRunService, input)
            } finally {
                finishRun()
            }
        }
        return START_NOT_STICKY
    }

    private fun finishRun() {
        runs.finish()?.let { stopSelfResult(it) }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

/** Accessed on the service's main thread, including completion callbacks. */
internal class ScheduledRunStarts {
    var activeCount = 0
        private set
    private var latestStartId = 0

    fun start(startId: Int) {
        latestStartId = startId
        activeCount++
    }

    fun finish(): Int? {
        activeCount--
        return latestStartId.takeIf { activeCount == 0 }
    }
}

internal fun scheduledRunNotification(context: Context): Notification {
    val title = context.getString(R.string.cron_wake_running)
    context.getSystemService(NotificationManager::class.java).createNotificationChannel(
        NotificationChannel(CHANNEL_ID, title, NotificationManager.IMPORTANCE_LOW),
    )
    return NotificationCompat.Builder(context, CHANNEL_ID)
        .setContentTitle(title)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .build()
}
