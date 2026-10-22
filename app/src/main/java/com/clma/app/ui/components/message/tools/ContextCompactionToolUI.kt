package com.clma.app.ui.components.message.tools

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.MagicWand01
import com.clma.app.R
import com.clma.app.data.ai.ContextCompactionPresentation

/** Renders automatic context compression as an informative, non-interactive tool step. */
internal object ContextCompactionToolUI : ToolUIRenderer {
    override val toolName: String = ContextCompactionPresentation.TOOL_NAME

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.MagicWand01

    @Composable
    override fun title(context: ToolUIContext): String =
        stringResource(R.string.setting_model_page_prompt_compress)
}
