package com.clma.app.ui.components.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.width
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AppAlertDialogTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun bothActionsKeepTheirCallbacksAndMinimumTouchHeight() {
        var confirms = 0
        var dismisses = 0
        compose.setContent {
            MaterialTheme {
                AppAlertDialog(
                    onDismissRequest = {},
                    title = { Text("Settings") },
                    confirmButton = {
                        DialogPrimaryButton(onClick = { confirms++ }) { Text("Save") }
                    },
                    dismissButton = {
                        DialogSecondaryButton(onClick = { dismisses++ }) { Text("Cancel") }
                    },
                )
            }
        }

        compose.onNodeWithText("Save").assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText("Cancel").assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle {
            assertEquals(1, confirms)
            assertEquals(1, dismisses)
        }
    }

    @Test
    fun confirmOnlyDialogDoesNotRequireADismissAction() {
        var confirms = 0
        compose.setContent {
            MaterialTheme {
                AppAlertDialog(
                    onDismissRequest = {},
                    confirmButton = {
                        DialogPrimaryButton(onClick = { confirms++ }) { Text("OK") }
                    },
                )
            }
        }

        compose.onNodeWithText("OK").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, confirms) }
    }

    @Test
    fun dismissOnlyDialogDoesNotRequireAConfirmAction() {
        var dismisses = 0
        compose.setContent {
            MaterialTheme {
                AppAlertDialog(
                    onDismissRequest = {},
                    confirmButton = null,
                    dismissButton = {
                        DialogSecondaryButton(onClick = { dismisses++ }) { Text("Close") }
                    },
                )
            }
        }

        compose.onNodeWithText("Close").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, dismisses) }
    }

    @Test
    fun hidingDismissActionDoesNotLeaveAnEmptyButtonColumn() {
        val running = mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                AppAlertDialog(
                    onDismissRequest = {},
                    confirmButton = {
                        DialogPrimaryButton(onClick = {}) { Text("Continue") }
                    },
                    dismissButton = if (running.value) null else {
                        {
                            DialogSecondaryButton(onClick = {}) { Text("Cancel") }
                        }
                    },
                )
            }
        }

        val initialWidth = compose.onNodeWithText("Continue").fetchSemanticsNode().boundsInRoot.width
        compose.runOnIdle { running.value = true }
        compose.onNodeWithText("Cancel").assertDoesNotExist()
        val fullWidth = compose.onNodeWithText("Continue").fetchSemanticsNode().boundsInRoot.width
        assertTrue("The remaining action must fill the freed column", fullWidth > initialWidth)
    }

    @Test
    fun primaryAndSecondaryActionsPreserveDisabledSemantics() {
        compose.setContent {
            MaterialTheme {
                AppAlertDialog(
                    onDismissRequest = {},
                    confirmButton = {
                        DialogPrimaryButton(onClick = {}, enabled = false, destructive = true) {
                            Text("Delete")
                        }
                    },
                    dismissButton = {
                        DialogSecondaryButton(onClick = {}, enabled = false) { Text("Cancel") }
                    },
                )
            }
        }

        compose.onNodeWithText("Delete").assertIsNotEnabled()
        compose.onNodeWithText("Cancel").assertIsNotEnabled()
    }

    @Test
    fun narrowDialogStacksActionsWithLargeFonts() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                MaterialTheme {
                    AppAlertDialog(
                        onDismissRequest = {},
                        modifier = Modifier.width(280.dp),
                        confirmButton = {
                            DialogPrimaryButton(onClick = {}) { Text("Save all changes") }
                        },
                        dismissButton = {
                            DialogSecondaryButton(onClick = {}) { Text("Keep editing") }
                        },
                    )
                }
            }
        }

        val dismiss = compose.onNodeWithText("Keep editing").assertIsDisplayed().fetchSemanticsNode()
        val confirm = compose.onNodeWithText("Save all changes").assertIsDisplayed().fetchSemanticsNode()
        assertTrue("Narrow dialogs must wrap rather than overlap actions", confirm.boundsInRoot.top >= dismiss.boundsInRoot.bottom)
    }
}
