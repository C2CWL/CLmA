package me.rerere.ai.provider.providers.openai

/**
 * Normalises a local first-frame image for the video vendors that accept an **inline** image.
 *
 * [dataUriOrUrl] is either a `data:{mime};base64,...` URI (what `OpenAIProvider.toDataUri()`
 * produces from a picked file) or a plain `http(s)` URL.
 *
 * DashScope and Volcengine Ark accept the `data:` URI verbatim, but Zhipu and SiliconFlow document
 * their image field as「图片 URL 或 Base64 编码」— the *bare* base64 payload, with no `data:`
 * prefix — so the prefix is stripped here. Anything that is not a data URI is passed through
 * untouched, which keeps a public URL working for every vendor.
 *
 * Public (like the other helpers in this package) so the app module's test source set can pin it.
 */
fun inlineImagePayload(dataUriOrUrl: String): String =
    if (dataUriOrUrl.startsWith(DATA_URI_PREFIX, ignoreCase = true)) {
        dataUriOrUrl.substringAfter(',', missingDelimiterValue = dataUriOrUrl)
    } else {
        dataUriOrUrl
    }

private const val DATA_URI_PREFIX = "data:"
