package com.clma.app.ui.theme

import android.content.Context
import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import com.clma.app.data.datastore.ChatFontFamily
import com.clma.app.data.datastore.DisplaySetting
import java.io.File

val LocalChatFontFamily = staticCompositionLocalOf<FontFamily?> { null }

@Composable
fun ChatFontProvider(
    displaySetting: DisplaySetting,
    content: @Composable () -> Unit,
) {
    val chatFontFamily = rememberChatFontFamily(displaySetting)
    CompositionLocalProvider(LocalChatFontFamily provides chatFontFamily) {
        content()
    }
}

@Composable
fun rememberChatFontFamily(displaySetting: DisplaySetting): FontFamily {
    val context = LocalContext.current
    return remember(
        displaySetting.chatFontFamily,
        displaySetting.chatCustomFontPath,
    ) {
        displaySetting.resolveChatFontFamily(context)
    }
}

fun DisplaySetting.resolveChatFontFamily(context: Context): FontFamily =
    resolveFontFamily(context, chatFontFamily, chatCustomFontPath)

/** The app-wide UI font ([DisplaySetting.uiFontFamily]); same rules as the chat font. */
fun DisplaySetting.resolveUiFontFamily(context: Context): FontFamily =
    resolveFontFamily(context, uiFontFamily, uiCustomFontPath)

/** Resolves the native [Typeface] for the chat font, or null for system default. */
fun DisplaySetting.resolveChatTypeface(context: Context): Typeface? =
    resolveTypeface(context, chatFontFamily, chatCustomFontPath)

/** Resolves the native [Typeface] for the app-wide UI font, or null for system default. */
fun DisplaySetting.resolveUiTypeface(context: Context): Typeface? =
    resolveTypeface(context, uiFontFamily, uiCustomFontPath)

private fun resolveFontFamily(context: Context, family: ChatFontFamily, customFontPath: String): FontFamily =
    when (family) {
        ChatFontFamily.DEFAULT -> FontFamily.Default
        ChatFontFamily.SERIF -> FontFamily.Serif
        ChatFontFamily.MONOSPACE -> FontFamily.Monospace
        ChatFontFamily.CUSTOM -> loadCustomFontFamily(context, customFontPath) ?: FontFamily.Default
    }

private fun resolveTypeface(context: Context, family: ChatFontFamily, customFontPath: String): Typeface? =
    when (family) {
        ChatFontFamily.DEFAULT -> null
        ChatFontFamily.SERIF -> Typeface.SERIF
        ChatFontFamily.MONOSPACE -> Typeface.MONOSPACE
        ChatFontFamily.CUSTOM -> loadCustomTypeface(context, customFontPath)
    }

private fun loadCustomTypeface(context: Context, relativePath: String): Typeface? {
    val file = resolveFilesDirFile(context, relativePath) ?: return null
    if (!file.isFile) return null
    return runCatching { Typeface.createFromFile(file) }.getOrNull()
}

private fun loadCustomFontFamily(context: Context, relativePath: String): FontFamily? {
    val file = resolveFilesDirFile(context, relativePath) ?: return null
    if (!file.isFile) return null
    return runCatching {
        Typeface.createFromFile(file)
        FontFamily(Font(file))
    }.getOrNull()
}

private fun resolveFilesDirFile(context: Context, relativePath: String): File? {
    if (relativePath.isBlank()) return null
    val filesDir = runCatching { context.filesDir.canonicalFile }.getOrNull() ?: return null
    val file = runCatching { File(filesDir, relativePath).canonicalFile }.getOrNull() ?: return null
    return file.takeIf { it.path.startsWith("${filesDir.path}${File.separator}") }
}
