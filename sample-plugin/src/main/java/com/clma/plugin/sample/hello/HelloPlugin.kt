package com.clma.plugin.sample.hello

import android.widget.TextView
import com.clma.plugin.api.ClmaPlugin
import com.clma.plugin.api.PluginDescriptor
import com.clma.plugin.api.PluginSettingsSurface
import com.clma.plugin.api.PluginTool
import com.clma.plugin.api.PluginToolDescriptor
import org.json.JSONObject

class HelloPlugin : ClmaPlugin {
    override val descriptor = PluginDescriptor(
        id = "com.clma.plugin.hello",
        name = "Hello Plugin",
        version = "0.1.0",
        description = "A minimal native Android plugin example.",
    )

    private val helloTool = object : PluginTool {
        override val descriptor = PluginToolDescriptor(
            name = "hello",
            description = "Return a greeting from a native Android plugin.",
            inputSchemaJson = """{"type":"object","properties":{"who":{"type":"string","description":"Name to greet"}}}""",
            needsApproval = false,
        )

        override suspend fun execute(inputJson: String): String {
            val who = runCatching { JSONObject(inputJson).optString("who") }
                .getOrDefault("CLmA")
                .ifBlank { "CLmA" }
            return "Hello, $who! This greeting came from a native Android plugin."
        }
    }

    override fun tools(): List<PluginTool> = listOf(helloTool)

    override fun settingsSurface(): PluginSettingsSurface = PluginSettingsSurface { context ->
        TextView(context).apply {
            text = "Hello Plugin is active. It contributes one tool: plugin__com.clma.plugin.hello__hello"
            textSize = 15f
            setPadding(32, 24, 32, 24)
        }
    }
}
