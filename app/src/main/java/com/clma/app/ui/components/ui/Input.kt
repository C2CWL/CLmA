package com.clma.app.ui.components.ui

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import com.clma.app.ui.hooks.SyncedTextFieldState
import com.clma.app.ui.hooks.rememberSyncedTextField

@Composable
fun <T : Number> OutlinedNumberInput(
    value: T,
    onValueChange: (T) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "",
    colors: TextFieldColors = OutlinedTextFieldDefaults.colors()
) {
    val field = rememberNumberInputField(value, onValueChange)
    OutlinedTextField(
        modifier = modifier,
        value = field.value,
        onValueChange = field::onValueChange,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        isError = !field.text.isValidNumberInput(),
        colors = colors
    )
}

@Composable
fun <T : Number> NumberInput(
    value: T,
    onValueChange: (T) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "",
    colors: TextFieldColors = TextFieldDefaults.colors()
) {
    val field = rememberNumberInputField(value, onValueChange)
    TextField(
        modifier = modifier,
        value = field.value,
        onValueChange = field::onValueChange,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        isError = !field.text.isValidNumberInput(),
        colors = colors
    )
}

/**
 * Local text state for the number inputs. It used to be `remember(value)`, so when the caller
 * persists asynchronously a late echo of an earlier keystroke re-created the text and dropped
 * what was typed since. Now the value only replaces the text when it changes from elsewhere.
 */
@Composable
private fun <T : Number> rememberNumberInputField(
    value: T,
    onValueChange: (T) -> Unit,
): SyncedTextFieldState = rememberSyncedTextField(
    externalValue = value.toString(),
    key = Unit,
    expectedEcho = { typed -> parseNumberInput(typed, value)?.toString() ?: typed },
) { typed ->
    parseNumberInput(typed, value)?.let(onValueChange)
}

private fun <T : Number> parseNumberInput(text: String, like: T): T? {
    if (!text.isValidNumberInput()) return null
    return try {
        @Suppress("UNCHECKED_CAST")
        when (like) {
            is Int -> text.toInt() as T
            is Float -> text.toFloat() as T
            is Double -> text.toDouble() as T
            else -> null
        }
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

private val NumberRegex = Regex("^[+-]?\\d+(\\.\\d+)?$")
private fun String.isValidNumberInput() = this.isNotEmpty() && NumberRegex.matches(this)
