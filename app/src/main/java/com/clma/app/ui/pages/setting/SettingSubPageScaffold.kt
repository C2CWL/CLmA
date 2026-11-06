package com.clma.app.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import com.clma.app.ui.components.nav.BackButton
import com.clma.app.ui.theme.LocalDarkMode
import com.clma.app.utils.plus

/** Light settings page wash — aligns with mock `#F2F3F5`, not a global theme preset. */
internal val SettingsPageBackgroundLight = Color(0xFFF2F3F5)

/** Dark settings page fill. Near-white theme text on `surfaceContainerLow` (#22191A) glares. */
internal val SettingsPageBackgroundDark = Color.Black

/** Settings hub / sub-page scaffold fill. Dark & OLED stay pure black. */
@Composable
internal fun settingsPageContainerColor(): Color {
    return if (LocalDarkMode.current) {
        SettingsPageBackgroundDark
    } else {
        SettingsPageBackgroundLight
    }
}

/**
 * Dark settings primary text — blended over black, not just translucent.
 * Copying a near-white color with alpha still reads as white on OLED.
 */
@Composable
internal fun settingsPrimaryTextColor(): Color {
    val scheme = MaterialTheme.colorScheme
    return if (LocalDarkMode.current) {
        blendOver(scheme.onSurface, SettingsPageBackgroundDark, 0.68f)
    } else {
        scheme.onSurface
    }
}

/** Dark settings secondary / supporting text. */
@Composable
internal fun settingsSecondaryTextColor(): Color {
    val scheme = MaterialTheme.colorScheme
    return if (LocalDarkMode.current) {
        blendOver(scheme.onSurfaceVariant, SettingsPageBackgroundDark, 0.68f)
    } else {
        scheme.onSurfaceVariant
    }
}

/** Leading icons on hub leaf rows. */
@Composable
internal fun settingsIconTint(): Color {
    val scheme = MaterialTheme.colorScheme
    return if (LocalDarkMode.current) {
        blendOver(scheme.onSurface, SettingsPageBackgroundDark, 0.60f)
    } else {
        scheme.onSurface.copy(alpha = 0.88f)
    }
}

/** Opaque blend: translucent white over black still looks white. */
internal fun blendOver(foreground: Color, background: Color, alpha: Float): Color {
    val a = alpha.coerceIn(0f, 1f)
    return Color(
        red = foreground.red * a + background.red * (1f - a),
        green = foreground.green * a + background.green * (1f - a),
        blue = foreground.blue * a + background.blue * (1f - a),
        alpha = 1f,
    )
}

@Composable
internal fun settingsTopBarColors(): TopAppBarColors {
    val bg = settingsPageContainerColor()
    val title = settingsPrimaryTextColor()
    val icon = settingsIconTint()
    return TopAppBarDefaults.topAppBarColors(
        containerColor = bg,
        scrolledContainerColor = bg,
        titleContentColor = title,
        navigationIconContentColor = icon,
        actionIconContentColor = icon,
    )
}

/**
 * The frame every simple settings sub-page shares: large collapsing title, back button and a
 * lazy column of card groups. Pages split out of the old 「界面偏好设置」/「主题」/「模型设置」 use
 * it so they look exactly like the pages they came from.
 */
@Composable
internal fun SettingSubPageScaffold(
    title: String,
    content: LazyListScope.() -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val pageColor = settingsPageContainerColor()
    val topBarColors = settingsTopBarColors()
    val titleColor = settingsPrimaryTextColor()
    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(title, color = titleColor) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = pageColor,
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }
}
