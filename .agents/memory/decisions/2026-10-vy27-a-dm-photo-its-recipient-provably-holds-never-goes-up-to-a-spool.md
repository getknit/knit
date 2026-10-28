---
id: "2026-10.vy27"
slug: a-dm-photo-its-recipient-provably-holds-never-goes-up-to-a-spool
title: "A DM photo its recipient provably holds never goes up to a spool"
date: 2026-10-17
topics: [spool, attachments]
---

# ADR 2026-10.vy27 — A DM photo its recipient provably holds never goes up to a spool

Status: Accepted (2026-10-17; `AttachmentDeferPolicy.proved`, `ScopeSync`'s `LocalPass.PROVED`,
`AttachmentHolderEntity` / `AttachmentHolderDao` (DB v22), `BlobExchange.confirmHeld` / `onPeerHolds`,
`MeshMetrics.spoolAttachProved`, `AttachmentDeferPolicy.PROOF_WAIT_MS` (revision 2). Amends ADR 021. No wire change: the holder query is ADR 2026-10.6e5t's, asked of a
hash we hold. Spec: `docs/SPOOL_PROTOCOL.md` C-9.5-6, C-9.5-8, §9.5's reference-client paragraph, §10.1)

The maintainer's rule of 2026-10-17: any file sent should try the radios first, and a DM photo a live-linked
recipient got "shouldn't even go up to the spool". ADR 021's gate could not say that. It is a deferral by
construction: every signal it reads either lapses (the sighting) or stands in for the bytes (the ack, the row's
plane, the arrival memo), so it holds a photo back while the peers are together and uploads it the round after they
part. A pair that walks away from a café after swapping photos over Bluetooth still sent every one of them to the
relay, fifteen minutes late. The bytes were already where they needed to be; the upload served nobody, because a DM
scope has exactly two members and both held them.

**What was missing is a fact about the bytes, not a better stand-in for them.** ADR 021 rules out the obvious
candidate, and the reason still holds: a delivery ack says the frame arrived, never the bytes, because an attachment
travels by a separate demand-driven pull and "acked but never fetched" is a real state. Gating on the ack for good
would strand exactly the photo the spool exists to rescue. So the proof here is the bytes themselves, per recipient
and per attachment hash, learnt over a short-range radio, and only that:

- **On the recipient** it is its own radio arrival (ADR 2026-09.e8yw), once the store has taken the bytes — after
  the hash check, not before. `MeshManager.watchIncomingFiles` writes it when `BlobExchange.onReceived` reports the
  store took them. The in-memory `noteRadioArrival` stays ahead of the store as e8yw requires; the two differ in that
  the memo is a deferral and this record is for good, so it must not be written for bytes that then fail their hash.
- **On the sender** it is the recipient's own statement, over a live short-range link, that it holds the bytes.
  `BlobExchange` could not tell a complete, hash-checked serve of a blob to a peer from a partial one: the serve memo
  is stamped at enqueue, the link's feed end and `blobprog`'s `held` are byte counts, and the only hash check on the
  path is the receiver's store. So the sender asks. ADR 2026-10.6e5t's holder query already exists, already reaches
  every capable neighbour, and its responder answers `HAVE` or `SENDING` only for bytes `BlobStore.servable` holds —
  screened and hash-checked. Asking it about a hash we hold rather than want is legal under 6e5t (a query serves,
  pulls and stamps nothing), so no frame, field or responder rule changes; the iOS port's responder answers the
  same way, so an iPhone recipient can give the proof too. The policy asks in each round where a send of ours meets
  a fresh sighting (`AttachmentDeferPolicy.askHolder`). `HolderAskBatch` gathers a round's asks for 200 ms and sends
  one frame per peer, because the responder's per-asker budget is 16 frames at a burst and our own want queries
  share it; the exchange floors each (hash, peer) at `BlobExchange.PROOF_FLOOR_MS` (10 s, under the 15 s re-look of
  a deferred attachment so every round asks once), sends only to a linked neighbour whose link carries
  `CAP_BLOB_HAVE`, and keeps its proof queries apart from its want queries, so neither answer can reach the other's
  path. A `HAVE` or `SENDING` over a short-range plane, answering that very query, writes the record
  (`onPeerHolds`). A no, no answer, or an answer over LoRa leaves ADR 021's rule in charge.

Both land in one table, `attachment_holders` (hash, holder, plane, provedAt), and one query reads it from either end:
`AttachmentHolderDao.heldInPair` matches a holder that is the peer on a message of ours, or ourselves on a message of
the peer's. The message is required, which is what keeps avatars and group pictures uploading as they always did
(they write no message row), and the holder is required, which is what keeps a photo forwarded under the same sealed
identity to a third person (ADR 2026-10.mptm) needing that person's own proof — the "one hash names one authored row"
shortcut ADR 2026-09.xmte leaned on no longer holds since mptm, so the key is per holder. `ScopeSync.passLocally`
asks `proved` ahead of the files gate and the deferral and settles the attachment: no `ahave`, no `aput`, no retry
owed. It does not lapse when the peers part and it does not end at last call: C-9.5-7 protects a push that is still
owed, and none is.

**Why it persists.** A deferral is in memory because losing it only means deferring less. A proof that is lost means
uploading the very photo it was kept to spare, and a phone restarts far more often than a photo leaves custody (24 h).
So it is a Room table (DB v22, `KnitMigrations.MIGRATION_21_22`), pruned with the blob in `BlobRepository`'s GC, and
carried in a backup like `blobs`: a restore is a move, and the peer still holds what it held.

**The alternatives a reader reaches for first.** A completion report from the receiver — a final `blobprog`, or an
unsolicited `blobhas` once the hash checks — is a new meaning for an existing frame, and it goes to whoever served
the file, which on a multi-hop pull is a carrier, not the author. The author asking the recipient directly needs no
new meaning and reaches the one party whose holding matters. Proving the serve on the sender alone (the link fed every
byte) is not hash-checked and says nothing of a copy that then failed the receiver's check. And holding a photo back
while a proof query is outstanding would add latency to every send whose peer cannot answer; the order is to change
nothing without proof.

## What it costs

On the wire, one small `blobhave` per deferred photo of ours per round (15 s) while its recipient is linked and the
spool plane is on — none at all with the plane off, since the question is asked only from a spool round. On disk, one
short row per proved photo. At a spool, an upload that never happens tells it less than a late one: no size and no
time for that attachment. The absence is a weaker hint of its own — a DM scope whose frames grow with no matching
upload may have had its members together when they were sent — which §10.1 now prices beside ADR 021's timing signal.
Frames stay unconditional (C-9.5-9), so neither hint generalises.

**A known limit, accepted for this change.** The sender's proof is the recipient's word, and the word is unsigned:
`blobhas` carries no signature and a link's HELLO is not authenticated. A device on a link that claims the
recipient's node id, in the sender's radio range when the photo is sent, can answer `HAVE` and keep the photo off the
spool. Nothing leaks, but the photo then has no relay copy: the frame still goes up, so the recipient knows the photo
exists, and it gets the bytes only when the two next meet over a radio — and never, if they don't. Closing it takes a
signed answer, an additive wire change on both ports under `docs/WIRE_COMPAT.md`, and is out of scope here; spec
§10.4 records it. The recipient's own proof is unaffected: it is the bytes, hash-checked. A recipient that pulled
the photo off a spool also answers `HAVE`; that is harmless here, since the spool then holds it already.

What it does not cover: groups (no per-member receipt matrix, ADR 021 bound 3; one member holding a photo says nothing
of another), frames, and a photo whose peer is not nearby, which uploads at once as before because its frame goes to
the spool. A photo whose recipient's answer comes back only after the peers parted, or never (a build without
`CAP_BLOB_HAVE`, a link that dropped), uploads after the sighting window exactly as ADR 021 says, or, with nothing
to defer on, after one proof round (below).

