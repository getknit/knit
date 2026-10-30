---
id: "2026-10.j67x"
slug: our-unanswered-reset-keeps-the-scope-it-replaced-and-may-refill-it-with-the-rese
title: "Our unanswered reset keeps the scope it replaced and may refill it with the reset alone"
date: 2026-10-16
topics: [crypto, pfs, spool]
---

# ADR 2026-10.j67x — Our unanswered reset keeps the scope it replaced and may refill it with the reset alone

Status: Draft (2026-10-16; issue #182; `RatchetSessions.pendingResetInit` / `exports`, `ExportedRoots.pendingResetInit`,
`ScopeRoots.pendingResetInit`, `Scope.resetInit`, `ScopeFrames.mayRefill` / `ownResetInit`, the `prevRoot` slot in
`RatchetSessions.reRoot`; spec `docs/SPOOL_PROTOCOL.md` C-3.1-4, C-3.1-5, C-4.4-3, §10.1; FSR §7, §8). Amends ADR 032's export bound and the spool spec's
C-3.1-3. Amended by ADR 2026-10.4mjm (2026-10-29).

**What was observed.** 2026-10-16, Pixel 9 (feat/voip-calling): at 15:51:28 it reset its sessions with three peers in
one second (the spurious resets ADR 2026-10.9wvr fixed). Two were in radio range, answered and were covered by the
relays again within seconds. The third, the Pixel 8, was away from home: `…debug.RATCHET` read `hasSession: true,
confirmed: false`, `…debug.SPOOL` listed no scope of that peer on any of three connected relays, the thread read "Not
covered by relays yet", and the Pixel 8's own new DMs — still sealed under the old session, into the old scope — no
longer reached the Pixel 9 either. It looked like a relay outage; it was the export rule.

`RatchetSessions.exportedRoots` skipped every unconfirmed session, and that took the session's `prevRoot` with it —
the root whose scope C-3.1-2 keeps for its 48 h drain. The new root's scope was off the table too, and the peer could
only learn that root by receiving the reset, which C-3.1-3 forbade sealing into the old scope, the only one the peer
was on. So a reset to a peer the relay alone reached waited for the radios, in both directions.

**What changed.** One named exception to the confirmed-only bound, and one to drain-never-refill:

- **The state.** Our own reset, unanswered, over a root it replaced: `weAreInitiator`, not `confirmed`, `prevRoot`
  present, `lastResetSentAt == establishedAt` (`RatchetSessions.pendingResetInit`). Read off the row alone — no clock,
  no new column: `initiate` and the reset's re-root stamp the two fields with the same `now`, a plain `sealDm` init
  leaves `lastResetSentAt = 0`, and a reset *marked* under a plain init (ADR 2026-09.qerd) stamps it past the init.
- **The export.** That session exports like a confirmed one — the new root active, the replaced root retiring — plus
  the reset's init ephemeral (`ExportedRoots.pendingResetInit`). The new root's scope is where the peer's answer lands
  (it is sealed under the root the peer just adopted); the replaced root's is where the peer still seals its own DMs.
  `ScopeRegistry` keeps the exception only while the replaced root's drain is open; past it the session is any other
  unconfirmed initiation and leaves the table until the answer.
- **The refill.** Both of the peer's DM scopes carry the ephemeral (`Scope.resetInit`). Into the retiring one
  `ScopeSync`'s push takes exactly one frame: our own reset to the scope's peer, read off the cleartext v2 header —
  `FLAG_RESET` set and the init's ephemeral equal to the pending one (`ScopeFrames.ownResetInit`, `mayRefill`). The
  pull side is the §4.4 rule, unchanged. The spool sees an ordinary sealed v2 DM; no daemon change.
