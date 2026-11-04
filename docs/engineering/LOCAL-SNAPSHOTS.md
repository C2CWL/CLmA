# Local snapshots and restore safety

## Policy

- Automatic snapshots are opt-in: 24-hour interval, three archives, 256 MiB combined automatic archive budget.
- The backup page exposes intervals of 12/24/72/168 hours, retention of 1/3/5/10 archives and 128/256/512/1024 MiB budgets.
- A process foreground transition schedules one check after 15 seconds. Backgrounding cancels it. There is no periodic worker, foreground service, cloud transfer or persistent timer.
- Failures and interrupted attempts back off for 1/2/4/8/16/24 hours. No repeated notifications are emitted. The page exposes the unfinished-attempt status and last successful time.
- Automatic archives contain settings (always included by BackupManager), database, skills and assistant memory. Large attachment trees, workspaces, extra configuration stores and secret vaults are excluded.

## Storage and integrity

`LocalSnapshotStore` stores archives, manifests and device-local scheduler configuration under `Context.noBackupFilesDir/local-snapshots`. Configuration intentionally stays outside restored Settings/DataStore: importing an old backup must not overwrite this device's retention policy, retry timestamps or snapshot index. It is not another application-data backup format; every archive is created by the existing BackupManager.

Archives are written and synced in a preparing directory, checked with RestoreQuota and ZIP CRC32, SHA-256 recorded, and published with an atomic rename. Restore checks the recorded size/hash and ZIP CRC before calling the shared staging lifecycle. An unpublished replacement never causes pruning of older archives. Configuration uses a synced temporary file and atomic replacement.

Automatic archives share one count/byte budget. The latest pre-restore archive has a separate single-archive budget of the configured size. Steady-state archive space can therefore reach twice the budget; creation and restore also need temporary archive/staging space. Retention changes take effect after the next verified snapshot. Malformed configuration/manifests fail closed and are not silently discarded.

## Restore integration

`BackupManager.stageRestore` validates the incoming archive first, then saves current settings and the selected categories through the same createBackup path before publishing PendingRestore. Failure to create the safety archive aborts staging, including when automatic snapshots are disabled. Local, WebDAV and S3 restores all reach this hook. Chatbox/Cherry Studio additive imports are not covered.

The local snapshot tab lists automatic and pre-restore archives, asks for confirmation, stages the restore and shows the existing mandatory restart dialog. After restart, selecting the pre-restore archive provides a return path. The selected archive is pinned in cache before staging because publishing its replacement safety copy can prune its original directory.

Secret-vault payloads in safety archives reuse SecretsBackupCrypto with a random local password wrapped by Android Keystore. The wrapping key is device-bound and no password is requested or persisted in plaintext. Other archive contents are **not encrypted**: settings may contain API credentials, and conversations/skills/memory may contain private data. Storage is private and excluded from system backup, but clearing application data/uninstalling destroys this recovery path. Explicit encrypted/off-device backups remain necessary. Existing secret imports merge entries; rollback does not remove newly added secret names. File restore follows existing PendingRestore semantics and is not an exact directory mirror.

## Verification

`LocalSnapshotStoreTest` exercises the real store with temporary directories and real ZIP archives: configuration round-trip/defaults, publication/reopening, hash and CRC corruption, missing settings, cancellation cleanup, traversal rejection, oversize rejection, separate retention pools, combined byte retention, due scheduling and capped backoff.

Local validation is limited to static checks and XML/resource-reference validation. No local or SSH Android/Gradle build is permitted. Compilation, JVM tests (`:app:testClmaUnitTest`) and Android lint require the repository CI after an authorized push. Device lifecycle/Keystore behavior and restore/restart UI still need isolated emulator validation; do not validate destructive restores on a user's live data.
