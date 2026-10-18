package me.rerere.rikkahub.data.datastore

import android.content.Context
import android.util.Log
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.IOException
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import io.pebbletemplates.pebble.PebbleEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.decodeFromJsonElement
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.registry.ModelRegistry
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.AssistantResolver
import me.rerere.rikkahub.data.ai.ContextWindowResolver
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.gemini.DENIED_MODEL_IDS
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_COMPRESS_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_OCR_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_SUGGESTION_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_TITLE_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_TRANSLATION_PROMPT
import me.rerere.rikkahub.data.ai.prompts.LEARNING_MODE_PROMPT
import me.rerere.asr.ASRProviderSetting
import me.rerere.rikkahub.data.datastore.migration.PreferenceStoreV1Migration
import me.rerere.rikkahub.data.datastore.migration.PreferenceStoreV2Migration
import me.rerere.rikkahub.data.datastore.migration.PreferenceStoreV3Migration
import me.rerere.rikkahub.data.datastore.migration.PreferenceStoreV4Migration
import me.rerere.rikkahub.data.datastore.migration.PreferenceStoreV5Migration
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.QuickMessage
import me.rerere.rikkahub.data.model.Tag
import me.rerere.rikkahub.data.sync.BackupItem
import me.rerere.rikkahub.data.sync.s3.S3Config
import me.rerere.rikkahub.subagent.SubAgentDefaults
import me.rerere.rikkahub.subagent.SubAgentDispatchProfile
import me.rerere.rikkahub.ui.theme.CustomTheme
import me.rerere.rikkahub.ui.theme.PresetThemes
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.toMutableStateFlow
import me.rerere.search.SearchCommonOptions
import me.rerere.search.SearchServiceOptions
import me.rerere.tts.provider.TTSProviderSetting
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.io.File
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

enum class AutoCompactionThresholdMode {
    PERCENT,
    TOKENS,
}

/** Default automatic compaction summary target as a percentage of the active context. */
const val DEFAULT_CONTEXT_COMPACTION_TARGET_PERCENT = 1

private const val TAG = "PreferencesStore"

/**
 * Per-entry tolerant decode for the persisted `providers` list.
 *
 * Why this exists: the providers list is stored in DataStore as a single JSON array of
 * polymorphic [ProviderSetting] entries. A single-shot `decodeFromString<List<ProviderSetting>>`
 * throws on the entire list as soon as one element has an unknown polymorphic discriminator.
 *
 * Concrete trigger that motivated this: the never-shipped Phase-22A scaffolding seeded a
 * `"type":"local_llamacpp"` entry into DEFAULT_PROVIDERS for early test installs. Deleting
 * the `LlamaCppLocal` subclass would otherwise make decode-of-list throw on those entries
 * → user loses ALL their saved providers (API keys, custom models, the lot).
 *
 * Per-entry decode lets surviving entries land while the unknown one is logged and skipped.
 * Keep this even though `local_llamacpp` now ships: it's good hygiene for any future
 * polymorphic schema change (renamed types, removed types, etc).
 */
private fun decodeProvidersTolerant(raw: String): List<ProviderSetting> {
    if (raw.isBlank()) return emptyList()
    val array = runCatching {
        JsonInstant.parseToJsonElement(raw) as? JsonArray
    }.getOrNull() ?: return emptyList()
    return array.mapNotNull { element ->
        try {
            JsonInstant.decodeFromJsonElement<ProviderSetting>(element)
        } catch (e: SerializationException) {
            Log.w(TAG, "Skipping unrecognised provider entry during decode: ${e.message}")
            null
        }
    }
}

/**
 * `models` transform for the GeminiOAuth normalization branch below: de-duplicate by id, then
 * drop any entry whose modelId is in [DENIED_MODEL_IDS].
 *
 * The fetch-side filter in `GeminiProvider.fetchAvailableModels` only stops
 * `gemini-3.1-pro-high` being re-added; a copy already persisted before that filter existed
 * survives the merge in `mergeCodexModels` untouched. This runs on every settings load, so it
 * evicts the stale entry read-side, without a migration or rewriting the persisted JSON.
 */
private fun dropDeniedGeminiOAuthModels(models: List<Model>): List<Model> =
    models.distinctBy { model -> model.id }.filterNot { model -> model.modelId in DENIED_MODEL_IDS }

private const val SETTINGS_STORE_NAME = "settings"

// 读取失败时的最大重试次数
private const val READ_MAX_RETRIES = 3

@Volatile
private var settingsDataStore: DataStore<Preferences>? = null

// 进程内单例, 同一文件只能存在一个 DataStore 实例
private val Context.settingsStore: DataStore<Preferences>
    get() = settingsDataStore ?: synchronized(SettingsStore::class) {
        settingsDataStore ?: createSettingsDataStore(applicationContext).also { settingsDataStore = it }
    }

private fun createSettingsDataStore(context: Context): DataStore<Preferences> {
    val file = context.preferencesDataStoreFile(SETTINGS_STORE_NAME)
    return PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { exception ->
            // 文件已损坏无法解析, 先留一份原文件用于排查/抢救, 再重建为空
            Log.e(TAG, "Settings datastore corrupted, resetting", exception)
            runCatching {
                file.copyTo(File(file.parentFile, "${file.name}.corrupt-${System.currentTimeMillis()}"))
            }.onFailure {
                Log.e(TAG, "Failed to backup corrupted settings file", it)
            }
            emptyPreferences()
        },
        migrations = listOf(
            PreferenceStoreV1Migration(),
            PreferenceStoreV2Migration(),
            PreferenceStoreV3Migration(),
            PreferenceStoreV4Migration(),
            PreferenceStoreV5Migration(),
        ),
        produceFile = { file },
    )
}

