package com.clma.app.data.ai.tools.local

import android.app.DownloadManager
import android.content.Context
import android.os.Environment
import androidx.core.net.toUri
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import com.clma.ai.core.InputSchema
import com.clma.ai.core.Tool
import com.clma.ai.ui.UIMessagePart
import com.clma.app.data.ai.net.isBlockedDownloadUrl

fun downloadTool(context: Context): Tool = Tool(
    name = "download_file",
    description = """
        Queue a file download via Android's DownloadManager. Files land in the public Downloads
        directory. Returns immediately with a download_id; the actual download proceeds in the
        background.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("url", buildJsonObject {
                    put("type", "string")
                    put("description", "The URL of the file to download")
                })
                put("filename", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional filename to save as (defaults to last URL path segment)")
                })
            },
            required = listOf("url")
        )
    },
    execute = {
        val params = it.jsonObject
        val url = params["url"]?.jsonPrimitive?.contentOrNull
            ?: error("url is required")
        val filenameParam = params["filename"]?.jsonPrimitive?.contentOrNull?.takeIf { s -> s.isNotEmpty() }

        // DownloadManager is not behind withEgressGuard: without this pre-check the model could
        // pull `http://127.0.0.1:8080/...` or a LAN/metadata address into public Downloads and
        // then read it back with the (auto-approved) read_file tool.
        if (isBlockedDownloadUrl(url)) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("error", "blocked_private_address")
                        put(
                            "detail",
                            "Downloads from loopback, private, link-local or otherwise non-routable " +
                                "addresses are refused.",
                        )
                    }.toString()
                )
            )
        }

        try {
            val uri = url.toUri()
            val rawName = filenameParam
                ?: uri.lastPathSegment
                ?: "download_${System.currentTimeMillis()}"
            val name = rawName.substringAfterLast('/').trimStart('.').ifEmpty {
                "download_${System.currentTimeMillis()}"
            }
            val request = DownloadManager.Request(uri)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                .setTitle(name)
            val dm = context.getSystemService(DownloadManager::class.java)
                ?: return@Tool listOf(
                    UIMessagePart.Text(
                        buildJsonObject { put("error", "DownloadManager unavailable") }.toString()
                    )
                )
            val id = dm.enqueue(request)
            val payload = buildJsonObject {
                put("success", true)
                put("download_id", id)
                put("filename", name)
            }
            listOf(UIMessagePart.Text(payload.toString()))
        } catch (e: IllegalArgumentException) {
            listOf(
                UIMessagePart.Text(
                    buildJsonObject { put("error", e.message ?: "invalid argument") }.toString()
                )
            )
        } catch (e: SecurityException) {
            listOf(
                UIMessagePart.Text(
                    buildJsonObject { put("error", e.message ?: "security error") }.toString()
                )
            )
        }
    }
)