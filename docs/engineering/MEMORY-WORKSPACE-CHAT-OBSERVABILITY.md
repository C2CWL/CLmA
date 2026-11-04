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
Public entry conflicts to watch: AssistantMemoryPage/AssistantDetailVM and (if changed) ChatList/ChatMessageJumper; ChatService change limited to cold index renderer call. No phone operations, private-data exports, Gradle/Android builds, pushes or PRs.

Implementation and verification results are appended with each delivery. An audited existing capability is not a new feature or a device-tested result.
