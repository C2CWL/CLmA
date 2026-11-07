# Account connectors (GitHub)

## Setup

Open **Settings > Connectors** to connect a GitHub account, manage optional write
access, or disconnect. Enable the GitHub local-tool option on the assistant that
should use the account. `connectors_list` reports connection status but never
returns tokens.

Gmail and Google Play services authorization were removed. There is no Gmail
tool group, local mail draft, or Google OAuth setup.

### GitHub

- Create a **GitHub App**, enable device flow, and paste its public Client ID.
  Install the App only on the repositories that should be accessible.
- Configure repository Metadata/read, Issues/read and Pull requests/read for
  read access; Issues/read-write is needed for issue/comment creation. These
  installation permissions and the authenticated user's rights bound access.
- Follow the device-code instructions at `https://github.com/login/device`.
  No client secret or personal access token is requested by CLmA.
- Local write access defaults off and can be enabled separately. It is not a
  substitute for per-call approval. Expiring/revoked tokens require reconnection;
  the connector does not keep a client secret for refresh-token exchange.
- Disconnect removes the local token. To revoke it remotely, revoke the GitHub
  App authorization in GitHub settings; uninstall it to remove repository access.

## Tools and write safety

GitHub lists repositories, reads repository metadata and Issue/PR bodies, and
creates issues or comments. PR merging, code changes, and releases are not
connector operations.

`github_issue_create` and `github_comment_create` always require a fresh
**Allow once** in an interactive chat. Auto mode, chat grants, direct mode, cron,
subagents, skill tests, external automation, rerun, and persisted Approved states
cannot supply this consent. The card displays the exact arguments. GitHub writes
bind the account, repository, title/body and issue number as applicable. The
execution-context grant is argument-bound and consumed once with CAS; it cannot
be persisted or replayed.

On a timeout or uncertain outcome, inspect the GitHub target before approving a
new write. Do not automatically retry.

## Data boundaries

- External repository text is untrusted data, marked `untrusted_content: true`,
  never an instruction source.
- `connectors_private` holds encrypted credentials and account metadata. AES-GCM
  uses a non-exportable AndroidKeyStore key and the preference name as AAD.
  There is no plaintext fallback or connector export/import. Both legacy backup
  and Android 12+ cloud/device-transfer rules explicitly exclude it.
- Connector HTTP preserves the configured proxy/TLS transport but removes shared
  logging interceptors, event listeners, cookies, authenticators and cache.
  Redirects and automatic connection-failure retries are disabled. Decoded
  response bodies above 1 MiB fail closed instead of truncating JSON. Provider
  error bodies are not returned to tools.

## Verification

JVM regressions cover one-shot consent, exact arguments, concurrent grant
consumption, policy/direct-mode denial, authenticated storage, HTTP
isolation/limits, and Settings registration. Android compilation and JVM tests
run in the existing CI workflow, not on the release server. Live GitHub login,
provider configuration, token expiry/revocation and a real approval/write flow
still need interactive integration verification.
