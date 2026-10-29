package com.clma.app.ui.hooks

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * Local, IME-safe state for a text field whose value is persisted asynchronously.
 *
 * Use with the `TextFieldValue` overload of `OutlinedTextField` / `TextField`:
 *
 * ```
 * val name = rememberSyncedTextField(assistant.name, key = assistant.id) { text ->
 *     vm.updateAssistant { it.copy(name = text) }
 * }
 * OutlinedTextField(value = name.value, onValueChange = name::onValueChange)
 * ```
 *
 * The field is always driven by local state (text, selection and IME composition are kept), every
 * text change is pushed through `onValueChange`, and the persisted value only replaces the local
 * text when it changes from outside (see [ExternalTextSync]). Changing `key` (e.g. switching to
 * another assistant / provider) re-initialises the field from the external value.
 */
@Stable
class SyncedTextFieldState internal constructor(
    initialText: String,
    private val onPush: State<(String) -> Unit>,
    private val expectedEcho: State<(String) -> String>,
) {
    private val sync = ExternalTextSync(initialText)

    var value: TextFieldValue by mutableStateOf(
        TextFieldValue(initialText, TextRange(initialText.length))
    )
        private set

    val text: String get() = value.text

    fun onValueChange(newValue: TextFieldValue) {
        val textChanged = newValue.text != value.text
        value = newValue
        if (textChanged) {
            sync.onLocalEdit(expectedEcho.value(newValue.text))
            onPush.value(newValue.text)
        }
    }

    /** String-based variant of [onValueChange], for fields that only take a `String` (e.g. `SelectTextField`). */
    fun onTextChange(newText: String) {
        if (newText != value.text) {
            onValueChange(TextFieldValue(newText, TextRange(newText.length)))
        }
    }

    /** Replaces the local text without pushing (e.g. normalising a number on blur). */
    fun replaceText(text: String) {
        if (text != value.text) {
            value = TextFieldValue(text, TextRange(text.length))
        }
    }

    internal fun onExternal(external: String) {
        val adopted = sync.onExternal(external, value.text) ?: return
        value = TextFieldValue(adopted, TextRange(adopted.length))
    }
}

/**
 * @param externalValue the persisted value as currently observed (may lag behind the user).
 * @param key identity of the edited entity; a new key resets the field from [externalValue].
 * @param expectedEcho maps the typed text to what [externalValue] will read after the write lands,
 *   for callers that normalise before persisting (trim, parse numbers, ...). Defaults to identity.
 * @param onValueChange called with the new text on every text change; persist it from here.
 */
@Composable
fun rememberSyncedTextField(
    externalValue: String,
    key: Any?,
    expectedEcho: (String) -> String = { it },
    onValueChange: (String) -> Unit,
): SyncedTextFieldState {
    val latestPush = rememberUpdatedState(onValueChange)
    val latestEcho = rememberUpdatedState(expectedEcho)
    val state = remember(key) { SyncedTextFieldState(externalValue, latestPush, latestEcho) }
    LaunchedEffect(state, externalValue) {
        state.onExternal(externalValue)
    }
    return state
}

private class TextFieldStateSyncHolder(initialText: String) {
    val sync = ExternalTextSync(initialText)
    var lastSeenText: String = initialText
}

/**
 * [rememberSyncedTextField] for the `TextFieldState` based fields (`TextArea`, `BasicTextField(state)`).
 *
 * The returned state is owned locally; every text change is pushed through [onValueChange], and
 * [externalValue] only replaces the text when it changes from outside or when [key] changes.
 */
@Composable
fun rememberSyncedTextFieldState(
    externalValue: String,
    key: Any?,
    onValueChange: (String) -> Unit,
): TextFieldState {
    val latestPush = rememberUpdatedState(onValueChange)
    val state = remember(key) { TextFieldState(externalValue) }
    val holder = remember(key) { TextFieldStateSyncHolder(externalValue) }
    LaunchedEffect(state) {
        snapshotFlow { state.text.toString() }.collect { text ->
            if (text != holder.lastSeenText) {
                holder.lastSeenText = text
                holder.sync.onLocalEdit(text)
                latestPush.value(text)
            }
        }
    }
    LaunchedEffect(state, externalValue) {
        val adopted = holder.sync.onExternal(externalValue, state.text.toString()) ?: return@LaunchedEffect
        holder.lastSeenText = adopted
        state.setTextAndPlaceCursorAtEnd(adopted)
    }
    return state
}

/** [rememberSyncedTextField] `expectedEcho` for an optional positive Int persisted as `text.toIntOrNull()?.takeIf { it > 0 }`. */
fun positiveIntEcho(text: String): String =
    if (text.isBlank()) "" else text.toIntOrNull()?.takeIf { it > 0 }?.toString() ?: ""

/** [rememberSyncedTextField] `expectedEcho` for an optional positive Long persisted as `text.toLongOrNull()?.takeIf { it > 0 }`. */
fun positiveLongEcho(text: String): String =
    if (text.isBlank()) "" else text.toLongOrNull()?.takeIf { it > 0 }?.toString() ?: ""
