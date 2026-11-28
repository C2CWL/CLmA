package com.clma.app.data.botmode.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.botSettingsDataStore by preferencesDataStore(name = "bot_mode_settings")
private val Context.botProfilesDataStore by preferencesDataStore(name = "bot_profiles")
private val documentKey = stringPreferencesKey("settings")

class BotModeSettingsStore(context: Context) {
    private val dataStore = context.applicationContext.botSettingsDataStore
    val settings: Flow<BotModeSettings> = dataStore.data
        .map { BotSettingsCodec.decodeSettings(it[documentKey]) }.distinctUntilChanged()

    suspend fun update(transform: (BotModeSettings) -> BotModeSettings) {
        dataStore.edit { preferences ->
            val previous = BotSettingsCodec.decodeSettings(preferences[documentKey])
            val next = transform(previous)
            if (next != previous) preferences[documentKey] = BotSettingsCodec.encodeSettings(next)
        }
    }
}

class BotProfileStore(context: Context) {
    private val dataStore = context.applicationContext.botProfilesDataStore
    val profiles: Flow<Map<String, BotProfile>> = dataStore.data
        .map { BotSettingsCodec.decodeProfiles(it[documentKey]) }.distinctUntilChanged()

    suspend fun profile(botId: String): BotProfile = profiles.first()[botId] ?: BotProfile()

    suspend fun update(botId: String, transform: (BotProfile) -> BotProfile) {
        dataStore.edit { preferences ->
            val previous = BotSettingsCodec.decodeProfiles(preferences[documentKey])
            val next = transform(previous[botId] ?: BotProfile())
            require(next.soul.length <= BOT_SOUL_MAX_LENGTH)
            require(next.description.length <= BOT_DESCRIPTION_MAX_LENGTH)
            require(next.alwaysAllow.size <= BOT_ALWAYS_ALLOW_MAX)
            require(next.reportsTo != botId)
            val updated = withBotProfile(previous, botId, next)
            if (updated != previous) preferences[documentKey] = BotSettingsCodec.encodeProfiles(updated)
        }
    }

    suspend fun allow(botId: String, toolName: String): Boolean {
        var accepted = false
        update(botId) { profile ->
            val next = withAlwaysAllowTool(profile.alwaysAllow, toolName)
            accepted = next != null
            if (next == null) profile else profile.copy(alwaysAllow = next)
        }
        return accepted
    }
}

// A settings read failure must not turn a completed generation or routine into a failed run.
internal suspend fun BotModeSettingsStore.notificationSettings(): BotModeSettings =
    try {
        settings.first()
    } catch (e: java.io.IOException) {
        android.util.Log.w("BotModeSettingsStore", "Could not read notification settings", e)
        BotModeSettings(
            notifyReplies = false,
            notifyRoutineResults = false,
            notifyApprovals = false,
            notifyFailures = false,
        )
    }

internal fun requiresBotToolConsent(toolName: String): Boolean =
    com.clma.app.connectors.ConnectorWriteApproval.requiresApproval(toolName) ||
        toolName in com.clma.app.data.ai.tools.ToolApprovalDefaults.NO_ALWAYS_ALLOW

internal fun com.clma.ai.ui.UIMessagePart.Tool.canGrantForBot(): Boolean =
    !BotToolNames.asksHuman(toolName) && !BotToolNames.isOutbound(toolName) && !requiresBotToolConsent(toolName) &&
        !com.clma.app.data.ai.tools.HardlineCommandGuard.requiresSshPowerApproval(toolName, input)
