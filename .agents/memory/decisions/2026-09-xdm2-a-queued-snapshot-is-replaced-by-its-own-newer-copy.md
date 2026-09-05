---
id: "2026-09.xdm2"
slug: a-queued-snapshot-is-replaced-by-its-own-newer-copy
title: "A queued snapshot is replaced by its own newer copy"
date: 2026-09-04
topics: [lora, airtime, mesh]
---

# ADR 2026-09.xdm2 — A queued snapshot is replaced by its own newer copy

Status: Accepted (2026-09-04; `OutboundFrame.supersedes`, `LoraPacePolicy.enqueue`/`lastSuperseded`,
`LoraMeshTransport.profileKey`/`supersedeKeyFor`)

**What was observed.** On the lab fleet, the ADR 044 gateway election stopped settling: both boards in one
pocket held `role = ACTIVE` indefinitely, `gatewaysHeard = 0` on one of them for 25 minutes and counting. It
was found while verifying the LongFast bridge's outbound half (ADR 2026-09.7r4d), where it is expensive rather
than merely untidy — two ACTIVE gateways transmit every public post twice, and each then hears the *other's*
copy off the air and re-ingests it as a stranger's post, so one message rendered as three.

The offer was never *refused*. It was never **chosen**. pixel-7's pacer queue held **28 profile frames**, all
held back by ADR 056's bootstrap share, and the dequeue picks the lowest `FrameClass` ordinal —
`BOOTSTRAP(0) < GOSSIP(1)`. The transmit log says it exactly:

```
19:29:01  fanout:profile ×2
19:43:59  fanout:profile ×2      ← WINDOW_MS later
19:59:01  fanout:profile ×2      ← WINDOW_MS later
```

Every time the 15-minute rolling window freed bootstrap air, the pacer spent it on two more profiles;
`loraOfferSent` stayed at 0, `queued` at 28, `loraSent` frozen at 9. Force-stopping the app cleared the queue,
one offer escaped within three minutes, and the far board flipped to `PASSIVE` in the same minute. So the
failure is **state-dependent, not build-dependent**: whichever process gets its first offer out before the
queue fills keeps its role forever, and one that loses that race never offers again. An earlier read that
blamed the branch's own LoRa changes was wrong, and so was a reading of 18 distinct profile publishes that in
fact spanned seven hours of log rather than 35 minutes.

**What changed.** `OutboundFrame` gains `supersedes: String?` — a key naming *the thing this frame is a
snapshot of* — and `LoraPacePolicy.enqueue` drops any queued frame carrying the same key before it decides
anything else. Two classes of frame are snapshots and both now use it: the OFFER (`"offer"`, one publisher so
one key) and a profile (`"profile:<authorId>"`, per author because two peers' profiles are different state and
neither replaces the other). Supersession runs **before** the queue-full check, so replacing a stale copy can
never cost an unrelated frame its slot, and it is **never a drop** — nothing is lost that the newer frame does
not already carry, so `loraDroppedQueue`, whose job is to say when the plane shed something it wanted, must not
move.

This replaces `dropQueued(FrameClass)`, which was the same idea applied to one class from the call site. One
mechanism rather than two, for the reason `MeshManager.bridgeCandidates` gives about `LoraFramePolicy`: a rule
stated twice drifts.

**Why here rather than in the class order.** `LoraAirtime.admits` already exempts a `GOSSIP` frame from the
BRIDGE share, and says why in so many words — *"the OFFER is not backfill: it is the one packet that decides
whether any backfill happens at all… So serving must not be able to starve it."* The **budget** protected the
offer and the **queue** did not, and that asymmetry is the whole bug. The alternative fix — letting a waiting
`GOSSIP` frame outrank `BOOTSTRAP` after some age — makes the class order conditional, which is a much larger
idea than it looks and would need its own answer for every other pair. Bounding the backlog instead means the
class order stays a total order and simply has nothing stale to prefer.

**What it costs.** A profile that was superseded out of the queue has already burned its `sigSeen` slot
(`fanout` adds it before enqueuing), so if the newer copy then fails to ride, the older one cannot be re-fanned
for the 10-minute dedup window. Acceptable: the newer copy is the one the far side wants under newest-wins, and
a genuinely lost profile is repaired by the bridge's digest-driven backfill (`serveOne`), which is the path
that exists for exactly this.

**What it does not fix.** The backlog had a source — profiles arriving faster than a 25 % bootstrap share can
carry them — and this bounds the queue rather than that rate. Bounded, the depth is one per author, so the
drain is finite and the offer's wait is bounded by it; unbounded, it was permanent. Why four devices in one
pocket republish often enough to outrun the share is still open, and is worth measuring before ADR 056's share
is touched.

**The trap.** `aProfileBacklogCannotStarveTheGatewayOffer` (`LoraMeshTransportTest`) reproduces the whole
failure end to end — twelve republishes, then assert the offer reaches the air — and it was checked to **fail**
with the profile key removed, because a starvation test that passes either way pins nothing.
`aProfileSupersedesOnlyItsOwnAuthorsOlderCopy` guards the other direction: one key for the whole class would
drop a peer's only profile whenever anybody else republished, and that profile is the bootstrap without which
the far side can decrypt nothing.
