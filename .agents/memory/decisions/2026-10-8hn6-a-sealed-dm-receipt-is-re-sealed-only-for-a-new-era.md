---
id: "2026-10.8hn6"
slug: a-sealed-dm-receipt-is-re-sealed-only-for-a-new-era
title: "A sealed DM receipt is re-sealed only for a new era, a lost receipt, or past its re-arm floor"
date: 2026-10-29
topics: [receipts, ratchet, custody]
---

# ADR 2026-10.8hn6 — A sealed DM receipt is re-sealed only for a new era, a lost receipt, or past its re-arm floor

Status: Accepted (2026-10-29; draft until the maintainer accepts it)

**What was observed.** GitLab #176, found reading the code while porting sealed receipts to knit-ios. The
pre-decrypt exists-gate (`InboundPipeline.decryptAndDeliver`) answers every re-delivered DM whose row we hold, and
for a ratchet-capable author off the LoRa board that answer was a new sealed `CTL_RECEIPT` under a fresh frame id:
flooded, custodied by every node for a day, one chain key spent. A sealed receipt purges nothing (ADR 018), so
nothing stopped the next copy, and the router admits one per 10 min seen window: up to about 144 receipts per
delivered DM per day. Two paths feed it:

- **Quota divergence.** A carrier whose per-sender bucket keeps a DM the recipient's quota evicted re-serves it at
  every digest exchange. Member-targeted group push was the main cause of the unequal buckets, and ADR 2026-10.hr9f
  removed it.
- **A back-fill fed by two holders**, which needs no quota at all: a returning phone takes one holder's copy, and
  its relay (a fresh flood at hop 0) reaches the recipient past its seen window. `CustodyLabTest`'s
  `aDeliveredDmBackFilledToItsRecipientPastItsSeenWindowDrawsNoSecondReceipt` reproduces it: on the parent tree
  Alice seals two receipts for Bob's one DM.

Each receipt also takes a slot in our own per-sender bucket, which widens the divergence that caused the first path.
The issue was deferred on 2026-10-17 because the memo it proposed, one receipt per DM id for the DM's day, broke two
existing heals. ADR 039 §3 and §7 heal a tick lost over LoRa by re-acking the re-offered DM. ADR 2026-10.dmgw's
reset re-seal needs a receipt in the new era, since the old one was sealed into a root the author may have dropped.

**What changed.** The rule, normative for every port: a recipient answering a re-delivery of a DM it already
delivered seals no new receipt when all of these hold, and seals one exactly as before otherwise.

1. The copy did not arrive over LoRa. That plane re-offers without a digest, and its re-ack is the heal (ADR 039).
2. The author can read a sealed receipt. An author that cannot gets the cleartext receipt, which purges the DM at
   every carrier and ends the loop by itself.
3. We sealed a receipt for this DM's id in the **current era**. The era is the session root the receipt was sealed
   under, read from the seal itself, in the same critical section that committed it, and compared with the session
   root we hold with the author now. After a reset or an adopted replacement the roots differ, and the next
   re-delivery gets one receipt in the new era.
4. That receipt is still **live in our own custody**: held and unexpired, not merely an expired row the sweep has not
   reached. A receipt our quota or the TTL dropped is re-served by nobody, so the re-delivery is the only way left to
   a ✓✓.
