---
id: "2026-11.bmks"
slug: custody-carries-for-delivery
title: "custody carries for delivery, and stores agree only below the row cap"
date: 2026-11-20
topics: [custody, mesh]
---

# ADR 2026-11.bmks — custody carries for delivery, and stores agree only below the row cap

Status: Accepted (2026-11-20). A paired change with knit-ios (`custody-carry-policy`; knit-ios ADR 2026-11.3jry).
Amends ADR 006, ADR 2026-10.7mqm, ADR 2026-10.hr9f and ADR 2026-10.a5yj. Issues #352, #349, #276 (only "cap what one
answer sends"), #64.

**What was observed.** The mesh simulator's sidewalk pass-by (`PassBySimTest`, `ble1m`, 15 m range, 5 pairs, a
960-DM load per walker: about 11 s of link a pair) on knit-next `8a78db55d`, seeds 1 to 3. Every linked pair landed
exactly 4 of the 20 DMs the two walkers had for each other, whether the link held 3.5 s or 12.5 s, and between 1,598 and
1,946 records a pair were cut with the link. A digest answer served everything else newest received first, so the only
frames the peer could read waited behind other people's mail, and the whole difference went to the link at once and
died with it. On `ble1m+nan` the pairs whose Aware path came up moved the whole difference (2,000 records) and then each
kept only 930 of its 1,920 carried frames at the 1,000-row cap: a full store evicts a frame for every frame it admits,
so two full stores never match, and the rules that push them toward matching spend the contact on the wrong frames. In a
48-walker park outing every store reached the cap within three minutes. Baseline reports:
`/media/walter/data/knit-sim/baseline-ccp/` on the workstation (point-in-time numbers).

**The decision.** Custody stops aiming for identical stores. What counts is that frames reach their recipients. Below
the row cap nothing changes: admission, the TTLs, the sender, group and room trims and the digest fold stay functions of
the frame and the held set, and two stores under `maxRows` that hold the same frames keep the same frames (ADR 006). At
the cap, stores may differ for good: the global cap's choice of row now reads local state. Nothing else in custody does.
ADR 2026-10.hr9f holds: an answer still owes the peer every live frame its digest lacks, less the memo below.

## What changed

- **The serve order** (`ForwardSync`, contract §1). A round serves profiles (newest received first), then frames
  addressed to the asking peer (`recipientId` bytewise, `(sentAt, id)` ascending), then group frames whose own cleartext
  `group.members` lists the peer (any group, `departed` not counted, `(sentAt, id)` ascending), then everything else
  (newest received first). Oldest first in tiers 2 and 3 because the key is frame-global, a ratchet reads in sending
  order, and a group's seed (DM-form) lands before its posts. A tier only orders, never withholds. Tier 3 costs no load:
  a row's roster (at most `MAX_MEMBERS` = 8 ids, else unremembered and tier 4) is read once when custody keeps the frame,
  and rebuilt from the group rows after a restart. A sender's key goes ahead of the first frame of theirs a round serves
  when the peer may lack it, charged to that round, at most once per answer (`KeyExchange.keyFor` / `sendKey`, ADR
  2026-09.g64k).
- **Paced rounds** (`ForwardSync`, `CustodyRounds`, contract §2). An answer is released in rounds, each cut fresh from
  an index (`ForwardStore.liveIndex`: no row bytes, the cost of each frame's encoded `WireEnvelope`) when it is due. The
  first pick always goes; the round stops at the first later pick past its budget and never skips. Bluetooth 16,384 B
  every 2,000 ms whenever a Bluetooth link holds the peer; Wi-Fi Aware 524,288 B every 1,000 ms, which applies only
  while Aware holds the peer and Bluetooth doesn't (the composite sends over the first child holding the peer,
  Bluetooth first, and Aware raises no sync data path to a peer Bluetooth links unless a file is wanted, so a passing
  pair whose Bluetooth links syncs custody over Bluetooth, as it did before); a contact's direct link 262,144 B every
  1,000 ms. The next round is due `max(interval, bytes × interval / round)` after the last. One answer
  per peer: a newer digest replaces its digest and restarts its age but never brings the next round forward. An answer
  ends when nothing is left, when no radio (or, for a link answer, no pair view) holds the peer, or
  `ANSWER_MAX_AGE_MS` = 120,000 after its latest digest. Everything an answer remembers is trimmed to the held ids at
  every cut, and it forgets a release at the first digest that arrives `JUDGE_AFTER_MS` or more after it, so an answer
  that digests keep alive never withholds a lost frame past §6's lapse.
