---
id: "2026-09.bgk3"
slug: an-unfulfillable-responder-request-is-re-filed-on-a-curve-and-given-up-on
title: "An unfulfillable responder request is re-filed on a curve and given up on"
date: 2026-09-19
topics: [wifi-aware, reliability, mesh]
---

# ADR 2026-09.bgk3 — An unfulfillable responder request is re-filed on a curve and given up on

Status: Accepted (2026-09-19)

**What was observed.** On the lab Pixel 3 (blueline, API 31) at 23:19:33 on 2026-09-18, during the ADR
2026-09.535d trial, the Wi-Fi Aware responder's `onUnavailable` handler re-filed its `requestNetwork`
**174 times in about 130 ms** — one `responder listening on port N` per re-file, ~10 ms apart — and kept
going until an unrelated `re-attaching to recover wedged discovery/responder` tore the session down at
23:19:35.960. The capture starts inside the burst, so its trigger is not in it. What is in it: `live=[]`.
No initiator link was up, so the handler's premise — an inbound NDP refused because *our* link held the one
interface (the P0 finding of `docs/NAN_CONCURRENCY_REAUDIT.md`, "observed self-heal in 9 ms") — did not
hold. Whatever the framework's reason was, it was a property of the request, and it was persistent. Work
item #77.

It was not mistaken for anything; it had simply never been bounded. The handler was `stopResponder();
startResponder()` behind a generation guard, on the reasoning that a refused knocker backs off and tries
the fresh request. A knocker does. A framework that has stopped recognising the publish handle the request
is built on, or will not hand out an interface, answers the fresh request the same way ~10 ms later, and
the only thing that ended it was another recovery path noticing something else. Every `requestNetwork`
is a binder object in `system_server` until its callback is unregistered — released here, so not a leak
of ADR 052's kind, but the same family of cost, and 1,300 requests a second of it.

**What changed.** `NanResponderPolicy` (pure, JVM-tested) paces the re-file, and the transport's
`refileResponder` reads the interface state at the moment the verdict lands to decide what the verdict
*means*:

