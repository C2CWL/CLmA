package com.clma.ai.provider.providers.openai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The OpenAI-compatible `/embeddings` shape, in both directions.
 *
 * Kept apart from the call - [EmbeddingsAPI] does the socket, this does the bytes - and free of
 * anything Android, because this is the half that fails *silently*: a request the provider quietly
 * reshapes, or a response whose vectors come back in a different order than they were asked for,
 * produces an index that is wrong rather than one that errors. Everything else about a cloud
 * embedding - which key, which window, which model the user meant - is decided above this file;
 * what is decided here is the wire format, and it has a test.
 */
object EmbeddingsRules {

    /** The path appended to a provider's `baseUrl`. */
    const val PATH = "/embeddings"

    /**
     * The request body: the model, and the inputs as an array.
     *
     * Deliberately nothing else. The optional knobs of the OpenAI schema (`encoding_format`,
     * `dimensions`, `user`) are left out because implementations differ on which of them they
     * accept, and a request one of them rejects with a 400 is a far louder failure than a default
     * every one of them honours anyway - `float` is what this client reads back regardless.
     */
    fun requestBody(modelId: String, inputs: List<String>): JsonObject = buildJsonObject {
        put("model", modelId)
        putJsonArray("input") { inputs.forEach { add(it) } }
    }

    /**
     * The vectors, in the order of the inputs that were sent.
     *
     * `index` is honoured rather than assumed to be the position: the schema says a response may
     * arrive in any order, and a response that was reordered without being re-sorted would label
     * every vector with the wrong passage - the one failure mode of this feature that no later
     * step could detect.
     */
    fun parseVectors(body: String): List<FloatArray> {
        val root = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: error("the embedding response is not a JSON object: ${body.take(200)}")

        val data = root["data"] as? JsonArray
            ?: error(errorMessageOf(root) ?: "the embedding response has no `data` field")

        val vectors = data.mapIndexed { position, element ->
            val entry = runCatching { element.jsonObject }.getOrNull()
                ?: error("entry $position of the embedding response is not an object")
            val index = entry["index"]?.jsonPrimitive?.intOrNull ?: position
            val values = entry["embedding"] as? JsonArray
                ?: error("entry $index of the embedding response has no `embedding`")
            index to FloatArray(values.size) { i ->
                values[i].jsonPrimitive.floatOrNull
                    ?: error("entry $index of the embedding response is not numeric at position $i")
            }
        }

        if (vectors.isEmpty()) error("the embedding response carried no vectors")

        // A batch whose widths disagree is not one model's output. Stopping here keeps the mixed
        // widths out of the index, where they would surface as a search that quietly returns
        // nothing rather than as an error anybody could act on.
        val width = vectors.first().second.size
        vectors.firstOrNull { it.second.size != width }?.let { (index, vector) ->
            error("the embedding response mixed widths: entry $index has ${vector.size}, expected $width")
        }

        return vectors.sortedBy { it.first }.map { it.second }
    }

    /** The provider's own words when it refuses, which beat anything this file could invent. */
    private fun errorMessageOf(root: JsonObject): String? {
        val error = root["error"]
        val message = (error as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull
        return message ?: error?.jsonPrimitive?.contentOrNull
    }
}
