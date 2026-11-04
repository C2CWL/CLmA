# Memory, workspace and long-chat observability

Base: `0a1ff0811f4a6d6c4f3f0aa7f14e2d0b17c3cafa` (main, 2026-11-04).
Isolated worktree: `/workspace/clma-memory-observability`.
Branch: `feat/memory-workspace-chat-observability`.

## Audit before changes

| Priority | Existing capability (source) | Verified gap / intended change |
|---|---|---|
| A | Hot memory: Room MemoryDAO → MemoryRepository → GenerationPrompts; global/assistant scope chosen in ChatService. Cold memory: assistant-owned Markdown, INDEX injection, sorted tool listing/search, legacy copy-once migration. | Hot queries have no ORDER BY; prompt preserves arbitrary input order and shares the global pretty JSON config. Cold prompt uses multiline interpolation with trimIndent (content can alter indentation). Make explicit hot/cold serialization stable without categorizing/reinterpreting user prose, or changing scope/migration. |
| B | memory_tool create/edit/delete; manual add/edit/delete in AssistantMemoryPage; cold tools; existing tool transcripts. | No shared reviewable history for manual operations, exact no-op decisions, or cold store writes. Add bounded local metadata history at actual mutation paths; reuse manual editor, do not introduce automatic consolidation. No semantic merge inference: edits are edits unless the caller explicitly explains them. |
| C | Workspace terminal already streams; background processes expose captured output via status. workspace_edit_file already stores actual full-file DiffMetadata; EditFileToolUI renders it. | Foreground workspace_shell waits until completion; workspace_write_file has no actual diff. Add only missing surfaces where feasible; do not duplicate terminal/diff implementation. |
| D | ChatMessageJumper already offers top/previous/next/bottom; ChatList has in-conversation search; SearchPage/SearchVM has cross-conversation/all-assistant FTS and jump to result; Export.kt supports selected-message exports. Doctor reports cache bytes. | No conversation outline/minimap; no categorized on-device storage usage analysis. Existing search/export/jump must be reused, not rebuilt. |

## Boundaries

No Kelivo source copied. No context-inspector/redaction or backup/restore module changes.
Public entry conflicts to watch: AssistantMemoryPage/AssistantDetailVM and (if changed) ChatList/ChatMessageJumper; ChatService changes limited to cold index rendering and passing the shared history/scope into the existing cold store. No phone operations, private-data exports, Gradle/Android builds, pushes or PRs.

Implementation and verification results are appended with each delivery. An audited existing capability is not a new feature or a device-tested result.

## Local delivery (2026-11-04)

| Priority | Delivered source changes | Remaining / not claimed |
|---|---|---|
| A | Explicit Room ID ordering for both query paths; isolated JSON renderer with ID ordering; deterministic cold-index wrapper. Tests added for query order, escaped prose, blank index and truncation. | JVM tests have not run. The renderer does not change scope selection or legacy migration. |
| B | Shared bounded 200-entry metadata journal; hot create/edit/delete and exact-content no-op edit; cold create/edit/delete/unchanged write and absent-file delete; manual/tool attribution; history dialog in assistant memory settings. Persistence failure remains visible without retrying the mutation. | No semantic consolidation or model-reasoning logs; no bulk assistant-deletion audit; no recovery of historical memory prose. Cold operations do not retain filenames, so individual cold records cannot be identified from history. A failed cold delete is currently recorded as no_op. |
| C | workspace_write_file reads an optional pre-write baseline, attaches unified DiffMetadata after a successful write, and reuses EditFileToolUI summary/preview. Missing file uses an empty baseline; unavailable/oversized baseline falls back to content preview. Cancellation propagates. | No foreground shell live-output plumbing. Current Tool.execute returns only final parts, and WorkspaceShellRunner.execute/readResult collects until process completion. End-to-end streaming needs a separate event API through execution and chat UI, not a cosmetic callback. Concurrent external edits between baseline read and write are not serialized; diff is the sampled pre-write baseline, not a transactional guarantee. |
| D | Conversation outline in existing jump menu; entries use display-group role plus first 120 text characters, including existing automatic-compaction grouping; clicking uses the same LazyListState/index. | No whole-turn grouping, categorized storage-usage UI or device performance validation. Existing global search/export capabilities were audited, not reimplemented. |

### Verification actually performed

- `git diff --check`: passed (whitespace only, not a Kotlin type/compiler check).
- Tree-sitter Kotlin parsing: 16 changed/new Kotlin files, no parser errors.
- XML parsing: both new English/Chinese resource files parsed successfully.
- Scope review: history and cold store both use `ColdMemoryRules.normalizeDir`; invalid/null normalized directories cannot match the cold history filter, and the valid empty-root directory is preserved.
- ktlint unavailable: existing `/workspace/ktlint-1.8.0` fails Java loading, and ZIP inspection reports `BadZipFile`. No successful ktlint result is claimed.
- No Gradle/Android compilation, JVM test execution, phone interaction, remote push or PR. CI has not run for these local changes.

### Integration / review notes

Public-entry conflict points: AssistantMemoryPage, AssistantDetailVM, ChatList, ChatMessageJumper, ChatService and RepositoryModule. Review those hunks when integrating sibling worktrees. No context-inspector/redaction or backup/restore-specific files changed. The local metadata journal is app-private, globally bounded (not 200 entries per assistant), and is not wired into backup/restore by this patch.

This is a partial delivery of the audited A–D group. The unimplemented shell streaming and storage analysis are explicitly deferred rather than reported complete. Local commits are the rollback boundary; nothing has been published.

## Delivery 2 (2026-11-05): remaining audit gaps, batch 1

| Item | Delivered source change | Not claimed |
|---|---|---|
| Cold memory identification | `MemoryOperation.name` carries the Markdown file name for cold records; `AssistantColdMemoryStore` records it on create/edit/no_op/delete; assistant memory history dialog shows it. Journals from before the field decode with `name == null`. | No prose, prompts or reasons are retained. Hot records still identify by row id only. |
| Storage usage analysis | Doctor maintenance adds one read-only `maint.storage_breakdown` row (databases, images, videos, uploads, workspace, memory history, cache, total), reusing the existing `directorySize` walk. | Only app-private directories; shared storage needs permissions and is not measured. No per-assistant attribution. |

Verification: `git diff --check`, tree-sitter Kotlin parse of the changed files, XML parse plus duplicate-name check of both `strings.xml`. No local Gradle build or test run — CI is the compiler. Not device-tested.

Still open from the audit: semantic memory consolidation, foreground `workspace_shell` live output, whole-turn conversation minimap, structured JSON export.
