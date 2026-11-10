package com.clma.app.data.ai.tools.local

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.clma.ai.core.InputSchema
import com.clma.ai.core.Tool
import com.clma.ai.ui.UIMessagePart
import com.clma.app.data.ai.tools.ToolInvocationContext
import com.clma.app.data.repository.WorkspaceRepository
import com.clma.app.utils.FileOpenUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.util.UUID

fun deliverFileTool(
    context: Context,
    invocationContext: ToolInvocationContext,
    workspaceId: String?,
): Tool =
    Tool(
        name = "deliver_file",
        description =
            "Attach an existing file as a clickable, shareable chat document. Copies into persistent " +
                "app-owned storage; does not open another app. source defaults local (~ or absolute; /upload supported). " +
                "Set source=workspace for an absolute path inside the calling assistant's bound Workspace. " +
                "Set export_downloads=true only when the user requested a public export: writes a new MediaStore " +
                "Downloads/CLmA entry, never overwrites an existing file. Public export requires Android 10+. " +
                "Interactive chat only; background delivery is unsupported.",
        parameters = {
            InputSchema.Obj(
                properties =
                    buildJsonObject {
                        put("path", buildJsonObject { put("type", "string") })
                        put(
                            "source",
                            buildJsonObject {
                                put("type", "string")
                                put("description", "local (default) or workspace")
                            },
                        )
                        put("export_downloads", buildJsonObject { put("type", "boolean") })
                    },
                required = listOf("path"),
            )
        },
        execute = { input ->
            withContext(Dispatchers.IO) {
                var staged: File? = null
                var published: Uri? = null
                var complete = false
                try {
                    check(!invocationContext.isHeadless) { "deliver_file requires an interactive chat" }
                    val args = input.jsonObject
                    val path = args["path"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    require(path.isNotBlank()) { "path is required" }
                    val source = args["source"]?.jsonPrimitive?.contentOrNull ?: "local"
                    require(source == "local" || source == "workspace") { "source must be local or workspace" }
                    val export = args["export_downloads"]?.jsonPrimitive?.booleanOrNull ?: false
                    check(!export || Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        "Public export requires Android 10+; use the attachment share action on this device"
                    }
                    val name = File(path).name
                    require(name.isNotBlank() && name != "." && name != "..") { "Invalid file name" }
                    val destination = File(context.filesDir, "tool_outputs/deliveries/${UUID.randomUUID()}/$name")
                    check(destination.parentFile!!.mkdirs()) { "Cannot create delivery directory" }
                    staged = destination
                    if (source == "workspace") {
                        check(!workspaceId.isNullOrBlank()) { "The calling assistant has no bound Workspace" }
                        require(path.startsWith('/')) { "Workspace path must be absolute" }
                        val repository =
                            org.koin.java.KoinJavaComponent
                                .getKoin()
                                .get<WorkspaceRepository>()
                        destination.outputStream().use { repository.exportRootfsFile(workspaceId, path, it) }
                    } else {
                        val file =
                            if (path.startsWith("/upload/")) {
                                resolveAttachmentFile(File(context.filesDir, "upload"), path)
                            } else {
                                val expanded = AgentWorkspace.expand(path)
                                require(File(expanded).isAbsolute) { "Local path must be absolute or start with ~/" }
                                PathSafetyGuard.check(expanded)?.let { error(it.detail) }
                                allFilesAccessGuard(expanded)?.let { error(it) }
                                File(expanded).also { require(it.isFile) { "File not found" } }
                            }
                        file.inputStream().use { stream -> destination.outputStream().use { stream.copyTo(it) } }
                    }
                    val mime = FileOpenUtil.guessMime(destination).let { if (it == "*/*") "application/octet-stream" else it }
                    if (export && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val resolver = context.contentResolver
                        val values =
                            ContentValues().apply {
                                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/CLmA")
                                put(MediaStore.MediaColumns.IS_PENDING, 1)
                            }
                        val uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
                        published = uri
                        checkNotNull(resolver.openOutputStream(uri)).use { output ->
                            destination.inputStream().use { it.copyTo(output) }
                        }
                        check(
                            resolver.update(
                                uri,
                                ContentValues().apply {
                                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                                },
                                null,
                                null,
                            ) == 1,
                        ) { "Could not publish Downloads entry" }
                    }
                    val result =
                        listOf(
                            UIMessagePart.Document(url = Uri.fromFile(destination).toString(), fileName = name, mime = mime),
                            UIMessagePart.Text(
                                buildJsonObject {
                                    put("success", true)
                                    put("file_name", name)
                                    put("bytes", destination.length())
                                    put("attached", true)
                                    put("exported", published != null)
                                    published?.let { put("download_uri", it.toString()) }
                                }.toString(),
                            ),
                        )
                    complete = true
                    result
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    fmTextPart(fmErrEnvelope("file_delivery_failed", e.message ?: "Cannot deliver file"))
                } finally {
                    if (!complete) {
                        published?.let { runCatching { context.contentResolver.delete(it, null, null) } }
                        staged?.let {
                            it.delete()
                            it.parentFile?.delete()
                        }
                    }
                }
            }
        },
    )
