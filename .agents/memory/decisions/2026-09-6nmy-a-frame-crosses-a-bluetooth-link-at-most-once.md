---
id: "2026-09.6nmy"
slug: a-frame-crosses-a-bluetooth-link-at-most-once
title: "A frame crosses a Bluetooth link at most once"
date: 2026-09-18
topics: [bluetooth, fast-path, battery]
---

# ADR 2026-09.6nmy — A frame crosses a Bluetooth link at most once

Status: Accepted (2026-09-18; `mesh/link/LinkCrossings`, `BleFastRoutePolicy.fanout` excludes the author)

Found by the 2026-09-18 battery review, reading the code rather than a log: every `shouldFastFanout` frame —
room chat, reactions, receipts, group meta, profiles — was written to every live L2CAP link **twice**, and a
relayed one a third time back over the link it came in on.

The two writers are both correct on their own. `MeshRouter.sendOwn` (our frame) and `scheduleRelay` (a
first-seen relayed frame, after 0–150 ms of jitter, split-horizoned by `heardFrom`) flood over
`CompositeMeshTransport.send`, which hands the Bluetooth child `wire.relayed()` for each linked neighbour.
`fastFanout` — the immediate copy, the fast plane's whole point — hands the same child `wire` for every
`links.keys` (`BleFastRoutePolicy.fanout`), which is the link copy the composite used to send on the plane's
behalf before ADR 2026-09.sjaa moved it inside. Different `hops`, identical `sig` and `signed`, one ordered
stream: the far end's `SeenSet` dropped whichever landed second, every time. On a two-radio mesh in steady
state that is about half the L2CAP bytes of the room class, and the BLE radio is the one on all night. The
echo is the same shape one step later: `fastFanout` carries no hop id, so a node re-fanning a frame it just
received sends it back to the peer that handed it over — the split horizon `fastSend` has at
`InboundPipeline.onDeliver` (`it != fromNodeId`) never reached the fan-out arm.

## What changed

A **per-link crossing memo** in the transport, not a routing change. `mesh/link/LinkCrossings` keeps one
`SeenSet` per linked peer, keyed on `mesh/link/FrameKey` (the sig-prefix key the side channel's heard set
already used, hoisted; `u:<id>` for an unsigned frame). `BluetoothMeshTransport` marks a frame on the way
**in** (`linkCallbacks.onInbound`) as well as out, and `send`, `fastFanout` and `fastSend` write to a link
only on the first crossing — otherwise `bleLinkDupSkipped` counts it. `registerLink` and `teardownLink`
forget the peer, so a fresh stream starts clean. `BleFastRoutePolicy.fanout` also drops the frame's own
author from `linkTargets`, for the one case the memo cannot see: a frame first heard off a side-channel page
arrives with the author as its hop, so the router's split horizon cannot exclude the link the copy would have
come by, and the author never needs its frame back.

**The invariant that makes this a byte saving and not a delivery change:** the memo's window is the router
`SeenSet`'s (`SeenSet.DEFAULT_TTL_MS`, ten minutes), and inbound counts as a crossing. So every write it
suppresses is a frame the far end handed its router inside a window where that router would drop it again —
the same drop that happened before, moved one hop earlier and off the air. A wiped-and-restarted peer, whose
empty `SeenSet` would *not* drop a re-serve, comes back on a new link, and a new link is a clean memo; the
custody re-serve after its digest exchange goes through exactly as before.

The alternative a reader reaches for first is to **drop one of the two writers**: have `fastFanout` skip
the link copy for `wire.relay` frames (the router will flood them), or have the router skip peers a fast
plane already served. The first regresses Bluetooth to the suppressible, jittered flood — a linked third
peer whose copy the overhear rule cancelled then waits for the 60 s custody re-offer, which is what the
immediate link copy exists to prevent. The second needs the composite to know per child what the fast arm
delivered, a cross-layer memo in a worse place. Keeping both writers and de-duplicating at the stream is
local to one file, keeps the router's accounting (`onRelayed` credits the hand-off, which did happen) and
its suppression semantics untouched, and its failure mode is today's duplicate.

## What it costs, what it does not cover, and the trap

Cost: one `SeenSet` per link, at most 1024 keys each, one hash lookup per write. Not covered: the Wi-Fi
Aware plane, whose fast copy rides the cue plane and whose NDP is ephemeral — there is no second writer to
one stream there; and LoRa, whose own `sigSeen` already answers this question. Not changed: a frame whose
fast copy the NAN size gate refused (`fastTooBig`) still gets the router's copy over BLE — the memo only
knows what was actually enqueued.

The trap is mistaking the memo for a delivery guarantee. It is not one; it only says "this stream already
carried these bytes". `ForwardSync.onDigest` will still be handed a frame a peer's digest says it lacks
within the window, and the memo will skip it if the same link just carried it — correct, because the copy
is ahead of the digest reply on the same ordered stream. What would break the invariant is a `SeenSet`
window shorter than the memo's, or marking a frame as crossed on a link that did not take it (a write
that failed tears the link down, which forgets the memo — keep it that way).

`SideChannelLabTest.aFrameCrossesEachPipeOnce` is the regression: a paged line with the pages lost on
purpose, one room post, `assertConverged`, then exactly one `via=…` line per (sender, receiver) for the
post, none back toward the author, and Alice's second copy in `dupSkipped` rather than lost. `LabTransport`
keeps the same memo, so the box remains the plane as shipped; `LinkCrossingsTest` and `FrameKeyTest` pin the
memo and the key, `BleFastRoutePolicyTest.fanoutNeverRoutesAFrameBackToItsAuthor` the author rule. Device
oracle: `bleLinkDupSkipped` on `…debug.STATE` climbing about one per room frame per link while delivery
counters stay where they were.

## Amendment 2026-11-23 — only a relayed frame counts on the way in (#356)

The inbound mark assumed the far end's router handed the frame on. A point-to-point (`relay = false`) frame need not
have passed it: a key sent ahead of a backlog (ADR 2026-09.g64k, `KeyExchange.serve` from `ForwardSync.onDigest`) is the
sender's copy of a profile from its pin store, the same signed bytes as the custody row of that profile. A carrier whose
router had not seen the profile (fresh after a restore, or past its window) and whose custody lacked it sent the key to
its peer; the peer marked the frame crossed on the way in and then skipped its own custody push of that profile back as
an echo, for the memo's ten minutes, so the two custodies disagreed until the window or the link ended
(`RestoreLabTest` under chaos; `KeyExchangeLabTest.aKeySentAheadDoesNotCostItsSenderTheCustodyCopyOfThatProfile` forces
the order).

Now a frame read off a link marks its crossing only when `LinkCrossings.marksInbound` says so: `wire.relay`, the bit
every link codec carries. The Bluetooth reader (`LinkInbound.hand`), the direct link's arrivals and the lab's pipes all
ask it. The outbound mark is unchanged: the far end's router takes a point-to-point frame into its seen set like any
other, and ADR 2026-09.7bu7 custodies a served profile. Not marking a point-to-point frame on the way in costs nothing
the memo was for: the router never floods one back toward its hop, and a copy that does cross again is the far end's
router's to drop, as before the memo. The rule sits on the receiver, so a key an older build or an iPhone sends ahead
to an Android phone is covered too. A key an Android phone sends ahead to an iPhone is not yet: knit-ios marks every
frame that arrives, whatever its relay bit (its twin of this memo, knit-ios ADR 2026-10.f5zs rule 6), so that pair can
still disagree on the profile for the window until knit-ios takes the same rule.
