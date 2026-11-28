// Adapted from OpenMausBot (https://github.com/milind-soni/OpenMausBot), src/components/NotificationSettingsRows.tsx, src/locales/en.json, src/locales/zh.json,
// Copyright 2026 Milind Soni and OpenMausBot contributors, Apache License 2.0
// (third_party/openmausbot/LICENSE). Modified: ported to Kotlin and changed for CLmA.
package com.clma.app.ui.pages.bot.settings

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clma.app.R
import com.clma.app.data.botmode.settings.BotModeSettings
import com.clma.app.data.botmode.settings.BotModeSettingsStore
import com.clma.app.ui.pages.bot.BotCollapsibleGroup
import com.clma.app.ui.pages.setting.SettingSubPageScaffold
import org.koin.compose.koinInject

@Composable
fun BotFeedbackPage() {
    val store = koinInject<BotModeSettingsStore>()
    val settings by store.settings.collectAsStateWithLifecycle(initialValue = BotModeSettings())
    val save = rememberBotSettingsSaver()
    fun update(transform: (BotModeSettings) -> BotModeSettings) = save { store.update(transform) }

    SettingSubPageScaffold(title = stringResource(R.string.bot_feedback_title)) {
        item {
            BotCollapsibleGroup("feedback_notifications", stringResource(R.string.bot_feedback_notifications)) {
                Text(stringResource(R.string.bot_feedback_notification_dependency))
                BotSwitchRow(R.string.bot_feedback_approval, settings.notifyApprovals) { enabled ->
                    update {
                        it.copy(notifyApprovals = enabled, notifyQuestions = it.notifyQuestions ?: it.notifyApprovals)
                    }
                }
                BotSwitchRow(R.string.bot_feedback_question, settings.notifyQuestions ?: settings.notifyApprovals) { enabled ->
                    update { it.copy(notifyQuestions = enabled) }
                }
                BotSwitchRow(R.string.bot_feedback_done, settings.notifyReplies) { enabled ->
                    update { it.copy(notifyReplies = enabled) }
                }
                BotSwitchRow(R.string.bot_feedback_failed, settings.notifyFailures) { enabled ->
                    update { it.copy(notifyFailures = enabled) }
                }
                BotSwitchRow(R.string.bot_feedback_routine, settings.notifyRoutineResults) { enabled ->
                    update { it.copy(notifyRoutineResults = enabled) }
                }
            }
        }
        item {
            BotCollapsibleGroup("feedback_vibration", stringResource(R.string.bot_feedback_vibration)) {
                Text(stringResource(R.string.bot_feedback_haptic_dependency))
                BotSwitchRow(R.string.bot_feedback_vibration, settings.feedbackVibration) { enabled ->
                    update { it.copy(feedbackVibration = enabled) }
                }
                FeedbackLevelRow(R.string.bot_feedback_strength, settings.feedbackStrength) { level ->
                    update { it.copy(feedbackStrength = level) }
                }
            }
        }
        item {
            BotCollapsibleGroup("feedback_sound", stringResource(R.string.bot_feedback_sound)) {
                BotSwitchRow(R.string.bot_feedback_sound, settings.feedbackSound) { enabled ->
                    update { it.copy(feedbackSound = enabled) }
                }
                FeedbackLevelRow(R.string.bot_feedback_volume, settings.feedbackVolume) { level ->
                    update { it.copy(feedbackVolume = level) }
                }
            }
        }
    }
}

@Composable
private fun FeedbackLevelRow(title: Int, value: Int, onSelect: (Int) -> Unit) {
    BotChoiceRow(
        title = stringResource(title),
        value = stringResource(R.string.bot_feedback_percent, value),
        options = (25..100 step 25).map { it to stringResource(R.string.bot_feedback_percent, it) },
        onSelect = onSelect,
    )
}
