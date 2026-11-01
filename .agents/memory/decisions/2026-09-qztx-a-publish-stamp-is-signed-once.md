---
id: "2026-09.qztx"
slug: a-publish-stamp-is-signed-once
title: "A publish stamp is signed once, under one lock, by whoever writes it"
date: 2026-09-16
topics: [mesh, spool, profiles]
---

# ADR 2026-09.qztx — A publish stamp is signed once, under one lock, by whoever writes it

Status: Accepted (2026-09-16; `MeshManager.profileLock`, `signOwnProfileLocked`, `nextPublishStamp` — amends
ADR 2026-09.y5f3's "one id, one set of bytes". No wire change, no DB migration, no capability bit.)

**Amended 2026-10-31:** the watcher judges every value, not only the first. It judges each one as the settings
stand when it reaches it (`currentPresentation()`), not as the value was emitted, and its custody read no longer
signs a missing row, which now counts as a change. Edits queued behind the seed or a publish had each minted the
newest frame again, because `broadcastProfile` signs the settings of its own moment. A boards-everywhere lab boot
binds a board just after start, then renames, and published four versions of every node's profile, two of them
identical. It now publishes two. The watcher also drops `distinctUntilChanged()` for `conflate()`: distinct
compared values as they were emitted, so a board bound while a publish signed it, then unbound, folded into one
value equal to the last, was dropped, and the profile kept naming the board. Each settings write now costs one
keyed custody read and a payload decode, and publishes nothing unless the settings differ from the frame. Pinned
by `MeshManagerTest.editsQueuedBehindAPublishAreCarriedByItsFrameAndNotFloodedAgain` and
`aRevertWrittenWhileAPublishSignsTheEditIsPublishedToo`.

## What was observed

Three mesh-in-a-box failures in two days, on both CI runners, that read as three different flakes:

- GitHub, 2026-09-16 09:14, `InternetPlaneLabTest.aRelayThatDropsEverySocketReconvergesOnAFreshConnection`:
  `bob's relay never converged the scope … local=11 spool=12`, and the per-row diff named it — one id,
  `profile-<bob>-1789549964760`, held by alice as `#283628698/1338673193` and by bob as
  `#294736774/-642508840`. Two byte-variants of bob's own profile under one publish stamp.
- GitLab, 2026-09-16 09:30, `AttachmentLabTest.anImageForSomeoneAwayIsCarriedWithItsBytesAndPulledFromTheCarrier`:
  `carol holds alice as Presentation(name=, version=1789550968964); alice is Presentation(name=Alice,
  version=1789550969000)`. Carol had alice's key and alice's *new* stamp, and still showed the blank name
  from the startup seed.
- The throttled repro loop (one core at 50 %, `InternetPlaneLabTest` + two other classes) reproduced the
  first shape on its first run — `aPlantedGarbageBlobIsQuarantinedOnceAndTheScopeKeepsWorking`, same
  one-id-two-hashes diff.

They are one bug. ADR 2026-09.y5f3 made `ownProfile()` the one *reader* of the profile bytes — the custodied
row for the current stamp, else a fresh signing custodied at once — but `broadcastProfile()` (the edit path)
still signed `currentProfileEnvelope()` on its own, and nothing ordered the two against each other.
`currentProfileEnvelope()` reads the version, the stamp, the prekey, the board and the name as five
separate DataStore reads, and `watchProfileChanges` writes the version and then the stamp as two separate
DataStore writes. On one slow core a concurrent `ownProfile()` — the startup seed's tail (`lab.node` returns
the moment the seed row exists, and the scenario renames straight after), the link-up push, the reachable
reflood, the LoRa beacon — reads the **old version, then the new stamp** (its version read predates the
bump; it is descheduled; both writes land; it resumes at the stamp read). `frame(newStamp)` is null because
the edit is still signing, so it signs `(newStamp, oldVersion, newName)` and custodies it; custody's
`INSERT … IGNORE` then makes the edit's own `onSeen` of `(newStamp, newVersion, newName)` a no-op, while the
flood has already carried the edit's bytes to whoever was linked. From there:

- a spool scope holds both variants and never converges (`store.has(id)` is true on both sides, so §9.6
  never accounts it either) — the first and third failures;
- every peer that gets the profile by custody or push gets the variant with the *old* version, and
  `handleProfile`'s LWW gate refuses it as not newer than the seed — the second failure, and in the field
  a name typed in the first seconds after meeting someone that stays blank on their phone until the 12 h
  republish mints a new stamp.

Two edits inside one clock tick had the same shape by a different route: the stamp was `clock()`, so the
second edit's frame reused the first's id and was a `SeenSet` duplicate everywhere.

## What changed

- **One lock, `profileLock`, around every signer of our own profile and every writer of its stamp.**
  `ownProfile()` takes it around `signOwnProfileLocked()` (the old body: build, read custody by id, else
  sign and custody). `broadcastProfile()` takes it around the version bump, the stamp write and the same
  signing, then floods the wire it got *outside* the lock through `originateWire` (which is what the
  reflood already used — `originateSigned(currentProfileEnvelope())` is gone). `republishProfile()` takes it
  around the staleness check, the stamp write and the signing. The version bump moved *into*
  `broadcastProfile()` from its two callers (`watchProfileChanges`, `rotatePrekeyIfDue`) so that the
  version, the stamp and the bytes are one step: a reader either holds the lock before the edit and reads
  the old version with the old stamp (whose row exists), or after it and reads the new pair (whose row
  exists). There is no third read.
