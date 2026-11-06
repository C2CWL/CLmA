package com.clma.app.ui.pages.setting

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Package01
import me.rerere.hugeicons.stroke.Connect
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Refresh03
import me.rerere.hugeicons.stroke.Tools
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Image03
import me.rerere.hugeicons.stroke.MusicNote03
import me.rerere.hugeicons.stroke.Text
import me.rerere.hugeicons.stroke.Video01
import me.rerere.hugeicons.stroke.Share01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Tick02
import me.rerere.hugeicons.stroke.TextSelection
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Settings03
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.CardDefaults
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.util.fastForEach
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingToolbarDefaults.ScreenOffset
import androidx.compose.material3.FloatingToolbarDefaults.floatingToolbarVerticalNestedScroll
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.MultiChoiceSegmentedButtonRow
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.clma.app.LocalNavController
import com.clma.app.R
import com.clma.app.data.Ability
import com.clma.app.data.Model
import com.clma.app.data.ProviderSetting
import com.clma.app.services.ability.PreviewProvider
import com.clma.app.services.ability.getAbilityClass
import com.clma.app.ui.components.ProviderBalanceDisplayMode
import com.clma.app.ui.components.ProviderBalanceText
import com.clma.app.ui.components.SelectionItem
import com.clma.app.ui.components.SettingSwitch
import com.clma.app.ui.viewmodel.ProviderViewModel
import com.clma.app.utils.toLanguageTag
import kotlinx.coroutines.launch
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
import com.clma.app.data.Relay

