package com.clma.app.data.ai.tools.local

import android.content.Context
import com.clma.ai.core.InputSchema
import com.clma.ai.core.Tool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.RandomAccessFile
import java.util.Base64

fun readAttachmentTool(context: Context): Tool =
    Tool(
        name = "read_attachment",
        description =
            "Read an uploaded file or its /upload/.extracted/ UTF-8 text without a Workspace. " +
                "Text: offset is a 1-based line, column a 0-based character position; limit defaults 500 lines, " +
                "each page is capped at 100000 characters. Continue using next_offset AND next_column. " +
                "Binary: set mode=base64, byte_offset (default 0), max_bytes (default 65536, max 1048576); " +
                "continue using next_byte_offset. Never treat binary bytes as parsed document text.",
        parameters = {
            InputSchema.Obj(
                properties =
                    buildJsonObject {
                        put("path", buildJsonObject { put("type", "string") })
                        put(
                            "mode",
                            buildJsonObject {
                                put("type", "string")
                                put("description", "text (default) or base64")
                            },
                        )
                        listOf("offset", "column", "limit", "byte_offset", "max_bytes").forEach { name ->
                            put(name, buildJsonObject { put("type", "integer") })
                        }
                    },
                required = listOf("path"),
            )
        },
        execute = { input ->
            withContext(Dispatchers.IO) {
                try {
                    val args = input.jsonObject
                    val path = args["path"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val file = resolveAttachmentFile(File(context.filesDir, "upload"), path)
                    val mode = args["mode"]?.jsonPrimitive?.contentOrNull ?: "text"
                    require(mode == "text" || mode == "base64") { "mode must be text or base64" }
                    fmTextPart(
                        buildJsonObject {
                            put("path", path)
                            if (mode == "base64") {
                                val offset = args["byte_offset"]?.jsonPrimitive?.longOrNull ?: 0L
                                require(offset >= 0) { "byte_offset must be nonnegative" }
                                val maxBytes = (args["max_bytes"]?.jsonPrimitive?.intOrNull ?: 65536).coerceIn(1, 1048576)
                                RandomAccessFile(file, "r").use { source ->
                                    source.seek(offset)
                                    val bytes = ByteArray(minOf(maxBytes.toLong(), (source.length() - offset).coerceAtLeast(0)).toInt())
                                    source.readFully(bytes)
                                    val next = offset + bytes.size
                                    put("content_base64", Base64.getEncoder().encodeToString(bytes))
                                    put("bytes_read", bytes.size)
                                    put("total_bytes", source.length())
                                    put("truncated", next < source.length())
                                    if (next < source.length()) put("next_byte_offset", next)
                                }
                            } else {
                                val page =
                                    readAttachmentPage(
                                        file,
                                        args["offset"]?.jsonPrimitive?.intOrNull ?: 1,
                                        args["column"]?.jsonPrimitive?.intOrNull ?: 0,
                                        (args["limit"]?.jsonPrimitive?.intOrNull ?: 500).coerceIn(1, 2000),
                                    )
                                put("text", page.text)
                                put("truncated", page.nextOffset != null)
                                page.nextOffset?.let { put("next_offset", it) }
                                page.nextColumn?.let { put("next_column", it) }
                            }
                        }.toString(),
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    fmTextPart(fmErrEnvelope("attachment_read_failed", e.message ?: "Cannot read attachment"))
                }
            }
        },
    )
