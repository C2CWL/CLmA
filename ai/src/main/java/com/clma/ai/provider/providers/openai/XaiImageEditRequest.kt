package com.clma.ai.provider.providers.openai

import com.clma.ai.provider.ImageEditParams
import com.clma.ai.ui.ImageAspectRatio
import com.clma.ai.util.mergeCustomBody
import com.clma.ai.util.toJsonRequestBody
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.RequestBody
import java.io.File
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

internal fun isXaiBaseUrl(baseUrl: String): Boolean =
    baseUrl.toHttpUrlOrNull()?.host == "api.x.ai"

fun xaiImageAspectRatio(ratio: ImageAspectRatio): String = when (ratio) {
    ImageAspectRatio.SQUARE -> "1:1"
    ImageAspectRatio.LANDSCAPE -> "16:9"
    ImageAspectRatio.PORTRAIT -> "9:16"
}

private fun requireXaiImageCount(count: Int) {
    require(count in 1..5) { "xAI image editing requires between 1 and 5 source images" }
}

internal fun buildXaiImageEditJson(params: ImageEditParams, imageUrls: List<String>): JsonObject {
    requireXaiImageCount(imageUrls.size)
    return buildJsonObject {
        put("model", params.model.modelId)
        put("prompt", params.prompt)
        put("aspect_ratio", xaiImageAspectRatio(params.aspectRatio))
        put("n", params.numOfImages.coerceIn(1, 10))
        // grok-imagine's default URLs expire within minutes, so ask for the bytes directly.
        // A custom body may still override this (the edit path's response parser accepts both).
        put("response_format", "b64_json")
        if (imageUrls.size == 1) {
            putJsonObject("image") {
                put("url", imageUrls.single())
                put("type", "image_url")
            }
        } else {
            putJsonArray("images") {
                imageUrls.forEach { url ->
                    addJsonObject { put("url", url) }
                }
            }
        }
    }.mergeCustomBody(params.customBody.filterNot { it.key in setOf("image", "images", "size") })
}

/** Reads local files and builds a JSON body. Call from the IO dispatcher. */
@OptIn(ExperimentalEncodingApi::class)
fun buildXaiImageEditRequestBody(params: ImageEditParams): RequestBody {
    requireXaiImageCount(params.images.size)
    val imageUrls = params.images.map { path ->
        val file = File(path)
        require(file.isFile) { "Image file does not exist: $path" }
        val mime = when (file.extension.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            else -> error("Unsupported image file type for xAI edit: ${file.extension}")
        }
        "data:$mime;base64,${Base64.encode(file.readBytes())}"
    }
    return buildXaiImageEditJson(params, imageUrls).toJsonRequestBody()
}
