package com.clma.ai.util

import java.io.IOException

/**
 * The request could not be built or sent because it is too large for the app's memory (an
 * OutOfMemoryError while assembling it, or a pre-send size check).
 *
 * An [IOException] on purpose: OkHttp interceptors may only surface IOExceptions without
 * killing the process (see RequestLoggingInterceptor), and providers already forward those to
 * the generation loop. The retry policy refuses it - rebuilding the same request just runs out
 * of memory again - and the chat offers "compress and continue" instead.
 */
open class RequestTooLargeException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * A single video/audio attachment is over [MediaLimits.MAX_INLINE_MEDIA_BYTES]. Compressing the
 * history cannot fix this (the file is in the message being sent), so it is not treated as
 * [isRequestTooLarge] - but it is still never retried.
 */
class MediaTooLargeException(
    val fileName: String,
    val sizeBytes: Long,
    val limitBytes: Long = MediaLimits.MAX_INLINE_MEDIA_BYTES,
) : RequestTooLargeException(
    "File $fileName is ${sizeBytes / (1024 * 1024)} MB, over the " +
        "${limitBytes / (1024 * 1024)} MB limit for video/audio sent to a model",
)

/** Hard limits on media inlined into a request (base64 grows it by 4/3, built per request). */
object MediaLimits {
    /** A video or audio file larger than this is not inlined into a request. */
    const val MAX_INLINE_MEDIA_BYTES: Long = 20L * 1024 * 1024
}

private const val MAX_CAUSE_DEPTH = 8

private fun Throwable.causeChain(): Sequence<Throwable> =
    generateSequence(this) { it.cause }.take(MAX_CAUSE_DEPTH)

/**
 * True when this failure, or anything in its cause chain, means "the request is too large for
 * memory": an [OutOfMemoryError] (possibly wrapped, e.g. by the request interceptor) or a
 * [RequestTooLargeException].
 */
fun Throwable.isRequestTooLarge(): Boolean =
    causeChain().any {
        it is OutOfMemoryError || (it is RequestTooLargeException && it !is MediaTooLargeException)
    }

/** True when a video/audio attachment is over the inline limit; see [MediaTooLargeException]. */
fun Throwable.isMediaTooLarge(): Boolean = causeChain().any { it is MediaTooLargeException }

/**
 * True when retrying cannot help because the VM itself failed (OutOfMemoryError,
 * StackOverflowError, ...) or the request is too large. Superset of [isRequestTooLarge].
 */
fun Throwable.isFatalForRetry(): Boolean =
    causeChain().any { it is VirtualMachineError || it is RequestTooLargeException }
