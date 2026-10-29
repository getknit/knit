# LoRa bridge (Meshtastic over BLE) — the long-range Nearby-room + DM plane

How Knit carries **its user's own Nearby-room posts and sealed 1:1 DMs** over LoRa via a Meshtastic board
attached over BLE. The design rationale is ADR 038 (the plane), ADR 039 (DMs) and ADR 2026-10.rp3s (the board is an
endpoint: it airs only what its own user wrote); this file is the operational detail. Behind `BuildConfig.LORA_PLANE`, which is **on everywhere since 2.5.0** (ADR
2026-09.6gtm; `-PloraPlane=false` rebuilds the dark artifact 2.3.0–2.4.x shipped). Visible is not enabled:
`SettingsStore.loraEnabled` still defaults false and nothing leaves over the radio until a board is paired
and set up.

This file is the mechanism. Which ADR governs which symbol is `.agents/context/lora.md`; `grep` it before you
touch the plane.

## Shape

**Nothing here ticks until a board is configured** (ADR 2026-09.5bqu). The child is in the composite on
`BuildConfig.LORA_PLANE` alone, so on most installs it is started and never armed; `lingerSweepLoop` therefore parks
on `awaitConfigured()` rather than sweeping an empty heard set once a minute forever. Losing the board runs the same
`quiesce()` that `stop()` does — that is what makes the parking safe, since `recomputeReachable` is the only thing
that ages `lastHeardAt` out, and a parked sweep would otherwise strand the last board's peers in `reachable`
permanently.

`LoraMeshTransport` (`mesh/lora/`, pure) is a **fast-plane-only** `CompositeMeshTransport` child, added
LAST (lowest send-preference). `neighbors` is always empty, so the flood / custody digest sync / keyreq /
blob pulls never touch the ~1 kbps link — `send`/`sendFile`/`sendDigest` are no-ops, and since ADR 2026-10.rp3s so
is `fastSend`. Only `fastFanout`/`longRangeFanout` ride it, gated by `LoraFramePolicy`:

- **Whose (ADR 2026-10.rp3s).** The board is an endpoint: a frame rides only when `LoraFramePolicy.airs` says this
  phone's user wrote it. The composite re-calls the fan-out on every relay and `InboundPipeline.onDeliver` re-fans
  every DM-form frame it delivers — both plane-agnostic on purpose — and the admit rule refuses all of it. Until our own
  id is read nothing is ours: a frame fanned then is judged on its kind alone and carries its author
  (`RideGate.authorId`), and `sendFrame` refuses one that is not ours as `StaleAtSend.NOT_OWN` (the pacer waits for the
  id). The deferred "Forward over LoRa" of a pocket-mate's room post is a second clause of `airs`, nowhere else.
