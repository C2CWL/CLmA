package me.rerere.rikkahub.ui.components.message.tools

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.richtext.ZoomableAsyncImage
import java.io.File

/**
 * A tool-result image.
 *
 * Screen-automation screenshots are transient: unless the model asked to keep one (`keep=true`),
 * the file is deleted when the turn ends (`ScreenshotLedger.purge`). A chat bubble that outlives
 * its file must not turn into a broken image, so a `file://` url whose file is gone renders a
 * small "[screenshot cleaned up]" placeholder instead of the picture.
 */
@Composable
fun ToolResultImage(url: String, modifier: Modifier = Modifier) {
    val missing = url.startsWith("file://") &&
        !File(Uri.parse(url).path.orEmpty()).exists()
    if (missing) {
        Box(
            modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.chat_tool_image_cleaned),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
    } else {
        ZoomableAsyncImage(
            model = url,
            contentDescription = null,
            modifier = modifier,
        )
    }
}