- **The stamp is strictly monotonic**: `nextPublishStamp()` = `max(clock(), previous + 1)`, so two edits in
  one tick are two frames.

The alternative a reader reaches for first is a single-snapshot read of the settings — one DataStore
`data.first()` mapped to all five fields. It closes the mixed read but not the race: two signers can still
both find `frame(stamp)` null and sign, and `IGNORE` picks one while the other's bytes are already on the
air. The lock is what makes "sign once per stamp" true; the snapshot would only make the loser's variant
better-formed. Making custody the writer of record (custody first, flood what custody holds) is half of
this change and is kept — `broadcastProfile` now floods exactly the bytes it custodied — but alone it has
the same two-signer hole.

## What the throttled loop found next: a stamp nobody asked for

With the lock in, the same loop failed `RoomTickPlanesLabTest.aRoomTickReachesAnAuthorOnlyLoRaCanHearOnceTheRideHoldRunsOut`
on custody parity: bob held a **third** stamp of his own profile, 60 ms after the rename's, that alice never
got — minted after the link was cut, with only the board left to carry it. Every node in every lab log had
three stamps at boot (the old GitLab traces too: `-4679, -4681, -4693`); a boot with one rename should have
two. Instrumenting `watchProfileChanges` showed the third as an *edit* whose value was the stored state,
unchanged.

The cause is operator order. The watcher was `combine(five settings flows).drop(1).distinctUntilChanged()`.
Every one of the five is a `map` over the one DataStore, and DataStore re-emits every projection on a write
to **any** key — the seed's own stamp write, the contribution ledger's flush, a read-state stamp. So the
combine emitted the same presentation twice at start: `drop(1)` swallowed the first, and the second was the
first thing `distinctUntilChanged` ever saw. Every launch published a "changed" profile on the first settings
write after start: a fresh version, a fresh custody row (the previous lingering to its TTL), a sealed
`CTL_PROFILE` to every contact — at a moment nothing chose, which in the lab meant sometimes after the
`unlink`. `MeshManagerTest` never showed it because its rig stubs the five flows as separate
`MutableStateFlow`s, which dedupe exactly the re-emission that matters.

The watcher is now `combine(…).distinctUntilChanged()` with no drop, and its **first** value is judged rather
than discarded: after the startup seed has run (`seeded.join()`, so a stale stamp has been refreshed first),
`custodyPresentsOtherThan(live)` decodes the custodied frame for the current stamp (`ownProfile()`) and compares
the fields the frame carries — name and status normalized and capped, the avatar by its hash, the flag, the
board's number and key. Equal, and the common launch publishes nothing. Different, and it publishes once —
which closes a gap the phantom had been masking: an edit made **while the mesh was stopped** (the Profile
screen writes settings whether or not the service runs) met a stamp that was not yet stale, so the seed reused
the custodied bytes and the new name waited for the 12 h republish. The same branch covers an edit that lands
before the collector subscribes (its combine's initial value already carries it), which `MeshManagerTest`'s
`awaitProfileWatcher` used to have to step around.

Pinned by `MeshManagerTest.aSettingsWriteThatChangesNothingNeverRepublishesTheProfile` (the rig's status flow
is now a replaying `MutableSharedFlow`, so a test can re-emit an equal value the way DataStore does) and
`anEditMadeWhileTheMeshWasStoppedIsPublishedWhenItStarts` (stop, rename, start: one flood, a fresh id).

## What it costs

A `Mutex` acquisition on every profile signing and every edit: rare, and the critical section is a few
DataStore reads and one custody write. One custody read and a payload decode on the watcher's first value
per session start. Nothing under the lock takes the ratchet mutex or a DB transaction,
and nothing under it calls back into a signer (`onSeen` → `onCarriedFrame` wants a blob, it does not sign).
The lock does not cover the version bump's *readers* elsewhere (`broadcastSealedProfile` reads
`profileVersion` on its own) — that path seals the current settings per (peer, version) and is idempotent
on the receiver, so a mixed read there costs one extra sealed frame, never a variant.

Not covered: a stamp whose custody row was evicted or expired before a signer asks for it is re-signed
from live settings under the old id. Custody keeps profiles on the full TTL (24 h) and the republish
refreshes the stamp every 12 h, so that needs a quota eviction of our own profile row; it is the same
residual y5f3 had.

The trap: **don't sign a profile outside `signOwnProfileLocked`, and don't write `profilePublishedAt` or
`profileVersion` outside the lock.** A new caller that wants the current bytes reads `ownProfile()`; a new
caller that wants a fresh stamp goes through `broadcastProfile()` or `republishProfile()`.

Kept true by the lab: the profile half of `MeshLab.assertConverged` and `awaitDmScope`'s per-row custody
diff are what named this, and the throttled loop (one core at 50 %) over `InternetPlaneLabTest`,
`ProfileUpdateLabTest` and `RoomTickPlanesLabTest` and over the whole package is the regression bar (the
pre-fix tree failed two of its first six runs; the fixed tree ran the whole package four times and the three
classes ten times clean). On the lab's side, `LabNode.setDisplayName` / `setStatus` /
`setOpenToChat` / `setAvatar` now return only once the edit is published (the version moved and the frame
left the router), so a scenario cannot link or unlink inside the watcher's gap. `MeshManagerTest`'s profile
cases pin the id, the flood, the silent launch and the stopped-mesh edit.
