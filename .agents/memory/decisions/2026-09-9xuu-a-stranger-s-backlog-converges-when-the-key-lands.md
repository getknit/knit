---
id: "2026-09.9xuu"
slug: a-stranger-s-backlog-converges-when-the-key-lands
title: "A stranger's backlog converges when the key lands"
date: 2026-09-26
topics: [custody, mesh, convergence]
---

# ADR 2026-09.9xuu — A stranger's backlog converges when the key lands

Status: Accepted (2026-09-26)

**What was observed.** A device run of the iOS port saw custody on a Moto G never settle with the Pixel behind
it: 41 frames outstanding at every check, 16 of them parked. The Moto G carried the Pixel's frames and served
them to a peer that had never met the Pixel, which refused every one for want of the Pixel's key. The report
noted that Android's router does the same, and it does. The mesh-in-a-box lab reproduces the numbers exactly
(Alice posts 40 times to Bob and leaves; Carol, who has never met Alice, links to Bob): 41 `NO_SENDER_KEY`
drops, 16 held and 16 replayed, and Carol short 25 of Bob's custody, and 25 of Alice's posts, for the whole
ten-minute seen window. Once that window lapses the 60 s re-offer brings them through, so on Android this is a
delay of about ten minutes, not a loss. For all of that time the two digests disagree, so the Wi-Fi Aware cue
plane wants a reconcile that achieves nothing.

Three things line up to do it. `ForwardDao.liveRows` reads newest-received first, so `ForwardSync.onDigest`
served a sender's profile (usually the oldest row it holds of theirs) *after* all of their chat. The lab's
trace has Alice's profile at frames #130–131, behind her 41 chat frames at #89–129. `PendingInbound` parked
at most 16 frames per sender. And `MeshRouter` marks an id seen before `verifyInbound` runs, so every frame
the park turned away was deduped on each re-serve until the window lapsed. On Bluetooth, `LinkCrossings`
also kept the carrier from re-writing it to that link at all.

**What changed.** Two things, each pinned by one `StrangerBacklogLabTest` scenario:

- `ForwardSync.onDigest` serves `profile` frames ahead of everything else (a stable sort, so the rest keeps
  the store's order). When the carrier holds the author's profile, the newcomer pins it before the first
  post and refuses nothing.
- `PendingInbound` parks a carrier's whole custody quota for one sender (`MAX_PER_SENDER` 16 → 200,
  `ForwardRepository.DEFAULT_MAX_PER_SENDER`), under a new global byte budget (`MAX_BYTES`, 4 MiB) beside a
  larger frame cap (128 → 512). When the carrier no longer holds the profile (the per-sender quota evicts a
  chatty sender's oldest frame, which is its profile), the key comes by `keyreq` and the whole backlog is
  waiting to replay.

**The alternative, and why not.** The report suggested, and the first cut built, keeping only the ids of
the frames the park turned away, then, when the key landed, reopening them at the router
(`MeshRouter.reopen`) and re-advertising our digest to the carrier. It is lighter on memory and does nothing
on Bluetooth, the plane the iOS port runs on. `LinkCrossings` stops the carrier writing the same signed
frame to one link twice inside the seen window, on the stated invariant that the far end's router would drop
it anyway. A reopen breaks that invariant from the receiving end, which cannot reach the sender's memo. The
lab caught it: reopened 25, served 0. Making it work would have taken a per-frame bypass through
`MeshTransport.send` or a new wire signal. Holding the bytes needs neither, and it works the same on every
plane.

**What it costs.** Memory, bounded and lower than before where it matters. The old caps allowed
128 × the link's 512 KiB payload ceiling of unauthenticated bytes (tens of MiB); the byte budget caps it at
4 MiB whatever the frame count, where 200 ordinary text frames take well under a megabyte. The per-sender
cap is still a claim an attacker can mint, so the global caps remain the real bound, oldest-first as
before. An attacker who fills them evicts honest parked frames, and the victim is back to the old
ten-minute delay, not a loss. The replay on a pin can now run up to 200 frames on the inbound coroutine,
the same work as the burst that brought them.

**What it does not cover.** A backlog larger than the park, or one evicted by an attacker's flood, still
waits out the seen window. So does a sender whose key arrives through a door that never releases the park
(a contact card import). `MeshManager` injects the custody quota as the park's per-sender cap
(`ForwardRepository.DEFAULT_MAX_PER_SENDER`; the mesh layer keeps no import of the data layer, as
`AttachmentDeferPolicy`), and `PendingInboundTest` pins the class default to it, so the two cannot drift.
The trap is the byte budget: a much larger quota needs it to follow, or the tail of every served backlog is
ten minutes late again. `StrangerBacklogLabTest` fails against either half reverted, and
`PendingInboundTest` pins the defaults and the byte budget. The scenario's custody check is "Carol is short
nothing Bob carries", not full parity. Its seam drops Bob's copy of the profile by hand, inside a seen
window that then dedups Carol's copy of it; in the field the quota evicts that profile on every node
alike.
