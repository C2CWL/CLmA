package com.clma.app.data.vector

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Turns an embedding into bytes for Room and back.
 *
 * Little-endian float32, written explicitly rather than via a platform default: the bytes end up
 * in a database file that outlives the process (and can be copied between a phone and a desktop
 * through a backup), and "the host is big-endian" is exactly the kind of assumption that works
 * everywhere until it silently produces garbage vectors somewhere it is not tested.
 */
object EmbeddingCodec {

    const val BYTES_PER_FLOAT = 4

    fun encode(vector: FloatArray): ByteArray =
        ByteBuffer.allocate(vector.size * BYTES_PER_FLOAT)
            .order(ByteOrder.LITTLE_ENDIAN)
            .also { buffer -> vector.forEach { buffer.putFloat(it) } }
            .array()

    /**
     * Reads [bytes] back. Throws [IllegalArgumentException] when the length is not a whole number
     * of floats - a truncated blob means something wrote a partial row, and a silently short
     * vector would poison every comparison it takes part in.
     */
    fun decode(bytes: ByteArray): FloatArray {
        require(bytes.size % BYTES_PER_FLOAT == 0) {
            "embedding blob is ${bytes.size} bytes, not a whole number of floats"
        }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / BYTES_PER_FLOAT) { buffer.float }
    }
}
