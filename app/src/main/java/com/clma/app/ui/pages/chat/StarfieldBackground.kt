package com.clma.app.ui.pages.chat

import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import com.clma.app.ui.theme.LocalDarkMode
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * v4 「星空 / 柔雾」动态渐变：铺满星云色块；深色加发光星点与低频流星，浅色仅柔雾无星。
 *
 * 时钟节流与 [MeshGradientBackground] 相同（约 30fps，后台挂起）。
 */
private const val STARFIELD_FRAME_INTERVAL_MS = 33L
private const val METEOR_PERIOD_MS = 7_500f
private const val METEOR_VISIBLE_MS = 1_200f

@Composable
fun StarfieldMeshGradientBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val elapsedMillis = remember { mutableFloatStateOf(0f) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val start = withInfiniteAnimationFrameMillis { it } - elapsedMillis.floatValue.toLong()
            while (isActive) {
                val now = withInfiniteAnimationFrameMillis { it }
                elapsedMillis.floatValue = (now - start).toFloat()
                delay(STARFIELD_FRAME_INTERVAL_MS)
            }
        }
    }

    val dark = LocalDarkMode.current
    val baseGradient = if (dark) {
        arrayOf(
            0.0f to Color(0xFF1A2240),
            0.22f to Color(0xFF141C34),
            0.48f to Color(0xFF101828),
            0.72f to Color(0xFF0C1220),
            1.0f to Color(0xFF0A0E18),
        )
    } else {
        arrayOf(
            0.0f to Color(0xFFC8DBF0),
            0.18f to Color(0xFFD6E5F5),
            0.42f to Color(0xFFE4EEF8),
            0.68f to Color(0xFFEEF4FA),
            1.0f to Color(0xFFF7FAFC),
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(colorStops = baseGradient)),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val t = elapsedMillis.floatValue
            val w = size.width
            val h = size.height
            val r = maxOf(w, h)

            if (dark) {
                drawDarkNebula(t, w, h, r)
                drawSoftWashDark()
                drawGlowingStars(t, w, h, dense = true)
                drawMeteor(t, w, h)
            } else {
                drawLightFog(t, w, h, r)
                drawSoftWashLight()
            }
        }
        content()
    }
}

/**
 * 无壁纸、关渐变、样式为星空时的默认氛围：深色分层夜空+稀疏发光星；浅色干净柔雾无星。
 * 静态绘制，不跑帧循环。
 */
@Composable
fun StarfieldAmbientBackground(modifier: Modifier = Modifier) {
    val dark = LocalDarkMode.current
    val baseGradient = if (dark) {
        arrayOf(
            0.0f to Color(0xFF1A2030),
            0.35f to Color(0xFF141926),
            0.70f to Color(0xFF10141C),
            1.0f to Color(0xFF0C0E14),
        )
    } else {
        arrayOf(
            0.0f to Color(0xFFDDE7F4),
            0.28f to Color(0xFFE8EFF8),
            0.62f to Color(0xFFF1F5FA),
            1.0f to Color(0xFFF7F9FC),
        )
    }
    Canvas(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(colorStops = baseGradient)),
    ) {
        val w = size.width
        val h = size.height
        if (dark) {
            drawBlob(
                center = Offset(w * 0.45f, h * -0.02f),
                radius = maxOf(w, h) * 0.55f,
                color = Color(0xFF6E8CD2),
                centerAlpha = 0.28f,
            )
            drawBlob(
                center = Offset(w * 1.02f, h * 0.45f),
                radius = maxOf(w, h) * 0.42f,
                color = Color(0xFF5A64AA),
                centerAlpha = 0.16f,
            )
            drawBlob(
                center = Offset(w * -0.02f, h * 0.78f),
                radius = maxOf(w, h) * 0.38f,
                color = Color(0xFF377882),
                centerAlpha = 0.14f,
            )
            drawBlob(
                center = Offset(w * 0.55f, h * 1.05f),
                radius = maxOf(w, h) * 0.40f,
                color = Color(0xFF283C64),
                centerAlpha = 0.12f,
            )
            drawGlowingStars(elapsedMillis = 0f, w = w, h = h, dense = false)
        } else {
            drawBlob(
                center = Offset(w * 0.50f, h * -0.02f),
                radius = maxOf(w, h) * 0.58f,
                color = Color(0xFF91B4EB),
                centerAlpha = 0.40f,
            )
            drawBlob(
                center = Offset(w * 0.0f, h * 0.50f),
                radius = maxOf(w, h) * 0.40f,
                color = Color(0xFFA0CDD2),
                centerAlpha = 0.20f,
            )
            drawBlob(
                center = Offset(w * 1.0f, h * 0.62f),
                radius = maxOf(w, h) * 0.38f,
                color = Color(0xFFBEAFE1),
                centerAlpha = 0.16f,
            )
            drawBlob(
                center = Offset(w * 0.50f, h * 1.05f),
                radius = maxOf(w, h) * 0.36f,
                color = Color(0xFFAAC3E6),
                centerAlpha = 0.10f,
            )
        }
    }
}

