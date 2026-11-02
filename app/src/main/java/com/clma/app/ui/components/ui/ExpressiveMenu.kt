package com.clma.app.ui.components.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuBoxScope
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.clma.app.data.datastore.PopupMenuStyle
import com.clma.app.ui.context.LocalSettings
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Tick02

/** Shared bubble treatment, including menus hosted outside the main settings provider. */
object AppMenuDefaults {
    val Shape = RoundedCornerShape(16.dp)
    val ItemShape = RoundedCornerShape(10.dp)
    val HorizontalPadding = 6.dp
    val ShadowElevation = 4.dp

    val containerColor: Color
        @Composable get() = MaterialTheme.colorScheme.surfaceContainerLowest

    val border: BorderStroke
        @Composable get() = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
}

/** One row in an [AppDropdownMenu]; null [selected] denotes an action, not a choice. */
data class AppMenuEntry(
    val text: String,
    val icon: ImageVector? = null,
    val enabled: Boolean = true,
    val supporting: String? = null,
    val selected: Boolean? = null,
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

@Composable
fun AppGroupedDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    groups: List<List<AppMenuEntry>>,
    modifier: Modifier = Modifier,
) {
    AppDropdownMenu(expanded, onDismissRequest, modifier = modifier) {
        groups.filter { it.isNotEmpty() }.forEachIndexed { index, group ->
            if (index > 0) AppMenuDivider()
            group.forEach { entry ->
                AppDropdownMenuItem(
                    text = { Text(entry.text) },
                    onClick = {
                        entry.onClick()
                        onDismissRequest()
                    },
                    leadingIcon = entry.icon?.let { icon ->
                        { Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp)) }
                    },
                    supportingText = entry.supporting?.let { supporting -> { Text(supporting) } },
                    enabled = entry.enabled,
                    selected = entry.selected,
                    destructive = entry.destructive,
                )
            }
        }
    }
}

/** Separate name for grouped lists avoids JVM generic type erasure. */
@Composable
fun AppDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    items: List<AppMenuEntry>,
    modifier: Modifier = Modifier,
) {
    AppGroupedDropdownMenu(expanded, onDismissRequest, listOf(items), modifier)
}

/** Keeps the stored motion preference; both paths use the same lightweight bubble surface. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    style: PopupMenuStyle = LocalSettings.current.displaySetting.popupMenuStyle,
    content: @Composable ColumnScope.() -> Unit,
) {
    when (style) {
        PopupMenuStyle.EXPRESSIVE -> DropdownMenuPopup(
            expanded = expanded,
            onDismissRequest = onDismissRequest,
            modifier = modifier,
        ) {
            Surface(
                shape = AppMenuDefaults.Shape,
                color = AppMenuDefaults.containerColor,
                tonalElevation = 0.dp,
                shadowElevation = AppMenuDefaults.ShadowElevation,
                border = AppMenuDefaults.border,
            ) {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = AppMenuDefaults.HorizontalPadding, vertical = 8.dp),
                    content = content,
                )
            }
        }
        PopupMenuStyle.CLASSIC -> DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismissRequest,
            modifier = modifier,
            shape = AppMenuDefaults.Shape,
            containerColor = AppMenuDefaults.containerColor,
            tonalElevation = 0.dp,
            shadowElevation = AppMenuDefaults.ShadowElevation,
            border = AppMenuDefaults.border,
        ) {
            Column(Modifier.padding(horizontal = AppMenuDefaults.HorizontalPadding), content = content)
        }
    }
}

/** Preserve exposed-menu anchoring and IME handling rather than substituting a generic popup. */
@Composable
fun ExposedDropdownMenuBoxScope.AppExposedDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    ExposedDropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        shape = AppMenuDefaults.Shape,
        containerColor = AppMenuDefaults.containerColor,
        tonalElevation = 0.dp,
        shadowElevation = AppMenuDefaults.ShadowElevation,
        border = AppMenuDefaults.border,
    ) {
        Column(Modifier.padding(horizontal = AppMenuDefaults.HorizontalPadding), content = content)
    }
}

@Composable
fun AppDropdownMenuItem(
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    enabled: Boolean = true,
    selected: Boolean? = null,
    destructive: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val foreground = when {
        destructive -> colors.error
        selected == true -> colors.primary
        else -> colors.onSurface
    }
    DropdownMenuItem(
        text = {
            Column {
                ProvideTextStyle(MaterialTheme.typography.bodyLarge, content = text)
                if (supportingText != null) {
                    CompositionLocalProvider(
                        LocalContentColor provides if (enabled) colors.onSurfaceVariant else colors.onSurface.copy(alpha = 0.38f),
                    ) {
                        ProvideTextStyle(MaterialTheme.typography.bodySmall, content = supportingText)
                    }
                }
            }
        },
        onClick = onClick,
        modifier = modifier
            .clip(AppMenuDefaults.ItemShape)
            .background(if (selected == true && enabled) colors.primary.copy(alpha = 0.08f) else Color.Transparent)
            .semantics { if (selected != null) this.selected = selected },
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon ?: if (selected == true) {
            { Icon(HugeIcons.Tick02, contentDescription = null, modifier = Modifier.size(18.dp)) }
        } else {
            null
        },
        enabled = enabled,
        colors = MenuDefaults.itemColors(
            textColor = foreground,
            leadingIconColor = if (destructive || selected == true) foreground else colors.onSurfaceVariant,
            trailingIconColor = foreground,
        ),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    )
}

@Composable
fun AppMenuDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
    )
}
