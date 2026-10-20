package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.datastore.PopupMenuStyle
import me.rerere.rikkahub.ui.context.LocalSettings

/** One row in an [AppDropdownMenu]. */
data class AppMenuEntry(
    val text: String,
    val icon: ImageVector? = null,
    val enabled: Boolean = true,
    val supporting: String? = null,
    val onClick: () -> Unit,
)

/**
 * App-wide popup menu. Follows [PopupMenuStyle]:
 * - [PopupMenuStyle.EXPRESSIVE]: M3 Expressive popup + grouped item shapes (spring scale).
 * - [PopupMenuStyle.CLASSIC]: stock [DropdownMenu].
 *
 * [groups] is a list of sections; empty groups are skipped. Pass a single list for a
 * flat menu.
 */
@Composable
fun AppDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    groups: List<List<AppMenuEntry>>,
    modifier: Modifier = Modifier,
) {
    // LocalSettings.current is @Composable — cannot wrap in runCatching.
    val style = LocalSettings.current.displaySetting.popupMenuStyle

    when (style) {
        PopupMenuStyle.EXPRESSIVE -> ExpressiveMenu(
            expanded = expanded,
            onDismissRequest = onDismissRequest,
            groups = groups,
            modifier = modifier,
        )
        PopupMenuStyle.CLASSIC -> ClassicMenu(
            expanded = expanded,
            onDismissRequest = onDismissRequest,
            groups = groups,
            modifier = modifier,
        )
    }
}

/** Convenience for a single flat group. */
@Composable
fun AppDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    items: List<AppMenuEntry>,
    modifier: Modifier = Modifier,
) {
    AppDropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        groups = listOf(items),
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ExpressiveMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    groups: List<List<AppMenuEntry>>,
    modifier: Modifier = Modifier,
) {
    DropdownMenuPopup(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
    ) {
        val shown = groups.filter { it.isNotEmpty() }
        Column(Modifier.verticalScroll(rememberScrollState())) {
            shown.forEachIndexed { gi, group ->
                if (gi > 0) Spacer(Modifier.height(2.dp))
                DropdownMenuGroup(
                    shapes = MenuDefaults.groupShape(gi, shown.size),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    group.forEachIndexed { i, entry ->
                        DropdownMenuItem(
                            onClick = {
                                entry.onClick()
                                onDismissRequest()
                            },
                            text = { Text(entry.text) },
                            shape = when {
                                group.size == 1 -> MenuDefaults.standaloneItemShape
                                i == 0 -> MenuDefaults.leadingItemShape
                                i == group.lastIndex -> MenuDefaults.trailingItemShape
                                else -> MenuDefaults.middleItemShape
                            },
                            leadingIcon = entry.icon?.let { icon ->
                                {
                                    Icon(
                                        icon,
                                        contentDescription = null,
                                        modifier = Modifier.size(MenuDefaults.LeadingIconSize),
                                    )
                                }
                            },
                            supportingText = entry.supporting?.let { s -> { Text(s) } },
                            enabled = entry.enabled,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ClassicMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    groups: List<List<AppMenuEntry>>,
    modifier: Modifier = Modifier,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
    ) {
        groups.filter { it.isNotEmpty() }.forEach { group ->
            group.forEach { entry ->
                DropdownMenuItem(
                    text = { Text(entry.text) },
                    onClick = {
                        entry.onClick()
                        onDismissRequest()
                    },
                    leadingIcon = entry.icon?.let { icon ->
                        { Icon(icon, contentDescription = null) }
                    },
                    enabled = entry.enabled,
                )
            }
        }
    }
}
