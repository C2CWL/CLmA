package com.clma.app.data.plugins

enum class PluginPackageKind {
    NATIVE_APK,
    DSH_GO_PACKAGE,
    GITHUB_SOURCE,
    UNKNOWN,
}

enum class PluginInstallability {
    DIRECT_APK,
    MANUAL_BUILD_REQUIRED,
    EXTERNAL_RUNTIME_ONLY,
    SOURCE_ONLY,
}

enum class PluginPackageNextStep {
    INSTALL_APK,
    BUILD_NATIVE_APK,
    USE_EXTERNAL_RUNTIME,
    OPEN_SOURCE,
}

data class PluginPackagePlan(
    val installability: PluginInstallability,
    val canInstallInApp: Boolean,
    val nextStep: PluginPackageNextStep,
    val toolchain: String? = null,
)

/**
 * Converts marketplace metadata into a local decision. This is intentionally
 * separate from [PluginMarketSourceAdapter]: a source can list many package
 * formats, while each package adapter decides what CLmA can do with one.
 */
interface PluginPackageAdapter {
    fun supports(entry: PluginMarketEntry): Boolean
    fun plan(entry: PluginMarketEntry): PluginPackagePlan
}

class NativeApkPackageAdapter : PluginPackageAdapter {
    override fun supports(entry: PluginMarketEntry): Boolean =
        entry.packageKind == PluginPackageKind.NATIVE_APK ||
            entry.installability == PluginInstallability.DIRECT_APK

    override fun plan(entry: PluginMarketEntry): PluginPackagePlan = PluginPackagePlan(
        installability = PluginInstallability.DIRECT_APK,
        canInstallInApp = true,
        nextStep = PluginPackageNextStep.INSTALL_APK,
    )
}

class DshGoPackageAdapter : PluginPackageAdapter {
    override fun supports(entry: PluginMarketEntry): Boolean =
        entry.sourceKey == "dsh-go" || entry.packageKind == PluginPackageKind.DSH_GO_PACKAGE

    override fun plan(entry: PluginMarketEntry): PluginPackagePlan = PluginPackagePlan(
        installability = PluginInstallability.EXTERNAL_RUNTIME_ONLY,
        canInstallInApp = false,
        nextStep = PluginPackageNextStep.USE_EXTERNAL_RUNTIME,
        toolchain = "dsh CLI",
    )
}

class GitHubSourcePackageAdapter : PluginPackageAdapter {
    override fun supports(entry: PluginMarketEntry): Boolean =
        entry.sourceKey == "github-topic" || entry.packageKind == PluginPackageKind.GITHUB_SOURCE

    override fun plan(entry: PluginMarketEntry): PluginPackagePlan = PluginPackagePlan(
        installability = PluginInstallability.SOURCE_ONLY,
        canInstallInApp = false,
        nextStep = PluginPackageNextStep.OPEN_SOURCE,
        toolchain = "Android SDK + CLmA plugin API",
    )
}

class PluginPackageAdapterRegistry(
    private val adapters: List<PluginPackageAdapter>,
) {
    fun plan(entry: PluginMarketEntry): PluginPackagePlan =
        adapters.firstOrNull { it.supports(entry) }?.plan(entry)
            ?: PluginPackagePlan(
                installability = PluginInstallability.MANUAL_BUILD_REQUIRED,
                canInstallInApp = false,
                nextStep = PluginPackageNextStep.BUILD_NATIVE_APK,
                toolchain = "Android SDK + CLmA plugin API",
            )
}
