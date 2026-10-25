# Blob exchange and custody — which decision governs which symbol

The symbol → ADR routing for how an attachment's bytes move between phones (`BlobExchange`, the holder query, the
carrier's pull, the parked ask, the resumed prefix, the progress reports) and for store-and-forward custody (what
`ForwardStore` keeps, what the digest folds, what `ForwardSync` and `KeyExchange` serve, what `PendingInbound`
parks). The `blobs-owner` reads it before it writes and the `blobs-reviewer` before it judges (both in
`.agents/personas/`); `AGENTS.md` points here instead of carrying these lines. Each entry names the symbols that
trigger it, the ADRs to read (`.agents/memory/decisions.md` maps an id to its file, amendments included) and, where
there is one, the section of `.agents/context/store-and-forward.md`, `docs/PICTURE_FETCH_BLUETOOTH.md` or
`.agents/context/mesh-transport.md` that explains the mechanism. An entry is an index into its ADRs, not a
substitute: where they disagree, the ADR wins and the entry is fixed. The invariants themselves are binding in
`.agents/rules/mesh.md` (the custody, "served only to a fresh ask", holder-query, picture-fan-out, resume and
re-want bullets).

Adjacent routing that stays where its owner is, because another subsystem owns it:

- **How fast a link is fed** — the pace budget, feed window and link boost (ADRs 2026-10.8jwn, 5buf, 2da4),
  `FramedLink`, `LinkCrossings`: `.agents/context/bluetooth.md`. A blob change that moves a `FramedLink` line owes
  `bluetooth-reviewer` too.