/**
 * 详细设置页面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingProviderDetailPage(
    settingId: Long,
    viewModel: ProviderViewModel = viewModel(),
) {
    val navController = LocalNavController.current
    val coroutineScope = rememberCoroutineScope()

    val provider by remember(settingId) {
        derivedStateOf {
            viewModel.providers.find { it.id == settingId }
        }
    }

    var internalProvider by remember(provider) { mutableStateOf(provider) }
    var balancePreview by remember(provider) { mutableStateOf<ProviderSetting?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ProviderConfigure(
            provider = internalProvider,
            onEdit = {
                internalProvider = it
            }
        )

        if (internalProvider is ProviderSetting.OpenAI) {
            SettingProviderBalanceOption(
                provider = internalProvider,
                balanceOption = internalProvider.balanceOption,
                onEdit = { internalProvider = internalProvider.copyProvider(balanceOption = it) }
            )
            TextButton(onClick = { balancePreview = internalProvider }) {
                Text(stringResource(R.string.provider_balance_test))
            }
            ProviderBalanceText(
                providerSetting = balancePreview ?: provider,
                style = MaterialTheme.typography.labelSmall,
                allowRefresh = true,
                displayMode = ProviderBalanceDisplayMode.Card,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ProviderConnectionTester(
                internalProvider = internalProvider,
                originalProvider = provider,
                modifier = Modifier.weight(1f)
            )

            Button(
                onClick = {
                    coroutineScope.launch {
                        viewModel.updateProvider(internalProvider)
                        navController.popBackStack()
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.confirm))
            }
        }

        Button(
            onClick = {
                coroutineScope.launch {
                    viewModel.removeProvider(internalProvider)
                    navController.popBackStack()
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.provider_delete))
        }
    }
}

@Composable
private fun ProviderConnectionTester(
    internalProvider: ProviderSetting?,
    originalProvider: ProviderSetting?,
    modifier: Modifier = Modifier,
) {
    val coroutineScope = rememberCoroutineScope()
    var testStatus by remember { mutableStateOf<TestStatus>(TestStatus.Idle) }

    Button(
        onClick = {
            coroutineScope.launch {
                testStatus = TestStatus.Testing
                testStatus = try {
                    internalProvider?.testConnection()
                    TestStatus.Success
                } catch (e: Exception) {
                    TestStatus.Error(e.message ?: "Unknown error")
                }
            }
        },
        modifier = modifier,
        enabled = internalProvider != null && testStatus != TestStatus.Testing
    ) {
        when (testStatus) {
            is TestStatus.Idle -> Text(stringResource(R.string.provider_test))
            is TestStatus.Testing -> Text(stringResource(R.string.provider_testing))
            is TestStatus.Success -> Text(stringResource(R.string.provider_test_success))
            is TestStatus.Error -> Text(stringResource(R.string.provider_test_failed))
        }
    }
}

private sealed class TestStatus {
    data object Idle : TestStatus()
    data object Testing : TestStatus()
    data object Success : TestStatus()
    data class Error(val message: String) : TestStatus()
}

@Composable
private fun ProviderConfigure(
    provider: ProviderSetting?,
    onEdit: (ProviderSetting) -> Unit,
) {
    if (provider == null) return

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.provider_name),
            style = MaterialTheme.typography.titleMedium
        )
        TextField(
            value = provider.name,
            onValueChange = { onEdit(provider.copyProvider(name = it)) },
            modifier = Modifier.fillMaxWidth()
        )

        when (provider) {
            is ProviderSetting.OpenAI -> OpenAIConfigure(provider, onEdit)
            is ProviderSetting.Gemini -> GeminiConfigure(provider, onEdit)
            is ProviderSetting.Codex -> CodexConfigure(provider, onEdit)
            is ProviderSetting.Claude -> ClaudeConfigure(provider, onEdit)
            is ProviderSetting.Deepseek -> DeepseekConfigure(provider, onEdit)
            is ProviderSetting.Relay -> RelayConfigure(provider, onEdit)
        }
    }
}

@Composable
private fun OpenAIConfigure(
    provider: ProviderSetting.OpenAI,
    onEdit: (ProviderSetting) -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.provider_api_key),
            style = MaterialTheme.typography.titleMedium
        )
        TextField(
            value = provider.apiKey,
            onValueChange = { onEdit(provider.copy(apiKey = it)) },
            modifier = Modifier.fillMaxWidth()
        )

        Text(
            text = stringResource(R.string.provider_base_url),
            style = MaterialTheme.typography.titleMedium
        )
        TextField(
            value = provider.baseUrl,
            onValueChange = { onEdit(provider.copy(baseUrl = it)) },
            modifier = Modifier.fillMaxWidth()
        )

        Text(
            text = stringResource(R.string.provider_model),
            style = MaterialTheme.typography.titleMedium
        )

        ModelListEditor(
            models = provider.models,
            parentProvider = provider,
            onModelsChange = { onEdit(provider.copy(models = it)) }
        )
    }
}

@Composable
private fun GeminiConfigure(
    provider: ProviderSetting.Gemini,
    onEdit: (ProviderSetting) -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.provider_api_key),
            style = MaterialTheme.typography.titleMedium
        )
        TextField(
            value = provider.apiKey,
            onValueChange = { onEdit(provider.copy(apiKey = it)) },
            modifier = Modifier.fillMaxWidth()
        )

        Text(
            text = stringResource(R.string.provider_base_url),
            style = MaterialTheme.typography.titleMedium
        )
        TextField(
            value = provider.baseUrl,
            onValueChange = { onEdit(provider.copy(baseUrl = it)) },
            modifier = Modifier.fillMaxWidth()
        )

        Text(
            text = stringResource(R.string.provider_model),
            style = MaterialTheme.typography.titleMedium
        )

        ModelListEditor(
            models = provider.models,
            parentProvider = provider,
            onModelsChange = { onEdit(provider.copy(models = it)) }
        )
    }
}

@Composable
private fun CodexConfigure(
    provider: ProviderSetting.Codex,
    onEdit: (ProviderSetting) -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.provider_api_key),
            style = MaterialTheme.typography.titleMedium
        )
        TextField(
            value = provider.apiKey,
            onValueChange = { onEdit(provider.copy(apiKey = it)) },
            modifier = Modifier.fillMaxWidth()
        )

        Text(
            text = stringResource(R.string.provider_base_url),
            style = MaterialTheme.typography.titleMedium
        )
        TextField(
            value = provider.baseUrl,
            onValueChange = { onEdit(provider.copy(baseUrl = it)) },
            modifier = Modifier.fillMaxWidth()
        )

        Text(
            text = stringResource(R.string.provider_model),
            style = MaterialTheme.typography.titleMedium
        )

        ModelListEditor(
            models = provider.models,
            parentProvider = provider,
            onModelsChange = { onEdit(provider.copy(models = it)) }
        )
    }
}

@Composable
private fun ClaudeConfigure(
    provider: ProviderSetting.Claude,
    onEdit: (ProviderSetting) -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.provider_api_key),
            style = MaterialTheme.typography.titleMedium
        )
        TextField(
            value = provider.apiKey,
            onValueChange = { onEdit(provider.copy(apiKey = it)) },
            modifier = Modifier.fillMaxWidth()
        )

        Text(
            text = stringResource(R.string.provider_base_url),
            style = MaterialTheme.typography.titleMedium
        )
        TextField(
            value = provider.baseUrl,
            onValueChange = { onEdit(provider.copy(baseUrl = it)) },
            modifier = Modifier.fillMaxWidth()
        )

        Text(
            text = stringResource(R.string.provider_model),
            style = MaterialTheme.typography.titleMedium
        )

        ModelListEditor(
            models = provider.models,
            parentProvider = provider,
            onModelsChange = { onEdit(provider.copy(models = it)) }
        )
    }
}

@Composable
private fun DeepseekConfigure(
    provider: ProviderSetting.Deepseek,
    onEdit: (ProviderSetting) -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.provider_api_key),
            style = MaterialTheme.typography.titleMedium
        )
        TextField(
            value = provider.apiKey,
            onValueChange = { onEdit(provider.copy(apiKey = it)) },
            modifier = Modifier.fillMaxWidth()
        )

        Text(
            text = stringResource(R.string.provider_base_url),
            style = MaterialTheme.typography.titleMedium
        )
        TextField(
            value = provider.baseUrl,
            onValueChange = { onEdit(provider.copy(baseUrl = it)) },
            modifier = Modifier.fillMaxWidth()
        )

        Text(
            text = stringResource(R.string.provider_model),
            style = MaterialTheme.typography.titleMedium
        )

        ModelListEditor(
            models = provider.models,
            parentProvider = provider,
            onModelsChange = { onEdit(provider.copy(models = it)) }
        )
    }
}

@Composable
private fun RelayConfigure(
    provider: ProviderSetting.Relay,
    onEdit: (ProviderSetting) -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.provider_base_url),
            style = MaterialTheme.typography.titleMedium
        )
        TextField(
            value = provider.baseUrl,
            onValueChange = { onEdit(provider.copy(baseUrl = it)) },
            modifier = Modifier.fillMaxWidth()
        )

        Text(
            text = stringResource(R.string.provider_model),
            style = MaterialTheme.typography.titleMedium
        )

        ModelListEditor(
            models = provider.models,
            parentProvider = provider,
            onModelsChange = { onEdit(provider.copy(models = it)) }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelListEditor(
    models: List<Model>,
    parentProvider: ProviderSetting,
    onModelsChange: (List<Model>) -> Unit,
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var editingModel by remember { mutableStateOf<Model?>(null) }

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        models.forEach { model ->
            val dismissState = rememberSwipeToDismissBoxState(
                confirmValueChange = { value ->
                    if (value == SwipeToDismissBoxValue.EndToStart) {
                        onModelsChange(models - model)
                        true
                    } else {
                        false
                    }
                }
            )

            SwipeToDismissBox(
                state = dismissState,
                backgroundContent = {
                    SwipeToDismissBackground(dismissState)
                },
                enableDismissFromStartToEnd = false,
            ) {
                OutlinedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { editingModel = model },
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = model.displayName.ifBlank { model.modelId },
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = model.modelId,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Icon(
                            imageVector = HugeIcons.ArrowRight01,
                            contentDescription = null
                        )
                    }
                }
            }
        }

        Button(
            onClick = { showAddDialog = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = HugeIcons.Add01,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.size(8.dp))
            Text(stringResource(R.string.provider_model_add))
        }
    }

    if (showAddDialog) {
        ModelEditDialog(
            model = null,
            parentProvider = parentProvider,
            onDismiss = { showAddDialog = false },
            onConfirm = { newModel ->
                onModelsChange(models + newModel)
                showAddDialog = false
            }
        )
    }

    editingModel?.let { model ->
        ModelEditDialog(
            model = model,
            parentProvider = parentProvider,
            onDismiss = { editingModel = null },
            onConfirm = { updatedModel ->
                onModelsChange(models.map { if (it == model) updatedModel else it })
                editingModel = null
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDismissBackground(dismissState: SwipeToDismissBoxState) {
    val color = when (dismissState.dismissDirection) {
        SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.errorContainer
        else -> Color.Transparent
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(color)
            .padding(16.dp),
        contentAlignment = Alignment.CenterEnd
    ) {
        if (dismissState.dismissDirection == SwipeToDismissBoxValue.EndToStart) {
            Icon(
                imageVector = HugeIcons.Delete01,
                contentDescription = stringResource(R.string.delete),
                tint = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelEditDialog(
    model: Model?,
    parentProvider: ProviderSetting,
    onDismiss: () -> Unit,
    onConfirm: (Model) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var currentPage by remember { mutableIntStateOf(0) }
    val pagerState = rememberPagerState(
        initialPage = 0,
        pageCount = { 2 }
    )

    LaunchedEffect(currentPage) {
        pagerState.animateScrollToPage(currentPage)
    }

    LaunchedEffect(pagerState.currentPage) {
        currentPage = pagerState.currentPage
    }

    var internalModel by remember(model) {
        mutableStateOf(
            model ?: Model(
                modelId = "",
                displayName = "",
                abilities = emptyList(),
            )
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
        ) {
            SecondaryTabRow(
                selectedTabIndex = currentPage,
                modifier = Modifier.fillMaxWidth()
            ) {
                Tab(
                    selected = currentPage == 0,
                    onClick = { currentPage = 0 },
                    text = { Text(stringResource(R.string.provider_model_basic)) }
                )
                Tab(
                    selected = currentPage == 1,
                    onClick = { currentPage = 1 },
                    text = { Text(stringResource(R.string.provider_model_advanced)) }
                )
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) { page ->
                when (page) {
                    0 -> {
                        // 基本设置页面
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.provider_model_id),
                                style = MaterialTheme.typography.titleMedium
                            )
                            TextField(
                                value = internalModel.modelId,
                                onValueChange = { internalModel = internalModel.copy(modelId = it) },
                                modifier = Modifier.fillMaxWidth()
                            )

                            Text(
                                text = stringResource(R.string.provider_model_display_name),
                                style = MaterialTheme.typography.titleMedium
                            )
                            TextField(
                                value = internalModel.displayName,
                                onValueChange = {
                                    internalModel = internalModel.copy(displayName = it)
                                },
                                modifier = Modifier.fillMaxWidth()
                            )

                            Text(
                                text = stringResource(R.string.provider_model_abilities),
                                style = MaterialTheme.typography.titleMedium
                            )

                            AbilitySelector(
                                modelId = internalModel.modelId,
                                abilities = internalModel.abilities,
                                onUpdateAbilities = {
                                    internalModel = internalModel.copy(abilities = it)
                                }
                            )
                            // Codex always exposes Standard/Fast; relays opt in with this switch.
                            if (parentProvider !is ProviderSetting.Codex) {
                                SpeedTiersOptInRow(
                                    checked = internalModel.supportsSpeedTiers,
                                    onCheckedChange = { enabled ->
                                        internalModel = internalModel.copy(
                                            supportsSpeedTiers = enabled,
                                            serviceTier = if (enabled) internalModel.serviceTier else null,
                                        )
                                    },
                                )
                            }
                        }
                    }

                    1 -> {
                        // 高级设置页面
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.provider_model_temperature),
                                style = MaterialTheme.typography.titleMedium
                            )
                            TextField(
                                value = internalModel.temperature?.toString() ?: "",
                                onValueChange = {
                                    internalModel = internalModel.copy(
                                        temperature = it.toDoubleOrNull()
                                    )
                                },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.fillMaxWidth()
                            )

                            Text(
                                text = stringResource(R.string.provider_model_top_p),
                                style = MaterialTheme.typography.titleMedium
                            )
                            TextField(
                                value = internalModel.topP?.toString() ?: "",
                                onValueChange = {
                                    internalModel = internalModel.copy(topP = it.toDoubleOrNull())
                                },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.fillMaxWidth()
                            )

                            Text(
                                text = stringResource(R.string.provider_model_max_tokens),
                                style = MaterialTheme.typography.titleMedium
                            )
                            TextField(
                                value = internalModel.maxTokens?.toString() ?: "",
                                onValueChange = {
                                    internalModel = internalModel.copy(
                                        maxTokens = it.toIntOrNull()
                                    )
                                },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.cancel))
                }

                Button(
                    onClick = { onConfirm(internalModel) },
                    modifier = Modifier.weight(1f),
                    enabled = internalModel.modelId.isNotBlank()
                ) {
                    Text(stringResource(R.string.confirm))
                }
            }
        }
    }
}

@Composable
private fun AbilitySelector(
    modelId: String,
    abilities: List<Ability>,
    onUpdateAbilities: (List<Ability>) -> Unit,
) {
    var showAbilityModal by remember { mutableStateOf(false) }

    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        abilities.forEach { ability ->
            val abilityClass = getAbilityClass(ability)
            BadgedBox(
                badge = {
                    Badge(
                        modifier = Modifier
                            .offset(x = 8.dp, y = (-8).dp)
                            .size(20.dp)
                            .clickable {
                                onUpdateAbilities(abilities - ability)
                            },
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    ) {
                        Icon(
                            imageVector = HugeIcons.Cancel01,
                            contentDescription = stringResource(R.string.delete),
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
            ) {
                Card(
                    modifier = Modifier.size(64.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = when (abilityClass) {
                                Ability.Vision::class -> HugeIcons.Image03
                                Ability.Audio::class -> HugeIcons.MusicNote03
                                Ability.Video::class -> HugeIcons.Video01
                                Ability.Document::class -> HugeIcons.Text
                                else -> HugeIcons.Package01
                            },
                            contentDescription = null,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }
        }

        Card(
            modifier = Modifier
                .size(64.dp)
                .clickable { showAbilityModal = true },
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = HugeIcons.Add01,
                    contentDescription = stringResource(R.string.provider_model_ability_add),
                    modifier = Modifier.size(32.dp)
                )
            }
        }
    }

    if (showAbilityModal) {
        ModalAbilitySelector(
            modelId = modelId,
            abilities = abilities,
            onUpdateAbilities = {
                onUpdateAbilities(it)
            },
            onDismiss = { showAbilityModal = false }
        )
    }
}

@Composable
fun ModalAbilitySelector(
    modelId: String,
    abilities: List<Ability>,
    onUpdateAbilities: (List<Ability>) -> Unit,
    onDismiss: () -> Unit,
) {
    var showPreview by remember { mutableStateOf<Ability?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(R.string.provider_model_abilities),
                style = MaterialTheme.typography.titleLarge
            )

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    SelectionItem(
                        icon = HugeIcons.Image03,
                        title = stringResource(R.string.ability_vision),
                        selected = abilities.any { it is Ability.Vision },
                        onClick = {
                            if (abilities.any { it is Ability.Vision }) {
                                onUpdateAbilities(abilities.filterNot { it is Ability.Vision })
                            } else {
                                onUpdateAbilities(abilities + Ability.Vision())
                            }
                        },
                        onLongClick = {
                            showPreview = abilities.find { it is Ability.Vision }
                                ?: Ability.Vision()
                        }
                    )
                }

                item {
                    SelectionItem(
                        icon = HugeIcons.MusicNote03,
                        title = stringResource(R.string.ability_audio),
                        selected = abilities.any { it is Ability.Audio },
                        onClick = {
                            if (abilities.any { it is Ability.Audio }) {
                                onUpdateAbilities(abilities.filterNot { it is Ability.Audio })
                            } else {
                                onUpdateAbilities(abilities + Ability.Audio())
                            }
                        },
                        onLongClick = {
                            showPreview = abilities.find { it is Ability.Audio }
                                ?: Ability.Audio()
                        }
                    )
                }

                item {
                    SelectionItem(
                        icon = HugeIcons.Video01,
                        title = stringResource(R.string.ability_video),
                        selected = abilities.any { it is Ability.Video },
                        onClick = {
                            if (abilities.any { it is Ability.Video }) {
                                onUpdateAbilities(abilities.filterNot { it is Ability.Video })
                            } else {
                                onUpdateAbilities(abilities + Ability.Video())
                            }
                        },
                        onLongClick = {
                            showPreview = abilities.find { it is Ability.Video }
                                ?: Ability.Video()
                        }
                    )
                }

                item {
                    SelectionItem(
                        icon = HugeIcons.Text,
                        title = stringResource(R.string.ability_document),
                        selected = abilities.any { it is Ability.Document },
                        onClick = {
                            if (abilities.any { it is Ability.Document }) {
                                onUpdateAbilities(abilities.filterNot { it is Ability.Document })
                            } else {
                                onUpdateAbilities(abilities + Ability.Document())
                            }
                        },
                        onLongClick = {
                            showPreview = abilities.find { it is Ability.Document }
                                ?: Ability.Document()
                        }
                    )
                }
            }
        }
    }

    showPreview?.let { ability ->
        AbilityPreviewDialog(
            ability = ability,
            modelId = modelId,
            onDismiss = { showPreview = null },
            onConfirm = { updatedAbility ->
                onUpdateAbilities(
                    abilities.map {
                        if (getAbilityClass(it) == getAbilityClass(updatedAbility)) {
                            updatedAbility
                        } else {
                            it
                        }
                    }
                )
                showPreview = null
            }
        )
    }
}

@Composable
private fun AbilityPreviewDialog(
    ability: Ability,
    modelId: String,
    onDismiss: () -> Unit,
    onConfirm: (Ability) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var previewProvider by remember { mutableStateOf<PreviewProvider?>(null) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                previewProvider?.release()
                previewProvider = null
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            previewProvider?.release()
        }
    }

    ModalBottomSheet(
        onDismissRequest = {
            previewProvider?.release()
            onDismiss()
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(
                    when (ability) {
                        is Ability.Vision -> R.string.ability_vision
                        is Ability.Audio -> R.string.ability_audio
                        is Ability.Video -> R.string.ability_video
                        is Ability.Document -> R.string.ability_document
                    }
                ),
                style = MaterialTheme.typography.titleLarge
            )

            when (ability) {
                is Ability.Vision -> VisionAbilityPreview(
                    ability = ability,
                    modelId = modelId,
                    onAbilityChange = {
                        // Update in real-time
                    },
                    previewProvider = previewProvider,
                    onPreviewProviderChange = { previewProvider = it }
                )

                is Ability.Audio -> AudioAbilityPreview(
                    ability = ability,
                    modelId = modelId,
                    onAbilityChange = {},
                    previewProvider = previewProvider,
                    onPreviewProviderChange = { previewProvider = it }
                )

                is Ability.Video -> VideoAbilityPreview(
                    ability = ability,
                    modelId = modelId,
                    onAbilityChange = {},
                    previewProvider = previewProvider,
                    onPreviewProviderChange = { previewProvider = it }
                )

                is Ability.Document -> DocumentAbilityPreview(
                    ability = ability,
                    modelId = modelId,
                    onAbilityChange = {},
                    previewProvider = previewProvider,
                    onPreviewProviderChange = { previewProvider = it }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        previewProvider?.release()
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.cancel))
                }

                Button(
                    onClick = {
                        previewProvider?.release()
                        onConfirm(ability)
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.confirm))
                }
            }
        }
    }
}

@Composable
private fun VisionAbilityPreview(
    ability: Ability.Vision,
    modelId: String,
    onAbilityChange: (Ability.Vision) -> Unit,
    previewProvider: PreviewProvider?,
    onPreviewProviderChange: (PreviewProvider?) -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.ability_vision_description),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun AudioAbilityPreview(
    ability: Ability.Audio,
    modelId: String,
    onAbilityChange: (Ability.Audio) -> Unit,
    previewProvider: PreviewProvider?,
    onPreviewProviderChange: (PreviewProvider?) -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.ability_audio_description),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun VideoAbilityPreview(
    ability: Ability.Video,
    modelId: String,
    onAbilityChange: (Ability.Video) -> Unit,
    previewProvider: PreviewProvider?,
    onPreviewProviderChange: (PreviewProvider?) -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.ability_video_description),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun DocumentAbilityPreview(
    ability: Ability.Document,
    modelId: String,
    onAbilityChange: (Ability.Document) -> Unit,
    previewProvider: PreviewProvider?,
    onPreviewProviderChange: (PreviewProvider?) -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.ability_document_description),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun SpeedTiersOptInRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    SettingSwitch(
        icon = HugeIcons.Tools,
        title = stringResource(R.string.provider_model_supports_speed_tiers),
        checked = checked,
        onCheckedChange = onCheckedChange
    )
}

private fun ProviderSetting.copyProvider(
    name: String = this.name,
    balanceOption: ProviderSetting.BalanceOption = this.balanceOption,
): ProviderSetting {
    return when (this) {
        is ProviderSetting.OpenAI -> copy(name = name, balanceOption = balanceOption)
        is ProviderSetting.Gemini -> copy(name = name)
        is ProviderSetting.Codex -> copy(name = name)
        is ProviderSetting.Claude -> copy(name = name)
        is ProviderSetting.Deepseek -> copy(name = name)
        is ProviderSetting.Relay -> copy(name = name)
    }
}

@Composable
private fun stringResource(id: Int): String {
    return androidx.compose.ui.res.stringResource(id)
}
