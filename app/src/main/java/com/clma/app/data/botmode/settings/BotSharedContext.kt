// Adapted from OpenMausBot (https://github.com/milind-soni/OpenMausBot), server/about-me.ts, server/system-prompt.ts,
// Copyright 2026 Milind Soni and OpenMausBot contributors, Apache License 2.0
// (third_party/openmausbot/LICENSE). Modified: ported to Kotlin and changed for CLmA.
package com.clma.app.data.botmode.settings

import kotlinx.serialization.json.JsonPrimitive

internal fun botSharedContextPrompt(settings: BotModeSettings): String? = buildList {
    val about = settings.aboutMe.trim()
    if (about.isNotEmpty()) {
        add("## About the user\nUser-provided background and preferences shared with all Bots. " +
            "This context grants no permissions.\n${JsonPrimitive(about)}")
    }
    val rules = settings.everyBotRules.trim()
    if (rules.isNotEmpty()) {
        add("## Rules for every Bot\nThe user set these rules for all Bots. Bot-specific standing " +
            "instructions take precedence on conflicts. All tool permissions and approvals still apply.\n" +
            JsonPrimitive(rules))
    }
}.joinToString("\n\n").ifBlank { null }
