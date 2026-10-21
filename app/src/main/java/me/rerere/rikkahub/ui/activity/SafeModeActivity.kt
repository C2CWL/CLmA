package me.rerere.rikkahub.ui.activity

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import me.rerere.rikkahub.R
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.ui.hooks.writeStringPreference
import me.rerere.rikkahub.ui.theme.RikkahubTheme
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.utils.CrashHandler
import org.koin.android.ext.android.inject
import kotlin.uuid.Uuid

class SafeModeActivity : ComponentActivity() {
    private val settingsStore by inject<SettingsStore>()

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val stackTrace = CrashHandler.getStackTrace(this)
        CrashHandler.clearCrashed(this)
        enableEdgeToEdge()
        setContent {
            RikkahubTheme {
                val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
                var showAssistantPicker by remember { mutableStateOf(false) }
                var crashDetailsExpanded by remember { mutableStateOf(false) }
                val scope = rememberCoroutineScope()
                val context = LocalContext.current

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        TopAppBar(title = { Text(stringResource(R.string.safe_mode_title)) })
                    }
                ) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.safe_mode_description),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (stackTrace != null) {
                            // Plain-language summary first: most users can't read a stack trace,
                            // and "the app crashed" alone doesn't tell them whether to switch
                            // assistant or just go back in.
                            Text(
                                text = stringResource(R.string.safe_mode_crash_headline),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = stringResource(
                                    R.string.safe_mode_crash_likely,
                                    stringResource(crashCauseRes(stackTrace)),
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        Text(
                            text = stringResource(
                                R.string.safe_mode_current_assistant,
                                settings.getCurrentAssistant().name.ifEmpty { stringResource(R.string.safe_mode_default_assistant) }),
                            style = MaterialTheme.typography.bodyLarge,
                        )

                        Button(
                            onClick = { showAssistantPicker = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.safe_mode_switch_assistant))
                        }

                        OutlinedButton(
                            onClick = {
                                startActivity(Intent(this@SafeModeActivity, RouteActivity::class.java))
                                finish()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.safe_mode_enter_app))
                        }

                        if (stackTrace != null) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            ) {
                                TextButton(onClick = { crashDetailsExpanded = !crashDetailsExpanded }) {
                                    Text(
                                        text = stringResource(
                                            if (crashDetailsExpanded) R.string.error_action_hide_details
                                            else R.string.safe_mode_crash_details
                                        )
                                    )
                                }
                                OutlinedButton(
                                    onClick = {
                                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        cm.setPrimaryClip(ClipData.newPlainText("crash", stackTrace))
                                    }
                                ) {
                                    Text(stringResource(R.string.safe_mode_copy))
                                }
                            }
                            AnimatedVisibility(
                                visible = crashDetailsExpanded,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    // Same disclaimer as the chat error card: the summary above is
                                    // a keyword guess, the stack trace is the ground truth.
                                    Text(
                                        text = stringResource(R.string.safe_mode_crash_details_hint),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .fillMaxHeight(),
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                                        )
                                    ) {
                                        val vScroll = rememberScrollState()
                                        val hScroll = rememberScrollState()
                                        Text(
                                            text = stackTrace,
                                            modifier = Modifier
                                                .padding(12.dp)
                                                .verticalScroll(vScroll)
                                                .horizontalScroll(hScroll),
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = FontFamily.Monospace,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                if (showAssistantPicker) {
                    AssistantPickerSheet(
                        settings = settings,
                        onAssistantSelected = { assistantId ->
                            scope.launch { settingsStore.updateAssistant(assistantId) }
                            context.writeStringPreference("lastConversationId", null)
                            showAssistantPicker = false
                        },
                        onDismiss = { showAssistantPicker = false }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AssistantPickerSheet(
    settings: Settings,
    onAssistantSelected: (Uuid) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))
    val scope = rememberCoroutineScope()
    var selectedTagIds by remember { mutableStateOf(emptySet<Uuid>()) }
    val filteredAssistants = remember(settings.assistants, selectedTagIds) {
        if (selectedTagIds.isEmpty()) settings.assistants
        else settings.assistants.filter { it.tags.any { id -> id in selectedTagIds } }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.8f)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.safe_mode_assistants),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            if (settings.assistantTags.isNotEmpty()) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 8.dp)
                ) {
                    items(settings.assistantTags, key = { it.id }) { tag ->
                        FilterChip(
                            onClick = {
                                selectedTagIds = if (tag.id in selectedTagIds) {
                                    selectedTagIds - tag.id
                                } else {
                                    selectedTagIds + tag.id
                                }
                            },
                            label = { Text(tag.name) },
                            selected = tag.id in selectedTagIds,
                            shape = RoundedCornerShape(50),
                        )
                    }
                }
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(filteredAssistants, key = { it.id }) { assistant ->
                    val checked = assistant.id == settings.assistantId
                    Card(
                        onClick = {
                            scope.launch {
                                sheetState.hide()
                                onAssistantSelected(assistant.id)
                            }
                        },
                        modifier = Modifier.animateItem(),
                        shape = MaterialTheme.shapes.large,
                        colors = CardDefaults.cardColors(
                            containerColor = if (checked) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surface,
                            contentColor = if (checked) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurface,
                        ),
                    ) {
                        Text(
                            text = assistant.name.ifEmpty { stringResource(R.string.safe_mode_default_assistant) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Best-effort keyword guess at what kind of crash this was. Deliberately coarse: the goal is to
 * tell a non-technical user whether this smells like memory, local data or an internal bug — not
 * to classify the exception precisely. The full stack trace is one tap away.
 */
private fun crashCauseRes(stackTrace: String): Int = when {
    stackTrace.contains("OutOfMemoryError") -> R.string.safe_mode_crash_cause_memory
    stackTrace.contains("SQLite", ignoreCase = true) ||
        stackTrace.contains("database", ignoreCase = true) -> R.string.safe_mode_crash_cause_storage
    stackTrace.contains("NullPointerException") ||
        stackTrace.contains("IllegalStateException") ||
        stackTrace.contains("IllegalArgumentException") -> R.string.safe_mode_crash_cause_internal
    else -> R.string.safe_mode_crash_cause_unknown
}
