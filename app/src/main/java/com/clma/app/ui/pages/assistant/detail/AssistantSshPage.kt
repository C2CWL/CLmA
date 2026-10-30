package com.clma.app.ui.pages.assistant.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clma.app.R
import com.clma.app.Screen
import com.clma.app.data.ai.tools.LocalToolOption
import com.clma.app.data.db.entity.SshHostEntity
import com.clma.app.data.repository.SshHostRepository
import com.clma.app.ui.components.nav.BackButton
import com.clma.app.ui.components.ui.CardGroup
import com.clma.app.ui.context.LocalNavController
import com.clma.app.ui.theme.CustomColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

@Composable
fun AssistantSshPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(parameters = { parametersOf(id) })
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val repository = koinInject<SshHostRepository>()
    val navController = LocalNavController.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    val hostsState = remember { mutableStateOf<List<SshHostEntity>>(emptyList()) }
    val hosts by hostsState
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val currentAssistant = rememberUpdatedState(assistant)
    val refreshHosts = remember(repository, vm) {
        suspend {
            val refreshedHosts = withContext(Dispatchers.IO) { repository.getAll() }
            hostsState.value = refreshedHosts
            val validNames = refreshedHosts.mapTo(mutableSetOf()) { it.name }
            if (currentAssistant.value.enabledSshHosts.any { it !in validNames }) {
                vm.updateAssistant { current ->
                    val selected = current.enabledSshHosts intersect validNames
                    current.copy(
                        enabledSshHosts = selected,
                        localTools = if (selected.isEmpty()) current.localTools - LocalToolOption.Ssh
                            else current.localTools,
                    )
                }
            }
        }
    }
    LaunchedEffect(refreshHosts) { refreshHosts() }
    DisposableEffect(lifecycleOwner, refreshHosts) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) coroutineScope.launch { refreshHosts() }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun updateEnabledHosts(transform: (Set<String>) -> Set<String>) {
        vm.updateAssistant { current ->
            val selected = transform(current.enabledSshHosts)
            current.copy(
                enabledSshHosts = selected,
                localTools = if (selected.isEmpty()) current.localTools - LocalToolOption.Ssh
                    else (current.localTools + LocalToolOption.Ssh).distinct(),
            )
        }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.assistant_ssh_page_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                CardGroup(title = { Text(stringResource(R.string.assistant_ssh_page_access)) }) {
                    if (hosts.isEmpty()) {
                        item(headlineContent = { Text(stringResource(R.string.assistant_ssh_page_empty)) })
                    }
                    hosts.forEach { host ->
                        val checked = host.name in assistant.enabledSshHosts
                        item(
                            onClick = {
                                updateEnabledHosts { selected ->
                                    if (host.name in selected) selected - host.name else selected + host.name
                                }
                            },
                            headlineContent = { Text(host.name) },
                            supportingContent = { Text("${host.user}@${host.host}:${host.port}") },
                            trailingContent = {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = { enabled ->
                                        updateEnabledHosts { selected ->
                                            if (enabled) selected + host.name else selected - host.name
                                        }
                                    },
                                )
                            },
                        )
                    }
                }
            }
            item {
                TextButton(onClick = { navController.navigate(Screen.SettingSsh) }) {
                    Text(stringResource(R.string.assistant_ssh_page_manage))
                }
            }
        }
    }
}
