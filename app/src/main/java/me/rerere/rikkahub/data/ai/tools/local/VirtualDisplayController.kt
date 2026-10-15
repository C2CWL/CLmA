package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.util.Log
import android.view.Surface

/**
 * Manages virtual display lifecycle from the app process (no Shizuku/root needed for creation).
 *
 * Each virtual display is created with an [ImageReader]-backed [Surface] so the framework has a
 * valid render target. The display's content is readable via
 * [me.rerere.rikkahub.service.RikkaAccessibilityService.captureScreenshot] (the existing
 * `take_screenshot` tool — pass `display_id`).
 *
 * Input injection and app launching on the virtual display require shell privileges and go
 * through Shizuku (`input -d <id>`, `am start --display <id>`) — see [VirtualDisplayTools].
 *
 * Thread-safety: all public methods are synchronized on the object's internal lock.
 * Created displays survive until [destroy] is called or the process dies.
 */
object VirtualDisplayController {

    private const val TAG = "VirtualDisplayCtrl"

    /** Displays created by this controller, keyed by display id. */
    private val displays = mutableMapOf<Int, DisplayEntry>()

    /** Lock for [displays]. */
    private val lock = Any()

    /** An entry holding the VirtualDisplay and its ImageReader (for Surface lifecycle). */
    private class DisplayEntry(
        val display: VirtualDisplay,
        val imageReader: ImageReader,
        val name: String,
        val width: Int,
        val height: Int,
        val densityDpi: Int,
    )

    /**
     * Create a virtual display. Width/height are aligned to 16 (MediaCodec requirement; also
     * avoids odd-sized buffers on some GPUs). Returns the display id, or an error string.
     */
    fun create(context: Context, width: Int, height: Int, densityDpi: Int): Result<Int> = synchronized(lock) {
        if (width < 1 || height < 1) {
            return Result.Error("invalid_dimensions", "width and height must be >= 1")
        }
        if (densityDpi < 1 || densityDpi > 640) {
            return Result.Error("invalid_density", "density must be between 1 and 640")
        }
        // Align to 16 (avoids IllegalArgumentException on some GPU/SurfaceFlinger paths).
        val alignedW = (width + 15) and 15.inv()
        val alignedH = (height + 15) and 15.inv()
        val name = "RikkaHub-VD-${System.currentTimeMillis()}"
        return try {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val imageReader = ImageReader.newInstance(alignedW, alignedH, 0x2 /* PixelFormat.RGBA_8888 */, 2)
            val surface = imageReader.surface
            val display = createVirtualDisplay(dm, name, alignedW, alignedH, densityDpi, surface)
            val id = display.display.displayId
            displays[id] = DisplayEntry(display, imageReader, name, alignedW, alignedH, densityDpi)
            Log.i(TAG, "Created virtual display id=$id ${alignedW}x${alignedH} density=$densityDpi")
            Result.Ok(id)
        } catch (t: Throwable) {
            Log.e(TAG, "createVirtualDisplay failed", t)
            Result.Error("create_failed", t.message ?: t::class.java.simpleName)
        }
    }

    /**
     * Destroy a virtual display by id. Returns true if found and destroyed.
     */
    fun destroy(displayId: Int): Boolean = synchronized(lock) {
        val entry = displays.remove(displayId)
        if (entry == null) return false
        runCatching { entry.display.release() }
        runCatching { entry.imageReader.close() }
        Log.i(TAG, "Destroyed virtual display id=$displayId")
        true
    }

    /** Destroy all displays. Called on process teardown. */
    fun destroyAll() = synchronized(lock) {
        displays.keys.toList().forEach { id ->
            destroy(id)
        }
    }

    /** List all managed virtual displays. */
    fun list(): List<DisplayInfo> = synchronized(lock) {
        displays.values.map { e ->
            DisplayInfo(
                displayId = e.display.display.displayId,
                name = e.name,
                width = e.width,
                height = e.height,
                densityDpi = e.densityDpi,
            )
        }
    }

    /** True if a display with the given id is managed by this controller. */
    fun contains(displayId: Int): Boolean = synchronized(lock) {
        displayId in displays
    }

    private fun createVirtualDisplay(
        dm: DisplayManager,
        name: String,
        width: Int,
        height: Int,
        densityDpi: Int,
        surface: Surface,
    ): VirtualDisplay {
        return if (android.os.Build.VERSION.SDK_INT >= 33) {
            val config = android.hardware.display.VirtualDisplayConfig.Builder(name, width, height, densityDpi)
                .setSurface(surface)
                .build()
            dm.createVirtualDisplay(config)
                ?: throw RuntimeException("createVirtualDisplay returned null")
        } else {
            @Suppress("DEPRECATION")
            dm.createVirtualDisplay(name, width, height, densityDpi, surface, 0)
                ?: throw RuntimeException("createVirtualDisplay returned null")
        }
    }

    data class DisplayInfo(
        val displayId: Int,
        val name: String,
        val width: Int,
        val height: Int,
        val densityDpi: Int,
    )

    sealed class Result<out T> {
        data class Ok<T>(val value: T) : Result<T>()
        data class Error(val code: String, val message: String) : Result<Nothing>()
    }
}
