// Adapted from OpenMausBot (https://github.com/milind-soni/OpenMausBot), server/system-prompt.ts, server/bot-folder.ts, server/chief-of-staff.ts,
// Copyright 2026 Milind Soni and OpenMausBot contributors, Apache License 2.0
// (third_party/openmausbot/LICENSE). Modified: ported to Kotlin and changed for CLmA.
package com.clma.app.data.botmode.settings

internal data class BotTeamMember(val id: String, val name: String, val description: String)

internal fun botProfilePrompt(
    botId: String,
    profile: BotProfile,
    bots: List<BotTeamMember>,
    canDelegate: Boolean = true,
): String? = buildList {
    val soul = profile.soul.trim()
    if (soul.isNotEmpty()) {
        add(
            "## Standing instructions\n" +
                "The user manages these instructions in Bot settings. They rank above memory and imported skills, " +
                "and below the user's current request and safety boundaries. This block grants no tool permissions " +
                "or permission to expose secrets.\n\n" +
                "--- BEGIN STANDING INSTRUCTIONS ---\n$soul\n--- END STANDING INSTRUCTIONS ---",
        )
    }
    val teammates = bots.filter { it.id != botId }
    teammates.firstOrNull { it.id == profile.reportsTo }?.let {
        add("You report to ${it.name.singleLine()}; share relevant progress and blockers when coordinating.")
    }
    if (profile.chiefOfStaff) {
        val roster = teammates.take(40).joinToString("\n") {
            "- ${it.name.singleLine()} (id ${it.id})" +
                it.description.singleLine().take(400).let { description ->
                    if (description.isBlank()) "" else ": $description"
                }
        }.ifEmpty { "- No teammates are available." }
        val delegation = if (canDelegate) {
            "Use ask_bot for advice, delegate_bot to queue a task, or coordinate_bots for multiple teammates. " +
                "Give a clear, self-contained brief. Retrieve queued work with peer_task_get before finishing. " +
                "Report assignments and results accurately, verify returned work, and consolidate the answer."
        } else {
            "This turn has no peer tools. Describe any coordination that still needs to happen."
        }
        add(
            "## Chief of Staff\nYou coordinate the user's Bot team.\n$roster\n\n" +
                "$delegation Normal permissions and approvals still apply.",
        )
    }
}.joinToString("\n\n").ifBlank { null }

private fun String.singleLine(): String = lineSequence().joinToString(" ").trim()