private fun DrawScope.drawDarkNebula(t: Float, w: Float, h: Float, r: Float) {
    val p1 = phaseAtElapsed(t, 5_500, loops = 20)
    val p2 = phaseAtElapsed(t, 7_000, loops = 1)
    val p3 = phaseAtElapsed(t, 8_500, loops = 10)
    val p4 = phaseAtElapsed(t, 6_200, loops = 10)
    val p5 = phaseAtElapsed(t, 9_000, loops = 8)
    val p6 = phaseAtElapsed(t, 10_500, loops = 6)

    // Large overlapping blobs covering most of the screen (v4).
    drawBlob(
        center = Offset(
            w * 0.18f + sin(p1) * w * 0.10f,
            h * 0.08f + cos(p1 * 0.9f) * h * 0.08f,
        ),
        radius = r * 0.55f,
        color = Color(0xFF5F7DD2),
        centerAlpha = 0.48f,
    )
    drawBlob(
        center = Offset(
            w * 0.88f + sin(p2 + PI.toFloat() * 0.4f) * w * 0.08f,
            h * 0.12f + cos(p2) * h * 0.07f,
        ),
        radius = r * 0.52f,
        color = Color(0xFF825FBA),
        centerAlpha = 0.36f,
    )
    drawBlob(
        center = Offset(
            w * 0.12f + sin(p3) * w * 0.09f,
            h * 0.48f + cos(p3 * 0.85f) * h * 0.08f,
        ),
        radius = r * 0.50f,
        color = Color(0xFF2D8C96),
        centerAlpha = 0.32f,
    )
    drawBlob(
        center = Offset(
            w * 0.90f + sin(p4 + PI.toFloat()) * w * 0.08f,
            h * 0.55f + cos(p4 * 1.05f) * h * 0.07f,
        ),
        radius = r * 0.52f,
        color = Color(0xFF506EBE),
        centerAlpha = 0.30f,
    )
    drawBlob(
        center = Offset(
            w * 0.50f + sin(p5) * w * 0.06f,
            h * 0.42f + cos(p5) * h * 0.06f,
        ),
        radius = r * 0.46f,
        color = Color(0xFF6478BE),
        centerAlpha = 0.24f,
    )
    drawBlob(
        center = Offset(
            w * 0.50f + sin(p6) * w * 0.05f,
            h * 0.92f + cos(p6 * 0.8f) * h * 0.04f,
        ),
        radius = r * 0.58f,
        color = Color(0xFF37508C),
        centerAlpha = 0.28f,
    )
}

private fun DrawScope.drawLightFog(t: Float, w: Float, h: Float, r: Float) {
    val p1 = phaseAtElapsed(t, 5_500, loops = 20)
    val p2 = phaseAtElapsed(t, 7_000, loops = 1)
    val p3 = phaseAtElapsed(t, 8_500, loops = 10)
    val p4 = phaseAtElapsed(t, 6_200, loops = 10)
    val p5 = phaseAtElapsed(t, 9_000, loops = 8)
    val p6 = phaseAtElapsed(t, 10_500, loops = 6)

    drawBlob(
        center = Offset(
            w * 0.15f + sin(p1) * w * 0.09f,
            h * 0.05f + cos(p1 * 0.9f) * h * 0.07f,
        ),
        radius = r * 0.58f,
        color = Color(0xFF82AFEB),
        centerAlpha = 0.55f,
    )
    drawBlob(
        center = Offset(
            w * 0.90f + sin(p2) * w * 0.08f,
            h * 0.08f + cos(p2) * h * 0.06f,
        ),
        radius = r * 0.54f,
        color = Color(0xFFB9A5E1),
        centerAlpha = 0.42f,
    )
    drawBlob(
        center = Offset(
            w * 0.12f + sin(p3) * w * 0.08f,
            h * 0.45f + cos(p3) * h * 0.07f,
        ),
        radius = r * 0.52f,
        color = Color(0xFF8CD2CD),
        centerAlpha = 0.38f,
    )
    drawBlob(
        center = Offset(
            w * 0.88f + sin(p4) * w * 0.07f,
            h * 0.50f + cos(p4) * h * 0.06f,
        ),
        radius = r * 0.54f,
        color = Color(0xFF9BBEEB),
        centerAlpha = 0.36f,
    )
    drawBlob(
        center = Offset(
            w * 0.50f + sin(p5) * w * 0.05f,
            h * 0.45f + cos(p5) * h * 0.05f,
        ),
        radius = r * 0.48f,
        color = Color(0xFFAFC3E6),
        centerAlpha = 0.30f,
    )
    drawBlob(
        center = Offset(
            w * 0.50f + sin(p6) * w * 0.04f,
            h * 0.95f + cos(p6) * h * 0.03f,
        ),
        radius = r * 0.60f,
        color = Color(0xFFA0C8DC),
        centerAlpha = 0.32f,
    )
}

