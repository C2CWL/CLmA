// Adapted from OpenMausBot (https://github.com/milind-soni/OpenMausBot), src/components/bot-settings/ToolGroupsCard.tsx, src/components/bot-settings/ToolBudgetLine.tsx,
// Copyright 2026 Milind Soni and OpenMausBot contributors, Apache License 2.0
// (third_party/openmausbot/LICENSE). Modified: ported to Kotlin and changed for CLmA.
package com.clma.app.ui.pages.bot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clma.app.R
import com.clma.app.data.botmode.settings.BotToolBudgets
import com.clma.app.data.botmode.settings.BotToolGroup
import com.clma.app.data.datastore.Settings
import com.clma.app.data.datastore.getEffectiveContextLength
import com.clma.app.data.datastore.resolveChatModel
import com.clma.app.data.model.Assistant
import com.clma.app.data.model.Conversation

@Composable
internal fun BotToolGroupsSection(
    bot: Assistant,
    settings: Settings,
    conversation: Conversation?,
    onSetEnabled: (BotToolGroup, Boolean) -> Unit,
) {
    val model = settings.resolveChatModel(conversation?.chatModelId, bot.id)
    val snapshots by BotToolBudgets.state.collectAsStateWithLifecycle()
    val budget = if (model != null && conversation != null) {
        snapshots[BotToolBudgets.Key(bot.id, conversation.id, model.id)]?.takeIf { it.off == bot.botToolGroupsOff }
    } else null
    BotCollapsibleGroup(key = "tool_groups", title = stringResource(R.string.bot_tools_title), initiallyExpanded = false) {
        if (model != null) {
            val name = model.displayName.ifBlank { model.modelId }
            val context = settings.getEffectiveContextLength(model)
            Text(
                if (context != null) stringResource(R.string.bot_tools_model_context, name, context) else name,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            if (budget == null) stringResource(R.string.bot_tools_waiting)
            else stringResource(R.string.bot_tools_budget, budget.functions, budget.nativeTools, budget.tokens),
            style = MaterialTheme.typography.bodySmall,
        )
        BotToolGroup.entries.forEach { group ->
            val label = when (group) {
                BotToolGroup.WEB -> R.string.bot_tools_web
                BotToolGroup.IMAGES -> R.string.bot_tools_images
                BotToolGroup.WORKSPACE -> R.string.bot_tools_workspace
                BotToolGroup.APPS -> R.string.bot_tools_apps
                BotToolGroup.DATA -> R.string.bot_tools_data
            }
            val groupName = stringResource(label)
            val enabled = group.key !in bot.botToolGroupsOff
            val cost = budget?.groups?.get(group)
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    if (cost == null) groupName
                    else stringResource(R.string.bot_tools_group_cost, groupName, cost.count, cost.tokens),
                    Modifier.weight(1f),
                )
                Switch(
                    checked = enabled,
                    onCheckedChange = { onSetEnabled(group, it) },
                    modifier = Modifier.semantics { contentDescription = groupName },
                )
            }
        }
    }
}