- **A reset we left is pushed nowhere** (review finding 1a). A flagged reset of ours whose ephemeral is not the
  pending one — a race we lost on our own reset, a reset a later one superseded — goes into no DM scope, live or
  retiring. Before, the loser of a mutual reset pushed its own stale reset into its live scope (the winner's root),
  and a winner that had already confirmed on the loser's answer adopted it as a replacement: a pair on two roots. A
  pair scope (§3.5) is not the ratchet's and keeps the plain rule.
- **A second reset over our own keeps the drained root** (review finding 2). `reRoot` over our own reset, still
  unanswered and still draining, keeps that reset's `prevRoot` in the slot (FSR §7) rather than the first reset's
  root, which the peer may never have derived: the second reset then rides the scope the peer still pulls — it is on
  that root, or drains it if it did adopt the first reset. Cost: the first reset's era. An answer the peer sealed
  under it is lost; the peer adopts the second reset under the one-minute floor, and its recovery re-seals. The
  drain's 48 h restarts on each such re-root (FSR §10, spool §10.1), uncapped: the peer may still be on the drained
  root, and the reset floors bound the chain.
- **The replaced era's own init is no race** (review round 3). A re-root mints fresh state, so the old era's anchor
  (`peerInitEphPub`) goes, and while our reset waits we are an unconfirmed initiator: any peer init with an unknown
  ephemeral went to `resolveRace`, which has no `at` check. The relay feeds exactly that: the drained root's scope,
  subscribed for the wait, holds for 48 h every frame the peer sealed as that root's unconfirmed initiator, init
  attached (common, since the root is often the peer's own earlier reset); a ctl there has no message row, so the
  exists-gate never stops it, and `ScopeSync`'s accounted set is in memory, so after a restart the 24–48 h band is
  re-pulled. With a smaller-id peer we "lost", adopted the root we had left, and the peer adopted our reset: two
  roots. `RatchetEngine.resolveSession` now reads an init that derives our own `prevRoot` (still draining) under
  that root, read-only, before any race — **only on a frame that predates our session** (FSR §7): its `sentAt` more
  than the skew bound before our `establishedAt`, which is our clock since we initiated (ADR 026's initiator rule,
  `RatchetSessions.predates`, handed in as `OpenContext.predatesSession`). A frame sealed since is the peer *still*
  that root's unconfirmed initiator (review round 4): it races as before — a smaller-id peer wins and keeps the root,
  we adopt it — or both sides read each other read-only and nobody confirms. A peer reset that crossed ours carries
  a fresh ephemeral and derives a root of its own, so a genuine race is untouched. Residue, pre-existing and
  unchanged by the gate: when that live frame reaches us but the peer heard our answer *before* our reset, the peer
  confirms, adopts our reset as a replacement (the flag), and our race on its live frame adopts the root it just
  left — the same order problem as the engine half of finding 1 (#184), below. Two clock edges of
  `RatchetSessions.predates`, both bounded: a peer clock **slow** by d > 5 min makes its live frames sealed soon
  after our reset look predating, so they are read read-only and neither side confirms until d − 5 min have passed
  (bounded by the 48 h drain; nothing is lost, the frames open). A peer clock **fast** by d lets its stale frames
  from the d − 5 min before our reset race — the old bug, for that window only. Not covered: an init of an era older than `prevRoot` (no anchor
  survives for it; the `prevPeerInitEphPub` column it would take was not added). Regressions:
  `RatchetSessionsExportTest.theReplacedErasOwnInitReServedWhileOurResetWaitsIsNoRace`,
  `ResetOverRelayLabTest.theReplacedErasOwnInitReplayedByTheRelayIsNoRaceAgainstOurReset` (both fail with the check
  reverted, the lab one at "the resetter never confirmed on its own root", after it has proven the re-pull), and for
  the gate `RatchetSessionsExportTest.theReplacedErasInitSealedSinceOurResetStillRaces…` and
  `ResetOverRelayLabTest.theReplacedErasInitSealedSinceOurResetStillRacesAndThePairMeetsOnOneRoot` (both fail with the
  gate removed).
- **The event.** `sealResetDm` compares the view before and after like every other writer, so a re-root over a
  confirmed session reports a change (to the reset's view, no longer to "nothing"), and the answer's confirmation
  reports the return to the ordinary pair (the ephemeral drops out of the view). `RatchetSessions.rootChanges` keeps
  ADR 2026-09.dcah's rule: no report on an unchanged view.
- **One reader kept to confirmed sessions.** `MeshManager.broadcastSealedProfile` used the export list as "the
  confirmed peers"; it now filters on `ExportedRoots.confirmed`, so a sealed profile is never sealed on the
  unanswered reset (it would carry the init unflagged, #90).

**The reset alone, not every frame that carries its init.** Every frame sealed on the unanswered session carries the
same init (#90), and any of them would let the peer derive the new root. Only the flagged one is adopted under the
peer's one-minute reset floor and is exempt from its race-remnant guard (FSR §7, ADR 024); an unflagged copy reaching
the peer first meets the hour-long incidental floor or is read without adoption. And the peer needs nothing else from
the old scope: once it adopts, it derives the new one, where every frame of ours — the reset, the recovery's
re-seals, new DMs — already went. So the exception is one frame per reset, bounded by our own reset floors.

**Alternatives.** A standing pair scope for every contact (spec §3.5) as the fallback: it would carry the reset, but a
pair scope's id is stable per pair across eras, so keeping it subscribed for good gives a spool the cross-era
linkability §3.5 bounds on purpose. Waiting for the radios: the case the relay plane exists for. Exporting every
unconfirmed session: undoes ADR 032's bound for our first-contact inits, which the pair scope already carries.

**Pair walk.**

- *One side resets, the peer is on the old root (the field case).* We export the new root and the old one carrying
  the reset; the peer, confirmed on the old root, pulls the reset out of the scope it holds, adopts under the reset
  floor, derives the new root's scope (old one retiring) and answers (IntroSync's per-init answer, sealed under the
  new root). We pull the answer from the new scope and confirm: both on the new root's scope, the old one retiring.
- *Both reset at once.* Each exports its new root and the old root carrying its own reset, and each pulls the other's
  reset out of the old scope. The smaller node id wins: it keeps its root, `prevRoot` becomes the loser's (the
  one-slot overwrite #92 describes takes the pre-reset root out of the slot), and it keeps exporting — its reset now
  rides the loser's root's scope, which the loser subscribes as its own active one. The loser adopts the winner's
  root, confirmed: it exports the winner's root and its own only as the ordinary drain, carrying nothing. The
  winner confirms on the loser's answer. Both end on the winner's root's scope and the same retiring one — when the
  winner hears the loser's reset before its answer. In the other order the winner defects to the loser's root (the
  engine half, below).
- *A reset from no session* (a restore, a stranger's undecryptable frames): no root replaced, nothing exported, as
  before. *Our first-contact init*: unchanged, the pair scope carries it. *A session a peer initiated* is confirmed on
  our side the moment it opens, so it always exported and still does; the exception never applies to it.

**A table change that reshapes a scope heals it.** The custody change that carries our reset can wake a round
before the table re-derives (the collector runs on its own coroutine); that round sees the old scope still live and
the reset not yet pending, and — under the rule above — refuses it. The table that follows keeps every id, so
`ScopeSync`'s `adoptScopes` used to see no change and the reset waited for the 60 s tick (the chaos sweep: 14 runs in
20). A scope whose push set moved — retiring flipped, or the pending reset's ephemeral set or cleared — is now marked
dirty and wakes the worker; an unchanged table still wakes nothing (ADR 2026-09.dcah). Regression: `ScopeSyncTest`'s
"a scope whose push set changed with the table is healed at once".

**The engine half of finding 1 is not fixed here** (filed as #184). The copy of the loser's reset already in
the old scope — it was the pending one when the loser pushed it — still reaches a winner that has confirmed on the
loser's answer, and the winner adopts it: the session is confirmed, so it is not a race; its `init.at` (the loser's
clock) beats the winner's `establishedAt` (the winner's) about half the time; the winner adopted no replacement from
this peer, so the floor allows it; and `FLAG_RESET` exempts it from the race-remnant guard (`isRemnantOf`, ADR 024).
ADR 024's argument — "a reset abandons the losing root on the sender's side too" — fails when the sender lost the
race *on that very reset*: it abandoned its own root for ours. The same arrives over the radios from custody; the
relay only makes the unsafe order likelier. The winner's state is exactly `isRemnantOf`'s minus the flag (confirmed
initiator, no peer-init anchor, the peer the larger id). What would tell a race-time reset from a genuine later one is
single-clock on the loser's side: the race-time reset's `init.at` is before the loser sealed the answer that confirmed
us (both the loser's clock), a genuine later reset after it. That needs the confirming frame's `sentAt` kept on the
row (a column, or memory a restart loses — a restart then fails open, back to today's adoption) and a rule in FSR
§7, with the comparison strict (`init.at` before that `sentAt`, never at or after). Its cost is wider than races:
the state it keys on is `isRemnantOf`'s minus the flag, so it binds every later reset from a higher-id peer on every
session we started, not only a race-time one. It refuses a genuine reset whenever the peer's clock has stepped back
past the answer's stamp — a phone restored onto a device whose clock runs behind, or any NTP correction or manual
set — and each refusal is ADR 024's one-way blackout until our own heuristic fires. Not contained, so it is written
up, not built: `ResetOverRelayLabTest.aMutualResetWhoseWinnerHearsTheAnswerFirstEndsOnTheWinnersRoot` pins it, `@Ignore`d
under #184, and fails today on "the winner stayed on its root". The scenario fixes the unsafe order with `FakeSpool`'s per-scope
SUB hold (the winner's old-scope round waits on its SUB until the answer has confirmed it), so a future pass means the
engine fix works, not that the race fell the safe way.

**Spool-side observability.** The reset's blob lands in the old era's scope as the new era's scope is first
subscribed, from the same connection, so a spool can link the two eras by timing (spec §10.1). Not a new class — the
C-3.1-2 drain subscribes both from one connection after every replacement — but one blob marks the hand-over.

**Before the peer derives the new scope.** It is live on our side at once, so everything else that pushes into a live
scope goes there before the peer can read it: attachments, and `ScopeSync.pushDirect`'s room ticks, whose `true` tells
`AckSync` the tick went home when the peer cannot yet pull it (it can once it adopts; the spool keeps it 48 h).
`RelayReach` reads **pending** — "Not covered by relays yet" — while our reset is unanswered (the user's call,
2026-10-16): a live scope carrying the pending reset is no coverage (`ScopeStatus.pendingReset`,
`RelayReach.coveredLabelsOf`), so the thread notice says so and the attachment lines (`attachmentReach`,
`attachmentWait`, both read `reachFor`) promise no relay delivery; the answer turns it covered. This closes ADR
032's open coverage note for this case — the table said "we can push", not "the pair shares a scope". Regressions:
`RelayReachTest`'s pending-reset case, and `ResetOverRelayLabTest.aResetToAPeerOnlyTheRelayReaches…` (pending while
unanswered, covered after the confirmation).

**A withheld reset leaves a scope unconverged.** A reset of ours we no longer push stays in our custody, and so in
the scope's local fold, for its 24 h: where nobody else pushes it (a superseded reset the peer never saw) the scope
reads unconverged and lists once a tick until custody drops it. Over the radios the frame still flows, and is inert
at the peer (older than the session it would replace).

**What it does not cover.** A genuine split brain — the peer on a root we never held — gains nothing: our reset goes
into a scope the peer does not subscribe, as before. The row cannot tell whether the replaced root had confirmed. A
second reset over our own now keeps the drained root (above); one rare path still puts an unconfirmed root in the
slot — a reset that re-rooted a first-contact init past a prekey rotation — and the peer holds it only if it adopted
that init and its answer is still on the way. A first reset whose blob did reach the spool before a second one is
sealed leaves the peer two flagged inits to pull at once; if it takes the first, it refuses the second inside its
one-minute floor — qerd's existing hole, on one more path. Since the slot now holds the drained root, not the first
reset's, that cost is two-way until the peer's heuristic fires: the peer sits on the first reset's root, which we no
longer hold, so its frames no longer open for us either, and ours (under the second reset) fail at it. A strict "previously
confirmed" test needs a column (a `prevRootConfirmed`, migration and `KnitDatabaseMigrationTest` case) and was not
taken. While the reset is unanswered the thread reads as covered by the relays (the new root's scope is live), which
says we can push, not that the peer derived the scope — ADR 032's open coverage note.

Regressions: `RatchetSessionsExportTest` (the export, the race, the second reset, the first-contact and no-session
cases, the pure rule), `ResetOverRelayLabTest.aSecondResetPastTheFloorWithTheFirstUnansweredStillCrossesTheRelay`
(fails without the kept slot at "bob never adopted the second reset over the relay"), `ScopeRegistryTest` and `ScopeFramesTest` (the derivation, the refill, and a reset we left pushed nowhere), `ScopeSyncTest`'s carrier push,
`InboundPipelineTest.ourOwnResetRequestMovesAConfirmedPeerToTheResetsScopeView`, and
`ResetOverRelayLabTest.aResetToAPeerOnlyTheRelayReachesCrossesItAndThePairCarriesOnUnderTheNewSession` (fails on the
old export rule at "bob never adopted the reset over the relay").

## Amendment (2026-10-29, ADR 2026-10.4mjm): an init older than prevRoot is healed, not refused

The "not covered" init above — an init of an era older than `prevRoot`, re-served while our reset waits — still races,
and a smaller-id peer still wins it: we adopt that era and our pending reset becomes `prevRoot`. That reset is our own
init lost to a race, so the race-loser branch now flags it (`prevRootOwnInitAt`), and once the peer adopts the reset and
answers it, the answer moves us back onto it (rule C). No `prevPeerInitEphPub` column was added: it could not reach the
restored side, which holds no session row at all (#189's comment). Regression:
`RatchetSessionsReclaimTest.issue189sThreeErasAnInitOlderThanPrevRootIsHealedByTheReclaim`.

