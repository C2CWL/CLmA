package com.clma.app.data.ai.tools.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.clma.app.service.VirtualDisplayPreviewWindow
import com.clma.app.virtualdisplay.VirtualDisplayLauncher

/**
 * Manages virtual display lifecycle, delegating to a privileged daemon process via Shizuku/Root
 * (Aether agent mode approach) so displays carry privileged system flags (FLAG_PUBLIC, FLAG_TRUSTED,
 * FLAG_OWN_FOCUS, FLAG_SUPPORTS_TOUCH, etc.) needed to run arbitrary apps and inject touch.
 *
 * If the privileged launcher is unavailable (e.g. Shizuku not active and no Root), it seamlessly
 * falls back to a local unprivileged VirtualDisplay in the app process.
 *
 * Frame pipeline: every display (privileged or local) renders into a single app-owned
 * [ImageReader] that is handed over as the display surface. One drain listener keeps the newest
 * frame latched, and both screenshots ([captureFrame]) and the live preview ([copyFrame]) read
 * from that same image. Because nobody ever swaps the display surface, a screenshot taken while
 * the preview is up still returns the live picture — the earlier design, where the preview window
 * took over the surface, froze captures on the frame from before the switch.
 *
 * Thread-safety: display bookkeeping is synchronized on an internal lock; frames are synchronized
 * per display, so a slow copy never blocks creating or destroying another display.
 * Created displays survive until [destroy] is called or the process dies.
 */
object VirtualDisplayController {

    private const val TAG = "VirtualDisplayCtrl"

    /** Frames buffered by the reader. 4 matches the privileged service's own pipeline. */
    private const val MAX_IMAGES = 4

    /** Managed display entries, keyed by display id. */
    private val displays = mutableMapOf<Int, DisplayEntry>()

    /** Lock for [displays]. */
    private val lock = Any()

    /** Drains ImageReader callbacks off the main thread. */
    private val frameHandler: Handler by lazy {
        val thread = HandlerThread("VDFrames").apply { start() }
        Handler(thread.looper)
    }

    private sealed class DisplayEntry {
        abstract val info: DisplayInfo
        abstract val imageReader: ImageReader

        /** Guards [latestImage] and [seq]; every pixel copy happens inside it. */
        val frameLock = Any()

        /** Newest latched frame. Closed on swap, on destroy, or when the entry is replaced. */
        var latestImage: Image? = null

        /** Bumped whenever a new frame is latched, so the preview can skip unchanged frames. */
        @Volatile
        var seq: Long = 0L

        data class Privileged(
            override val info: DisplayInfo,
            override val imageReader: ImageReader,
        ) : DisplayEntry()

        data class Local(
            override val info: DisplayInfo,
            override val imageReader: ImageReader,
            val display: VirtualDisplay,
        ) : DisplayEntry()
    }

