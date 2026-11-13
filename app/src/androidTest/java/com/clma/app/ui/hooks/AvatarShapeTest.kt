package com.clma.app.ui.hooks

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test

class AvatarShapeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun idleAvatarStaysCircularAndLoadingCanStopAndRestart() {
        val loading = mutableStateOf(false)
        var shape: Shape = CircleShape
        var compositions = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val current = rememberAvatarShape(loading.value)
            SideEffect {
                shape = current
                compositions++
            }
        }
        compose.runOnIdle { assertEquals(CircleShape, shape) }
        val idleCompositions = compositions
        compose.mainClock.advanceTimeBy(500)
        compose.runOnIdle { assertEquals(idleCompositions, compositions) }

        compose.runOnIdle { loading.value = true }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { assertNotEquals(CircleShape, shape) }
        compose.runOnIdle { loading.value = false }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { assertEquals(CircleShape, shape) }
        val stoppedCompositions = compositions
        compose.mainClock.advanceTimeBy(500)
        compose.runOnIdle { assertEquals(stoppedCompositions, compositions) }

        compose.runOnIdle { loading.value = true }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { assertNotEquals(CircleShape, shape) }
    }
}
