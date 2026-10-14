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
import dev.chrisbanes.haze.glass.material3.Material3

/**
 * The one glass recipe shared by every floating surface — the drawer's two cards, the chat input
 * bar and the top-bar buttons — so they keep reading as the same material.
 *
 * Two properties matter, and both were learned the hard way:
 *
 * 1. **The container colour has to stay translucent.** `GlassStyle.Material3(containerColor = …)`
 *    routes that colour into `backgroundColor`, and the renderer keys `sourceIsOpaque` off
 *    `backgroundColor.alpha == 1f`: an opaque container makes the whole captured backdrop vanish,
 *    so the "glass" degenerates into a painted panel that also hides whatever scrolls behind it.
 * 2. **Refraction stays off.** Haze's default optics carry an edge bezel
 *    (`RefractionProfile.Edge(20.dp)` + a 48dp displacement). On a *large* surface whose backdrop is
 *    empty — the drawer card while the list has not scrolled under it yet — there is nothing to
 *    refract except the surface's own rim, which shows up as a stray bright/grey band along the
 *    edge.
 *
 * [blurRadius] and [depth] stay per-caller because they are size dependent: a compact button and a
 * full-width bar want different amounts of diffusion.
 */
@Composable
fun rikkaGlassStyle(
    containerShape: RoundedCornerShape,
    blurRadius: Dp = 20.dp,
    depth: Float = 0.5f,
): GlassStyle {
    val base = MaterialTheme.colorScheme.surfaceContainer
    return GlassStyle.Material3(
        containerColor = base.copy(alpha = 0.35f),
        tint = base.copy(alpha = 0.20f),
    ) {
        optics(
            GlassDefaults.optics.copy(
                blurRadius = OpticalSizeValue.Fixed(blurRadius),
                depth = OpticalSizeValue.Fixed(depth),
                refractionStrength = 0f,
                refractionHeightFraction = 0f,
                refractionDisplacement = 0.dp,
                refractionDetailIntensity = 0f,
                refractionFoldStrength = 0f,
            )
        )
        // A soft key light. The 0.48 default leaves a highlight that reads as a second rim on a
        // large, mostly-empty surface.
        specularIntensity(0.22f)
        edgeShadow(Color.Black.copy(alpha = 0.06f))
        shape(containerShape)
    }
}
