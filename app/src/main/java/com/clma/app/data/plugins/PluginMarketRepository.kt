package com.clma.app.data.plugins

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

private const val MARKET_PAGE_SIZE = 50

data class PluginMarketEntry(
    val key: String,
    val sourceKey: String,
    val sourceLabel: String,
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
    val packageKind: PluginPackageKind,
    val installability: PluginInstallability,
    val installCommand: String?,
)

data class PluginMarketSourceError(
    val source: String,
    val message: String,
)

data class PluginMarketLoadResult(
    val items: List<PluginMarketEntry>,
    val errors: List<PluginMarketSourceError> = emptyList(),
)

interface PluginMarketSourceAdapter {
    val key: String
    val label: String
    suspend fun load(query: String): List<PluginMarketEntry>
}

class PluginMarketRepository(
    private val adapters: List<PluginMarketSourceAdapter>,
) {
    suspend fun load(query: String = ""): PluginMarketLoadResult = withContext(Dispatchers.IO) {
        val results = adapters.map { adapter ->
            adapter.key to runCatching { adapter.load(query) }
        }
        val items = results.asSequence()
            .mapNotNull { (_, result) -> result.getOrNull() }
            .flatten()
            .sortedWith(
                compareByDescending<PluginMarketEntry> { it.verified }
                    .thenBy { it.sourceLabel }
                    .thenBy { it.name.lowercase() },
            )
            .distinctBy { it.key }
            .take(MARKET_PAGE_SIZE)
            .toList()
        val errors = results.mapNotNull { (key, result) ->
            result.exceptionOrNull()?.let { error ->
                PluginMarketSourceError(key, error.message ?: "unknown error")
            }
        }
        PluginMarketLoadResult(items = items, errors = errors)
    }
}

/**
 * Read-only adapter for the public DSH Go Marketplace API V2.
 *
 * DSH packages are not CLmA native APK plugins and are never passed to
 * [PluginRepository.install]. The UI exposes source links and the DSH CLI
 * command only.
 */
class DshGoMarketSourceAdapter(
    private val client: OkHttpClient,
) : PluginMarketSourceAdapter {
    override val key: String = "dsh-go"
    override val label: String = "DSH Go"

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Serializable
    private data class DshGoEnvelope(
        val data: DshGoData,
        val meta: DshGoMeta? = null,
    )

    @Serializable
    private data class DshGoData(
        val items: List<DshGoPackage> = emptyList(),
        val count: Int = 0,
    )

    @Serializable
    private data class DshGoMeta(
        @SerialName("request_id") val requestId: String? = null,
        @SerialName("registry_revision") val registryRevision: String? = null,
    )

    @Serializable
    private data class DshGoPackage(
        val key: String? = null,
        val type: String = "",
        val id: String = "",
        @SerialName("publisher_id") val publisherId: String? = null,
        val source: DshGoSource? = null,
        val metadata: DshGoMetadata? = null,
        @SerialName("latest_release") val latestRelease: DshGoRelease? = null,
        @SerialName("release_count") val releaseCount: Int = 0,
    )

    @Serializable
    private data class DshGoSource(
        val provider: String? = null,
        val repo: String? = null,
    )

    @Serializable
    private data class DshGoMetadata(
        val name: String? = null,
        val description: String? = null,
        val category: String? = null,
        val tags: List<String> = emptyList(),
        val verified: Boolean = false,
        @SerialName("repo_url") val repoUrl: String? = null,
        val homepage: String? = null,
    )

    @Serializable
    private data class DshGoRelease(
        val version: String? = null,
        val channel: String? = null,
        @SerialName("published_at") val publishedAt: String? = null,
        val yanked: Boolean = false,
        val revoked: Boolean = false,
    )

    override suspend fun load(query: String): List<PluginMarketEntry> {
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
                .addQueryParameter("limit", MARKET_PAGE_SIZE.toString())
                .build()
        }
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .get()
            .build()
        val envelope = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("DSH Go request failed: HTTP ${response.code}")
            val body = response.body?.string().orEmpty()
            runCatching { json.decodeFromString<DshGoEnvelope>(body) }
                .getOrElse { error("Invalid DSH Go response: ${it.message ?: "unknown error"}") }
        }
        return envelope.data.items
            .asSequence()
            .filter { it.id.isNotBlank() && it.type.isNotBlank() }
            .filter { it.latestRelease?.yanked != true && it.latestRelease?.revoked != true }
            .mapNotNull { it.toEntry() }
            .distinctBy { it.key }
            .take(MARKET_PAGE_SIZE)
            .toList()
    }

    private fun DshGoPackage.toEntry(): PluginMarketEntry? {
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
            key = "dsh-go:$coordinate",
            sourceKey = "dsh-go",
            sourceLabel = "DSH Go",
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
            packageKind = PluginPackageKind.DSH_GO_PACKAGE,
            installability = PluginInstallability.EXTERNAL_RUNTIME_ONLY,
            installCommand = "dsh package install $coordinate",
        )
    }
}

