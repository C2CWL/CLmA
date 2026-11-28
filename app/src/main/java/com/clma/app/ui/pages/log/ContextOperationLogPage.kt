package com.clma.app.ui.pages.log

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clma.app.R
import com.clma.app.data.botmode.activeLibraryScope
import com.clma.app.ui.context.LocalSettings
import androidx.compose.runtime.key
import com.clma.app.data.db.entity.ContextOperationLogEntity
import com.clma.app.data.db.entity.ContextOperationResult
import com.clma.app.data.db.entity.ContextOperationType
import com.clma.app.data.repository.ConversationRepository
import com.clma.app.ui.components.nav.BackButton
import com.clma.app.ui.theme.CustomColors
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Package01
import org.koin.compose.koinInject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ContextOperationLogPage() {
    val repository = koinInject<ConversationRepository>()
    val settings = LocalSettings.current
    val library = settings.activeLibraryScope()
    val logs by key(settings.init, library) {
        val flow = remember(library) { repository.observeContextOperationLogs(library) }
        flow.collectAsStateWithLifecycle(initialValue = emptyList<ContextOperationLogEntity>())
    }
    if (settings.init) return
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.chat_context_operation_log)) },
                navigationIcon = { BackButton() },
                actions = {
                    if (logs.isNotEmpty()) {
                        IconButton(onClick = {
                            scope.launch { repository.deleteContextOperationLogs(library) }
                        }) {
                            Icon(
                                HugeIcons.Delete01,
                                contentDescription = stringResource(R.string.chat_context_operation_log_clear),
                            )
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { contentPadding ->
        if (logs.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    HugeIcons.Package01,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(12.dp))
                Text(
                    text = stringResource(R.string.chat_context_operation_log_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = contentPadding,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(logs, key = { it.id }) { log ->
                    ContextOperationLogItem(log)
                }
            }
        }
    }
}

@Composable
private fun ContextOperationLogItem(log: ContextOperationLogEntity) {
    val isCompact = log.operationType == ContextOperationType.COMPACT
    val formatter = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    val resultColor = when (log.result) {
        ContextOperationResult.SUCCESS -> MaterialTheme.colorScheme.primary
        ContextOperationResult.FAILED -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val modeLabel = stringResource(
        if (log.isAuto) R.string.chat_context_op_auto else R.string.chat_context_op_manual,
    )
    val resultLabel = stringResource(
        when (log.result) {
            ContextOperationResult.SUCCESS -> R.string.chat_context_op_result_success
            ContextOperationResult.FAILED -> R.string.chat_context_op_result_failed
            else -> R.string.chat_context_op_result_cancelled
        },
    )
    val messageLabel = if (log.messageCount > 0) {
        stringResource(R.string.chat_page_message_count, log.messageCount)
    } else null
    val tokenLabel = if (log.sourceTokenEstimate > 0) {
        stringResource(R.string.chat_context_op_tokens, log.sourceTokenEstimate)
    } else null
    val meta = listOfNotNull(modeLabel, resultLabel, messageLabel, tokenLabel).joinToString(" · ")
    val title = log.conversationTitle?.takeIf { it.isNotBlank() }
    val details = log.details?.takeIf { it.isNotBlank() }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = if (isCompact) HugeIcons.Package01 else HugeIcons.Delete01,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(
                        if (isCompact) R.string.chat_context_op_compact
                        else R.string.chat_context_op_clear,
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = formatter.format(Date(log.createdAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (title != null) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = resultColor,
                )
                if (details != null) {
                    Text(
                        text = details,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
