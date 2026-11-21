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
is `fastSend`. Only `fastFanout` rides it as a mesh frame, gated by `LoraFramePolicy`; our DMs ride as compact
unicasts through `LoraDirectSink.offer` (ADR 2026-10.x9fa, "DMs" below), which deleted ADR 039's long-range fan-out
seam (`MeshTransport.longRangeFanout`):
<!-- doc-lint: allow longRangeFanout — the seam ADR 2026-10.x9fa deleted, named as history -->

- **Whose (ADR 2026-10.rp3s).** The board is an endpoint: a frame rides only when `LoraFramePolicy.airs` says this
  phone's user wrote it. The composite re-calls the fan-out on every relay — plane-agnostic on purpose — and the admit
  rule refuses all of it; `InboundPipeline.onDeliver` no longer re-fans a DM-form frame anywhere long-range. Until our own
  id is read nothing is ours: a frame fanned then is judged on its kind alone and carries its author
  (`RideGate.authorId`), and `sendFrame` refuses one that is not ours as `StaleAtSend.NOT_OWN` (the pacer waits for the
  id). The deferred "Forward over LoRa" of a pocket-mate's room post is a second clause of `airs`, nowhere else.
- **What** (`fastFanout`, the composite's coordination-plane blast, into one internal `fanout`): a broadcast `chat`
  (recipientId == null && group == null) **that fits one packet** at the current `maxPayload` — a longer one is counted
  `loraRoomTooLong` and rides the radios and the spool only — and nothing else. No DM-form mesh frame airs since ADR
  2026-10.x9fa: a DM, its ✓✓ and a DM reaction leave compact, and every other DM-form frame (a reset, a seed, a key reply, an intro) travels custody, the spool and
  the links only. A room `reaction` and a `profile` never ride (no key bootstrap crosses the plane, so a stranger heard
  only over LoRa stays in `PendingInbound` and is never shown).
  **One airing per post while it is fresh (#239, ADR 2026-10.rp3s's 2026-10-29 amendment).** The fan-out's dedup
  (`sigSeen`, a `LoraFanoutSeen` keyed on the signature prefix) holds each post until its own freshness deadline
  (`sentAt + FRESH_MS`, the `RideGate.freshUntil` the send-time re-ask reads) on the wall clock, not for a ten-minute
  TTL: the router's seen window lapses at ten minutes, so an own post re-served in minutes 10–15 (custody, a spool
  pull, re-fanned by `InboundPipeline.onDeliver`) used to air twice. A frame dropped unsent (`abandon`: any
  `StaleAtSend`, a NAK or give-up on its packet, a slot that is not Knit's, a shed) hands its slot back every time.
  **The covers (ADR 054, ADR 2026-09.y5f3)** now guard the compact DM alone: one to us, or to a peer BLE/NAN holds a
  **live link** to (`skipCovered`, read off `linkedPeers`, fed only by `suppressDataPath` — links, never sightings),
  or to a peer a connected spool heard from within `SPOOL_COVER_MS` (15 min) never airs, and one already queued is
  abandoned at dequeue (`StaleAtSend.LINKED` / `INTERNET`): the link or the spool carries its signed copy for free.
  Counted `loraSkippedLinked` / `loraSkippedInternet`; `internetPeers` comes from `MeshManager`'s spool-presence watch
  through `MeshTransport.coveredByInternet`. A live compact frame from a covered peer (an RSSI, not MQTT, a fresh
  `rx_time`) lifts their cover (`liftedPeers`, `lora cover lifted <peer>`) until the relay itself drops them: their
  sender aired it because no relay joins us by its reckoning (#294, ADR 2026-09.y5f3's 2026-11-04 amendment). The cover
  every gate reads is `internetPeers` less `liftedPeers`, in memory only, emptied by `quiesce`, and shows as
  `internetCovered` in
  `…debug.LORA`.
- **No room ✓✓ (ADR 2026-10.rp3s).** `fastSend` refuses everything, so AckSync's cleartext receipt and its sealed
  escalation never air. A room tick goes home over a link or the spool; `AckSync.escalateRide` reads
  `shortRangeReachable`, so an author the board alone can hear keeps the tick parked rather than sealing it into an
  owed entry nothing can carry. Room ✓✓ ids still ride the **signed** copy of an own DM tick as riders (`MeshManager`'s
  `riderCap`), which goes by custody, the spool and the links; its compact copies carry the DM ids alone.

Everything else (group-form chat, `groupupdate`/`groupleave`, `typing`, `blobreq`/`keyreq`) is refused.

Outbound decodes `wire.signed` only to apply the policy, then reuses `FastFrameCodec` (ADR 030) to
compact/fragment: `sig`/`signed` pass through byte-exact, so this is **not a wire change** and the
originator's signature verifies unchanged. One packet may leave *larger* than the frame needs
(ADR 2026-09.mhs5): a 2.8 board that signs on the bound slot signs anything at or under 165 B, so `LoraFrameCodec`
grows the frame's **last** packet to 166 and the board sends it unsigned (on a board that does not sign there — pre-2.8,
or 2.8.2 under `COMPATIBLE`, #243 — `padTo` finds no saving and pads nothing) — a few bytes of pad instead of 66 of signature, ~20 % off the
room post (the only frame of this form that still airs). Two rules keep that safe. The pad is only legal where a receiver ignores trailing
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
                              compact DMs (LoraDirectPolicy marks, catch-up on a hearing) · LoraReprobe (the re-probe)
  └─ MeshtasticLink (seam)    state / packets / outcomes / queue · suspend send()
       └─ MeshtasticSession   pure actor: want_config handshake · drain-until-empty on FromNum · 180s heartbeat ·
          (pure)              client packet ids ↔ queueStatus/NAK · reconnect-with-backoff (ConnectBackoffPolicy) ·
                              direct-then-background dial (BoardDialPolicy, ADR 2026-09.hp88)
            └─ MeshtasticGattDialer (seam)   dial() → connect · requestMtu(512, floor 128) · discover · resolve chars
                 └─ MeshtasticGatt           the kind's android.bluetooth half (mesh/bluetooth/meshtastic/): UUIDs,
                    │                        numbers, `lora-dial`, FromNum, the `MeshtasticGatt` log tag
                    └─ BoardGatt             the shared GATT core both kinds dial through (mesh/bluetooth/, Stage 3c):
                                             connect · MTU settle · discover · one op at a time · stackCall
```

**The neutral seam (ADR 2026-11.gsqu, Stage 1).** The transport reads its board through `BoardLink`, never
`MeshtasticLink` directly: `MeshtasticBoardLink` adapts the unchanged Meshtastic link with no buffering of its own
(`state` is a value-mapping view, read on every `.value`; `packets` and `outcomes` are mapped on the transport's
collectors). `BoardState.Ready` carries the board's `BoardAddr`, its payload cap, its `RadioParams` and the kind's own
state (`detail`, the `LinkState.Ready`); a `BoardPacket` carries the neutral fields and the `ReceivedPacket` as its
attachment. `BoardLink.send` takes a `SendPurpose` (`KnitFrame`, `DirectDm`, `PublicPost`, `AutoReply`, `Admin`) and the
adapter writes exactly the fields the transport did (`hop_limit` 3 but on the admin write, the port, `want_ack` on a
compact DM alone, the bound slot or 0), returning `SendResult` and the `userSeq()` stamped before the write. A kind's
numbers are `BoardTraits` (`MeshtasticTraits` reads every Meshtastic constant where it lives); a compact DM's
`airings` ride its `DirectSend`, so admission, booking and refund read one number. Every refusal is read through
`BoardTraits.refusalOf` (`RefusalClass`: `OWN_NO_KEY`, `PEER_LACKS_KEY`, `PACE`, `DEST_REFUSED`, `OTHER`). `LoraAirtime`
prices from `RadioParams` and a `DutyPolicy`, mapped from a `LoraRadioConfig` in one place. `MeshManager` sizes a compact
copy by its sink's `LoraDirectSink.budgetFor` (Meshtastic: 168 B, 9 tick acks), and a copy that sheds its inline acks
gives the LoRa-held ones back to the coalescer (`compactCopyOf`).

Everything only a Meshtastic board does sits behind `BoardKindHooks` (Stage 1c), and the transport reads the
`MeshtasticLink` in one place, where it builds `MeshtasticBoardLink` and `MeshtasticHooks`. `MeshtasticHooks` is made
per plane instance and creates the kind's state itself (`DmAutoReplyPolicy`, `LoraContactPolicy`, the room's post
floor, the setup flag); it reaches back through `PlaneCore` (the ledger, the config, the payload cap, `restored`, a
room-class `enqueue`, `limitersMoved`, `onBoardKeyHeard`). The inbound dispatch is `route` → `PacketRoute` (`DIRECT`,
`KNIT`, `RETIRED`, `KIND`, `HEARD`, `DROPPED`) in the order the transport always read it: the compact DM on the bound
slot or index 0 ahead of everything, a hearing for everything else, then a `NodeInfo` or a text to the hooks
(`onKindPacket`), a Knit frame on the bound slot, or the burned tag. The board's ACK of a compact DM to us is
`bookDirectAck`; the room is `postToPublicChannel` and `sendKindFrame` (the reply too); the auto-reply's memory goes in
and out of the plane state through `restoreState` / `snapshotState` with no format change; the slot guards are
`directSlotRefused` (slot 0) and `checkSlot` (`SlotCheck`, and the core refuses a slot the kind has no answer for);
the session-up pricing and binding are `onReady`; a missing key is `teachKey` (`KeyTeach`, `KeyWrite`, the core holding
and releasing the frame), `onDelivered` and `bookKeyReply`, and the setup is `provision`. The core keeps every branch
order: a handled missing key, the ended send, the 35, the destination's refusal.

**Per-kind scaffolding (ADR 2026-11.gsqu, Stage 2).** A plane instance is addressed by its `BoardKind`: each per-kind
Koin single carries `loraPlane(kind)`, and the composite takes one LoRa child per `BUILT_BOARD_KINDS` (Meshtastic's
alone). `MeshManager` reaches the compact-DM sink through `LoraDirectRouter.sinkFor(peerId)`, once per offer: the bound
kind's plane, Meshtastic's with none bound, never a board map — the map stays the plane's own gate. Each instance keeps
its limiters under its own key (`lora_plane_state`, `meshcore_plane_state`), reports `boardKind` on its
`TransportStatus` (every kind is `TransportKind.LoRa`), and writes its own field-log stream (`lora` is Meshtastic's; a
non-Meshtastic line carries `board`). `LoraStatusRepository` hands the UI the bound kind's `LoraFacts`, with that
kind's compact budget (`directBudget`) so the composer never reaches a transport. A board is bound through
`BoardBindPolicy`, which clears the other kind's identity and keeps the shared switches and its setup record.

`MeshtasticProto` + `ProtoIo` are a hand-rolled protobuf codec (zero new deps): `ToRadio{want_config_id,
heartbeat, disconnect, packet{Data{portnum=PRIVATE_APP 256, payload}}}` out; `FromRadio{my_info,
config_complete_id, packet, queueStatus, rebooted, channel, metadata}` in; `Routing.error_reason` NAKs.
Golden byte vectors pin every field number; malformed input decodes to null, never throws.

## Board facts (verified 2026-08-24)

- Service `6ba1b218-15a8-461f-9fa8-5dcae273eafd`; ToRadio `f75c76d2-…` (write), FromRadio `2c55e69e-…`
  (read; one protobuf per read, 0 bytes = drained), FromNum `ed9da18c-…` (notify, u32 LE counter).
- Bonding required in PIN modes; the V4's OLED shows a random 6-digit PIN. MTU 512 requested; a floor of 128
  (`MeshtasticGatt.MIN_MTU`) catches a failed negotiation, and the transport sizes each packet to the MTU it got
  (`MeshtasticBoardLink.payloadCap`), so no write is ever split. ESP32 = **one BLE client** — the Meshtastic app must
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
- **Firmware 2.8 signs the broadcasts it sends for us — by version and policy (#243).** `Router::perhapsEncode`
  attaches a 64-byte XEdDSA signature (`Data.xeddsa_signature`, +66 B with framing) to a non-PKI broadcast it
  originates that still fits signed — so a Knit payload **≤ 165 B** (`MeshtasticProto.MAX_SIGNED_PAYLOAD`) grows by
  66 B on the air and a larger one goes unsigned exactly as before. Which boards: tag **2.8.1** (and the withdrawn 2.8.0)
  signs every broadcast whatever `Config.SecurityConfig.packet_signature_policy` says, and in ham mode every unicast,
  routing included (`Router.cpp:1270`); a build with upstream **`34cb8d582`** (reported `2.8.2`, `Router.cpp:1205-1235`)
  signs only under `BALANCED` or `STRICT` — broadcasts, a `NodeInfo` or position even unicast, a ham data unicast —
  never under `COMPATIBLE` (the default), never on a `use_aead` channel and never a routing packet.
  `DeviceMetadata.has_xeddsa` is set on every XEdDSA build (`main.cpp:1390`), so it says what the build *can* do, not
  whether it signs. `LoraAirtime.price` is the rule, off the handshake's security config (`want_config` streams it on
  both versions; only field 9 is read, never the keys beside it) — none streamed reads as signing, a 2.8.2 dev build
  from before `34cb8d582` (10-01 to 10-21) signs under `COMPATIBLE` yet reads unsigned (accepted, ADR 2026-09.mhs5's
  2026-10-30 amendment). An AEAD channel adds 12 B (`MESHTASTIC_AEAD_OVERHEAD`) to every packet on it, and 2.8.1 signs
  there anyway and then refuses `TOO_LARGE` what the tag pushes over, so only ≤ 154 B of text / 153 of a Knit frame
  leaves signed. It is a cliff, not a ramp, and it falls between ADR 060's
  one-packet room tick (157 B, +36 % air) and its one-packet DM ✓✓ (221 B, unchanged). Knit cannot ask for it
  or decline it; `LoraAirtime` charges for it, and since ADR 2026-09.mhs5 **dodges** it — see the codec note
  below. Receiving is unaffected while
  `Config.SecurityConfig.packet_signature_policy` is at its `COMPATIBLE` default — under `STRICT` a board
  drops every unsigned non-PKI, non-routing packet (`Router.cpp:780-784` at `34cb8d5`), which after the padding is every
  Knit frame and room post rather than most of them, and with `COMPATIBLE` the default on 2.8.2 most senders stop
  signing, so a contact on a `STRICT` board hears none of ours; `BALANCED` is safe (it drops only unsigned packets that
  *could* have been signed, and a padded frame could not). **The phone gets the whole story** (ADR 2026-09.ggq4, verified
  on both lab tags): the 64-byte `Data.xeddsa_signature` rides up intact, and the board's own verdict
  `MeshPacket.xeddsa_signed` (field 22, computed on receive against the key *its* NodeDB holds, never trusted off the
  air) beside it, plus `NodeInfo.has_xeddsa_signed` (14) and `DeviceMetadata.has_xeddsa` (14). The signing input Knit
  verifies is 2.8.1's versioned buffer over the whole
  `Data` envelope (`0x01 ‖ from ‖ id ‖ to ‖ portnum ‖ request_id ‖ reply_id ‖ emoji ‖ bitfield ‖ flags ‖ payload`;
  ggq4's 2026-10-29 amendment, #242); 2.8.0's `LE32(from) ‖ LE32(id) ‖ LE32(portnum) ‖ payload` was dropped when
  Meshtastic withdrew that release, under the board's Curve25519 key converted to Ed25519 (`y = (u − 1)/(u + 1)`, sign bit 0); on 2.8 a node
  number **is** the CRC32 of that key (`NodeDB::createNewIdentity`, from 2.8.0, once a region is set). The text cliff is
  **166 B** (one-byte portnum), the Knit-frame one 165.
  Two rules to remember: a signed packet that fails against the key the *receiving* board holds is dropped
  before the phone sees it, whatever the policy — a stale or planted key silently blackholes that sender —
  and an unverifiable one (no key in the NodeDB) is passed up with the signature and the flag false.
  <!-- doc-lint: allow Config.SecurityConfig.packet_signature_policy — a Meshtastic protobuf field, not the app's Config -->
  <!-- doc-lint: allow NodeInfo.has_xeddsa_signed — a Meshtastic protobuf field, not the app's NodeInfo -->

## Pacing

One budget since ADR 2026-10.ug4a. `LoraPacePolicy` (pure): 3 s min inter-packet gap, a 32-frame queue (ADR 054 — sized
to hold a 15-minute wait), NAK back-off (rate/duty → a 60 s cool-down), hold while `queueFree == 0`, and a hold while
the window's one ledger will not take a frame — a refused frame is skipped rather than blocking the queue behind it,
except past a `DIRECT` frame the window's **total** refused: that one holds everything ranked behind it until the air
it needs comes back (`LoraAirtime.refusal`, ADR 044's 2026-10-04 amendment, narrowed to `DIRECT` by ADR 2026-10.ug4a —
every room post is one packet, so none is starved by smaller ones). `LoraAirtime` keeps **one ledger and one total**:
every frame of ours spends the one allowance (`Ceiling.FULL`), and the Meshtastic room's posts and the DM auto-reply
only while the total, their own cost included, stays within half of it (`Ceiling.HALF`, `PUBLIC_CEILING`; refused as
`CEILING`, which holds nothing). No bucket, no share, no tick tail. A booking is `(atMs, ms)`; `refund` matches on the
two. The ledger is **not persisted** and nothing seeds it: a restart starts the window empty (below).
Two priorities (`FrameClass`): `DIRECT` — a compact DM, its ✓✓ (`DirectKind.RECEIPT`, said so by `MeshManager`'s offer:
the transport cannot read sealed bytes), a reaction, a resend, a re-probe — dequeues before `ROOM` — a Knit room post,
the Meshtastic room's post, the auto-reply — FIFO within each. When full the queue **sheds** the oldest **whole** frame
(never a lone fragment) of the lowest rank present, the newcomer included (`OutboundFrame.shedRank`): a room post, then a
✓✓, then a DM or a reaction — so a ✓✓ never pushes out something the user typed, and a room post alone at the bottom is
`REFUSED` rather than evicting a DM. Both eviction and refusal count as `loraDroppedQueue`. A compact DM keys every copy
the plane might queue on its frame id (`frameKey`), so the offer, the resend and the re-probe see a copy still waiting
(`hasQueued`, the frame being written included, and the in-flight table for one awaiting its answer) and do not queue a
second. Every booking is priced by `LoraAirtime.price` off one snapshot per handshake (`onBoard`, #243): the firmware's
version, `has_xeddsa`, `packet_signature_policy`, ham mode and `use_aead` on slot 0 and the Knit slot, asked per slot and
packet class. A data unicast — a compact DM and the auto-reply alike — is a PKI unicast, unsigned and +12 B, unless the
board is in ham mode; our board's `NodeInfo` after a `35` is signed under 2.8.2's `BALANCED`/`STRICT` (2.8.1: only in
ham mode); its routing ACK only by 2.8.1 in ham mode; an AEAD slot adds 12 B to every channel-encrypted packet. A compact
DM is admitted against every airing
the firmware may give it (`OutboundFrame.admissionSizes`, ADR 2026-10.x9fa): one airing would fit a nearly spent window
where its retries would not.

**By purpose, through the kind (ADR 2026-11.gsqu's Stage 5a-1 amendment).** A frame names what it is on the air
(`OutboundFrame.purpose`, an `AirPurpose`: room flood, compact DM, public post, reply; the hooks' ACK; MeshCore's PATH
return) and the ledger prices it through the kind's hook — `BoardTraits.price` for the price, `phyBytes` for its bytes on
the air, `airMs` for the time. Meshtastic's hook is the code it replaced, mapping each purpose to the slot, packet class
and port `LoraAirtime.price` reads, `LoraAirtime.formulaMs` at 24 B of framing and a 20.25-symbol preamble
(`LoraAirtimeKindParityTest` against a verbatim copy, beside `LoraAirtimeParityTest`'s golden). A third ceiling,
`Ceiling.FLOOD` (`FLOOD_CEILING`, 0.15), counts the window's **flood bookings** alone (a `Booking` carries the ceiling it was
admitted under; `record` takes it): a MeshCore board's room posts (`BoardTraits.roomCeiling`; Meshtastic's stays `FULL`).
A frame its **own** ceiling refuses on an empty window is `NEVER`, and once the board's duty is known
(`LoraAirtime.dutyKnown`; before, it is only skipped) the pacer drops it (`LoraPacePolicy.drainNever`) — counted
`loraAirNever`, logged `lora air never <label>` — rather than leave it queued for the 1-s idle tick to ask about again. On `FULL` that is the whole allowance, as it always was.

**The shared-band brake (ADR 2026-10.ug4a).** While the board reports its channel utilisation at or over 40 %
(`BUSY_BAND_PERCENT`, the firmware's `max_channel_util_percent`, compared `>=`) on a reading at most 5 minutes old
(`BRAKE_FRESH_MS`), off a dedicated slot, the pacer skips Knit room posts (`OutboundFrame.braked`: `ROOM` on the bound
Knit slot) without dropping them. 40 % is where the firmware stops even its own non-polite automatic traffic
(`isTxAllowedChannelUtil()` at its default; the polite 25 % gates only the optional kind), so Knit holds a typed post
only there. Still stricter than stock, which never gates text a client originates — a stock client would send it — and
on a busy metro slot, at or over 40 % most of the time, Knit room posts may never cross LoRa and drop as `STALE` after
`FRESH_MS`. Compact DMs, the Meshtastic room and the auto-reply still go — the last two are the
band's own kind of traffic, floored at 30 s and capped at half. The figure is the last **minute** of TX, RX and noise
together, our own airings included, so our own burst can brake our next room post. It reads only the board's live
self-telemetry (`MeshtasticSession`'s `PORT_TELEMETRY && self` arm, `BoardAir.atMs` stamping each reading so a repeat is
a new value), timed on arrival by the transport (`onBoardAir`); the handshake `NodeInfo`'s `device_metrics` can be hours
old and never feeds it. Losing the board forgets the reading (`forgetBand`); a re-handshake does not, and the last
reading ages out. A brake is not an airtime refusal: it never sets the air deferral; a braked-only queue reports itself
due when the reading goes stale, and a new reading wakes the pacer. A braked post keeps its `FRESH_MS` age-out at send
(`STALE`), so a post still braked past `FRESH_MS` from its send drops from LoRa; the radios, custody and the spool still carry it.
Counted once per frame (`loraBandBraked`, apart from `loraAirtimeHeld`), logged on its edges (`lora band busy` /
`lora band clear`), and shown as `braked` / `boardAirAgeMs` in `…debug.LORA` (as of the last status publish, which the
linger sweep refreshes every minute). The Nearby room says so with `LoraReach.RoomBandBusy` while someone is
LoRa-only (#240, ADR 2026-09.ursc's 2026-10-29 amendment).

**Freshness gate** (fan-out paths only, room included): a `chat`/`reaction` whose `sentAt` is more than
15 min old (`LoraFramePolicy.FRESH_MS`) is a custody re-serve and is not fanned — without it a newcomer's
whole backfill re-fanned over the air, twelve frames at a time. Profiles (publish-stamped `sentAt`) and
receipts are exempt; a compact DM never asks it (its age bound is the receiver's window and the source's). It reads the
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

Bluetooth going off need not reach the session as a disconnect, so the session watches `dialer.adapterOn` and ends at
once (`lora adapter off mid-session`): Ready goes straight to `Unavailable`, no backoff, and the loop dials direct when
the adapter returns. The connect, a GATT op or the bond read that throws is a failed op (`lora gatt <op> threw`, status
`-2`; `dial`'s adapter reads and the receiver's `isEnabled` are outside that guard); anything else that throws ends that
pass as `threw <class>` (`lora link threw`) and takes the backoff. Nothing but `stop()` or a terminal state ends the
loop, and nothing else would start it again. The inbox is one per session: a `send` or `provisionChannel` the session
did not serve is answered `NotReady` as it ends, never carried to the next one, whose gates it was not asked under. The
transport requeues on `NotReady`, a packet unsent or whose write failed; one that likely went (a write timed out, a
compact DM's lost to `Closed`, a cancel in the write or drain) is `Timeout`, booked and never re-aired. A broadcast's
`Closed` stays `NotReady`, at the risk of a second airing (hp88's 2026-11-06 amendment).

## Limiters that outlive the process (ADR 2026-09.7svb, ADR 2026-10.ug4a)

**A limiter that resets on launch is not a limiter — where it bounds something a restart replays.** These are persisted
through `LoraPlaneState` (a JSON blob under one settings key, `LoraPlaneStateStore`; `mesh/lora/` itself stays
Android-free): the DM auto-reply's memory (its once-a-day cap per sender, across a restart; the board hands a packet to
the phone once, so a reconnect brings only what queued while the phone was away — "A DM to the board is answered once")
and the re-probe's schedule (`reprobes`, ADR 2026-10.rp3s, its keys kept by ADR 2026-10.x9fa) — the one thing
on the plane that airs without a user action. **The airtime ledger is not** (ADR 2026-10.ug4a, superseding 7svb's
ledger half): the regional duty cycle is the firmware's to enforce (`Router::send` refuses with `DUTY_CYCLE_LIMIT` over
the trailing hour, which the pacer backs off on), so 7svb's "the ledger is the only thing enforcing it" was false; a
resend needs a hearing; everything else is something the user just typed. In a 100 %-duty region a restart resets
politeness by at most one allowance. The board's `airUtilTx` is not a seed either: an hour of everything the radio
sent, relays included, it would over-book a client on a busy mesh by most of an allowance (2.5–5 % an hour is 22–45 s
of a 45 s window) and under-book a recent burst. The probe window and the re-offer's per-frame hour went with the reach
gate (ADR 2026-10.x9fa), the beacon floor, the gossip interval, the serve caps and the profile re-fan gate with the
traffic they limited (ADR 2026-10.rp3s): a blob an older build wrote — its `air` ledger included — still loads under
the same key, the store ignoring the dropped keys, and every limiter this build keeps comes back
(`LoraPlaneStateStoreTest`).

- **None of them is cleared by `quiesce`** — the restore runs once per instance, so a clear on `stop()` would
  reopen every window for an in-process restart.
- **Stamps are wall-clock, converted at each end** (`WallShift`). `clock` is `elapsedRealtime`, which
  restarts at zero every boot: persisted directly it reads as the far future and ages nothing out. A
  snapshot stamped *ahead* of the wall clock is refused whole — the clock moved backwards, and starting clean is the
  smaller error.
- **Nothing transmits until it is back** (`awaitRestored`, on the pacer and the re-probe). It releases on failure
  and behind a 5 s timeout: a store that never answers costs a schedule, not the plane. The pacer also waits for the
  self id (`selfIdKnown`, resolved on its own coroutine at `start`): until it lands, nothing is ours, and only the
  send-time re-ask (`staleReason`) holding the id keeps a frame that is not, or one addressed to us, off the air (#141).
- **The re-probe snapshot is capped** (`STATE_REPROBE_CAP`, 64) with direct board holders first, then the soonest due
  (`LoraReprobe.capped`): a non-holder's schedule — started while the board map had not reported, restored from an
  older build, or kept by a holder who lost the capability — is forgotten on its first due round anyway, so it is the
  one dropped, never a holder's earned backoff.
- **`sigSeen` and `lastHeardAt` are deliberately not persisted.** A restored `sigSeen` would carry "we
  transmitted" across a restart, which on a plane with no acks is not evidence anyone heard (the ADR
  2026-09.y8pu reasoning); a restored `lastHeardAt` would claim `reachable` on evidence this process never
  saw (ADR 2026-09.2ajk). What an empty `sigSeen` would let through — an own room post re-served back to us after a
  restart (a neighbour's custody, a spool pull: the router's seen window is empty too, and `InboundPipeline.onDeliver`
  re-fans a first-seen self frame) — the fan-out refuses on its own: a post whose `sentAt` is before this process
  started (`LoraFramePolicy.predates`, `processStartWall` from `Process.getStartElapsedRealtime`) is not one the user
  just typed, and never re-airs (`loraSuppressed`, `lora not re-aired`, ADR 2026-10.ug4a).
- Writes are debounced 2 s behind a conflated wake, plus one on `stop()` taken **before** `quiesce()`. A kill inside the
  debounce loses that one change. The restore runs **once per instance**: an in-process stop/start keeps the live
  limiters rather than re-reading a staler blob.

> **The pacer's monitor is taken outside the ledger's, never the other way round.** `LoraPacePolicy`,
> `LoraAirtime`, `LoraDirectPolicy` and `LoraReprobe` are all `@Synchronized` on every entry point, because the queue has
> six writers on five coroutines (`take` on the pacer; `enqueue` on whichever coroutine sent; `onQueueStatus`
> / `evictOversize` / `onNak` on the link, queue and outcomes collectors, and `onBandReading` on the board-air
> collector) and the ledger has readers that prune — the status publisher's `snapshot`. `ArrayDeque` is not thread-safe and nulls a slot on
> removal, so a structural change under `admitBest`'s index walk hands it a null through a non-null type:
> field-observed on the lab P7 (2026-09-10) as `NullPointerException: … OutboundFrame.getBucket()`, which
> kills the pacer coroutine and silences the plane while it still reports `state: Ready`. Nothing restarts
> it. Regression: `LoraPacePolicyTest.aQueueDrainedWhileOtherThreadsEnqueueDoesNotTearItself`.

## DMs (ADR 2026-10.x9fa)

**A DM rides as one compact pair-sealed unicast.** `MeshManager` offers the compact copy of a DM, its coalesced ✓✓ and
a DM reaction beside the signed copy (`MeshManager.offerLoraDirect` → `LoraDirectSink.offer`, bound in `di/MeshModule`
where the build carries the plane), which still goes to custody, the spool and the links exactly as before; the board no
longer carries any DM-form mesh frame. The crypto half — the pair seal `mesh/crypto/LoraDirectSeal`, the board map and
unacked source off `MeshManager`, delivery through `InboundPipeline.deliverLoraDirect` and the `lora_direct_seen` table
that lets the signed copy still run its ratchet step — is `.agents/context/crypto.md`'s compact-door entry. The transport seals the
plaintext it never reads (`LoraDirectCrypto.seal`, a fresh salt per seal) into **one** packet, tag `0x11`
(`LORA_DIRECT_TAG`): `tag 1 | pairTag 4 | salt 8 | frameId 16 | sentAt u48 6 | AES-256-GCM` around the v3 compact
content, 51 B of overhead (`LORA_DIRECT_OVERHEAD`). It goes as a **Meshtastic unicast with `want_ack`** to the
recipient's board (`LinkDestination.Direct(BoardAddr)`), on the bound Knit **secondary**, `PRIVATE_APP`, `hop_limit =
HOP_LIMIT`. Never on slot 0: Knit's frames belong on the Knit channel, so a direct send to a plane bound at 0 is
refused (`loraDirectSlotZero`, `StaleAtSend.SLOT_ZERO`). **Firmware 2.8 PKI-encrypts it all the same** (ADR 2026-10.x9fa's
2026-10-30 amendment): `Router::wouldEncryptWithPKC` (`Router.cpp:1189-1212` at v2.8.1) never reads the channel index,
so every compact DM that airs is a PKI unicast — 12 B more on the air, reported on the receiver's index 0 with
`pki_encrypted`, acked there — and with no key for the destination our own board refuses it (`39`) rather than fall
back to the channel key (ADR 2026-10.aajj, below). `Router::perhapsDecode`'s legacy-DM refusal is the text port's alone
(ADR 2026-09.4n5p's 2026-10-29 amendment), which is the decode side; the earlier reading here, that the secondary keeps
the DM off PKI, was wrong, and the 2.8 board trial that "confirmed it" passed through PKI on index 0.

- **Who.** Only a pinned contact whose profile names a board and carries the capability: the board map
  (`Flow<Map<String, LoraBoardHolder>>`, built by `MeshManager.loraBoardMap`: a contact's claim before a stranger's, then
  the newest claim, then the larger node id; our own board never).
  Without the capability their board would ack a packet their app throws away. A map that has not reported reads "not
  now"; a failing source is counted (`loraBoardHoldersFailed`) and asked again every 5 min, its last map in force.
- **Gates**, asked at offer and again before the first byte (`RideGate.direct` / `reachHeld` / `refusalHeld` /
  `unackedFrameId`):
  DMs switched on (`LoraConfig.dms`); still a direct board holder at the same board (`NOT_DIRECT`); no live link and no
  spool cover (`LINKED` / `INTERNET`); the slot; one copy per frame id queued or awaiting its answer (`hasQueued` and
  `LoraDirectPolicy.hasFrame`); the peer not marked refused or keyless, nor — for a ✓✓ or a reaction — unreachable
  (`HELD`, an offer only: `reachHeld` on a ✓✓ or a reaction, `refusalHeld` on a DM the user typed, which passes an
  unreachable mark and gets an attempt of its own, `loraDirectPastHold`, `lora direct past hold <id> -> <peer>`; x9fa's
  2026-11-19 amendment); and the
  seal fitting one PKI packet (`LoraDirectPolicy.payloadCap`: `min(MAX_PAYLOAD − 12, maxPayload − want_ack's 2 B)`,
  219 B at every MTU a board negotiates; `loraDirectTooLong`). `MeshManager.LORA_DIRECT_PLAINTEXT_BYTES` (168 B) reads
  the same function. One send in flight
  per peer not known reachable (`mustWait`): the rest are parked (≤ 8 per peer), offered again on the destination's
  ack, released if that send never flew. On a failure (`LoraDirectPolicy.unparkOnFailure`) the newest parked DM the user
  typed goes back through the offer at once — its own attempt, `lora direct next <id> -> <peer> (typed during the
  failed send, +<n> left)` — the older DMs go to the source, a parked ✓✓ is told undelivered and a reaction rides its
  signed copy; only where asking again could help (their board silent, our own board's refusal, a second
  `PKI_UNKNOWN_PUBKEY` to the re-offer, which marks unreachable), never after their board's refusal, where everything
  goes to the source as before. No time edge: a DM
  parked while the failed send was still queued, or in a key's retry gap, is as much its turn.
- **The flight table** (`LoraDirectPolicy`, `BoardTraits.maxFlights`: 32 on Meshtastic, 2 on MeshCore). While it is
  full every compact frame waits in the pacer (`LoraPacePolicy.take`'s `directRoom`, asked per frame against
  `LoraDirectPolicy.flightRoom`'s `DirectFlightRoom`; skipped like a braked post, never asked for air, never a holder; a
  30-s backstop deferral, `FLIGHT_RECHECK_MS`, cleared by `onFlightEnded` on every settle), so no launch pushes a live send
  out as no outcome. Room posts still go. Sends to peers marked unreachable, and resends and re-probes to peers not known
  reachable (marks are wiped by a board loss and never restored, the re-probe schedule is), hold at most
  `maxFlights - 1` places: the last is kept for a fresh frame to a reachable or unknown peer, so attempts at absent boards
  (minutes each on MeshCore) never stall a DM to a peer who is there. A peer whose board acked this session (`REACHABLE`)
  may use both places, its after-ack batch included.
- **A ✓✓ whose compact copy did not land** (`LoraMeshTransport.onDirectUndelivered(frameId, peerId)`, wired in
  `di/LoraWiring` to `MeshManager.onLoraDirectUndelivered`; `lora direct undelivered <id> -> <peer> (<why>)`). Once per
  send, for a `DirectKind.RECEIPT` held at the offer by an unreachable mark (`held`); dropped unflown for want of air —
  a shed or oversize evict, a pacing NAK at the write, `abandonNever`, a give-up at the write (`air`) — or for an
  unreachable mark at send (`held`); refused by our own board after it took it (`refused`); parked behind a failed send
  (`parked`) or when the board goes (`board lost`, with every ✓✓ in flight); or its own send ended unreachable or
  unanswered (`unreachable`). Never for their board's refusal, a missing key, a cover, slot 0, `NOT_DIRECT` or a seal
  too long. The app holds its ids to ride the next DM the user types (`DmAckCoalescer`'s ride-only hold, by the tick's
  frame id through `LoraTickIds`), or releases them as one compact tick when that author's board next acks a compact
  frame of ours of any kind (`LoraMeshTransport.onBoardAcked(frameId, peerId)`, wired to `MeshManager.onLoraBoardAcked`: a DM,
  a ✓✓ or a reaction, on time in `settle` or late in `onUnawaited`; never an implicit ack nor an `AckProof.INVALID`
  one; the same lock-free contract as `onRadioReached`; field trial 2026-11-20, where a landed ✓✓ released nothing and a
  held one waited 70 min). Never on the 45-s timer: against an unreachable board that would loop.
- **Outcomes** (`MeshtasticSession.routeNak` → `PacketOutcome`). `Acked(byDestination)` only when the `Routing`
  packet comes from the destination's board: the board is `Reachable` (45-min linger), the re-probe resets, parked
  offers go. An implicit ack — a relay, or our own board hearing it repeated — is counted (`loraDirectImplicitAck`)
  and ends nothing. `MAX_RETRANSMIT` / `NO_RESPONSE` → `Unreachable`; no answer by the deadline (`deadlineMs`, five gaps at the busiest contention window plus 10 s, about 2 min for a full packet at LongFast) → `NoOutcome`; both mark the board `Unreachable` (never `Reachable` on silence) and hold
  offers. A refusal by the destination's board (`NO_CHANNEL`, which a board that still holds our key never sends:
  it decodes the PKI DM on index 0) holds the peer until their profile changes; one by our own board only paces
  (`pace.onNak`). The destination's `PKI_UNKNOWN_PUBKEY` (35: its board lacks ours) is never a refusal: our board
  answers it with our NodeInfo (booked, unsigned), and the frame goes once more one retransmit gap on
  (`loraDirectPeerLacksOurKey`, `lora direct peer lacks our key`, `lora resend <id> -> <peer> (our key sent)`); a
  second 35 to that re-offer is a give-up (`Unreachable`). The table of sends awaiting an answer is bounded (32),
  in memory, and cleared — booked — when the board goes.
- **Our board without their key** (ADR 2026-10.aajj). Our own board refuses a compact DM it holds no key for:
  `queueStatus.res = 39`, then a self-NAK on index 0 (or the reverse) — acted on once per packet (`loraDirectNoKey`,
  `lora direct no key <id> -> <peer>`), the other report only attributed (`lora nak … (late, no key)`). Nothing aired,
  nothing was booked, and the frame is **held** while `LoraContactPolicy` decides whether one admin `add_contact`
  (field 66, a local admin message, no passkey, never during a setup) may teach our board the peer's profile key: a
  contact's rank, a board no rival profile binds by its own key, not ours, and a 32-byte key whose CRC32 is the node
  number. The 39 is the board's word: a key in the phone's copy of its NodeInfo stream (`MeshtasticLink.nodeUser`) is
  read only if it was streamed after the send was issued — the firmware never reports a dropped node, and an
  over-the-air NodeInfo can be anybody's — and then stops the write (a different one is `loraDirectBoardKeyConflict`).
  The `User` is an allowlist: id, the names the stream showed (else `Knit abcd` / `Knit` / unmessagable) and the key —
  never the contact's display name, `role`, `hw_model` or an unknown field. On the write's `queueStatus` res 0 (`loraDirectContactAdded`,
  `lora direct contact added !<node> for <peer>`) the held frame goes back through the pacer and its gates; a write that
  timed out re-offers it once unrecorded (`lora direct contact unconfirmed`), the next 39 then terminal; the peer's
  other offers park behind it. A second 39 after a write (an `is_managed` board drops it silently), or a 39 with nothing
  Knit may write, marks the peer `NO_KEY`: held and counted, lifted by a changed profile entry, a NodeInfo from their
  board with a key (`lora direct key heard`), or the 4-h lapse. One write per (board, key) until a DM to it is acked
  `byDestination`, then one more; at most two keys per peer a day; in memory only. The board marks the contact a
  favourite (`NodeDB::addFromContact`).
- **What reach means here.** A radio, never a person: the destination's ack says its board took the packet, whatever
  its phone is doing. It never feeds `reachable`; a fresh authenticated compact DM *from* the peer does. After a
  restart every board is `Unknown`, so the first DM goes and its outcome decides.
- **The hearing hook** (`noteHeard`). Any packet from a board in the map clears an `Unreachable` mark — any portnum,
  slot 0 included, the session now surfacing NODEINFO and a far board's ROUTING packets for it — but only a live one:
  an RSSI (the handshake's NodeDB replay has none), not via MQTT, not our own board, and `rx_time` (`MeshPacket` field
  7) within `HEARING_MAX_AGE_MS` (3 min) when present (the to-phone queue replayed at reconnect keeps its old time). On
  the edge out of `Unreachable`, once the current gap has passed since the failure, the newest unacked DM is resent at
  once (`lora resend <id> -> <peer>`) and the older ≤ 2 follow only after its destination acks it; inside the gap the
  next re-probe is pulled forward instead. A hearing never resets the backoff. A received compact DM counts as a
  hearing only after `inbox.deliver` returns, so a ✓✓ it carried is applied before the resend asks what is unacked.
  Every resend re-asks `LoraDirectSource.isUnacked` at send (`StaleAtSend.ACKED`), never behind a timer.
- **The catch-up** (`catchUp`, #294, ADR 2026-10.x9fa's 2026-11-04 amendment). The hearing above is one of four facts
  that re-air what a cover or a failure stranded: a hearing on the edge out of `Unreachable` (`heard`), a relay cover
  lapsing (`cover lapsed`) or lifted by a live compact frame (`lifted`), and the destination's board acking a fresh DM
  (`after ack`, that DM skipped; never on a resend's ack). Each logs `lora resend <id> -> <peer> (<why>, +<n> after its
  ack)` — `(after ack, +<n> with it)` behind a fresh ack — or `lora catch-up refused <peer>` when a gate of the resend
  said no. All share the re-probe's gap since the
  last failure (`LoraReprobe.onHeard`), and a catch-up books as a re-probe (`onReprobed`; behind a fresh ack at the base
  gap). Only DMs no board took (`DirectDm.boardTook`, off `MeshManager.loraRadioReached`, in memory, dropped before the
  source's limit) with no copy waiting; after a restart the first catch-up per peer may re-air up to three a board
  took before it: a DM the board took waits on its phone, the re-probe's question, so ordinary chat never re-airs (the
  coalescer holds a ✓✓ up to 45 s). On a hearing or a cover the newest goes, ≤ 2 older after its ack (`RESEND_LIMIT`):
  a hearing is not an acked unicast. Behind a fresh DM's ack the destination's board just took a DM the user wrote, so
  up to `AFTER_ACK_LIMIT` (8) go at once, metered by the flight table, the window and the queue cap (x9fa's 2026-11-19
  amendment).
- **The re-probe** (`LoraReprobe`, `lora reprobe <id> -> <peer>`, `loraReprobed`) keys on our DMs still without their
  ✓✓, whatever the radio said: the phone may be away from a board that acks everything. The probe is that one DM;
  the ≤ 2 older ones no board took ride as its `flushAfter` and air once the destination's board acks it, in the same
  round (`lora reprobe <id> -> <peer> (+<n> after its ack)`, #343, x9fa's 2026-11-17 amendment). 15 min after the first DM,
  doubling to 4 h; a destination's ack of a fresh DM resets the gap and asks again one base gap on, an ack of a re-aired one keeps the earned gap (15, 30, 60 min while the phone stays away); a ✓✓ on the last re-aired DM
  resets it; no unacked DM left forgets the peer. Deferred while covered, refused, a send is queued or awaiting its
  answer, or the map has not reported; a restored schedule for a peer without the capability is forgotten on its first
  due round. Persisted under its old `ReprobeStamp` keys.
- **Air.** Priced unsigned (`LoraAirtime.UNSIGNED`), admitted against `LoraDirectPolicy.AIRINGS` (5: firmware 2.8's
  `NUM_RELIABLE_UNICAST_ATTEMPTS`, `NextHopRouter.h:115`; 2.7.26 made three, over-booked) and booked for all five at
  send; a destination's ack refunds the unflown ones (`LoraAirtime.refund`, counted at the quietest gap). The gap is
  `RadioInterface::getRetransmissionMsec`'s: `2·air + (2^CW + 2·CWmax + 2^((CWmax+CWmin)/2))·slot + 4.5 s`, slot ≈ 44.5 ms
  at LongFast. Every airing is priced with the 12-B PKC overhead (`OutboundFrame.price`, at admission, at booking and in
  the flight's `airMs`, so the refund matches; a board in ham mode skips PKI and is signed by the firmware's rule
  instead — 2.8.1 always, 2.8.2 under `BALANCED`/`STRICT` — read off the board's snapshot, `LoraAirtime.price`).
  A relay's repeat is an implicit ack that stops the retries, so silence then follows. Our board's ACK of
  a compact DM to us, opened or not, is booked as a nominal 18 B (8 B and the 2.8.1 ack proof's 10, attached only when
  our board holds the sender's key authoritatively, so the booking errs high) when the firmware sent one
  (`LoraDirectPolicy.boardAcks`, off the decoded `want_ack`, `hop_limit` and `next_hop`): `ReliableRouter::sniffReceived`
  (`ReliableRouter.cpp:123-158`) ACKs a `want_ack` unicast, and one without it only when the sender's next-hop routing
  named us with hops left; a unicast with neither draws nothing. The re-ACK of a duplicate the board never hands over
  (`NextHopRouter.cpp:163-170`) is not seen, so not booked. At LongFast a 40-character DM is one 95-B packet
  (1.13 s) and its ✓✓ one ~76-B packet (≈ 1.0 s); the worst case is five airings each (5.7 s, 5.0 s; 10.6 s for a full
  219-B packet), with the give-up about 46 s after the send on a quiet band. A send whose `queueStatus` timed out is
  awaited under its handle; a destination's ack after the deadline still marks the board reachable (`lora direct late
  ack`); a destination's refusal lapses after 4 h.
- **Receive** (`onDirectPacket`). A `PRIVATE_APP` packet starting `0x11` is taken before the bound-channel filter, on
  the bound slot or index 0 (a 2.8 board PKI-encrypts every compact DM, and the receiving board reports it there with
  `pki_encrypted`), only when addressed to our board, and whatever our own DM switch says — our board has already acked
  it. The sending radio counts in `boardsHeard` at once on the bound slot, and on index 0 only once the seal opened. `LoraDirectCrypto.open` names the
  sender from the pairTag index; a stranger, a failed seal and an out-of-window stamp are counted
  (`loraDirectStranger` / `AuthFailed` / `ClockRefused`) and dropped. An opened one is handed to `LoraDirectInbox`
  (`loraDirectDelivered`, `lora rx direct <id> from <peer>`); `lora direct delivered <id> from <peer>` follows once
  the pipeline has returned. That line means "handled", not "stored": a duplicate or a blocked copy logs it too.
- **Composer.** `LoraSizeHint.DM_BODY_BYTES` (160 B) is the body plus the inline acks `inlineAcksFor` may spend out
  of it, in one PKI packet (168 B of plaintext; a 160-B body is 164 B, re-measured 2026-10-30), pinned by
  `CoordinationPlaneSizeBudgetTest.aDmAtTheHintBudgetIsOneCompactPacketAtTheMtuFloor`. A reply or an attachment never
  fits; the hint names all three.

**What still doesn't cross:** group chat, group machinery (seeds, key req/ack, escalated ticks), our sealed content
ctls (resets, intro `CTL_PROFILE`, scope config, transfer, call), `typing`, attachment bytes and any DM past one
packet — all ride custody, the spool and the links. A board-less recipient behind another board-holder gets nothing
over LoRa: a unicast reaches one board, and that board's phone is its own.

**The ✓✓ is coalesced (ADR 054).** A DM that arrived over the board does not seal its receipt at once:
`InboundPipeline.acknowledge` holds it in `mesh/DmAckCoalescer` for ≤ 45 s from the batch's oldest id (12 ids flush
early), then `MeshManager.flushDmAcks` originates one signed tick (sealed only as an answer, room riders included) for
custody, the spool and the links, and offers its compact copies as `DirectKind.RECEIPT`: the DM ids alone, nine a packet
(`LORA_DIRECT_TICK_ACKS`), the first under the signed tick's id (`FrameClass.DIRECT` since ADR 2026-10.ug4a: shed
before a DM). With no session the author holds yet the compact copies still go, under a fresh id, and the signed tick
is held until one is answered (`holdDeclinedTicks`) — never downgraded to cleartext. The signed tick seals only the ids
still unanswered (ADR 2026-10.8hn6's 2026-11-06 amendment, #300): one the signed copy's re-ack or a held flush already
sealed keeps its compact tick in the same offer, and a batch with nothing left to seal airs under a fresh id and
originates nothing. What the tick seals is filed in `DmReAckLedger`, so a signed copy landing after the flush seals none.
If we send the author a DM meanwhile, `sendChat` carries pending ids inline (`MessageContent.acks`), out of the
composer's budget; the compact copy sheds them if they would cost a second packet. `heal()` is the flush backstop.
The reader never retries a compact ✓✓: the author's resend or re-probe of a DM still without one draws a re-ack
(`deliverLoraDirect`'s exists-gate), and the signed tick arrives by any other path. The board's routing ACK is not a
✓✓: it says only that their radio took the packet.

**A read rides the coalesced ✓✓, never a packet of its own (ADR 2026-11.z6jw).** `offerLoraTicks` masks each compact
chunk over its own id list with the ids the person has already read (`MessageContent.rm`, compact label 20): at most
4 bytes on a 9-id tick with 10 to spare, so the packet count never moves (`CoordinationPlaneSizeBudgetTest`). Toward a
capable author every compact tick carries `rm` at a width set by its id count alone, so its length on the public band says
nothing. A compact bit is best effort and never marks a read told; `ReadSync` never offers anything to the compact
door, and a read that missed the 45 s hold reaches a LoRa-only author through custody or the spool. The inline acks'
23-byte reservation is untouched: reserving for the mask would cost an ack, and that ack its own packet.
`readsLoraOffered` counts offers, not packets aired.

**Metadata.** Content stays end-to-end sealed, but `from`/`to` node numbers, timing and size are cleartext at
kilometre range, and the static per-direction pairTag links one pair's packets. `SettingsStore.loraDmEnabled`
(default **on**, the "Private messages over LoRa" switch, `…debug.LORA --ez dms`) rides into `LoraConfig.dms`; off,
the transport sends no compact DM while the room keeps riding. Each side gates its own sends.

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
   of `LoraAirtime.price`'s reading of the same string, because here a wrong guess writes to somebody's
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

A board whose bound slot is **not** the Knit channel never transmits (`MeshtasticHooks.boundSlotIsKnit`,
counted as `loraSuppressed`): after a restore, or before a setup, sending would put Knit's cleartext frames
onto whatever channel the board is on — most likely the public one. A board that reports no channel table at
all is given the benefit of the doubt, since going mute on unreadable firmware is the worse failure. A Knit slot
another client switched to AEAD (`use_aead`, #243) refuses a Knit broadcast and a ham-mode compact DM
(`MeshtasticHooks.aeadBlocks`, `lora send skipped: slot N is AEAD`, also `loraSuppressed`), since AEAD changes the
channel hash; a PKI compact DM leaves on channel 0 and still goes. The radio screen then reads the board as not set up,
and the setup writes the slot's own settings back with field 8 alone removed, which clears AEAD and keeps everything
else, position precision included (`MeshtasticSession.identityOnly`, `ChannelInfo.settingsRaw`). Trial owed: two
phones, A's Knit slot on AEAD — a DM airs PKI on index 0 and B shows ✓, a room post logs the line above, the setup
clears AEAD (ADR 2026-09.mhs5's 2026-10-30 amendment, trials 4 and 5, with the stock-client `--sendtext` legs).

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
  Between ✓ and ✓✓ (ADR 040's 2026-10-31 amendment), one of our DMs whose compact copy the destination's own board acked
  is `DeliveryStatus.RadioReached`: the LoRa mark ahead of one ✓ (`chat_tick_lora_reached`), "Reached their LoRa
  radio" on the bubble, the chat-list row and the details screen, which adds the ack's time (none for a late one) and
  "Waiting for their phone to confirm". It says their **radio** took the packet, never their phone. The source is
  `LoraMeshTransport.onRadioReached(frameId, peerId, late)`, called for a `DirectKind.MESSAGE` on a destination's ACK in `settle`
  or, late, in `onUnawaited` (an implicit ACK, a receipt's or a reaction's never), bound in `di/MeshModule` to
  `MeshManager.onLoraRadioReached` → `MeshController.loraRadioReached` (`LoraRadioReached`, row id → wall-clock stamp,
  first ack wins, 256 held, in memory only, never pruned: `received` always wins). The ack's proof gates it: firmware
  2.8.1's `ack_proof_status` (`MeshPacket` field 23, `AckProof` on `PacketOutcome.Acked.proof`) `INVALID` is never shown
  (`lora direct ack proof invalid <rowId> -> <peer>`); `ABSENT` — 2.7.x, ham mode, or a proof a relay's echo left the
  firmware unable to check — still counts, so the residual trust is an unauthenticated routing header anyone with the
  channel key can forge. Trial: P9 → P7 short DM with P7's board in range and P7's phone unable to take it (Bluetooth
  off, or LoRa off in Knit) and P9's spool off; the middle state shows within ~5 s of `lora direct acked <rowId> -> <P7>`
  (`…debug.LORA` `loraDirectAcked` +1) and holds; P7 Bluetooth back on → ✓✓ with its own glyph.
- **Header.** `LoraPlane { Off, Down, Live }` (`mesh/lora/LoraPlane.kt`) from `LoraStatusRepository.facts`
  (pushed) → `ConnectionStatusRow(lora = …)`: `Icons.Outlined.Sensors` (Live, tertiary) / `SensorsOff` (Down)
  after the cloud, spoken as "LoRa radio connected / not connected"; 45 s grace on the Down edge.
- **LoRa radio screen.** `BondedBoardDirectory` tags each bonded device with its `BoardKind` (`BoardFilter.kindOf`: LE
  only; MeshCore first, on the `MeshCore-` name prefix alone, never the generic NUS UUID; else Meshtastic on
  `Meshtastic_xxxx` / `<short>_xxxx` / the service UUID in cache). A MeshCore entry is listed but not pickable until
  Stage 5 (`BoardOption.pickable`); a Meshtastic dial that finds no Meshtastic service twice in a row ends terminal
  (`DialResult.ServiceAbsent`), so a MeshCore board bound by an older build is no longer redialled forever.
  `BoardFilter.visible` lists the tagged devices plus the bound board, with
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
  out there a full queue would delay* — because the room is addressed to nobody. It is one of the room's two LoRa
  states: `RoomBandBusy` outranks it while the busy-band brake holds room posts (#240, ursc's 2026-10-29 amendment); there is deliberately no `LoraOnly` counterpart (the room's audience is always a mix, so a
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
  the window's half ceiling, `Ceiling.HALF`, ADR 2026-10.ug4a) → `Destination.Public` on the shared pacer → channel 0,
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
  the number in one edit, read with it from one snapshot, and only when the number is the key's CRC32 — signing or not,
  ggq4's 2026-10-30 amendment; `MeshtasticHooks.boundKey`), `peers.loraKey` stores it, and `deliverMeshPost`
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
- **Signable cap.** On a board that signs on slot 0 the composer caps a post at that slot's cliff
  (`LoraFacts.roomSignedUpTo` ← `AirtimeSnapshot.roomSignedUpTo` ← `LoraAirtime.price`): 166
  (`PublicPostPolicy.MAX_SIGNED_TEXT_BYTES`, `MeshtasticProto.maxSignedPayload(PORT_TEXT_MESSAGE)`), or 154 on a 2.8.1
  board whose slot 0 is AEAD, so every post leaves signed; where the board signs no room post (pre-2.8, 2.8.2 under
  `COMPATIBLE`, 2.8.2 on AEAD) the 200-byte client convention. `postToPublicChannel` trims to the same
  `onAirBudget(price.signedUpTo)`. The `lora ready` line reads `signing=<Knit slot> policy=<policy|null>
  knitSignedUpTo=… roomSignedUpTo=… licensed=…`, and `…debug.LORA` carries `signing` and `roomSignedUpTo`.
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
  (`radioRoomTitle` over `meshRoomChannel`: "Meshtastic (<live board's slot 0, else the newest post's channel>)",
  else "Meshtastic"; the MeshCore room alike), both rooms wear the radio glyph (`RoomAvatar(bridged = true)`, never the
  Knit mark; ADR 2026-09.26q3's 2026-11-18 amendment), the row exists while a board is
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
channel-keyed **text** DM at decode — `Router::perhapsDecode`'s legacy-DM check is the `TEXT_MESSAGE_APP` port's alone,
ADR 2026-09.4n5p's 2026-10-29 amendment), to `onTextPacket` → `onDirectMessage` → `DmAutoReplyPolicy.judge` → one fixed
line (`DmAutoReplyPolicy.TEXT`, 149 B ASCII: unmonitored, nobody reads it, part of a Knit mesh, getknit.app)
queued as `Destination.Reply` — the room's own write with the sender's number in `OutboundFrame.to`, so
`Router::perhapsEncode` PKI-encrypts it to their key. It is the plane's one direct send **without** `want_ack`
(`LinkDestination.Direct(ack = false)`); the compact DM (ADR 2026-10.x9fa) is the other unicast, acked, on the Knit
secondary.

- **Gates, counted by name** (`autoReplyRefusedByReason`): the room's `DEDICATED` (ADR 067) and
  `KNIT_ON_PRIMARY`, plus `NOT_SET_UP` — the bound slot must carry the Knit channel, and an **empty**
  channel table reads as not set up (the opposite of `boundSlotIsKnit`'s reading for Knit's own frames),
  because the setup's confirmation sheet is where the user consented to the board saying it is
  unmonitored. No settings switch, and the room switch does not gate it.
- **Politeness**: `PER_SENDER_MS` = 24 h per node (`SeenSet` of 256, keyed `!hex`), `FLOOR_MS` = 30 s for
  anybody on its own timestamp (never shared with `lastPublicPostAt`), and the window's half ceiling
  (`Ceiling.HALF`, ADR 2026-10.ug4a) asked inside `judge` through its `carried` check, after both caps and before
  either is claimed (`NO_AIR`, never queued, uses up nobody's answer), priced unsigned (a unicast, which 2.8 does not
  sign). The floor is asked **before** the sender is stamped, so a `TOO_SOON` sender's next message still earns the
  reply; a `REPLIED_RECENTLY` one is silence for the day, which is what breaks a
  loop with a neighbour's bot. The per-sender memory persists in `LoraPlaneSnapshot.autoReplied` so the once-a-day cap
  holds across a restart (ADR 2026-09.4n5p's read-once amendment).
- **The board hands each packet to the phone once** (firmware 332b5b0a2). `MeshService::getForPhone` dequeues it
  (`MeshService.h:129`, called from `PhoneAPI::available`, `PhoneAPI.cpp:1738`); `getFromRadio` copies it into the
  `FromRadio` and frees it (`PhoneAPI.cpp:1084-1088`, `releasePhonePacket` at 1191-1196); a session that closes frees a
  packet it had taken and not handed over (`PhoneAPI.cpp:381`), and NimBLE drops the reads it had staged
  (`NimbleBluetooth.cpp:728`), so that one is lost, never re-sent. `toPhoneQueue` belongs to `MeshService`, not the
  session, so what a reconnect brings is only what queued while no phone read it — each packet still once. A DM
  refused `NOT_READY` (the memory not back yet) is therefore never answered; the sender's next one is. The queue is
  bounded (`MAX_RX_TOPHONE`, `mesh-pb-constants.h:28-41`): 8 on classic ESP32 and STM32WL, 16 on nRF52840 and on
  unclassified small parts, 32 on medium-memory parts, RP2040 and ESP32-C3. When it is full, an incoming `PRIVATE_APP`
  packet is dropped, while a `TEXT_MESSAGE_APP` or `ROUTING_APP` one evicts the oldest (`MeshService.cpp:512-526`). Our
  board ACKs a compact DM as it receives it (`RoutingModule.cpp:31`, `sniffReceived`) and only then queues it for the
  phone (`:37`), so an ACKed DM can be lost there, as at a BLE drop; the re-probe on its missing ✓✓ is the repair
  (ADR 2026-10.x9fa).
- **Not on hardware yet.** Needs a stock third node DMing a Knit board; the oracle is `…debug.LORA`'s
  `autoReplyHeard` / `autoReplySent` and the reply on the stock node's screen, PKI (the padlock) and once.
  The rig trap: `FakeMeshtasticLink` is node `1u`, so a test DM `from = 0x1u` is `OWN_BOARD`.

## The MeshCore client (ADR 2026-11.gsqu, Stage 3a/3b)

Built and JVM-tested, reachable from nothing yet: Stage 5 wires it behind `MESHCORE_PLANE`. Everything here is against
the companion firmware at `companion-v1.18.0` (`~/source/meshcore`, `examples/companion_radio/MyMesh.cpp`; the bench
is `~/source/meshcore-spike/FINDINGS.md`).

```
MeshcoreSession (pure, a BoardLink)   handshake · drain · polls · sends and their attempts · pins · setup/restore ·
                                      BoardDialPolicy reconnect, MeshtasticSession's loop
  ├─ MeshcoreProto + Cobs             the companion frames both ways, total; COBS for the compact DM's text
  ├─ MeshcoreAckTable · MeshcorePins · MeshcoreHeadroom · MeshcoreAirRing   pure policies, @Synchronized
  └─ MeshcoreDialer / NusChannel (seam)   one frame per RX write and per TX notify; NusEvent.Frame / Disconnected
       └─ MeshcoreGatt (Stage 3c)     mesh/bluetooth/meshcore/: NUS, MTU ≥ 176, `meshcore-dial`, TX subscribe, NusInbox
            └─ BoardGatt               the GATT core it shares with MeshtasticGatt
```

**The board answers every connected client at once** (`MultiSerialInterface`, bench Q8), and the user's own MeshCore
app may share it. So drained data — codes 7, 8, 16, 17, 27 — is never matched to a request: it becomes a `BoardPacket`
whenever it arrives, whoever's `SYNC_NEXT_MESSAGE` drew it, because the firmware pops a message before it replies.
Answers are matched by content: `SENT` by the ack code the phone computes (`MeshcoreProto.expectedAck`:
`sha256(timestamp ‖ attempt & 3 ‖ text ‖ our key)[0..4]`, `BaseChatMesh.cpp:462`), `SEND_CONFIRMED` by our own table
(`MeshcoreAckTable`), `CHANNEL_INFO` by index, `CONTACT` by key, stats by kind. A bare `OK` / `ERR` may be anybody's:
after a timeout on one a barrier (`GET_DEVICE_TIME` → `CURR_TIME`) drains the strays, every flash write waits at least
3 s, and every write the plane relies on is read back. Where the success answer has content, an `ERR` is provisional:
a barrier follows, and our success arriving before its `CURR_TIME` makes the `ERR` another client's (the firmware answers
in order); two `ERR`s and no success are ambiguous (`ambiguousAnswers`), read as no answer. Another client's `CURR_TIME`
cannot be told from ours, so one landing first ends a barrier early: a known limit, costing a timeout, never a refusal. A room or Public post's bare answer is judged with every `OK` / `ERR` seen before a barrier written straight
after it: exactly one is ours, anything else reads as a timeout, never a refusal (`MeshcoreCounters.strayAnswers`); a
stray `TABLE_FULL` anywhere holds the headroom. The one write refuses any frame outside `ENCODED_COMMANDS`. What nobody waited on is counted (`MeshcoreCounters.unexpected`)
and dropped; every frame handler is guarded, so a bad frame never ends the actor.

- **Handshake.** `DEVICE_QUERY [22, 3]`, refused below `FIRMWARE_VER_CODE` 11 (companion v1.15.0, the first with
  commands 54, 62 and 64: `MeshcoreLinkState.FirmwareTooOld`); `APP_START`; every channel slot, keeping only (index,
  empty, is-Knit); `SET_DEVICE_TIME` forward (an `ERR ILLEGAL_ARG` is the board's clock ahead, benign);
  `SET_FLOOD_SCOPE_KEY [54, 0]`; the default scope read as a flag; CORE / PACKETS / RADIO stats and the battery
  (millivolts only). `SELF_INFO`'s radio must be sane (the firmware's own `constrain` bounds) or `Ready.radio` is null.
- **Drain.** At Ready, on `MSG_WAITING` and on every stats poll (a reply or a push lost to the board's four-frame BLE
  queue, `SerialBLEInterface.cpp`, is picked up there). Lossy by design: a lost reply is gone (Q3). A round reads at most
16 replies, then the loop serves one waiting command (the inbox is also selected first), so a busy band never starves a
send or a retry; every `call` has a deadline on the wait for the actor to start it, never on a started op, which always
returns its own result (a send's handle is taken before it is posted). A send its deadline caught unstarted is `Busy`,
which the core requeues; `Timeout` is a started attempt whose `SENT` never came. The deadline claims and answers in one
step and the actor's refusal skips what it claimed (`SessionOp`). Drained packets wait for their one collector,
which subscribes before `start`, in a 1,024-packet backlog that drops its oldest past that (`packetsDropped`). The first `NO_MORE` of
  a connection makes what follows `MeshcorePacket.live`; before it is the offline queue replayed, whose frames were
  composed at reception and may be V1 (7, 8) if no client had asked for v3. Nothing drained is discarded: the user's own
  DMs, other slots and Public all become packets, typed (`MeshcorePacket.Type`) with the raw `path_len`.
- **Polls.** CORE `outbound_queue` every 1 s while anything is queued or in flight, 60 s otherwise; every reading is
  emitted unconflated. `TABLE_FULL` holds headroom at 0 until a poll shows the queue fell or `sent` rose
  (`MeshcoreHeadroom`; the 16-packet pool is shared with received packets), or a poll reads an empty queue, or two polls
have held it. RADIO stats feed `BoardAir` over a ≥ 60 s ring
  (`MeshcoreAirRing`, which also restarts on the same poll's CORE uptime going backwards). CORE uptime going backwards
  is a reboot: the ack table is dropped.
- **A compact DM** is `SEND_TXT_MSG`, `TXT_TYPE_PLAIN`, text `COBS(seal)` ≤ 157 B (seal ≤ 156), sent on the route the
  board holds for the contact (ADR 2026-11.gsqu's 2026-11-19 amendment: no pin). A route this connection has not read is
  read first; one that reads UNKNOWN is written to zero hops before attempt 0 (`writeZeroHop`, the Knit record at
  `out_path_len 0`). No key for the prefix, or a read or write that fails, is refused unwritten
  (`MeshcoreRefusal.NO_RECORD` / `ROUTE_UNWRITTEN`, both `RefusalClass.OWN_NO_KEY`). Attempts 0..2 keep the timestamp and
  change `attempt` (a same-attempt retry is byte-identical and dropped unanswered, Q14), each after the firmware's
  `est_timeout` (the route's: a flood's `500 + 16 × air`, a path's `500 + (6 × air + 250) × (hops + 1)`) plus 1 s; a
  confirmation of any attempt ends the flight as `Acked(VALID, flown = n)`, a give-up `Unreachable(flown)`, a dropped
  link `NoOutcome(flown)`. A flight never outlives `MeshcoreSession.MAX_FLIGHT_MS`; the core's deadline is a backstop
  above it. The board's 8-code ack table is circular and filled by every client's sends: `Busy` while eight codes are
  unconfirmed inside their window; a confirmation after `Unreachable` is a late `Acked` under the same handle.
- **The ladder** (`nextRung`, once per unanswered attempt, `Flight.routedFor`): a key no longer taught ends the flight
  `NoOutcome` (`meshcore direct flight ended: no longer taught`); the record is read back, then
  - N > 0 hops or UNKNOWN, zero hops not yet written this flight: zero hops (`writeZeroHop`, once a flight, never on a `0x81`);
  - zero hops with fewer zero-hop attempts than the flight's two (one for a flight started on a learned path): again;
  - otherwise, before any flood: the core asked whether the DM is still wanted (`floodWanted` → `directStillWanted`: link,
    spool cover, DMs on, still a direct board holder, a person's DM still without its ✓✓; `meshcore direct flood skipped: no
    longer wanted`, `NoOutcome`),
    then `RESET_PATH` read back UNKNOWN (`meshcore route reset`, `meshcoreRouteResets`), and the next attempt floods
    (`meshcore direct flooded`, `meshcoreDirectFlooded`);
  - after the flight's one flood: one more `est_timeout` and the end (`meshcore direct flood unanswered`); a retry only along
    a path a `0x81` named since this flight's reset, while attempts remain. Never a second flood.
  - Each zero-hop write and reset spends one of the key's 24 a UTC day (`MeshcorePins.spendLadderWrite`); past them the
    attempt goes on a known route as read, and on an UNKNOWN one not at all (`ROUTE_BUDGET_SPENT`, `RefusalClass.OTHER`: never a flood
    before zero hops, never a teach; `meshcore ladder write deferred: …`, `meshcoreLadderWritesDeferred`).
  - A path a `0x81` named since the reset, read back at N > 0 hops, is tried once before the next rung writes over it.
  Each write runs inside `await`, which dispatches a late `SEND_CONFIRMED` inline: the flight is asked for again after it,
  so a confirm during the reset stops the flood. A direct attempt's hops come back out of its `est_timeout`
  (`MeshcoreTraits.directHopsOf`; `meshcore direct hops=N`, the gauge `meshcoreRouteHops`) and its surcharge over the
  zero-hop price is reported (`MeshcoreAir.DirectRouted`); a confirmed flood owes the reciprocal PATH our board sent
  (`MeshcoreAir.ReciprocalPath`, at the route's length once it is read).
- **Route reads and re-teaches.** A `0x81` for a key Knit taught queues one read of its record (`MeshcorePins.Job.READ`,
  `meshcoreRouteReads`): the cached route is forgotten until it lands, and the firmware's learned route then cached and kept,
  never written; a send reads the record whenever the cache is empty or UNKNOWN. A `0x8F` forgets the route and
  queues one re-teach a minute (`RETEACH_GAP_MS`). A push for any other key is ignored. The cache (`MeshcorePins.routeOf`)
  empties at every Ready and refills from the Ready's listing; one job per tick under sends and the drain.
- **Contacts.** `teach` writes a `Knit xxxx` (from the key), favourite, zero-hop contact only into a confirmed free slot
  (`GET_CONTACTS` past every `lastmod` is the count read), so overwrite-oldest never evicts the user's own; `readContact`,
  `removeContact`, `resetPath` read back what they did.
- **Setup and restore** (`MeshcoreSetupRecord`). Reads first; then auto-add (chat and overwrite-oldest off), `[38, 1]`,
  the `Knit abcd` name (only on a board this phone holds no record for; a re-setup keeps the board's name) and the channel,
  each read back, the channel last; a failure puts back what it wrote. Restore puts back each value only where the board
  still shows Knit's, and reports the rest (`leftAsFound`): a handle the user chose stays. `rename` writes
  `SET_ADVERT_NAME` alone, behind the set-up gate, read back, and never touches the record (ADR 2026-11.f4d3). The radio, the PIN and
  the keys are never touched; the encoder cannot even write them (`MeshcoreProto.ENCODED_COMMANDS`, pinned).
- **Room posts** are `SEND_CHANNEL_DATA` on the bound slot, flooded, `data_type` `0xFFFF` (`DATA_TYPE_DEV`) until
  MeshCore registers Knit's; the slot is re-read when its last read is over 60 s old and refused when it is not Knit's.
  Its `NOT_FOUND` is a slot the board no longer has (`SLOT_NOT_KNIT`, the reading dropped), never a contact.
  A reading never outlives its connection: it is cleared at every handshake and session end, and set only for a slot
  read as Knit's.
  A Public post's text is refused past `160 − (name + 2)` bytes, where the firmware would trim it and answer OK.
- **The fake.** `FakeMeshcoreAir` runs the command handler per board and an air of neighbours and repeaters;
  `MeshcoreFakeVsSimTest` replays the simulator runs of Q11–Q15 into it and checks its answers and airings node by node.
  Nothing in it models the BLE side (Q8's 173-B notify cut, the four-frame queue) beyond `FakeNusChannel.dropFrame`.
  **The channel** (opt-in, `FakeMeshcoreAir(channel = MeshcoreChannel(…))`, Stage 5c-2a) makes the air the firmware's:
  - each airing takes the fake's own Semtech airtime (`MeshcoreChannel.airtimeMs`, 32-symbol preamble at SF ≤ 8: 633 ms
    for 181 B at SF7 / 62.5, Q5's measurement) and lands at its end; a receiver decodes one packet at a time, an overlap
    corrupts both (`FakeRadio.collisions`; a per-link `capture` alone keeps the first, radio behaviour the source is silent
    on) and the radio stays busy to its CAD, and what arrives while it transmits is lost (`deaf`); a scoped flood carries
    its 4 B of transport codes;
  - a radio airs one packet at a time from a priority queue (soonest eligible, lowest priority, first in), under the
    50 % budget over an hour and listen-before-talk (200-ms CAD retries, forced after 4 s; `Dispatcher.cpp`); a
    `rogue` radio skips both;
  - a repeater (pool 32) re-airs a flood `nextInt(0, 5·t + 1)` after the end of reception,
    `t` = airtime(path + payload + 2) × `tx_delay_factor` (0.5), priority by hop count, and forwards a direct packet whose next hop it is after the
    `direct_tx_delay_factor` (0.3) draw, stripping itself (`simple_repeater/MyMesh.cpp:547-553`, `Mesh.cpp:78-108`); every
    other node drops that packet, the destination included, and an ack is offered to whoever hears it on the way;
    `rx_delay_base` above 0 delays a flood by `calcRxDelay` (under 50 ms ignored, 32 s cap). Each draw is
    `Random(mix(runSeed, repeater, origin, n))`, logged in `draws` and on the airing;
  - every node keeps a 160-entry ring of SHA-256(type + payload), path excluded, cleared on reboot (`evictRing` for tests);
  - contacts keep `out_path`; through a repeater a direct DM lands reported `0xFF`; a flooded DM draws the flooded PATH
    return, the sender's reciprocal direct PATH, and `0x81` on each board when a PATH reaches it; a flooded matched ack
    draws one direct PATH 3 s later while the sender's record is pinned (the Q13 PATH, `BaseChatMesh.cpp:372-390`);
  - boards: `powered` (off: deaf, mute, the link dropped, a dial times out after its window; on: `reboot()`, which clears
    the offline queue, the ack table and the ring and keeps contacts), the offline cap at the source's 16, an RTC that
    ticks with the air's clock, `tx_air` in ms for every airing, `lossy(from, to)`, `hold` / `release`, synchronous
    `listeners`, `pathHashMode`.
  `quickMeshcoreAir` is the Stage 3b air with its figures ÷ 10 for the lab's ordinary cases. Given a `serial` context
  (`limitedParallelism(1)` on a scope the caller owns), every write, delivery and timer runs on it and `snapshot` reads
  there; `airings` is copy-on-write. `MeshcoreAirRig` runs the production stack (session, hooks, transport) per node over it.
- **The GATT client (Stage 3c).** `MeshcoreGatt` dials through the `BoardGatt` core `MeshtasticGatt` uses, with its own
  parameters: the Nordic UART service (`MeshcoreUuids`: RX `…02` written, TX `…03` notified, one companion frame each,
  no framing byte), a 176-B MTU floor (`NusLinkPolicy.MIN_MTU`, the firmware's `MAX_FRAME_SIZE` and an ESP32 board's
  cap, so a notify carries at most 173 B), its own arbiter tag `meshcore-dial`, and the `MeshcoreGatt` log tag. The dial
  subscribes to TX before it opens. A write longer than `mtu − 3` is refused (`STATUS_TOO_LONG`), never sent as a long
  write, and the session reads that as a refusal of the one command (`CommandRefused`: a send answers `TooLarge`,
  `commandsRefused`), never a session end; it refuses anything over `MAX_NOTIFY` itself first, which no encoder emits; every write is `WRITE_TYPE_DEFAULT`. Notifies land in a `NusInbox`: an unlimited channel bounded at 1,024 frames
  or 256 KiB held, past which the connection ends under `STATUS_OVERFLOW` (counted in `inboxOverflows`), nothing
  dropped. Pairing (`ESP_LE_AUTH_REQ_SC_MITM_BOND`, static passkey 123456 or the board's screen; Q8's random-PIN trap)
  is the system's, at the first protected op: on an ESP32 the session's first RX write, under `BONDING_TIMEOUT_MS`; if a
  board's CCCD needs encryption, the TX subscribe, given the same 90 s with the `meshcore-dial` hold released, and read
  the same way — a bond refused, or a link closed with the board still unbonded (read settled, `BondSettle`), is
  `NusDialResult.BondRefused`, terminal. No bond receiver (the bond is polled),
  and an unbonded board is never dialled in the background. Nothing constructs it before Stage 5.

### MeshCore's price, band and law (ADR 2026-11.gsqu's Stage 5a-1 amendment)

Pure and JVM-tested; Stage 5a-2 wires them (below). `MeshcoreTraits` prices by purpose what `companion-v1.18.0` puts on the air:
header, the 4 B of transport codes on a scoped **flood** only (a room post, a public post, a PATH return; never a
zero-hop DM or an ack), path length, the hashes, the 2-B MAC, and the plaintext zero-padded to the 16-B AES block (a room
post's 3-B data header, a text's 5-B timestamp and flags, a DM's COBS byte). The time is the LoRa formula at the
firmware's own preamble — 32 symbols at SF ≤ 8, 16 above (`RadioLibWrappers.h:56`) — which is all of Q5's gap (633.3 ms
for 181 B at SF7 / BW 62.5 against 633 ± 17 measured), plus `FIXED_MS` (17, the measurement's sigma) per packet. It
never carries Meshtastic's signing facts, falls back to the companion's build default (869.618 MHz, BW 62.5, SF8), and
sizes the compact DM at 3 airings, 2 flights (the board's 8 ack codes over 3) and a deadline above
`MeshcoreSession.MAX_FLIGHT_MS`.

The firmware enforces no regional duty (a 50 % token bucket, Q4), so `LoraBand` gives the duty from the frequency and
bandwidth (the whole channel inside a row; the strictest row it is in; `STRICTEST_PERCENT`, 0.1 %, when none holds it,
and before any band is known), the allowance reads it, and `MeshcoreDutyLaw` holds every Knit frame while the board's
own trailing-hour `tx_air` — every client's sends, our acks and PATH returns, any repeats — reaches it. The law's clock
is the board's uptime, so it survives an app restart (its ring is the state to persist), not a board reboot (a sample
whose uptime or counter fell, or whose boot time moved, starts it again, the old boot's hour and the unseen stretch up to the
reboot carried on the wall clock as a pre-boot term for an hour); with no baseline an hour old it bounds the hour
from the counter since boot, so a restart never hands out a fresh hour. The brake (`MeshcoreAirRing`) is Δ(`rx_air` +
`tx_air`) over ≥ 60 s at 40 %. Each EU duty assumes its row's power limit (25 mW, 500 mW at 869.4–869.65), the board's power being
the user's; on a 0.1 % or unmatched band the plane is effectively dead, by design; `rx_air` counts only packets the firmware parsed, and a client-repeat board inflates both.

### The MeshCore plane in the core (ADR 2026-11.gsqu's Stage 5a-2 amendment)

Debug builds only (`MESHCORE_PLANE`): `di/LoraWiring.loraPlaneOf` builds each kind's plane in one call, and MeshCore's is
`MeshcoreSession` over `MeshcoreGatt` with `MeshcoreTraits` and `MeshcoreHooks` (`meshcorePlane`), injected into the core as a
`BoardPlane`. A release build constructs no MeshCore child (`builtBoardKinds`).

- **What is Knit's.** The board is Knit's alone. `MeshcoreHooks.route` reads the slot and the data type before a byte is
  decoded (the channel key is public): channel data on the board's own Knit slot under `KNIT_DATA_TYPE` is a Knit frame, a
  contact DM in a COBS-decoded `0x11` seal's shape is a compact DM, a text on Public slot 0 is the kind's (Stage 6, "MeshCore's
  Public channel" below), and everything else — a foreign DM, another slot or data type, channel data on Public slot 0, a
  `0x04` fragment that does not parse or names more than two parts (since Stage 5c-1,
  `meshcoreFragmentsRefused`) — is counted and discarded as `PacketRoute.DROPPED`: never a hearing, never stored, logged,
  shown, relayed or in custody.
- **Our board's answers**, booked from one place (`route`), for every **live** plain or signed-plain contact DM, Knit's or not
  (`companion-v1.18.0`: a 6-B ack code for plain, 4 for signed): for a direct one, along the route our record of the sender
  holds as last read (`MeshcorePacket.senderRoute`) at most one multi-ack and the ack, each carrying the path's bytes
  (`sendAckTo` sends one multi-ack whatever `multi_acks` reads), one flooded ack on an UNKNOWN record, the larger of the two
  at zero hops with no read; for a flooded one one PATH return at `pathReturnPlaintext(pathLen, txtType)` (its hash size
  included); nothing for one replayed from the offline queue. A DM of ours on a route other than zero hops
  (`MeshcoreAir.DirectRouted`) books only what the route adds — a flood's transport codes, a path's bytes, the larger of the
  two for an attempt with no `SENT` — and a reciprocal PATH (`MeshcoreAir.ReciprocalPath`) books whole, all through
  `LoraAirtime.recordMs` under `Ceiling.FULL`; never a repeater's re-airing (ADR 2026-11.gsqu's 2026-11-19 amendment, the
  user's decision; `meshcorePathSurcharges`, `meshcoreReciprocalsBooked`).
- **Hearing.** `BoardTraits.live` (any path; the cover's lift), `heardRoute` (MeshCore: a live drain at any hop count, what
  lifts a mark since ADR 2026-11.gsqu's 2026-11-19 amendment) and `heardZeroHop` (a live drain at zero hops; a direct DM only
  by its own flood path length, never our record of its sender); `hearsSender` false, so `noteHeard`
  and `noteBoard` never run on a MeshCore sender. A room post's author is heard only when the post is live, its `sentAt`
  within `-MAX_FUTURE_SKEW_MS..FRESH_MS`, and its signature verifies against the author's pinned key (`LoraAuthorCheck`,
  `pinnedAuthorCheck`): at any hop count a board holder marked unreachable gets `onBoardHeard` — through repeaters at most
  once a re-probe gap (`LoraDirectPolicy.onHeardFar`) — and at zero hops its radio
  is counted by author (`AuthorRadio`). An opened DM hears its board at any hop count, fresh (the same window), and since Stage 5c-2a it only lifts a mark: it never counts a radio, since a DM along a learned path lands `0xFF` too. **The accepted cost:** two MeshCore users who only DM each other read "0 other radios in range" (`LoraRadioScreen`), because `boardsHeardAt` is fed by verified zero-hop room posts alone; a DM could count once a zero-hop proof exists (Stage 5d or later). So on MeshCore `heard` (fresh-frame authors,
  any hop count, unverified) and `boardsHeard` (verified zero-hop authors, their keys pinned) read differently from Meshtastic: when the
  airtime row reads 100 %, compare `boardsHeard` with the dump's `meshcoreZeroHopKnitPosts` (live zero-hop Knit **packets**,
  unverified: a two-part post counts 2 since Stage 5c-1) rather than with `heard`. Every room post's hearing, refused or
  not, ends in `loraHearingsJudged` (Stage 8): a dump where it climbs while `meshcoreZeroHopKnitPosts` stands is Knit posts
  refused as hearings at once — through a repeater, out of the offline queue, or stale.
- **The law.** Before Ready is published the session tells the hooks (`MeshcoreSessionObserver.beforeReady`), which put the
  board's radio and its band's duty into the ledger (`LoraAirtime.onRadio`), or `forgetRadio` with no sane radio — never the
  last board's. Every poll's CORE uptime and RADIO `tx_air` feed `MeshcoreDutyLaw`; while its verdict holds, the pacer holds
  every frame until `releaseInSecs` (`LoraPacePolicy.holdForLaw`, a floor on `nextDueAt`, never a tick), logged
  `lora law hold` / `lora law clear` (the pacer told only of a change), shown as `LoraFacts.lawHeld` (the saturated notices).
  The rings are persisted in `meshcore_plane_state` (`LoraPlaneSnapshot.meshcoreLaws`, the last four boards) under a SHA-256
  digest of each board's key and applied when that board identifies. A board with no ring is bounded from the counter since
  boot: up to an hour's hold at a first connection on a 1 % band. The brake reads the board's
  `MeshcoreAirRing` through `boardAir`, as Meshtastic's reads its telemetry.
- **Dialling.** An unbonded board is never dialled in the background's place: after its three direct dials it waits out the
  background window (its bond re-read each minute), so the hourly direct net is its only dial. A board without NUS twice in a
  row is terminal (`NusDialResult.ServiceAbsent`: `StaleBond` bonded, `NeedsPairing` not).
- **The door** (5b-2's second part): the plane is fed `meshcoreBoardMap` and `bindMeshcoreDirect` is bound before start; the
  setup record is `meshcore_setup` under SQLCipher, and it is the consent the session touches a board under. A room post
  takes up to two elided packets since Stage 5c-1 (the next section).
- **Face.** `LoraFacts.floodSpent` (the room's saturated notice at 90 % of the flood share), `roomBodyBytes` (200 B on
  MeshCore in two elided packets since Stage 5c-1, 40 B in one `0x05` before; pinned by `CoordinationPlaneSizeBudgetTest`)
  and `directReach` (the acked boards a MeshCore DM notice reads,
  never the flood-fed `reachable`; a zero-hop hearing never marks one); the version string is cleaned before anything shows it. The dump is
  `…debug.LORA --es kind meshcore` (`debug-bridge.md`).

### MeshCore's room posts and the reassembler (ADR 2026-11.gsqu's Stage 5c-1 amendment)

- **Out.** An own room post rides the elided form (`BoardTraits.elidesRoom`: `LoraFrameCodec.encodeBest(elide = true,
  bind = true)` → `FastFrameCodec.encodeElided`, the sender a 4-byte prefix; a frame the elision cannot reproduce keeps
  `0x05`/`0x03`, logged `lora elide refused <label>`). Past one 164-B packet it is split into the **bound** form (`0x07`,
  `fragmentBound`), **bound both ways** (the maintainer's decision, 2026-11-14): each part carries 152 B of the frame and the
  first 8 bytes of SHA-256 over the other part's slice. A bound part is never padded. Each part is booked under
  `Ceiling.FLOOD`; a frame past two bound parts is `lora room too long … parts=3` and never airs. Measured with random
  printable ASCII (2026-11-14): 57 B of text in one packet, 197 B in two; the composer's budget is 184 B, cut to 50 B or 0
  where the band's flood ceiling admits only one or no full packet (`AirtimeSnapshot.roomPacketsFit`,
  `LoraSizeHint.roomBodyBytes(kind, packetsFit)`). Every fragmented frame draws a fresh id (`fragIds`, `SecureRandom`);
  Meshtastic keeps its seeded counter.
- **In.** On a kind that elides, `onFramePacket` reads through `readVerified`: a whole `0x03`/`0x05` frame as ever, a whole
  `0x06` resolved and verified, a bound part through `LoraFragCandidates`, and never an unbound `0x04` (`route` refuses it,
  `meshcoreFragmentsRefused`). **Arrival order is not trusted:** behind a MeshCore repeater a forger's short part routinely
  lands before the honest one, since the repeater holds a packet a random `0..5·t` (`t` = airtime × `tx_delay_factor`, up to
  2.0; `simple_repeater/MyMesh.cpp:547`, `:893`). So a head and a tail pair only when each names the other, one
  verification a matched pair; a fake costs memory, never a check. Each side of an entry (heads, and tails waiting for
  their head) keeps `LoraFragCandidates.perPartFor` parts — what this receiver can decode within the whole 60-s window,
  60 s ÷ 197 ms + 1 = **306** at the companion default (SF8 / 62.5), `capacityFor`'s figure off the shortest part's least
  airtime (`LoraAirtime.leastRoomAirMs`: 21 B unscoped at CR 4/5 behind a 6-symbol preamble, no `FIXED_MS`, an assumption a
  board owes; the booked 341 ms made it 177, under the 197 parts one firmware-preamble radio lands, `MeshcoreAirPhysicsTest`) — so within the window no radio
  input fills a side, however long the firmware held the honest part (the score delay `calcRxDelay`, up to 32 s; CAD
  retries; `0..5·tx_delay_factor` airtimes a hop, which made a repeat-hold window the wrong measure). Past it a newcomer
  replaces a random waiting one (`SecureRandom`, `FRAG_CANDIDATES_FULL`). A pairing leaves the entry verified or
  not, so two authors under one id each pair their own. Every part ages out on its own arrival. The store holds at most
  `capacityFor` entries (the 60-s window over the shortest bound part's least 197 ms: 306) and `MAX_BYTES` = 256 KiB of slices,
  evicting the oldest past either (`FRAG_OVERFLOW`). The collector suspends on the check.
- **Resolution.** An elided sender prefix resolves against `LoraAuthorIds`: the direct contacts first, every other peer whose
  key the table holds only when no contact's candidate verifies (`di/loraAuthorIds`, node ids only, kept across a board
  change). At most 8 resolutions a pairing (4 candidates × 2 tiers), each a `PeerRepository.find` and an Ed25519 verify. A
  stranger's post never resolves; Meshtastic never reads `0x06` or `0x07` (`UNKNOWN_TAG`) and keeps its sender-keyed
  `FragReassembler`.
- **Presence.** On MeshCore a frame is `reachable` presence only once its signature verifies (a whole `0x03`/`0x05` is
  checked for it): a stranger cannot make an id look LoRa-reachable.
- **Counted** as `FastPathDrop`s, on the dump's `meshcoreFrag*` keys and `fastDrop_*`: `FRAG_TIMEOUT`, `FRAG_OVERFLOW`,
  `FRAG_CANDIDATES_FULL` (a waiting part replaced past its side's cap: forgers aiming at an observed id),
  `FRAG_UNVERIFIED` (a pairing that did not verify: a stranger, a forger's own pair), `ELIDED_UNVERIFIED`.
- **The form is to agree with iOS** before MeshCore reaches iOS (`docs/WIRE_COMPAT.md`'s `0x07` precedent, vectors
  `lora-frag-v1.json`); an author tag would not close the race the binding does (`docs/NEXT_WIRE_BREAK.md` item 16).

### MeshCore's contacts (ADR 2026-11.gsqu's Stage 5b-2 amendment)

The board is Knit's, so its contact table is the taught set. The hooks decide it (`MeshcoreContacts.wanted`) from each board
map the core hands over (`BoardKindHooks.onBoardMap`; `MeshcoreContacts.holdersOf` turns `meshcoreBoardMap` into the core's
holders: prefix-addressed, keyed, ranked, `direct` false when contested) and give it to the session
(`setKnitContacts`): only those keys are ever sent to or written (`MeshcorePins.keyFor`, security B1), and a prefix another
key holds is refused (`learn`) or, listed on the board under another key, contested for the connection (`contest`, B3). A
key that leaves the set is removed from the board; a refused DM teaches only a key in the set, once per (board, key) until a
DM to it is acked. A key the set gains while a connection is Ready and no reconcile is pending is written at once (a live
`TEACH` job, Stage 5c-2b: a read of the key, a count read when the Ready took no listing, at most one write): the firmware
drops a DM from a sender it holds no contact for, so a new contact who writes first must not wait for our next Ready or our
own send. It is outside the deletion's once-a-minute re-teach gap (nothing pushed), bounded instead at
`MeshcorePins.MAX_LIVE_TEACHES_PER_DAY` (4) writes a key a UTC day — past it the next Ready's reconcile writes the key, never
dropped — and never for a contested prefix; a failed one is re-queued once. Every write the board took is charged, whatever
its read-back found. Every write of a Knit record (the live teach, the reactive teach, the reconcile's) needs the key taught
and uncontested (`MeshcorePins.keyFor`) before the write and taught after the read-back, and never teaches a key back: one
the hooks dropped meanwhile ends `NOT_FOUND`, so the removal they queued behind it removes it. Oracles:
`meshcoreLiveTeachesWritten` / `…Failed` / `…Deferred`, `meshcoreReconcilesDropped`.

```
Ready ──(taught set known, board set up for Knit: Knit slot + consent)──► GET_CONTACTS since 0  (one a Ready)
   └─ MeshcoreContacts.plan: remove the untaught (≤ 16) → overwrite the changed (≤ 16) → write the missing (room)
        one step a tick; a listed Knit record's route is cached as read, a learned path kept; a short or failed listing removes nothing
setup: reads → erase (REMOVE_CONTACT each, clear every slot but 0 and Knit's) → writes → plan from what is left
restore: remove Knit's contacts (one listing) → slot → name, manual add, autoadd → forget the taught set
```

**Nothing of the user's is touched off a board set up for Knit** (Knit's slot and consent; since ADR 2026-11.f4d3 the
board's name is its owner's handle and no part of the gate, so a stock-app rename no longer hands the board back — only
Restore does): no DM, route write, route read,
teach, path reset or removal (`NO_RECORD` under the send's handle, `ContactWrite.NOT_SET_UP`); only setup's erase and
Restore's own removals bypass it. The gate also needs this phone's `meshcore_setup` record for the dialled address **and that board's key digest**, not
pending, on a **bonded** link — the single source of consent, owned by the session's actor: read in the handshake before
Ready (a read that keeps failing is no consent, `consentReadFailures`), moved only inside its own setup, Restore and unbind
(durable write first), observed by the hooks (`MeshcoreBoardOps.consent`) for the offers and the profile's claim; Restore
is refused on another board or an unbonded one (`Mismatch`), and an unbonded board is inert (`unbondedInert`) (a board's slot and name are a
claim any client can write); an unbind with the board away leaves the record pending, and its Restore runs at that board's
next connection (`lora cleanup kind=meshcore …`). Our board's ack of a DM from a taught, pinned sender is booked along its
path alone. A listing's
contests stand until the next whole listing on the same board of ours (a rebind clears them); a listing ends short past its
count + 8 or 60 s. The plane's board map is `meshcoreBoardMap`, whose first emission is the stores' real state, never a
placeholder: an empty map would be a taught set of nothing, and the reconcile would empty the board. Consent also needs the
board to **sign a fresh challenge** with the key it reports (`knit-meshcore-consent/v1` ‖ 32 random bytes, `SIGN_START` /
`SIGN_DATA` / `SIGN_FINISH`, `keyUnproven`), asked again before a Restore, a cleanup or a setup writes; the session's actor
is the record's only writer while the board is up (an unbind waits for it, under `consentLock`), and an unbind with the board
away withdraws the consent at once — for the plane's bound board only; another address's record is only marked pending. The
consent publishes the key the board proved (`provenKey`), an unanswered proof is asked again later (`proofUnanswered`), and
every record read or write on the actor is bounded at 2 s. The firmware rewrites a
contact's name and type on every advert it hears and its `out_path` on every PATH (`BaseChatMesh.cpp:191-193`, `:357`), so a
taught peer whose board is not named `Knit xxxx` is overwritten again at each Ready, and Restore counts it Knit's only while
taught.

The local count is the listing's, kept as writes and removals read back and on `0x8F`; a teach with no listing taken counts
once. Never `CMD_FACTORY_RESET`. Oracles: `meshcore reconcile listed=N Plan(remove=…, overwrite=…, teach=…, verified=…,
contested=…, deferred=…)`, `lora setup kind=meshcore slot=S erased contacts=N slots=M`, `lora restore kind=meshcore
contacts=N …` — counts only, never a key, a prefix or a name.

#### The bind: the board signs the sealed profile's proof (ADR 2026-11.gsqu's Stage 5d amendment)

The sealed profile's `meshcoreKey` goes with `meshcoreSig`, the board's own Ed25519 signature over
`MeshcoreBind.message` — `knit-meshcore-bind/v1` ‖ our 16-byte node id ‖ the board's 32-byte key — which every receiver
checks against the frame's authenticated sender. The session's actor asks for it (`MeshcoreSession.bind`, the same three
sign commands as the consent proof, its own domain) right after every proof that **grants** consent — the handshake's
(under `consentLock`, before the Ready), a setup's, a consent re-read's — and checks the answer with
`MeshcoreBind.verifies` (the key first, then `S < L`, then Tink). The signature rides `MeshcoreConsent.sig`, published by the
actor with the key it was made over, so the hooks claim `(key, sig)` off the consent alone and a withdrawal is never
overtaken by a late bind; a pending cleanup, a Restore and every withdrawal never sign. Every such proof signs again
(determinism: one board, one (node, key), one signature), so a reconnect writes nothing and mints nothing; three commands a
proof, no air. A bind that fails publishes the consent without the signature (the claim store keeps a proof it holds for the
same key) and is owed on its own backoff, 10 s to 10 min, as an idle job (`rebindIfOwed`) — re-published with the consent
on success, dropped once the connection no longer consents. A signature that does not verify is read as a refused proof is:
counted (`meshcoreBindRefused`) and `SELF_INFO` read once, never inert, never a withdrawal. No answer, another client's
`SIGN_FINISH` in the shared buffer, or no node id to bind counts `meshcoreBindUnanswered` (one counter; the line says which:
`meshcore bind unanswered` or `meshcore bind skipped`). The node id is
`Identity.nodeId` (`MeshcoreSession(ownNode = …)`); a session built without it binds nothing. `proveKey` refuses a key
`MeshcoreKey` refuses before it asks anything (`keyUnproven`): under a low-order key any challenge would verify. The DI's
claim write (`di/writeMeshcoreClaim`) keeps a stored signature that already proves (our node, the key) rather than write a
fresh one — a `keep` check asked inside the store's own edit (`MeshcoreClaimStore.setBoardKey`), never a read before it —
so a signer that were not deterministic would still mint nothing per reconnect. Oracles: `meshcore bind signed`,
`meshcore bind unanswered: …`, `meshcore bind refused: …`, `meshcore bind skipped: …` — never a key, a prefix or a signature.

### MeshCore's Public channel (ADR 2026-11.gsqu's Stage 6 amendment)

The board is Knit's except slot 0, which setup keeps and the plane mirrors both ways (debug builds, `MESHCORE_PLANE`). This
section is the plane (6a); the room, its rows and its composer are 6b's.

- **Slot 0 as found.** The handshake's slot scan keeps `MeshcoreDetail.publicSlot`: the slot's name (cleaned), whether its
  secret is `MeshcoreProto.PUBLIC_GROUP_PSK` (`MyMesh.cpp:114`), whether it is empty (unnamed, or the null key) or Knit's;
  never the secret. `MeshcoreHooks.statusOf` renders it as `LoraStatus.publicChannel`, and `LoraStatusRepository` reads a
  MeshCore plane's `primaryChannel`, `primaryKeyIsPublic` and `canPost` from it (posting needs the MeshCore room on, the
  plane Live, the board set up with consent, its name postable (`MeshcoreNames.check`), and slot 0 a channel; ADR
  2026-11.f4d3 took the `Knit xxxx` name out of the condition).
- **In.** A `CHANNEL_TEXT` on slot 0 routes `KIND` (no hearing on this kind); `onKindPacket` counts it
  (`meshcorePublicHeard`), drops it while `meshcoreRoomEnabled` is off (`…RoomOff`, the plane's own config), judges it
  (`MeshcorePublicJudge`: the companion's `"%s: "` form, `BaseChatMesh.cpp:518-531`; split at the first `": "` behind
  1–31 B) and launches `onPublicPost` with a `MeshcorePublicPost` (name or null, body, `PublicPostKey` of the sender's stamp
  and the raw text, `live`). Replays from the board's 16-frame offline queue are delivered. Data on slot 0 stays discarded.
- **Out.** `postPublic(body, consented)` (the transport's; `postToPublicChannel` carries no consented name and always
  answers `NAME_CHANGED` on MeshCore) refuses `ROOM_OFF`, `NOT_READY`, `NOT_SET_UP`, `NAME_UNPOSTABLE`, `NAME_CHANGED` (the
  composer's consented handle is not the board's name now), `NO_CHANNEL` / `KNIT_ON_PRIMARY`, `TOO_SOON` (30 s),
  `TOO_LARGE` (`publicTextCap`: 160 B less the name and `": "`, 127 B under a 31-B name, or what the band's flood ceiling
  can never carry), `NO_AIR`, in that order; then queues one channel text under `Ceiling.FLOOD` with the wall clock's
  seconds as its stamp (`OutboundFrame.timestampSecs`) and the name's bytes (`OutboundFrame.publicName`), and answers
  `PublicPostResult.Queued` with the post as receivers read it (the name's own bytes + `": "` + the text, never through a
  `String`). At the write the session reads `SELF_INFO` and slot 0 again and refuses a board no longer Knit's, a name no
  longer the frame's or no longer postable, or a slot no longer a channel (`MeshcoreRefusal.NOT_SET_UP` / `NAME_CHANGED` /
  `NAME_UNPOSTABLE` / `NO_PUBLIC_SLOT`). That read and the write are two round trips: a client on the board's other
  interface can still rename between them, which the firmware gives no way to close. A `Busy` retry writes the same
  stamp, and the refusal republishes the Ready so the face and the enqueue gate follow. A post older than `FRESH_MS` past
  its stamp is never written (`RideGate.freshUntil`, set at enqueue). Every end without airing is reported by key on
  `LoraMeshTransport.publicPostsEnded` (`PublicPostEnded`: `STALE` and `REFUSED_AT_WRITE`, which never aired and read "Not
  sent"; `ENDED_AT_WRITE`, a write with no answer, which may have). Priced from the frame's own name
  (`OutboundFrame.price` for `Destination.Public`, so admission and booking agree): the prefix is the name + 2 B, and the
  largest post is always 185 B on air, about 1.16 s at SF8 / BW 62.5, under any name. With no name known it is priced as
  under a 31-B one.
- **Oracles.** The MeshCore dump's `meshcorePublicHeard` / `…RoomOff` / `…Refused` / `…Delivered` / `…PostsQueued` /
  `…PostsRefused` / `…PostsSent` / `…PostsEnded`; the lines `lora public post refused: <reason>` (at the enqueue),
  `lora public post refused at the write` (Knit's gate, the encoder or the firmware refused it: not set up any more, a
  name changed or unpostable, no slot), `lora public post ended at the write` (unconfirmed), `lora public post stale at the
  write` and `lora tx public <n>B` (sizes and
  reasons only: no name, text or stamp is ever logged).
- **Trial recipe** (two V4s at companion v1.18.0, a stock MeshCore client on a third device; not run). Set up board A in
  Knit and enable the MeshCore room. From the stock client post `hello` on Public: A's dump `meshcorePublicDelivered` +1,
  `meshcorePublicHeard` +1. From Knit post the composer's cap (149 B under `Knit xxxx`, 127 B under a 31-B handle): the
  stock client shows `<name>: <text>` whole, A's `floodUsedMs` rises by about 1.16 s, `lora tx public <cap>B`. Post again within 30 s: `lora public post refused: TOO_SOON`. Rename A from the
  stock client over USB, then post: `meshcorePublicPostsEnded` +1, `lora public post refused at the write`, the row reads
  "Not sent", and nothing on the stock client. `grep` logcat for the
  stock post's words and sender name: zero hits.

### Stage 7: the radio screen's half (ADR 2026-11.gsqu's Stage 7a amendment)

- **The face.** The MeshCore plane's `LoraStatus.meshcore` (`MeshcoreBoardFace`) carries what the radio screen shows of the
  board: the cleaned firmware, the radio (kHz, SF, BW, CR) and the default scope while Ready, the battery in millivolts (MeshCore
  gives no percent and no "powered"), `sharesPosition` as the last `SELF_INFO` for the bound address read it, and the setup
  state off this phone's record (`BoardSetupState`), away too. 7b's view model reads it from `loraPlane(MESHCORE)`'s
  `LoraPlaneStatus` with `getOrNull` (a release build defines none).
- **Position.** Any non-zero `advert_loc_policy`, or location telemetry `ALLOW_ALL` beside base `ALLOW_ALL`: the board airs a
  position Knit never writes. The flag is all that leaves the parser.
- **Preview.** `setupPreview()` on the Ready board: the contact count and the slots in use besides Public and Knit's, each
  null when unanswered. The screen says "about N".
- **Forget.** `MeshcoreForget.forget(anyway)` (Koin single, debug builds): the unbind, then one settings edit a branch behind
  the hooks' claim fence. CLEARED / MISMATCH / FORGOTTEN_PENDING: the binding and claim gone, the plane off. PENDING: the
  binding kept for the next connection's cleanup (`lora cleanup kind=meshcore done=…`), the claim gone now. FAILED: nothing
  written; a settings edit that throws after an unbind that withdrew answers FAILED and owes the claim's withdrawal, retried on
  the claim backoff until it lands (`lora forget kind=meshcore claim withdrawn on retry`). Each logs `lora forget kind=meshcore
  result=… anyway=…`.
- **A different board.** A setup over another key's record answers `ProvisionResult.DifferentBoard(token)` before any write
  (`meshcore setup refused: this phone set up another board at that address`, `meshcoreDifferentBoards`);
  `provisionReplacing(token)` replaces it only while that record is unchanged.
- **Wrong firmware.** `NeedsPairing` / `StaleBond` carry `PairingCause.NO_SERVICE` after two dials with no service of the
  kind: wrong firmware, or a GATT cache from before a reflash; the phone cannot tell them apart.
- **The raw send.** `…debug.LORATX --es kind meshcore --es text <s>`: one `Destination.Debug` frame through the pacer, priced
  and booked as a room post on the Knit slot, held by the law like any frame, written by the actor as `SendPurpose.Debug`
  (refused at the write without consent). The answer is `result` (`SENT`, `ENDED`, `Stale`, `Queued` past the 45-s wait — under the bridge's `goAsync()` limit of about a minute, so the reply is never lost; the frame is never written past it either, or
  a refusal: `NotReady`, `NoConsent`, `LawHolds`, `NoKnitSlot`, `TooLarge`, `NoAir`, `Busy`, `QueueFull`), `slot`, `bookedMs`
  and `law` (`holding`, `trailingTxSecs`, `limitSecs`); never the text. Lines: `lora debug tx queued <n>B slot=… bookedMs=…`,
  `lora debug tx <outcome>`. A Knit receiver decodes nothing of it; a stock client holding the Knit channel shows nothing
  either (channel data under Knit's data type), so the oracle is the sender's `SENT`, the ledger and the board's own
  `tx_air`.

### Stage 7b: the screen (ADR 2026-11.gsqu's Stage 7b amendment)

- **Which plane.** The view model reads the bound kind's status: MeshCore's while `boundBoardKind` is MESHCORE and the build
  carries the plane, else Meshtastic's. A MeshCore board is pickable only there; picking it binds MeshCore (`bindBoard`).
- **Rows.** Kind, firmware, radio, scope, volts and Knit radios while Ready; the setup state always. Set up at NONE or
  UNKNOWN, Restore at SET_UP, nothing at PENDING.
- **Setup.** The confirmation reads `setupPreview()` as it opens and shows the counts above zero; the erase line always; the
  position line unless `sharesPosition` is false. DifferentBoard opens the replace dialog; its action calls
  `provisionReplacing` with the token. Nothing is written to settings: the record is the plane's.
- **Forget.** Forget, or a pick while a MeshCore board is bound, runs `MeshcoreForget.forget(false)`: PENDING opens Keep it /
  Forget anyway (`forget(true)`), FAILED and MISMATCH show a line. A pick that follows a Forget restores the plane switch.
- **Device check.** On a lab phone with a set-up MeshCore board: the setup line, the volts and the confirmation's position
  line match `…debug.LORA --es kind meshcore`'s `setup`, `batteryMv` and `sharesPosition`; Forget with the board off shows the
  dialog and `lora forget kind=meshcore result=PENDING`; Keep it leaves `bound` true and `claimed` false.

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
  (`airtime` carries `usedMs` against `allowanceMs` — one ledger, ADR 2026-10.ug4a, empty after a restart — with
  `publicCeilingMs`, how far the Meshtastic room and the auto-reply may take the total; `braked` and `boardAirAgeMs`, the
  shared-band brake and how old its reading is; the board's own `boardAirUtilTxPercent` / `boardChannelUtilPercent`;
  and `dedicated`, true when the board is on its own RF slot and so off the politeness ceiling and never braked — ADR
  067; `loraSent − loraDirectSent` is the room-post count,
  since nothing else of ours rides; `loraRoomTooLong` the own posts kept off for needing two packets, `loraReprobed`
  the re-probes, `loraRetiredCtl` an older build's OFFERs heard and dropped, `loraBoardHoldersFailed` the board-holder
  source failing — non-zero, the re-probe may have stopped probing silent holders). `loraAirtimeHeld` beside `queued`
  is what tells a starved plane from a quiet one: a spent window *holds* frames rather than dropping them, so no counter
  moves until the queue overflows and the drops all arrive at once; `loraBandBraked` is the room posts the brake held,
  apart from it. It is the
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
- DM (ADR 2026-10.x9fa): `…debug.SEND --es conv <peerId> --es text …` on A →
  one `lora tx direct:message:<id> parts=1` on A, `lora direct acked <id> -> <B>` within a few seconds, the DM on B
  (`lora rx direct <id> from <A>`, then `lora direct delivered <id> from <A>`), and A's ✓✓ once B's compact receipt crosses back (`direct:receipt:` on B).
  `…debug.LORA`: `loraDirectSent` / `loraDirectAcked` on A, `loraDirectDelivered` on B, `direct.reachable 1` on A,
  `loraTooBig == 0`. `meshtastic --listen` on a third board shows nothing addressed to it. A DM past one packet counts
  `loraDirectTooLong` and lands later over BLE or the spool. The strand trial: power B's board off, send two DMs from A
  → one airing and its four retries (`airtime.usedMs` rises by five airings at once; `meshtastic --listen` near A shows five transmissions, the give-up about a minute later), `lora direct unreachable`,
  `loraDirectUnreachable 1`, the second `lora direct next … (typed during the failed send` then `lora direct past hold`
  and a second give-up (`loraDirectUnreachable 2`; x9fa's 2026-11-19 amendment); power B's board on and let it air anything →
  `lora direct heard <B>`, then `lora resend` once the gap since the failure has passed (else the next
  `lora reprobe … (+1 after its ack)`), and both land: the older within a minute of the newest, never a gap later (#343). The relay strand (#294), A and B on one relay and both boards, no link: A sends a DM
  and switches its relays off within a second → `lora resend <id> -> <B> (cover lapsed` on A within seconds (or
  `(after ack` behind A's next DM); B logs `lora cover lifted <A>` on the first compact frame, its `internetCovered`
  drops by one and `loraDirectSent` climbs while `loraSkippedInternet` stays flat; every ✓✓ is home in a minute or
  two. A 10-DM chat at under 45 s spacing with relays off keeps `loraDirectResent` at 0. `…debug.LORA --ez dms false` on A → a new DM stays LoRa-silent (`loraDirectSent`
  flat) while a room post crosses. Rejoin a BLE clique after an hour of history: `loraSuppressed` climbs,
  `loraDroppedQueue` stays 0.

- Airtime (ADR 054), the three-phone trial: A + C linked over BLE, B far over LoRa, boards on A and B. (1) A ↔ C
  text 20 messages over BLE → on A `loraSkippedLinked` climbs by ~40 (DMs + ✓✓s), `loraSent` and
  `airtime.usedMs` stay flat, ✓✓ instant, B's board hears nothing. (2) A ↔ B text over LoRa: each ✓✓ lands on A
  within ~45 s or with B's reply; `loraTickDeferred`/`receiptsCoalesced` climb; a burst of three from A yields
  one tick from B. (3) A burst past the window: `airtime.usedMs` reaches `allowanceMs`, the chat notice
  appears on A for B **on the spending send, not a minute later** (the publish-on-send above), the **Nearby
  room** on A carries its own `RoomSaturated` notice for as long as B is LoRa-only, `loraDroppedQueue == 0`,
  the queue drains within 15 min, ticks yield first. Link B over BLE and the room notice goes quiet while
  `airtime.usedMs` stays put — the room speaks about a delayed audience, not about the ledger alone.
  (4) `loraNak == 0` throughout, EU boards included.
- Endpoint (ADR 2026-10.rp3s), the pocket trial: A's board alone; B's board plus a board-less C linked to B over BLE,
  out of BLE/NAN range of A. (1) A posts one packet's worth in the Nearby room → B and C both get it (B's board heard
  it, the pocket flooded it); A's `loraSent` +1. (2) C posts → B gets it over the link, B's `loraSent` and `lora tx`
  lines do not move, A never gets it. (3) Idle 30 min with every phone up: `meshtastic --listen` on A's board over
  USB shows no `PRIVATE_APP` packet from B's board, and `…debug.LORA` on B reads `loraSent` and `airtime.usedMs` flat
  — not the board's own `airUtilTx`, which counts Meshtastic's NodeInfo and telemetry too.

## First-session unknowns to confirm (assumptions, not blockers)

Whether an empty FromRadio read returns immediately or blocks ~20 s (could cut the 30 s read timeout);
`phone_timeout_secs` default (180 s heartbeat is safe either way); whether `queueStatus` is pushed as the
TX queue drains; that `mesh_packet_id` echoes our client id; that PRIVATE_APP packets reach the phone and
our own broadcast is not echoed; the 600 ms bonded post-connect settle (drop if unneeded).
