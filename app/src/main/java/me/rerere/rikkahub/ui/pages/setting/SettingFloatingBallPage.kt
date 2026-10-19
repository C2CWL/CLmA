package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.BorderStroke
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.service.HaloBallLook
import me.rerere.rikkahub.service.HaloBallView
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.FloatingBallStyle
import me.rerere.rikkahub.data.datastore.FloatingBallTuckStyle
import me.rerere.rikkahub.service.FloatingBallIconFiles
import me.rerere.rikkahub.service.FloatingBallService
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.Select
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import java.io.File
import kotlin.math.roundToInt

/**
 * Settings for the always-available floating ball: master switch, the SYSTEM_ALERT_WINDOW grant
 * it depends on, how it looks (colour + icon) and a short explanation of the gestures.
 */
@Composable
fun SettingFloatingBallPage(vm: SettingVM = koinViewModel()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var showIdleDialog by remember { mutableStateOf(false) }
    var showIconDialog by remember { mutableStateOf(false) }
    var showColorDialog by remember { mutableStateOf(false) }
    // Draft so dragging the slider does not write DataStore on every frame; committed on release.
    var alphaDraft by remember(settings.floatingBallHiddenAlpha) {
        mutableStateOf(settings.floatingBallHiddenAlpha.toFloat())
    }
    // Same idea for the colour picker: edited locally, committed when the dialog is confirmed.
    var colorDraft by remember(showColorDialog) { mutableStateOf(settings.floatingBallColor) }

    val themePrimary = MaterialTheme.colorScheme.primary
    val ballColor = settings.floatingBallColor?.let { Color(it) } ?: themePrimary

    fun overlaySettingsIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }

    fun applyEnabled(enabled: Boolean) {
        vm.updateSettings { it.copy(floatingBallEnabled = enabled) }
        if (!enabled) {
            FloatingBallService.stop(context)
            return
        }
        if (Settings.canDrawOverlays(context)) {
            FloatingBallService.start(context)
        } else {
            // Can't show anything without the grant: send the user straight to it. Returning to
            // this page re-checks and starts the ball (see the resume observer below).
            context.startActivity(overlaySettingsIntent())
        }
    }

    // Copies the picked image next to the app's private files so the ball can render it later.
    val iconPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            // A new file name per pick: the path itself changes, so the settings flow emits and
            // the running ball reloads the picture (the same path would be de-duplicated away).
            val target = FloatingBallService.newIconFile(context)
            val copied = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    } ?: error("Cannot open $uri")
                }.onFailure { runCatching { target.delete() } }.isSuccess
            }
            if (copied) {
                vm.updateSettings { it.copy(floatingBallIconPath = target.absolutePath) }
                withContext(Dispatchers.IO) { FloatingBallIconFiles.deleteStale(context.filesDir, keep = target) }
            }
        }
    }

    // Re-check the grant on resume (the user comes back from the system settings page) and, if
    // the switch is on, start the ball now that it can actually be shown.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, settings.floatingBallEnabled) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                overlayGranted = Settings.canDrawOverlays(context)
                if (overlayGranted && settings.floatingBallEnabled) {
                    FloatingBallService.start(context)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_floating_ball_page_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                CardGroup(modifier = Modifier.padding(horizontal = 8.dp)) {
                    item(
                        headlineContent = {
                            Text(stringResource(R.string.setting_floating_ball_enable_title))
                        },
                        supportingContent = {
                            Text(stringResource(R.string.setting_floating_ball_enable_desc))
                        },
                        trailingContent = {
                            Switch(
                                checked = settings.floatingBallEnabled,
                                onCheckedChange = { applyEnabled(it) },
                            )
                        },
                    )
                    item(
                        onClick = {
                            if (!overlayGranted) context.startActivity(overlaySettingsIntent())
                        },
                        headlineContent = {
                            Text(stringResource(R.string.setting_floating_ball_overlay_title))
                        },
                        supportingContent = {
                            Text(
                                stringResource(
                                    if (overlayGranted) {
                                        R.string.setting_floating_ball_overlay_granted
                                    } else {
                                        R.string.setting_floating_ball_overlay_required
                                    }
                                )
                            )
                        },
                        trailingContent = {
                            if (!overlayGranted) {
                                TextButton(onClick = { context.startActivity(overlaySettingsIntent()) }) {
                                    Text(stringResource(R.string.setting_floating_ball_overlay_grant))
                                }
                            }
                        },
                    )
                }
            }

            item {
                CardGroup(modifier = Modifier.padding(horizontal = 8.dp)) {
                    item(
                        headlineContent = {
                            Text(stringResource(R.string.setting_floating_ball_style_title))
                        },
                        supportingContent = {
                            Text(stringResource(R.string.setting_floating_ball_style_desc))
                        },
                        trailingContent = {
                            Select(
                                options = FloatingBallStyle.entries,
                                selectedOption = settings.floatingBallStyle,
                                onOptionSelected = { style ->
                                    vm.updateSettings { it.copy(floatingBallStyle = style) }
                                },
                                optionToString = {
                                    stringResource(
                                        when (it) {
                                            FloatingBallStyle.ORIGINAL -> R.string.setting_floating_ball_style_original
                                            FloatingBallStyle.CAPSULE -> R.string.setting_floating_ball_style_capsule
                                        }
                                    )
                                },
                                // Blend into the card: same container colour, hairline for affordance.
                                containerColor = CustomColors.listItemColors.containerColor,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier.width(150.dp),
                            )
                        },
                    )
                    // The capsule always tucks into its handle (half a capsule would show a cut-off
                    // sliver of text), so the choice only exists for the round ball.
                    if (settings.floatingBallStyle == FloatingBallStyle.ORIGINAL) {
                        item(
                            headlineContent = {
                                Text(stringResource(R.string.setting_floating_ball_tuck_style_title))
                            },
                            supportingContent = {
                                Text(stringResource(R.string.setting_floating_ball_tuck_style_desc))
                            },
                            trailingContent = {
                                Select(
                                    options = FloatingBallTuckStyle.entries,
                                    selectedOption = settings.floatingBallTuckStyle,
                                    onOptionSelected = { tuck ->
                                        vm.updateSettings { it.copy(floatingBallTuckStyle = tuck) }
                                    },
                                    optionToString = {
                                        stringResource(
                                            when (it) {
                                                FloatingBallTuckStyle.HANDLE -> R.string.setting_floating_ball_tuck_style_handle
                                                FloatingBallTuckStyle.HALF_BALL -> R.string.setting_floating_ball_tuck_style_half_ball
                                            }
                                        )
                                    },
                                    containerColor = CustomColors.listItemColors.containerColor,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                    modifier = Modifier.width(150.dp),
                                )
                            },
                        )
                    }
                    item(
                        onClick = { showIconDialog = true },
                        leadingContent = {
                            FloatingBallPreview(color = ballColor, iconPath = settings.floatingBallIconPath)
                        },
                        headlineContent = {
                            Text(stringResource(R.string.setting_floating_ball_icon_title))
                        },
                        supportingContent = {
                            Text(stringResource(R.string.setting_floating_ball_icon_desc))
                        },
                    )
                    item(
                        onClick = {
                            colorDraft = settings.floatingBallColor
                            showColorDialog = true
                        },
                        leadingContent = {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(ballColor)
                            )
                        },
                        headlineContent = {
                            Text(stringResource(R.string.setting_floating_ball_color_title))
                        },
                        supportingContent = {
                            Text(
                                stringResource(
                                    if (settings.floatingBallColor == null) {
                                        R.string.setting_floating_ball_color_follow_theme
                                    } else {
                                        R.string.setting_floating_ball_color_custom
                                    }
                                )
                            )
                        },
                        trailingContent = {
                            TextButton(
                                onClick = {
                                    colorDraft = settings.floatingBallColor
                                    showColorDialog = true
                                }
                            ) {
                                Text(stringResource(R.string.setting_floating_ball_change))
                            }
                        },
                    )
                }
            }

            item {
                CardGroup(modifier = Modifier.padding(horizontal = 8.dp)) {
                    item(
                        headlineContent = {
                            Text(stringResource(R.string.setting_floating_ball_gestures_title))
                        },
                        supportingContent = {
                            Text(stringResource(R.string.setting_floating_ball_gestures_desc))
                        },
                    )
                    item(
                        headlineContent = {
                            Text(stringResource(R.string.quick_chat_look_screen))
                        },
                        supportingContent = {
                            Text(stringResource(R.string.setting_floating_ball_screen_action_hint))
                        },
                    )
                    item(
                        headlineContent = {
                            Text(stringResource(R.string.setting_floating_ball_voice_title))
                        },
                        supportingContent = {
                            Text(stringResource(R.string.setting_floating_ball_asr_hint))
                        },
                    )
                }
            }

            item {
                CardGroup(modifier = Modifier.padding(horizontal = 8.dp)) {
                    item(
                        onClick = { showIdleDialog = true },
                        headlineContent = {
                            Text(stringResource(R.string.setting_floating_ball_idle_title))
                        },
                        supportingContent = {
                            Text(stringResource(R.string.setting_floating_ball_idle_desc))
                        },
                        trailingContent = {
                            TextButton(onClick = { showIdleDialog = true }) {
                                Text(idleSecondsLabel(settings.floatingBallIdleSeconds))
                            }
                        },
                    )
                    item(
                        headlineContent = {
                            Text(stringResource(R.string.setting_floating_ball_alpha_title))
                        },
                        supportingContent = {
                            Column {
                                Text(stringResource(R.string.setting_floating_ball_alpha_desc))
                                Slider(
                                    value = alphaDraft,
                                    onValueChange = { alphaDraft = it },
                                    onValueChangeFinished = {
                                        vm.updateSettings {
                                            it.copy(floatingBallHiddenAlpha = alphaDraft.roundToInt())
                                        }
                                    },
                                    valueRange = 20f..100f,
                                    steps = 7,
                                )
                            }
                        },
                        trailingContent = {
                            Text("${alphaDraft.roundToInt()}%")
                        },
                    )
                }
            }

            item {
                Text(
                    text = stringResource(R.string.setting_floating_ball_notes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
    }

    if (showIconDialog) {
        AlertDialog(
            onDismissRequest = { showIconDialog = false },
            title = { Text(stringResource(R.string.setting_floating_ball_icon_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            showIconDialog = false
                            iconPicker.launch("image/*")
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.setting_floating_ball_icon_pick))
                    }
                    if (settings.floatingBallIconPath.isNotBlank()) {
                        Button(
                            onClick = {
                                showIconDialog = false
                                scope.launch {
                                    vm.updateSettings { it.copy(floatingBallIconPath = "") }
                                    withContext(Dispatchers.IO) {
                                        FloatingBallIconFiles.deleteStale(context.filesDir, keep = null)
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.setting_floating_ball_icon_reset))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showIconDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    if (showColorDialog) {
        val custom = colorDraft != null
        AlertDialog(
            onDismissRequest = { showColorDialog = false },
            title = { Text(stringResource(R.string.setting_floating_ball_color_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { colorDraft = null },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = !custom, onClick = { colorDraft = null })
                        Text(stringResource(R.string.setting_floating_ball_color_follow_theme))
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (colorDraft == null) colorDraft = themePrimary.toArgb()
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = custom,
                            onClick = { if (colorDraft == null) colorDraft = themePrimary.toArgb() },
                        )
                        Text(stringResource(R.string.setting_floating_ball_color_custom))
                    }
                    colorDraft?.let { draft ->
                        ColorPickerRow(
                            color = Color(draft),
                            onColorChange = { colorDraft = it.toArgb() },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val value = colorDraft
                        vm.updateSettings { it.copy(floatingBallColor = value) }
                        showColorDialog = false
                    }
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showColorDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    if (showIdleDialog) {
        AlertDialog(
            onDismissRequest = { showIdleDialog = false },
            title = { Text(stringResource(R.string.setting_floating_ball_idle_title)) },
            text = {
                Column {
                    IDLE_SECONDS_OPTIONS.forEach { option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    vm.updateSettings {
                                        it.copy(floatingBallIdleSeconds = option)
                                    }
                                    showIdleDialog = false
                                },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = settings.floatingBallIdleSeconds == option,
                                onClick = {
                                    vm.updateSettings {
                                        it.copy(floatingBallIdleSeconds = option)
                                    }
                                    showIdleDialog = false
                                },
                            )
                            Text(idleSecondsLabel(option))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showIdleDialog = false }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
        )
    }
}

/**
 * Miniature of the ball, so the appearance rows show what the user is about to get: a halo ring
 * in the theme / picked colour, or the picked image cropped into the circle. Mirrors the ring
 * geometry [me.rerere.rikkahub.service.HaloBallView] draws (the glow and the working-state orbit
 * only show on the real overlay).
 */
@Composable
private fun FloatingBallPreview(
    color: Color,
    iconPath: String,
    size: Dp = 36.dp,
) {
    // Mirrors the resting ball: translucent surface disc with a hairline, then the slim ring or
    // the picked image. (The drop shadow is left out at this size.)
    val surface = MaterialTheme.colorScheme.surface.copy(alpha = HaloBallLook.BACKING_ALPHA)
    val outline = MaterialTheme.colorScheme.outlineVariant
    Box(modifier = Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val half = this.size.minDimension / 2f
            val backing = half * HaloBallLook.BACKING_RADIUS_FRACTION
            drawCircle(color = surface, radius = backing)
            drawCircle(color = outline, radius = backing, style = Stroke(width = 1.dp.toPx()))
            if (iconPath.isBlank()) {
                drawCircle(
                    color = color.copy(alpha = HaloBallLook.REST_RING_ALPHA / 255f),
                    radius = half * HaloBallView.RING_RADIUS_FRACTION,
                    style = Stroke(
                        width = half * HaloBallView.STROKE_FRACTION * HaloBallLook.REST_RING_STROKE_SCALE
                    ),
                )
            }
        }
        if (iconPath.isNotBlank()) {
            // Same rim as the service: the image fills the ring's outer edge.
            val iconFraction = HaloBallView.RING_RADIUS_FRACTION + HaloBallView.STROKE_FRACTION / 2f
            AsyncImage(
                model = File(iconPath),
                contentDescription = null,
                modifier = Modifier
                    .size(size * iconFraction)
                    .clip(CircleShape),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

/** `0` disables the auto-tuck; anything else is a wait, in seconds. */
private val IDLE_SECONDS_OPTIONS = listOf(0, 2, 3, 5, 10)

@Composable
private fun idleSecondsLabel(seconds: Int): String =
    if (seconds <= 0) {
        stringResource(R.string.setting_floating_ball_idle_off)
    } else {
        stringResource(R.string.setting_floating_ball_idle_seconds, seconds)
    }
