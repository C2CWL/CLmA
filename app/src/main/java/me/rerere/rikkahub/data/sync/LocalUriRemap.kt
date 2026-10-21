package me.rerere.rikkahub.data.sync

import androidx.core.net.toUri
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import java.io.File

/**
 * Re-points the absolute `file:///…/files/<relative>` references carried by a backup at the
 * files directory of the install that is restoring it.
 *
 * Avatars and chat wallpapers are persisted as absolute paths, and a backup keeps those strings
 * verbatim. Importing into a build with a different applicationId — upstream ↔ fork, or
 * release ↔ debug — therefore leaves every image pointing at a directory that does not exist on
 * this device, and the avatar renders blank. Remapping on restore makes an archive portable
 * across packages and devices; a reference whose file is genuinely missing degrades to the
 * default avatar / no wallpaper instead of staying dangling.
 *
 * Pure data in, pure data out — no Android types beyond the URI conversion, so the path logic is
 * unit-testable off-device (see `LocalUriRemapTest`).
 */
object LocalUriRemap {

    private const val FILES_MARKER = "/files/"

    fun apply(settings: Settings, filesDir: File): Settings = settings.copy(
        assistants = settings.assistants.map { assistant -> assistant.remap(filesDir) },
    )

    private fun Assistant.remap(filesDir: File): Assistant = copy(
        avatar = when (val current = avatar) {
            is Avatar.Image ->
                resolve(current.url, filesDir)?.let { Avatar.Image(it) } ?: Avatar.Dummy

            else -> current
        },
        background = resolve(background, filesDir),
        darkBackground = resolve(darkBackground, filesDir),
        lightBackground = resolve(lightBackground, filesDir),
    )

    /**
     * The URI to persist, or null when the reference cannot be resolved:
     *
     * - null and non-`file://` values pass through untouched (remote URLs, content URIs);
     * - `file://…/files/<relative>` becomes `<filesDir>/<relative>` when that file is there;
     * - anything pointing at a file that is not there degrades to null.
     */
    internal fun resolve(value: String?, filesDir: File): String? {
        val relative = relativePathInFiles(value) ?: return value
        val file = File(filesDir, relative)
        return if (file.isFile) file.toUri().toString() else null
    }

    /**
     * The `<relative>` part of a `file://…/files/<relative>` reference, or null when [value] is
     * not a local file reference this object knows how to rewrite.
     */
    internal fun relativePathInFiles(value: String?): String? {
        if (value == null || !value.startsWith("file://")) return null
        val marker = value.indexOf(FILES_MARKER)
        if (marker < 0) return null
        return value.substring(marker + FILES_MARKER.length).takeIf { it.isNotEmpty() }
    }
}
