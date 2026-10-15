package me.rerere.rikkahub.data.agentdef

import me.rerere.rikkahub.data.ai.tools.LocalToolOption

/**
 * P2-06c — the edit-time state of one expert definition.
 *
 * [AgentDefinition] is the storage shape; this is the *editing* shape the expert sheet binds to.
 * The two differ in exactly one place, and it is the reason this type exists at all: the four
 * surface fields and the D9 namespace are **tri-state** (`null` = "inherit the parent
 * assistant"), but a text field or a switch cannot express "unset" — it can only hold a value.
 * The draft keeps the tri-state explicit ([ownsLocalTools] and friends) and separates the raw
 * namespace text the user is still typing ([namespaceInput]) from the slug that is actually
 * stored ([namespaceSlug]).
 *
 * Everything here is pure: no Room, no Android, no `R`, no `Assistant`. That is deliberate — it
 * is what lets `AgentDefinitionDraftTest` cover the inherit-vs-own transitions, the namespace
 * suggestion / validation and the card summary on a plain JVM. The sheet itself is not
 * unit-tested; this module has no Robolectric, the same call P2-06b made for the write tools.
 *
 * ## Why each toggle refuses to "help"
 *
 * Inheriting and owning an *empty* set are different things and the store tells them apart:
 * `localTools = null` keeps the parent's list, `localTools = emptyList()` gives the expert no
 * tools at all. So [ownLocalTools] materialises an empty list and never guesses a starting set,
 * while [inheritLocalTools] is the only way back to `null`. [toggleLocalTool] on an inheriting
 * draft materialises first — the UI only shows the tool list once the user has asked for a
 * custom surface, so that branch is a defensive fallback rather than a path the UI walks.
 *
 * ## What is *not* here
 *
 * `createdAtMs` / `updatedAtMs` are absent on purpose: `AgentDefinitionRepository.update` reads
 * the stored row's `createdAtMs` itself and stamps `updatedAtMs` from its clock, so a draft that
 * carried them would only create a second, wrong source for the same two values.
 */
