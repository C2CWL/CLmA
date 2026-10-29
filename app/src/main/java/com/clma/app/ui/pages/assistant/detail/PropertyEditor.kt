package com.clma.app.ui.pages.assistant.detail

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete01
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import com.clma.ai.provider.CustomBody
import com.clma.ai.provider.CustomHeader
import com.clma.highlight.LocalCodeHighlighter
import com.clma.app.R
import com.clma.app.ui.components.ui.CardGroup
import com.clma.app.ui.components.ui.SelectTextField
import com.clma.app.ui.components.richtext.HighlightCodeVisualTransformation
import com.clma.app.ui.hooks.rememberSyncedTextField
import com.clma.app.ui.theme.JetbrainsMono
import com.clma.app.ui.theme.LocalDarkMode

private val jsonLenient = Json {
    ignoreUnknownKeys = true
    isLenient = true
    prettyPrint = true
}

private val COMMON_HEADER_NAMES = listOf(
    "User-Agent",
    "HTTP-Referer",
    "X-Title",
    "Referer",
    "Origin",
    "Accept-Language",
    "Cookie",
    "anthropic-beta",
    "OpenAI-Organization",
    "OpenAI-Project",
)

// 按已输入内容过滤常用请求头，没有匹配(或已完整输入)时展示全部
private fun commonHeaderNames(input: String): List<String> {
    val keyword = input.trim()
    val matched = COMMON_HEADER_NAMES.filter {
        it.contains(keyword, ignoreCase = true) && !it.equals(keyword, ignoreCase = true)
    }
    return matched.ifEmpty { COMMON_HEADER_NAMES }
}

@Composable
fun CustomHeaders(headers: List<CustomHeader>, onUpdate: (List<CustomHeader>) -> Unit) {
    Column(
        modifier = Modifier.padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(stringResource(R.string.assistant_page_custom_headers))
        Spacer(Modifier.height(8.dp))

        headers.forEachIndexed { index, header ->
            // Local field state: the list may be persisted asynchronously, and re-keying the text on
            // every echo used to fight the IME (duplicated characters / jumping cursor).
            val headerNameField = rememberSyncedTextField(
                externalValue = header.name,
                key = index,
                expectedEcho = { it.trim() },
            ) { name ->
                val updatedHeaders = headers.toMutableList()
                if (index in updatedHeaders.indices) {
                    updatedHeaders[index] = updatedHeaders[index].copy(name = name.trim())
                    onUpdate(updatedHeaders)
                }
            }
            val headerValueField = rememberSyncedTextField(
                externalValue = header.value,
                key = index,
                expectedEcho = { it.trim() },
            ) { value ->
                val updatedHeaders = headers.toMutableList()
                if (index in updatedHeaders.indices) {
                    updatedHeaders[index] = updatedHeaders[index].copy(value = value.trim())
                    onUpdate(updatedHeaders)
                }
            }

            CardGroup {
                item(
                    supportingContent = {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            SelectTextField(
                                value = headerNameField.text,
                                options = commonHeaderNames(headerNameField.text),
                                onValueChange = headerNameField::onTextChange,
                                onOptionSelected = { name: String -> headerNameField.onTextChange(name) },
                                label = { Text(stringResource(R.string.assistant_page_header_name)) },
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = headerValueField.value,
                                onValueChange = headerValueField::onValueChange,
                                label = { Text(stringResource(R.string.assistant_page_header_value)) },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    },
                    trailingContent = {
                        IconButton(onClick = {
                            val updatedHeaders = headers.toMutableList()
                            updatedHeaders.removeAt(index)
                            onUpdate(updatedHeaders)
                        }) {
                            Icon(
                                HugeIcons.Delete01,
                                contentDescription = stringResource(R.string.assistant_page_delete_header)
                            )
                        }
                    },
                    headlineContent = {},
                )
            }
        }

        Button(
            onClick = {
                val updatedHeaders = headers.toMutableList()
                updatedHeaders.add(CustomHeader("", ""))
                onUpdate(updatedHeaders)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(HugeIcons.Add01, contentDescription = stringResource(R.string.assistant_page_add_header))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.assistant_page_add_header))
        }
    }
}

