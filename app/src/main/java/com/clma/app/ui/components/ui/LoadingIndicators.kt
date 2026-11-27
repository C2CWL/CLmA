package com.clma.app.ui.components.ui

import android.content.Context
import android.graphics.drawable.AnimatedVectorDrawable
import android.view.View
import android.widget.ImageView
import androidx.appcompat.content.res.AppCompatResources
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.clma.app.R
import com.clma.app.data.datastore.LoadingIndicatorStyle
import com.clma.app.data.datastore.effectiveLoadingIndicatorStyle
import com.clma.app.ui.components.message.WallpaperContrastPlate
import com.clma.app.ui.context.LocalSettings
import dev.chrisbanes.haze.HazeState

/*
 * Single home for loading-indicator style dispatch. Both entry points below switch over every
 * [LoadingIndicatorStyle] with an exhaustive `when` (no `else`), so a new style fails to compile
 * until it is wired into both the icon slot and the chat row.
 */

/*
 * Wallpaper plate behind the new rows (原子三点 / 呼吸光球 / 双星绕轨): still lighter than the usage
 * line's (0.70 / 20dp frosted, 0.86 flat), but opaque enough to keep the model-routing and
 * retry-failure status readable on a busy photo. The flat fallback for devices without real blur
 * (API < 31) is scaled down in proportion.
 */
private const val THINKING_PLATE_FROST_TINT_ALPHA = 0.58f
private val THINKING_PLATE_FROST_BLUR = 14.dp
private const val THINKING_PLATE_FLAT_ALPHA = 0.74f

/** Icon-sized indicator for square slots (e.g. the compress dialog), in the chosen style. */
@Composable
fun AppLoadingIndicator(modifier: Modifier = Modifier) {
    when (LocalSettings.current.displaySetting.effectiveLoadingIndicatorStyle) {
        LoadingIndicatorStyle.APP_ICON -> AppIconLoadingIndicator(modifier = modifier)
        LoadingIndicatorStyle.ATOM_DOTS -> AtomDotsLoadingIndicator(modifier = modifier)
        LoadingIndicatorStyle.BREATHING_ORB -> BreathingOrbLoadingIndicator(modifier = modifier)
        LoadingIndicatorStyle.TWIN_ORBIT -> TwinOrbitLoadingIndicator(modifier = modifier)
    }
}

/**
 * The chat list's "assistant is working" item, in the chosen style.
 *
 * APP_ICON keeps the original centred icon + optional [processingStatus] row. The other styles
 * are a left-aligned icon + label row where the reply text starts; the label is
 * [processingStatus] when there is one, otherwise the [phase] (思考中 / 回复中 / 工作中).
 * Over a photo wallpaper ([photoShown]) those rows sit on a compact plate (same frosted / flat
 * recipe as the usage line) so they stay legible on busy photos.
 */
@Composable
fun ChatLoadingIndicator(
    processingStatus: String?,
    phase: GenerationPhase = GenerationPhase.WORKING,
    photoShown: Boolean = false,
    hazeState: HazeState? = null,
) {
    // All mounted work styles use their original display-frame animation, without a 66ms cap.
    when (LocalSettings.current.displaySetting.effectiveLoadingIndicatorStyle) {
        LoadingIndicatorStyle.APP_ICON -> Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AppLoadingIndicator(
                modifier = Modifier.size(28.dp)
            )
            AnimatedVisibility(
                visible = processingStatus != null,
            ) {
                // Same wallpaper plate as the other styles: routing / failure text was bare over
                // the chat background and all but disappeared on a busy photo.
                WallpaperContrastPlate(
                    photoShown = photoShown,
                    hazeState = hazeState,
                    frostTintAlpha = THINKING_PLATE_FROST_TINT_ALPHA,
                    frostBlur = THINKING_PLATE_FROST_BLUR,
                    flatAlpha = THINKING_PLATE_FLAT_ALPHA,
                ) {
                    Text(
                        text = processingStatus ?: "",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                    )
                }
            }
        }

        LoadingIndicatorStyle.ATOM_DOTS -> ThinkingRowSlot(photoShown, hazeState) { rowModifier ->
            AtomDotsThinkingRow(
                phase = phase, processingStatus = processingStatus, modifier = rowModifier,
            )
        }

        LoadingIndicatorStyle.BREATHING_ORB -> ThinkingRowSlot(photoShown, hazeState) { rowModifier ->
            BreathingOrbThinkingRow(
                phase = phase, processingStatus = processingStatus, modifier = rowModifier,
            )
        }

        LoadingIndicatorStyle.TWIN_ORBIT -> ThinkingRowSlot(photoShown, hazeState) { rowModifier ->
            TwinOrbitThinkingRow(
                phase = phase, processingStatus = processingStatus, modifier = rowModifier,
            )
        }
    }
}

/**
 * Lays out a left-aligned indicator row: on a soft plate over photo wallpapers, bare otherwise.
 * Being the list's last item it stays below the newest content and goes when done.
 */
@Composable
private fun ThinkingRowSlot(
    photoShown: Boolean,
    hazeState: HazeState?,
    row: @Composable (Modifier) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        if (photoShown) {
            WallpaperContrastPlate(
                photoShown = true,
                hazeState = hazeState,
                frostTintAlpha = THINKING_PLATE_FROST_TINT_ALPHA,
                frostBlur = THINKING_PLATE_FROST_BLUR,
                flatAlpha = THINKING_PLATE_FLAT_ALPHA,
            ) {
                row(Modifier.padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp))
            }
        } else {
            row(Modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp))
        }
    }
}

@Composable
private fun AppIconLoadingIndicator(modifier: Modifier) {
    val primaryColor = MaterialTheme.colorScheme.primary.toArgb()
    val active = rememberWorkAnimationsEnabled()
    AndroidView(
        modifier = modifier,
        factory = { context -> WorkLoadingIconView(context) },
        onReset = null,
        onRelease = { it.releaseAnimation() },
        update = { it.updateAnimation(active, primaryColor) },
    )
}

/** Restores ic_clma_loading's rotating ring and fixed centre, not a new whole-mark spin. */
private class WorkLoadingIconView(context: Context) : ImageView(context) {
    private var controller: WorkAnimationController? = null
    private var active = false

    init {
        val animation = AppCompatResources.getDrawable(context, R.drawable.ic_clma_loading)
            as? AnimatedVectorDrawable
        setImageDrawable(animation)
        controller = WorkAnimationController(
            start = { animation?.start() },
            stop = { animation?.stop() },
        )
    }

    fun updateAnimation(enabled: Boolean, color: Int) {
        active = enabled
        drawable?.setTint(color)
        refreshAnimation()
    }

    private fun refreshAnimation() {
        controller?.update(active, isAttachedToWindow && isShown && windowVisibility == View.VISIBLE)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        refreshAnimation()
    }

    override fun onDetachedFromWindow() {
        controller?.update(false, false)
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        refreshAnimation()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        refreshAnimation()
    }

    fun releaseAnimation() {
        try { controller?.release() } finally { setImageDrawable(null) }
    }
}
