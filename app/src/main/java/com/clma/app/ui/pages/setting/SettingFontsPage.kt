package com.clma.app.ui.pages.setting

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete02
import me.rerere.hugeicons.stroke.FileImport
import com.clma.app.R
import com.clma.app.data.datastore.ChatFontFamily
import com.clma.app.data.datastore.DisplaySetting
import com.clma.app.data.files.FileFolders
import com.clma.app.data.files.FileUtils
import com.clma.app.ui.components.richtext.MarkdownBlock
import com.clma.app.ui.components.ui.CardGroup
import com.clma.app.ui.components.ui.PercentSlider
import com.clma.app.ui.components.ui.Select
import com.clma.app.ui.context.LocalToaster
import com.clma.app.ui.theme.FontScale
import com.clma.app.ui.theme.rememberChatFontFamily
import com.clma.app.ui.theme.resolveUiFontFamily
import com.clma.app.utils.plus
import org.koin.androidx.compose.koinViewModel
import java.io.File
import androidx.compose.ui.platform.LocalDensity
import com.clma.app.ui.theme.LocalBaseFontScale
import androidx.compose.material3.MaterialTheme


/** 字体与排版: chat and app fonts, custom font files and both text-size sliders. */
@Composable
fun SettingFontsPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var displaySetting by remember(settings) { mutableStateOf(settings.displaySetting) }
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val chatFontFamily = rememberChatFontFamily(displaySetting)
    val uiFontFamily = remember(displaySetting.uiFontFamily, displaySetting.uiCustomFontPath) {
        displaySetting.resolveUiFontFamily(context)
    }

    fun updateDisplaySetting(setting: DisplaySetting) {
        displaySetting = setting
        vm.updateSettings(settings.copy(displaySetting = setting))
    }

    val importSuccessMsg = stringResource(R.string.setting_display_page_custom_font_import_success)
    val importFailedMsg = stringResource(R.string.setting_display_page_custom_font_import_failed)
    val fontPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    importCustomChatFontInternal(context, uri, CHAT_FONT_PREFIX)
                }
            }.onSuccess { importedFont ->
                updateDisplaySetting(
                    displaySetting.copy(
                        chatFontFamily = ChatFontFamily.CUSTOM,
                        chatCustomFontPath = importedFont.relativePath,
                        chatCustomFontName = importedFont.displayName,
                    )
                )
                toaster.show(importSuccessMsg, type = ToastType.Success)
            }.onFailure { error ->
                toaster.show(importFailedMsg.format(error.message.orEmpty()), type = ToastType.Error)
            }
        }
    }

    val uiFontPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    importCustomChatFontInternal(context, uri, UI_FONT_PREFIX)
                }
            }.onSuccess { importedFont ->
                updateDisplaySetting(
                    displaySetting.copy(
                        uiFontFamily = ChatFontFamily.CUSTOM,
                        uiCustomFontPath = importedFont.relativePath,
                        uiCustomFontName = importedFont.displayName,
                    )
                )
                toaster.show(importSuccessMsg, type = ToastType.Success)
            }.onFailure { error ->
                toaster.show(importFailedMsg.format(error.message.orEmpty()), type = ToastType.Error)
            }
        }
    }

    SettingSubPageScaffold(title = stringResource(R.string.setting_hub_fonts)) {
        item {
            CardGroup(
                modifier = Modifier.padding(horizontal = 8.dp),
                title = { Text(stringResource(R.string.setting_page_font_display_settings)) },
            ) {
                item(
                    headlineContent = { Text(stringResource(R.string.setting_display_page_chat_font_family_title)) },
                    supportingContent = {
                        Select(
                            options = ChatFontFamily.entries,
                            selectedOption = displaySetting.chatFontFamily,
                            onOptionSelected = { family ->
                                if (family == ChatFontFamily.CUSTOM && displaySetting.chatCustomFontPath.isBlank()) {
                                    fontPickerLauncher.launch(CustomFontMimeTypesUI)
                                } else {
                                    updateDisplaySetting(displaySetting.copy(chatFontFamily = family))
                                }
                            },
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .fillMaxWidth(),
                            optionToString = { it.labelUI() },
                            optionLeading = { family ->
                                Text(
                                    text = "Aa",
                                    fontFamily = family.toFontFamilyUI(chatFontFamily),
                                )
                            }
                        )
                    }
                )
                item(
                    headlineContent = { Text(stringResource(R.string.setting_display_page_custom_font_title)) },
                    supportingContent = {
                        Text(
                            if (displaySetting.chatCustomFontName.isNotBlank()) {
                                displaySetting.chatCustomFontName
                            } else {
                                stringResource(R.string.setting_display_page_custom_font_not_imported)
                            }
                        )
                    },
                    trailingContent = {
                        Row {
                            IconButton(
                                onClick = { fontPickerLauncher.launch(CustomFontMimeTypesUI) }
                            ) {
                                Icon(
                                    HugeIcons.FileImport,
                                    contentDescription = stringResource(
                                        R.string.setting_display_page_custom_font_import
                                    )
                                )
                            }
                            if (displaySetting.chatCustomFontPath.isNotBlank()) {
                                IconButton(
                                    onClick = {
                                        deleteCustomChatFontInternal(context, displaySetting.chatCustomFontPath)
                                        updateDisplaySetting(
                                            displaySetting.copy(
                                                chatFontFamily = ChatFontFamily.DEFAULT,
                                                chatCustomFontPath = "",
                                                chatCustomFontName = "",
                                            )
                                        )
                                    }
                                ) {
                                    Icon(
                                        HugeIcons.Delete02,
                                        contentDescription = stringResource(
                                            R.string.setting_display_page_custom_font_remove
                                        )
                                    )
                                }
                            }
                        }
                    }
                )
                item(
                    headlineContent = { Text(stringResource(R.string.setting_display_page_ui_font_family_title)) },
                    supportingContent = {
                        Column {
                            Text(stringResource(R.string.setting_display_page_ui_font_family_desc))
                            Select(
                                options = ChatFontFamily.entries,
                                selectedOption = displaySetting.uiFontFamily,
                                onOptionSelected = { family ->
                                    if (family == ChatFontFamily.CUSTOM && displaySetting.uiCustomFontPath.isBlank()) {
                                        uiFontPickerLauncher.launch(CustomFontMimeTypesUI)
                                    } else {
                                        updateDisplaySetting(displaySetting.copy(uiFontFamily = family))
                                    }
                                },
                                modifier = Modifier
                                    .padding(top = 4.dp)
                                    .fillMaxWidth(),
                                optionToString = { it.labelUI() },
                                optionLeading = { family ->
                                    Text(
                                        text = "Aa",
                                        fontFamily = family.toFontFamilyUI(uiFontFamily),
                                    )
                                }
                            )
                        }
                    }
                )
                item(
                    headlineContent = { Text(stringResource(R.string.setting_display_page_ui_custom_font_title)) },
                    supportingContent = {
                        Text(
                            if (displaySetting.uiCustomFontName.isNotBlank()) {
                                displaySetting.uiCustomFontName
                            } else {
                                stringResource(R.string.setting_display_page_custom_font_not_imported)
                            }
                        )
                    },
                    trailingContent = {
                        Row {
                            IconButton(
                                onClick = { uiFontPickerLauncher.launch(CustomFontMimeTypesUI) }
                            ) {
                                Icon(
                                    HugeIcons.FileImport,
                                    contentDescription = stringResource(
                                        R.string.setting_display_page_custom_font_import
                                    )
                                )
                            }
                            if (displaySetting.uiCustomFontPath.isNotBlank()) {
                                IconButton(
                                    onClick = {
                                        deleteCustomChatFontInternal(context, displaySetting.uiCustomFontPath)
                                        updateDisplaySetting(
                                            displaySetting.copy(
                                                uiFontFamily = ChatFontFamily.DEFAULT,
                                                uiCustomFontPath = "",
                                                uiCustomFontName = "",
                                            )
                                        )
                                    }
                                ) {
                                    Icon(
                                        HugeIcons.Delete02,
                                        contentDescription = stringResource(
                                            R.string.setting_display_page_custom_font_remove
                                        )
                                    )
                                }
                            }
                        }
                    }
                )
                item(
                    headlineContent = { Text(stringResource(R.string.setting_display_page_font_size_title)) },
                    supportingContent = {
                        Column {
                            PercentSlider(
                                value = displaySetting.fontSizeRatio,
                                onValueChange = {
                                    updateDisplaySetting(displaySetting.copy(fontSizeRatio = it))
                                },
                                valueRange = FontScale.CHAT_RANGE,
                                title = stringResource(R.string.setting_display_page_font_size_title),
                            ) { ratio ->
                                // Follows the thumb while dragging; the setting is saved on release.
                                MarkdownBlock(
                                    content = stringResource(R.string.setting_display_page_font_size_preview),
                                    style = LocalTextStyle.current.copy(
                                        fontSize = LocalTextStyle.current.fontSize * ratio,
                                        lineHeight = LocalTextStyle.current.lineHeight * ratio,
                                        fontFamily = chatFontFamily
                                    )
                                )
                            }
                        }
                    }
                )
                item(
                    headlineContent = { Text(stringResource(R.string.setting_display_page_ui_font_scale_title)) },
                    supportingContent = {
                        Column {
                            Text(stringResource(R.string.setting_display_page_ui_font_scale_desc))
                            PercentSlider(
                                value = FontScale.uiScale(displaySetting.uiFontScale),
                                onValueChange = {
                                    updateDisplaySetting(displaySetting.copy(uiFontScale = it))
                                },
                                valueRange = FontScale.UI_RANGE,
                                title = stringResource(R.string.setting_display_page_ui_font_scale_title),
                            ) { candidate ->
                                // The app already renders at the saved scale; scale the sample
                                // by candidate/current so it shows the size the thumb points at.
                                val currentScale = LocalDensity.current.fontScale
                                val systemScale = LocalBaseFontScale.current ?: currentScale
                                val ratio = FontScale.previewRatio(systemScale, currentScale, candidate)
                                val sample = MaterialTheme.typography.bodyMedium
                                Text(
                                    text = stringResource(R.string.setting_display_page_ui_font_scale_preview),
                                    style = sample.copy(
                                        fontSize = sample.fontSize * ratio,
                                        lineHeight = sample.lineHeight * ratio,
                                    ),
                                )
                            }
                        }
                    }
                )
            }
        }
    }
}

