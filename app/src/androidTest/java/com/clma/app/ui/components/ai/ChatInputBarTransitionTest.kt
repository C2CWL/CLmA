package com.clma.app.ui.components.ai

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Device/UI coverage; the standard build workflow only runs JVM tests, not these tests. */
class ChatInputBarTransitionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun focusSelectionAndDraftSurviveOpeningAndMidFlightReversal() {
        val expanded = mutableStateOf(false)
        val field = TextFieldState("draft ".repeat(30))
        var mounts = 0
        var releases = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme {
                ChatInputBar(
                    expanded = expanded.value,
                    modifier = Modifier.width(280.dp),
                    left = { Box(Modifier.size(if (expanded.value) 160.dp else 32.dp, 32.dp)) },
                    right = { Box(Modifier.size(if (expanded.value) 100.dp else 64.dp, 36.dp)) },
                    center = {
                        DisposableEffect(field) { mounts++; onDispose { releases++ } }
                        BasicTextField(
                            state = field,
                            modifier = Modifier.heightIn(min = 56.dp).testTag("field"),
                        )
                    },
                )
            }
        }
        compose.onNodeWithTag("field").performClick()
        val initialWidth = compose.onNodeWithTag("field").fetchSemanticsNode().size.width
        val selected = TextRange(2, 7)
        compose.runOnIdle {
            field.edit { selection = selected }
            expanded.value = true
        }
        compose.mainClock.advanceTimeBy(112)
        val middleWidth = compose.onNodeWithTag("field").fetchSemanticsNode().size.width
        compose.runOnIdle { expanded.value = false }
        compose.mainClock.advanceTimeBy(48)
        compose.runOnIdle { expanded.value = true }
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithTag("field").assertIsFocused()
        val finalWidth = compose.onNodeWithTag("field").fetchSemanticsNode().size.width
        assertTrue(initialWidth <= middleWidth && middleWidth <= finalWidth)
        compose.runOnIdle {
            assertEquals("draft ".repeat(30), field.text.toString())
            assertEquals(selected, field.selection)
            assertEquals(1, mounts)
            assertEquals(0, releases)
        }
    }

    @Test fun emptyFieldStaysFocusedWhenExpandedActionsChangeWidth() {
        val expanded = mutableStateOf(false)
        val labelWidth = mutableStateOf(100)
        val field = TextFieldState()
        compose.setContent {
            MaterialTheme {
                ChatInputBar(
                    expanded = expanded.value,
                    modifier = Modifier.width(240.dp),
                    left = { Box(Modifier.size(if (expanded.value) labelWidth.value.dp else 30.dp, 32.dp)) },
                    right = { Box(Modifier.size(64.dp, 36.dp)) },
                    center = { BasicTextField(state = field, modifier = Modifier.heightIn(min = 56.dp).testTag("field")) },
                )
            }
        }
        compose.onNodeWithTag("field").performClick()
        compose.runOnIdle { expanded.value = true; labelWidth.value = 220 }
        compose.waitForIdle()
        compose.onNodeWithTag("field").assertIsFocused()
        compose.runOnIdle { assertEquals("", field.text.toString()) }
    }
}
