---
id: "2026-09.ywzn"
slug: a-blob-obtained-off-the-radios-serves-the-neighbours-that-asked-for-it
title: "A blob obtained off the radios serves the neighbours that asked for it"
date: 2026-09-15
topics: [mesh, attachments, spool]
---

# ADR 2026-09.ywzn — A blob obtained off the radios serves the neighbours that asked for it

Status: **Superseded by ADR 2026-09.4tx5 (2026-09-19, #79)** — `wanters` and `onObtainedOffMesh` are gone; a
neighbour that asked while we lacked the bytes is served on its next ask (its 60 s tick, ADR 2026-09.ptv8) and
never pushed, because the push could not tell an asker that still lacks the bytes from one whose copy is already
arriving from somebody else. Was: Accepted (2026-09-15). GitLab work item #53. The second half of the asymmetry #51 opened
(`cdab7b5c`), which fixed the `fetching` mark and deliberately left `wanters` alone.

**What was observed.** `BlobExchange` has two pieces of bookkeeping for a blob it does not hold:
`fetching`, the pull we started ourselves, and `wanters`, the neighbours that asked us for it while we
were empty-handed. `wanters` was drained in exactly one place — `removeWanters` inside `onReceived`, the
**radio** arrival — and two planes put bytes in the store without ever reaching it. The spool writes the
blob in `ScopeSync.fetchAttachment` and fires `onAttachmentObtained`; a direct avatar push is ingested by
`InboundPipeline.onAvatarReceived`, and that hash can legitimately be in `fetching` first, because
`pullRelayAvatarIfNeeded` wants the avatar of a peer that is not yet a neighbour and may become one. On
both paths the entry survived and the asking peer was never served. It looked self-healing, and it is,
but not at the speed the shape suggests: the requester asks again only on a **new** link
(`onNeighborAdded`), on its own restart, or once its `fetching` mark ages out at `FETCH_TTL_MS` = 30 min
and something re-drives `want` — there is no `retryMissing` for blobs the way `KeyExchange` has one. For
a pair that stays continuously linked that is thirty to forty minutes for a picture the other end already
held. The stale entry also sat in a 256-key cap that evicts oldest-first, so it could evict a live one.

**What changed.** `BlobExchange.onObtainedOffMesh(hash)` does for an off-radio arrival what `onReceived`
does for a radio one: clear the fetch mark, detach `wanters[hash]`, and send the stored file to each
peer still in it, past the same `servedRecently` memo. There is no `fromNodeId` to filter out, because no
neighbour handed us these bytes. `MeshManager` calls it after `pipeline.onObtained` on the spool hook, and
`InboundPipeline.onAvatarReceived` calls it as its last step. This is a real behaviour change and not
bookkeeping: it makes the spool plane *push* bytes onto a radio. That is exactly what the requester asked
for, it is the same send `onRequest` would have made had we held the bytes when it asked, and the serve
memo bounds a duplicate the same way — so the alternative of leaving the entry to the requester's own
re-ask buys nothing but the delay.

The obvious place to put this is `InboundPipeline.onObtained`, which would catch both planes in one door.
It cannot go there: `MeshManager` wires that same function as `BlobExchange`'s own `onObtained` callback,
which `onReceived` awaits *before* it reaches `removeWanters`. Draining from inside it would empty the set
the radio path is about to read, losing the filter that keeps a blob from bouncing straight back at the
neighbour that just served it. The two off-radio call sites are the narrower and safer seam.

Fixed alongside it, because it is the same stranded-wanter failure with a different cause: `onRequest`
gated its serve on `file != null && mime != null`, so a blob we hold whose mime row is missing fell
through to `recordWanter` + `want`, and `want` returns at once for a hash the store has — a wanter with
no pull in flight and no arrival that could ever drain it. Holding the bytes now decides the serve, and a
missing row costs only the type, which falls back to `image/jpeg` as `MeshBlobStore.fileFor` already does.

**What it costs.** A spool-fed node spends radio airtime and a file transfer on a neighbour it would
otherwise have made wait, which is the point. What it does not cover: the content-filter branch of
`onAvatarReceived` deletes the blob and returns, so nothing is served there and the entry falls to the
ordinary cap eviction — correct, since we no longer hold the bytes. The trap for the next person is the
one above: any new drain must not live in a door the radio path shares. Kept true by
`InternetPlaneLabTest.aPhotoTheRelayDeliveredIsServedToTheNeighbourWhoAskedWhileWeLackedIt` — Carol
carries the frame, asks the one neighbour she has while the picture exists nowhere but on Alice, and holds
it once Bob pulls it off the relay, with no re-link and no second `blobreq` — and by
`BlobExchangeTest.aBlobObtainedOffTheMeshIsServedToTheNeighborThatAskedForIt`,
`theServeMemoStillBoundsAnOffMeshDrain` and `aHeldBlobWithNoStoredMimeIsStillServed`.
