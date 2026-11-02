---
id: "2026-11.nsbf"
slug: a-block-also-holds-a-person-s-profile-presentation
title: "A block also holds a person's profile presentation"
date: 2026-11-01
topics: [moderation, privacy, security, profile, contacts]
---

# ADR 2026-11.nsbf — A block also holds a person's profile presentation

Status: Accepted (2026-11-01). Amends ADR 010 and ADR 2026-09.bts9 (where the block list is read), and closes the
blocking item ADR 2026-10.jgeg left out. #249, option 1, split out of #234. A paired change: knit-ios brings its ADR
2026-10.5wgg, which kept a blocked sender's profiles and photo fetches on purpose, into line (knit-ios #37). No wire
byte, capability, custody rule or vector moves.

**What was observed.** A block hid a person's messages, but their profile went on updating. Their new name, status
and photo showed wherever their row is drawn: their profile, group member lists, mentions and quotes in other
people's messages, and the Blocked list. Android also wrote "renamed" and "new photo" notices for them, and both
phones fetched their photo. The profile door cap (ADR 2026-10.jgeg) bounded what a stranger costs, and named this as
its own change: it is a product choice, and the maintainer chose to hold the blocked person's presentation.

**What changed.** From the moment of a block the phone keeps the person's name and status as they were, shows no
photo for them, writes no notices about them, and fetches and keeps no photo for them. Unblocking brings back their
newest profile and photo.

- **The decision comes first.** `InboundPipeline.handleProfile` reads the block list once, before `storeProfileRow`'s
  transaction (which may not read DataStore): a profile is blocked when its sender's node id is, or when the device
  tag it carries is (`caughtByDeviceTag`, continuity's unchanged condition). So a re-keyed phone that continuity
  catches presents nothing, not even on its first profile; continuity still records the block after an applied
  profile, as before.
- **Presentation is held like a stale one.** `storeProfileRow` keeps `name`, `status`, `avatarHash` and `openToChat`
  from the row (a new row starts empty, as any first row does) and never asks `avatarAdoptable` about the advertised
  photo. Everything else moves as for anyone: the pin, the prekey and its watermark, capabilities, protocol version,
  device tag, `lastSeenAt`, the LoRa board fields (attribution, which the block on a blocked contact's board post
  reads), and the presentation watermark `updatedAt`, which keeps peer-cap eviction and LoRa lookups as they were and
  lets the unblock's re-apply pass the version check. The follow-ups (`applyPresentationFollowUps`: notices, the
  reclaim, the pull) do not run; the flushes, the proof of key, the key cache, `onProfilePinned` and the parked release
  do. Nothing is counted: a blocked profile is not a drop.
- **The photo.** `pullRelayAvatarIfNeeded` never wants a blocked person's photo, `adoptAdvertisedAvatar` never points
  a blocked owner at one that lands (and releases the bytes when every owner is blocked), and `onAvatarReceived`
  refuses a blocked neighbour's push before it stores the bytes or creates a row.
- **On block.** `mesh/BlockListWatch`, started by `MeshManager` beside `OpenToChatWatch`, diffs the block list. Each
  id that joins gets `InboundPipeline.onBlocked`: the row's `avatarHash` is cleared (the face draws as the monogram),
  the in-memory pull is forgotten, and the bytes go through `BlobRepository.deleteIfUnreferenced`, which keeps them
  for a message, a group photo, another row, a carried frame or our own avatar. An outbound serve streams from its own
  temp copy (`MeshBlobStore.fileFor`), so a serve in flight is not cut. `BlobExchange` has no unwant, and none was
  added: a want made before the block runs until it lands or expires, and the adoption gate refuses it. The watch's
  first value is diffed against the list it last acted on (below). `sweepLocalStorage` (at start, before the orphan collection, and every
  10 minutes) runs `onBlocked` for every blocked id whose row still shows a face: the backstop for a block made while
  Knit was down, by a restore, the debug bridge, or between a profile's block read and its write.
- **On unblock.** Each id that leaves gets `InboundPipeline.reapplyHeldProfile`: the held frame (`peer_profiles`) is
  decoded and its name, status and flag applied in the profile's own order (an equal version applies, an older one
  applies nothing), with no notice and nothing else of a delivery — no custody, fan-out, cache, count, flush or
  parked release. The face follows the frame's advertised photo whether or not its presentation applied (contract
  revision 1): held and adoptable bytes become the face at once (a block undone before they were collected), and
  otherwise the photo is wanted with the profile ceiling from any holder, the person too when they are a linked
  neighbour, since their push memo already holds the hash and they will not push it again. A hash refused as a photo
  stays refused. With no frame held, nothing is re-applied.
- **The restore is durable** (contract revision 2). One want lapses with the fetch TTL and a restart, and a linked
  neighbour never pushes the photo again, so the unblock also records the face as owed (`BlockListStore.owedFaces`, a
  DataStore set of `"<nodeId> <hash>"`, no schema change). `InboundPipeline.rewantOwedFaces` runs at the end of every
  database re-arm (`MeshManager.rewantMissingBlobs`: the 60 s tick, a link-up, the start) and wants each owed face
  again, adopting held bytes at once, until the face shows, a newer profile takes over (the row shows another face, or
  the held frame advertises another photo), the photo is refused, or the person is blocked again. The watch diffs its
  first value against the list it last acted on (`BlockListStore.actedOn`, also DataStore), so a block or an
  unblock made while the mesh was stopped is acted on at the next start; a hook that throws leaves its id unrecorded,
  to be tried again then. Both ride a backup with the database they describe.
