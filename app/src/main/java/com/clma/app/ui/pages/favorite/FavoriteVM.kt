package com.clma.app.ui.pages.favorite

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.clma.app.data.botmode.activeLibraryIsBot
import com.clma.app.data.botmode.isBotAssistantId
import com.clma.app.data.datastore.SettingsStore
import com.clma.app.data.db.entity.FavoriteEntity
import com.clma.app.data.repository.ConversationRepository
import com.clma.app.data.favorite.NodeFavoriteAdapter
import com.clma.app.data.model.FavoriteType
import com.clma.app.data.repository.FavoriteRepository
import kotlin.uuid.Uuid

data class NodeFavoriteListItem(
    val id: String,
    val refKey: String,
    val conversationId: Uuid,
    val nodeId: Uuid,
    val conversationTitle: String,
    val preview: String,
    val createdAt: Long,
)

@OptIn(ExperimentalCoroutinesApi::class)
class FavoriteVM(
    private val favoriteRepository: FavoriteRepository,
    private val conversationRepository: ConversationRepository,
    private val settingsStore: SettingsStore,
) : ViewModel() {
    // Favorites are global records; the list only shows the active library's (classic vs Bot).
    // A favorite whose conversation is gone keeps showing in classic mode, as before.
    val nodeFavorites = combine(
        favoriteRepository.listByType(FavoriteType.NODE),
        settingsStore.settingsFlow,
    ) { favorites, settings -> favorites to settings }
        .mapLatest { (favorites, settings) ->
            val bot = settings.activeLibraryIsBot()
            val assistantOf = mutableMapOf<Uuid, Uuid?>()
            favorites.mapNotNull { entity ->
                val ref = NodeFavoriteAdapter.decodeRef(entity) ?: return@mapNotNull null
                val owner = assistantOf.getOrPut(ref.conversationId) {
                    conversationRepository.getAssistantIdOfConversation(ref.conversationId)
                }
                val isBot = owner != null && settings.isBotAssistantId(owner)
                if (isBot != bot) return@mapNotNull null
                val meta = NodeFavoriteAdapter.decodeMeta(entity)

                NodeFavoriteListItem(
                    id = entity.id,
                    refKey = entity.refKey,
                    conversationId = ref.conversationId,
                    nodeId = ref.nodeId,
                    conversationTitle = meta?.title.orEmpty(),
                    preview = meta?.previewText ?: "",
                    createdAt = entity.createdAt,
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun removeFavorite(refKey: String) {
        viewModelScope.launch {
            favoriteRepository.deleteByRefKey(refKey)
        }
    }

    suspend fun getEntityByRefKey(refKey: String): FavoriteEntity? {
        return favoriteRepository.getByRefKey(refKey)
    }

    fun restoreFavorite(entity: FavoriteEntity) {
        viewModelScope.launch {
            favoriteRepository.upsert(entity)
        }
    }
}
