---
id: "2026-10.a4pm"
slug: a-lora-dm-waits-for-its-recipient-to-be-heard
title: "A LoRa DM waits for its recipient to be heard"
date: 2026-10-10
topics: [lora, dm]
---

# ADR 2026-10.a4pm — A LoRa DM waits for its recipient to be heard

Status: Superseded by ADR 2026-10.x9fa (2026-10-29), never device-trialled. Proposed 2026-10-10 (JVM-tested; it named `FanoutHint.CHAT`, `LoraMeshTransport.reachGate` /
`unheardRecipient` / `dmProbeAt` / `DM_PROBE_WINDOW_MS` / `frameKey` / `reofferedAt` / `REOFFER_MEMORY_MS`,
`LoraDmProbe`, `LoraPacePolicy.upgradeQueued`, `RideGate.reachGated`, `StaleAtSend.UNHEARD`, `OutboundFrame.sigKey`,
`LoraPlaneSnapshot.dmProbes` / `reoffered`, `MeshManager.framesFor` / `FAR_REOFFER_OWN_LIMIT` /
`FAR_REOFFER_RELAYED_LIMIT`, `ForwardStore.liveFramesByIds` / `liveFramesToFromOthers`). Amends ADR 039 §7.

**What was observed.** Every DM-form frame — a DM, its sealed reaction, a session reset, a seed or key request
sent to one peer, the coalesced ✓✓ — went out on the LoRa fan-out whether or not the recipient's board was
anywhere in range. Each one is 2–3 packets, ~5–7 s of a 45 s / 15 min allowance at LongFast (ADR 039: ~2.5 s a
packet). The covers (ADR 054's link, ADR 2026-09.y5f3's spool) keep a frame off the air when another plane
carries it; nothing kept one off when *nothing* could hear it. A user texting a friend whose board was at home
spent the window on frames nobody received, and the room and every far peer then went without. Not mistaken for
anything: it was the known residual of ADR 039 ("best-effort by construction"), priced only once the room, the
bridge and the covers had taken the rest of the waste out.

## What changed