data class AgentDefinitionDraft(
    val id: String,
    val name: String = "",
    val description: String = "",
    val systemPrompt: String = "",
    /** `Uuid` string of the chat model, or null to inherit the parent's. */
    val modelId: String? = null,
    val enabled: Boolean = true,

    // ---- the four tool-surface fields; null = inherit the parent assistant ----------------
    val localTools: List<LocalToolOption>? = null,
    val disabledLocalTools: Set<String>? = null,
    val mcpServers: Set<String>? = null,
    val skills: Set<String>? = null,

    /**
     * D9 — raw text in the namespace field, or null when the expert has no private namespace.
     *
     * Kept raw (not normalised) so the field can echo back what the user typed, including text
     * that is not yet a valid slug. [namespaceSlug] is the normalised value; the write path
     * re-checks it, and the sheet refuses to save while [namespaceProblem] is non-null.
     */
    val namespaceInput: String? = null,

    val tokenBudget: Long? = null,
) {

    // ---- inherit vs own ------------------------------------------------------------------

    val ownsLocalTools: Boolean get() = localTools != null
    val ownsDisabledTools: Boolean get() = disabledLocalTools != null
    val ownsMcpServers: Boolean get() = mcpServers != null
    val ownsSkills: Boolean get() = skills != null

    /** True when the namespace field is on, regardless of whether the text is usable yet. */
    val namespaceOn: Boolean get() = namespaceInput != null

    fun ownLocalTools(): AgentDefinitionDraft =
        if (localTools != null) this else copy(localTools = emptyList())

    fun inheritLocalTools(): AgentDefinitionDraft = copy(localTools = null)

    /**
     * Adds / removes one tool group, keeping [LocalToolGroups.all]'s order so the stored list is
     * stable no matter which switch the user flipped first.
     */
    fun toggleLocalTool(option: LocalToolOption, enabled: Boolean): AgentDefinitionDraft {
        val current = localTools.orEmpty()
        val next = if (enabled) current + option else current - option
        return copy(localTools = LocalToolGroups.order(next))
    }

    fun ownDisabledTools(): AgentDefinitionDraft =
        if (disabledLocalTools != null) this else copy(disabledLocalTools = emptySet())

    fun inheritDisabledTools(): AgentDefinitionDraft = copy(disabledLocalTools = null)

    fun toggleDisabledTool(toolName: String, disabled: Boolean): AgentDefinitionDraft {
        val current = disabledLocalTools.orEmpty()
        val next = if (disabled) current + toolName else current - toolName
        return copy(disabledLocalTools = next.toSortedSet())
    }

    fun ownMcpServers(): AgentDefinitionDraft =
        if (mcpServers != null) this else copy(mcpServers = emptySet())

    fun inheritMcpServers(): AgentDefinitionDraft = copy(mcpServers = null)

    fun toggleMcpServer(serverId: String, enabled: Boolean): AgentDefinitionDraft {
        val current = mcpServers.orEmpty()
        val next = if (enabled) current + serverId else current - serverId
        return copy(mcpServers = next.toSortedSet())
    }

    fun ownSkills(): AgentDefinitionDraft =
        if (skills != null) this else copy(skills = emptySet())

    fun inheritSkills(): AgentDefinitionDraft = copy(skills = null)

    fun toggleSkill(name: String, enabled: Boolean): AgentDefinitionDraft {
        val current = skills.orEmpty()
        val next = if (enabled) current + name else current - name
        return copy(skills = next.toSortedSet())
    }

    // ---- D9 namespace --------------------------------------------------------------------

    /** Turns the namespace on, seeded with [seed] (the sheet passes [suggestedNamespace]). */
    fun withNamespace(seed: String): AgentDefinitionDraft = copy(namespaceInput = seed)

    fun clearNamespace(): AgentDefinitionDraft = copy(namespaceInput = null)

    /** The slug this draft would store, or null when the field is off or unusable. */
    fun namespaceSlug(): String? = AgentNamespace.normalizeSlug(namespaceInput)

    /** `agents/<slug>/memory` — the folder the run's cold memory would land in, or null. */
    fun namespacePreview(): String? = AgentNamespace.coldMemoryDirFor(namespaceSlug())

    /**
     * `slugify(name)` — what the sheet offers when the namespace switch is flipped on. May be
     * `""` when the name has no usable character (e.g. written entirely in a non-Latin script);
     * the caller then leaves the field empty rather than inventing a namespace, because two
     * experts silently sharing one would mix their memory files.
     */
    fun suggestedNamespace(): String = AgentNamespace.slugify(name)

    /**
     * Why the namespace cannot be saved yet, or null when it is fine. Only meaningful while the
     * namespace is on; a draft with no namespace never has a problem.
     */
    fun namespaceProblem(others: List<AgentDefinition>): NamespaceProblem? {
        if (!namespaceOn) return null
        val slug = namespaceSlug() ?: return NamespaceProblem.EMPTY
        val clash = others.any { other ->
            other.id != id && AgentNamespace.normalizeSlug(other.slug) == slug
        }
        return if (clash) NamespaceProblem.DUPLICATE else null
    }

    /**
     * True when another expert already answers to [name] case-insensitively. Dispatch resolves
     * experts by name case-insensitively, so the sheet rejects this rather than letting the
     * ambiguity reach the resolver at dispatch time.
     */
    fun nameClash(others: List<AgentDefinition>): Boolean =
        others.any { other ->
            other.id != id && other.name.isNotBlank() && other.name.equals(name, ignoreCase = true)
        }

    /** True when the sheet's Save button should be enabled. */
    fun canSave(others: List<AgentDefinition>): Boolean =
        name.isNotBlank() && !nameClash(others) && namespaceProblem(others) == null

    // ---- storage -------------------------------------------------------------------------

    /** The row this draft would store. Whether it is inserted or updated is the caller's call. */
    fun toDefinition(): AgentDefinition = AgentDefinition(
        id = id,
        name = name.trim(),
        description = description.trim(),
        systemPrompt = systemPrompt,
        modelId = modelId,
        enabled = enabled,
        localTools = localTools?.let(LocalToolGroups::order),
        disabledLocalTools = disabledLocalTools,
        mcpServers = mcpServers,
        skills = skills,
        slug = namespaceSlug(),
        tokenBudget = tokenBudget,
    )

    /** The card summary for this draft. */
    fun surfaceSummary(): SurfaceSummary = SurfaceSummary(
        ownLocalTools = ownsLocalTools,
        localToolCount = localTools?.size ?: 0,
        ownsDisabledTools = ownsDisabledTools,
        disabledToolCount = disabledLocalTools?.size ?: 0,
        ownsMcpServers = ownsMcpServers,
        mcpServerCount = mcpServers?.size ?: 0,
        ownsSkills = ownsSkills,
        skillCount = skills?.size ?: 0,
        namespace = namespaceSlug(),
    )

    companion object {
        /** Opens the editor on an existing row, keeping every "inherit" exactly as stored. */
        fun of(definition: AgentDefinition): AgentDefinitionDraft = AgentDefinitionDraft(
            id = definition.id,
            name = definition.name,
            description = definition.description,
            systemPrompt = definition.systemPrompt,
            modelId = definition.modelId,
            enabled = definition.enabled,
            localTools = definition.localTools,
            disabledLocalTools = definition.disabledLocalTools,
            mcpServers = definition.mcpServers,
            skills = definition.skills,
            namespaceInput = definition.slug,
            tokenBudget = definition.tokenBudget,
        )
    }
}

/** Why [AgentDefinitionDraft.namespaceProblem] rejected the namespace. */
enum class NamespaceProblem {
    /** The field is on but nothing usable is in it (blank, or a name with no Latin characters). */
    EMPTY,

    /** Another expert already owns this slug. */
    DUPLICATE,
}

/**
 * P2-06c — what the expert card shows about an expert's surface, and what the sheet shows as
 * "custom" chips. Pure so the card's "inherit vs own" rendering is testable without a UI.
 */
