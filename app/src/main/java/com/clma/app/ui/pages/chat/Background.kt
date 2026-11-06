package com.clma.app.ui.pages.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import com.clma.app.data.datastore.Settings
import com.clma.app.data.datastore.usesGeminiChatUi
import com.clma.app.data.datastore.getCurrentAssistant
import com.clma.app.ui.theme.LocalDarkMode

@Composable
fun AssistantBackground(setting: Settings, modifier: Modifier, emptyConversation: Boolean = true) {
    val assistant = setting.getCurrentAssistant()
    if (assistant.useGradientBackground) {
        if (setting.displaySetting.usesGeminiChatUi) {
            if (emptyConversation) {
                val accent = MaterialTheme.colorScheme.primary
                val dark = LocalDarkMode.current
                Canvas(modifier.fillMaxSize()) {
                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(accent.copy(alpha = if (dark) 0.14f else 0.09f), Color.Transparent),
                            center = Offset(size.width * 0.5f, size.height * 0.22f),
                            radius = minOf(size.width * 0.8f, size.height * 0.42f),
                        ),
                    )
                }
            }
        } else {
            MeshGradientBackground(modifier = modifier)
        }
        return
    }
    val bg = effectiveBackground(
        darkTheme = LocalDarkMode.current,
        background = assistant.background,
        darkBackground = assistant.darkBackground,
        lightBackground = assistant.lightBackground,
        mode = assistant.backgroundMode,
    )
    if (bg != null) {
        val backgroundColor = MaterialTheme.colorScheme.background
        val backgroundOpacity = assistant.backgroundOpacity.coerceIn(0f, 1f)
        // Light theme: no veil (a white ramp is what washes a dark photo out).
        // Dark theme: the historical near-black ramp, which a dark photo absorbs.
        val scrim = wallpaperScrim(LocalDarkMode.current)
        Box(modifier = modifier) {
            AsyncImage(
                model = bg,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(backgroundOpacity)
            )

            if (scrim.topAlpha > 0f || scrim.bottomAlpha > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    backgroundColor.copy(alpha = scrim.topAlpha),
                                    backgroundColor.copy(alpha = scrim.bottomAlpha),
                                )
                            )
                        )
                )
            }
        }
    }
}
