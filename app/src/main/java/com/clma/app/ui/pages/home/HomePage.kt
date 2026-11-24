package com.clma.app.ui.pages.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.clma.app.R
import com.clma.app.Screen
import com.clma.app.ui.components.ui.Greeting
import com.clma.app.ui.context.LocalNavController
import com.clma.app.ui.pages.chat.MeshGradientBackground
import com.clma.app.ui.theme.LocalDarkMode
import com.clma.app.utils.base64Encode
import com.clma.app.utils.navigateToChatPage
import com.composables.icons.lucide.ArrowUp
import com.composables.icons.lucide.BookOpen
import com.composables.icons.lucide.Bot
import com.composables.icons.lucide.CodeXml
import com.composables.icons.lucide.FolderOpen
import com.composables.icons.lucide.Lightbulb
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Menu
import com.composables.icons.lucide.Mic
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Sparkles

const val HOME_AS_START_PAGE_PREF_KEY = "home_as_start_page_enabled"

private data class HomePrompt(
    val icon: ImageVector,
    val titleRes: Int,
    val subtitleRes: Int,
    val promptRes: Int,
    val tint: @Composable () -> Color,
)

/** A calm, standalone starting page — inspired by DSH go's dark landing screen.
 *  It never touches the existing chat layouts: cards and the composer simply open
 *  a fresh temporary chat with a prefill. */
@Composable
fun HomePage() {
    val navController = LocalNavController.current
    val darkMode = LocalDarkMode.current
    val keyboard = LocalSoftwareKeyboardController.current
    var draft by remember { mutableStateOf("") }
    val scrollState = rememberScrollState()

    val prompts = listOf(
        HomePrompt(Lucide.FolderOpen, R.string.home_prompt_workspace_title,
            R.string.home_prompt_workspace_sub, R.string.home_prompt_workspace_text,
            { Color(0xFF74C7A6) }),
        HomePrompt(Lucide.CodeXml, R.string.home_prompt_script_title,
            R.string.home_prompt_script_sub, R.string.home_prompt_script_text,
            { Color(0xFFB39DFF) }),
        HomePrompt(Lucide.BookOpen, R.string.home_prompt_project_title,
            R.string.home_prompt_project_sub, R.string.home_prompt_project_text,
            { Color(0xFFE8B563) }),
        HomePrompt(Lucide.Lightbulb, R.string.home_prompt_git_title,
            R.string.home_prompt_git_sub, R.string.home_prompt_git_text,
            { Color(0xFF6FAFE8) }),
    )

    fun newChat() {
        navigateToChatPage(navigator = navController, isTemporary = true)
    }

    fun start(text: String) {
        if (text.isBlank()) return
        // Screen.Chat.text is base64-encoded by convention (see applyInitialChatInput).
        navigateToChatPage(navigator = navController, initText = text.trim().base64Encode(), isTemporary = true)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (darkMode) {
            MeshGradientBackground(modifier = Modifier.fillMaxSize())
        } else {
            Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding(),
        ) {
            // Top bar: menu + new chat + assistant switcher
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)),
                    modifier = Modifier.size(48.dp).clickable { navController.navigate(Screen.Setting) },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Lucide.Menu, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)),
                    modifier = Modifier.clickable { newChat() },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.home_new_chat),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)),
                    modifier = Modifier.clickable { navController.navigate(Screen.Assistant) },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Lucide.Bot, contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.home_assistant),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            // Center: greeting + prompt grid
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(scrollState)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Greeting(
                    style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Light),
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(R.string.home_subtitle),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(28.dp))

                for (i in prompts.indices step 2) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        for (p in prompts.subList(i, minOf(i + 2, prompts.size))) {
                            val promptText = stringResource(p.promptRes)
                            HomePromptCard(
                                prompt = p,
                                modifier = Modifier.weight(1f),
                                onClick = { start(promptText) },
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                }
            }

            // Bottom: floating composer
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .heightIn(min = 60.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Lucide.Plus, contentDescription = null,
                        modifier = Modifier.padding(start = 16.dp).size(22.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextField(
                        value = draft,
                        onValueChange = { draft = it },
                        placeholder = {
                            Text(
                                stringResource(R.string.home_composer_hint),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    if (draft.isBlank()) {
                        Icon(
                            Lucide.Mic, contentDescription = null,
                            modifier = Modifier.padding(end = 18.dp).size(22.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .padding(end = 12.dp)
                                .size(40.dp)
                                .clickable {
                                    start(draft)
                                    draft = ""
                                    keyboard?.hide()
                                },
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Lucide.ArrowUp, contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomePromptCard(
    prompt: HomePrompt,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val tint = prompt.tint()
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.62f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)),
        modifier = modifier
            .heightIn(min = 108.dp)
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Surface(shape = RoundedCornerShape(12.dp), color = tint.copy(alpha = 0.16f)) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(34.dp)) {
                    Icon(prompt.icon, contentDescription = null, modifier = Modifier.size(19.dp), tint = tint)
                }
            }
            Text(
                stringResource(prompt.titleRes),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Start,
            )
            Text(
                stringResource(prompt.subtitleRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Start,
            )
        }
    }
}
