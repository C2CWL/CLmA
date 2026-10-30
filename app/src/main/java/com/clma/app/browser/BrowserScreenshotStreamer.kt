package com.clma.app.browser

/** Best-effort screenshot transport for headless browser tools; defaults to [NoOp]. */
interface BrowserScreenshotStreamer {

    /**
     * Send [screenshotPath] (an absolute file path to a PNG on disk) into the chat
     * identified by [callerConvId] with a one-line caption derived from [actionLabel] and
     * the destination [currentUrl]. Best-effort — implementations log failures and return.
     *
     * Implementations should NOT delete the file after sending; the [BrowserController]
     * writes screenshots to a cache subdir that is swept by the OS (and the user's manual
     * "Clear cache" Doctor row).
     */
    suspend fun send(
        callerConvId: String,
        screenshotPath: String,
        actionLabel: String,
        currentUrl: String?,
    )

    /**
     * Default implementation used when no real streamer is registered (JVM tests, or a
     * build without a screenshot transport). Eats the call without ceremony.
     */
    object NoOp : BrowserScreenshotStreamer {
        override suspend fun send(
            callerConvId: String,
            screenshotPath: String,
            actionLabel: String,
            currentUrl: String?,
        ) {
            // intentional no-op
        }
    }
}