class SettingsStore(
    context: Context,
    scope: AppScope,
) : KoinComponent {
    companion object {
        // 版本号
        val VERSION = intPreferencesKey("data_version")

        // UI设置
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val THEME_ID = stringPreferencesKey("theme_id")
        val CUSTOM_THEMES = stringPreferencesKey("custom_themes")
        val DISPLAY_SETTING = stringPreferencesKey("display_setting")
        val NETWORK_SETTING = stringPreferencesKey("network_setting")
        val DEVELOPER_MODE = booleanPreferencesKey("developer_mode")

        // 模型选择
        val FAVORITE_MODELS = stringPreferencesKey("favorite_models")
        val SELECT_MODEL = stringPreferencesKey("chat_model")
        val FAST_MODEL = stringPreferencesKey("fast_model")
        val FAST_MODEL_REASONING_LEVEL = stringPreferencesKey("fast_model_reasoning_level")
        val TRANSLATE_MODEL = stringPreferencesKey("translate_model")
        val ENABLE_SUGGESTION = booleanPreferencesKey("enable_suggestion")
        val IMAGE_GENERATION_MODEL = stringPreferencesKey("image_generation_model")
        val VIDEO_GENERATION_MODEL = stringPreferencesKey("video_generation_model")
        val TITLE_PROMPT = stringPreferencesKey("title_prompt")
        val TRANSLATION_PROMPT = stringPreferencesKey("translation_prompt")
        val TRANSLATE_THINKING_BUDGET = intPreferencesKey("translate_thinking_budget")
        val SUGGESTION_PROMPT = stringPreferencesKey("suggestion_prompt")
        val OCR_MODEL = stringPreferencesKey("ocr_model")
        val OCR_PROMPT = stringPreferencesKey("ocr_prompt")
        val COMPRESS_MODEL = stringPreferencesKey("compress_model")
        val COMPRESS_PROMPT = stringPreferencesKey("compress_prompt")
        val ENABLE_AUTO_COMPACTION = booleanPreferencesKey("enable_auto_compaction")
        val AUTO_COMPACTION_THRESHOLD_MODE = stringPreferencesKey("auto_compaction_threshold_mode")
        val AUTO_COMPACTION_THRESHOLD_PERCENT = intPreferencesKey("auto_compaction_threshold_percent")
        val AUTO_COMPACTION_THRESHOLD_TOKENS_K = intPreferencesKey("auto_compaction_threshold_tokens_k")
        val AUTO_COMPACTION_KEEP_RECENT_TOOL_CALLS = intPreferencesKey("auto_compaction_keep_recent_tool_calls")
        val CONTEXT_COMPACTION_TARGET_TOKENS_K = intPreferencesKey("context_compaction_target_tokens_k")
        val RESPONSE_STREAM_MAX_RETRIES = intPreferencesKey("response_stream_max_retries")

        // 提供商
        val PROVIDERS = stringPreferencesKey("providers")
        // IDs of built-in providers the user explicitly deleted; the re-seed pass
        // skips these so deletions are sticky across app restarts.
        val DELETED_BUILTIN_PROVIDER_IDS = stringPreferencesKey("deleted_builtin_provider_ids")
        // Names of bundled skills the user explicitly deleted; the seed pass skips these
        // (see Settings.deletedBundledSkills). Missing key -> emptySet(), no migration.
        val DELETED_BUNDLED_SKILLS = stringPreferencesKey("deleted_bundled_skills")
        val SKILL_GROUPS = stringPreferencesKey("skill_groups")

        // 助手
        val SELECT_ASSISTANT = stringPreferencesKey("select_assistant")
        val ASSISTANTS = stringPreferencesKey("assistants")
        val ASSISTANT_TAGS = stringPreferencesKey("assistant_tags")
        // 这两个字段此前只存在于 `Settings` 数据类里, 没有对应 key, 也就没进逐键读写管道:
        // 值只活在 settingsFlow 的内存快照中, 本进程内看着生效, 重启/升级后又回到默认值
        // (子智能体归档夹显示「不归入」; 默认技能播种标记归零). 由
        // SettingsPersistenceCoverageTest 守住这条不变量。
        val SUB_AGENT_ARCHIVE_FOLDERS = stringPreferencesKey("sub_agent_archive_folders")
        val AUTO_ENABLED_DEFAULT_SKILLS = stringPreferencesKey("auto_enabled_default_skills")
        /**
         * Global (all-assistants) ceiling on concurrently running sub-agents. Stored as its own
         * top-level preference, so it needs the same three-place wiring as every other Settings
         * field: this key, the write in [persistSettings], and the read when [Settings] is rebuilt.
         */
        val SUB_AGENT_GLOBAL_CONCURRENCY_CAP = intPreferencesKey("sub_agent_global_concurrency_cap")
        val SUB_AGENT_DISPATCH_PROFILE = stringPreferencesKey("sub_agent_dispatch_profile")

        // 搜索
        val SEARCH_SERVICES = stringPreferencesKey("search_services")
        val SEARCH_COMMON = stringPreferencesKey("search_common")
        val SEARCH_SELECTED = intPreferencesKey("search_selected")
        val ENABLE_WEB_FETCH_TOOLS = booleanPreferencesKey("enable_web_fetch_tools")
        val PARSE_TEXT_TOOL_CALLS = booleanPreferencesKey("parse_text_tool_calls")

        // MCP
        val MCP_SERVERS = stringPreferencesKey("mcp_servers")

        // WebDAV
        val WEBDAV_CONFIG = stringPreferencesKey("webdav_config")

        // S3
        val S3_CONFIG = stringPreferencesKey("s3_config")

        // TTS
        val TTS_PROVIDERS = stringPreferencesKey("tts_providers")
        val SELECTED_TTS_PROVIDER = stringPreferencesKey("selected_tts_provider")
        val DEFAULT_TTS_PLAYBACK_SPEED = floatPreferencesKey("default_tts_playback_speed")

        // ASR
        val ASR_PROVIDERS = stringPreferencesKey("asr_providers")
        val SELECTED_ASR_PROVIDER = stringPreferencesKey("selected_asr_provider")

        // Web Server
        val WEB_SERVER_ENABLED = booleanPreferencesKey("web_server_enabled")
        val WEB_SERVER_PORT = intPreferencesKey("web_server_port")
        val WEB_SERVER_JWT_ENABLED = booleanPreferencesKey("web_server_jwt_enabled")
        val WEB_SERVER_ACCESS_PASSWORD = stringPreferencesKey("web_server_access_password")
        val WEB_SERVER_LOCALHOST_ONLY = booleanPreferencesKey("web_server_localhost_only")

        // Floating ball (always-available quick chat)
        val FLOATING_BALL_ENABLED = booleanPreferencesKey("floating_ball_enabled")
        val FLOATING_BALL_IDLE_SECONDS = intPreferencesKey("floating_ball_idle_seconds")
        val FLOATING_BALL_HIDDEN_ALPHA = intPreferencesKey("floating_ball_hidden_alpha")
        // Empty string = follow the app theme; otherwise an ARGB int as text.
        val FLOATING_BALL_COLOR = stringPreferencesKey("floating_ball_color")
        // Absolute path to a user-picked ball image inside the app's files dir; empty = default glyph.
        val FLOATING_BALL_ICON_PATH = stringPreferencesKey("floating_ball_icon_path")

        // AI logging
        val AI_LOG_LEVEL = stringPreferencesKey("ai_log_level")

        // 提示词注入
        val MODE_INJECTIONS = stringPreferencesKey("mode_injections")
        val LOREBOOKS = stringPreferencesKey("lorebooks")
        val QUICK_MESSAGES = stringPreferencesKey("quick_messages")

        // 备份提醒
        val BACKUP_REMINDER_CONFIG = stringPreferencesKey("backup_reminder_config")

        // 统计
        val LAUNCH_COUNT = intPreferencesKey("launch_count")

        // Uses the same DataStore singleton without starting settings flows or requiring Koin.
        internal suspend fun restoreBeforeInitialization(context: Context, settings: Settings) {
            require(!settings.init) { "Cannot restore uninitialized settings" }
            persistSettings(context.settingsStore, settings)
        }

        private suspend fun persistSettings(dataStore: DataStore<Preferences>, settings: Settings) {
            dataStore.edit { preferences ->
                preferences[DYNAMIC_COLOR] = settings.dynamicColor
                preferences[THEME_ID] = settings.themeId
                preferences[CUSTOM_THEMES] = JsonInstant.encodeToString(settings.customThemes)
                preferences[DEVELOPER_MODE] = settings.developerMode
                preferences[DISPLAY_SETTING] = JsonInstant.encodeToString(
                    settings.displaySetting.copy(
                        pasteLongTextThreshold = settings.displaySetting.pasteLongTextThreshold.coerceIn(100, 10000)
                    )
                )
                preferences[NETWORK_SETTING] = JsonInstant.encodeToString(settings.networkSetting)

                preferences[FAVORITE_MODELS] = JsonInstant.encodeToString(settings.favoriteModels)
                preferences[SELECT_MODEL] = settings.chatModelId.toString()
                preferences[FAST_MODEL] = settings.fastModelId.toString()
                preferences[FAST_MODEL_REASONING_LEVEL] = settings.fastModelReasoningLevel.name
                preferences[TRANSLATE_MODEL] = settings.translateModeId.toString()
                preferences[ENABLE_SUGGESTION] = settings.enableSuggestion
                preferences[IMAGE_GENERATION_MODEL] = settings.imageGenerationModelId.toString()
                preferences[VIDEO_GENERATION_MODEL] = settings.videoGenerationModelId.toString()
                preferences[TITLE_PROMPT] = settings.titlePrompt
                preferences[TRANSLATION_PROMPT] = settings.translatePrompt
                preferences[TRANSLATE_THINKING_BUDGET] = settings.translateThinkingBudget
                preferences[SUGGESTION_PROMPT] = settings.suggestionPrompt
                preferences[OCR_MODEL] = settings.ocrModelId.toString()
                preferences[OCR_PROMPT] = settings.ocrPrompt
                preferences[COMPRESS_MODEL] = settings.compressModelId.toString()
                preferences[COMPRESS_PROMPT] = settings.compressPrompt
                preferences[ENABLE_AUTO_COMPACTION] = settings.enableAutoCompaction
                preferences[AUTO_COMPACTION_THRESHOLD_MODE] = settings.autoCompactionThresholdMode.name
                preferences[AUTO_COMPACTION_THRESHOLD_PERCENT] =
                    settings.autoCompactionThresholdPercent.coerceIn(5, 95)
                preferences[AUTO_COMPACTION_THRESHOLD_TOKENS_K] =
                    settings.autoCompactionThresholdTokensK.coerceIn(1, Int.MAX_VALUE / 1_000)
                preferences[AUTO_COMPACTION_KEEP_RECENT_TOOL_CALLS] =
                    settings.autoCompactionKeepRecentToolCalls.coerceIn(0, 1_000)
                settings.contextCompactionTargetTokensK?.let { targetTokensK ->
                    preferences[CONTEXT_COMPACTION_TARGET_TOKENS_K] =
                        targetTokensK.coerceIn(1, Int.MAX_VALUE / 1_000)
                } ?: preferences.remove(CONTEXT_COMPACTION_TARGET_TOKENS_K)
                preferences[RESPONSE_STREAM_MAX_RETRIES] = settings.responseStreamMaxRetries.coerceIn(0, 10)

                preferences[PROVIDERS] = JsonInstant.encodeToString(settings.providers)
                preferences[DELETED_BUILTIN_PROVIDER_IDS] = JsonInstant.encodeToString(
                    settings.deletedBuiltInProviderIds.map { it.toString() }.toSet()
                )
                preferences[DELETED_BUNDLED_SKILLS] = JsonInstant.encodeToString(settings.deletedBundledSkills)
                preferences[SKILL_GROUPS] = JsonInstant.encodeToString(settings.skillGroups)

                preferences[ASSISTANTS] = JsonInstant.encodeToString(settings.assistants)
                preferences[SELECT_ASSISTANT] = settings.assistantId.toString()
                preferences[ASSISTANT_TAGS] = JsonInstant.encodeToString(settings.assistantTags)
                preferences[SUB_AGENT_ARCHIVE_FOLDERS] =
                    JsonInstant.encodeToString(settings.subAgentArchiveFolders)
                preferences[AUTO_ENABLED_DEFAULT_SKILLS] =
                    JsonInstant.encodeToString(settings.autoEnabledDefaultSkills)
                preferences[SUB_AGENT_GLOBAL_CONCURRENCY_CAP] =
                    settings.subAgentGlobalConcurrencyCap.coerceIn(
                        SubAgentDefaults.MIN_GLOBAL_CONCURRENCY_CAP,
                        SubAgentDefaults.MAX_GLOBAL_CONCURRENCY_CAP,
                    )
                preferences[SUB_AGENT_DISPATCH_PROFILE] =
                    settings.subAgentDispatchProfile.name

                preferences[SEARCH_SERVICES] = JsonInstant.encodeToString(settings.searchServices)
                preferences[SEARCH_COMMON] = JsonInstant.encodeToString(settings.searchCommonOptions)
                // maxOf(0, size - 1) guards the empty-list case: a persisted "[]" for
                // search_services leaves searchServices empty (the ?: default only fires on a
                // missing key, not on an empty array), and coerceIn(0, -1) throws
                // IllegalArgumentException because min > max, crashing every settings write.
                preferences[SEARCH_SELECTED] =
                    settings.searchServiceSelected.coerceIn(0, maxOf(0, settings.searchServices.size - 1))
                preferences[ENABLE_WEB_FETCH_TOOLS] = settings.enableWebFetchTools
                preferences[PARSE_TEXT_TOOL_CALLS] = settings.parseTextToolCalls

                preferences[MCP_SERVERS] = JsonInstant.encodeToString(settings.mcpServers)
                preferences[WEBDAV_CONFIG] = JsonInstant.encodeToString(settings.webDavConfig)
                preferences[S3_CONFIG] = JsonInstant.encodeToString(settings.s3Config)
                preferences[TTS_PROVIDERS] = JsonInstant.encodeToString(settings.ttsProviders)
                settings.selectedTTSProviderId?.let {
                    preferences[SELECTED_TTS_PROVIDER] = it.toString()
                } ?: preferences.remove(SELECTED_TTS_PROVIDER)
                preferences[DEFAULT_TTS_PLAYBACK_SPEED] = settings.defaultTTSPlaybackSpeed.coerceIn(0.5f, 2.0f)
                preferences[ASR_PROVIDERS] = JsonInstant.encodeToString(settings.asrProviders)
                settings.selectedASRProviderId?.let {
                    preferences[SELECTED_ASR_PROVIDER] = it.toString()
                } ?: preferences.remove(SELECTED_ASR_PROVIDER)
                preferences[MODE_INJECTIONS] = JsonInstant.encodeToString(settings.modeInjections)
                preferences[LOREBOOKS] = JsonInstant.encodeToString(settings.lorebooks)
                preferences[QUICK_MESSAGES] = JsonInstant.encodeToString(settings.quickMessages)
                preferences[WEB_SERVER_ENABLED] = settings.webServerEnabled
                preferences[WEB_SERVER_PORT] = settings.webServerPort
                preferences[WEB_SERVER_JWT_ENABLED] = settings.webServerJwtEnabled
                preferences[WEB_SERVER_ACCESS_PASSWORD] = settings.webServerAccessPassword
                preferences[WEB_SERVER_LOCALHOST_ONLY] = settings.webServerLocalhostOnly
                preferences[FLOATING_BALL_ENABLED] = settings.floatingBallEnabled
                preferences[FLOATING_BALL_IDLE_SECONDS] = settings.floatingBallIdleSeconds.coerceIn(0, 60)
                preferences[FLOATING_BALL_HIDDEN_ALPHA] = settings.floatingBallHiddenAlpha.coerceIn(10, 100)
                preferences[FLOATING_BALL_COLOR] = settings.floatingBallColor?.toString() ?: ""
                preferences[FLOATING_BALL_ICON_PATH] = settings.floatingBallIconPath
                preferences[AI_LOG_LEVEL] = settings.aiLogLevel.preferenceName
                preferences[BACKUP_REMINDER_CONFIG] = JsonInstant.encodeToString(settings.backupReminderConfig)
                preferences[LAUNCH_COUNT] = settings.launchCount
            }
        }
    }

    private val dataStore = context.settingsStore

    // 读取失败时绝不能回退为空配置, 否则默认值会被当成用户数据写回, 覆盖全部设置
    // 偶发 IO 错误重试, 仍失败则向上抛出 (文件损坏由 corruptionHandler 处理)
    val settingsFlowRaw = dataStore.data
        .retryWhen { cause, attempt ->
            val shouldRetry = cause is IOException && cause !is CorruptionException && attempt < READ_MAX_RETRIES
            if (shouldRetry) {
                Log.w(TAG, "Failed to read settings, retrying (${attempt + 1}/$READ_MAX_RETRIES)", cause)
                delay((100L shl attempt.toInt()).milliseconds)
            }
            shouldRetry
        }.map { preferences ->
            Settings(
                favoriteModels = preferences[FAVORITE_MODELS]?.let { raw ->
                    runCatching { JsonInstant.decodeFromString<List<Uuid>>(raw) }.getOrElse {
                        Log.w(TAG, "Failed to decode favoriteModels, using default", it)
                        emptyList()
                    }
                } ?: emptyList(),
                chatModelId = preferences[SELECT_MODEL]?.let { runCatching { Uuid.parse(it) }.getOrNull() }
                    ?: DEFAULT_AUTO_MODEL_ID,
                fastModelId = preferences[FAST_MODEL]?.let { runCatching { Uuid.parse(it) }.getOrNull() }
                    ?: DEFAULT_AUTO_MODEL_ID,
                fastModelReasoningLevel = preferences[FAST_MODEL_REASONING_LEVEL]
                    ?.let { value -> ReasoningLevel.entries.find { it.name == value } }
                    ?: ReasoningLevel.AUTO,
                translateModeId = preferences[TRANSLATE_MODEL]?.let { runCatching { Uuid.parse(it) }.getOrNull() }
                    ?: DEFAULT_AUTO_MODEL_ID,
                enableSuggestion = preferences[ENABLE_SUGGESTION] != false,
                imageGenerationModelId = preferences[IMAGE_GENERATION_MODEL]?.let { runCatching { Uuid.parse(it) }.getOrNull() } ?: Uuid.random(),
                videoGenerationModelId = preferences[VIDEO_GENERATION_MODEL]?.let { runCatching { Uuid.parse(it) }.getOrNull() } ?: Uuid.random(),
                titlePrompt = preferences[TITLE_PROMPT] ?: DEFAULT_TITLE_PROMPT,
                translatePrompt = preferences[TRANSLATION_PROMPT] ?: DEFAULT_TRANSLATION_PROMPT,
                translateThinkingBudget = preferences[TRANSLATE_THINKING_BUDGET] ?: 0,
                suggestionPrompt = preferences[SUGGESTION_PROMPT] ?: DEFAULT_SUGGESTION_PROMPT,
                ocrModelId = preferences[OCR_MODEL]?.let { runCatching { Uuid.parse(it) }.getOrNull() } ?: Uuid.random(),
                ocrPrompt = preferences[OCR_PROMPT] ?: DEFAULT_OCR_PROMPT,
                compressModelId = preferences[COMPRESS_MODEL]?.let { runCatching { Uuid.parse(it) }.getOrNull() } ?: DEFAULT_AUTO_MODEL_ID,
                compressPrompt = preferences[COMPRESS_PROMPT] ?: DEFAULT_COMPRESS_PROMPT,
                assistantId = preferences[SELECT_ASSISTANT]?.let { runCatching { Uuid.parse(it) }.getOrNull() }
                    ?: DEFAULT_ASSISTANT_ID,
                enableAutoCompaction = preferences[ENABLE_AUTO_COMPACTION] == true,
                autoCompactionThresholdMode = preferences[AUTO_COMPACTION_THRESHOLD_MODE]
                    ?.let { value -> runCatching { AutoCompactionThresholdMode.valueOf(value) }.getOrNull() }
                    ?: AutoCompactionThresholdMode.PERCENT,
                autoCompactionThresholdPercent = (preferences[AUTO_COMPACTION_THRESHOLD_PERCENT] ?: 80)
                    .coerceIn(5, 95),
                autoCompactionThresholdTokensK = (preferences[AUTO_COMPACTION_THRESHOLD_TOKENS_K] ?: 8)
                    .coerceIn(1, Int.MAX_VALUE / 1_000),
                autoCompactionKeepRecentToolCalls =
                    (preferences[AUTO_COMPACTION_KEEP_RECENT_TOOL_CALLS] ?: 5).coerceIn(0, 1_000),
                contextCompactionTargetTokensK = preferences[CONTEXT_COMPACTION_TARGET_TOKENS_K]
                    ?.coerceIn(1, Int.MAX_VALUE / 1_000),
                responseStreamMaxRetries = (preferences[RESPONSE_STREAM_MAX_RETRIES] ?: 5)
                    .coerceIn(0, 10),
                assistantTags = preferences[ASSISTANT_TAGS]?.let { raw ->
                    runCatching { JsonInstant.decodeFromString<List<Tag>>(raw) }.getOrElse {
                        Log.w(TAG, "Failed to decode assistantTags, using default", it)
                        emptyList()
                    }
                } ?: emptyList(),
                providers = decodeProvidersTolerant(preferences[PROVIDERS] ?: "[]"),
                deletedBuiltInProviderIds = preferences[DELETED_BUILTIN_PROVIDER_IDS]
                    ?.let { raw ->
                        runCatching {
                            JsonInstant.decodeFromString<Set<String>>(raw)
                                .mapNotNull { runCatching { Uuid.parse(it) }.getOrNull() }
                                .toSet()
                        }.getOrNull()
                    } ?: emptySet(),
                deletedBundledSkills = preferences[DELETED_BUNDLED_SKILLS]?.let { raw ->
                    runCatching { JsonInstant.decodeFromString<Set<String>>(raw) }.getOrElse {
                        Log.w(TAG, "Failed to decode deletedBundledSkills, using default", it)
                        emptySet()
                    }
                } ?: emptySet(),
                skillGroups = preferences[SKILL_GROUPS]?.let { raw ->
                    runCatching { JsonInstant.decodeFromString<List<SkillGroup>>(raw) }.getOrElse {
                        Log.w(TAG, "Failed to decode skillGroups, using default", it)
                        emptyList()
                    }
                } ?: emptyList(),
                assistants = runCatching {
                    JsonInstant.decodeFromString<List<Assistant>>(preferences[ASSISTANTS] ?: "[]")
                }.getOrElse {
                    Log.w(TAG, "Failed to decode assistants, using default", it)
                    emptyList()
                },
                subAgentArchiveFolders = preferences[SUB_AGENT_ARCHIVE_FOLDERS]?.let { raw ->
                    runCatching { JsonInstant.decodeFromString<Map<String, String>>(raw) }.getOrElse {
                        Log.w(TAG, "Failed to decode subAgentArchiveFolders, using empty", it)
                        emptyMap()
                    }
                } ?: emptyMap(),
                autoEnabledDefaultSkills = preferences[AUTO_ENABLED_DEFAULT_SKILLS]?.let { raw ->
                    runCatching { JsonInstant.decodeFromString<Set<String>>(raw) }.getOrElse {
                        Log.w(TAG, "Failed to decode autoEnabledDefaultSkills, using empty", it)
                        emptySet()
                    }
                } ?: emptySet(),
                subAgentGlobalConcurrencyCap =
                    (preferences[SUB_AGENT_GLOBAL_CONCURRENCY_CAP]
                        ?: SubAgentDefaults.GLOBAL_CONCURRENCY_CAP).coerceIn(
                        SubAgentDefaults.MIN_GLOBAL_CONCURRENCY_CAP,
                        SubAgentDefaults.MAX_GLOBAL_CONCURRENCY_CAP,
                    ),
                subAgentDispatchProfile = runCatching {
                    SubAgentDispatchProfile.valueOf(
                        preferences[SUB_AGENT_DISPATCH_PROFILE] ?: ""
                    )
                }.getOrDefault(SubAgentDispatchProfile.NEVER),
                dynamicColor = preferences[DYNAMIC_COLOR] != false,
                themeId = preferences[THEME_ID] ?: PresetThemes[0].id,
                customThemes = preferences[CUSTOM_THEMES]?.let { raw ->
                    runCatching { JsonInstant.decodeFromString<List<CustomTheme>>(raw) }.getOrElse {
                        Log.w(TAG, "Failed to decode customThemes, using default", it)
                        emptyList()
                    }
                } ?: emptyList(),
                developerMode = preferences[DEVELOPER_MODE] == true,
                // `enableLatexRendering` is forced on: rendering LaTeX is strictly better than
                // showing the raw `$...$` source, so the switch was removed and the stored value
                // ignored. The field is kept on DisplaySetting so old blobs still decode.
                displaySetting = runCatching {
                    JsonInstant.decodeFromString<DisplaySetting>(preferences[DISPLAY_SETTING] ?: "{}")
                }.getOrElse {
                    Log.w(TAG, "Failed to decode displaySetting, using default", it)
                    DisplaySetting()
                }.copy(enableLatexRendering = true),
                networkSetting = runCatching {
                    JsonInstant.decodeFromString<NetworkSetting>(preferences[NETWORK_SETTING] ?: "{}")
                }.getOrElse {
                    Log.w(TAG, "Failed to decode networkSetting, using default", it)
                    NetworkSetting()
                },
                searchServices = preferences[SEARCH_SERVICES]?.let { raw ->
                    runCatching { JsonInstant.decodeFromString<List<SearchServiceOptions>>(raw) }.getOrElse {
                        Log.w(TAG, "Failed to decode searchServices, using default", it)
                        listOf(SearchServiceOptions.DEFAULT)
                    }
                } ?: listOf(SearchServiceOptions.DEFAULT),
                searchCommonOptions = preferences[SEARCH_COMMON]?.let { raw ->
                    runCatching { JsonInstant.decodeFromString<SearchCommonOptions>(raw) }.getOrElse {
                        Log.w(TAG, "Failed to decode searchCommonOptions, using default", it)
                        SearchCommonOptions()
                    }
                } ?: SearchCommonOptions(),
                searchServiceSelected = preferences[SEARCH_SELECTED] ?: 0,
                enableWebFetchTools = preferences[ENABLE_WEB_FETCH_TOOLS] != false,
                parseTextToolCalls = preferences[PARSE_TEXT_TOOL_CALLS] != false,
                mcpServers = preferences[MCP_SERVERS]?.let { raw ->
                    runCatching { JsonInstant.decodeFromString<List<McpServerConfig>>(raw) }.getOrElse {
                        Log.w(TAG, "Failed to decode mcpServers, using default", it)
                        emptyList()
                    }
                } ?: emptyList(),
                webDavConfig = preferences[WEBDAV_CONFIG]?.let { raw ->
                    runCatching { JsonInstant.decodeFromString<WebDavConfig>(raw) }.getOrElse {
                        Log.w(TAG, "Failed to decode webDavConfig, using default", it)
                        WebDavConfig()
                    }
                } ?: WebDavConfig(),
                s3Config = preferences[S3_CONFIG]?.let { raw ->
                    runCatching { JsonInstant.decodeFromString<S3Config>(raw) }.getOrElse {
                        Log.w(TAG, "Failed to decode s3Config, using default", it)
                        S3Config()
                    }
                } ?: S3Config(),
                ttsProviders = preferences[TTS_PROVIDERS]?.let { raw ->
                    runCatching { JsonInstant.decodeFromString<List<TTSProviderSetting>>(raw) }.getOrElse {
                        Log.w(TAG, "Failed to decode ttsProviders, using default", it)
                        emptyList()
                    }
                } ?: emptyList(),
                selectedTTSProviderId = preferences[SELECTED_TTS_PROVIDER]?.let { runCatching { Uuid.parse(it) }.getOrNull() }
                    ?: DEFAULT_SYSTEM_TTS_ID,
                defaultTTSPlaybackSpeed = preferences[DEFAULT_TTS_PLAYBACK_SPEED]?.coerceIn(0.5f, 2.0f) ?: 1.0f,
                asrProviders = preferences[ASR_PROVIDERS]?.let { raw ->
                    runCatching { JsonInstant.decodeFromString<List<ASRProviderSetting>>(raw) }.getOrElse {
                        Log.w(TAG, "Failed to decode asrProviders, using default", it)
                        emptyList()
                    }
                } ?: emptyList(),
                selectedASRProviderId = preferences[SELECTED_ASR_PROVIDER]?.let { runCatching { Uuid.parse(it) }.getOrNull() },
                modeInjections = preferences[MODE_INJECTIONS]?.let { raw ->
                    runCatching { JsonInstant.decodeFromString<List<PromptInjection.ModeInjection>>(raw) }.getOrElse {
                        Log.w(TAG, "Failed to decode modeInjections, using default", it)
                        emptyList()
                    }
                } ?: emptyList(),
                lorebooks = preferences[LOREBOOKS]?.let { raw ->
                    runCatching { JsonInstant.decodeFromString<List<Lorebook>>(raw) }.getOrElse {
                        Log.w(TAG, "Failed to decode lorebooks, using default", it)
                        emptyList()
                    }
                } ?: emptyList(),
                quickMessages = preferences[QUICK_MESSAGES]?.let { raw ->
                    runCatching { JsonInstant.decodeFromString<List<QuickMessage>>(raw) }.getOrElse {
                        Log.w(TAG, "Failed to decode quickMessages, using default", it)
                        emptyList()
                    }
                } ?: emptyList(),
                webServerEnabled = preferences[WEB_SERVER_ENABLED] == true,
                webServerPort = preferences[WEB_SERVER_PORT] ?: 8080,
                webServerJwtEnabled = preferences[WEB_SERVER_JWT_ENABLED] == true,
                webServerAccessPassword = preferences[WEB_SERVER_ACCESS_PASSWORD] ?: "",
                webServerLocalhostOnly = preferences[WEB_SERVER_LOCALHOST_ONLY] == true,
                floatingBallEnabled = preferences[FLOATING_BALL_ENABLED] == true,
                floatingBallIdleSeconds = preferences[FLOATING_BALL_IDLE_SECONDS] ?: 3,
                floatingBallHiddenAlpha = preferences[FLOATING_BALL_HIDDEN_ALPHA] ?: 50,
                floatingBallColor = preferences[FLOATING_BALL_COLOR]?.takeIf { it.isNotBlank() }?.toIntOrNull(),
                floatingBallIconPath = preferences[FLOATING_BALL_ICON_PATH] ?: "",
                aiLogLevel = AiLogLevel.fromPreference(preferences[AI_LOG_LEVEL]),
                backupReminderConfig = preferences[BACKUP_REMINDER_CONFIG]?.let { raw ->
                    runCatching { JsonInstant.decodeFromString<BackupReminderConfig>(raw) }.getOrElse {
                        Log.w(TAG, "Failed to decode backupReminderConfig, using default", it)
                        BackupReminderConfig()
                    }
                } ?: BackupReminderConfig(),
                launchCount = preferences[LAUNCH_COUNT] ?: 0,
            )
        }
        .map {
            val deletedDefaultIds = it.deletedBuiltInProviderIds
            var providers = it.providers.ifEmpty {
                DEFAULT_PROVIDERS.filter { p -> p.id !in deletedDefaultIds }
            }.toMutableList()
            // One-shot cleanup (2026-10): the upstream-sponsored built-in providers carrying
            // affiliate links (AiHubMix / APIMart / 小马算力 / 随想AI网关 / MaruCode / AckAI /
            // UnifyLLM) are no longer shipped in DEFAULT_PROVIDERS. Fresh installs never seed
            // them; existing installs already have them, so drop only the rows the user never
            // touched (still disabled, no API key, no models) and leave a configured one be.
            providers.removeAll { provider ->
                provider.builtIn &&
                    provider.id in REMOVED_SPONSORED_PROVIDER_IDS &&
                    !provider.enabled &&
                    provider.models.isEmpty() &&
                    when (provider) {
                        is ProviderSetting.OpenAI -> provider.apiKey.isBlank()
                        is ProviderSetting.Google -> provider.apiKey.isBlank()
                        is ProviderSetting.Claude -> provider.apiKey.isBlank()
                        else -> true
                    }
            }
            // For existing installs that pre-date the on-device AICore provider being
            // promoted to first-place, hoist it to the top so the user does not have to
            // scroll past every legacy aggregator to find it.
            val aicoreIndex = providers.indexOfFirst { it is ProviderSetting.AICore }
            if (aicoreIndex > 0) {
                val aicoreRow = providers.removeAt(aicoreIndex)
                providers.add(0, aicoreRow)
            }
            DEFAULT_PROVIDERS.forEach { defaultProvider ->
                if (defaultProvider.id in deletedDefaultIds) return@forEach
                if (providers.none { it.id == defaultProvider.id }) {
                    // On-device built-in providers (AICore, LiteRT) are pinned to the top of
                    // the list in the order they appear in DEFAULT_PROVIDERS. Remote provider
                    // defaults continue to append at the end so existing users see no
                    // reordering of their configured remote providers.
                    when (defaultProvider) {
                        is ProviderSetting.AICore -> providers.add(0, defaultProvider.copyProvider())
                        is ProviderSetting.LiteRtLocal -> {
                            // Insert right after AICore, or at 0 if AICore is absent.
                            // indexOfFirst returns -1 when absent; -1 + 1 = 0, so insert at 0.
                            val insertAt = providers.indexOfFirst { it is ProviderSetting.AICore } + 1
                            providers.add(insertAt, defaultProvider.copyProvider())
                        }
                        is ProviderSetting.LlamaCppLocal -> {
                            // Insert right after LiteRtLocal, so it groups with the other
                            // on-device provider instead of appending after every remote
                            // provider below. Falls back to right after AICore, or 0, if
                            // LiteRtLocal is absent - same absent-index fallback as above.
                            val insertAt = providers.indexOfFirst { it is ProviderSetting.LiteRtLocal }
                                .let { if (it >= 0) it + 1 else providers.indexOfFirst { p -> p is ProviderSetting.AICore } + 1 }
                            providers.add(insertAt, defaultProvider.copyProvider())
                        }
                        else -> providers.add(defaultProvider.copyProvider())
                    }
                }
            }
            providers = providers.map { provider ->
                val defaultProvider = DEFAULT_PROVIDERS.find { it.id == provider.id }
                if (defaultProvider != null) {
                    provider.copyProvider(
                        builtIn = defaultProvider.builtIn,
                        description = defaultProvider.description,
                        shortDescription = defaultProvider.shortDescription,
                    )
                } else provider
            }.toMutableList()
            var assistants = it.assistants.ifEmpty { DEFAULT_ASSISTANTS }.toMutableList()
            DEFAULT_ASSISTANTS.forEach { defaultAssistant ->
                if (assistants.none { it.id == defaultAssistant.id }) {
                    assistants.add(defaultAssistant.copy())
                }
            }
            // One-shot upgrade for existing installs that pre-date the agent-core auto-load:
            // if a default-IDed assistant has an empty enabledSkills, treat it as fresh and
            // pin agent-core. Users who deliberately added other skills are untouched.
            assistants = assistants.map { assistant ->
                val isDefault = DEFAULT_ASSISTANTS.any { it.id == assistant.id }
                if (isDefault && assistant.enabledSkills.isEmpty()) {
                    assistant.copy(enabledSkills = setOf("agent-core"))
                } else assistant
            }.toMutableList()
            // One-shot additive enable for newly-bundled default-on skills. Each name is added
            // to every default assistant exactly once, tracked in autoEnabledDefaultSkills, so a
            // user who later disables one is not re-opted-in on the next launch. A brand-new
            // skill cannot have been deliberately disabled before it shipped, so the first add is
            // always safe.
            val skillsToSeed = DEFAULT_AUTO_ENABLED_SKILLS - it.autoEnabledDefaultSkills
            if (skillsToSeed.isNotEmpty()) {
                assistants = assistants.map { assistant ->
                    if (DEFAULT_ASSISTANTS.any { d -> d.id == assistant.id }) {
                        assistant.copy(enabledSkills = assistant.enabledSkills + skillsToSeed)
                    } else assistant
                }.toMutableList()
            }
            val newAutoEnabled = it.autoEnabledDefaultSkills + DEFAULT_AUTO_ENABLED_SKILLS
            val ttsProviders = it.ttsProviders.ifEmpty { DEFAULT_TTS_PROVIDERS }.toMutableList()
            DEFAULT_TTS_PROVIDERS.forEach { defaultTTSProvider ->
                if (ttsProviders.none { provider -> provider.id == defaultTTSProvider.id }) {
                    ttsProviders.add(defaultTTSProvider.copyProvider())
                }
            }
            it.copy(
                providers = providers,
                assistants = assistants,
                autoEnabledDefaultSkills = newAutoEnabled,
                ttsProviders = ttsProviders,
            )
        }
        .map { settings ->
            // 去重并清理无效引用
            val validMcpServerIds = settings.mcpServers.map { it.id }.toSet()
            val validModeInjectionIds = settings.modeInjections.map { it.id }.toSet()
            val validLorebookIds = settings.lorebooks.map { it.id }.toSet()
            val validQuickMessageIds = settings.quickMessages.map { it.id }.toSet()
            val asrProviders = settings.asrProviders.distinctBy { it.id }
            settings.copy(
                providers = settings.providers.distinctBy { it.id }.map { provider ->
                    when (provider) {
                        is ProviderSetting.OpenAI -> provider.copy(
                            models = provider.models.distinctBy { model -> model.id }
                        )

                        is ProviderSetting.Google -> provider.copy(
                            models = provider.models.distinctBy { model -> model.id }
                        )

                        is ProviderSetting.Claude -> provider.copy(
                            models = provider.models.distinctBy { model -> model.id }
                        )

                        is ProviderSetting.AICore -> provider.copy(
                            models = provider.models.distinctBy { model -> model.id }
                        )

                        is ProviderSetting.LiteRtLocal -> provider.copy(
                            models = provider.models.distinctBy { model -> model.id }
                        )

                        is ProviderSetting.LlamaCppLocal -> provider.copy(
                            models = provider.models.distinctBy { model -> model.id }
                        )

                        is ProviderSetting.Codex -> provider.copy(
                            models = provider.models.distinctBy { model -> model.id }
                        )

                        is ProviderSetting.Grok -> provider.copy(
                            models = provider.models.distinctBy { model -> model.id }
                        )

                        is ProviderSetting.GeminiOAuth -> provider.copy(
                            models = dropDeniedGeminiOAuthModels(provider.models)
                        )
                    }
                },
                assistants = settings.assistants.distinctBy { it.id }.map { assistant ->
                    assistant.copy(
                        // 过滤掉不存在的 MCP 服务器 ID
                        mcpServers = assistant.mcpServers.filter { serverId ->
                            serverId in validMcpServerIds
                        }.toSet(),
                        // 过滤掉不存在的模式注入 ID
                        modeInjectionIds = assistant.modeInjectionIds.filter { id ->
                            id in validModeInjectionIds
                        }.toSet(),
                        // 过滤掉不存在的 Lorebook ID
                        lorebookIds = assistant.lorebookIds.filter { id ->
                            id in validLorebookIds
                        }.toSet(),
                        // 过滤掉不存在的快捷消息 ID
                        quickMessageIds = assistant.quickMessageIds.filter { id ->
                            id in validQuickMessageIds
                        }.toSet()
                    )
                },
                ttsProviders = settings.ttsProviders.distinctBy { it.id },
                asrProviders = asrProviders,
                selectedASRProviderId = settings.selectedASRProviderId
                    ?.takeIf { id -> asrProviders.any { provider -> provider.id == id } }
                    ?: asrProviders.firstOrNull()?.id,
                favoriteModels = settings.favoriteModels.filter { uuid ->
                    settings.providers.flatMap { it.models }.any { it.id == uuid }
                },
                modeInjections = settings.modeInjections.distinctBy { it.id },
                lorebooks = settings.lorebooks.distinctBy { it.id },
                quickMessages = settings.quickMessages.distinctBy { it.id },
            )
        }
        .onEach {
            get<PebbleEngine>().templateCache.invalidateAll()
        }

    val settingsFlow = settingsFlowRaw
        .distinctUntilChanged()
        .toMutableStateFlow(scope, Settings.dummy())

    suspend fun update(settings: Settings) {
        if(settings.init) {
            Log.w(TAG, "Cannot update dummy settings")
            return
        }
        transformLock.withLock {
            updateInternal(settings)
        }
    }

    /**
     * Unlocked write path: every caller must already hold [transformLock]. Writes disk
     * before memory: if `dataStore.edit` throws (IOException, disk full, a serialization
     * bug), `settingsFlow` must still match what's actually on disk rather than a value
     * that was never persisted, or observers would react to a change that silently rolls
     * back on the next app launch.
     */
    private suspend fun updateInternal(settings: Settings) {
        persistSettings(dataStore, settings)
        settingsFlow.value = settings
    }

    /** Serialises every settings write: both transform-based `update {fn}` calls and
     *  direct `update(Settings)` calls (e.g. a WebDAV/S3 restore replacing the whole
     *  settings object), so concurrent callers don't race each other. Without this lock,
     *  two writes dispatched in quick succession could both read `settingsFlow.value`
     *  before either has persisted, then each write its own delta off the same stale
     *  base: last writer wins, the earlier change is silently dropped. The most-visible
     *  repro: rapid-fire taps on per-assistant tool toggles where every other tap
     *  appeared to revert; a restore racing an in-flight transform update is the same
     *  class of bug with worse stakes. */
    private val transformLock = kotlinx.coroutines.sync.Mutex()

    suspend fun update(fn: (Settings) -> Settings) {
        transformLock.withLock {
            updateInternal(fn(settingsFlow.value))
        }
    }

    // 只原子地修改单个 key, 不能用 update() 写回整份快照
    suspend fun incrementLaunchCount(): Int {
        var count = 0
        dataStore.edit { preferences ->
            count = (preferences[LAUNCH_COUNT] ?: 0) + 1
            preferences[LAUNCH_COUNT] = count
        }
        return count
    }

    suspend fun updateAssistant(assistantId: Uuid) {
        dataStore.edit { preferences ->
            preferences[SELECT_ASSISTANT] = assistantId.toString()
        }
    }

    suspend fun updateAssistantModel(assistantId: Uuid, modelId: Uuid) {
        update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == assistantId) {
                        assistant.copy(chatModelId = modelId)
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    suspend fun updateAssistantReasoningLevel(assistantId: Uuid, reasoningLevel: ReasoningLevel) {
        update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == assistantId) {
                        assistant.copy(reasoningLevel = reasoningLevel)
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    suspend fun updateModelReasoningLevel(modelId: Uuid, reasoningLevel: ReasoningLevel) {
        update { settings ->
            settings.copy(
                providers = settings.providers.map { provider ->
                    provider.copyProvider(
                        models = provider.models.map { model ->
                            if (model.id == modelId) {
                                model.copy(reasoningLevel = reasoningLevel)
                            } else {
                                model
                            }
                        }
                    )
                }
            )
        }
    }

    suspend fun updateAssistantWebSearch(assistantId: Uuid, enabled: Boolean) {
        update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == assistantId) {
                        assistant.copy(enableWebSearch = enabled)
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    suspend fun updateAssistantMcpServers(assistantId: Uuid, mcpServers: Set<Uuid>) {
        update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == assistantId) {
                        assistant.copy(mcpServers = mcpServers)
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    suspend fun updateAssistantInjections(
        assistantId: Uuid,
        modeInjectionIds: Set<Uuid>,
        lorebookIds: Set<Uuid>,
        quickMessageIds: Set<Uuid> = emptySet(),
    ) {
        update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == assistantId) {
                        assistant.copy(
                            modeInjectionIds = modeInjectionIds,
                            lorebookIds = lorebookIds,
                            quickMessageIds = quickMessageIds,
                        )
                    } else {
                        assistant
                    }
                }
            )
        }
    }
}

