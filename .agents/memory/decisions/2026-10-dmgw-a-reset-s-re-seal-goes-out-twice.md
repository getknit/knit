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

## Amendment 2026-11-18 — a reopened id stays unmarked until it opens

Status: Draft (2026-11-18), GitLab #347. Node-local: no wire, vector, schema or iOS change.

**What was observed.** `SeenSet.reopen` removed the mark of a DM we could not open, once per id per window, and the
next copy the router admitted took the mark again. A copy of the original that reached the phone that reset in the ten
minutes before the second pass (a neighbour's flood, a carrier answering our digest once our own copy was evicted, a
spool) failed with its own reopen refused, and its mark dropped the second pass as seen. Every custody then held the
originals, the digests agreed, and the DM never showed. `RestoreLabTest` had met it as a flake (9b0527769) and was
deflaked, not fixed. #347 has the rest.

**The rule.** A reopened id stays unmarked until a copy of it opens.

- `reopen(id, sender)` keeps its refusals (a live mark, once per id per window judged from the last reopen's time) and
  leaves a guard: the sender, the reopen's time, and a count of tries per signature. `SeenSet.guardFor` reads it while
  it is unsettled and younger than `REOPEN_GUARD_MS` (two windows and two minutes, 22 min: it outlasts the second pass,
  which can leave up to a window after the failure), and `add` refuses a guarded id.
- `MeshRouter.handleGuarded` meets a copy of a guarded id ahead of the meter and the mark. Only the guarded sender's
  signed DM to us is delivered (`InboundPipeline.signedBySender`, which counts, asks and parks nothing); anything else is
  `DropReason.REOPENED_UNVERIFIED`. A verified copy is delivered whatever its bytes, up to `REOPEN_TRIES` (3)
  deliveries per signature, a signature's count refilling a window after its first try (`SeenSet.spendTry`); past that
  it is `REOPENED_SPENT`. At most `REOPEN_KEYS` (8) signatures are counted per id, the least recently tried evicted, so
  a new one always passes. A guarded delivery is never marked, relayed or counted as an overhear
  (`MeshMetrics.Snapshot.Frames.framesReopenedDelivered`). Copies of one id take turns on a per-id `Mutex`, so two
  re-seals never open at once, and one that waited past the settle reads the id as marked and dedups.
- The pipeline's `settleReopened(id, sender)` (`MeshRouter.settle`, `SeenSet.settle`) marks the id and settles the guard
  in one step, keeping its time, so a stale copy failing beside the one that opened is still refused its reopen inside
  the window. It settles only a guard naming that sender. It runs when a DM's step commits (the decrypt path and
  `ratchetOnly`), on each true answer of the exists-gate and on a row another sender holds. Never on `DUPLICATE`: a new
  era's frame can meet an old era's row (#135).
- The guard table holds `REOPENED_MAX` (1,024) ids, sized for a restore's backlog (custody's `DEFAULT_MAX_ROWS`, 1,000).
  A full table drops a guard that no longer guards first (lapsed or settled, oldest first), and only then the newest
  live guard of the sender holding the most, so one sender's reopens evict its own (`SeenSet.makeRoom`). An evicted
  live guard leaves its id unmarked, and the next copy that fails re-arms it.
- `persistWithInlineAcks` takes a row its own sender already holds, written by a sibling signed copy, as it takes
  another sender's: the step and the inline acks commit, the row is not saved again, and nothing is shown or acked
  twice when two re-seals open together.
- If the guard lapses with the DM still unopened, the next copy is marked, fails and re-arms it, as every id did before.

**Why each alternative strands the DM.**

- *A guard of one window.* The second pass leaves eleven minutes after the first, and the first can leave up to a
  window after the failure; a copy after a ten-minute guard marks the id inside the second pass's window again.
- *Release the guard on any verified signature.* A stale genuine re-seal (an earlier reset's pass, or `RESEND_OWN`)
  verifies and does not open, and releasing on it is the old strand with one more step.
- *Drop a copy whose bytes equal the one that failed* (#347's option 1). The same bytes can open later: a follow-up can
  carry its reset's init and arrive ahead of the reset (ADR 2026-11.sfms), the 60-min incidental floor can end by time
  alone, and a peek can race a commit.
- *A fixed cap of three per signature.* A follow-up met four times before its reset is adopted would be refused for the
  rest of the guard, a DM the old rule delivered once the mark lapsed. The cap refills instead, so every copy the old
  rule admitted at any point in the window still gets through.
- *Evict guards oldest first.* An honest restore's backlog, or one sender's many failures, would push out the guards
  of every other sender's DMs.

**What it costs.** For a reopened id, one signature verify per copy, and a delivery and a ratchet peek for at most
three copies per signature per window, inside 22 minutes; a guard holds eight signature counts, in a table of 1,024
ids. A full table costs one scan per new reopen. A DM refused before persist (a blocked sender, moderation) keeps its
guard to the lapse and pays that bound. On Bluetooth, `LinkCrossings` still withholds a same-signature repeat on one
link for a window. Regression: `SeenSetTest`'s guard and eviction cases, `MeshRouterTest`'s guarded cases (an evicted
guard among them), `RestoreLabTest.aCopyOfTheOriginalBetweenThePassesNeverCostsTheRestoredNodeTheSecondPass` (red
before this amendment), its twin and its two-custodian cases,
`ResetOverRelayLabTest.aFollowUpAheadOfItsResetOpensAfterItsSignatureHasMetTheGuardFourTimes`, and
`MaliciousForkLabTest.aFloodOfReopensFromOnePinnedIdentityNeverCostsAnotherSendersDmItsSecondPass`.
