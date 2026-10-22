package com.clma.app.data.datastore

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Which loading indicator the chat shows while the assistant is working.
 *
 * Persisted as a plain string ([serialName]) inside the display-setting JSON. Decoding goes
 * through [LoadingIndicatorStyleSerializer], which maps names of removed styles ("default",
 * "shimmer", "sparkle", "claude") and any unknown name to [APP_ICON] instead of throwing, so an
 * old saved pick can never make the whole display setting fail to load.
 */
@Serializable(with = LoadingIndicatorStyleSerializer::class)
enum class LoadingIndicatorStyle(val serialName: String) {
    /** Animated CLmA mark (the long-standing default). */
    APP_ICON("app_icon"),

    /** Atom icon spinning in a primary→tertiary gradient, phase label and three rising dots. */
    ATOM_DOTS("atom_dots"),

    /** Soft gradient orb with a glow, breathing and shifting between primary and tertiary. */
    BREATHING_ORB("breathing_orb"),

    /** Faint ring with a primary and a tertiary dot chasing each other, with fading tails. */
    TWIN_ORBIT("twin_orbit");

    companion object {
        /** Saved name → style; removed or unknown names fall back to [APP_ICON]. */
        fun fromSerialName(name: String?): LoadingIndicatorStyle =
            entries.firstOrNull { it.serialName == name } ?: APP_ICON
    }
}

object LoadingIndicatorStyleSerializer : KSerializer<LoadingIndicatorStyle> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.clma.app.data.datastore.LoadingIndicatorStyle", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: LoadingIndicatorStyle) {
        encoder.encodeString(value.serialName)
    }

    override fun deserialize(decoder: Decoder): LoadingIndicatorStyle =
        LoadingIndicatorStyle.fromSerialName(decoder.decodeString())
}

/**
 * Effective style. [style] is null until the user picks one in the selector. The old switch
 * ([useAppIconStyle]) used to choose between the app icon and the plain Material indicator; the
 * latter is gone, so both of its values now resolve to [LoadingIndicatorStyle.APP_ICON].
 */
@Suppress("UNUSED_PARAMETER")
fun resolveLoadingIndicatorStyle(style: LoadingIndicatorStyle?, useAppIconStyle: Boolean): LoadingIndicatorStyle =
    style ?: LoadingIndicatorStyle.APP_ICON

/**
 * Applies a selector pick. Picking the app icon also turns the legacy switch on, so an older build
 * reading the same settings shows the same thing; the newer styles leave the switch as it was.
 */
fun DisplaySetting.withLoadingIndicatorStyle(style: LoadingIndicatorStyle): DisplaySetting = copy(
    loadingIndicatorStyle = style,
    useAppIconStyleLoadingIndicator = when (style) {
        LoadingIndicatorStyle.APP_ICON -> true
        LoadingIndicatorStyle.ATOM_DOTS,
        LoadingIndicatorStyle.BREATHING_ORB,
        LoadingIndicatorStyle.TWIN_ORBIT -> useAppIconStyleLoadingIndicator
    },
)

val DisplaySetting.effectiveLoadingIndicatorStyle: LoadingIndicatorStyle
    get() = resolveLoadingIndicatorStyle(loadingIndicatorStyle, useAppIconStyleLoadingIndicator)
