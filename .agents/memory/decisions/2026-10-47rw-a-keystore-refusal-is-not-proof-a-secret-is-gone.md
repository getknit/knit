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
- **Start over is the user's call, never the app's.** The same screen carries a secondary Start over behind one
  confirmation that names what goes and the way back (restore a backup at onboarding). It is "Sign out here"'s
  wipe, `SignOut.here` (ADR 2026-09.ypcc): the platform's Clear storage, which also clears the app's Keystore
  keys (`PackageManagerService.clearApplicationUserDataLIF` → `removeKeystoreDataIfNeeded`), so the next open is
  onboarding under a fresh key. No count of failed tries unlocks it and nothing offers it automatically: a
  refusal is usually gone a moment or a reboot later, and the button sits beside Try again, not instead of it.
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

**What it costs and does not cover.** A Keystore that is broken for good no longer starts over by itself: the
user does, with Start over, and a phone whose Keystore cannot mint a fresh key either lands back on Try again. A
proven loss costs two seconds of retries before the wipe, and a first run costs none. A legacy
(API 29/30) keystore daemon dead for the whole retry window still reads as KEY_MISSING; and
`ui/invite/ShareSigningKey` has the same `containsAlias ?: generate` shape, out of scope here. Regression:
`KeystoreFailureTest`, `KeystoreSecretTest`, `DatabaseKeyRecoveryTest`, `IdentityKeyStoreRecoveryTest`,
`BackupWriterKeystoreTest`, `RestoreStagerTest`, `MeshServiceGraphOffMainTest`, `StorageGateTest`,
`StorageUnavailableScreenContentTest`; on a device, `DatabaseKeyTest` (`aKeystoreRefusalNeverWipes`, through the
debug injector over the real Keystore). The trap: never read a null or an exception from the Keystore as "gone"
without a row of the table above that says so, and never generate under an alias a live wrap depends on.

## Amendment (2026-10-27): an identity that opens but does not parse is read again first

On Play, 2.9.0, one Redmi Note 14 Pro+ 5G (HyperOS, API 36) unwrapped its identity with the tag verified and failed
partway through `hybridPriv`'s array, on a header no build writes there. The format has only grown additively since
2.0.0 and the restore stager parses with the same code before it wraps, so a verified tag no longer proves the bytes
are the ones sealed: a vendor Keystore can hand back others. `IdentityKeyStore.loaded` now unwraps again after each
of the retry waits (500 ms, 1500 ms) before it fails, and a later read that parses is used as it stands. One that
never parses is still the `IllegalStateException` above, nothing minted or moved, and its message now says whether the
reads agreed and gives each one's `CborShape`: major types, counts, lengths and the identity's own field names, never
a value byte (`567 B {hybridPriv:[i×149 t7@276 [i×154]@284 !@567` is a span gone mid-array). A re-read the Keystore
refuses or calls lost ends the re-reads and is named in the message; it is never a reason to mint. Reads that agree
point to bytes altered on the way in, or a bad build; reads that differ, to the way out. Play strips exception
messages, so the shape reaches the local crash log and a field log, not the console. `DatabaseKey`'s wrong-length
check is unchanged. Regression: `IdentityKeyStoreRecoveryTest`, `CborShapeTest`.

## Amendment (2026-11-05): a store proves its wrap, and the failed read's shape rides the crash's frames

**What the next reports showed.** The same phone crashed on 2.10.1 and on 2.10.2, five reports in ten days. 2.10.2 has
the re-read, so no read parsed across its two seconds: the fault lasts, and a glitch on the way out is off the list.
`Decoder.kt:410` is `readNumber`'s major-type check, and end of input would have thrown earlier, in
`peekCurByteOrFail`, so the bytes hold a real non-integer header. The field is `sigPriv`, not the amendment above's
`hybridPriv`: these frames are PC-encoded, so `SourceFile:61` is dex offset 0x3D, the `index == 1` branch in both
APKs. Play had retraced the 2.9.0 report, which leaves no offset to check that claim against.

