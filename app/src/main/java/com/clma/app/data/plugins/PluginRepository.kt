package com.clma.app.data.plugins

import android.content.Context
import android.net.Uri
import com.clma.app.BuildConfig
import com.clma.plugin.api.ClmaPlugin
import com.clma.plugin.api.PluginSettingsSurface
import com.clma.plugin.api.PluginTool
import dalvik.system.DexClassLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.zip.ZipFile

@Serializable
data class PluginManifest(
    val id: String,
    val name: String,
    val version: String,
    val description: String = "",
    val minHostVersion: String? = null,
    /** Fully qualified class name implementing [ClmaPlugin]. */
    val entry: String,
)

@Serializable
data class PluginRegistryEntry(
    val id: String,
    val name: String,
    val version: String,
    val description: String = "",
    val minHostVersion: String? = null,
    val path: String,
    val entryClass: String,
    val enabled: Boolean = true,
    val error: String? = null,
    val installedAt: String = "",
)

@Serializable
data class PluginRegistry(
    val plugins: List<PluginRegistryEntry> = emptyList(),
)

data class PluginState(
    val plugins: List<PluginRegistryEntry> = emptyList(),
)

data class PluginUiEntry(
    val entry: PluginRegistryEntry,
    val surface: PluginSettingsSurface,
)

/**
 * Installs and loads native Android plugins.
 *
 * Plugins are APKs copied into app-private storage via SAF. Their manifest is
 * read from `assets/clma-plugin.json`; a [DexClassLoader] instantiates the
 * declared entry class. Plugin code is trusted native code and shares the
 * host process: only install plugins from sources you trust.
 */
