package com.clma.llamacpp

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.clma.ai.core.MessageRole
import com.clma.ai.ui.UIMessage
import com.clma.ai.ui.UIMessagePart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.util.Locale

/** Sparse, original-resolution keyframes, not native video or audio understanding. */
internal object LocalVideoFrames {
    private const val MAX_FRAMES = 6
    private const val MAX_DURATION_MS = 60_000L

    suspend fun expand(context: Context, messages: List<UIMessage>, temporary: MutableList<File>): List<UIMessage> =
        messages.map { message ->
            suspend fun expandParts(parts: List<UIMessagePart>): List<UIMessagePart> = buildList {
                for (part in parts) {
                    currentCoroutineContext().ensureActive()
                    when (part) {
                        is UIMessagePart.Video -> {
                            require(message.role != MessageRole.ASSISTANT) { "Assistant video history is unsupported" }
                            addAll(extract(context, part.url, temporary))
                        }
                        is UIMessagePart.Tool -> if (part.isExecuted) {
                            val output = buildList {
                                for (item in part.output) {
                                    if (item is UIMessagePart.Video) addAll(extract(context, item.url, temporary))
                                    else add(item)
                                }
                            }
                            add(part.copy(output = output))
                        } else add(part)
                        else -> add(part)
                    }
                }
            }
            message.copy(parts = expandParts(message.parts))
        }

    private suspend fun extract(context: Context, url: String, temporary: MutableList<File>): List<UIMessagePart> {
        val uri = Uri.parse(url)
        require(uri.scheme == "content" || uri.scheme == "file" || uri.scheme == null) {
            "Download the video locally before using local vision"
        }
        val retriever = MediaMetadataRetriever()
        val extractor = MediaExtractor()
        try {
            if (uri.scheme == null) {
                retriever.setDataSource(url)
                extractor.setDataSource(url)
            } else {
                retriever.setDataSource(context, uri)
                extractor.setDataSource(context, uri, null)
            }
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            require(duration != null && duration in 1L..MAX_DURATION_MS) {
                "Local video preview accepts clips up to 60 seconds; trim this video first"
            }
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            }
            require(track != null) { "No decodable video track" }
            extractor.selectTrack(track)
            val times = linkedSetOf<Long>()
            for (i in 0 until MAX_FRAMES) {
                // Use real sync-sample PTS, not the requested seek time as a fabricated timestamp.
                val requestedUs = (duration - 1) * 1000 * i / (MAX_FRAMES - 1)
                extractor.seekTo(requestedUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                val pts = extractor.sampleTime
                if (pts >= 0) times += pts
            }
            require(times.isNotEmpty()) { "Video has no accessible keyframes" }
            require(temporary.size + times.size <= 12) {
                "At most 12 video keyframes per request; send fewer clips or shorten history"
            }
            return buildList {
                add(UIMessagePart.Text("[Sparse video preview: ${times.size} keyframes; original resolution. " +
                    "Audio is NOT included. Events between frames may be missed; do not infer continuous motion. " +
                    "For unreadable text or brief events, request a trimmed clip or a full-resolution still.]"))
                for (pts in times.sorted()) {
                    currentCoroutineContext().ensureActive()
                    val bitmap = requireNotNull(retriever.getFrameAtTime(pts, MediaMetadataRetriever.OPTION_PREVIOUS_SYNC)) {
                        "Cannot decode video keyframe"
                    }
                    val file = File.createTempFile("video-frame-", ".png", context.cacheDir)
                    temporary += file
                    try {
                        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                    } finally {
                        bitmap.recycle()
                    }
                    add(UIMessagePart.Text(String.format(Locale.ROOT, "[Video keyframe PTS %.3f s]", pts / 1_000_000.0)))
                    add(UIMessagePart.Image(Uri.fromFile(file).toString()))
                }
            }
        } finally {
            try { extractor.release() } finally { retriever.release() }
        }
    }
}
