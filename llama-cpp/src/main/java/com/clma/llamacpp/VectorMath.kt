package com.clma.llamacpp

/**
 * The vector arithmetic a semantic index is built on, kept apart from anything that loads a
 * model so it can be unit-tested on the JVM (which is also how it gets into CI, since the
 * `:llama-cpp` module's tests run there).
 *
 * Everything here assumes dense `FloatArray`s of the same length. A length mismatch is a bug
 * at the call site - comparing vectors from two different models, or a stale index built
 * against a model that has since been swapped - so it throws rather than returning a
 * plausible-looking partial score.
 */
object VectorMath {

    /**
     * Scales [vector] to unit length, returning a new array.
     *
     * An all-zero vector is returned as zeros rather than divided by zero: it means the model
     * produced nothing meaningful for this input, and a NaN-riddled vector would poison every
     * later comparison instead of simply scoring 0 against everything.
     */
    fun l2Normalize(vector: FloatArray): FloatArray {
        var sum = 0.0
        for (value in vector) sum += value.toDouble() * value.toDouble()
        val norm = kotlin.math.sqrt(sum).toFloat()
        if (norm == 0f || !norm.isFinite()) return FloatArray(vector.size)
        return FloatArray(vector.size) { vector[it] / norm }
    }

    /** Dot product. Throws [IllegalArgumentException] when the lengths differ. */
    fun dot(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "vector length mismatch: ${a.size} vs ${b.size}" }
        var sum = 0.0
        for (i in a.indices) sum += a[i].toDouble() * b[i].toDouble()
        return sum.toFloat()
    }

    /**
     * Cosine similarity, computed on the raw vectors: normalising first is not required, and
     * doing it here means a caller cannot forget to.
     *
     * Returns 0 when either side is all zeros, which is the honest answer - the two are
     * unrelated as far as this representation can tell.
     */
    fun cosine(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "vector length mismatch: ${a.size} vs ${b.size}" }
        var dotProduct = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            dotProduct += a[i].toDouble() * b[i].toDouble()
            normA += a[i].toDouble() * a[i].toDouble()
            normB += b[i].toDouble() * b[i].toDouble()
        }
        if (normA == 0.0 || normB == 0.0) return 0f
        return (dotProduct / (kotlin.math.sqrt(normA) * kotlin.math.sqrt(normB))).toFloat()
    }
}
