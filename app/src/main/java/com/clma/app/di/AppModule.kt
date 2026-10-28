package com.clma.app.di

import kotlinx.serialization.json.Json
import com.clma.app.AppScope
import com.clma.app.data.ai.AILoggingManager
import com.clma.app.data.ai.tools.ChatToolFactory
import com.clma.app.data.ai.tools.LocalTools
import com.clma.app.data.ai.tools.local.BiometricResultBuffer
import com.clma.app.data.ai.tools.local.CameraResultBuffer
import com.clma.app.data.event.AppEventBus
import com.clma.app.data.ai.tools.local.InteractiveToolStreamer
import com.clma.app.data.repository.ScheduledJobRepository
import com.clma.app.data.repository.SshHostRepository
import com.clma.app.data.repository.TelegramChatRepository
import com.clma.app.data.notifications.NotificationListenerPreferences
import com.clma.app.data.telegram.TelegramBotClient
import com.clma.app.data.telegram.TelegramBotPreferences
import com.clma.app.service.ChatService
import com.clma.app.service.CronJobScheduler
import com.clma.app.ui.pages.extensions.workspace.WorkspaceTerminalSessionManager
import com.clma.app.utils.EmojiData
import com.clma.app.utils.EmojiUtils
import com.clma.app.utils.JsonInstant
import com.clma.app.utils.SoundEffectPlayer
import com.clma.app.web.WebServerManager
import com.clma.tts.provider.TTSManager
import org.koin.dsl.module

