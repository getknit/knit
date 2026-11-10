---
id: "2026-11.mfbq"
slug: a-callee-with-no-live-path-declines-no-route-unrung
title: "A callee with no live path declines no_route unrung, and the caller re-places it as a mesh call"
date: 2026-11-10
topics: [calls]
---

# ADR 2026-11.mfbq — A callee with no live path declines no_route unrung, and the caller re-places it as a mesh call

Status: Accepted (2026-11-10)

**What was observed.** GitHub getknit/knit#63: two Moto Edges with Wi-Fi and mobile data off, linked over Bluetooth
alone. A call from the chat rang on the other phone, and answering it never connected. A live call between neighbours
carries its media over a Wi-Fi Direct group (ADR 2026-10.kxnw) or an Internet route; with neither, ICE has no network
to gather on and the call ends `no_route` 20 s after the answer (C-3.3-3). Bluetooth alone is a walkie's path (ADR
2026-10.79yb). The caller's half was 562f5b267: the guess `CallKind.AUTO` makes (C-3.11-3) copied the leg's gates and
missed a switched-off Wi-Fi radio, so it now asks the leg itself (`CallDirect.canOffer`, `call/WalkieRoute`). The
caller cannot see the callee's radios, so a callee whose Wi-Fi alone is off still rang and failed.

**What changed.** The callee says so, and the caller acts on it.

- **Callee (C-3.3-8).** `CallManager.onOffer` declines a live OFFER `DECLINE rsn=no_route` without ringing when this
  phone holds no network at all (`networked`, `AndroidInternetGate.hasAnyNetwork`: no default network, validated or
  not) and cannot join the offered group (no usable `dw`, or `CallDirect.canJoin` refuses: Wi-Fi off, no permission,
  the radio held by a transfer). It runs after the busy and glare answers, so a phone in a call still says busy. Like
  C-3.3-7 it sends no RINGING and records no missed call.
- **Caller (C-3.3-9).** A live call placed without a kind (`CallKind.AUTO`), toward a peer carrying `CAP_WALKIE` in a
  build running the walkie, that gets `DECLINE rsn=no_route` before an ANSWER, is re-placed at once as a walkie
  session under a new call id (`CallManager.asWalkieInstead`). The live attempt is dropped without a CANCEL, since the
  callee already buried it, and without a record, so the thread gets one line, the mesh call's. The new `Live` keeps
  the attempt's `startedAt` and peer, so the system call carries over as it does for glare (`SystemCallSync.carried`)
  and moves to the loudspeaker as a walkie does. It happens once: the walkie attempt is never re-placed. A live call
  placed on purpose ends "No route between the phones", as it did.

No wire field, capability bit or reason code is added: `rsn = 4` already means "ICE failed or no network", and C-3.7-3's
relay refusal already sends it unrung. An older Android caller and today's iOS caller read it as that refusal: an
immediate "No route between the phones" instead of a 45 s ring and an answer that never connects.

**The alternatives.** Announcing "my Wi-Fi Direct is usable" in presence costs advert bytes, goes stale between adverts
and tells every listener which radios a person has switched off (#285's concern). Reading a Wi-Fi Aware sighting of the
callee as proof its Wi-Fi is on costs nothing on the wire, but a phone whose image has no Aware, or whose Aware is dark
(ICM, Doze), would lose live calls it can take today, Motos among them. Reviving the parked drop to a walkie
(C-3.11-4's `m = 1|4`) would act after the answer, on the mechanism that stranded the P9↔P7 call. A RINGING that said
"no live path" would ring the person first and need a CANCEL whose callee records a missed call.

**What it costs.** The test is deliberately narrow. A callee with any network at all rings, because ICE may connect
over a LAN with no Internet or over a cellular route still validating. So a callee on a network that turns out to reach
nothing still rings and fails as before, and a Wi-Fi Direct join that fails on a vendor's stack is not covered either.
An older caller's attempt to an unreachable callee leaves no trace on the callee's phone, as C-3.3-7's refusal does.
The refusal is also a silent probe: an accepted contact (only one reaches `onCallSignal`) can send a live OFFER, from
any number of hops away, and learn without ringing that this phone has no network, and, with a `dw`, whether its Wi-Fi
Direct is usable and whether it sees the caller as a neighbour. Before, an offline callee rang, so the probe was seen,
and the caller learned the path was missing only after the person chose to answer. A stranger or a blocked contact
learns nothing. A missed-call record withdrawn when the same peer's walkie OFFER follows would make a probe visible
(C-3.7-3 leaves one); it was not taken here. knit-ios owes the same two halves: an iPhone has no Wi-Fi Direct, so an offline iPhone callee on Bluetooth
never connects a live call. `CallManagerTest`'s `aLiveOfferWithNoNetworkAndNoLegIsDeclinedNoRouteUnrung`,
`aLiveOfferThisPhoneCouldJoinStillRingsWithNoNetwork`, `aWalkieOfferRingsWithNoNetwork`,
`anAutoLiveCallDeclinedNoRouteIsRePlacedAsAWalkieOnce` and `aLiveCallPlacedOnPurposeEndsOnTheSameDecline` keep it
true.
