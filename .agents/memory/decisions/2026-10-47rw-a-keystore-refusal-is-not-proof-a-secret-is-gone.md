---
id: "2026-10.47rw"
slug: a-keystore-refusal-is-not-proof-a-secret-is-gone
title: "A Keystore refusal is not proof a secret is gone"
date: 2026-10-01
topics: [crypto, storage, reliability]
---

# ADR 2026-10.47rw — A Keystore refusal is not proof a secret is gone

Status: Accepted (2026-10-01). Amends ADR 2026-09.vztn for one typed failure.

**What was observed.** 2026-10-01 15:15:50, the lab Pixel 3 (API 31, StrongBox key), the first open after
`:app:installDebug` replaced its 2.7.0 debug build, dozing behind its keyguard, the process started by a
debug-bridge broadcast. keystore2 logged `upgrade_keyblob_if_required_with … Error::Km(ErrorCode(-1000))`
(KM_ERROR_UNKNOWN_ERROR), and `Cipher.init` threw `InvalidKeyException: Keystore operation failed`.
`DatabaseKey.getOrCreate` caught *any* unwrap failure as "the key is gone", deleted `knit.db` and minted a new
passphrase; `KeystoreSecret.load()` returned null on the same failure and `IdentityKeyStore` read null as "no
identity yet". Node `t3dqn4lj…` became `rs6wwtor…`, its database gone. The Pixel 7, 8 and 9 Pro XL took the same
install in the same minute and kept theirs. GitLab #110.

**What it was mistaken for.** A lost key. Nothing told the two apart: a key that is gone and a Keystore that
refused one operation both arrived as "an exception from the unwrap". The AOSP sources (android12 keystore2,
android11 legacy keystore) say which is which:

| Where | Backend verdict | Surfaces as | Proves |
|---|---|---|---|
| `KeyStore.getEntry` | **any** error | `null` (base `engineGetEntry` → `engineContainsAlias` swallows it) | nothing |
| `KeyStore.getKey` | KEY_NOT_FOUND | `null` | absence, on API 31+ |
| `KeyStore.getKey` | other | `UnrecoverableKeyException` + `KeyStoreException` cause | nothing |
| `getKey`, API 29/30 | keystore binder `RemoteException` | `contains()` false → `null` | nothing |
| `Cipher.init` | KEY_NOT_FOUND / PERMANENTLY_INVALIDATED | `KeyPermanentlyInvalidatedException` | loss |
| `Cipher.init` | LOCKED / UNINITIALIZED | `UserNotAuthenticatedException` | nothing |
| `Cipher.init` | anything else (the P3) | `InvalidKeyException("Keystore operation failed", cause)` | nothing |
| `doFinal` | VERIFICATION_FAILED | `AEADBadTagException` | loss |
| `doFinal` | anything else | `IllegalBlockSizeException` + cause | nothing |

The issue's own list put `UnrecoverableKeyException` on the permanent side; keystore2 wraps every backend error
in one, so it is a refusal. And the old code looked keys up with `getEntry`, which reads a busy keystore2 as an
empty alias.

**What changed.**

- `data/crypto/KeystoreCipher` puts the four Keystore operations (look up, generate, seal, open) behind one seam
  (`AndroidKeystoreCipher`, which now looks up with `getKey`), so the decisions around them are JVM-tested over
  `FakeKeystoreCipher`. `DatabaseKey` keeps its passphrase in a `KeystoreSecret` instead of a second copy of the
  same code.
- `KeystoreSecret.read()` answers `Present`, `Absent` (no file; the Keystore is not asked), `Lost(reason)` or
  `Unavailable(cause)`. `KeystoreFailure.classify` reads the cause chain: `AEADBadTagException` is
  TAG_MISMATCH, `KeyPermanentlyInvalidatedException` is KEY_INVALIDATED, a null lookup is KEY_MISSING, a file
  shorter than IV + tag is MALFORMED (at once, no retry) — and **everything else is a refusal**. The unwrap is
  tried three times (waits 500 ms, 1500 ms); `Lost` needs a loss on every attempt, and any refusal makes the read
  `Unavailable`. That also covers a StrongBox that answers a tag mismatch once, and the legacy keystore's dead
  daemon.
