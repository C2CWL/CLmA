package com.clma.app.data.botmode

import com.clma.app.data.datastore.BotChannel
import com.clma.app.data.datastore.DisplaySetting
import com.clma.app.data.datastore.Settings
import com.clma.app.data.model.Assistant
import kotlin.uuid.Uuid

/** Visible channels for Bot mode (empty when Bot mode is off — callers still may read). */
fun Settings.botChannels(): List<BotChannel> = displaySetting.botChannels

fun DisplaySetting.withBotChannelUpserted(channel: BotChannel): DisplaySetting {
    val without = botChannels.filterNot { it.id == channel.id }
    return copy(botChannels = without + channel)
}

fun DisplaySetting.withBotChannelRemoved(channelId: Uuid): DisplaySetting =
    copy(botChannels = botChannels.filterNot { it.id == channelId })

fun BotChannel.memberAssistants(assistants: List<Assistant>): List<Assistant> {
    val byId = assistants.associateBy { it.id }
    return memberAssistantIds.mapNotNull { byId[it] }
}

/**
 * Very light @-mention parse for shared channel prompts: returns assistant ids whose names
 * appear after an `@` token. Unknown names are ignored.
 */
fun parseBotMentions(text: String, members: List<Assistant>): List<Uuid> {
    if (text.isBlank() || members.isEmpty()) return emptyList()
    val byName = members
        .filter { it.name.isNotBlank() }
        .associateBy { it.name.trim().lowercase() }
    val found = linkedSetOf<Uuid>()
    val regex = Regex("""@([\w\u4e00-\u9fff.-]+)""")
    for (match in regex.findAll(text)) {
        val key = match.groupValues[1].lowercase()
        byName[key]?.id?.let { found += it }
    }
    return found.toList()
}

/** Channel members that are still in the Bot library (classic assistants never take part). */
fun BotChannel.botMembers(settings: Settings): List<Assistant> =
    memberAssistants(settings.botAssistants())

/**
 * Who a channel message goes to: the members @-mentioned in [text], or every member when the
 * text mentions nobody.
 */
fun channelTargets(text: String, members: List<Assistant>): List<Uuid> {
    val mentioned = parseBotMentions(text, members)
    return mentioned.ifEmpty { members.map { it.id } }
}

/**
 * The message a member receives: the channel's name and note first, so the bot knows it was
 * addressed in a channel, then the user's text unchanged.
 */
fun channelMessageText(channel: BotChannel, memberNames: List<String>, text: String): String = buildString {
    append("[Channel: ").append(channel.name.ifBlank { "Channel" }).append(']')
    if (memberNames.isNotEmpty()) append(" Members: ").append(memberNames.joinToString(", "))
    append('\n')
    if (channel.description.isNotBlank()) append("Channel note: ").append(channel.description.trim()).append('\n')
    append('\n')
    append(text.trim())
}
