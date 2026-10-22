package me.rerere.rikkahub.ui.pages.videogen

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import coil3.compose.AsyncImage
import com.dokar.sonner.ToastType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.ai.provider.ModelType
import me.rerere.ai.ui.ImageAspectRatio
import me.rerere.common.android.appTempFolder
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowUp02
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Image03
import me.rerere.hugeicons.stroke.Tools
import me.rerere.hugeicons.stroke.Video01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.files.FileUtils
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.ui.components.ai.ModelSelector
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.components.ui.OutlinedNumberInput
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.utils.ImageUtils
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.Uuid

/**
 * The standalone video-generation page — the "raw entry point" sibling of `ImageGenPage`.
 *
 * Two tabs behind a pager: generate (prompt + shape/duration + the inline player) and the gallery
 * of every clip the app has produced (this page's own output, plus anything the `generate_video`
 * tool wrote). Both write to the same `gen_media` rows and the same `videos/` directory.
 */
@Composable
fun VideoGenPage(
    modifier: Modifier = Modifier,
    vm: VideoGenVM = koinViewModel(),
) {
    val pagerState = rememberPagerState { 2 }
    val scope = rememberCoroutineScope()

    val isGenerating by vm.isGenerating.collectAsStateWithLifecycle()
    var showCancelDialog by remember { mutableStateOf(false) }
    BackHandler(isGenerating) {
        showCancelDialog = true
    }
    if (showCancelDialog) {
        CancelDialog(
            onDismiss = { showCancelDialog = false },
            onConfirm = {
                showCancelDialog = false
                vm.cancelGeneration()
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.videogen_page_title)) },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = vm::startNewSession) {
                        Icon(
                            imageVector = HugeIcons.Add01,
                            contentDescription = stringResource(R.string.accessibility_new_session),
                        )
                    }
                },
            )
        },
        bottomBar = { BottomBar(pagerState, scope) },
    ) { innerPadding ->
        HorizontalPager(
            state = pagerState,
            modifier = modifier
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
        ) { page ->
            when (page) {
                0 -> VideoGenScreen(vm = vm)
                1 -> VideoGalleryScreen(vm = vm)
            }
        }
    }
}

@Composable
private fun CancelDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.videogen_page_cancel_generation_title)) },
        text = { Text(stringResource(R.string.videogen_page_cancel_generation_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.videogen_page_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.videogen_page_cancel))
            }
        },
    )
}

@Composable
private fun BottomBar(
    pagerState: PagerState,
    scope: CoroutineScope,
) {
    NavigationBar {
        NavigationBarItem(
            selected = 0 == pagerState.currentPage,
            label = { Text(stringResource(R.string.videogen_page_tab_generate)) },
            icon = { Icon(HugeIcons.Video01, null) },
            onClick = { scope.launch { pagerState.animateScrollToPage(0) } },
        )
        NavigationBarItem(
            selected = 1 == pagerState.currentPage,
            label = { Text(stringResource(R.string.videogen_page_tab_gallery)) },
            icon = { Icon(HugeIcons.Image03, null) },
            onClick = { scope.launch { pagerState.animateScrollToPage(1) } },
        )
    }
}

