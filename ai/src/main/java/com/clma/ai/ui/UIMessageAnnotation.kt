package com.clma.ai.ui

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class UIMessageAnnotation {
    @Serializable
    @SerialName("url_citation")
    data class UrlCitation(
        val title: String,
        val url: String
    ) : UIMessageAnnotation()

    @Serializable
    @SerialName("recalled_thread")
    data class RecalledThread(
        val conversationId: String,
        val title: String,
        val date: String,
    ) : UIMessageAnnotation()
}