@Serializable
data class Settings(
    @Transient
    val init: Boolean = false,
    val dynamicColor: Boolean = true,
    val themeId: String = PresetThemes[0].id,
    val customThemes: List<CustomTheme> = emptyList(),
    val developerMode: Boolean = false,
    val displaySetting: DisplaySetting = DisplaySetting(),
    val networkSetting: NetworkSetting = NetworkSetting(),
    val favoriteModels: List<Uuid> = emptyList(),
    val chatModelId: Uuid = Uuid.random(),
    val fastModelId: Uuid = Uuid.random(),
    val fastModelReasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
    val imageGenerationModelId: Uuid = Uuid.random(),
    val videoGenerationModelId: Uuid = Uuid.random(),
    val titlePrompt: String = DEFAULT_TITLE_PROMPT,
    val translateModeId: Uuid = Uuid.random(),
    val translatePrompt: String = DEFAULT_TRANSLATION_PROMPT,
    val translateThinkingBudget: Int = 0,
    val enableSuggestion: Boolean = true,
    val suggestionPrompt: String = DEFAULT_SUGGESTION_PROMPT,
    val ocrModelId: Uuid = Uuid.random(),
    val ocrPrompt: String = DEFAULT_OCR_PROMPT,
    val compressModelId: Uuid = Uuid.random(),
    val compressPrompt: String = DEFAULT_COMPRESS_PROMPT,
    val enableAutoCompaction: Boolean = false,
    val autoCompactionThresholdMode: AutoCompactionThresholdMode = AutoCompactionThresholdMode.PERCENT,
    val autoCompactionThresholdPercent: Int = 80,
    val autoCompactionThresholdTokensK: Int = 8,
    /** Number of most-recent executed tool calls kept raw during the first automatic pass. */
    val autoCompactionKeepRecentToolCalls: Int = 5,
    /**
     * Null uses [DEFAULT_CONTEXT_COMPACTION_TARGET_PERCENT] of the active context. A non-null
     * value is an explicit summary target stored in thousands of tokens for easy mobile editing.
     */
    val contextCompactionTargetTokensK: Int? = null,
    /** Additional attempts for Response API streams that fail before yielding content. */
    val responseStreamMaxRetries: Int = 5,
    val assistantId: Uuid = DEFAULT_ASSISTANT_ID,
    val providers: List<ProviderSetting> = DEFAULT_PROVIDERS,
    /**
     * IDs of built-in providers the user explicitly removed via long-press. The re-seed
     * pass on settings load skips these so deletions stick across app restarts. Without
     * this gate, deleting a default provider would just re-add it on next read.
     */
    val deletedBuiltInProviderIds: Set<Uuid> = emptySet(),
    val assistants: List<Assistant> = DEFAULT_ASSISTANTS,
    /**
     * P2-23 (D7) — assistantId -> the folder its sub-agent conversations are filed into.
     * Absent means "do not file". Kept in Settings rather than on `FolderEntity` so no
     * `AppDatabase` identity hash moves (see `ImportedDatabaseReconciler`).
     */
    val subAgentArchiveFolders: Map<String, String> = emptyMap(),
    /**
     * Global (all-assistants) ceiling on concurrently running sub-agents, on top of each
     * assistant's own [Assistant.maxConcurrentSubAgents]. Lives here rather than on the assistant
     * because it is a device-wide resource limit (provider rate limits, memory, battery), not a
     * property of any one assistant. Clamped to
     * [SubAgentDefaults.MIN_GLOBAL_CONCURRENCY_CAP]..[SubAgentDefaults.MAX_GLOBAL_CONCURRENCY_CAP].
     */
    val subAgentGlobalConcurrencyCap: Int = SubAgentDefaults.GLOBAL_CONCURRENCY_CAP,
    /**
     * The five-level dispatch posture (拍板: default NEVER). See [SubAgentDispatchProfile].
     */
    val subAgentDispatchProfile: SubAgentDispatchProfile = SubAgentDispatchProfile.NEVER,
    /**
     * Names of bundled default-on skills (see [DEFAULT_AUTO_ENABLED_SKILLS]) that have already
     * been seeded into the default assistants' enabledSkills exactly once. Mirrors
     * [deletedBuiltInProviderIds]: it lets a newly-shipped skill auto-enable on upgrade while
     * still respecting a later deliberate user-disable - once a name is recorded here it is
     * never re-added, so toggling it off sticks across launches.
     */
    val autoEnabledDefaultSkills: Set<String> = emptySet(),
    /**
     * Names of bundled skills the user explicitly deleted via [SkillManager.deleteSkill].
     * Recorded outside the skill directory (deletion wipes that directory, sentinel and
     * all), so [SkillManager.seedDefaultSkillsIfNeeded] can tell "never seeded" apart from
     * "seeded, then deleted" and skip re-creating it. A deliberate reinstall through the
     * skill catalog removes the name again.
     */
    val deletedBundledSkills: Set<String> = emptySet(),
    /**
     * User-created skill groups. Each group carries an on/off switch so a whole batch of
     * skills can be disabled in one tap without touching per-assistant `enabledSkills`.
     * See [SkillGroup] / [SkillGroupRules].
     */
    val skillGroups: List<SkillGroup> = emptyList(),
    val assistantTags: List<Tag> = emptyList(),
    val searchServices: List<SearchServiceOptions> = listOf(SearchServiceOptions.DEFAULT),
    val searchCommonOptions: SearchCommonOptions = SearchCommonOptions(),
    val searchServiceSelected: Int = 0,
    val enableWebFetchTools: Boolean = true,
    /**
     * Recover a tool call a model wrote as literal reply text (`<tool_call>{…}</tool_call>`)
     * instead of in the structured field. Some OpenAI-compatible gateways serve models whose chat
     * template asks for that shape and then stream it as ordinary content, which without this
     * would both show the markup to the user and never execute the tool. On by default; an escape
     * hatch for anyone whose model legitimately writes that markup in prose.
     */
    val parseTextToolCalls: Boolean = true,
    val mcpServers: List<McpServerConfig> = emptyList(),
    val webDavConfig: WebDavConfig = WebDavConfig(),
    val s3Config: S3Config = S3Config(),
    val ttsProviders: List<TTSProviderSetting> = DEFAULT_TTS_PROVIDERS,
    val selectedTTSProviderId: Uuid = DEFAULT_SYSTEM_TTS_ID,
    val defaultTTSPlaybackSpeed: Float = 1.0f,
    val asrProviders: List<ASRProviderSetting> = emptyList(),
    val selectedASRProviderId: Uuid? = null,
    val modeInjections: List<PromptInjection.ModeInjection> = DEFAULT_MODE_INJECTIONS,
    val lorebooks: List<Lorebook> = emptyList(),
    val quickMessages: List<QuickMessage> = emptyList(),
    val webServerEnabled: Boolean = false,
    val webServerPort: Int = 8080,
    val webServerJwtEnabled: Boolean = false,
    val webServerAccessPassword: String = "",
    val webServerLocalhostOnly: Boolean = false,
    /**
     * Always-available floating ball (quick chat overlay). Off by default: it needs
     * SYSTEM_ALERT_WINDOW and a persistent foreground service, so it stays strictly opt-in.
     */
    val floatingBallEnabled: Boolean = false,
    /**
     * Seconds the ball can stay untouched before it tucks itself into the screen edge (only half
     * of it remains visible, dimmed). Touching it wakes it back up without opening the panel.
     * `0` disables the auto-tuck.
     */
    val floatingBallIdleSeconds: Int = 3,
    /** Opacity, in percent, that the tucked-away ball fades to so it stays unobtrusive. */
    val floatingBallHiddenAlpha: Int = 50,
    /**
     * ARGB colour of the ball's default (glyph) look. `null` follows the app theme's primary
     * colour, so the ball re-tints itself whenever the theme / dynamic colour / dark mode changes.
     */
    val floatingBallColor: Int? = null,
    /**
     * Absolute path to a user-picked image shown as the whole ball (circular crop). Empty string
     * keeps the built-in themed glyph.
     */
    val floatingBallIconPath: String = "",
    val aiLogLevel: AiLogLevel = AiLogLevel.INFO,
    val backupReminderConfig: BackupReminderConfig = BackupReminderConfig(),
    val launchCount: Int = 0,
) {
    companion object {
        // 构造一个用于初始化的settings, 但它不能用于保存，防止使用初始值存储
        fun dummy() = Settings(init = true)
    }
}