@Composable
private fun VideoGenScreen(vm: VideoGenVM) {
    val prompt by vm.prompt.collectAsStateWithLifecycle()
    val isGenerating by vm.isGenerating.collectAsStateWithLifecycle()
    val currentVideos by vm.currentVideos.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val settings by vm.settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val context = LocalContext.current
    val filesManager: FilesManager = koinInject()
    var showSettingsSheet by remember { mutableStateOf(false) }

    LaunchedEffect(error) {
        error?.let { message ->
            toaster.show(message = message, type = ToastType.Error)
            vm.clearError()
        }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .imePadding(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.Center,
        ) {
            when {
                currentVideos.isNotEmpty() -> {
                    val video = currentVideos.last()
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        VideoPlayer(
                            filePath = video.filePath,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(16f / 9f)
                                .clip(RoundedCornerShape(12.dp)),
                        )
                        Text(
                            text = video.prompt,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { openVideoExternally(context, video.filePath) }) {
                                Text(stringResource(R.string.videogen_page_open))
                            }
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        if (!File(video.filePath).exists()) {
                                            toaster.show(
                                                  message = context.getString(R.string.videogen_page_video_missing),
                                                  type = ToastType.Error,
                                            )
                                            return@launch
                                        }
                                        try {
                                            filesManager.saveMessageVideo(context, "file://${video.filePath}")
                                            toaster.show(
                                                  message = context.getString(R.string.videogen_page_save_success),
                                                  type = ToastType.Success,
                                            )
                                        } catch (e: Exception) {
                                            if (e is CancellationException) throw e
                                            toaster.show(
                                                  message = context.getString(R.string.videogen_page_save_failed, e.message),
                                                  type = ToastType.Error,
                                            )
                                        }
                                    }
                                },
                            ) {
                                Text(stringResource(R.string.videogen_page_save))
                            }
                            TextButton(onClick = { vm.deleteVideo(video) }) {
                                Text(stringResource(R.string.videogen_page_delete))
                            }
                        }
                    }
                }

                isGenerating -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        ContainedLoadingIndicator()
                        Text(
                            text = stringResource(R.string.videogen_page_generating_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }

        InputBar(
            prompt = prompt,
            vm = vm,
            isGenerating = isGenerating,
            settings = settings,
            onShowSettings = { showSettingsSheet = true },
        )
    }

    if (showSettingsSheet) {
        VideoSettingsBottomSheet(
            vm = vm,
            settings = settings,
            scope = scope,
            onDismiss = { showSettingsSheet = false },
        )
    }
}

