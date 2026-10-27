package com.clma.app.ui.pages.setting.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clma.app.R
import com.clma.app.data.datastore.Settings
import com.clma.app.data.vector.CloudEmbeddingCandidate
import com.clma.app.data.vector.CloudEmbeddingModels
import com.clma.app.data.vector.CloudEmbeddingRules
import com.clma.app.data.vector.EmbeddingCatalog
import com.clma.app.data.vector.EmbeddingModelFiles
import com.clma.app.data.vector.EmbeddingModelRules
import com.clma.app.ui.pages.setting.SettingVM
import com.clma.app.ui.pages.setting.locallm.SettingLocalLlmViewModel
import com.clma.llamacpp.LlamaCppEmbeddingCatalog
import com.clma.llamacpp.LlamaCppEmbeddingEntry
import com.clma.locallm.LocalRuntime
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.CheckmarkCircle02
import me.rerere.hugeicons.stroke.Download04
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmbeddingModelSheet(
    show: Boolean,
    onDismissRequest: () -> Unit,
    settings: Settings,
    vm: SettingVM,
) {
    if (!show) return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current

    val localLlmVm = koinViewModel<SettingLocalLlmViewModel>(
        key = "configure-${LocalRuntime.LlamaCpp.displayName}",
        parameters = { parametersOf(LocalRuntime.LlamaCpp) },
    )
    val downloadProgress by localLlmVm.downloadProgress.collectAsStateWithLifecycle()
    val downloadError by localLlmVm.errorMessage.collectAsStateWithLifecycle()

    val installedFiles = remember(show, downloadProgress) {
        EmbeddingModelFiles.installedFiles(context)
    }

    val isCloud = CloudEmbeddingRules.isCloud(settings.embeddingBackend)

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.setting_model_page_embedding_model),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(R.string.setting_model_page_embedding_model_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            // Backend Selector (Local vs Cloud)
            item {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                        selected = !isCloud,
                        onClick = {
                            vm.updateSettings(
                                settings.copy(embeddingBackend = CloudEmbeddingRules.BACKEND_LOCAL)
                            )
                        },
                    ) {
                        Text(stringResource(R.string.setting_model_page_embedding_backend_local))
                    }
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                        selected = isCloud,
                        onClick = {
                            vm.updateSettings(
                                settings.copy(embeddingBackend = CloudEmbeddingRules.BACKEND_CLOUD)
                            )
                        },
                    ) {
                        Text(stringResource(R.string.setting_model_page_embedding_backend_cloud))
                    }
                }
            }

            if (!isCloud) {
                // Local Embedding Section
                item {
                    // GPU Acceleration Switch
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.setting_model_page_embedding_use_gpu),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    text = stringResource(R.string.setting_model_page_embedding_use_gpu_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = settings.embeddingUseGpu == true,
                                onCheckedChange = {
                                    vm.updateSettings(settings.copy(embeddingUseGpu = it))
                                },
                            )
                        }
                    }
                }

                if (downloadProgress != null) {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        text = stringResource(R.string.setting_model_page_embedding_downloading),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                    )
                                    Text(
                                        text = "${downloadProgress?.percent ?: 0}%",
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                                LinearProgressIndicator(
                                    progress = { (downloadProgress?.percent ?: 0) / 100f },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }

                if (!downloadError.isNullOrBlank()) {
                    item {
                        Text(
                            text = downloadError ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                // Installed local models
                item {
                    Text(
                        text = stringResource(R.string.setting_model_page_embedding_installed_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                val autoPicked = EmbeddingModelRules.pick(
                    configured = null,
                    installed = installedFiles,
                    curatedOrder = EmbeddingCatalog.curatedOrder,
                )

                // Option: Auto
                item {
                    val isAutoSelected = settings.embeddingModelFile.isBlank()
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (isAutoSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                vm.updateSettings(settings.copy(embeddingModelFile = ""))
                            },
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                        ) {
                            RadioButton(
                                selected = isAutoSelected,
                                onClick = {
                                    vm.updateSettings(settings.copy(embeddingModelFile = ""))
                                },
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (autoPicked != null) {
                                        stringResource(R.string.setting_model_page_embedding_auto_picked, autoPicked)
                                    } else {
                                        stringResource(R.string.setting_model_page_embedding_status_not_ready)
                                    },
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (isAutoSelected) FontWeight.Bold else FontWeight.Normal,
                                )
                            }
                        }
                    }
                }

                if (installedFiles.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.setting_model_page_embedding_local_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                } else {
                    items(installedFiles) { fileName ->
                        val isSelected = settings.embeddingModelFile == fileName
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    vm.updateSettings(settings.copy(embeddingModelFile = fileName))
                                },
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = {
                                        vm.updateSettings(settings.copy(embeddingModelFile = fileName))
                                    },
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = fileName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    )
                                }
                            }
                        }
                    }
                }

                // Curated Catalog Section
                item {
                    Text(
                        text = stringResource(R.string.setting_model_page_embedding_curated_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }

                items(LlamaCppEmbeddingCatalog.PICKER_ORDER) { entry ->
                    val isInstalled = entry.file in installedFiles
                    val isCurrent = (settings.embeddingModelFile == entry.file) ||
                        (settings.embeddingModelFile.isBlank() && autoPicked == entry.file)

                    CuratedEmbeddingEntryCard(
                        entry = entry,
                        isInstalled = isInstalled,
                        isCurrent = isCurrent,
                        downloading = downloadProgress != null,
                        onSelect = {
                            vm.updateSettings(settings.copy(embeddingModelFile = entry.file))
                        },
                        onDownload = {
                            localLlmVm.startManualDownload(entry.resolveUrl())
                        },
                    )
                }
            } else {
                // Cloud Embedding Section
                item {
                    Text(
                        text = stringResource(R.string.setting_model_page_embedding_cloud_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                val candidates = CloudEmbeddingModels.candidates(settings.providers)
                if (candidates.isEmpty()) {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = stringResource(R.string.setting_model_page_embedding_cloud_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                } else {
                    items(candidates) { candidate ->
                        val isSelected = settings.embeddingCloudModel == candidate.modelUuid
                        CloudEmbeddingCard(
                            candidate = candidate,
                            isSelected = isSelected,
                            onSelect = {
                                vm.updateSettings(
                                    settings.copy(
                                        embeddingBackend = CloudEmbeddingRules.BACKEND_CLOUD,
                                        embeddingCloudModel = candidate.modelUuid,
                                    )
                                )
                            },
                        )
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CuratedEmbeddingEntryCard(
    entry: LlamaCppEmbeddingEntry,
    isInstalled: Boolean,
    isCurrent: Boolean,
    downloading: Boolean,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrent) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = entry.repo,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (isInstalled) {
                    if (isCurrent) {
                        Icon(
                            imageVector = HugeIcons.CheckmarkCircle02,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp),
                        )
                    } else {
                        OutlinedButton(onClick = onSelect) {
                            Text(stringResource(R.string.common_use))
                        }
                    }
                } else {
                    Button(
                        onClick = onDownload,
                        enabled = !downloading,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                        ),
                    ) {
                        Icon(
                            imageVector = HugeIcons.Download04,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.size(4.dp))
                        Text(stringResource(R.string.common_install))
                    }
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // Size tag
                val sizeMb = entry.sizeBytes / (1024 * 1024)
                SuggestionChip(
                    onClick = {},
                    enabled = false,
                    label = { Text("${sizeMb} MB") },
                    colors = SuggestionChipDefaults.suggestionChipColors(
                        disabledContainerColor = MaterialTheme.colorScheme.surface,
                        disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                )

                // Dim tag
                SuggestionChip(
                    onClick = {},
                    enabled = false,
                    label = { Text(stringResource(R.string.setting_model_page_embedding_dim, entry.dim)) },
                    colors = SuggestionChipDefaults.suggestionChipColors(
                        disabledContainerColor = MaterialTheme.colorScheme.surface,
                        disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                )

                // Context tag
                SuggestionChip(
                    onClick = {},
                    enabled = false,
                    label = { Text(stringResource(R.string.setting_model_page_embedding_ctx, entry.contextLabel)) },
                    colors = SuggestionChipDefaults.suggestionChipColors(
                        disabledContainerColor = MaterialTheme.colorScheme.surface,
                        disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                )

                entry.tags.forEach { tag ->
                    val displayTag = when (tag) {
                        "chinese" -> stringResource(R.string.setting_model_page_embedding_tag_chinese)
                        "multilingual" -> stringResource(R.string.setting_model_page_embedding_tag_multilingual)
                        "small" -> stringResource(R.string.setting_model_page_embedding_tag_compact)
                        "long-context" -> stringResource(R.string.setting_model_page_embedding_tag_long_context)
                        else -> tag
                    }
                    SuggestionChip(
                        onClick = {},
                        enabled = false,
                        label = { Text(displayTag) },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            disabledContainerColor = MaterialTheme.colorScheme.surface,
                            disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun CloudEmbeddingCard(
    candidate: CloudEmbeddingCandidate,
    isSelected: Boolean,
    onSelect: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = candidate.supported) { onSelect() },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
        ) {
            RadioButton(
                selected = isSelected,
                onClick = onSelect,
                enabled = candidate.supported,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = candidate.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                )
                Text(
                    text = "${candidate.providerName} · ${candidate.modelId}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!candidate.supported) {
                    Text(
                        text = stringResource(R.string.setting_model_page_embedding_unsupported_protocol),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}
