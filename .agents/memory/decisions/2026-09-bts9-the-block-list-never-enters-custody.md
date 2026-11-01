---
id: "2026-09.bts9"
slug: the-block-list-never-enters-custody
title: "The block list never enters custody: a blocker carries a blocked sender's frames like every other node"
date: 2026-09-14
topics: [custody, convergence, moderation, privacy]
---

# ADR 2026-09.bts9 — The block list never enters custody: a blocker carries a blocked sender's frames like every other node

Status: Accepted (2026-09-14). Amends ADR 010 (the block list also leaves `canCarry` and `KeyExchange.want`,
and a message sent during a block stays unseen after the unblock). GitLab work item #45.

**What was observed.** The mesh-in-a-box lab, 2026-09-14, first run of
`BlockAndRequestLabTest.aBlockedSendersRoomPostIsStillAckedAndBlockingStaysInvisible`: Bob blocks Alice, Alice
posts in the Nearby room, Bob's room never shows the post and Alice still gets Bob's ✓✓ — the delivery half of
ADR 010 held. The custody oracle failed: `InboundPipeline.canCarry` returned false for a blocked author, so
Bob's live custody set lacked every frame Alice sent while Carol's held them. The digest exchange is
push-based (`ForwardSync.onDigest` sends a peer every live frame it lacks, and never asks `authenticate`;
only `onSeen` does), so Carol re-served Alice's frames to Bob on every exchange and Bob refused them every
time. Bob's `liveFingerprint` could never equal anyone's for as long as the block stood — the "digests
diverge, the cue plane churns forever" class `rules/mesh.md` exists to prevent, and it was read as a
finding about the lab rather than a rule the code had broken. Two documents disagreed and the code followed
the wrong one: ADR 010 says blocking is "never folded into custody/relay"; `context/store-and-forward.md`
said a carrier stores a frame only from a sender who is "pinned, **not blocked**", and
`InboundPipelineTest.canCarryRefusesABlockedSender` pinned that sentence. The router already relayed a
blocked sender's frames live ("so a blocked user stays a working peer"); custody is a delayed relay of the
same frames, so the refusal bought nothing the relay did not already give away.

**What changed.** Blocking is presentation only. The block check is gone from `canCarry`, which now admits a
frame on the pinned key and a byte-exact signature alone — the same rule on every node, which is what a
content digest needs — and a blocked sender's frames are carried, re-served and accounted like anyone
else's, on the radios and on the spool plane (`ScopeSync.acceptClaimed` / `acceptCommonsPost` run the same
gate, so a blocked member's spool frame is accepted into custody and then dropped at the delivery door
instead of being quarantined for the connection). `KeyExchange.want` no longer skips a blocked peer's key
either: its reason was "we drop its frames anyway", and a blocker that has not pinned the sender (a DB wipe
with the DataStore block list intact; a device-tag continuity block on a re-keyed identity) would otherwise
sit in the same divergence until the profile happened to re-serve. The request goes to neighbors, never to
the blocked peer, so nothing leaks. The block list is now read in exactly one kind of place: the local
delivery path — `handleChat` (which still ticks a room or group post, ADR 010), the reaction, commons,
group-leave and roster doors, the notification count, and the DAO's read filters. Since ADR 2026-11.nsbf it is also
read for presentation: the profile writer's name, status, flag and photo, the photo's pull, adoption and push doors,
and `mesh/BlockListWatch` — never custody, relay or a key want.

The alternative a reader reaches for first is to keep the refusal and make it convergence-safe: fold the
ids a node refused-by-block into its digest as an accounted band, the way `ScopeSync` accounts a spool
frame it holds no bytes for (spec §9.6). It cannot work here: the digest is a hash over a *set*, and the
refused set is derived from a per-node input, so two blockers with different lists — or one blocker and
everyone else — still hash differently; the band only hides *which* frames differ. The other alternative,
documenting the churn as accepted, would have cost every phone that blocks an active room poster its NAN
cue plane for the life of the block.

The corollary, decided with it: **a message sent while its sender was blocked stays unseen after an
unblock.** Before this change such a DM trickled in by accident — dropped on the delivery path but marked
seen by the router, then re-served by a peer once the ten-minute seen window lapsed, if a peer still held
it. Now the blocker holds it in custody himself (an addressed DM is self-custodied so the digests converge,
`InboundPipeline.onDeliver`), so nobody re-serves it and there is no replay from own custody on unblock.
That is what the confirm sheet already says ("You won't receive messages from them") and what every other
messenger does. The replay-on-unblock alternative (feed own custodied frames from that sender through
`onDeliver`, the `PendingInbound.release` shape) was rejected as new machinery — ordering, group seeds that
died at the blocked gate, a late ✓✓ — for a behaviour nobody had asked for.

**What it costs.** A blocker spends custody on the blocked sender's frames, bounded by the frame-global
quotas every node applies alike (`ForwardRepository.DEFAULT_MAX_PER_SENDER = 200`, the group, broadcast and
global caps, the TTLs — ADR 006), and the Your mesh screen's "carrying right now" counts them. What it does
not cover: the pinned-key half of `canCarry` remains the one per-node custody condition, and it is
legitimate because it self-heals — the sender's custodied profile or a `keyreq` pins the key and the next
exchange converges. The trap: any new per-node input in `canCarry` — a setting, a moderation verdict, a
version — reopens this exact class; a local decision is a *delivery* gate (`docs/WIRE_COMPAT.md` rule 5), and
`rules/mesh.md` now says so. Kept true by `BlockAndRequestLabTest` (un-ignored; Bob rides the custody oracle
as a carrier), `TimeLabTest.aDmReceivedWhileBlockedStaysUnseenAfterTheUnblock`, and
`InboundPipelineTest.canCarryStillCarriesABlockedSender`.