private fun DrawScope.drawSoftWashDark() {
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(
                Color(0xFF5A78BE).copy(alpha = 0.16f),
                Color.Transparent,
                Color(0xFF465A96).copy(alpha = 0.12f),
            ),
        ),
    )
}

private fun DrawScope.drawSoftWashLight() {
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(
                Color(0xFF96B9E6).copy(alpha = 0.18f),
                Color.Transparent,
                Color(0xFFB4AFE0).copy(alpha = 0.12f),
            ),
        ),
    )
}

private data class StarSpec(
    val nx: Float,
    val ny: Float,
    val core: Float,
    val halo: Float,
    val coreAlpha: Float,
    val haloAlpha: Float,
)

/** Sparse star catalog in normalized coords; [dense] adds more mid/faint stars for mesh. */
private val AMBIENT_STARS = listOf(
    StarSpec(0.12f, 0.16f, 1.4f, 5.0f, 0.95f, 0.22f),
    StarSpec(0.78f, 0.22f, 1.1f, 4.0f, 0.85f, 0.18f),
    StarSpec(0.34f, 0.40f, 1.5f, 6.0f, 0.90f, 0.16f),
    StarSpec(0.62f, 0.58f, 1.0f, 3.5f, 0.75f, 0.14f),
    StarSpec(0.88f, 0.70f, 1.2f, 4.5f, 0.80f, 0.15f),
    StarSpec(0.20f, 0.78f, 1.0f, 3.0f, 0.70f, 0.12f),
    StarSpec(0.48f, 0.88f, 1.0f, 3.5f, 0.65f, 0.10f),
)

private val MESH_STARS = listOf(
    StarSpec(0.26f, 0.07f, 2.0f, 8.0f, 1.00f, 0.32f),
    StarSpec(0.76f, 0.09f, 1.8f, 7.0f, 0.98f, 0.28f),
    StarSpec(0.52f, 0.22f, 2.1f, 9.0f, 1.00f, 0.26f),
    StarSpec(0.88f, 0.30f, 1.7f, 7.0f, 0.95f, 0.24f),
    StarSpec(0.10f, 0.14f, 1.3f, 5.0f, 0.90f, 0.20f),
    StarSpec(0.40f, 0.12f, 1.2f, 5.0f, 0.85f, 0.18f),
    StarSpec(0.64f, 0.16f, 1.1f, 4.5f, 0.80f, 0.16f),
    StarSpec(0.18f, 0.30f, 1.3f, 5.5f, 0.88f, 0.18f),
    StarSpec(0.48f, 0.34f, 1.0f, 4.0f, 0.75f, 0.14f),
    StarSpec(0.72f, 0.38f, 1.2f, 5.0f, 0.85f, 0.17f),
    StarSpec(0.92f, 0.44f, 1.1f, 4.5f, 0.78f, 0.14f),
    StarSpec(0.08f, 0.48f, 0.9f, 3.5f, 0.65f, 0.12f),
    StarSpec(0.28f, 0.52f, 1.0f, 4.0f, 0.70f, 0.13f),
    StarSpec(0.56f, 0.50f, 0.9f, 3.5f, 0.62f, 0.11f),
    StarSpec(0.82f, 0.56f, 1.2f, 5.0f, 0.80f, 0.15f),
    StarSpec(0.14f, 0.66f, 0.9f, 3.5f, 0.58f, 0.10f),
    StarSpec(0.38f, 0.70f, 1.0f, 4.0f, 0.68f, 0.12f),
    StarSpec(0.60f, 0.68f, 1.5f, 6.0f, 0.90f, 0.18f),
    StarSpec(0.78f, 0.76f, 0.9f, 3.5f, 0.55f, 0.10f),
    StarSpec(0.94f, 0.72f, 1.0f, 4.0f, 0.62f, 0.11f),
    StarSpec(0.22f, 0.84f, 0.8f, 3.0f, 0.50f, 0.09f),
    StarSpec(0.50f, 0.86f, 0.9f, 3.5f, 0.55f, 0.10f),
    StarSpec(0.70f, 0.90f, 1.0f, 4.0f, 0.58f, 0.10f),
)

