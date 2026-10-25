package com.clma.app.data.vector

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One embedded chunk of one document.
 *
 * `source` is the kind of thing the chunk came from (`memory`, `history`, `file`, `tool`), and
 * `doc_key` identifies the document within that source (a file name, a conversation id, a tool
 * name). Together with `chunk_index` they are the chunk's identity, which is what lets a
 * re-index replace exactly the rows it is refreshing.
 *
 * `model_id` and `dim` are stored with every row rather than assumed globally: swapping the
 * embedding model invalidates every vector at once, and recording which model produced each one
 * is what makes that a *detectable* state instead of a silent quality collapse. Search filters
 * on `model_id`, and [VectorSearchRules] reports anything it had to skip.
 */
@Entity(
    tableName = "vector_chunks",
    indices = [
        Index(value = ["source", "doc_key", "chunk_index"], unique = true),
        Index(value = ["source", "model_id"]),
    ],
)
data class VectorChunkEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo("source")
    val source: String,
    @ColumnInfo("doc_key")
    val docKey: String,
    @ColumnInfo("chunk_index")
    val chunkIndex: Int,
    @ColumnInfo("content_hash")
    val contentHash: Long,
    @ColumnInfo("model_id")
    val modelId: String,
    @ColumnInfo("dim")
    val dim: Int,
    /** The vector, little-endian float32, `dim * 4` bytes. Room maps ByteArray to BLOB. */
    @ColumnInfo("embedding")
    val embedding: ByteArray,
    /** The chunk's text, so a hit can be shown and re-read without re-reading the document. */
    @ColumnInfo("text")
    val text: String,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
) {
    // ByteArray gives this class identity equality, which is the wrong thing to compare two
    // rows by and would silently break any list operation on them. Row equality by primary key
    // is what the index code actually means.
    override fun equals(other: Any?): Boolean = this === other || (other is VectorChunkEntity && other.id == id)

    override fun hashCode(): Int = id.hashCode()
}

/** The stored fingerprints of one document, enough for [IndexSyncRules.plan]. */
data class StoredChunkRow(
    @ColumnInfo("id") val id: Long,
    @ColumnInfo("chunk_index") val chunkIndex: Int,
    @ColumnInfo("content_hash") val contentHash: Long,
    @ColumnInfo("model_id") val modelId: String,
) {
    fun toStoredChunk(): StoredChunk =
        StoredChunk(id = id, index = chunkIndex, contentHash = contentHash, modelId = modelId)
}
