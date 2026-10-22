package me.rerere.rikkahub.ui.components.ui

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
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.theme.FontScale

/**
 * A continuous (1% granularity, no step dots) ratio slider with an editable percentage: tapping
 * the "NN%" label opens a small integer input. Typed values are clamped to [valueRange]; input
 * that is not an integer percentage is ignored and the current value is kept.
 *
 * While the thumb is dragged the value lives in local state (and drives [preview]); it is handed
 * to [onValueChange] once, on release, so a drag does not persist settings — and re-lay out the
 * whole app — on every 1% tick.
 */
@Composable
fun PercentSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    title: String,
    modifier: Modifier = Modifier,
    preview: (@Composable (Float) -> Unit)? = null,
) {
    var editing by remember { mutableStateOf(false) }
    // Non-null only while dragging.
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
                onValueChange = { raw -> dragValue = FontScale.snap(raw, valueRange) },
                onValueChangeFinished = {
                    val released = dragValue
                    dragValue = null
                    if (released != null && released != value) onValueChange(released)
                },
                valueRange = valueRange,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { editing = true }) {
                Text(text = "${FontScale.percentLabel(shown)}%")
            }
        }
        preview?.invoke(shown)
    }
    if (editing) {
        var input by remember { mutableStateOf(FontScale.percentLabel(value).toString()) }
        val confirm = {
            FontScale.parsePercent(input, valueRange)?.let(onValueChange)
            editing = false
        }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(title) },
            text = {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it.filter { c -> c.isDigit() }.take(3) },
                    singleLine = true,
                    suffix = { Text("%") },
                    supportingText = {
                        Text(
                            "${FontScale.percentLabel(valueRange.start)}% – " +
                                "${FontScale.percentLabel(valueRange.endInclusive)}%"
                        )
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
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