/**
 * Finds CLmA plugin repositories through the GitHub topic `clma-plugin`.
 * This source needs no CLmA-maintained registry; the topic is the registry.
 */
class GitHubTopicMarketSourceAdapter(
    private val client: OkHttpClient,
) : PluginMarketSourceAdapter {
    override val key: String = "github-topic"
    override val label: String = "GitHub topic:clma-plugin"

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Serializable
    private data class GitHubSearchEnvelope(
        val items: List<GitHubRepositoryItem> = emptyList(),
    )

    @Serializable
    private data class GitHubRepositoryItem(
        @SerialName("full_name") val fullName: String = "",
        @SerialName("html_url") val htmlUrl: String = "",
        val description: String? = null,
        val topics: List<String> = emptyList(),
        val owner: GitHubOwner? = null,
    )

    @Serializable
    private data class GitHubOwner(
        val login: String? = null,
    )

    override suspend fun load(query: String): List<PluginMarketEntry> {
        val term = buildString {
            append("topic:clma-plugin")
            val trimmed = query.trim()
            if (trimmed.isNotBlank()) append(' ').append(trimmed)
        }
        val url = HttpUrl.Builder()
            .scheme("https")
            .host("api.github.com")
            .addPathSegments("search/repositories")
            .addQueryParameter("q", term)
            .addQueryParameter("per_page", MARKET_PAGE_SIZE.toString())
            .addQueryParameter("sort", "updated")
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "CLmA Plugin Market")
            .get()
            .build()
        val envelope = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("GitHub topic request failed: HTTP ${response.code}")
            val body = response.body?.string().orEmpty()
            runCatching { json.decodeFromString<GitHubSearchEnvelope>(body) }
                .getOrElse { error("Invalid GitHub response: ${it.message ?: "unknown error"}") }
        }
        return envelope.items
            .asSequence()
            .filter { it.fullName.isNotBlank() }
            .map { it.toEntry() }
            .distinctBy { it.key }
            .take(MARKET_PAGE_SIZE)
            .toList()
    }

    private fun GitHubRepositoryItem.toEntry(): PluginMarketEntry {
        val name = fullName.substringAfter('/').ifBlank { fullName }
        return PluginMarketEntry(
            key = "github-topic:$fullName",
            sourceKey = "github-topic",
            sourceLabel = "GitHub topic:clma-plugin",
            type = "clma-plugin-repository",
            id = fullName,
            name = name,
            description = description.orEmpty(),
            version = null,
            verified = false,
            publisher = owner?.login?.takeIf { it.isNotBlank() }
                ?: fullName.substringBefore('/').takeIf { it.isNotBlank() },
            repoUrl = htmlUrl.takeIf { it.isNotBlank() } ?: "https://github.com/$fullName",
            tags = topics,
            coordinate = fullName,
            packageKind = PluginPackageKind.GITHUB_SOURCE,
            installability = PluginInstallability.SOURCE_ONLY,
            installCommand = null,
        )
    }
}