- **Contended** — a link, a handshake or an accept of ours is live, or the post-link `SETTLE_MS` has not
  run out (the transport's own definition of "the framework may still hold the NDI"). This is the
  documented knock refusal; it says nothing about the request and is not counted. It is re-filed after
  the 500 ms floor. The knocker's own `NanConnectPolicy` first retry is 2 s out, so the floor costs it
  nothing, and a framework that refuses in a loop costs two files a second for the life of the link.
- **Uncontended** — nothing of ours on the interface, refused anyway. Counted: the re-file backs off along
  0.5 → 1 → 2 → 4 → 8 → 16 → 32 → 60 s (±20 % jitter, the cap shared with `NanConnectPolicy` and the
  Tier-1 heal window), and at **five in a row** — four re-files, 7.5 s of a free interface — the request
  is given up on and the session cycled through `sessionCycleWithSettle()`: fresh publish, fresh handle,
  the framework's request cache wiped by the NAN-down it produces. That is Tier 1's cure for a wedged
  responder, and a request refused five times with the interface free is a wedged responder.

The cycle is under the shared `REATTACH_COOLDOWN_MS` every other session cycle honours (a refusal inside
the window re-files along the curve instead) and is **capped at three per episode**, because the
`NanWatchdogPolicy` livelock of 2026-09-07 was exactly an uncapped cycle: each one orphans every
`PeerHandle` the initiator dials with, and a cycle that fires on every cooldown for as long as the
refusal lasts keeps destroying the sessions a handshake needs. Once the cycles are spent the re-file
simply keeps following the curve to its one-minute saturation — a chipset that refuses the responder
for good costs one request a minute, and the request is still there to take if the framework relents.
The watchdog's own tiers remain behind it.

The refunds are the part that has bitten before (ADR 055: a budget is only worth what its refund path is
worth). The streak is per publish session — `onPublishStarted` zeroes it, since it was about the old
request. The cycle budget is refunded by exactly one thing: the responder's **`onAvailable`**, an NDP
that actually landed on the request, the only proof the responder works. Not by a fresh session (the
escalation's own cycle would refund itself), not by the Aware availability edge (a session cycle
produces one — the same loop ADR 055 closed on the attach side), not by `heal()`. Both are cleared by
`stop()`.

The alternative a reader reaches for first is the issue's own suggestion, `reattach()`: the path that
ended the field burst. It tears down and attaches inline, which is the coin-flip race
`sessionCycleWithSettle` was written to replace (`docs/NAN_CONCURRENCY_REAUDIT.md` §5.2) — the attach
can land before the framework's ~50 ms disable-and-wipe, and then the request cache the refusal may live
in is never cleared. The settle sequence is deterministic and already the responder-refresh cure the
watchdog uses; the initiator's `reattach()` stays where it is for the subscribe wedge.

**What it costs.** A knock refused while our link is up now waits up to 600 ms for a responder to be
re-filed rather than 9 ms; the knocker is not back for two seconds, so no serve is lost. A genuinely
broken request takes ~7.5 s to be given up on instead of being hammered until something else breaks.
What it does not cover: the *cause* of the Pixel 3's refusal is unknown — the capture starts mid-burst,
and it has not reproduced on demand — so this is a bound, not a cure; if it is a dead publish handle the
cycle is the cure, and if it is something the cycle cannot reach the node re-files once a minute and the
watchdog's Tier 1 and Tier 2 still stand behind it. The trap: the pending re-file is a `Runnable` held in
`responderRefile` and dropped by `stopResponder()` — a new teardown path that bypasses `stopResponder`
leaves a stale file armed against a session that is gone (guarded by `publishSession === pub`, so it
would no-op, but do not rely on the guard alone). And do not "fix" the contended case by counting it: a
hub serving a long sync collects knocks for the life of the link, and counting them would cycle a
healthy session out from under its own initiator.

What keeps this true: `NanResponderPolicyTest` — the curve, the floor against `NanConnectPolicy`'s base,
the five-verdict give-up, the three-cycle budget, the Pixel 3 window replayed against the policy (two
files where there were 174), and the 1,440-a-day saturation cost. The `…debug.STATE` line carries
`refused=<streak>/<cycles>`.

**Device trial, 2026-09-19, Pixel 3 (blueline, API 31) and Pixel 9 Pro XL (API 37).** The fault is not
reproducible on demand, so it is injected: `…debug.NANREFUSE --ei count N` (`NanFaultInjector`) delivers the
framework's verdict to the live responder callback 10 ms after each file — the field cadence — and the
transport's own `stopResponder` / `refileResponder` then run unmodified. On the Pixel 3, the device the burst
was captured on, 25 armed verdicts with no link up walked every phase in 108 s: re-files at 500 / 841 / 2023 /
4097 ms, the fifth verdict cycling the session (`1/3 this episode`, 7.5 s in), the fresh session's streak
starting at 1, its fifth and sixth verdicts re-filed at 7.9 and 14.3 s because the reattach cooldown still
held, the seventh cycling (`2/3`), the same shape to `3/3`, and past the cap the fifth verdict re-filed at
9.4 s instead of cycling. The 25th verdict spent, the file at 01:30:38 stood; `NANREFUSE` read
`filed:true, refusals:6, cycles:3, armed:0` and the state line `refused=6/3`. Twenty-five requests in 108 s
where the same phone had filed 174 in 130 ms. On the Pixel 9, five verdicts produced one cycle and a standing
responder at `refusals:0, cycles:1`; it re-discovered the Pixel 8 and 7 within minutes of the cycle (no sync was
owed, so no link was due).

**The fault then reproduced by itself, twice, and the log holds the cause the field capture lacked.** At
01:48:08 and again at 01:53:08 the Pixel 3's STA Wi-Fi dropped and reconnected (`[139 WIFI] CONNECTED →
DISCONNECTED`, `ClientModeImpl: Leaving Connected state`, back up within four seconds). Each time the Aware
factory released our standing responder request as unfulfillable in the same millisecond
(`WIFI_AWARE_FACTORY: releaseRequestAsUnfulfillableByAnyFactory … role=1`), and from then on **every**
request built on the app's handles — initiator and responder alike — was `acceptRequest`ed and released
as unfulfillable within ~10 ms of filing. Twenty seconds later `WifiAwareStateManager` said why: `no client
exists for clientId=14913` on the next `sendMessage`, `updatePublish` and `terminateSession`. The STA drop
had taken the app's Aware client with it, and **no callback said so** — no `onAwareSessionTerminated`, no
`onSessionTerminated`; the app held live-looking publish and subscribe handles that the framework no longer
knew. That is the 174-in-130-ms shape exactly: a request the framework refuses on sight, re-filed on sight.
The Pixel 3 sits at −51 dBm on a 5 GHz STA that dropped twice in five minutes, which is also why its
discovery was blind for long stretches (every fresh attach discovered peers within ~20 s; a dropped client
discovers nothing until something re-attaches).

The fix handled the natural run as designed. The 01:53:08 verdict counted `(1 in a row) — re-filing in
545ms`; the next three arrived while an initiate to the Pixel 7 was in flight and were logged `(inbound NDP
during our initiate) — re-filing in ~500ms` **without** advancing the streak — the contended branch,
exercised naturally; the streak then ran 2 → 6 at 1.0 / 2.0 / 4.0 / 7.0 / 15.7 s. It did not cycle at the
fifth because this episode's three cycles had already been spent by the injected run twenty minutes earlier
and no serve had refunded them — the cap doing its job — so the Tier-1 watchdog took it at 60 s owed
(`refreshing responder via session cycle`), the fresh attach worked, and the responder stood. On an
un-spent episode the escalation fires at the fifth verdict, ~7.5 s in, and it is the **primary** recovery
for this failure: there is no other signal, and Tier 1 needs a sync owed.

Not exercised on device: the `onAvailable` refund of the cycle budget. It needs a knock on the Pixel 9's
responder, the only lab node with a larger id is the Pixel 3, and the Pixel 3's initiates to the Pixel 9
never produced an `onDataPathRequest` on the Pixel 9 that night (a 15 s handshake timeout, then fast-fails;
its STA was flapping, and the Pixel 9's sightings of it were intermittent) — even with the Pixel 3's
Bluetooth off so that nothing else could carry a 700 KB attachment. The refund is three assignments behind a
callback the framework fires on every serve; read it in `startResponder`'s `onAvailable`. The oracles for
a natural run on the transport tag are `responder request declared unfulfillable with no link up (N in a
row) — re-filing in Xms`, `… (inbound NDP during our initiate) — re-filing in Xms`, and `… N times in a row
with no link up — cycling the session (c/3 this episode)`; a healthy device logs none of the first kind,
and `refused=` on the state line reads `0/0` after any serve.

**Addendum, later the same night — what the Pixel 3's bursts are.** Three more natural bursts on the Pixel 3
(01:53, 02:06, 03:46), each paced and, once the injected run's budget had lapsed, cycled at the fifth verdict
as designed. Each began with the STA Wi-Fi dropping, and three of the four STA drops landed within 40 ms of a
Qualcomm HAL `NDP Cmd Type 0xa` confirm indication (`Response code 1`, REJECT) — the firmware finishing a
failed data-path negotiation the Pixel 3 had started 30–100 s earlier. It happened on a 5 GHz STA and on a
2.4 GHz one alike. So on blueline the chain is: initiate → the NDP request never reaches the peer (the Pixel
9's firmware counted `Num Data Path Request Events 0` across some twenty attempts, phones adjacent, ICM on
or off) → the firmware's negotiation times out with a reject that also knocks the STA off its AP → the STA
drop silently drops the Aware client → every request refused on sight. The Pixel 3 has never formed a NAN
data path with any lab Pixel, and initiating is what hurts it. One concrete lead for the request never
arriving: the Pixel 3's framework holds the Pixel 9's publish instance id as `16777216` (0x01000000) where the
Pixel 9's own `mPubSubId` is 1 — a byte-swapped u32 out of the QCA HAL's match indication, which would put
Publish ID 0 into every NDP request and follow-up the Pixel 3 sends. Its broadcast SDFs are also heard at
about a third the rate of the other Pixels', so the old firmware is out of step in more than one way. Not
fixable from the app (the swap lives inside `system_server`'s peer table); the transport's job is to stay
bounded around it, which this ADR's curve, the reattach cooldown and the three-cycle cap do — the 03:46
burst cost five files and one cycle.

**Follow-up this opens.** A STA drop that silently kills the Aware client is a fault of its own: the
transport could watch for it directly — the framework's `WifiManager.NETWORK_STATE_CHANGED_ACTION` /
`ConnectivityManager` default-network loss on Wi-Fi, or the first `no client exists` failure — and re-attach
without waiting for five refusals. Whether the client dies on every Android 12 device or only on blueline's
HAL is not established; the Pixel 9 (API 37) rode the same evening without one. Parked for a work item
rather than folded in here: this ADR bounds the re-file, and the bound is what the field burst needed.