    /**
     * Create a virtual display. Width/height are aligned to 16 (MediaCodec requirement; also
     * avoids odd-sized buffers on some GPUs). Returns the display id, or an error string.
     *
     * [showPreview] pops up the floating live-preview window for watching the agent work. It
     * needs SYSTEM_ALERT_WINDOW; without it the preview is silently skipped, the display is not.
     */
    fun create(
        context: Context,
        width: Int,
        height: Int,
        densityDpi: Int,
        showPreview: Boolean = true,
    ): Result<Int> {
        if (width < 1 || height < 1) {
            return Result.Error("invalid_dimensions", "width and height must be >= 1")
        }
        if (densityDpi < 1 || densityDpi > 640) {
            return Result.Error("invalid_density", "density must be between 1 and 640")
        }
        val alignedW = (width + 15) and 15.inv()
        val alignedH = (height + 15) and 15.inv()
        val name = "CLmA-VD-${System.currentTimeMillis()}"

        // 1. Try the privileged virtual display service via Shizuku/Root first. Our own reader is
        //    handed over as the display surface, so privileged flags and app-side frames both work.
        try {
            val service = VirtualDisplayLauncher.getOrLaunch(context)
            val reader = newReader(alignedW, alignedH)
            val displayId = try {
                service.createDisplay(name, alignedW, alignedH, densityDpi, reader.surface)
            } catch (t: Throwable) {
                runCatching { reader.close() }
                throw t
            }
            if (displayId > 0) {
                val info = DisplayInfo(displayId, name, alignedW, alignedH, densityDpi)
                register(displayId, DisplayEntry.Privileged(info, reader), context, showPreview)
                Log.i(TAG, "Created privileged virtual display id=$displayId ${alignedW}x${alignedH} density=$densityDpi")
                return Result.Ok(displayId)
            }
            runCatching { reader.close() }
            Log.w(TAG, "createDisplay returned invalid displayId: $displayId")
        } catch (t: Throwable) {
            Log.w(TAG, "Privileged VirtualDisplayLauncher failed: ${t.message}. Falling back to local VirtualDisplay.")
        }

        // 2. Fall back to a local unprivileged VirtualDisplay in the app process.
        return try {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val reader = newReader(alignedW, alignedH)
            val display = try {
                createLocalVirtualDisplay(dm, name, alignedW, alignedH, densityDpi, reader.surface)
            } catch (t: Throwable) {
                runCatching { reader.close() }
                throw t
            }
            val id = display.display.displayId
            val info = DisplayInfo(id, name, alignedW, alignedH, densityDpi)
            register(id, DisplayEntry.Local(info, reader, display), context, showPreview)
            Log.i(TAG, "Created local fallback virtual display id=$id ${alignedW}x${alignedH} density=$densityDpi")
            Result.Ok(id)
        } catch (t: Throwable) {
            Log.e(TAG, "createLocalVirtualDisplay failed", t)
            Result.Error("create_failed", t.message ?: t::class.java.simpleName)
        }
    }

