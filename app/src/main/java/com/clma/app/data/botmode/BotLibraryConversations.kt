package com.clma.app.data.botmode

import com.clma.app.data.datastore.Settings
import com.clma.app.data.model.Assistant
import com.clma.app.data.model.isBot
import kotlin.uuid.Uuid

/**
 * Conversation side of the two assistant libraries. A conversation belongs to the library of
 * the assistant it is bound to (`conversation.assistantId`); a conversation whose assistant no
 * longer exists counts as classic, because every pre-existing record is classic. Nothing here
 * moves or deletes data: lists are only filtered at read time.
 */
fun Settings.isBotAssistantId(assistantId: Uuid): Boolean =
    assistants.any { it.id == assistantId && it.isBot }

/** Library of the entry the user is in right now: Bot mode shows bots, otherwise classic. */
fun Settings.activeLibraryIsBot(): Boolean = displaySetting.botModeEnabled

fun Settings.isInLibrary(assistantId: Uuid, bot: Boolean): Boolean = isBotAssistantId(assistantId) == bot

fun Settings.isInActiveLibrary(assistantId: Uuid): Boolean = isInLibrary(assistantId, activeLibraryIsBot())

/** Keeps only items whose conversation belongs to [bot]'s library. */
fun <T> List<T>.inLibrary(settings: Settings, bot: Boolean, assistantIdOf: (T) -> Uuid): List<T> =
    filter { settings.isInLibrary(assistantIdOf(it), bot) }

/**
 * SQL-friendly assistant scope for paged queries (message search), so LIMIT/OFFSET stay right.
 * Bot library = an allow-list of bot ids; classic library = a deny-list of bot ids (so orphaned
 * conversations of deleted assistants stay visible in classic mode, as before).
 */
data class AssistantScope(
    val include: Set<String>? = null,
    val exclude: Set<String> = emptySet(),
) {
    fun allows(assistantId: String): Boolean =
        (include == null || assistantId in include) && assistantId !in exclude

    /** True when nothing can match (Bot library with no bots). */
    val isEmpty: Boolean get() = include != null && include.isEmpty()

    companion object {
        val ALL = AssistantScope()
    }
}

fun Settings.libraryScope(bot: Boolean): AssistantScope {
    val botIds = assistants.filter { it.isBot }.map { it.id.toString() }.toSet()
    return if (bot) AssistantScope(include = botIds) else AssistantScope(exclude = botIds)
}

fun Settings.activeLibraryScope(): AssistantScope = libraryScope(activeLibraryIsBot())

/** Scope for a model-facing tool running as [assistantId]: the library that assistant lives in. */
fun Settings.libraryScopeOf(assistantId: Uuid): AssistantScope = libraryScope(isBotAssistantId(assistantId))

/** Assistant pickers outside a library's own pages (e.g. the share sheet): the active library only. */
fun Settings.activeLibraryAssistants(): List<Assistant> =
    if (activeLibraryIsBot()) botsForContactList() else classicAssistants()