@Serializable
enum class AiLogLevel(val preferenceName: String) {
    @SerialName("off")
    OFF("off"),
    @SerialName("info")
    INFO("info"),
    @SerialName("debug")
    DEBUG("debug");

    companion object {
        fun fromPreference(value: String?): AiLogLevel = entries.firstOrNull { it.preferenceName == value } ?: INFO
    }
}

@Serializable
data class NetworkSetting(
    val userAgent: String = "",
    val proxyUrl: String = "",
    val proxyUsername: String = "",
    val proxyPassword: String = "",
    val enableAutoRetry: Boolean = true,
    /**
     * Seconds to wait for the first byte of a streamed model reply before treating the
     * connection as dead and re-issuing the request on a fresh one. `0` disables the watchdog.
     * A silently-dead socket otherwise emits nothing and fails nothing, so the 10-minute
     * transport readTimeout is the only thing that would ever notice - the "thinking forever
     * with no content" report. Kept well above a legitimate long-context prefill.
     */
    val streamFirstOutputTimeoutSeconds: Int = 120,
    /**
     * Seconds to wait for the NEXT piece of a streamed reply once it has already started, before
     * treating it as stalled and recovering (continue the partial reply, or restart it). `0`
     * disables the watchdog. The first-output watchdog cannot see this case: as soon as the stream
     * emits anything - including its first reasoning delta - that one is disarmed for good, and a
     * reply that then goes quiet for minutes leaves the UI counting seconds with nothing to show
     * for it. This is a per-gap budget, not a cap on the whole reply.
     */
    val streamStallTimeoutSeconds: Int = 120,
) {
    companion object {
        /**
         * Upper bound for both silence watchdogs. Hand-typed values are clamped to this on the way
         * in, and the settings screen clamps to it too, so the two agree. Ten minutes matches the
         * shared OkHttp client's readTimeout: waiting longer than that cannot succeed.
         */
        const val MAX_WATCHDOG_SECONDS = 600
    }
}

