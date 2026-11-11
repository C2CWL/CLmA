package com.clma.app.ui.components.message.tools

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.clma.app.R
import com.clma.app.data.ai.ContextCompactionPresentation
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import com.clma.common.http.jsonObjectOrNull
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AlertCircle
import me.rerere.hugeicons.stroke.CheckmarkCircle01
import me.rerere.hugeicons.stroke.MagicWand01

/** Presentation cards have a fixed status schema, independent of the model's tool output. */
internal object ContextCompactionToolUI : ToolUIRenderer {
    override val toolName: String = ContextCompactionPresentation.TOOL_NAME

    override fun icon(context: ToolUIContext): ImageVector = when (status(context)) {
        "success" -> HugeIcons.CheckmarkCircle01
        "failure", "cancelled" -> HugeIcons.AlertCircle
        else -> HugeIcons.MagicWand01
    }

    @Composable
    override fun title(context: ToolUIContext): String = stringResource(when (status(context)) {
        "failure" -> R.string.context_compaction_failure
        "cancelled" -> R.string.context_compaction_cancelled
        else -> R.string.context_compaction_success
    })

    override fun hasSummary(context: ToolUIContext): Boolean = true

    @Composable
    override fun Summary(context: ToolUIContext) {
        val args = context.arguments.jsonObjectOrNull
        val before = args?.get("tokensBefore")?.jsonPrimitive?.intOrNull
        val after = args?.get("tokensAfter")?.jsonPrimitive?.intOrNull
        val text = when {
            before != null && after != null -> stringResource(R.string.context_compaction_tokens, before, after)
            before != null -> stringResource(R.string.context_compaction_unchanged, before)
            else -> title(context)
        }
        Text(text = text, style = MaterialTheme.typography.labelSmall)
    }

    private fun status(context: ToolUIContext): String? =
        context.arguments.jsonObjectOrNull?.get("status")?.jsonPrimitive?.content
}
