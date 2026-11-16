---
id: "2026-11.43f7"
slug: a-bluetooth-link-s-reader-waits-for-room-instead-of-dropping
title: "A Bluetooth link's reader waits for room instead of dropping"
date: 2026-11-15
topics: [bluetooth, mesh]
---

# ADR 2026-11.43f7 — A Bluetooth link's reader waits for room instead of dropping

Status: Proposed (2026-11-15, #331). A draft: built (`mesh/bluetooth/LinkInbound`, a suspending
`LinkCallbacks.onInbound`, `LinkCrossings.firstCrossingIf`), and owed the device trial at the end before it stands.

**What was observed.** A phone whose router fell behind lost Bluetooth link frames, and could lose a DM for good.
`FramedLink`'s reader handed each frame to `BluetoothMeshTransport.LinkEvents.onInbound`, which marked the crossing and
then called `_inbound.tryEmit` on a `MutableSharedFlow` of 256, ignoring a refusal. Behind it the composite's `merge`
holds one more and a channel of 64. The sender marked the frame crossed when it wrote it (`writeOnce`), so for
`SeenSet.DEFAULT_TTL_MS` (ten minutes) it skipped every re-serve of that frame over the link (`bleLinkDupSkipped`),
while the receiver's `SeenSet` had never seen it. That breaks what ADR 2026-09.6nmy rests on: whatever the memo
suppresses, the far end's router already has. In the lab, a 48-node gather with phone-sized queues dropped tens of
thousands of frames. Custody parity froze for about ten minutes, and one run lost a DM for good when custody's
1,000-row cap evicted it on every node during the freeze. The lab has no airtime, so those counts are an upper bound.
`InboundBurstLabTest.aDmBurstPastAPhoneQueueLandsOverItsLink` reproduces it on one link: under the drop model, Alice
held 322 of Bob's 400 DMs after 120 s, and the memo withheld the rest.

**What changed.** The link's reader waits for room instead of dropping.

1. `LinkCallbacks.onInbound` is `suspend`, and so are `FramedLink.readLoop` and `handleFrame`, so the wait is a
   suspension inside the link's `readerJob`. `FramedLink.close()` cancels it, and `handleFrame` checks for
   cancellation before the hand-off. A torn-down link's reader is never left blocked on a router that is behind, and
   never hands a frame over after its link ended. The design pass rejected a blocking wait (`runBlocking { emit }`, a
   blocking channel send) inside a non-suspending callback. `close()` reaches neither, so a reader blocked there would
   hand a dead link's frame over later and mark a crossing after `teardownLink` forgot them.
2. `LinkInbound.hand` (one per link) tries `tryEmit`, and on a refusal calls `emit`, which suspends until the router
   makes room. A `MutableSharedFlow` resumes its waiting emitters in order, so each parked link advances at its share
   of the router's rate, and a link flooding past the queue does not hold an honest link behind all of it
   (`LinkInboundTest.aFloodingLinkTakesOnlyItsShareOfASlowRouter`). A frame read off a link the plane no longer holds (`links[nodeId] !== link`) is not handed
   over.
3. **The crossing is marked after the hand-off, and only while the link is current.**
   - After: a frame counts as crossed only once the router's queue holds it, so a wait that `close()` cancels leaves no
     mark. The cost is a frame the router re-fans toward this link in the instant between the two, which the far
     end's `SeenSet` drops.
   - Only while current: `LinkCrossings.firstCrossingIf` reads `links[nodeId] === link` under the lock `forget` takes.
     `registerLink` now forgets the peer after `links.put` (it used to forget before), so a mark the replaced link's
     reader judged while it held the id lands before that forget. No mark lands in the replacement's memo. A link's
     end already takes it out of `links` before it forgets (`teardownLink`).
