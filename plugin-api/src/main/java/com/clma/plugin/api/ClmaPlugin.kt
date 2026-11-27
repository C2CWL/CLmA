package com.clma.plugin.api

import android.content.Context
import android.view.View

/** Static metadata declared by a native plugin. */
data class PluginDescriptor(
    val id: String,
    val name: String,
    val version: String,
    val description: String = "",
    val minHostVersion: String? = null,
)

/** Description of one tool contributed by a plugin. */
data class PluginToolDescriptor(
    val name: String,
    val description: String,
    /** JSON Schema object for the tool input, serialized as a string. */
    val inputSchemaJson: String = """{"type":"object","properties":{}}""",
    /** Whether the host should ask the user before running this tool. */
    val needsApproval: Boolean = false,
)

/** A tool implemented by a native plugin. */
interface PluginTool {
    val descriptor: PluginToolDescriptor

    /** Receives a JSON object string and returns a plain text result. */
    suspend fun execute(inputJson: String): String
}

/** An optional Android View rendered inside the plugin's settings card. */
fun interface PluginSettingsSurface {
    fun createView(context: Context): View
}

/**
 * Entry point every native Android plugin must implement.
 *
 * The host installs a signed/unverified APK from user-selected storage, reads
 * `assets/clma-plugin.json` for [entry], and instantiates the class with a
 * DexClassLoader. The API types are loaded from the host first, so plugins and
 * the host must use a compatible `plugin-api` contract.
 */
interface ClmaPlugin {
    val descriptor: PluginDescriptor

    fun tools(): List<PluginTool> = emptyList()

    fun settingsSurface(): PluginSettingsSurface? = null
}