@Serializable
enum class ChatFontFamily {
    @SerialName("default")
    DEFAULT,
    @SerialName("serif")
    SERIF,
    @SerialName("monospace")
    MONOSPACE,

    @SerialName("custom")
    CUSTOM,
}

@Serializable
data class DisplaySetting(
    val userAvatar: Avatar = Avatar.Dummy,
    val userNickname: String = "",
    val useAppIconStyleLoadingIndicator: Boolean = true,
    val showUserAvatar: Boolean = true,
    val showAssistantBubble: Boolean = false,
    val bubbleOpacity: Float = 1.0f,
    // 简洁布局：扁平顶栏 + 两行输入框。默认关，关时与原来完全一致。
    val cleanLayout: Boolean = false,
    val showModelIcon: Boolean = true,
    val showModelName: Boolean = true,
    val showDateTimeInMessage: Boolean = false,
    val showTokenUsage: Boolean = true,
    val showThinkingContent: Boolean = true,
    val autoCloseThinking: Boolean = true,
    // 思考/工具步骤组折叠时尾部保留几步，0 = 只显示一行标题。默认 2 即原来的行为。
    val thinkingStepsVisibleCount: Int = 2,
    val updateCheckDisabledUntilEpochMillis: Long = 0L,
    val showMessageJumper: Boolean = true,
    val messageJumperOnLeft: Boolean = false,
    val fontSizeRatio: Float = 1.0f,
    val enableMessageGenerationHapticEffect: Boolean = false,
    val skipCropImage: Boolean = true,
    val enableNotificationOnMessageGeneration: Boolean = false,
    val enableLiveUpdateNotification: Boolean = false,
    val codeBlockAutoWrap: Boolean = false,
    val codeBlockAutoCollapse: Boolean = false,
    val showLineNumbers: Boolean = false,
    val ttsOnlyReadQuoted: Boolean = false,
    val ttsOnlyReadOutsideBrackets: Boolean = false,
    val autoPlayTTSAfterGeneration: Boolean = false,
    val pasteLongTextAsFile: Boolean = false,
    val pasteLongTextThreshold: Int = 1000,
    val sendOnEnter: Boolean = false,
    val enableAutoScroll: Boolean = true,
    val enableLatexRendering: Boolean = true,
    val enableBlurEffect: Boolean = false,
    val chatFontFamily: ChatFontFamily = ChatFontFamily.DEFAULT,
    val chatCustomFontPath: String = "",
    val chatCustomFontName: String = "",
    val enableVolumeKeyScroll: Boolean = false,
    val volumeKeyScrollRatio: Float = 1.0f,
    val enableBounceOverscroll: Boolean = true,
)

