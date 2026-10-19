package me.rerere.rikkahub.ui.components.ui

import android.graphics.drawable.AnimatedVectorDrawable
import android.widget.ImageView
import androidx.appcompat.content.res.AppCompatResources
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.chrisbanes.haze.HazeState
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.LoadingIndicatorStyle
import me.rerere.rikkahub.data.datastore.effectiveLoadingIndicatorStyle
import me.rerere.rikkahub.ui.components.message.WallpaperContrastPlate
import me.rerere.rikkahub.ui.context.LocalSettings

/*
 * Single home for loading-indicator style dispatch. Both entry points below switch over every
 * [LoadingIndicatorStyle] with an exhaustive `when` (no `else`), so a new style fails to compile
 * until it is wired into both the icon slot and the chat row.
 */

/*
 * Wallpaper plate behind the sparkle row: deliberately lighter than the usage line's (0.70 / 20dp
 * frosted, 0.86 flat) so it reads as a soft, see-through capsule rather than a block. The flat
 * fallback for devices without real blur (API < 31) is scaled down in proportion.
 */
private const val THINKING_PLATE_FROST_TINT_ALPHA = 0.42f
private val THINKING_PLATE_FROST_BLUR = 12.dp
private const val THINKING_PLATE_FLAT_ALPHA = 0.60f

/** Icon-sized indicator for square slots (e.g. the compress dialog), in the chosen style. */
@Composable
fun RabbitLoadingIndicator(modifier: Modifier = Modifier) {
    when (LocalSettings.current.displaySetting.effectiveLoadingIndicatorStyle) {
        LoadingIndicatorStyle.SPARKLE -> SparkleLoadingIndicator(modifier = modifier)
        LoadingIndicatorStyle.SHIMMER -> ShimmerBar(modifier = modifier)
        LoadingIndicatorStyle.APP_ICON -> AppIconLoadingIndicator(modifier = modifier)
        LoadingIndicatorStyle.DEFAULT -> ContainedLoadingIndicator(modifier = modifier)
    }
}

/**
 * The chat list's "assistant is working" item, in the chosen style, showing [processingStatus]
 * when there is one. DEFAULT and APP_ICON keep the original centred icon + status row; SHIMMER is
 * a label pill; SPARKLE is a left-aligned icon + "Replying" row where the reply text starts.
 *
 * Over a photo wallpaper ([photoShown]) the SPARKLE row sits on a compact plate (same frosted /
 * flat recipe as the usage line) so the soft palette stays visible on busy photos.
 */
@Composable
fun ChatLoadingIndicator(
    processingStatus: String?,
    photoShown: Boolean = false,
    hazeState: HazeState? = null,
) {
    when (LocalSettings.current.displaySetting.effectiveLoadingIndicatorStyle) {
        LoadingIndicatorStyle.SHIMMER -> ShimmerThinkingLabel(
            modifier = Modifier.padding(8.dp),
            text = processingStatus,
        )

        LoadingIndicatorStyle.SPARKLE -> if (photoShown) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
            ) {
                // Wraps icon + label as one pill; the row itself is unchanged (motion, static
                // fallback), only padded so it does not touch the plate's edge.
                WallpaperContrastPlate(
                    photoShown = true,
                    hazeState = hazeState,
                    frostTintAlpha = THINKING_PLATE_FROST_TINT_ALPHA,
                    frostBlur = THINKING_PLATE_FROST_BLUR,
                    flatAlpha = THINKING_PLATE_FLAT_ALPHA,
                ) {
                    SparkleThinkingRow(
                        modifier = Modifier.padding(start = 4.dp, end = 10.dp, top = 2.dp, bottom = 2.dp),
                        text = processingStatus,
                    )
                }
            }
        } else {
            SparkleThinkingRow(
                // Being the list's last item it stays below the newest content and goes when done.
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                text = processingStatus,
            )
        }

        LoadingIndicatorStyle.DEFAULT, LoadingIndicatorStyle.APP_ICON -> Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RabbitLoadingIndicator(
                modifier = Modifier.size(28.dp)
            )
            AnimatedVisibility(
                visible = processingStatus != null,
            ) {
                Text(
                    text = processingStatus ?: "",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AppIconLoadingIndicator(modifier: Modifier) {
    val primaryColor = MaterialTheme.colorScheme.primary.toArgb()
    AndroidView(
        modifier = modifier,
        factory = { context ->
            ImageView(context).apply {
                val drawable = AppCompatResources.getDrawable(context, R.drawable.rabbit) as? AnimatedVectorDrawable
                setImageDrawable(drawable)
                drawable?.setTint(primaryColor)
                drawable?.start()
            }
        },
        update = { imageView ->
            (imageView.drawable as? AnimatedVectorDrawable)?.apply {
                setTint(primaryColor)
                start()
            }
        }
    )
}
