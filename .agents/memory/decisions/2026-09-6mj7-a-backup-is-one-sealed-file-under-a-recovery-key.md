---
id: "2026-09.6mj7"
slug: a-backup-is-one-sealed-file-under-a-recovery-key
title: "A backup is one sealed file under a recovery key, and a restore is a move"
date: 2026-09-20
topics: [data, crypto, ui, backup]
---

# ADR 2026-09.6mj7 — A backup is one sealed file under a recovery key, and a restore is a move

Status: Accepted (2026-09-20). Format: `docs/BACKUP_FORMAT.md`. Code: `data/backup/`, `ui/backup/`,
`RestartActivity`, `MeshManager.finishRestore`, `SeenSet.reopen`. No wire change, no DB schema change.

**What was observed.** A lost or replaced phone loses the person: the node id is the hash of the identity
key, and the key is wrapped under a hardware Keystore key that never leaves the phone. Android's own Auto
Backup and device-to-device transfer carry nothing of ours by design (`data_extraction_rules.xml` excludes
`identity.key`, `db.key`, `knit.db*` and `datastore/`) — the wrapped files are useless elsewhere — so the
only way onto a new phone was a new identity, with every pin, verification, group and message gone.

The reader's first reach is Android's backup service with our own `BackupAgent`. It does not fit: the
cloud quota is 25 MB per app (a phone with photos is over it at once), the restore lands only at install
time in a restricted process with no `KnitApplication`, and the file would sit on Google's servers under a
key the user never chose. Measured against what the user actually wants — *my* identity, on *my* new
phone, from a file *I* hold — it is the wrong shape. So: a manual, user-driven encrypted file.

**What changed.**

- One file, one key. `"KNITBK" ‖ version ‖ kdf ‖ CBOR header{salt, date}` in the clear, then a Tink
  streaming AEAD (AES-256-GCM-HKDF, 1 MiB segments) over a length-prefixed container: manifest first,
  then the identity file's plaintext, the SQLCipher passphrase, the settings file and the database copy.
  The key is a **30-digit recovery key** the app mints and shows once (Signal's model): ~100 uniform
  bits, so a single HKDF is the whole derivation and no slow KDF has to make up for a weak passphrase.
  The `kdf` byte is the door for a passphrase mode later. Nothing identifying is outside the seal.
- The database copy is a **fresh Room-built file filled inside SQLite**, not a file copy and not a
  dump. Room creates the schema, the identity hash and the FTS triggers exactly as it would for the real
  database; the live file is then `ATTACH`ed to a *raw* single-connection SQLCipher handle and copied
  table by table (`INSERT … SELECT`, column names spelled out — a migrated file's column order need not
  match a fresh one's) under a `SAVEPOINT`. Two traps found on the way: SQLite reports `ATTACH` as a
  read-only statement, so through Room's pooled SQLCipher connection it lands on a reader and the copy on
  the writer never sees it; and the SQLCipher Android layer rewrites every `BEGIN` to `EXCLUSIVE`, which
  spans attached databases and would take the live write lock for the whole copy (the mesh's writers hit
  `busy_timeout` at 3 s). A savepoint outside a transaction is a deferred transaction that neither
  rewrite touches: one read snapshot of the live file, no write lock, the mesh keeps running.
- **Custody and both ratchets are never carried** (`BackupTables.TRANSIENT`). An empty custody store is
  the documented self-healing start (`ForwardSync.onDigest` re-serves a node its own frames). A send
  chain carried forward re-seals indices its peers already consumed — each a `DUPLICATE`, which ADR 024
  deliberately never counts, so the loss would be silent. The wipe/reinstall path exists for exactly
  this (`FORWARD_SECRECY_RATCHET.md` §7), and it keeps forward-secrecy material out of a file that may
  sit on a cloud drive for years. `BackupTablesTest` pins every table to a list.
- **A restore is staged, verified, then applied by a fresh process.** Both readers on the other side
  fail destructively (`DatabaseKey` wipes on an unwrappable passphrase, `IdentityKeyStore` mints a new
  identity on an unparsable file), so `RestoreStager` wraps the secrets under the live aliases *into
  staging* (`KeystoreSecret` gained a `dir`), reads them back, checks the identity's node id against the
  manifest, opens the staged database under the passphrase, and writes `READY` last. The app then stops
  the service (`stopService`, not `MeshService.stop` — that one records "mesh off"), clears its
  notifications and conversation shortcuts, and relaunches through `RestartActivity` in `:restart`;
  `KnitApplication.onCreate` applies the staged files before Koin (and returns at once in the trampoline's
  own process). Every step is an atomic rename inside the app's data dir; a crash between two resumes.
- **The first start after a restore resets every DM session** (`finishRestore`, on `restore_pending`):
  a fresh signed prekey (a forced `rotatePrekey`, new), a profile bump past anything the old phone
  published, and `sendSessionReset` to every DM peer, so the receiver re-seals what it sent while the
  phone was between lives instead of waiting for three undecryptable frames and a six-hour floor.
- **`SeenSet.reopen`.** The re-seal answers under the *same* frame id — by design, so every other node
  dedups it — but the copy custody served the restored node moments earlier had put that id in its seen
  set, and the fresh seal was deduped too (`RestoreLabTest` caught it: `deduped=2`, four DMs stuck at
  two). A sealed DM addressed to us that fails to open now reopens its id once per window; a second
  reopen is refused, which caps what a peer re-serving an unopenable frame can make us relay at one extra
  copy. The pre-existing forced-reset scenario never hit this because the resetting node still held
  `prevRoot` and opened the old copies itself.

**What it costs, and the traps.** A restore is a **move**: two phones on one identity keep resetting
each other's sessions, and nothing in the format prevents it — the confirm dialog and the onboarding copy
say so, and since ADR 2026-09.ypcc each phone notices the other from its profile stamp and offers to sign
out. DMs sent to the phone *between* the backup and the restore arrive only through the peer's
re-seal (≤ 24 h custody, the last 24 h of unacked DMs); older ones are gone with the session. The
transient LoRa keys are dropped (the bond is per phone) — the user pairs the board again. A backup whose
database is newer than the reading build is refused (`NEWER_APP`); Room has no way down. The
`SettingsKeys.TRANSIENT_PREFIXES` list and `BackupTables` are the two places a new persistent thing must
be classified; the second is test-enforced, the first is not.

**Device run, 2026-09-20 (Pixel 9 Pro XL, the backup half).** The live 14.3 MB SQLCipher database with
the mesh up (two BLE links, spool connected) exported and sealed to a 14.76 MB file in 2.5 s through the
`…debug.BACKUP` bridge, and again in ~2 s through the real screen and document picker into Downloads; both
files verified on the phone under their keys in under a second (`RestoreStager.stage` then `discard`), a
wrong key was refused as `WRONG_KEY_OR_DAMAGED`, neither the node id nor the name appears in the file's
bytes, the live `-wal` was untouched and no lock or busy error was logged. Still owed: the restore half
on a second phone from onboarding, then the Settings door on the same phone.
