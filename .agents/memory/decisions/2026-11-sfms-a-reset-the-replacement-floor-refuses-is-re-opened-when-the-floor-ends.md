---
id: "2026-11.sfms"
slug: a-reset-the-replacement-floor-refuses-is-re-opened-when-the-floor-ends
title: "A reset the replacement floor refuses is re-opened when the floor ends"
date: 2026-11-01
topics: [crypto, pfs, recovery]
---

# ADR 2026-11.sfms — A reset the replacement floor refuses is re-opened when the floor ends

Status: Draft (2026-11-01). Work item knit/knit-next#251. Amends ADR 2026-10.gcrd ("DUPLICATE outranks the rest").
Cites ADR 023's one-minute `FLAG_RESET` floor, which is unchanged. The sender half is an amendment to ADR 2026-09.qerd
(its 2026-11-01 section). No wire, vector or schema change; Android only (knit-ios has no DM ratchet), and FSR §7
states the rule for a port.

**What was observed.** In the 2026-10-31 reset-mid-offer device trial the Pixel 7 forced a DM session
reset about 3 s after the Pixel 9 had answered its previous forced reset. The Pixel 9 dropped the new one as
`DUPLICATE se=1 pe=0 n=0 init=true`, and the Pixel 7 sat `confirmed: false` for over five minutes, every
confirmed-only path shut (link signals, scope export), until a third forced reset. It looked like a re-serve of the
first reset. It was the second, refused by the receiver's floor and misnamed:

- `RatchetSessions.contextFor` refuses a flagged init inside `RESET_REPLACEMENT_MIN_INTERVAL_MS` of the last
  replacement adopted from that peer (`allowReplacement = false`), and the engine judges the frame under the roots
  already held.
- The resetter restarts its numbering, so the refused frame's `se = 1 n = 0` meets the row the first reset's own
  `se = 1 n = 0` left; the ladder sets `consumed`, and the verdict ranks `DUPLICATE` first — the benign verdict, which
  drives nothing.
- Nothing ever brings it back: the router marked the id seen for 10 min before the decrypt, custody stored it and the
  digest then advertises it (no neighbour re-pushes it), it is a ctl with no message row (nothing re-seals it), and
  `SeenSet.reopen` runs only for a reset-triggering drop. The resetter's own heuristic is floored for 6 h.

**What changed.**

- **The verdict.** A `FLAG_RESET` init refused by the floor alone (newer `at`, an ephemeral never resolved, the
  signed-prekey private held) is remembered on the resolution; the ladder still climbs, and only when nothing opens
  does the engine derive the init's root (`respond`, one X3DH on a rare branch) and compare it with every root it
  holds: the active root, `prevRoot` live or past its drain, a race's other root. Only an era never held is
  `OpenOutcome.Failed.RESET_FLOORED`, and it outranks `DUPLICATE`. That is sound because a flagged init is adopted or
  refused, never read (ADR 2026-09.pz9g): a frame carrying one was never opened, so its index is no proof of a
  consumed frame. The derivation check is the design pass's blocking finding. Our own re-root drops the peer's anchor
  (`initiate` writes `peerInitEphPub = null`) and keeps the peer's root as `prevRoot`; without the check a re-entry
  of a reset we adopted and left would read as floored, and its re-open would adopt a root the peer has left.
- **The drop.** `DropReason.RATCHET_RESET_FLOORED`, outside `RESET_TRIGGERING_DROPS`: the peer is not diverged, it
  asked to re-root inside our floor.
- **The hold.** `InboundPipeline.onRatchetFailure` holds the signed frame whole (`FlooredResets`, wire, envelope and
  source, as `PendingGroupKeys` holds a seed), one per peer, the newest by `sentAt` winning, synchronously inside the
  failure, so a replayed reset that floors again holds before its follow-ups replay. Only a signed frame: the flag is
  not in a v2 frame's AEAD. The entry carries `RatchetSessions.rootGeneration`, read before the open, and any root
  change since (an adoption, a race, our own re-root, a forget) drops it.
- **The evidence gate.** While a hold stands, a frame from that peer that fails to open carrying the held init's
  ephemeral is the resetter's own follow-up and never reaches `noteUndecryptable` (beside `isLiveEvidence` and
  `retiredBase` in `maybeRequestReset`). Counted, it would fire our own reset meanwhile.
- **The re-open.** `MeshManager`'s 60 s tick, beside `resealSecondPass`, takes each hold whose floor is open, asked of
  `RatchetSessions.resetFloorOpen` on the open path's own wall clock, never the manager's `clock()`. It re-delivers
  the frame through `onDeliver` with `relay = false` under `NonCancellable`, which skips the router's seen set, custody
  and the flood and verifies it again. Adopted, it runs every adoption's recovery (purge, re-seal, forced seed flush,
  `IntroSync`'s answer, which confirms the resetter). If the session then anchors the held ephemeral, the receiver
  replays, oldest first, the resetter's ratchet DMs to it in custody with no row whose header carries that init.

Rejected:

- **Let the sender re-send its own unanswered reset after a bounded wait** (#251's option 3). It also heals a reset
  refused as a stale `at` or by an old receiver, but it needs a send rule both sides must agree on and a floor of its
  own. Deferred, filed separately.
- **Hold the frame id and read custody back.** Custody's quota, its byte cap, or a `relay = false` delivery that never
  stored a copy can lose it. The frame is held whole.
- **Return `RESET_FLOORED` for every floored flagged init without the derivation check.** That is the blocking
  finding above.
- **Select the backlog by `sentAt` since the reset.** That compares two clocks. The init ephemeral cannot drift.

**What it costs.** One X3DH per floored flagged frame that opens nothing. At most one re-open per peer per floor: one
that floors again can only follow another adoption from that peer, itself floored at a minute. Both the hold and the
floor are in memory, so a restart inside the minute loses the re-open with the floor it waited on; that residue is
accepted, and the sender half makes it rare. A follow-up the resetter sealed under the reset carries its init
unflagged and still meets the 60-minute incidental floor until the reset is adopted; the replay is what reaches it,
and only from our own custody. A re-entry of an era we held keeps the ladder's verdict, read-only under `prevRoot` as
before. A flagged frame reading under a kept `prevRoot` is a pre-existing gap pz9g names, untouched here.

Pinned by: `RatchetEngineTest`'s #251 cases (floored never `DUPLICATE`, the unflagged and adopted re-serve verdicts
kept, and `aReEntryOfAResetWeAdoptedAndLeftIsNeverFloored`, the blocking case); `InboundPipelineTest`'s hold, follow-up
gate, re-open, root-change drop and unsigned cases; and
`ResetOverRelayLabTest.aResetBobsFloorRefusedIsReOpenedWhenTheFloorEndsAndItsDmLands`, whose floor is shortened
through `LabLimits.resetFloorMs`.