1. **The reach gate, keyed on the originator's hint.** In `fanout`, after the covers and the freshness gate and
   before `encodeOrNull` / `sigSeen.add`, `reachGate` decides. The plane cannot read a sealed frame, so the
   originator says what it is through `MeshTransport.longRangeFanout`'s `FanoutHint` — a seam value, not a wire
   field:
   - our own DM (`FanoutHint.CHAT`, set only by `MeshManager.sendChat` for a 1:1 message and by the pending-key
     flush, `flushPendingFor`) is gated and may probe. The post-reset re-seal (`resealRecentDmsTo`) is `CONTENT`:
     custody keeps the first-stored seal of an id (`ForwardSync.onSeen` returns early on `has(id)`), so a held
     re-seal would later be re-offered as the old bytes the reset peer cannot open. Exempt, the fresh seal flies
     now, and — sharing the frame's key — replaces an old-seal copy still queued, which is the copy worth losing;
   - our own ✓✓ (`TICK`) is **not** gated. It exists only because its author's DM just reached us, so it is
     evidence-driven by construction, about one packet, and it flew unconditionally before this change. Held, it
     would be stranded: it has no message row, so `framesFor` never re-offers it, and a re-offered old DM does not
     make its author heard on our board (`isPresenceEvidence` takes 15 min), so the author could show one tick until
     radio or spool contact;
   - any other frame of our own (`CONTENT`: a sealed reaction, a session reset, a seed or key reply) is **not**
     gated either. It has no message row, so `framesFor` would never re-offer it, and it draws no ✓✓, so it could
     neither probe nor make its recipient heard. Held, it would wait for a radio or spool contact;
   - a relayed DM-form frame is opaque: gated, re-offered from custody with the relayed share. It may take the
     probe only when its author is a pocket-mate we hold a live link to — as the gateway, a board-less
     pocket-mate's DM reaches the air only through us, unhinted, and without the probe its DMs to a quiet far
     peer would wait for a hearing nothing provokes. The gateway cannot tell that pocket-mate's DM from their
     tick or ctl, so a probe can be spent on a frame that draws no ✓✓; the next window probes again;
   - until our own id is known nothing is gated (`Reach.Exempt`): the gate cannot tell our ctl from a
     stranger's DM, and the send-time re-ask still refuses a frame addressed to us once the pacer has the id
     (#141).

   A gated frame whose `recipientId` is not in `reachable` is held: counted `loraUnheardHeld`, left in custody,
   its dedup slot untouched. `reachable` is author-keyed reach (ADR 2026-09.2ajk), so a recipient heard only
   through a far gateway's airing or a Meshtastic repeat counts — that airing is the route back. A co-pocket
   board's airing still does not (ADR 2026-09.6gk8), and a co-pocket peer is linked and covered before this gate
   asks. The Nearby room, `profile`, the targeted path (`fastSend`, reach-gated since ADR 039) and the bridge
   backfill (gated on a far OFFER, ADR 044) are untouched.
2. **The probe.** The first `CHAT` DM to an unheard recipient in each `DM_PROBE_WINDOW_MS` (15 min, one airtime
   window) rides anyway, counted `loraDmProbes`, logged `lora probe <label> -> <id>`. If the recipient is there,
   the probe makes us heard on their board, their sealed ✓✓ rides back and makes them heard on ours, and that
   first hearing runs `reofferTo`. The claim is atomic (`LoraDmProbe`, `@Synchronized`) and given back unless the
   frame reaches the queue — `DROPPED_OLDEST` reached it (it shed an older frame), only `REFUSED` did not — and
   given back again if the queued probe is refused at send for any reason (it rides on `OutboundFrame.probe`): a
   probe that never flew told nobody anything. No probe is claimed before the state restore has run.
3. **A probe, not a beacon.** The alternative a reader reaches for first is a profile beacon to provoke the
   recipient's first hearing. It does not work: a recipient that already hears us — from our room posts, or our
   last beacon — never takes our beacon as a first hearing and never beacons back, and a beacon names nobody.
   The ✓✓ to a DM is the one reply a present recipient is certain to send — which is also why only `CHAT` may
   probe: a frame that draws no ✓✓ would spend the window and learn nothing.
4. **Re-asked at send** (ADR 2026-09.5dt2's rule: repeat a gate, never add one). A frame that passed the gate
   carries `RideGate.reachGated`; `staleReason` returns `UNHEARD` if its recipient lingered out while it waited,
   after `LINKED` and `INTERNET`. The probe, an exempt own ctl, everything not DM-form and the bridge backfill
   carry `false`.
5. **A frame that never flew hands back what it held** (`abandon`). Every path that takes a dedup slot — the
   fan-out, the bridge's serve, the re-offer — records it on `OutboundFrame.sigKey`, and every path that drops a
   frame before its first fragment reopens it (`SeenSet.reopen`): the send-time re-ask, a NAK or give-up on the
   first fragment (`DUTY_CYCLE_LIMIT`, `RATE_LIMIT_EXCEEDED`), a missing config or a bound slot that is not
   Knit's, and every frame the pacer drops unqueued (`LoraPacePolicy.drainShed`): a victim shed for a newcomer, a
   newcomer or a requeue the full queue `REFUSED`, an oversize eviction. One path hands each back, so nothing is
   released twice; a refused serve also reports `SKIPPED` rather than `SENT`, so it spends nothing of the round. The frame never flew, so the slot records a transmission
   that did not happen, and on a plane with no acks that slot is what turns away the next attempt: the re-offer
   after an UNHEARD drop, a re-offer or backfill after the link that made a frame LINKED goes again. The next
   attempt asks its own gates again, so nothing re-airs what another plane carries. `SeenSet.reopen` lets one
   more copy through per id per window, so a frame refused twice within ten minutes keeps its slot the second
   time. A probe gives back its window (`OutboundFrame.probe`) and a re-offer its hour (`OutboundFrame.reofferId`)
   the same way. A frame with a fragment already on the air keeps all three — it did fly, in part.
   (`fastSend` and the beacon keep their slots: AckSync's retries and the beacon floor own those.)
6. **Every copy of a DM-form frame is one copy in the queue.** The fan-out, the bridge's serve and the re-offer
   queue a DM-form frame under one `OutboundFrame.supersedes` key (`frameKey`). When the re-offer finds the slot
   taken but the pacer holding a copy that has not started (`sentParts == 0`) on `BRIDGE`, its own `LIVE` copy takes
   that one's place (`LoraPacePolicy.upgradeQueued`) instead of being turned away. Only that lift: a swap the other
   way would push a live copy down (a relayed re-offer on `BRIDGE` replacing a queued `LIVE` fan-out), and a queued
   copy holding a probe window is never swapped, since the replacement does not carry the claim. That closes a stall the
   lab found: Bob's OFFER had Alice's bridge serve her held DMs onto `BRIDGE`, which at the lab's preset carries
   about three one-packet frames a window; the fourth sat out the window while `LIVE` had air, and the re-offer
   on Bob's first hearing skipped it on the dedup slot the serve had taken. A part-sent copy is never replaced or
   superseded — the board holds its first fragments (feb2d5a4); the pacer's supersede now spares one for every
   key, profiles included. The bridge, for its part, never queues a second copy: `serveOne` skips a DM-form
   frame the pacer already holds (`LoraPacePolicy.hasQueued`), or it would supersede a queued probe or LIVE
   re-offer onto `BRIDGE`. "Holds" includes the frame being written: `take` removes it from the queue, so the pacer
   remembers it until `done` or `requeue`, and both `hasQueued` and `upgradeQueued` treat it as queued — else a
   serve in that gap queues a second copy and a `Busy` requeue (which supersedes nothing) lets both fly. And a frame the pacer took and could not send (the board busy, the link gone) is put
   back by `LoraPacePolicy.requeue`, which supersedes nothing: through `enqueue`, a requeued older profile
   deleted a newer one queued meanwhile, ADR 2026-09.xdm2 read backwards.
7. **The re-offer is now the delivery path, so its cap is split** (amends ADR 039 §7). `MeshManager.framesFor`
   returns our own unacked DMs first — chosen by id off `MessageDao.unackedDmsTo`, newest first, over-fetched ×2
   and capped at `FAR_REOFFER_OWN_LIMIT = 6` after `ForwardStore.liveFramesByIds`, so an unacked row custody no
   longer holds takes no slot — then the newest live custody frames to the peer that others wrote, up to
   `FAR_REOFFER_RELAYED_LIMIT = 4` (`liveFramesToFromOthers`). This fixes a latent bug: the old read applied
   `LIMIT 4` to the custody rows *before* dropping our acked DMs and our own sealed ctls, so those could take
   every slot an unacked DM needed. `liveFramesTo` and its query had no other caller and are gone.
   `reofferOne` books on `LIVE` a frame that was a live send held back — our own, or a board-less pocket-mate's
   (author in `linkedPeers`) we were the gateway for — and anything else on `BRIDGE`. The relayed four slots
   are shared with relayed ✓✓s and ctls to the same peer, newest first, so a burst of a pocket-mate's ticks can
   push an older relayed DM out of one sighting's batch.
8. **One frame is re-aired at most once an hour across first hearings** (`reofferedAt`, `REOFFER_MEMORY_MS`).
   The first hearing comes once per 45-min linger, so an asymmetric pair — we hear B only now and then while B
   hears us — would re-air the same backlog every linger cycle. Marked when the frame is queued or lifted.
9. **The probe window and the re-offer memory are persisted limiters** (ADR 2026-09.7svb):
   `LoraPlaneSnapshot.dmProbes` (`STATE_DM_PROBE_CAP` = 64) and `reoffered` (`STATE_REOFFER_CAP` = 64), on the
   wall clock, restored with the rest. Neither is cleared by `quiesce`: like the auto-reply memory and the ledger,
   they are about recipients, frames and the air, not the board, and because the restore runs once per instance a
   clear on `stop()` would reopen them for an in-process stop/start.

Not a wire change and not a schema change: `FanoutHint` is the transport seam's own value, `forward_store.senderId`
already existed, and the two queries are reads.

## What it costs, and what it does not cover

- **The asymmetric pair.** If B hears A but A cannot hear B, A now gets one DM per probe window through to B where
  before every DM went out. The rest wait for A to hear B (then once an hour per frame), or for a radio or spool
  contact.
- **A recipient with "Private messages over LoRa" off never sends a ✓✓ back** (`loraDmEnabled` gates its own
  sends), so it is never heard through this mechanism; only probes reach it — one DM per window, unless its room
  posts or beacon make it heard.
- **A re-offer batch holds the room and ticks.** Six own two-packet DMs are ~30 s, two thirds of a LongFast window,
  booked on `LIVE`; `DM` ranks above `ROOM` and `TICK` in the dequeue, and a live frame the total refuses holds
  everything ranked behind it (ADR 044's 2026-10-04 amendment). So a backlog delivered on a sighting can keep room
  posts and ✓✓s waiting for most of a window. It is air those DMs would have spent live anyway, bunched — the old
  path spent it whether or not anyone heard. The cap went from 8 to 6 for this.
- **Our own ✓✓s and ctls still fly to peers nobody hears.** A ✓✓ is about one packet and answers a DM that just
  arrived, so its author was very likely in range; a ctl has no row to be re-offered from. Both cost what they cost
  before this change.
- **The gateway's probe can be wasted.** It cannot tell a pocket-mate's DM from their ✓✓ or ctl, so a window's
  probe may go on a frame that draws no ✓✓ back; the pocket-mate's held DMs then wait for the next window.
- **A board-less recipient behind a far gateway** is heard when the gateway airs any fresh frame of theirs; until
  then a DM to them probes like any other. The far gateway's OFFER can still have our bridge serve a held DM to
  that pocket.
- **Latency.** A held DM waits for the probe's round trip (the recipient's ✓✓ hold, ADR 054) plus the re-offer. One
  typed after the recipient is heard rides live.

Pinned by `LoraMeshTransportTest`'s reach-gate block (the probe and the held DMs on LIVE, a relayed re-offer on
BRIDGE, a pocket-mate's on LIVE, the window across a restart, a tick and a relayed DM never probing while an own ctl
flies, an UNHEARD drop re-offered, a probe surviving the re-ask, a queued bridge copy lifted onto LIVE, the hour
across first hearings and restarts, a probe that shed an older frame, a linked pocket-mate's DM probing, an own ✓✓
airing unheard, a probe and a re-offer refused at send, a probe NAKed on its first fragment and a probe shed from a full
queue giving back what they held, the bridge not displacing a queued probe),
`LoraDmProbeTest`, `LoraPacePolicyTest.aRequeuedFrameSupersedesNothing` / `hasQueuedSeesAWaitingCopyByKey` /
`anInFlightFrameStillCountsAsQueued` / `aShedFrameIsHandedBackForTheCallerToAbandon`,
`MeshManagerTest.thePendingFlushHintsChatAndThePostResetResealHintsContent` /
`anUnackedDmCustodyNoLongerHoldsLeavesItsSlotToAnOlderOne`,
`LoraPacePolicyTest.anUnstartedCopyOnAnotherBucketIsUpgradedInPlace` and `aPartSentCopyIsNeitherUpgradedNorSuperseded`,
`MeshManagerTest.ourUnackedDmsAreNotCrowdedOutByNewerAckedFramesOrOwnCtls` and its neighbours, `ForwardDaoTest`,
`ForwardRepositoryTest`, and end to end `LoraPocketLabTest.aDmToAQuietFarPeerProbesOnceAndTheRestLandOnItsTick`,
`aDmToAQuietFarPeerLandsWithTheBridgeOnWithoutWaitingOutTheBridgeShare`, `aDmToAnAbsentPeerSpendsOneProbePerWindow` `aBoardLessPocketMatesDmsToAQuietFarPeerProbeThroughTheGateway` and
`anAbsentPeerThatReturnsLongAfterTicksEveryDmWithoutANewOne`.

Field oracle: `…debug.LORA`'s `loraDmProbes`, `loraUnheardHeld`, `loraReoffered` and
`loraStaleAtSendByReason.UNHEARD`, the `lora probe` and `lora reoffer:<id> lifted a queued copy onto LIVE` lines; the
field log's counters line carries the three counters. Device trial owed (two phones with boards, no short-range link,
quiet past the 45-min linger): three DMs A→B read `loraDmProbes 1`, `loraUnheardHeld 2`, then `loraReoffered ≥ 2` and
three ✓✓ on A once B's tick lands; B's board off, two more DMs read one probe and nothing else on air for 15 min.
