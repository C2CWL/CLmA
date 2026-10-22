package me.rerere.ai.ui

import kotlinx.serialization.Serializable

/**
 * One finished generated video.
 *
 * Mirrors [ImageGenerationItem] deliberately: [data] is the **base64-encoded** video bytes and the
 * caller persists them to disk. Video vendors (DashScope Wan, Volcengine Seedance, Sora, Veo) all
 * hand back a *short-lived* download URL rather than inline bytes, so the provider downloads the
 * clip immediately — a URL parked in a result envelope is useless a few hours later — and this is
 * the shape it hands on. `mimeType` is whatever the download reported (usually `video/mp4`).
 *
 * `partial` exists for symmetry with images and for providers that can report progress; the async
 * task APIs implemented here only ever emit the final file, so it stays `false`.
 */
@Serializable
data class VideoGenerationItem(
    val data: String,
    val mimeType: String = "video/mp4",
    val partial: Boolean = false,
)