- `DatabaseKey.getOrCreate` and `IdentityKeyStore.loaded` wipe/mint on `Absent` (as before) and on `Lost` (after
  `keepAside()` moves the old wrap to `noBackupFilesDir/<name>.lost`, one slot, never backed up), and on
  `Unavailable` throw `KeystoreUnavailableException` with every file as it was. Every mint stores, then reads
  back, and fails the open unless the bytes match — a Keystore that seals but never opens would otherwise have
  the next start read the new wrap as lost and wipe again. KEY_INVALIDATED mints under a fresh key; every other
  loss keeps the alias's key.
- A wrap that opens but holds the wrong number of bytes, or an identity that opens but does not parse, is a code
  or version mismatch, not a loss: `IllegalStateException`, nothing wiped. A bad release used to hand every user a
  new identity.
- **Generating a key replaces the alias.** `keystoreKey() = existing ?: generate` let one transient lookup inside
  `store()` (the weekly prekey rotation; `RestoreStager.wrap`, which writes staged files under the *live*
  aliases) delete the key every live wrap depends on. `store()` now generates only when the lookup says absent on
  every look while the live wrap exists, or with no live wrap at all (a first run: no waiting), or when asked for a
  fresh key; a lookup that throws propagates.
- The backup writer read the passphrase through `getOrCreate()` beside the open database, so a refusal there
  deleted the live `knit.db`. It reads `DatabaseKey.current()` and `KeystoreSecret.read()`, which never wipe or
  mint, and a refusal is `BackupProblem.KEYSTORE_UNAVAILABLE` ("try again in a moment"); the restore stager maps a
  refused wrap or read-back the same way, where it used to say the backup was damaged.
- **The surfaces (amends ADR 2026-09.vztn).** A graph that cannot be built is still a crash on the main looper —
  except when `Throwable.keystoreUnavailable()` finds the typed refusal down the cause chain (Koin nests one
  `InstanceCreationException` per single). Then `MeshService.declineStorage` posts `StorageAlert` (ALERTS
  channel, id 11), drops the foreground state, keeps the heartbeat, stops, and leaves `meshEnabled` alone; the
  next `startMesh` takes the notice back. `MainActivity` no longer lets `KnitApp`'s first composition open the
  database and the identity on the main thread: `ui/StorageGate` opens them (and `MeshController`, unless
  SEED_DEMO) on IO while the first draw is held — the splash looks as it did — and shows `KnitApp`, or on a
  refusal `StorageUnavailableScreen` with Try again, retried on every resume too. Any other failure in the gate
  is re-thrown on the main looper, as before.
- Debug builds wrap the real cipher in `FaultyKeystoreCipher`: while `files/keystore-fault` holds N, the next N
  unwraps throw the P3's exception. That is how a device trial reproduces a refusal.

**The alternatives.** *Retry, then wipe as before*: a one-file change, but a Keystore that needs a minute, an
unlock or a reboot still wipes. *Quarantine the database instead of deleting it*: on a proven loss the passphrase
is cryptographically gone, so a kept database is storage nobody can open, and swapping it back later collides with
clone detection (ADR 2026-09.ypcc); the small wrap files are kept aside instead. *Crash on a refusal (vztn's
rule)*: data-safe, but a background sticky restart is refused from the background and a battery-exempt phone
crash-loops, and "Knit keeps stopping" on every open reads as data loss and invites Clear storage — the wipe this
exists to prevent. *Use `android.security.KeyStoreException.isTransientFailure()`*: public only from API 33,
hidden below, and the classifier needs no codes — unrecognised already means refusal.

**What it costs and does not cover.** A Keystore that is broken for good now leaves the app on Try again instead
of silently starting over; the only way out is the system's Clear storage (an in-app "start over" would be a
follow-up). A proven loss costs two seconds of retries before the wipe, and a first run costs none. A legacy
(API 29/30) keystore daemon dead for the whole retry window still reads as KEY_MISSING; and
`ui/invite/ShareSigningKey` has the same `containsAlias ?: generate` shape, out of scope here. Regression:
`KeystoreFailureTest`, `KeystoreSecretTest`, `DatabaseKeyRecoveryTest`, `IdentityKeyStoreRecoveryTest`,
`BackupWriterKeystoreTest`, `RestoreStagerTest`, `MeshServiceGraphOffMainTest`, `StorageGateTest`,
`StorageUnavailableScreenContentTest`; on a device, `DatabaseKeyTest` (`aKeystoreRefusalNeverWipes`, through the
debug injector over the real Keystore). The trap: never read a null or an exception from the Keystore as "gone"
without a row of the table above that says so, and never generate under an alias a live wrap depends on.
