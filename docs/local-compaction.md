# Local (on-device) context compaction

`CompactionEngine.LOCAL` runs the existing compaction pipeline against an installed
llama.cpp GGUF instead of a cloud model. It is selected under Settings → Runtime and
context → Compaction engine → Local, and it applies to both manual and automatic
compaction, exactly like the other engines.

## Why a separate engine instead of "pick a local model"

Model mode resolves its compression model by falling through to the current chat model and
then to the first model of any enabled provider. With a local GGUF merely selected as the
compression model, a disabled or missing local provider would silently send the summary
prompt to a billed cloud provider. Local mode therefore resolves strictly:

1. `compressModelId` must point at a model belonging to an **enabled** `LlamaCppLocal` provider.
2. The model must be a plain text GGUF. Catalog entries that carry a vision projector are
   rejected, because they are recognition tools that unload the resident text model.
3. No current-chat fallback, no "first enabled provider" fallback.

Anything else resolves to null and raises an actionable error naming the setting.

## Failure policy

- Local compaction never switches to cloud, and never silently continues with uncompacted
  context. `LocalCompactionException` is excluded from the model-routing block in
  `handleMessageComplete`, and a failed pre-send compaction is surfaced instead of sending
  the raw oversized request.
- The failure is still attached to the conversation as the usual compaction failure card, so
  the user sees what happened and the original messages stay untouched.

## Retained raw tail

Automatic compaction normally keeps a tail sized by `autoCompactionKeepRecentToolCalls`
(retained tool calls) plus `maxTailTokens`. In Local mode the boundary is additionally
clamped to `ContextCompactionPlanner.recentUserTurnsStartIndex`, so the two most recent user
turns and everything after them stay verbatim, regardless of tool-call settings. Manual
compaction applies the same clamp and refuses (`check`) when no older messages remain.

Consequence: if the two retained turns alone exceed the trigger, compaction cannot shrink
below it. That is reported as a no-op rather than as a reason to compact further, and the
second "compact the full active context" pass is skipped for Local mode.

## Request shaping for small local models

- The map budget is capped at 2,048 tokens per request so a 4k-class window is not asked to
  hold a huge chunk.
- Requests are issued strictly one at a time (`chunked(1)`), not `MAX_PARALLEL_COMPACTION_REQUESTS`:
  llama.cpp serializes on one shared runtime anyway, and the app's vision recognition uses the
  same `loadMutex`, so concurrency would only produce interleaved load/unload pressure.
- Per-request output is capped at 1,024 tokens, and `enable_thinking = false` is passed
  through `TextGenerationParams.enableThinking` → `ChatRequestMapper` so a hybrid-reasoning
  GGUF does not spend the whole output budget inside a thinking block.
- `TextGenerationParams.enableThinking` is null by default, so ordinary local chat keeps the
  template's own default. Only Local compaction sets it.

## Not implemented

- No model is downloaded, installed or selected automatically. No community fine-tune is
  registered as a default: candidates must be measured on this pipeline first.
- No end-to-end quality benchmark of a specific GGUF ships with this change; CI and device
  runs are required before claiming parity with a cloud summary model.