- **The Wi-Fi Aware reconcile and bulk gates** (`digestSyncWanted`, `bulkSyncWanted`, the file route's NAN arm):
  `.agents/context/wifi-aware.md`.
- **The LoRa backfill and bridge custody window** (ADRs 044, 054, 2026-09.rre4, zkma, y8pu): `.agents/context/lora.md`
  — a board carries a frame and never a blob.
- **The spool's attachment class and its deferral** (ADRs 021, 2026-09.xmte, p7j8, e8yw, 2026-10.vy27; the accounting of ADR 062):
  `.agents/context/spool.md` and `docs/SPOOL_PROTOCOL.md` §4.5 / §9.5. `AttachmentDeferPolicy.noteRadioArrival`
  is fed from `MeshManager.watchIncomingFiles` ahead of the store write, and must stay ahead of it.
- **Receive-side screening** — `MeshBlobStore.ingest`, `BlobStore.storing`, a held-but-refused avatar (#109): the
  receive-side screening entry in `.agents/context/moderation.md` and `docs/CONTENT_MODERATION.md` §3. "Held" means "screened".
- **Group-tick escalation into custody** (ADR 033) and the sealed-tick seal-once cache: `.agents/context/crypto.md`;
  the room tick's ride hold (ADR 2026-09.y5f3) and `MeshRouter.handOn` (2026-09.wkbk): their entry in `.agents/context/spool.md`.
- **The Your mesh ledger's hooks** (`onRelayed` / `onServed`, `ForwardDao.observeCarriedForOthers`, ADR
  2026-09.2v2t), **the heal basket's custody replay** (`replayUndeliveredGroupCustody`, ADR 2026-09.6st4), **the chat's
  blob-size subscription** (ADR 2026-09.fjcw), **the profile re-flood memo** (ADR 2026-09.uc8p) and **the picture's
  bytes before they are sealed** (`ImageStrip`, ADRs 2026-10.unqn and 8hmx): their own entries in `.agents/context/`
  (`app-shell.md`, `service.md`, `messages.md`, `contacts.md`).

## Entries

- **When touching `mesh/BlobExchange`, `FramedLink`'s `rxKey` / pending-file count, `MeshTransport.arrivingFiles` /
  `fileInFlightTo`, or what re-asks for a blob (`rewantMissingBlobs`, the tick's `reask`):** READ ADR 2026-09.4tx5
  (and 2026-09.ptv8 for the database re-arm). A blob is served only to a fresh ask — nothing is pushed to a peer that
  did not just ask, there is no wanter set — and "is it on the way" is a read of the link (and of the store,
  `BlobStore.storing`: a blob is screened before it is held, #109), never a memo with a TTL: the receiver stays quiet
  for a hash whose header is in (and, past its end, until the consumer has taken it with `MeshTransport.fileTaken`;
  every read runs link, then `storing`, then held, #138), the holder refuses a re-ask for a key still queued or
  streaming to that peer. The Bluetooth stack's buffer sits between those reads (ADR 2026-10.nxfb, #121): a peer asked
  before is not asked again while its file is streaming in (`fileArrivingFrom`), and a file the link fed in full stays
  in flight while the link goes on feeding that peer. The lab pins it with `LabTransport.holdFiles` and the `files`
  recorder (one copy per (hash, link)).
- **Before touching `BlobExchange.want`'s hints, its round (`decide`, `FIRST_ASK_GRACE_MS`, `ROUND_MS`) or
  `MeshTransport.bulkRouteTo`:** READ ADR 2026-10.f7gg — a round asks the likeliest holder alone (author, then Wi-Fi
  Aware linked, then reachable, then the source hop) and widens to everyone only after its grace, because every holder
  serves a fresh ask; there is no negative answer on the wire, so never make it one-at-a-time without one. A link-up
  clears its newcomer's asks (`BlobExchange.linked`), since an ask can die with a link. Lab: `BlobFirstAskLabTest`.
- **Before touching the proof query — `BlobExchange.confirmHeld` / `onPeerHolds` / `PROOF_FLOOR_MS`, the proof branch
  of `onHaveAnswer` (its `shortRange`), `onReceived`'s result, `MeshManager.askHolder` or what writes
  `attachment_holders` (`BlobRepository.recordHolder`):** READ ADR 2026-10.vy27 (amending ADR 021). A DM photo's
  sender asks its linked recipient with the ordinary holder query whether it holds a photo of ours; a short-range
  `HAVE`/`SENDING` answering that query is the proof that keeps the photo off every spool for good. Proof queries
  never touch a want, and a want's answer never proves; the recipient's own proof is written only after
  `onReceived` reports the store took the bytes, never on the arrival memo. Lab:
  `PhotoProofLabTest.aPhotoItsRecipientHoldsNeverGoesUpEvenAfterThePeersPart`; unit: `BlobHolderQueryTest`'s
  proof cases.
- **Before touching the holder query — `BlobExchange.onHaveQuery` / `onHaveAnswer` / `decide` / `wantAll`,
  `FrameType.BLOB_HAVE` / `BLOB_HAS` / `isUnsignedPointToPoint`, `BlobHaveContent` / `BlobHasContent`,
  `Protocol.CAP_BLOB_HAVE`, `BlobStore.servable`, `MeshTransport.feedingLoad` / `slowLinkTo`, or
  `BuildConfig.BLOB_QUERY`:** READ ADR 2026-10.6e5t — a neighbour whose link carries the bit is queried first, the
  best holder by plane and load asked alone, a silent one confirmed then held to its ask's floor (the amendment of
  2026-10-06, #140: only a no, a gone link or the floor moves on; waits stretch to the neighbour's measured round
  trip); the rest keep f7gg's round. A query is not an ask (it serves, pulls and stamps nothing), HAVE is the serve
  predicate read without materialising, SENDING peeks the memo, an unknown state is never HAVE (PARTIAL is reserved
  for a range ask), every build answers and the asking is on in every build since 2026-10-08
  (`BuildConfig.BLOB_QUERY`). Wire change: the iOS port owes the responder as a companion change. Step 4's
  pull-through is bounded by ADR 2026-10.2drc (the next entry). Lab: `BlobHaveLabTest`; unit: `BlobHolderQueryTest`.
- **Before touching the pull-through's budget and deadline — `BlobReqContent.pull` / `until`, `BlobExchange.PULL_BUDGET`,
  `budgetOf`, `noteAsker`, `pullNewcomers`, `Want.budget` / `until` / `freshLinks`, `PullThroughGate`, `wantAll`'s
  `until`, the `sentAt` / `dm` of `AttachmentFetchRow`, `deliveredFetch`'s deadline, `CarriedAttachment.sentAt`,
  `ForwardStore.attachmentsNeedingFetch(now)`, `ParkedAsk.ask`, `BlobEvent.PullWithheld` / `EchoDeclined`, `Want.dead`'s blind
  half, `Want.cycleGap`'s long backoff (`LONG_DEAD_MS`, `MAX_LONG_BACKOFF_SHIFT`, `Want.longDead`):** READ ADR 2026-10.2drc (#195).
  Every `blobreq` carries how many phones deep it may still pull, the receiver counted, and the root's deadline. A want
  of our own mints `PULL_BUDGET` (3); a neighbour that lacks the blob wants it with one less, and an absent field (an
  older build, the iOS port today) is a fetch only and never refreshes a want. An ask keeps a want alive only when it
  carries at least the want's own budget, so only a root mints liveness: no local rule could tell a root's ask from an echo's, and each one tried
  in review opened another loop. The deadline is a DM's custody window from its latest row (`PullThroughGate.until`);
  past it nobody pulls or makes an echo. A pull never needed a carrier, so reach to a far end two or more hops away is
  the cost. Past two lead-less cycles a blind cycle that brought nothing is dead (amending f7gg), and a streak dead for a
  day backs off into hours, fast-only wants excepted; since the 2026-10-19 amendment so does one past its own deadline
  once it reaches the 6 min ceiling (`Want.longDead`), since a restart forgets the day's streak but never the deadline. Wire change: two vectors added; the iOS port owes the fields and
  rules as a companion change. Lab: `StrandedAttachmentLabTest`, `PullBudgetLabTest`; unit: `BlobHolderQueryTest`, `PullThroughGateTest`,
  `WireSerializationTest`, `GoldenVectorTest`.
- **Before touching `CarrierPullPolicy`, `ForwardStore.carriedAttachments`, what `onCarriedFrame` /
  `rewantMissingBlobs` hold back, `BlobExchange`'s parked asks (`parkBehindFeed`, `waitsBehindFeed`, `serveParked`),
  or `answersIn`'s author rule:** READ ADR 2026-10.g24x. A carrier leaves a DM's picture to its linked recipient for
  up to 10 min, and a group's it is not in to the members while the author is linked (never a member's own copy,
  #132), a holder feeds one 1M/2M copy of a blob at a time, and a linked author's HAVE decides the fetch. Lab:
  `PictureFanoutLabTest`.
- **Before touching `blobprog` / `BlobProgressContent`, `blobcancel` / `BlobCancelContent`, `FramedLink.cancelQueued`,
  `BlobHasItem.eta`, `FeedMeter`, `FramedLink.feed` / `onProgress` / `onRxProgress`, `MeshTransport.fileFeed` /
  `expectedFileRate` / `fileProgress`, or how long a parked ask or a held asker waits:** READ ADR 2026-10.nsr7. A
  file's receiver reports what it holds every 2 s by its own clock, the sender measures the drain from it, and a
  parked ask waits for a live feed however long (no bound of its own) until it ends, stalls (15 s with no progress) or
  passes its deadline (first measured ETA × 1.25 + 30 s); the fastest asker goes next, and one goes beside a feed only
  when its own copy would end first. Members go before carriers, and an asker lets go of a neighbour its ask found
  lacking the blob, or whose other stream to it has more than 30 s left while an idle holder waits, withdrawing its
  ask with a `blobcancel` (the amendments). Phase 2 (an in-flight window instead of the pace caps) is recorded there
  and not built.
- **Before touching `FramedLink.rxFile`, `ArrivingFile`, `FileHeaderWire.size`, `MeshController.arrivals` /
  `arrivalTicker`, `ChatViewModel.arrivals` or `ui/chat/ArrivalIndicator`:** READ ADR 2026-10.y9qh — the chat's
  progress ring is the same read of the link (bytes in, and the size the header declared: a label, never a bound),
  polled only while an attachment the window shows is awaited, finite on screen, and a `size` that will not decode
  costs the label, never the file.
- **Before touching `PartialBlobs`, `mesh/link/FileIntake`, `BlobReqContent.offset` / `FileHeaderWire.offset`, or what
  `FramedLink.close()` keeps:** READ ADR 2026-10.wtyc — a link cut mid-stream keeps an attachment's prefix
  (session-scoped, keep-the-longer, never written in place), the ask names its length, the holder streams the tail
  (`size` is the tail's; the whole is `offset + size`), and a spliced file that fails its hash drops the prefix and
  re-asks from 0. Receive-side changes go through `FileIntake` under the link's lock. A link takes one resumed header
  per key per `FileIntake.RESUME_WINDOW_MS` (the ask round), refused before its prefix opens (the amendment, #161).
- **When touching `data/AttachmentIdentities`, `messages.attachmentPlainHash` / `attachmentSourceHash`,
  `AttachmentStore.ingestPicked` or its reuse check, `AttachmentStore.Ingested.reuse`, `MeshManager.sealAttachment`,
  `InboundPipeline.copyFromOwnStore`, or `MessageDao.sealedHolding`:** READ ADR 2026-10.mptm. A phone seals a
  plaintext once: a pick it already holds goes out as that identity (a sealed copy's hash and key, or a room picture's
  plaintext hash with no key — key-less first, then earliest), its bytes unchanged. A key-less frame naming a
  plaintext held sealed is shown from our own copy (through `MeshBlobStore.ingest`) **only when its author was party
  to the conversation that copy came through** — never from `BlobExchange.want`, which an unsigned `blobreq` or a
  stranger's post reaches: that would be a confirmation oracle. The plaintext name is recorded at send, on arrival and
  on save, never by a bulk decrypt, and `identityFor` must skip a sealed row without one. Carriers see equality on
  purpose; convergent encryption was rejected. Lab: `AttachmentReuseLabTest`.
- **Before touching the fast-only want — `BlobExchange.want` / `wantAll`'s `fastOnly` and `approved`, `Want.dead`'s
  fast-only half, `BlobReqContent.fastOnly`, `FileMeta.fastOnly` / `onDropped`, `CompositeMeshTransport.sendFile`'s
  fast-only route, `CompositeMeshTransport.fastLinked` / `BlobExchange.fastLinkUp`, `CarriedAttachment.roster`,
  `BluetoothFetchGate`, `MessageDao.attachmentFetchRows`, `MeshController.downloadAttachment`,
  `messages.attachmentFetchApproved`, or what `rewantMissingBlobs`, `InboundPipeline.onCarriedFrame` and `deliverChat`
  pass as the flag:** READ ADR 2026-10.js2p. An attachment every row of which declares over the user's Bluetooth
  download limit, untapped, is fetched only over Wi-Fi Aware — the rule folds per row in Kotlin, so a stranger's row
  cannot hold a contact's. A fast-only want queries, asks and pulls through only a neighbour linked or reachable on that
  plane; with none it asks nobody and stamps nothing (never a blind ask of a Bluetooth holder); a cycle that asked and
  still wants it is dead and backs off; a Wi-Fi Aware link coming up — seen per holder, at the edge, even to a neighbour
  already linked over Bluetooth — asks that holder again at once, and resets nothing else (links flap per sync). The holder serves over a live link or one it
  raises, else drops it (`file route: … fast-only — dropped`) and forgets the serve. The flag is re-derived from the
  rows on every re-arm and limit change, the re-arms and the tap's want run under one mutex (the tap's approval before
  it, on the manager's scope), and the tap is sticky in the exchange; a neighbour's ask sets the flag only on a want it creates. An unopened frame addressed to us — by its own roster
  for a group, never by group rows alone — is not wanted by custody (delivery follows; the tick holds one that never
  opens to Wi-Fi Aware, only when every carried frame naming the hash is ours). A room post declares its size in the
  clear since ADR 2026-10.hmfe (`ChatContent.attachmentSize`, the sealed path's `declaresSize` rule): it is every node's,
  so `onCarriedFrame` — which runs ahead of `deliverChat` — judges it by that size folded with the rows, and the tick
  counts a carried room post no row names (`CarriedAttachment.declaredSize`, read off its signed bytes) as ours when it
  declares over the limit; a post declaring nothing is never held. The re-arm reads custody before the rows, since
  custody's read loads that size only while no row names the hash, and holds what it cannot judge between its reads
  (`CarriedAttachment.rowCovered`, a frame trimmed between custody's two reads) until the next re-arm (js2p's
  2026-10-19 amendment).
  Lab: `LargeAttachmentLabTest`, `AttachmentLabTest`'s two #132 cases; unit: `BlobExchangeTest`'s fast-only cases,
  `CompositeMeshTransportTest`, `BluetoothFetchGateTest`.
- **When touching `KeyExchange`'s cache / `profileFor` / `serveKey`, `peer_profiles` (`PeerProfileEntity`,
  `PeerRepository.recordProfileFrame`), or the key-ahead in `ForwardSync.onDigest` (`keylessSenders`):** READ ADR
  2026-09.g64k (it closes the case ADR 2026-09.9xuu set aside). A carrier keeps the signed profile each pin came from,
  so it can prove the key of anyone whose frames it serves after a restart and after custody lost the profile. A key
  goes point to point, never flooded, and only to a peer holding none of that sender's frames we hold. Don't fold the
  frame into `peers` (a `ByteArray` breaks its equality), and don't keep the profile in custody past the quota or TTL
  (that moves the convergent quota rule). Regression: `StrangerBacklogLabTest`, `ForwardSyncTest`'s
  `onDigestServes…Key…` cases.
- **When touching `ForwardRepository`'s quotas or TTLs (`DEFAULT_MAX_ROWS` / `_PER_SENDER` / `_PER_GROUP` /
  `_BROADCAST`, `DEFAULT_TTL_MS`, `DEFAULT_BROADCAST_TTL_MS`), its eviction order, `StoreDigest`, `ForwardStore`'s
  live reads, or anything else the custody digest folds over:** READ ADR 006 and the "Bounds" and "A bounded custody
  quota must be *convergent*" sections of `.agents/context/store-and-forward.md`. Every bound is the same on every
  node: a full bucket keeps its newest-N by the frame-global `(sentAt, id)` on every origin, `ORIGIN_SELF` included
  (bc4ba0a5); expiry keys off `sentAt`, never `receivedAt` (cf0b6e00); the digest folds live ids only (daffbc82) and
  every decision site reads it through `current()` (3548c123), whose invariant is `current() == liveFingerprint`. A
  per-node input in the carry gate, the quota or the fold — local receive time, origin, the block list, a setting — is
  a digest no peer can match, and the cue plane re-attempts a reconcile on every cue for as long as it holds. The TTL
  and quota constants are convergence-critical: moving one is a mesh-wide change the iOS port must mirror.
- **When touching `InboundPipeline.canCarry`, `KeyExchange.want`, or anything else that decides whether a frame enters
  custody:** READ ADR 2026-09.bts9 (amending ADR 010). The carry gate is a pinned sender and a frame signature that
  verifies — authenticated, never decrypted — and nothing per-node: the block list is a delivery-path input, so a
  blocker carries, re-serves and accounts a blocked sender's frames and drops them only on delivery (e7528cb7). A
  message sent during a block stays unseen after the unblock, by design.
- **When touching `ForwardSync.onAck` / `onSeen`'s `acked` tombstones, a cleartext receipt's purge, or the order a DM
  and its receipt can arrive in:** READ ADR 2026-09.adpz and the DM paragraph of
  `.agents/context/store-and-forward.md`. A sealed receipt purges nothing (a carrier cannot read it; both age out on
  the frame-global TTL). A cleartext one purges the carried DM mesh-wide only when it comes from the DM's
  `recipientId`, and one that lands *before* its DM is remembered as `(ackId, acker)` for the tombstone's 24 h so
  `onSeen` refuses the DM when it arrives (2efea24a, #100) — custody must never depend on arrival order.
- **When touching custody of our own frames — `MeshManager.verifierBundle`, the self branch of `verifyInbound`, where
  an own frame is custodied relative to its flood, or `ForwardStore.carriedAttachments`' self rows:** READ the
  "Custody carries our own frames too" section of `.agents/context/store-and-forward.md`. A self frame is verified
  against our own bundle (a node never pins its own key) so a custody wipe re-converges by re-carrying our sends
  (2cdf2332), and an own frame is custodied *before* it floods, or a link raised between the two misses both until the
  60 s re-offer (85baeeb9, #141; `LabCustodyGate` stages it). Our own `profile` never pins.
- **When touching `ForwardSync.onDigest`'s serve order, `PendingInbound` (`MAX_PER_SENDER`, `MAX_FRAMES`, `MAX_BYTES`,
  `HOLD_TTL_MS`), or which delivered frames are custodied (a profile served by `KeyExchange`, a frame delivered off a
  key request):** READ ADR 2026-09.9xuu and ADR 2026-09.7bu7, then the `KeyExchange` and `PendingInbound` sections of
  `.agents/context/store-and-forward.md`. A digest serves a sender's profile ahead of their chat, and the park holds a
  whole custody quota per sender (200) under a 512-frame / 4 MiB global bound, so a stranger's backlog converges when
  the key lands instead of after the ten-minute seen window (aeba5382). A profile is custodied wherever it is
  delivered — flooded, served or fetched (910f469b) — or the digests disagree for a `SeenSet` window after every key
  request. Reopening dropped ids on key arrival was tried and cannot work on Bluetooth: `LinkCrossings` will not write
  the same frame to one link twice inside the window. Lab: `StrangerBacklogLabTest`, `KeyExchangeLabTest`.
- **When touching how often a digest is written to a link (`FramedLink.sendDigest` / `writePendingDigest`,
  `digestsReplaced`), `ForwardSync`'s 60 s re-offer, or what a link carries ahead of a back-fill:** READ ADR
  2026-09.tjfb. A link holds at most one custody digest waiting to be written; a newer one replaces it (11b91371). The
  slot is `FramedLink`'s, which is the Bluetooth plane's file (`.agents/context/bluetooth.md`): a change there owes
  `bluetooth-reviewer`. The residue is the Bluetooth stack's own socket buffer, which the slot cannot reach.
- **When touching `DigestTracker`, the identical-digest skip, or what a Wi-Fi Aware cue asks custody for:** READ
  `docs/DIGEST_PULL_REATTACH.md` (Part A) and the sync-gate entry of `.agents/context/wifi-aware.md`. The cue plane
  brings up a scarce data path only when two peers' digests differ, so every divergence this file guards against costs
  a reconcile that moves nothing; the NAN gates around it (`digestSyncWanted`, `bulkSyncWanted`, the initiator hold)
  are the Wi-Fi Aware plane's.
- **When touching the Nearby room's flood bounds — `IngressBudget`, `MessageRepository.sweepRetention`'s room cap,
  `roomMaxPerStranger`:** READ ADR 2026-09.vybk. A room post is metered per link in `MeshRouter`, ahead of the verify,
  custody and the moderator; a refused frame is not marked seen, so custody re-offers it through the same meter later
  — a delay, never a veto, and the carried sets still converge. Strangers-first eviction is the room's *message
  retention*, a local presentation bound; custody's per-sender quota and its frame-global order are untouched, and
  must stay so.

## Adding an entry

A new decision about blob exchange or custody gets a bullet here — the symbols that trigger it, the ADR, and the
invariant in a sentence or two — never a bullet in `AGENTS.md`. A symbol listed nowhere is not exempt: `BlobExchange`'s
KDoc and its inner classes' record most of the incidents that shaped them, and `ForwardRepository`'s constants say
which ones are convergence-critical.
