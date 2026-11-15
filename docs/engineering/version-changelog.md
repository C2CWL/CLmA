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
