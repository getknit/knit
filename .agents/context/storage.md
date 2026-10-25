# Storage, keys and backup — which decision governs which symbol

The symbol → ADR routing for the encrypted database, the Keystore-wrapped secrets, backup and restore, and clone
detection with sign-out.

Each entry names the symbols that trigger it and the ADRs to read (`.agents/memory/decisions.md` maps an id to its
file, amendments included). An entry is an index into its ADRs, not a substitute: where they disagree, the ADR wins
and the entry is fixed. `grep` this file for every symbol you touch; `AGENTS.md` routes here instead of carrying these
lines.

## Entries

- **When touching `data/crypto/DatabaseKey`, `SqlCipherKey`, the driver or pool size in `KnitDatabase.build`, or
  anything else that opens a SQLCipher file:** READ ADR 2026-09.uzkm. The database is keyed with the passphrase's
  raw form (`x'<hex>'`, no KDF), and a file an older build keyed through the KDF is rekeyed once by
  `SqlCipherKey.upgrade` before Room opens it. Never hand SQLCipher the bare passphrase: every pooled connection
  would pay a 256,000-iteration derivation under the pool's lock (≈46 s of cold start on a 32-bit phone). The pool
  is capped at four. Regression: `SqlCipherKeyTest`, `SqlCipherRawKeyTest`.
- **When touching `data/crypto/KeystoreSecret`, `KeystoreCipher`, `KeystoreFailure`, `DatabaseKey.getOrCreate` /
  `current`, `IdentityKeyStore.loaded` / `generateAndStore`, the `.lost` slot, `ui/StorageGate`, the `StorageGate`
  half of `MainActivity`, or anything else that reads a Keystore-wrapped secret:** READ ADR 2026-10.47rw. Only
  proof a secret is gone may wipe or mint: a tag that fails, a key permanently invalidated, a key missing on
  every look, a wrap too short to be one — the same verdict on all three attempts. Everything else, unrecognised
  included, is a refusal: `KeystoreUnavailableException`, every file as it was, and the service and the UI stand
  down on it (alert / Try again) instead of crashing. The screen's Start over is the user's choice behind a
  confirmation (`SignOut.here`'s wipe); never trigger it from code or a retry count. Look keys up with `getKey`, never `getEntry` (keystore2
  answers null for a busy backend there); never generate under an alias a live wrap depends on without
  confirmed absence; read a mint back before using it; and a live reader (the backup writer) uses `current()` /
  `read()`, never `getOrCreate()`. Debug builds refuse on cue while `files/keystore-fault` holds a count.
  Regression: `KeystoreSecretTest`, `DatabaseKeyRecoveryTest`, `IdentityKeyStoreRecoveryTest`,
  `BackupWriterKeystoreTest`, `StorageGateTest`; on a device `DatabaseKeyTest`.
- **When touching `data/backup/`, `ui/backup/`, `RestartActivity`, `KnitApplication.onCreate`'s pre-Koin
  block, `MeshManager.finishRestore`, `SeenSet.reopen`, `SettingsKeys.TRANSIENT_PREFIXES`, or adding a table
  or a DataStore key:** READ `docs/BACKUP_FORMAT.md` and ADR 2026-09.6mj7. A backup is one file sealed by a
  recovery key the app shows once and never keeps; custody and both ratchets are never carried
  (`BackupTables`, test-pinned to the schema — a new table must be classified), a restore is staged and
  verified in full before `READY` (both readers on the other side fail destructively), applied by the
  relaunched process before Koin, and finished on the first mesh start by one session reset per peer whose
  session was wiped (DM peers and every group's other members, ADR 2026-09.qerd). A restore is a **move** —
  the mesh has no clone tolerance — and the copy says so. The database copy goes
  through a raw single-connection driver on purpose (`ATTACH` is read-only to SQLite and lands on a pool
  reader; the SQLCipher layer rewrites `BEGIN` to `EXCLUSIVE`) — don't move it onto the Room connection.
- **When touching `mesh/CloneWatch`, the self branch of `InboundPipeline.handleProfile`, the `clone_`
  settings keys, `ui/chatlist/CloneBanner`, the Settings clone row, or `ui/signout/`:** READ ADR
  2026-09.ypcc. One backup restored onto two phones is detected from the **profile stamp alone** — a
  `profile` under our own node id whose `sentAt` is past `SettingsStore.profilePublishedAt` was minted
  elsewhere; chat frames are not evidence (a `DatabaseKey` wipe empties the record they would be judged
  against) — gated on `restorePending`, undone by a dismissal only for frames stamped before it, and
  answered by one `broadcastProfile` per not-visible → visible edge so the other phone sees us too (never
  while lit: no ping-pong). "Sign out here" is `ActivityManager.clearApplicationUserData()` — the whole
  phone, grants included, so the next open is onboarding — not a file list and not the restore trampoline.
  A twin heard only over LoRa is invisible (the plane drops its own node id at ingest). Regression:
  `CloneWatchTest`, `CloneLabTest` (`MeshLab.node(sameIdentityAs)`, `MeshLab.retire`), `SignOutTest`.
