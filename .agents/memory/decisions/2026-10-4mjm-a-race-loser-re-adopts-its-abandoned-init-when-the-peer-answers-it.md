---
id: "2026-10.4mjm"
slug: a-race-loser-re-adopts-its-abandoned-init-when-the-peer-answers-it
title: "A race loser re-adopts its abandoned init when the peer answers it"
date: 2026-10-29
topics: [crypto, pfs, recovery]
---

# ADR 2026-10.4mjm — A race loser re-adopts its abandoned init when the peer answers it

Status: Draft (2026-10-29; issue #189, "rule C" in its 2026-10-29 comment; `RatchetEngine.SessionState.prevRootOwnInitAt`,
`OpenContext.sealedSinceLostRace`, `RatchetEngine.reclaim`, `RatchetSessions.sealedSinceLostRace`, the
`ratchet_sessions.prevRootOwnInitAt` column, DB v29; spec FSR §7). Amends ADR 2026-09.pz9g's "era effects only under the
active root", closes ADR 2026-10.j67x's "not covered" init and ADR 2026-10.x9fa's #189 residue.

**Context:** a pair split onto two roots by a race and a restore. The mesh lab reached it without forcing
(`RestoreLabTest` under `KNIT_LAB_BOARDS=1`, seeds 2000 to 2009, before x9fa's answer gate), and
`LoraDirectLabTest.aLoraFirstContactLeavesNoInitInAnyTickAndSurvivesARestore` was held under #189 for it. The trace:

1. Both phones open a session at once. A (the smaller id) wins with init `I_A`; B adopts it, and B's own init `I_B`
   becomes B's `prevRoot` (`RatchetEngine.resolveRace`'s loser branch).
2. A frame of A's carrying `I_A` stays in custody. Two doors: A's IntroSync answer to B's init (no message row, so the
   exists-gate never stops it), and an exists-gate re-ack riding a race-kept winner's init (x9fa's second door).
3. B restores from backup. A restore keeps no session, so B's reset `R_B` is minted from nothing, with no `prevRoot`.
4. A, confirmed on `I_A`, takes `R_B` as a flagged replacement and answers under it.
5. The re-served `I_A` frame reaches B while `R_B` is unanswered there. With no `prevRoot` the j67x guard cannot fire,
   `resolveRace` runs, A wins on node id, and B adopts `I_A` and purges `R_B`.
6. A sits on `R_B`, B on `I_A`. Each reads the other read-only, if at all, until a heuristic reset fires (three
   distinct frames, a 6 h floor).

It looked like a LoRa bug, because LoRa makes the race routine (a reply to a compact-first DM is a `sealDm` from no
session); it needs only a race and a restore inside custody's 24 h or the spool's 48 h.

**Decision:** the side that lost a race with its own unconfirmed init moves back onto that init when the peer seals
under it as responder, after the init we lost to.

- **The flag is a stamp.** `ratchet_sessions.prevRootOwnInitAt` (DB v29, `MIGRATION_28_29`, TRANSIENT like the rest of
  the table): the race-loser branch sets it to the abandoned init's own `establishedAt` (our clock, ADR 026), read
  before the branch overwrites `establishedAt` with the winner's `at`. Non-null is the flag. Every other write of
  `prevRoot` clears it: the replacement branch, the race-winner branch, `retirePrevRoot`, the swap itself and
  `RatchetSessions.reRoot` (the drained path included). Seal, `markReset`, `touch` and `commitSend` never touch it.
- **The trigger.** A frame opens under that `prevRoot` (`RootCandidate.reclaims`): the peer's role there is responder
  (it adopted our init), and the frame's `sentAt` is more than `MAX_FUTURE_SKEW_MS` past the session's
  `establishedAt`, which is the winning init's `at`. Both are the peer's clock; `sentAt` is AAD-bound on every DM path.
  The facade judges it in `contextFor` (`sealedSinceLostRace`), as it judges `predatesSession`, so the peek and the
  commit agree. It is evaluated on every rung — skipped key, live chain and fresh derivation — so a first frame of the
  answer's epoch read read-only inside the margin does not leave the later ones on `openLive` for good.
