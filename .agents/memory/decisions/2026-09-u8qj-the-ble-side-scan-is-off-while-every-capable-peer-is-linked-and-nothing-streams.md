---
id: "2026-09.u8qj"
slug: the-ble-side-scan-is-off-while-every-capable-peer-is-linked-and-nothing-streams
title: "The BLE side scan is off while every capable peer is linked and nothing streams"
date: 2026-09-19
topics: [mesh, bluetooth]
---

# ADR 2026-09.u8qj — The BLE side scan is off while every capable peer is linked and nothing streams

Status: Accepted (2026-09-19) — built and JVM-tested; lands ahead of the battery night ADR 2026-09.sjaa owes, so
that night measures the channel and not this. Work item knit/knit-next#66, from the 2026-09-18 battery review.

**What was observed.** `SideScanPolicy` (ADR 2026-09.sjaa) ran the side channel's receive scan *continuously*
— LOW_POWER screen-off, BALANCED interactive or charging — whenever `SideCapableTracker.anyCapable` held, and it
holds for a flagged peer sighted inside 10 min **or linked**. In a settled clique at night every flagged peer is
linked, so that is ~10 % receiver duty all night against the presence scan's ~0.6 % once floored: some fifteen
times the cost, and the dominant BLE drain the moment `BuildConfig.BLE_SIDE_PLANE` flips on in release. It was
easy to mistake for the channel's price. It is not: a page only tells a receiver something its links will not in
two cases — the receiver is *sighted but unlinked* (over the link budget, in connect backoff: no link copy reaches
it), or a file is streaming on one of its links and the small frames behind it are head-of-line blocked. In every
other state `fastFanout`'s link copy (`BleFastRoutePolicy.fanout`, every linked peer but the author) already
carries the same frame, and the scan hears it a second time.

**What changed.** `SideCapableTracker.audience(now, linked)` classifies the post-prune set as `Nobody`,
`AllLinked` or `SomeUnlinked` (`anyCapable` is now `!= Nobody`, so the sender's gate is untouched), and
`SideScanPolicy.Inputs` takes that plus `streamInFlight` = `links.values.any { txInProgress || rxInProgress }`.
The table gains one Off row — `AllLinked && !streamInFlight` — ahead of the power tiers; connect-in-flight, the
low-battery Off, the A2DP cap, the 30 s start ration and the 25 min restart are as they were. Two things around it:

- **The flag lingers from the link's end.** `teardownLink` now calls `SideCapableTracker.touch(nodeId, now)`.
  A peer linked for two hours has a two-hour-old sighting (the floored presence scan never re-sighted it), so
  without the restamp its drop — or its *eviction* over the link budget, which is exactly the unlinked case the
  channel exists for — pruned it at once and the scan stayed Off until presence happened to re-sight it. `touch`
  only restamps a peer already holding the flag; it cannot make anyone capable. `teardownLink` also wakes the side
  loop, which the eviction path never did.
- **The sighting edge compares the audience, not `anyCapable`.** A flagged stranger appearing beside an
  all-linked clique flips `AllLinked → SomeUnlinked` with `anyCapable` unchanged; the old edge would not have woken
  the loop.

The alternative a reader reaches for first — gate the *send* half the same way, so a sender with every flagged
peer linked stops airing pages — is deliberately not taken. A linked receiver may be listening for a reason the
sender cannot see (its own stream, an unlinked peer only it sights), the two advertising sets cost ~1 % airtime
each, and stopping and restarting a set per state change is what the carousel was built to avoid. The send gate
stays `anyCapable`. A `LinkCallbacks` event for a stream starting was also not added: `FramedLink`'s flags are
plain `@Volatile` vars, the loop re-asks every `SIDE_TICK_MS` = 10 s, a BLE-paced blob runs tens of seconds, and
a few-KB avatar is over before the tick and blocks nothing. The one free edge — `onFile`, a received file
finalizing — does wake the loop so the rx case drops back to Off at once.

**What it costs, what it does not cover, and the trap.** The concession is the *bystander*. In the 2026-09-17
trial the Pixel 9 — linked to the streaming Moto, no stream of its own — heard each post off a page 0.3 s before
its link copy (2–18 s: the sender's ACL was saturated by a blob to a third phone). That phone's scan is now Off,
because nothing on *its* links is streaming, and it gets those posts at the link copy's 2–18 s. The frame still
arrives; only the latency during someone else's blob is given up, and the two cases the channel was built for —
the streaming pair and the unlinked peer — are unchanged. A sender-side "streaming" bit on the advert flags byte
could buy it back, but the presence scan is floored for minutes in exactly the clique where it would matter, so
the sighting would rarely land in time; parked, not built. Latency to re-arm after a stream starts is one tick
plus whatever the shared start ration still owes (≤ 30 s after the last start). Pinned by `SideScanPolicyTest`
(`offWhenEveryCapablePeerIsLinkedAndNothingStreams`, `listensWhileAStreamIsInFlightOnALinkedClique`,
`aStreamWithNobodyCapableIsStillOff`) and `SideCapableTrackerTest` (`theAudienceSaysWhetherALinkAlreadyReachesEveryone`,
`aDroppedLinkLingersFromItsEndOnceTouched`, `touchCannotMakeAnUnknownPeerCapable`); the lab's `LabPages` has no
scan tier, so `SideChannelLabTest` is a regression only. On a device the tier-change line now says why:
`ble-side want=Off audience=AllLinked stream=false busy=false` is the settled-clique night (the line logs on any change of tier *or* reason, and the same string rides the 60 s `bt state` line), and the
60 s `bt state` line's `rx=Off` should hold across it; a `700 KB` attachment should flip both ends to
`rx → LowPower`/`Balanced` within a tick and back to Off after the `file …` line. Still owed before the release
flag flips, as ADR 2026-09.sjaa lists: the battery night against the dark build, and a sighted-but-unlinked device
case (the lab links everyone).
