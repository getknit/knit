---
id: "2026-09.5dt2"
slug: a-queued-lora-frame-is-re-asked-the-gates-it-passed-at-enqueue
title: "A queued LoRa frame is re-asked the gates it passed at enqueue"
date: 2026-09-09
topics: [lora, airtime]
---

# ADR 2026-09.5dt2 — A queued LoRa frame is re-asked the gates it passed at enqueue

Status: Accepted (2026-09-09)

**Superseded in part by ADR 2026-10.x9fa (2026-10-29):** `RideGate.reachGated` and `StaleAtSend.UNHEARD` are gone with the reach gate; a compact DM's gates are re-asked at send through `RideGate.direct` / `reachHeld` / `unackedFrameId`.

The airtime row on a lab Pixel 7 read **100 %** while the phone sat alone in a room. The number was
honest — `totalMs 44629 / liveBudgetMs 45000` — and so was the ledger behind it: the rolling window
drained on schedule fifteen minutes later, and every sample reconciled to the packet against the log. The
board's own `airUtilTxPercent` was 1.4 %, which is the gap that made it look like a display bug and is not
one. This is neither the hour-versus-fifteen-minutes units mistake of the `hop_limit` work nor the stale
snapshot the signal row had; the plane really did spend its whole allowance.

It spent it in one 27-second burst: 22 packets, ~2 s each at LongFast — `profile-self parts=3`,
`fanout:profile parts=3`, and eight `far:chat parts=2`. The trigger was reconnection, not a message. The
phone had been isolated for hours (`live=[] reach=[]`), two peers appeared on Wi-Fi Aware at 15:58:26, and
a backlog landed: `delivered` 951→1058 and `relayed` 716→819 in one minute. The receipts it then owed are
sealed `CTL_RECEIPT` ctl frames, wire-indistinguishable from chat (ADR 018), so each one fanned as
`far:chat`. **One typed message, twenty-two packets.**

None of them needed to ride. `fanout` asked `coveredByLink` at enqueue, and at that instant
`hzwyqdbj…` was not in `linkedPeers` — its frames were arriving *relayed* via `ijeeg…` (`hop=ijeeg…`).
Its own link came up at **15:58:31.987**, four seconds later. `LoraPacePolicy` then drained the queue one
frame per 3 s over the next 24 s and `sendFrame` never re-asked, while the same receipts went out over
Wi-Fi Aware anyway (`fast-send → hzwyqdbj…` at 15:58:31.6 and 31.9). The far side proved it: at 16:08:46
this phone received `rx chat id=cy4R93SxcMQ58LTYLIZ3IA` over LoRa — the id it had already received over
Wi-Fi Aware at 15:58:27.564. Both phones were doing it, in both directions.

**The queue is a time-delayed commitment.** The pacing floor, a full board queue and a spent airtime
share can hold a frame for a whole window — `loraAirtimeHeld` was 101 that session, and ADR 054's own
residuals note the dwell can outrun the ten-minute sig window. Every gate `fanout` passed was answered
about the mesh as it was at that moment.

So `OutboundFrame` now carries a `RideGate` — the enqueue-time answers, as data the queue can hold
without holding the envelope — and `sendFrame` re-asks them through `staleReason` before the first
fragment leaves. `RideGate` carries only the questions its path actually asked, and that is the whole
design rather than a detail: a uniform re-check would role-gate `fastSend` and walk straight back into
ADR 044's field amendment, where suppressing a `relay = false` targeted send stranded AckSync's ticks for
their full 24 h of retries. The recipient half reads `linkedPeers` — links, never sightings — for the
third time and the same reason.

The alternative a reader reaches for first is evicting the queue from `suppressDataPath` when a peer
links. It is strictly weaker: it still races a frame enqueued after the sweep, and it would teach
`LoraPacePolicy` about the link set, which ADR 2026-09.t8t8 declined for pending airtime on the same
grounds. Re-asking at the last moment before the frame costs air needs no queue walk and cannot be raced.
Delaying the enqueue instead was rejected outright — latency on the far case is what the plane exists to
avoid.

**Discarding, not holding, is deliberate**: it is the same judgement `fanout` already makes, and the
frame stays repairable — backfill re-serves it (`serveOne` has not consulted `sigSeen` since ADR
2026-09.y8pu) and AckSync retries a tick for 24 h. The check is skipped once `sentParts > 0`, or a
part-sent frame would strand the fragments the board already holds.

`loraStaleAtSend` is a **new** counter with a `StaleAtSend` reason map, deliberately not folded into
`loraSkippedLinked` or `loraSuppressed`: those say the gate refused a frame before it ever queued, this
says the queue held it until the answer moved, and only the split measures the race. `loraDroppedQueue`
must not move for it (ADR 2026-09.xdm2 — it means the plane shed something it wanted). The field oracle is
`…debug.LORA`'s `loraStaleAtSendByReason`; `LINKED` climbing while the phones are in range is airtime
saved, `LINKED` climbing while they are far apart means the link set is lying.

What it does not cover: nothing reclaims the queue slot early, so a dead frame still costs a 3 s pacer
turn ahead of live traffic — the eight in the field cost 24 s of queue latency, which this fix does not
recover. Nor does it touch what *made* six receipts: `DmAckCoalescer` holds only LoRa-arrived ✓✓, so a
burst that arrives over Wi-Fi Aware is not its business.

The trap is the sighting half. `onForeignReachable` is not a link, and refusing on one takes away a far
peer's only route — pinned by `aQueuedDmStillRidesWhenItsRecipientIsOnlySighted` and
`aQueuedTargetedSendSurvivesGoingPassive`, beside `aQueuedDmIsAbandonedWhenItsRecipientLinksWhileItWaits`
(the bug), `aQueuedChatThatAgesPastTheFreshnessWindowIsAbandonedAtSend`,
`aQueuedFanOutIsAbandonedWhenACoPocketBoardTakesTheRole` and
`aPartSentFrameFinishesItsFragmentsEvenWhenItsRecipientLinks`.

**Device-verified 2026-09-09** on the lab Pixel 7 + Pixel 9 (Heltec V4 boards, both in BLE/NAN range, so
the original isolation could not be staged — the mechanism does not need it). The P9 sat `PASSIVE` with a
link up and five frames queued behind a 94 %-spent window; when the window released at 17:16 every one of
them was refused at the gate rather than aired:

```
17:15:57.989 lora stale at send reoffer:cC1PHuUrziiKvv-0pW8x5g: linked
17:16:00.994 lora stale at send fanout:profile: passive
17:16:03.995 lora stale at send fanout:profile: passive
17:16:06.999 lora stale at send profile-self: passive
17:16:10.002 lora stale at send far:chat: passive
```

`loraStaleAtSendByReason {LINKED: 1, PASSIVE: 4}`, `loraDroppedQueue 0`, `loraNak 0`, and the window went
to `totalMs 0/45000` — that air is simply never spent. The `reoffer` is this ADR's own bug: a DM-form
frame queued while its addressee was unlinked and refused because the link came up while it waited. The
P7 is the control and held ACTIVE throughout: four queued frames, all aired, `loraStaleAtSend 0`,
reception unaffected on both (`loraReceived`/`loraReassembled` equal).

The 3-second spacing between those five lines is the cost this does not fix, measured: each refusal still
consumes a pacer turn, so a dead frame delays live traffic even though it never reaches the air.
