package me.rerere.rikkahub.data.ai.tools.local

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.AgentTurnTracker
import me.rerere.rikkahub.data.ai.ScreenshotLedger
import me.rerere.rikkahub.service.ActionLogEntry
import me.rerere.rikkahub.service.RikkaAccessibilityService
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private const val SCREENSHOT_CACHE_DIR = "screenshots"
private const val PICTURES_SUBDIR = "RikkaHub/Screenshots"
private const val PRUNE_OLDER_THAN_MS = 60L * 60L * 1000L  // 1 hour — cache only

/**
 * Longest side of the copy handed to the model. A phone screenshot is ~1440x3168; the vision
 * encoders re-scale to roughly this size anyway, so shrinking at capture time removes ~3/4 of
 * the pixels from the PNG encode, the disk write and every later decode. The kept gallery copy
 * is always the original resolution.
 */
private const val MODEL_MAX_DIM = 1600

/** Scales [src] down so its longest side is at most [maxDim]; returns [src] when it already fits. */
private fun downscaleForModel(src: Bitmap, maxDim: Int): Bitmap {
    val longest = maxOf(src.width, src.height)
    if (longest <= maxDim || longest == 0) return src
    val scale = maxDim.toFloat() / longest.toFloat()
    val w = (src.width * scale).roundToInt().coerceAtLeast(1)
    val h = (src.height * scale).roundToInt().coerceAtLeast(1)
    return try {
        Bitmap.createScaledBitmap(src, w, h, true)
    } catch (t: Throwable) {
        src
    }
}

private fun pruneOldCacheScreenshots(dir: File) {
    val cutoff = System.currentTimeMillis() - PRUNE_OLDER_THAN_MS
    dir.listFiles()?.forEach { f ->
        if (f.lastModified() < cutoff) f.delete()
    }
}

