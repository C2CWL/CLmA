package com.clma.app

import com.clma.app.diagnostics.FunctionalDiagnostics
import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import com.clma.app.ui.motion.Motion
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.lifecycle.lifecycleScope
import android.widget.Toast
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
import com.clma.app.data.datastore.Settings
import com.clma.app.data.repository.ConversationRepository
import com.clma.app.data.model.Assistant
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
import com.clma.app.ui.hooks.writeBooleanPreference
import com.clma.app.ui.hooks.rememberCustomTtsState
import com.clma.app.ui.hooks.rememberCustomAsrState
import com.clma.app.ui.hooks.rememberCurrentColorMode
import com.clma.app.ui.hooks.rememberSharedPreferenceBoolean
import com.clma.app.ui.pages.assistant.AssistantPage
import com.clma.app.ui.pages.assistant.detail.AssistantBasicPage
import com.clma.app.ui.pages.assistant.detail.AssistantDetailPage
import com.clma.app.ui.pages.assistant.detail.AssistantExtensionsPage
import com.clma.app.ui.pages.assistant.detail.AssistantLocalToolPage
import com.clma.app.ui.pages.assistant.detail.AssistantSshPage
import com.clma.app.ui.pages.assistant.detail.AssistantMcpPage
import com.clma.app.ui.pages.assistant.detail.AssistantMemoryPage
import com.clma.app.ui.pages.assistant.detail.AssistantPromptPage
import com.clma.app.ui.pages.assistant.detail.AssistantRequestPage
import com.clma.app.ui.pages.backup.BackupPage
import com.clma.app.ui.pages.chat.ChatPage
import com.clma.app.ui.pages.debug.DebugPage
import com.clma.app.ui.pages.developer.DeveloperPage
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
import com.clma.app.ui.pages.setting.SettingColorModePage
import com.clma.app.ui.pages.setting.SettingFontsPage
import com.clma.app.ui.pages.setting.SettingChatLayoutPage
import com.clma.app.ui.pages.setting.SettingRuntimePage
import com.clma.app.ui.pages.setting.SettingThemePage
import com.clma.app.ui.pages.setting.SettingFilesPage
import com.clma.app.ui.pages.setting.SettingMcpPage
import com.clma.app.ui.pages.setting.SettingModelPage
import com.clma.app.ui.pages.setting.SettingPage
import com.clma.app.ui.pages.setting.SettingsHubSectionId
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
import com.clma.app.ui.pages.bot.BOT_MODE_ROOT_PREF_KEY
import com.clma.app.data.botmode.ensureBotLibrary
import com.clma.app.data.botmode.withAssistantInLibrary
import com.clma.app.ui.pages.bot.BotHomePage
import com.clma.app.ui.pages.bot.BotChatPage
import com.clma.app.ui.pages.bot.conversationLinkTarget
import com.clma.app.ui.pages.bot.initialRootStack
import com.clma.app.ui.pages.bot.libraryGuardedStack
import com.clma.app.ui.pages.bot.routeConversationId
import com.clma.app.ui.pages.bot.isStackResetTo
import com.clma.app.ui.pages.bot.reconcileRootStack
import com.clma.app.ui.pages.bot.resetStackTo
import okhttp3.OkHttpClient
import org.koin.android.ext.android.inject
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

private const val TAG = "RouteActivity"
private const val ACTION_TRANSLATE = "me.rerere.rikkahub.action.TRANSLATE"

class RouteActivity : FragmentActivity() {
    companion object {
        const val EXTRA_OPEN_CODEX_SETTINGS = "open_codex_settings"
        const val EXTRA_OPEN_GEMINI_SETTINGS = "open_gemini_settings"
    }