- **The carry ledger** (`CarryLedger`, contract §3). Per held frame id, up to `SPREAD_MAX` = 8 distinct peers believed
  to hold it: seen in their digest, the frame's source (counted in the frame's own write, recorded only if the row is
  held after it), or a hand-off credited one interval after release if the link that carried it is still up. A digest
  is the peer's latest word. Only a proven link feeds the ledger: its node id was proven (both ends carry the link
  proof, or the link is sealed), or it is a contact's direct link. Each transport stamps what a link delivers with that
  link's proof and identity (`InboundFrame.proven` / `link`, `ReceivedDigest.proven` / `link`, `MeshTransport.linkToken`),
  so no `FramedLink` line moved; a digest off a link no longer current reads unproven. In memory only, bounded by the
  held set, empty after a restart.
- **Reach, withdrawn** (contract §4). The brief's "keep what this phone is likelier to deliver" (contacts and recent
  sightings) was dropped before code. A digest lists what a store keeps, so whatever eviction reads, a peer can read
  back. Nothing in custody reads a contact, a sighting or a meeting; no key joins the cap's order without a security
  pass.
- **The eviction order at the cap** (`ForwardDao.largestBucketRows`, `CapEviction`, contract §5). While live rows exceed
  `maxRows`, one row at a time goes: sender's live count descending (unchanged, ADR 2026-10.7mqm), then spread
  descending, then `sentAt` ascending, then id ascending as unsigned UTF-8 bytes. Spread is read once per write from a
  snapshot that already counts the newcomer's source; the newcomer is a candidate like any row. With no holders
  recorded the order is 7mqm's. Whichever row keys 2 to 4 pick, one of the largest buckets loses exactly one row, so the
  flood bound is unchanged. Two linked full stores stop swapping without end: a frame a peer serves back arrives already
  counted as held by it, ties with the rows the two share, and `(sentAt, id)` evicts it by its own write; the memo then
  marks it, and an offer that draws nothing backs off. What is left is one crossing a frame a lapse.
- **The not-kept memo** (`CarryLedger`, contract §6), generalising the direct-link push memo of ADR 2026-10.a5yj's
  2026-10-29 amendment to every plane. One memo per proven peer (`MEMO_MAX_PEERS` = 64). An id released is pending until
  a digest at least `JUDGE_AFTER_MS` later (20,000 Bluetooth; 5,000 Aware and direct link) judges it: listed, kept;
  lacking and still held, not kept for `NOT_KEPT_LAPSE_MS` = 600,000. Pending ends unjudged when the Bluetooth or direct
  link that carried it ends, or the lapse after release. A frame addressed to the peer is never pending or marked. A
  Bluetooth or direct-link relink clears the marks; a new Aware path clears nothing. A direct-link digest (the pair's
  view only) feeds the ledger additively and never replaces a radio answer's digest.
