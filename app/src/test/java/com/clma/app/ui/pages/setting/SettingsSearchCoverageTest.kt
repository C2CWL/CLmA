package com.clma.app.ui.pages.setting

import com.clma.app.Screen
import com.clma.app.data.datastore.SettingsHubStyle
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Keeps the settings search complete as the app grows. Each test fails with a message that
 * says exactly what to add to [settingsSearchPages] (or, with a reason, to an exclusion list).
 */
class SettingsSearchCoverageTest {

    private val mainSrc = "src/main/java/com/clma/app"

    /** Screens that are not settings destinations at all. */
    private val notSettings = mapOf(
        "Chat" to "chat screen",
        "ShareHandler" to "share-sheet entry point",
        "History" to "conversation history",
        "Favorite" to "favourite messages",
        "Translator" to "translator tool",
        "Setting" to "the settings hub itself",
        "ImageGen" to "image generation tool",
        "VideoGen" to "video generation tool",
        "WebView" to "in-app browser for links",
        "MessageSearch" to "message search",
        "Stats" to "usage statistics",
        "GettingStarted" to "first-run onboarding, opened from chat",
    )

    /**
     * Settings screens deliberately left out of search, each with the reason. Most need an id
     * of an item the user picks first; the item itself is reachable through a dynamic result.
     */
    private val settingsInternal = mapOf(
        "SettingsSearch" to "this search page",
        "Debug" to "hidden page (long-press on About › Version), hard-coded English, no string resources",
        "AssistantBasic" to "per-assistant sub-page; the assistant itself is a dynamic result",
        "AssistantPrompt" to "per-assistant sub-page; the assistant itself is a dynamic result",
        "AssistantMemory" to "per-assistant sub-page; the assistant itself is a dynamic result",
        "AssistantRequest" to "per-assistant sub-page; the assistant itself is a dynamic result",
        "AssistantMcp" to "per-assistant sub-page; the assistant itself is a dynamic result",
        "AssistantLocalTool" to "per-assistant sub-page; the assistant itself is a dynamic result",
        "AssistantInjections" to "per-assistant sub-page; the assistant itself is a dynamic result",
        "WorkflowDetail" to "per-workflow page; workflows live in their own store, not indexed",
        "ScheduledJobDetail" to "per-job page; scheduled jobs live in their own store, not indexed",
        "WorkspaceTerminal" to "per-workspace tool; the workspace itself is a dynamic result",
        "WorkspaceFileEditor" to "per-file editor inside a workspace",
    )

    private fun screenNames(): Set<String> {
        val src = File("$mainSrc/RouteActivity.kt").readText()
        val body = src.substringAfter("sealed interface Screen").substringBefore("\n}")
        return Regex("""data\s+(?:object|class)\s+(\w+)""").findAll(body).map { it.groupValues[1] }.toSet()
    }

    private val Screen.name: String get() = javaClass.simpleName

    private fun allStaticEntries(): List<SettingsSearchEntry> =
        SettingsHubStyle.entries.flatMap { settingsSearchIndex(developerMode = true, hubStyle = it) }

    private fun dynamicTargetNames(): Set<String> {
        val one = listOf(SettingsSearchUserItem("id", "name"))
        val content = SettingsSearchUserContent(
            workspaces = one, skills = one, quickMessages = one, modeInjections = one,
            lorebooks = one, mcpServers = one, providers = one, searchServices = one,
            assistants = one, ttsProviders = one, asrProviders = one,
        )
        return settingsSearchDynamicEntries(content, settingsSearchIndex(developerMode = true))
            .map { it.route.name }.toSet()
    }

    @Test
    fun `screen parser sees the screens`() {
        val names = screenNames()
        assertTrue(names.size > 50)
        assertTrue("Workspaces" in names && "Backup" in names && "Stats" in names)
    }

    @Test
    fun `every screen is indexed, a dynamic target, or excluded with a reason`() {
        val indexed = allStaticEntries().map { it.route.name }.toSet()
        val dynamic = dynamicTargetNames()
        val missing = screenNames() - indexed - dynamic - notSettings.keys - settingsInternal.keys
        if (missing.isNotEmpty()) {
            fail(
                "Screens not reachable from settings search: $missing. Add a SettingsSearchPageSpec " +
                    "(parent = the page that opens it) in SettingsSearchIndex.kt, or list it with a " +
                    "reason in SettingsSearchCoverageTest."
            )
        }
    }

