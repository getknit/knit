---
id: "2026-09.6gk8"
slug: a-co-pocket-board-s-airing-is-not-lora-reach
title: "A co-pocket board's airing is not LoRa reach, and a cleartext tick backs off"
date: 2026-09-26
topics: [lora, acksync, receipts, airtime]
---

# ADR 2026-09.6gk8 — A co-pocket board's airing is not LoRa reach, and a cleartext tick backs off

Status: Accepted (2026-09-26)

The lab Pixel 9's airtime row read **100 %** for well over an hour after a burst of test posts had
stopped. The ledger was honest again, as in ADR 2026-09.5dt2: 27 bookings × 1 665 ms = 44 955 of 45 000 ms
(every packet is padded past the 2.8 signature cliff, so they all cost the same and a 28th never fits), with
32 frames queued behind them, `loraAirtimeHeld` at 4 465 (LIVE), `loraDroppedQueue` at 4 105 and
`receiptsResent` at 8 082. What held it full was one log line, 218 times in 57 ms:
`lora pad send:receipt->t5ruzvj…` — cleartext delivery ticks owed to a board-less test peer (the iOS port on
a host dongle). The P9 was PASSIVE and linked over BLE to the P7, the ACTIVE gateway.

Three faults stacked:

1. **A co-pocket gateway's airing counted as reach.** `onFramePacket` credits the frame's *author* with
   presence (ADR 2026-09.2ajk — deliberately, since that is how a far pocket reaches a board-less author
   through its gateway, ADR 2026-09.wkbk). But the P7 airs what the pocket's own radios carried to it, so
   the P9's board heard `t5ruzvj`'s posts off the P7's board and put `t5ruzvj` in LoRa `reachable`. The
   dump showed it: `boardsHeard: 1` against `heard: 5`. `fastSend` gates on "not linked", and `t5ruzvj` was
   linked to the P7, not the P9, so every owed ✓✓ went to the air.
2. **A cleartext owed tick never backed off.** `AckSync.backOff` exempted the cleartext form because a
   retry costs the *author* only a SeenSet dedup. The cost that matters is the sender's: every `heal()`
   (heartbeat, motion, each app resume on a phone in daily use) re-sent every entry, for the 24 h TTL.
3. **Its retries never deduplicated on LoRa.** The cleartext form is rebuilt with a fresh id per attempt,
   so the targeted path's sig-keyed window never matched a repeat, and each retry pass queued the whole owed
   set again behind the copies still waiting for air.

**What changed.**

- The plane learns which Knit node owns a radio from that radio's OFFERs (`packet.from` → the OFFER's
  publisher key; an OFFER is only aired by its publisher's own board, and a Meshtastic repeat keeps `from`).
  A fresh frame aired by our own board or by a board whose owner is in `pocketKeys()` — a node we hold a
  live link to — is **not** presence evidence (`LoraMeshTransport.airedByPocket`). Each author remembers
  the radio that last put it in `reachable` (`heardVia`), so the hearing is withdrawn the moment that radio
  turns out to be co-pocket: when its OFFER is first heard, or when `suppressDataPath` links its owner.
  Delivery, dedup, custody and relay are untouched; `boardsHeard` still counts every radio.
- `AckSync.backOff` applies to both forms: the same doubling schedule (15 m … 8 h cap, ~8 sends per 24 h),
  and a live link still overrides it.
- `fastSend` keys a cleartext `receipt` on (recipient, acked id) instead of its signature
  (`targetedKey`), so the 10-minute window holds one packet per tick however many attempts are made.

The first fix a reader would reach for — credit presence only to the board's own owner, via the profile's
`loraNode` — breaks ADR 2026-09.wkbk: a far pocket's tick to a board-less author rides the far gateway's
hearing of that author and is handed the last hop by `MeshRouter.handOn`. The rule here is narrower: only a
radio *in our own pocket* stops vouching, because anything it airs reached us, or will, over BLE/NAN.

**What it costs and does not cover.** A radio whose OFFER has not been heard yet is taken as far (today's
reading), so a co-pocket gateway can still put authors in `reachable` for up to one gossip interval after
the P9 first hears it. The backoff and the receipt key bound what that costs. "Last radio wins" can
withdraw a far author who was *also* aired by a co-pocket gateway, for example a frame the gateway pulled
off a spool, until the far gateway airs them again. The cleartext tick toward a relay-only author with no
board and no spool is still stranded, as before. That is ADR 2026-09.wkbk's open residual and not a
regression: the author's directly linked peers tick it. A passive board does not OFFER, but it airs only
its own frames and targeted ticks, never somebody else's post, so it needs no binding.

Kept true by `LoraMeshTransportTest.aCoPocketGatewaysAiringIsNotReachForItsAuthors`,
`aFarGatewaysAiringStillPutsItsBoardlessAuthorInReach`, `linkingTheGatewayWithdrawsTheReachItsBoardVouchedFor`,
`aRadioLearnedToBeCoPocketWithdrawsItsEarlierHearings`,
`aCleartextTicksRetriesDedupOnWhatTheySayNotOnTheirSignature`, and
`AckSyncTest.aCleartextTickBacksOffLikeTheSealedOne` / `aBackedOffCleartextTickStillGoesHomeTheMomentALinkExists`.
