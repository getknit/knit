---
id: "2026-11.4ckq"
slug: a-peer-s-advertised-photo-is-stored-at-once-and-re-wanted-from-the-database
title: "a peer's advertised photo is stored at once and re-wanted from the database"
date: 2026-11-01
topics: [blobs, contacts]
---

# ADR 2026-11.4ckq — a peer's advertised photo is stored at once and re-wanted from the database

Status: Proposed (draft, 2026-11-01). Fixes knit-next #258; closes #253 and heals #256. Applies ADR 2026-09.nxcq's
decided/shown split to peers, under ADR 2026-09.ptv8's database re-arm, ADR 2026-09.4tx5's serve memo and ADR
2026-10.g24x's "a linked author's HAVE decides". Leaves ADR 2026-11.nsbf's owed faces as they are. Receiver-local: no
wire byte, capability, custody rule or vector moves. Schema: DB v30.

**What was observed.** A contact's new profile photo that failed to arrive the first time was never asked for again.
Found by reading the code (#258), not reproduced on a device; the lab scenarios below reproduce each way in.

- **The push.** `MeshManager.sendAvatarIfNeeded` memoized `sentAvatarHashes` whatever `sendFile` returned, for the life
  of the process and across link flaps on purpose, and `InboundPipeline.pullRelayAvatarIfNeeded` never wanted a linked
  neighbour's photo. One lost push (a link cut mid-file, a Wi-Fi Aware data path closing, `sendFile` finding no route)
  left that receiver without the photo for good.
- **The pull.** The want's owner was `advertisedAvatars`, an in-memory map. A want that reached its fetch TTL, or a
  restart before the bytes landed, was never re-armed: the profile that advertised the photo is a `SeenSet` duplicate
  and a custody `has` from then on. `rewantMissingBlobs` covered attachments, carried frames, group photos and the
  faces an unblock owes, never an ordinary peer's photo. A sealed `CTL_PROFILE`'s photo went the same way.
- **#253** was the push case in one order: the push landed before the rename's profile, which advertised no photo,
  cleared the face and deleted the bytes. **#256** was a face naming a hash the start-up orphan collection deleted
  between the bytes' insert and the row's write; a face that looked held was never wanted again.

**What changed.**

- **The row holds the decided photo.** `peers.advertisedAvatar` (and `advertisedKnown`), beside the shown
  `avatarHash`. Every profile writer records it in the same write and under the same presentation watermark: the
  cleartext profile (`storeProfileRow`), the sealed `CTL_PROFILE` (`applySealedProfile`, in the ctl commit) and an
  unblock's re-apply (`reapplyRow`). No photo advertised clears it. It is written whether or not the sender is blocked,
  since the decided hash is not presentation; ADR 2026-11.nsbf's "never want or adopt while blocked" stays at the want
  and adopt sites. Existing rows read unknown, and the start-up pass `backfillAdvertisedAvatars` fills each from its held
  profile frame (`peer_profiles`) when that frame is as new as the row's presentation. A row a newer sealed profile
  moved past stays unknown until its next profile. It is never backfilled from `avatarHash`: that marks exactly the
  stranded faces as settled.
- **The want is the row.** `advertisedAvatars` is gone. A landing blob is adopted by `PeerDao.awaitingAvatar` (rows
  advertising it and not showing it), the block list read outside the transaction and the column compared again inside
  `pointPeerAtAvatar`'s (#98). A late arrival of a photo the row moved on from points nobody back at it.
- **A push is adopted only by a row that decided on it.** `onAvatarReceived` points the face at the push when the row
  advertises that hash, does not know what it advertises, or does not exist. Otherwise the bytes stay and no face moves,
  and the profile naming them finds them held (`avatarAdoptable`). #253's order now settles without a second transfer.
- **A refusal is durable.** A face screening refused goes through `BlobRepository.dropRefusedFace`: the bytes go and
  the verdict stays. `deleteIfUnreferenced` and the start-up collection keep a verdict, and a refusal, while a row
  advertises the hash, and never keep the bytes for it, so a block, a refusal and the ceiling's release still free them.
  An over-ceiling refusal (#234) is kept in a `photo_refusals` table and seeded into the session at every start: a
  held copy found over the ceiling refuses the hash (empty `holder`), while a holder whose stream or size answer passed
  it is kept as a mark on that holder alone (`PartialBlobs.onMarked`, `BlobRepository.markOverCeiling`, at most 16 a
  hash), since a neighbour's claim must never refuse a contact's photo for everyone. Without both, the re-arm would fetch, refuse and delete the same photo on every tick and every restart. A
  profile moving its advertised photo off one that never became the face releases that hash's leftovers
  (`releasedByProfile`).
- **The database re-arm wants faces, bounded.** `MeshManager.rewantFaces`, after the attachments' batch, wants the
  advertised photo of each peer whose bytes are not held: contacts and group co-members, verified peers, peers with a
  conversation or a request, and anyone heard within a day (`lastSeenAt`), at most `FACE_REWANT_CAP` (64). Every peer
  with a reason of its own (an anchor, verified, a conversation) goes ahead of one only heard lately, then newest heard
  first: ordered by `lastSeenAt` alone, a crowd of strangers heard today (a busy room, a cheap set of identities) took
  every place and a contact not heard for two days was never asked for again (the round-1 review's blocking finding).
  No stranger sub-cap: the contacts go first, and the strangers only fill what is left. It skips blocked peers and refused photos. The predicate is "not held", not "not shown", so a
  #256 face is caught: its dangling face is cleared first (it draws the monogram anyway, and the landing is then a row
  write that redraws it). `BlobExchange.wantFaces` wants them in one batch with the photo ceiling, each
  fetch only (budget 0, no deadline): it asks whoever is linked and never asks anyone to pull it through, so 64
  re-armed roots cannot fill the neighbours' want tables with echoes (#195), and one nobody in reach holds backs off as a
  dead want does. A face already wanted (the profile's own pull, with the full budget) keeps its budget, deadline and
  age, so it runs its course and lapses with the fetch TTL; the next re-arm makes it again, fetch only. A face re-arm lifts a fast-only flag only on a want photos alone make (its
  ceiling still set): a peer advertising as its photo the hash of an attachment held to Wi-Fi Aware over the user's
  limit (ADR 2026-10.js2p) must not have that attachment fetched over Bluetooth, uncapped (the stage-1 review's finding).
  A sealed profile's own photo, which custody would hold to Wi-Fi Aware behind its unopened frame, is
  handled at the source instead: every carried hash whose frames' senders all advertise it as their photo
  (`MeshManager.sendersOwnPhotos`) is wanted as a photo (`wantFaces`, under `FileIntake.MAX_AVATAR_BYTES`, asked of its
  sender alone while linked), never as a carried attachment, so only a frame's own author moves it, and it cannot push
  a large file past the user's limit by advertising it as its photo (the stage-2 review). Accepted and bounded: such a photo loses custody's
  pull-through budget and deadline (it is fetch only, as every re-armed face), it gives its place up once the want
  table is three-quarters full, and, like every face, it is not wanted before the start's sweeps are done
  (`startSweepsDone`), so an earlier session's refusals and holder marks are seeded first. The chaos sweep found a sealed-only
  photo stranded behind that hold after a restart. Farther reach
  stays the profile-time pull's. No face is wanted before the start's sweeps are done (`startSweepsDone`): a link-up can
  run ahead of them, before the earlier sessions' refusals and marks are seeded and the advertised photos backfilled. A face never takes
  the last quarter of the want table (`MAX_FETCHING`, 256) and gives its place up to any other want of ours
  (`BlobExchange.makeRoom`), so a crowd of faces never evicts an attachment's want for the next re-arm to mint again (#195's
  churn). A stranger outside the set keeps its once-per-profile pull.
- **A linked author is asked alone.** The re-arm runs at the link-up, beside the author's own push, and a push in the
  stack buffer is neither `fileInFlightTo` on the author's side nor arriving on ours (#121). A face is wanted with
  `pushedBy` set to its person (`BlobExchange.pushedPool`). While a radio links them, they alone are asked, until they
  answer NONE, their link goes, or the floor: their serve memo's window (`SERVE_MEMO_MS`) from the first step on this
  link, never while they stream to us, so a push queued behind another file waits out that file's measured ETA
  (`fileArrivingEtaMs`, the step's streaming wait). Never on the 5 s first-ask grace. Past the hold the round is the
  ordinary one: the author is asked first again, its push's memo lapsed, then everyone. A contact's direct link holds nothing here, since its peer is asked last for the bytes (ADR 2026-10.a5yj). A fetch
  already standing on another linked holder when the author links keeps that holder in the pool and runs on; the hold
  starts once it ends. Dropping it made the fetch read the holder's link as gone and end with no `blobcancel`, and its
  queued copy came beside the author's (the round-1 review's finding).
- **The author's push stamps its serve memo.** `sendAvatarIfNeeded` claims the memo key `hash|peer`
  (`BlobExchange.claimPush`) before it sends. A fresh ask inside it is refused and answered SENDING, as for a copy we
  served (4tx5's in-flight rule), and one after it is served: a lost push comes back on the next round. A push that
  found no route forgets its stamp. A copy already on its way (the peer's ask was served first, or our push is still
  queued) is not pushed again. `sentAvatarHashes` stays as it was, with its KDoc corrected.

The alternatives, from the issue: re-wanting from the held signed profile on every re-arm (no schema, but a decode per
peer per tick, no cover for a sealed `CTL_PROFILE`, and a frame that can be older than the row), and fixing the sender
alone (it closes only the no-route half, and a flap re-pushes every photo). Waiting for the author on the 5 s grace, as
any other round does, was the design pass's blocking finding: every holder asked after it serves a copy while the push
lands.

- **An owed face (ADR 2026-11.nsbf) is not shown while its bytes are gone.** `rewantOwedFaces` settles an entry when the
  row shows a face, and a face naming the owed hash whose bytes the start-up collection took (#256) used to count: the
  entry settled, the re-arm cleared the dangling face, and nothing wanted it again for a neighbour outside the bounded
  set (not a contact, not heard for a day). Now only a face whose bytes are held, or another hash a newer profile
  brought, settles it (the stage-2 review; `BlockAndRequestLabTest`'s failing seeds).

**What it costs.**

- One schema bump (v29 → v30, `MIGRATION_29_30`): two columns and an index on `peers`, one table keyed by (hash,
  holder) carried in a backup beside `blob_verdicts`.
- A sealed `CTL_PROFILE`'s releases (the face it cleared, the advertised photo it moved off) run after the ctl commit
  (`CtlOutcome.released`), never inside it: the release reads DataStore and the commit holds the ratchet's lock. The
  ratchet commit itself is unchanged; a crash between the two leaves the bytes to the next start's collection.
- A bounded query and an `UPDATE` (the dangling faces) per re-arm, and up to 64 extra wants in the table.
- A verdict row and a refusal row kept per refused photo while a peer advertises it.
- Up to a 45 s wait on a linked author whose push was lost with the link still up, then one more serve from it.
- An accepted transient: a row a push created, then an older relayed profile, can show the older photo until the newer
  profile lands; it converges.
- `lastSeenAt` is sender-asserted presentation (ADR 2026-10.k8ge). Here it only chooses which strangers' photos are
  re-wanted, a local bound; never a presence tier, a route or custody.

What it does not cover:

- **Knit for iOS** may share the bug; it is receiver-local, so its own session files and fixes it. No wire change.
- ADR 2026-11.nsbf's owed faces (`owedFaces`, `rewantOwedFaces`) still run beside this. Both running is harmless, and
  folding them is a follow-up that needs a migration of the owed entries.
- A push kept unadopted for a profile that has not landed yet is an orphan to a start-up collection still pending
  (#256's window, and the advertised column cannot cover it: nothing advertises the hash yet). The bytes go, and the
  re-arm asks the author for them again once its push's memo lapses: one more transfer, no strand. The chaos sweep found
  it; `PeerFaceLabTest` waits for the start-up collection so its scenarios test what they name.
- A sealed profile's face and a cleartext one that disagree on an unblock still follow the held frame first
  (nsbf's edge case). The next re-arm then wants the advertised one, so it converges.

**The trap for the next person:** a renderer must never read `advertisedAvatar`, and the shown `avatarHash` must stay
"bytes held and screened". A surface that draws the advertised hash shows bytes that may not be here, the #108 trap
for peers. And a new path that adopts a peer photo goes through `pointPeerAtAvatar` with a `FaceGate`, or a late or
pushed photo can point a face at a hash the row moved on from.

Kept true by:

- `PeerFaceLabTest`: a contact's face ahead of 70 strangers heard today, a carrier's standing fetch kept when the
  author links, a querying asker held on an author that cannot answer (Carol never queried), a photo only a sealed
  profile named fetched after a restart, a push lost with the link up, a cut push fetched from a holder that links later, a restart between
  the profile and the bytes, #253's order, a linked author's push in the stack buffer (one copy per (hash, link)), a
  refused face across ten re-arms, and a crowd of more than 256 wants. Each ends in `assertConverged`.
- `BlockAndRequestLabTest`, with the #253 rename wait removed. Its #256 scenario keeps the `startSweepsDone` wait:
  without it the owed face came back over the ticks, but the full oracle failed 2 runs in 200 afterwards on a cause not
  pinned. #256's heal is kept true by `PeerFaceLabTest`'s restart scenario (which reaches the state on its own) and the
  dangling owed-face and dangling-face unit cases.
- `InboundPipelineTest`'s advertised-photo cases, `BlobExchangeTest`'s face cases (`pushedPool`, `wantFaces`,
  `claimPush`), `MeshManagerTest.theDatabaseReArmWantsTheAdvertisedFacesOfThePeersItHasAReasonToShow`,
  `PeerDaoTest`, `BlobRepositoryTest` and the 29 → 30 case in `KnitDatabaseMigrationTest`.
