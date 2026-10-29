---
id: "2026-10.rp3s"
slug: the-lora-plane-airs-only-its-user-s-own-frames
title: "the LoRa plane airs only its user's own frames"
date: 2026-10-29
topics: [lora]
---

# ADR 2026-10.rp3s — the LoRa plane airs only its user's own frames

Status: Proposed (2026-10-29; Stage 1 of the LoRa rewrite, drafted by the lora owner, pending the maintainer).
Supersedes ADR 044 in part (the gateway election, the gossip and the backfill), ADR 2026-09.qsj6, 2026-09.rre4,
2026-09.y8pu, 2026-09.zkma, 2026-09.t8t8 and 2026-09.7c8n, ADR 056, ADR 057, ADR 039 §8 (the beacon), the LoRa leg of
ADR 2026-09.y5f3, ADR 2026-09.wkbk, the profile and OFFER half of ADR 2026-09.xdm2, and the pocket and cleartext-tick
halves of ADR 2026-09.6gk8.

**Superseded in part by ADR 2026-10.ug4a (2026-10-29):** the persisted ledger and the mapping of a retired bucket's booking onto `LIVE` (`AirBucket.restoredFrom`). The ledger is no longer persisted and has no buckets; an older blob's `air` is ignored on load.
<!-- doc-lint: allow AirBucket — the mapping as rp3s shipped it, superseded by ADR 2026-10.ug4a -->
<!-- doc-lint: allow restoredFrom — the mapping as rp3s shipped it, superseded by ADR 2026-10.ug4a -->

**Superseded in part by ADR 2026-10.x9fa (2026-10-29):** the probe, the first-hearing re-offer and the heard-loss repair; the re-probe is kept, re-keyed on DMs without their ✓✓.

