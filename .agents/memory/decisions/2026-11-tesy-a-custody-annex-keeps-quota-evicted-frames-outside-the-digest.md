---
id: "2026-11.tesy"
slug: a-custody-annex-keeps-quota-evicted-frames-outside-the-digest
title: "a custody annex keeps quota-evicted frames outside the digest"
date: 2026-11-15
topics: [custody, convergence, store-and-forward, relay]
---

# ADR 2026-11.tesy — a custody annex keeps quota-evicted frames outside the digest

Status: Proposed (2026-11-15). A draft: nothing is built, no size is chosen, and no wire byte moves until it is.
Every change below is additive.

**What was asked.** Whether custody's size could vary by phone while the 24 h TTL stays: two builds handshake their
limits and fold the digest at the lower of the two, so that a phone run mainly as a relay can hold as much as it
has room for.

**What is proposed.** A phone in relay mode keeps today's custody exactly as it is, and beside it an **annex**: a
second table that takes the frames the quotas evict instead of deleting them. The digest never folds the annex,
so every other phone, every shipped build and knit-ios see an ordinary node. The annex is reconciled between two
relays and pulled by a recipient, both over a link that is up for another reason.

## Why the digest cannot fold at the pair's lower limit

- **The cue is broadcast.** The Wi-Fi Aware cue plane advertises one digest (`StoreDigest`) to every peer before
  any link exists, and `DigestTracker` brings up a data path only when two digests differ (ADR 006). A value
  chosen per pair cannot ride it, so the cue has to stay at the limits every phone has: 1,000 rows, 200 per
  sender, group and room (`ForwardRepository.DEFAULT_MAX_ROWS` and its three siblings).
- **A larger store cannot be trusted to compute the smaller one.** A node holding more would have to derive what
  a 1,000-row phone holds from its own set. `ForwardRepository.insertAndTrim` trims as frames arrive, in a fixed
  order (sender, group, room, global), and a frame sits in more than one bucket, so a row one trim removes frees
  a place in another bucket for whatever arrives next. Nobody has shown that the derived set equals the set a
  real phone ends with. If it ever differs, the relay's digest differs from every phone near it for as long as
  the frames live, and each of them re-attempts a data path on every cue: the failure ADR 006 was written for,
  on the node built to sit among the most phones.
- **The link digest is a flat list of ids.** ADR 2026-09.tjfb measured digests as about 204 KB of the 211 KB one
  slow Bluetooth link carried while a post waited. A store sixteen times the size offers a list sixteen times as
  long, every 60 s.

## The annex

- **Custody is untouched.** `ForwardRepository.store`, the trims, `StoreDigest` and `ForwardSync.onDigest` behave
  byte for byte as on a phone with relay mode off. The one change is at eviction: a row the sender, group, room
  or global trim removes is written to the annex in the same transaction.
- **What enters it.** Only quota and global-cap evictions. A frame custody refuses (over
  `DEFAULT_MAX_SIGNED_BYTES`, dead on arrival, past the future window), a row the TTL sweep drops and a DM a
  receipt tombstones never enter, and a tombstone deletes the annex's copy too.
- **Its bounds.** The frame's own expiry, unchanged, so nothing outlives the 24 h (6 h for a room post). A row
  cap and per-bucket quotas larger than custody's, trimmed by the same frame-global rules, the global cap from
  the largest sender bucket first (ADR 2026-10.7mqm), so a flood still displaces its own frames. The worst case
  is the row cap times 61,440 B (ADR 2026-10.mqzv).
- **Nothing is promoted.** A frame in the annex never returns to custody when a bucket later has room. A phone
  without an annex would not hold it, and the two digests would differ.
- **Relay mode is a setting**, off by default, on the phone that wants to carry more.

## How an annex frame travels

- **A capability bit** in the link HELLO (`Protocol.hello`), with a size tier beside it, names a peer that
  understands the annex. An annex record is never written toward a HELLO without the bit: older builds drop a
  link on a record tag they do not know.
- **Relay to relay.** Two annex nodes reconcile at the lower of their two tiers, which is where the handshake of
  limits fits. The exchange is a fingerprint per bucket and a list only for a bucket that differs, not one flat
  list. A frame handed over that the peer does not keep is remembered and not handed again, as `LinkPushRounds`
  does for a direct link (#235).
- **Relay to recipient.** A phone with the bit asks for what the annex holds for it; nothing is pushed. A frame
  pushed at a phone whose bucket is full is evicted in the write that stores it, and an ordinary digest exchange
  would offer it again every round.
- **A served frame is delivered, not carried.** The receiver verifies it and runs delivery, and does not run
  `ForwardRepository.store` unless it is a relay itself, where the frame goes to its own annex. It is never
  relayed onward.
- **An annex difference never brings up a link.** It rides a Bluetooth link's 60 s round or a data path that a
  custody difference opened. Two relays whose custody agrees and who share only Wi-Fi Aware do not reconcile.

## What it buys

- **The global cap rarely binds on honest traffic; the 200-row buckets do.** On 2026-09-27 a lab phone held 195
  rows of one sender, 162 of them cleartext receipts, and a frame served into that bucket was evicted on
  arrival. The gain is a phone that was away recovering the older frames of a busy sender, group or room from a
  relay it meets inside the day.
- **A phone without the bit gains nothing.** It is not harmed either: it never sees an annex record.

## What was ruled out

- **Folding the digest at the pair's lower limit**, for the three reasons above.
- **A second digest on the cue plane** for annex-capable peers. It spends the cue's scarce bytes
  (`docs/NEXT_WIRE_BREAK.md` item 12 turns down the same route), and an annex difference is not worth a data path.
- **Larger limits for every phone.** That is item 12 itself (7 days, with quotas to match), which waits for a
  wire break because a mixed fleet's digests differ for the whole rollout. This ADR does not replace it.

## What it does not cover

- **Attachments.** `store` answers false for a frame its own write evicts, so nothing pulls that frame's blob.
  A relay holding a picture message in the annex does not hold the picture, and `BlobStore` needs its own answer.
- **The Internet relay and a contact's direct link.** Both carry scoped custody only; the annex is radio only.
- **LoRa.** No annex frame is offered to the bridge.

## Still to decide

- **The sizes and the tiers.** No number here is measured. Each needs the same headroom reading
  `CustodyHeadroomLabTest` gives the byte cap, and a look at what a whole-annex read costs in memory.
- **Delivery without custody.** `InboundPipeline` says delivery runs regardless of custody. No test pins that
  for a frame that arrives outside the flood and the digest reply, which is how an annex frame arrives.
- **What a recipient may ask for.** A DM names its recipient in the clear (`ForwardEntity.recipientId`), so a
  relay can answer a peer whose link proof (`CAP_LINK_PROOF`) names it without learning anything new. A group
  frame carries only its group id, so asking for a group's frames tells the relay which groups the phone is in.
  Version 1 may be DMs and room posts only. This is the security reviewer's question.
- **Which frame types are worth keeping.** If most evicted rows are cleartext receipts, the annex fills with them.
- **Whether knit-ios pulls.** It needs nothing to coexist. Pulling is a paired change.

## Kept true by

Nothing yet. What the build owes before it is Accepted:

- **A mesh-lab scenario** with one relay among ordinary nodes, its buckets driven past their quotas, ending in
  `assertConverged` with every node's `StoreDigest` equal, beside `CustodyQuotaLabTest`.
- **A repository test** that the set `ForwardRepository` keeps and the digest it folds are identical with the
  annex on and off, over the same arrivals.
- **A vector** in `vectors/` for the annex record and the HELLO bit, shared with knit-ios, once the wire is drawn.
