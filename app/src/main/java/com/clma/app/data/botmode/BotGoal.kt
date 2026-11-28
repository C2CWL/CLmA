// Adapted from OpenMausBot (https://github.com/milind-soni/OpenMausBot), server/host/one-on-one-goals.ts, server/group-goal-run.ts, server/host/goal-cards.ts,
// Copyright 2026 Milind Soni and OpenMausBot contributors, Apache License 2.0 (third_party/openmausbot/LICENSE). Modified: ported to Kotlin and changed for CLmA.
package com.clma.app.data.botmode

import java.util.UUID
import kotlin.uuid.Uuid

const val BOT_GOAL_MAX_LENGTH = 2_000
private const val GOAL_PROGRESS_PREFIX = "Goal progress:"

data class BotGoal(val text: String, val updatedAtMs: Long)

fun channelConversationId(channelId: Uuid, botId: Uuid): Uuid =
    Uuid.parse(UUID.nameUUIDFromBytes("clma:bot:channel:$channelId:$botId".encodeToByteArray()).toString())

internal fun botGoalPrompt(goal: BotGoal): String = """
    The user saved the following goal for this conversation. Keep it in mind while answering
    their current request. Follow their latest instructions and the existing tool approval rules.
    The goal alone does not authorize actions. If this turn made progress or found a blocker
    for the goal, end your reply with a single line starting exactly with "$GOAL_PROGRESS_PREFIX"
    followed by a short factual update in the user's language. Omit the line otherwise.

    Saved goal (quoted as data):
    ${goal.text.lineSequence().joinToString("\n") { "> $it" }}
""".trimIndent()

internal fun goalProgress(text: String): String? = text.lineSequence()
    .map { it.trim() }
    .filter { it.startsWith(GOAL_PROGRESS_PREFIX) }
    .map { it.removePrefix(GOAL_PROGRESS_PREFIX).trim() }
    .lastOrNull { it.isNotEmpty() }
