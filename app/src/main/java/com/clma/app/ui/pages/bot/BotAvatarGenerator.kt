// Adapted from OpenMausBot (https://github.com/milind-soni/OpenMausBot), src/components/AvatarImageGenerator.tsx, src/components/BotProfileAvatarCard.tsx, server/routes/image-settings.ts,
// Copyright 2026 Milind Soni and OpenMausBot contributors, Apache License 2.0
// (third_party/openmausbot/LICENSE). Modified: ported to Kotlin and changed for CLmA.
package com.clma.app.ui.pages.bot

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clma.ai.provider.ImageGenerationParams
import com.clma.ai.provider.ProviderManager
import com.clma.ai.ui.ImageAspectRatio
import com.clma.app.R
import com.clma.app.data.botmode.settings.BotProfileStore
import com.clma.app.data.datastore.SettingsStore
import com.clma.app.data.datastore.findModelById
import com.clma.app.data.datastore.findProvider
import com.clma.app.data.model.Assistant
import com.clma.app.data.model.isBot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

@Composable
internal fun BotAvatarGenerator(bot: Assistant, name: String, onAdopt: suspend (Bitmap) -> Boolean) {
    if (!bot.isBot) return
    var open by rememberSaveable(bot.id.toString()) { mutableStateOf(false) }
    TextButton(onClick = { open = true }) { Text(stringResource(R.string.bot_avatar_generate)) }
    if (open) BotAvatarDialog(bot, name, onAdopt, onClose = { open = false })
}

@Composable
private fun BotAvatarDialog(bot: Assistant, name: String, onAdopt: suspend (Bitmap) -> Boolean, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val settingsStore = koinInject<SettingsStore>()
    val profiles = koinInject<BotProfileStore>()
    val providers = koinInject<ProviderManager>()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val model = settings.findModelById(settings.imageGenerationModelId)
    val provider = model?.findProvider(settings.providers)
    var direction by rememberSaveable { mutableStateOf("") }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var generating by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    val busy = generating || saving

    AlertDialog(
        onDismissRequest = { if (!saving) onClose() },
        title = { Text(stringResource(R.string.bot_avatar_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(if (provider == null) R.string.bot_avatar_no_provider else R.string.bot_avatar_provider_hint))
                OutlinedTextField(
                    value = direction,
                    onValueChange = { if (it.length <= 1_000) direction = it },
                    label = { Text(stringResource(R.string.bot_avatar_direction)) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                    maxLines = 3,
                )
                preview?.let {
                    Image(
                        it.asImageBitmap(),
                        contentDescription = stringResource(R.string.bot_avatar_preview),
                        modifier = Modifier.size(120.dp),
                        contentScale = ContentScale.Crop,
                    )
                }
                TextButton(enabled = !busy && model != null && provider != null, onClick = {
                    if (model == null || provider == null) return@TextButton
                    val artDirection = direction.trim()
                    generating = true
                    error = null
                    scope.launch {
                        try {
                            val profile = profiles.profile(bot.id.toString())
                            val role = listOf(profile.description, bot.systemPrompt, profile.soul)
                                .map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n").take(3_000)
                            val prompt = buildString {
                                append("Create a square profile icon for an AI assistant named ").append(name.take(200))
                                append(". Use one clear subject, a simple background, and no lettering.")
                                if (role.isNotEmpty()) append("\nRole inspiration: ").append(role)
                                if (artDirection.isNotEmpty()) append("\nArt direction: ").append(artDirection)
                            }
                            preview = withContext(Dispatchers.IO) {
                                val result = providers.getProviderByType(provider).generateImage(
                                    provider,
                                    ImageGenerationParams(
                                        model = model,
                                        prompt = prompt,
                                        numOfImages = 1,
                                        aspectRatio = ImageAspectRatio.SQUARE,
                                        customHeaders = model.customHeaders,
                                        customBody = model.customBodies,
                                    ),
                                ).first { !it.partial }
                                decodeAvatarPreview(result.data)
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            error = R.string.bot_avatar_failed
                        } finally {
                            generating = false
                        }
                    }
                }) {
                    Text(stringResource(if (generating) R.string.bot_avatar_generating else R.string.bot_avatar_generate))
                }
                error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && preview != null, onClick = {
                val image = preview ?: return@TextButton
                saving = true
                error = null
                scope.launch {
                    try {
                        if (onAdopt(image)) onClose() else error = R.string.bot_settings_save_failed
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        error = R.string.bot_settings_save_failed
                    } finally {
                        saving = false
                    }
                }
            }) { Text(stringResource(R.string.bot_avatar_use)) }
        },
        dismissButton = {
            TextButton(enabled = !saving, onClick = onClose) { Text(stringResource(R.string.bot_home_cancel)) }
        },
    )
}

private fun decodeAvatarPreview(data: String): Bitmap {
    val payload = if (data.startsWith("data:image/")) data.substringAfter("base64,") else data
    require(payload.length <= 32 * 1024 * 1024)
    val bytes = Base64.decode(payload, Base64.DEFAULT)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 512) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options))
}
