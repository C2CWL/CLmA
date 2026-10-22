package com.clma.app.ui.pages.setting

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clma.app.R
import com.clma.app.data.datastore.EdgeBarColor
import com.clma.app.service.EdgeBarLook
import com.clma.app.service.FloatingBallService
import com.clma.app.ui.components.nav.BackButton
import com.clma.app.ui.components.ui.CardGroup
import com.clma.app.ui.components.ui.Select
import com.clma.app.ui.theme.CustomColors
import com.clma.app.utils.plus
import org.koin.androidx.compose.koinViewModel

/**
 * Settings for the always-available edge bar (it replaced the floating ball): master switch, the
 * SYSTEM_ALERT_WINDOW grant it depends on, the colour swatch, and a preview of the bar.
 */
@Composable
fun SettingFloatingBallPage(vm: SettingVM = koinViewModel()) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }

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
            // this page re-checks and starts the bar (see the resume observer below).
            context.startActivity(overlaySettingsIntent())
        }
    }

    // Re-check the grant on resume (the user comes back from the system settings page) and, if
    // the switch is on, start the bar now that it can actually be shown.
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
                        headlineContent = {
                            Text(stringResource(R.string.setting_floating_ball_task_card_title))
                        },
                        supportingContent = {
                            Text(stringResource(R.string.setting_floating_ball_task_card_desc))
                        },
                        trailingContent = {
                            Switch(
                                checked = settings.displaySetting.edgeBarTaskCard,
                                onCheckedChange = { checked ->
                                    vm.updateSettings {
                                        it.copy(displaySetting = it.displaySetting.copy(edgeBarTaskCard = checked))
                                    }
                                },
                            )
                        },
                    )
                    item(
                        headlineContent = {
                            Text(stringResource(R.string.setting_floating_ball_done_tap_title))
                        },
                        supportingContent = {
                            Text(stringResource(R.string.setting_floating_ball_done_tap_desc))
                        },
                        trailingContent = {
                            Select(
                                options = listOf(true, false),
                                selectedOption = settings.displaySetting.edgeBarDoneTapOpensChatBox,
                                onOptionSelected = { opensChatBox ->
                                    vm.updateSettings {
                                        it.copy(
                                            displaySetting = it.displaySetting.copy(
                                                edgeBarDoneTapOpensChatBox = opensChatBox,
                                            )
                                        )
                                    }
                                },
                                optionToString = { opensChatBox ->
                                    stringResource(
                                        if (opensChatBox) {
                                            R.string.setting_floating_ball_done_tap_chat_box
                                        } else {
                                            R.string.setting_floating_ball_done_tap_app
                                        }
                                    )
                                },
                                containerColor = CustomColors.listItemColors.containerColor,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier.width(130.dp),
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
                            Text(stringResource(R.string.setting_floating_ball_color_title))
                        },
                        supportingContent = {
                            EdgeBarSwatches(
                                selected = settings.floatingBallBarColor,
                                onSelect = { color ->
                                    vm.updateSettings { it.copy(floatingBallBarColor = color) }
                                },
                                modifier = Modifier.padding(top = 12.dp),
                            )
                        },
                    )
                    item(
                        headlineContent = {
                            Text(stringResource(R.string.setting_floating_ball_preview_title))
                        },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(stringResource(R.string.setting_floating_ball_preview_desc))
                                EdgeBarPreview(color = settings.floatingBallBarColor)
                            }
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
}

/**
 * The six colour swatches; the selected one gets a ring in the theme's primary colour. The first
 * one follows the theme and is drawn half cream / half warm dark grey.
 */
