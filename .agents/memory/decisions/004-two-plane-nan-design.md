---
id: "004"
slug: two-plane-nan-design
title: "Two-plane NAN design (cue plane + ephemeral NDP) with an accept-any responder"
date: 2026-07-07
topics: [mesh, nan, concurrency]
---

# ADR 004 — Two-plane NAN design (cue plane + ephemeral NDP) with an accept-any responder

Status: Accepted · Re-audited 2026-07-04 (`docs/NAN_CONCURRENCY_REAUDIT.md`)

`maxNdiInterfaces == 1` is per-*role*: a single accept-any responder multiplexes many inbound NDPs, but
each initiator needs its own NDI. So coordination rides Wi-Fi Aware messages (no data path) and data rides
one ephemeral NDP brought up only for a digest-differing peer. Per-peer responders don't compose. Detail:
`context/mesh-transport.md`. A concurrent-serve redesign is proposed in
`docs/NAN_CONCURRENCY_REAUDIT.md` §5.

## Amendment 2026-11-08 — the SSI's caps pick a link's form (ADR 2026-11.8mhs)

The accept-any responder tells a sealed link from a plain one by the first record (`KEY_SHARE` or `HELLO`), so it
still accepts without knowing the peer. A dialer opens with the sealed form when the SSI it dials from carries
`CAP_LINK_SEAL`: the one decision the SSI's caps make about the handshake (the proof decision still reads the reply's
caps). The fixed PMK stays; it keeps the data path off open air and now protects legacy links only.
