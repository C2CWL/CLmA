// Adapted from OpenMausBot (https://github.com/milind-soni/OpenMausBot), server/routines.ts, server/webhook-ingress.ts.
// Copyright 2026 Milind Soni and OpenMausBot contributors, Apache License 2.0 (third_party/openmausbot/LICENSE). Modified: ported to Kotlin and changed for CLmA.
package com.clma.app.service

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.provider.Telephony
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.await
import com.clma.app.AppScope
import com.clma.app.data.ai.tools.local.NotificationListenerHandle
import com.clma.app.data.botmode.BotRoutineEventKind
import com.clma.app.data.botmode.BotRoutineEventKind.*
import com.clma.app.data.botmode.BotRoutineTrigger
import com.clma.app.data.datastore.SettingsStore
import com.clma.app.data.db.entity.ScheduledJobEntity
import com.clma.app.data.model.isBot
import com.clma.app.data.repository.ScheduledJobRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

internal object BotRoutineEvents : KoinComponent {
    const val KEY_CONTEXT = "bot_event_context"
    const val KEY_TRIGGER = "bot_event_trigger"
    const val PORT = 18765

    private val repo: ScheduledJobRepository by inject()
    private val settings: SettingsStore by inject()
    private val scope: AppScope by inject()

    fun hasPermission(context: Context, kind: BotRoutineEventKind): Boolean = when (kind) {
        NOTIFICATION, EMAIL -> NotificationListenerHandle.isEnabledInSettings(context)
        SMS -> ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) ==
            PackageManager.PERMISSION_GRANTED
        else -> true
    }

    fun start(context: Context) {
        scope.launch(Dispatchers.IO) {
            var powerWatching = false
            var networkWatching = false
            val power = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val kind = when (intent.action) {
                        Intent.ACTION_POWER_CONNECTED -> POWER_CONNECTED
                        Intent.ACTION_POWER_DISCONNECTED -> POWER_DISCONNECTED
                        else -> return
                    }
                    emit(context, kind)
                }
            }
            val network = context.getSystemService(ConnectivityManager::class.java)
                ?.let { DefaultNetworkEvents(context, it) }
            val webhook = BotRoutineWebhook(context)
            combine(repo.observeAll(), settings.settingsFlowRaw) { jobs, config ->
                val owners = config.assistants.filter { it.isBot }.map { it.id.toString() }.toSet()
                jobs.asSequence().filter { it.enabled && it.assistantId in owners }
                    .mapNotNull { BotRoutineTrigger.from(it)?.kind }.toSet()
            }.distinctUntilChanged().collect { kinds ->
                safely {
                    val watchPower = POWER_CONNECTED in kinds || POWER_DISCONNECTED in kinds
                    if (watchPower != powerWatching) {
                        if (watchPower) {
                            ContextCompat.registerReceiver(context, power, IntentFilter().apply {
                                addAction(Intent.ACTION_POWER_CONNECTED)
                                addAction(Intent.ACTION_POWER_DISCONNECTED)
                            }, ContextCompat.RECEIVER_NOT_EXPORTED)
                        } else context.unregisterReceiver(power)
                        powerWatching = watchPower
                    }
                }
                safely {
                    val watchNetwork = NETWORK_AVAILABLE in kinds || NETWORK_LOST in kinds
                    if (network != null && watchNetwork != networkWatching) {
                        if (watchNetwork) network.start() else network.stop()
                        networkWatching = watchNetwork
                    }
                }
                safely { webhook.setEnabled(WEBHOOK in kinds) }
            }
        }
    }

    fun notification(context: Context, app: String, title: String?, body: String?) {
        // Our own run notifications cannot start new runs.
        if (app == context.packageName) return
        scope.launch(Dispatchers.IO) {
            safely { dispatchMatching(context, setOf(NOTIFICATION, EMAIL), app, title, body) }
        }
    }

    fun emit(
        context: Context,
        kind: BotRoutineEventKind,
        source: String = "",
        title: String? = null,
        body: String? = null,
    ) {
        scope.launch(Dispatchers.IO) { safely { dispatch(context, kind, source, title, body) } }
    }

    suspend fun dispatch(
        context: Context,
        kind: BotRoutineEventKind,
        source: String = "",
        title: String? = null,
        body: String? = null,
    ) = dispatchMatching(context, setOf(kind), source, title, body)

    private suspend fun dispatchMatching(
        context: Context,
        kinds: Set<BotRoutineEventKind>,
        source: String,
        title: String?,
        body: String?,
    ) {
        val allowedKinds = kinds.filter { hasPermission(context, it) }
        if (allowedKinds.isEmpty()) return
        val matches = repo.getEnabled().mapNotNull { job ->
            val trigger = BotRoutineTrigger.from(job) ?: return@mapNotNull null
            if (allowedKinds.any { trigger.matches(it, source) }) job to trigger else null
        }
        if (matches.isEmpty()) return
        val owners = settings.settingsFlowRaw.first().assistants.filter { it.isBot }
            .map { it.id.toString() }.toSet()
        if (BOT_FINISHED in kinds && source !in owners) return
        for ((job, trigger) in matches) {
            if (job.assistantId in owners) {
                safely { enqueue(context, job, trigger, eventContext(trigger.kind, source, title, body)) }
            }
        }
    }

    suspend fun webhook(id: String, secret: String): ScheduledJobEntity? {
        val job = repo.getById(id)?.takeIf { it.enabled } ?: return null
        val trigger = BotRoutineTrigger.from(job)?.takeIf { it.kind == WEBHOOK } ?: return null
        if (!java.security.MessageDigest.isEqual(trigger.webhookSecret.toByteArray(), secret.toByteArray())) return null
        return job.takeIf {
            settings.settingsFlowRaw.first().assistants.any { it.isBot && it.id.toString() == job.assistantId }
        }
    }

    suspend fun enqueue(
        context: Context,
        job: ScheduledJobEntity,
        trigger: BotRoutineTrigger,
        event: String,
    ) {
        val work = OneTimeWorkRequestBuilder<CronJobWorker>()
            .setInputData(Data.Builder()
                .putString(CronJobWorker.KEY_JOB_ID, job.id)
                .putString(KEY_TRIGGER, trigger.revision)
                .putString(KEY_CONTEXT, event)
                .build())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork("bot_event_${job.id}", ExistingWorkPolicy.APPEND_OR_REPLACE, work)
            .await()
    }

    fun eventContext(
        kind: BotRoutineEventKind,
        source: String = "",
        title: String? = null,
        body: String? = null,
    ): String = buildJsonObject {
        put("event", kind.name.lowercase())
        put("received_at_ms", System.currentTimeMillis())
        if (source.isNotEmpty()) put("source", source)
        if (title != null) put("title", title.take(200))
        if (body != null) put("body", body.take(1000))
    }.toString()

    private suspend fun safely(action: suspend () -> Unit) {
        try {
            action()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("BotRoutineEvents", "Trigger operation failed (${e.javaClass.simpleName})")
        }
    }
}

