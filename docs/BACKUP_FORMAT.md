# Backup file format

The `.knitbackup` file a phone writes from **Settings › Backup and restore**, and reads back on the same
or another phone. This is the normative description of the bytes; the code is `data/backup/`
(`BackupFormat`, `BackupKeys`, `BackupArchive`), pinned by `BackupArchiveTest`. Decision record: ADR 2026-09.6mj7.

## What it is for

A phone's whole Knit state, moved by the user: the identity (the node id *is* the hash of its key, so
without the key the person is gone), every contact pin and verification, every group, every message and
attachment, and the settings. Android's own backup and device-to-device transfer carry none of this by
design (`res/xml/data_extraction_rules.xml`): the secrets are wrapped under a hardware Keystore key that
never leaves the phone, so a copy of the files is useless elsewhere. This format carries the *unwrapped*
secrets under a key the user holds instead.

A restore is a **move**, never a copy. The mesh has no multi-device concept: two phones running one
identity each hold a different ratchet session per peer and keep resetting each other's conversations.
The UI says so; the format does nothing to prevent it. The app does notice it after the fact — a profile
frame under its own node id carrying a publish stamp it never minted (`mesh/CloneWatch`, ADR
2026-09.ypcc) — and offers "Sign out here", which clears the phone so the next open is a fresh identity.

## Layout

```
"KNITBK"                       6 bytes, magic
u8   format version            1
u8   kdf id                    1 = recovery key
u16be headerLen
CBOR BackupHeader { salt: bstr(32), createdAt: int }        headerLen bytes
--- everything above is the AEAD's associated data; everything below is the ciphertext ---
Tink StreamingAead  AES-256-GCM-HKDF, 1 MiB segments (PredefinedStreamingAeadParameters.AES256_GCM_HKDF_1MB)
  over the container:
    u8 containerVersion = 1
    entry*: u16be nameLen ‖ name (UTF-8) ‖ u64be size ‖ bytes
```

The first entry is always `manifest.cbor`; the manifest names every entry that follows, in order, with
its size and SHA-256:

```
BackupManifest {
  v: int,                 the format version, again, inside the seal
  schemaVersion: int,     the Room version of the carried database (KnitDatabase.SCHEMA_VERSION at write time)
  appVersionCode: int,
  appVersionName: tstr,
  createdAt: int,         ms since the epoch, the same value as the header's
  nodeId: tstr,           the identity the file carries
  displayName: tstr?,     the owner's name at the time, for the restore prompt
  entries: [ { name: tstr, size: int, sha256: bstr(32) } ]
}
```

Entries, in the order written (largest last, so a reader can refuse early):

| Name | Bytes |
|---|---|
| `identity.key` | the plaintext `IdentityKeyStore.Stored` CBOR: the Tink hybrid and Ed25519 private keysets and the signed-prekey list |
| `db.passphrase` | the 32 random bytes SQLCipher was keyed with (`DatabaseKey`) |
| `settings.preferences_pb` | the preferences DataStore, as DataStore's own file serializer writes it, minus the phone-bound keys (below) |
| `knit.db` | a SQLCipher database at `schemaVersion`, keyed with `db.passphrase` as a raw key (`x'<64 hex>'`, no KDF — ADR 2026-09.uzkm), holding the carried tables |

Nothing identifying sits outside the seal. The plaintext prefix says "a Knit backup, made on this date"
and carries a salt; the node id, the name and the entry list are inside.

## Key

A **recovery key** the app mints: 30 decimal digits from a CSPRNG, shown once as six groups of five,
never stored. ~100 bits, uniform, so no slow KDF is needed — the whole derivation is

```
ikm = HKDF-SHA256(ikm = the 30 ASCII digits, salt = header.salt, info = "knit/backup/v1/key", 32 bytes)
```

fed to Tink's streaming AEAD as its key material; Tink derives a per-file subkey from its own header
nonce, so one recovery key across several backups is fine. A wrong key, a flipped byte anywhere and a file
cut short all fail the same way — Tink's tag check on the segment — and are reported as one problem
(`WRONG_KEY_OR_DAMAGED`); the app cannot tell them apart and does not pretend to. Typed input tolerates
spaces and dashes; anything that is not exactly 30 digits after stripping is not a key.

`kdf id` is the door for a passphrase mode later (a slow KDF, its parameters in nullable header fields).
A reader refuses a format version or a KDF id it does not know before it touches the seal.

## What the database carries — and what it must not

`knit.db` is a fresh Room-built file (so the schema, its identity hash and the FTS content-sync triggers
are exactly what the app would create) filled from the live one under a read snapshot, table by table,
inside SQLite (`DatabaseExport`). The mesh keeps running: the live database's write lock is never taken.

Carried (`BackupTables.CARRIED`): `blobs`, `blob_verdicts`, `peers`, `met_peers`, `groups`,
`group_roots`, `commons`, `commons_members`, `commons_outbox`, `messages`, `reactions`,
`message_receipts`, `drafts`. Rebuilt on arrival: `messages_fts` (the triggers fill it as the rows land).

**Never carried** (`BackupTables.TRANSIENT`), on purpose — they exist in the copy, empty:

- `forward_store`: store-and-forward custody. An empty store is the documented, self-healing start
  state — the digest pull refills it, and a node is re-served its own frames (`ForwardSync.onDigest`).