    private val okHttpClient by inject<OkHttpClient>()
    private val settingsStore by inject<SettingsStore>()
    private val conversationRepo by inject<ConversationRepository>()
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
        FunctionalDiagnostics.event("activity.create", System.identityHashCode(this), if (savedInstanceState == null) 0 else 1)
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
        val conversationId = intent.getStringExtra("conversationId")
        val destination = when (intent.action) {
            ACTION_TRANSLATE -> Screen.Translator
            Intent.ACTION_SEND -> Screen.ShareHandler(
                text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty(),
                streamUri = intent.getStringExtra(Intent.EXTRA_STREAM),
            )
            Intent.ACTION_PROCESS_TEXT -> Screen.ShareHandler(
                text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty(),
            )
            else -> conversationId?.let { id ->
                // Library isolation: the target page depends on which library the stored
                // conversation belongs to, which needs the real settings and a DB read.
                // One-shot: keep it out of the Intent so a config change can't replay it.
                intent.removeExtra("conversationId")
                openConversationLink(backStack, id)
                return
            }
        }
        if (destination != null && backStack.lastOrNull() != destination) {
            backStack.add(destination)
        }
    }

    /**
     * "Open this conversation" (edge bar tap, notification, shortcut): Bot mode opens Bot
     * conversations in the Bot chat page, classic opens classic ones in the classic page, and a
     * conversation of the other library is refused with a hint — the mode is never switched
     * implicitly (the explicit Bot-mode switch is untouched).
     */
    private fun openConversationLink(backStack: MutableList<NavKey>, conversationId: String) {
        lifecycleScope.launch {
            val settings = settingsStore.settingsFlow.first { !it.init }
            val owner = runCatching { Uuid.parse(conversationId) }.getOrNull()
                ?.let { conversationRepo.getAssistantIdOfConversation(it) }
            val destination = conversationLinkTarget(
                botRoot = backStack.firstOrNull() == Screen.BotHome,
                conversationId = conversationId,
                owner = owner,
                settings = settings,
            )
            if (destination == null) {
                Toast.makeText(
                    this@RouteActivity,
                    if (settings.displaySetting.botModeEnabled) {
                        R.string.bot_library_link_classic_conversation
                    } else {
                        R.string.bot_library_link_bot_conversation
                    },
                    Toast.LENGTH_SHORT,
                ).show()
                return@launch
            }
            // Replaces the stack — the same semantics as the drawer's clearAndNavigate — instead
            // of stacking another chat: repeated taps used to pile up chat entries.
            if (!isStackResetTo(backStack, destination)) {
                resetStackTo(backStack, destination)
            }
        }
    }

    /** Drops conversation pages of the other library from the stack (see [libraryGuardedStack]). */
    private suspend fun guardStackLibrary(backStack: MutableList<NavKey>, settings: Settings, newChat: () -> Screen.Chat) {
        val before = backStack.toList()
        val owners = before.mapNotNull { it.routeConversationId() }.distinct().associateWith { id ->
            runCatching { Uuid.parse(id) }.getOrNull()?.let { conversationRepo.getAssistantIdOfConversation(it) }
        }
        val guarded = libraryGuardedStack(before, settings, owners, newChat)
        // Only apply if the user has not navigated meanwhile.
        if (guarded !== before && backStack.toList() == before) {
            backStack.clear()
            backStack.addAll(guarded)
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    fun AppRoutes() {
        val toastState = rememberToasterState()
        val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
        val colorMode = rememberCurrentColorMode()
        // Tell the system which splash to show on the next cold start (API 31+). Runs on start
        // and whenever the visual style / colour mode changes; skips the placeholder settings
        // emitted before the store has loaded.
        LaunchedEffect(settings.init, settings.themeId, settings.dynamicColor, colorMode) {
            if (!settings.init) {
                syncSplashTheme(SplashStyle.resolve(settings.themeId, settings.dynamicColor, colorMode))
            }
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

        // Bot mode has its own root (Bot home). The real settings load asynchronously, so the
        // cold-start root comes from a synchronous pref mirror; the effect below corrects it.
        val initialStack = remember {
            initialRootStack(
                botMode = readBooleanPreference(BOT_MODE_ROOT_PREF_KEY, false),
                chatIds = initialChatIds,
                deepLinkConversationId = deepLinkConversationId,
            )
        }
        val backStack = rememberNavBackStack(*initialStack.toTypedArray())
        val botModeEnabled = settings.displaySetting.botModeEnabled
        LaunchedEffect(settings.init, botModeEnabled) {
            if (settings.init) return@LaunchedEffect
            writeBooleanPreference(BOT_MODE_ROOT_PREF_KEY, botModeEnabled)
            // Separate libraries: Bot mode sets up its own (with a main bot) on first use, and
            // the current assistant always belongs to the active library.
            val mainName = getString(R.string.bot_main_default_name)
            val mainPrompt = getString(R.string.bot_main_default_prompt)
            val fix: (Settings) -> Settings = { s ->
                val withLibrary = if (botModeEnabled) {
                    s.ensureBotLibrary { Assistant(name = mainName, systemPrompt = mainPrompt) }
                } else {
                    s
                }
                withLibrary.withAssistantInLibrary(botModeEnabled)
            }
            if (fix(settings) != settings) settingsStore.update(fix)
            reconcileRootStack(backStack, botModeEnabled) {
                Screen.Chat(
                    if (readBooleanPreference("create_new_conversation_on_start", true)) {
                        Uuid.random().toString()
                    } else {
                        readStringPreference("lastConversationId", null) ?: Uuid.random().toString()
                    }
                )
            }
            // Library isolation: a restored / linked conversation page of the other library
            // (e.g. the last Bot conversation restored into the classic chat) never shows.
            guardStackLibrary(backStack, settings) { Screen.Chat(Uuid.random().toString()) }
        }
        val diagnosticFunctions by rememberSharedPreferenceBoolean(FunctionalDiagnostics.FUNCTION_KEY)
        LaunchedEffect(backStack, diagnosticFunctions) {
            if (!diagnosticFunctions) return@LaunchedEffect
            snapshotFlow { backStack.toList() }.collect { stack ->
                FunctionalDiagnostics.event("navigation.stackSize", value = stack.size.toLong())
                FunctionalDiagnostics.event("navigation.chatOnTop", value = if (stack.lastOrNull() is Screen.Chat) 1 else 0)
            }
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
                backStack.size == 1 &&
                (backStack.firstOrNull() is Screen.Chat || backStack.firstOrNull() == Screen.BotHome)
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
                        // 轻滑 + 淡入，进出对称（对齐 Kelivo 节拍，不再缩到 0.7）。
                        transitionSpec = {
                            if (backStack.size == 1) {
                                fadeIn(Motion.enter(Motion.PAGE)) togetherWith fadeOut(Motion.exit(Motion.FAST))
                            } else {
                                pageForwardEnter() togetherWith pageForwardExit()
                            }
                        },
                        popTransitionSpec = {
                            pageBackEnter() togetherWith pageBackExit()
                        },
                        predictivePopTransitionSpec = {
                            pageBackEnter() togetherWith pageBackExit()
                        },
                        entryProvider = entryProvider {
                            entry<Screen.Chat>(
                                metadata = NavDisplay.transitionSpec {
                                    fadeIn(Motion.enter(Motion.PAGE)) togetherWith fadeOut(Motion.exit(Motion.FAST))
                                } + NavDisplay.popTransitionSpec {
                                    fadeIn(Motion.enter(Motion.PAGE)) togetherWith fadeOut(Motion.exit(Motion.FAST))
                                }
                            ) { key ->
                                ChatPage(
                                    id = Uuid.parse(key.id),
                                    text = key.text,
                                    files = key.files.map { it.toUri() },
                                    nodeId = key.nodeId?.let { Uuid.parse(it) },
                                    messageId = key.messageId?.let { Uuid.parse(it) },
                                    isTemporary = key.isTemporary,
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

                            entry<Screen.AssistantSsh> { key ->
                                AssistantSshPage(key.id)
                            }

                            entry<Screen.AssistantInjections> { key ->
                                AssistantExtensionsPage(key.id)
                            }

                            entry<Screen.Translator> {
                                TranslatorPage()
                            }

                            entry<Screen.BotHome> {
                                BotHomePage()
                            }

                            entry<Screen.BotChat> { key ->
                                BotChatPage(
                                    botId = Uuid.parse(key.botId),
                                    conversationId = Uuid.parse(key.conversationId),
                                    text = key.text,
                                    files = key.files.map { it.toUri() },
                                )
                            }

                            entry<Screen.BotInfo> { key ->
                                com.clma.app.ui.pages.bot.BotInfoPage(
                                    botId = key.botId,
                                    conversationId = key.conversationId,
                                )
                            }

                            entry<Screen.BotChannel> { key ->
                                com.clma.app.ui.pages.bot.BotChannelPage(channelId = key.channelId)
                            }

                            entry<Screen.Setting> {
                                SettingPage()
                            }

                            entry<Screen.SettingsCategory> { key ->
                                SettingPage(sectionId = key.sectionId)
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

                            entry<Screen.SettingColorMode> {
                                SettingColorModePage()
                            }

                            entry<Screen.SettingFonts> {
                                SettingFontsPage()
                            }

                            entry<Screen.SettingChatLayout> {
                                SettingChatLayoutPage()
                            }

                            entry<Screen.SettingRuntime> {
                                SettingRuntimePage()
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

                            entry<Screen.SettingConnectors> {
                                com.clma.app.ui.pages.setting.SettingConnectorsPage()
                            }

                            entry<Screen.SettingFiles> {
                                SettingFilesPage()
                            }

                            entry<Screen.SettingWeb> {
                                SettingWebPage()
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

                            entry<Screen.VirtualDisplays> {
                                com.clma.app.ui.pages.setting.shizuku.VirtualDisplaysPage()
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
        val nodeId: String? = null,
        val isTemporary: Boolean = false,
        val messageId: String? = null,
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
    data class AssistantSsh(val id: String) : Screen

    @Serializable
    data class AssistantInjections(val id: String) : Screen

    @Serializable
    data object Translator : Screen

    @Serializable
    data object Setting : Screen

    /** Bot mode root: bot list / channels / settings tabs. Classic mode never shows it. */
    @Serializable
    data object BotHome : Screen

    /** One bot's conversation in Bot mode (its own chrome, no classic drawer). */
    @Serializable
    data class BotChat(
        val botId: String,
        val conversationId: String,
        // Composer prefill (share sheet in Bot mode), same encoding as [Chat.text] / [Chat.files].
        val text: String? = null,
        val files: List<String> = emptyList(),
    ) : Screen

    /** A bot's info page in Bot mode (model, prompt, memory, approvals, apps, displays...). */
    @Serializable
    data class BotInfo(val botId: String, val conversationId: String? = null) : Screen

    /** One Bot-mode channel: its members and a message box that reaches them. */
    @Serializable
    data class BotChannel(val channelId: String) : Screen

    @Serializable
    data object SettingsSearch : Screen

    @Serializable
    data class SettingsCategory(val sectionId: SettingsHubSectionId) : Screen

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
    data object SettingColorMode : Screen

    @Serializable
    data object SettingFonts : Screen

    @Serializable
    data object SettingChatLayout : Screen

    @Serializable
    data object SettingRuntime : Screen

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
    data object SettingConnectors : Screen

    @Serializable
    data object SettingFiles : Screen

    @Serializable
    data object SettingWeb : Screen

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
    data object VirtualDisplays : Screen

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

// ---- 页面转场（统一节拍，见 ui/motion/Motion.kt） ----
// 前进：新页从右侧 1/4 处轻滑进来并淡入；旧页向左挪一点并淡出。
// 返回：完全镜像，所以进出对称。
private fun pageForwardEnter(): EnterTransition =
    slideInHorizontally(Motion.enter(Motion.PAGE)) { it / 4 } + fadeIn(Motion.enter(Motion.PAGE))

private fun pageForwardExit(): ExitTransition =
    slideOutHorizontally(Motion.enter(Motion.PAGE)) { -it / 8 } + fadeOut(Motion.exit(Motion.FAST))

private fun pageBackEnter(): EnterTransition =
    slideInHorizontally(Motion.enter(Motion.PAGE)) { -it / 8 } + fadeIn(Motion.enter(Motion.PAGE))

private fun pageBackExit(): ExitTransition =
    slideOutHorizontally(Motion.enter(Motion.PAGE)) { it / 4 } + fadeOut(Motion.exit(Motion.FAST))
