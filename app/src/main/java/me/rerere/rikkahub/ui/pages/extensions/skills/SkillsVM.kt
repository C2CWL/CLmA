package me.rerere.rikkahub.ui.pages.extensions.skills

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.SkillGroup
import me.rerere.rikkahub.data.datastore.SkillGroupRules
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.SkillFrontmatterParser
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.files.SkillMetadata
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.uuid.Uuid
import me.rerere.rikkahub.skills.CatalogEntry
import me.rerere.rikkahub.skills.SkillCatalog
import me.rerere.rikkahub.skills.SkillUrlImporter
import me.rerere.rikkahub.skills.SkillZipError
import me.rerere.rikkahub.skills.SkillZipImporter
import me.rerere.rikkahub.skills.loadCatalogFromAssets
import java.util.LinkedHashMap
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import kotlin.collections.iterator

class SkillsVM(
    private val context: Context,
    private val skillManager: SkillManager,
    private val urlImporter: SkillUrlImporter,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    companion object {
        private const val TAG = "SkillsVM"
        private const val MAX_MD_BYTES = 1L * 1024 * 1024 // 1 MB cap on local .md
    }
    private val _skills = MutableStateFlow<List<SkillMetadata>>(emptyList())
    val skills = _skills.asStateFlow()

    /**
     * Phase 19D — flow-derived snapshot of currently-installed skill names. The catalog
     * sheet observes this so the "Install" / "Installed" button state stays in sync as
     * the user (or LLM) installs / deletes skills.
     */
    val installedSkillNames = _skills
        .map { list -> list.mapTo(mutableSetOf()) { it.name } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** Phase 19D — bundled catalog. Loaded lazily on first access. */
    val catalog: SkillCatalog by lazy { loadCatalogFromAssets(context) }

    /** User-created skill groups, surfaced from settings; the page renders one section each. */
    val skillGroups = settingsStore.settingsFlow
        .map { it.skillGroups }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        loadSkills()
    }

    private fun loadSkills() {
        viewModelScope.launch(Dispatchers.IO) {
            _skills.value = skillManager.listSkills()
        }
    }

    fun saveSkill(name: String, content: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = skillManager.saveSkill(name, content)
            _skills.value = skillManager.listSkills()
            withContext(Dispatchers.Main) {
                onResult(result != null)
            }
        }
    }

    fun deleteSkill(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            skillManager.deleteSkill(name)
            _skills.value = skillManager.listSkills()
            // Drop the name from any group it belonged to so no group holds a phantom.
            settingsStore.update { s ->
                s.copy(skillGroups = SkillGroupRules.removeFromGroup(s.skillGroups, name))
            }
        }
    }

    // ---- Skill groups -------------------------------------------------------

    /** Create a group named [name] holding [skillNames]; each skill leaves any previous group. */
    fun createGroup(name: String, skillNames: Set<String>) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            settingsStore.update { s ->
                val pulled = skillNames.fold(s.skillGroups) { acc, n ->
                    SkillGroupRules.removeFromGroup(acc, n)
                }
                s.copy(
                    skillGroups = pulled + SkillGroup(
                        id = Uuid.random().toString(),
                        name = trimmed,
                        skillNames = skillNames,
                    ),
                )
            }
        }
    }

    fun renameGroup(groupId: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            settingsStore.update { s ->
                s.copy(skillGroups = SkillGroupRules.renameGroup(s.skillGroups, groupId, trimmed))
            }
        }
    }

    /** Flip one group's on/off switch; a disabled group's skills stop surfacing to the model. */
    fun setGroupEnabled(groupId: String, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            settingsStore.update { s ->
                s.copy(skillGroups = SkillGroupRules.setGroupEnabled(s.skillGroups, groupId, enabled))
            }
        }
    }

    fun assignSkillToGroup(groupId: String, skillName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            settingsStore.update { s ->
                s.copy(skillGroups = SkillGroupRules.assignToGroup(s.skillGroups, groupId, skillName))
            }
        }
    }

    /**
     * Replace the membership of group [groupId] with exactly [skillNames]: current members
     * not in the new set are removed, and each newly-chosen skill is pulled out of whatever
     * group it was in (a skill has at most one home). Used by the editor dialog's Save.
     */
    fun setGroupMembers(groupId: String, skillNames: Set<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            settingsStore.update { s ->
                val current = s.skillGroups.firstOrNull { it.id == groupId }?.skillNames.orEmpty()
                var groups = s.skillGroups
                (current - skillNames).forEach { name ->
                    groups = SkillGroupRules.removeFromGroup(groups, name)
                }
                skillNames.forEach { name ->
                    groups = SkillGroupRules.assignToGroup(groups, groupId, name)
                }
                s.copy(skillGroups = groups)
            }
        }
    }

    fun removeSkillFromGroup(skillName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            settingsStore.update { s ->
                s.copy(skillGroups = SkillGroupRules.removeFromGroup(s.skillGroups, skillName))
            }
        }
    }

    /** Delete the group definition; its skills stay on disk and fall back to "ungrouped". */
    fun deleteGroup(groupId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            settingsStore.update { s ->
                s.copy(skillGroups = SkillGroupRules.deleteGroup(s.skillGroups, groupId))
            }
        }
    }

    /** Delete the group definition AND uninstall every skill it held. */
    fun deleteGroupWithSkills(groupId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val group = settingsStore.settingsFlow.value.skillGroups.firstOrNull { it.id == groupId }
                ?: return@launch
            group.skillNames.forEach { skillManager.deleteSkill(it) }
            _skills.value = skillManager.listSkills()
            settingsStore.update { s ->
                s.copy(skillGroups = SkillGroupRules.deleteGroup(s.skillGroups, groupId))
            }
        }
    }

    fun getSkillsDir() = skillManager.getSkillsDir()

    fun importSkillFromGitHub(repoUrl: String, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val info = parseGitHubUrl(repoUrl) ?: run {
                    withContext(Dispatchers.Main) { onResult(false, "Invalid GitHub repository URL") }
                    return@launch
                }

                // Collect all files recursively via GitHub Contents API
                val files = mutableListOf<Pair<String, String>>() // relativePath -> downloadUrl
                val listed = listFilesRecursively(info.owner, info.repo, info.branch, info.path, info.path, files)
                if (!listed) {
                    withContext(Dispatchers.Main) { onResult(false, "Failed to list GitHub directory contents") }
                    return@launch
                }

                val skillMdEntry = files.find { it.first == "SKILL.md" } ?: run {
                    withContext(Dispatchers.Main) { onResult(false, "No SKILL.md found in the directory") }
                    return@launch
                }

                val skillMdContent = downloadText(skillMdEntry.second) ?: run {
                    withContext(Dispatchers.Main) { onResult(false, "Failed to download SKILL.md — check the URL and your network") }
                    return@launch
                }

                val frontmatter = SkillFrontmatterParser.parse(skillMdContent)
                val name = frontmatter["name"]
                if (name.isNullOrBlank()) {
                    withContext(Dispatchers.Main) { onResult(false, "SKILL.md is missing the required 'name' field") }
                    return@launch
                }

                val fileContents = LinkedHashMap<String, String>()
                for ((relativePath, downloadUrl) in files) {
                    val content = downloadText(downloadUrl)
                    if (content == null) {
                        withContext(Dispatchers.Main) { onResult(false, "Failed to download file: $relativePath") }
                        return@launch
                    }
                    fileContents[relativePath] = content
                }

                val saved = skillManager.saveSkillFilesAtomically(name, fileContents)
                if (!saved) {
                    withContext(Dispatchers.Main) { onResult(false, "Failed to save skill files") }
                    return@launch
                }

                _skills.value = skillManager.listSkills()
                withContext(Dispatchers.Main) { onResult(true, name) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onResult(false, e.message ?: "Unknown error") }
            }
        }
    }

    /**
     * Phase 19C — install a skill from a local file picked via SAF (`OpenDocument`).
     *
     * Accepts:
     *  - `.md` / `.markdown` (or `text/markdown` MIME): read up to [MAX_MD_BYTES] of UTF-8
     *    text, then run through [SkillUrlImporter.importFromText] (same format detection
     *    + HTML guard + transcoder pipeline as the GitHub URL path).
     *  - `.zip` (or `application/zip` MIME): extract via [SkillZipImporter] to a temp dir
     *    inside the app's cache, locate the SKILL.md, copy every file inside that root
     *    into the SkillManager via [SkillManager.saveSkillFilesAtomically].
     *
     * On failure, [onResult] receives `false` + a localised-string-key (`skill_import_*`)
     * the UI looks up via stringResource. On success, [onResult] receives `true` + the
     * installed skill's name.
     */
    fun importFromLocalFile(uri: Uri, onResult: (success: Boolean, message: String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val outcome = importOne(uri)
            _skills.value = skillManager.listSkills()
            withContext(Dispatchers.Main) { onResult(outcome.first, outcome.second) }
        }
    }

    /**
     * Batch variant of [importFromLocalFile] for a multi-select pick: import each URI in turn and
     * report how many landed plus the first failure reason. Sequential on purpose — a zip import
     * extracts into a temp dir, so running them concurrently would only add memory pressure.
     */
    fun importFromLocalFiles(uris: List<Uri>, onResult: (BatchImportResult) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            var imported = 0
            val failures = mutableListOf<BatchImportFailure>()
            val seen = mutableSetOf<String>()
            for (uri in uris) {
                // Some pickers hand back the same document twice; import it once.
                if (!seen.add(uri.toString())) continue
                val (success, message) = importOne(uri)
                if (success) {
                    imported++
                } else {
                    failures += BatchImportFailure(queryDisplayName(uri), message)
                }
            }
            _skills.value = skillManager.listSkills()
            withContext(Dispatchers.Main) { onResult(BatchImportResult(imported, failures)) }
        }
    }

    /** Single-file import shared by the one-shot and batch entry points. */
    private fun importOne(uri: Uri): Pair<Boolean, String> {
        return try {
            when (detectFileType(uri)) {
                LocalFileType.Markdown -> importLocalMarkdown(uri)
                LocalFileType.Zip -> importLocalZip(uri)
                LocalFileType.Unsupported -> false to "skill_import_unsupported_file_type"
            }
        } catch (t: Throwable) {
            Log.w(TAG, "import failed for $uri", t)
            false to (t.message ?: "skill_import_unsupported_file_type")
        }
    }

    /**
     * Phase 19D — install a skill from a [CatalogEntry].
     *
     * If the entry is `is_bundled = true`, this restores it via
     * [SkillManager.reinstallBundledSkill] (a no-op when the skill was already seeded and
     * never deleted) and we return success immediately so the UI flips its row to
     * "Installed". Otherwise [CatalogEntry.sourceUrl] is fetched
     * via [SkillUrlImporter.importFromUrl] under a 30-second hard timeout — same surface
     * as the existing GitHub-URL import path, including HTML guard + format detector.
     */
    fun installFromCatalog(entry: CatalogEntry, onResult: (success: Boolean, message: String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            if (entry.isBundled) {
                // #84: the skill may have been on disk already, or the user deleted it and
                // this tap is a deliberate reinstall — reinstallBundledSkill() clears any
                // deletion record and reseeds, and is a no-op when there was nothing to clear.
                skillManager.reinstallBundledSkill(entry.name)
                _skills.value = skillManager.listSkills()
                withContext(Dispatchers.Main) { onResult(true, entry.name) }
                return@launch
            }
            val url = entry.sourceUrl
            if (url.isNullOrBlank()) {
                withContext(Dispatchers.Main) { onResult(false, "skill_catalog_install_failed") }
                return@launch
            }
            // GitHub tree URL (https://github.com/owner/repo/tree/branch/sub/path) →
            // recursive Contents API download, so multi-file skills (scripts/, assets/)
            // land intact. Anything else falls back to the single-file raw-URL importer.
            if (parseGitHubUrl(url) != null) {
                importSkillFromGitHub(url, onResult)
                return@launch
            }
            val result = withTimeoutOrNull(30_000) {
                urlImporter.importFromUrl(url)
            }
            val (ok, msg) = when (result) {
                null -> false to "skill_catalog_install_failed"
                is SkillUrlImporter.Result.Ok -> true to result.metadata.name
                is SkillUrlImporter.Result.Err -> false to result.detail
            }
            _skills.value = skillManager.listSkills()
            withContext(Dispatchers.Main) { onResult(ok, msg) }
        }
    }

    private enum class LocalFileType { Markdown, Zip, Unsupported }

    private fun detectFileType(uri: Uri): LocalFileType {
        val mime = context.contentResolver.getType(uri)?.lowercase()
        if (mime != null) {
            if (mime == "text/markdown" || mime == "text/x-markdown" || mime == "text/plain") {
                return LocalFileType.Markdown
            }
            if (mime == "application/zip" || mime == "application/x-zip-compressed") {
                return LocalFileType.Zip
            }
        }
        // Fall back to the displayed filename. Some pickers (e.g. Files by Google) don't
        // attach a MIME type for `.md` and surface it as `application/octet-stream`.
        val name = queryDisplayName(uri)?.lowercase().orEmpty()
        return when {
            name.endsWith(".md") || name.endsWith(".markdown") -> LocalFileType.Markdown
            name.endsWith(".zip") -> LocalFileType.Zip
            else -> LocalFileType.Unsupported
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, arrayOf("_display_name"), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
    }

    private fun importLocalMarkdown(uri: Uri): Pair<Boolean, String> {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            // Read up to MAX_MD_BYTES + 1 to detect overflow without materialising the
            // whole stream blindly.
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                total += n
                if (total > MAX_MD_BYTES) {
                    // Use the markdown-specific cap error, not the zip cap key.
                    return false to "skill_import_md_too_large"
                }
                out.write(buf, 0, n)
            }
            out.toByteArray()
        } ?: return false to "skill_import_unsupported_file_type"
        val text = bytes.toString(Charsets.UTF_8)
        if (text.isBlank()) {
            return false to "skill_import_empty_file"
        }
        val sourceLabel = queryDisplayName(uri) ?: "local_file"
        val result = urlImporter.importFromText(text, sourceLabel = sourceLabel)
        return when (result) {
            is SkillUrlImporter.Result.Ok -> true to result.metadata.name
            is SkillUrlImporter.Result.Err -> false to result.detail
        }
    }

    private fun importLocalZip(uri: Uri): Pair<Boolean, String> {
        // Extract into a uniquely-named temp dir under cache so we never collide with
        // another import-in-flight. We delete it on success or failure.
        val tempRoot = File(context.cacheDir, "skill-zip-import")
        tempRoot.mkdirs()
        val workDir = Files.createTempDirectory(tempRoot.toPath(), "extract-").toFile()
        try {
            val skillRoot = context.contentResolver.openInputStream(uri)?.use { input ->
                SkillZipImporter.extractZipToDir(input, workDir)
            } ?: return false to "skill_import_unsupported_file_type"
            if (skillRoot.isFailure) {
                val err = skillRoot.exceptionOrNull()
                val key = when (err) {
                    is SkillZipError.MissingSkillMd -> "skill_import_missing_skill_md"
                    is SkillZipError.PathTraversal -> "skill_import_path_traversal"
                    is SkillZipError.TooLarge -> "skill_import_zip_too_large"
                    else -> "skill_import_unsupported_file_type"
                }
                return false to key
            }
            val rootDir = skillRoot.getOrThrow()
            val skillMd = rootDir.resolve("SKILL.md").takeIf { it.exists() }
                ?: rootDir.listFiles()?.firstOrNull { it.isFile && it.name.equals("SKILL.md", ignoreCase = true) }
                ?: return false to "skill_import_missing_skill_md"
            val frontmatter = SkillFrontmatterParser.parse(skillMd.readText())
            val skillName = frontmatter["name"]?.takeIf { it.isNotBlank() }
                ?: return false to "skill_import_missing_skill_md"
            // Collect every file into a relativePath -> content map, then atomic-save.
            val files = LinkedHashMap<String, String>()
            rootDir.walkTopDown().filter { it.isFile }.forEach { f ->
                val rel = f.relativeTo(rootDir).path.replace(File.separatorChar, '/')
                files[rel] = f.readText()
            }
            val saved = skillManager.saveSkillFilesAtomically(skillName, files)
            return if (saved) true to skillName else false to "skill_import_unsupported_file_type"
        } finally {
            runCatching { workDir.deleteRecursively() }
        }
    }

    private fun listFilesRecursively(
        owner: String,
        repo: String,
        branch: String,
        dirPath: String,
        basePath: String,
        result: MutableList<Pair<String, String>>,
    ): Boolean {
        val apiUrl = "https://api.github.com/repos/$owner/$repo/contents/$dirPath?ref=$branch"
        val json = downloadText(apiUrl) ?: return false
        val array = JSONArray(json)
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            val type = item.getString("type")
            val itemPath = item.getString("path")
            val relativePath = itemPath.removePrefix("$basePath/").removePrefix(basePath)
            when (type) {
                "file" -> {
                    val downloadUrl = item.optString("download_url").takeIf { it.isNotBlank() }
                        ?: return false
                    result.add(relativePath to downloadUrl)
                }

                "dir" -> {
                    val ok = listFilesRecursively(owner, repo, branch, itemPath, basePath, result)
                    if (!ok) return false
                }
            }
        }
        return true
    }

    private data class GitHubRepoInfo(
        val owner: String,
        val repo: String,
        val branch: String,
        val path: String,
    )

    private fun parseGitHubUrl(url: String): GitHubRepoInfo? {
        val trimmed = url.trim().trimEnd('/')
        // https://github.com/owner/repo
        // https://github.com/owner/repo/tree/branch
        // https://github.com/owner/repo/tree/branch/sub/path
        val regex = Regex("""https://github\.com/([^/]+)/([^/]+)(?:/tree/([^/]+)(/.*)?)?""")
        val match = regex.matchEntire(trimmed) ?: return null
        val owner = match.groupValues[1]
        val repo = match.groupValues[2]
        val branch = match.groupValues[3].ifBlank { "HEAD" }
        val subPath = match.groupValues[4].trimStart('/')
        return GitHubRepoInfo(owner, repo, branch, subPath)
    }

    private fun downloadText(url: String): String? {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        return try {
            if (connection.responseCode == 200) connection.inputStream.bufferedReader().readText()
            else null
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * Outcome of a multi-file skill import. [failures] carries the display name (when the picker
 * exposes one) plus the same localised-string-key / free-form message the single-file path emits.
 */
data class BatchImportResult(
    val imported: Int,
    val failures: List<BatchImportFailure>,
)

data class BatchImportFailure(
    val name: String?,
    val message: String,
)
