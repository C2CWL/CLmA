package com.clma.app.data.ai.tools.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.Surface
import com.clma.app.virtualdisplay.VirtualDisplayLauncher

/**
 * Manages virtual display lifecycle, delegating to a privileged daemon process via Shizuku/Root
 * (Aether agent mode approach) so displays carry privileged system flags (FLAG_PUBLIC, FLAG_TRUSTED,
 * FLAG_OWN_FOCUS, FLAG_SUPPORTS_TOUCH, etc.) needed to run arbitrary apps and inject touch.
 *
 * If the privileged launcher is unavailable (e.g. Shizuku not active and no Root), it seamlessly
 * falls back to a local unprivileged VirtualDisplay in the app process.
 *
 * Thread-safety: all public methods are synchronized on the object's internal lock.
 * Created displays survive until [destroy] is called or the process dies.
 */
object VirtualDisplayController {

    private const val TAG = "VirtualDisplayCtrl"

    /** Managed display entries, keyed by display id. */
    private val displays = mutableMapOf<Int, DisplayEntry>()

    /** Lock for [displays]. */
    private val lock = Any()

    private sealed class DisplayEntry {
        abstract val info: DisplayInfo

        data class Privileged(
            override val info: DisplayInfo,
        ) : DisplayEntry()

        data class Local(
            override val info: DisplayInfo,
            val display: VirtualDisplay,
            val imageReader: ImageReader,
        ) : DisplayEntry()
    }

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
        val alignedW = (width + 15) and 15.inv()
        val alignedH = (height + 15) and 15.inv()
        val name = "CLmA-VD-${System.currentTimeMillis()}"

        // 1. Try privileged virtual display service via Shizuku/Root first
        try {
            val service = VirtualDisplayLauncher.getOrLaunch(context)
            val displayId = service.createOwnedDisplay(name, alignedW, alignedH, densityDpi)
            if (displayId > 0) {
                val info = DisplayInfo(displayId, name, alignedW, alignedH, densityDpi)
                displays[displayId] = DisplayEntry.Privileged(info)
                Log.i(TAG, "Created privileged virtual display id=$displayId ${alignedW}x${alignedH} density=$densityDpi")
                return Result.Ok(displayId)
            } else {
                Log.w(TAG, "createOwnedDisplay returned invalid displayId: $displayId")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Privileged VirtualDisplayLauncher failed: ${t.message}. Falling back to local VirtualDisplay.")
        }

        // 2. Fall back to local unprivileged VirtualDisplay
        return try {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val imageReader = ImageReader.newInstance(alignedW, alignedH, 0x2 /* PixelFormat.RGBA_8888 */, 2)
            val surface = imageReader.surface
            val display = createLocalVirtualDisplay(dm, name, alignedW, alignedH, densityDpi, surface)
            val id = display.display.displayId
            val info = DisplayInfo(id, name, alignedW, alignedH, densityDpi)
            displays[id] = DisplayEntry.Local(info, display, imageReader)
            Log.i(TAG, "Created local fallback virtual display id=$id ${alignedW}x${alignedH} density=$densityDpi")
            Result.Ok(id)
        } catch (t: Throwable) {
            Log.e(TAG, "createLocalVirtualDisplay failed", t)
            Result.Error("create_failed", t.message ?: t::class.java.simpleName)
        }
    }

    /**
     * Destroy a virtual display by id. Returns true if found and destroyed.
     */
    fun destroy(displayId: Int): Boolean = synchronized(lock) {
        val entry = displays.remove(displayId) ?: return false
        return when (entry) {
            is DisplayEntry.Privileged -> {
                VirtualDisplayLauncher.currentService()?.let { svc ->
                    runCatching { svc.releaseDisplay(displayId) }
                }
                Log.i(TAG, "Destroyed privileged virtual display id=$displayId")
                true
            }
            is DisplayEntry.Local -> {
                runCatching { entry.display.release() }
                runCatching { entry.imageReader.close() }
                Log.i(TAG, "Destroyed local virtual display id=$displayId")
                true
            }
        }
    }

    /** Destroy all displays. Called on process teardown. */
    fun destroyAll() = synchronized(lock) {
        displays.keys.toList().forEach { id ->
            destroy(id)
        }
        VirtualDisplayLauncher.currentService()?.let { svc ->
            runCatching { svc.destroy() }
        }
    }

    /** List all managed virtual displays. */
    fun list(): List<DisplayInfo> = synchronized(lock) {
        displays.values.map { it.info }
    }

    /** True if a display with the given id is managed by this controller. */
    fun contains(displayId: Int): Boolean = synchronized(lock) {
        displayId in displays
    }

    /**
     * Capture the latest rendered frame from a virtual display.
     *
     * Returns a software [Bitmap] (ARGB_8888) sized to the display, or null when the display
     * is unknown or no frame has been rendered yet (callers may retry after a short delay).
     * The caller owns the bitmap and must [recycle] it when done.
     */
    fun captureFrame(displayId: Int): Bitmap? = synchronized(lock) {
        val entry = displays[displayId] ?: return null
        return when (entry) {
            is DisplayEntry.Privileged -> {
                val svc = VirtualDisplayLauncher.currentService() ?: return null
                val pfd = try {
                    svc.captureFrameStream(displayId)
                } catch (e: Exception) {
                    Log.w(TAG, "captureFrameStream IPC failed: ${e.message}")
                    null
                } ?: return null
                try {
                    ParcelFileDescriptor.AutoCloseInputStream(pfd).use { stream ->
                        BitmapFactory.decodeStream(stream)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "decodeStream from captureFrameStream failed: ${e.message}")
                    null
                }
            }
            is DisplayEntry.Local -> {
                captureLocalFrame(entry)
            }
        }
    }

    private fun captureLocalFrame(entry: DisplayEntry.Local): Bitmap? {
        val image = try {
            entry.imageReader.acquireLatestImage()
        } catch (e: Exception) {
            Log.w(TAG, "acquireLatestImage failed: ${e.message}")
            null
        } ?: return null
        return try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * entry.info.width
            val paddedWidth = entry.info.width + rowPadding / pixelStride
            val bitmap = Bitmap.createBitmap(paddedWidth, entry.info.height, Bitmap.Config.ARGB_8888)
            buffer.rewind()
            bitmap.copyPixelsFromBuffer(buffer)
            if (paddedWidth != entry.info.width) {
                Bitmap.createBitmap(bitmap, 0, 0, entry.info.width, entry.info.height).also { bitmap.recycle() }
            } else {
                bitmap
            }
        } finally {
            image.close()
        }
    }

    private fun createLocalVirtualDisplay(
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
