---
id: "2026-10.dmgw"
slug: a-reset-s-re-seal-goes-out-twice
title: "A reset's re-seal goes out twice, the second past the routers' dedup window"
date: 2026-10-16
topics: [crypto, custody]
---

# ADR 2026-10.dmgw — A reset's re-seal goes out twice, the second past the routers' dedup window

Status: Accepted (2026-10-16)

**What was observed.** GitLab #171, found reading the code while porting the ratchet to knit-ios and pinned
there on three engines in a line. When a peer's `CTL_SESSION_RESET` commits, `MeshManager.resealRecentDmsTo`
re-seals our unacked DMs of the last 24 h under the fresh session and floods each under its **original** id and
`sentAt` (FSR §7). Its KDoc said this "puts recoverable copies back on the mesh". It does not, behind a relay:

- `MeshRouter.handleInbound` drops any id its `SeenSet` saw in the last `SeenSet.DEFAULT_TTL_MS` (10 min), and
  `SeenSet.add` never refreshes a duplicate's stamp, so the window runs from first sight.
- Only the recipient that failed to open the frame lets one more copy past its router (`SeenSet.reopen`).
- Every custody keeps the first bytes it stored under an id (`ForwardSync.onSeen` returns at `store.has`), the
  reset peer's own included, and digests compare ids, so no later exchange carries the re-seal.

So a peer two hops away, behind relay R, never reads the DMs that crossed R within ten minutes before its reset —
which for a heuristic reset are exactly the three whose failures fired it, and after a restore everything we sent
in the ten minutes before the restored phone came back behind a relay. Its later frames open under the new
session, so nothing fires another reset. `RestoreLabTest` linked the pair directly, the one case that worked.

**What changed.** The re-seal goes out twice. `resealRecentDmsTo` records, per peer and in memory, the ids it
re-seals — before the first copy leaves, so anyone who sees a copy sees the pass owed — with `dueAt = now +
ResetReseal.SECOND_PASS_MS` (`SeenSet.DEFAULT_TTL_MS` + 60 s, tied so they move together); a later reset of the same
peer replaces the entry. The 60 s re-offer tick (`MeshManager.reofferToNeighbors`, its first step, outside the
link-set guards) drains each due entry once: it removes it, then re-seals only the recorded ids `unackedDmsTo` still
returns — not one acked since, not a DM sent after the reset. The second pass records nothing, so it cannot re-arm.
Clock-driven, not a `delay`: the router's `SeenSet` reads the same injected clock, and a lab clock jump moves
decisions, never schedulers. Any sight of the id a relay had before the reset is then over eleven minutes old, so
the second copy crosses it. Sender-local, no wire change.

Alternatives:

- *Let a router pass one copy per window whose signature differs* (as `SeenSet.reopen` does for the recipient and
  `FrameKey` for the link plane). A change on every relay that helps only once the relays have upgraded, and it
  lets any sender buy each relay one extra forward per id per window. Custody still keeps the originals.
- *Re-seal under a fresh frame id*, the original inside the sealed content for the receiver's exists-gate. A wire
  change (`docs/NEXT_WIRE_BREAK.md`), and an older receiver would show the DM twice.
- *Correct the docs and accept the limit* — done too (the re-seal's KDoc, `SeenSet.reopen`'s, the `reopenFrame`
  rationale in `InboundPipeline`, FSR §7), but on its own it leaves the traced case lost.

**What it costs.** One more seal (one chain key) and one flood per DM still unacked about eleven minutes after a
reset, once per reset; a further reset inside the window replaces the pending pass rather than adding one. On a
peer that already read the first copy but whose ✓✓ has not reached us, the exists-gate stops the second before
decrypt and re-acks it, leaving one chain index unread there: a skipped key only if a later frame of that epoch
opens, and one that opens no stored ciphertext, since every custodian kept the original. If that peer deleted the
DM in the meantime, nothing tombstones it, so the second copy opens on its fresh index and the message comes back
once; the first pass had the same exposure for seconds, this widens it to eleven minutes. What it does not cover: a
peer reachable live at neither pass still misses those DMs, since custody serves only the original bytes; a relay
that first sees the original *after* the reset (a custody pull from a neighbour inside the window) starts its own
window then, which can cover the second pass too, and only the router or wire alternatives above would close
that; and the pending pass lives in memory, so a process restart inside the window loses it. The trap:
never let the second pass record an entry of its own (a re-seal loop that burns a key per DM every eleven minutes),
and never move the drain under `neighbors.isNotEmpty()` or onto a `delay`. Regression:
`MeshManagerTest.aResetsResealGoesOutOnceMoreAfterTheDedupWindow`, `RestoreLabTest`'s relayed-restore scenarios.

## Amendment 2026-10-29 — the second pass's re-ack is bounded per era (ADR 2026-10.8hn6)

"What it costs" says that on a peer that already read the first copy but whose ✓✓ has not reached us, the exists-gate
stops the second copy and re-acks it. It still stops it before decrypt. It no longer always re-acks: a re-delivered DM
draws a new sealed receipt only in a new era, when the receipt the peer sealed for it has left the peer's own custody,
or once the receipt is older than its re-arm floor (15 min, doubling). The reset this ADR re-seals for is a new era,
so the first copy past it still earns one receipt under the new root. The second pass, eleven minutes later, finds
that receipt held, in the same era and inside its floor, and seals nothing. Off LoRa, the second pass now buys one
receipt in all at such a peer, where it used to buy two.