- `ratchet_sessions`, `ratchet_local_epochs`, `ratchet_recv_epochs`, `ratchet_skipped_keys`,
  `group_send_chains`, `group_recv_chains`, `group_skipped_keys`, `group_key_sends`: both ratchets.
  A send chain carried forward from a snapshot re-seals indices its peers have already consumed; every
  such frame drops as a `DUPLICATE`, which the reset heuristic never counts (ADR 024) — a silent loss.
  The reset/replacement path (`FORWARD_SECRECY_RATCHET.md` §7, `GROUP_FORWARD_SECRECY.md` advance rule
  5) was built for "wipe / reinstall", and a restore is one. Leaving them out also keeps forward-secrecy
  material out of a file that may sit on a cloud drive for years.

**Device-local** (`BackupTables.DEVICE_LOCAL`), also empty in the copy: `saved_files`, where each received
file was saved on this phone. Its document URIs name this phone's storage and are readable only through
grants this install holds (ADR 2026-09.7ad3); after a restore a file bubble asks where to save, as it did
the first time.

`BackupTablesTest` pins the four lists to the exported schema: a new table fails the build until it is
classified.

## Settings

Every key is carried except the phone's own (`SettingsKeys.TRANSIENT_PREFIXES`): the Wi-Fi Aware
give-up and initiator-hold journals, the model poison-pill latch, the clone-watch stamps, a mesh pause's
deadline (`mesh_pause_until` — a backup taken mid-pause must not pause the other phone), and the paired LoRa
board's address, names, pre-setup values and airtime ledger (the Bluetooth bond is per phone; pairing the
board again brings them back). The board's node number and key stay — they ride the profile. Relay URLs are carried
*with* their bearer tokens: they are the user's, and the file is sealed.

Two keys are written **at restore time**, not at backup time: `onboarding_seen = true` (or the name page
would save its empty field over the restored name) and `restore_pending = true` (below).

## Restore

`RestoreStager` decrypts into `noBackupFilesDir/restore-staging/` and proves every piece before it writes
`READY`, because the two readers on the other side fail *destructively* (`DatabaseKey` wipes the database
on a passphrase it cannot unwrap; `IdentityKeyStore` mints a new identity on a file it cannot parse):

1. the manifest's `schemaVersion` is at most this build's — Room has no way down;
2. every entry's size and SHA-256 match the manifest as it is written;
3. the identity parses and its node id is the manifest's;
4. the identity and the passphrase are wrapped under the **live** Keystore aliases into the staging
   directory (`KeystoreSecret(…, dir = staging)`) and read back;
5. the staged database opens under the passphrase's raw key, is at `schemaVersion`, and passes `quick_check`
   (a copy a pre-2026-09.uzkm development build keyed with the passphrase itself is rekeyed onto the raw
   key first, by the same `SqlCipherKey.upgrade` the live database goes through);
6. the settings parse.

The plaintext identity and passphrase live in memory only. Then the app stops its mesh service, clears
its notifications and conversation shortcuts, and relaunches through `RestartActivity` — a second process
that kills the first and starts `MainActivity`. In the new process `KnitApplication.onCreate` runs
`RestoreApplier.applyPending` *before* Koin: it deletes the live database's `-wal`/`-shm`/`-journal`
(SQLite validates a WAL against its own header only, so a stale one would be replayed onto the restored
file), renames each staged file into place, and deletes `READY` last. Each step is an atomic rename inside
the app's data directory, so a crash between two moves resumes on the next start.

The first mesh start after that (`MeshManager.finishRestore`, gated on `restore_pending`) mints a fresh
signed prekey, sends a session reset to every peer whose session the restore wiped — each DM peer and the
other members of every group, since group seeds travel as control DMs — and then bumps and re-publishes the
profile (so the fresh prekey and the restored name outrank whatever the old phone last published). The
receiver adopts the fresh init, re-seals its still-unacked DMs of the last 24 h under it and re-flushes its
group seeds. The transport is already up by then, so a peer's re-served backlog may have tripped the
heuristic's own reset first, or a group post opened a session with a plain init; a session found here was
made since the wipe, so it gets the reset sealed under it, or nothing when a reset already went — never a
second root the peer would refuse inside its one-minute floor (ADR 2026-09.qerd,
`FORWARD_SECRECY_RATCHET.md` §7). Group send chains re-mint on the next group send by themselves.
`RestoreLabTest` runs the whole thing between real stacks in one JVM.

One router detail makes the re-seal land: the sender re-seals under the **same** frame id (so every other
node dedups it), but the copy custody served the restored node moments earlier put that id in its seen
set. A sealed DM addressed to us that fails to open reopens its id once (`SeenSet.reopen`), so the fresh
seal gets through; a second reopen inside the window is refused, which bounds what a peer re-serving an
unopenable frame can make us relay.

## Compatibility

Additions to the header, the manifest or an entry are nullable fields (the `docs/WIRE_COMPAT.md` rule,
applied to a file): an older reader ignores them. A new entry name is refused by older readers
(`NOT_A_BACKUP`), so it needs a format-version bump. A backup whose database is newer than the reading
build is refused as `NEWER_APP` ("update Knit first"); an older one is installed and Room migrates it on
the next open, like any upgrade.
