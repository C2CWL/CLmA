package com.clma.app.virtualdisplay

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.InputMethod
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.view.inputmethod.EditorInfo
import androidx.annotation.RequiresApi
import com.clma.app.shizuku.ShizukuManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Accessibility IME APIs work without an exposed window tree or changing the user's IME. */
internal object VirtualDisplayUnicodeInput {
    private val mutex = Mutex()
    private var method: Any? = null // API 33 type must not be resolved on older Android.
    private var generation = 0L

    @RequiresApi(33)
    fun create(service: AccessibilityService): InputMethod = object : InputMethod(service) {
        override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
            generation++
        }
        override fun onFinishInput() {
            generation++
        }
    }.also { method = it; generation++ }

    fun clear() {
        method = null
        generation++
    }

    private fun error(code: String): JsonObject = buildJsonObject {
        put("error", code)
        put("success", false)
        put("detail", "No text sent. Focus an editor on the target VD; an active accessibility input session and verified VD input focus are required. Never switch the main-screen keyboard to recover.")
    }

    private data class Session(
        val epoch: Long,
        val owner: Any,
        val connection: Any,
        val packageName: String,
    )

    @RequiresApi(33)
    private fun snapshot(): Session? {
        val ime = method as? InputMethod ?: return null
        val info = ime.currentInputEditorInfo ?: return null
        val connection = ime.currentInputConnection ?: return null
        if (!ime.currentInputStarted || info.packageName.isNullOrBlank()) return null
        return Session(generation, ime, connection, info.packageName)
    }

    suspend fun commit(context: Context, displayId: Int, text: String): JsonObject = mutex.withLock {
        if (Build.VERSION.SDK_INT < 33) return@withLock error("unicode_input_requires_api33")
        if (displayId <= 0) return@withLock error("invalid_virtual_display")
        val session = withContext(Dispatchers.Main.immediate) { snapshot() }
            ?: return@withLock error("vd_input_session_unavailable")
        // No text is interpolated into shell commands; dumps stay in memory, never tool output.
        val checkedAt = SystemClock.uptimeMillis()
        val dump = ShizukuManager.exec(context, "dumpsys input_method", 5_000)
        if (dump["error"] != null || dump["exit_code"]?.jsonPrimitive?.intOrNull != 0) {
            return@withLock error("vd_input_focus_unverified")
        }
        val target = parseInputTarget(dump["stdout"]?.jsonPrimitive?.contentOrNull.orEmpty())
            ?: return@withLock error("vd_input_focus_unverified")
        if (target.displayId != displayId) return@withLock error("vd_input_focus_mismatch")
        val uid = runCatching { context.packageManager.getApplicationInfo(session.packageName, 0).uid }.getOrNull()
        if (uid != target.uid) return@withLock error("vd_input_package_mismatch")
        withContext(Dispatchers.Main.immediate) {
            if (session.owner !== method || session.epoch != generation || SystemClock.uptimeMillis() - checkedAt > 500) {
                return@withContext error("vd_input_session_changed")
            }
            if (!com.clma.app.data.ai.tools.local.VirtualDisplayController.contains(displayId)) {
                return@withContext error("display_not_found")
            }
            // Use the captured connection, not a newly obtained possibly-main-screen connection.
            val sent = runCatching {
                (session.connection as InputMethod.AccessibilityInputConnection).commitText(text, 1, null)
            }.isSuccess
            if (!sent) return@withContext error("vd_input_commit_failed")
            buildJsonObject {
                put("submitted", true)
                put("verified", false)
                put("display_id", displayId)
                put("method", "accessibility_input_connection")
                put("detail", "Unicode commit submitted to the captured VD input session. The API has no delivery acknowledgement; verify with take_screenshot(display_id=$displayId). Do not blindly retry: that can duplicate text.")
            }
        }
    }
}
