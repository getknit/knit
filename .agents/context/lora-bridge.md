# LoRa bridge (Meshtastic over BLE) — the long-range Nearby-room + DM plane

How Knit carries **broadcast (Nearby-room) frames and sealed 1:1 DMs** over LoRa via a Meshtastic board
attached over BLE. The design rationale is ADR 038 (the plane) and ADR 039 (DMs); this file is the
operational detail. Off by default, behind `BuildConfig.LORA_PLANE` (debug on, release/staging off,
`-PloraPlane=true|false`).

## Shape

`LoraMeshTransport` (`mesh/lora/`, pure) is a **fast-plane-only** `CompositeMeshTransport` child, added
LAST (lowest send-preference). `neighbors` is always empty, so the flood / custody digest sync / keyreq /
blob pulls never touch the ~1 kbps link — `send`/`sendFile`/`sendDigest` are no-ops. Only
`fastFanout`/`longRangeFanout`/`fastSend` ride it, gated by `LoraFramePolicy`:

- **FANOUT** (`fastFanout` — the composite's coordination-plane blast — and `longRangeFanout` — the seam
  reserved for a plane with no data path, ADR 039; both land in one internal `fanout`): broadcast `chat` +
  broadcast `reaction` (recipientId == null && group == null), `profile`, and **DM-form chat** (`chat &&
  recipientId != null && group == null`, any `relay`). The DM form is admitted opaque: a DM, its sealed
  receipt/reaction, a session reset, a group-key seed and an escalated group tick are wire-indistinguishable
  and all ride. `shouldLongRangeFanout` (`mesh/FrameFanout.kt`) is what feeds it, from `originateSigned` and
  `onDeliver` — never widen `shouldFastFanout` for this; that predicate is the NAN coordination plane's.
  **The recipient gate (ADR 054):** a DM-form frame addressed to us, or to a peer BLE/NAN holds a **live link**
  to (`coveredByLink`, read off `linkedPeers` — links, never sightings), is skipped on this path and on the
  bridge backfill before the sig-dedup slot is spent: the link carries it. Counted `loraSkippedLinked`. The
  originator's `FanoutHint` (`CONTENT`/`TICK`) rides beside the frame — see Pacing.
- **TARGETED** (`fastSend`, unchanged): `receipt`, and `chat && !wire.relay && recipientId == to` (AckSync's
  sealed `CTL_RECEIPT` tick — a flooded DM never rides this path, so no `fastSend` caller can widen it).

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
> The set is keyed on the frame **author**, and a gateway puts other people's frames on air, so a peer
> with no board of its own is routinely in it. Nothing above the composite may read it as proximity:
> `MeshController.neighbors` (the notification count, the Contacts online dot, Diagnostics' *Directly
> connected*) is `CompositeMeshTransport.shortRangeReachable`; the full union is `MeshController.reachable`
> and only Diagnostics' *Reachable via relay* reads it. Writes are gated on
> `mesh/FramePresence.kt`'s `isPresenceEvidence` (shared with the Internet plane, which needed the same
> rule) — 15 min for everything except `profile`, which gets 13 h (the
> 12 h `PROFILE_REPUBLISH_MS` cadence plus slack) — because the ADR 044 backfill, the ADR 039 re-offer
> and `onDeliver`'s re-fan of a frame the **Internet plane** just pulled off a spool all put old frames on
> air, and taking those as presence showed a phone switched off for days as a live neighbour. It cannot
> reuse `isFresh` (which returns true for every non-chat type), and `profile` cannot simply be excluded:
> a board that comes up beacons its profile first, and that first hearing is what fires `reofferTo`. The
> gate wraps **only** `noteReachable` — decode, dedup, delivery, custody and relay all still run.

## The layers