@Composable
private fun InputBar(
    prompt: String,
    vm: VideoGenVM,
    isGenerating: Boolean,
    settings: Settings,
    onShowSettings: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val referenceImage by vm.referenceImage.collectAsStateWithLifecycle()

    // Picking a first frame turns the call into image-to-video. The image is re-encoded to PNG in
    // the app temp dir (exactly like the image page) so the data URI handed to the vendor is honest
    // about its format.
    val firstFrameLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val path = withContext(Dispatchers.IO) {
                    runCatching {
                        val bitmap = ImageUtils.loadOptimizedBitmap(context, uri, maxSize = 2048)
                            ?: error("Failed to decode image")
                        val pngBytes = FileUtils.compressBitmapToPng(bitmap)
                        bitmap.recycle()
                        val file = File(context.appTempFolder, "videogen_ref_${Uuid.random()}.png")
                        file.writeBytes(pngBytes)
                        file.absolutePath
                    }.getOrNull()
                }
                if (path != null) vm.setReferenceImage(path)
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val frame = referenceImage
        if (frame != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                AsyncImage(
                    model = File(frame),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(8.dp)),
                )
                Text(
                    text = stringResource(R.string.videogen_page_first_frame),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { vm.setReferenceImage(null) }) {
                    Icon(
                        imageVector = HugeIcons.Cancel01,
                        contentDescription = stringResource(R.string.videogen_page_remove_first_frame),
                    )
                }
            }
        }

        OutlinedTextField(
            value = prompt,
            onValueChange = vm::updatePrompt,
            placeholder = { Text(stringResource(R.string.videogen_page_prompt_placeholder)) },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 140.dp),
            minLines = 1,
            maxLines = 5,
            shape = MaterialTheme.shapes.large,
            textStyle = MaterialTheme.typography.bodySmall,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ModelSelector(
                modelId = settings.videoGenerationModelId,
                providers = settings.providers,
                type = ModelType.VIDEO,
                onlyIcon = true,
                onSelect = { model ->
                    scope.launch {
                        vm.settingsStore.update { old -> old.copy(videoGenerationModelId = model.id) }
                    }
                },
            )

            IconButton(onClick = onShowSettings) {
                Icon(HugeIcons.Tools, null)
            }

            IconButton(onClick = { firstFrameLauncher.launch("image/*") }) {
                Icon(
                    imageVector = HugeIcons.Add01,
                    contentDescription = stringResource(R.string.accessibility_add_reference_image),
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            val canSend = prompt.isNotBlank()
            Surface(
                onClick = {
                    if (isGenerating) vm.cancelGeneration() else vm.generateVideo()
                },
                enabled = isGenerating || canSend,
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = when {
                    isGenerating -> MaterialTheme.colorScheme.errorContainer
                    !canSend -> MaterialTheme.colorScheme.surfaceContainerHigh
                    else -> MaterialTheme.colorScheme.primary
                },
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isGenerating) HugeIcons.Cancel01 else HugeIcons.ArrowUp02,
                        contentDescription = stringResource(R.string.videogen_page_generate),
                        tint = when {
                            isGenerating -> MaterialTheme.colorScheme.onErrorContainer
                            !canSend -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            else -> MaterialTheme.colorScheme.onPrimary
                        },
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VideoSettingsBottomSheet(
    vm: VideoGenVM,
    settings: Settings,
    scope: CoroutineScope,
    onDismiss: () -> Unit,
) {
    val aspectRatio by vm.aspectRatio.collectAsStateWithLifecycle()
    val durationSeconds by vm.durationSeconds.collectAsStateWithLifecycle()
    val numberOfVideos by vm.numberOfVideos.collectAsStateWithLifecycle()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.videogen_page_settings_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )

            FormItem(
                label = { Text(stringResource(R.string.videogen_page_model_selection)) },
                description = { Text(stringResource(R.string.videogen_page_model_selection_desc)) },
            ) {
                ModelSelector(
                    modelId = settings.videoGenerationModelId,
                    providers = settings.providers,
                    type = ModelType.VIDEO,
                    onlyIcon = false,
                    onSelect = { model ->
                        scope.launch {
                            vm.settingsStore.update { old -> old.copy(videoGenerationModelId = model.id) }
                        }
                    },
                )
            }

            FormItem(
                label = { Text(stringResource(R.string.videogen_page_duration)) },
                description = { Text(stringResource(R.string.videogen_page_duration_desc)) },
            ) {
                OutlinedNumberInput(
                    value = durationSeconds,
                    onValueChange = vm::updateDurationSeconds,
                    modifier = Modifier.width(120.dp),
                )
            }

            FormItem(
                label = { Text(stringResource(R.string.videogen_page_count)) },
                description = { Text(stringResource(R.string.videogen_page_count_desc)) },
            ) {
                OutlinedNumberInput(
                    value = numberOfVideos,
                    onValueChange = vm::updateNumberOfVideos,
                    modifier = Modifier.width(120.dp),
                )
            }

            AspectRatioChips(aspectRatio = aspectRatio, onSelect = vm::updateAspectRatio)

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AspectRatioChips(
    aspectRatio: ImageAspectRatio,
    onSelect: (ImageAspectRatio) -> Unit,
) {
    FormItem(
        label = { Text(stringResource(R.string.videogen_page_aspect_ratio)) },
        description = { Text(stringResource(R.string.videogen_page_aspect_ratio_desc)) },
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ImageAspectRatio.entries.forEach { ratio ->
                FilterChip(
                    selected = aspectRatio == ratio,
                    onClick = { onSelect(ratio) },
                    label = {
                        Text(
                            stringResource(
                                when (ratio) {
                                    ImageAspectRatio.LANDSCAPE -> R.string.videogen_page_aspect_ratio_landscape
                                    ImageAspectRatio.PORTRAIT -> R.string.videogen_page_aspect_ratio_portrait
                                    ImageAspectRatio.SQUARE -> R.string.videogen_page_aspect_ratio_square
                                }
                            )
                        )
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VideoGalleryScreen(vm: VideoGenVM) {
    val videos = vm.generatedVideos.collectAsLazyPagingItems()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val filesManager: FilesManager = koinInject()
    var selected by remember { mutableStateOf(setOf<GeneratedVideo>()) }
    var detail by remember { mutableStateOf<GeneratedVideo?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var isDeleting by remember { mutableStateOf(false) }

    if (showDeleteDialog && selected.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.videogen_page_delete_videos_title)) },
            text = { Text(stringResource(R.string.videogen_page_delete_videos_message, selected.size)) },
            confirmButton = {
                TextButton(
                    enabled = !isDeleting,
                    onClick = {
                        val targets = selected.toList()
                        scope.launch {
                            isDeleting = true
                            val failed = vm.deleteVideos(targets)
                            isDeleting = false
                            showDeleteDialog = false
                            selected = emptySet()
                            videos.refresh()
                            toaster.show(
                                message = if (failed.isEmpty()) {
                                    context.getString(R.string.videogen_page_delete_videos_success, targets.size)
                                } else {
                                    context.getString(
                                        R.string.videogen_page_delete_videos_failed,
                                        targets.size - failed.size,
                                        failed.size,
                                    )
                                },
                                type = if (failed.isEmpty()) ToastType.Success else ToastType.Error,
                            )
                        }
                    },
                ) {
                    Text(
                        if (isDeleting) stringResource(R.string.videogen_page_deleting)
                        else stringResource(R.string.videogen_page_delete)
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.videogen_page_cancel))
                }
            },
        )
    }

    detail?.let { video ->
        ModalBottomSheet(
            onDismissRequest = { detail = null },
            dragHandle = { BottomSheetDefaults.DragHandle() },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (File(video.filePath).exists()) {
                    VideoPlayer(
                        filePath = video.filePath,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(12.dp)),
                    )
                } else {
                    Text(
                        text = stringResource(R.string.videogen_page_video_missing),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(text = video.prompt, style = MaterialTheme.typography.bodyMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(onClick = { openVideoExternally(context, video.filePath) }) {
                        Text(stringResource(R.string.videogen_page_open))
                    }
                    TextButton(
                        onClick = {
                            scope.launch {
                                if (!File(video.filePath).exists()) {
                                    toaster.show(
                                          message = context.getString(R.string.videogen_page_video_missing),
                                          type = ToastType.Error,
                                    )
                                    return@launch
                                }
                                try {
                                    filesManager.saveMessageVideo(context, "file://${video.filePath}")
                                    toaster.show(
                                          message = context.getString(R.string.videogen_page_save_success),
                                          type = ToastType.Success,
                                    )
                                } catch (e: Exception) {
                                    if (e is CancellationException) throw e
                                    toaster.show(
                                          message = context.getString(R.string.videogen_page_save_failed, e.message),
                                          type = ToastType.Error,
                                    )
                                }
                            }
                        },
                    ) {
                        Text(stringResource(R.string.videogen_page_save))
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(
                        onClick = {
                            detail = null
                            vm.deleteVideo(video)
                            videos.refresh()
                        },
                    ) {
                        Text(stringResource(R.string.videogen_page_delete))
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (selected.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.videogen_page_selected_count, selected.size),
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = { selected = emptySet() }) {
                    Text(stringResource(R.string.videogen_page_cancel))
                }
                TextButton(onClick = { showDeleteDialog = true }) {
                    Text(stringResource(R.string.videogen_page_delete))
                }
            }
        }

        if (videos.itemCount == 0) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.videogen_page_no_generated_videos),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(
                    count = videos.itemCount,
                    key = videos.itemKey { it.id },
                ) { index ->
                    val video = videos[index]
                    if (video != null) {
                        VideoCard(
                            video = video,
                            isSelected = video in selected,
                            onClick = {
                                if (selected.isEmpty()) detail = video
                                else selected = if (video in selected) selected - video else selected + video
                            },
                            onLongClick = {
                                selected = if (video in selected) selected - video else selected + video
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * A gallery tile. Deliberately **no** [VideoPlayer] here: a two-column grid would otherwise build
 * one `VideoView` per cell, and the clip is only worth decoding once the user taps it (the detail
 * sheet plays it). The tile is a dark placeholder with a video glyph plus the prompt.
 */
@Composable
private fun VideoCard(
    video: GeneratedVideo,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            val exists = remember(video.filePath) { File(video.filePath).exists() }
            if (exists) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = HugeIcons.Video01,
                        contentDescription = stringResource(R.string.videogen_page_open),
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = video.prompt,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp),
                )
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.videogen_page_video_missing),
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                )
            }
        }
    }
}

/**
 * Minimal inline player. Deliberately a plain [VideoView], **not** media3/ExoPlayer: only the
 * `speech` module depends on media3 today, and this app is size-sensitive — the built-in view
 * costs nothing extra. A [MediaController] supplies scrub + play/pause.
 */
@Composable
private fun VideoPlayer(
    filePath: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            VideoView(ctx).apply {
                setBackgroundColor(android.graphics.Color.BLACK)
                setVideoPath(filePath)
                val controller = MediaController(ctx).apply { setAnchorView(this@apply) }
                setMediaController(controller)
                tag = filePath
            }
        },
        update = { view ->
            if (view.tag != filePath) {
                view.tag = filePath
                view.setVideoPath(filePath)
            }
        },
    )
}

/** Opens [filePath] in whatever external player the user has, granting read access. */
private fun openVideoExternally(context: Context, filePath: String) {
    val file = File(filePath)
    if (!file.exists()) return
    val uri: Uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file,
    )
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "video/*")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(Intent.createChooser(intent, null)) }
}
