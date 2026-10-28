package com.clma.app.ui.pages.setting.components

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.Refresh03
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import androidx.compose.ui.unit.dp
import com.clma.ai.provider.BalanceMethod
import com.clma.ai.provider.CustomHeader
import com.clma.ai.provider.BalanceOption
import com.clma.ai.provider.ProviderSetting
import com.clma.common.http.isJsonExprValid
import com.clma.app.R
import com.clma.app.data.datastore.DEFAULT_PROVIDERS
import com.clma.app.ui.theme.JetbrainsMono

private val ApiPathRegex = Regex("""^(?:/|https?://)[^ \t\n\r]*$""")

@Composable
fun SettingProviderBalanceOption(
    provider: ProviderSetting,
    balanceOption: BalanceOption,
    modifier: Modifier = Modifier,
    onEdit: (BalanceOption) -> Unit,
) {
    var expand by remember { mutableStateOf(balanceOption.enabled) }
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.setting_provider_page_balance_info),
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = {
                    expand = !expand
                }
            ) {
                if (expand) {
                    Icon(
                        imageVector = HugeIcons.ArrowUp01,
                        contentDescription = null,
                    )
                } else {
                    Icon(
                        imageVector = HugeIcons.ArrowDown01,
                        contentDescription = null,
                    )
                }
            }
            Checkbox(
                checked = balanceOption.enabled,
                onCheckedChange = { onEdit(balanceOption.copy(enabled = it)) }
            )
        }
        AnimatedVisibility(visible = expand) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = balanceOption.apiPath,
                    onValueChange = { onEdit(balanceOption.copy(apiPath = it)) },
                    label = { Text(stringResource(R.string.setting_provider_page_balance_api_path)) },
                    isError = !balanceOption.apiPath.matches(ApiPathRegex),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = balanceOption.resultPath,
                    onValueChange = { onEdit(balanceOption.copy(resultPath = it)) },
                    label = { Text(stringResource(R.string.setting_provider_page_balance_json_key)) },
                    isError = !isJsonExprValid(balanceOption.resultPath),
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetbrainsMono),
                    supportingText = { Text(stringResource(R.string.provider_balance_expression_help)) },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BalanceMethod.entries.forEach { method ->
                        FilterChip(
                            selected = balanceOption.method == method,
                            onClick = { onEdit(balanceOption.copy(method = method)) },
                            label = { Text(method.name) },
                        )
                    }
                }
                if (balanceOption.method == BalanceMethod.POST) {
                    OutlinedTextField(
                        value = balanceOption.requestBody,
                        onValueChange = { onEdit(balanceOption.copy(requestBody = it)) },
                        label = { Text(stringResource(R.string.provider_balance_body)) },
                        isError = runCatching {
                            Json.parseToJsonElement(balanceOption.requestBody) is JsonObject
                        }.getOrDefault(false).not(),
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetbrainsMono),
                    )
                }
                Text(stringResource(R.string.provider_balance_headers), style = MaterialTheme.typography.labelLarge)
                Text(stringResource(R.string.provider_balance_origin_help), style = MaterialTheme.typography.bodySmall)
                balanceOption.headers.forEachIndexed { index, header ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedTextField(
                            value = header.name,
                            onValueChange = { name ->
                                onEdit(balanceOption.copy(headers = balanceOption.headers.toMutableList().apply {
                                    set(index, header.copy(name = name))
                                }))
                            },
                            label = { Text(stringResource(R.string.provider_balance_header_name)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = header.value,
                            onValueChange = { value ->
                                onEdit(balanceOption.copy(headers = balanceOption.headers.toMutableList().apply {
                                    set(index, header.copy(value = value))
                                }))
                            },
                            label = { Text(stringResource(R.string.provider_balance_header_value)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        )
                        TextButton(onClick = {
                            onEdit(balanceOption.copy(headers = balanceOption.headers.filterIndexed { i, _ -> i != index }))
                        }) { Text(stringResource(R.string.provider_balance_remove_header)) }
                    }
                }
                TextButton(onClick = {
                    onEdit(balanceOption.copy(headers = balanceOption.headers + CustomHeader("", "")))
                }) { Text(stringResource(R.string.provider_balance_add_header)) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = balanceOption.prefix,
                        onValueChange = { onEdit(balanceOption.copy(prefix = it)) },
                        label = { Text(stringResource(R.string.provider_balance_prefix)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = balanceOption.suffix,
                        onValueChange = { onEdit(balanceOption.copy(suffix = it)) },
                        label = { Text(stringResource(R.string.provider_balance_suffix)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                }
                Text(stringResource(R.string.provider_balance_decimals, balanceOption.decimals))
                Slider(
                    value = balanceOption.decimals.coerceIn(0, 8).toFloat(),
                    onValueChange = { onEdit(balanceOption.copy(decimals = it.toInt())) },
                    valueRange = 0f..8f,
                    steps = 7,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.provider_balance_animation), modifier = Modifier.weight(1f))
                    Switch(
                        checked = balanceOption.animate,
                        onCheckedChange = { onEdit(balanceOption.copy(animate = it)) },
                    )
                }
                IconButton(
                    onClick = {
                        val defaultProvider = DEFAULT_PROVIDERS.find { it.id == provider.id }
                        if (defaultProvider != null) {
                            onEdit(defaultProvider.balanceOption.copy())
                        } else {
                            onEdit(BalanceOption())
                        }
                    }
                ) {
                    Icon(HugeIcons.Refresh03, null)
                }
            }
        }
    }
}
