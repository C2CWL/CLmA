package com.clma.app.data.vector

/**
 * A stable 64-bit fingerprint of a piece of text.
 *
 * Deliberately not `String.hashCode()`: that is only specified to be *consistent within one
 * run of one JVM implementation*, so the same document could hash differently on the next app
 * version and the whole index would silently re-embed itself. FNV-1a is written out here in
 * ten lines rather than pulled in as a dependency because the property that matters is
 * "identical forever, identical on every platform", and every general-purpose hasher in the
 * ecosystem comes with a question mark over exactly that.
 *
 * Collisions are not a correctness risk here: a colliding pair means one unchanged document
 * is skipped when it should have been re-embedded. The next edit to either document fixes
 * that, and nothing is ever lost - the index is a derived cache.
 */
object ContentHash {

    /** FNV-1a 64-bit offset basis, as a signed Long. */
    private const val OFFSET_BASIS = -3750763034362895579L

    /** FNV-1a 64-bit prime. */
    private const val PRIME = 1099511628211L

    /**
     * Hashes [text] as UTF-8 bytes. Byte-level on purpose: two strings that differ only in
     * normalization form are different documents, and treating them as equal would skip a
     * re-embed that genuinely changed the vector.
     */
    fun of(text: String): Long {
        var hash = OFFSET_BASIS
        for (byte in text.encodeToByteArray()) {
            hash = hash xor (byte.toLong() and 0xFF)
            hash *= PRIME
        }
        return hash
    }

    /**
     * Folds a list of already-computed fingerprints into one, order-sensitively. Used for the
     * whole-document check: it answers "did anything in this document change?" without
     * re-reading the document's text, which is what makes an incremental re-index cheap.
     */
    fun fold(hashes: List<Long>): Long {
        var hash = OFFSET_BASIS
        for (value in hashes) {
            // Mix each 8-byte fingerprint in bytewise, so the fold is order-sensitive and
            // spreads as well as `of` does.
            var remaining = value
            repeat(8) {
                hash = hash xor (remaining and 0xFF)
                hash *= PRIME
                remaining = remaining ushr 8
            }
        }
        return hash
    }
}
