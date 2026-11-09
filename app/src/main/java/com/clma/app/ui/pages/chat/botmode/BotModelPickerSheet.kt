package com.clma.app.ui.pages.chat.botmode

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.clma.ai.provider.Model
import com.clma.ai.provider.ModelType
import com.clma.ai.provider.ProviderSetting
import com.clma.app.R
import com.clma.app.ui.components.ai.ModelListSheet
import com.clma.app.ui.components.ai.rememberModelListState
import kotlin.uuid.Uuid

/**
 * Per-bot model picker: reuses [ModelListSheet], with a provider chip row in the header.
 * Selection should update the current assistant's chatModelId.
 */
@Composable
fun BotModelPickerButton(
    modelId: Uuid?,
    modelLabel: String?,
    providers: List<ProviderSetting>,
    onSelect: (Model) -> Unit,
    modifier: Modifier = Modifier,
) {
    var providerFilter by remember { mutableStateOf<Uuid?>(null) }
    val visibleProviders = remember(providers, providerFilter) {
        if (providerFilter == null) providers
        else providers.filter { it.id == providerFilter }
    }
    val state = rememberModelListState(
        modelId = modelId,
        providers = visibleProviders,
        type = ModelType.CHAT,
    )
    TextButton(onClick = { state.open() }, modifier = modifier) {
        Text(
            text = modelLabel?.takeIf { it.isNotBlank() }
                ?: stringResource(R.string.bot_mode_pick_model),
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
        )
    }
    ModelListSheet(
        state = state,
        onSelect = onSelect,
        header = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.bot_mode_model_sheet_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.bot_mode_model_sheet_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ProviderChipRow(
                    providers = providers,
                    selectedId = providerFilter,
                    onSelect = { providerFilter = it },
                )
            }
        },
    )
}

@Composable
private fun ProviderChipRow(
    providers: List<ProviderSetting>,
    selectedId: Uuid?,
    onSelect: (Uuid?) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(
            selected = selectedId == null,
            onClick = { onSelect(null) },
            label = { Text(stringResource(R.string.bot_mode_provider_all)) },
        )
        providers.forEach { provider ->
            val enabled = provider.enabled && provider.models.any { it.type == ModelType.CHAT }
            FilterChip(
                selected = selectedId == provider.id,
                enabled = enabled,
                onClick = { onSelect(provider.id) },
                label = {
                    Text(
                        text = if (enabled) {
                            provider.name.ifBlank { stringResource(R.string.bot_mode_provider_unnamed) }
                        } else {
                            stringResource(
                                R.string.bot_mode_provider_disabled,
                                provider.name.ifBlank { stringResource(R.string.bot_mode_provider_unnamed) },
                            )
                        },
                        maxLines = 1,
                    )
                },
            )
        }
    }
}
