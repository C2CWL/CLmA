package com.clma.app.data.ai

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Best-effort transformer cleanup must not keep a stopped turn alive indefinitely. */
internal suspend fun finalizeCancelledGeneration(block: suspend () -> Unit) {
    withContext(NonCancellable) {
        withTimeoutOrNull(1_500L) { block() }
    }
}
