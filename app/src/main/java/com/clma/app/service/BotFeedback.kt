package com.clma.app.service

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.clma.app.data.botmode.settings.BotModeSettings
import com.clma.app.data.botmode.settings.BotToolNames

internal enum class BotFeedbackEvent {
    APPROVAL, QUESTION, DONE, FAILED, ROUTINE_RESULT;

    fun notificationEnabled(settings: BotModeSettings): Boolean = when (this) {
        APPROVAL -> settings.notifyApprovals
        QUESTION -> settings.notifyQuestions ?: settings.notifyApprovals
        DONE -> settings.notifyReplies
        FAILED -> settings.notifyFailures
        ROUTINE_RESULT -> settings.notifyRoutineResults
    }

    companion object {
        fun pending(toolName: String): BotFeedbackEvent =
            if (BotToolNames.asksHuman(toolName)) QUESTION else APPROVAL
    }
}

internal object BotFeedback {
    private val main = Handler(Looper.getMainLooper())
    private var activeTone: ToneGenerator? = null

    fun deliver(
        context: Context,
        settings: BotModeSettings,
        event: BotFeedbackEvent,
        channelId: String,
        showNotification: Boolean,
        allowVibration: Boolean,
        postNotification: () -> Unit,
    ) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = manager.getNotificationChannel(channelId)
        val notificationsAllowed = channel?.importance != NotificationManager.IMPORTANCE_NONE &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

        if (showNotification && notificationsAllowed && event.notificationEnabled(settings)) {
            attempt { postNotification() }
        }

        if ((!settings.feedbackSound && !settings.feedbackVibration) ||
            manager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
        ) return
        val audio = context.getSystemService(AudioManager::class.java) ?: return
        if (audio.ringerMode == AudioManager.RINGER_MODE_SILENT) return

        if (settings.feedbackVibration && allowVibration && Settings.System.getInt(
                context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1,
            ) != 0
        ) {
            attempt { vibrate(context, settings.feedbackStrength) }
        }
        if (settings.feedbackSound && audio.ringerMode == AudioManager.RINGER_MODE_NORMAL) {
            main.post { attempt { playTone(event, settings.feedbackVolume) } }
        }
    }

    @Suppress("DEPRECATION")
    private fun vibrate(context: Context, strength: Int) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            context.getSystemService(Vibrator::class.java)
        } ?: return
        if (!vibrator.hasVibrator()) return
        val level = strength.coerceIn(1, 100)
        val effect = if (vibrator.hasAmplitudeControl()) {
            VibrationEffect.createOneShot(110, (level * 255 / 100).coerceAtLeast(1))
        } else {
            VibrationEffect.createOneShot(30L + level, VibrationEffect.DEFAULT_AMPLITUDE)
        }
        vibrator.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build())
    }

    private fun playTone(event: BotFeedbackEvent, volume: Int) {
        activeTone?.release()
        activeTone = null
        val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, volume.coerceIn(0, 100))
        activeTone = tone
        val type = when (event) {
            BotFeedbackEvent.APPROVAL, BotFeedbackEvent.QUESTION -> ToneGenerator.TONE_PROP_BEEP2
            BotFeedbackEvent.DONE, BotFeedbackEvent.ROUTINE_RESULT -> ToneGenerator.TONE_PROP_ACK
            BotFeedbackEvent.FAILED -> ToneGenerator.TONE_PROP_NACK
        }
        try {
            tone.startTone(type, 180)
        } finally {
            main.postDelayed({
                if (activeTone === tone) {
                    activeTone = null
                    tone.release()
                }
            }, 250)
        }
    }

    private inline fun attempt(block: () -> Unit) {
        try {
            block()
        } catch (e: RuntimeException) {
            Log.w("BotFeedback", "Could not deliver Bot feedback", e)
        }
    }
}
