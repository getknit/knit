---
id: "2026-10.hr9f"
slug: custody-serves-a-group-s-frames-to-any-peer
title: "Custody serves a group's frames to any peer"
date: 2026-10-29
topics: [custody, groups]
---

# ADR 2026-10.hr9f — Custody serves a group's frames to any peer

Status: Accepted (2026-10-29). Applies ADR 006's convergence rule to who a digest reply serves. Part 1 of the fix for
issues #176 and #235; the bounded sealed re-ack (Part 2) is its own decision. Android only so far; the companion
change for knit-ios is below.

**What was observed.** `ForwardSync.onDigest` served a carried group frame only to a peer named in the frame's
roster, and a DM or a room post to anyone. Every custody bucket keeps its newest N by the frame-global `(sentAt, id)`
(ADR 006, ADR 2026-10.7mqm), so two nodes keep the same frames of a sender only when they hold the same set of that
sender's frames. A non-member that missed some of a member's posts live never got them back-filled, so its bucket
for that member was smaller and kept older frames, that member's DMs above all, which every member's quota had
already evicted. At every digest exchange it re-served them, and each was evicted again in the write that stored it.
Issue #235 reproduced the loop on 2026-10-28 (`exp/dht-links`, the P9 and P7) and first read it as a receipt purge;
sealed receipts purge nothing, and the cause is this smaller bucket. #176 traced the cost: on Wi-Fi Aware the
`DigestTracker` cap and the router's seen window are both 600 s, so nearly every re-serve is admitted, re-floods with
a fresh hop budget, and, for a DM to the receiver, draws a fresh sealed `CTL_RECEIPT` from the exists-gate, which is
itself custodied and adds to the quota pressure. Bluetooth's `LinkCrossings` caps it at one write per link per ten
minutes. `RestoreLabTest` had named the member filter "the partition the design does not close", and
`docs/NEXT_WIRE_BREAK.md` called it "an optimisation, not correctness".

**What changed.** A digest reply serves every live frame the peer's digest lacks, whatever its form: a group's frame
goes to a non-member exactly as a room post does. The rest of `onDigest` is unchanged: no author-skip (a wiped
author is re-served its own frames), profiles first (ADR 2026-09.9xuu), the key ahead of a keyless backlog
(`keylessSenders`, ADR 2026-09.g64k), and `onServed` once per frame sent (ADR 2026-09.2v2t). Nothing about what
custody stores moves: `ForwardRepository.carriesGroup` already stores a group frame whatever the receiver's
membership (ADR 2026-10.7mqm), so the frames a back-fill hands a non-member are the frames the live flood already
handed it.

The alternatives:

- **Keep the filter and exempt a member's DMs from the quota at a non-member.** A per-node rule in the trim, which is
  exactly what ADR 006 forbids: the non-member's digest would never match.
- **Bound the re-ack only** (#176's option 1). That ends the receipts, not the re-serves: the non-member still holds
  frames the members evicted, re-serves them every exchange, and the cue plane reconciles for their day. It is Part 2,
  and it stands beside this, not instead of it.
- **A per-link memo of frames the peer refused** (the branch fix on `exp/dht-links`, `LinkPushRounds`). It stops the
  re-serve on one link and leaves the digests unequal, so a Wi-Fi Aware cue still raises a data path that moves
  nothing.

**Why exposing the frames is not a new leak.** The roster rides in the clear on every group frame, and the live flood
already hands every group frame to every non-member in reach. A back-fill only extends that exposure in time, to a
non-member that was out of reach when the frame flooded. The frame's body stays sealed.

**What it costs.**

- **A one-time back-fill**: at most the per-group quota (200) per group a non-member meets, and its sender buckets
  bound it further. It happens once per frame; the held sets are then equal and the diff is empty.
- **One re-flood per back-filled frame per link per seen window.** A served copy is a fresh wrapper with a full hop
  budget, so the receiver relays it on once, as it does any first-seen frame.
- **Carrier picture pulls.** A back-filled group frame reaches `onCarried` → `InboundPipeline.onCarriedFrame` at a
  non-member, which pulls its picture as a carrier, up to `CARRIER_BLOB_BUDGET_BYTES` (128 MiB) per node. Every
  holder serves members before carriers (ADR 2026-10.nsr7, `BlobExchange.addresseesAcross`), so these pulls queue
  behind the members'. `CarrierPullPolicy.heldBack` holds a non-member's pull of a group picture only while the
  frame's author is linked to it (ADR 2026-10.g24x); a back-fill from another carrier, with the author gone, pulls at
  once.
- **The Your mesh credit.** A group frame served to a non-member is a hand-off to someone other than its author, so
  `ContributionLedger` books it as "passed along", once per frame; a group frame has no `recipientId`, so it is never
  "handed straight to".
- **The mixed-fleet transition.** A non-member that meets only iPhones or shipped Android builds stays short of the
  group's frames, and keeps re-serving what the members evicted, until those nodes update. A current node serving an
  old one is enough for the old one's buckets to catch up.

**What it does not cover.** #176's second path needs no quota: a phone fed by two holders relays one holder's copy to
the recipient past its seen window, and the recipient re-acks. That is Part 2's bounded re-ack. A busy group's own
member flooding its bucket is ADR 2026-10.7mqm's open insider case and is unchanged.

**Kept true by** `ForwardSyncTest.aNonMemberThatLacksAGroupFrameIsServedIt`;
`CustodyQuotaLabTest.aNonMemberBackFilledAGroupDropsTheDmTheMembersEvicted` (a non-member holds a member's DM the
members evicted; once it meets them it sends that DM at most once over each link, and the three stores agree; with
the member filter back it sent it three times over each, once per seen window, and custody never agreed).
`RestoreLabTest.bothMintErasOfARestoredSendersEpochDeliverWhenTheirFramesInterleave` no longer narrows its custody
leg to the members; it passed with the narrowing gone under the old rule too, so it is not this rule's guard. No vector moves: `vectors/custody-v1.json` pins store-time rules only, and this is a serve
rule, so it is no wire change. `.agents/rules/mesh.md` "Custody must converge" states the rule.

## Companion change for knit-ios

knit-ios's `CustodySync.missing` (`Sources/KnitMesh/Custody/CustodySync.swift`, at knit-ios `85866c5`) skips a group
frame whose roster lacks the peer: the `entry.groupId != nil` arm at `:199-204`, documented at `:163` ("A group frame
goes only to a member of its roster") and in the round-budget paragraph at `:152-155`. ADR 2026-10.7mqm's group gate
is the precedent for mirroring a custody rule across the two ports.

1. Drop the roster check: a group frame takes a round slot like any other frame the digest lacks. The arm's
   `store.frame` load existed only to read the roster, so it goes too.
2. Rewrite the two doc comments: a group frame goes to any peer that lacks it, as a room post does.
3. The round budget (`roundFrames`, `roundBytes`) now spends slots on group frames a non-member lacks. That is the
   back-fill, once per frame, and the "busy groups the peer is not in cannot starve" sentence no longer holds as
   worded: a non-member's first round may be spent on a group's frames, and the rest follow at its next digest.
4. A test beside the Android pair: a non-member that lacks a group frame is served it.
