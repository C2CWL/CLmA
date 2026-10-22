package me.rerere.rikkahub.data.datastore

import androidx.datastore.preferences.core.Preferences
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards one invariant: **every field of [Settings] has its own `Preferences.Key`.**
 *
 * Settings is persisted field-by-field — `SettingsStore.persistSettings()` writes one key per
 * field, and `settingsFlowRaw` rebuilds `Settings(...)` from those same keys. A field that is
 * added to the data class but never wired into *both* halves lives only in the in-memory
 * `settingsFlow` snapshot: `updateInternal()` assigns the new object directly, so it looks like
 * it saved, and then the next launch (or app update) re-reads the disk and it is gone.
 *
 * Two shipped bugs of exactly that shape:
 *  - `autoEnabledDefaultSkills` — never persisted, so the one-shot default-skill seeding saw an
 *    empty marker every launch and re-enabled skills the user had deliberately turned off.
 *  - `subAgentArchiveFolders` (P2-23) — the sub-agent archive folder silently reverted to
 *    "Do not file" after every update, because the choice only ever existed in memory.
 *
 * Reflection compares the two sides directly: the constructor properties of [Settings] against
 * the `Preferences.Key`s declared in `SettingsStore`. Key names are assumed to be the
 * camelCase→snake_case form of the field name unless listed in [aliases]. Adding a field (or a
 * key) without the other half fails these tests on purpose — update [aliases] instead.
 */
class SettingsPersistenceCoverageTest {

    /**
     * Fields whose key name is not the plain snake_case of the field name. Any entry here should
     * have a reason; the rest is mechanical naming drift (model-id keys, acronyms, renames).
     */
    private val aliases = mapOf(
        "chatModelId" to "chat_model",
        "fastModelId" to "fast_model",
        "imageGenerationModelId" to "image_generation_model",
        "videoGenerationModelId" to "video_generation_model",
        "translateModeId" to "translate_model",
        "translatePrompt" to "translation_prompt",
        "ocrModelId" to "ocr_model",
        "compressModelId" to "compress_model",
        "assistantId" to "select_assistant",
        "deletedBuiltInProviderIds" to "deleted_builtin_provider_ids",
        "searchCommonOptions" to "search_common",
        "searchServiceSelected" to "search_selected",
        "webDavConfig" to "webdav_config",
        "selectedTTSProviderId" to "selected_tts_provider",
        "defaultTTSPlaybackSpeed" to "default_tts_playback_speed",
        "selectedASRProviderId" to "selected_asr_provider",
    )

    /** `init` is a `@Transient` marker for [Settings.dummy]; it is never written to disk. */
    private val fieldsWithoutKey = setOf("init")

    /** `data_version` is a bare version stamp; no `Settings` field backs it. */
    private val keysWithoutField = setOf("data_version")

    private fun settingsFields(): List<String> =
        Settings::class.java.declaredFields
            .filter { !Modifier.isStatic(it.modifiers) && !it.name.startsWith("$") }
            .map { it.name }
            .sorted()

    private fun declaredKeys(): Map<String, String> =
        SettingsStore::class.java.declaredFields
            .filter { Preferences.Key::class.java.isAssignableFrom(it.type) }
            .associate { field ->
                field.isAccessible = true
                val key = field.get(null) as Preferences.Key<*>
                field.name to key.name
            }

    private fun expectedKeyName(field: String): String = aliases[field] ?: snakeCase(field)

    private fun snakeCase(name: String): String = buildString {
        name.forEachIndexed { index, c ->
            if (c.isUpperCase()) {
                if (index > 0) append('_')
                append(c.lowercaseChar())
            } else {
                append(c)
            }
        }
    }

    @Test
    fun `every Settings field has a declared preference key`() {
        val keyNames = declaredKeys().values.toSet()

        val unwired = settingsFields()
            .filterNot { it in fieldsWithoutKey }
            .filterNot { expectedKeyName(it) in keyNames }

        assertEquals(
            "这些 Settings 字段没有对应的 preference key: 它们只活在内存快照里, 重启/升级后会丢。",
            emptyList<String>(),
            unwired,
        )
    }

    @Test
    fun `every declared preference key belongs to a Settings field`() {
        val expected = settingsFields()
            .filterNot { it in fieldsWithoutKey }
            .map { expectedKeyName(it) }
            .toSet()

        val orphans = declaredKeys().values
            .filterNot { it in expected }
            .filterNot { it in keysWithoutField }

        assertEquals(
            "这些 preference key 在 Settings 里没有对应字段: 要么字段被删了, 要么该加进 keysWithoutField。",
            emptyList<String>(),
            orphans,
        )
    }

    /**
     * The two fields whose missing key started this: pin them by name so a future rename of the
     * key would surface here rather than as a silent revert on the next app update.
     */
    @Test
    fun `sub-agent archive folders and default-skill seeding are persisted`() {
        val keyNames = declaredKeys().values

        assertEquals("sub_agent_archive_folders", expectedKeyName("subAgentArchiveFolders"))
        assertEquals("auto_enabled_default_skills", expectedKeyName("autoEnabledDefaultSkills"))
        assertTrue(keyNames.contains("sub_agent_archive_folders"))
        assertTrue(keyNames.contains("auto_enabled_default_skills"))
    }
}
