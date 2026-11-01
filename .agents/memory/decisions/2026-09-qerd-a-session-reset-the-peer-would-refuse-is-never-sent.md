---
id: "2026-09.qerd"
slug: a-session-reset-the-peer-would-refuse-is-never-sent
title: "A session reset the peer would refuse is never sent"
date: 2026-09-25
topics: [crypto, pfs, recovery, backup]
---

# ADR 2026-09.qerd — A session reset the peer would refuse is never sent

Status: Accepted (2026-09-25). Amends ADR 2026-09.6mj7 (what the first start after a restore resets). Code:
`RatchetSessions.sealResetDm`, `InboundPipeline.sendSessionReset`, `MeshManager.finishRestore`,
`IntroSync.onPeerFrameOpened`, `MeshManager.seedSendFloorOpen`. No wire change, no DB schema change.

**What was observed.** The mesh-lab chaos sweep failed both `RestoreLabTest` scenarios with one ending: the
restored phone re-rooted toward a peer inside that peer's one-minute `FLAG_RESET` floor
(`RESET_REPLACEMENT_MIN_INTERVAL_MS`). The peer dropped the second init as `DUPLICATE` (its first frame lands
on the receive row the first root left), which triggers nothing; the restored phone had already purged the
root the peer stayed on, so each side's frames failed `AEAD_FAIL` on the other, and its own heuristic was
floored for six hours.

- **#85.** `MeshManager.start` brings the transport up before the start hooks that run `finishRestore`. A peer
  re-serving three or more old frames tripped the heuristic's reset R1 first; `finishRestore` then sealed R2
  over it, unconditionally. Chaos seed 1000, run 1.
- **#86.** `finishRestore` reset only DM-thread peers, but the restore wiped the sessions with contacts it
  shares only a group with too — group seeds are control DMs. The next group post's seed `sealDm` opened that
  session with a plain init, the peer adopted it, and the peer's pre-restore re-serves (live evidence under
  the five-minute skew allowance, ADR 026) fired a heuristic reset the peer refused. Often the plain init had
  been answered by then, which is why a guard on unconfirmed sessions alone still failed (seed 1001). Seeds
  1000 and 1007.

**The rule.** The sender looks at the session it holds before minting a root, because the receiver's floor is
fixed in every build in the field:

- **Its own init, unanswered, against the peer's current prekey:** never a second root, whatever its age — the
  peer's floor starts when it adopts, which may be now, from custody. A plain init (a first `sealDm`,
  `lastResetSentAt < establishedAt`) is *marked*: the `CTL_SESSION_RESET` sealed under it as v2 with `flags =
  RESET` on a header that still carries the same init, stamped like any reset, nothing purged. The peer
  resolves it idempotently by its ephemeral (before any flag is read) or adopts it under the reset floor, and
  the recovery runs either way — `resealUnacked` and the forced seed flush key on the ctl, not on a purge. An
  init that already is a reset is declined for a minute, then re-rooted as before: only a fresh `at` outlives
  a peer that judges this one's stale (clock lag).