private val CustomFontMimeTypesUI = arrayOf(
    "font/*",
    "application/x-font-ttf",
    "application/x-font-otf",
    "application/vnd.ms-opentype",
    "application/octet-stream",
    "*/*",
)

private val CustomFontExtensionsUI = setOf("ttf", "otf", "ttc")

private data class ImportedChatFontUI(
    val relativePath: String,
    val displayName: String,
)

@Composable
private fun ChatFontFamily.labelUI(): String = when (this) {
    ChatFontFamily.DEFAULT -> stringResource(R.string.setting_display_page_chat_font_family_default)
    ChatFontFamily.SERIF -> stringResource(R.string.setting_display_page_chat_font_family_serif)
    ChatFontFamily.MONOSPACE -> stringResource(R.string.setting_display_page_chat_font_family_monospace)
    ChatFontFamily.CUSTOM -> stringResource(R.string.setting_display_page_chat_font_family_custom)
}

private fun ChatFontFamily.toFontFamilyUI(customFontFamily: FontFamily): FontFamily = when (this) {
    ChatFontFamily.DEFAULT -> FontFamily.Default
    ChatFontFamily.SERIF -> FontFamily.Serif
    ChatFontFamily.MONOSPACE -> FontFamily.Monospace
    ChatFontFamily.CUSTOM -> customFontFamily
}