- **The re-offer back-off** (`ForwardSync.reoffer`, `MeshManager.reofferToNeighbors`, contract §7, #64). The periodic
  custody digest to a linked neighbour waits 60 s, doubling to 600 s after each offer that drew nothing; a frame kept
  from that neighbour resets it to 60 s at once. Due when `now − last offer ≥ interval − REOFFER_MS / 2`. The link-up
  digest is never held and counts as an offer. Only the custody digest is held: key asks, owed ticks, seeds and blob
  re-asks keep their rules.
- **The Wi-Fi Aware hold-off** (Android only; `DigestTracker`, `WifiAwareTransport`). The no-progress throttle reset only
  on equal digests, which full stores never show again, so every full pair would have sat at the 10-minute ceiling and a
  new DM would have waited it out. Each data path is now judged at its end against its own link's progress marks: frames
  custody kept from the peer, or releases a later digest judged kept (never a bare release, or one fresh frame would
  reset it). Equal digests still clear it and skip the sync; an exchange that moved something clears it; one that moved
  nothing doubles the hold-off from 30 s to 600 s, during which the initiator re-syncs only once a frame the peer lacks
  and would take has arrived. No hold-off without the peer's custody digest in the exchange, or on a bulk-only path. The
  three-in-a-minute tally before it engages is kept. The initiator (larger id) alone keeps a hold-off. The responder
  keeps none, because two state machines that never talk cannot agree on when the initiator will dial (two review
  rounds found a responder guess that ran short, cycling the smaller phone's NAN session every period, and a restart
  loop through the watchdog's kill; the maintainer chose this mechanism on 2026-11-20). Instead, for a larger peer its
  digest-pure want, which feeds the watchdog's owed episodes and the ICM relight, is false on digest divergence alone
  while it trusts the link: within `RESPONDER_TRUST_MS` = 2 × `COOLDOWN_MAX_MS` (1,200,000 ms) of the last data path
  with that peer that came up (it served the peer's dial, proof the link is alive) or closed with the digests still
  different, or of the plane's start (a restart grace, so a restarted responder, a watchdog kill included, cannot loop).
  Observed convergence ends that peer's trust and its grace, so a new divergence is owed at once as before, and bulk
  wants still count. 1,200 s is above the initiator's worst gap between dials while its one NDI is free: its 600 s
  hold-off, two 240 s idle ticks after it (nothing wakes its loop at the end), the settle and the 15 s handshake,
  1,096.5 s; a busy NDI can push past it, which the renewal at each path that comes up covers. Cost: a responder whose
  initiator truly wedged after a non-convergent exchange, or right after a restart, notices it up to 20 minutes later.
  A path is held open while a custody answer to its peer rides Wi-Fi Aware (at most `SYNC_MAX_WINDOW_MS`, about
  15 MiB at 512 KiB a second), and a path cut with such an answer still live ends its pending releases unjudged.
- **Counters** (`MeshMetrics`, `metricsJson`): `custodyRoundsCut`, `custodyAnswersCutShort`, `custodyNotKept`,
  `custodyReoffersHeld`, and Android's `custodyKeylessReleased` (a frame released with no key to send for a peer that may
  lack it).

## Constants

| Name | Value |
| --- | --- |
| Round and interval, Bluetooth | 16,384 B, 2,000 ms |
| Round and interval, Wi-Fi Aware (Android) | 524,288 B, 1,000 ms |
| Round and interval, direct link (Android) | 262,144 B, 1,000 ms |
| `ANSWER_MAX_AGE_MS` | 120,000 |
| `SPREAD_MAX` | 8 |
| `MAX_MEMBERS` | 8 |
| `JUDGE_AFTER_MS` | 20,000 Bluetooth; 5,000 Aware and direct link |
| `NOT_KEPT_LAPSE_MS` | 600,000 |
| `MEMO_MAX_PEERS` | 64 |
| `REOFFER_MS`, `REOFFER_MAX_MS`, tolerance | 60,000, 600,000, 30,000 |

The round, the lapse and the back-off are local policy pinned by unit tests on each side
(`CustodyConstantsTest`); only §5 is in `vectors/custody-v1.json`, which gains `limits.spreadMax` and per-step
`carry.holders` (46 steps, the 33 before unchanged).

## Alternatives not taken

- **Keep identical stores and only reorder the answer.** Ordering alone lands the DMs first, but a full store still
  trades a frame for every frame it admits, so the re-offer and Aware's sync-wanted test churn forever over stores that
  can never match; the memo, the back-off and the hold-off are what make different stores cheap.
- **Cap the answer in frames.** Frames range from 600 B to 60 KiB, so a frame budget is either a fraction of a
  contact or a whole one; bytes at the link's pace is the bound a cut loses about one round of.
- **Spread before the bucket size.** Faster to spread frames, but a flood of free identities could then reach a
  smaller bucket; key 1 first keeps 7mqm's bound exactly.

## Accepted consequences

- Stores at the cap differ for good, and their digests never match. The memo, the back-off and Android's Aware hold-off
  bound what that costs.
- A mixed fleet differs at the cap, as it did for 7mqm. An old build still sends the whole difference and re-offers
  every 60 s; a new build's memo and pace bound what it sends back. A build older than the link proof gets no memo and
  adds no holder; on Bluetooth the link's own duplicate skip (`LinkCrossings`) keeps a frame from crossing twice in 10
  minutes. That skip is a bounded memory, and a busy link outruns it: it remembers 1,024 writes a link inside the
  router's seen window (`LinkCrossings.MAX_PER_LINK`, `SeenSet.DEFAULT_TTL_MS`), which a link at the full Bluetooth
  pace makes in about 80 s, so a frame the skip has forgotten can cross again at the next digest that still lacks it.
  The pace is the bound that always holds.
- Holders can be faked. Digests are unsigned and link identities are free, so a peer can raise `spread` for chosen
  frames. Key 1 comes first, so this picks which row a largest bucket loses, and never how many rows or from which
  smaller bucket. A full carrier can be made to drop a chosen frame of a largest bucket, its newest included, where the
  old rule always took the oldest. A fake holder needs a proven link, so it can't borrow an honest node's id.
- What eviction reads, a peer can read back, because a digest lists what a store keeps. Holder counts show little:
  whether this phone saw another carrier of a frame, close to what holding the frame already shows. That is why reach is
  withdrawn.
- Two full stores that see each other holding a frame may both evict it; the old rule had every store evict the same
  oldest rows, so this is no new kind of loss. A restart empties the ledger, and eviction orders by `(sentAt, id)` until
  digests refill it. An evicted frame can come back from a peer once the seen window lapses, as today.
- A held Bluetooth link serves custody at 8 KiB/s at most, whatever it could carry; an adaptive pace is out of scope. A
  credit can run ahead of the write (the clock credits it while the link may still hold the frame unwritten), which
  overstates spread by a round at most and matters only at the cap. A credit also counts a frame the peer didn't keep:
  a full peer can evict a frame by its own write as it arrives, the hand-off is credited all the same, and the peer
  stays among the frame's holders until a proven digest from it that can judge the frame lacks it, on this link or a
  later one. Until then the frame ties on spread with rows the two really share, and a write at the cap can evict it
  though no one else holds it. The peer's own order had ranked the frame last, so the rule stands; crediting a
  hand-off to a full peer only once its digest lists the frame is left for later. Two carriers serve one newcomer the same first
  rounds, about a round of overlap per extra carrier.
- A full pair's offers often don't reach the longest gap. After an exchange a node's offers to its peer come 60, 180,
  420 and 900 s later. The peer's marks from that exchange lapse inside the 480 s gap, so its answer to the offer at
  900 s carries those frames once more, and the node's cap keeps some or none as its store then stands. A kept frame
  returns the interval to 60 s, and it climbs again. Android's lab saw that in about a third of its chaos schedules
  (`CustodyPaceLabTest`; seeds 543869138, 296711625, 296711629, 296711630, 296711636), about every 15 minutes, with 1 to 3 frames kept each time and no frame crossing twice inside a lapse. Even when it
  settles, a full pair's gaps reach 600 s only about 41 lab minutes in. An offer that draws a kept frame was worth
  making, so the back-off doesn't change; the cost is a few more digests than a settled pair sends, never more than the
  one a minute every pair sent before. And one keep at a lapse can bring another at the next offer: the keep evicts
  a row the two shared, the next digest lacks that row, and the peer serves it for the first time, so no memo holds
  it; it is kept in turn when a credit has meanwhile raised an older row's spread, holding the interval at 60 s for an
  offer or two. The memo still holds each row to one
  crossing a lapse.
- A frame the peer would evict on arrival still crosses once per lapse or link. A frame released without its sender's
  key can arrive late: the peer parks it, its next digest lacks it, and it waits out a mark unless addressed to the peer.
  A frame addressed to the peer that its digest keeps lacking is released again at each digest past the judging delay;
  the pace bounds it, and on Bluetooth the link's duplicate skip holds it to one crossing in 10 minutes while that skip
  still remembers it.
- Android only: a new frame on the smaller-id node for the larger waits out the larger's Aware hold-off (up to 600 s)
  unless the coordination plane's fast path carries it. And while the smaller id trusts the link it does not
  relight ICM for the larger, so on a screen-off, dozing smaller phone the larger's dials land in sparse discovery
  windows (a delivery in a minute or two, not seconds), and the smaller's idle-responder self-heal is masked for up to
  10 minutes. Both are bounded, and they end the all-day ICM drain a full pair would otherwise cause. A digest or a
  kept frame on another plane during an Aware exchange also reads as that exchange's progress, so the hold-off can
  engage or clear a little early.

## What this means for other decisions

- **ADR 006 and ADR 2026-10.7mqm:** identical stores only below the cap; the flood bound is unchanged.
- **ADR 2026-10.hr9f:** an answer still owes every live frame the digest lacks, now in paced rounds, less the memo.
- **ADR 2026-10.a5yj:** the direct-link push memo (`LinkPushRounds`, deleted) is now the ledger's memo on every plane.
- **ADR 2026-11.tesy** (Proposed): its annex cites `LinkPushRounds` and a flat 1,000-row digest. The memo it leaned on
  now covers every plane and judges by digest; an annex outside the digest would sit beside the ledger, not replace it.
- **The block list** still never enters custody (ADR 010, ADR 2026-09.bts9), and neither does any contact or sighting.

**Kept true by** `CustodyVectorTest` (the cap's order, the 46 steps), `CapEvictionTest`, `ForwardRepositoryTest` (key by
key, the flood bound under an adversarial reader, trims that never read carry, nothing below the cap),
`CarryLedgerTest` (holders, the memo, proven-only feeds, bounds over a six-hour digest loop, the transport asked
outside the ledger's lock), `ForwardSyncTest` (tiers, rounds, pace, endings, keys once per answer, the re-offer's
worked cases), `CustodyConstantsTest`, `LinkStampTest`, `DirectLinkTransportTest`, and the lab's paced and full-store
scenarios.
