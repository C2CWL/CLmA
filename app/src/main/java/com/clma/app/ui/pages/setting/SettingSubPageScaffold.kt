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

/** Settings hub / sub-page scaffold fill. Dark & OLED keep theme `surfaceContainerLow`. */
@Composable
internal fun settingsPageContainerColor(): Color {
    return if (LocalDarkMode.current) {
        MaterialTheme.colorScheme.surfaceContainerLow
    } else {
        SettingsPageBackgroundLight
    }
}

/**
 * Dark settings primary text — slightly muted so low-brightness OLED is less harsh.
 * Does not brighten page/card surfaces.
 */
@Composable
internal fun settingsPrimaryTextColor(): Color {
    val scheme = MaterialTheme.colorScheme
    return if (LocalDarkMode.current) {
        scheme.onSurface.copy(alpha = 0.78f)
    } else {
        scheme.onSurface
    }
}

/** Dark settings secondary / supporting text. */
@Composable
internal fun settingsSecondaryTextColor(): Color {
    val scheme = MaterialTheme.colorScheme
    return if (LocalDarkMode.current) {
        scheme.onSurfaceVariant.copy(alpha = 0.82f)
    } else {
        scheme.onSurfaceVariant
    }
}

/** Leading icons on hub leaf rows. */
@Composable
internal fun settingsIconTint(): Color {
    val scheme = MaterialTheme.colorScheme
    return if (LocalDarkMode.current) {
        scheme.onSurface.copy(alpha = 0.68f)
    } else {
        scheme.onSurface.copy(alpha = 0.88f)
    }
}

@Composable
internal fun settingsTopBarColors(): TopAppBarColors {
    val bg = settingsPageContainerColor()
    val title = settingsPrimaryTextColor()
    return TopAppBarDefaults.topAppBarColors(
        containerColor = bg,
        scrolledContainerColor = bg,
        titleContentColor = title,
        navigationIconContentColor = title,
        actionIconContentColor = title.copy(alpha = if (LocalDarkMode.current) 0.85f else 1f),
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