    private fun newReader(width: Int, height: Int): ImageReader =
        ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, MAX_IMAGES)

    private fun register(
        displayId: Int,
        entry: DisplayEntry,
        context: Context,
        showPreview: Boolean,
    ) {
        attachFrameListener(entry)
        synchronized(lock) { displays[displayId] = entry }
        if (showPreview) {
            VirtualDisplayPreviewWindow.show(context.applicationContext, entry.info)
        }
    }

    /**
     * Drains the reader continuously so the producer never blocks, and latches the newest frame
     * for both consumers. Acquiring only on demand would let the queue fill with stale frames and
     * stall rendering, which is why the drain is unconditional.
     */
    private fun attachFrameListener(entry: DisplayEntry) {
        entry.imageReader.setOnImageAvailableListener({ reader ->
            val image = runCatching { reader.acquireLatestImage() }.getOrNull()
                ?: return@setOnImageAvailableListener
            val previous: Image?
            synchronized(entry.frameLock) {
                previous = entry.latestImage
                entry.latestImage = image
                entry.seq++
            }
            // Safe to close: every reader copies pixels while holding frameLock.
            runCatching { previous?.close() }
        }, frameHandler)
    }

    /**
     * Destroy a virtual display by id. Returns true if found and destroyed.
     */
    fun destroy(displayId: Int): Boolean {
        val entry = synchronized(lock) { displays.remove(displayId) } ?: return false
        // Close the preview first so it stops polling before the reader goes away.
        VirtualDisplayPreviewWindow.hideIfShowing(displayId)
        return when (entry) {
            is DisplayEntry.Privileged -> {
                VirtualDisplayLauncher.currentService()?.let { svc ->
                    runCatching { svc.releaseDisplay(displayId) }
                }
                closeEntry(entry)
                Log.i(TAG, "Destroyed privileged virtual display id=$displayId")
                true
            }
            is DisplayEntry.Local -> {
                runCatching { entry.display.release() }
                closeEntry(entry)
                Log.i(TAG, "Destroyed local virtual display id=$displayId")
                true
            }
        }
    }

    private fun closeEntry(entry: DisplayEntry) {
        runCatching { entry.imageReader.setOnImageAvailableListener(null, null) }
        synchronized(entry.frameLock) {
            runCatching { entry.latestImage?.close() }
            entry.latestImage = null
            entry.seq++
        }
        runCatching { entry.imageReader.close() }
    }

    /** Destroy all displays. Called on process teardown. */
    fun destroyAll() {
        val ids = synchronized(lock) { displays.keys.toList() }
        ids.forEach { id ->
            runCatching { destroy(id) }
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

    /** Show the floating live preview for an existing display (no-op if the id is unknown). */
    fun showPreview(context: Context, displayId: Int) {
        val info = synchronized(lock) { displays[displayId]?.info } ?: return
        VirtualDisplayPreviewWindow.show(context.applicationContext, info)
    }

    /** Close the floating live preview for [displayId]; the display keeps running. */
    fun hidePreview(displayId: Int) {
        VirtualDisplayPreviewWindow.hideIfShowing(displayId)
    }

    /**
     * Geometry of the newest frame, or null when the display is unknown or nothing has rendered
     * yet. The preview polls this cheaply to decide whether it needs to copy anything.
     */
    fun frameSpec(displayId: Int): FrameSpec? {
        val entry = entryFor(displayId) ?: return null
        synchronized(entry.frameLock) {
            val image = entry.latestImage ?: return null
            val bufferWidth = bufferWidthOf(image)
            return FrameSpec(
                width = image.width,
                height = image.height,
                bufferWidth = bufferWidth,
                bufferHeight = image.height,
                seq = entry.seq,
            )
        }
    }

    /**
     * Copy the newest frame into [target] without allocating. [target] must already be
     * `bufferWidth x bufferHeight` for this display (see [frameSpec]); returns false when the
     * geometry does not match or no frame is available yet.
     */
    fun copyFrame(displayId: Int, target: Bitmap): Boolean {
        val entry = entryFor(displayId) ?: return false
        synchronized(entry.frameLock) {
            val image = entry.latestImage ?: return false
            if (target.isRecycled ||
                target.width != bufferWidthOf(image) ||
                target.height != image.height
            ) {
                return false
            }
            return try {
                val buffer = image.planes[0].buffer
                buffer.rewind()
                target.copyPixelsFromBuffer(buffer)
                true
            } catch (t: Throwable) {
                Log.w(TAG, "copyFrame failed: ${t.message}")
                false
            }
        }
    }

    /**
     * Capture the latest rendered frame from a virtual display.
     *
     * Returns a software [Bitmap] (ARGB_8888) sized to the display, or null when the display
     * is unknown or no frame has been rendered yet (callers may retry after a short delay).
     * The caller owns the bitmap and must recycle it when done. Works while the live preview is
     * up, because both read the same latched image.
     */
    fun captureFrame(displayId: Int): Bitmap? {
        val entry = entryFor(displayId) ?: return null
        synchronized(entry.frameLock) {
            val image = entry.latestImage ?: return null
            return try {
                val plane = image.planes[0]
                val width = image.width
                val height = image.height
                val bufferWidth = bufferWidthOf(image)
                val padded = Bitmap.createBitmap(bufferWidth, height, Bitmap.Config.ARGB_8888)
                val buffer = plane.buffer
                buffer.rewind()
                padded.copyPixelsFromBuffer(buffer)
                if (bufferWidth != width) {
                    Bitmap.createBitmap(padded, 0, 0, width, height).also { padded.recycle() }
                } else {
                    padded
                }
            } catch (t: Throwable) {
                Log.w(TAG, "captureFrame failed: ${t.message}")
                null
            }
        }
    }

    /** Row padding makes buffers wider than the display; both consumers must agree on it. */
    private fun bufferWidthOf(image: Image): Int {
        val plane = image.planes[0]
        val stride = if (plane.pixelStride > 0) plane.rowStride / plane.pixelStride else image.width
        return stride.coerceAtLeast(image.width)
    }

    private fun entryFor(displayId: Int): DisplayEntry? = synchronized(lock) {
        displays[displayId]
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

    /** Newest frame geometry; [bufferWidth] may exceed [width] because of row padding. */
    data class FrameSpec(
        val width: Int,
        val height: Int,
        val bufferWidth: Int,
        val bufferHeight: Int,
        val seq: Long,
    )

    sealed class Result<out T> {
        data class Ok<T>(val value: T) : Result<T>()
        data class Error(val code: String, val message: String) : Result<Nothing>()
    }
}