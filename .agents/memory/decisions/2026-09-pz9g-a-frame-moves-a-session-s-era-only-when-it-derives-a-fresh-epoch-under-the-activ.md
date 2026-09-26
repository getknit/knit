---
id: "2026-09.pz9g"
slug: a-frame-moves-a-session-s-era-only-when-it-derives-a-fresh-epoch-under-the-activ
title: "A frame moves a session's era only when it derives a fresh epoch under the active root"
date: 2026-09-25
topics: [crypto, pfs, recovery]
---

# ADR 2026-09.pz9g — A frame moves a session's era only when it derives a fresh epoch under the active root

Status: Accepted (2026-09-25)

The mesh-lab chaos sweep (2026-09-24) turned up two DM losses with one shape: a frame arrived ahead of the
init that announces its session, and the engine read it as evidence about the era it holds now.

- **#83.** Both sides wrote first while apart. The loser adopted the winner's root and answered under it,
  its tick and its intro riding the live link, while its opening DM, sealed under the losing root, rode
  custody. The answers landed first, the winner confirmed on them without ever seeing the loser's init, and
  the opening DM then hit either the stale-init return or the race-remnant guard. Neither offers the
  loser's root, so the DM failed `AEAD_FAIL` for good: nothing re-serves or re-seals it, and one or two
  lost frames never trip the reset heuristic. Chaos seeds 1003 and 1010 on
  `CustodyLabTest.bothSidesSendWhileApartAndMerge`.
- **#87.** After Alice's reset, Bob's while-apart DM opened under the `prevRoot` her reset kept, and
  `touch` ran on the *replacement*: the DM names a dead-era epoch of hers whose key survives, so it
  confirmed the session, adopted Bob's dead-era epoch as her DH base, and raised `highestPeAcked`. Her tick
  then carried no init, under a root Bob had never seen, and he dropped it as `DUPLICATE`. His reseal after
  the reset reused the DM's id, which her router deduplicated, so his DM stayed on one tick. Chaos seeds
  2001, 2003 and 2005 on `SessionLabTest.aForcedResetHealsAndTheOtherSidesUnackedDmsAreResealed`.

**The rule.** Three things an open records are claims about the current era: the DH-base adoption, the
`highestPeAcked` raise, and an initiator's confirmation. They are recorded only when `openNewEpoch` derives
a fresh epoch under the resolved session's active root. A frame opened under any other candidate (a kept
`prevRoot`, a race's other root, a late race loser's root) opens, delivers and stores its chain, and
records none of them. Neither do the live-chain and skipped-key rungs. Every root change on our side purges
the receive rows (the replacement and race-adopt purges, `sealResetDm` for its own), so each stored row was
derived by exactly one fresh derivation, whose candidate already decided. In era a later frame of the epoch
would repeat what its first frame recorded; out of era it would be wrong.

Two alternatives do not work. Tagging the candidate in `openNewEpoch` alone, which is what #87 suggested,
leaves the live chain and the skipped key recording: a second while-apart DM from the same epoch confirmed
the replacement exactly as the first used to. An own-era test on the number of our epoch a frame names
(`pe > sendEpoch` for an initiator) misreads frames: a peer can seal under the *new* root against a
dead-era key of ours, and that frame is this era's. The era is the root, not the number.

**The late loser's root is a read-only candidate.** It is offered on the three non-adopting returns (stale
init, replacement floor, remnant guard), in exactly the state the remnant guard reads: a confirmed session
we initiated with no peer init resolved, an init from the higher node id, no `FLAG_RESET`, and a frame
sealed against our signed prekey (`pe = 0`). It is never adopted, so the guard's point stands. Nothing is
recorded either, which departs from #83's suggestion to anchor the init's ephemeral. A resetter's follow-up
frames carry its reset init without the flag, so to a session we initiated they look like a remnant. Had
reading one anchored that ephemeral, the flagged reset with the same ephemeral would later have opened as
an idempotent re-serve on the skipped key, never adopted. Re-serves of the late DM end as `DUPLICATE` on
the receive row its first open stored. A flagged init never gets the candidate: a reset is adopted or
refused, never read without adoption, because reading it would run its re-seal under the root it asks us
to leave.

**An init is answered once, not once per peer per hour.** With the false confirmation gone, a resetter
confirms only on a frame its peer seals under the new root, and after a reset the peer often has nothing
else to send: its re-seals reuse ids the resetter holds, so they stop at the exists-gate before the
ratchet. `IntroSync`'s answer to an init-bearing frame was that frame, but its floor was per peer, so a
reset within an hour of an earlier answer left the resetter unconfirmed. An unconfirmed session exports no
spool scope. The floor is now per init: a new init is answered at once, and repeats of the same init stay
floored. A new init reaches the hook only by opening, which the replacement floors bound, or through the
remnant candidate, which is one answer per frame the peer sent.

**What it costs.** The remnant guard's state is every session we initiated whose peer never sent an init,
not only races we won. A higher-id peer that lost its ratchet state and re-initiates *without*
`FLAG_RESET` is therefore read rather than refused. Recovery moves to that peer's side, through its
heuristic and then its flagged reset. Our ticks for the DMs we read in that window are sealed under the
root it lost, and it reads them only once its reset lands and custody re-serves its DMs past our dedup
window. Restores and resets send the flag, so this is the rare path. The race winner no longer takes the
loser's pre-adoption epoch as its DH base: it seals against the loser's signed prekey, init attached,
until the loser answers under the winning root. That is a few frames on the prekey rather than a fresh
epoch. A responder no longer raises `highestPeAcked` from old-era frames, so a few epoch keys live
somewhat longer, still inside the cap and the hard TTL.

Not covered. Frames sealed on a reset-minted, still-unconfirmed session carry the reset's init unflagged,
so a peer inside its 60-minute incidental-replacement floor refuses the init on them. Flagging them waits
on a pre-existing defect it would widen: in a mutual reset the higher-id side's flagged reset can reach
the winner late and be adopted, exempt from the remnant guard, so the winner defects to the losing root.

Regression: `RatchetEngineTest` (the three `…NeverConfirmsTheReplacement` cases on the new-epoch, live-chain
and skipped-key rungs; `oldEraFramesDrainingAfterAnAdoptedResetNeverBecomeTheAdoptersDhBase`;
`anInEraFrameNamingOurRetiredEpochStillConfirmsTheReplacement`; the two
`aRaceLosersLateOpeningDmOpensReadOnly…` cases; `anUnanchoredWinnerThatReadAResetsUnflaggedFollowerStillAdoptsTheReset`;
`theRaceWinnerNeverTakesTheLosersPreAdoptionEpochAsItsDhBase`; `aStaleFlaggedResetIsRefusedNeverReadWithoutAdoption`),
`IntroSyncTest` (`a new init is answered inside the floor, once`), and `SessionLabTest`
(`theLosersOpeningDmLandingAfterItsAnswersStillOpens` and the two `aTickSealedAfterAResetStillReaches…`
scenarios). No wire field, derivation or vector moved; an iOS port must mirror the rule.