**What the 2026-10-27 amendment assumed.** It sent the shape to the local crash log, and that log is out of reach. The
log is read in Diagnostics, behind `ui/StorageGate`, which re-throws this failure before the first draw.
`CrashRedactor` would also have spoiled it: a token holding `×` becomes `[text]`, and a message stops at 300
characters. Play is more selective than the amendment says: it keeps a framework's or the VM's message
(`NoClassDefFoundError`, `ActivityNotFoundException`, a null-pointer's) and drops those of exceptions the app throws.

**What changed.**

- `KeystoreSecret.store` seals, opens what it sealed and compares before it writes. Other bytes, or an open the Keystore
  refuses, are a `KeystoreUnavailableException` with the file's bytes as they were. `IdentityKeyStore.persist` had no
  read-back, so the weekly rotation could replace the only good copy with one that never opens right. It covers every
  caller, the passphrase and the restore stager included, at the price of one more Keystore operation per store. It
  gets one attempt where `read` gets three: a rotation waits for the next heal and a mint shows Try again. It proves the
  Keystore handed back what it was given, not that those bytes parse. The key is generated before the check, so a
  failed proof after `freshKey` or three absent lookups leaves the old wrap under a replaced key; both are cases where
  that wrap was already dead.
- `IdentityKeyStore.rotatePrekeyIfDue` answers false when its prekey cannot be stored. The old prekey stands and the
  next heal tries again. It runs inline in the mesh start and the heal basket, so a throw would skip every later step,
  and on a phone that always mis-seals, every time. `currentPrekey` and `rotatePrekey` still throw: one has no prekey
  to fall back on, and the other is retried by the restore's own flag.
- `CborShape` walks only what `Stored` holds: integers, arrays and maps keyed by the field names, and in an array
  nothing wider than a `ByteArray`'s two-byte element. It stops at the first head that is anything else and prints
  nothing of it. The 2026-10-27 walk was fit for a log the phone keeps, not for a
  report that leaves it, and its "never a value byte" was wrong. A `ByteArray` here is an array of integers, so past a
  lost span the walk reads key bytes as heads: `t7@274` pins the byte at 274 exactly, a long `b<n>` the bytes of its
  length, and a tag's argument printed eight raw bytes. The security review's port pinned up to six secret bytes in one
  walk of a synthetic identity. A field name met inside an array is read with its value, so a span lost at a boundary
  still shows the structure after it: `[i×149 sigPriv:[i×154]@282 …`.
- `crash/TraceText` puts `describeReads`' text in the exception's frames, as `knit.TraceText.t<chunk>(TraceText:<n>)`.
  Play keeps every frame, retrace leaves a class it has no entry for alone, and `CrashRedactor` passes frames through
  whole, so the same text now reaches both. `scripts/trace-text.py` reads it from a report: pull the text with
  `gpc auth token` and `errorReports:search`, whose filter is `errorIssueId = <id>`.
- Debug builds hand back altered bytes on cue while `files/keystore-alter` holds a count: the next N opens of anything
  longer than a passphrase lose a span.

**The alternatives.** *An exception class per verdict*: Play keeps class names, but that is one bit, and the shape is
the part that separates a span lost at a boundary from garbage. *A message Play might keep*: which messages survive
is Play's unstated rule. *The stand-down first*: it ends the crash, and the crash is the only report this phone sends.
The user is still stuck until the stand-down ships, which is the price of learning the cause first.

**What it costs and does not cover.** This is the one place the app means a crash to tell Google something, past ADR
028's "leaves the phone only by the user's hand", and the README now says so. The text is sizes, offsets, run lengths,
the field names and whether the reads agreed. No token names a byte: a head read out of step in an array shows only that
it read as an integer one or two bytes wide, and an offset how many value bytes before it took one byte to encode. The
slot after a name can be wider, as `createdAt` has to be. A short run can narrow any of that to one head's width, so one
byte to a few values, and the review's port of the first form of this walk saw
that in well under one run in a thousand. That holds per walk, and a phone whose reads differ sends more walks, each
with the same kinds of token. The price is that a report says where the
run broke and not what it broke on. A `TraceText` caller answers for what it carries, because nothing downstream reads a
frame: never hand it a message built from what a peer or the user wrote. A rotation that keeps failing is only a
`Log.w`, which this phone's reports cannot show.

Proven on `Knit_API30_Glow`, a debug build with `files/keystore-alter` at 99: the same `readNumber` throw, the three
frames in `dumpsys dropbox --print data_app_crash`, unchanged by retrace against the 2.10.2 map, decoded to
`3 reads, the same bytes every time: 674 B {hybridPriv:[i×149 sigPriv:[i×154]@282 prekeys:[…]@574 !@674`; both files
untouched, and a clean open after the marker went to 0. Noise rather than a lost span is covered on the JVM only, since
the walk is plain Kotlin. Not proven: Play's own handling of a frame it has never seen, which only the next report from
this phone can show. Regression: `KeystoreSecretTest`, `IdentityKeyStoreRecoveryTest`, `DatabaseKeyRecoveryTest`,
`CborShapeTest`, `TraceTextTest`.
