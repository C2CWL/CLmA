// Adapted from OpenMausBot (https://github.com/milind-soni/OpenMausBot), server/routines.ts.
// Copyright 2026 Milind Soni and OpenMausBot contributors, Apache License 2.0 (third_party/openmausbot/LICENSE). Modified: ported to Kotlin and changed for CLmA.
package com.clma.app.data.botmode

import com.clma.app.data.db.entity.ScheduledJobEntity
import kotlin.uuid.Uuid

enum class BotRoutineEventKind {
    NOTIFICATION, SMS, EMAIL, WEBHOOK, BOT_FINISHED, POWER_CONNECTED, POWER_DISCONNECTED,
    NETWORK_AVAILABLE, NETWORK_LOST, BOOT,
}

data class BotRoutineTrigger(
    val kind: BotRoutineEventKind,
    val source: String = "",
    val revision: String = "",
    val webhookSecret: String = "",
) {
    fun validFor(owner: String): Boolean = when (kind) {
        BotRoutineEventKind.NOTIFICATION, BotRoutineEventKind.EMAIL ->
            source.length <= 200 && source.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+"))
        BotRoutineEventKind.BOT_FINISHED ->
            source != owner && uuidIsCanonical(source)
        else -> source.isEmpty()
    }

    fun matches(kind: BotRoutineEventKind, source: String): Boolean =
        this.kind == kind && (this.source.isEmpty() || this.source == source)

    fun encode(): String = "${kind.name}:${source}:${revision}:${webhookSecret}"

    companion object {
        const val SCHEDULE_TYPE = "event"

        fun from(job: ScheduledJobEntity): BotRoutineTrigger? {
            if (job.scheduleType != SCHEDULE_TYPE || job.mode != "llm") return null
            val fields = job.cronExpression?.split(':') ?: return null
            if (fields.size != 4) return null
            val kind = BotRoutineEventKind.entries.firstOrNull { it.name == fields[0] } ?: return null
            val (revision, secret) = fields.drop(2)
            if (!uuidIsCanonical(revision)) return null
            if (kind == BotRoutineEventKind.WEBHOOK && !uuidIsCanonical(secret)) return null
            if (kind != BotRoutineEventKind.WEBHOOK && secret.isNotEmpty()) return null
            return BotRoutineTrigger(kind, fields[1], revision, secret).takeIf { it.validFor(job.assistantId) }
        }

        private fun uuidIsCanonical(value: String): Boolean =
            runCatching { Uuid.parse(value).toString() == value }.getOrDefault(false)
    }
}