private class DefaultNetworkEvents(
    private val context: Context,
    private val connectivity: ConnectivityManager,
) {
    @Volatile private var callback: ConnectivityManager.NetworkCallback? = null

    fun start() {
        var current = connectivity.activeNetwork
        val listener = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (callback !== this) return
                val wasConnected = current != null
                current = network
                if (!wasConnected) BotRoutineEvents.emit(context, NETWORK_AVAILABLE)
            }

            override fun onLost(network: Network) {
                if (callback !== this || current != network) return
                current = null
                BotRoutineEvents.emit(context, NETWORK_LOST)
            }
        }
        callback = listener
        connectivity.registerDefaultNetworkCallback(listener)
    }

    fun stop() {
        val listener = callback ?: return
        callback = null
        connectivity.unregisterNetworkCallback(listener)
    }
}

class BotRoutineSmsReceiver : BroadcastReceiver(), KoinComponent {
    private val scope: AppScope by inject()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION ||
            !BotRoutineEvents.hasPermission(context, SMS)) return
        val messages = runCatching { Telephony.Sms.Intents.getMessagesFromIntent(intent) }.getOrNull()
            ?.filterNotNull().orEmpty()
        if (messages.isEmpty()) return
        val pending = goAsync()
        scope.launch(Dispatchers.IO) {
            try {
                BotRoutineEvents.dispatch(
                    context, SMS, title = messages.first().originatingAddress,
                    body = messages.joinToString("") { it.messageBody.orEmpty() },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("BotRoutineEvents", "SMS dispatch failed (${e.javaClass.simpleName})")
            } finally {
                pending.finish()
            }
        }
    }
}
