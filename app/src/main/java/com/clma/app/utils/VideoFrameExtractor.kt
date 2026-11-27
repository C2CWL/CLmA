package com.clma.app.utils

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream
import java.util.Locale

object VideoFrameExtractor {
    const val MAX_FRAMES_PER_REQUEST = VideoSampling.MAX_FRAMES
    private const val MAX_FRAME_EDGE = 768
    private const val PROBE_EDGE = 24

    data class ExtractedFrame(val imageUrl: String, val timestampText: String)

    /** Android returns the nearest decoded frame. Labels describe seek positions, not exact PTS. */
    suspend fun extract(
        context: Context,
        url: String,
        maxFrames: Int = MAX_FRAMES_PER_REQUEST,
        detail: LongRange? = null,
    ): List<ExtractedFrame> {
        if (maxFrames < 1) return emptyList()
        val retriever = MediaMetadataRetriever()
        return try {
            val uri = Uri.parse(url)
            when (uri.scheme) {
                "content" -> retriever.setDataSource(context, uri)
                "file" -> retriever.setDataSource(requireNotNull(uri.path))
                null -> retriever.setDataSource(url)
                else -> return emptyList()
            }
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?.takeIf { it in 1..(Long.MAX_VALUE / 1_000) } ?: return emptyList()
            val endUs = durationMs * 1_000 - 1
            val range = if (detail == null) 0L..endUs else {
                if (detail.first > endUs) return emptyList()
                detail.first..minOf(detail.last, endUs)
            }
            val times = if (detail != null) {
                VideoSampling.times(range, minOf(maxFrames, VideoSampling.DETAIL_FRAMES))
            } else {
                val count = minOf(maxFrames, VideoSampling.overviewCount(durationMs))
                val probes = VideoSampling.times(range, maxOf(4, count * 3))
                val samples = probes.mapNotNull { time ->
                    currentCoroutineContext().ensureActive()
                    val bitmap = retriever.frame(time, PROBE_EDGE) ?: return@mapNotNull null
                    try {
                        val thumbnail = Bitmap.createScaledBitmap(bitmap, PROBE_EDGE, PROBE_EDGE, true)
                        try {
                            val pixels = IntArray(PROBE_EDGE * PROBE_EDGE)
                            thumbnail.getPixels(pixels, 0, PROBE_EDGE, 0, 0, PROBE_EDGE, PROBE_EDGE)
                            VideoSampling.Sample(time, pixels)
                        } finally {
                            if (thumbnail !== bitmap) thumbnail.recycle()
                        }
                    } finally {
                        bitmap.recycle()
                    }
                }
                VideoSampling.select(samples, count)
            }
            times.take(maxFrames).mapNotNull { time ->
                currentCoroutineContext().ensureActive()
                val frame = retriever.frame(time, MAX_FRAME_EDGE) ?: return@mapNotNull null
                try {
                    val buffer = ByteArrayOutputStream()
                    if (!frame.compress(Bitmap.CompressFormat.JPEG, 80, buffer)) return@mapNotNull null
                    ExtractedFrame(
                        "data:image/jpeg;base64," + Base64.encodeToString(buffer.toByteArray(), Base64.NO_WRAP),
                        String.format(Locale.ROOT, "[Video frame near %.3f s]", time / 1_000_000.0),
                    )
                } finally {
                    frame.recycle()
                }
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            Log.w("VideoFrameExtractor", "Frame extraction failed", error)
            emptyList()
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun MediaMetadataRetriever.frame(time: Long, edge: Int): Bitmap? {
        val width = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: edge
        val height = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: edge
        val rotated = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION) in setOf("90", "270")
        val ratio = minOf(1.0, edge.toDouble() / maxOf(width, height, 1))
        val scaledWidth = ((if (rotated) height else width) * ratio).toInt().coerceAtLeast(1)
        val scaledHeight = ((if (rotated) width else height) * ratio).toInt().coerceAtLeast(1)
        val option = MediaMetadataRetriever.OPTION_CLOSEST
        if (Build.VERSION.SDK_INT >= 27) {
            return getScaledFrameAtTime(time, option, scaledWidth, scaledHeight)
        }
        val bitmap = getFrameAtTime(time, option) ?: return null
        val scaled = Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }
}