- **What** (`fastFanout` — the composite's coordination-plane blast — and `longRangeFanout` — the seam reserved for a
  plane with no data path, ADR 039; both land in one internal `fanout`): a broadcast `chat` (recipientId == null &&
  group == null) **that fits one packet** at the current `maxPayload` — a longer one is counted `loraRoomTooLong` and
  rides the radios and the spool only — and **DM-form chat** (`chat && recipientId != null && group == null`, any
  `relay`). The DM form is admitted opaque: a DM, its sealed receipt/reaction, a session reset, a group-key seed and an
  escalated group tick are wire-indistinguishable and all ride. `shouldLongRangeFanout` (`mesh/FrameFanout.kt`) feeds
  it — never widen `shouldFastFanout` for this; that predicate is the NAN coordination plane's. A room `reaction` and a
  `profile` never ride (no key bootstrap crosses the plane, so a stranger heard only over LoRa stays in
  `PendingInbound` and is never shown).
  **The recipient gate (ADR 054):** a DM-form frame addressed to us, or to a peer BLE/NAN holds a **live link**
  to (`coveredByLink`, read off `linkedPeers`, fed only by `suppressDataPath` — links, never sightings), is skipped
  before the sig-dedup slot is spent: the link carries it. Counted `loraSkippedLinked`. **The second cover (ADR
  2026-09.y5f3):** a DM-form frame to a peer a connected spool heard from within `SPOOL_COVER_MS` (15 min) is skipped
  the same way — fan-out and the first-hearing re-offer alike, and a frame already queued is abandoned at dequeue
  (`StaleAtSend.INTERNET`) — because the frame is in the spool anyway and the spool costs no air. Fed by
  `MeshTransport.coveredByInternet` (`internetPeers`, from `MeshManager`'s spool-presence watch), counted
  `loraSkippedInternet`, shown as `internetCovered` in `…debug.LORA`. The originator's `FanoutHint`
  (`CONTENT`/`CHAT`/`TICK`) rides beside the frame — see Pacing.
- **No room ✓✓ (ADR 2026-10.rp3s).** `fastSend` refuses everything, so AckSync's cleartext receipt and its sealed
  escalation never air. A room tick goes home over a link or the spool; `AckSync.escalateRide` reads
  `shortRangeReachable`, so an author the board alone can hear keeps the tick parked rather than sealing it into an
  owed entry nothing can carry. Room ✓✓ ids still ride an own DM tick as riders (`MeshManager`'s `riderCap`).

Everything else (group-form chat, `groupupdate`/`groupleave`, `typing`, `blobreq`/`keyreq`) is refused.

Outbound decodes `wire.signed` only to apply the policy, then reuses `FastFrameCodec` (ADR 030) to
compact/fragment: `sig`/`signed` pass through byte-exact, so this is **not a wire change** and the
originator's signature verifies unchanged. One packet may leave *larger* than the frame needs
(ADR 2026-09.mhs5): a 2.8 board signs anything at or under 165 B, so `LoraFrameCodec` grows the frame's **last**
packet to 166 and the board sends it unsigned — a few bytes of pad instead of 66 of signature, ~20 % off the
tick and the room post. Two rules keep that safe. The pad is only legal where a receiver ignores trailing
bytes, which is a **deflated** body (`FastFrameCodec.deflated`; a stored `0x03` would swallow the pad into
`signed` and corrupt silently, a stored `0x05` rejects it) — and the frames with most to gain *store*, because
the ADR 060 transcoder already took the compressible keys out, so a stored one-packet frame is re-deflated
first (`FastFrameCodec.deflatedForm`, a measured +5 B) whenever `LoraAirtime` prices the result cheaper.
Only the last packet moves, so the fragment count cannot change; fragmented stored frames are the case this
does not reach. Verified on a 2.8 board (2026-09-01): a real room post logs `lora pad fanout:chat +46B past
the signature cliff`, and a 166-byte payload is heard on a second board as `Lora RX … encrypted len=190` /
`Packet RX: 1262ms` where 165 B reads `len=255` / `1655ms` — that `encrypted len` is the cleanest signing
oracle there is, since `len = payload + 24` unsigned and +66 signed. **The tolerance is an accident of `inflate`'s loop, not a designed property** —
`FastFrameCodecTest.aDeflatedFrameIgnoresTrailingBytesBecauseThePaddedLoraSenderDependsOnIt` is what stops a
future "hardening" from breaking every padded sender against older receivers. Meshtastic `Data.payload` cap = **231 B** on the air for a Knit
packet (`MeshtasticProto.MAX_PAYLOAD`: the firmware transmits at most a 237-byte `Data`, and the two-byte
`PRIVATE_APP` portnum plus the payload framing take 6 — the proto's `DATA_PAYLOAD_LEN = 233` assumes a one-byte
portnum; measured 2026-08-29: 231 queues, 232 NAKs `TOO_LARGE`) → ≤ 3 fragments (ceiling `3 × 227 = 681 B`
compact); an MTU-255 board takes 228. Every frame the transcoder reproduces leaves in the `0x05` transcoded
form (`mesh/link/FrameTranscoder`, ADR 060; `loraTranscoded`, `transcodeFallbacks`) — a **flag-day** while the
plane is debug-only, since a LoRa sighting carries no capability: an older debug build on the channel drops
`0x05` as `UNKNOWN_TAG`. The gate it needs before the plane ships to release is in the roadmap. Before the board reports its MTU the cap is the MTU-255 floor
(`LoraMeshTransport.PRE_READY_PAYLOAD`), not the maximum — frames fanned out during the connect used to be
chunked at 233 and every one came back `TOO_LARGE`. Inbound mirrors `WifiAwareTransport.emitFastWire`: decode/reassemble →
`_inbound.tryEmit(InboundFrame(wire, env, fromNodeId = env.senderId))`, so the router's dedup / verify /
custody / relay all run unchanged.

> **`reachable` here is *reach*, never *nearby*, and only fresh frames feed it** (ADR 2026-09.2ajk).
> The set is keyed on the frame **author**, and an older build's gateway still puts other people's frames on air, so
> a peer with no board of its own can be in it. Nothing above the composite may read it as proximity:
> `MeshController.neighbors` (the notification count, the Contacts online dot, Diagnostics' *Directly
> connected*) is `CompositeMeshTransport.shortRangeReachable`; the full union is `MeshController.reachable`
> and only Diagnostics' *Reachable long-range* reads it. Writes are gated on
> `mesh/FramePresence.kt`'s `isPresenceEvidence` (shared with the Internet plane, which needed the same
> rule) — 15 min for everything except `profile`, which gets 13 h (the
> 12 h `PROFILE_REPUBLISH_MS` cadence plus slack) — because a backfill, a re-offer and a re-fan of a frame the
> **Internet plane** just pulled off a spool all put old frames on air, and taking those as presence showed a phone
> switched off for days as a live neighbour. It cannot reuse `isFresh` (which returns true for every non-chat type).
> The gate wraps **only** `noteReachable` — decode, dedup, delivery, custody and relay all still run.
>
> **Our own board vouches for nobody.** A fresh frame our own board airs back through a repeater is never presence.
> The co-pocket withdrawal of ADR 2026-09.6gk8 (`boardOwners`, `heardVia`, `pocketKeys`) read an older build's
> OFFERs to learn whose radio a board was, and left with them (ADR 2026-10.rp3s): an older build's gateway airing a
> board-less pocket-mate's frame can put that pocket-mate in `reachable`. Nothing of ours airs on that alone except
> own DMs, which the link cover still stops.
> <!-- doc-lint: allow boardOwners — the retired co-pocket machinery, named as what left -->
> <!-- doc-lint: allow heardVia — the retired co-pocket machinery, named as what left -->
> <!-- doc-lint: allow pocketKeys — the retired co-pocket machinery, named as what left -->

## The layers

```
LoraMeshTransport (pure)      fastFanout/longRangeFanout · LoraFramePolicy (airs + isFresh) · LoraFrameCodec ·
                              LoraPacePolicy (class shedding + LoraAirtime budget) · reachable(isPresenceEvidence, 45min linger) ·
                              boardsHeard + its RxQuality (the signal row, same 45min linger) ·
                              reofferTo on first hearing · LoraDmProbe + LoraReprobe (the probe and the re-probe)
  └─ MeshtasticLink (seam)    state / packets / outcomes / queue · suspend send()
       └─ MeshtasticSession   pure actor: want_config handshake · drain-until-empty on FromNum · 180s heartbeat ·
          (pure)              client packet ids ↔ queueStatus/NAK · reconnect-with-backoff (ConnectBackoffPolicy) ·
                              direct-then-background dial (BoardDialPolicy, ADR 2026-09.hp88)
            └─ MeshtasticGattDialer (seam)   dial() → connect · requestMtu(512, gate ≥263) · discover · resolve chars
                 └─ MeshtasticGatt           the ONLY android.bluetooth importer for the feature (mesh/bluetooth/meshtastic/)
```

`MeshtasticProto` + `ProtoIo` are a hand-rolled protobuf codec (zero new deps): `ToRadio{want_config_id,
heartbeat, disconnect, packet{Data{portnum=PRIVATE_APP 256, payload}}}` out; `FromRadio{my_info,
config_complete_id, packet, queueStatus, rebooted, channel, metadata}` in; `Routing.error_reason` NAKs.
Golden byte vectors pin every field number; malformed input decodes to null, never throws.

## Board facts (verified 2026-08-24)

- Service `6ba1b218-15a8-461f-9fa8-5dcae273eafd`; ToRadio `f75c76d2-…` (write), FromRadio `2c55e69e-…`
  (read; one protobuf per read, 0 bytes = drained), FromNum `ed9da18c-…` (notify, u32 LE counter).
- Bonding required in PIN modes; the V4's OLED shows a random 6-digit PIN. MTU 512 requested (gate ≥ 263 so
  the worst-case 259-B `ToRadio{packet}` is one write). ESP32 = **one BLE client** — the Meshtastic app must
  be disconnected from the board. Board **Wi-Fi must be off** (it disables the board's Bluetooth).
- **The Exchange MTU must not race the bonding handshake** (verified 2026-09-01, Heltec V4 / 2.8 alpha
  `2.8.0.7239fe8`). A bonded board starts SMP the moment the ACL is up, and `connectGatt`'s
  `STATE_CONNECTED` reaches us *before* the link is encrypted — so an MTU request sent on that callback
  lands mid-handshake and the board answers too late to be heard: the app sees the default 23 with a
  non-success status (`onConfigureMTU(…, 23, 6)`), and the real response shows up in the stack log as
  `ATT - Ignore wrong response. Receives (03)`. `MeshtasticGatt` therefore waits `SETTLE_MS` before the
  first exchange and retries, and `onMtuChanged` trusts the reported size over the status (the stack
  reports the bearer's current MTU either way). This is not board-specific and not Knit-specific — the
  Meshtastic app loses the same race; BlueZ wins it only because it touches no ATT until the link is
  encrypted, which is the fastest way to tell a phone-side stall from a real board fault.
- Handshake: write `ToRadio{want_config_id=N}`, drain FromRadio until `config_complete_id=N` (no MeshPacket
  before that); then FromNum notify → drain until empty. `ToRadio{heartbeat}` every 180 s keeps the phone
  API alive; the node answers `queueStatus{free,maxlen,mesh_packet_id}` (also after each packet) — flow
  control. `rebooted`/unsolicited `my_info` → re-handshake.
- Send `MeshPacket{to=0xFFFFFFFF, channel=idx, id=client nonzero, hop_limit=3
  (`LoraMeshTransport.HOP_LIMIT`, **stated**), want_ack=false}`.
  **Omitting it does not mean the node's default — it means zero** (measured 2026-09-04, fw
  `2.8.0.47db0e3`, `lora.hop_limit = 3`). A/B from one board to another, 6 x 200 B each arm, watching the
  receiving board's own `airUtilTx` over serial: with the field omitted every packet arrived
  `hop_limit = 0 / hop_start = 0` and the neighbour relayed **nothing** (0.7348611 % flat across the arm);
  with `--ei hop 3` every packet arrived `3/3` and the neighbour's airtime rose **+0.2646 % = 9.5 s**, about
  five of the six repeated. The same board's NodeDB shows 110/110 stock nodes sending `hop_start = 3`. So
  2.8's `LOCAL_ONLY` **does** relay a secondary-channel packet, and the only thing stopping ours is a hop
  limit of zero: until this was stated, ADR 045's borrowed hops had never happened and ADR 044's bridge had
  only ever worked board-to-board **direct**. `everyPacketLeavesWithHopsToSpend` is what stops it
  regressing — a missing hop limit is invisible in the payload, so only the send records catch it. Reading
  the board's own `lora.hop_limit` instead is deliberately not done: absent decodes as 0, which is the bug.

  **The airtime this buys is not yet budgeted.** Measured, 8 x 200 B from one board with the other in range
  and originating nothing:

  | | originator | neighbour |
  |---|---|---|
  | hop omitted | +0.365 % (13.1 s) | **-0.042 %** — decay, nothing relayed |
  | `hop = 3`   | +0.389 % (14.0 s) | **+0.443 % (15.9 s)** — pure relay |

  The originator's own cost is the same in both arms, so the whole difference is the neighbour: **one
  relaying board doubles a packet's air**, and `LoraAirtime`'s ledger did not move by a millisecond in
  either arm. In a 100 %-duty region the only ceiling is the 10 % politeness figure, halved by `SAFETY` to
  the 5 % it budgets — so **the safety factor is worth exactly one relaying neighbour**, before any stock
  node repeats us. Owed a follow-up.
- NAKs: portnum ROUTING_APP(5), `request_id` = our id, `error_reason` (NO_CHANNEL 6,
  TOO_LARGE 7, DUTY_CYCLE_LIMIT 9, RATE_LIMIT_EXCEEDED 38) — counted per reason as `loraNakByReason`.
- The router transmits at most a **237-byte `Data`** (`MeshtasticProto.LORA_DATA_MAX`, measured 2026-08-29 on a
  Heltec V4 / 2.7.26 with `…debug.LORATX`: a 231-byte payload queues, 232 and 233 NAK `TOO_LARGE`). The
  firmware's own budget is 239 (`MAX_LORA_PAYLOAD_LEN` 255 − the 16-byte header, `MeshtasticProto.DATA_ENCODED_MAX`);
  the two-byte gap the lab measured is `Data.bitfield`, which `Router::perhapsEncode` fills in after us. The ATT
  MTU is the *other* limit (`mtu − 27` at MTU 255 = 228); the effective cap is the smaller of the two.
- **Firmware 2.8 signs the broadcasts it sends for us.** `Router::perhapsEncode` attaches a 64-byte XEdDSA
  signature (`Data.xeddsa_signature`, +66 B with framing) to any non-PKI broadcast it originates that still
  fits signed — so a Knit payload **≤ 165 B** (`MeshtasticProto.MAX_SIGNED_PAYLOAD`) grows by 66 B on the air
  and a larger one goes unsigned exactly as before. It is a cliff, not a ramp, and it falls between ADR 060's
  one-packet room tick (157 B, +36 % air) and its one-packet DM ✓✓ (221 B, unchanged). Knit cannot ask for it
  or decline it; `LoraAirtime` charges for it, and since ADR 2026-09.mhs5 **dodges** it — see the codec note
  below. Receiving is unaffected while
  `Config.SecurityConfig.packet_signature_policy` is at its `COMPATIBLE` default — under `STRICT` a board
  drops every unsigned packet, which after the padding is every Knit frame rather than most of them;
  `BALANCED` is safe (it drops only unsigned packets that *could* have been signed, and a padded frame could
  not). **The phone gets the whole story** (ADR 2026-09.ggq4, verified on both lab tags): the 64-byte
  `Data.xeddsa_signature` rides up intact, and the board's own verdict `MeshPacket.xeddsa_signed` (field 22,
  computed on receive against the key *its* NodeDB holds, never trusted off the air) beside it, plus
  `NodeInfo.has_xeddsa_signed` (14) and `DeviceMetadata.has_xeddsa` (14, which `LoraAirtime.onFirmware` now
  takes ahead of the version parse). The signing input Knit verifies is 2.8.1's versioned buffer over the whole
  `Data` envelope (`0x01 ‖ from ‖ id ‖ to ‖ portnum ‖ request_id ‖ reply_id ‖ emoji ‖ bitfield ‖ flags ‖ payload`;
  ggq4's 2026-10-29 amendment, #242); 2.8.0's `LE32(from) ‖ LE32(id) ‖ LE32(portnum) ‖ payload` was dropped when
  Meshtastic withdrew that release, under the board's Curve25519 key converted to Ed25519 (`y = (u − 1)/(u + 1)`, sign bit 0); on 2.8 a node
  number **is** the CRC32 of that key. The text cliff is **166 B** (one-byte portnum), the Knit-frame one 165.
  Two rules to remember: a signed packet that fails against the key the *receiving* board holds is dropped
  before the phone sees it, whatever the policy — a stale or planted key silently blackholes that sender —
  and an unverifiable one (no key in the NodeDB) is passed up with the signature and the flag false.
  <!-- doc-lint: allow Config.SecurityConfig.packet_signature_policy — a Meshtastic protobuf field, not the app's Config -->
  <!-- doc-lint: allow NodeInfo.has_xeddsa_signed — a Meshtastic protobuf field, not the app's NodeInfo -->

## Pacing

`LoraPacePolicy` (pure): 3 s min inter-packet gap, a 32-frame queue (ADR 054 — sized to hold a 15-minute
wait), NAK back-off (rate/duty → a 60 s cool-down), hold while `queueFree == 0`, and a hold while the frame's
`AirBucket` budget is spent — a refused frame is skipped rather than blocking the queue behind it, except past a
live frame the window's **total** refused: that one holds everything ranked behind it until the air it needs comes
back (`LoraAirtime.refusal`, ADR 044's 2026-10-04 amendment). Otherwise one-packet room posts take each packet's
worth the window hands back and a two-packet DM never fits. Two buckets since ADR 2026-10.rp3s: `LIVE` (everything
Knit of ours, the re-offer and the re-probe included) may spend the whole allowance, `PUBLIC` (the Meshtastic room's
posts and the DM auto-reply) 15 % of it and the total. A booking a retired bucket left in the persisted ledger is
restored as `LIVE` (`BRIDGE`, `GOSSIP`) or dropped (`BOOTSTRAP`, `AirBucket.restoredFrom`).
When full the queue **sheds by class** (`FrameClass`: DM > ROOM > TICK): the oldest **whole** frame (never a lone
fragment) of the lowest class present goes, the newcomer included — a room post alone at the bottom is `REFUSED`
rather than evicting a DM. `TICK` is a frame **we** originated as a delivery receipt, said so by `FanoutHint.TICK` on
`MeshTransport.longRangeFanout` (the transport cannot read a sealed frame). Dequeue runs the same class order forwards
(highest class first, FIFO within it). Both eviction and refusal count as `loraDroppedQueue`. A DM-form frame keys
every copy the plane might queue on its id (`frameKey`), so the re-offer and the re-probe see a copy still waiting
(`hasQueued`, the frame in flight included) and do not queue a second.

**Freshness gate** (fan-out paths only, room included): a `chat`/`reaction` whose `sentAt` is more than
15 min old (`LoraFramePolicy.FRESH_MS`) is a custody re-serve and is not fanned — without it a newcomer's
whole backfill re-fanned over the air, twelve frames at a time. Profiles (publish-stamped `sentAt`) and
receipts are exempt, as are the re-offer and the re-probe. It reads the
injected `wallClock` (epoch) — the transport's `clock` is `elapsedRealtime` and is not comparable to a
frame's `sentAt`. Counted with the sig-window rejections under `loraSuppressed`. `BleConnectArbiter` lets
the board dial pause the mesh BLE scan for its connect window (scanning starves connects).

**Reconnect (ADR 2026-09.hp88).** A lost board gets three **direct** dials (`autoConnect = false`, 30 s, arbiter
held). After that the dial goes **background** (`autoConnect = true`, no arbiter while waiting): the controller
connects at low duty when the board advertises, with one direct dial an hour as the net for stacks whose
autoConnect never fires. The wait reads as `Disconnected("waiting for the board")`. A session Ready for 5 min
resets the streak. A background refusal is `Failed` and backs off; only a spent window skips the backoff. The
dial closes its GATT client on every non-`Opened` exit, cancellation included. While the link is down the pacer
parks on `link.state`, never on its 1 s idle tick (#67).

## Limiters that outlive the process (ADR 2026-09.7svb)

**A limiter that resets on launch is not a limiter.** These are persisted through `LoraPlaneState` (a JSON blob under
one settings key, `LoraPlaneStateStore`; `mesh/lora/` itself stays Android-free): the airtime ledger, the DM
auto-reply's memory, the DM probe window and the re-offer's per-frame hour (`dmProbes` / `reoffered`, ADR
2026-10.a4pm), and the re-probe's schedule (`reprobes`, ADR 2026-10.rp3s). They used to begin each launch empty, so a
restart handed the plane a **fresh 45-second allowance** and re-ran every first-time behaviour against it. The duty
cycle is law rather than politeness (ADR 067) and the ledger is the only thing enforcing it, so a crash loop, a
force-stop or a lab reinstall cycle spent air nothing accounted for. The beacon floor, the gossip interval, the serve
caps and the profile re-fan gate were persisted too, and left with the traffic they limited (ADR 2026-10.rp3s): a
blob the last build wrote still loads, the store ignores the dropped keys, and every limiter this build keeps comes
back (`LoraPlaneStateStoreTest`).

- **None of them is cleared by `quiesce`** — the restore runs once per instance, so a clear on `stop()` would
  reopen every window for an in-process restart.
- **Stamps are wall-clock, converted at each end** (`WallShift`). `clock` is `elapsedRealtime`, which
  restarts at zero every boot: persisted directly it reads as the far future and ages nothing out. A
  snapshot stamped *ahead* of the wall clock is refused whole — the clock moved backwards, and one fresh
  window is the smaller error.
- **Nothing transmits until it is back** (`awaitRestored`, on the pacer and the re-probe). It releases on failure
  and behind a 5 s timeout: a store that never answers costs a window, not the plane. The pacer also waits for the
  self id (`selfIdKnown`, resolved on its own coroutine at `start`): until it lands, nothing is ours, and only the
  send-time re-ask (`staleReason`) holding the id keeps a frame that is not, or one addressed to us, off the air (#141).
- **A booking carries its recorded cost, not its size** — a board swapped between two processes has a
  different preset, and the air was spent at the old one.
- **`sigSeen` and `lastHeardAt` are deliberately not persisted.** A restored `sigSeen` would carry "we
  transmitted" across a restart, which on a plane with no acks is not evidence anyone heard (the ADR
  2026-09.y8pu reasoning); a restored `lastHeardAt` would claim `reachable` on evidence this process never
  saw (ADR 2026-09.2ajk). The restored ledger bounds what both would otherwise let through.
- Writes are debounced 2 s behind a conflated wake (the pacer's 3 s floor bounds the rate anyway), plus one
  on `stop()` taken **before** `quiesce()`. A kill inside the debounce loses the last packet or two of the ledger.
  The restore runs **once per instance**: an in-process stop/start keeps the live ledger rather than re-reading a
  staler blob.

> **The pacer's monitor is taken outside the ledger's, never the other way round.** `LoraPacePolicy`,
> `LoraAirtime`, `LoraDmProbe` and `LoraReprobe` are all `@Synchronized` on every entry point, because the queue has
> six writers on five coroutines (`take` on the pacer; `enqueue` on whichever coroutine sent; `onQueueStatus`
> / `evictOversize` / `onNak` on the link, queue and outcomes collectors) and the ledger has a seventh — the
> `LoraPlaneState` save, whose `bookings` prunes. `ArrayDeque` is not thread-safe and nulls a slot on
> removal, so a structural change under `admitBest`'s index walk hands it a null through a non-null type:
> field-observed on the lab P7 (2026-09-10) as `NullPointerException: … OutboundFrame.getBucket()`, which
> kills the pacer coroutine and silences the plane while it still reports `state: Ready`. Nothing restarts
> it. Regression: `LoraPacePolicyTest.aQueueDrainedWhileOtherThreadsEnqueueDoesNotTearItself`.

## DMs (ADR 039)

A 1:1 DM rides as its ordinary sealed frame — nothing is re-encoded, the signature verifies unchanged, and
the far side needs only the pinned profile (key + `CAP_RATCHET` + prekey), which since ADR 2026-10.rp3s it must have
from a radio, the spool or an older build's beacon — no profile rides the plane; X3DH
attaches its init to every frame until the first reply, so no round trip is needed. The epoch ratchet
tolerates a lossy hop by design (independent epochs, ≤ 200 skipped keys per epoch). Sizes
(`CoordinationPlaneSizeBudgetTest.sealedDmsFitTheLoraHop`): a 100-char DM compacts to **387 B** steady-state
and **439 B** with the init — 2 packets either way; the 3-packet ceiling (681 B) is ≈ 400 characters steady /
≈ 335 with the init; an attachment *reference* costs ~167 B more and still fits; a `TextLimits.MESSAGE`-length
DM is `loraTooBig` and rides the radios/custody. The ✓✓ is the recipient's sealed `CTL_RECEIPT` — a DM-form
frame originated `relay = true`, so it crosses back on the same rule, and it re-runs on every re-delivery via
the pre-decrypt exists-gate (which is how a tick lost over LoRa heals when the DM is re-offered). Between two
builds that read crypto scheme v3 (ADR 059) every sealed DM-form frame is ~30 B lighter (derived nonce,
compact plaintext), and through ADR 060's `0x05` transcoder the signed, custodied DM ✓✓ crosses in **one
packet** (221 B at 228/231/255), a sealed reaction with a single-code-point emoji in one at 231 (a skin-tone,
flag or ZWJ-sequence reaction is two — 261 B for the longest RGI sequence, 290 B at the `TextLimits.REACTION`
cap — never three), the profile bootstrap in two at 228 instead
of three; AckSync's `relay = false` tick for a room or group post crosses **unsigned as one packet** (157 B
transcoded, ~218 B on `0x03`; at the MTU-255 ESP32 boards the latter needs the measured cap, `TORADIO_OVERHEAD` 27 → 228-B payloads, pinned by `CoordinationPlaneSizeBudgetTest`; verified on the
lab Pixel 9's MTU-255 ESP32 board 2026-08-29 — `lora ready … mtu=255 maxPayload=228`, fragmented 228-B writes accepted).

**A DM waits for its recipient to be heard (ADR 2026-10.a4pm).** A DM-form fan-out to a recipient not in
`reachable` (author-keyed reach: a peer heard through a Meshtastic repeater or an older build's gateway counts, our
own board's airing does not) is held, counted `loraUnheardHeld`, left in custody with its dedup slot free — the check sits after the
covers and the freshness gate and **before** the slot. The plane cannot read a sealed frame, so the originator's
`FanoutHint` decides what is gated: our own DM (`CHAT`, from `sendChat` and the pending-key flush only) is gated and
may probe; our own ✓✓ (`TICK`) is **not** gated — it answers a DM that just reached us, is about one packet, and
held it would be stranded (no message row to re-offer from, and a re-offered old DM does not make its author heard
here); our own sealed ctls (`CONTENT` — reactions, resets, seeds, key replies, and the post-reset re-seal, whose
custody copy is the old seal) are **not** gated either, because they have no message row for the re-offer to find
(or would be re-offered unopenable) and draw no ✓✓. A relayed DM-form frame never reaches the gate: the admit rule
refuses it first (ADR 2026-10.rp3s). Until our own id is known nothing is gated (the send-time re-ask covers a DM to us). The first `CHAT` DM to an unheard recipient in each
`DM_PROBE_WINDOW_MS` (15 min) rides anyway as the **probe** (`loraDmProbes`, `lora probe <label> -> <id>`): if the
recipient is there, the probe makes us heard on their board, their sealed ✓✓ rides back and makes them heard on
ours, and that first hearing re-offers what was held. A DM rather than a profile beacon on purpose: a recipient
that already hears us never takes a beacon as a first hearing and never beacons back.

**The re-probe (ADR 2026-10.rp3s).** A recipient whose board was off when the probe flew, and who then has nothing to
say, was made heard again by the beacons and OFFERs this plane no longer sends — without them every DM held behind a
lost probe would wait for a radio or spool contact. `LoraReprobe` keeps a schedule per recipient an own DM went to
(held, probed, or flown to them while heard), and the linger sweep (`reprobeDue`) re-airs the **newest** own unacked
DM to a due recipient as a probe on `LIVE` (`lora reprobe <id> -> <recipient>`, `loraReprobed`): first one
`DM_PROBE_WINDOW_MS` after the hold, then at a gap doubling to 4 h (`LoraReprobe.CAP_MS`). It shares the probe window
with the fan-out's probe (`dmProbeAt.claim`), carries the covers but not the reach gate on its `RideGate`, and is
deferred without doubling while the recipient is linked or spool-covered, or a copy of that DM is still queued
(`hasQueued`). Only a board holder (`isBoardHolder`) is probed or re-probed at all: a recipient this plane hears
(reach is author-keyed and fed only by fresh frames off their board), or one whose pinned profile names a board
(`PeerEntity.loraNode`, supplied by `MeshManager.loraBoardHolders` as the transport's `boardHolders`). A DM to a
board-less contact can be carried by nothing on the air and rides custody and the spool, but the contact is scheduled
all the same and deferred at no air cost each due round, so a `loraNode` that arrives later is probed on the next one
without a new DM. Until the holder source first reports, every recipient reads as not a holder *for now*: the reach
gate holds without a probe and the re-probe defers, never forgets, so a restored schedule outlives a slow first
emission. A failing source is caught, logged (`lora board holders failed`) and counted (`loraBoardHoldersFailed`), and
the last set it gave stays in force. A recipient who keeps being heard has
no first hearing coming, so a DM lost on its way to them is repaired instead (`repairHeard`, `lora repair <id> -> <id>`):
once the newest own unacked DM to them is older than one gap and was not re-offered in the hour, it is re-aired as a
re-offer (LIVE, `reofferedAt`, `hasQueued`, reach re-asked at send), and the gap still doubles. A first hearing resets the gap to the base, and so does an acked DM (the frame the last re-probe aired
is no longer among the unacked ones); no unacked DM left drops the recipient, and nothing else does. The ✓✓ to the re-probe makes them heard,
and the ordinary first-hearing re-offer carries the rest. NodeInfo and telemetry are never a hearing: `BoardQuiet`
stretches them to hours, and the handshake replays a synthetic NodeDB. The schedule is persisted
(`LoraPlaneSnapshot.reprobes`), so a restart keeps the backoff earned. The window is persisted with the other limiters (`LoraPlaneSnapshot.dmProbes`) and not cleared by
`quiesce`. A queued frame that passed the gate carries `RideGate.reachGated` and is dropped `StaleAtSend.UNHEARD`
if its recipient lingers out before it airs; the probe is never dropped for that. **Any** frame refused at send
— and every frame dropped before its first fragment (a NAK or give-up on it, a missing config or a slot that is not
Knit's, a frame the pacer sheds, refuses or evicts unqueued) — hands back what it took (`abandon`): the fan-out's dedup
slot (`OutboundFrame.sigKey`, once per id per window, which is what `SeenSet.reopen` gives), a probe's window, a
re-offer's hour. What it costs: if B hears A but A cannot hear B, A gets one DM per window through instead of every
one; and **a recipient with LoRa DMs switched off never sends a ✓✓ back**, so (unless its room posts make it heard)
only probes reach it. The room is not gated.

**Re-offer on first hearing.** The plane has no custody sync, so a DM sent while the peer's board was off — or
held by the reach gate above — would be lost to it. On first hearing a peer (once per 45-min linger) the transport
pulls `FarPeerFrameSource.framesFor(peer)` and enqueues each verbatim, class DM, on `LIVE`, through a private path
(`reofferTo`). `MeshManager` answers our own unacked DMs only — chosen by id off `MessageDao.unackedDmsTo`, newest
first, over-fetched and capped at `FAR_REOFFER_OWN_LIMIT` (6) after `ForwardStore.liveFramesByIds`, so a row custody no
longer holds takes no slot; the frames others wrote that it used to add left with the bridge (ADR 2026-10.rp3s). One
frame is re-offered at most once an hour across first hearings (`reofferedAt`, persisted as
`LoraPlaneSnapshot.reoffered`), so a peer heard only now and then does not have the same backlog re-aired every linger
cycle. The re-offer does **not** ask the fan-out's `sigSeen`: the probe that went out a minute ago into an empty sky is
exactly the frame this first hearing exists to repair (the ADR 2026-09.y8pu reasoning, now that the bridge's serve is
gone). The re-offer waits `REOFFER_SETTLE_MS` (2 s) after the first hearing, so the ✓✓ that caused it — often the
probe's own — is applied before custody is read and the acknowledged probe is not re-aired. It asks `LoraPacePolicy.hasQueued` instead — every copy of a DM-form frame is queued under one `supersedes`
key (`frameKey`), and the frame being written counts until `done` or `requeue` — so a copy still waiting is never
doubled. A frame put back after a busy board or a lost link goes through `LoraPacePolicy.requeue`, which supersedes
nothing. Skipped for a peer another plane links (`linkedPeers`) or a spool covers — custody syncs to it for real
there. Bounded: ≤ 6 frames × ≤ 3 packets per sighting on the 32-frame queue. Counted as `loraReoffered`. A six-DM
batch is ~30 s of a 45 s window on `LIVE`, and DM ranks above the room and ticks, so a sighting's backlog can hold
those for most of a window.

**What still doesn't cross:** group chat (group-form frames are refused; sealed group *machinery* — seeds,
key req/ack, escalated ticks — crosses opaquely and is bounded by the group logic), `typing`, attachment
bytes (a DM with an image arrives as text plus a loading placeholder until a radio/spool path exists —
`blobreq` never rides LoRa; `AttachmentDeferPolicy` already ignores LoRa sightings), and DMs beyond the
size ceiling. A board-less recipient behind another board-holder gets nothing over LoRa from a build since ADR
2026-10.rp3s: the board-holder airs only its own user's frames, so what reaches a board-less phone over LoRa is what
its pocket-mate's board *hears* and the pocket floods to it.

**The ✓✓ is coalesced (ADR 054).** A DM that arrived over the board does not seal its receipt at once:
`InboundPipeline.acknowledge` holds it in `mesh/DmAckCoalescer` for ≤ 45 s (anchored on the oldest held id;
re-deliveries inside the hold add nothing), then `MeshManager.flushDmAcks` originates **one** sealed tick
(`ack`/`acks`, ≤ 12 ids — pinned to fit 3 packets at the ESP32 cap), hinted `TICK`. If we send the author a
DM meanwhile, `sendChat` carries up to 4 pending ids inline as `MessageContent.acks` on the plain DM instead
(`Protocol.CAP_INLINE_ACK` on the author's profile; 23 B each reserved out of the composer's LoRa body budget
so a reply can never become `loraTooBig` to save a tick; a v1 fallback gives them back) and no tick goes out.
DMs off the phone radios keep the instant receipt; an author who cannot read a sealed tick keeps the cleartext
form. Airtime: ~2 packets per DM; a receipt is ~0.2 s riding a reply, ~3 s standalone — an exchange costs
~4 s of the 45-s window instead of ~7 s. `heal()` is the flush backstop; a process death inside the hold
loses the pending acks and the peer's next re-offer heals it. Counted `loraTickDeferred`, `receiptsCoalesced`.

**Metadata.** Content stays end-to-end sealed, but a DM's cleartext `senderId`/`recipientId`, timing and
size now travel on the public-PSK rendezvous channel at kilometre range. `SettingsStore.loraDmEnabled`
(default **on**, the "Private messages over LoRa" switch on the LoRa radio screen, `…debug.LORA --ez dms`)
rides into `LoraConfig.dms`; off, the transport refuses DM-form on fan-out and skips the re-offer while the
room keeps riding. Each side gates its own sends. The confidentiality fix remains the deferred private PSK.

## Board provisioning (Knit configures the board)

`LoraMeshTransport.provisionKnitChannel()` (the settings button "Set up this board for Knit", or
`…debug.LORAPROV`) configures the board over the Meshtastic **admin** API so the user never hand-configures
anything. The mechanics, all inside `MeshtasticSession` (serialized with sends): an admin `get_channel` to
the local node (`to = myNodeNum`, portnum ADMIN=6, `want_response`) yields the `session_passkey` (field 101,
300 s TTL) that every following write echoes; then `begin_edit_settings` → the writes → `commit_edit_settings`.
The commit reboots the board to apply the edit, so the session ends (reset backoff) and re-handshakes,
reloading the channel table; the result returns as soon as the writes are accepted. A
`Routing.ADMIN_BAD_SESSION_KEY` NAK triggers one fresh-passkey retry of the whole transaction. **What** gets
written is the next section.

**`KnitChannel`** (`mesh/lora/KnitChannel.kt`): name `"Knit"`, 16-byte AES128 PSK **derived** (pinned +
guarded by `KnitChannelTest`) via `HKDF-SHA256(ikm="nearby", salt=0³², info="knit/lora/channel/psk/v1")`.
The seed is public, so the PSK is public — deliberately: the Nearby room is cleartext, so this channel is a
**rendezvous** (any two Knit boards converge with zero coordination), not a confidentiality boundary. Knit's
per-frame Ed25519 signatures remain the integrity boundary. A confidential per-deployment PSK (shared
out-of-band via a channel QR/URL) is deferred — see `roadmap.md`.

> **Knit shares the public frequency on purpose.** The firmware derives its RF slot from
> `hash(primary channel name) % numChannels` whenever `lora.channel_num` is 0
> (`RadioInterface::getChannelNum`), so writing Knit into a **secondary** slot leaves the board on whatever
> frequency its primary picks — for a stock board, the public LongFast one. That is deliberate: the default
> `rebroadcast_mode = ALL` "rebroadcast[s] any observed message… **from another mesh with the same lora
> params**", so stock Meshtastic nodes repeat Knit's packets up to their hop limit without being able to read
> a byte of them. Free hops through somebody else's infrastructure are worth more than a quiet channel to a
> plane whose whole job is reach. (ADR 038 claimed the *channel* kept Knit off LongFast; it never did, and
> ADR 045 decided it should not.)
>
> **The catch: convergence rests on the primary — and on the preset.** Two boards meet only if their primary
> names hash alike (automatic for stock boards, false for anyone who renamed theirs) *and* they are on the same
> modem preset. The preset decides **both** halves: it is the modulation, and — because `Channels::getName`
> substitutes the preset's own display name for an unnamed primary — it is also the string hashed into the RF
> slot. So in the US a stock `MediumFast` board sits on slot 45 of 104 and a stock `LongFast` one on slot 20:
> different frequency *and* different modulation, doubly deaf. 2.8 made this easy to trip over by defaulting
> freshly flashed US boards to `LongTurbo` (slot 14 of 52, a third grid again).
>
> **Knit does not pick a preset, and must not start.** ADR 045's bargain is borrowing hops from stock nodes,
> and `rebroadcast_mode = ALL` repeats only traffic "from another mesh with the same lora params" — so those
> hops exist only on the preset the neighbours actually run, which is regularly not the region default
> (observed 2026-08-31: a `MediumFast` neighbourhood in a `LongFast`-default region). Pinning a global default
> would trade every borrowed relay for at best 5 dB. `MediumFast` also costs **0.30×** the air of `LongFast`
> (71 signed ticks a window against 21) for 0.68× the range, so following the neighbours is usually the better
> trade on both axes anyway.
>
> `LoraRadioUiState.customPrimary` warns about the renamed primary — that one really is a misconfiguration.
> `LoraRadioUiState.presetMismatch` is a **notice, not a warning**: neutral-coloured, it says what the board is
> on and that the user's *other* boards have to match, and never asks them to change this one. It triggers off
> `LoraRegion.defaultPreset`, which like `bandStartKhz` is stated only where Knit knows it exactly, so `OTHER`
> (which buckets the ham carve-outs and their `TinyFast`/`NarrowSlow` defaults) simply stays quiet. Gating it
> on evidence instead is not available: `boardsHeard` counts only radios that sent a **Knit** frame
> (`noteBoard` sits on the Knit intake path), never stock neighbours, so "heard nobody" is the ordinary state
> of a solo user.

## Setting a board up (ADR 045)

A board is **either set up for Knit or a stock Meshtastic node** — there is no lighter option and no
hand-editable channel index, so every Knit board is configured identically and any two meet without
coordination. `provisionKnitChannel(ProvisionMode.Setup)` — the "Set up this board for Knit" button, or
`…debug.LORAPROV` — does all of it in one `begin_edit`/`commit_edit` transaction after three reads:

1. `get_config(DEVICE)`, `get_config(POSITION)`, `get_module_config(TELEMETRY)` — **before** the transaction.
   `AdminModule::handleSetConfig` assigns the whole sub-config, so every write is a read-modify-write over the
   board's own bytes (`spliceVarintFields`, `ProtoIo.kt`): only the intended fields change and everything this
   codec does not model (`role`, `gps_mode`, `tzdef`, …) is copied through byte-for-byte. A read that fails
   aborts with nothing written.
2. `set_channel { index N, settings { psk, name "Knit", module_settings {} }, role = SECONDARY }` into the
   lowest free secondary slot (1..7), reusing an existing Knit channel wherever it already sits. **Index 0 is
   never touched** — see the note above. The empty `module_settings` **is** `position_precision = 0`; an
   absent one reads as "unset" and the firmware defaults to full precision.
3. `set_config` / `set_module_config` stretching `node_info_broadcast_secs`, `position_broadcast_secs` (smart
   broadcast cleared) and `telemetry.device_update_interval` to `BoardQuiet.QUIET_SECS` (6 h), and setting
   `rebroadcast_mode = LOCAL_ONLY` so the board keeps relaying its own channels — all ADR 044's bridge needs —
   and stops spending its battery repeating the rest of the band. The GPS itself is not touched: silencing
   what the board *broadcasts* is Knit's business, powering down the user's hardware is not.
4. `set_owner` writing the board's **Knit identity** (`BoardOwner`/`BoardName`): the long name
   **`Knit abcd`** / short name **`Knit`** (ADR 049) — the suffix is the low two bytes of its node number,
   the same shape the firmware's own `Meshtastic abcd` default uses, so two boards in one pocket stay
   distinguishable — plus **`is_unmessagable`** (ADR 2026-09.emd7). Read first (`get_owner_request`,
   **before** the transaction, like the configs) and spliced over the board's own bytes,
   `spliceStringFields` for the names then `spliceVarintFields` for the mark. Never composed from scratch:
   `handleSetOwner` merges rather than assigns, and **both** its bools are cleared by an omission —
   `is_licensed` has no presence at all (and takes `override_duty_cycle` with it), while `is_unmessagable`
   has presence yet is still assigned whenever the two presence bits differ. Deliberately **not** the user's
   display name — a `NodeInfo` is cleartext on the public frequency.

   **The mark says what the board already is.** Knit's intake keeps only `PRIVATE_APP`, so a stranger's
   `TEXT_MESSAGE_APP` DM to a Knit board is ACKed by the firmware's routing layer and then dropped unseen —
   a *delivered* tick against a message nobody will read. Clients that honour the flag grey the node out
   instead. It is a `NodeInfo` hint and changes no routing, which is why it is this and **not**
   `device.role = CLIENT_MUTE`: that would stop rebroadcast outright, and ADR 044's bridge needs
   `LOCAL_ONLY`'s own-channel relaying. Gated on firmware **2.6.9** (`BoardName.honoursUnmessagable`), where
   the plumbing landed; older boards drop field 9 and never echo it back, so writing it would leave the
   setup looking permanently unfinished. A version that will not parse counts as **too old** — the opposite
   of `LoraAirtime.signsPackets`' reading of the same string, because here a wrong guess writes to somebody's
   hardware rather than merely over-charging for airtime.

The settings **and the identity** the board had first come back as `ProvisionResult.Provisioned.previous`
and are persisted per board (`SettingsStore.loraBoardSetup`); **Restore** writes them back and disables every
Knit channel — the mark included, since a restored board is a stock node and a stock node is read. With no
name recorded, the restore writes the one the firmware itself would have chosen
(`BoardName.stock`, unmarked) rather than leave a restored board saying Knit. It
leaves **no** Knit channel, so the caller switches the plane off with it — otherwise the next fan-out would go
out over whatever channel remains — and restoring a board that carries none is refused, since there is nothing
to undo and the config writes would push somebody's board to values it never had. Re-running the setup on a
board that already has the channel is a reported no-op **except for the identity write**: a board set up by
an older Knit — missing its name (pre-ADR 049), the unmonitored mark (pre-ADR 2026-09.emd7), or both — gets
one `set_owner` and nothing else, so the recorded settings are never overwritten with the quieted ones. The
screen offers exactly that as a single button (`lora_rename`, no confirmation — one reversible write)
whenever `LoraRadioUiState.needsRename`: the board carries the Knit channel and `BoardInfo.owner` — its own
`NodeInfo.user`, decoded off the same handshake ADR 041's battery comes from — differs from
`BoardName.forNode`'s. A board whose firmware never sends its own `NodeInfo` reports nothing and is left
alone. Two traps here:

- **The button's label depends on which half is missing.** The screen compares `meshName` against
  `knitName`: equal names mean the board is already called what Knit calls it and the *mark* is what is
  missing, so it says "Mark this board unmonitored" instead of lying about a rename.
- **`identityOnly` fills the recorded name in, it does not overwrite it** (`previous.owner ?: was.owner`). A
  board missing only the mark is *already* `Knit abcd`, and recording that would destroy the only copy of
  the stock name a Restore has to put back. Unconditional overwrite was safe only while this path was
  reachable exclusively on a stock-named board.

### The dedicated-frequency setup (ADR 067)

`ProvisionMode.SetupDedicated` is everything above **plus** one spliced varint: `lora.channel_num`, pinning
the radio to the slot `LoraSlot` derives instead of leaving the firmware to hash it out of the primary's name.
It exists because ADR 045's bargain has a premise — a Meshtastic neighbourhood to borrow relaying from — and
an isolated farm or a mountain house does not have one, so the shared slot buys nothing while still costing
`LoraAirtime`'s politeness ceiling. Offered in every build since ADR 067's 2026-10-22 amendment (debug-only
before), and never the default.

- **The slot is derived, not asked for**: `hash(KnitChannel.NAME) % (region band / preset bandwidth)`, so two
  boards in the same region on the same preset converge with no coordination, exactly as the shared slot does.
- **An unknown band is refused, not guessed** (`ProvisionResult.NoDedicatedSlot`, nothing written): picking a
  slot means picking a transmit frequency, so `LoraRegion` carries a band only for **US** and **ANZ** — the two
  wide 100 %-duty regions this is worth doing in, un-collapsed from `OTHER`. `MIN_CHANNELS = 2` also refuses a
  band whose only slot *is* the stock one.
- **`BoardConfig.LORA` is outside `BoardConfig.QUIET` on purpose**: the ordinary setup still never reads or
  writes the radio, so ADR 045's promise survives the codec learning to address it. The board's own
  `channel_num` is recorded like every other prior value, and a restore of a board that was never pinned
  touches no radio config at all.
- **What changes on the air** is in `LoraAirtime` below, not here.
- **A pinned board has no Meshtastic room.** No public radio can hear it, so `LoraStatusRepository` folds
  `AirtimeSnapshot.dedicated` into `LoraFacts.room` (the row goes, history and all; `canPost` with it) and
  the transport closes both doors the way the room switch does (`MESH_POST_DEDICATED` inbound,
  `PublicPostRefusal.DEDICATED` outbound). Read off the governor's **sticky** verdict, never the live
  `LinkState.Ready.radio`: the setup ends in a board reboot, and the row must not come back — nor the setup
  button (`LoraRadioUiState.dedicated`) or the room switch wake up — for the seconds the link is down. The
  screen offers the button only while the board is *not* yet pinned; afterwards the line says so and
  Restore is the way back (device-observed 2026-09-17: the button had kept showing over a pinned board).

Drive it headlessly with `…debug.LORAPROV --es mode dedicated`; `--es mode restore` undoes either setup.

A board whose bound slot is **not** the Knit channel never transmits (`LoraMeshTransport.boundSlotIsKnit`,
counted as `loraSuppressed`): after a restore, or before a setup, sending would put Knit's cleartext frames
onto whatever channel the board is on — most likely the public one. A board that reports no channel table at
all is given the benefit of the doubt, since going mute on unreadable firmware is the worse failure.

**The cost, which the confirmation states out loud:** the board is renamed for Knit, is marked unmonitored
so other people's apps stop offering it as a message target, stops broadcasting its
position and node info, and stops relaying other radios' traffic. Its own main channel is left alone and it stays on the public
frequency, so nothing else about its place in the Meshtastic network changes.

Admin wire (pinned by `MeshtasticProtoTest`): `AdminMessage{ get_channel_request=1, get_channel_response=2,
get_owner_request=3, get_owner_response=4, get_config_request=5, get_config_response=6,
get_module_config_request=7, get_module_config_response=8, set_owner=32, set_channel=33, set_config=34,
set_module_config=35, begin_edit_settings=64, commit_edit_settings=65, session_passkey=101 }`;
`User{ long_name=2, short_name=3, is_unmessagable=9 }` (field 9 is `optional`, so absent and an explicit
false are two encodings — both read as messagable); `Channel{ index=1, settings=2, role=3 }` (Role SECONDARY=2, DISABLED=0);
`ChannelSettings{ psk=2, name=3, module_settings=7 }` with `ModuleSettings{ position_precision=1 }`;
`Config{ device=1, position=2 }` / `ModuleConfig{ telemetry=6 }` (the request enums number differently:
`ConfigType{ DEVICE=0, POSITION=1 }`, `ModuleConfigType{ TELEMETRY=5 }`);
`DeviceConfig{ rebroadcast_mode=6, node_info_broadcast_secs=7 }` (RebroadcastMode LOCAL_ONLY=2);
`Data.want_response=3`.

## UI surfaces (ADR 040)

- **Per message.** A frame off the board is stored `DeliveryPlane.LoRa` (`messages.receivedVia = 5`; the
  composite stamps `InboundFrame.kind`, `InboundPipeline.planeOf` maps it), and the receipt that flips our
  ✓✓ over LoRa records the same. `ui/chat/DeliveryStatus.kt` paints `Icons.Filled.Sensors` beside the ✓✓
  (`chat_tick_lora`) or on an arrival (`chat_arrived_lora`) with "Delivered over LoRa" / "Arrived over LoRa";
  the message-details screen swaps the icon the same way. Inbound rows are first-write-wins
  (`MessageDao.insertIfAbsent`), so a room post keeps the plane it first arrived on across re-serves.
- **Header.** `LoraPlane { Off, Down, Live }` (`mesh/lora/LoraPlane.kt`) from `LoraStatusRepository.facts`
  (pushed) → `ConnectionStatusRow(lora = …)`: `Icons.Outlined.Sensors` (Live, tertiary) / `SensorsOff` (Down)
  after the cloud, spoken as "LoRa radio connected / not connected"; 45 s grace on the Down edge.
- **LoRa radio screen.** `BondedBoardDirectory` marks each bonded device board-like (LE + `Meshtastic_xxxx` /
  `<short>_xxxx` / the service UUID in cache); `BoardFilter.visible` lists those plus the bound board, with
  "Show all paired devices" (`lora_show_all_boards`) for the rest; the list re-reads on resume
  (`refreshBoards`). A connected board shows the one setup section (`lora_setup` / `lora_restore`, ADR 045),
  firmware, **radios in range** (`lora_boards_heard`,
  distinct Meshtastic `packet.from` on our channel, control packets and part-fragments included) and — only
  when the two differ — **people reachable** (`lora_peers_heard`, distinct frame *authors*). The two diverge as
  soon as a gateway relays or backfills somebody else's frame, so "1 radio, 3 people" is normal; reporting only
  the author count read as phantom hardware in the field (2026-08-25: "3 peers heard" with two radios in
  existence). `LoraStatus.heard` stays the author count and remains what `reachable`/routing use. The
  Profile row reads "On · <board> · connected / not connected".
- **Chat.** `LoraNotice` (`chat_lora_notice`, `ui/chat/LoraReach.kt`) under the relay notice for a DM whose
  peer only the board has heard (`isLoraOnly(peerTransports[peer])`, plane live, not relay-covered), with a
  DMs-off variant and (ADR 054) a **saturated** variant — `LoraFacts.airtimeSpent`, ≥ 90 % of the window's
  live budget while live — that says messages are delayed; the composer's "long message" hint (`chat_lora_size_hint`) when the draft exceeds
  `LoraSizeHint`'s budget for its `LoraCarry` form (room 400 B, DM 320 B, −260 B replying, −170 B with a
  photo; pinned in `CoordinationPlaneSizeBudgetTest`).
  The **room** has its own rule, `loraRoomReachFor` → `LoraReach.RoomSaturated` (ADR 2026-09.ursc): the same spent
  window, but the audience test is existential — `peerTransports.values.any(::isLoraOnly)`, *is there anyone
  out there a full queue would delay* — because the room is addressed to nobody. It is the room's **only**
  LoRa state; there is deliberately no `LoraOnly` counterpart (the room's audience is always a mix, so a
  standing "some people are far" strip is permanent chrome saying nothing actionable), no `RelayReach` gate
  (the room is never scope-eligible, `SPOOL_PROTOCOL` §4.4, so no better carrier can exist), no `facts.dms`
  gate (the room rides whatever the DM switch says) and no dismissal (it clears itself as the rolling window
  ages air back, so "never again" would hide it exactly when it matters). Its copy names nobody and says what
  still works — everyone in phone-radio range gets the post at once.
  A **group** thread has its own rule too, `loraGroupReachFor` → `LoraReach.GroupUnsupported`
  (ADR 2026-09.6ww7), and it is the odd one out: every other notice here is congestion, this one is
  **capability** — `LoraFramePolicy` refuses group-form chat, so a member only the board can hear does not
  get these messages at all until they are back in phone-radio range or a relay carries the group scope.
  Gated on this group's **roster** (`members.any { it != me && it in loraOnlyIds }`, never the room's
  existential test — a LoRa-only stranger is not in the group), silenced by `RelayReach.Covered` (a group
  scope *is* scope-eligible where the room is not), and with no airtime or `dms` gate, both of which would
  be category errors. `ChatViewModel.LoraAudience` is the sealed `Peer`/`Room`/`Group` projection that feeds
  all three, chosen once from `Conversations.kindFor`.
  **`LoraStatus` is republished on every accepted send** (`LoraMeshTransport.sendMessage`), not only on the
  60-s linger sweep: a send is the only thing that spends the ledger, so without it the notice — and the
  radio screen's `lora_airtime` percentage — trailed the fact by up to a minute. The pacer's 3-s floor bounds
  the rate and `LoraStatusRepository` reduces the snapshot to a threshold, so no UI sees the churn.
- **Battery (ADR 041).** The board's own `DeviceMetrics` — its `FromRadio.node_info` (the entry whose `num`
  is `my_info`'s) in the handshake, then the TELEMETRY_APP packet the firmware sends the phone about once a
  minute — land in `MeshtasticLink.battery` (`BoardBattery`: `percent` / `voltage` / `powered`, folded by
  `BoardBattery.of`; a level above 100 is "plugged in", 0 with no voltage is no reading) → `LoraStatus.battery`
  → the status row's "Battery 78% · 3.92 V" / "Plugged in · 4.10 V" (`lora_battery`, error-coloured at ≤ 20 %)
  and the Profile row's "· battery 78%" while live (`LoraFacts.battery`, never a reach input). Never polled;
  cleared with the link.
- **Seam.** UI code reaches the transport only through `LoraPlaneStatus` (`status`, `provisionKnitChannel`),
  bound to `LoraMeshTransport` under `BuildConfig.LORA_PLANE` and to `LoraPlaneStatus.Dark` otherwise.

## The Meshtastic room (slot 0 mirror, ADR 2026-09.26q3)

The paired board's **primary (slot 0) channel**, mirrored into `Conversations.MESHTASTIC` on this phone and
this phone only. It is a simple interface to the board's own broadcast chat — whatever preset, name or key the
user gave slot 0 — and **nothing in it ever crosses Knit's mesh**: no frame, no custody, no fan-out, no
gateway election on either direction. A phone with no board never sees these posts; two phones with boards
each hear the channel for themselves.

- **The switch** (ADR 2026-09.x52a). `SettingsStore.loraRoomEnabled`, **default on**, on the LoRa settings
  screen beside the DM and bridge switches, and carried to the transport as `LoraConfig.room`. Off, the
  slot-0 branch of `onLoraPacket` **returns before `onPrimaryPacket`** — so `judge`, the name lookup, the
  XEdDSA verify, the contact resolution, the moderator, the row and its notification are all never reached,
  counted as `meshPostRefusedByReason.ROOM_OFF` while `meshPostHeard` stays at zero — `postToPublicChannel`
  refuses `ROOM_OFF`, and `ChatListViewModel` drops the room's row **including its history** (the rows stay
  in the database and return with the switch). `LoraFacts.room` is the user's answer *alone*, never folded
  with the plane the way `dms` is, because the row is drawn from history as well as from a live board;
  `canPost` folds both. Knit's own frames on the bound slot are untouched. Toggling off also calls
  `Notifier.clearConversation` so nothing already on the shade outlives the row. `…debug.LORA --ez room false`.
- **Inbound.** `LoraMeshTransport.onLoraPacket` routes a channel-0 `TEXT_MESSAGE_APP` packet — by portnum,
  never by the bound index, which defaults to 0 on a board that never ran the setup — to `onPrimaryPacket` →
  `PublicChannelPolicy.judge` (broadcast, text, a packet id, a body; `OWN_BOARD` refuses our own board's echo
  and `KNIT_ON_PRIMARY` the lab shape where slot 0 *is* the Knit channel, decided off the channel table) →
  `MeshPostSink.onPublicPostHeard` → `InboundPipeline.deliverMeshPost`, which writes the row through the
  ordinary `deliverChat` (room moderation, one notification, `ack = false`). The row's `senderId` is **this
  phone** by convention and `originNode != null` is what says the words are somebody else's; its id is
  `FrameId.forMeshPost(node, packetId)` so the board replaying its queue on reconnect is a no-op. Counters:
  `meshPostHeard` / `Ingested` / `ViaMqtt` / `Matched` / `RefusedByReason`.
- **Outbound.** `MeshManager.sendPublicPost` → room moderation → `PublicChannelSink.postToPublicChannel`
  (`NOT_READY`, `KNIT_ON_PRIMARY`, the 30 s `PUBLIC_POST_FLOOR_MS` claimed at the decision, `TOO_LARGE`,
  `AirBucket.PUBLIC` at 15 % of the window) → `Destination.Public` on the shared pacer → channel 0,
  `TEXT_MESSAGE_APP`, `HOP_LIMIT`, the line composed by `PublicPostPolicy.onAirText` — the user's words
  alone, **with no author name in front of them** (ADR 2026-09.9469 withdrew ADR 049's one exception: the
  board is the identity now that each phone posts through its own). Still behind
  `SettingsStore.meshtasticPostConsented`, whose sheet says what does travel — the board's own `Knit abcd`,
  which anyone holding the user's contact card reads back as them. The own row is stored **only** once the
  board queued it (`DeliveryPlane.LoRa`, never a ✓✓); a `PublicPostOutcome.Refused` reaches the composer as a
  toast with the draft kept, so a post never silently goes nowhere. `…debug.SEND --es conv m-public` drives it.
- **Contacts.** The profile carries the bound board's node number (`ProfileContent.loraNode`, persisted as
  `SettingsStore.loraBoard` when the board reports `Ready`, cleared on unbind, republished on change);
  `PeerEntity.loraNode` stores a peer's claim; `deliverMeshPost` resolves `packet.from` through
  `PeerRepository.findByLoraNode` (newest `updatedAt` wins — a board that changed hands is claimed twice until
  the old holder's next profile drops it) **once, at ingest**, and freezes it as `messages.originPeerId`, so
  history is never re-attributed. A resolved contact wears their name and avatar with the **unverified**
  styling (muted name, untappable avatar, the room strip) until a signature verifies the match — then a Knit
  author's colour, a shield and a direct avatar tap; a blocked contact's board is dropped
  (`BLOCKED_CONTACT`). A row's body is shown word for word, since ADR 2026-09.9469 left nothing on the line
  but the words — a heard `"Sam: hi"` is a stranger's content, not a prefix to strip. The match rests on a
  self-asserted node number — **unless the signature checks out** (ADR 2026-09.ggq4). The profile also
  carries the board's Curve25519 key (`ProfileContent.loraKey` ← `SettingsStore.loraBoard.key`, written with
  the number in one edit, read with it from one snapshot, and only while the board signs), `peers.loraKey` stores it, and `deliverMeshPost`
  verifies a signed post on the phone (`mesh/crypto/XeddsaVerify`, in 2.8.1's layout over `MeshPost.signedFields`
  — the packet's `to` and its `Data` envelope as decoded — then, only when the board may have merged the bitfield's
  bit into `want_response`, with it read as signed `false`; always over the payload as heard — `MeshPost.payload`,
  never the trimmed body) and freezes the verdict on the row as
  `messages.originSigned`: `UNSIGNED` (nothing to say — pre-2.8 radios never sign, a post past the cliff
  cannot), `SIGNED_BY_BOARD` (our board's `xeddsa_signed`: the radio that has been using the number), `SIGNED_BY_CONTACT`
  (verified under the contact's advertised key — the one verified state) and `SIGNATURE_MISMATCH` (some other
  radio on the contact's number: **not attributed**, drawn as a stranger that says so). Counters:
  `meshPostVerified` / `BoardVerified` / `SignatureMismatch`. A contact still on a (withdrawn) 2.8.0 board reads as a
  mismatch. A 2.8.0 board and a 2.8.1 board that hold each other's keys drop each other's signed posts before either
  phone sees them.
- **Signable cap.** On a board that signs (`LoraFacts.signs`) the composer caps a post at
  `PublicPostPolicy.MAX_SIGNED_TEXT_BYTES` (166, `MeshtasticProto.maxSignedPayload(PORT_TEXT_MESSAGE)`) so
  every post leaves signed, else the 200-byte client convention; `postToPublicChannel` trims to the same
  `onAirBudget(signing)`. Knit-frame padding (ADR 2026-09.mhs5) is untouched.
- **Verified on hardware (2026-09-05, ADR 2026-09.ggq4):** Pixel 7 on `!64761b18` and Pixel 9 on
  `!e681a7c3` (both 2.8.0.47db0e3), a post each way — the far phone's `LoraMeshTransport` log reads
  `lora public post from !… signed=true boardVerified=true`, `…debug.LORA` counts `meshPostMatched` /
  `meshPostVerified` and no mismatch, and the room draws the shield; a 166-byte post (`lora tx public 166B`)
  arrived signed and verified, so the text cliff holds. The oracle is the phone itself: `…debug.SEND --es
  conv m-public` to post, the transport log and the counters to read, no serial needed (and `!e681a7c3` is
  the Pixel 9's own board — a serial session on it wedges that phone's BLE link). Not seen on hardware: a
  mismatch or a board-only verdict, for want of a third signer on this mesh. The raw `FromRadio` capture
  (`.private/scripts/meshtastic-fromradio-capture.py`) is the serial-side oracle when a non-phone board is
  free.
- **UI.** `LoraFacts.primaryChannel` / `canPost` (only while Live) title the room and the chat-list row
  (`meshRoomChannel`: live board → newest post's channel → "Meshtastic"), the row exists while a board is
  bound or history remains, and `PublicPostGate` swaps the composer for a footer with no radio bound (or a
  Knit-at-0 board) and only changes the hint while the link is down, so the keyboard survives a BLE flap.
  Notifications key on the resolved contact (or the `!hex` id, never `me`), stay `IMPORTANCE_LOW`, and carry
  no inline reply. The composer hint and the pinned strip both say the room is outside Knit's encryption as
  well as unverified, since it is drawn like every other thread and a reader who never opens the dialog would
  carry Knit's padlock into a channel that has none. How strongly is read off slot 0's own key
  (`PublicChannelPolicy.primaryKeyIsPublic` → `LoraFacts.primaryKeyIsPublic` → `ChatUiState`): a psk of one
  byte or none is Meshtastic's published default family, so that channel is **unencrypted**; a 16- or 32-byte
  psk is the user's own key, shared with every radio holding it and still **not end-to-end encrypted**.
  Unknown — no table, or the link down — reads as public, because the two mistakes do not cost the same.
  `MeshRoomNotice` is the room's **only** pinned strip: `reachFor` answers
  `RelayReach.Silent` for `Conversations.MESHTASTIC` ahead of every other rule, so no relay notice ever
  appears here. The exclusion is structural like the Nearby room's, but it earns no copy at all — these posts
  never enter Knit's mesh, so no relay could carry them under any configuration, and "not covered by relays
  yet" promised a coverage that is never coming.

### A DM to the board is answered once (ADR 2026-09.4n5p)

A `TEXT_MESSAGE_APP` packet **addressed to the board** (`to == myNodeNum`) is a Meshtastic user's DM, and
nothing on this phone reads it — ADR 2026-09.emd7's `is_unmessagable` mark says so to the clients that
honour it, but the DM still arrives and the firmware ACKs it, so the sender saw a delivered tick against
words nobody read. `onLoraPacket` now routes it, **ahead of the room's branch and on the portnum + address
alone** (a 2.5+ board reports a PKI DM on index 0 whatever slot the sender used; current firmware refuses a
channel-keyed DM at decode), to `onDirectMessage` → `DmAutoReplyPolicy.judge` → one fixed line
(`DmAutoReplyPolicy.TEXT`, 149 B ASCII: unmonitored, nobody reads it, part of a Knit mesh, getknit.app)
queued as `Destination.Reply` — the room's own write with the sender's number in `OutboundFrame.to`, so
`Router::perhapsEncode` PKI-encrypts it to their key. `MeshtasticLink.send(to =)` is the only unicast the
plane ever sends.

- **Gates, counted by name** (`autoReplyRefusedByReason`): the room's `DEDICATED` (ADR 067) and
  `KNIT_ON_PRIMARY`, plus `NOT_SET_UP` — the bound slot must carry the Knit channel, and an **empty**
  channel table reads as not set up (the opposite of `boundSlotIsKnit`'s reading for Knit's own frames),
  because the setup's confirmation sheet is where the user consented to the board saying it is
  unmonitored. No settings switch, and the room switch does not gate it.
- **Politeness**: `PER_SENDER_MS` = 24 h per node (`SeenSet` of 256, keyed `!hex`), `FLOOR_MS` = 30 s for
  anybody on its own timestamp (never shared with `lastPublicPostAt`), the `PUBLIC` air share asked at the
  decision (`NO_AIR`, never queued), priced as a signed post (over-charges a PKI unicast by ~54 B — the
  conservative error). The floor is asked **before** the sender is stamped, so a `TOO_SOON` sender's next
  message still earns the reply; a `REPLIED_RECENTLY` one is silence for the day, which is what breaks a
  loop with a neighbour's bot. The per-sender memory persists in `LoraPlaneSnapshot.autoReplied` — the
  board replays its queue on reconnect, and a restart is a reconnect.
- **Not on hardware yet.** Needs a stock third node DMing a Knit board; the oracle is `…debug.LORA`'s
  `autoReplyHeard` / `autoReplySent` and the reply on the stock node's screen, PKI (the padlock) and once.
  The rig trap: `FakeMeshtasticLink` is node `1u`, so a test DM `from = 0x1u` is `OWN_BOARD`.

## Board setup (once, Meshtastic CLI or app)

Flash `firmware-heltec-v4-<ver>`; `--set lora.region <US|EU_868|…>`; `--set network.wifi_enabled false`;
`--set bluetooth.enabled true --set bluetooth.mode RANDOM_PIN`; same `lora.modem_preset` (LongFast) on
both. **Nothing else needs hand-setup** — pair the board in Knit, then tap "Set up this board for Knit" (or
`…debug.LORAPROV`) on each phone: the channel and the housekeeping both follow from that one action, and
there is no hand-picked channel index any more (the debug bridge keeps `--ei channel <idx>` for lab work).
Leave each board's **primary channel at its default** — renaming it moves the radio to another frequency,
where no other Knit board is listening. Set the Meshtastic app's device to **None** /
`adb shell am force-stop com.geeksville.mesh`.

## On-device verification (physical devices only, with an explicit go-ahead — `rules/devices.md`)

- Pair: Profile → LoRa radio → pick the bonded board → status `Ready`. `adb logcat -s MeshtasticGatt
  MeshtasticLink LoraMeshTransport`: `lora dial … bonded=true` → `mtu 517` → `handshake nonce=…` →
  `my_info !… pio=heltec-v4` → `config complete` → `ready`.
- Battery: the status row shows the reading with `ready` (the handshake's `node_info`) and refreshes within a
  minute; unplug USB and "Plugged in" becomes a percentage on the next telemetry, replug and it flips back.
- Signal: the row reads the freshest `RxQuality` in `boardsHeardAt` — **only radios that sent a Knit frame on
  the bound channel**, keyed per radio, aged out with the radio at the 45-minute linger and dropped on `stop()`.
  It must never be taken in `MeshtasticSession`: that runs ahead of the portnum/channel filter, and the board
  hands the phone the whole air. On a stock board (primary left at the public default, ADR 045) that is mostly
  strangers three to seven hops out at the noise floor, plus a synthetic POSITION/TELEMETRY per NodeDB entry
  replayed at **every** handshake carrying a stale per-node SNR and no RSSI at all. Reading the last of those
  decayed a real +6 dB / -7 dBm board-to-board link to -17 dB / -105 dBm within minutes and latched it there,
  which looked exactly like a radio going deaf and cleared on a board reboot. Field oracle: `…debug.LORATX`
  from the other phone snaps the row back within a second, while `meshtastic --listen` on the board shows the
  two populations side by side.
- Set the boards up (ADR 045): `…debug.LORAPROV` (or the screen's "Set up this board for Knit") on **both**
  phones → log `lora provision set up chN 'Knit'`, the board reboots, and the link returns. Then, over USB
  with the Meshtastic app disconnected: (1) `meshtastic --info` on both boards reports the **same, unchanged**
  frequency and an untouched primary channel — the setup must never move the radio; (2) a Nearby post crosses
  and `loraNak == 0`; (3) the **battery row still refreshes within a minute**, which is what proves stretching
  the *mesh* telemetry interval did not silence the phone-only telemetry ADR 041 reads (if it did, that
  interval is the wrong lever); (4) `meshtastic --get device.role`, `--get lora.region`,
  `--get position.gps_mode` are unchanged from before while `--get device.rebroadcast_mode` now reads
  `LOCAL_ONLY` — the read-modify-write proof; (5) a post crosses further than either board's own range when a
  stock node sits between them — the free-repeater effect this design is built on; (6) `…debug.LORAPROV --es
  mode restore` leaves no Knit channel, every setting back at its pre-setup value, and the LoRa switch off.
- Provision the channel: tap "Set up this board for Knit" (or `…debug.LORAPROV`) on **both** phones → log
  `lora provision wrote chN 'Knit'` (or `reuse`) → the board reboots and the link reconnects → the channel
  index in settings now points at the Knit slot. Both boards must be provisioned before frames cross.
- `…debug.LORA` (debug bridge): `--es address <MAC>` + `--es name <n>` binds a board, `--ei channel <idx>`,
  `--ez on <true|false>`, `--ez dms <true|false>`, `--ez room <true|false>`; no extras dumps
  `state/boardNodeNum/snr/rssi/queueFree/queued/heard/boardsHeard/internetCovered/radio/airtime/counters`
  (`airtime` also carries `dedicated`, true when the board is on its own RF slot and so off the politeness
  ceiling — ADR 067; `liveMs`/`publicMs` against their budgets — `loraSent − loraDmSent` is the room-post count,
  since nothing else of ours rides; `loraRoomTooLong` the own posts kept off for needing two packets, `loraReprobed`
  the re-probes, `loraRetiredCtl` an older build's OFFERs heard and dropped, `loraBoardHoldersFailed` the board-holder
  source failing — non-zero, the re-probe may have stopped probing silent holders). `loraAirtimeHeld` /
  `loraAirtimeHeldByBucket` beside `queued` is what tells a starved plane from a quiet one: a spent bucket
  *holds* frames rather than dropping them, so no counter moves until the queue overflows and the drops all
  arrive at once. It is the
  two-board oracle. `…debug.LORATX --es text <s>` sends a raw payload straight to the board (board-side
  sanity via `meshtastic --noproto`). `…debug.LORAPROV` sets the board up headlessly; `--es mode dedicated`
  is ADR 067's dedicated-frequency setup and `--es mode restore` undoes either.
- Broadcast: `…debug.SEND --es conv nearby --es text …` (one packet's worth) on A → appears on B within ~5–10 s;
  no ✓✓ comes back over LoRa and no reaction crosses (ADR 2026-10.rp3s) — the tick waits for a link or the spool.
  A post past the one-packet budget counts `loraRoomTooLong` and stays on the radios. Move B out of BLE/NAN range
  and repeat. Counters:
  `loraSent/loraReceived/loraReassembled` climb, `loraNak == 0`, `loraDroppedQueue == 0` at chat pace,
  `loraTooBig` only for long posts. Diagnostics lists a LoRa-reachable node under **Reachable long-range**
  tagged `LoRa`, never under *Directly connected* (ADR 2026-09.2ajk).
- DM (ADR 039): `…debug.SEND --es conv <peerId> --es text …` on A → appears on B within ~10 s; A's tick
  flips ✓✓ (the sealed receipt crossed back); `loraDmSent`/`loraDmReceived` climb on both, `loraTooBig == 0`.
  Reply from B (turnaround epoch) and a DM reaction cross; a 500-char DM counts `loraTooBig` and lands later
  over BLE. The strand trial (ADR 2026-10.rp3s): power B's board off, send two DMs from A (the first goes as the
  probe, the second is held), power it on with B silent → within one window A logs `lora reprobe <id> -> <B>`
  (`loraReprobed 1`), B's ✓✓ makes B heard, A logs `reoffer` for the other, both land and A shows ✓✓ on both.
  The reach gate (ADR 2026-10.a4pm): two phones with boards, no short-range link, quiet past the 45-min linger →
  three DMs A→B read `loraDmProbes 1`, `loraUnheardHeld 2` on A, then `loraReoffered ≥ 2` and three ✓✓ once B's
  tick lands; B's board off, two more DMs → one more `lora probe` and nothing else of them on air for 15 min.
  `…debug.LORA --ez dms false` on A → a new DM stays LoRa-silent
  (`loraDmSent` flat) while a room post crosses. Rejoin a BLE clique after an hour of history:
  `loraSuppressed` climbs, `loraDroppedQueue` stays 0.

- Airtime (ADR 054), the three-phone trial: A + C linked over BLE, B far over LoRa, boards on A and B. (1) A ↔ C
  text 20 messages over BLE → on A `loraSkippedLinked` climbs by ~40 (DMs + ✓✓s), `loraSent` and
  `airtime.liveMs` stay flat, ✓✓ instant, B's board hears nothing. (2) A ↔ B text over LoRa: each ✓✓ lands on A
  within ~45 s or with B's reply; `loraTickDeferred`/`receiptsCoalesced` climb; a burst of three from A yields
  one tick from B. (3) A burst past the window: `airtime.liveMs` reaches `liveBudgetMs`, the chat notice
  appears on A for B **on the spending send, not a minute later** (the publish-on-send above), the **Nearby
  room** on A carries its own `RoomSaturated` notice for as long as B is LoRa-only, `loraDroppedQueue == 0`,
  the queue drains within 15 min, ticks yield first. Link B over BLE and the room notice goes quiet while
  `airtime.liveMs` stays put — the room speaks about a delayed audience, not about the ledger alone.
  (4) `loraNak == 0` throughout, EU boards included.
- Endpoint (ADR 2026-10.rp3s), the pocket trial: A's board alone; B's board plus a board-less C linked to B over BLE,
  out of BLE/NAN range of A. (1) A posts one packet's worth in the Nearby room → B and C both get it (B's board heard
  it, the pocket flooded it); A's `loraSent` +1. (2) C posts → B gets it over the link, B's `loraSent` and `lora tx`
  lines do not move, A never gets it. (3) Idle 30 min with every phone up: `meshtastic --listen` on A's board over
  USB shows no `PRIVATE_APP` packet from B's board, and `…debug.LORA` on B reads `loraSent` and `airtime.totalMs` flat
  — not the board's own `airUtilTx`, which counts Meshtastic's NodeInfo and telemetry too.

## First-session unknowns to confirm (assumptions, not blockers)

Whether an empty FromRadio read returns immediately or blocks ~20 s (could cut the 30 s read timeout);
`phone_timeout_secs` default (180 s heartbeat is safe either way); whether `queueStatus` is pushed as the
TX queue drains; that `mesh_packet_id` echoes our client id; that PRIVATE_APP packets reach the phone and
our own broadcast is not echoed; the 600 ms bonded post-connect settle (drop if unneeded).
