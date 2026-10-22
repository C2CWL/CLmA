package com.clma.app.ui.components.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.clma.ai.core.MessageRole
import com.clma.ai.ui.UIMessage
import com.clma.ai.ui.UIMessagePart
import com.clma.app.R

/** What the assistant is doing right now, as far as the chat can tell. Drives the loading label. */
enum class GenerationPhase {
    /** A reasoning block is still streaming. */
    THINKING,

    /** Reply text is streaming. */
    REPLYING,

    /** Tool call, waiting for the first token, or anything else we cannot tell apart. */
    WORKING,
}

/**
 * Phase from the newest message of the conversation (the selected branch of its last node).
 * Only the last part counts: an unfinished reasoning part means thinking, a text part means
 * replying; tools, a finished reasoning part, a user message (request just sent) or nothing at
 * all fall back to [GenerationPhase.WORKING].
 */
fun generationPhaseOf(message: UIMessage?): GenerationPhase {
    if (message == null || message.role != MessageRole.ASSISTANT) return GenerationPhase.WORKING
    return when (val last = message.parts.lastOrNull()) {
        is UIMessagePart.Reasoning -> if (last.finishedAt == null) GenerationPhase.THINKING else GenerationPhase.WORKING
        is UIMessagePart.Text -> GenerationPhase.REPLYING
        else -> GenerationPhase.WORKING
    }
}

/** Short label for [phase] without trailing ellipsis, e.g. 思考中 / 回复中 / 工作中. */
@Composable
fun generationPhaseLabel(phase: GenerationPhase): String = stringResource(
    when (phase) {
        GenerationPhase.THINKING -> R.string.chat_loading_phase_thinking
        GenerationPhase.REPLYING -> R.string.chat_loading_phase_replying
        GenerationPhase.WORKING -> R.string.chat_loading_phase_working
    }
)
