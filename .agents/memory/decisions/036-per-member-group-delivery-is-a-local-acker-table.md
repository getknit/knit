---
id: "036"
slug: per-member-group-delivery-is-a-local-acker-table
title: "Per-member group delivery is a local acker table; the tick's wire semantic is untouched"
date: 2026-08-23
topics: [groups, data, receipts]
---

# ADR 036 — Per-member group delivery is a local acker table; the tick's wire semantic is untouched

Status: Accepted (2026-08-23)

**Amended by ADR 2026-11.z6jw (2026-11-15):** read receipts now exist. They ride the delivery receipt as a read mask
(`MessageContent.rm`), and `message_receipts.readAt` records them on the same rows, so "Deliberately NOT done: read
receipts" below no longer holds. The table's three UI rules stand; Message details adds a "Read by" section above
"Delivered to", and a DM shows a read line under its delivered one.

ADR 033 closed the last delay-tolerance gap in the group tick, so an author now reliably learns their group
message landed — but only *that* it landed. `messages.received` is one boolean the first member's ack flips
for the whole roster, so "Message info" could never answer the question people actually open it for: **who
has this, and who doesn't.**

The acker was never missing from the wire. Every receipt form arrives as a signed `RelayEnvelope` whose
`senderId` is the acking member — the cleartext `RECEIPT` frame, the sealed `CTL_RECEIPT`, and ADR 033's
batched `acks` alike — and both apply paths used it as a guard and then dropped it
(`InboundPipeline.handleReceipt` / `applySealedReceipt`). So this is **receive-side bookkeeping, not a wire
change**: one local `message_receipts` table (DB v6, `MIGRATION_5_6`, PK `(messageId, ackerNodeId)`, the
`reactions` shape one table over), written by `MessageReceiptRepository.record` in the **same transaction**
as the tick it accompanies. No new frame type, no new ctl value, no new `MessageContent` field, no
capability bit, nothing folded into any digest, and `AckSync` is untouched end to end. **ADR 033's "no
per-member ack matrix" therefore stands as written** — it was a statement about the wire, and the tick still
means "≥1 recipient received it".

**The one new guard, and why it is only on the row.** The forged-ack guard's null arm accepts *any* signed
sender for a group message — that null arm IS the group tick (ADR 018/033), and it must keep doing so.
Storing the sender turns that same null arm into a roster-spoofing surface, so `ackerFor` gates the **row**
(and only the row) on membership: a DM's addressed recipient, a group's *effective* roster member, any
signed peer in the public room (no roster to check against), and nobody at all for a message we don't hold
— so a receipt can never plant an orphan. `markReceived` is reached identically in every case; a non-member's
ack still ticks and simply names nobody.

Three UI rules that follow from what we can honestly claim:

1. **A ticked message with zero rows predates the table** → show no roster at all. Naming every member as
   "waiting" would contradict the ✓✓ above it, and the migration deliberately backfills nothing: we never
   observed who acked those, and inventing it is the one thing worse than saying nothing.
2. **The broadcast room gets an open "received by N" list, no denominator, no waiting half** — it has no
   roster, so there is nobody to be waiting on. Hidden until an ack lands (an empty list is not a fact).
   Its ticks also never escalate into custody by design, so the list is patchier than a group's.
3. **A DM never shows the split** — its single ✓✓ already names the only recipient there is.

`notedAt` is **our** clock at apply time ("when their receipt reached you"), deliberately not the acker's
`sentAt`: mesh devices have no time sync and an escalated batch's `sentAt` is its 45 s flush time, so a
peer-clock value could render a delivery *earlier* than the send it acknowledges. `via` is first-evidence-wins
like `markReceived`'s. Blocked ackers are listed, not filtered — the reactions precedent on the same screen.
Deliberately NOT done: read receipts (they exist nowhere in the app), and no fraction on the bubble/chat-list
tick — the aggregate stays the aggregate.

Tests: `MessageReceiptRepositoryTest` (real SQL, plus the pin that `record` **nested inside the ctl commit's
transaction commits rather than deadlocking** — the failure mode would otherwise be a silent coroutine hang),
`InboundPipelineTest` (cleartext/sealed/batched acks each name their sender; a non-member's ack ticks and
names nobody; a re-serve keeps the first crossing; an unheld id records nothing),
`MessageDetailsViewModelTest` and `MessageDetailsScreenContentTest` (the three UI rules),
`KnitDatabaseMigrationTest` (5→6, empty and un-backfilled). **`AckSyncTest`, `GoldenVectorTest` and `WireSerializationTest` are untouched and pass
unmodified — the executable proof that nothing on the wire moved.** Scheme doc:
`docs/ENCRYPTED_RECEIPTS_REACTIONS.md`; context: `context/store-and-forward.md`.
