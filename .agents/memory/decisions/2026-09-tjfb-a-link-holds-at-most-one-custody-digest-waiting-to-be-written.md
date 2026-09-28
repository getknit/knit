---
id: "2026-09.tjfb"
slug: a-link-holds-at-most-one-custody-digest-waiting-to-be-written
title: "A link holds at most one custody digest waiting to be written"
date: 2026-09-27
topics: [mesh, custody, airtime]
---

# ADR 2026-09.tjfb — A link holds at most one custody digest waiting to be written

Status: Accepted (2026-09-27; `FramedLink.sendDigest` / `writePendingDigest`, counter `digestsReplaced`)

## What was observed

The knit-ios interop runs of 2026-09-27 (15:20 to 16:45) linked the Pixel 3 to knit-peer on BlueZ `hci1`, a
fresh identity each run, while the Pixel stayed linked to the other lab phones. At link-up the Pixel queued a
back-fill of about 780 frames, roughly 500 KB, and `hci1` carried it in about nine minutes at 0.5 to 2.5 KB/s.
Every one of the Pixel's digests was a 20,455-byte record (780 ids of mostly 22 characters). Only the link-up
digest reached the peer before the back-fill finished; after it, ten arrived in under six minutes, 33 to 44 s
apart, though the Pixel makes one a minute. A room post and a DM sent as custody matched took 392.9 s and
383.1 s to arrive: of the 211 KB the link carried while the post waited, about 204 KB was digests. Nothing
was lost. The frames were late, behind digests that were stale when written. Sent once custody had matched
for some minutes, posts took 1.0 to 6.3 s, and the 6.3 s one landed 0.1 s after the digest ahead of it.

The Kotlin says the same thing. `FramedLink` has one `Channel.UNLIMITED` queue that the writer drains in
order, and `MeshManager.reofferToNeighborsPeriodically` calls `ForwardSync.onNeighborAdded` for every linked
peer every 60 s, which calls `sendDigest(peer, store.liveIds(now))` without asking what the link already
holds. A frame is never queued twice on Bluetooth (`LinkCrossings`, ADR 2026-09.6nmy); a digest had no such
check. So a link busy for *n* minutes with a back-fill collected *n* digests, each carrying the id set of the
minute it was queued, and every frame sent after them waited for all of them. Between two phones at tens of
KB/s the same queue drains in seconds, which is why only `hci1` made it obvious; a weak link at the edge of
`PROMOTE_RSSI_FLOOR` is the Android case.

The mesh lab cannot show it: `LabTransport.sendDigest` hands the ids straight to the far node's flow, with no
queue and no byte rate, because the lab stands in at the `MeshTransport` seam and `FramedLink` sits below it.

## What changed

`FramedLink` keeps one digest slot per link (`pendingDigest`, an `AtomicReference`). `sendDigest` swaps its
ids into the slot and queues a `DigestDue` marker only when the slot was empty; when it was not, the newer
set has replaced the older one where it stood in the queue, and `digestsReplaced` counts it. The writer
empties the slot as it reaches the marker (`writePendingDigest`, in both `writeLoop` and the between-chunks
`drainFramesInto`) and writes what it found, so a digest sent while one is on the socket queues behind it as
a fresh record. Only the caller that swaps the slot from null queues a marker, and only the writer empties
it, so at most one marker is ever queued: one digest waits and at most one is being written.

It is safe because a digest is a snapshot of custody's live ids and the receiver answers each one on its own
with a diff (`ForwardSync.onDigest` keeps no state between digests): a newer snapshot says everything an older
one would, and a stale one only makes the peer re-serve frames we have since received, which our `SeenSet`
drops. The link-up digest is never dropped, only overtaken before it is written. On an idle link the writer
takes each digest as it comes, so the cadence and the bytes are what they were.

The alternative the proposal named, a pending flag with the ids read when the writer reaches it, gives a
fresher set and was not taken: the ids come from a Room read (`ForwardStore.liveIds`), so `FramedLink` would
need the store or a suspend supplier through `MeshTransport.sendDigest`, and the writer would run a database
query inside `drainFramesInto`, which is not a suspend function and runs between a paced file's chunks. The
slot needs none of that, and the set it writes is at most one re-offer tick old.