private fun DrawScope.drawGlowingStars(
    elapsedMillis: Float,
    w: Float,
    h: Float,
    dense: Boolean,
) {
    val twinkle = if (elapsedMillis <= 0f) {
        1f
    } else {
        0.88f + 0.12f * sin(elapsedMillis / 900f)
    }
    val densityScale = minOf(w, h) / 390f
    val stars = if (dense) MESH_STARS else AMBIENT_STARS
    for (s in stars) {
        val center = Offset(w * s.nx, h * s.ny)
        drawGlowingStar(
            center = center,
            coreRadius = s.core * densityScale,
            haloRadius = s.halo * densityScale,
            coreAlpha = (s.coreAlpha * twinkle).coerceIn(0f, 1f),
            haloAlpha = (s.haloAlpha * twinkle).coerceIn(0f, 1f),
        )
    }
}

private fun DrawScope.drawGlowingStar(
    center: Offset,
    coreRadius: Float,
    haloRadius: Float,
    coreAlpha: Float,
    haloAlpha: Float,
) {
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                Color(0xFFB4D2FF).copy(alpha = haloAlpha),
                Color.Transparent,
            ),
            center = center,
            radius = haloRadius,
        ),
        radius = haloRadius,
        center = center,
    )
    drawCircle(
        color = Color.White.copy(alpha = coreAlpha),
        radius = coreRadius.coerceAtLeast(0.6f),
        center = center,
    )
}

private fun DrawScope.drawMeteor(elapsedMillis: Float, w: Float, h: Float) {
    val progress = meteorProgressAtElapsed(elapsedMillis) ?: return
    val start = Offset(-0.08f * w, 0.16f * h)
    val end = Offset(0.82f * w, 0.48f * h)
    val head = Offset(
        start.x + (end.x - start.x) * progress,
        start.y + (end.y - start.y) * progress,
    )
    val tail = Offset(
        head.x - (end.x - start.x) * 0.14f,
        head.y - (end.y - start.y) * 0.14f,
    )
    val fade = when {
        progress < 0.12f -> progress / 0.12f
        progress > 0.85f -> (1f - progress) / 0.15f
        else -> 1f
    }.coerceIn(0f, 1f)

    drawLine(
        brush = Brush.linearGradient(
            colors = listOf(
                Color.Transparent,
                Color.White.copy(alpha = 0.15f * fade),
                Color.White.copy(alpha = 0.90f * fade),
                Color(0xFFB4D2FF).copy(alpha = 0.95f * fade),
            ),
            start = tail,
            end = head,
        ),
        start = tail,
        end = head,
        strokeWidth = 2.2f * (minOf(w, h) / 390f),
        cap = StrokeCap.Round,
    )
    drawGlowingStar(
        center = head,
        coreRadius = 2.4f * (minOf(w, h) / 390f),
        haloRadius = 10f * (minOf(w, h) / 390f),
        coreAlpha = 0.98f * fade,
        haloAlpha = 0.45f * fade,
    )
}

/**
 * Progress of the rare meteor streak in `[0, 1]`, or null when it should not be drawn.
 * Visible for [METEOR_VISIBLE_MS] near the end of each [METEOR_PERIOD_MS] cycle.
 */
internal fun meteorProgressAtElapsed(
    elapsedMillis: Float,
    periodMs: Float = METEOR_PERIOD_MS,
    visibleMs: Float = METEOR_VISIBLE_MS,
): Float? {
    if (periodMs <= 0f || visibleMs <= 0f) return null
    val t = elapsedMillis.mod(periodMs)
    val start = periodMs - visibleMs
    if (t < start) return null
    return ((t - start) / visibleMs).coerceIn(0f, 1f)
}

/** Soft radial blob: coloured centre fading to transparent. */
private fun DrawScope.drawBlob(
    center: Offset,
    radius: Float,
    color: Color,
    centerAlpha: Float,
) {
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = centerAlpha), Color.Transparent),
            center = center,
            radius = radius,
        ),
        radius = radius,
        center = center,
    )
}
