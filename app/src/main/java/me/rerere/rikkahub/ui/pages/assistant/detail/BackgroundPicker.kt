package me.rerere.rikkahub.ui.pages.assistant.detail

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.ui.components.ui.FormItem
import org.koin.compose.koinInject

/** Which wallpaper slot the user is editing. */
enum class BackgroundSlot {
    COMMON, DARK, LIGHT
}

@Composable
fun BackgroundPicker(
    modifier: Modifier = Modifier,
    background: String?,
    darkBackground: String?,
    lightBackground: String?,
    backgroundOpacity: Float = 1.0f,
    onUpdate: (slot: BackgroundSlot, value: String?) -> Unit
) {
    val filesManager: FilesManager = koinInject()
    var selectedSlot by remember { mutableStateOf(BackgroundSlot.COMMON) }
    var showPickOption by remember { mutableStateOf(false) }
    var showUrlInput by remember { mutableStateOf(false) }
    var urlInput by remember { mutableStateOf("") }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val localUris = filesManager.createChatFilesByContents(listOf(it))
            localUris.firstOrNull()?.let { localUri ->
                onUpdate(selectedSlot, localUri.toString())
            }
        }
    }

    val previewOpacity = backgroundOpacity.coerceIn(0f, 1f)

    val currentValue = when (selectedSlot) {
        BackgroundSlot.COMMON -> background
        BackgroundSlot.DARK -> darkBackground
        BackgroundSlot.LIGHT -> lightBackground
    }

    FormItem(
        modifier = modifier,
        label = {
            Text(stringResource(R.string.assistant_page_chat_background))
        },
        description = {
            Text(stringResource(R.string.assistant_page_chat_background_desc))
        }
    ) {
        // Segmented control: common / dark / light
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth()
        ) {
            SegmentedButton(
                selected = selectedSlot == BackgroundSlot.COMMON,
                onClick = { selectedSlot = BackgroundSlot.COMMON },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3),
            ) {
                Text(stringResource(R.string.assistant_page_background_slot_common))
            }
            SegmentedButton(
                selected = selectedSlot == BackgroundSlot.DARK,
                onClick = { selectedSlot = BackgroundSlot.DARK },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3),
            ) {
                Text(stringResource(R.string.assistant_page_background_slot_dark))
            }
            SegmentedButton(
                selected = selectedSlot == BackgroundSlot.LIGHT,
                onClick = { selectedSlot = BackgroundSlot.LIGHT },
                shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3),
            ) {
                Text(stringResource(R.string.assistant_page_background_slot_light))
            }
        }

        Spacer(Modifier.height(4.dp))

        Button(
            onClick = {
                showPickOption = true
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = if (currentValue != null) {
                    stringResource(R.string.assistant_page_change_background)
                } else {
                    stringResource(R.string.assistant_page_select_background)
                }
            )
        }

        if (currentValue != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.assistant_page_background_set),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = {
                        onUpdate(selectedSlot, null)
                    }
                ) {
                    Text(stringResource(R.string.assistant_page_remove))
                }
            }

            AsyncImage(
                model = currentValue,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(previewOpacity)
            )
        } else if (selectedSlot != BackgroundSlot.COMMON && background != null) {
            Text(
                text = stringResource(R.string.assistant_page_background_fallback_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (showPickOption) {
        AlertDialog(
            onDismissRequest = {
                showPickOption = false
            },
            title = {
                Text(stringResource(R.string.assistant_page_select_background))
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            showPickOption = false
                            imagePickerLauncher.launch("image/*")
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.assistant_page_select_from_gallery))
                    }
                    Button(
                        onClick = {
                            showPickOption = false
                            urlInput = ""
                            showUrlInput = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.assistant_page_enter_image_url))
                    }
                    if (currentValue != null) {
                        Button(
                            onClick = {
                                showPickOption = false
                                onUpdate(selectedSlot, null)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.assistant_page_remove_background))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showPickOption = false
                    }
                ) {
                    Text(stringResource(R.string.assistant_page_cancel))
                }
            }
        )
    }

    if (showUrlInput) {
        AlertDialog(
            onDismissRequest = {
                showUrlInput = false
            },
            title = {
                Text(stringResource(R.string.assistant_page_enter_image_url))
            },
            text = {
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    label = { Text(stringResource(R.string.assistant_page_image_url)) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("https://example.com/image.jpg") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (urlInput.isNotBlank()) {
                            onUpdate(selectedSlot, urlInput.trim())
                            showUrlInput = false
                        }
                    }
                ) {
                    Text(stringResource(R.string.assistant_page_confirm))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showUrlInput = false
                    }
                ) {
                    Text(stringResource(R.string.assistant_page_cancel))
                }
            }
        )
    }
}