@Composable
private fun EdgeBarSwatches(
    selected: EdgeBarColor,
    onSelect: (EdgeBarColor) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ring = MaterialTheme.colorScheme.primary
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        EdgeBarColor.entries.forEach { color ->
            val isSelected = color == selected
            val label = stringResource(color.labelRes())
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .border(
                        width = if (isSelected) 2.dp else 0.dp,
                        color = if (isSelected) ring else Color.Transparent,
                        shape = CircleShape,
                    )
                    .clickable(role = Role.RadioButton) { onSelect(color) }
                    .semantics {
                        contentDescription = label
                        this.selected = isSelected
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (color.followsTheme) {
                    FollowThemeSwatch()
                } else {
                    // A light checker-ish backing so the translucent swatches read as translucent.
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(SWATCH_BACKING)
                            .background(Color(color.argb(dark = false)))
                            .then(
                                color.outlineArgb(dark = false)?.let {
                                    Modifier.border(1.dp, Color(it), CircleShape)
                                } ?: Modifier
                            ),
                    )
                }
            }
        }
    }
}

private val SWATCH_BACKING = Color(0xFFE6E6EA)

/**
 * The "follow theme" swatch: left half the light-mode cream, right half the dark-mode warm grey,
 * so it reads as "changes with the theme".
 */
@Composable
private fun FollowThemeSwatch() {
    Canvas(modifier = Modifier.size(30.dp)) {
        drawCircle(color = SWATCH_BACKING)
        drawArc(
            color = Color(EdgeBarColor.THEME_LIGHT_FILL),
            startAngle = 90f,
            sweepAngle = 180f,
            useCenter = true,
        )
        drawArc(
            color = Color(EdgeBarColor.THEME_DARK_FILL),
            startAngle = 270f,
            sweepAngle = 180f,
            useCenter = true,
        )
        val stroke = 1.dp.toPx()
        drawCircle(
            color = Color(EdgeBarColor.THEME_LIGHT_OUTLINE),
            radius = size.minDimension / 2f - stroke / 2f,
            style = Stroke(width = stroke),
        )
    }
}

/**
 * The bar at its real size against a light and a dark backdrop, flush with the right edge the way
 * it sits on screen by default. The "follow theme" swatch shows its light-mode look on the light
 * backdrop and its dark-mode look on the dark one.
 */
@Composable
private fun EdgeBarPreview(color: EdgeBarColor) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        listOf(Color(0xFFF1F1F4) to false, Color(0xFF1E1E22) to true).forEach { (backdrop, dark) ->
            Canvas(
                modifier = Modifier
                    .weight(1f)
                    .height((EdgeBarLook.TOUCH_HEIGHT_DP + 24f).dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(backdrop),
            ) {
                val barW = EdgeBarLook.BAR_WIDTH_DP.dp.toPx()
                val barH = EdgeBarLook.BAR_HEIGHT_DP.dp.toPx()
                val radius = EdgeBarLook.BAR_CORNER_DP.dp.toPx()
                val topLeft = Offset(size.width - barW, (size.height - barH) / 2f)
                drawRoundRect(
                    color = Color(color.argb(dark)),
                    topLeft = topLeft,
                    size = Size(barW, barH),
                    cornerRadius = CornerRadius(radius, radius),
                )
                color.outlineArgb(dark)?.let { outline ->
                    val stroke = EdgeBarLook.OUTLINE_DP.dp.toPx()
                    drawRoundRect(
                        color = Color(outline),
                        topLeft = topLeft + Offset(stroke / 2f, stroke / 2f),
                        size = Size(barW - stroke, barH - stroke),
                        cornerRadius = CornerRadius(radius - stroke / 2f, radius - stroke / 2f),
                        style = Stroke(width = stroke),
                    )
                }
            }
        }
    }
}

private fun EdgeBarColor.labelRes(): Int = when (this) {
    EdgeBarColor.FOLLOW_THEME -> R.string.setting_floating_ball_color_follow_theme
    EdgeBarColor.LIGHT_GRAY -> R.string.setting_floating_ball_color_light_gray
    EdgeBarColor.BLACK -> R.string.setting_floating_ball_color_black
    EdgeBarColor.WHITE -> R.string.setting_floating_ball_color_white
    EdgeBarColor.BLUE -> R.string.setting_floating_ball_color_blue
    EdgeBarColor.TEAL -> R.string.setting_floating_ball_color_teal
}
