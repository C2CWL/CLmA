package me.rerere.rikkahub.ui.components.message

import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur

/**
 * A compact plate behind loose chrome that sits straight on a photo wallpaper (e.g. the sparkle
 * "Replying" row). Same mechanism as the usage line ([usageLineStyle] decides frosted vs flat vs
 * none: frosted blur of the wallpaper only on API 31+ with a haze state, a flat `surface` plate
 * otherwise), but the strength is the caller's own: [frostTintAlpha] / [frostBlur] for the
 * frosted pill and [flatAlpha] for the flat one, so the usage line keeps its values.
 * No photo → [content] is drawn as-is, no plate at all.
 */
@Composable
internal fun WallpaperContrastPlate(
    photoShown: Boolean,
    hazeState: HazeState?,
    frostTintAlpha: Float,
    frostBlur: Dp,
    flatAlpha: Float,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(16.dp),
    content: @Composable () -> Unit,
) {
    val style = usageLineStyle(photoShown, sdkInt = Build.VERSION.SDK_INT, hasHazeState = hazeState != null)
    if (style.frost != null && hazeState != null) {
        val backdrop = MaterialTheme.colorScheme.background
        val tint = MaterialTheme.colorScheme.surface.copy(alpha = frostTintAlpha)
        val blurStyle = remember(backdrop, tint, frostBlur) {
            HazeBlurStyle {
                backgroundColor(backdrop)
                blurRadius(frostBlur)
                colorEffects(listOf(HazeColorEffect.tint(tint)))
            }
        }
        Box(
            modifier = modifier
                .clip(shape)
                .hazeBlur(
                    input = HazeInput.Sources(hazeState, selection = WallpaperOnlySelection),
                    style = blurStyle,
                ),
        ) {
            content()
        }
    } else if (style.plateAlpha != null) {
        Surface(
            modifier = modifier,
            shape = shape,
            color = MaterialTheme.colorScheme.surface.copy(alpha = flatAlpha),
        ) {
            content()
        }
    } else {
        Box(modifier = modifier) { content() }
    }
}
