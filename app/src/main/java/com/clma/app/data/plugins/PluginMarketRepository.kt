package com.clma.app.data.plugins

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

@Serializable
data class PluginMarketEnvelope(
    val data: PluginMarketData,
    val meta: PluginMarketMeta? = null,
)

@Serializable
data class PluginMarketData(
    val items: List<PluginMarketPackage> = emptyList(),
    val count: Int = 0,
)

@Serializable
data class PluginMarketMeta(
    @SerialName("request_id") val requestId: String? = null,
    @SerialName("registry_revision") val registryRevision: String? = null,
)

@Serializable
data class PluginMarketPackage(
    val key: String? = null,
    val type: String = "",
    val id: String = "",
    @SerialName("publisher_id") val publisherId: String? = null,
    val source: PluginMarketSource? = null,
    val metadata: PluginMarketMetadata? = null,
    @SerialName("latest_release") val latestRelease: PluginMarketRelease? = null,
    @SerialName("release_count") val releaseCount: Int = 0,
)

@Serializable
data class PluginMarketSource(
    val provider: String? = null,
    val repo: String? = null,
)

@Serializable
data class PluginMarketMetadata(
    val name: String? = null,
    val description: String? = null,
    val category: String? = null,
    val tags: List<String> = emptyList(),
    val verified: Boolean = false,
    @SerialName("repo_url") val repoUrl: String? = null,
    val homepage: String? = null,
)

@Serializable
data class PluginMarketRelease(
    val version: String? = null,
    val channel: String? = null,
    @SerialName("published_at") val publishedAt: String? = null,
    val yanked: Boolean = false,
    val revoked: Boolean = false,
)

data class PluginMarketEntry(
    val key: String,
    val type: String,
    val id: String,
    val name: String,
    val description: String,
    val version: String?,
    val verified: Boolean,
    val publisher: String?,
    val repoUrl: String?,
    val tags: List<String>,
    val coordinate: String,
    val installCommand: String,
)

/**
 * Read-only adapter for the public DSH Go Marketplace API V2.
 *
 * This is discovery only. DSH packages are not CLmA native APK plugins and are
 * never passed to [PluginRepository.install]; the UI shows source links and a
 * copyable CLI command for the local DSH runtime.
 */
class PluginMarketRepository(
    private val client: OkHttpClient,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    suspend fun load(query: String = ""): List<PluginMarketEntry> = withContext(Dispatchers.IO) {
        val url = if (query.isBlank()) {
            HttpUrl.Builder()
                .scheme("https")
                .host("dsh-go.pages.dev")
                .addPathSegments("api/v2/packages")
                .build()
        } else {
            HttpUrl.Builder()
                .scheme("https")
                .host("dsh-go.pages.dev")
                .addPathSegments("api/v2/search")
                .addQueryParameter("q", query.trim())
                .addQueryParameter("limit", "50")
                .build()
        }
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .get()
            .build()
        val envelope = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Market request failed: HTTP ${response.code}")
            val body = response.body?.string().orEmpty()
            runCatching { json.decodeFromString<PluginMarketEnvelope>(body) }
                .getOrElse { error("Invalid market response: ${it.message ?: "unknown error"}") }
        }
        envelope.data.items
            .asSequence()
            .filter { it.id.isNotBlank() && it.type.isNotBlank() }
            .filter { it.latestRelease?.yanked != true && it.latestRelease?.revoked != true }
            .mapNotNull { it.toEntry() }
            .sortedWith(compareByDescending<PluginMarketEntry> { it.verified }.thenBy { it.name.lowercase() })
            .distinctBy { it.key }
            .take(50)
            .toList()
    }

    private fun PluginMarketPackage.toEntry(): PluginMarketEntry? {
        if (id.isBlank() || type.isBlank()) return null
        val name = metadata?.name?.takeIf { it.isNotBlank() } ?: id
        val version = latestRelease?.version?.takeIf { it.isNotBlank() }
        val coordinate = buildString {
            append(type).append(':').append(id)
            if (!version.isNullOrBlank()) append('@').append(version)
        }
        val repo = source?.repo?.takeIf { it.isNotBlank() }
        val repoUrl = metadata?.repoUrl?.takeIf { it.isNotBlank() }
            ?: repo?.let { "https://github.com/$it" }
            ?: metadata?.homepage?.takeIf { it.isNotBlank() }
        return PluginMarketEntry(
            key = key?.takeIf { it.isNotBlank() } ?: coordinate,
            type = type,
            id = id,
            name = name,
            description = metadata?.description.orEmpty(),
            version = version,
            verified = metadata?.verified == true,
            publisher = publisherId?.takeIf { it.isNotBlank() }
                ?: repo?.substringBefore('/')?.takeIf { it.isNotBlank() },
            repoUrl = repoUrl,
            tags = metadata?.tags.orEmpty(),
            coordinate = coordinate,
            installCommand = "dsh package install $coordinate",
        )
    }
}
