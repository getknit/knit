# Storage, keys and backup — which decision governs which symbol

The symbol → ADR routing for the encrypted database, the Keystore-wrapped secrets, backup and restore, and clone
detection with sign-out.

Each entry names the symbols that trigger it and the ADRs to read (`.agents/memory/decisions.md` maps an id to its
file, amendments included). An entry is an index into its ADRs, not a substitute: where they disagree, the ADR wins
and the entry is fixed. `grep` this file for every symbol you touch; `AGENTS.md` routes here instead of carrying these
lines.

## Entries

- **When touching `data/changes/DataChange`, `DataChangeSink`, `di/DataChangeWiring`, or the `changes` parameter of
  `MessageRepository`, `ReactionRepository`, `GroupRepository`, `PeerRepository` or `BlobRepository`:** READ
  `docs/USB_CONTROL.md` (experimental; no ADR while it is debug-only). A repository write reports the id it touched —
  never its state — right after the DAO call, and only when something was written (a re-received row, an unchanged
  reaction or a sweep that deleted nothing reports nothing). Release wires `DataChangeSink.NONE`; the debug build's only
  reader is the USB control's change log, which keeps keys only while USB control is on. A key can outrun its caller's
  transaction or name a write that rolled back, so a reader re-reads the row and never trusts the key for state
  (`usbcontrol/ChangeFeed`). `BlobRepository` reports every store and delete of a blob's bytes by hash
  (`DataChange.Blob`, the attachment's `held`), inside its callers' transactions; the filter's verdicts are watched from
  `BlobVerdictDao` rather than reported (`usbcontrol/ChangeWatch`). Regression: `RepositoryChangesTest`.
- **When touching `data/crypto/DatabaseKey`, `SqlCipherKey`, the driver or pool size in `KnitDatabase.build`, or
  anything else that opens a SQLCipher file:** READ ADR 2026-09.uzkm. The database is keyed with the passphrase's
  raw form (`x'<hex>'`, no KDF), and a file an older build keyed through the KDF is rekeyed once by
  `SqlCipherKey.upgrade` before Room opens it. Never hand SQLCipher the bare passphrase: every pooled connection
  would pay a 256,000-iteration derivation under the pool's lock (≈46 s of cold start on a 32-bit phone). The pool
  is capped at four. Regression: `SqlCipherKeyTest`, `SqlCipherRawKeyTest`.
- **When touching `data/crypto/KeystoreSecret`, `KeystoreCipher`, `KeystoreFailure`, `StrongBoxFirst`,
  `DatabaseKey.getOrCreate` / `current`, `IdentityKeyStore.loaded` / `generateAndStore`, the `.lost` slot,
  `ui/StorageGate`, the `StorageGate` half of `MainActivity`, `ui/invite/ShareSigningKey`'s key generation, or anything
  else that reads a Keystore-wrapped secret or makes a Keystore key:** READ ADR 2026-10.47rw. Only
  proof a secret is gone may wipe or mint: a tag that fails, a key permanently invalidated, a key missing on
  every look, a wrap too short to be one — the same verdict on all three attempts. Everything else, unrecognised
  included, is a refusal: `KeystoreUnavailableException`, every file as it was, and the service and the UI stand
  down on it (alert / Try again) instead of crashing. The screen's Start over is the user's choice behind a
  confirmation (`SignOut.here`'s wipe); never trigger it from code or a retry count. Look keys up with `getKey`, never `getEntry` (keystore2
  answers null for a busy backend there); never generate under an alias a live wrap depends on without
  confirmed absence; read a mint back before using it; and a live reader (the backup writer) uses `current()` /
  `read()`, never `getOrCreate()`. Debug builds refuse on cue while `files/keystore-fault` holds a count.
  An identity that opens but does not parse is unwrapped again over the same waits before it fails, and the failure
  names each read's `CborShape` (the 2026-10-27 amendment), in its message and in its frames, the only part Play keeps
  (`crash/TraceText`, read back with `scripts/trace-text.py`). `CborShape` stops at the first head `Stored` could not
  hold and prints nothing of it: past a lost span a head is a key byte. `KeystoreSecret.store` opens what it sealed and compares
  before it replaces a file, and `rotatePrekeyIfDue` answers false rather than throw into the start path. Debug builds
  hand back altered bytes on cue while `files/keystore-alter` holds a count (the 2026-11-05 amendment).
  A new key is asked for through `StrongBoxFirst`: the StrongBox first, and the TEE under the same alias on any
  exception from that attempt, never a bare `setIsStrongBoxBacked(true)` with a catch of its own. A Xiaomi 14's
  StrongBox refused every key for a whole Test Lab run, which left each fresh install on "Waiting for secure storage",
  and made them all on the next run (#351, the 2026-11-19 amendment). Its applet was not ready, which would refuse
  `begin` on a key already there too, so an open in that state is a refusal and Try again (the 2026-11-20 amendment).
  The one warning it logs names a constant label, never a derived alias, and
  `ShareSigningKey` decides it has no key with `getKey` like the rest. Debug builds refuse
  the next StrongBox generations on cue while `files/keystore-strongbox` holds a count.
  Regression: `KeystoreSecretTest`, `StrongBoxFirstTest`, `DatabaseKeyRecoveryTest`, `IdentityKeyStoreRecoveryTest`,
  `CborShapeTest`, `TraceTextTest`, `BackupWriterKeystoreTest`, `StorageGateTest`; on a device `DatabaseKeyTest`.
- **When touching `data/backup/`, `ui/backup/`, `RestartActivity`, `KnitApplication.onCreate`'s pre-Koin
  block, `MeshManager.finishRestore`, `SeenSet.reopen`, `SettingsKeys.TRANSIENT_PREFIXES`, or adding a table
  or a DataStore key:** READ `docs/BACKUP_FORMAT.md` and ADR 2026-09.6mj7. A backup is one file sealed by a
  recovery key the app shows once and never keeps; custody and both ratchets are never carried
  (`BackupTables`, test-pinned to the schema — a new table must be classified), a restore is staged and
  verified in full before `READY` (both readers on the other side fail destructively), applied by the
  relaunched process before Koin, and finished on the first mesh start by one session reset per peer whose
  session was wiped (DM peers and every group's other members, ADR 2026-09.qerd). A restore is a **move** —
  the mesh has no clone tolerance — and the copy says so. A board kind's keys (`BoardKeys`, ADR 2026-11.gsqu
  Stage 2) split the way their `lora_` peers do, named key by key and never one bare `meshcore_` prefix; no DataStore
  key holds a board's contacts (plaintext), and a cross-kind bind (`BoardBindPolicy`) clears only the other kind's
  identity. The MeshCore setup record is the `meshcore_setup` table (DB v37, `data/meshcore/MeshcoreSetupStore`, ADR
  2026-11.gsqu's Stage 5b-2 amendment): SQLCipher only (its prior advert name may be the user's), keyed by board address
  and naming the board's key digest (`boardDigest`: it counts for no other board at that address),
  `DEVICE_LOCAL` (never in a backup), cleared by sign-out with the whole database, untouched by a cross-kind bind, and the
  consent the MeshCore session touches that board's contacts under; an unbind that cannot reach the board leaves it
  `pendingCleanup`. A restore empties it, so the restore dialog warns while a record is kept or unreadable
  (`MeshcoreSetupStore.anyRecord`, `RestorePhase.Staged.meshcoreSetup`, ADR 2026-11.gsqu's Stage 7b amendment). v33–v37 fold into the next release's single bump. The database copy goes
  through a raw single-connection driver on purpose (`ATTACH` is read-only to SQLite and lands on a pool
  reader; the SQLCipher layer rewrites `BEGIN` to `EXCLUSIVE`) — don't move it onto the Room connection.
- **When touching `dm_scope_anchors` (`data/scopeanchor/`, DB v34), `DmScopeAnchorDao`, `mesh/spool/DmScopeAnchors`,
  `MeshController.dropScopeAnchor`, or what `finishRestore` arms:** READ ADR 2026-11.dgj2 and spool spec C-3.1-6. The
  table is `BackupTables.CARRIED`, the one crypto-derived row a backup carries beside `group_roots`: per DM peer a
  restore resets that is the person's contact (`MeshManager.anchorTargets`: an accepted DM or an accepted group's
  member, never a blocked one), the live DM scope's id and outer seal keys, never a root or a chain. Only that set
  keeps a row, from a confirmed session. A reconcile runs on root changes and on a change to the block list or the
  accepted set (never on a settings write that left them as they were), and once per heal basket, one pass at a time
  under `MeshManager.anchorPass` (inputs read inside it); it deletes every unarmed row it did not just
  write, but for a peer our own reset is still waiting on, and the row of a blocked peer or a peer no longer a target,
  armed or not. So a removed contact, a stranger, an unaccepted request, a blocked sender or a drained era leaves nothing in
  the next backup. Presence keys stay `TRANSIENT`
  (ADR 2026-11.xp6d) because a restore re-mints them, while the anchor is the only way a restore's reset reaches a
  relay-only contact. A restore file is hostile input: rows of the wrong length, or armed past the 30-day window and
  its 24 h clock slack, are skipped and dropped, and while `restorePending` no reconcile runs (it would delete the carried rows before
  `finishRestore` arms them) and no row reaches the scope table (`MeshManager.restoredDmRoots`). Never log a row.
- **When touching the `lora_direct_seen` table (`data/ratchet/LoraDirectSeen.kt`, DB v28):** READ ADR 2026-10.x9fa and
  `.agents/context/crypto.md`'s compact-door entry. It is ratchet state — which DMs a compact LoRa copy delivered and
  whether the signed copy's ratchet step has run — so it is `BackupTables.TRANSIENT`, never carried, and swept 48 h after
  arrival on our own clock. `LocalMessageDelete` must never touch it: a held id is what keeps a deleted DM deleted.
- **When touching the `read_marks` table or `message_receipts.readAt` (`data/receipt/ReadMarkEntity` /
  `ReadMarkDao` / `ReadMarkRepository`, `MessageReceiptDao.markRead`, DB v35):** READ ADR 2026-11.z6jw. `read_marks`
  is the reader's durable outbox of reads owed to their authors (`toldAt` null = owed, `toldRoot` = the root that told
  it), `BackupTables.TRANSIENT` because a restore re-roots every session; owed marks are pruned 24 h after the read,
  told ones 48 h. `readAt` is our clock, never earlier than the row's `notedAt`, first-evidence-wins, and null on every
  row from before v35: no read is ever invented.
- **When touching `own_presence_keys` / `peer_presence_keys` (`data/presencekey/`, DB v32), `PresenceKeyDao`,
  `PeerDao.deleteOrphanPresenceKeys` or the `presence_key_last_generation` setting:** READ ADR 2026-11.xp6d ("The
  presence key"). Both tables are `BackupTables.TRANSIENT`: a backup must not carry the means to recognise every
  contact's phone (the DM scope anchors it does carry are the exception ADR 2026-11.dgj2 argues), and a restore mints its own keys afresh while the carried generation counter keeps their numbers above
  everything as of the backup. Numbers minted after the backup can repeat, and the new root tells the eras apart. A
  peer's keys go with its `peers` row. Never log a row.
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
