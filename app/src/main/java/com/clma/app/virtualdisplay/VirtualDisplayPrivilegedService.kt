package com.clma.app.virtualdisplay

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.hardware.display.VirtualDisplayConfig
import android.media.Image
import android.media.ImageReader
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.UserHandle
import android.util.Log
import android.view.Surface
import androidx.annotation.Keep
import androidx.core.content.getSystemService
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.exitProcess
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "VirtualDisplayService"
private const val SHELL_PACKAGE_NAME = "com.android.shell"
private const val SYSTEM_PACKAGE_NAME = "android"
private const val ROOT_UID = 0

/**
 * Privileged Virtual Display lifecycle service. Runs in a separate shell (Shizuku) or root
 * process started by [VirtualDisplayStarter].
 */
class VirtualDisplayPrivilegedService @Keep constructor(
    private val context: Context,
) : IVirtualDisplayService.Stub() {

    private val privilegedContext: Context by lazy { contextForCurrentProcess(context) }
    private val displayManager: DisplayManager by lazy {
        privilegedContext.getSystemService<DisplayManager>()
            ?: error("DisplayManager not available in privileged context")
    }

    private val backgroundThread = HandlerThread("VDFrames").apply { start() }
    private val backgroundHandler = Handler(backgroundThread.looper)

    private val userContexts = ConcurrentHashMap<UserHandle, Context>()

    private class DisplayHolder(
        val display: VirtualDisplay,
        val reader: ImageReader?,
        val name: String,
        val width: Int,
        val height: Int,
        val density: Int,
    ) {
        val frameLock = Any()
        var latestImage: Image? = null

        fun updateLatestImage(newImage: Image) {
            val oldImage: Image?
            synchronized(frameLock) {
                oldImage = latestImage
                latestImage = newImage
            }
            runCatching { oldImage?.close() }
        }

        fun recycle() {
            synchronized(frameLock) {
                runCatching { latestImage?.close() }
                latestImage = null
            }
            runCatching { display.release() }
            runCatching { reader?.close() }
        }
    }

    private val displays = ConcurrentHashMap<Int, DisplayHolder>()
    private val previewSurfaces = ConcurrentHashMap<Int, Surface>()
    private val displayLocks = ConcurrentHashMap<Int, Any>()

    private fun callerUserHandle(): UserHandle = UserHandle.getUserHandleForUid(Binder.getCallingUid())

    private fun callerUserContext(): Context = userContextFor(callerUserHandle())

    private fun userContextFor(user: UserHandle): Context =
        userContexts.computeIfAbsent(user) { createPackageContextAsUser(it) }

    @SuppressLint("DiscouragedPrivateApi")
    private fun createPackageContextAsUser(user: UserHandle): Context {
        val packageName = privilegedContext.packageName
        return runCatching {
            Context::class.java
                .getMethod(
                    "createPackageContextAsUser",
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    UserHandle::class.java,
                )
                .invoke(privilegedContext, packageName, Context.CONTEXT_IGNORE_SECURITY, user) as Context
        }.getOrElse { throwable ->
            val cause = (throwable as? java.lang.reflect.InvocationTargetException)?.targetException ?: throwable
            error("Virtual display cannot act on behalf of Android user $user: ${cause.message ?: cause.javaClass.simpleName}")
        }
    }

    override fun createDisplay(
        name: String,
        width: Int,
        height: Int,
        density: Int,
        surface: Surface,
    ): Int {
        val display = createVirtualDisplayInternal(name, width, height, density, surface)
        val displayId = display.display.displayId
        val holder = DisplayHolder(display, null, name, width, height, density)
        displays[displayId] = holder
        displayLocks[displayId] = Any()
        Log.i(TAG, "Created external virtual display id=$displayId ($width x $height @ ${density}dpi)")
        return displayId
    }

    override fun createOwnedDisplay(
        name: String,
        width: Int,
        height: Int,
        density: Int,
    ): Int {
        // maxImages = 4 allows continuous pipeline flow without frame drop starvation
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 4)
        val display = createVirtualDisplayInternal(name, width, height, density, reader.surface)
        val displayId = display.display.displayId
        val holder = DisplayHolder(display, reader, name, width, height, density)

        // Drain off-screen render surface AND latch the latest frame for captureFrameStream
        reader.setOnImageAvailableListener({ available ->
            val image = runCatching { available.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            holder.updateLatestImage(image)
        }, backgroundHandler)

        displays[displayId] = holder
        displayLocks[displayId] = Any()
        Log.i(TAG, "Created owned virtual display id=$displayId ($width x $height @ ${density}dpi)")
        return displayId
    }

    override fun attachPreviewSurface(displayId: Int, surface: Surface) {
        val holder = displays[displayId]
            ?: error("Display $displayId is not managed by VirtualDisplayService.")
        synchronized(displayLock(displayId)) {
            previewSurfaces[displayId] = surface
            holder.display.surface = surface
        }
    }

    override fun detachPreviewSurface(displayId: Int) {
        val holder = displays[displayId]
            ?: error("Display $displayId is not managed by VirtualDisplayService.")
        synchronized(displayLock(displayId)) {
            previewSurfaces.remove(displayId)
            val originalSurface = holder.reader?.surface
            if (originalSurface != null) {
                holder.display.surface = originalSurface
            }
        }
    }

    override fun releaseDisplay(displayId: Int) {
        synchronized(displayLock(displayId)) {
            previewSurfaces.remove(displayId)
            displays.remove(displayId)?.recycle()
            displayLocks.remove(displayId)
        }
        Log.i(TAG, "Released virtual display id=$displayId")
    }

    override fun captureFrameStream(displayId: Int): ParcelFileDescriptor? {
        val holder = displays[displayId] ?: return null
        val bitmap = synchronized(holder.frameLock) {
            val image = holder.latestImage ?: return@synchronized null
            try {
                val plane = image.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val width = holder.width
                val height = holder.height
                val rowPadding = rowStride - pixelStride * width
                val paddedWidth = width + rowPadding / pixelStride
                val bmp = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
                buffer.rewind()
                bmp.copyPixelsFromBuffer(buffer)
                if (paddedWidth != width) {
                    Bitmap.createBitmap(bmp, 0, 0, width, height).also { bmp.recycle() }
                } else {
                    bmp
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed reading bitmap from latestImage", t)
                null
            }
        } ?: return null

        val pipe = ParcelFileDescriptor.createPipe()
        val readSide = pipe[0]
        val writeSide = pipe[1]

        Thread({
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(writeSide).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    out.flush()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Error streaming frame bitmap", t)
            } finally {
                bitmap.recycle()
            }
        }, "VDFramePipeWriter").start()

        return readSide
    }

    override fun destroy() {
        displays.keys.toList().forEach { displayId ->
            runCatching { releaseDisplay(displayId) }
        }
        previewSurfaces.clear()
        displays.clear()
        displayLocks.clear()
        backgroundThread.quitSafely()
        exitProcess(0)
    }

    override fun linkClient(client: IBinder) {
        client.linkToDeath({ destroy() }, 0)
    }

    override fun launchPackage(packageName: String, displayId: Int) {
        ensureManagedDisplay(displayId)
        val userContext = callerUserContext()
        val intent = userContext.packageManager.getLaunchIntentForPackage(packageName)
            ?: error("No launchable activity for $packageName.")
        val options = ActivityOptions.makeBasic()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            options.launchDisplayId = displayId
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)

        val targetDisplay = displayManager.getDisplay(displayId)
            ?: error("Display $displayId is not available.")
        val displayContext = userContext.createDisplayContext(targetDisplay)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        PendingIntent.getActivity(
            displayContext,
            intent.hashCode(),
            intent,
            flags,
        ).send(
            userContext,
            0,
            null,
            null,
            null,
            null,
            options.toBundle(),
        )
    }

    override fun listDisplaysJson(): String =
        JSONArray().apply {
            displayManager.displays.forEach { display ->
                val size = Point()
                @Suppress("DEPRECATION")
                display.getSize(size)
                put(
                    JSONObject().apply {
                        put("display_id", display.displayId)
                        put("name", display.name.orEmpty())
                        put("width", display.mode?.physicalWidth ?: size.x)
                        put("height", display.mode?.physicalHeight ?: size.y)
                        put("is_clma_display", displays.containsKey(display.displayId))
                    }
                )
            }
        }.toString()

    @Suppress("DEPRECATION")
    override fun listInstalledAppsJson(): String {
        val packageManager = callerUserContext().packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val launchables = packageManager.queryIntentActivities(
            launcherIntent,
            PackageManager.MATCH_DISABLED_COMPONENTS,
        )
        val uniqueApps = linkedMapOf<String, JSONObject>()
        launchables
            .sortedWith(
                compareBy(
                    { it.loadLabel(packageManager).toString().lowercase() },
                    { it.activityInfo.packageName },
                )
            )
            .forEach { info ->
                val activityInfo = info.activityInfo ?: return@forEach
                val applicationInfo = activityInfo.applicationInfo ?: return@forEach
                val packageName = activityInfo.packageName.orEmpty()
                if (packageName.isBlank() || uniqueApps.containsKey(packageName)) return@forEach
                uniqueApps[packageName] = JSONObject().apply {
                    put("package_name", packageName)
                    put("app_name", info.loadLabel(packageManager).toString())
                    put("activity_name", activityInfo.name.orEmpty())
                    put("enabled", activityInfo.enabled && applicationInfo.enabled)
                    put("system", applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0)
                    put("launchable", true)
                }
            }
        return JSONArray(uniqueApps.values).toString()
    }

    private fun createVirtualDisplayInternal(
        name: String,
        width: Int,
        height: Int,
        density: Int,
        surface: Surface,
    ): VirtualDisplay {
        return if (Build.VERSION.SDK_INT >= 33) {
            val config = VirtualDisplayConfig.Builder(name, width, height, density)
                .setSurface(surface)
                .setFlags(VirtualDisplayProtocol.agentVirtualDisplayFlags())
                .build()
            displayManager.createVirtualDisplay(config)
                ?: error("createVirtualDisplay returned null")
        } else {
            @Suppress("DEPRECATION")
            displayManager.createVirtualDisplay(
                name,
                width,
                height,
                density,
                surface,
                VirtualDisplayProtocol.agentVirtualDisplayFlags(),
            ) ?: error("createVirtualDisplay returned null")
        }
    }

    private fun displayLock(displayId: Int): Any =
        displayLocks.computeIfAbsent(displayId) { Any() }

    private fun ensureManagedDisplay(displayId: Int) {
        if (!displays.containsKey(displayId)) {
            error("Display $displayId is not managed by VirtualDisplayService.")
        }
    }

    private fun contextForCurrentProcess(baseContext: Context): Context {
        val packageName = packageNameForCurrentProcess(baseContext.packageName)
        if (packageName == baseContext.packageName) return baseContext
        return baseContext.createPackageContext(packageName, Context.CONTEXT_IGNORE_SECURITY)
    }

    private fun packageNameForCurrentProcess(defaultPackageName: String): String =
        when (Process.myUid()) {
            Process.SHELL_UID -> SHELL_PACKAGE_NAME
            ROOT_UID, Process.SYSTEM_UID -> SYSTEM_PACKAGE_NAME
            else -> defaultPackageName
        }
}
