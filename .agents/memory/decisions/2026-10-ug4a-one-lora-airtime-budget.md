---
id: "2026-10.ug4a"
slug: one-lora-airtime-budget
title: "One LoRa airtime budget, read off the board"
date: 2026-10-29
topics: [lora]
---

# ADR 2026-10.ug4a — One LoRa airtime budget, read off the board

Status: Proposed (2026-10-29; Stage 3 of the LoRa rewrite, drafted by the lora owner, pending the maintainer).
Supersedes ADR 2026-09.7svb's ledger half (the airtime ledger is no longer persisted; the auto-reply memory and the
re-probe schedule still are), ADR 054's tick class and tail (`FrameClass.TICK`, `TICK_TAIL_SHARE`) and its bucketed
budget, and ADR 044's buckets (`AirBucket`, with the `PUBLIC` share ADR 2026-10.rp3s kept). Keeps ADR 067's
dedicated-slot unlock, ADR 044's allowance and its 2026-10-04 holder amendment (narrowed below), and ADR 054's
15-minute window.

**Context:** after ADR 2026-10.rp3s and ADR 2026-10.x9fa the board airs only its user's own frames: one-packet room
posts, compact one-packet DMs with their ✓✓ and reactions, the Meshtastic room's posts and the DM auto-reply. The
governor still carried machinery sized for the bridge:

- two buckets (`LIVE`, `PUBLIC` at 15 %) with their own counters, a third pacing class (`TICK`) and ADR 054's tick tail,
  which refused a ✓✓ in a window's last quarter;
- a persisted ledger (ADR 2026-09.7svb), written on the grounds that "the ledger is the only thing enforcing" the
  regional duty cycle;
- no reading of the board's own report of the band. Its `channel_utilization` arrives about once a minute and only fed
  diagnostics.

The ✓✓ the tail held back is now one unsigned unicast packet, and losing it costs a re-probe of the whole DM 15 minutes
later — several airings to save one. And 7svb's premise was wrong: the firmware enforces the duty cycle itself.
`Router::send` refuses with `DUTY_CYCLE_LIMIT` over the trailing hour at the full regional duty cycle, wherever the duty
is under 100 % and `override_duty_cycle` is off (firmware `src/mesh/Router.cpp:484-510`, 332b5b0a2), and the pacer
already backs off 60 s on that NAK. `isTxAllowedAirUtil` is the firmware's own-telemetry gate and returns no NAK.

**Decision:**

- **One ledger, one total.** A booking is `(atMs, ms)`. Every frame of ours spends the one allowance, unchanged:
  `min(region duty, 10 % politeness) × 0.5` of 15 minutes, with ADR 067's dedicated-slot unlock. `LoraAirtime.refusal`
  takes a `Ceiling`: `FULL` for every Knit frame, `HALF` for the foreign audience. Its reasons are `TOTAL`, `CEILING`
  (which replaces `SHARE` and, like it, holds nothing) and `NEVER`. `refund` matches on `(atMs, ms)` alone, which is
  exact with one ledger: only a compact DM books five identical airings at one instant.
- **The foreign audience's ceiling is half the window, on the total.** A Meshtastic-room post or an auto-reply is
  admitted only while the window's total, its own cost included, stays within `PUBLIC_CEILING` (0.5) of the allowance.
  That is a deliberately larger slot-0 share than the 15 % bucket: with one ledger the room has no spend of its own to
  be judged by, up to half the window is the room's while Knit is idle, and a busy Knit window leaves it less. In
  practice the 30 s floors (`PUBLIC_POST_FLOOR_MS`, `DmAutoReplyPolicy.FLOOR_MS`) bind first.
