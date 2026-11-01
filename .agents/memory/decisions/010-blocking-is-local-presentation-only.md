---
id: "010"
slug: blocking-is-local-presentation-only
title: "Blocking is local presentation only — a blocked sender's broadcast/group message is still acked"
date: 2026-07-08
topics: [privacy, custody, moderation]
---

# ADR 010 — Blocking is local presentation only — a blocked sender's broadcast/group message is still acked

Status: Accepted (2026-07-08). Amended 2026-09-14 by ADR 2026-09.bts9: the block list also leaves `canCarry` and
`KeyExchange.want` (it was read there, against this ADR's own rule, and diverged a blocker's custody digest for
the life of the block — work item #45), and a message sent during a block stays unseen after an unblock.
Amended 2026-11-01 by ADR 2026-11.nsbf: a block also holds the blocked person's profile presentation — their name and
status stay as they were, their face is the monogram and their photo is never fetched — and the unblock applies their
held profile again. Still presentation only: what the mesh carries is unchanged.

Blocking suppresses *surfacing* (persist / notify / group-roster reconcile) but must not change what the
mesh observes about delivery. `InboundPipeline.handleChat` therefore still sends the best-effort
broadcast/group delivery tick for a blocked sender (`ackBlockedRoomChat` → `AckSync.owe`); it only skips
the local surfacing. Two reasons: (1) blocking must stay **invisible** to the blocked party; (2) that
broadcast/group receipt is a *fragile* unicast `relay = false` tick (unlike a DM's flooded, custodied
one), so when the blocker is the sender's only reachable acker, dropping it strands their Nearby/group
✓✓ forever — the observed 4-phone bug. A **DM is deliberately still not acked**: its receipt floods and
is custodied (real delay-tolerance, no single-hop trap) and acking it would also vaccine-purge it from
mesh-wide custody. This is a local-delivery-path decision (like ADR 009), never folded into custody/relay,
so it is not convergence-critical. Regression tests: `InboundPipelineTest` (broadcast/group acked, DM not).
