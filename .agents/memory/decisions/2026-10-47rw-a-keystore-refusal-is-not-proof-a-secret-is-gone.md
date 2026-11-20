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

## Amendment (2026-11-19): a StrongBox that refuses a new key is not a Keystore that refuses

**What was observed.** Firebase Test Lab, a Xiaomi 14 (`houji`, API 35, HyperOS `OS2.0.109.4.VNCEUXM`), the seeded
debug build, run `matrix-1261cld3vxsmd`. Every key generation failed in the StrongBox HAL, 99 times across the run's
test processes: `javacard.keymint.device.strongbox-impl: Error in sending generateKey`, keystore2's
`Error::Km(r#UNIMPLEMENTED)`, and `android.security.KeyStoreException: Not implemented (internal Keystore code: -100)`
under a plain `ProviderException("Keystore key generation failed")`. `DatabaseKey.mint` threw
`KeystoreUnavailableException`, each test's screenshot shows "Waiting for secure storage", and all 24 tests that ran
timed out. GitLab #351.

**What it was mistaken for.** A refusal the next attempt may not repeat. `AndroidKeystoreCipher.generate` asked the
StrongBox first and fell back to the TEE only on `StrongBoxUnavailableException`, Android's word for a phone with no
StrongBox. Anything else was rethrown, and this ADR's surfaces took it from there: nothing wiped, Try again. But this
StrongBox is there and makes nothing, on every attempt. Try again minted into the same refusal, and so did Start
over, which clears the data and mints again. "A phone whose Keystore cannot mint a fresh key either lands back on Try
again", above, described a fresh install that never opens. `ui/invite/ShareSigningKey` had the same narrow fallback.
`AndroidDeviceKeystore.generate` (ADR 2026-10.wk3m) already fell back on any StrongBox failure.

**What changed.**

- `data/crypto/StrongBoxFirst.generate` is the one rule for where a new key is made: the StrongBox attempt first, and
  on any `Exception` from it (never an `Error`) the alias is deleted and the TEE is asked, under the same alias. A TEE
  attempt that throws too throws its own exception, with the StrongBox's suppressed under it, so a Keystore that makes
  no key at all is still `KeystoreUnavailableException` and Try again. A refusal other than
  `StrongBoxUnavailableException` logs one `Log.w` with the wait and the key's label, which is a constant: a wrap's
  file name, or "the share signing key". A phone with no StrongBox logs nothing, as before.
- `KeystoreCipher.generate` takes the backing it is asked for and the seam gains `delete`; the real cipher no longer
  decides. `KeystoreSecret` asks through `StrongBoxFirst` at both places it generates (`store(freshKey = true)`, and
  `keyForStore` once absence holds), so the rule is JVM-tested over `FakeKeystoreCipher` like the rest. Whether a key
  may be generated at all is unchanged. `AndroidDeviceKeystore` is as it was: it logs nothing by rule, and its aliases
  are derived.
- `ShareSigningKey` uses the same rule, and now decides that it has no key with `getKey`, not `containsAlias`. This
  ADR left that lookup out of scope. With the delete it could no longer stay there: a busy keystore2 answers
  `containsAlias` false, and a StrongBox attempt that then failed used to be rethrown with the key intact, where it
  would now be deleted and remade. A lookup that throws fails that one share, which the share screen already toasts.
- Debug builds refuse the next N StrongBox generations while `files/keystore-strongbox` holds N, with the Xiaomi's
  exception, so an emulator runs the fallback over the real Keystore.

**Why the delete is safe.** GitLab #351 argued that no wrap exists under the alias at generation time. That is not so:
`store(freshKey = true)` and `keyForStore` after three absent looks both generate beside a wrap. What holds is that
`KeystoreSecret` reaches `generate` only for a key the Keystore reported invalidated, or absent on every look, or with
no live wrap at all, and `ShareSigningKey` only after `getKey` answered null; and that generating already replaces the
alias. What a failed generation leaves under it, from the AOSP sources (android11 to android15):

| | API 29/30 (legacy keystore) | API 31+ (keystore2) |
|---|---|---|
| AES key (`AndroidKeyStoreKeyGeneratorSpi`) | deletes the alias before it tries, and again when it fails | binds the alias only after KeyMint succeeds, so it is unchanged |
| EC pair (`AndroidKeyStoreKeyPairGeneratorSpi`) | deletes before and after | deletes the key when anything after generation fails |

So the delete repeats the platform in three cells and removes a dead key in the fourth. It is there for a vendor stack
that does neither, which nothing here has shown to exist.

**The alternatives.** *Retry the StrongBox before falling back*: it keeps a StrongBox key through a one-off error, adds
its wait to first launch, and does nothing for this phone. *Never ask the StrongBox*: the simplest, and it gives up the
secure element on every phone where it works. *Remember a refusal for the process*, so the second key skips the
attempt: a mint happens once per alias per install, and the warning's wait says whether that is worth any state.
*Decide inside the real cipher*, the seam left at four operations: the JVM tests would then prove the fake's copy of
the rule, and the debug injector could not refuse the StrongBox alone.

**What it costs and does not cover.** One StrongBox error at a mint, on a phone whose StrongBox works, leaves that key
in the TEE for as long as the install lives, and only the key's own `KeyInfo` records it. The TEE is where every phone
without a StrongBox already keeps the same key. Not checked: whether every Xiaomi 14 does this or only this unit's
secure element ("Error in sending" reads like a HAL that cannot reach it), and whether #311's Redmi, also HyperOS, has
a StrongBox behind its misread identity. `ShareSigningKey` looks once where `KeystoreSecret` looks three times, so
on API 29/30 a dead keystore daemon still reads as no key there, and no test reaches that object (it has no seam).
The trap: a new key path asks through `StrongBoxFirst`, never `setIsStrongBoxBacked(true)` with a catch of its own.
Regression: `StrongBoxFirstTest`, `KeystoreSecretTest`, `DatabaseKeyRecoveryTest`; on a device, `DatabaseKeyTest`
(`aStrongBoxRefusalEndsOnATeeKeyUnderTheSameAlias`, through the debug injector over the real Keystore).

Proven on `Knit_A11y_API34`, an emulator with no StrongBox, a debug build with `files/keystore-strongbox` at 99 over
cleared data: `StrongBox refused db.key after 0 ms; making it in the TEE` and the same line for `identity.key`, the
app past the storage gate, the same node id after a cold restart with the marker still at 97, and no line at all with
no marker. `DatabaseKeyTest` passes on the managed `pixel8api34`. Not proven: the Xiaomi itself, which a Firebase Test
Lab run on `houji@35` owes, and that a key a real StrongBox refused ends outside it, which only a phone with one can
show (`DatabaseKeyTest`'s security-level line holds trivially everywhere else).