**Context:** The plane treated the board as one more mesh plane. Every frame aired byte-exact as a flood at hop 3,
and the plane aired a great deal its user never wrote: a pocket-mate's frames (the ADR 044 bridge), OFFER gossip and
the backfill it drove, re-offers of relayed DMs, the profile beacon and every relayed profile, and a ✓✓ from every
reader of every room post. Most of the plane's size — about ten thousand lines and forty ADRs — was rationing that
traffic: the gateway election, Trickle, the serve caps, five airtime buckets, co-pocket reach, the dedup-slot handback.
That rationing is where it kept breaking (the reviewer's thirteen shapes are mostly its incidents). The 2026-10-04
field walk showed the cost with the band idle (channel use 0 %): Knit's own 45 s / 15 min budget was the cap, 53 room
posts queued, 11 aired, and the DM never landed.

**Decision:** The board is an **endpoint**. It airs only frames this phone's user wrote; what it hears still goes
through the router, custody and the BLE/NAN relay into the pocket, which costs no air.

- **One admit rule**, `LoraFramePolicy.airs(authorId, selfId)`: a frame rides only when its author is our own id,
  and nothing rides before that id is read. `LoraMeshTransport.fanout` asks it first; a frame fanned before the id
  is known carries its author on `RideGate.authorId`, and `sendFrame` refuses it at send as `StaleAtSend.NOT_OWN` (the
  pacer already waits for the id), so a room post typed during a Keystore backoff still rides and a relayed one does
  not. One predicate on purpose: the deferred "Forward over LoRa" of a pocket-mate's room post is a second clause there.
- **What rides.** An own Nearby-room `chat` only when `LoraFrameCodec.encodeBest` fits it in **one** packet at the
  current `maxPayload` (a longer one counts `loraRoomTooLong`, not `loraTooBig`); own DM-form frames exactly as before
  (the reach gate, the probe, the own re-offer) until Stage 2 replaces them. Never a room `reaction`, never a
  `profile`, never anything somebody else wrote. `fastSend` refuses everything, so no room ✓✓ airs; a room tick
  goes home by a link or the spool. Room ✓✓ ids still ride an own DM tick as riders (`MeshManager`'s `riderCap`),
  which costs nothing extra.
- **Removed:** `LoraGatewayPolicy`, `LoraGossipPolicy`, `LoraCtl`, `BridgeFrameSource`, `ProfileFrameSource`, the
  serve caps, the beacon floor, `profileSeen`, the `BRIDGE`/`GOSSIP`/`BOOTSTRAP` buckets and the `BOOTSTRAP`/`GOSSIP`
  frame classes, `LoraPacePolicy.upgradeQueued` / `pendingSizes`, `RideGate.roleGated`, the relayed half of
  `MeshManager.framesFor`, the bridge switch (`SettingsStore.loraBridgeEnabled`) and its strings, and `FieldEvent.LoraRole`.
  Tag `0x10` stays **burned** (`FastFrameCodec`'s registry, `docs/WIRE_COMPAT.md`): older builds still air OFFERs,
  and this build drops them before decode under `loraRetiredCtl`, so `UNKNOWN_TAG` keeps meaning a tag nobody assigned.
- **`AckSync.escalateRide` keeps its air route** — it is every fast plane's, and Wi-Fi Aware's `fastSend` reaches
  cue-only peers — but reads `CompositeMeshTransport.shortRangeReachable`, so a LoRa-only author's tick stays parked
  for a link or the spool instead of being sealed into an owed entry nothing can carry.
- **The backed-off re-probe** (`LoraReprobe`). The beacons and the OFFER serves were what made a quiet recipient
  heard again; without them, a recipient whose board was off when the probe flew, and who has nothing to say, would
  strand every DM held behind it. Per recipient with own unacked DMs, the linger sweep re-airs the **newest** as a
  probe on `LIVE`: one `DM_PROBE_WINDOW_MS` after the hold, then at a gap that doubles to 4 h, sharing the probe
  window with the fan-out's probe, deferred while the recipient is heard or covered or a copy is still queued, reset to
  the base gap by a first hearing or an acked DM, and dropped only when no own unacked DM is left. Only a board holder
  is probed or re-probed: a recipient this plane hears (reach is author-keyed and fed only by fresh frames off their
  board), or one whose pinned profile names a board (`PeerEntity.loraNode`, from `MeshManager.loraBoardHolders`).
  Every held recipient is scheduled all the same, and a non-holder is deferred at no air cost — so a `loraNode` that
  arrives later is probed on the next due round without a new DM. Unknown is "not now", never "never": while the
  holder source has not reported, the reach gate holds without a probe and the re-probe defers, so a restored schedule
  outlives a slow first emission. A failing source is caught and counted (`loraBoardHoldersFailed`). A
  recipient who keeps being heard is not re-probed; instead, once the newest own unacked DM to them is older than one
  gap and was not re-offered within the hour, it is re-aired as a re-offer (the heard-loss repair) — a heard
  recipient with no pinned board included. Before our own id is read, the fan-out admits at most `PRE_ID_ADMISSIONS`
  (4) frames, refusing the rest under `loraPreIdRefused`, so relayed re-fans cannot fill the queue and shed an own post
  typed during a Keystore backoff. A slot is charged only to a frame that reaches the queue — past the covers, the
  freshness check, the encode, the one-packet rule and the dedup — so a covered, stale or oversize frame never spends
  the slot an own post needs. The recipient's ✓✓ makes
  them heard, and the ordinary first-hearing re-offer carries the rest. Every own DM to a recipient puts them on the
  schedule, a heard one too: a DM lost on its way to a heard recipient has no first hearing to repair it until they
  linger out, and nothing is re-probed while they stay heard. NodeInfo and telemetry are never a hearing (`BoardQuiet`
  stretches them to hours, and the handshake replays a synthetic NodeDB). Its schedule persists beside `dmProbes`.
- **`sigSeen` is the fan-out's alone.** The receive-side add (it stopped a heard frame being re-fanned) is dead under
  the admit rule and is dropped. The re-offer and the re-probe no longer consult it — it records a transmission, and
  a probe into an empty sky must not take the slot the re-offer needs now that `serveOne` (ADR 2026-09.y8pu's repair)
  is gone — and ask `LoraPacePolicy.hasQueued` (`frameKey`) so a copy still queued is not doubled. `reofferedAt`'s hour
  stays the re-offer's bound. A first hearing waits `REOFFER_SETTLE_MS` (2 s) before it re-offers, so the ✓✓ that made
  it a first hearing — very often the probe's own — is applied first and the probe is not re-aired.
- **Persisted state.** A blob the last build wrote still loads (the store ignores the dropped keys) and keeps every
  limiter this build has; a `BRIDGE` or `GOSSIP` booking is restored as `LIVE` (`AirBucket.restoredFrom`), so the first
  launch after the upgrade gets no free window, and a `BOOTSTRAP` one, judged outside the total, is dropped.

The alternative a reader reaches for first is to keep the bridge and tune its rations again. That is the forty ADRs:
each fixed one starvation and exposed the next, and the field walk failed on Knit's own budget with the band idle.

**Consequences / limits:**

- A board-less pocket-mate's frames no longer cross on a neighbour's board; they reach a far pocket by a link, the
  spool, or not at all. A manual "Forward over LoRa" is deferred (`roadmap.md`).
- A stranger heard only over LoRa is never shown: no profile rides the plane, so an author with no pinned key stays in
  `PendingInbound` and expires, as before.
- A room ✓✓ to an author the board alone can hear waits for a link or the spool, possibly for good.
- Older builds on the channel keep bridging. Their airings still count as reach here (author-keyed, fresh only): the
  co-pocket withdrawal of ADR 2026-09.6gk8 needed their OFFERs, which this build drops. A frame an old build's
  gateway airs on behalf of our own pocket-mate can therefore read that pocket-mate as LoRa-reachable; nothing of
  ours airs to them on that alone except own DMs, which the link cover still stops.
- Air: no beacon, no OFFER, no profile, no room ✓✓ — with no own unacked DM in the last 24 h the board airs nothing
  of its own accord. What it still spends unprompted: re-probes for own unacked DMs to **board-holding** contacts (a
  pinned profile naming a `loraNode`, or heard on this plane; a board-less contact is scheduled but never probed until
  one of those holds), one DM-sized packet or two at 15, 45, 105,
  225 and 465 min and then every 4 h — at most about nine per contact per day, inside custody's 24 h — and the
  heard-loss repair: a recipient who keeps talking is not re-probed, but the newest own DM to them that stays unacked
  for a whole gap is re-aired as a re-offer (at most once an hour per frame, on the same doubling gap). The re-offer may
  re-air a frame a probe just aired if the recipient is heard inside ten minutes by something other than that probe's
  ✓✓ (the settle covers the ✓✓); that is the repair working, at one DM's air.
- Guards: `LoraFramePolicyTest` (the admit rule, no reaction or profile), `LoraMeshTransportTest` (a relayed room post,
  a reaction, a profile and a relayed DM never air; an own one-packet post airs and a two-packet one is counted;
  `fastSend` refuses; nothing before the id; the re-offer ignores the dedup slot; the re-probe scenario and its
  persistence; the retired tag), `LoraReprobeTest`, `LoraAirtimeTest` (`restoredFrom`), `LoraPlaneStateStoreTest` (an old
  blob), `CoordinationPlaneSizeBudgetTest` (`LoraSizeHint.ROOM_BODY_BYTES` fits one packet at the MTU-255 floor with
  incompressible text), `AckSyncRideDeadlineTest` (a LoRa-only author's tick stays parked), and the lab's
  `LoraPocketLabTest` / `RoomTickPlanesLabTest`. The board trial is owed: a pocket-mate's post never airs, an own
  one-packet post lands, an idle board airs no `PRIVATE_APP` over 30 min (`meshtastic --listen`, `loraSent` and
  `airtime` flat), and the strand trial (the re-probe) lands both DMs.
