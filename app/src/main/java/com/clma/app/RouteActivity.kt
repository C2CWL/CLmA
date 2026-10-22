package com.clma.app

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.network.cachecontrol.CacheControlCacheStrategy
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import coil3.svg.SvgDecoder
import com.dokar.sonner.Toaster
import com.dokar.sonner.rememberToasterState
import kotlinx.serialization.Serializable
import com.clma.app.data.datastore.SettingsStore
import com.clma.app.data.datastore.DEFAULT_CODEX_PROVIDER_ID
import com.clma.app.data.datastore.DEFAULT_GEMINI_OAUTH_PROVIDER_ID
import com.clma.app.data.db.DatabaseMigrationTracker
import com.clma.app.data.db.MigrationState
import com.clma.app.data.event.AppEvent
import com.clma.app.data.event.AppEventBus
import com.clma.app.ui.activity.SafeModeActivity
import com.clma.app.ui.components.ui.TTSController
import com.clma.app.ui.context.LocalNavController
import com.clma.app.ui.context.LocalASRState
import com.clma.app.ui.context.LocalSettings
import com.clma.app.ui.context.LocalSharedTransitionScope
import com.clma.app.ui.context.LocalTTSState
import com.clma.app.ui.context.LocalToaster
import com.clma.app.ui.context.Navigator
import com.clma.app.ui.hooks.readBooleanPreference
import com.clma.app.ui.hooks.readStringPreference
import com.clma.app.ui.hooks.rememberCustomTtsState
import com.clma.app.ui.hooks.rememberCustomAsrState
import com.clma.app.ui.hooks.rememberCurrentColorMode
import com.clma.app.ui.pages.assistant.AssistantPage
import com.clma.app.ui.pages.assistant.detail.AssistantBasicPage
import com.clma.app.ui.pages.assistant.detail.AssistantDetailPage
import com.clma.app.ui.pages.assistant.detail.AssistantExtensionsPage
import com.clma.app.ui.pages.assistant.detail.AssistantLocalToolPage
import com.clma.app.ui.pages.assistant.detail.AssistantMcpPage
import com.clma.app.ui.pages.assistant.detail.AssistantMemoryPage
import com.clma.app.ui.pages.assistant.detail.AssistantPromptPage
import com.clma.app.ui.pages.assistant.detail.AssistantRequestPage
import com.clma.app.ui.pages.backup.BackupPage
import com.clma.app.ui.pages.chat.ChatPage
import com.clma.app.ui.pages.debug.DebugPage
import com.clma.app.ui.pages.developer.DeveloperPage
import com.clma.app.ui.pages.extensions.ExtensionsPage
import com.clma.app.ui.pages.extensions.PromptPage
import com.clma.app.ui.pages.extensions.QuickMessagesPage
import com.clma.app.ui.pages.extensions.skills.SkillDetailPage
import com.clma.app.ui.pages.extensions.skills.SkillsPage
import com.clma.app.ui.pages.gettingstarted.GETTING_STARTED_SEEN_KEY
import com.clma.app.ui.pages.gettingstarted.GettingStartedPage
import com.clma.app.ui.pages.gettingstarted.hasConfiguredModel
import com.clma.app.ui.pages.extensions.workspace.WorkspaceDetailPage
import com.clma.app.ui.pages.extensions.workspace.WorkspaceFileEditorPage
import com.clma.app.ui.pages.extensions.workspace.WorkspacePage
import com.clma.app.ui.pages.extensions.workspace.WorkspaceTerminalPage
import com.clma.workspace.WorkspaceStorageArea
import com.clma.app.ui.pages.favorite.FavoritePage
import com.clma.app.ui.pages.history.HistoryPage
import com.clma.app.ui.pages.imggen.ImageGenPage
import com.clma.app.ui.pages.videogen.VideoGenPage
import com.clma.app.ui.pages.log.LogPage
import com.clma.app.ui.pages.search.SearchPage
import com.clma.app.ui.pages.setting.SettingAboutPage
import com.clma.app.ui.pages.setting.SettingAccessibilityPage
import com.clma.app.ui.pages.setting.SettingFloatingBallPage
import com.clma.app.ui.pages.setting.SettingNotificationsPage
import com.clma.app.ui.pages.setting.SettingPermissionsPage
import com.clma.app.ui.pages.setting.SettingPreferencesThemePage
import com.clma.app.ui.pages.setting.SettingPreferencesNotificationPage
import com.clma.app.ui.pages.setting.SettingPreferencesGeneralPage
import com.clma.app.ui.pages.setting.SettingPreferencesNetworkPage
import com.clma.app.ui.pages.setting.SettingPreferencesUIPage
import com.clma.app.ui.pages.setting.SettingThemePage
import com.clma.app.ui.pages.setting.SettingFilesPage
import com.clma.app.ui.pages.setting.SettingMcpPage
import com.clma.app.ui.pages.setting.SettingModelPage
import com.clma.app.ui.pages.setting.SettingPage
import com.clma.app.ui.pages.setting.SettingProviderDetailPage
import com.clma.app.ui.pages.setting.SettingProviderPage
import com.clma.app.ui.pages.setting.SettingSearchDetailPage
import com.clma.app.ui.pages.setting.SettingSearchPage
import com.clma.app.ui.pages.setting.SettingsSearchPage
import com.clma.app.ui.pages.setting.SettingSpeechPage
import com.clma.app.ui.pages.setting.SettingSubAgentsPage
// P2-32 ssh hosts page
import com.clma.app.ui.pages.setting.SettingSshPage
import com.clma.app.ui.pages.setting.SettingSecretsPage
import com.clma.app.ui.pages.setting.SettingTelegramPage
import com.clma.app.ui.pages.setting.SettingWebPage
import com.clma.app.ui.pages.share.handler.ShareHandlerPage
import com.clma.app.ui.pages.stats.StatsPage
import com.clma.app.ui.pages.translator.TranslatorPage
import com.clma.app.ui.pages.webview.WebViewPage
import com.clma.app.ui.theme.LocalDarkMode
import com.clma.app.ui.theme.RikkahubTheme
import com.clma.app.ui.theme.SplashStyle
import com.clma.app.ui.theme.syncSplashTheme
import com.clma.app.utils.CrashHandler
import com.clma.app.utils.openUsageAccessSettings
import com.clma.app.utils.resolveInitialChatStack
import okhttp3.OkHttpClient
import org.koin.android.ext.android.inject
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

