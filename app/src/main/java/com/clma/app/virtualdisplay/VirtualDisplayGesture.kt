package com.clma.app.virtualdisplay

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent

/** Runs only in the shell/root VD process. Never falls back to the default display. */
internal object VirtualDisplayGesture {
    fun swipe(displayId: Int, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): Boolean {
        require(displayId > 0)
        val managerClass = Class.forName("android.hardware.input.InputManager")
        val manager = managerClass.getMethod("getInstance").invoke(null)
        val inject = managerClass.getMethod("injectInputEvent", android.view.InputEvent::class.java, Int::class.javaPrimitiveType)
        val setDisplay = android.view.InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, x: Float, y: Float): Boolean {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            try {
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                setDisplay.invoke(event, displayId)
                return inject.invoke(manager, event, 2) as Boolean // WAIT_FOR_FINISH
            } finally {
                event.recycle()
            }
        }
        var x = x1.toFloat()
        var y = y1.toFloat()
        var released = false
        if (!send(MotionEvent.ACTION_DOWN, x, y)) return false
        try {
            // Time-based ease-out avoids compressing the gesture if injection is slow.
            while (true) {
                val elapsed = SystemClock.uptimeMillis() - down
                val t = (elapsed.toFloat() / durationMs).coerceIn(0f, 1f)
                val fraction = swipeFraction(t)
                x = x1 + (x2 - x1) * fraction
                y = y1 + (y2 - y1) * fraction
                if (!send(MotionEvent.ACTION_MOVE, x, y)) return false
                if (t >= 1f) break
                SystemClock.sleep(16)
            }
            // Repeated stationary MOVE samples flush release velocity without a second tap.
            repeat(12) {
                SystemClock.sleep(16)
                if (!send(MotionEvent.ACTION_MOVE, x, y)) return false
            }
            released = send(MotionEvent.ACTION_UP, x, y)
            return released
        } finally {
            if (!released) runCatching { send(MotionEvent.ACTION_CANCEL, x, y) }
        }
    }
}