@Serializable
data class WebDavConfig(
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val path: String = "rikkahub_backups",
    // Backed-up slices. New installs default to everything; a pre-granular `["DATABASE","FILES"]`
    // still decodes because BackupItem keeps the legacy FILES value, and is expanded on the next
    // write by BackupItem.normalize.
    val items: List<BackupItem> = BackupItem.selectable,
)

@Serializable
data class BackupReminderConfig(
    val enabled: Boolean = false,
    val intervalDays: Int = 7,
    val lastBackupTime: Long = 0L,
)

fun Settings.isNotConfigured() = providers.all { it.models.isEmpty() }

fun Settings.findModelById(uuid: Uuid?, fallback: Uuid? = null): Model? {
    if (uuid == null && fallback == null) return null
    return uuid?.let { this.providers.findModelById(it) }
        ?: fallback?.let { this.providers.findModelById(it) }
}

fun List<ProviderSetting>.findModelById(uuid: Uuid): Model? {
    this.forEach { setting ->
        setting.models.forEach { model ->
            if (model.id == uuid) {
                return model
            }
        }
    }
    return null
}

fun Settings.getCurrentChatModel(): Model? {
    return findModelById(this.getCurrentAssistant().chatModelId ?: this.chatModelId)
}

/**
 * The chat model a conversation will actually talk to: the conversation's own override, then the
 * model of the assistant the conversation belongs to, then the global default.
 *
 * Unlike [getCurrentChatModel], an id that no longer resolves (model deleted, or re-added under a
 * fresh UUID) is skipped instead of ending the chain. A dangling assistant default used to make
 * the send guard report "select a model first" while the picker highlighted the conversation's
 * perfectly valid override, and picking another model could not clear it, because the picker
 * writes the conversation override, not the assistant default.
 */
