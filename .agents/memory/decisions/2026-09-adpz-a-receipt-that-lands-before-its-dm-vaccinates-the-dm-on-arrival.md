---
id: "2026-09.adpz"
slug: a-receipt-that-lands-before-its-dm-vaccinates-the-dm-on-arrival
title: "A receipt that lands before its DM vaccinates the DM on arrival"
date: 2026-09-28
topics: [mesh, custody, receipts, convergence]
---

# ADR 2026-09.adpz — A receipt that lands before its DM vaccinates the DM on arrival

Status: Accepted (2026-09-28)

**What was observed.** knit-ios ran an hour's soak on 2026-09-28 (`interop.py iphone-soak`) along a line:
the Pixel 3, an iPhone, then knit-peer. Custody matched on every id in all four comparisons, at 438 to 500
frames, except two DMs. The iPhone carried `IQy6nnIysCZ2gY8QFwiP-A` two hours after the Pixel had purged it,
though both held its receipt. The Pixel carried `nxhsL2NDvJcBFJxXNKoZjA`, a DM the iPhone had delivered, with
24 h left on it. knit-ios mirrors `ForwardSync.onAck` exactly, so the same gap showed on both platforms
(issue #100).

It looked like a knit-ios interop bug and is not one. `onAck` purged a carried DM only when the store already
held it, because only the DM names its recipient. A receipt that arrived first did nothing: the id was not
tombstoned, and the router marked the receipt seen, so it was never applied again. The DM that followed was
custodied by `onSeen` and carried for its full day. The neighbours that had purged it refused it from their
`acked` tombstone, so custody could not converge while it lived (ADR 006's liveness rule now hung on arrival
order), and it held a slot in its sender's 200-frame quota. Two ordinary paths deliver the receipt first: the
newest-received-first back-fill in `onDigest`, and the recipient's own relay, which `MeshRouter` schedules
after the jitter while the receipt goes out at once.

Only the **cleartext** receipt vaccine-purges. A sealed `CTL_RECEIPT` never calls `onAck` (ADR 018), so a DM
between two ratchet-capable phones was never affected. The gap applies to every author that cannot read a sealed
receipt: knit-ios today, an older build, and the DM-ack coalescer's per-id fallback.

**What changed.** `onAck` records every receipt as `(ackId, acker)` in `earlyAcks`, a `SeenSet` with the
tombstone's own TTL (24 h) and 4096-entry cap, and then purges a held DM as before. `onSeen` drops a DM whose id is
recorded with its own cleartext `recipientId` as the acker, tombstones it in `acked` as a purge would, and never
calls `onCarried`. It asks once before the custody write and again after it, removing the row if the memo
appeared in between. Each side writes before it reads. A receipt handled on another dispatcher while the DM's row
is being written, such as an author's own send answered over a live link, therefore finds the row, or leaves a
memo that the second check finds. The rule applies whatever the origin, so every node applies the same one. The
purge stays recipient-authenticated: `verifyInbound` has already checked the receipt's signature, and the memo
matches only the recipient the DM itself names, so a forged receipt, or one from anyone else, still can't evict
an undelivered DM. A memo for a group or room frame, or from a non-recipient, never matches anything, because
those frames name no recipient or a different one. It costs one slot in the bounded set.

The alternatives came from the issue:

- **Serve a DM ahead of receipts in a digest reply.** This covers back-fill only; the recipient's relay would
  still land behind its receipt. With the memo in place it adds nothing.
- **Don't relay a DM addressed to us.** This removes the second path and nothing else. The relay may also be
  deliberate: a recipient that stops relaying its own DMs stands out to anyone watching the air.

**What it costs, what it doesn't cover.** A bounded in-memory set, one extra lookup after each custody write, and no
wire or schema change. The memo is stamped when the receipt arrives, which is after the DM was sent, so it always
outlives the DM's frame-global expiry (`sentAt` + 24 h). It can't lapse while a live copy circulates. Like `acked`,
it doesn't survive a restart. A node that restarts between the receipt and the DM carries the DM, as it would have
carried a re-planted copy before. Persisting both tombstones is a separate change. knit-ios mirrors the memo in its
own `onAck`/`onSeen`, or the two platforms diverge on exactly these ids again.

The trap for the next person: the memo is keyed on the **acker**, not on the id alone. A key of the bare id would
let any signed receipt that names a DM's id, from anyone, keep that DM out of custody, which is the forged-receipt
eviction that the recipient check exists to stop. The tests are `ForwardSyncTest`'s "a receipt that lands before its
DM" cases (the in-flight-write race, and the non-recipient and group/room negatives) and
`CustodyLabTest.aDmWhoseCleartextReceiptLandsFirstIsCarriedByNobody`, which forces the receipt ahead of the DM at a
carrier and fails on custody parity without the change. The lab rig that produces a cleartext receipt is
`LabNode.answerWithCleartextReceipts`.
