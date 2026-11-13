package com.clma.app.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Keep partial output durable without pinning generation completion to a busy database. */
internal suspend fun persistGenerationCompletion(
    scope: CoroutineScope,
    timeoutMillis: Long = 1_500L,
    onFinished: () -> Unit = {},
    persist: suspend () -> Unit,
) {
    // Service ownership lets the write finish even if generation is cancelled. Read current
    // session state under the persistence mutex; never enqueue a captured stale snapshot.
    val write = scope.launch(Dispatchers.IO) { persist() }
    write.invokeOnCompletion { onFinished() }
    withContext(NonCancellable) {
        withTimeoutOrNull(timeoutMillis) { write.join() }
    }
}
