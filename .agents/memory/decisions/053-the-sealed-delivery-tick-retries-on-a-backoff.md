---
id: "053"
slug: the-sealed-delivery-tick-retries-on-a-backoff
title: "The sealed delivery tick retries on a backoff, because its retry cadence outran the dedup window"
date: 2026-08-27
topics: [crypto, custody, receipts]
---

# ADR 053 — The sealed delivery tick retries on a backoff, because its retry cadence outran the dedup window

A Pixel 9 left running for a few days logged **332 `RATCHET_DUPLICATE` drops**. Nothing was wrong with the
crypto — that reason means a v2 DM landed on a chain index below `epoch.next` (`RatchetEngine.open`), which is
*proof we already decrypted that frame*, which is exactly why ADR 024 took it back out of
`RESET_TRIGGERING_DROPS`. It was `AckSync`, and the count was a cadence bug hiding behind a benign label.

**Two windows in the wrong order.** A sealed delivery tick is sealed **once** and every retry re-sends those
bytes verbatim — sealing consumes a DM chain key, so per-retry re-sealing would burn epochs and starve real
DMs out of the receiver's ≤200/epoch skipped-key budget (ADR 018). Verbatim means one frame id for the whole
life of the entry, and the only thing that can absorb a repeat is the router's `SeenSet`. That window is
**10 minutes**; `retryPending` runs off the heal heartbeat, which is **15** (`MeshService`'s
`INTERVAL_FIFTEEN_MINUTES`). So *every* retry cleared the window, reached the ratchet, and hit a consumed
index — `OWED_TTL_MS` / 15 min ≈ **96 duplicates per stuck tick**, all the way to the 24 h TTL. 332 is three
or four such ticks. The exists-gate cannot help either: a ctl DM never persists a message row, so
`messages.exists` is false for a tick forever.

The entries that drip are the ones holding **cached sealed bytes** — a broadcast-room tick
(`escalatable = false`, and `sealDeliveryTickEnvelope` seals for any `CAP_RATCHET` author regardless of
group-ness) toward an author reachable over the coordination plane but never live-linked, plus the race where
a group author unlinks between the `absent` check and the send. The other two owed forms were already clean:
the cleartext form is rebuilt with a fresh id per attempt, and an escalated batch is remembered in `escalated`
and never re-sent.

**The fix is the schedule, not the seal.** A sealed owed entry now carries `retries` + `nextAttemptAt` and
doubles from one heartbeat — 15 m, 30 m, 1 h, 2 h, 4 h, then an 8 h ceiling — so the same 24 h horizon costs
~8 best-effort re-sends instead of ~96, a 12× cut. Nothing about the seal, the frame, or the TTL changes.
`sweep` still ages the entry out on `recordedAt`, so the backoff cannot extend an entry's life, and a tick
lost outright still self-heals the way it always did: the message re-serves through the deliver path and
re-`owe`s it.

**A live link always overrides the schedule.** It is reliable, it ends the entry, and it is precisely what the
backoff is waiting for — so `sendOwedIfDue` resolves the link first and only throttles the best-effort
coordination-plane send. A re-owe rides the same schedule as the heartbeat for the same reason the heartbeat
does: the bytes are identical, so an off-schedule re-send is one more duplicate at the author.

Rejected: **raising the `SeenSet` TTL above the heartbeat** — one constant, kills the drops outright, but it
is a global flood parameter and lengthening it delays every legitimate re-propagation, not just this one.
And **letting broadcast ticks escalate** like group ticks (sealed once, custodied, remembered ⇒ zero
re-sends), which reverses ADR 033's deliberate choice to keep the broadcast room best-effort-only as the
ambient, short-lived class — trading 332 cheap drops for real custody rows across the mesh.

`receiptsResent` is now counted only when something actually goes out, which makes it a re-send tally rather
than a heartbeat tally and the corroborating counter for this drop reason. Three JVM tests in `AckSyncTest`
pin the curve (8 sends across the 24 h TTL, the exact due steps), the live-link override, and the cleartext
form's exemption.

## Amendment 2026-10-27 — a lost tick does not self-heal by a re-serve

The sentence above, "a tick lost outright still self-heals the way it always did: the message re-serves through the
deliver path and re-`owe`s it", stopped being true with ADR 018. Since then the recipient custodies its own inbound
DMs, and every member already custodied a group or room frame. Once we hold the frame our digest folds it, the
carriers' digests agree with ours, and nobody re-serves it. The exists-gate re-acks only on a re-delivery, and a
re-delivery now takes real divergence: our copy evicted by quota, a wipe, or a plane that re-offers without a digest
(LoRa). So a tick lost before it was sealed and custodied is lost for good, and nothing in this ADR's schedule brings
it back.

Finding 11 of #141 is the case that showed it. A spool worker that a reconcile stopped mid-delivery cut a DM's delivery
after the row's commit and before its one-shot sealed receipt. Every digest then agreed, and the sender never saw
✓✓. ADR 2026-10.f82h closes the cancellation half: the router finishes a delivery it admitted, whoever cancels the
caller. Process death inside the same window is still open (#56). `DmAckCoalescer`'s and `AckSync`'s KDocs say
the same thing.
