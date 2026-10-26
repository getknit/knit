---
id: "006"
slug: convergent-custody-quota
title: "Convergent custody quota (frame-global `sentAt`, live-only, `ORIGIN_SELF` included)"
date: 2026-07-07
topics: [custody, convergence, store-and-forward]
---

# ADR 006 — Convergent custody quota (frame-global `sentAt`, live-only, `ORIGIN_SELF` included)

Status: Accepted (DB v18, `forward_store.sentAt`). Amended by ADR 2026-10.7mqm: the global cap evicts from the
largest sender bucket first, and custody refuses a group frame whose roster is not its own.

The cue plane brings up a scarce NDP only when two peers' content digests differ, so the custody bound
must be identical on every node or the mesh churns forever. Evict newest-N by frame-global `(sentAt, id)`
on every origin, fold live ids only. Makes TTL constants convergence-critical. Detail:
`context/store-and-forward.md`.