fun takeScreenshotTool(context: Context): Tool = Tool(
    name = "take_screenshot",
    description = "Capture the current display via AccessibilityService and return it as a vision attachment. The image is downscaled for the model. By default the screenshot is a transient working copy: it is deleted when this turn ends, so pass keep=true when the user wants the picture saved — that also writes a full-resolution copy to Pictures/RikkaHub/Screenshots/ and fills gallery_path. Secure surfaces (banking, DRM, password fields) error gracefully. OS-rate-limited to ~1/sec. The result includes screen_state (foreground package, shade_open, display size). For \"did my action work\" checks prefer the \"after\" object that action tools already return; screenshot only when you need visual detail.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("display_id", buildJsonObject {
                    put("type", "integer")
                    put("description", "Display id to capture (default 0)")
                })
                put("keep", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Keep this screenshot: save a full-resolution copy to the gallery and do not delete it when the turn ends. Default false (transient working copy).")
                })
            }
        )
    },
    execute = { input ->
        val displayId = input.jsonObject["display_id"]?.jsonPrimitive?.intOrNull ?: 0
        val keep = input.jsonObject["keep"]?.jsonPrimitive?.booleanOrNull ?: false

        // Virtual displays: AccessibilityService.takeScreenshot() returns
        // ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY for any non-default display (platform
        // limitation, verified by CTS). Route virtual-display captures through the
        // ImageReader that backs the display's Surface instead.
        val isVirtualDisplay = displayId != 0 && VirtualDisplayController.contains(displayId)

        val outcome = if (isVirtualDisplay) {
            val cacheDir = File(context.cacheDir, SCREENSHOT_CACHE_DIR).apply { mkdirs() }
            pruneOldCacheScreenshots(cacheDir)
            captureVirtualDisplay(displayId, keep, cacheDir, context)
        } else {
            AccessibilityServiceHandle.withService { svc ->
            val cacheDir = File(context.cacheDir, SCREENSHOT_CACHE_DIR).apply { mkdirs() }
            pruneOldCacheScreenshots(cacheDir)
            val res = svc.captureScreenshot(displayId)
            when (res) {
                is RikkaAccessibilityService.ScreenshotOutcome.Failure -> {
                    svc.appendLog(
                        ActionLogEntry(
                            type = "take_screenshot",
                            paramsSummary = "fail:${res.reason}",
                            success = false,
                            timestampMs = System.currentTimeMillis(),
                        )
                    )
                    buildJsonObject {
                        put("error", "screenshot_unavailable")
                        put("reason", res.reason)
                    }
                }

                is RikkaAccessibilityService.ScreenshotOutcome.Success -> {
                    val ts = System.currentTimeMillis()
                    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(ts))
                    val displayName = "Screenshot_${timestamp}.png"

                    // Downscale for the model. Providers re-encode and downscale anyway, so
                    // shrinking here cuts the PNG encode, the on-disk copy and every later
                    // decode / JPEG pass. The kept gallery copy stays full resolution.
                    val modelBitmap = downscaleForModel(res.bitmap, MODEL_MAX_DIM)
                    val modelW = modelBitmap.width
                    val modelH = modelBitmap.height
                    val pngBytes: ByteArray? = try {
                        ByteArrayOutputStream().use { bos ->
                            modelBitmap.compress(Bitmap.CompressFormat.PNG, 100, bos)
                            bos.toByteArray()
                        }
                    } catch (t: Throwable) {
                        null
                    } finally {
                        if (modelBitmap !== res.bitmap) modelBitmap.recycle()
                    }
                    if (pngBytes == null) {
                        res.bitmap.recycle()
                        return@withService buildJsonObject {
                            put("error", "write_failed")
                            put("reason", "encode_failed")
                        }
                    }

                    // 1) Always write a cache copy — this is the path attached to the LLM as
                    //    inline vision (file:// uri readable by the encoder; reliable across
                    //    Android versions, regardless of MediaStore success).
                    val cacheFile = File(File(context.cacheDir, SCREENSHOT_CACHE_DIR), "screen-$ts.png")
                    try {
                        cacheFile.writeBytes(pngBytes)
                    } catch (t: Throwable) {
                        res.bitmap.recycle()
                        return@withService buildJsonObject {
                            put("error", "write_failed")
                            put("reason", t.message ?: t::class.simpleName ?: "unknown")
                        }
                    }

                    // 2) A gallery copy only when the picture is meant to outlive the turn:
                    //    either the caller asked to keep it, or this turn never drove the screen
                    //    (a plain "take a screenshot" request). A screen-automation screenshot
                    //    stays in the cache and is deleted when the turn ends.
                    val writeGallery = keep || !AgentTurnTracker.didAutomate()
                    val gallerySave: GallerySave? = if (writeGallery) {
                        val fullPng = try {
                            ByteArrayOutputStream().use { bos ->
                                res.bitmap.compress(Bitmap.CompressFormat.PNG, 100, bos)
                                bos.toByteArray()
                            }
                        } catch (t: Throwable) {
                            null
                        }
                        if (fullPng != null) saveToGallery(context, fullPng, displayName) else null
                    } else {
                        null
                    }
                    res.bitmap.recycle()

                    val galleryPath = gallerySave?.path
                    if (!keep) {
                        ScreenshotLedger.recordTransient {
                            runCatching { cacheFile.delete() }.getOrDefault(false)
                        }
                        gallerySave?.let { saved ->
                            ScreenshotLedger.recordTransient { saved.remove() }
                        }
                    }

                    svc.appendLog(
                        ActionLogEntry(
                            type = "take_screenshot",
                            paramsSummary = "ok ${cacheFile.length() / 1024}KB display=$displayId" +
                                " model=${modelW}x${modelH}" +
                                (if (keep) " keep=true" else "") +
                                (if (galleryPath != null) " gallery=$galleryPath" else ""),
                            success = true,
                            timestampMs = ts,
                        )
                    )

                    buildJsonObject {
                        put("success", true)
                        put("file_path", cacheFile.absolutePath)
                        if (keep) put("kept", true)
                        if (galleryPath != null) {
                            put("gallery_path", galleryPath)
                            put("saved_to", "Pictures/$PICTURES_SUBDIR")
                        } else {
                            put(
                                "note",
                                "Transient working copy — deleted when this turn ends. " +
                                    "Pass keep=true if the user wants the picture saved.",
                            )
                        }
                        put("screen_state", screenStateJson(svc, screenChanged = null))
                    }
                }
            }
        }
        }

        val parts = mutableListOf<UIMessagePart>()
        outcome.jsonObject["file_path"]?.jsonPrimitive?.contentOrNull?.let { fp ->
            parts.add(UIMessagePart.Image(url = "file://$fp"))
        }
        parts.add(UIMessagePart.Text(outcome.toString()))
        parts
    }
)

/** A gallery copy of a screenshot: where the user can find it, and how to take it back out. */
private class GallerySave(val path: String, val remove: () -> Boolean)

/**
 * Captures a frame from a virtual display via its ImageReader, writes the model-size PNG to the
 * cache, and optionally saves a full-resolution gallery copy. Mirrors the flow of the
 * AccessibilityService screenshot path but without any a11y dependency.
 */