A digest still goes out between file chunks. It is what makes the peer serve us what we lack, so holding it
behind an 8 MiB file paced at 28 KB/s would stall the other direction for five minutes; with the slot, a file
costs one digest per tick, the same as an idle link.

Nothing on the wire moves: the `DIGEST` record, its JSON and the 60 s re-offer are unchanged, and the receiver
cannot tell a replaced digest from one never sent. It is local to the sender and not cross-platform law; the
iOS port mirrors it as its own choice.

## Measured on hardware

Same rig on 2026-09-27, 18:23 to 18:43: the Pixel 3 on this commit, linked to the other four lab phones, and
knit-ios `scripts/interop.py android-rings --peer-log debug --arrival-timeout 900` with knit-peer on `hci1`.
All ten steps passed, and the link held. The room post and the DM took **133.1 s and 124.4 s**, against
392.9 s and 383.1 s on the old queue. On the four phone links alone `digestsReplaced` stayed at 0 for three
minutes, and the Pixel 3, 7, 8 and 9 held the same custody fingerprint, so digests still flow and custody
still converges.

That is a third of the old wait, not the few seconds the slot would give on its own, and the logs say why.
`digestsReplaced` rose once a minute to 4 while the back-fill was going into the socket, then stopped at
18:36. From 18:40:20 to 18:43:19, six digests still reached the peer 33 to 37 s apart, and the post landed
directly behind the sixth. Those six were each written whole as its tick came round, with nothing waiting in
`FramedLink`: the writer had handed the back-fill's tail to the Bluetooth stack by 18:36, and the stack's own
socket buffer held it, plus every digest written after it, at least 120 KB, three to four minutes of `hci1`
air. `FramedLink` sees only its own queue. Once it has written a record, the record cannot be replaced, and
the socket gives no depth to read. The same buffer is why the file feed is paced (`BLE_PACE_BYTES_PER_SEC`).

## What it costs

**The Bluetooth stack's buffer is outside the slot's reach.** On a link much slower than that buffer is deep,
a frame still waits behind whatever the stack holds, and a link past its back-fill keeps it nearly full: one
20 KB digest a minute is most of what `hci1` carries in that minute. Closing that means keeping the socket
shallow — pacing frame writes as the file feed already is, at a rate learned from the link — or a smaller
digest record. The first needs a throughput estimate `FramedLink` does not have; the second is a wire
question. Neither is done here. Between two phones at tens of KB/s the same buffer drains in seconds.

A frame sent during a back-fill still waits for the rest of the back-fill ahead of it; that is a priority
question (live frames ahead of custody re-serves), not this one. On a link as slow as `hci1`, the one waiting
digest is still 20 KB, about 40 s of air at 0.5 KB/s once a minute; making it smaller means a different
record, a wire question.

`LinkCrossings` holds 1024 keys per link for ten minutes. A back-fill that takes longer than that on a link
slower than `hci1`, or a full 1000-frame custody plus a busy room, can let a peer's next digest queue a still
waiting frame a second time. This change neither causes nor fixes that.

**The trap:** the slot and the marker are one invariant. Code that enqueues a `DigestDue` without swapping the
slot from null, or writes a digest without emptying it, either strands the slot (no digest is ever queued
again on that link) or writes one twice. In `FramedLinkTest`,
`digestsSentWhileTheWriterIsBusyCollapseIntoTheNewest` and
`aDigestStillGoesOutBetweenFileChunksAndCollapsesThereToo` fail against the old queue, and
`anIdleLinkWritesEveryDigestItIsHanded` and
`aDigestAlreadyOnTheSocketIsNeverRewrittenAndTheNextQueuesBehindIt` pin what must not change. On hardware,
`digestsReplaced` on `…debug.STATE` climbs about once a minute per link while a back-fill runs and stays
still on an idle link.
