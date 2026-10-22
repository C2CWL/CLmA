package me.rerere.rikkahub.data.usage

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * Ambient who-is-asking for one model call.
 *
 * It travels in the coroutine context instead of being threaded through the Provider
 * interface, because the purpose of a call is known at the call site while that interface is
 * fixed: a chat turn and its tool-loop follow-ups share one Provider instance but must not
 * share a purpose. Wrap the call in `withContext(UsageCallContext(purpose = ...))` and the
 * recording decorator picks it up.
 *
 * With no element installed the call is recorded as [UsagePurpose.UNKNOWN] - never silently
 * as MAIN - so an un-wired path stays visible in the numbers instead of hiding inside the
 * biggest bucket.
 */
data class UsageCallContext(
    val purpose: UsagePurpose = UsagePurpose.UNKNOWN,
    val conversationId: String? = null,
    val assistantId: String? = null,
    val runId: String? = null,
    val parentRunId: String? = null,
) : AbstractCoroutineContextElement(UsageCallContext) {
    companion object Key : CoroutineContext.Key<UsageCallContext>
}

/** The context declared for the current model call, or null when nobody declared one. */
suspend fun currentUsageCallContext(): UsageCallContext? = coroutineContext[UsageCallContext]
