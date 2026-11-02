# Account connectors (Gmail and GitHub)

## Setup

Open **Settings > Connectors** to connect an account, review local mail drafts,
manage optional write access, or disconnect. Enable the Gmail/GitHub local-tool
option on the assistant that should use the account. `connectors_list` reports
connection status but never returns tokens.

### Gmail

- Requires Google Play services. Configure a Google Cloud project, enable Gmail
  API, and create an Android OAuth client for the actual application ID and the
  signing certificate SHA-1. Different build/signing identities need their own
  registered clients. There is no loopback redirect or embedded client secret.
- Configure the OAuth consent screen and add test accounts while in testing.
  Gmail restricted scopes can require Google verification/security review before
  a production rollout; adding this code does not complete that approval.
- Initial login requests `gmail.readonly`. Sending is enabled separately with
  `gmail.send`; remote drafts request `gmail.compose`. The compose scope also
  permits sending at Google, so CLmA still independently gates send and upload.
- Access tokens are obtained through Play services when needed, not exposed to
  tools or stored as plaintext. A new interactive authorization is required if
  Play services cannot satisfy the previously approved scope silently.
- Disconnect removes this device's account metadata and local drafts. The UI
  offers Google access revocation. Mail already uploaded/sent remains in Gmail.

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

Gmail searches messages, reads MIME-decoded plain text (no attachments), creates
local drafts, gets full draft previews, uploads drafts, and sends. Local drafts
can be reviewed, edited and deleted from Settings. The first version does not
provide attachment handling or remote draft editing/deletion.

GitHub lists repositories, reads repository metadata and Issue/PR bodies, and
creates issues or comments. PR merging, code changes, and releases are not
connector operations.

`gmail_send`, `gmail_draft_upload`, `github_issue_create`, and
`github_comment_create` always require a fresh **Allow once** in an interactive
chat. Auto mode, chat grants, direct mode, cron, subagents, skill tests, external
automation, rerun, and persisted Approved states cannot supply this consent.
The card displays the exact arguments. Gmail additionally binds the draft to
its complete account/to/cc/subject/body preview; changed drafts must be reviewed
again. GitHub writes bind the account, repository, title/body and issue number
as applicable. The execution-context grant is argument-bound and consumed once
with CAS; it cannot be persisted or replayed.

Gmail records an attempt before POST. On a timeout or uncertain outcome, inspect
Gmail's Sent/drafts or the GitHub target before approving a new write. Do not
automatically retry. Sending a previously uploaded draft sends the reviewed local
snapshot; the remote draft may remain. Local draft cleanup never deletes remote
mail.

## Data boundaries

- External mail and repository text is untrusted data, marked
  `untrusted_content: true`, never an instruction source.
- `connectors_private` holds encrypted credentials/account metadata and drafts.
  AES-GCM uses a non-exportable AndroidKeyStore key and the preference name as
  AAD. There is no plaintext fallback or connector export/import. Both legacy
  backup and Android 12+ cloud/device-transfer rules explicitly exclude it.
- Connector HTTP preserves the configured proxy/TLS transport but removes
  shared logging interceptors, event listeners, cookies, authenticators and
  cache. Redirects and automatic connection-failure retries are disabled.
  Decoded response bodies above 1 MiB fail closed instead of truncating JSON.
  Provider error bodies and SDK exception messages are not returned to tools.

## Verification

JVM regressions cover one-shot consent, exact arguments, concurrent grant
consumption, policy/direct-mode denial, authenticated storage, MIME decoding,
header injection, HTTP isolation/limits, and Settings registration. Android
compilation and JVM tests run in the existing CI workflow, not on the release
server. Live Google/GitHub login, provider configuration, token expiry/revocation
and a real approval/send flow still need interactive integration verification.
