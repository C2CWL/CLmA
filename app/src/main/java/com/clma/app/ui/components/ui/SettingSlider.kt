package com.clma.app.ui.components.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.clma.app.R
import kotlin.math.roundToInt

/**
 * Continuous (no step dots by default) slider with a tappable value label that opens a
 * number dialog. Drag updates live in local state; [onValueChange] fires once on release
 * so settings are not rewritten on every pixel.
 *
 * Use [steps] = 0 (default) to hide the old fixed-dot track. Discrete values still snap via
 * [snap] on drag / release / typed input.
 */
@Composable
fun SettingSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    title: String,
    valueLabel: (Float) -> String,
    parseInput: (String) -> Float?,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    snap: (Float) -> Float = { raw -> raw.coerceIn(valueRange) },
    rangeHint: String? = null,
    preview: (@Composable (Float) -> Unit)? = null,
) {
    var editing by remember { mutableStateOf(false) }
    var dragValue by remember { mutableStateOf<Float?>(null) }
    val shown = dragValue ?: value
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Slider(
                value = shown.coerceIn(valueRange),
                onValueChange = { raw -> dragValue = snap(raw) },
                onValueChangeFinished = {
                    val released = dragValue
                    dragValue = null
                    if (released != null && released != value) onValueChange(released)
                },
                valueRange = valueRange,
                steps = steps,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { editing = true }) {
                Text(text = valueLabel(shown))
            }
        }
        preview?.invoke(shown)
    }
    if (editing) {
        var input by remember { mutableStateOf(valueLabel(value).filter { it.isDigit() || it == '.' || it == '-' }) }
        val confirm = {
            parseInput(input)?.let { parsed -> onValueChange(snap(parsed)) }
            editing = false
        }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(title) },
            text = {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it.filter { c -> c.isDigit() || c == '.' || c == '-' }.take(8) },
                    singleLine = true,
                    supportingText = rangeHint?.let { { Text(it) } },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { confirm() }),
                )
            },
            confirmButton = {
                TextButton(onClick = { confirm() }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { editing = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

/** Snap to the nearest whole number inside [range]. */
fun snapToInt(value: Float, range: ClosedFloatingPointRange<Float>): Float =
    value.roundToInt().toFloat().coerceIn(range)

/** Parse a plain integer (optional unit suffix stripped by the caller filter). */
fun parseIntInRange(input: String, range: ClosedFloatingPointRange<Float>): Float? {
    val n = input.trim().toIntOrNull() ?: return null
    return n.toFloat().coerceIn(range)
}