data class SurfaceSummary(
    val ownLocalTools: Boolean,
    val localToolCount: Int,
    val ownsDisabledTools: Boolean,
    val disabledToolCount: Int,
    val ownsMcpServers: Boolean,
    val mcpServerCount: Int,
    val ownsSkills: Boolean,
    val skillCount: Int,
    /** The normalised namespace, or null when the expert has none. */
    val namespace: String?,
) {
    /** True when the expert overrides any part of its parent's surface. */
    val hasOwnSurface: Boolean
        get() = ownLocalTools || ownsDisabledTools || ownsMcpServers || ownsSkills || namespace != null

    /** Convenience for the card: how many surface groups are overridden. */
    val ownGroupCount: Int
        get() = listOf(ownLocalTools, ownsDisabledTools, ownsMcpServers, ownsSkills).count { it } +
            (if (namespace != null) 1 else 0)
}

/** [AgentDefinitionDraft.surfaceSummary] for a stored row (the card's path). */
fun AgentDefinition.surfaceSummary(): SurfaceSummary = SurfaceSummary(
    ownLocalTools = localTools != null,
    localToolCount = localTools?.size ?: 0,
    ownsDisabledTools = disabledLocalTools != null,
    disabledToolCount = disabledLocalTools?.size ?: 0,
    ownsMcpServers = mcpServers != null,
    mcpServerCount = mcpServers?.size ?: 0,
    ownsSkills = skills != null,
    skillCount = skills?.size ?: 0,
    namespace = AgentNamespace.normalizeSlug(slug),
)

/**
 * P2-06c — the ordered catalogue of every tool group this build defines.
 *
 * Mirrors the order `AssistantLocalToolPage` lists its rows in (the page groups them into
 * sections; the flat order is the page's reading order). The sheet uses it for two things: to
 * render the group list in a familiar order, and to keep a stored [AgentDefinitionDraft.localTools]
 * list in that order regardless of the order the user flipped switches — the definition's JSON is
 * user-visible through `encodeDefinition`, so it should not depend on tap order.
 *
 * The **labels** are deliberately not here: they are `R.string` ids, and this file must stay
 * compilable on a bare JVM. The sheet owns the option → title mapping and falls back to
 * [LocalToolOption]'s serial name for anything the map has not learned yet.
 */
object LocalToolGroups {
    val all: List<LocalToolOption> = listOf(
        LocalToolOption.JavascriptEngine,
        LocalToolOption.TimeInfo,
        LocalToolOption.Clipboard,
        LocalToolOption.Tts,
        LocalToolOption.AskUser,
        LocalToolOption.Battery,
        LocalToolOption.UsageLedger,
        LocalToolOption.AudioInfo,
        LocalToolOption.TelephonyInfo,
        LocalToolOption.WifiInfo,
        LocalToolOption.Sensors,
        LocalToolOption.StorageInfo,
        LocalToolOption.Toast,
        LocalToolOption.Notification,
        LocalToolOption.Share,
        LocalToolOption.Torch,
        LocalToolOption.Vibrate,
        LocalToolOption.Brightness,
        LocalToolOption.Volume,
        LocalToolOption.Location,
        LocalToolOption.Contacts,
        LocalToolOption.CallLog,
        LocalToolOption.SmsInbox,
        LocalToolOption.CameraPhoto,
        // P2-33 image generation tools
        LocalToolOption.ImageGeneration,
        // P2-33b video generation tool
        LocalToolOption.VideoGeneration,
        LocalToolOption.MicRecorder,
        LocalToolOption.SpeechToText,
        LocalToolOption.Fingerprint,
        LocalToolOption.NotificationListener,
        LocalToolOption.MediaPlayer,
        LocalToolOption.MediaScanner,
        LocalToolOption.Download,
        LocalToolOption.CronJobs,
        LocalToolOption.Files,
        LocalToolOption.Ssh,
        LocalToolOption.TelegramBot,
        LocalToolOption.McpControl,
        LocalToolOption.ExternalAutomation,
        LocalToolOption.Reliability,
        LocalToolOption.SubAgents,
        LocalToolOption.CostGuards,
        LocalToolOption.Workflows,
        LocalToolOption.SkillImport,
        LocalToolOption.JsSkills,
        LocalToolOption.SystemIntents,
        LocalToolOption.Browser,
        LocalToolOption.SmsSend,
        LocalToolOption.Wallpaper,
        LocalToolOption.Keystore,
        LocalToolOption.Nfc,
        LocalToolOption.ExternalStorage,
        LocalToolOption.Archive,
        LocalToolOption.Shizuku,
        LocalToolOption.ScreenAutomation,
        LocalToolOption.AppLauncher,
        LocalToolOption.Termux,
        LocalToolOption.VirtualDisplay,
        LocalToolOption.KeyboardControl,
        LocalToolOption.ModelControl,
    )

    private val index: Map<LocalToolOption, Int> =
        all.withIndex().associate { (position, option) -> option to position }

    /**
     * De-duplicates [options] and returns them in [all]'s order. Anything this build does not
     * know sorts last, which keeps a hand-edited row from crashing the editor.
     */
    fun order(options: Collection<LocalToolOption>): List<LocalToolOption> =
        options.distinct().sortedBy { index[it] ?: Int.MAX_VALUE }
}
