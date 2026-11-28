package com.clma.app.utils

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Decode at most the first minute of the video soundtrack to mono PCM WAV. */
internal object VideoSpeech {
    private const val MAX_DURATION_US = 60_000_000L
    private const val MAX_PCM_BYTES = 6_000_000
    private const val CODEC_TIMEOUT_US = 10_000L
    private const val WAV_HEADER_SIZE = 44

    suspend fun extractAudio(context: Context, url: String, output: File): Boolean {
        val extractor = MediaExtractor()
        try {
            val uri = Uri.parse(url)
            when (uri.scheme) {
                "content" -> extractor.setDataSource(context, uri, null)
                "file" -> extractor.setDataSource(requireNotNull(uri.path))
                null -> extractor.setDataSource(url)
                else -> return false
            }
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return false
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val decoder = MediaCodec.createDecoderByType(requireNotNull(format.getString(MediaFormat.KEY_MIME)))
            try {
                decoder.configure(format, null, null, 0)
                decoder.start()
                return RandomAccessFile(output, "rw").use { decode(extractor, decoder, it) }
            } finally {
                runCatching { decoder.release() }
            }
        } finally {
            extractor.release()
        }
    }

    private suspend fun decode(extractor: MediaExtractor, decoder: MediaCodec, output: RandomAccessFile): Boolean {
        val firstTime = extractor.sampleTime
        if (firstTime < 0) return false
        output.seek(WAV_HEADER_SIZE.toLong())
        val info = MediaCodec.BufferInfo()
        var inputEnded = false
        var outputEnded = false
        var bytesWritten = 0
        var sampleRate = 0
        val mono = ByteArray(8_192)
        while (!outputEnded && bytesWritten < MAX_PCM_BYTES) {
            currentCoroutineContext().ensureActive()
            if (!inputEnded) {
                val index = decoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
                if (index >= 0) {
                    val input = requireNotNull(decoder.getInputBuffer(index))
                    val timestamp = extractor.sampleTime
                    val size = if (timestamp in firstTime until firstTime + MAX_DURATION_US) {
                        input.clear()
                        extractor.readSampleData(input, 0)
                    } else -1
                    inputEnded = size < 0
                    if (inputEnded) {
                        decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    } else {
                        decoder.queueInputBuffer(index, 0, size, timestamp - firstTime, 0)
                        extractor.advance()
                    }
                }
            }
            val index = decoder.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)
            if (index < 0) continue
            try {
                outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                if (info.size == 0 || info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) continue
                val format = decoder.getOutputFormat(index)
                if (format.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                    format.getInteger(MediaFormat.KEY_PCM_ENCODING) != AudioFormat.ENCODING_PCM_16BIT
                ) return false
                val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                if (rate !in 8_000..192_000 || sampleRate != 0 && rate != sampleRate) return false
                sampleRate = rate
                val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                if (channels !in 1..8) return false
                val pcm = requireNotNull(decoder.getOutputBuffer(index)).order(ByteOrder.LITTLE_ENDIAN)
                pcm.limit(info.offset + info.size)
                pcm.position(info.offset)
                val byteLimit = minOf(MAX_PCM_BYTES, sampleRate * 2 * 60)
                while (pcm.remaining() >= channels * 2 && bytesWritten < byteLimit) {
                    val samples = minOf(pcm.remaining() / (channels * 2), mono.size / 2, (byteLimit - bytesWritten) / 2)
                    for (sample in 0 until samples) {
                        var sum = 0
                        repeat(channels) { sum += pcm.short }
                        val average = sum / channels
                        mono[sample * 2] = average.toByte()
                        mono[sample * 2 + 1] = (average shr 8).toByte()
                    }
                    output.write(mono, 0, samples * 2)
                    bytesWritten += samples * 2
                }
                outputEnded = outputEnded || bytesWritten >= byteLimit
            } finally {
                decoder.releaseOutputBuffer(index, false)
            }
        }
        if (bytesWritten == 0) return false
        output.seek(0)
        output.write(wavHeader(sampleRate, bytesWritten))
        return true
    }

    private fun wavHeader(sampleRate: Int, pcmBytes: Int): ByteArray =
        ByteBuffer.allocate(WAV_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(36 + pcmBytes)
            .put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(sampleRate).putInt(sampleRate * 2)
            .putShort(2).putShort(16).put("data".toByteArray()).putInt(pcmBytes)
            .array()
}
