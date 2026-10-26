package com.clma.app.data.datastore

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * 聊天界面屏幕常亮模式。
 *
 * 控制在聊天界面停留时的屏幕休眠行为：
 * - [NEVER]：关闭，遵循系统息屏设置；
 * - [WHILE_GENERATING]：仅在 AI 回复生成中保持常亮（默认）；
 * - [ALWAYS_IN_CHAT]：停留在聊天界面期间全程保持常亮。
 */
@Serializable(with = KeepScreenOnModeSerializer::class)
enum class KeepScreenOnMode(val serialName: String) {
    /** 关闭：遵循系统息屏设置。 */
    @SerialName("never")
    NEVER("never"),

    /** 仅生成中常亮：AI 回复生成中阻止息屏。 */
    @SerialName("while_generating")
    WHILE_GENERATING("while_generating"),

    /** 聊天界面始终常亮：停留于聊天界面全程保持常亮。 */
    @SerialName("always_in_chat")
    ALWAYS_IN_CHAT("always_in_chat");

    companion object {
        fun fromSerialName(name: String?): KeepScreenOnMode =
            entries.firstOrNull { it.serialName.equals(name, ignoreCase = true) } ?: WHILE_GENERATING
    }
}

object KeepScreenOnModeSerializer : KSerializer<KeepScreenOnMode> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.clma.app.data.datastore.KeepScreenOnMode", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: KeepScreenOnMode) {
        encoder.encodeString(value.serialName)
    }

    override fun deserialize(decoder: Decoder): KeepScreenOnMode =
        KeepScreenOnMode.fromSerialName(decoder.decodeString())
}
