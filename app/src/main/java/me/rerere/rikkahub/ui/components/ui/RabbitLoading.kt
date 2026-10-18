package me.rerere.rikkahub.ui.components.ui

import android.graphics.drawable.AnimatedVectorDrawable
import android.widget.ImageView
import androidx.appcompat.content.res.AppCompatResources
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
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
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.LoadingIndicatorStyle
import me.rerere.rikkahub.data.datastore.effectiveLoadingIndicatorStyle
import me.rerere.rikkahub.ui.context.LocalSettings

/*
 * Single home for loading-indicator style dispatch. Both entry points below switch over every
 * [LoadingIndicatorStyle] with an exhaustive `when` (no `else`), so a new style fails to compile
 * until it is wired into both the icon slot and the chat row.
 */

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
 */
@Composable
fun ChatLoadingIndicator(processingStatus: String?) {
    when (LocalSettings.current.displaySetting.effectiveLoadingIndicatorStyle) {
        LoadingIndicatorStyle.SHIMMER -> ShimmerThinkingLabel(
            modifier = Modifier.padding(8.dp),
            text = processingStatus,
        )

        LoadingIndicatorStyle.SPARKLE -> SparkleThinkingRow(
            // Being the list's last item it stays below the newest content and goes when done.
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            text = processingStatus,
        )

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
