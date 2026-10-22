package me.rerere.rikkahub.reliability

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for the version comparator + the GitHub → update-card mapping. We don't
 * fire real HTTP — [GitHubReleaseChecker.check] is reach-out + parse, exercised end-to-end
 * by manual invocation via the `check_app_updates` LLM tool. The comparator and the mapper
 * are the parts with non-trivial logic worth pinning.
 */
class GitHubReleaseCheckerTest {

    private val checker = GitHubReleaseChecker(OkHttpClient())

    @Test fun `agent revision bump is newer`() {
        assertTrue(checker.isNewer("2.1.15-agent.1", "2.1.15-agent.0"))
        assertTrue(checker.isNewer("v2.1.15-agent.1", "2.1.15-agent.0"))
    }

    @Test fun `patch bump is newer`() {
        assertTrue(checker.isNewer("2.1.16-agent.0", "2.1.15-agent.0"))
        assertTrue(checker.isNewer("2.1.16-agent.0", "2.1.15-agent.99"))
    }

    @Test fun `minor bump is newer regardless of patch and agent`() {
        assertTrue(checker.isNewer("2.2.0-agent.0", "2.1.99-agent.99"))
    }

    @Test fun `major bump is newer regardless of all else`() {
        assertTrue(checker.isNewer("3.0.0-agent.0", "2.99.99-agent.99"))
    }

    @Test fun `same version is not newer`() {
        assertFalse(checker.isNewer("2.1.15-agent.0", "2.1.15-agent.0"))
        assertFalse(checker.isNewer("v2.1.15-agent.0", "2.1.15-agent.0"))
    }

    @Test fun `older version is not newer`() {
        assertFalse(checker.isNewer("2.1.14-agent.99", "2.1.15-agent.0"))
        assertFalse(checker.isNewer("2.1.15-agent.0", "2.1.15-agent.1"))
    }

    @Test fun `missing agent suffix treated as agent_0`() {
        assertTrue(checker.isNewer("2.1.16", "2.1.15-agent.0"))
        assertFalse(checker.isNewer("2.1.15", "2.1.15-agent.0"))
    }

    @Test fun `unparseable strings fail safe to false`() {
        assertFalse(checker.isNewer("totally bogus", "2.1.15-agent.0"))
        assertFalse(checker.isNewer("2.1.15-agent.0", "totally bogus"))
    }

    @Test fun `partial version comparator works`() {
        assertTrue(checker.isNewer("3", "2.99.99-agent.99"))
        assertFalse(checker.isNewer("2", "2.0.0-agent.0"))
    }

    // --- the fork's own `-pure.N` schema ------------------------------------------------

    @Test fun `pure revision bump is newer`() {
        assertTrue(checker.isNewer("2.5.1-pure.7", "2.5.1-pure.6"))
        assertTrue(checker.isNewer("v2.5.1-pure.7", "2.5.1-pure.6"))
    }

    @Test fun `the shipped tag is not newer than itself`() {
        // The card compares the latest tag against BuildConfig.VERSION_NAME; when they are
        // the same string nothing must be offered, or every launch would nag.
        assertFalse(checker.isNewer("2.5.1-pure.7", "2.5.1-pure.7"))
        assertFalse(checker.isNewer("v2.5.1-pure.7", "2.5.1-pure.7"))
    }

    @Test fun `upstream core bump outranks a pure revision`() {
        assertTrue(checker.isNewer("2.6.0-pure.0", "2.5.1-pure.9"))
        assertFalse(checker.isNewer("2.5.1-pure.9", "2.6.0-pure.0"))
    }

    @Test fun `flavour rename keeps comparing`() {
        // `-agent.N` (older name) vs `-pure.N` (current) — only the trailing integer counts.
        assertTrue(checker.isNewer("2.5.1-pure.1", "2.5.1-agent.0"))
    }

    // --- GitHub release → update-card contract ------------------------------------------

    @Test fun `release maps onto the update card contract`() {
        val info = GitHubReleaseChecker.Release(
            tag_name = "v2.5.1-pure.7",
            published_at = "2026-10-06T13:13:33Z",
            body = "changelog",
            assets = listOf(
                GitHubReleaseChecker.Asset(
                    name = "app-arm64.apk",
                    size = 50_775_526,
                    browser_download_url = "https://example.test/arm64.apk",
                )
            ),
        ).toUpdateInfo()

        assertEquals("2.5.1-pure.7", info.version)
        assertEquals("2026-10-06T13:13:33Z", info.publishedAt)
        assertEquals("changelog", info.changelog)
        assertEquals(1, info.downloads.size)
        assertEquals("app-arm64.apk", info.downloads.single().name)
        assertEquals("https://example.test/arm64.apk", info.downloads.single().url)
        assertEquals("48.4 MB", info.downloads.single().size)
    }

    @Test fun `asset size formatting is human readable`() {
        assertEquals("", formatAssetSize(0))
        assertEquals("1.0 MB", formatAssetSize(1_048_576))
        assertEquals("512 KB", formatAssetSize(524_288))
    }
}