val appModule = module {
    single<Json> { JsonInstant }

    single {
        AppEventBus()
    }

    single { CameraResultBuffer() }
    single { BiometricResultBuffer() }
    // Phase 25 — NFC reader-mode + SAF directory-picker Activity bridges, and the SAF
    // tree-grant store backing the ExternalStorage tools.
    single { com.clma.app.data.ai.tools.local.NfcResultBuffer() }
    single { com.clma.app.data.ai.tools.local.SafPickerResultBuffer() }
    single { com.clma.app.data.storage.StorageVolumeGrantStore(get()) }

    single { ScheduledJobRepository(get<com.clma.app.data.db.AppDatabase>().scheduledJobDao()) }
    single { com.clma.app.data.repository.ScheduledJobRunRepository(get<com.clma.app.data.db.AppDatabase>().scheduledJobRunDao()) }
    single { com.clma.app.service.DirectModeActionRunner(get()) }
    single { CronJobScheduler(get(), get(), get()) }
    single { SshHostRepository(get<com.clma.app.data.db.AppDatabase>().sshHostDao()) }
    single { TelegramChatRepository(get<com.clma.app.data.db.AppDatabase>().telegramChatDao()) }
    single { TelegramBotPreferences(get()) }
    single { com.clma.app.browser.BrowserPreferences(get()) }
    single { com.clma.app.data.preferences.TermuxPreferences(get()) }
    // Pass 3: Telegram-bound screenshot streamer for headless browser mode. Bound to the
    // [BrowserScreenshotStreamer] interface so [BrowserController.streamScreenshotIfHeadless]
    // can resolve it lazily via Koin without taking a constructor dep — avoids a cycle
    // through TelegramBotClient → TelegramBotPreferences → ... → LocalTools → controller.
    single<com.clma.app.browser.BrowserScreenshotStreamer> {
        com.clma.app.data.telegram.TelegramBrowserScreenshotStreamer(get(), get(), get())
    }
    // Interactive-tool post-action screenshot streamer for headless mode (Telegram bot /
    // cron / sub-agent). Resolves lazily inside each interactive tool's execute lambda so
    // there's no DI cycle through LocalTools → ChatService → ... → TelegramBotClient.
    single<InteractiveToolStreamer> {
        com.clma.app.data.telegram.TelegramInteractiveToolStreamer(get(), get(), get(), get())
    }
    single { com.clma.app.data.preferences.ToolApprovalPreferences(get()) }
    single {
        TelegramBotClient(
            tokenProvider = { runCatching { kotlinx.coroutines.runBlocking { get<TelegramBotPreferences>().current().token } }.getOrDefault("") },
            proxyConfigProvider = {
                runCatching {
                    kotlinx.coroutines.runBlocking { get<TelegramBotPreferences>().current() }
                }.getOrDefault(com.clma.app.data.telegram.TelegramBotConfig())
            },
        )
    }
    // Phase 24 — Telegram long-poll stall tracker. Shared singleton: TelegramBotService's
    // poll loop calls markUpdate() on every getUpdates; the in-service stall checker and
    // DoctorChecks read it. No cross-dependencies, so no DI-cycle risk.
    single { com.clma.app.data.telegram.TelegramPollStallTracker() }
    single { NotificationListenerPreferences(get()) }

    // Phase 13: External Automation Intent API
    single { com.clma.app.automation.ExternalAutomationConfig(get()) }
    single {
        com.clma.app.automation.ExternalAutomationDispatcher(
            context = get(),
            config = get(),
            chatService = get(),
            conversationRepo = get(),
            settingsStore = get(),
            appScope = get(),
            // Phase 24 — unified AgentRun ledger writer.
            agentRunRepo = get(),
        )
    }

    // Phase 14: Reliability bundle
    single { com.clma.app.reliability.GitHubReleaseChecker(get()) }
    single { com.clma.app.reliability.BugReportBuilder(get()) }

    // Phase 11: Sub-agents
    single { com.clma.app.subagent.SubAgentRegistry() }
    single {
        com.clma.app.subagent.SubAgentEngine(
            registry = get(),
            // chatService is resolved lazily inside SubAgentEngine to break the
            // ChatService→LocalTools→SubAgentEngine→ChatService cycle. See SubAgentEngine kdoc.
            conversationRepo = get(),
            settingsStore = get(),
            appScope = get(),
            // Phase 24 — unified AgentRun ledger writer. No DI cycle: AgentRunRepository
            // depends only on its DAO.
            agentRunRepo = get(),
            // P2-06b — the expert library, the source of truth for a dispatch's `agent` name.
            agentDefinitionRepository = get(),
            // P2-23 — read-only: only asked whether the configured archive folder still exists.
            folderRepository = get(),
            // P2-13 — read-only: the budget gate and the run usage read-back.
            usageLedger = get(),
        )
    }

    // Phase 16: Skill URL-import
    single {
        com.clma.app.skills.SkillUrlImporter(
            skillManager = get<com.clma.app.data.files.SkillManager>(),
        )
    }

    // Phase 19B: Skill isolation tester. Eager construction is safe here — ChatService
    // doesn't reach back into SkillTestRunner anywhere, so no DI cycle.
    single {
        com.clma.app.skills.SkillTestRunner(
            chatService = get(),
            skillManager = get(),
            conversationRepo = get(),
            settingsStore = get(),
        )
    }

    // Phase 18: JS skills (run_js + secrets store)
    single { com.clma.app.skills.js.JsSkillRunner(get()) }
    single { com.clma.app.skills.js.SkillSecretsStore(get()) }

    // T-07: named secrets a workflow action can reference as {{secret:NAME}} — keystore-backed
    // so the token never has to live inside the (plaintext, model-visible) workflow JSON.
    single { com.clma.app.workflow.secrets.WorkflowSecretsStore(get()) }
    single {
        com.clma.app.data.secrets.AgentSecretsStore(get()).also {
            // 工具输出脱敏需要读保险箱明文 (见 SecretVaultRegistry)
            com.clma.app.data.secrets.SecretVaultRegistry.install(it)
        }
    }

    // Phase 12: Workflows
    single {
        com.clma.app.workflow.repository.WorkflowRepository(
            workflowDao = get<com.clma.app.data.db.AppDatabase>().workflowDao(),
            workflowRunDao = get<com.clma.app.data.db.AppDatabase>().workflowRunDao(),
        )
    }
    single { com.clma.app.workflow.condition.ContextProvider(get()) }
    single { com.clma.app.workflow.execution.WorkflowActionRunner() }
    single {
        com.clma.app.workflow.execution.WorkflowEngine(
            repository = get(),
            settingsStore = get(),
            contextProvider = get(),
            actionRunner = get(),
        ).also { engine ->
            // Bridge for the repo to notify the engine on delete so the engine's per-workflow
            // lock map doesn't leak. Lazy because both singletons have to exist first.
            get<com.clma.app.workflow.repository.WorkflowRepository>().bindEngine(engine)
        }
    }
    single {
        com.clma.app.workflow.trigger.TriggerRegistry(
            context = get(),
            appScope = get(),
            workflowRepository = get(),
        )
    }

    single { com.clma.app.data.keyboard.KeyboardApiClient(get()) }

    single {
        LocalTools(
            usageRecordDao = get(),
            agentRunRepository = get(),
            context = get(),
            eventBus = get(),
            cameraResultBuffer = get(),
            biometricResultBuffer = get(),
            scheduledJobRepository = get(),
            scheduledJobRunRepository = get(),
            cronJobScheduler = get(),
            settingsStore = get(),
            sshHostRepository = get(),
            agentSecretsStore = get(),
            telegramBotPreferences = get(),
            telegramBotClient = get(),
            notificationListenerPreferences = get(),
            mcpManager = get(),
            externalAutomationConfig = get(),
            gitHubReleaseChecker = get(),
            bugReportBuilder = get(),
            agentDefinitionRepository = get(),
            subAgentEngine = get(),
            subAgentRegistry = get(),
            conversationRepo = get(),
            workflowRepository = get(),
            workflowEngine = get(),
            skillUrlImporter = get(),
            skillManager = get(),
            jsSkillRunner = get(),
            skillSecretsStore = get(),
            browserPreferences = get(),
            termuxPreferences = get(),
            interactiveToolStreamer = get(),
            nfcResultBuffer = get(),
            safPickerResultBuffer = get(),
            storageVolumeGrantStore = get(),
            okHttpClient = get(),
            keyboardApiClient = get(),
            // P2-33 image generation tools
            providerManager = get(),
            filesManager = get(),
            genMediaRepository = get(),
        )
    }

    // In-app UpdateCard removed for CLmA: no UpdateChecker singleton.
    // GitHubReleaseChecker stays for the agent check_app_updates tool only.

    single {
        AppScope()
    }

    single<EmojiData> {
        EmojiUtils.loadEmoji(get())
    }

    single {
        TTSManager(get())
    }

    single {
        SoundEffectPlayer(get())
    }

    single {
        AILoggingManager(get(), get())
    }

    single {
        WorkspaceTerminalSessionManager(get(), get())
    }

    // Phase 22A: Local-LLM on-device providers
    single { com.clma.locallm.LocalRuntimePreferences(get()) }
    single { com.clma.locallm.litert.LiteRtRuntime(get()) }
    single { com.clma.llamacpp.LlamaCppRuntime() }

    single {
        ChatToolFactory(
            json = get(),
            memoryRepository = get(),
            conversationRepository = get(),
            localTools = get(),
            mcpManager = get(),
            skillManager = get(),
            workspaceRepository = get(),
        )
    }

    single {
        ChatService(
            context = get(),
            appScope = get(),
            appEventBus = get(),
            settingsStore = get(),
            conversationRepo = get(),
            memoryRepository = get(),
            generationLoop = get(),
            translationHandler = get(),
            templateTransformer = get(),
            providerManager = get(),
            localTools = get(),
            chatToolFactory = get(),
            mcpManager = get(),
            filesManager = get(),
            skillManager = get(),
            toolApprovalPreferences = get(),
            workspaceRepository = get(),
            folderRepository = get(),
        )
    }

    single {
        WebServerManager(
            context = get(),
            appScope = get(),
            chatService = get(),
            conversationRepo = get(),
            folderRepo = get(),
            settingsStore = get(),
            filesManager = get()
        )
    }

    single {
        com.clma.app.ui.pages.setting.doctor.DoctorChecks(
            context = get(),
            settingsStore = get(),
            telegramPrefs = get(),
            workflowRepository = get(),
            scheduledJobRepository = get(),
            scheduledJobRunRepository = get(),
            conversationRepository = get(),
            database = get(),
            // Pass 3: surface the browser write-tools-enabled INFO row + profile-dir AutoFix.
            browserPreferences = get(),
            // Phase 25: surface the SAF granted-directories live count.
            storageVolumeGrantStore = get(),
            // LiteRT accelerator status row in the Doctor: shows the persisted backend
            // decision so a silent GPU -> CPU fallback is visible.
            localRuntimePreferences = get(),
            // Doctor refresh: skills.* and service.mcp_servers rows.
            skillManager = get(),
            mcpManager = get(),
            // P2-06b: the expert library behind the assistant.subagent_profiles row.
            agentDefinitionRepository = get(),
        )
    }
}
