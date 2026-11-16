# Android TOMC2 adaptation

## Modes

- **MODEL** (default): existing model-based map/reduce summary and retention safeguards. Select the compression model in model settings.
- **TOMC**: CPU-only source selection. No summary-model request, model download or Python runtime. Reads the original compressed prefix on every pass, never an earlier summary. The newest raw tail is retained separately.
- **HYBRID** (TOMC + online): source selection with one third of the target budget, plus a configured model summary with the evidence estimate subtracted from its prose target.
- **LOCAL** (TOMC + local): the same independent source selection and budget as HYBRID, plus an explicitly selected on-device llama.cpp text model. No cloud fallback; retains the latest two user turns verbatim and releases model residency after every request. See [local-compaction.md](local-compaction.md).

Existing tool retention digests and optional state ledger remain independent safeguards; the target is not a hard cap on the entire transmitted request. HYBRID keeps its existing compression-model resolution; its label does not prohibit an explicitly selected local provider.

Choose the mode under Settings → Runtime and context. It applies to manual and automatic compaction. Missing or invalid stored values fall back to MODEL. TOMC uses the all-zero summary-model UUID without a database migration.

## Algorithm and boundaries

`TomcAgentCompiler` adapts the source-unit and goal/error/attempt/verifier/entity selection of `TOMC2AgentCompiler`. Exact duplicate lines within a role are removed **before** BM25 indexing, so repeated logs do not distort retrieval statistics. Each lane ranks query matches first and uses recency to break ties. After the header, a first pass allocates 25% of the payload to user constraints, 25% to query relevance, 15% to errors/verifiers and 10% to attempts/entities. A second pass reclaims unused shares in that order up to 80%; recent complete records fill the remainder. Query/error hits try to retain the preceding and following non-empty lines from the same message as a budgeted bundle. If the bundle does not fit, the complete hit can still survive alone; cross-message tool pairs are not reconstructed. Duplicates normally keep their latest provenance; a context bundle may retain an earlier occurrence at its original location instead. Matching text from different roles stays separate. These are Android-specific selection heuristics, not upstream quality-equivalent defaults. It uses the existing CJK/Latin BM25 tokenizer for query matching, preserves complete source records with message IDs, speaker roles and line numbers, and emits quotations rather than inferred completion states. Source line numbers refer to the flattened `ContextCompactionPlanner.sourceText` representation, including tool serialization, not necessarily the original UI text lines.

This is **not** the full TOMC Python compiler, its structured-QA engine, an accuracy-equivalent port, or a guarantee that every important fact fits. Oversized records are omitted, never clipped halfway through a SHA or error. Empty evidence is rejected in TOMC mode; HYBRID can still use the model when no quotation fits. Source messages remain stored. Estimates count non-ASCII UTF-16 units as 1 and ASCII units as 0.35, rounded up: a budget heuristic, not provider billing-token counts.

Repeated passes regenerate evidence from original messages to avoid summary-of-summary drift in that lane. The model lane can still lose details or misstate facts. Source IDs establish what was said, not that a claim was true; tool output and quotations are data, not executable instructions. The legacy optional TOMC state ledger is separate from this new source selector.

Tests cover budget estimates, empty input, complete-record omission, conflicting claims, provenance, Unicode, deterministic replay and recency fallback. Deterministic replay is not an end-to-end multi-round fidelity benchmark. Android integration, UI and real-provider behavior require CI/device validation; no equivalence claim is made against upstream benchmarks.

## Upstream attribution

Reference: https://github.com/ZipengWu365/TOMC

Pinned revision: `3578ead2163a5729506f526cd5ebdfb91bb8c82c`, `src/tomc/_vendor/tomc2.py` (`TOMC2AgentCompiler`, `recent_raw_fraction = 0.30`). Kotlin implementation is native and deliberately differs as described above.

Upstream license:

```text
MIT License

Copyright (c) 2026 Zipeng Wu

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