- **Its own init, answered under a minute ago, when the heuristic asks** (`ResetCause.UNREADABLE`): declined.
  The frames that failed to open prove the peer held another session with us, so taking our init was a
  replacement that started its floor, no later than it sealed the answer. `confirmedAt` is in memory, stamped
  in `commitOpen` only when our own unconfirmed initiation turns confirmed (a race lost to the peer's init
  also confirms, as responder, and doesn't count). The heuristic fires again on the next failure after the
  minute. An on-demand reset (`ON_DEMAND`, the debug bridge) has no such evidence and goes out: on first
  contact the peer *established* the session, which starts no floor — the first cut applied this rule to every
  reset and failed four `SessionLabTest` cases and one `TimeLabTest` case that reset right after acquaintance.
- **After a wipe** (`ResetCause.AFTER_WIPE`, `finishRestore` only): the restore emptied the ratchet tables and
  the self session is forgotten before, so any session present was made since the wipe. It gets only the
  marker — under a confirmed or responder session too, where no init rides and so no flag — or nothing when a
  reset already went (`lastResetSentAt > 0`, which also makes a crash re-run idempotent). A pending init
  against a prekey the peer has since replaced is re-rooted.

`finishRestore` resets every wiped peer — DM peers plus the decoded roster of every active group, self out —
and sends the resets **before** the profile bump: a peer that sees the bump first flushes its seeds under the
session it holds, the old one, and the fifteen-minute seed floor then holds back the flush the reset forces.
`IntroSync` answers a marked init once more even when it answered the same init unmarked, since the peer may
have read the plain one read-only as a race remnant and adopt only the marked frame.

**The seed floor counts only the current root.** With the resets fixed, the group scenario still failed under
chaos (seeds 1000 and 1004): bob posted before alice's reset reached him, so his seed to her went out under
the session her restore wiped (`AEAD_FAIL se=1` on her side), and when he adopted the reset its forced flush
met the fifteen-minute per-(group, member) floor that dead send had stamped — `GROUP_RATCHET_NO_KEY` on his
posts for a quarter hour. `seedSendFloorOpen` now stamps a fingerprint of the pairwise root with the time and
applies the floor only while the root is unchanged. A root moves only through the ratchet's rate-limited paths
(the replacement floors, our six-hour heuristic), so this adds at most one send per change. Letting `force`
bypass the floor was the obvious alternative and is wrong: a bare reset marker, which this ADR makes a normal
frame, would then buy a seed re-send per group on every copy, where under an unchanged root it stays floored.

**What was not done.** The #86 issue proposed narrowing `InboundPipeline.isLiveEvidence` for sessions we
initiated. With the rules above, stale frames can still count, but whatever they fire marks, declines, or
finds the restore already covered the peer; the ADR 024/026 gate stays as it is. Muting the heuristic while
`restore_pending` was the fallback and turned out unnecessary. The lab fixture does not wait for the restore
to finish: that would close a window production leaves open.

**What it costs.** A marked init is flagged from then on, so the next reset over it — six hours later through
the heuristic — re-roots; a peer that refuses our plain init's `at` as stale waits that long, as it did
before. The marker does not widen the mutual-reset defect ADR 2026-09.pz9g parks: it rides only an init of
ours we still hold, which means we never adopted the peer's root, so the peer cannot be the confirmed,
unanchored winner of a race against it — that state implies we once held its root and lost it, and a flagged
reset is the recovery pz9g names for exactly that. Still open, both present before: a flagged reset re-rooted
after six hours just as the peer finally adopts it, and days of skew making our `at` look stale. Commons
members and tick-only sessions are not in the restore's set; they strand no content, and the rules above cover
their heuristic resets.

Regression: `RatchetSessionsResetTest` (two real session services over Room: the #85 double reset, the mark
over a plain init opened idempotently and by adoption, both declines and their re-roots after the floor, the
`AFTER_WIPE` marker over confirmed and responder sessions, an on-demand reset right after first contact);
`InboundPipelineTest` `aResetMarkerUnderTheSessionWeAlreadyHoldStillReSealsAndFlushes` and
`aResetTheRatchetDeclines…`; `MeshManagerTest.aRestoreResetsEveryWipedPeerBeforeItsProfileBump` and
`aSeedSentUnderARootTheMemberHasLeftDoesNotHoldBackTheNextOne`; `IntroSyncTest` (a marked init's one extra
answer); and both `RestoreLabTest` scenarios under `scripts/lab-chaos.sh --seed 1000 --runs 20`.

## Amendment (2026-11-01, #251): an init the peer took as a replacement starts its floor too

Status: Draft. The on-demand exemption above rests on first contact, where the peer *established* the session and
started no floor. It was applied to every on-demand reset. In the 2026-10-31 reset-mid-offer trial the Pixel 7 forced
a reset about 3 s after the Pixel 9 had answered its previous forced reset. The Pixel 9 had *replaced* its session to
take that one, so its floor was running, and it dropped the second as `DUPLICATE`. The Pixel 7 sat unconfirmed for over
five minutes.

`RatchetSessions.commitOpen` now stamps an in-memory `replacedAt` beside `confirmedAt` when the answered init was one
the peer took as a replacement. Each case below is judged on our own row as the answer lands:

- a reset or a marker (`lastResetSentAt > 0 && lastResetSentAt >= establishedAt`, the test `resetPlan` already uses);
- a rule-C reclaim (ADR 2026-10.4mjm);
- a race we won after resolving the peer's own init (`peerInitEphPub != null`), which the peer resolves by adopting
  ours and purging.

For a minute from `replacedAt`, `resetPlan` declines every cause but `AFTER_WIPE`, whatever our role is by then, with
the reason "the peer adopted our reset under a minute ago". The rule runs after the heuristic's `confirmedAt` decline,
so a case that rule already decides keeps its reason: `vectors/ratchet-v1.json`'s `confirmEra` step pins the wording.
A plain first contact stamps nothing, so `anOnDemandResetRightAfterFirstContactGoesOut`, the four `SessionLabTest`
cases and the `TimeLabTest` case stay as they were. The floor the sender models is one number with the floor the
receiver applies (`RatchetSessions.resetFloorMs`, the constant in production).

The sender cannot see the receiver's floor, which runs from the receiver's adoption, so a reset can still land inside
it: one delayed in custody or the spool, a race the receiver lost unseen, a restart that forgot `replacedAt`, or an
older build. The receiver half, ADR 2026-11.sfms, holds such a reset and re-opens it when the floor ends.

Regression: `RatchetSessionsResetTest`'s #251 cases (answered reset, adopted peer reset, won race), and
`SessionLabTest.aSecondForcedResetRightAfterTheFirstWasAnsweredIsDeclined`.
