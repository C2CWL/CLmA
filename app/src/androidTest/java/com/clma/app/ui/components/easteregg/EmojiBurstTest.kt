package com.clma.app.ui.components.easteregg

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class EmojiBurstTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun idleHostDoesNotRequestFramesAndBurstRestartsAnimation() {
        var burst: (Offset) -> Unit = {}
        compose.mainClock.autoAdvance = false
        compose.setContent {
            EmojiBurstHost(
                modifier = Modifier.size(200.dp),
                emojiOptions = listOf("🎉"),
            ) { onBurst ->
                burst = onBurst
                Box(Modifier.size(200.dp))
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { assertFalse(compose.mainClock.hasPendingWork) }

        compose.runOnIdle { burst(Offset(100f, 100f)) }
        compose.mainClock.advanceTimeBy(100)
        compose.runOnIdle { assertTrue(compose.mainClock.hasPendingWork) }

        compose.mainClock.advanceTimeBy(30_000)
        compose.runOnIdle { assertFalse(compose.mainClock.hasPendingWork) }
    }
}
