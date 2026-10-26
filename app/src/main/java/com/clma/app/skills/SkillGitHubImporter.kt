package com.clma.app.skills

import com.clma.app.data.files.SkillFrontmatterParser
import com.clma.app.data.files.SkillNameRules
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Phase 20 — install a multi-file skill straight from a GitHub repo / tree URL.
 *
 * The UI path (`SkillsVM.importSkillFromGitHub`) already does this with the Contents API,
 * but the LLM tool `skill_install_from_url` only knew the single-file raw-URL importer, so
 * pasting `github.com/owner/repo` into chat died with `html_response`. This routes the same
 * URL through codeload's zip so scripts/ and assets/ land intact, and reuses
 * [SkillZipImporter] for the extraction caps + path-traversal guards.
 *
 * Accepted shapes:
 *  - `https://github.com/owner/repo`
 *  - `https://github.com/owner/repo/tree/<branch>[/<subdir>]`
 *  - `https://github.com/owner/repo/blob/<branch>/<subdir>` (treated as the containing dir)
 *
 * No auth: public repos only, same as the single-file importer.
 */
object GitHubSkillImporter {

    data class Ref(val owner: String, val repo: String, val branch: String?, val path: String?)

    private const val MAX_ZIP_BYTES = 30L * 1024 * 1024
    private const val USER_AGENT = "CLmA/skill-importer"

