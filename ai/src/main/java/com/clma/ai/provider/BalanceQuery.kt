package com.clma.ai.provider

import com.clma.common.http.getByKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.math.RoundingMode

fun buildBalanceRequest(
    baseUrl: String,
    apiKey: String,
    providerHeaders: List<CustomHeader>,
    option: BalanceOption,
): Request {
    require(option.enabled) { "Balance query is disabled" }
    val base = baseUrl.toHttpUrl()
    val path = option.apiPath.trim()
    require(path.isNotEmpty()) { "Balance API path is empty" }
    val url = if (path.startsWith("/")) {
        (baseUrl.trimEnd('/') + path).toHttpUrl()
    } else {
        path.toHttpUrl()
    }
    // Never forward the chat API key or provider headers to a different origin.
    require(url.scheme == base.scheme && url.host == base.host && url.port == base.port) {
        "Balance URL must use the provider origin"
    }
    require(url.username.isEmpty() && url.password.isEmpty()) { "URL credentials are not supported" }
    val builder = Request.Builder().url(url)
    if (apiKey.isNotBlank()) builder.header("Authorization", "Bearer $apiKey")
    (providerHeaders + option.headers).filter { it.name.isNotBlank() }.forEach {
        builder.header(it.name, it.value)
    }
    when (option.method) {
        BalanceMethod.GET -> builder.get()
        BalanceMethod.POST -> {
            require(Json.parseToJsonElement(option.requestBody) is JsonObject) {
                "Balance request body must be a JSON object"
            }
            builder.post(option.requestBody.toRequestBody("application/json".toMediaType()))
        }
    }
    return builder.build()
}

fun formatBalanceResponse(body: String, option: BalanceOption): String {
    val root = Json.parseToJsonElement(body) as? JsonObject
        ?: error("Balance response must be a JSON object")
    val value = root.getByKey(option.resultPath)
    require(value.isNotBlank()) { "Balance result is empty; check the result expression" }
    val number = value.toBigDecimalOrNull()
    val formatted = if (number != null) {
        number.setScale(option.decimals.coerceIn(0, 8), RoundingMode.HALF_UP).toPlainString()
    } else {
        require(value != "NaN" && value != "Infinity" && value != "-Infinity") {
            "Balance result is not finite"
        }
        value
    }
    return option.prefix + formatted + option.suffix
}
