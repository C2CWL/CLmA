# CLmA plugin source and package adapters

CLmA plugin discovery is designed for more than one ecosystem. DSH Go is only one source adapter.

## Source adapters

A source adapter reads an external registry and normalizes results into `PluginMarketEntry`.

Current sources:

- DSH Go public API V2: `https://dsh-go.pages.dev/api/v2/packages`, `/api/v2/search`.
- GitHub topic discovery: repositories tagged `clma-plugin`.

Adding a source means implementing `PluginMarketSourceAdapter`; the settings UI and package adapter registry do not need ecosystem-specific branches.

## Package adapters

A package adapter answers one question: what can CLmA safely do with this normalized entry?

| Package kind | Install in CLmA | Next step |
| --- | --- | --- |
| native APK | yes | install through Settings → Plugins |
| DSH Go package | no | run through external `dsh` CLI |
| GitHub repository | no | review source and adapt it |
| unknown | no | build a native plugin manually |

The conversion layer keeps package-format decisions out of the UI and out of the source adapters.

## Conversion boundary

Automatic JS/TS-to-APK conversion is not a simple repack step. A real converter needs:

- a JS/TS runtime adapter compatible with the plugin ecosystem;
- a permission and tool bridge that maps host capabilities into the guest runtime;
- a reproducible APK build pipeline, preferably CI-driven;
- install-time manifest validation and a dedicated security review.

Until that exists, discovery entries are labeled honestly and are never passed to the native APK installer.

## Trust model

- `verified` is source-provided metadata, not a CLmA trust decision.
- Publisher, repository, source label, and adapter decision are shown to the user.
- Market discovery never downloads code automatically.
- Native APK installation remains a separate explicit user action.