    /** Null when [url] is not a github.com repo/tree URL. Public for testability. */
    fun parse(url: String): Ref? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() !in setOf("http", "https")) return null
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return null
        if (host != "github.com") return null
        val segs = uri.path.orEmpty().trim('/').split('/').filter { it.isNotBlank() }
        if (segs.size < 2) return null
        val owner = segs[0]
        val repo = segs[1].removeSuffix(".git")
        if (owner.isBlank() || repo.isBlank()) return null
        if (segs.size >= 4 && segs[2] in setOf("tree", "blob")) {
            val path = segs.drop(4).joinToString("/").takeIf { it.isNotBlank() }
            return Ref(owner, repo, segs[3], path)
        }
        return Ref(owner, repo, null, null)
    }

    /**
     * Download + extract + persist. Mirrors [SkillUrlImporter.importFromText]'s contract:
     * the skill's own `name:` wins (normalised when off-spec), `source-url:` is stamped for
     * audit, and [override] replaces the name when the caller asked for it.
     */
    fun import(
        url: String,
        override: String?,
        saver: SkillSaver,
        httpClient: OkHttpClient,
    ): SkillUrlImporter.Result {
        val ref = parse(url)
            ?: return SkillUrlImporter.Result.Err("invalid_url", "not a GitHub repository URL")

        val branch = ref.branch ?: defaultBranch(ref, httpClient)
            ?: return SkillUrlImporter.Result.Err(
                "github_resolve_failed",
                "could not resolve the default branch of ${ref.owner}/${ref.repo}",
            )

        val zip = downloadZip(ref, branch, httpClient)
            ?: return SkillUrlImporter.Result.Err(
                "github_download_failed",
                "could not download ${ref.owner}/${ref.repo}@$branch (private repo or bad branch?)",
            )

        val staging = runCatching { createStagingDir() }.getOrNull()
            ?: run {
                zip.delete()
                return SkillUrlImporter.Result.Err("io_failed", "could not create a temp directory")
            }
        try {
            val extracted = zip.inputStream().use { input ->
                SkillZipImporter.extractZipToDir(input, staging).getOrElse { error ->
                    return when (error) {
                        is SkillZipError.MissingSkillMd -> SkillUrlImporter.Result.Err(
                            "missing_skill_md",
                            "the repository does not contain a SKILL.md",
                        )
                        else -> SkillUrlImporter.Result.Err(
                            "zip_failed",
                            error.message ?: "could not extract the repository archive",
                        )
                    }
                }
            }

            // codeload zips nest under <repo>-<branch>/…; SkillZipImporter already peels
            // that off when SKILL.md is at the root. A tree URL with a subdir needs a
            // second hop so we install only that folder, not the whole monorepo.
            val root = when {
                ref.path.isNullOrBlank() -> extracted
                else -> {
                    val nested = File(extracted, ref.path)
                    if (!nested.isDirectory) {
                        return SkillUrlImporter.Result.Err(
                            "missing_subdir",
                            "path '${ref.path}' not found in ${ref.owner}/${ref.repo}",
                        )
                    }
                    val md = File(nested, "SKILL.md")
                    if (!md.isFile) {
                        return SkillUrlImporter.Result.Err(
                            "missing_skill_md",
                            "no SKILL.md under '${ref.path}'",
                        )
                    }
                    nested
                }
            }

            // Flatten to skill-root-relative paths so saveSkillFilesAtomically writes the
            // skill directory, not repo-name/SKILL.md.
            val files = LinkedHashMap<String, ByteArray>()
            root.walkTopDown().filter { it.isFile }.forEach { file ->
                val rel = file.relativeTo(root).invariantSeparatorsPath
                files[rel] = file.readBytes()
            }

            val skillMdBytes = files["SKILL.md"]
                ?: return SkillUrlImporter.Result.Err("missing_skill_md", "SKILL.md is not at the skill root")
            val skillMd = skillMdBytes.toString(Charsets.UTF_8)
            val frontmatter = SkillFrontmatterParser.parse(skillMd)
            val declared = (override?.takeIf { it.isNotBlank() } ?: frontmatter["name"])?.trim()
                ?: return SkillUrlImporter.Result.Err("missing_name", "SKILL.md has no 'name' field")
            val name = if (SkillNameRules.isValid(declared)) declared else SkillNameRules.normalize(declared)
            if (name.isEmpty()) {
                return SkillUrlImporter.Result.Err(
                    "invalid_name",
                    "skill name must be 1..${SkillNameRules.MAX_LENGTH} chars, [a-z0-9-] (got '$declared')",
                )
            }

            var finalMd = skillMd
            if (name != frontmatter["name"]?.trim()) finalMd = rewriteName(finalMd, name)
            if (frontmatter["source-url"]?.isNotBlank() != true) finalMd = stampSourceUrl(finalMd, url)
            files["SKILL.md"] = finalMd.toByteArray(Charsets.UTF_8)

            val metadata = saver.saveSkillFiles(name, files)
                ?: return SkillUrlImporter.Result.Err("save_failed", "could not write skill files")
            return SkillUrlImporter.Result.Ok(metadata, SkillUrlImporter.SkillFormat.GITHUB)
        } finally {
            runCatching { zip.delete() }
            runCatching { staging.deleteRecursively() }
        }
    }

    private fun createStagingDir(): File {
        val dir = File(System.getProperty("java.io.tmpdir") ?: "/tmp", "skill-gh-${System.nanoTime()}")
        if (!dir.mkdirs()) throw IllegalStateException("mkdir failed: ${dir.path}")
        return dir
    }

    private fun defaultBranch(ref: Ref, httpClient: OkHttpClient): String? {
        val request = Request.Builder()
            .url("https://api.github.com/repos/${ref.owner}/${ref.repo}")
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/vnd.github+json")
            .build()
        return runCatching {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                Regex("\"default_branch\"\\s*:\\s*\"([^\"]+)\"")
                    .find(response.body.string())
                    ?.groupValues?.get(1)
            }
        }.getOrNull()
    }

    private fun downloadZip(ref: Ref, branch: String, httpClient: OkHttpClient): File? {
        // `zip/<branch>` is the documented codeload form and takes slashes in branch names
        // (`feature/x`) verbatim; `zip/refs/heads/<branch>` 404s for those.
        val url = "https://codeload.github.com/${ref.owner}/${ref.repo}/zip/$branch"
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        return runCatching {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                if ((response.body.contentLength()) > MAX_ZIP_BYTES) return@use null
                val target = File.createTempFile("skill-gh-", ".zip")
                target.outputStream().use { output ->
                    response.body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > MAX_ZIP_BYTES) throw IllegalStateException("zip too large")
                            output.write(buffer, 0, read)
                        }
                    }
                }
                target
            }
        }.getOrNull()
    }

    private fun rewriteName(body: String, newName: String): String {
        if (!body.startsWith("---")) return body
        val end = body.indexOf("\r\n---", startIndex = 3).takeIf { it >= 0 }
            ?: body.indexOf("\n---", startIndex = 3).takeIf { it >= 0 }
            ?: return body
        val head = body.substring(0, end).lines().joinToString("\n") { line ->
            if (line.trimEnd('\r').startsWith("name:")) "name: \"$newName\"" else line
        }
        return head + body.substring(end)
    }

    private fun stampSourceUrl(body: String, url: String): String {
        if (!body.startsWith("---")) return body
        val end = body.indexOf("\r\n---", startIndex = 3).takeIf { it >= 0 }
            ?: body.indexOf("\n---", startIndex = 3).takeIf { it >= 0 }
            ?: return body
        val escaped = url.replace("\\", "\\\\").replace("\"", "\\\"")
        return body.substring(0, end) + "\nsource-url: \"$escaped\"" + body.substring(end)
    }

    /** Convenience client used when the caller has none (tests inject their own). */
    fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
}