5. Less than the **re-arm floor** has passed since the last receipt we sealed for this id. The floor is 15 min,
   doubles with each re-seal in the same era, and stops at 4 h; a new era starts again at 15 min (the shape of ADR
   053's tick backoff). The reason is that a receipt held in custody is not necessarily applied at the author: it
   custodies a frame before it opens it, so a receipt it holds but never applied is never re-served. That happens
   after a process death between the custody write and the ratchet commit, or after an open that failed and left its
   era unmoved. Its own digest then folds our receipt and every carrier agrees with it, so without a floor the DM's
   ✓✓ would be lost for good.

What is recorded: the receipt sealed for a DM, under that DM's own id, at first delivery and at a re-delivery alike,
with the root it was sealed under, its frame id, when, and how many times it has been re-sealed in that era. The
acks of other messages riding in it are not recorded (AckSync's ledger owns them), and neither are the LoRa
coalescer's batch or the acks a reply carries inline. Never key the rule on the copy itself: a verbatim re-serve after
a reset carries the old era's ratchet header, and its signature is the same bytes in every era.

On Android the memo is `DmReAckLedger`, in memory, at most `ForwardRepository.DEFAULT_MAX_ROWS` entries evicted
oldest receipt first, each aged out `DEFAULT_TTL_MS` after its last receipt (by then that receipt has left custody
and would re-seal anyway). `InboundPipeline.sealDmReceipt` records into it with the tag `RatchetSessions.sealDmTagged`
returns, and `acknowledge`'s DM branch reads it on the exists-gate's call (`reDelivery`), comparing with
`RatchetSessions.rootTagFor` and reading custody through `ForwardStore.frame`, never `has`. `RatchetCrypto.rootTag` is
local storage only, so a port keys on its own handle for the same root. The tag is never re-read from `sessionFor`
after the seal: inbound runs concurrently on the radio collector and the spool's deliveries, so a reset adopted on the
other plane can commit in between and file the receipt in an era it was not sealed in. Reading it from the seal also
makes a first-contact seal correct, where the receipt's own prekey initiation creates the session. A skip counts
`receiptsReAckSkipped` (the `reAcksSkipped=` field of the minute metrics line). Sender-local; no byte on the wire moves.

Alternatives:

- *#176's option 1 as written, one receipt per DM id for its day.* Breaks the LoRa heal and the reset re-seal (above),
  and loses a held-but-unapplied receipt for good.
- *Keep a delivered DM's custody row out of quota eviction* (option 2). It does not touch the two-holder path, where
  the recipient still holds the DM, and it makes the quota a per-node rule, which ADR 006 forbids.
- *Re-ack once per process* (option 3). Same holes as option 1, plus a re-arm at every restart.
- *A re-arm keyed on whether the author applied the receipt.* Only the author knows that, and telling us is a wire
  change for a heal the floor already gives.

**What it costs.** The bound holds while our receipts fit our own per-sender custody bucket (200, ADR 006's quota).
Under our own quota pressure old receipts drop out of our custody, and the not-held re-arm re-seals each re-served DM
as before. Each such receipt takes a slot again, so the amplifier is back in that regime; hr9f closes the cause that
fed it most. Inside the bound, the worst case is a re-delivery landing just past each floor: 10 receipts per DM per
day (at 0, 15, 45, 105, 225, 465, 705, 945, 1185 and 1425 min) instead of 144. A restart forgets the memo and re-arms
one receipt per held DM. A wall clock stepped back past a receipt's stamp (ADR 026's offline drift corrected) reads
as past the floor and re-seals once, so a jump never holds back the re-arm. A DM first acked over LoRa (the coalescer) or inline in a reply has no entry, so a later
re-delivery off the board seals one receipt. Two copies landing on two planes at once can both pass the gate before
either records, which costs one extra receipt, as before. What it does not cover: a receipt the author holds but
never applied, for a DM nobody re-delivers to us, is still lost; the floor needs a re-delivery to act, and with every
digest agreeing none comes (ADR 053's 2026-10-27 amendment, #56). The traps: never record the riding room ids, never
read the era back after the seal, and never test custody with `has`. Regression: `DmReAckLedgerTest`;
`InboundPipelineTest`'s `aReServedDeliveredDmIsAnsweredByTheReceiptAlreadySealedInItsEra`, `…LeftOurCustodyReAcks`,
`…ExpiredUnsweptReAcks`, `…PastItsFloorReAcksAndTheFloorDoubles`, `…AfterAResetDrawsExactlyOneReceiptInTheNewEra`,
`aReceiptThatOpenedTheSessionAnswersTheNextCopy` and `aReServedDmOverLoraStillTakesTheCoalescer`;
`RatchetSessionsSealTest.aTaggedSealNamesTheRootItCommitted`; the `CustodyLabTest` scenario above;
`SessionLabTest.anAuthorsResetDrawsOneFreshReceiptForADmACarrierBackFillsToTheRecipient` (the author resets, its
first-era receipt never lands, and a carrier's back-fill inside the floor draws the one new-era receipt that ticks
it); and the tick-healing nets `RestoreLabTest`, `SessionLabTest` and `LoraPocketLabTest`.

## Companion change for knit-ios

knit-ios ports the same exists-gate and the same unconditional sealed re-ack (#176), so it has the same loop. Port
the rule above, not the Kotlin: where the exists-gate answers a re-delivered DM to us from an author that reads sealed
receipts, skip the seal while the receipt sealed for that DM's id was sealed under the session root held now, is
live in the port's own custody, and is younger than its floor (15 min doubling per same-era re-seal to 4 h). Record
from the seal path itself, with the root of the session that seal committed, for the DM's own id only. The era handle
is the port's own (Android's `rootTag` is local storage, not part of the reference); a LoRa exemption applies only
where the port re-offers DMs over a plane without a digest. No vector, wire field or capability bit moves, so the two
platforms may ship it independently, and a mixed pair only differs in how many receipts the unported side mints.
