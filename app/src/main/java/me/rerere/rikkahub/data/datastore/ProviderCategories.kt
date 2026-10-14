package me.rerere.rikkahub.data.datastore

import androidx.annotation.StringRes
import me.rerere.ai.provider.AICORE_PROVIDER_ID
import me.rerere.ai.provider.LLAMACPP_PROVIDER_ID
import me.rerere.ai.provider.LITERT_PROVIDER_ID
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.R
import kotlin.uuid.Uuid

/**
 * AI 服务商页的分组。内置（预配置）服务商按 国模 / 国际 / 本地 归类，页面上分组标题展示。
 * 用户在列表里自己添加、导入或从内置复制出来的服务商不属于任何内置分类，统一归入 [OTHER]。
 */
enum class ProviderCategory(@StringRes val titleRes: Int) {
    DOMESTIC(R.string.setting_provider_category_domestic),
    INTERNATIONAL(R.string.setting_provider_category_international),
    LOCAL(R.string.setting_provider_category_local),
    OTHER(R.string.setting_provider_category_other),
}

/**
 * 内置服务商 id -> 分组。id 是内置服务商的稳定标识
 * （见 [DEFAULT_PROVIDERS] 与 me.rerere.ai.provider.ProviderSetting），
 * 用户自己添加的服务商 id 是随机的，查不到即 [ProviderCategory.OTHER]。
 *
 * 分类按「模型/厂商归属」而非「接入协议」划分：
 * - 本地：端侧推理（AICore / LiteRT-LM / llama.cpp），不联网、无需 Key。
 * - 国模：国内厂商（含国内聚合网关）。
 * - 国际：海外厂商与全球聚合网关。
 */
private val BUILT_IN_PROVIDER_CATEGORY: Map<Uuid, ProviderCategory> = buildMap {
    // 本地（端侧）
    put(AICORE_PROVIDER_ID, ProviderCategory.LOCAL)
    put(LITERT_PROVIDER_ID, ProviderCategory.LOCAL)
    put(LLAMACPP_PROVIDER_ID, ProviderCategory.LOCAL)

    // 国模
    put(Uuid.parse("56a94d29-c88b-41c5-8e09-38a7612d6cf8"), ProviderCategory.DOMESTIC) // 硅基流动
    put(Uuid.parse("f099ad5b-ef03-446d-8e78-7e36787f780b"), ProviderCategory.DOMESTIC) // DeepSeek
    put(Uuid.parse("d6c4d8c6-3f62-4ca9-a6f3-7ade6b15ecc3"), ProviderCategory.DOMESTIC) // 月之暗面
    put(Uuid.parse("f76cae46-069a-4334-ab8e-224e4979e58c"), ProviderCategory.DOMESTIC) // 阿里云百炼
    put(Uuid.parse("3dfd6f9b-f9d9-417f-80c1-ff8d77184191"), ProviderCategory.DOMESTIC) // 火山引擎
    put(Uuid.parse("3bc40dc1-b11a-46fa-863b-6306971223be"), ProviderCategory.DOMESTIC) // 智谱AI开放平台
    put(Uuid.parse("f4f8870e-82d3-495b-9b64-d58e508b3b2c"), ProviderCategory.DOMESTIC) // 阶跃星辰
    put(Uuid.parse("ef5d149b-8e34-404b-818c-6ec242e5c3c5"), ProviderCategory.DOMESTIC) // 腾讯Hunyuan
    put(Uuid.parse("a2bafe83-eaf8-47bf-a8c7-3dd82d89f637"), ProviderCategory.DOMESTIC) // MIMO
    put(Uuid.parse("b4deabea-20fb-4101-a74c-65679c7e4754"), ProviderCategory.DOMESTIC) // MiniMax (anthropic/v1 - minimaxi)
    put(Uuid.parse("b55716c7-e465-4666-a788-4243aab13fb3"), ProviderCategory.DOMESTIC) // MiniMax (anthropic/v1 - minimax.io)
    put(Uuid.parse("da93779f-3956-48cc-82ef-67bb482eaaf7"), ProviderCategory.DOMESTIC) // 302.AI

    // 国际
    put(Uuid.parse("a8d2d463-e8c0-41f2-b89e-f5eb8e716cce"), ProviderCategory.INTERNATIONAL) // RikkaHub
    put(Uuid.parse("1eeea727-9ee5-4cae-93e6-6fb01a4d051e"), ProviderCategory.INTERNATIONAL) // OpenAI
    put(DEFAULT_CODEX_PROVIDER_ID, ProviderCategory.INTERNATIONAL) // Codex
    put(DEFAULT_GROK_PROVIDER_ID, ProviderCategory.INTERNATIONAL) // Grok
    put(DEFAULT_GEMINI_OAUTH_PROVIDER_ID, ProviderCategory.INTERNATIONAL) // Gemini OAuth
    put(Uuid.parse("6ab18148-c138-4394-a46f-1cd8c8ceaa6d"), ProviderCategory.INTERNATIONAL) // Gemini
    put(Uuid.parse("d5734028-d39b-4d41-9841-fd648d65440e"), ProviderCategory.INTERNATIONAL) // OpenRouter
    put(Uuid.parse("386e0f29-8228-4512-affe-8fd8add82d88"), ProviderCategory.INTERNATIONAL) // Vercel AI Gateway
    put(Uuid.parse("ff3cde7e-0f65-43d7-8fb2-6475c99f5990"), ProviderCategory.INTERNATIONAL) // xAI
}

/** 服务商所属分组；非内置（用户自建/导入）一律 [ProviderCategory.OTHER]。 */
fun ProviderSetting.category(): ProviderCategory =
    BUILT_IN_PROVIDER_CATEGORY[id] ?: ProviderCategory.OTHER

/**
 * 已从 [DEFAULT_PROVIDERS] 删除的上游赞助内置服务商（条目里带返利/推广链接）。
 * 新装不再出现；老用户由 PreferencesStore 做一次性清理（只删没被动过的行）。
 */
val REMOVED_SPONSORED_PROVIDER_IDS: Set<Uuid> = setOf(
    Uuid.parse("1b1395ed-b702-4aeb-8bc1-b681c4456953"), // AiHubMix
    Uuid.parse("2a05506f-3a59-450a-a493-33a82bc85a81"), // APIMart
    Uuid.parse("da020a90-f7b3-4c29-b90e-c511a0630630"), // 小马算力
    Uuid.parse("aecf04fd-cb5c-4582-aed2-e8bf393923fd"), // 随想AI网关
    Uuid.parse("afbc54ad-807e-4455-9594-7d7a546356ad"), // MaruCode
    Uuid.parse("53027b08-1b58-43d5-90ed-29173203e3d8"), // AckAI
    Uuid.parse("4da09554-8844-4cc8-a4a9-fe1b2515e91b"), // UnifyLLM
)
