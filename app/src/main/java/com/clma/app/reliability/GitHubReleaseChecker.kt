package me.rerere.rikkahub.reliability

import android.util.Log
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.utils.UpdateDownload
import me.rerere.rikkahub.utils.UpdateInfo
import okhttp3.OkHttpClient
import okhttp3.Request

private const val TAG = "GHReleaseChecker"

/**
 * Checks GitHub Releases for the latest tag of [DEFAULT_REPO] and compares it against the
 * locally-installed [BuildConfig.VERSION_NAME]. Pure HTTP — no caching, no scheduler, no UI.
 * Callers decide when to invoke.
 *
 * Two consumers, one round-trip each:
 *   - [check] → [CheckResult], backing the `check_app_updates` LLM tool.
 *   - [fetchUpdateInfo] → [UpdateInfo], the shape the in-app update card renders and the
 *     same contract upstream RikkaHub serves from its hosted `UPDATE_API_URL`.
 *
 * Tag schema: every release on this fork ships as `vX.Y.Z-pure.N`, where `vX.Y.Z` is the
 * upstream RikkaHub version this fork is built on and `N` is the Pure revision. The
 * newer-than comparator is lexicographic by (X, Y, Z, N); the flavour label itself is
 * ignored, so the older `-agent.N` spelling still compares correctly.
 */
class GitHubReleaseChecker(
    private val client: OkHttpClient,
    private val repo: String = DEFAULT_REPO,
) {

    private val json = Json { ignoreUnknownKeys = true }

    private val latestUrl: String get() = "https://api.github.com/repos/$repo/releases/latest"

    @Serializable
    data class Asset(
        val name: String = "",
        val size: Long = 0,
        val browser_download_url: String = "",
    )

    @Serializable
    data class Release(
        val tag_name: String = "",
        val name: String = "",
        val html_url: String = "",
        val published_at: String = "",
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        val body: String = "",
        val assets: List<Asset> = emptyList(),
    )

    sealed class CheckResult {
        data class Available(val current: String, val latest: Release) : CheckResult()
        data class UpToDate(val current: String, val latest: Release) : CheckResult()
        data class Failed(val message: String) : CheckResult()
    }

    suspend fun check(): CheckResult = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(latestUrl)
            .get()
            .addHeader("Accept", "application/vnd.github+json")
            .addHeader("X-GitHub-Api-Version", "2022-11-28")
            .addHeader("User-Agent", "rikkahub-agent/${BuildConfig.VERSION_NAME}")
            .build()
        val response = try {
            client.newCall(req).execute()
        } catch (t: Throwable) {
            Log.w(TAG, "GitHub release fetch failed", t)
            return@withContext CheckResult.Failed("network error: ${t.message ?: t.javaClass.simpleName}")
        }
        response.use { resp ->
            if (!resp.isSuccessful) {
                return@withContext CheckResult.Failed("github responded ${resp.code}")
            }
            val body = resp.body.string()
            val release = try {
                json.decodeFromString<Release>(body)
            } catch (t: Throwable) {
                return@withContext CheckResult.Failed("could not parse github response: ${t.message ?: t.javaClass.simpleName}")
            }
            val current = BuildConfig.VERSION_NAME
            val latest = release.tag_name.removePrefix("v")
            return@withContext if (isNewer(latest, current)) {
                CheckResult.Available(current, release)
            } else {
                CheckResult.UpToDate(current, release)
            }
        }
    }

    /**
     * The in-app update card's data source: the same HTTP call as [check], reshaped into the
     * upstream [UpdateInfo] contract so the card + its "remind me later" plumbing work
     * unchanged. Throws on failure (network / non-2xx / parse) rather than reporting
     * "up to date", so a broken check is visible instead of silently hiding an update.
     */
    suspend fun fetchUpdateInfo(): UpdateInfo = when (val result = check()) {
        is CheckResult.Available -> result.latest.toUpdateInfo()
        is CheckResult.UpToDate -> result.latest.toUpdateInfo()
        is CheckResult.Failed -> throw IllegalStateException(result.message)
    }

    /**
     * True if [latestRaw] is strictly newer than [currentRaw]. Both expected in the
     * `X.Y.Z-pure.N` shape; missing components default to 0 so partial / older formats
     * still compare. Returns false on parse error to fail safe (no spurious update prompt).
     */
    fun isNewer(latestRaw: String, currentRaw: String): Boolean {
        val latest = parse(latestRaw) ?: return false
        val current = parse(currentRaw) ?: return false
        return compareLists(latest, current) > 0
    }

    private fun parse(raw: String): IntArray? {
        // Accepts `2.5.1-pure.7`, `v2.5.1-pure.7`, `2.1.15-agent.0`, or just `2.5.1`.
        val cleaned = raw.removePrefix("v").trim()
        if (cleaned.isEmpty()) return null
        val parts = cleaned.split('-')
        val core = parts[0].split('.').mapNotNull { it.toIntOrNull() }
        if (core.size !in 1..3) return null
        val revision = if (parts.size > 1) {
            // Any `-<flavour>.<N>` tail — only the trailing integer matters.
            val tail = parts.drop(1).joinToString("-")
            tail.split('.').lastOrNull()?.toIntOrNull() ?: 0
        } else 0
        return IntArray(4).also {
            it[0] = core.getOrNull(0) ?: 0
            it[1] = core.getOrNull(1) ?: 0
            it[2] = core.getOrNull(2) ?: 0
            it[3] = revision
        }
    }

    private fun compareLists(a: IntArray, b: IntArray): Int {
        for (i in 0..3) {
            val cmp = a[i].compareTo(b[i])
            if (cmp != 0) return cmp
        }
        return 0
    }

    companion object {
        /** The fork's own repository — releases here are the ones this build can install. */
        const val DEFAULT_REPO = "wuyhong715/rikkahub-agent-pure"
    }
}

/**
 * Maps a GitHub release onto upstream's [UpdateInfo]. Pure — no Android, no HTTP — so it can
 * be unit-tested on a bare JVM (see GitHubReleaseCheckerTest).
 */
fun GitHubReleaseChecker.Release.toUpdateInfo(): UpdateInfo = UpdateInfo(
    version = tag_name.removePrefix("v"),
    publishedAt = published_at,
    changelog = body,
    downloads = assets.map { asset ->
        UpdateDownload(
            name = asset.name,
            url = asset.browser_download_url,
            size = formatAssetSize(asset.size),
        )
    },
)

/** `50775526` → `48.4 MB`. Cosmetic — the card shows it under the file name. */
internal fun formatAssetSize(bytes: Long): String {
    if (bytes <= 0) return ""
    val mb = bytes / 1_048_576.0
    return if (mb >= 1) {
        String.format(Locale.US, "%.1f MB", mb)
    } else {
        String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    }
}
