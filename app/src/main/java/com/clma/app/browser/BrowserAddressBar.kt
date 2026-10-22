package me.rerere.rikkahub.browser

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.CheckmarkCircle01
import me.rerere.hugeicons.stroke.Lock
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.rikkahub.R

/**
 * Top app bar for [BrowserActivity]. Editable URL/search bar + back + refresh + kebab.
 *
 * The title slot is an editable [TextField] so the user can navigate manually before
 * (or instead of) handing control to the AI. Submitting the field invokes [onNavigate]
 * with the raw query — the caller normalises it (scheme / host / search engine).
 *
 * Display contract (Chrome-style): while UNFOCUSED the field shows only the page's host
 * (plus a lock glyph on HTTPS) so the user can see which site they're on despite the
 * narrow screen; on FOCUS it switches to the full URL with everything selected, ready
 * to replace in one keystroke.
 *
 * Rarely-used actions (forward, desktop mode, copy/share/open-external, stop AI) live in
 * the overflow menu — the bar itself stays within [✕][←][url…][⟳][⋮] even on 360dp-wide
 * phones.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserAddressBar(
    url: String,
    canGoBack: Boolean,
    canGoForward: Boolean,
    desktopMode: Boolean,
    onClose: () -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onRefresh: () -> Unit,
    onStopAi: () -> Unit,
    onNavigate: (String) -> Unit,
    onDesktopModeChange: (Boolean) -> Unit,
    onCopyLink: () -> Unit,
    onShare: () -> Unit,
    onOpenExternal: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var fieldValue by remember(url) {
        mutableStateOf(TextFieldValue(text = url, selection = TextRange(url.length)))
    }
    var hasFocus by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    // Sync external URL updates into the field ONLY while it's not being edited; once the
    // user is typing we don't want a navigation event to clobber their query mid-type.
    LaunchedEffect(url) {
        if (!hasFocus && url != fieldValue.text) {
            fieldValue = TextFieldValue(text = url, selection = TextRange(url.length))
        }
    }

    // Chrome-style: unfocused shows just the host (full URL is noise at this width);
    // focused shows the full URL, fully selected. about:blank → "" so the placeholder
    // shows; unparseable input falls back to the raw string inside shortHostForDisplay.
    val displayValue = if (hasFocus) fieldValue else TextFieldValue(shortHostForDisplay(fieldValue.text))
    val isSecure = fieldValue.text.startsWith("https://", ignoreCase = true)

    TopAppBar(
        title = {
            TextField(
                value = displayValue,
                onValueChange = { fieldValue = it },
                singleLine = true,
                placeholder = {
                    Text(
                        text = stringResource(R.string.browser_address_bar_placeholder),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                leadingIcon = if (isSecure && !hasFocus) {
                    {
                        Icon(
                            imageVector = HugeIcons.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Go,
                    autoCorrectEnabled = false,
                ),
                keyboardActions = KeyboardActions(
                    onGo = {
                        val q = fieldValue.text.trim()
                        if (q.isNotEmpty()) {
                            onNavigate(q)
                            keyboard?.hide()
                        }
                    }
                ),
                textStyle = MaterialTheme.typography.bodyMedium,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    disabledContainerColor = MaterialTheme.colorScheme.surface,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onFocusChanged { state ->
                        val nowFocused = state.isFocused
                        if (nowFocused && !hasFocus) {
                            // Select-all on focus so the user replaces existing URL in one keystroke.
                            fieldValue = fieldValue.copy(
                                selection = TextRange(0, fieldValue.text.length)
                            )
                        }
                        hasFocus = nowFocused
                    },
            )
        },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = HugeIcons.Cancel01,
                    contentDescription = stringResource(R.string.browser_address_bar_close),
                )
            }
        },
        actions = {
            IconButton(onClick = onBack, enabled = canGoBack) {
                Icon(
                    imageVector = HugeIcons.ArrowLeft01,
                    contentDescription = stringResource(R.string.browser_address_bar_back),
                )
            }
            IconButton(onClick = onRefresh) {
                Icon(
                    imageVector = HugeIcons.Refresh01,
                    contentDescription = stringResource(R.string.browser_address_bar_refresh),
                )
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        imageVector = HugeIcons.MoreVertical,
                        contentDescription = stringResource(R.string.browser_address_bar_more),
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    // Forward is low-frequency on a phone (system back covers the common
                    // case) — it lives here instead of costing the bar 48dp.
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.browser_address_bar_forward)) },
                        enabled = canGoForward,
                        onClick = {
                            menuExpanded = false
                            onForward()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.browser_menu_desktop)) },
                        trailingIcon = if (desktopMode) {
                            {
                                Icon(
                                    imageVector = HugeIcons.CheckmarkCircle01,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        } else {
                            null
                        },
                        onClick = {
                            menuExpanded = false
                            onDesktopModeChange(!desktopMode)
                        },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.browser_menu_copy_link)) },
                        onClick = {
                            menuExpanded = false
                            onCopyLink()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.browser_menu_share)) },
                        onClick = {
                            menuExpanded = false
                            onShare()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.browser_menu_open_external)) },
                        onClick = {
                            menuExpanded = false
                            onOpenExternal()
                        },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.browser_address_bar_stop_ai)) },
                        onClick = {
                            menuExpanded = false
                            onStopAi()
                        },
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        modifier = Modifier.padding(horizontal = 0.dp),
    )
}
