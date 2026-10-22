package com.clma.app.ui.components.richtext

import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.DefaultAlpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.request.error
import coil3.request.placeholder
import com.clma.app.R
import com.clma.app.data.ai.tools.local.AgentWorkspace
import com.clma.app.ui.components.ui.ImagePreviewDialog
import com.clma.app.ui.components.ui.LocalExportContext
import com.clma.app.ui.modifier.shimmer
import com.clma.app.ui.theme.LocalDarkMode

@Composable
fun ZoomableAsyncImage(
    model: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.Center,
    contentScale: ContentScale = ContentScale.Fit,
    alpha: Float = DefaultAlpha,
) {
    var showImageViewer by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val placeholder = if(LocalDarkMode.current) R.drawable.placeholder_dark else R.drawable.placeholder
    val export = LocalExportContext.current
    // AI 在工作区里生成图片后, Markdown 里写的是 Rootfs 内路径 (/workspace/a.png、a.png 等),
    // 这些路径在 Android 上不存在, 必须先映射到宿主机上的真实文件再交给 Coil。
    val workspaceResolver = LocalWorkspaceImageResolver.current
    val workspacePath = remember(model) { ChatImagePaths.workspacePathOf(model) }
    val displayModel by produceState(
        initialValue = expandAgentHome(model).takeIf { workspacePath == null },
        model,
        workspacePath,
        workspaceResolver,
    ) {
        value = if (workspacePath != null && workspaceResolver != null) {
            workspaceResolver.resolve(workspacePath) ?: model
        } else {
            expandAgentHome(model)
        }
    }
    val coilModel = ImageRequest.Builder(context)
        .data(displayModel)
        .placeholder(placeholder)
        // 加载失败时也显示占位图, 而不是一块透明的空白
        .error(placeholder)
        .crossfade(false)
        .allowHardware(!export)
        .build()
    var loading by remember { mutableStateOf(false) }
    AsyncImage(
        model = coilModel,
        contentDescription = contentDescription,
        modifier = modifier
            .shimmer(isLoading = loading)
            .clickable {
                showImageViewer = true
            },
        contentScale = contentScale,
        alpha = alpha,
        alignment = alignment,
        onLoading = {
            loading = true
        },
        onSuccess = {
            loading = false
        },
        onError = {
            loading = false
        },
    )
    if (showImageViewer) {
        ImagePreviewDialog(images = listOf(displayModel ?: model ?: "")) {
            showImageViewer = false
        }
    }
}

/** `~/a.png` 指向 AgentWorkspace (本地文件工具的 home), 展开成宿主机绝对路径 */
private fun expandAgentHome(model: String?): String? {
    if (model == null || !model.startsWith("~/")) return model
    return runCatching { AgentWorkspace.expand(model) }.getOrDefault(model)
}
