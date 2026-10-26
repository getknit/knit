---
id: "2026-10.7mqm"
slug: custody-evicts-from-the-largest-sender-bucket-first
title: "Custody evicts from the largest sender bucket first"
date: 2026-10-26
topics: [custody, convergence, security]
---

# ADR 2026-10.7mqm — Custody evicts from the largest sender bucket first

Status: Accepted (2026-10-26). Amends ADR 006: the global cap's eviction key, and a group gate at store time.
Malicious-fork review C2 (`.private/docs/fork-review/custody.md` F1, F9, F10). Android only so far; the companion
change for knit-ios is below.

**What was observed.** A code audit, not the field. Every custody quota evicted the globally oldest live frame by
`(sentAt, id)`, the global cap included (`ForwardDao.evictOldest`), with nothing fair between senders. Identities are
free (a fresh key pair self-certifies, every node pins it), so five Sybils × 200 frames stamped `now + 299 s` filled
the 1,000-row cap with the newest frames in every store. Every honest frame was evicted, on every node at once:
ADR 006 makes the rule identical everywhere, so nobody was left to re-serve them. For the next five minutes every
honest frame was the oldest in the store and was evicted by the very write that stored it, after the router had
marked it seen. A future stamp was not even needed for the wipe, since a fresh flood is already the newest. The
group quota (one insider, or anyone who knew a group's id) and the room quota (200 posts) were open the same way.
ADR 006 was right that the rule must be convergent. Being convergent did not make it fair: the rule applied the
attacker's win on every node at once.

**What changed.**

- **The global cap evicts from the largest sender bucket.** While the live rows exceed the cap, one row is evicted at
  a time: the oldest `(sentAt, id)` frame of the sender holding the most live frames. On a tie for most, the sender
  whose oldest frame is oldest loses it. The sizes are recounted after each eviction. In SQL
  (`ForwardDao.evictFairest`), with ids and sender ids compared bytewise (SQLite's `BINARY`):

  ```sql
  DELETE FROM forward_store WHERE id = (
    SELECT f.id FROM forward_store AS f
    JOIN (SELECT senderId, COUNT(*) AS n FROM forward_store WHERE expiresAt >= :now GROUP BY senderId) AS b
      ON b.senderId = f.senderId
    WHERE f.expiresAt >= :now
    ORDER BY b.n DESC, f.sentAt ASC, f.id ASC LIMIT 1)
  ```

  `ForwardRepository.insertAndTrim` runs it `count − maxRows` times, after the sender, group and room trims, in that
  fixed order. Like the old key it is a function of the live held set alone, so two nodes holding the same frames
  keep the same ones, and the ADR 006 invariants are unchanged: every origin, `ORIGIN_SELF` included; live rows only;
  expiry keyed on `sentAt`. A flood now displaces only the largest buckets, which are its own.
- **The sender, group and room trims stay newest-N by `(sentAt, id)`.** That is deliberate. Every shipped Android
  build and knit-ios trim newest-N, and a busy room sitting at its quota is the ordinary festival case: a fair room
  trim would split an old node from a new one for good. The design pass's simulation split 917 of 1,000 such cases
  with fair room trims, and none with only the global cap fair.
- **The group gate.** `ForwardRepository.store` refuses a frame with `env.group != null` unless
  `Conversations.groupIdFor(members + departed) == group.id` and `senderId ∈ members`
  (`ForwardRepository.carriesGroup`). Before this, any pinned identity that knew a group's id (it rides every group
  frame in the clear) could fill that group's 200 slots on every carrier, and the frames were the group's newest. The
  gate is a function of the frame alone, so it stays convergent. It sits beside the dead-on-arrival and future-window
  refusals, not in `InboundPipeline.canCarry`. That gate is also `ScopeSync.acceptClaimed`'s delivery gate, and our
  own `ORIGIN_SELF` frames skip it. Delivery and relay are unchanged: `vetRoster` still decides what a member reads. A
  refusal counts `groupCustodyRefused` (`MeshMetrics.Snapshot.Groups`) and logs `custody refused <id> from <sender>`.
- **`store` answers whether the frame is held after its own write.** It answered `true` for a frame the quotas had
  just evicted, so `ForwardSync.onSeen` went on to custody that frame's attachment, pulling a blob for a frame we do
  not hold. The design pass found this; it predates C2.

**Why the future window stays at five minutes.** The review suggested cutting it to seconds. Under the fair cap a
future stamp only orders a sender's own frames and breaks ties, so it no longer buys a flood anything. A tighter
window would refuse a fast-clocked honest frame after `MeshRouter` had marked it seen (`MeshRouter.handleInbound`),
so peers' digests would differ for a seen window per frame, up to ten minutes, which is the churn ADR 006 exists to
stop. The edge of the window still splits nodes whose clocks disagree about a frame stamped right at it (F9). That
is recorded as residual; the stamp only reorders the stamper's own bucket.

**Honest group shapes, verified before refusing anything.**

- Android: every group frame is built by one mapper, `GroupEntity.toGroupInfo()`, from a row whose
  `members ∪ departed` is the founding roster. `createGroup` writes the founding set. `vetRoster` pins a row only from
  a roster that derives, and never mutates it within the pin. `recordDeparture` moves a leaver from members to
  departed. A rejoin moves them back (`storedRoster`), or for the rejoiner re-creates the founding row.
- A reaction sent after leaving (`ChatViewModel.react` builds its `GroupInfo` with no `left` check): `leave` only
  marks the row left (`GroupDao.markLeft`) and deletes the thread's messages, so the leaver is still in their own
  `members` and the frame passes. In practice there is nothing left to react to.
- `groupleave` carries its group in the payload, a seed's roster rides inside a sealed DM, and ticks and receipts
  are DM-form: none names a group on the envelope.
- knit-ios (checked at knit-ios `9a67484`): every group-shaped frame it originates (v1 group chat, group-form chat,
  group-form `CTL_REACTION`, `groupupdate`) carries `members` = roster minus departed and `departed` = the rest, so
  `members + departed` is the stored roster (`GroupStore.swift:83-95`). A stored roster only comes from a frame whose
  `members + departed` derived to the id at first sight (`GroupRoster.vet`), seeds pin the same way, and `departed`
  has existed on iOS since groups landed, so it holds no truncated pins. Every group send path is gated on
  `!group.hasLeft`. A reaction after leaving falls back to a cleartext `reaction` frame with no `group`. `groupleave`,
  ticks and receipts carry no `group`. Typing carries one but is never custodied.
- Pinned in `CustodyGroupGateTest` (every shape built through `toGroupInfo`) and
  `MaliciousForkLabTest.aLeaveAndARejoinAreCarriedByEveryNodeUnderTheGroupGate`.

**Simulation (the design pass's).** SeenSet window, simultaneous two-way exchange, 2 and 3 nodes, 1,500 cases each:
no permanent 2-cycle for any variant. The fair rules converge within about 2 SeenSet windows in the worst case,
today's rule within 1. `ForwardRepositoryTest`'s exchange case reproduces that through the real store: 2 and 3
stores, room, sender and global quotas binding together, converged within 0 to 2 windows across its seeds.

**What it costs, and what it does not cover.**

- Identities are free. The wipe now needs roughly `cap − honest senders` pinned Sybils (about 1,000 in the field)
  instead of 5. Bounding how fast a link can introduce identities is H5 (link authentication), not this change.
- Still open: the room flood (200 Sybil posts; `IngressBudget` is keyed by a claimed id, H5) and the group insider
  flood (a trusted member flooding their own group's bucket).
- A mixed fleet holds different sets while the global cap binds, until every node runs the new rule. Honest traffic
  rarely reaches the cap, so this is a transition cost and not a standing split. knit-ios must mirror the rule (below).
- Old-build residuals for the gate. 2.0.0–2.2.3 (before `departed`, commit `4c280ecd1`) send the effective roster
  alone after a departure. Custody refuses those frames; delivery still takes them through `vetRoster`'s within-pin
  arm. The same holds for a current-build phone still holding a group row a pre-2.3.0 build pinned from such a
  truncated frame: its own frames are refused by custody until any current member's frame repairs the pin
  (`vetRoster`'s verified-superset arm).
- The trap for the next reader: "fair" is tempting to extend to the room and group trims. Don't, unless every build
  in the field and knit-ios move in lockstep (a wire-break-class change, `docs/NEXT_WIRE_BREAK.md`).

**Kept true by** `ForwardDaoTest`'s three `evictFairest` cases; `ForwardRepositoryTest`'s Sybil-flood,
honest-intake, room/group newest-N, permutation and exchange cases; `CustodyGroupGateTest`; `CustodyVectorTest` over
`vectors/custody-v1.json`; `CustodyQuotaLabTest` (`aFullStoreEvictsFromTheLargestSenderOnEveryNodeAlike`,
`theRoomQuotaAndTheCapBindingTogetherConvergePastTheSeenWindow`); and `MaliciousForkLabTest`'s Sybil flood, non-member
group flood and leave/rejoin cases. `.agents/rules/mesh.md` "Custody must converge" states the rule.

## Companion change for knit-ios

knit-ios's `CustodyRules.evictions` mirrors the old rule exactly. Until it moves, a mixed fleet diverges whenever the
global cap binds.

1. **Global trim only:** while live rows exceed `maxRows`, evict one row: the live frame with the greatest live count
   for its sender, then the smallest `sentAt`, then the smallest `id` (bytewise). Recount after each eviction. Run it
   after the sender, group and room trims, which stay newest-N by `(sentAt, id)` exactly as today.
2. **The group gate** beside `expiry`'s dead-on-arrival and future-window refusals: refuse a frame naming a group
   unless the group id derived from `members + departed` equals `group.id` and the sender is in `members`. It is a
   custody refusal only, never a delivery or relay one.
3. **Answer "not held"** for a frame its own trim evicted, so no attachment pull follows it.
4. **Check against `vectors/custody-v1.json`:** replay `steps` through one store with `limits`, and match each step's
   `kept`, `evicted` and `held`. The file's `about` states the rule. It covers each bucket's trim, both tie-breaks,
   a frame evicted by its own write, expired residue, both refusals and the gate.
