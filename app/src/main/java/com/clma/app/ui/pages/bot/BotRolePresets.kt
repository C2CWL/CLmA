// Adapted from OpenMausBot (https://github.com/milind-soni/OpenMausBot), src/components/ReadyBotGallery.tsx, src/lib/ready-bots.ts, src/components/ShareTemplateDialog.tsx,
// Copyright 2026 Milind Soni and OpenMausBot contributors, Apache License 2.0
// (third_party/openmausbot/LICENSE). Modified: ported to Kotlin and changed for CLmA.
package com.clma.app.ui.pages.bot

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.clma.app.R
import com.clma.app.data.botmode.BotRolePreset
import com.clma.app.data.botmode.BotRolePresetFile
import com.clma.app.data.botmode.settings.BotProfileStore
import com.clma.app.data.model.Assistant
import com.clma.app.data.model.isBot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

private val roleChoices = listOf(
    R.string.bot_presets_assistant to R.string.bot_presets_assistant_prompt,
    R.string.bot_presets_research to R.string.bot_presets_research_prompt,
    R.string.bot_presets_writer to R.string.bot_presets_writer_prompt,
)

@Composable
internal fun BotRolePresetPicker(onImporting: (Boolean) -> Unit, onPick: (BotRolePreset) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var choosing by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        onImporting(true)
        scope.launch {
            try {
                val preset = withContext(Dispatchers.IO) {
                    val input = checkNotNull(context.contentResolver.openInputStream(uri))
                    input.use(BotRolePresetFile::read)
                }
                onPick(preset)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Toast.makeText(context, R.string.bot_presets_import_failed, Toast.LENGTH_SHORT).show()
            } finally {
                importing = false
                onImporting(false)
            }
        }
    }
    Row {
        Column {
            TextButton(onClick = { choosing = true }, enabled = !importing) {
                Text(stringResource(R.string.bot_presets_roles))
            }
            DropdownMenu(expanded = choosing, onDismissRequest = { choosing = false }) {
                roleChoices.forEach { (title, prompt) ->
                    val roleName = stringResource(title)
                    val instructions = stringResource(prompt)
                    DropdownMenuItem(
                        text = { Text(roleName) },
                        onClick = {
                            choosing = false
                            onPick(BotRolePreset(1, roleName, instructions))
                        },
                    )
                }
            }
        }
        TextButton(onClick = { importFile.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, enabled = !importing) {
            Text(stringResource(if (importing) R.string.bot_presets_importing else R.string.bot_presets_import))
        }
    }
}

@Composable
internal fun BotRolePresetExport(bot: Assistant, fallbackName: String) {
    if (!bot.isBot) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val profiles = koinInject<BotProfileStore>()
    var exporting by remember { mutableStateOf(false) }
    val exportFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        exporting = true
        scope.launch {
            val message = try {
                val profile = profiles.profile(bot.id.toString())
                val prompt = listOf(profile.description, bot.systemPrompt, profile.soul)
                    .map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n\n")
                val bytes = BotRolePresetFile.encode(
                    BotRolePreset(1, bot.name.ifBlank { fallbackName }, prompt, bot.enabledSkills.sorted()),
                )
                withContext(Dispatchers.IO) {
                    val output = checkNotNull(context.contentResolver.openOutputStream(uri, "wt"))
                    output.use { it.write(bytes) }
                }
                R.string.bot_presets_export_done
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                R.string.bot_presets_export_failed
            } finally {
                exporting = false
            }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
    TextButton(onClick = { exportFile.launch("bot-role.json") }, enabled = !exporting) {
        Text(stringResource(R.string.bot_presets_export))
    }
}
