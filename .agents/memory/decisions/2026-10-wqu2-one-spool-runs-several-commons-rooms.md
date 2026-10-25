---
id: "2026-10.wqu2"
slug: one-spool-runs-several-commons-rooms
title: "One spool runs several commons rooms, each named in HELLO by a one-way tag"
date: 2026-10-24
topics: [spool, commons]
---

# ADR 2026-10.wqu2 — One spool runs several commons rooms, each named in HELLO by a one-way tag

Status: Accepted (2026-10-24) — spec only, `docs/SPOOL_PROTOCOL.md` §7.4 (S-7.4-1/2/3/4/5 and C-7.4-11 amended;
S-7.4-15, B-7.4-16, C-7.4-17, C-7.4-18, S-7.4-19, B-7.4-20 new), §7.5, §10.1, §12, §13, Appendices A and B;
`docs/RELAY_INVITE.md` §3. Amends ADR 2026-09.wx8e's "one shared scope per spool". Neither the client nor
knit-spool implements it yet; knit-spool follows the spec, and the client's half (a room per secret, not per URL)
is a separate change.

**What was observed.** knit-spool was asked to run more than one room on one box. Nothing cryptographic stood in
the way: the scope id and the seal keys are both derived per secret, so two invites already give two independent
rooms, and no §13 derivation row moves. What could not stretch was the description. HELLO's `commons` is one map,
and turning it into an array breaks every decoder that predates the change, on the type. And S-7.4-1 forbade the
scope id "in any form", which left a member with no way to tell its own room's entry from another's.

**What changed.**

- **A list beside the map.** A new optional HELLO field, `rooms`, lists every room, each entry
  `{ tag, name?, maxFrames, ttlMs, maxBlob, attach? }`; `commons` stays, carrying exactly `rooms[0]` without its
  tag, so a client that predates the list still sees one room. A spool running several rooms sends both; one
  running a single room may keep sending `commons` alone, and should add `rooms`.
- **The tag.** `SHA-256("knit/spool/v1/commons-tag" ‖ scopeId)`, first 8 bytes. The spool computes it from the id
  it is configured with — it can compute anything from the id and nothing from the secret, and implements no KDF —
  and a member from the id it derives. A `sub` takes the id, which a 64-bit prefix of a hash of a uniformly random
  256-bit id does not give back, so a tag lets nobody subscribe and S-7.4-1 now allows the id in that form only.
  The width is chosen for accidental matches alone: a spool refuses to run two rooms with one tag (S-7.4-15), and
  a rotated room's tag matching a live one is about R / 2⁶⁴ for R rooms. Rejected: matching by name (not unique,
  not a trust boundary) and learning the bounds from the DIGEST (it echoes bounds, S-6.2-3, but not `attach`, which
  C-7.4-14 needs before any `aput`, and it costs a `sub` for an id the spool may not run).
- **Per-room limits under a spool-wide one.** Bounds and `attach` are per room. The push budget is per room
  (20 pushes/s, burst 4×, a one-room spool's budget unchanged) under a spool-wide total (40/s, burst 4×), both
  refused `err rate` with `retryMs` and never a strike, and a push refused by one is not charged to the other. The
  total is there because a spool's egress is the sum over rooms of rate × members: a per-room cap alone grows the
  box's egress with every room the operator adds.
- **Moderation stays spool-wide** (B-7.4-20): no per-room form, so no room entry can carry a flag a client would
  ignore under B-2-2.
- **The directory.** A room list in HELLO tells whoever connects how many rooms there are and their names. Names
  are optional per room, and a spool with no access token should not advertise them (S-7.4-19); the invite link
  carries its room's name anyway. §10.1 gains the bullet, including that the operator now sees which room each
  connection is in, where it used to see only in or out.
- **An invite is never a rotation** (C-7.4-18). The app as built reads a second secret at the same relay as the
  room rotated and leaves the first room with its history (`docs/RELAY_INVITE.md` §3). With several rooms that
  destroys a room the member still belongs to, so only the spool says a room is gone: its tag absent from `rooms`,
  or the existing TTL probe on a spool that sends no `rooms`.

**What it costs.** A client that predates `rooms` sees `rooms[0]` on every spool. In another room it takes the
wrong name and `attach`; its rotation probe (`ScopeSync.answeredAsNoCommons` compares the echoed `ttlMs` with
`commons`'s) marks a live room stale unless the room's `ttlMs` equals the first room's; and joining a second room
where it already holds one deletes the first. The spec documents this rather than telling spools not to run a
second room: a spool cannot see which clients its members run, the commons is hidden in this app's release builds
(`BuildConfig.COMMONS`), and the iOS port has no commons client, so the exposed population is development and test
builds. Operators keep the existing room first, give every room its `ttlMs`, and hand a second room's invite only
to members on a build that reads `rooms` — none yet. Since no room is shed under the watermark (S-7.4-3), the
daemon has to budget storage for the sum of the rooms' pinned footprints. The §13 rows (`commonsTag`,
`commonsScopeId2`, `commonsTag2`, `helloSpoolRooms`) were computed by a throwaway encoder checked against
`helloSpoolCommons`, `helloSpoolCommonsAttach` and `commonsScopeId`; no test pins them until each implementation
lands its half, and that test is what keeps the two in step.
