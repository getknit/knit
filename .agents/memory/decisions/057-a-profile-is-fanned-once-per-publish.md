---
id: "057"
slug: a-profile-is-fanned-once-per-publish
title: "A profile is fanned once per publish, and a lost one is repaired by the digest, not by repetition"
date: 2026-08-28
topics: [profile, mesh, convergence]
---

# ADR 057 — A profile is fanned once per publish, and a lost one is repaired by the digest, not by repetition

Status: Superseded by ADR 2026-10.rp3s (2026-10-29) — no profile rides the plane, so neither the re-fan gate nor its digest repair exists.

ADR 056 bounded what the LoRa key bootstrap can spend. This is the other half: why it was spending it. Of the
376 frames the lab gateway had ever put on the air, 296 were profiles — and they were not 296 different
facts. They were a handful of profiles, re-fanned over and over.

**The mechanism.** A relayed `profile` reaching `LoraMeshTransport.fanout` was gated by exactly one thing:
`sigSeen`, whose TTL is 10 minutes because it is a flood-suppression window. `MeshRouter`'s `SeenSet` lapses
on the same 10 minutes, so a profile that keeps arriving over BLE/NAN looks first-seen again on every lapse,
`InboundPipeline` re-fans it, and the LoRa side has nothing left to say no with. `LoraFramePolicy.isFresh`
cannot help: it deliberately exempts a profile from the staleness check, because a profile's `sentAt` is a
publish stamp that is hours old by design and refusing the key bootstrap for being old is exactly wrong. So
the same publish rode a ~1 kbps shared medium every ten minutes, indefinitely, at ~4.75 s a time.

**The decision.** A second dedup, `profileSeen`, keyed on the frame **id** — which
`MeshManager.currentProfileEnvelope` derives from the publish stamp, so it is stable for a publish and new
for the next one — with `PROFILE_REFAN_MS` = 12 h, the author's own `PROFILE_REPUBLISH_MS`. Anything shorter
only re-sends bytes the horizon already has, and anything longer is moot because a republish mints a new id
and rides on its own merits. The two sets answer genuinely different questions on genuinely different clocks
("is this frame in flight right now" vs "does the LoRa horizon already have this profile"), which is why this
is a second set rather than a longer TTL on the first.

Checked **before** `sigSeen` and **after** `encodeOrNull`, both deliberately: a held-back profile must leave
the signature slot free for the backfill to take, and a frame that could not be encoded must not consume a
window it never rode.

**What repairs a genuinely lost profile, since repetition no longer does.** The bridge's digest-driven
backfill: a far gateway's OFFER names what it holds, `framesMissing` diffs it, and
`LoraFramePolicy.backfillRank` serves profiles first. `serveOne` is therefore **not** gated on `profileSeen`
— pinned by `LoraBridgeTest.theBridgeStillServesAProfileTheFanOutHasStoppedReOffering`, where a pocket that
comes up after the fan-out still gets a key it has no way to ask for (the plane refuses `keyreq`).

**Not gated: our own beacon.** `sendSelfProfile` keeps its 5-minute floor and its 60-second first-hearing
gap. Both are event-driven — a board link coming up, a peer heard for the first time, a backfill about to be
served — and the first-hearing case is precisely "a new listener appeared", which is the one time re-sending
an unchanged publish is the whole point. `beaconProfile`'s own doc has the case: A beaconed two minutes ago,
B just came up, A must speak again or B's parked frames expire.

`MeshMetrics.loraProfileRefanSkipped` counts what the gate holds back; against `loraSent` it is the direct
measure of the redundancy, and `…debug.LORA` reports it.
