package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.glass.GlassDefaults
import dev.chrisbanes.haze.glass.GlassStyle
import dev.chrisbanes.haze.glass.OpticalSizeValue
import dev.chrisbanes.haze.glass.RefractionProfile
import dev.chrisbanes.haze.glass.SurfaceProfile
import dev.chrisbanes.haze.glass.material3.Material3

/**
 * Liquid glass recipe for floating surfaces (chat input bar and top-bar glass buttons).
 *
 * Characteristics:
 * 1. Translucent container tint so the backdrop is refracted rather than veiled in grey.
 * 2. Optical refraction with [SurfaceProfile.Squircle] and [RefractionProfile.Surface], creating
 *    a curved liquid dome that bends text and content passing underneath.
 * 3. Subtle chromatic dispersion and crisp specular highlights for a fluid, refractive glass feel.
 */
@Composable
fun rikkaGlassStyle(
    containerShape: RoundedCornerShape,
    blurRadius: Dp = 20.dp,
    depth: Float = 0.5f,
): GlassStyle {
    val base = MaterialTheme.colorScheme.surfaceContainer
    return GlassStyle.Material3(
        containerColor = base.copy(alpha = 0.26f),
        tint = base.copy(alpha = 0.16f),
    ) {
        optics(
            GlassDefaults.optics.copy(
                blurRadius = OpticalSizeValue.Fixed(blurRadius),
                depth = OpticalSizeValue.Fixed(depth),
                refractionStrength = 0.65f,
                refractionHeightFraction = 0.22f,
                refractionDisplacement = 18.dp,
                refractionDetailIntensity = 0.60f,
                refractionFoldStrength = 0f,
                refractionProfile = RefractionProfile.Surface,
            )
        )
        surfaceProfile(SurfaceProfile.Squircle)
        specularIntensity(0.48f)
        specularExponent(32f)
        chromaticAberrationStrength(0.16f)
        edgeShadow(Color.Black.copy(alpha = 0.08f))
        shape(containerShape)
    }
}
