package com.clma.app.di

import com.clma.app.ui.pages.assistant.AssistantVM
import com.clma.app.ui.pages.assistant.detail.AssistantDetailVM
import com.clma.app.ui.pages.backup.BackupVM
import com.clma.app.ui.pages.chat.ChatDrawerVM
import com.clma.app.ui.pages.chat.ChatVM
import com.clma.app.ui.pages.debug.DebugVM
import com.clma.app.ui.pages.developer.DeveloperVM
import com.clma.app.ui.pages.favorite.FavoriteVM
import com.clma.app.ui.pages.search.SearchVM
import com.clma.app.ui.pages.history.HistoryVM
import com.clma.app.ui.pages.stats.StatsVM
import com.clma.app.ui.pages.imggen.ImgGenVM
import com.clma.app.ui.pages.videogen.VideoGenVM
import com.clma.app.ui.pages.extensions.PromptVM
import com.clma.app.ui.pages.extensions.QuickMessagesVM
import com.clma.app.ui.pages.extensions.skills.SkillDetailVM
import com.clma.app.ui.pages.extensions.skills.SkillsVM
import com.clma.app.ui.pages.extensions.workspace.WorkspaceDetailVM
import com.clma.app.ui.pages.extensions.workspace.WorkspaceVM
import com.clma.app.ui.pages.setting.SettingVM
// P2-32 ssh hosts page
import com.clma.app.ui.pages.setting.SettingSshViewModel
import com.clma.app.ui.pages.setting.browser.SettingBrowserViewModel
import com.clma.app.ui.pages.setting.termux.SettingTermuxViewModel
import com.clma.app.ui.pages.setting.locallm.SettingLocalLlmViewModel
import com.clma.app.ui.pages.share.handler.ShareHandlerVM
import com.clma.app.ui.pages.translator.TranslatorVM
import com.clma.app.ui.pages.setting.doctor.DoctorViewModel
import com.clma.app.ui.pages.setting.scheduledjobs.ScheduledJobsViewModel
import com.clma.app.workflow.ui.WorkflowsViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val viewModelModule = module {
    viewModel<ChatVM> { params ->
        ChatVM(
            id = params.get(),
            isTemporary = runCatching { params.get<Boolean>(1) }.getOrDefault(false),
            context = get(),
            settingsStore = get(),
            conversationRepo = get(),
            chatService = get(),
            filesManager = get(),
            favoriteRepository = get(),
            usageLedger = get(),
        )
    }
    viewModelOf(::ChatDrawerVM)
    viewModelOf(::SettingVM)
    viewModelOf(::DebugVM)
    viewModelOf(::DeveloperVM)
    viewModelOf(::HistoryVM)
    viewModelOf(::AssistantVM)
    viewModel<AssistantDetailVM> {
        AssistantDetailVM(
            id = it.get(),
            settingsStore = get(),
            memoryRepository = get(),
            filesManager = get(),
            skillManager = get(),
            workspaceRepository = get(),
            // P2-24 — read-only: the sub-agent archive folder picker.
            folderRepository = get(),
        )
    }
    viewModelOf(::TranslatorVM)
    viewModel<ShareHandlerVM> {
        ShareHandlerVM(
            text = it.get(),
            settingsStore = get(),
        )
    }
    viewModelOf(::BackupVM)
    viewModelOf(::ImgGenVM)
    viewModelOf(::VideoGenVM)
    viewModelOf(::PromptVM)
    viewModelOf(::QuickMessagesVM)
    viewModel<SkillsVM> {
        SkillsVM(
            context = get(),
            skillManager = get(),
            urlImporter = get(),
            settingsStore = get(),
        )
    }
    viewModelOf(::SkillDetailVM)
    viewModelOf(::WorkspaceVM)
    viewModel<WorkspaceDetailVM> {
        WorkspaceDetailVM(
            id = it.get(),
            repository = get(),
            terminalSessionManager = get(),
        )
    }
    viewModelOf(::FavoriteVM)
    viewModelOf(::SearchVM)
    viewModelOf(::StatsVM)
    viewModelOf(::WorkflowsViewModel)
    viewModelOf(::ScheduledJobsViewModel)
    viewModelOf(::DoctorViewModel)
    viewModelOf(::SettingBrowserViewModel)
    viewModelOf(::SettingTermuxViewModel)
    // P2-32 ssh hosts page
    viewModelOf(::SettingSshViewModel)

    // Phase 22A: parameterised by LocalRuntime — one VM instance per provider tile.
    viewModel<SettingLocalLlmViewModel> { params ->
        SettingLocalLlmViewModel(
            runtime = params.get(),
            context = get(),
            prefs = get(),
            httpClient = get(),
            settingsStore = get(),
            hfApi = get(),
        )
    }
}
