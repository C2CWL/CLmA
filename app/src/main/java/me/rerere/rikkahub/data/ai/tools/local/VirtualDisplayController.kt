package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Build
import android.util.Log
import android.view.Surface

/**
 * Manages virtual display lifecycle from the app process (no Shizuku/root needed for creation).
 *
 * Each virtual display is created with an [ImageReader]-backed [Surface] so the framework has a
 * valid render target. The display's content is readable via [captureFrame] (the `take_screenshot`
 * tool routes virtual-display captures here, because
 * `AccessibilityService.takeScreenshot(displayId)` returns `ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY`
 * for any display other than `Display.DEFAULT_DISPLAY` — a platform limitation verified by CTS).
 *
 * Displays are created with [PREFERRED_FLAGS] (PUBLIC + TRUSTED + SUPPORTS_TOUCH) so that the
 * accessibility service can see the windows on them and touch/launch requests are allowed.
 * A plain `flags = 0` display is private to this process: the a11y service then reports
 * `no_window_on_display` for it. If the platform refuses the flags (SecurityException /
 * IllegalArgumentException on some OEM builds), creation falls back to `flags = 0` so the
 * feature keeps working in blind mode; the flags actually used are reported by [list].
 *
 * Input injection and app launching on the virtual display require shell privileges and go
 * through Shizuku (`input -d <id>`, `am start --display <id>`) — see [VirtualDisplayTools].
 *
 * Thread-safety: all public methods are synchronized on the object's internal lock.
 * Created displays survive until [destroy] is called or the process dies.
 */
object VirtualDisplayController {

    private const val TAG = "VirtualDisplayCtrl"

    /**
     * Flags requested for every display this controller creates.
     *
     * - `PUBLIC` (1, public API): the display is visible to other processes. Without it the
     *   accessibility service never receives its windows, which is exactly the
     *   `no_window_on_display` failure.
     * - `SUPPORTS_TOUCH` (1 << 6, `@hide` in AOSP): the display accepts touch input.
     * - `TRUSTED` (1 << 10, `@hide`, API 33+): activities that require a trusted display may
     *   run here.
     *
     * The two `@hide` constants are written as literals because they are not part of the
     * public SDK (`DisplayManager` only exposes PUBLIC/PRESENTATION/SECURE/OWN_CONTENT_ONLY/
     * AUTO_MIRROR). They are stable AOSP values — see `DisplayManager.VIRTUAL_DISPLAY_FLAG_*`.
     */
    private val PREFERRED_FLAGS: Int
        get() = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
            FLAG_SUPPORTS_TOUCH or
            (if (Build.VERSION.SDK_INT >= 33) FLAG_TRUSTED else 0)

    /** `DisplayManager.VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH` — hidden in the public SDK. */
    private const val FLAG_SUPPORTS_TOUCH = 1 shl 6

    /** `DisplayManager.VIRTUAL_DISPLAY_FLAG_TRUSTED` — hidden in the public SDK, API 33+. */
    private const val FLAG_TRUSTED = 1 shl 10

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
        /** Flags the display was actually created with (0 when the platform refused [PREFERRED_FLAGS]). */
        val flags: Int,
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
            val (display, flags) = createWithFallback(dm, name, alignedW, alignedH, densityDpi, surface)
            val id = display.display.displayId
            displays[id] = DisplayEntry(display, imageReader, name, alignedW, alignedH, densityDpi, flags)
            Log.i(TAG, "Created virtual display id=$id ${alignedW}x${alignedH} density=$densityDpi flags=$flags")
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
                flags = e.flags,
            )
        }
    }

    /** True if a display with the given id is managed by this controller. */
    fun contains(displayId: Int): Boolean = synchronized(lock) {
        displayId in displays
    }

    /**
     * Capture the latest rendered frame from a virtual display's [ImageReader].
     *
     * Returns a software [Bitmap] (ARGB_8888) sized to the display, or null when the display
     * is unknown or no frame has been rendered yet (callers may retry after a short delay).
     * The caller owns the bitmap and must [recycle] it when done.
     */
    fun captureFrame(displayId: Int): Bitmap? = synchronized(lock) {
        val entry = displays[displayId] ?: return null
        val image = try {
            entry.imageReader.acquireLatestImage()
        } catch (e: Exception) {
            Log.w(TAG, "acquireLatestImage failed: ${e.message}")
            null
        } ?: return null
        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            // ImageReader rowStride may include padding beyond the visible width.
            val rowPadding = rowStride - pixelStride * entry.width
            val paddedWidth = entry.width + rowPadding / pixelStride
            val bitmap = Bitmap.createBitmap(paddedWidth, entry.height, Bitmap.Config.ARGB_8888)
            buffer.rewind()
            bitmap.copyPixelsFromBuffer(buffer)
            // Crop away the padding so the caller gets exactly the display dimensions.
            if (paddedWidth != entry.width) {
                Bitmap.createBitmap(bitmap, 0, 0, entry.width, entry.height).also { bitmap.recycle() }
            } else {
                bitmap
            }
        } finally {
            image.close()
        }
    }

    /**
     * Creates the display with [PREFERRED_FLAGS], falling back to `flags = 0` when the platform
     * rejects them. Returns the display and the flags actually used.
     */
    private fun createWithFallback(
        dm: DisplayManager,
        name: String,
        width: Int,
        height: Int,
        densityDpi: Int,
        surface: Surface,
    ): Pair<VirtualDisplay, Int> {
        val preferred = PREFERRED_FLAGS
        return try {
            createVirtualDisplay(dm, name, width, height, densityDpi, surface, preferred) to preferred
        } catch (t: Throwable) {
            // Some OEM builds reject PUBLIC/TRUSTED for app-created displays. Losing the flags
            // only costs a11y visibility — the display itself still works, so fall back rather
            // than failing the whole tool call.
            Log.w(TAG, "createVirtualDisplay(flags=$preferred) rejected, retrying with flags=0", t)
            createVirtualDisplay(dm, name, width, height, densityDpi, surface, 0) to 0
        }
    }

    private fun createVirtualDisplay(
        dm: DisplayManager,
        name: String,
        width: Int,
        height: Int,
        densityDpi: Int,
        surface: Surface,
        flags: Int,
    ): VirtualDisplay {
        return if (Build.VERSION.SDK_INT >= 33) {
            val config = android.hardware.display.VirtualDisplayConfig.Builder(name, width, height, densityDpi)
                .setSurface(surface)
                .setFlags(flags)
                .build()
            dm.createVirtualDisplay(config)
                ?: throw RuntimeException("createVirtualDisplay returned null")
        } else {
            @Suppress("DEPRECATION")
            dm.createVirtualDisplay(name, width, height, densityDpi, surface, flags)
                ?: throw RuntimeException("createVirtualDisplay returned null")
        }
    }

    data class DisplayInfo(
        val displayId: Int,
        val name: String,
        val width: Int,
        val height: Int,
        val densityDpi: Int,
        /** Flags the display was created with; 0 means the preferred flags were refused. */
        val flags: Int,
    )

    sealed class Result<out T> {
        data class Ok<T>(val value: T) : Result<T>()
        data class Error(val code: String, val message: String) : Result<Nothing>()
    }
}