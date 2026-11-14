package com.clma.app.ui.pages.chat

import com.clma.app.data.model.Conversation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.uuid.Uuid

/** Only drawer-relevant fields: never retain the streaming message tree in drawer state. */
internal data class DrawerConversationMetadata(
    val conversationId: Uuid,
    val assistantId: Uuid,
    val agentModeEnabled: Boolean,
)

internal fun Conversation.toDrawerMetadata() = DrawerConversationMetadata(id, assistantId, agentModeEnabled)

internal fun Flow<Conversation>.drawerMetadataFlow(): Flow<DrawerConversationMetadata> =
    map { it.toDrawerMetadata() }.distinctUntilChanged()
