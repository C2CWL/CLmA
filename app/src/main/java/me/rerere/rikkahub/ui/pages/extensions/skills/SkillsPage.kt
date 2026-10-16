package me.rerere.rikkahub.ui.pages.extensions.skills

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.text.font.FontWeight
import com.composables.icons.lucide.Globe
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import com.dokar.sonner.ToastType
import me.rerere.rikkahub.R
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Download01
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.Puzzle
import me.rerere.rikkahub.data.datastore.SkillGroup
import me.rerere.rikkahub.data.datastore.SkillGroupRules
import me.rerere.rikkahub.data.files.SkillFrontmatterParser
import me.rerere.rikkahub.data.files.SkillMetadata
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.skills.CatalogEntry
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.RikkaConfirmDialog
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

@Composable
fun SkillsPage() {
    val navController = LocalNavController.current
    val vm = koinViewModel<SkillsVM>()
    val skills by vm.skills.collectAsStateWithLifecycle()
    val groups by vm.skillGroups.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val toaster = LocalToaster.current
    val context = LocalContext.current
    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    var showImportDialog by rememberSaveable { mutableStateOf(false) }
    var showCatalog by rememberSaveable { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<SkillMetadata?>(null) }
    // Group management UI state. `groupEditor` is null when closed, New when creating, or
    // Edit(group) when renaming / re-picking members.
    var groupEditor by remember { mutableStateOf<GroupEditorTarget?>(null) }
    var deleteGroupTarget by remember { mutableStateOf<SkillGroup?>(null) }
    var deleteGroupSkillsToo by remember { mutableStateOf(false) }

    val byName = remember(skills) { skills.associateBy { it.name } }
    // Core (auto_load) skills get their own locked section: they are the system persona and
    // cannot be grouped or disabled (see SkillGroupRules.canGroupSkill).
    val coreSkills = remember(skills) { skills.filter { it.autoLoad } }
    val groupableSkills = remember(skills) { skills.filter { SkillGroupRules.canGroupSkill(it.name) } }
    val ungroupedSkills = remember(skills, groups) {
        val grouped = SkillGroupRules.groupedSkillNames(groups)
        skills.filter { !it.autoLoad && it.name !in grouped }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.skills_page_title)) },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = { showCatalog = true }) {
                        Icon(
                            imageVector = Lucide.Globe,
                            contentDescription = stringResource(R.string.skill_catalog_title),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        floatingActionButton = {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SmallFloatingActionButton(onClick = { showImportDialog = true }) {
                    Icon(
                        HugeIcons.Download01,
                        contentDescription = stringResource(R.string.skills_page_import_from_github)
                    )
                }
                FloatingActionButton(onClick = { showAddDialog = true }) {
                    Icon(HugeIcons.Add01, contentDescription = stringResource(R.string.accessibility_add_skill))
                }
            }
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 16.dp,
                bottom = 16.dp + 72.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (skills.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            imageVector = HugeIcons.Puzzle,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = stringResource(R.string.skills_page_empty_title),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = stringResource(R.string.skills_page_empty_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Entry point for group creation. Empty-group-only case is fine to show always:
            // an empty list still lets the user land here before installing anything.
            item(key = "new-group") {
                OutlinedButton(
                    onClick = { groupEditor = GroupEditorTarget.New },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = HugeIcons.Add01,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = stringResource(R.string.skills_page_new_group),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }

            // Core (auto_load) skills — locked section, no group controls.
            if (coreSkills.isNotEmpty()) {
                item(key = "core-header") {
                    SectionHeader(
                        title = stringResource(R.string.skills_page_group_core),
                        subtitle = stringResource(R.string.skills_page_group_core_hint),
                    )
                }
                items(coreSkills, key = { "core-${it.name}" }) { skill ->
                    SkillCard(
                        skill = skill,
                        onClick = { navController.navigate(Screen.SkillDetail(skill.name)) },
                        onDelete = { deleteTarget = skill },
                    )
                }
            }

            // User-created groups. A disabled group dims its members and suppresses them from
            // the AI surface (see SkillGroupRules.disabledSkillNames).
            groups.forEach { group ->
                val members = group.skillNames.mapNotNull { byName[it] }
                item(key = "grp-${group.id}") {
                    GroupHeader(
                        group = group,
                        onToggle = { vm.setGroupEnabled(group.id, it) },
                        onEdit = { groupEditor = GroupEditorTarget.Edit(group) },
                        onDelete = { withSkills ->
                            deleteGroupSkillsToo = withSkills
                            deleteGroupTarget = group
                        },
                    )
                }
                if (members.isEmpty()) {
                    item(key = "grp-empty-${group.id}") {
                        Text(
                            text = stringResource(R.string.skills_page_group_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                        )
                    }
                } else {
                    items(members, key = { "m-${group.id}-${it.name}" }) { skill ->
                        SkillCard(
                            skill = skill,
                            onClick = { navController.navigate(Screen.SkillDetail(skill.name)) },
                            onDelete = { deleteTarget = skill },
                            dimmed = !group.enabled,
                        )
                    }
                }
            }

            if (ungroupedSkills.isNotEmpty()) {
                item(key = "ungrouped-header") {
                    SectionHeader(
                        title = stringResource(R.string.skills_page_group_ungrouped),
                        subtitle = null,
                    )
                }
                items(ungroupedSkills, key = { "u-${it.name}" }) { skill ->
                    SkillCard(
                        skill = skill,
                        onClick = { navController.navigate(Screen.SkillDetail(skill.name)) },
                        onDelete = { deleteTarget = skill },
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        AddSkillDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { name, content ->
                vm.saveSkill(name, content) { success ->
                    showAddDialog = false
                    if (!success) {
                        toaster.show(context.getString(R.string.skills_page_save_failed))
                    }
                }
            },
        )
    }

    // Phase 19C — local-file picker. Lifted to screen scope so the launcher survives
    // dialog dismiss/recreate (per Compose docs: `rememberLauncherForActivityResult`
    // belongs at the highest stable composable, NOT inside a dialog body).
    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            vm.importFromLocalFile(uri) { success, message ->
                showImportDialog = false
                if (success) {
                    toaster.show(context.getString(R.string.skills_page_import_success, message))
                } else {
                    val key = mapImportErrorKeyToString(context, message)
                    toaster.show(context.getString(R.string.skills_page_import_failed, key))
                }
            }
        }
    }

    // Batch import — pick several `.md` / `.zip` files at once from the system file manager.
    val openMultipleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            vm.importFromLocalFiles(uris) { result ->
                showImportDialog = false
                if (result.failures.isEmpty()) {
                    toaster.show(
                        context.getString(R.string.skills_page_import_batch_success, result.imported),
                        type = ToastType.Success,
                    )
                } else {
                    val reason = mapImportErrorKeyToString(context, result.failures.first().message)
                    toaster.show(
                        context.getString(
                            R.string.skills_page_import_batch_partial,
                            result.imported,
                            result.failures.size,
                            reason,
                        ),
                        type = ToastType.Error,
                    )
                }
            }
        }
    }

    if (showImportDialog) {
        ImportSkillDialog(
            onDismiss = { showImportDialog = false },
            onConfirm = { repoUrl ->
                vm.importSkillFromGitHub(repoUrl) { success, message ->
                    showImportDialog = false
                    if (success) {
                        toaster.show(context.getString(R.string.skills_page_import_success, message))
                    } else {
                        toaster.show(context.getString(R.string.skills_page_import_failed, message))
                    }
                }
            },
            onPickFile = {
                openDocumentLauncher.launch(arrayOf(
                    "text/markdown",
                    "text/plain",
                    "application/zip",
                    "application/octet-stream",
                ))
            },
            onPickMultipleFiles = {
                openMultipleLauncher.launch(arrayOf(
                    "text/markdown",
                    "text/plain",
                    "application/zip",
                    "application/octet-stream",
                ))
            },
        )
    }

    RikkaConfirmDialog(
        show = deleteTarget != null,
        title = stringResource(R.string.skills_page_delete_title),
        confirmText = stringResource(R.string.delete),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            deleteTarget?.let { vm.deleteSkill(it.name) }
            deleteTarget = null
        },
        onDismiss = { deleteTarget = null },
    ) {
        Text(stringResource(R.string.skills_page_delete_message, deleteTarget?.name ?: ""))
    }

    // Group deletion — one dialog, two modes: keep skills (they fall back to Ungrouped) or
    // uninstall them along with the group.
    RikkaConfirmDialog(
        show = deleteGroupTarget != null,
        title = stringResource(R.string.skills_page_group_delete),
        confirmText = stringResource(R.string.delete),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            val target = deleteGroupTarget
            if (target != null) {
                if (deleteGroupSkillsToo) vm.deleteGroupWithSkills(target.id) else vm.deleteGroup(target.id)
            }
            deleteGroupTarget = null
            deleteGroupSkillsToo = false
        },
        onDismiss = {
            deleteGroupTarget = null
            deleteGroupSkillsToo = false
        },
    ) {
        val target = deleteGroupTarget
        val message = if (deleteGroupSkillsToo) {
            stringResource(
                R.string.skills_page_group_delete_with_skills_message,
                target?.name ?: "",
                target?.skillNames?.size ?: 0,
            )
        } else {
            stringResource(R.string.skills_page_group_delete_message, target?.name ?: "")
        }
        Text(message)
    }

    groupEditor?.let { target ->
        val editing = target as? GroupEditorTarget.Edit
        SkillGroupEditorDialog(
            initialName = editing?.group?.name ?: "",
            initialSelected = editing?.group?.skillNames ?: emptySet(),
            allSkills = groupableSkills,
            onDismiss = { groupEditor = null },
            onConfirm = { name, selected ->
                when (target) {
                    is GroupEditorTarget.New -> {
                        vm.createGroup(name, selected)
                        toaster.show(context.getString(R.string.skills_page_group_created))
                    }

                    is GroupEditorTarget.Edit -> {
                        vm.renameGroup(target.group.id, name)
                        vm.setGroupMembers(target.group.id, selected)
                    }
                }
                groupEditor = null
            },
        )
    }

    if (showCatalog) {
        val installedNames by vm.installedSkillNames.collectAsStateWithLifecycle()
        FeaturedCatalogSheet(
            entries = vm.catalog.skills,
            installedNames = installedNames,
            onInstall = { entry, onDone ->
                vm.installFromCatalog(entry) { success, message ->
                    if (success) {
                        toaster.show(context.getString(R.string.skills_page_import_success, message))
                    } else {
                        val key = mapImportErrorKeyToString(context, message)
                        toaster.show(context.getString(R.string.skill_catalog_install_failed, key))
                    }
                    onDone()
                }
            },
            onDismiss = { showCatalog = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FeaturedCatalogSheet(
    entries: List<CatalogEntry>,
    installedNames: Set<String>,
    onInstall: (CatalogEntry, onResult: () -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var installing by remember { mutableStateOf<Set<String>>(emptySet()) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.7f)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.skill_catalog_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(Lucide.X, contentDescription = stringResource(R.string.cancel))
                }
            }
            if (entries.isEmpty()) {
                Text(
                    text = stringResource(R.string.skills_page_empty_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    items(entries, key = { it.name }) { entry ->
                        CatalogRow(
                            entry = entry,
                            installed = entry.name in installedNames,
                            installing = entry.name in installing,
                            onInstall = {
                                installing = installing + entry.name
                                onInstall(entry) {
                                    installing = installing - entry.name
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CatalogRow(
    entry: CatalogEntry,
    installed: Boolean,
    installing: Boolean,
    onInstall: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "${entry.category} · ${entry.sizeKb} KB",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
            Text(
                text = entry.title,
                style = MaterialTheme.typography.titleSmallEmphasized,
            )
            Text(
                text = entry.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
            FilledTonalButton(
                onClick = onInstall,
                enabled = !installed && !installing,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            ) {
                Text(
                    when {
                        installed -> stringResource(R.string.skill_catalog_installed)
                        installing -> stringResource(R.string.skill_catalog_installing)
                        else -> stringResource(R.string.skill_catalog_install)
                    }
                )
            }
        }
    }
}

@Composable
private fun SkillCard(
    skill: SkillMetadata,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    /** Render faded when the owning group is disabled (its skills are suppressed). */
    dimmed: Boolean = false,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (dimmed) 0.45f else 1f),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = HugeIcons.Puzzle,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = skill.name,
                    style = MaterialTheme.typography.titleSmallEmphasized,
                )
                Text(
                    text = skill.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
                if (!skill.compatibility.isNullOrBlank() || !skill.license.isNullOrBlank()) {
                    Text(
                        text = listOfNotNull(
                            skill.compatibility?.takeIf { it.isNotBlank() },
                            skill.license?.takeIf { it.isNotBlank() },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        imageVector = HugeIcons.MoreVertical,
                        contentDescription = stringResource(R.string.skills_page_more_actions),
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                        leadingIcon = {
                            Icon(
                                imageVector = HugeIcons.Delete01,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun AddSkillDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, content: String) -> Unit,
) {
    var content by rememberSaveable { mutableStateOf("") }

    val name = remember(content) {
        SkillFrontmatterParser.parse(content)["name"]?.trim() ?: ""
    }
    val nameError = content.isNotBlank() && name.isBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.skills_page_add_title)) },
        text = {
            OutlinedTextField(
                value = content,
                onValueChange = { content = it },
                label = { Text(stringResource(R.string.skills_page_skill_content_label)) },
                placeholder = {
                    Text(
                        "---\nname: my-skill\ndescription: \"...\"\n---\n\nSkill body goes here...",
                        fontFamily = FontFamily.Monospace,
                    )
                },
                supportingText = {
                    if (nameError) Text(
                        stringResource(R.string.skills_page_name_error),
                        color = MaterialTheme.colorScheme.error
                    )
                    else if (name.isNotBlank()) Text(stringResource(R.string.skills_page_skill_name, name))
                    else Text(stringResource(R.string.skills_page_paste_hint))
                },
                isError = nameError,
                minLines = 8,
                maxLines = 14,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name, content) },
                enabled = name.isNotBlank() && !nameError,
            ) {
                Text(stringResource(R.string.skills_page_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun ImportSkillDialog(
    onDismiss: () -> Unit,
    onConfirm: (repoUrl: String) -> Unit,
    onPickFile: () -> Unit,
    onPickMultipleFiles: () -> Unit,
) {
    var url by rememberSaveable { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!loading) onDismiss() },
        title = { Text(stringResource(R.string.skills_page_import_from_github)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.skills_page_import_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.skills_page_repo_url_label)) },
                    placeholder = { Text("https://github.com/owner/repo", fontFamily = FontFamily.Monospace) },
                    supportingText = { Text(stringResource(R.string.skills_page_repo_url_hint)) },
                    singleLine = true,
                    enabled = !loading,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
                // Phase 19C — secondary action that defers to the screen-level file
                // launcher. The dialog passes through; SkillsPage handles the SAF
                // OpenDocument result and routes to the VM.
                OutlinedButton(
                    onClick = onPickFile,
                    enabled = !loading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.skill_import_from_file_label))
                }
                // Batch entry point: the picker allows selecting several bundles at once and
                // the VM reports back how many landed / failed.
                OutlinedButton(
                    onClick = onPickMultipleFiles,
                    enabled = !loading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.skills_page_import_batch))
                }
                if (loading) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(
                            stringResource(R.string.skills_page_downloading),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    loading = true
                    onConfirm(url)
                },
                enabled = url.isNotBlank() && !loading,
            ) {
                Text(stringResource(R.string.skills_page_import_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !loading) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/**
 * Map a VM-emitted error key (one of the `skill_import_*` resource names) to its
 * localized string. We use this rather than passing string-resource IDs out of the VM
 * because the VM has no `Context` callback path for stringResource lookups.
 */
private fun mapImportErrorKeyToString(context: android.content.Context, key: String): String {
    return when (key) {
        "skill_import_unsupported_file_type" ->
            context.getString(R.string.skill_import_unsupported_file_type)
        "skill_import_missing_skill_md" ->
            context.getString(R.string.skill_import_missing_skill_md)
        "skill_import_path_traversal" ->
            context.getString(R.string.skill_import_path_traversal)
        "skill_import_zip_too_large" ->
            context.getString(R.string.skill_import_zip_too_large)
        "skill_import_md_too_large" ->
            context.getString(R.string.skill_import_md_too_large)
        "skill_import_empty_file" ->
            context.getString(R.string.skill_import_empty_file)
        else -> key  // already a free-form message (e.g. importer's "html_response" detail)
    }
}

/** Small labelled divider above a section of skill cards. */
@Composable
private fun SectionHeader(title: String, subtitle: String?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmallEmphasized,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A group's header row: name, member count, on/off switch, and an overflow menu with
 * edit / delete-group / delete-group-and-skills. The switch is the whole-group toggle —
 * flipping it off suppresses every member from the AI surface without touching any
 * assistant's `enabledSkills`.
 */
@Composable
private fun GroupHeader(
    group: SkillGroup,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: (withSkills: Boolean) -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(group.name, style = MaterialTheme.typography.titleSmallEmphasized)
            Text(
                text = stringResource(R.string.skills_page_group_members, group.skillNames.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = group.enabled, onCheckedChange = onToggle)
        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(
                    imageVector = HugeIcons.MoreVertical,
                    contentDescription = stringResource(R.string.skills_page_more_actions),
                )
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.skills_page_group_edit)) },
                    leadingIcon = {
                        Icon(HugeIcons.Puzzle, contentDescription = null)
                    },
                    onClick = {
                        menuExpanded = false
                        onEdit()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.skills_page_group_delete)) },
                    leadingIcon = {
                        Icon(HugeIcons.Delete01, contentDescription = null)
                    },
                    onClick = {
                        menuExpanded = false
                        onDelete(false)
                    },
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(R.string.skills_page_group_delete_with_skills),
                            color = MaterialTheme.colorScheme.error,
                        )
                    },
                    leadingIcon = {
                        Icon(
                            HugeIcons.Delete01,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        onDelete(true)
                    },
                )
            }
        }
    }
}

/**
 * Create / edit dialog for a skill group: a name field plus a checkbox list of the skills
 * that may be grouped. Core (auto_load) skills are never passed in — they cannot be grouped.
 */
@Composable
private fun SkillGroupEditorDialog(
    initialName: String,
    initialSelected: Set<String>,
    allSkills: List<SkillMetadata>,
    onDismiss: () -> Unit,
    onConfirm: (name: String, selected: Set<String>) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    var selected by remember { mutableStateOf(initialSelected) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (initialName.isBlank()) R.string.skills_page_new_group
                    else R.string.skills_page_group_edit
                )
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.skills_page_group_name)) },
                    placeholder = { Text(stringResource(R.string.skills_page_group_name_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(R.string.skills_page_group_select_skills),
                    style = MaterialTheme.typography.labelMedium,
                )
                if (allSkills.isEmpty()) {
                    Text(
                        text = stringResource(R.string.skills_page_group_no_skills),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(allSkills, key = { it.name }) { skill ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selected = if (skill.name in selected) {
                                            selected - skill.name
                                        } else {
                                            selected + skill.name
                                        }
                                    }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = skill.name in selected,
                                    onCheckedChange = { checked ->
                                        selected = if (checked) selected + skill.name else selected - skill.name
                                    },
                                )
                                Column(modifier = Modifier.padding(start = 4.dp)) {
                                    Text(skill.name, style = MaterialTheme.typography.bodyMedium)
                                    if (skill.description.isNotBlank()) {
                                        Text(
                                            text = skill.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name, selected) },
                enabled = name.isNotBlank(),
            ) {
                Text(stringResource(R.string.skills_page_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/** Which group the editor dialog is open for: a fresh one, or an existing group being edited. */
private sealed interface GroupEditorTarget {
    data object New : GroupEditorTarget
    data class Edit(val group: SkillGroup) : GroupEditorTarget
}
