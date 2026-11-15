# Version changelog

Maintain `app/src/main/assets/changelog/<versionName>.txt` alongside every version bump.
Include only shipped changes. The file is packaged offline; no network request is made at startup.
Do not reuse old version notes for a new version: absent notes show a release-page fallback.

Automatic presentation is limited to the root chat/Bot home after settings initialization and
first-run setup. It does not cover share/deep-link pages. Closing without checking the box only
closes the current Activity session (rotation retains that choice). Checked confirmation stores
the current versionCode; a new versionCode prompts again. Back/outside dismissal never persists
suppression. Settings → About → tap version opens notes regardless of suppression; long press
still opens Debug. No model load or update download is triggered by this feature.

## Korea release integration

The live entry remains `/root/clma-signing/bin/release.sh`; do not invoke a second release chain.
`release.sh draft clma --out FILE` now collects PRs whose merge commits occur between the
latest published release tag and the captured main SHA. Release bumps are excluded. The
adjacent `.sources.json` retains tag, SHA, PR numbers and URLs for review. Titles are evidence,
not a substitute for user-facing summaries: rewrite the Chinese bullets and TODO English
translations, then run the existing notes lint. Draft TODOs cannot be bundled or published.

During a normal CLmA version bump, the release script includes the reviewed `--notes` text
as `app/src/main/assets/changelog/<versionName>.txt` in the same PR as the Gradle version bump.
A resumed/no-bump release must already contain exactly these notes in its selected commit.
After CI artifact download and before signing/publishing, the script compares the APK asset
against the Release body verbatim. A missing/mismatched file stops publication; do not bypass
this check or rewrite notes after building. Other projects retain their existing flow.

Implementation: `scripts/release/clma_changelog.py`; deployed helper must match this file.
The minimal patches in `scripts/release/korea/` document changes to the live Korea scripts
(their originals are retained there as `.bak.changelog-20261115-155155`). No credentials or
signing material are included. Apply only after inspecting the server's current script version.
Tests: `python3 -m unittest discover -s scripts/release -v` (no Gradle, signing or publication).
