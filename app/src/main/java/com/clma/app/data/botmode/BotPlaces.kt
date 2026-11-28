// Adapted from OpenMausBot (https://github.com/milind-soni/OpenMausBot), server/routes/sidebar-sections.ts
// Copyright 2026 Milind Soni and OpenMausBot contributors, Apache License 2.0 (third_party/openmausbot/LICENSE). Modified: ported to Kotlin and changed for CLmA.
package com.clma.app.data.botmode

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
enum class BotPlaceColor { DEFAULT, BLUE, GREEN, PURPLE, ORANGE }

@Serializable
data class BotPlace(
    val id: Uuid = Uuid.random(),
    val name: String,
    val color: BotPlaceColor = BotPlaceColor.DEFAULT,
    val botIds: Set<Uuid> = emptySet(),
    val channelIds: Set<Uuid> = emptySet(),
)

internal fun String.isBotPlaceName() = trim().length in 1..60 && none(Char::isISOControl)

/** Apply only the membership changes made in the editor. Adding an item moves it from its old place. */
internal fun List<BotPlace>.saveBotPlace(
    initial: BotPlace?,
    draft: BotPlace,
    validBots: Set<Uuid>,
    validChannels: Set<Uuid>,
): List<BotPlace> {
    val name = draft.name.trim()
    if (!draft.name.isBotPlaceName()) return this
    val current = firstOrNull { it.id == draft.id }
    if ((initial == null) != (current == null)) return this

    val nextName = if (initial?.name == name && current != null) current.name else name
    if (any { it.id != draft.id && it.name.equals(nextName, ignoreCase = true) }) return this

    val addedBots = (draft.botIds - initial?.botIds.orEmpty()).intersect(validBots)
    val addedChannels = (draft.channelIds - initial?.channelIds.orEmpty()).intersect(validChannels)
    val removedBots = initial?.botIds.orEmpty() - draft.botIds
    val removedChannels = initial?.channelIds.orEmpty() - draft.channelIds
    val saved = draft.copy(
        name = nextName,
        color = if (initial?.color == draft.color && current != null) current.color else draft.color,
        botIds = (current?.botIds.orEmpty() - removedBots + addedBots).intersect(validBots),
        channelIds = (current?.channelIds.orEmpty() - removedChannels + addedChannels).intersect(validChannels),
    )
    val updated = map { place ->
        if (place.id == draft.id) saved
        else place.copy(
            botIds = (place.botIds - addedBots).intersect(validBots),
            channelIds = (place.channelIds - addedChannels).intersect(validChannels),
        )
    }
    return if (current == null) updated + saved else updated
}
