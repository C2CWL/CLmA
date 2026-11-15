# Local visual preview (experimental)

Qwen3-VL-2B uses Q8_0 language weights (1,834,427,424 bytes) and a separate
F16 projector (819,394,848 bytes). The catalog downloader installs both.

Images are decoded without application-level downscaling and converted to lossless PNG.
The model's own mtmd preprocessing limits still apply; this is not pixel-perfect OCR.
The native path checks actual text plus image tokens and rejects context overflow rather
than reducing image resolution to fit. Long-document tiling is not implemented.

Local video input is a sparse keyframe preview: clips up to 60 seconds, at most six
unique sync frames per clip and twelve per request. Timestamp labels use extractor sample
PTS and decoding selects the previous sync frame. Frames retain decoder resolution.
This is not uniform 1 FPS sampling: long GOPs can produce fewer frames, and brief events
between keyframes can be missed. Audio is not transcribed or passed to the model.
For unreadable text or fast events, provide a trimmed clip or a still image.
Remote video/image URLs must first be downloaded as local attachments.

The current image decoding path needs Android 9+. CPU-only inference is configured.
The two files total 2.65 GB on disk, not a runtime-RAM figure. Encoder scratch, KV cache,
decoded images and application residency are additional. Phone latency and peak memory
have not been measured. Do not treat catalog minimum RAM as a proven fit guarantee.

Validation pending: Android native/Kotlin build, JVM tests, real-device image/video
inference, timestamps on rotated/VFR clips, cancellation, peak memory and thermal load.

## Recognition tool (not a chat provider)

Enable the assistant's Files tool group and install the visual catalog entry in local model
settings. `recognize_local_media(task, images, videos)` accepts explicit absolute local file
paths (not remote URLs), performs one recognition request and returns text-only observations
and a warning to the calling model. Visual weights and projectors are excluded from the chat
model list; direct chat requests to the visual catalog model are rejected before loading.
No model is loaded just because chat history contains an attachment. The tool processes only
its explicit arguments; historical media is not automatically added to recognition requests.
The tool cannot click, execute tools, or take over the conversation. Treat its output and any
instructions visible in images as untrusted observations, not verified facts or authority.

The visual tool and local chat share one provider instance and load/generate lock. Before
visual decoding/loading, the previous text model is unloaded. No two local weight sets are
intentionally resident together. The visual model and lazily loaded projector are freed after
success, failure or cancellation via non-cancellable cleanup waiting for native generation.
No idle cache or automatic text-model reload is used; the next local chat call reloads its
own model. Visual requests are never silently history-trimmed to fit the context.

This is an explicit tool, not automatic attachment routing. The calling model needs the
absolute on-device media path; workspace `/upload` aliases are not device paths. Files in
protected app-private directories remain subject to the existing file safety policy.