// Chat and UI fonts are stored side by side in fonts/ (archived by the FONTS backup item) and
// never replace each other: each import only swaps files with its own prefix.
private const val CHAT_FONT_PREFIX = "chat_font"
private const val UI_FONT_PREFIX = "ui_font"

private fun importCustomChatFontInternal(context: Context, uri: Uri, prefix: String): ImportedChatFontUI {
    val displayName = FileUtils.getFileNameFromUri(context, uri)?.takeIf { it.isNotBlank() } ?: "custom_font"
    val extension = displayName.substringAfterLast('.', "")
        .lowercase()
        .takeIf { it in CustomFontExtensionsUI }
        ?: "ttf"
    val fontDir = File(context.filesDir, FileFolders.FONTS).apply { mkdirs() }
    val targetFile = File(fontDir, "$prefix.${System.currentTimeMillis()}.$extension")
    val tempFile = File(fontDir, "${prefix}_import.tmp")

    try {
        tempFile.delete()
        context.contentResolver.openInputStream(uri)?.use { input ->
            tempFile.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: error("Unable to open selected font")

        runCatching {
            Typeface.createFromFile(tempFile)
        }.onFailure { error ->
            throw IllegalArgumentException(error.message ?: "Invalid font file", error)
        }

        replaceCustomChatFontInternal(fontDir, tempFile, targetFile, prefix)
    } catch (error: Throwable) {
        tempFile.delete()
        throw error
    }

    val relativePath = FileUtils.getRelativePathInFilesDir(context.filesDir, targetFile)
        ?: "${FileFolders.FONTS}/${targetFile.name}"
    return ImportedChatFontUI(relativePath = relativePath, displayName = displayName)
}

private fun replaceCustomChatFontInternal(fontDir: File, tempFile: File, targetFile: File, prefix: String) {
    val existingFiles = fontDir.listFiles { file ->
        file.isFile && file.name.startsWith("$prefix.") && file != tempFile
    }?.toList().orEmpty()
    val backups = existingFiles.map { file ->
        file to File(fontDir, "previous_${file.name}").also { it.delete() }
    }

    try {
        backups.forEach { (file, backup) ->
            check(file.renameTo(backup)) { "Unable to prepare existing font for replacement" }
        }
        check(tempFile.renameTo(targetFile)) { "Unable to save selected font" }
        backups.forEach { (_, backup) -> backup.delete() }
    } catch (error: Throwable) {
        tempFile.delete()
        backups.forEach { (file, backup) ->
            if (!file.exists() && backup.exists()) {
                backup.renameTo(file)
            }
        }
        throw error
    }
}

private fun deleteCustomChatFontInternal(context: Context, relativePath: String) {
    val filesDir = runCatching { context.filesDir.canonicalFile }.getOrNull() ?: return
    val fontFile = runCatching { File(filesDir, relativePath).canonicalFile }.getOrNull() ?: return
    if (fontFile.path.startsWith("${filesDir.path}${File.separator}")) {
        fontFile.delete()
    }
}
