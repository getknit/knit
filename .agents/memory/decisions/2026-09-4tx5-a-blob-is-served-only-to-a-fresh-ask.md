---
id: "2026-09.4tx5"
slug: a-blob-is-served-only-to-a-fresh-ask
title: "A blob is served only to a fresh ask, and never asked for while it is arriving"
date: 2026-09-19
topics: [mesh, attachments, blob-exchange, bluetooth]
---

# ADR 2026-09.4tx5 — A blob is served only to a fresh ask, and never asked for while it is arriving

Status: Accepted (2026-09-19). Work item knit/knit-next#79, seen while verifying #66. Supersedes ADR
2026-09.ywzn (#53): `BlobExchange.wanters` and `onObtainedOffMesh` are gone. No wire change, no DB change.

**What was observed.** 2026-09-19 20:43, three phones linked over BLE (P3, Moto G, P9), a 699 KB DM
attachment P3 → Moto. It crossed the mesh three times to the Moto and twice to P9: P3 served the Moto at
20:43:07 and again at 20:44:34, P9 served the Moto a third copy, and the Moto served P9 a copy P9 had held
since 20:43:32. The Moto's controller drains at ~7 KB/s where P3 feeds at the 28 KB/s pace, so its first copy
took well over a minute to land. `saveIncoming` deduplicated every extra copy; the cost was three 700 KB
transfers where one would do, on the plane where a blob head-of-line-blocks chat (ADR 2026-09.sjaa).

The issue read the 20:43:07 transfers as "the sender's push". There is no push: every attachment `sendFile`
in the tree is a `BlobExchange` serve, and those two were P3 answering the Moto's and P9's first `blobreq`,
memo-stamped as every serve is. Three mechanisms made the copies, and only one of them was the one named:

- **The 60 s re-ask is real and blind.** `reofferToNeighborsPeriodically` calls
  `blobExchange.onNeighborAdded(peer)` for every linked neighbour (since July), which re-asks for everything
  in `fetching`; `rewantMissingBlobs` re-arms that set from the database first (ADR 2026-09.ptv8). Nothing
  told it the bytes were already streaming in. The Moto's tick at 20:44:32 re-asked P3, whose 45 s memo had
  lapsed — the second copy, 2 s after the tick. `want()` itself was never the re-broadcast: it is a no-op
  for a hash already in the set.
- **The wanter drain pushed to a peer that was being served by someone else.** In a clique the recipient's
  first ask reaches every neighbour; each non-holder recorded it in `wanters` and, when its own carrier pull
  landed ~24 s later, pushed a copy (`onReceived` → `removeWanters` → `sendFile`). That is the P9 → Moto
  copy and, mirrored, the Moto → P9 copy. The serving side cannot tell "asked 25 s ago, still needs it" from
  "asked 25 s ago, getting it from the author right now" — so a wanter TTL keyed on the re-ask period, the
  issue's first suggestion, stops neither: both asks were seconds old when the carriers obtained the blob.
- **The holder's memo is shorter than a transfer** and measures from the enqueue. `FramedLink.sendFile` is
  an enqueue; the stream runs later on the writer loop, queued behind whatever file is already going.

**What changed.** Three seams, two of them reads of the link and none a memo:

1. `MeshTransport.arrivingFiles()` — the keys with a `FILE_HEADER` in and no `FILE_END` yet on any live
   link, read off `FramedLink.rxKey` (set at the header, cleared at end, abort and close). `want` returns for
   an arriving hash without marking it; `onNeighborAdded` keeps it in the set but does not ask. A pull, like
   the side scan's `streamInFlight` (ADR 2026-09.u8qj): no TTL to pick, no abort callback to plumb — a torn
   link clears its own state and the next tick re-arms from the database and asks again.
2. `MeshTransport.fileInFlightTo(nodeId, key)` — queued on the link or streaming, counted in `FramedLink`
   from the enqueue to the end of the stream. `onRequest` refuses a serve while one is in flight, whatever the
   memo says: the case the memo cannot cover is a serve queued behind a multi-minute blob to the same peer,
   or an older build's 60 s re-ask against a slow transfer. `SERVE_MEMO_MS` stays 45 s — it is the flood bound
   for a held blob and covers the composite's 10 s fast-link grace, when a serve sits on no link yet.
3. **No push to a peer that did not just ask.** `onRequest` for a hash we lack pulls it on the asker's
   behalf and remembers nothing; `onReceived` saves, clears the mark and notifies. The asker re-asks on its
   own tick while it still lacks the bytes, stays quiet while they arrive from anyone, and never asks again
   once it holds them — which is the only party that can make that distinction. The hop-by-hop walk still
   happens; each hop moves on the asker's next re-ask instead of the carrier's arrival.

The alternative a reader reaches for first is the wanter TTL. It fixes a wanter that stopped asking long ago
and nothing else, as above. The other is a `blobhave` notice — a new unsigned point-to-point type the carrier
sends its wanters, answered by a fresh ask only if still lacking. Zero duplicates and one round trip, but a
wire type forever for a minute of latency, and an older build drops every notice as a bad signature. Not
taken; if the latency below turns out to matter, that is the additive route.

**What it costs.** Up to ~60 s on a true two-hop pull where the carrier is still fetching when the ask
arrives (the recipient asks the carrier, the carrier's own pull lands 24 s later, the recipient's next tick is
≤ 60 s off), against N−2 needless copies per attachment in an N-clique before. A blob wanted past the
30-min `FETCH_TTL_MS` that the database does not name (a group photo, a relay avatar) is re-driven by its own
advertiser as before, no longer by a carrier's push. What it does not cover: the *first* `want` still reaches
every neighbour before any header lands — it is what makes a holder serve — so a holder linked to several
askers serves each one; that is one copy per asker per holder, the design. An older build keeps pushing to
its wanters. The trap: a new "is it on the way" question must read the link (`arrivingFiles`,
`fileInFlightTo`), never a set here with a TTL — the transfer's length is the receiver's controller's to
decide, and no constant covers it. Kept true by `AttachmentLabTest.aPictureAlreadyStreamingInIsNeitherAskedForAgainNorServedTwice`
(Alice's serve to Bob parked with its header across, Carol carrying and holding, the re-link Bob ↔ Carol as
the tick: two asks, one copy per (hash, link), nothing pushed — fails on the parent with a third `blobreq` and
Carol's push), `InternetPlaneLabTest.aPhotoTheRelayDeliveredIsServedToTheNeighbourWhoAskedWhileWeLackedItOnItsNextAsk`
(#53's shape, now served on the re-ask and never before), `BlobExchangeTest` (`aWantForAnArrivingBlobIsSilent`,
`theTickReAskSkipsAnArrivingBlob`, `aReAskWhileTheCopyIsQueuedOnTheLinkShipsNothing`,
`aRequesterWeLackForIsServedOnItsNextAskNotPushed`) and `FramedLinkTest` (`rxKey…`, `aPendingFile…`).
**Device-verified 2026-09-19 22:15** on the trial's own shape (P3 → Moto G, 700 183 B, P9 linked to both):
P3 served the Moto and P9 once each at 22:15:17 (24.4 s each at the pace) and nothing else moved for six
minutes — the Moto's stream ran 22:15:26–22:16:23 and P9's 22:15:29–22:17:01, so both phones' 60 s ticks
(22:16:02, 22:16:13) fell inside their own streams, exactly where the old build re-asked, and no re-ask, no
carrier push and no push back reached any link. The holders' `file ATTACHMENT/<hash>` lines are the oracle:
two on P3, none on the Moto, none on P9.