    @Test
    fun `exclusion lists hold no stale or indexed names`() {
        val names = screenNames()
        val indexed = allStaticEntries().map { it.route.name }.toSet() + dynamicTargetNames()
        (notSettings.keys + settingsInternal.keys).forEach { name ->
            assertTrue("excluded screen $name no longer exists", name in names)
            assertTrue("excluded screen $name is indexed — drop it from the exclusion list", name !in indexed)
        }
    }

    @Test
    fun `every navigate target inside settings pages is searchable`() {
        val roots = listOf(
            "$mainSrc/ui/pages/setting",
            "$mainSrc/ui/pages/extensions",
            "$mainSrc/ui/pages/backup",
            "$mainSrc/workflow/ui",
        )
        // Detail pages (workflow / scheduled job) may open the conversation that ran them.
        val outsideSettings = setOf("Chat")
        val searchable = allStaticEntries().map { it.route.name }.toSet() + dynamicTargetNames() +
            settingsInternal.keys + outsideSettings
        val navigate = Regex("""navigate\(\s*Screen\.(\w+)""")
        val problems = mutableListOf<String>()
        roots.map(::File).filter { it.exists() }.forEach { root ->
            root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
                navigate.findAll(file.readText()).forEach { match ->
                    val target = match.groupValues[1]
                    if (target !in searchable) problems += "${file.name} → Screen.$target"
                }
            }
        }
        if (problems.isNotEmpty()) {
            fail("Settings pages link to screens the search cannot reach: $problems")
        }
    }

    @Test
    fun `every page spec resolves to a parent chain that ends at a hub row`() {
        val hubRoutes = settingsHubItems.values.mapNotNull { it.route }.toSet()
        val indexedRoutes = allStaticEntries().map { it.route }.toSet()
        settingsSearchPages.forEach { spec ->
            if (spec.route !in hubRoutes) {
                assertTrue("${spec.route} needs a parent", spec.parent != null)
                assertTrue("${spec.route} needs a titleRes", spec.titleRes != 0)
            }
            assertTrue("${spec.route} does not resolve to a hub row", spec.route in indexedRoutes)
        }
        val routes = settingsSearchPages.map { it.route }
        assertTrue("duplicate page specs", routes.size == routes.distinct().size)
    }

    // ── Rows inside pages ──

    /** Source files of each indexed page; scanned for `headlineContent = { Text(stringResource(R.string.x)) }`. */
    private val pageSources: Map<String, List<String>> = mapOf(
        "SettingPreferencesTheme" to listOf("ui/pages/setting/SettingPreferencesThemePage.kt"),
        "SettingTheme" to listOf("ui/pages/setting/SettingThemePage.kt"),
        "SettingPreferencesUI" to listOf("ui/pages/setting/SettingPreferencesUIPage.kt"),
        "SettingPreferencesGeneral" to listOf("ui/pages/setting/SettingPreferencesGeneralPage.kt"),
        "SettingPreferencesNotification" to listOf("ui/pages/setting/SettingPreferencesNotificationPage.kt"),
        "SettingModels" to listOf("ui/pages/setting/SettingModelPage.kt", "ui/pages/setting/SettingModelPromptPage.kt"),
        "SettingProvider" to listOf("ui/pages/setting/SettingProviderPage.kt"),
        "SettingSearch" to listOf("ui/pages/setting/SettingSearchPage.kt"),
        "SettingSpeech" to listOf("ui/pages/setting/SettingSpeechPage.kt"),
        "SettingPreferencesNetwork" to listOf("ui/pages/setting/SettingPreferencesNetworkPage.kt"),
        "Assistant" to listOf("ui/pages/assistant/AssistantPage.kt"),
        "Extensions" to listOf("ui/pages/extensions/ExtensionsPage.kt"),
        "QuickMessages" to listOf("ui/pages/extensions/QuickMessagesPage.kt"),
        "Prompts" to listOf("ui/pages/extensions/PromptPage.kt"),
        "Skills" to listOf("ui/pages/extensions/skills/SkillsPage.kt"),
        "Workspaces" to listOf("ui/pages/extensions/workspace/WorkspacePage.kt"),
        "SettingSubAgents" to listOf("ui/pages/setting/SettingSubAgentsPage.kt"),
        "SettingMcp" to listOf("ui/pages/setting/SettingMcpPage.kt"),
        "SettingWorkflows" to listOf("workflow/ui/WorkflowsScreen.kt"),
        "SettingScheduledJobs" to listOf("ui/pages/setting/scheduledjobs/ScheduledJobsScreen.kt"),
        "SettingToolApprovals" to listOf("ui/pages/setting/SettingToolApprovalsPage.kt"),
        "SettingAccessibility" to listOf("ui/pages/setting/SettingAccessibilityPage.kt"),
        "SettingFloatingBall" to listOf("ui/pages/setting/SettingFloatingBallPage.kt"),
        "SettingNotifications" to listOf("ui/pages/setting/SettingNotificationsPage.kt"),
        "SettingPermissions" to listOf("ui/pages/setting/SettingPermissionsPage.kt"),
        "SettingShizuku" to listOf("ui/pages/setting/shizuku/SettingShizukuPage.kt"),
        "SettingTermux" to listOf("ui/pages/setting/termux/SettingTermuxPage.kt"),
        "SettingSsh" to listOf("ui/pages/setting/SettingSshPage.kt"),
        "SettingSecrets" to listOf("ui/pages/setting/SettingSecretsPage.kt"),
        "SettingWeb" to listOf("ui/pages/setting/SettingWebPage.kt"),
        "SettingTelegram" to listOf("ui/pages/setting/SettingTelegramPage.kt"),
        "SettingBrowser" to listOf("ui/pages/setting/browser/SettingBrowserPage.kt"),
        "Backup" to listOf(
            "ui/pages/backup/BackupPage.kt",
            "ui/pages/backup/tabs/ImportExportTab.kt",
            "ui/pages/backup/tabs/WebDavTab.kt",
            "ui/pages/backup/tabs/S3Tab.kt",
            "ui/pages/backup/tabs/ReminderTab.kt",
        ),
        "SettingFiles" to listOf("ui/pages/setting/SettingFilesPage.kt"),
        "SettingDoctor" to listOf("ui/pages/setting/doctor/DoctorScreen.kt"),
        "Log" to listOf("ui/pages/log/LogPage.kt"),
        "SettingAbout" to listOf("ui/pages/setting/SettingAboutPage.kt"),
        "Developer" to listOf("ui/pages/developer/DeveloperPage.kt"),
    )

    /** Headline strings that are runtime state or per-item actions, not settings. */
    private val headlineNotSettings = setOf(
        "assistant_page_clone",
        "assistant_page_delete",
        "setting_page_notifications_recent_empty",
        "setting_secrets_page_empty",
        "setting_ssh_page_empty",
        "setting_ssh_page_known_hosts_empty",
        "setting_ssh_page_secrets_note",
        "setting_page_accessibility_status_running",
        "setting_page_accessibility_status_enabled_not_connected",
        "setting_page_accessibility_status_not_running",
        "setting_page_accessibility_overlay_granted",
        "setting_page_accessibility_overlay_not_granted",
        "setting_page_accessibility_no_actions",
        "setting_page_config",
        "setting_provider_page_test_connection",
        "setting_page_web_server_error",
    )

    @Test
    fun `every indexed page has its source files mapped`() {
        val indexed = allStaticEntries()
            .filter { it.breadcrumbRes.isEmpty() || settingsSearchPages.any { spec -> spec.route == it.route && spec.parent != null } }
            .map { it.route.name }.toSet()
        val unmapped = indexed - pageSources.keys
        if (unmapped.isNotEmpty()) {
            fail("Map the source files of $unmapped in SettingsSearchCoverageTest.pageSources")
        }
        pageSources.values.flatten().forEach { path ->
            assertTrue("mapped source $path does not exist", File("$mainSrc/$path").exists())
        }
    }

    @Test
    fun `every headline row of a mapped page is indexed`() {
        val headline = Regex(
            """headlineContent\s*=\s*\{\s*Text\(\s*(?:text\s*=\s*)?stringResource\(\s*R\.string\.(\w+)\s*\)"""
        )
        val entries = allStaticEntries()
        val problems = mutableListOf<String>()
        for ((page, files) in pageSources) {
            val childPages = settingsSearchPages.filter { it.parent?.name == page }.map { it.route.name }.toSet()
            val covered = entries
                .filter { it.route.name == page || it.route.name in childPages }
                .flatMap { listOf(it.titleRes) + it.altTitles + listOfNotNull(it.descriptionRes) }
                .map(SettingsSearchTestStrings::nameOf)
                .toSet()
            for (path in files) {
                headline.findAll(File("$mainSrc/$path").readText()).forEach { match ->
                    val name = match.groupValues[1]
                    if (name !in covered && name !in headlineNotSettings) problems += "$page: $name ($path)"
                }
            }
        }
        if (problems.isNotEmpty()) {
            fail(
                "Rows shown in settings pages but missing from the search index — add a row(...) to " +
                    "the page's SettingsSearchPageSpec (or to headlineNotSettings if it is state, not a " +
                    "setting):\n" + problems.joinToString("\n")
            )
        }
    }
}