@Composable
fun CustomBodies(customBodies: List<CustomBody>, onUpdate: (List<CustomBody>) -> Unit) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(stringResource(R.string.assistant_page_custom_bodies))
        Spacer(Modifier.height(8.dp))

        customBodies.forEachIndexed { index, body ->
            val bodyKeyField = rememberSyncedTextField(
                externalValue = body.key,
                key = index,
                expectedEcho = { it.trim() },
            ) { key ->
                val updatedBodies = customBodies.toMutableList()
                if (index in updatedBodies.indices) {
                    updatedBodies[index] = updatedBodies[index].copy(key = key.trim())
                    onUpdate(updatedBodies)
                }
            }
            var jsonParseError by remember { mutableStateOf<String?>(null) }
            // The persisted value is a JsonElement; compare in its re-encoded form so our own
            // writes are recognised as echoes and the user's formatting is left alone.
            val bodyValueField = rememberSyncedTextField(
                externalValue = jsonLenient.encodeToString(JsonElement.serializer(), body.value),
                key = index,
                expectedEcho = { text ->
                    runCatching {
                        jsonLenient.encodeToString(JsonElement.serializer(), jsonLenient.parseToJsonElement(text))
                    }.getOrNull() ?: text
                },
            ) { newString ->
                try {
                    val newJsonValue = jsonLenient.parseToJsonElement(newString)
                    val updatedBodies = customBodies.toMutableList()
                    if (index in updatedBodies.indices) {
                        updatedBodies[index] = updatedBodies[index].copy(value = newJsonValue)
                        onUpdate(updatedBodies)
                    }
                    jsonParseError = null
                } catch (e: Exception) {
                    jsonParseError =
                        context.getString(
                            R.string.assistant_page_invalid_json,
                            e.message?.take(100) ?: ""
                        )
                }
            }

            CardGroup {
                item(
                    supportingContent = {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedTextField(
                                value = bodyKeyField.value,
                                onValueChange = bodyKeyField::onValueChange,
                                label = { Text(stringResource(R.string.assistant_page_body_key)) },
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = bodyValueField.value,
                                onValueChange = bodyValueField::onValueChange,
                                label = { Text(stringResource(R.string.assistant_page_body_value)) },
                                modifier = Modifier.fillMaxWidth(),
                                isError = jsonParseError != null,
                                supportingText = {
                                    if (jsonParseError != null) {
                                        Text(jsonParseError!!)
                                    }
                                },
                                minLines = 3,
                                maxLines = 5,
                                visualTransformation = HighlightCodeVisualTransformation(
                                    language = "json",
                                    highlighter = LocalCodeHighlighter.current,
                                    darkMode = LocalDarkMode.current
                                ),
                                textStyle = LocalTextStyle.current.merge(fontFamily = JetbrainsMono),
                            )
                        }
                    },
                    trailingContent = {
                        IconButton(onClick = {
                            val updatedBodies = customBodies.toMutableList()
                            updatedBodies.removeAt(index)
                            onUpdate(updatedBodies)
                        }) {
                            Icon(
                                HugeIcons.Delete01,
                                contentDescription = stringResource(R.string.assistant_page_delete_body)
                            )
                        }
                    },
                    headlineContent = {},
                )
            }
        }

        Button(
            onClick = {
                val updatedBodies = customBodies.toMutableList()
                updatedBodies.add(CustomBody("", JsonPrimitive("")))
                onUpdate(updatedBodies)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(HugeIcons.Add01, contentDescription = stringResource(R.string.assistant_page_add_body))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.assistant_page_add_body))
        }
    }
}
