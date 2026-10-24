package com.clma.app.ui.pages.setting

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Image02
import me.rerere.hugeicons.stroke.Clean
import me.rerere.hugeicons.stroke.Delete01
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import com.clma.app.data.datastore.SettingsStore
import com.clma.app.data.db.entity.GenMediaEntity
import com.clma.app.data.db.entity.ManagedFileEntity
import com.clma.app.data.files.AssistantMediaRefs
import com.clma.app.R
import com.clma.app.data.files.FileFolders
import com.clma.app.data.files.FilesManager
import com.clma.app.data.files.GalleryCleaner
import com.clma.app.ui.components.nav.BackButton
import com.clma.app.ui.context.LocalToaster
import com.clma.app.ui.theme.CustomColors
import com.clma.app.utils.fileSizeToString
import org.koin.compose.koinInject
import java.io.File
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingFilesPage(
    filesManager: FilesManager = koinInject(),
    galleryCleaner: GalleryCleaner = koinInject(),
    settingsStore: SettingsStore = koinInject(),
) {
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val gridState = rememberLazyStaggeredGridState()
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current

    // 预先获取字符串资源
    val deletedToast = stringResource(R.string.setting_files_page_deleted_toast)
    val deleteFailedToast = stringResource(R.string.setting_files_page_delete_failed_toast)
    val cleanedToast = stringResource(R.string.setting_files_page_cleaned_toast)
    val cleanFailedToast = stringResource(R.string.setting_files_page_clean_failed_toast)

    var section by remember { mutableStateOf(FilesSection.UPLOAD) }
    var pendingDelete by remember { mutableStateOf<PendingDelete?>(null) }
    var showCleanSheet by remember { mutableStateOf(false) }
    var selectedCleanRange by remember { mutableStateOf(CleanRange.DAYS_7) }

    // 助手头像 / 聊天壁纸同样存放在 upload 目录里，必须先认出来再分组，清理时排除
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val protectedFiles = remember(settings.assistants, settings.displaySetting.userAvatar) {
        AssistantMediaRefs.protectedFiles(
            assistants = settings.assistants,
            filesDir = context.filesDir,
            userAvatar = settings.displaySetting.userAvatar,
        )
    }

    val uploadFiles by filesManager.observe(FileFolders.UPLOAD)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val protectedUploads = remember(uploadFiles, protectedFiles) {
        uploadFiles.filter { it.relativePath in protectedFiles }
    }
    val plainUploads = remember(uploadFiles, protectedFiles) {
        uploadFiles.filterNot { it.relativePath in protectedFiles }
    }

    var generatedImages by remember { mutableStateOf<List<GenMediaEntity>>(emptyList()) }
    LaunchedEffect(section) {
        if (section == FilesSection.GENERATED_IMAGES) {
            generatedImages = galleryCleaner.listImages()
        }
    }

    val hasContent = when (section) {
        FilesSection.UPLOAD -> uploadFiles.isNotEmpty()
        FilesSection.GENERATED_IMAGES -> generatedImages.isNotEmpty()
    }

    pendingDelete?.let { target ->
        val title = when (target) {
            is PendingDelete.Managed -> stringResource(R.string.setting_files_page_delete_file_title)
            is PendingDelete.Generated -> stringResource(R.string.setting_files_page_delete_image_title)
        }
        val label = when (target) {
            is PendingDelete.Managed -> target.file.displayName
            is PendingDelete.Generated -> File(target.media.path).name
        }
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(title) },
            text = { Text(label) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        scope.launch {
                            val ok = when (target) {
                                is PendingDelete.Managed ->
                                    filesManager.delete(target.file.id, deleteFromDisk = true)
                                is PendingDelete.Generated -> {
                                    val result = galleryCleaner.delete(target.media)
                                    generatedImages = galleryCleaner.listImages()
                                    result
                                }
                            }
                            toaster.show(if (ok) deletedToast else deleteFailedToast)
                        }
                    }
                ) {
                    Text(stringResource(R.string.setting_files_page_delete_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.setting_files_page_cancel_action))
                }
            }
        )
    }

    if (showCleanSheet) {
        val sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        )
        ModalBottomSheet(
            onDismissRequest = { showCleanSheet = false },
            sheetState = sheetState,
        ) {
            CleanFilesSheet(
                selectedRange = selectedCleanRange,
                keptCount = if (section == FilesSection.UPLOAD) protectedUploads.size else 0,
                onRangeSelected = { selectedCleanRange = it },
                onClean = {
                    showCleanSheet = false
                    val cutoff = selectedCleanRange.days?.let { days ->
                        System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days.toLong())
                    }
                    scope.launch {
                        val ok = when (section) {
                            FilesSection.UPLOAD -> {
                                if (cutoff != null) {
                                    filesManager.deleteOlderThan(
                                        folder = FileFolders.UPLOAD,
                                        cutoffMillis = cutoff,
                                        excludeRelativePaths = protectedFiles.keys,
                                    )
                                } else {
                                    filesManager.deleteAll(
                                        folder = FileFolders.UPLOAD,
                                        excludeRelativePaths = protectedFiles.keys,
                                    )
                                }
                            }

                            FilesSection.GENERATED_IMAGES -> {
                                if (cutoff != null) {
                                    galleryCleaner.deleteOlderThan(cutoff)
                                } else {
                                    galleryCleaner.deleteAll()
                                }
                            }
                        }
                        if (section == FilesSection.GENERATED_IMAGES) {
                            generatedImages = galleryCleaner.listImages()
                        }
                        toaster.show(if (ok) cleanedToast else cleanFailedToast)
                    }
                },
            )
        }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_files_page_title)) },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(
                        onClick = { showCleanSheet = true },
                        enabled = hasContent,
                    ) {
                        Icon(
                            imageVector = HugeIcons.Clean,
                            contentDescription = stringResource(R.string.setting_files_page_clean_content_description),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor
    ) { innerPadding ->
        val layoutDirection = LocalLayoutDirection.current
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = innerPadding.calculateTopPadding(),
                    start = innerPadding.calculateStartPadding(layoutDirection),
                    end = innerPadding.calculateEndPadding(layoutDirection),
                )
        ) {
            SectionRow(
                selected = section,
                onSelect = { section = it },
            )

            if (!hasContent) {
                Box(
                    modifier = Modifier
                        .fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(stringResource(R.string.setting_files_page_no_files))
                }
            } else {
                LazyVerticalStaggeredGrid(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        top = 16.dp,
                        end = 16.dp,
                        bottom = innerPadding.calculateBottomPadding() + 16.dp,
                    ),
                    verticalItemSpacing = 8.dp,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    state = gridState,
                    columns = StaggeredGridCells.Fixed(2)
                ) {
                    when (section) {
                        FilesSection.UPLOAD -> {
                            if (protectedUploads.isNotEmpty()) {
                                item(
                                    span = StaggeredGridItemSpan.FullLine,
                                    key = "section-protected",
                                ) {
                                    SectionHeader(
                                        title = stringResource(R.string.setting_files_page_section_protected),
                                        subtitle = stringResource(R.string.setting_files_page_protected_hint),
                                    )
                                }
                                items(protectedUploads, key = { "protected-${it.id}" }) { file ->
                                    FileItem(
                                        file = file,
                                        fileOnDisk = filesManager.getFile(file),
                                        protectedRole = protectedFiles[file.relativePath],
                                        onDelete = null,
                                    )
                                }
                            }
                            if (plainUploads.isNotEmpty()) {
                                if (protectedUploads.isNotEmpty()) {
                                    item(
                                        span = StaggeredGridItemSpan.FullLine,
                                        key = "section-files",
                                    ) {
                                        SectionHeader(
                                            title = stringResource(R.string.setting_files_page_section_files),
                                            subtitle = null,
                                        )
                                    }
                                }
                                items(plainUploads, key = { "file-${it.id}" }) { file ->
                                    FileItem(
                                        file = file,
                                        fileOnDisk = filesManager.getFile(file),
                                        protectedRole = null,
                                        onDelete = { pendingDelete = PendingDelete.Managed(file) },
                                    )
                                }
                            }
                        }

                        FilesSection.GENERATED_IMAGES -> {
                            items(generatedImages, key = { "generated-${it.id}" }) { media ->
                                GeneratedImageItem(
                                    media = media,
                                    fileOnDisk = File(context.filesDir, media.path),
                                    onDelete = { pendingDelete = PendingDelete.Generated(media) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 文件页的两个分区：上传目录（附件 + 头像/壁纸）与生成图片（画廊）。 */
private enum class FilesSection { UPLOAD, GENERATED_IMAGES }

private sealed interface PendingDelete {
    data class Managed(val file: ManagedFileEntity) : PendingDelete
    data class Generated(val media: GenMediaEntity) : PendingDelete
}

private enum class CleanRange(val days: Int?) {
    DAYS_7(7),
    DAYS_14(14),
    DAYS_30(30),
    ALL(null),
}

@Composable
private fun CleanFilesSheet(
    selectedRange: CleanRange,
    keptCount: Int,
    onRangeSelected: (CleanRange) -> Unit,
    onClean: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp)
            .padding(bottom = 16.dp),
    ) {
        Text(
            text = stringResource(R.string.setting_files_page_clean_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = stringResource(R.string.setting_files_page_clean_range_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
        )
        if (keptCount > 0) {
            Text(
                text = stringResource(R.string.setting_files_page_clean_kept_note, keptCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }

        CleanRange.entries.forEach { range ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onRangeSelected(range) }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = selectedRange == range,
                    onClick = { onRangeSelected(range) },
                )
                Text(
                    text = range.days?.let {
                        stringResource(R.string.setting_files_page_clean_older_than_days, it)
                    } ?: stringResource(R.string.setting_files_page_clean_all),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }

        TextButton(
            onClick = onClean,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        ) {
            Text(stringResource(R.string.setting_files_page_clean_action))
        }
    }
}

@Composable
private fun SectionRow(
    selected: FilesSection,
    onSelect: (FilesSection) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilesSection.entries.forEach { item ->
            FilterChip(
                selected = selected == item,
                onClick = { onSelect(item) },
                label = { Text(sectionLabel(item)) }
            )
        }
    }
}

@Composable
private fun sectionLabel(section: FilesSection): String = when (section) {
    FilesSection.UPLOAD -> stringResource(R.string.setting_files_page_folder_upload)
    FilesSection.GENERATED_IMAGES -> stringResource(R.string.setting_files_page_folder_images)
}

@Composable
private fun SectionHeader(title: String, subtitle: String?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 8.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun FileItem(
    file: ManagedFileEntity,
    fileOnDisk: File,
    /** 非空表示这是助手头像/壁纸：不提供删除入口，清理也不会删。 */
    protectedRole: AssistantMediaRefs.Role?,
    onDelete: (() -> Unit)?,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CustomColors.listItemColors.containerColor)
    ) {
        Column {
            Box(
                modifier = Modifier.fillMaxWidth()
            ) {
                if (file.mimeType.startsWith("image/")) {
                    AsyncImage(
                        model = fileOnDisk,
                        contentDescription = file.displayName,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(4f / 3f),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(4f / 3f),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = HugeIcons.Image02,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (onDelete != null) {
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.align(Alignment.TopEnd)
                    ) {
                        Icon(
                            HugeIcons.Delete01,
                            contentDescription = stringResource(R.string.setting_files_page_delete_content_description)
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
            ) {
                Text(
                    text = file.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (protectedRole != null) {
                    Text(
                        text = protectedRoleLabel(protectedRole),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Text(
                    text = file.mimeType,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = file.sizeBytes.fileSizeToString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun protectedRoleLabel(role: AssistantMediaRefs.Role): String = when (role) {
    AssistantMediaRefs.Role.AVATAR -> stringResource(R.string.setting_files_page_protected_avatar)
    AssistantMediaRefs.Role.BACKGROUND -> stringResource(R.string.setting_files_page_protected_background)
}

@Composable
private fun GeneratedImageItem(
    media: GenMediaEntity,
    fileOnDisk: File,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CustomColors.listItemColors.containerColor)
    ) {
        Column {
            Box(
                modifier = Modifier.fillMaxWidth()
            ) {
                AsyncImage(
                    model = fileOnDisk,
                    contentDescription = File(media.path).name,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(4f / 3f),
                    contentScale = ContentScale.Crop
                )
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.align(Alignment.TopEnd)
                ) {
                    Icon(
                        HugeIcons.Delete01,
                        contentDescription = stringResource(R.string.setting_files_page_delete_content_description)
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
            ) {
                Text(
                    text = File(media.path).name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = media.modelId,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = fileOnDisk.length().fileSizeToString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
