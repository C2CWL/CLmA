package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dokar.sonner.ToastType
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Lock
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.secrets.AgentSecretsStore
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.compose.koinInject

/**
 * Manage the on-device agent secret vault ([AgentSecretsStore]) — the store behind
 * `request_secret` / `password_secret` / `stdin_secret`.
 *
 * Values are NEVER displayed or exported here (or anywhere): the page only lists names
 * and offers deletion. Losing a name means the model can no longer reference it; the
 * user re-enters the value via a new `request_secret` card.
 */
@Composable
fun SettingSecretsPage() {
    val secretsStore: AgentSecretsStore = koinInject()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val toaster = LocalToaster.current

    var names by remember { mutableStateOf(secretsStore.list()) }
    var pendingDelete by remember { mutableStateOf<String?>(null) }

    val deletedMessage = stringResource(R.string.setting_secrets_page_deleted)

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_secrets_page_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                CardGroup(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_secrets_page_list)) },
                ) {
                    if (names.isEmpty()) {
                        item(
                            headlineContent = { Text(stringResource(R.string.setting_secrets_page_empty)) },
                            supportingContent = {
                                Text(stringResource(R.string.setting_secrets_page_empty_hint))
                            },
                        )
                    }
                    names.forEach { name ->
                        item(
                            leadingContent = {
                                Icon(
                                    imageVector = HugeIcons.Lock,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            },
                            headlineContent = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                Text(stringResource(R.string.setting_secrets_page_value_hidden))
                            },
                            trailingContent = {
                                IconButton(onClick = { pendingDelete = name }) {
                                    Icon(
                                        imageVector = HugeIcons.Delete01,
                                        contentDescription = stringResource(R.string.setting_secrets_page_delete),
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            },
                        )
                    }
                }
            }

            item {
                CardGroup(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_secrets_page_about_title)) },
                        supportingContent = {
                            Text(stringResource(R.string.setting_secrets_page_about_body))
                        },
                    )
                }
            }
        }
    }

    pendingDelete?.let { name ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.setting_secrets_page_delete_confirm_title, name)) },
            text = { Text(stringResource(R.string.setting_secrets_page_delete_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        secretsStore.remove(name)
                        names = secretsStore.list()
                        pendingDelete = null
                        toaster.show(deletedMessage, ToastType.Normal)
                    },
                ) {
                    Text(
                        text = stringResource(R.string.setting_secrets_page_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.setting_ssh_page_cancel))
                }
            },
        )
    }
}