```
LoraMeshTransport (pure)      fastFanout/longRangeFanout/fastSend · LoraFramePolicy (+ isFresh) · LoraFrameCodec ·
                              LoraPacePolicy (class shedding + LoraAirtime budget) · reachable(isPresenceEvidence, 45min linger) ·
                              boardsHeard + its RxQuality (the signal row, same 45min linger) ·
                              beacon + reofferTo on first hearing · LoraGatewayPolicy/LoraGossipPolicy/LoraCtl (the bridge)
  └─ MeshtasticLink (seam)    state / packets / outcomes / queue · suspend send()
       └─ MeshtasticSession   pure actor: want_config handshake · drain-until-empty on FromNum · 180s heartbeat ·
          (pure)              client packet ids ↔ queueStatus/NAK · reconnect-with-backoff (ConnectBackoffPolicy)
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
  takes ahead of the version parse). The signing input is `LE32(from) ‖ LE32(id) ‖ LE32(portnum) ‖ payload`
  under the board's Curve25519 key converted to Ed25519 (`y = (u − 1)/(u + 1)`, sign bit 0); on 2.8 a node
  number **is** the CRC32 of that key. The text cliff is **166 B** (one-byte portnum), the Knit-frame one 165.
  Two rules to remember: a signed packet that fails against the key the *receiving* board holds is dropped
  before the phone sees it, whatever the policy — a stale or planted key silently blackholes that sender —
  and an unverifiable one (no key in the NodeDB) is passed up with the signature and the flag false.

## Key bootstrap (the far side has never seen the author's profile)

Two paths: (1) `MeshManager.watchReachable` already refloods our profile on a new `reachable` peer;
(2) `LoraMeshTransport` beacons its own signed profile (`ProfileFrameSource` ← `MeshManager`) on
session-up under a **5-min floor** and on first hearing a peer under a **60-s gap** (one timestamp, two
gaps — ADR 039 §8: a peer that just appeared has demonstrably never heard us, and without a periodic beacon
this is the only way a late arrival learns our key). The composite's self-profile `fastFanout` shares the
5-min floor. `PendingInbound` (~2 min) replays the parked chat once the profile pins the key. A **sig-keyed
SeenSet** (first 8 B of `sig`, 10 min) recorded on send *and* receive stops re-fanning a LoRa-received
frame and bounds AckSync's 24 h verbatim tick retries.

## Bridging pockets (ADR 044)

Two BLE/NAN cliques ("pockets") out of range of each other, one board-holder in each, boards in LoRa range.
**Live traffic already crossed** before ADR 044 and still does with no new machinery: `InboundPipeline.onDeliver`
re-fans every first-seen *relayed* frame, and `fanout` never checked authorship — so a post by any pocket-A
member reaches A's gateway over BLE, crosses the hop, and floods pocket B from B's gateway. ADR 044 adds the
three things that were missing.

**A gateway role.** `LoraGatewayPolicy`: anything publishing a `LoraCtl` OFFER has a board; a publisher we
hold a **live link** to (`suppressDataPath`, the higher-preference planes' `neighbors`) is a co-pocket rival,
one we do not is the bridge peer and is never suppressed. Lowest publisher key wins; the rest go PASSIVE and
suppress the **floodable** paths (fan-out, beacon, offer, backfill) — inbound is untouched, so a spare board
still feeds its pocket, and `fastSend` is untouched too (a `relay = false` targeted tick is owed by one node,
never flooded, so no co-pocket board has a copy to relay *or* to duplicate). Recovery: on a lost link, on a
gateway ageing past `STALE_MS` (45 min), and on the 60-s sweep — being wrongly passive is total silence, so it
must never need an event to recover. Closes ADR 038's "one board per clique" residual.

> **It must be the link set, never `reachable`.** BLE publishes presence adverts far beyond L2CAP range and
> Wi-Fi Aware keeps a peer reachable for 150 s after its last cue, so a sighting is not a data path — and
> standing down is only safe toward a board our frames can actually be handed to. Electing on `reachable`
> was field-observed (2026-08-25, two Pixels across a field): the higher-keyed one showed "listening", sent
> nothing in either conversation, and its ✓✓ ticks never landed, with no peer carrying any of it. The same
> audit moved ADR 039's two `foreignReachable` guards (`fastSend`'s "another plane covers this peer" and the
> re-offer's "custody syncs there for real") onto the link set for the identical reason — custody's digest
> exchange runs off `neighbors`, so a sighting never triggers it, and the `fastSend` one alone would have kept
> those receipts stranded. `…debug.LORA` reports `role` with both its inputs plus `pocketSightings`, so the
> gap between heard and linked is visible rather than inferred.

**An airtime governor.** `LoraAirtime` (pure): time-on-air from the LoRa formula at the board's own preset
(231 B at LongFast ≈ 2 s) **plus the signature 2.8 adds to anything under the cliff** — gated on the board's
firmware (`LoraAirtime.signsPackets`, from the handshake's `DeviceMetadata`; unknown reads as signing, since
under-charging can cost compliance where over-charging only costs throughput) **less whatever `padTo` dodges**
(ADR 2026-09.mhs5 — the same governor that prices the cliff is the one the codec asks whether to pad past it), a rolling **15-minute window** (ADR 054 — it was an hour, and a burst of chat then
blacked the plane out for the rest of it; the hourly total is unchanged, the worst straddling hour ≤ 6.25 %),
and one allowance = `min(region duty cycle, 10 % politeness) × 0.5` of the window — **45 s of air at LongFast**.
Those two ceilings are computed **independently** (ADR 067): the regional duty cycle is law and only the
firmware's own `override_duty_cycle` lifts it, while the 10 % is Knit's manners toward everyone else on the
shared band — which a dedicated RF slot makes vacuous. So a board on a dedicated slot in a 100 %-duty region
gets **450 s a window**, one in EU_868 still gets 45 s, and the unlock is a constructor flag wired to
`BuildConfig.DEBUG` (`LoraAirtime` itself stays pure and is tested both ways).
`AirBucket.LIVE` may spend all of it; `AirBucket.BRIDGE` (offers + backfill + the ADR 039 re-offer) is capped
at 30 %, so backfill degrades before live chat does — but that cap is on **serving**: a `FrameClass.GOSSIP`
OFFER books `BRIDGE` and is judged only against the window *total* (ADR 2026-09.t8t8). An OFFER is not
backfill, it is the packet that decides whether any backfill happens at all — including the far pocket's,
whose air this budget does not pay for — so a gateway busy serving used to starve its own offers and thereby
silence the other pocket entirely (field-observed 2026-09-04: `BRIDGE 13372/13500`, two transmitted offers
against seven published, the far side hearing none and holding a room post ~30 min). What bounds gossip is the
Trickle timer, not a share; a reserved *slice* of BRIDGE was rejected because it cannot be sized across presets
(a 48-prefix OFFER is ~2 s at LongFast and ~13 s at LongSlow against the same 13.5 s budget). Only one OFFER is
ever queued — `publishOffer` calls `LoraPacePolicy.dropQueued`, since a superseded snapshot names a set we have
since changed. Serving also asks the budget **before** it queues (`serveOne` → `Serve.NO_AIR` ends the round),
so the hourly serve cap is no longer spent on frames that only class shedding will remove — and it asks
against what is already **queued** for BRIDGE (`LoraPacePolicy.pendingSizes`) as well as what is recorded, so
a round cannot promise more air than the window has left. Without that, a round passed admission whole and
then ran out of window part-way down the queue, and the frame the queue reached last was always the room post
the backfill rank had deliberately put *first* (`FrameClass` drains a DM before the room; `backfillRank`
spends slots the other way). ADR 2026-09.zkma;
`LoraBridgeTest.aRoundThatCannotPayForEverythingKeepsWhatTheRankChose`. `AirBucket.BOOTSTRAP` (a live `profile` fan-out, ours or
relayed — paired to `FrameClass.BOOTSTRAP` by `AirBucket.defaultFor`) is capped at 25 % and is the **one class
judged outside the total**, so the key bootstrap still rides a spent window (ADR 056). It used to be admitted
unconditionally *and* recorded, which is a budget with no floor: on the lab gateway 79 % of every LoRa frame
ever sent was a profile (see the re-fan gate below). A backfilled profile stays on `BRIDGE` — re-served
history, not bootstrap. `FrameClass.TICK` (our own delivery receipts, see Pacing) never spends the last 25 %
of a window. Region + preset
are read off the board (`FromRadio.config` → `Config.LoRaConfig`, pinned by `MeshtasticProtoTest`; conservative
5 % until reported). `LoraPacePolicy.take` consults it, skipping a refused frame rather than blocking behind
it, and **dequeue is now by class then FIFO** (a reversal of ADR 039 §5 — bursts of backfill must not queue-jump
a live message).

**Digest-driven backfill.** `LoraCtl` (tag **`0x10`**, beside `FastFrameCodec`'s `0x03`/`0x04`; an older build
drops it as `UNKNOWN_TAG`, which is what makes this additive) carries an OFFER: publisher key + ≤ 48 4-byte id
prefixes (`StoreDigest.hash64` truncated), never fragmented, one packet. `LoraGossipPolicy` is Trickle —
5 → 15 min doubling, transmit in the interval's second half, snap to the floor on news, suppress only on **set
equality** (a superset has not said what we needed to say). "News" includes a *heard* OFFER announcing a set
that is not ours (ADR 2026-09.qsj6), which `onCtlPacket` follows with a `gossipWake` poke like the other two
reset sites — without it the loop sleeps on the old due time, wakes past the reset interval's end and doubles.
It snaps only a **backed-off** interval, so a divergence that cannot converge (serve cap spent, permanent
superset) settles at the floor cadence instead of two gateways resetting each other into the BRIDGE budget;
and a reset never moves an unspent transmit point *later* than the one its interval already picked.
On a far gateway's OFFER: `BridgeFrameSource`
(`MeshManager` over `ForwardStore.liveFrames`, already TTL- and quota-bounded, so no extra age gate) returns
what the prefixes don't name, ranked profile → room → DM newest-first (ADR 2026-09.rre4 — the reverse of the
pacing queue's `FrameClass` order, on purpose: the queue asks who transmits first, the rank asks which frames
are worth a scarce slot, and a room post is readable by the whole far pocket for typically one packet against
a DM's one addressee for two); ≤ 4 per offer, ≤ 12 per publisher per
hour, and hard-bounded by the BRIDGE budget.

> **The offer names the head of that same ranked list** (`LoraFramePolicy.bridgeOrder`, ADR 2026-09.zkma) —
> one packet holds ~48 prefixes against a 1000-row store, so the offer is a *window*, and a frame it cannot
> reach that the serve prefers is re-sent every round for ever. The window used to be the newest 48 by
> `sentAt` while the serve preferred profiles, and a `profile`'s `sentAt` is a **publish stamp** hours old:
> no profile was ever named, every profile looked missing, and all four slots went to profiles both pockets
> already held — round after round, for as long as the pocket had 48 frames of recent chat, which is why it
> read as intermittent. Field-observed 2026-09-05: `bridge served=4/4` both ways, hours apart, the same four
> ids, three of them the recipient's **own** publishes, and a room post that only arrived when the phones
> re-linked over BLE. The list also collapses each author's profiles to the newest — only that one carries a
> usable key, and custody really did hold three. Regressions:
> `LoraBridgeTest.aRoomPostCrossesAPocketWhoseOfferIsFullOfRecentChat` and
> `onlyAnAuthorsNewestProfileIsWorthASlot`. Frames are re-wrapped verbatim like any custody
re-serve — no wire change, no custody rule touched. `SettingsStore.loraBridgeEnabled` (default **on**, the
"Bridge distant groups" switch) gates offering and serving together.

> **`serveOne` consults neither dedup set** (ADR 2026-09.y8pu, and ADR 057 for `profileSeen` before it). An
> OFFER is positive evidence the far gateway lacks this exact frame, which outranks either set's guess that
> we need not send it. `sigSeen` in particular records that we *transmitted*, and on a plane with no acks
> that is not evidence anyone *heard*: a fan-out into an empty sky spends the slot exactly as a heard one
> does, and for the ten minutes after it the repair path used to skip the frame it was there to repair — the
> field failure was a Nearby-room post sent out of LoRa range and never delivered on return (2026-09-02).
> `serveOne` still **records** the signature, so a live fan-out inside the window doesn't duplicate what the
> bridge just queued; the asymmetry is the point, since a fan-out is speculative and a backfill was asked
> for. `reofferOne` keeps its refusal on purpose — a first hearing proves nothing about what the peer holds,
> so without the dedup it would re-send on every 45-min linger. Regression:
> `LoraBridgeTest.aRoomPostFannedOutToAnEmptySkyIsStillBackfilledInsideTheDedupWindow`.

**Multi-hop is Meshtastic's job.** A frame injected at a far gateway can't be re-transmitted (sig dedup) and a
second board there is PASSIVE, so a third pocket is reached by the board's own 3-hop flood, not by a second
phone-level send.

**One fan-out per publish (ADR 057).** A relayed `profile` used to be gated only by `sigSeen`, whose 10-minute
TTL matches `MeshRouter`'s SeenSet — so a profile that kept arriving looked first-seen again on every lapse and
re-fanned forever (`isFresh` exempts profiles from the staleness check on purpose: a profile's `sentAt` is a
publish stamp, hours old by design). A second set, `profileSeen`, keyed on the **frame id** (stable per publish,
new on republish) with `PROFILE_REFAN_MS` = 12 h = `MeshManager.PROFILE_REPUBLISH_MS`, now holds it. Checked
before `sigSeen` and after `encodeOrNull` — a held profile must leave the signature slot free for the backfill,
and an unencodable frame must not consume a window it never rode. `serveOne` is deliberately **not** gated:
the digest-driven backfill is what repairs a genuinely lost profile now (this plane refuses `keyreq`, so a far
pocket cannot ask) — the reasoning ADR 2026-09.y8pu later extended to `sigSeen`. Our own `sendSelfProfile` beacon is not gated either — its 5-min floor and 60-s
first-hearing gap are event-driven, and "a new listener appeared" is exactly when re-sending is the point.
Counter: `loraProfileRefanSkipped`.

## Pacing

`LoraPacePolicy` (pure): 3 s min inter-packet gap, a 32-frame queue (ADR 054 — sized to hold a 15-minute
wait), NAK back-off (rate/duty → a 60 s cool-down), hold while `queueFree == 0`, and (ADR 044) a hold while the
frame's [AirBucket] budget is spent — a refused frame is skipped rather than blocking the queue behind it.
When full the queue **sheds by class** (`FrameClass`: BOOTSTRAP > GOSSIP > DM > ROOM > TICK): the oldest
**whole** frame (never a lone fragment) of the lowest class present goes, the newcomer included — a room post
alone at the bottom is `REFUSED` rather than evicting a DM, and nothing ever evicts the profile bootstrap
(queue order only — since ADR 056 the bootstrap is metered on the air, and a profile refused by its share
waits in the queue for the next window rather than being dropped).
`TICK` is a frame **we** originated as a delivery receipt, said so by `FanoutHint.TICK` on
`MeshTransport.longRangeFanout` (the transport cannot read a sealed frame); a relayed DM-form frame stays `DM`,
and every `fastSend` frame is a tick by policy. Dequeue runs the same class order forwards since ADR 044
(highest class first, FIFO within it — bursts of gossip/backfill must not queue-jump a live message). Both
eviction and refusal count as `loraDroppedQueue`.

**Freshness gate** (fan-out paths only, room included): a `chat`/`reaction` whose `sentAt` is more than
15 min old (`LoraFramePolicy.FRESH_MS`) is a custody re-serve and is not fanned — without it a newcomer's
whole backfill re-fanned over the air, twelve frames at a time. Profiles (publish-stamped `sentAt`) and
receipts are exempt, as are the targeted path (AckSync's verbatim retries) and the re-offer. It reads the
injected `wallClock` (epoch) — the transport's `clock` is `elapsedRealtime` and is not comparable to a
frame's `sentAt`. Counted with the sig-window rejections under `loraSuppressed`. `BleConnectArbiter` lets
the board dial pause the mesh BLE scan for its connect window (scanning starves connects).

## DMs (ADR 039)

A 1:1 DM rides as its ordinary sealed frame — nothing is re-encoded, the signature verifies unchanged, and
the far side needs only the pinned profile (key + `CAP_RATCHET` + prekey) the beacon already carries; X3DH
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

**Re-offer on first hearing.** The plane has no custody sync, so a DM sent while the peer's board was off
would be lost to it. On first hearing a peer (once per 45-min linger), after the beacon, the transport pulls
`FarPeerFrameSource.framesFor(peer)` (`MeshManager`: the newest 4 live custody frames addressed to the peer
via `ForwardStore.liveFramesTo`, minus our own frames it already acked via `MessageDao.unackedDmsTo`),
re-wraps them verbatim and enqueues them class DM through a private path (`reofferTo`). Skipped for a peer
another plane carries (`foreignReachable`) — custody syncs to it for real there. Bounded: ≤ 4 frames × ≤ 3
packets per sighting; a frame fanned inside the sig window is skipped. Counted as `loraReoffered`.

**What still doesn't cross:** group chat (group-form frames are refused; sealed group *machinery* — seeds,
key req/ack, escalated ticks — crosses opaquely and is bounded by the group logic), `typing`, attachment
bytes (a DM with an image arrives as text plus a loading placeholder until a radio/spool path exists —
`blobreq` never rides LoRa; `AttachmentDeferPolicy` already ignores LoRa sightings), and DMs beyond the
size ceiling. A board-less recipient behind another board-holder gets live DMs via that phone's relay but
no re-offer (no routing table).

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
- **`renameOnly` fills the recorded name in, it does not overwrite it** (`previous.owner ?: was.owner`). A
  board missing only the mark is *already* `Knit abcd`, and recording that would destroy the only copy of
  the stock name a Restore has to put back. Unconditional overwrite was safe only while this path was
  reachable exclusively on a stock-named board.

### The dedicated-frequency setup (ADR 067) — debug builds only

`ProvisionMode.SetupDedicated` is everything above **plus** one spliced varint: `lora.channel_num`, pinning
the radio to the slot `LoraSlot` derives instead of leaving the firmware to hash it out of the primary's name.
It exists because ADR 045's bargain has a premise — a Meshtastic neighbourhood to borrow relaying from — and
an isolated farm or a mountain house does not have one, so the shared slot buys nothing while still costing
`LoraAirtime`'s politeness ceiling. **Not offered in any release build** (`LoraRadioUiState.dedicatedOffered`
is `BuildConfig.DEBUG`, re-checked in the ViewModel so no route reaches it), and never the default.

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
  `SettingsStore.loraBoardNode` when the board reports `Ready`, cleared on unbind, republished on change);
  `PeerEntity.loraNode` stores a peer's claim; `deliverMeshPost` resolves `packet.from` through
  `PeerRepository.findByLoraNode` (newest `updatedAt` wins — a board that changed hands is claimed twice until
  the old holder's next profile drops it) **once, at ingest**, and freezes it as `messages.originPeerId`, so
  history is never re-attributed. A resolved contact wears their name and avatar with the **unverified**
  styling (muted name, untappable avatar, the room strip) until a signature verifies the match — then a Knit
  author's colour, a shield and a direct avatar tap; a blocked contact's board is dropped
  (`BLOCKED_CONTACT`). A row's body is shown word for word, since ADR 2026-09.9469 left nothing on the line
  but the words — a heard `"Sam: hi"` is a stranger's content, not a prefix to strip. The match rests on a
  self-asserted node number — **unless the signature checks out** (ADR 2026-09.ggq4). The profile also
  carries the board's Curve25519 key (`ProfileContent.loraKey` ← `SettingsStore.loraBoardKey`, written with
  the number in one edit and only while the board signs), `peers.loraKey` stores it, and `deliverMeshPost`
  verifies a signed post on the phone (`mesh/crypto/XeddsaVerify`, over `from ‖ id ‖ portnum ‖ payload` as
  heard — `MeshPost.payload`, never the trimmed body) and freezes the verdict on the row as
  `messages.originSigned`: `UNSIGNED` (nothing to say — pre-2.8 radios never sign, a post past the cliff
  cannot), `SIGNED_BY_BOARD` (our board's `xeddsa_signed`: the radio that has been using the number), `SIGNED_BY_CONTACT`
  (verified under the contact's advertised key — the one verified state) and `SIGNATURE_MISMATCH` (some other
  radio on the contact's number: **not attributed**, drawn as a stranger that says so). Counters:
  `meshPostVerified` / `BoardVerified` / `SignatureMismatch`.
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
  `--ez on <true|false>`, `--ez bridge <true|false>`; no extras dumps
  `state/boardNodeNum/snr/rssi/queueFree/queued/heard/role/pocketLinks/pocketSightings/gatewaysHeard/radio/airtime/counters`
  (`airtime` also carries `dedicated`, true when the board is on its own RF slot and so off the politeness
  ceiling — ADR 067; `liveMs`/`bridgeMs`/`bootstrapMs` against their budgets — `loraSent − loraDmSent −
  loraOfferSent` is the profile + room count, which holds only because `loraOfferSent` counts what was
  **transmitted** rather than what was enqueued (ADR 2026-09.t8t8), and profiles are the fragmented ones;
  `loraProfileRefanSkipped` against `loraSent` is the re-fan redundancy). `loraAirtimeHeld` /
  `loraAirtimeHeldByBucket` beside `queued` is what tells a starved plane from a quiet one: a spent bucket
  *holds* frames rather than dropping them, so no counter moves until the queue overflows and the drops all
  arrive at once. It is the
  two-board oracle. `…debug.LORATX --es text <s>` sends a raw payload straight to the board (board-side
  sanity via `meshtastic --noproto`). `…debug.LORAPROV` sets the board up headlessly; `--es mode dedicated`
  is ADR 067's debug-only dedicated-frequency setup and `--es mode restore` undoes either.
- Broadcast: `…debug.SEND --es conv nearby --es text …` on A → appears on B within ~5–10 s; A's tick flips
  ✓✓ (sealed tick over LoRa); a reaction crosses. Move B out of BLE/NAN range and repeat. Counters:
  `loraSent/loraReceived/loraReassembled` climb, `loraNak == 0`, `loraDroppedQueue == 0` at chat pace,
  `loraTooBig` only for long posts. Diagnostics lists a LoRa-reachable node under **Reachable via relay**
  tagged `LoRa`, never under *Directly connected* (ADR 2026-09.2ajk).
- DM (ADR 039): `…debug.SEND --es conv <peerId> --es text …` on A → appears on B within ~10 s; A's tick
  flips ✓✓ (the sealed receipt crossed back); `loraDmSent`/`loraDmReceived` climb on both, `loraTooBig == 0`.
  Reply from B (turnaround epoch) and a DM reaction cross; a 500-char DM counts `loraTooBig` and lands later
  over BLE. Power B's board off, send two DMs from A, power it on → B beacons, A logs `reoffer` ×2, both land,
  ✓✓ returns, `loraReoffered == 2`. `…debug.LORA --ez dms false` on A → a new DM stays LoRa-silent
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
- Bridge (ADR 044), the four-device trial: pocket A = board-holder + one more phone, pocket B likewise, the
  two pockets out of BLE/NAN range of each other. `…debug.LORA` shows `role: ACTIVE` on both board-holders and
  a real `radio` (region/preset off the board, not `(assumed)`). (1) A room post from A's **board-less** phone
  reaches B's board-less phone in ~10 s — `loraSent`/`loraReceived` climb on the gateways only. (2) Power B's
  board off, post twice in A, wait past `FRESH_MS` (15 min), power it back on → B offers, A logs
  `lora bridge served=2`, both land, `loraBridged == 2`. (3) Pair a third board to A's second phone → it
  reports `role: PASSIVE`, `loraPassive` climbs, its `loraSent` stays flat, and the bridge keeps working.
  (4) Over an hour of chat pace: `airtime.liveMs` under `liveBudgetMs`, `bridgeMs` under `bridgeBudgetMs`,
  `loraNak == 0`, `loraDroppedQueue == 0`. (5) `…debug.LORA --ez bridge false` → `loraOfferSent` stops
  climbing and no backfill is served, while a live room post still crosses. (6) With `bridgeMs` at its budget
  from serving, `lora tx offer` still appears on the gossip schedule and `loraOfferSent` matches that line
  count; `loraAirtimeHeld` names `BRIDGE` while `queued` shows the depth, and `loraBridged` stops short of
  `SERVE_CAP_PER_HOUR` rather than running to it with nothing landing (ADR 2026-09.t8t8).

## First-session unknowns to confirm (assumptions, not blockers)

Whether an empty FromRadio read returns immediately or blocks ~20 s (could cut the 30 s read timeout);
`phone_timeout_secs` default (180 s heartbeat is safe either way); whether `queueStatus` is pushed as the
TX queue drains; that `mesh_packet_id` echoes our client id; that PRIVATE_APP packets reach the phone and
our own broadcast is not echoed; the 600 ms bonded post-connect settle (drop if unneeded).