fun Settings.resolveChatModel(conversationChatModelId: Uuid?, assistantId: Uuid?): Model? {
    val assistant = assistantId
        ?.let { AssistantResolver.forConversation(this, it).assistant }
        ?: getCurrentAssistant()
    return listOfNotNull(conversationChatModelId, assistant.chatModelId, chatModelId)
        .firstNotNullOfOrNull { providers.findModelById(it) }
}

private const val FALLBACK_CONTEXT_COMPACTION_TARGET_TOKENS = 2_000

/** Returns the configured summary target, defaulting to 1% of the active context. */
fun Settings.getContextCompactionTargetTokens(contextLength: Int?): Int {
    val configured = contextCompactionTargetTokensK
        ?.toLong()
        ?.coerceAtLeast(1L)
        ?.times(1_000L)
    if (configured != null) {
        return configured.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
    return contextLength
        ?.takeIf { it > 0 }
        ?.let { length ->
            (length.toLong() * DEFAULT_CONTEXT_COMPACTION_TARGET_PERCENT / 100L)
                .coerceAtLeast(1L)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
        }
        ?: FALLBACK_CONTEXT_COMPACTION_TARGET_TOKENS
}

/**
 * The user's explicit context ceiling: the token-threshold setting in tokens, and only while that
 * mode is selected. A declared model limit, so it outranks provider metadata wherever a window is
 * needed.
 */
private fun Settings.declaredContextCeiling(): Int? =
    autoCompactionThresholdTokensK
        .takeIf { autoCompactionThresholdMode == AutoCompactionThresholdMode.TOKENS }
        ?.toLong()
        ?.coerceAtLeast(1L)
        ?.times(1_000L)
        ?.coerceAtMost(Int.MAX_VALUE.toLong())
        ?.toInt()

/**
 * The model window every context-budget decision reads — the reminder threshold, the automatic
 * compaction trigger and the compression input budget must agree on one number, or they fire in an
 * order nobody can explain.
 *
 * Sources, in order: the declared ceiling (TOKENS mode) → what the provider's model list published
 * (OpenRouter `context_length`, Gemini `inputTokenLimit`) → the static [ModelRegistry] entry for
 * the model id → null. `Model.contextLength` is empty for every provider that does not publish
 * one, which is why the registry lookup is not optional here: without it, percent-based features
 * were dead on DeepSeek / Gemini / Qwen / GLM and every OpenAI-compatible gateway.
 *
 * Null means the window is genuinely unknown; percent-based features must then stay off rather
 * than divide by an invented number (see [ContextWindowResolver]).
 */
fun Settings.getEffectiveContextLength(model: Model?): Int? =
    ContextWindowResolver.resolve(
        declaredCeiling = declaredContextCeiling(),
        providerWindow = model?.contextLength,
        registryWindow = model?.modelId?.let { modelId ->
            ModelRegistry.MODEL_CONTEXT_LENGTH.getData(modelId)
        },
    )

/**
 * P2-01 — every assistant lookup funnels through [AssistantResolver]; these extensions stay
 * for source compatibility but no longer carry logic of their own.
 */
fun Settings.getCurrentAssistant(): Assistant =
    AssistantResolver.current(this.assistants, assistantId)

fun Settings.getAssistantById(id: Uuid): Assistant? =
    AssistantResolver.byId(this.assistants, id)

fun Settings.findAssistantById(id: Uuid): Assistant? =
    AssistantResolver.byId(this.assistants, id)

fun Settings.getQuickMessagesOfAssistant(assistant: Assistant) =
    quickMessages.filter { it.id in assistant.quickMessageIds }

fun Settings.getSelectedTTSProvider(): TTSProviderSetting? {
    return ttsProviders.find { it.id == selectedTTSProviderId } ?: ttsProviders.firstOrNull()
}

fun Settings.getSelectedASRProvider(): ASRProviderSetting? {
    return selectedASRProviderId?.let { id ->
        asrProviders.find { it.id == id }
    } ?: asrProviders.firstOrNull()
}

fun Model.findProvider(providers: List<ProviderSetting>, checkOverwrite: Boolean = true): ProviderSetting? {
    val provider = findModelProviderFromList(providers) ?: return null
    val providerOverwrite = this.providerOverwrite
    if (checkOverwrite && providerOverwrite != null) {
        return providerOverwrite.copyProvider(models = emptyList())
    }
    return provider
}

private fun Model.findModelProviderFromList(providers: List<ProviderSetting>): ProviderSetting? {
    providers.forEach { setting ->
        setting.models.forEach { model ->
            if (model.id == this.id) {
                return setting
            }
        }
    }
    return null
}

internal val DEFAULT_ASSISTANT_ID = Uuid.parse("0950e2dc-9bd5-4801-afa3-aa887aa36b4e")

/**
 * Bundled skills shipped enabled-by-default on top of agent-core. The behavioral
 * `autonomous-agent` skill is auto_load (injected every turn). It is seeded to disk by
 * [SkillManager.seedDefaultSkillsIfNeeded] and added to the default assistants'
 * enabledSkills once via [Settings.autoEnabledDefaultSkills].
 *
 * `openclaw-converter` used to live here too but moved to the catalog-only set when the
 * bundled skills were trimmed to the two auto_load cores; existing users keep it (their
 * `autoEnabledDefaultSkills` already records it) and new users can install it from the
 * catalog.
 */
internal val DEFAULT_AUTO_ENABLED_SKILLS = setOf("autonomous-agent")

internal val DEFAULT_ASSISTANTS = listOf(
    Assistant(
        id = DEFAULT_ASSISTANT_ID,
        name = "",
        systemPrompt = "",
        // The agent-core skill bundle (SOUL/HEARTBEAT/TOOLS) ships with the app and is what
        // teaches every model "you are running on RikkaHub, here are the tools, here is how
        // to avoid loops". Auto-enabling it on default assistants means new users get an
        // agent-aware model out of the box without having to discover the skill toggle.
        enabledSkills = setOf("agent-core") + DEFAULT_AUTO_ENABLED_SKILLS,
    ),
    Assistant(
        id = Uuid.parse("3d47790c-c415-4b90-9388-751128adb0a0"),
        name = "",
        systemPrompt = """
            You are a helpful assistant, called {{char}}, based on model {{model_name}}.

            ## Info
            - Date: {{cur_date}}
            - Locale: {{locale}}
            - Timezone: {{timezone}}
            - Device Info: {{device_info}}
            - System Version: {{system_version}}
            - User Nickname: {{user}}

            ## Hint
            - If the user does not specify a language, reply in the user's primary language.
            - Remember to use Markdown syntax for formatting, and use latex for mathematical expressions.
        """.trimIndent(),
        enabledSkills = setOf("agent-core") + DEFAULT_AUTO_ENABLED_SKILLS,
    ),
)

val DEFAULT_SYSTEM_TTS_ID = Uuid.parse("026a01a2-c3a0-4fd5-8075-80e03bdef200")
private val DEFAULT_TTS_PROVIDERS = listOf(
    TTSProviderSetting.SystemTTS(
        id = DEFAULT_SYSTEM_TTS_ID,
        name = "",
    ),
    TTSProviderSetting.OpenAI(
        id = Uuid.parse("e36b22ef-ca82-40ab-9e70-60cad861911c"),
        name = "AiHubMix",
        baseUrl = "https://aihubmix.com/v1",
        model = "gpt-4o-mini-tts",
        voice = "alloy",
    )
)

internal val DEFAULT_ASSISTANTS_IDS = DEFAULT_ASSISTANTS.map { it.id }

val DEFAULT_MODE_INJECTIONS = listOf(
    PromptInjection.ModeInjection(
        id = Uuid.parse("b87eaf16-f5cd-4ac1-9e4f-b11ae3a61d74"),
        content = LEARNING_MODE_PROMPT,
        position = InjectionPosition.AFTER_SYSTEM_PROMPT,
        name = "Learning Mode"
    )
)
