package me.rerere.rikkahub.di

import okhttp3.Dispatcher

/**
 * Concurrency ceilings for the shared provider [okhttp3.OkHttpClient].
 *
 * OkHttp's defaults (64 total / **5 per host**) are tuned for short request/response calls. Our
 * client is different in two ways that make those defaults actively harmful:
 *
 *  - A streamed model reply holds its per-host slot for the *entire* turn - tens of seconds, and
 *    minutes on a long context.
 *  - One client is shared by every conversation, cron job, workflow and sub-agent, so several
 *    independent chats (or one chat that fanned out to several sub-agents) can be streaming to the
 *    same provider endpoint at the same moment.
 *
 * Past 5 concurrent requests to one host, the surplus sits in OkHttp's dispatcher queue having sent
 * zero bytes. From the app that is indistinguishable from a hang: the bubble shows "thinking...",
 * the elapsed counter keeps ticking, and logcat says nothing, because no request was ever made.
 *
 * Raising the ceiling moves the limit to where it belongs - the provider, which answers 429 promptly
 * and visibly instead of silently queueing.
 */
internal object HttpConcurrency {
    /** Total in-flight requests across all hosts. */
    const val MAX_REQUESTS = 128

    /** In-flight requests to a single host (OkHttp's default is 5). */
    const val MAX_REQUESTS_PER_HOST = 16

    fun dispatcher(): Dispatcher = Dispatcher().apply {
        maxRequests = MAX_REQUESTS
        maxRequestsPerHost = MAX_REQUESTS_PER_HOST
    }
}