private const val TAG = "RouteActivity"
private const val ACTION_TRANSLATE = "me.rerere.rikkahub.action.TRANSLATE"

class RouteActivity : ComponentActivity() {
    companion object {
        const val EXTRA_OPEN_CODEX_SETTINGS = "open_codex_settings"
        const val EXTRA_OPEN_GEMINI_SETTINGS = "open_gemini_settings"
    }

    private val okHttpClient by inject<OkHttpClient>()
    private val settingsStore by inject<SettingsStore>()
    private var navStack: MutableList<NavKey>? = null
    private val pendingIntents = ArrayDeque<Intent>()

    // Volume key listener registry — last registered handler wins
    internal val volumeKeyListeners = mutableListOf<(isVolumeUp: Boolean) -> Boolean>()

    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val isVolumeUp = when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> true
                KeyEvent.KEYCODE_VOLUME_DOWN -> false
                else -> return super.dispatchKeyEvent(event)
            }
            if (volumeKeyListeners.lastOrNull()?.invoke(isVolumeUp) == true) return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Launch screen (core-splashscreen): must run before super.onCreate. It switches the
        // activity from Theme.CLmA.Splash.* to postSplashScreenTheme (Theme.Rikkahub). No
        // keep-on-screen condition, so it never delays startup.
        installSplashScreen()
        enableEdgeToEdge()
        disableNavigationBarContrast()
        super.onCreate(savedInstanceState)
        if (CrashHandler.hasCrashed(this)) {
            startActivity(Intent(this, SafeModeActivity::class.java))
            finish()
            return
        }
        if (savedInstanceState == null) {
            handleIntent(intent)
        }
        setContent {
            RikkahubTheme {
                @OptIn(coil3.annotation.ExperimentalCoilApi::class)
                setSingletonImageLoaderFactory { context ->
                    ImageLoader.Builder(context)
                        .crossfade(true)
                        .components {
                            add(OkHttpNetworkFetcherFactory(
                                callFactory = { okHttpClient },
                                cacheStrategy = { CacheControlCacheStrategy() },
                            ))
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                add(AnimatedImageDecoder.Factory())
                            } else {
                                add(GifDecoder.Factory())
                            }
                            add(SvgDecoder.Factory(scaleToDensity = true))
                        }
                        .build()
                }
                AppRoutes()
            }
        }
    }

    private fun disableNavigationBarContrast() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        val backStack = navStack ?: run {
            // Compose hasn't created the nav back stack yet; process once it's ready.
            pendingIntents.addLast(intent)
            return
        }
        if (intent.getBooleanExtra(EXTRA_OPEN_CODEX_SETTINGS, false)) {
            val destination = Screen.SettingProviderDetail(DEFAULT_CODEX_PROVIDER_ID.toString())
            if (backStack.lastOrNull() != destination) backStack.add(destination)
            intent.removeExtra(EXTRA_OPEN_CODEX_SETTINGS)
        }
        if (intent.getBooleanExtra(EXTRA_OPEN_GEMINI_SETTINGS, false)) {
            val destination = Screen.SettingProviderDetail(DEFAULT_GEMINI_OAUTH_PROVIDER_ID.toString())
            if (backStack.lastOrNull() != destination) backStack.add(destination)
            intent.removeExtra(EXTRA_OPEN_GEMINI_SETTINGS)
        }
        val destination = when (intent.action) {
            ACTION_TRANSLATE -> Screen.Translator
            Intent.ACTION_SEND -> Screen.ShareHandler(
                text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty(),
                streamUri = intent.getStringExtra(Intent.EXTRA_STREAM),
            )
            Intent.ACTION_PROCESS_TEXT -> Screen.ShareHandler(
                text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty(),
            )
            else -> intent.getStringExtra("conversationId")?.let { Screen.Chat(it) }
        }
        if (destination != null && backStack.lastOrNull() != destination) {
            backStack.add(destination)
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    fun AppRoutes() {
        val toastState = rememberToasterState()
        val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
        val colorMode = rememberCurrentColorMode()
        // Tell the system which splash to show on the next cold start (API 31+), so it follows
        // the in-app light/dark setting. Runs on start and whenever the colour mode changes.
        LaunchedEffect(colorMode) {
            syncSplashTheme(SplashStyle.resolve(colorMode))
        }
        val tts = rememberCustomTtsState()
        val asr = rememberCustomAsrState()
        val eventBus = koinInject<AppEventBus>()
        LaunchedEffect(tts) {
            eventBus.events.collect { event ->
                when (event) {
                    is AppEvent.Speak -> tts.speak(event.text)
                    is AppEvent.OpenUsageAccessSettings -> this@RouteActivity.openUsageAccessSettings()
                    is AppEvent.ChatGenerationUpdate -> Unit // 本 fork 使用自有的前台服务通知，不经事件总线消费
                    is AppEvent.ChatGenerationEnded -> Unit // 本 fork 使用自有的前台服务通知，不经事件总线消费
                }
            }
        }
        val migrationState by DatabaseMigrationTracker.state.collectAsStateWithLifecycle()

        // Resolve once per composition (not on every recomposition) so a later mutation of the
        // Intent's extras can't flip which rememberNavBackStack() branch below gets called.
        val deepLinkConversationId = remember { intent?.getStringExtra("conversationId") }
        val initialChatIds = remember {
            resolveInitialChatStack(
                deepLinkConversationId = deepLinkConversationId,
                createNewOnStart = readBooleanPreference("create_new_conversation_on_start", true),
                lastConversationId = readStringPreference("lastConversationId", null),
                newId = { Uuid.random().toString() },
            )
        }

        val backStack = if (initialChatIds.size > 1) {
            rememberNavBackStack(Screen.Chat(initialChatIds[0]), Screen.Chat(initialChatIds[1]))
        } else {
            rememberNavBackStack(Screen.Chat(initialChatIds[0]))
        }
        SideEffect {
            navStack = backStack
            while (pendingIntents.isNotEmpty()) {
                handleIntent(pendingIntents.removeFirst())
            }
        }

        // First-run guide. The stock app opens into an empty chat with no model, no tools and
        // no permissions — the usual "installed it, now what?" dead end. If the user has not
        // seen this guide and still has no usable model, push the checklist on top of the
        // initial chat so the very first screen a new install sees explains what to do.
        // Gated on `!settings.init` so we wait for the real settings, and on a single-entry
        // Chat stack so we never hijack a share / deep-link launch.
        val gettingStartedSeen = remember { readBooleanPreference(GETTING_STARTED_SEEN_KEY, false) }
        LaunchedEffect(settings.init) {
            if (!gettingStartedSeen && !settings.init && !settings.hasConfiguredModel() &&
                backStack.size == 1 && backStack.firstOrNull() is Screen.Chat
            ) {
                backStack.add(Screen.GettingStarted)
            }
        }

        SharedTransitionLayout {
            CompositionLocalProvider(
                LocalNavController provides Navigator(backStack),
                LocalSharedTransitionScope provides this,
                LocalSettings provides settings,
                LocalToaster provides toastState,
                LocalTTSState provides tts,
                LocalASRState provides asr,
            ) {
                Toaster(
                    state = toastState,
                    darkTheme = LocalDarkMode.current,
                    richColors = true,
                    alignment = Alignment.TopCenter,
                    showCloseButton = true,
                )
                TTSController()
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .semantics { testTagsAsResourceId = true }
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    NavDisplay(
                        backStack = backStack,
                        entryDecorators = listOf(
                            rememberSaveableStateHolderNavEntryDecorator(),
                            rememberViewModelStoreNavEntryDecorator(),
                        ),
                        modifier = Modifier.fillMaxSize(),
                        onBack = { backStack.removeLastOrNull() },
                        transitionSpec = {
                            if (backStack.size == 1) fadeIn() togetherWith fadeOut()
                            else {
                                slideInHorizontally { it } togetherWith
                                    slideOutHorizontally { -it / 2 } + scaleOut(targetScale = 0.7f) + fadeOut()
                            }
                        },
                        popTransitionSpec = {
                            slideInHorizontally { -it / 2 } + scaleIn(initialScale = 0.7f) + fadeIn() togetherWith
                                slideOutHorizontally { it }
                        },
                        predictivePopTransitionSpec = {
                            slideInHorizontally { -it / 2 } + scaleIn(initialScale = 0.7f) + fadeIn() togetherWith
                                slideOutHorizontally { it }
                        },
                        entryProvider = entryProvider {
                            entry<Screen.Chat>(
                                metadata = NavDisplay.transitionSpec { fadeIn() togetherWith fadeOut() }
                                        + NavDisplay.popTransitionSpec { fadeIn() togetherWith fadeOut() }
                            ) { key ->
                                ChatPage(
                                    id = Uuid.parse(key.id),
                                    text = key.text,
                                    files = key.files.map { it.toUri() },
                                    nodeId = key.nodeId?.let { Uuid.parse(it) }
                                )
                            }

                            entry<Screen.ShareHandler> { key ->
                                ShareHandlerPage(
                                    text = key.text,
                                    image = key.streamUri
                                )
                            }

                            entry<Screen.History> {
                                HistoryPage()
                            }

                            entry<Screen.Favorite> {
                                FavoritePage()
                            }

                            entry<Screen.Assistant> {
                                AssistantPage()
                            }

                            entry<Screen.AssistantDetail> { key ->
                                AssistantDetailPage(key.id)
                            }

                            entry<Screen.AssistantBasic> { key ->
                                AssistantBasicPage(key.id)
                            }

                            entry<Screen.AssistantPrompt> { key ->
                                AssistantPromptPage(key.id)
                            }

                            entry<Screen.AssistantMemory> { key ->
                                AssistantMemoryPage(key.id)
                            }

                            entry<Screen.AssistantRequest> { key ->
                                AssistantRequestPage(key.id)
                            }

                            entry<Screen.AssistantMcp> { key ->
                                AssistantMcpPage(key.id)
                            }

                            entry<Screen.AssistantLocalTool> { key ->
                                AssistantLocalToolPage(key.id)
                            }

                            entry<Screen.AssistantInjections> { key ->
                                AssistantExtensionsPage(key.id)
                            }

                            entry<Screen.Translator> {
                                TranslatorPage()
                            }

                            entry<Screen.Setting> {
                                SettingPage()
                            }

                            entry<Screen.SettingsSearch> {
                                SettingsSearchPage()
                            }

                            entry<Screen.Backup> { key ->
                                BackupPage(initialTab = key.initialTab)
                            }

                            entry<Screen.ImageGen> {
                                ImageGenPage()
                            }

                            entry<Screen.VideoGen> {
                                VideoGenPage()
                            }

                            entry<Screen.WebView> { key ->
                                WebViewPage(key.url, key.contentId)
                            }

                            entry<Screen.SettingTheme> {
                                SettingThemePage()
                            }

                            entry<Screen.SettingPreferencesTheme> {
                                SettingPreferencesThemePage()
                            }

                            entry<Screen.SettingPreferencesNotification> {
                                SettingPreferencesNotificationPage()
                            }

                            entry<Screen.SettingPreferencesGeneral> {
                                SettingPreferencesGeneralPage()
                            }

                            entry<Screen.SettingPreferencesUI> {
                                SettingPreferencesUIPage()
                            }

                            entry<Screen.SettingPreferencesNetwork> {
                                SettingPreferencesNetworkPage()
                            }

                            entry<Screen.SettingProvider> {
                                SettingProviderPage()
                            }

                            entry<Screen.SettingProviderDetail> { key ->
                                val id = Uuid.parse(key.providerId)
                                SettingProviderDetailPage(id = id)
                            }

                            entry<Screen.SettingModels> { key ->
                                SettingModelPage(initialTab = key.initialTab)
                            }

                            entry<Screen.SettingAbout> {
                                SettingAboutPage()
                            }

                            entry<Screen.SettingSearch> {
                                SettingSearchPage()
                            }

                            entry<Screen.SettingSearchDetail> { key ->
                                val id = Uuid.parse(key.serviceId)
                                SettingSearchDetailPage(id)
                            }

                            entry<Screen.SettingSpeech> { key ->
                                SettingSpeechPage(initialTab = key.initialTab)
                            }

                            entry<Screen.SettingMcp> {
                                SettingMcpPage()
                            }

                            entry<Screen.SettingSubAgents> {
                                SettingSubAgentsPage()
                            }

                            entry<Screen.SettingSsh> {
                                SettingSshPage()
                            }

                            entry<Screen.SettingSecrets> {
                                SettingSecretsPage()
                            }

                            entry<Screen.SettingFiles> {
                                SettingFilesPage()
                            }

                            entry<Screen.SettingWeb> {
                                SettingWebPage()
                            }

                            entry<Screen.SettingTelegram> {
                                SettingTelegramPage()
                            }

                            entry<Screen.SettingWorkflows> {
                                com.clma.app.workflow.ui.WorkflowsScreen()
                            }

                            entry<Screen.WorkflowDetail> { key ->
                                com.clma.app.workflow.ui.WorkflowDetailScreen(workflowId = key.id)
                            }

                            entry<Screen.SettingScheduledJobs> {
                                com.clma.app.ui.pages.setting.scheduledjobs.ScheduledJobsScreen()
                            }

                            entry<Screen.SettingBrowser> {
                                com.clma.app.ui.pages.setting.browser.SettingBrowserPage()
                            }

                            entry<Screen.SettingTermux> {
                                com.clma.app.ui.pages.setting.termux.SettingTermuxPage()
                            }

                            entry<Screen.SettingShizuku> {
                                com.clma.app.ui.pages.setting.shizuku.SettingShizukuPage()
                            }

                            entry<Screen.ScheduledJobDetail> { key ->
                                com.clma.app.ui.pages.setting.scheduledjobs.ScheduledJobDetailScreen(jobId = key.id)
                            }

                            entry<Screen.SettingDoctor> {
                                com.clma.app.ui.pages.setting.doctor.DoctorScreen()
                            }

                            entry<Screen.GettingStarted> {
                                GettingStartedPage()
                            }

                            entry<Screen.SettingToolApprovals> {
                                com.clma.app.ui.pages.setting.SettingToolApprovalsPage()
                            }

                            entry<Screen.SettingAccessibility> {
                                SettingAccessibilityPage()
                            }

                            entry<Screen.SettingFloatingBall> {
                                SettingFloatingBallPage()
                            }

                            entry<Screen.SettingNotifications> {
                                SettingNotificationsPage()
                            }

                            entry<Screen.SettingPermissions> {
                                SettingPermissionsPage()
                            }

                            entry<Screen.Developer> {
                                DeveloperPage()
                            }

                            entry<Screen.Debug> {
                                DebugPage()
                            }

                            entry<Screen.Log> {
                                LogPage()
                            }

                            entry<Screen.Extensions> {
                                ExtensionsPage()
                            }

                            entry<Screen.QuickMessages> {
                                QuickMessagesPage()
                            }

                            entry<Screen.Prompts> { key ->
                                PromptPage(initialTab = key.initialTab)
                            }

                            entry<Screen.Skills> {
                                SkillsPage()
                            }

                            entry<Screen.Workspaces> {
                                WorkspacePage()
                            }

                            entry<Screen.WorkspaceDetail> { key ->
                                WorkspaceDetailPage(key.id)
                            }

                            entry<Screen.WorkspaceTerminal> { key ->
                                WorkspaceTerminalPage(key.id)
                            }

                            entry<Screen.WorkspaceFileEditor> { key ->
                                WorkspaceFileEditorPage(
                                    id = key.id,
                                    area = WorkspaceStorageArea.valueOf(key.area),
                                    path = key.path,
                                )
                            }

                            entry<Screen.SkillDetail> { key ->
                                SkillDetailPage(skillName = key.skillName)
                            }

                            entry<Screen.MessageSearch> {
                                SearchPage()
                            }

                            entry<Screen.Stats> {
                                StatsPage()
                            }

                        }
                    )
                    if (BuildConfig.DEBUG) {
                        Text(
                            text = "[开发模式]",
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                        )
                    }
                    AnimatedVisibility(
                        visible = migrationState is MigrationState.Migrating,
                        enter = fadeIn(),
                        exit = fadeOut(),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        val state = migrationState as? MigrationState.Migrating
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                CircularProgressIndicator()
                                Text(
                                    text = stringResource(R.string.db_migrating),
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                if (state != null) {
                                    Text(
                                        text = "v${state.from} → v${state.to}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

sealed interface Screen : NavKey {
    @Serializable
    data class Chat(
        val id: String,
        val text: String? = null,
        val files: List<String> = emptyList(),
        val nodeId: String? = null
    ) : Screen

    @Serializable
    data class ShareHandler(val text: String, val streamUri: String? = null) : Screen

    @Serializable
    data object History : Screen

    @Serializable
    data object Favorite : Screen

    @Serializable
    data object Assistant : Screen

    @Serializable
    data class AssistantDetail(val id: String) : Screen

    @Serializable
    data class AssistantBasic(val id: String) : Screen

    @Serializable
    data class AssistantPrompt(val id: String) : Screen

    @Serializable
    data class AssistantMemory(val id: String) : Screen

    @Serializable
    data class AssistantRequest(val id: String) : Screen

    @Serializable
    data class AssistantMcp(val id: String) : Screen

    @Serializable
    data class AssistantLocalTool(val id: String) : Screen

    @Serializable
    data class AssistantInjections(val id: String) : Screen

    @Serializable
    data object Translator : Screen

    @Serializable
    data object Setting : Screen

    @Serializable
    data object SettingsSearch : Screen

    @Serializable
    data class Backup(val initialTab: Int = 0) : Screen

    @Serializable
    data object ImageGen : Screen

    @Serializable
    data object VideoGen : Screen

    @Serializable
    data class WebView(val url: String = "", val contentId: String = "") : Screen

    @Serializable
    data object SettingTheme : Screen

    @Serializable
    data object SettingPreferencesTheme : Screen

    @Serializable
    data object SettingPreferencesNotification : Screen

    @Serializable
    data object SettingPreferencesGeneral : Screen

    @Serializable
    data object SettingPreferencesUI : Screen

    @Serializable
    data object SettingPreferencesNetwork : Screen

    @Serializable
    data object SettingProvider : Screen

    @Serializable
    data class SettingProviderDetail(val providerId: String) : Screen

    @Serializable
    data class SettingModels(val initialTab: Int = 0) : Screen

    @Serializable
    data object SettingAbout : Screen

    @Serializable
    data object SettingSearch : Screen

    @Serializable
    data class SettingSearchDetail(val serviceId: String) : Screen

    @Serializable
    data class SettingSpeech(val initialTab: Int = 0) : Screen

    @Serializable
    data object SettingMcp : Screen

    @Serializable
    data object SettingSubAgents : Screen

    @Serializable
    data object SettingSsh : Screen

    @Serializable
    data object SettingSecrets : Screen

    @Serializable
    data object SettingFiles : Screen

    @Serializable
    data object SettingWeb : Screen

    @Serializable
    data object SettingTelegram : Screen

    @Serializable
    data object SettingWorkflows : Screen

    @Serializable
    data class WorkflowDetail(val id: String) : Screen

    @Serializable
    data object SettingScheduledJobs : Screen

    @Serializable
    data object SettingBrowser : Screen

    @Serializable
    data object SettingTermux : Screen

    @Serializable
    data object SettingShizuku : Screen

    @Serializable
    data class ScheduledJobDetail(val id: String) : Screen

    @Serializable
    data object SettingDoctor : Screen

    @Serializable
    data object GettingStarted : Screen

    @Serializable
    data object SettingToolApprovals : Screen

    @Serializable
    data object SettingAccessibility : Screen

    @Serializable
    data object SettingFloatingBall : Screen

    @Serializable
    data object SettingNotifications : Screen

    @Serializable
    data object SettingPermissions : Screen

    @Serializable
    data object Developer : Screen

    @Serializable
    data object Debug : Screen

    @Serializable
    data object Log : Screen

    @Serializable
    data object Extensions : Screen

    @Serializable
    data object QuickMessages : Screen

    @Serializable
    data class Prompts(val initialTab: Int = 0) : Screen

    @Serializable
    data object Skills : Screen

    @Serializable
    data object Workspaces : Screen

    @Serializable
    data class WorkspaceDetail(val id: String) : Screen

    @Serializable
    data class WorkspaceTerminal(val id: String) : Screen

    @Serializable
    data class WorkspaceFileEditor(val id: String, val area: String, val path: String) : Screen

    @Serializable
    data class SkillDetail(val skillName: String) : Screen

    @Serializable
    data object MessageSearch : Screen

    @Serializable
    data object Stats : Screen

}