- **The frame is kept in the row's commit.** `handleProfile` records the verbatim profile (`peer_profiles`) in the
  same write transaction as the row, so an unblock never re-applies a frame older than a row that already moved past it.
  After the commit it reads the block list again: a profile held as a blocked one whose sender was unblocked meanwhile
  is re-applied, and one applied for a sender blocked meanwhile loses its face (`settleBlockMovedMidProfile`).
- **Unchanged.** Our own profile and photo still go to a blocked neighbour (`pushProfileTo`, `sendAvatarIfNeeded`):
  stopping them would let the blocked person notice (ADR 010; #249's option 2, rejected). The door, verify, custody
  carry and relay, `canCarry` and `KeyExchange.want` never read the block (ADR 2026-09.bts9). The sealed `CTL_PROFILE`
  still dies at `handleChat`'s block gate. Group and message photos are untouched. Custody still carries a blocked
  person's frames, so their photo may still be fetched or kept as a carried attachment; it is never adopted as their
  face.

The alternative a reader reaches for first is to drop a blocked person's profile at the door, as their chat is
dropped. It cannot be done: the profile is the proof of key a blocker serves to newcomers (ADR 2026-09.g64k), the
prekey a v2 session needs after an unblock, and custody's content (ADR 2026-09.bts9). Only its presentation is local.
Hiding the name itself, or drawing the person as "Blocked", is a separate decision.

**What it costs.**

- The name a blocked person had at the block stays visible: in groups, on their profile, in the Blocked list, and in
  mentions and quotes. Only their changes are held.
- Their face becomes the monogram at the block, even if they never change their photo.
- They get no notice for anything they changed while blocked, before or after the unblock.
- An unblock with no held frame, or with one older than the row's presentation, waits for their next profile for
  their current name and status. Honest phones republish: Android every 12 hours (`MeshManager.PROFILE_REPUBLISH_MS`),
  iOS every 12 hours too.
- Two edge cases where the phones differ on unblock, both because Android keeps no durable advertised hash (the gap
  #234 notes for peer photo re-wants): with no frame held, iOS restores the pre-block face from its kept hash and
  Android shows the monogram until the next profile; and when a sealed profile from before the block carried a
  different photo from the last cleartext frame, iOS restores the sealed one and Android the cleartext one, until the
  next profile. Neither touches an honest person in the common case: a frame is held once they have sent any cleartext
  profile, and their cleartext and sealed profiles carry the same photo.
- A photo transfer already running at the block may finish. It is never adopted, and its bytes go by the existing
  collection.
- A profile read as unblocked just before a block lands can still write its presentation: the re-check after its
  commit clears the face, and the name it wrote stays as the name at the block. The storage sweep backs that up.
- Two local differences from knit-ios (its ADR 2026-11.y793), neither on the wire: iOS keeps a blocked neighbour's
  pushed photo bytes when another want or row names that hash (never as their face), where Android refuses the push
  outright; and iOS withdraws its own photo-only want at the block with a `blobcancel`, where Android has no unwant
  and relies on the adoption gate.
- An existing quirk is unchanged: unblock removes the row's current device tag, so a tag that changed during the
  block stays in the blocked set.
- The trap: a new path that writes a peer's presentation or adopts a peer photo must read the block first, outside any
  transaction, or a blocked person's face comes back through it.

**What keeps it true.** `InboundPipelineTest` (`aBlockedPeersNewerProfileMovesEverythingButItsPresentation`, the
continuity, push, late-pull and sealed-gate cases, `onBlocked…`, the five unblock cases,
the mid-profile unblock and the owed-face lifetime cases), `BlockListWatchTest`, `MeshManagerTest`
(`aBlockClearsTheFaceAndReleasesItsBytes`, `anUnblockReappliesTheHeldProfile`,
`anUnblockMadeWhileTheMeshWasStoppedIsReappliedAtStart`, `theStorageSweepClearsABlockedRowsFace`), and two
`BlockAndRequestLabTest` scenarios: `aBlockHoldsTheBlockedPersonsNewNameAndPhotoUntilTheUnblock`, which ends in the
full oracle with custody identical on the blocker, and `anUnblockedNeighboursFaceComesBackAfterARestartAndALapsedWant`.

## Amendment (2026-11-01, #270): a block is recorded before its hook

`BlockListWatch` recorded the list it acted on only once every hook of a change had returned. A mesh stop or a process
death between a block's hook and that record lost the record: the next start diffed the block again, and an unblock made
while down in between was diffed against a list that never held the person, so it was never acted on (no re-applied
profile, no owed face). The watch now records a block before its hook (the list before plus the ids that joined) and an
unblock after it, as before. A block recorded and not yet acted on is the storage sweep's: it clears every blocked
person's face at each start and prune. An unblock has no such backstop, so it stays recorded only once acted on, and the
next start's diff runs it again. Rejected: running hooks and record under `NonCancellable` (closes a stop, not a process
death, and makes a stop wait on the hooks). A port with the watch wants the same order. Regression:
`BlockListWatchTest.aStopInsideABlocksHookKeepsTheBlockRecordedSoTheUnblockMadeWhileDownIsActedOn`.
