package com.clma.app.ui.pages.bot

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.clma.app.R
import com.clma.app.data.repository.ConversationRepository
import com.clma.app.ui.components.richtext.MarkdownBlock
import com.clma.app.ui.context.LocalNavController
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BotBriefConversationPage(botId: Uuid, conversationId: Uuid) {
    val nav = LocalNavController.current
    val repository: ConversationRepository = koinInject()
    val body by produceState<String?>(null, botId, conversationId) {
        value = repository.getConversationById(conversationId)
            ?.takeIf { it.assistantId == botId }
            ?.currentMessages?.firstOrNull()?.toText().orEmpty()
    }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.bot_brief_title)) },
            navigationIcon = {
                IconButton(onClick = { nav.popBackStack() }) {
                    Icon(HugeIcons.ArrowLeft01, contentDescription = stringResource(R.string.back))
                }
            },
        )
    }) { padding ->
        val content = body
        when {
            content == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            content.isEmpty() -> Text(
                stringResource(R.string.bot_brief_unavailable),
                modifier = Modifier.padding(padding).padding(16.dp),
            )
            else -> MarkdownBlock(
                content = content,
                modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            )
        }
    }
}
