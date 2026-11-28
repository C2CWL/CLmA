package com.clma.app.data.plugins

enum class PluginRuntimeKind {
    NATIVE_APK,
    DSH_CLI,
    JS_RUNTIME,
    SOURCE_ONLY,
}

data class PluginRuntimePlan(
    val runtime: PluginRuntimeKind,
    val executableInHost: Boolean,
    val toolchain: String? = null,
    val reason: String? = null,
)

/**
 * Describes how a marketplace entry could run. This layer deliberately does
 * not download, load, or execute external code. It only answers which runtime
 * would be required and whether CLmA can execute it today.
 */
interface PluginRuntimeAdapter {
    fun supports(entry: PluginMarketEntry): Boolean
    fun plan(entry: PluginMarketEntry): PluginRuntimePlan
}

class NativeApkRuntimeAdapter : PluginRuntimeAdapter {
    override fun supports(entry: PluginMarketEntry): Boolean =
        entry.packageKind == PluginPackageKind.NATIVE_APK ||
            entry.installability == PluginInstallability.DIRECT_APK

    override fun plan(entry: PluginMarketEntry): PluginRuntimePlan = PluginRuntimePlan(
        runtime = PluginRuntimeKind.NATIVE_APK,
        executableInHost = true,
    )
}

class DshCliRuntimeAdapter : PluginRuntimeAdapter {
    override fun supports(entry: PluginMarketEntry): Boolean =
        entry.sourceKey == "dsh-go" || entry.packageKind == PluginPackageKind.DSH_GO_PACKAGE

    override fun plan(entry: PluginMarketEntry): PluginRuntimePlan = PluginRuntimePlan(
        runtime = PluginRuntimeKind.DSH_CLI,
        executableInHost = false,
        toolchain = "dsh CLI",
        reason = "External runtime only",
    )
}

class JsRuntimeAdapter : PluginRuntimeAdapter {
    override fun supports(entry: PluginMarketEntry): Boolean =
        entry.packageKind == PluginPackageKind.JS_TS_PACKAGE

    override fun plan(entry: PluginMarketEntry): PluginRuntimePlan = PluginRuntimePlan(
        runtime = PluginRuntimeKind.JS_RUNTIME,
        executableInHost = false,
        toolchain = "CLmA JS runtime adapter (planned)",
        reason = "JS runtime adapter is not implemented; no external code is executed",
    )
}

class SourceOnlyRuntimeAdapter : PluginRuntimeAdapter {
    override fun supports(entry: PluginMarketEntry): Boolean = true

    override fun plan(entry: PluginMarketEntry): PluginRuntimePlan = PluginRuntimePlan(
        runtime = PluginRuntimeKind.SOURCE_ONLY,
        executableInHost = false,
        reason = "Source discovery only",
    )
}

class PluginRuntimeAdapterRegistry(
    private val adapters: List<PluginRuntimeAdapter>,
) {
    fun plan(entry: PluginMarketEntry): PluginRuntimePlan =
        adapters.firstOrNull { it.supports(entry) }?.plan(entry)
            ?: PluginRuntimePlan(
                runtime = PluginRuntimeKind.SOURCE_ONLY,
                executableInHost = false,
                reason = "No runtime adapter",
            )
}
