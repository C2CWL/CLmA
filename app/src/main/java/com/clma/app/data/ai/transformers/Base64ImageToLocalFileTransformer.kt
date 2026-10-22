package com.clma.app.data.ai.transformers

import com.clma.ai.ui.UIMessage
import com.clma.app.data.files.FilesManager
import org.koin.java.KoinJavaComponent.getKoin

object Base64ImageToLocalFileTransformer : OutputMessageTransformer {
    override suspend fun onGenerationFinish(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val filesManager = getKoin().get<FilesManager>()
        return messages.map { message ->
            filesManager.convertBase64ImagePartToLocalFile(message)
        }
    }
}