- **The auto-reply asks for air before the sender is stamped.** `DmAutoReplyPolicy.judge` takes a `carried` check,
  asked after every cap and before either is claimed, so a `NO_AIR` refusal (now the policy's `Refusal.NO_AIR`) uses up
  neither the sender's one answer nor the floor. The reply is priced unsigned (`LoraAirtime.UNSIGNED`).
  `Router::perhapsEncode` (`src/mesh/Router.cpp:1219-1231`) signs only a broadcast, a `NodeInfo` or a position, or any
  unicast on a ham-mode board. A PKI unicast text is never signed, and the 4n5p pricing as a signed post over-charged
  it by 66 B.
- **Two priorities.** `FrameClass` is `DIRECT` (compact DMs, their ✓✓, reactions, resends, re-probes) and `ROOM` (Knit
  room posts, the Meshtastic room, the auto-reply). Dequeue goes by class, then FIFO. A full queue sheds `ROOM` first,
  then a `DirectKind.RECEIPT` before a message or a reaction (`OutboundFrame.shedRank`), so a ✓✓ never pushes out
  something the user typed. ADR 044's holder rule narrows to a `DIRECT` frame refused on `TOTAL`. That is safe because
  every room post is one packet, so none can be starved by smaller ones the way the two-packet DM of 2026-10-04 was.
- **The ledger is not persisted, and nothing seeds it.** A restart starts the window empty. Autonomous air after a
  restart is bounded by the persisted re-probe schedule: since ADR 2026-10.x9fa the re-probe is the only thing that
  airs without a user action, and a resend needs a hearing first. In a 100 %-duty region a restart resets politeness
  by at most one allowance. It never resets anything the law requires, since the law is the firmware's. Seeding the
  window from the board's `air_util_tx` was designed and rejected. That figure is the radio's total TX over the
  trailing hour, relays and its own broadcasts included (`airtime.h:20`, `airtime.cpp:215-224`). As a window it
  over-books a client on a busy mesh: 2.5–5 % an hour is 22–45 s against a 45 s allowance, which would refuse DMs for
  15 minutes after every launch and burn auto-reply stamps. It also under-books a burst in the last five minutes by up
  to four times. `air_util_tx` stays a diagnostic.
- **An own room post written before this process started never re-airs** (`LoraFramePolicy.predates`, the fan-out,
  `processStartWall` from `Process.getStartElapsedRealtime`). After a restart the router's seen window and `sigSeen`
  are empty. A neighbour's custody re-serve or a spool pull of a recent own post then reaches
  `InboundPipeline.onDeliver`'s re-fan (a self frame passes `verifyInbound`). The admit rule, freshness and dedup all
  pass it, so without this gate a crash loop would re-air the last quarter-hour's posts on every launch. Counted
  `loraSuppressed`, logged `lora not re-aired`.
- **A shared-band brake.** While the board's channel utilisation is at or over `BUSY_BAND_PERCENT` (40, the firmware's
  `max_channel_util_percent`, compared `>=` as it does, `src/airtime.h:230`, `src/airtime.cpp:304-316`) on a reading at
  most `BRAKE_FRESH_MS` (5 min) old, off a dedicated slot, the pacer skips Knit room posts without dropping them.
  This is still stricter than stock, which never gates text a client originates: it holds a typed room post only where
  the firmware stops even its own non-polite automatic traffic (`isTxAllowedChannelUtil()` at its default, 40 %), where a
  stock client would still send it. The polite 25 % (`polite_channel_util_percent`) was the first figure; the maintainer
  raised the brake to 40 so a merely busy band does not hold typed posts. The consequence: on a busy metro slot, at or
  over 40 % most of the time, Knit room posts may never cross LoRa and drop as `STALE` after `FRESH_MS`. The figure
  covers the last 60 s of TX, RX and noise together, our own airings included (six 10 s periods, `airtime.h`). DMs,
  the Meshtastic room and the auto-reply are not braked: the last two are the band's own kind of traffic, already
  floored at 30 s and capped at half. A braked post keeps its `FRESH_MS` age-out at send, so a band busy for longer
  than that drops it from LoRa (`STALE`); the radios, custody and the spool still carry it. The brake reads only the
  board's live self-telemetry, timed on arrival. The handshake's `NodeInfo` carries `device_metrics` that can be hours
  old (`PhoneAPI.cpp:612-627`, `DeviceTelemetry.cpp:37-41`) and never feeds it. Losing the board forgets the reading.
  A braked-only queue sleeps to the reading's staleness deadline, and a new reading wakes it. The brake is not an
  airtime refusal and never sets the air deferral. It is counted once per frame (`loraBandBraked`) and logged on its
  edges (`lora band busy` / `lora band clear`).

**Consequences / limits:**

- A dedicated slot is never braked: ADR 067 lifts manners there, and any congestion on it is Knit's own.
- `LoraPlaneSnapshot.air` is gone; the DataStore key is unchanged, and an older blob's `air` is ignored by the store's
  `ignoreUnknownKeys` (`LoraPlaneStateStoreTest.aSnapshotWithALedgerStillLoadsAndTheLedgerIsLeftBehind`).
- The re-probe snapshot's cap (`STATE_REPROBE_CAP`) keeps current direct board holders first, then the soonest due
  (`LoraReprobe.capped`). Non-holder entries do exist: `onHeld` runs while the board map has not reported, older
  builds' schedules are restored, and a holder can lose the capability.
- `…debug.LORA`'s `airtime` keys are `usedMs`, `allowanceMs`, `publicCeilingMs`, `braked` and `boardAirAgeMs`. The
  bucket keys and `loraAirtimeHeldByBucket` are gone, and `loraBandBraked` is new. The field log keeps
  `airUsed`/`airBudget`, now fed from `usedMs`/`allowanceMs` (the Meshtastic room's spend included).
- The status's `braked` is as of the last publish, so the row can show a lifted brake for up to a minute.
- Our own airings count toward the channel reading, so a phone that airs a burst can brake its own next room post for
  a minute or so.
- The room's "band busy" notice is a follow-up: it needs strings in six locales. Done 2026-10-29 (#240):
  `LoraReach.RoomBandBusy`, recorded in ADR 2026-09.ursc's amendment.
- Guards: `LoraAirtimeTest` (the single total, the half ceiling with its own cost, no tail, `refund` without a bucket);
  `LoraPacePolicyTest` (two classes, the receipt shed before a message, the holder narrowed, the brake: skipped,
  counted once, a braked-only queue not due, stale and dedicated readings); `LoraMeshTransportTest` (restart starts
  empty, an own pre-start post never re-airs, the brake end to end, a NO_AIR sender still answered and priced
  unsigned); `LoraDirectTransportTest` (a DM airs while a room post is braked, a ✓✓ airs in the window's last
  quarter); `MeshtasticSessionTest` (air read only off live telemetry, each reading stamped); `LoraReprobeTest` (the
  cap); `DmAutoReplyPolicyTest` (NO_AIR claims nothing).
- Owed board trials: a restart (a DM airs within a minute of Ready), the brake on a busy band or a board reporting
  `channel_utilization >= 40` with a stock third node, the settings row tracking `usedMs / allowanceMs`, and an
  auto-reply above half the window.
