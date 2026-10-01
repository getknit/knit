---
id: "2026-09.nxcq"
slug: a-group-s-decided-photo-is-stored-at-once-and-the-shown-photo-waits-for-its-byte
title: "A group's decided photo is stored at once and the shown photo waits for its bytes"
date: 2026-09-30
topics: [groups, mesh, convergence]
---

# ADR 2026-09.nxcq — A group's decided photo is stored at once and the shown photo waits for its bytes

Status: Accepted (2026-09-30). Fixes knit-next #108, found while porting groups to Knit for iOS (knit-ios ADR
2026-09.n393, companion change A6).

**What was observed.** `InboundPipeline.groupPhotoDecision` took a newer group photo by moving the row's
`photoUpdatedAt` at once but kept the old `photoHash` until the new bytes were local, the peer-avatar rule
("a stored hash always renders"). Every frame the member sent in that window rebuilt `GroupInfo` from the row,
so it advertised the old hash at the new clock. A member already showing the new photo read that as a change
(hashes differ, clocks equal, and a tie went to the incoming photo) and switched back, at once if it still held
the old bytes, or after pulling them again. Each switch upserted the `gphoto:<gid>:<hash>` notice, which moved
the line to the end of the thread and credited whoever sent the frame. Until then every re-assertion of the
new photo was also a "change" that re-posted its line. A member whose screening refused the new photo never
adopted it, so it advertised the stale pair for as long as it stayed in the group, and re-pulled the photo
on every frame. The pull is hop by hop and pull-only, while the roster rides every chat frame and custody,
so the window is the common case, not an edge. Reproduced in the lab on `cb8bc4b5`. Bob holds Alice's new
photo's header but not its bytes, and Carol shows the new photo. Bob sends one message. Carol's old-photo line
moves to Bob's send time and names Bob, and Carol switches back to the old photo.

**What changed.** The row holds two photos (DB v18, `MIGRATION_17_18`, backfilled `photoShownHash = photoHash`):

- `photoHash` is the **decided** photo, which goes on the wire and wins last-writer-wins on `photoUpdatedAt`. It
  is taken the moment it is heard, bytes or no bytes, so every frame carries what its sender decided, the one
  condition under which last-writer-wins converges.
- `photoShownHash` is the photo that **renders**. It follows the decided photo once its blob is local and, with
  content filtering on, not flagged. Until then the previous photo keeps showing. Every surface reads it:
  `ConversationTitles`, the chat header, requests, group details, profile, the notification shade.
- **A clock tie keeps the held photo** (`photoWins`). The only frames with an equal clock and another hash are
  the stale pair an older build still sends while it pulls, and two members setting a photo in one
  millisecond. Only the first happens, and the old `>=` handed it the win. With nothing held, a tie still takes,
  so a hash sent without a clock is adopted.
- **The want is the row.** `advertisedGroupPhotos`, the in-memory map the arrival hook matched against, is gone.
  - `settleArrivedGroupPhoto` reads `GroupDao.awaitingPhoto(hash)` and shows the photo on every group that
    still decides on it.
  - `MeshManager.rewantMissingBlobs` asks for `photoHashesNeedingFetch()` at startup, at link-up and on the 60 s
    tick (ADR 2026-09.ptv8).
  - A frame re-asserting the decided photo re-arms the pull too, and shows bytes that came by the spool.
- **A refusal is durable.** A photo screening flagged stays decided (the group keeps advertising it), but its
  bytes go through `BlobRepository.dropRefusedGroupPhoto`, which keeps the verdict. `countByPhotoHash` counts
  both columns, so the verdict outlives `deleteIfUnreferenced` while the row names the hash. Every re-pull path
  skips a flagged hash while filtering is on. The verdict goes when the group moves on to another photo.

The alternatives, from the issue:

- **Advertise the pending pull from memory.** This lost its fix at every restart.
- **Break ties on the hash.** This is a total order, but an older build's stale pair then wins half the time,
  and a new build that takes it re-advertises the old photo at the new clock, so the whole group can converge
  back on the photo it replaced.
- **Store the decided hash in the one column and draw the member cluster until the bytes land.** No migration,
  but nothing changes when the bytes land: `GroupAvatar` remembers its failed load per hash, and every screen's
  StateFlow drops an identical row. So six view models would each have needed a blob-presence subscription,
  and the photo would have blinked to the cluster on every change. The second column makes the landing a row
  write, which redraws everything with no new plumbing.

**What it costs.**

- One schema bump.
- An `isImageFlagged` read per group frame while a decided photo is not shown yet.
- A `NOT IN (SELECT hash FROM blobs)` query on each re-ask point.
- A verdict row kept for each refused photo while its group still advertises it.
- Two members who set different photos in the same millisecond stay split until the next set.

What it does not cover:

- **Older Android builds** still send the stale pair while they pull. New builds ignore it, but old ones still
  switch on it until they update.
- **Knit for iOS** stores the decided hash at once already, but breaks ties with `>=`
  (`GroupRoster.swift:62`). A6 should mirror `photoWins`, keeping the nothing-held exception (knit-ios#1).
- **A group photo pulled over the spool plane** is still never screened (`ScopeSync` saves it through
  `scopeBlobs().save`); that is a separate gap (#109).
- **The notice's subject** is still the sender of the first frame that brought the change, which need not be
  the member who set the photo.

**The trap for the next person:** `toGroupInfo` must keep reading `photoHash`, and a renderer must never read
it. A surface that draws `photoHash` shows a hash whose bytes may not be here, and a sender that advertises
`photoShownHash` brings #108 back.

Kept true by:

- `AttachmentLabTest.aMemberStillPullingANewGroupPhotoNeverSwitchesTheOthersBack`: the scenario above,
  ending in `assertConverged`.
- `AttachmentLabTest.aGroupPhotoPullLostToARestartIsAskedForAgain`: the database re-arm; it fails with the
  rewant line removed.
- `InboundPipelineTest`'s photo cases (the tie, the stale pair, the re-arm, the refusal, a superseded arrival).
- `GroupDaoTest`, `BlobDaoTest`, `BlobRepositoryTest` and the 17 → 18 case in `KnitDatabaseMigrationTest`.
