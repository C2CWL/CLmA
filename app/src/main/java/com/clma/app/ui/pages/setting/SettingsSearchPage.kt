package com.clma.app.ui.pages.setting

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clma.app.R
import com.clma.app.data.datastore.Settings
import com.clma.app.data.db.entity.WorkspaceEntity
import com.clma.app.data.files.SkillManager
import com.clma.app.data.files.SkillMetadata
import com.clma.app.data.repository.WorkspaceRepository
import com.clma.app.ui.components.nav.BackButton
import com.clma.app.ui.components.ui.CardGroup
import com.clma.app.ui.context.LocalNavController
import com.clma.app.ui.theme.CustomColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import java.util.Locale

/** Rendering a few hundred rows inside one CardGroup is fine; thousands is not. */
private const val MAX_RESULTS = 200

@Composable
fun SettingsSearchPage(vm: SettingVM = koinViewModel()) {
    val navController = LocalNavController.current
    val context = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val focusRequester = remember { FocusRequester() }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    // User-created content that does not live in Settings.
    val workspaceRepository: WorkspaceRepository = koinInject()
    val skillManager: SkillManager = koinInject()
    val workspaceFlow = remember(workspaceRepository) { workspaceRepository.listFlow() }
    val workspaces by workspaceFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val skills by produceState(initialValue = emptyList<SkillMetadata>(), skillManager) {
        value = withContext(Dispatchers.IO) {
            runCatching { skillManager.listSkills() }.getOrDefault(emptyList())
        }
    }

    // Current-language strings plus English, both memoised: ranking reads every entry's strings
    // on each keystroke.
    val text = remember(context) { cachedStrings(context) }
    val englishText = remember(context) { cachedStrings(englishContext(context)) }

    val entries = remember(settings.developerMode) {
        settingsSearchIndex(developerMode = settings.developerMode)
    }
    val dynamicEntries = remember(entries, settings, workspaces, skills) {
        settingsSearchDynamicEntries(userContentOf(settings, workspaces, skills), entries)
    }
    val filtered: List<SettingsSearchItem> = remember(entries, dynamicEntries, query) {
        val q = query.trim()
        if (q.isEmpty()) {
            // Empty query: hub pages only — nested rows would drown the list.
            entries.filter { it.parentTitleRes == null }
        } else {
            rankSettingsSearch(q, entries + dynamicEntries, text, englishText).take(MAX_RESULTS)
        }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                navigationIcon = { BackButton() },
                title = { Text(stringResource(R.string.settings_search_page_title)) },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .focusRequester(focusRequester),
                placeholder = { Text(stringResource(R.string.settings_search_placeholder)) },
                shape = RoundedCornerShape(50),
                singleLine = true,
            )

            Box(modifier = Modifier.weight(1f)) {
                if (filtered.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.settings_search_no_results),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(8.dp),
                    ) {
                        item {
                            CardGroup {
                                // CardGroupScope.item is a plain builder — stringResource must
                                // live inside the @Composable content lambdas, not the forEach body.
                                filtered.forEach { result ->
                                    val descriptionRes = (result as? SettingsSearchEntry)?.descriptionRes
                                    val subtitle = (result as? SettingsSearchDynamicEntry)?.subtitle
                                    item(
                                        onClick = { navController.navigate(result.route) },
                                        overlineContent = {
                                            val group = stringResource(result.groupRes)
                                            val crumbs = result.breadcrumbRes.map { stringResource(it) }
                                            Text(
                                                text = (listOf(group) + crumbs).joinToString(" · "),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        },
                                        headlineContent = {
                                            Text(
                                                when (result) {
                                                    is SettingsSearchEntry -> stringResource(result.titleRes)
                                                    is SettingsSearchDynamicEntry -> result.title
                                                }
                                            )
                                        },
                                        supportingContent = if (descriptionRes != null) {
                                            { Text(stringResource(descriptionRes)) }
                                        } else if (subtitle != null) {
                                            {
                                                Text(
                                                    text = subtitle,
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                        } else {
                                            null
                                        },
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

private fun cachedStrings(context: Context): (Int) -> String {
    val cache = HashMap<Int, String>()
    return { id -> cache.getOrPut(id) { context.getString(id) } }
}

private fun englishContext(context: Context): Context {
    val config = Configuration(context.resources.configuration)
    config.setLocale(Locale.ENGLISH)
    return context.createConfigurationContext(config)
}

private fun userContentOf(
    settings: Settings,
    workspaces: List<WorkspaceEntity>,
    skills: List<SkillMetadata>,
) = SettingsSearchUserContent(
    workspaces = workspaces.map { SettingsSearchUserItem(it.id, it.name, it.root) },
    skills = skills.map { SettingsSearchUserItem(it.name, it.name, it.description) },
    quickMessages = settings.quickMessages.map { SettingsSearchUserItem(it.id.toString(), it.title, it.content) },
    modeInjections = settings.modeInjections.map { SettingsSearchUserItem(it.id.toString(), it.name) },
    lorebooks = settings.lorebooks.map { SettingsSearchUserItem(it.id.toString(), it.name, it.description) },
    mcpServers = settings.mcpServers.map { SettingsSearchUserItem(it.id.toString(), it.commonOptions.name) },
    providers = settings.providers.map { SettingsSearchUserItem(it.id.toString(), it.name) },
    searchServices = settings.searchServices.map { SettingsSearchUserItem(it.id.toString(), it.displayName) },
    assistants = settings.assistants.map { SettingsSearchUserItem(it.id.toString(), it.name) },
    ttsProviders = settings.ttsProviders.map { SettingsSearchUserItem(it.id.toString(), it.name) },
    asrProviders = settings.asrProviders.map { SettingsSearchUserItem(it.id.toString(), it.name) },
)