4. **Pages have a lane of their own** (`BleInbound`). A scan callback cannot wait, so a page is still a `tryEmit`, but
   into its own `MutableSharedFlow` of 32 (`SideCarousel.CAPACITY`), merged with the links' lane of 256 through a
   rendezvous into the plane's `inbound`. In one shared queue, a `MutableSharedFlow` gives every freed slot to its
   waiting emitters before a `tryEmit`, so any linked peer flooding its link would shut every page out for as long as
   the flood lasted, and pages from senders we hold no link to are the ones custody cannot repair (the
   security-reviewer's finding on the first draft). The merge's two collectors wait on one channel, which serves them
   in order, so a router that is behind takes from each lane in turn. A page that finds its own lane full is dropped
   and counted as `BleSideDrop.INBOUND_FULL`. The plane holds at most what the two lanes hold plus one frame in hand
   per lane; the composite's `merge` adds its shared channel of 64 behind it, as before.
5. Observability. A link's reader logs `bt inbound wait <id>: the router's queue is full` when a spell of waits starts,
   and `bt inbound waited <id> frames=<n> ms=<total> (<why>)` when it ends: at the first frame that goes straight in
   (`room`), at a wait `close()` cancelled (`closed`), at the first frame read once the link is not current
   (`replaced`), or as the reader's loop ends (`ended`, `FramedLink`'s reader-only `onReadEnd`; never `onLinkDown`,
   which the writer reports too). Every `wait` line is followed by one `waited`. `bleInboundWaits` and `bleInboundWaitMs` count in `MeshMetrics.Snapshot.Bluetooth` and the
   field log's counters.

This is the iOS port's rule. knit-ios ADR 2026-10.f5zs, rule 6, stops reading a channel once 256 KiB it delivered wait
unprocessed, resumes below half, and lets L2CAP's credits push back on the sender. `DirectLinkTransport` already never
drops (a suspending `_inbound.emit` behind an unbounded per-link `arrivals` channel), so Bluetooth was the one plane
that both marked crossings and dropped. The change cannot recreate the lab wedge fixed in `5faf45e21`: a phone's
`FramedLink.send` is an unbounded enqueue that never waits, so two routers never wait on each other. Keep it so.

The alternatives:

- **Cut the link on a refusal.** `closeSilent` redials skip the backoff, and the re-serve after the digest exchange
  lands on the same full queue, so the cut repeats.
- **Wait a bounded time, then drop and cut.** It duplicates `LinkSilence` (ADR 2026-10.697m), and cuts links that are
  only slow.
- **A bigger buffer.** It only moves the threshold. A gather hands a node its neighbours' whole custody at once.

**What it costs, and what it does not cover.**

- **Credit pushback on Android is unverified.** No ADR says what `com.android.bluetooth` does when an app stops reading
  an L2CAP CoC socket: withhold LE credits from the sender, buffer without a bound, or drop. f5zs established pushback
  for iOS and BlueZ only. This is the device trial's first question.
- **The silence and stall rules stay as they are, as the backstop.** `SocketStamps.input` stamps every read. The
  peer's 120 s write-stall timer runs per `write()` of at most 8 KiB, and any returned credit ends that write. So
  `LinkSilence`'s probe close (about 150–210 s) and the peer's stall close fire only for a router that hands over
  nothing for 90–120 s: a stopped router, not a slow one. Their cut makes both ends forget their crossings.
- **A screen-off receiver may sleep with its backlog.** Nothing on the mesh path holds a wakelock, and none is added.
  The sender's `WRITE_STALL_MS` runs on `elapsedRealtime` (pj9w), so a peer may cut at about 120 s. `closeSilent`
  redials at once and the digest exchange re-serves. Each lap drains what was already handed over, so the cycle ends.
  The cut is the accepted bound.
- **iPhone links get the same bound, with nothing to change.** Under f5zs rules 3–4, an iPhone sending to us closes
  after 120 s with bytes queued and none written, or past 4 MiB queued. Its reply rounds are capped at 200 frames or
  256 KiB, so it rarely fills a park. Our `LinkSilence` exempts doorbell links, so we never cut an iPhone for our own
  park.
- **Head-of-line wait.** All Bluetooth links feed one links' lane, so a live DM waits behind the whole backlog: about
  the backlog divided by the router's rate, possibly tens of seconds. That still beats a drop plus a ten-minute hold.
  The lasting cure is capping the sender's serve (#276).
- **A file streaming to us stalls during a park.** `onRxProgress` stops, so the sender's `FeedMeter` (15 s) may read
  the feed as stalled, and under ADR 2026-10.g24x another holder may serve the same blob. The cost is bounded and left
  as is.
- **Not covered.** This restores 6nmy's invariant for Bluetooth's inbound buffer only. `MeshRouter.handleInbound`'s
  `IngressBudget` refuses cleartext room `CHAT` before `seen.add`, which withholds the same way: a separate follow-up.
  Wi-Fi Aware keeps its `tryEmit` drop, and does not skip by crossings.

The trap is a wait on the reader path that `close()` cannot cancel, or a mark made before the hand-off or without the
current-link check. `LinkInboundTest` pins the mechanics over a real `FramedLink`: a full queue waits and loses nothing,
`close()` cancels the wait and marks nothing, a replaced link's waiting reader marks nothing on the replacement and
hands over nothing read after it, a spell open at the link's end is closed, a waiting reader never shuts a page out, and
a flooding link takes only its share. `LinkCrossingsTest` pins the guarded mark and its order with `forget`. The lab's
`LabLimits.inboundSlots` (`MeshLab.PHONE_INBOUND_SLOTS`) models the plane as shipped, in
`InboundBurstLabTest.aDmBurstPastAPhoneQueueLandsOverItsLink` (ending in `assertConverged`) and
`aPhoneQueueHoldsWhatFindsItFull`.

**Device trial owed.**

- Stimulus: 4–6 Android phones, each with a full custody store of distinct rows, brought into range of one target at
  once. Screen on, then off on a phone that suspends deeply. Add one iPhone sender through the knit-ios interop harness.
- Oracles: the target's custody parity with each sender (debug bridge); `bleLinkDupSkipped` on the senders;
  `bt inbound wait` / `bt inbound waited`, `bleInboundWaits` and `bleSideDrop_INBOUND_FULL` on the target;
  `bt link silent … write stall` or `probes` on the senders, and the iPhone's `link chN write stalled`;
  `dumpsys meminfo com.android.bluetooth` on the target.
- Pass: screen on, parity within 60–106 s, no DM missing, and no silence or stall close. Screen off, no DM missing,
  each cut followed by parity within minutes, and no cut loop past three laps. Record whether the senders' writes ever
  blocked: that settles the credit question.