private fun captureVirtualDisplay(
    displayId: Int,
    keep: Boolean,
    cacheDir: File,
    context: Context,
): JsonObject {
    val bitmap = VirtualDisplayController.captureFrame(displayId)
        ?: return buildJsonObject {
            put("error", "screenshot_unavailable")
            put("reason", "no_frame")
            put("detail", "Virtual display has not rendered a frame yet. The display may be empty or the target app has not drawn anything. Retry after a short delay or after an interaction (tap/swipe/key).")
        }

    val ts = System.currentTimeMillis()
    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(ts))
    val displayName = "Screenshot_${timestamp}_display${displayId}.png"

    val modelBitmap = downscaleForModel(bitmap, MODEL_MAX_DIM)
    // NOTE: keep the original bitmap alive for the gallery full-resolution copy below.

    val pngBytes: ByteArray? = try {
        ByteArrayOutputStream().use { bos ->
            modelBitmap.compress(Bitmap.CompressFormat.PNG, 100, bos)
            bos.toByteArray()
        }
    } catch (t: Throwable) {
        null
    }
    if (pngBytes == null) {
        if (modelBitmap !== bitmap) modelBitmap.recycle()
        bitmap.recycle()
        return buildJsonObject {
            put("error", "write_failed")
            put("reason", "encode_failed")
        }
    }

    val cacheFile = File(cacheDir, "screen-vd${displayId}-$ts.png")
    try {
        cacheFile.writeBytes(pngBytes)
    } catch (t: Throwable) {
        if (modelBitmap !== bitmap) modelBitmap.recycle()
        bitmap.recycle()
        return buildJsonObject {
            put("error", "write_failed")
            put("reason", t.message ?: t::class.simpleName ?: "unknown")
        }
    }

    val writeGallery = keep || !AgentTurnTracker.didAutomate()
    val gallerySave: GallerySave? = if (writeGallery) {
        // Use the original (full-resolution) bitmap for the gallery copy.
        val fullPng = try {
            ByteArrayOutputStream().use { bos ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, bos)
                bos.toByteArray()
            }
        } catch (t: Throwable) {
            null
        }
        if (fullPng != null) saveToGallery(context, fullPng, displayName) else null
    } else {
        null
    }
    // Now safe to recycle both bitmaps.
    if (modelBitmap !== bitmap) modelBitmap.recycle()
    bitmap.recycle()

    val galleryPath = gallerySave?.path
    if (!keep) {
        ScreenshotLedger.recordTransient {
            runCatching { cacheFile.delete() }.getOrDefault(false)
        }
        gallerySave?.let { saved ->
            ScreenshotLedger.recordTransient { saved.remove() }
        }
    }

    return buildJsonObject {
        put("success", true)
        put("file_path", cacheFile.absolutePath)
        put("display_id", displayId)
        if (keep) put("kept", true)
        if (galleryPath != null) {
            put("gallery_path", galleryPath)
            put("saved_to", "Pictures/$PICTURES_SUBDIR")
        } else {
            put("note", "Transient working copy — deleted when this turn ends. Pass keep=true to save.")
        }
        // No screen_state for virtual displays — the a11y service only tracks the default display.
        put("virtual_display", true)
    }
}

/**
 * Persist already-encoded [pngBytes] as a PNG into the device gallery at
 * Pictures/RikkaHub/Screenshots/.
 *
 * Q+ (API 29+): use MediaStore (no permission required for own-app inserts; visible to
 * the user's Gallery app via media indexing). The returned [GallerySave.remove] goes back
 * through the content resolver, so the entry disappears from the Gallery cleanly rather than
 * leaving a dangling row behind.
 *
 * Pre-Q (API 26-28): write directly to the public Pictures directory. WRITE_EXTERNAL_STORAGE
 * is granted from the manifest's pre-Q permission; if it isn't, the write throws and we
 * return null — the LLM still gets the cache copy, only the gallery copy is missing.
 *
 * Returns the save handle, or null on any failure.
 */
private fun saveToGallery(context: Context, pngBytes: ByteArray, displayName: String): GallerySave? {
    return runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_PICTURES}/$PICTURES_SUBDIR"
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri: Uri = context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                values
            ) ?: return null

            context.contentResolver.openOutputStream(uri)?.use { os ->
                os.write(pngBytes)
            } ?: run {
                context.contentResolver.delete(uri, null, null)
                return null
            }

            val finalize = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            context.contentResolver.update(uri, finalize, null, null)

            // Resolve the public on-device path for the JSON envelope and ActionLog. DATA is
            // deprecated but still populated for entries we own; fall back to the well-known
            // Pictures location so callers always get something absolute.
            val path = context.contentResolver.query(
                uri,
                arrayOf(MediaStore.Images.Media.DATA),
                null, null, null
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                ?: File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "$PICTURES_SUBDIR/$displayName"
                ).absolutePath

            GallerySave(path) {
                runCatching { context.contentResolver.delete(uri, null, null) > 0 }
                    .getOrDefault(false)
            }
        } else {
            val pictures = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            val targetDir = File(pictures, PICTURES_SUBDIR).apply { mkdirs() }
            val out = File(targetDir, displayName)
            FileOutputStream(out).use { os ->
                os.write(pngBytes)
            }
            // Tell MediaScanner so the gallery picks it up promptly.
            android.media.MediaScannerConnection.scanFile(
                context, arrayOf(out.absolutePath), arrayOf("image/png"), null
            )
            GallerySave(out.absolutePath) {
                runCatching { out.delete() }.getOrDefault(false)
            }
        }
    }.getOrNull()
}
