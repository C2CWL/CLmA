package com.clma.app.data.chat

import android.content.Context
import androidx.core.content.edit
import kotlin.uuid.Uuid

/**
 * Per-conversation input drafts.
 *
 * Why this lives outside `ChatVM`: the VM is created per conversation and destroyed on switch,
 * so its `ChatInputState` takes the unsent text with it. Users type a paragraph, hop to another
 * chat to check something, come back — and the paragraph is gone. Persisting the text keyed by
 * conversation id is the only state that has to survive that hop.
 *
 * Scope: the *text* only. Attachments are files on disk that the user can re-pick, and silently
 * resurrecting an unreferenced copy is worse than losing the chip. Editing an existing message
 * never writes a draft (see [ChatVM]'s save guard) — the edit box would otherwise overwrite the
 * real draft with someone else's history.
 *
 * One SharedPreferences entry per non-blank draft; the key is a fixed prefix plus the uuid so a
 * cleared draft removes its row instead of writing an empty string.
 */
class ChatDrafts(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun get(conversationId: Uuid): String = prefs.getString(key(conversationId), null).orEmpty()

    /** Blank text clears the row: an empty draft is indistinguishable from none. */
    fun save(conversationId: Uuid, text: String) {
        prefs.edit {
            if (text.isBlank()) remove(key(conversationId)) else putString(key(conversationId), text)
        }
    }

    fun clear(conversationId: Uuid) {
        prefs.edit { remove(key(conversationId)) }
    }

    private fun key(conversationId: Uuid) = "$PREFIX$conversationId"

    companion object {
        const val PREFS_NAME = "chat_drafts"
        private const val PREFIX = "draft_"
    }
}