class PluginRepository(
    private val context: Context,
    private val json: Json,
) {
    private val root = File(context.noBackupFilesDir, "native-plugins")
    private val registryFile = File(root, "registry.json")
    private val runtime = mutableMapOf<String, ClmaPlugin>()
    private val _state = MutableStateFlow(PluginState())

    val state: StateFlow<PluginState> = _state.asStateFlow()

    init {
        root.mkdirs()
        restore()
    }

    fun enabledPluginsWithTools(): List<Pair<PluginRegistryEntry, PluginTool>> =
        _state.value.plugins
            .filter { it.enabled && it.error == null }
            .flatMap { entry ->
                runtime[entry.id]?.tools()?.map { tool -> entry to tool }.orEmpty()
            }

    fun settingsSurfaces(): List<PluginUiEntry> =
        _state.value.plugins
            .filter { it.enabled && it.error == null }
            .mapNotNull { entry ->
                runtime[entry.id]?.settingsSurface()?.let { surface -> PluginUiEntry(entry, surface) }
            }

    suspend fun install(source: Uri): PluginRegistryEntry = withContext(Dispatchers.IO) {
        val temp = File(root, "incoming-${System.currentTimeMillis()}.apk")
        try {
            val input = context.contentResolver.openInputStream(source)
                ?: error("Cannot open the selected plugin APK")
            input.use { stream -> temp.outputStream().use { stream.copyTo(it) } }

            val manifest = readManifest(temp)
            validateManifest(manifest)
            if (!hostVersionAllowed(manifest.minHostVersion)) {
                error("Plugin requires CLmA ${manifest.minHostVersion} or newer")
            }

            val apk = File(
                root,
                "${safeFilePart(manifest.id)}-${safeFilePart(manifest.version)}-${temp.length()}.apk",
            )
            if (apk.exists() && !apk.delete()) error("Cannot replace the existing plugin file")
            temp.copyTo(apk, overwrite = true)

            val entry = PluginRegistryEntry(
                id = manifest.id,
                name = manifest.name,
                version = manifest.version,
                description = manifest.description,
                minHostVersion = manifest.minHostVersion,
                path = apk.absolutePath,
                entryClass = manifest.entry,
                enabled = true,
                installedAt = System.currentTimeMillis().toString(),
            )
            val next = _state.value.plugins.filterNot { it.id == manifest.id } + entry
            commit(next)
            _state.value.plugins.first { it.id == manifest.id }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        commit(_state.value.plugins.map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    fun uninstall(id: String) {
        val entry = _state.value.plugins.firstOrNull { it.id == id } ?: return
        runCatching { File(entry.path).delete() }
        runCatching {
            File(context.codeCacheDir, "native-plugins/${safeFilePart(id)}").deleteRecursively()
        }
        commit(_state.value.plugins.filterNot { it.id == id })
    }

    private fun restore() {
        if (!registryFile.exists()) return
        val registry = runCatching {
            json.decodeFromString<PluginRegistry>(registryFile.readText())
        }.getOrNull()
        if (registry == null) {
            registryFile.delete()
            return
        }
        commit(registry.plugins.filter { File(it.path).exists() })
    }

    private fun refreshRuntime(entries: List<PluginRegistryEntry>): List<PluginRegistryEntry> {
        runtime.clear()
        return entries.map { entry ->
            val insideRoot = runCatching {
                File(entry.path).canonicalPath.startsWith(root.canonicalPath)
            }.getOrDefault(false)
            when {
                !insideRoot -> entry.copy(error = "Plugin file is outside the plugin directory")
                !entry.enabled -> entry.copy(error = null)
                else -> runCatching { load(entry) }
                    .map { plugin ->
                        runtime[entry.id] = plugin
                        entry.copy(error = null)
                    }
                    .getOrElse { entry.copy(error = it.message ?: "Unknown load error") }
            }
        }
    }

    private fun load(entry: PluginRegistryEntry): ClmaPlugin {
        val codeCache = File(
            context.codeCacheDir,
            "native-plugins/${safeFilePart(entry.id)}",
        ).apply { mkdirs() }
        val loader = DexClassLoader(
            entry.path,
            codeCache.absolutePath,
            null,
            context.classLoader,
        )
        val instance = Class.forName(entry.entryClass, true, loader)
            .getDeclaredConstructor()
            .newInstance()
        require(instance is ClmaPlugin) { "Entry class must implement ClmaPlugin" }
        return instance
    }

    private fun commit(entries: List<PluginRegistryEntry>) {
        val refreshed = refreshRuntime(entries)
        _state.value = PluginState(refreshed)
        runCatching {
            root.mkdirs()
            registryFile.writeText(json.encodeToString(PluginRegistry(refreshed)))
        }
    }

    private fun readManifest(apk: File): PluginManifest {
        val text = ZipFile(apk).use { zip ->
            val manifestEntry = zip.getEntry("assets/clma-plugin.json")
                ?: zip.getEntry("clma-plugin.json")
                ?: error("Missing assets/clma-plugin.json in plugin APK")
            zip.getInputStream(manifestEntry).use { it.readBytes().toString(Charsets.UTF_8) }
        }
        return json.decodeFromString<PluginManifest>(text)
    }

    private fun validateManifest(manifest: PluginManifest) {
        require(manifest.id.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{1,127}"))) {
            "Invalid plugin id"
        }
        require(manifest.name.isNotBlank()) { "Plugin name must not be blank" }
        require(manifest.version.isNotBlank()) { "Plugin version must not be blank" }
        require(manifest.entry.matches(Regex("[A-Za-z_][A-Za-z0-9_.]*"))) {
            "Invalid plugin entry class"
        }
    }

    private fun hostVersionAllowed(minVersion: String?): Boolean {
        if (minVersion.isNullOrBlank()) return true
        fun parse(value: String): List<Int> =
            value.split('.').mapNotNull { it.trim().toIntOrNull() }
        val required = parse(minVersion)
        if (required.isEmpty()) return true
        val host = parse(BuildConfig.VERSION_NAME)
        for (index in required.indices) {
            val h = host.getOrNull(index) ?: 0
            val r = required[index]
            if (h != r) return h > r
        }
        return true
    }

    private fun safeFilePart(value: String): String =
        value.filter { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }
            .ifEmpty { "unknown" }
}
