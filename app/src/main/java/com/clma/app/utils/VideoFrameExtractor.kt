package com.clma.app.utils

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream
import java.util.Locale

/**
 * Sparse keyframe extraction for local videos using Android's platform
 * MediaMetadataRetriever + MediaExtractor. Visual frames + timestamps only —
 * no audio transcription. Frames are emitted as inline `data:image/jpeg;base64,...`
 * URLs, which FileEncoder/encodeBase64 passes straight through to `image_url`.
 */
object VideoFrameExtractor {
    private const val TAG = "VideoFrameExtractor"

    /** Hard ceiling: at most 12 keyframes per request, across every video in it. */
    const val MAX_FRAMES_PER_REQUEST = 12

    /** Balanced default: 8 evenly spaced sync samples per video. */
    const val TARGET_FRAMES = 8

    /** Fewer distinct frames than this is too sparse to be useful; caller falls back. */
    const val MIN_FRAMES = 6

    /** Longest edge of an encoded frame; keeps inline base64 and vision tokens bounded. */
    private const val MAX_FRAME_EDGE = 768
    private const val JPEG_QUALITY = 80

    /** One decoded frame plus its real sync-sample timestamp, never a fabricated seek time. */
    data class ExtractedFrame(
        val imageUrl: String,
        val timestampText: String,
    )

    /**
     * Never throws for a malformed/unreadable video (except cancellation): returns an
     * empty list so the caller keeps the original Video part for downstream fallback.
     */
    suspend fun extract(
        context: Context,
        url: String,
        maxFrames: Int = TARGET_FRAMES,
    ): List<ExtractedFrame> {
        if (maxFrames < 2) return emptyList()
        return runCatching { extractInternal(context, url, maxFrames) }.getOrElse { error ->
            if (error is CancellationException) throw error
            Log.w(TAG, "Frame extraction failed, falling back to original video", error)
            emptyList()
        }
    }

    private suspend fun extractInternal(
        context: Context,
        url: String,
        maxFrames: Int,
    ): List<ExtractedFrame> {
        val uri = Uri.parse(url)
        require(uri.scheme == "content" || uri.scheme == "file" || uri.scheme == null) {
            "Video must be local before extracting frames"
        }
        val path = when (uri.scheme) {
            null -> url
            "file" -> requireNotNull(uri.path) { "File URI has no path" }
            else -> null
        }

        val retriever = MediaMetadataRetriever()
        val extractor = MediaExtractor()
        try {
            if (path != null) {
                retriever.setDataSource(path)
                extractor.setDataSource(path)
            } else {
                retriever.setDataSource(context, uri)
                extractor.setDataSource(context, uri, null)
            }

            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
            require(durationMs != null && durationMs > 0) { "Video duration is unavailable" }

            val trackIndex = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("video/") == true
            }
            require(trackIndex != null) { "No decodable video track" }
            extractor.selectTrack(trackIndex)

            // Seek to PREVIOUS_SYNC so each timestamp is a real keyframe PTS, and decode
            // with OPTION_PREVIOUS_SYNC so non-key frames are never decoded (which can
            // stall on older API levels).
            val timestamps = linkedSetOf<Long>()
            for (i in 0 until maxFrames) {
                currentCoroutineContext().ensureActive()
                val requestedUs = (durationMs - 1) * 1000L * i / (maxFrames - 1)
                extractor.seekTo(requestedUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                val pts = extractor.sampleTime
                if (pts >= 0) timestamps += pts
            }
            require(timestamps.size >= MIN_FRAMES) { "Not enough distinct keyframes" }

            val frames = mutableListOf<ExtractedFrame>()
            for (pts in timestamps.sorted().take(MAX_FRAMES_PER_REQUEST)) {
                currentCoroutineContext().ensureActive()
                val bitmap = retriever.getFrameAtTime(
                    pts,
                    MediaMetadataRetriever.OPTION_PREVIOUS_SYNC,
                ) ?: continue
                val imageUrl = runCatching { encode(bitmap) }.getOrNull() ?: continue
                frames += ExtractedFrame(
                    imageUrl = imageUrl,
                    timestampText = String.format(
                        Locale.ROOT,
                        "[Video keyframe PTS %.3f s]",
                        pts / 1_000_000.0,
                    ),
                )
            }
            return if (frames.size >= MIN_FRAMES) frames else emptyList()
        } finally {
            try {
                extractor.release()
            } finally {
                retriever.release()
            }
        }
    }

    private fun encode(frame: Bitmap): String {
        val longest = maxOf(frame.width, frame.height).coerceAtLeast(1)
        val scaled = if (longest > MAX_FRAME_EDGE) {
            val ratio = MAX_FRAME_EDGE.toFloat() / longest
            Bitmap.createScaledBitmap(
                frame,
                (frame.width * ratio).toInt().coerceAtLeast(1),
                (frame.height * ratio).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            frame
        }
        try {
            val buffer = ByteArrayOutputStream()
            check(scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, buffer)) {
                "JPEG compression failed"
            }
            val payload = Base64.encodeToString(buffer.toByteArray(), Base64.NO_WRAP)
            return "data:image/jpeg;base64,$payload"
        } finally {
            if (scaled !== frame) scaled.recycle()
            frame.recycle()
        }
    }
}
