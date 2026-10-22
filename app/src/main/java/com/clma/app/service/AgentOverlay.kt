package com.clma.app.service

import android.content.Context
import android.provider.Settings
import com.clma.app.service.island.TopIslandController

/**
 * "The agent is working" signal for every generation turn (chat, Telegram, automation…),
 * raised by GenerationLoop when a turn starts and cleared when it ends.
 *
 * It used to draw its own text-only pill. That pill is now the top island
 * ([TopIslandController]): a turn that belongs to a chat conversation shows the full island with
 * status, input and stop; any other turn shows the island's plain "运行中" capsule. When the edge
 * bar is on screen the bar keeps carrying the signal too (see [FloatingBallService.setWorking]),
 * and the plain capsule steps aside for it.
 */
object AgentOverlay {

    fun canShow(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun show(context: Context) {
        val app = context.applicationContext
        // No-op unless the edge bar is actually up.
        FloatingBallService.setWorking(app, true)
        TopIslandController.onGenericTurnStarted()
    }

    fun hide(context: Context) {
        val app = context.applicationContext
        // No-op unless the edge bar is actually up (see setWorking), so this is safe unconditionally.
        FloatingBallService.setWorking(app, false)
        TopIslandController.onGenericTurnEnded()
    }
}
