// Adapted from OpenMausBot (https://github.com/milind-soni/OpenMausBot), shared/bot-profile.ts, shared/approval-mode.ts, shared/bot-owner.ts, server/activity.ts, server/store.ts,
// Copyright 2026 Milind Soni and OpenMausBot contributors, Apache License 2.0
// (third_party/openmausbot/LICENSE). Modified: ported to Kotlin and changed for CLmA.
package com.clma.app.data.botmode.settings

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.uuid.Uuid

@Serializable
enum class BotApprovalLevel { ASK, AUTO, FULL }

@Serializable
enum class BotOutboundPolicy { ASK, ALLOW }

@Serializable
data class BotModeSettings(
    val approvalLevel: BotApprovalLevel = BotApprovalLevel.ASK,
    val outbound: BotOutboundPolicy = BotOutboundPolicy.ASK,
    val notifyReplies: Boolean = true,
    val notifyRoutineResults: Boolean = true,
    val notifyApprovals: Boolean = true,
    val showRoutineReliability: Boolean = true,
    val notifyQuestions: Boolean? = null,
    val notifyFailures: Boolean = true,
    val feedbackVibration: Boolean = false,
    val feedbackStrength: Int = 50,
    val feedbackSound: Boolean = false,
    val feedbackVolume: Int = 50,
)

const val BOT_SOUL_MAX_LENGTH = 24_000
const val BOT_DESCRIPTION_MAX_LENGTH = 4_000

@Serializable
data class BotProfile(
    val approvalLevel: BotApprovalLevel? = null,
    val outbound: BotOutboundPolicy? = null,
    val alwaysAllow: List<String> = emptyList(),
    val soul: String = "",
    val description: String = "",
    val chiefOfStaff: Boolean = false,
    val reportsTo: String? = null,
)

internal object BotSettingsCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    fun decodeSettings(value: String?): BotModeSettings =
        value?.let { json.decodeFromString<BotModeSettings>(it) } ?: BotModeSettings()

    fun encodeSettings(settings: BotModeSettings): String = json.encodeToString(settings)

    fun decodeProfiles(value: String?): Map<String, BotProfile> =
        value?.let { json.decodeFromString<Map<String, BotProfile>>(it) } ?: emptyMap()

    fun encodeProfiles(profiles: Map<String, BotProfile>): String = json.encodeToString(profiles)
}

internal fun withBotProfile(
    profiles: Map<String, BotProfile>,
    botId: String,
    profile: BotProfile,
): Map<String, BotProfile> {
    val others = if (profile.chiefOfStaff) {
        profiles.mapValues { (id, saved) ->
            if (id != botId && saved.chiefOfStaff) saved.copy(chiefOfStaff = false) else saved
        }
    } else {
        profiles
    }
    return others + (botId to profile)
}

enum class BotActivity { WORKING, WAITING, IDLE }

fun botActivity(generating: Boolean, pending: Boolean, parked: Boolean = false): BotActivity = when {
    pending || parked -> BotActivity.WAITING
    generating -> BotActivity.WORKING
    else -> BotActivity.IDLE
}

internal data class LiveBotConversation(
    val id: Uuid,
    val generating: Boolean,
    val pending: Boolean,
)

internal data class BotActivitySnapshot(
    val activity: BotActivity = BotActivity.IDLE,
    val waitingConversationId: Uuid? = null,
)

internal fun botActivitySnapshot(
    savedPending: Set<Uuid>,
    live: List<LiveBotConversation>,
    parked: Uuid?,
): BotActivitySnapshot {
    val waitingId = parked ?: live.firstOrNull { it.pending }?.id
        ?: savedPending.firstOrNull { id -> live.none { it.id == id } }
    return BotActivitySnapshot(
        activity = botActivity(generating = live.any { it.generating }, pending = waitingId != null),
        waitingConversationId = waitingId,
    )
}
