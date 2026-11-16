---
id: "2026-11.z6jw"
slug: read-receipts-ride-the-delivery-receipt-as-a-read-mask
title: "Read receipts ride the delivery receipt as a read mask"
date: 2026-11-15
topics: [receipts, wire, lora, custody, ui]
---

# ADR 2026-11.z6jw — Read receipts ride the delivery receipt as a read mask

Status: Accepted (2026-11-15). Amends ADR 036 (its "Deliberately NOT done: read receipts") and qualifies ADR
2026-10.8hn6's 2026-11-06 amendment ("every signed DM ✓✓ records"). Spec: `docs/ENCRYPTED_RECEIPTS_REACTIONS.md` §9.

**What was asked.** The author of a DM or a private-group message learns that it was read, by whom and when,
the way the ✓✓ already tells them it arrived: sealed, carried through custody to an author who is away. A
Settings › Chats switch, on by default. LoRa may carry a read only on a packet that is going anyway. Message
details lists each reader and the time.

**What changed.**

- **Wire: one additive field on `CTL_RECEIPT`, not a new ctl.** `MessageContent.rm` (named `"rm"`, compact label
  20) is a bitmask over the frame's acked ids as sent (`ack` then `acks`; on a plain DM's inline acks, `acks`
  alone): bit i means "and I have read id i". Bits past the list are ignored, which is what lets the encoded width
  depend on the list's length alone (`ReadMask`): up to 4 ids every mask is one CBOR byte, from 5 to 62 the sender sets
  the first bit past the list so the value's width is fixed per n, and at 63 or 64 ids the mask goes as it is, the
  one residual (only an escalated group batch, custodied, never LoRa). A width that moved with the read pattern would
  tell which ids were read, which the first cut of this decision missed and review caught. A new `CTL_READ` was the obvious shape and is wrong here: every shipped build refuses an unknown ctl at
  the LoRa compact door and the unsigned live-link door, so it could never share a packet with a ✓✓, and the
  maintainer's LoRa rule needs exactly that. An old build skips the label and applies the ids as a ✓✓, which is
  true: a read implies a delivery.
- **`Protocol.CAP_READ_RECEIPTS` (`0x200000`)**, always advertised, read off the pinned profile. The fallback is
  correct without it, but would spend a custodied frame per read burst toward every author who cannot use it (old
  Android, all of iOS) for an idempotent re-tick. Toward a capable author every `CTL_RECEIPT` and every plain DM
  with inline acks carries `rm` at its fixed width, nothing read or the switch off included, so a frame's length never says
  "read within the hold" or "the switch is off" (a compact ✓✓'s length is visible on the public band and indexed
  by MQTT servers, ADR 2026-09.9469). Toward an incapable author `rm` is absent and the bytes are today's.
- **Receiver.** `MessageReceiptRepository.record(…, read)` stamps `message_receipts.readAt` (DB v35), our clock at
  apply time and never earlier than the row's `notedAt`, for ADR 036's reason. A read lands only on our own DM or
  private-group message: `ackerFor`'s room arm would otherwise let any signed peer write one.
- **Reader.** A message counts as read once at least half its bubble has stayed on screen in the foreground
  chat for 600 ms, or when the notification's Mark read clears it. The durable `read_marks` table is the outbox,
  since a read has no heal path: the exists-gate re-acks a delivery, never a read. `ReadSync` sets bits for free
  on every receipt the mesh seals toward that author anyway, carries owed ids as riders on the signed copies, and
  otherwise sends one standalone `relay = true` custodied frame per author, with no `relay = false` form. That
  frame waits 45 s for a linked author and 15 min, doubling, for an absent one, and stays under a per-reader
  budget. A read is "told" only once a `relay = true` frame carrying it is originated, or the spool push returns
  pushed. A seal, a live-link send, a compact LoRa bit and an owed entry never count. A root change toward the
  author owes the reads it can no longer open again.
- **LoRa.** Nothing read-related ever calls the compact door by itself. The coalesced ✓✓ and a reply's inline
  acks carry bits for ids the person has already read (`readAmong`, told or not): key and value cost 2 bytes on 1–4
  ids, 3 on 5–7 and 4 on 8–9, so a 9-id tick is 162 of its 168 bytes. The inline acks keep their 23-byte reservation, and no byte is added to it. A read that
  misses the 45 s hold reaches a LoRa-only author through custody or the spool. Groups never reach LoRa (ADR
  2026-10.rp3s).
- **UI.** `DeliveryStatus.Read` and `KnitIcons.ReadTick` (the ✓✓ knocked out of a disc, tinted primary: a new
  shape, so it reads without colour). A group send reads as read only once every current member has. Until then
  its description says "Delivered to N of M, read by K". Message details lists "Read by" (in order of arrival)
  above "Delivered to". The switch is reciprocal like typing indicators: off, nothing is sent and nobody's read is
  shown, while incoming reads are still recorded.

**What it costs.**

- **Custody.** Riding absorbs most reads: a reply, the next DM's instant receipt, an escalated group batch or
  the spool push. Riders add no custody row, and a spool push adds none either. The standalone frames each carry
  at most 64 ids, in ADR 033's terms:
  - per reader: at most 50 in any rolling 24 h (`DEFAULT_MAX_PER_SENDER / 4`, in memory, reset by a restart);
  - per (reader, author) while the author is absent: at most 7 a day (15 min doubling to 8 h), plus one per root
    change toward that author, all inside the 50;
  - while the author is linked: one per 45 s window, inside the 50.

  A reader's reads therefore cost at most a quarter of its own per-sender custody bucket. `ReadReceiptLabTest`
  checks that the reader's own undelivered DMs survive a read burst, but only at small scale.
- **8hn6.** Every signed DM ✓✓ records, except what it carries as a read. A read rider in `acks`, and the ids of a
  standalone read `CTL_RECEIPT` (`ReadSync`'s answer-only frame), are never claimed in or recorded to
  `DmReAckLedger`. The ledger files only the DM ids the receipt itself answers. A read the author already holds is
  absorbed by `readAt`'s fill-null-only write.
- **The spool push carries riders.** It is a signed `relay = false` push into the scope, and its riders are told only
  once the spool took it (`pushed = true`). Otherwise AckSync re-sends those bytes as an owed entry, which costs at
  most a duplicate read; the marks stay owed.
- **Privacy.** A standalone frame's existence shows on-path that the reader was reading. The switch off sends
  none; riding hides most.
- **Clocks.** "Read" times are when the read reached this phone.
- **The trap: never key a read on the delivery ledgers.** `AckSync`, `DmReAckLedger` and `DmAckCoalescer`
  dedupe by message id and would swallow it.
- **Tests that keep it true:** `MessageContentTest`, `GoldenVectorTest`, `CoordinationPlaneSizeBudgetTest` (9 ids
  plus a mask is one packet), `InboundPipelineTest`, `ReadSyncTest`, `ReadReceiptLabTest`,
  `MessageReceiptRepositoryTest`, `ReadMarkRepositoryTest`, `KnitDatabaseMigrationTest` (34 → 35),
  `MessageDetailsViewModelTest`, `ChatViewModelTest` and `DeliveryStatusTest`.