- **The swap** (`RatchetEngine.reclaim`, then `touch` with era effects on the trigger frame): the active root becomes
  that `prevRoot`, `confirmed`, `weAreInitiator`, `initEphPub` null, `establishedAt` restored from the stamp;
  `peerBasePub`, `peerBaseEpoch` and `highestPeAcked` restart and the trigger frame sets them; the send chain is
  dropped, so the next seal opens a new epoch (numbering stays monotone); the root we had adopted drains as `prevRoot`
  for a fresh 48 h with the peer as its initiator; the flag goes. `peerInitEphPub` stays the winning init's, so its
  re-serves stay inert, and `lastResetSentAt` is untouched (the 6 h floor and qerd's after-wipe decline read it).
- **What it purges.** The era abandoned: every receive chain and skipped key but the reclaimed root's
  (`OpenDelta.keepRecvTag`, `RatchetDao.deleteRecvEpochsForExcept` / `deleteSkippedKeysForExcept`). That root's rows
  are the era we hold next; an earlier frame of it may have been read read-only. The purge also stamps the facade's
  `lastReplacementAt` and clears the heuristic's count, as any root change, and the reclaim stamps `confirmedAt` as an
  answered init does. `rootChanges` fires (the export view moves), so the scope table and 8hn6's ledger see the era.
- **Observability.** `MeshMetrics.onRatchetReclaimed`, the STATE dump's `ratchetSwappedBack`; the RATCHET dump's
  `prevRootOwnInitAt`; a `RatchetSessions` log line, `reclaimed our own init with <peer>`.

**Why it is safe.** Only a peer that adopted our init can seal under it as responder: a race winner reads the loser's
root read-only and never seals under it (FSR §7). Only the loser acts, on its own row, so fielded peers behave as
today and nothing on the wire moved. A root that was confirmed and later replaced never qualifies, so a drain frame of
a dead era never swaps us back into it. The freshness half is the design pass's blocking finding: without it a pair
that converges today splits. B sends first-contact `I_B`; A adopts it and seals F under it; A restores and resets
`R_A`; B loses the race to `R_A` and flags `I_B`; F, re-served, is responder-sealed under `I_B`, but A no longer holds
`I_B`. F was sealed before `R_A`, and both stamps are A's clock, so F is never fresh. No ping-pong: the peer left our
init by a replacement or never adopted it, never by losing a race to it.

**Alternatives.** Refusing an unflagged init while our flagged reset is unanswered is unsafe one-sided: a fielded
winner still unconfirmed on that init keeps its root and waits, and we refuse it, so neither confirms. A flagged init
beating an unflagged one on both sides regresses mixed pairs and needs a capability bit or a flag day. A winner whose
intro answer carries no init cannot seal at all (it holds only its unconfirmed root), so it would stop answering, which
removes pz9g's convergence path. Keeping every era's peer init ephemeral (#189's option 1, a `prevPeerInitEphPub`
column) cannot reach a restored side: it holds no session row. A boolean flag (the first draft) cannot restore
`establishedAt`, which ADR 026 reads as our clock whenever we initiated.

**Pair walk.**

- *The trace.* B loses `R_B` to `I_A` and flags it; A answers under `R_B`; B swaps back. Both on `R_B`, B its confirmed
  initiator draining `I_A`, A its responder draining `I_A`. Had A been still unconfirmed on `I_A`, it would have won
  the race against `R_B` and never sealed under it: B stays on `I_A`, as today, and the pair meets there.
- *#189 as filed* (an init from an era older than `prevRoot`, re-served while our reset is unanswered): the race still
  runs and we adopt the old era, flagging our pending reset; the peer adopts that reset and answers it, and the answer
  swaps us back. Rule C covers it; option 1 is not needed for convergence. It also closes #184 from the loser's side.
- *An adgd re-add by the smaller id; both phones restored; flagged resets crossed.* The winner keeps its root and never
  seals under the loser's: no swap.
- *Talk frames* (`InboundPipeline.handleTalk`) pass no `sentAt` and never swap; neither does any open without one.

**Consequences / limits:**

- A flagged reset from the peer inside the minute after a swap is refused (the swap's purge stamped
  `lastReplacementAt`), dropped as `DUPLICATE` and re-served later: the peer's own floor is six times that.
- **Cross-clock replacement door** (design pass, residue): the restored B is confirmed on `R_B` before the `I_A`
  re-serve, and A's clock runs ahead of B's by more than the race-to-restore gap. `I_A.at` then beats B's
  `establishedAt` (B's clock), the replacement branch adopts `I_A` and clears the flag, and nothing swaps back. The pair
  then reads each other read-only for up to 48 h (the drain), until a reset heals it. Not built: setting the flag there
  too, when the replaced root was our own init, is the design pass's optional extension.
- **A clock stepped back across a restore** defeats the freshness half: a frame the peer sealed before its reset but
  stamped later than it would swap us onto a root the peer left. The pair then heals by the heuristic, as before.
- **Freshness is weak against a re-served *old* winner** (crypto-reviewer, 2026-10-29). In #189's three-era shape with
  two restores inside custody's window, the init we lose to can be an old one, `I_A0`; a frame A sealed after `I_A0`
  but before A's own later restore then counts as fresh, and moves B onto `R_B` after A has already left it for its
  reset `R_A`. Bounded: from minutes to an hour, until B adopts A's flagged `R_A`, which replaces the reclaimed root
  as any reset does. No code change; the freshness half compares against the init we lost to, never a later one.
- A session that lost its race before the upgrade has no stamp (the migration leaves it null) and never reclaims.
- An exception to pz9g: the reclaiming frame opens under a non-active root and still moves the era, because that root
  becomes the active one in the same delta.
- knit-ios has no DM ratchet, so nothing is paired; FSR §7 carries the rule for a future port.

Regressions: `RatchetEngineTest`'s rule C cases (the swap; no swap at or inside the margin, on an initiator-sealed
frame, under a confirmed-then-replaced root, or after crossed resets; the live-chain and skipped-key rungs; the flag
cleared by a replacement and an expired drain; the fifth `establishedAt` site in
`establishedAtIsOurOwnClockExactlyWhenWeAreTheInitiator`), `RatchetSessionsReclaimTest` (the trace across a store
reload, the second door, #189's three eras, `reRoot` clearing the flag, the reset declined inside the minute, the
pure rule), `KnitDatabaseMigrationTest`'s 27 → 28 case, `RestoreLabTest.aRestoredRaceLoserWhoMeetsTheWinnersOldInitFirstMovesBackOntoItsReset`
(fails without the swap), `RestoreLabTest.anAnswerSealedBeforeTheRestoreThatWonTheRaceNeverMovesTheLoserBack` (fails
without the freshness half), and `LoraDirectLabTest.aLoraFirstContactLeavesNoInitInAnyTickAndSurvivesARestore`, no
longer ignored (fails without the swap at "the restore's reset was never answered").