## Revision 2: one proof round before a push

The device trial (Pixel 3 → Moto G, 2026-10-17) found the gap the first version left open. A photo sent while the
only relay took no attachments had its receipt come back over that relay, so the sender held no radio ack for it.
When a relay that takes attachments connected, the grace was long gone and the pass had nothing to defer on. It asked
the recipient for proof and pushed in the same pass, and the recipient's `HAVE` landed one round later. The proof had
been one round trip away over a live link.

So a send of ours that has nothing else to defer on now holds one proof round when its peer is on a live short-range
link that answers the holder query (`canAskHolder`, the same test `confirmHeld` sends on).
`AttachmentDeferPolicy.PROOF_WAIT_MS` (10 s, the proof floor's scale) bounds the hold from the first pass that held,
in memory per (hash, peer). The answer over a link comes in a second or two. A yes settles the photo on the next
pass. A no or silence lets it push on the first pass after the wait. The wait is shorter than the spool's 15 s
re-look of a deferred attachment, so that is the very next pass: a delay of one round, never a refusal, as C-9.5-6
requires. A no does not end the wait early. At the spool's pass granularity it makes no difference, and it would take
the exchange a second callback for nothing. A peer that is not linked, or that does not answer, changes nothing, and
neither does a photo that is not ours: the recipient's proof is its own arrival, not an answer. The hold is counted
as a deferral (`spoolAttachDeferred`).

The round also holds back the ticket ask on a relay whose uploads are locked. There the lock settles an upload before
the deferral is reached, and settling it wakes the ticket refresher, a free key's only reason to ask (ADR
2026-10.s3ep's order). So `ScopeSync.passLocally` asks `AttachmentDeferPolicy.holdsForProof` ahead of the lock: a
send of ours whose recipient is linked and answering is deferred, not locked, until its round ends. A yes settles it
proved and nothing is woken. A no or silence lets the scheduled re-look lock it, with one wake. ADR 021's ordinary
deferral keeps the order the ticket work gave it (the lock first), since a photo nobody can prove is an upload that
waits. `ScopeSyncTest` pins both outcomes on a locked relay, driven by the re-look itself. `AttachmentDeferPolicyTest`'s three revision-2 cases pin the rule.
`PhotoProofLabTest.aPhotoWhoseReceiptCameOverTheRelayWaitsOneProofRoundAndNeverGoesUp` replays the slipped photo: a
frames-only relay, the receipt pinned to it, then attachment support past the grace. The hold runs on wall time, so
the lab widens it to 30 s (`LabLimits.proofWaitMs`) rather than let a stalled node outlast it.

**The trap** is the order on the recipient: the record goes after the store write, the memo before it. Swap them and a
copy that fails its hash becomes a permanent "never upload". `AttachmentDeferPolicyTest` pins the rule (proof across a
restart, an ack alone, groups, LoRa, who asks), `AttachmentHolderDaoTest` the SQL from both ends, `ScopeSyncTest` the
settle, `KnitDatabaseMigrationTest` the step, `HolderAskBatchTest` the batching (a flush a stop cancels must not
switch the asking off), and the lab the end-to-end shape: with proof the photo never goes up, even after the peers
part and the sender restarts (`PhotoProofLabTest`); with only the ack it goes up after they part, as before
(`InternetPlaneLabTest.aPhotoTheRadioCarriedIsNotUploadedUntilThePeersPart`). `spoolAttachProved`
counts every pass that settles on a proof, in Diagnostics and the `SPOOL` bridge, so a skipped upload never reads like a
broken one.

## Amendment 2026-10-27 — a contact's direct link proves too

Status: stands (2026-10-27); the owner's call, recorded in ADR 2026-10.a5yj's custody-and-blobs amendment.

"Learnt over a short-range radio, and only that" now reads: over a phone-to-phone path that carries the bytes, a radio
or a contact's direct Internet link between the pair (`DeliveryPlane.provesHold`). The recipient holds the bytes
whichever carried them, and refusing the proof cost the author's spool upload of every DM photo sent over a link. A
file off a link writes the recipient's record under `DeliveryPlane.DirectLink`, and the author's holder query goes over
a link for the hashes the link carries, its answer recorded under the plane it crossed. LoRa and the spool still never
prove. Spec: `docs/SPOOL_PROTOCOL.md` C-9.5-8 and §10.4, `docs/DIRECT_LINKS.md` L-7-6.
