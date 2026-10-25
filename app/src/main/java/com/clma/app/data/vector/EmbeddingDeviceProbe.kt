package com.clma.app.data.vector

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.os.StatFs
import java.util.Locale

/**
 * Reads the three facts [EmbeddingTierRules] needs off the platform.
 *
 * The only part of the device-adaptive choice that touches Android, kept apart from the rule so the
 * rule can be tested without one. Every read is guarded: a device that refuses to report its RAM
 * yields 0 rather than a crash, and 0 fails every rung (see [EmbeddingTierRules.fits]) which lands
 * on the light model - the safe direction to be wrong in.
 *
 * Disk is measured at `filesDir`, which is where the models actually go
 * (`ModelInstall.localModelsDir`). The path resolves to internal storage, so that one number
 * covers both the download and the index built next to it. It is deliberately *not* measured at
 * the models directory itself: on a fresh install that directory does not exist yet, `StatFs`
 * throws on it, and the answer would be "nothing fits" precisely when the user is choosing.
 */
object EmbeddingDeviceProbe {

    fun facts(context: Context): EmbeddingDeviceFacts = EmbeddingDeviceFacts(
        totalRamBytes = totalRamBytes(context),
        availableDiskBytes = availableDiskBytes(context),
        prefersChinese = prefersChinese(),
    )

    private fun totalRamBytes(context: Context): Long = runCatching {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        info.totalMem
    }.getOrDefault(0L)

    private fun availableDiskBytes(context: Context): Long = runCatching {
        StatFs(context.filesDir.absolutePath).availableBytes
    }.getOrDefault(0L)

    /**
     * `zh` and its regions (`zh-Hans-CN`, `zh-TW`) all start with `zh`; anything else is taken at
     * its word. The device's own setting rather than the app's language, because that is the one
     * that tends to match what the user types into the app.
     */
    private fun prefersChinese(): Boolean =
        Locale.getDefault().language.equals("zh", ignoreCase = true)

    /**
     * Whether the network this phone is on bills per byte.
     *
     * Asked once, at the moment a download is about to start, rather than watched: the question is
     * only ever "should this one be confirmed first", and a stale answer to it costs one dialog.
     * A device that cannot answer is treated as unmetered, because the alternative - refusing to
     * download on a phone whose network type is unknown - fails closed on a working connection.
     */
    fun isMetered(context: Context): Boolean = runCatching {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        manager.isActiveNetworkMetered
    }.getOrDefault(false)
}
