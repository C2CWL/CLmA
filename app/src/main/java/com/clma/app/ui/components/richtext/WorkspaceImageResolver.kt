package com.clma.app.ui.components.richtext

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import com.clma.app.data.repository.WorkspaceRepository
import org.koin.compose.koinInject

/** 把工作区 Rootfs 内的绝对路径解析成宿主机上可读的图片文件绝对路径; 找不到时返回 null */
fun interface WorkspaceImageResolver {
    suspend fun resolve(rootfsPath: String): String?
}

/** 聊天消息内提供, 让 [ZoomableAsyncImage] 能显示 AI 在工作区里生成的图片 */
val LocalWorkspaceImageResolver = staticCompositionLocalOf<WorkspaceImageResolver?> { null }

/** 为当前助手绑定的工作区提供图片路径解析; 没绑定工作区时不提供 */
@Composable
fun ProvideWorkspaceImageResolver(
    workspaceId: String?,
    content: @Composable () -> Unit,
) {
    if (workspaceId == null) {
        content()
        return
    }
    val workspaceRepository: WorkspaceRepository = koinInject()
    val resolver = remember(workspaceId, workspaceRepository) {
        WorkspaceImageResolver { path ->
            runCatching {
                workspaceRepository.resolveRootfsFileOrNull(workspaceId, path)?.absolutePath
            }.getOrNull()
        }
    }
    CompositionLocalProvider(LocalWorkspaceImageResolver provides resolver, content = content)
}
