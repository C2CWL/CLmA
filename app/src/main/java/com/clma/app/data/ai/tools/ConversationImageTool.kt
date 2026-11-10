package com.clma.app.data.ai.tools

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import com.clma.ai.core.InputSchema
import com.clma.ai.core.MessageRole
import com.clma.ai.core.Tool
import com.clma.ai.ui.UIMessage
import com.clma.ai.ui.UIMessagePart
import com.clma.ai.util.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream

/** References identify a part in a stored message, never an arbitrary path supplied by the model. */
internal fun conversationImageRef(
    message: UIMessage,
    partIndex: Int,
): String = "${message.id}:$partIndex"

fun createConversationImageTool(
    context: Context,
    modelCanSeeImages: Boolean,
    messages: suspend () -> List<UIMessage>,
): Tool =
    Tool(
        name = "conversation_image",
        description =
            "List or view user-uploaded images in the CURRENT conversation's selected branch, including compacted history. " +
                "Without image_ref, lists up to 20 stable image references with dates and nearby text (offset paginates). " +
                "With image_ref, returns one real image for visual inspection, at most 2048px and 2 MiB. " +
                "Use this instead of text search when an older screenshot has been elided. No arbitrary paths or other conversations.",
        parameters = {
            InputSchema.Obj(
                properties =
                    buildJsonObject {
                        put(
                            "image_ref",
                            buildJsonObject {
                                put("type", "string")
                                put("description", "Stable reference from an elided image placeholder or this tool's listing; omit to list")
                            },
                        )
                        put(
                            "offset",
                            buildJsonObject {
                                put("type", "integer")
                                put("minimum", 0)
                                put("description", "Listing offset, default 0")
                            },
                        )
                    },
            )
        },
        execute = { input ->
            val args = input.jsonObject
            val ref = args["image_ref"]?.jsonPrimitive?.contentOrNull
            val images =
                messages().filter { it.role == MessageRole.USER && !it.isSynthetic }.flatMap { message ->
                    message.parts.mapIndexedNotNull { index, part ->
                        if (part is UIMessagePart.Image) Triple(conversationImageRef(message, index), message, part) else null
                    }
                }
            if (ref == null) {
                val offset = (args["offset"]?.jsonPrimitive?.intOrNull ?: 0).coerceAtLeast(0)
                val page = images.drop(offset).take(20)
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put(
                                "images",
                                buildJsonArray {
                                    page.forEach { (imageRef, message, _) ->
                                        add(
                                            buildJsonObject {
                                                put("image_ref", imageRef)
                                                put("date", message.createdAt.toString())
                                                put("text", message.toText().take(240))
                                            },
                                        )
                                    }
                                },
                            )
                            if (images.size.toLong() > offset.toLong() + page.size) put("next_offset", offset + page.size)
                        }.toString(),
                    ),
                )
            } else if (!modelCanSeeImages) {
                imageRecallError("vision_unavailable", "The current model cannot see images. Do not guess their contents.")
            } else {
                val image = images.firstOrNull { it.first == ref }?.third
                if (image == null) {
                    imageRecallError(
                        "image_not_found",
                        "Reference is not in this conversation's selected user messages. List images to find a valid reference.",
                    )
                } else if (!image.url.startsWith("file://") && !image.url.startsWith("content://")) {
                    imageRecallError("unsupported_source", "Only locally stored attachments can be recalled; no remote fetch is performed.")
                } else {
                    withContext(Dispatchers.IO) {
                        val bitmap = image.toBitmap(context, maxDimension = 2048)
                        if (bitmap == null) {
                            imageRecallError("image_unavailable", "The attachment could not be decoded or is no longer accessible.")
                        } else {
                            try {
                                val bytes =
                                    ByteArrayOutputStream().use { output ->
                                        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, output)
                                        output.toByteArray()
                                    }
                                if (bytes.size > 2 * 1024 * 1024) {
                                    imageRecallError("image_too_large", "The resized image exceeds 2 MiB; use a cropped attachment.")
                                } else {
                                    listOf(
                                        UIMessagePart.Text(
                                            buildJsonObject {
                                                put("image_ref", ref)
                                                put("visible_to_you", true)
                                                put("width", bitmap.width)
                                                put("height", bitmap.height)
                                            }.toString(),
                                        ),
                                        UIMessagePart.Image("data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)),
                                    )
                                }
                            } finally {
                                bitmap.recycle()
                            }
                        }
                    }
                }
            }
        },
    )

private fun imageRecallError(
    code: String,
    detail: String,
): List<UIMessagePart> =
    listOf(
        UIMessagePart.Text(
            buildJsonObject {
                put("error", code)
                put("detail", detail)
            }.toString(),
        ),
    )
