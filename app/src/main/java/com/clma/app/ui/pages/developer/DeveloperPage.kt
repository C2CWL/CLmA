package com.clma.app.ui.pages.developer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clma.app.R
import com.clma.app.data.ai.AILogging
import com.clma.app.data.ai.ContextSectionKind
import com.clma.app.data.datastore.AiLogLevel
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.FileScript
import org.koin.androidx.compose.koinViewModel

@Composable
fun DeveloperPage(vm: DeveloperVM = koinViewModel()) {
    val pager = rememberPagerState { 1 }
    val scope = rememberCoroutineScope()
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Developer Page",
                        maxLines = 1,
                    )
                }
            )
        },
        bottomBar = {
            BottomAppBar {
                NavigationBarItem(
                    selected = pager.currentPage == 0,
                    onClick = { scope.launch { pager.animateScrollToPage(0) } },
                    label = {
                        Text(text = "Developer")
                    },
                    icon = {
                        Icon(HugeIcons.FileScript, null)
                    }
                )
            }
        }
    ) { innerPadding ->
        HorizontalPager(
            state = pager,
            contentPadding = innerPadding
        ) { page ->
            when (page) {
                0 -> {
                    LoggingPaging(vm = vm)
                }
            }
        }
    }
}

@Composable
fun LoggingPaging(vm: DeveloperVM) {
    val logs by vm.logs.collectAsStateWithLifecycle()
    val level by vm.logLevel.collectAsStateWithLifecycle()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(1) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(text = "AI Log Level")
                    AiLogLevel.entries.forEach { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = level == item,
                                onClick = { vm.setLogLevel(item) },
                            )
                            Text(text = item.name.lowercase().replaceFirstChar { it.uppercase() })
                        }
                    }
                }
            }
        }
        item {
            ContextInspector(logs.filterIsInstance<AILogging.Generation>().lastOrNull(), level)
        }
    }
}

@Composable
private fun ContextInspector(log: AILogging.Generation?, level: AiLogLevel) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.context_inspector_title))
            Text(stringResource(R.string.context_inspector_scope))
            Text(stringResource(R.string.context_inspector_privacy))
            if (level != AiLogLevel.DEBUG) Text(stringResource(R.string.context_inspector_debug))
            val inspection = log?.context
            if (inspection == null) {
                Text(stringResource(R.string.context_inspector_empty))
            } else {
                Text("${log?.modelId.orEmpty()} · ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(inspection.capturedAt))}")
                Text(stringResource(R.string.context_inspector_size, inspection.utf8Bytes))
                Text(stringResource(R.string.context_inspector_unknown_tokens))
                inspection.sections.forEach { section ->
                    val title = when (section.kind) {
                        ContextSectionKind.SYSTEM -> R.string.context_inspector_system
                        ContextSectionKind.MEMORY_SOURCE -> R.string.context_inspector_memory
                        ContextSectionKind.SYNTHETIC -> R.string.context_inspector_synthetic
                        ContextSectionKind.HISTORY -> R.string.context_inspector_history
                        ContextSectionKind.TOOLS -> R.string.context_inspector_tools
                    }
                    Text(stringResource(title))
                    Text(stringResource(R.string.context_inspector_section_size, section.chars, section.utf8Bytes))
                    section.preview?.let { preview ->
                        var expanded by remember(inspection.capturedAt, section.kind) { mutableStateOf(false) }
                        TextButton(onClick = { expanded = !expanded }) {
                            Text(stringResource(if (expanded) R.string.context_inspector_hide else R.string.context_inspector_show))
                        }
                        if (expanded) SelectionContainer { Text(preview) }
                        if (section.truncated) Text(stringResource(R.string.context_inspector_truncated))
                    }
                }
            }
        }
    }
}
