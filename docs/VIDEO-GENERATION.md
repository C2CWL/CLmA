# Video generation

How a `generate_video` call becomes a clip, what each of the five wired vendors honours, and what
is deliberately still missing.

## The layers

```
LLM tool call / video page
  ↓
app tool layer        picks the model, downloads + persists the clip, builds the {ok|error} envelope
  ↓  Provider.generateVideo(providerSetting, params)
provider layer (ai)   OpenAIProvider — the base URL's *host* picks the vendor family
  ↓  native async task API
vendor                submit → poll → (expiring) download URL
```

- **The vendor layer only speaks protocol.** It builds the submit body, polls the task and hands
  back the download URL. It does not persist, upload or render — the caller downloads the clip
  immediately (every vendor's URL expires) and the app tool layer writes it to
  `FilesManager.getVideosDir()` plus a `GenMediaRepository` row, so the result shows up in chat
  and in the in-app gallery.
- **Everything decidable without a socket is a pure function** in a `*VideoRequest.kt` file
  (host detection, body building, response parsing) so a bare-JVM test can pin it; only the
  provider glue touches OkHttp. Same rule the image paths follow.
- **Credentials are not a second config surface.** The family is inferred from the
  OpenAI-compatible provider the user already configured, and the key goes through the same key
  roulette as chat. There is no separate "video provider" settings screen.
- **`VideoGenerationParams` is the whole public request**: `model`, `prompt`, `aspectRatio`,
  `durationSeconds`, `numOfVideos`, `sourceImages` (first frame only) and the
  `customHeaders` / `customBody` escape hatches. A count > 1 is N **sequential** jobs — no wired
  vendor takes an `n` for video — each of which can take minutes.

## The five wired families

| Family | Host | Submit | Poll | Success | Terminal words |
|---|---|---|---|---|---|
| **DashScope** Wan (万相) | `*.aliyuncs.com` (dashscope) | `POST /api/v1/services/aigc/video-generation/video-synthesis` (+`X-DashScope-Async: enable`), or `…/image2video/video-synthesis` with a first frame | `GET /api/v1/tasks/{id}` | `SUCCEEDED` → `output.video_url` | `FAILED`, `CANCELED`, `UNKNOWN` |
| **Volcengine** Ark / BytePlus (Seedance) | `ark*.volces.com`, `ark*.bytepluses.com` | `POST /contents/generations/tasks` | `GET /contents/generations/tasks/{id}` | `succeeded` → `content.video_url` | `failed`, `expired`, `canceled`, `cancelled` |
| **MiniMax** Hailuo (海螺) — two dialects | `api.minimaxi.com`, `*.minimax.chat`, `*.minimax.cn`, `*.minimax.io` | classic `POST /v1/video_generation`; H3 `POST /v2/video_generation` (`content[]`) | classic `GET /v1/query/video_generation?task_id=` then `GET /v1/files/retrieve?file_id=`; H3 `GET /v2/query/video_generation/{id}` | classic `Success` → `file_id` → `download_url`; H3 `succeeded` → `task.content.url` | `Fail` / `failed`, `cancelled` |
| **Zhipu** CogVideoX / 清影 | `open.bigmodel.cn`, `api.z.ai` | `POST /api/paas/v4/videos/generations` | `GET /api/paas/v4/async-result/{id}` | `SUCCESS` → `video_result[0].url` | `FAIL` |
| **SiliconFlow** 硅基流动 | `api.siliconflow.cn`, `api.siliconflow.com` | `POST /v1/video/submit` | **`POST`** `/v1/video/status` with `{requestId}` | `Succeed` → `results.videos[0].url` | `Failed` |

### Per-vendor quirks worth remembering

- **Duration is not one number.** DashScope is model-tiered (wan2.1/2.2 are fixed at 5 s and reject
  the parameter, so it is omitted; wan2.5 accepts only 5/10; anything else is clamped 2–15);
  Seedance clamps 2–12; MiniMax snaps to 6/10 (classic) or clamps 4–15 (H3); Zhipu snaps to 5/10.
- **`resolution` is a quality tier, not a shape** for MiniMax (768P/1080P, H3 768P/2K), so it is
  never sent — shape is expressed with `ratio` / `size` / `image_size` instead.
- **Shape vocabularies differ**: DashScope `size` is `WIDTH*HEIGHT` (`1280*720`),
  Seedance `ratio` + `resolution`, MiniMax H3 `ratio` (required for text-to-video, `adaptive`
  for image-to-video), Zhipu `size`, SiliconFlow `image_size` (a three-value enum).
- **MiniMax reports a rejected task as HTTP 200** with `base_resp.status_code != 0`, so that is
  checked explicitly — otherwise a dead task would look queued.
- **SiliconFlow polls with POST**, not GET, unlike every other vendor here.
- **First-frame inlining differs**: DashScope / Ark / MiniMax take the `data:` URI as-is, while
  Zhipu and SiliconFlow document their image field as "URL **or** base64", so the prefix is
  stripped (`inlineImagePayload`).
- **All result URLs expire** (Ark 24 h, SiliconFlow ~1 h), hence the immediate download.
- **Status words go through one vocabulary** (`VideoTaskStatus.kt`): the terminal half
  (failed / cancelled / expired) is vendor-agnostic, so a word nobody has seen before keeps
  polling instead of silently ending a job — and a poll timeout reports the last status it saw.

## What the retired `videogen` module sketched

An earlier, unreferenced module (`me.rerere.videogen`, inherited from the upstream fork) held a
much wider capability model. It was deleted, but the design thinking was kept:

**Kept (now reflected in this codebase):** the layer boundary above ("protocol only; the caller
downloads and persists"), the explicit terminal-state vocabulary for task status (`VideoTaskStatus.kt`), the
"extra parameters escape hatch" idea (our `customBody` / `customHeaders`), and this capability
matrix itself.

**Still deliberately not implemented** — the roadmap, if anyone wants it:

| Idea | What it would take |
|---|---|
| Last frame, reference image / video / audio | `VideoGenerationParams` has no such inputs; Wan 3.0 (`input.media[]`) and Seedance (`role: last_frame` etc.) accept them |
| Document / web-page input | Wan 3.0 only |
| `callbackUrl` | Vendors that support it would let the app drop polling entirely |
| `seed`, `watermark`, `generateAudio`, `promptEnhancement` | Per-vendor knobs; a param the vendor ignores must be refused rather than silently dropped |
| Usage / cost reporting | Vendors return durations and sometimes tokens; nothing consumes them today |
| Richer task model (timestamps, raw metadata, terminal enum) | Only worth it once something above the provider reads it |

## Verification

The pure parts are pinned by JVM unit tests (`app/src/test/…/providers/openai/*VideoRequestTest.kt`)
and run in CI: `:app:assembleClma`, `:app:testClmaUnitTest`, `:ai:testDebugUnitTest`. Real clips
still require a real key and a real device — the request shapes are verified against the vendors'
published contracts, not against live traffic.

## 中文说明

生视频要走「工具层 → provider 层 → 厂商原生异步任务 API」三段。**厂商层只谈协议**（建任务、轮询、
给下载 URL），下载与落盘由上层做（所有厂商的 URL 都会过期）；**能不进网络就决定的逻辑一律做成纯函数**
放 `*VideoRequest.kt`，好让纯 JVM 测试盯住。**凭据不新开一套**：用用户已配好的 OpenAI 兼容 provider 的
baseUrl 认厂商（DashScope / 火山 Ark / MiniMax / 智谱 / 硅基流动 五家），走同一套 key 轮换。

各家的差异集中在：时长语义（有的固定 5 秒、有的只能 5/10、有的 2–15）、比例字段的词汇
（`size` / `ratio` / `image_size`，且 MiniMax 的 `resolution` 是画质档位不是比例）、以及
「首帧怎么塞」（data URI 还是裸 base64）。

早先那个未接线的 `videogen` 模块已删除，但它想做的更宽能力面（尾帧、参考图/视频/音频、文档/网页输入、
callback、seed / watermark / 有声 / 提示词增强、用量计量）被记在上面的路线图里，眼下**刻意不做**。
