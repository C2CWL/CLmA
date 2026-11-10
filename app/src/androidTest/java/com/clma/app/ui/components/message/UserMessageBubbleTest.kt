package com.clma.app.ui.components.message

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.clma.app.data.datastore.Settings
import com.clma.app.ui.components.ui.AppMenuEntry
import com.clma.app.ui.context.LocalSettings
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class UserMessageBubbleTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun tapDoesNothingAndLongPressOpensActionsWithoutEditing() {
        var edits = 0
        compose.setContent {
            CompositionLocalProvider(LocalSettings provides Settings()) {
                MaterialTheme {
                    UserMessageBubble(
                        text = "Sent message",
                        bubbleColor = MaterialTheme.colorScheme.primaryContainer,
                        menuItems =
                            listOf(
                                AppMenuEntry(text = "Copy", onClick = {}),
                                AppMenuEntry(text = "Select and copy", onClick = {}),
                                AppMenuEntry(text = "Edit", onClick = { edits++ }),
                                AppMenuEntry(text = "Delete", destructive = true, onClick = {}),
                            ),
                        onClickCitation = {},
                    )
                }
            }
        }

        compose.onNodeWithText("Sent message").performTouchInput { click() }
        compose.onNodeWithText("Edit").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, edits) }

        compose.onNodeWithText("Sent message").performTouchInput { longClick() }
        compose.onNodeWithText("Copy").assertIsDisplayed()
        compose.onNodeWithText("Select and copy").assertIsDisplayed()
        compose.onNodeWithText("Delete").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, edits) }
        compose.onNodeWithText("Edit").performClick()
        compose.runOnIdle { assertEquals(1, edits) }
        compose.onNodeWithText("Edit").assertDoesNotExist()
    }
}
