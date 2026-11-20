# LoRa plane — which decision governs which symbol

The symbol → ADR routing for Knit's LoRa (Meshtastic-over-BLE) plane: `mesh/lora/` and `mesh/bluetooth/meshtastic/`.
The `lora-owner` reads it before it writes and the `lora-reviewer` before it judges (both in `.agents/personas/`);
`AGENTS.md` points here instead of carrying these lines. Each entry names the symbols that trigger it, the ADRs to read
(`.agents/memory/decisions.md` maps an id to its file, amendments included) and, where there is one, the section of
`.agents/context/lora-bridge.md` that explains the mechanism. An entry is an index into its ADRs, not a substitute:
where they disagree, the ADR wins and the entry is fixed.

LoRa-adjacent routing that lives in another subsystem's file under `.agents/context/` (`spool.md`, `messages.md`,
`blobs.md`, `contacts.md`, `diagnostics.md`), because that subsystem owns it: how a Nearby-room ✓✓ gets home
(`AckSync`'s ride hold, `MeshRouter.handOn`, `coveredByInternet` — ADR 2026-09.y5f3 / 2026-09.wkbk; since ADR
2026-10.rp3s no room tick rides LoRa), a link preview on
a LoRa thread (ADR 2026-09.7x8k), a blob re-wanted at link-up (ADR 2026-09.ptv8), the presence tiers (`ui/Reach.kt`,
ADR 2026-09.2ajk), and the field log's `FieldEvent.Lora` sample and the link edges `LoraFieldLog` follows (ADR
2026-10.a239 and its 2026-10-18 amendment). The GATT clients
(`MeshtasticGatt` and `MeshcoreGatt` over the shared `BoardGatt` core, the plane's only `android.bluetooth.*` importers;
`mesh/lora/` is pure and JVM-tested) are also Bluetooth code, routed in `bluetooth.md`: a change there owes
`bluetooth-reviewer` as well as `lora-reviewer`.

## Entries

- **When touching what the plane airs — `LoraFramePolicy.airs`, `LoraFramePolicy.eligible`, `RideGate.authorId`,
  `StaleAtSend.NOT_OWN`, `fastSend`, the one-packet room rule (`loraRoomTooLong`), the fan-out's dedup (`sigSeen`,
  `LoraFanoutSeen`, `SIG_TTL_MS`), `RETIRED_CTL_TAG` / `loraRetiredCtl`, `LoraSizeHint.ROOM_BODY_BYTES`, or a "Forward
  over LoRa":** READ ADR 2026-10.rp3s and its 2026-10-29 amendment, then "Shape" in `lora-bridge.md`. The board is an endpoint: it airs only frames this phone's user wrote (one predicate,
  `LoraFramePolicy.airs`; the deferred "Forward over LoRa" is a second clause there and nowhere else), and nothing
  before our own id is read — a frame fanned then carries its author on `RideGate.authorId` and `sendFrame` refuses
  it as `NOT_OWN`. An own room `chat` rides only in one packet at the current `maxPayload`; a room `reaction`, a
  `profile`, a receipt and anything somebody else wrote never ride, and `fastSend` refuses everything (no room ✓✓
  airs). An own post airs once while it is fresh: the dedup holds it until its own `freshUntil` on the wall clock, not
  for the router's ten minutes, and a frame dropped unsent hands its slot back (#239). Inbound is unchanged: a heard
  frame still floods into the pocket over BLE/NAN. Tag `0x10` (the bridge's
  OFFER) is burned; it is dropped before decode under `loraRetiredCtl`, never as `UNKNOWN_TAG`.
- **When touching `LoraMeshTransport`'s shape — `fastFanout`, `neighbors`, `shortRange`,
  `LoraFramePolicy` (`isFresh`, `FRESH_MS`), `SettingsStore.loraDmEnabled`, or
  `BuildConfig.LORA_PLANE`:** READ ADR 038, ADR 2026-09.6gtm, ADR 2026-10.rp3s and ADR 2026-10.x9fa, then the "Shape"
  and "DMs" sections of `lora-bridge.md`. The plane is a fast-plane-only composite child (`neighbors` always empty,
  `send`/`sendFile`/`sendDigest`/`fastSend` no-ops), carrying its own user's one-packet room posts on `fastFanout` and
  its DMs as compact unicasts through `LoraDirectSink.offer` — never a DM-form mesh frame (ADR 2026-10.x9fa deleted
  the long-range fan-out seam), group-form chat, `typing`, `blobreq`, `keyreq`, a `profile` or a room reaction. The room
  post is not a wire change: `sig`/`signed` pass byte-exact. The flag is on everywhere since 2.5.0, `loraEnabled` still
  defaults false; `loraDmEnabled` gates our compact sends only, never what our board already acked.
  **Before touching a compact DM — `LoraDirectSink.offer`, `LoraDirectCrypto` / `LoraDirectOpen`, `LoraDirectInbox`,
  `LoraDirectSource`, `LoraBoardHolder` / the board map, `LORA_DIRECT_TAG`, `DirectKind`, `DirectSend`,
  `LoraDirectPolicy` (`mustWait`, `holds`, `unparkOnFailure`, `onHeard`, `deadlineMs`, `retriesBefore`, `RETRANSMITS`,
  `boardAcks`, `ACK_PAYLOAD_BYTES`), `RideGate.direct`
  / `reachHeld` / `refusalHeld` / `unackedFrameId`, `LoraMeshTransport.onDirectUndelivered`, `AFTER_ACK_LIMIT`,
  `loraDirectPastHold`, `StaleAtSend.NOT_DIRECT` / `HELD` / `ACKED` / `SLOT_ZERO`, `noteHeard` /
  `onBoardHeard` / `isLive`, `catchUp`, `DirectDm.boardTook`, `onDirectPacket`, `LoraReprobe` / `reprobeDue`, `frameKey` / `LoraPacePolicy.hasQueued` /
  `hasQueuedTo`, `LoraDirectPolicy.payloadCap`, `ReceivedPacket.pkiEncrypted`, or `LoraSizeHint.DM_BODY_BYTES`:** READ
  ADR 2026-10.x9fa (it supersedes ADR 039's DM form, ADR 2026-10.a4pm and rp3s's probe, re-offer and repair) and its
  2026-10-30 PKI amendment, then "DMs" in `lora-bridge.md`. Firmware 2.8 PKI-encrypts every compact DM whatever the
  slot: one shared cap (`payloadCap`, 219 B; `LORA_DIRECT_PLAINTEXT_BYTES` 168 B, `LORA_DIRECT_TICK_ACKS` 9), every
  airing priced with the 12-B PKC overhead (admission, booking, the flight's `airMs`), an ACK booked at 18 B with its
  proof, and a DM received on index 0 counted in `boardsHeard` only once it opened. One packet, sealed pairwise, a
  `want_ack` unicast to a pinned board holder with the capability, on the bound Knit secondary (never slot 0,
  `loraDirectSlotZero`); every gate re-asked at send; one copy per frame id queued or awaiting its answer; one send in
  flight per peer not known reachable. A destination's ACK is a radio's, never a person's: it marks the board
  `Reachable` (45-min linger) and never feeds `reachable`; an implicit ACK only counts; `Unreachable` or no outcome by
  the deadline holds a ✓✓ or a reaction until a live hearing of that board (RSSI, not MQTT, not our board, fresh
  `rx_time`) or the re-probe, while a DM the user types passes it (`refusalHeld`, `loraDirectPastHold`) and the newest
  one typed during a failed send gets the next attempt at once (`unparkOnFailure`, no time edge) — x9fa's 2026-11-19
  amendment; a destination's refusal holds until their profile changes or 4 h pass. Every send ends at its deadline. The re-probe
  keys on our DMs with no ✓✓, whatever the board said, and on its board ack the ≤ 2 older DMs no board took follow in
  the same round (#343, x9fa's 2026-11-17 amendment). A catch-up (x9fa's 2026-11-04 amendment, #294) re-airs the
  newest DM **no board took** (`DirectDm.boardTook`) on a fact — a hearing after a failure, a relay cover lapsed or
  lifted, the board acking a fresh DM — never a board-taken one (that is the re-probe's), inside the re-probe's gap
  since the last failure and booked on its schedule; no timer, nothing persisted. Behind a fresh ack up to
  `AFTER_ACK_LIMIT` (8) go at once, a hearing or a cover keeps the newest plus ≤ 2 after its ack; the flight table meters
  them (`LoraPacePolicy.take`'s `directRoom`, `LoraDirectPolicy.flightRoom` / `DirectFlightRoom`: a compact frame waits
  while `BoardTraits.maxFlights` sends are out, never evicting one; sends to peers marked unreachable and resends or
  re-probes to peers not known reachable hold at most `maxFlights - 1`, the last place kept for a fresh frame to a peer
  who is there, and a peer whose board acked this session may use both). A ✓✓ whose compact copy did not land for air, an unreachable mark or our own board's refusal is told
  to the app once per send (`onDirectUndelivered`), never for their refusal, a key, a cover, slot 0 or `NOT_DIRECT`. A board-map source that has not reported reads "not now"; a
  failing one is retried every 5 min and counted (`loraBoardHoldersFailed`). Before our id, the fan-out admits at most
  `PRE_ID_ADMISSIONS` (4) room posts (`loraPreIdRefused` past it), charging a slot only to a frame that reaches the
  queue (`reservePreId`). A frame dropped before its first fragment is `abandon`ed: its dedup slot comes back, and a
  compact DM releases the offers parked behind it. The re-probe's schedule is a persisted limiter, not cleared by
  `quiesce`; the reach marks and the in-flight table are in memory and are. Our board's ACK of a compact DM to us is
  booked only when the firmware sent one (`boardAcks`: `want_ack`, or a `next_hop` naming us with hops left).
  **Before touching `LoraContactPolicy`, `MeshtasticProto.encodeAdminAddContact`, `RoutingError.PKI_SEND_FAIL_PUBLIC_KEY`
  / `PKI_UNKNOWN_PUBKEY` on this plane, `LoraDirectPolicy.Reach.NO_KEY` / `firstNoKey` / `holdForContact` /
  `scheduleRetry`, `LoraBoardHolder.loraKey` / `contact` / `contested`, `MeshtasticLink.nodeUser`, `BoardInfo.licensed`
  / `afterPeerKey`, or `loraDirectNoKey` / `ContactAdded` / `PeerLacksOurKey` / `BoardKeyConflict`:** READ ADR
  2026-10.aajj. Our board's `39` (no key for theirs; queueStatus res first, then a self-NAK) is acted on once per packet
  and the frame is held, never given up; one `add_contact` is written only for a contact's board no rival binds by its
  own key, whose node is the CRC32 of the profile's 32-byte key. The 39 is the board's word: the phone's copy of its
  NodeInfo stream (`StreamedUser`, stamped) is read for a key only if streamed after the send was issued — a key there
  then stops the write (a different one a counted conflict) — never a stale or forged entry from before. The `User` is
  an allowlist (id, names, key; `Knit abcd` and unmessagable as the fallback), never a display name, `role`, `hw_model`
  or an unknown field; the frame goes again on the write's res 0 through the pacer, and a timed-out write re-offers
  once unrecorded. `NO_KEY` only after a write, or when none may be written. One write per (board, key) until a
  DM to it is acked, two keys per peer a day, in memory. The destination's `35` re-offers once after a retransmit gap,
  our NodeInfo booked, never `REFUSED`. Never for the auto-reply.
  **Before touching `start()`'s loops, `awaitConfigured`, `quiesce`, or what runs with no board bound:** READ ADR
  2026-09.5bqu — nothing ticks, transcodes or queues until a board is configured, and losing the board quiesces
  exactly as `stop()` does.
- **When touching `LoraFrameCodec` (`encodeBest`, `PacketCost`), `LoraAirtime.padTo`, `MeshtasticProto.MAX_PAYLOAD` /
  `MAX_SIGNED_PAYLOAD` / `LORA_DATA_MAX`, `PRE_READY_PAYLOAD`, `LoraMeshTransport.HOP_LIMIT`, or the `0x05` form on
  this plane:** READ ADR 2026-09.mhs5 (and ADR 030 / 060 for the codec it reuses), then "Board facts" in
  `lora-bridge.md`. A Knit payload is ≤ 231 B on the air (≤ 228 at MTU 255, the MTU-255 floor before Ready), ≤ 3
  fragments; a 2.8 board that signs on the bound slot signs anything ≤ 165 B, so the **last** packet of a **deflated**
  frame is padded to 166 — never a stored `0x03`/`0x05` body, never an earlier fragment — and on a board that does not
  sign there (pre-2.8, 2.8.2 under `COMPATIBLE`: mhs5's 2026-10-30 amendment) nothing is padded. `hop_limit` is stated (3) on every send: omitted
  means zero on 2.8, not the node's default (`everyPacketLeavesWithHopsToSpend`).
- **When touching `LoraAirtime` (`Ceiling`, `admits`, `refusal`, `refund`, `allowanceMs`, `publicCeilingMs`,
  `PUBLIC_CEILING`, `price`, `onBoard`, `dedicatedUnlocksDuty`), `LoraPacePolicy` (`FrameClass`, `enqueue`,
  `take`, `admitBest`, `onQueueStatus`, `onNak`), `OutboundFrame.supersedes` / `admissionSizes` / `price` /
  `shedRank`, `LoraAirtime.UNSIGNED`, `PacketPrice`, `SigningFacts`, `AirSlot`, `PacketKind`, `SignaturePolicy`,
  or `AirtimeSnapshot`:** READ ADR 2026-10.ug4a, then
  ADR 044 (its allowance and 2026-10-04 amendment; the rest is superseded), ADR 054 (the window), ADR 2026-09.xdm2,
  ADR 067, ADR 2026-10.rp3s and ADR 2026-10.x9fa, then "Pacing" in `lora-bridge.md`. One allowance =
  `min(region duty, 10 % politeness) × 0.5` of a 15-minute window (45 s at LongFast); the duty cycle is law (the
  firmware's `DUTY_CYCLE_LIMIT` enforces it) and only Knit's own dedicated slot lifts the politeness half. One ledger,
  no buckets: every frame of ours spends the one total (`Ceiling.FULL`), and the Meshtastic room's post and the DM
  auto-reply only while the total, their own cost included, stays within half (`Ceiling.HALF`, refused as `CEILING`,
  which holds nothing). No tick tail. Every air spend is booked — no class is exempt and unmetered. Two priorities:
  `DIRECT` (compact DMs, ✓✓, reactions, resends, re-probes) dequeues before `ROOM`; a full queue sheds `ROOM`, then a
  ✓✓, then a DM or a reaction (`shedRank`). A refused frame is skipped, except that a `DIRECT` frame the total refused
  holds everything ranked behind it. Every entry point of the pacer and the ledger is `@Synchronized`, and the pacer's
  monitor is taken outside the ledger's. Every booking is priced by `LoraAirtime.price` — one snapshot per handshake
  (`onBoard`: version, `has_xeddsa`, `packet_signature_policy`, ham mode, AEAD on slot 0 and the Knit slot), per slot
  and packet class (ADR 2026-09.mhs5's 2026-10-30 amendment, #243): 2.8.1 signs every broadcast whatever the policy,
  2.8.2 only under `BALANCED`/`STRICT` and never on AEAD, an unknown policy or version reads as signing; a data unicast
  — a compact DM, the auto-reply — is a PKI unicast, unsigned and +12 B, unless the board is in ham mode. A compact DM
  is admitted against every airing the firmware may give it (`LoraDirectPolicy.AIRINGS`), every airing booked at send
  and the unflown ones refunded on its ack (ADR 2026-10.x9fa) — counted from the elapsed time, or from
  `PacketOutcome.flown` when the link reports it (MeshCore, ADR 2026-11.gsqu §2), which a give-up's refund reads too.
  The ledger is not persisted and nothing seeds it (ADR 2026-10.ug4a): a restart starts the window empty.
  Since ADR 2026-11.gsqu's Stage 5a-1 amendment every booking is priced through the **kind's hook** — `BoardTraits.price`
  by `AirPurpose` (`OutboundFrame.purpose`), `BoardTraits.phyBytes`, `BoardTraits.airMs` over `LoraAirtime.formulaMs` —
  with the kind's `fallbackRadio` / `fallbackDutyPercent`; Meshtastic's hook is the replaced code (`LoraAirtimeKindParityTest`,
  `PacketPrice.purpose` null). A third ceiling, `Ceiling.FLOOD` (`FLOOD_CEILING` 0.15, `floodCeilingMs`), counts **flood
  bookings only** (a booking carries its ceiling) under the one total; the room's ceiling is `BoardTraits.roomCeiling`
  (Meshtastic `FULL`). `NEVER` is a frame over **its own** ceiling on an empty window, and the pacer drops it
  (`LoraPacePolicy.drainNever`, only once `LoraAirtime.dutyKnown`; counted `loraAirNever`, logged `lora air never`) instead
  of waking on its idle tick.
  `admitBest`'s holder rule rests on one-packet room posts; a two-part MeshCore post can be passed by a later one (room
  order inverts, accepted in gsqu).
  <!-- doc-lint: allow AirBucket — the buckets ADR 2026-10.ug4a removed, named as history -->
- **When touching the shared-band brake — `LoraPacePolicy.onBandReading` / `braked` / `forgetBand` /
  `BUSY_BAND_PERCENT` / `BRAKE_FRESH_MS`, `OutboundFrame.braked` / `heldForBand`, `BoardAir` (`atMs`),
  `MeshtasticSession.onSelfMetrics`, `LoraStatus.braked`, `LoraFacts.bandBusy`, `LoraReach.RoomBandBusy`, or
  `loraBandBraked`:** READ ADR 2026-10.ug4a, then "Pacing"
  in `lora-bridge.md`. While the board's channel utilisation is at or over 40 % (the firmware's
  `max_channel_util_percent`, where it stops even its own automatic traffic; stock firmware never gates typed text) on a reading at most 5 minutes old, off a dedicated slot, Knit room posts wait in the queue; compact DMs, the
  Meshtastic room and the auto-reply still go. Stricter than a stock client, which would send the typed post: on a slot
  at or over 40 % most of the time, room posts may never cross LoRa and drop as `STALE` past `FRESH_MS` from their send. The reading is the board's **live** self-telemetry only — never the
  handshake `NodeInfo`, whose `device_metrics` can be hours old — timed on arrival, forgotten when the board is lost; a
  braked-only queue sleeps to the reading's staleness and a new reading wakes it. A brake is not an airtime refusal:
  it never sets the air deferral and is counted apart (`loraBandBraked`, once per frame). `airUtilTx` is a diagnostic
  only, never a ledger seed.
- **When touching the brake's face — `LoraFacts.bandBusy` or `LoraReach.RoomBandBusy`:** READ ADR 2026-10.ug4a and
  ADR 2026-09.ursc (its 2026-10-29 amendment). `bandBusy` is `LoraStatus.braked` while the plane is Live, else false;
  `loraRoomReachFor` returns `RoomBandBusy` only when a peer is LoRa-only, ahead of `RoomSaturated` (a braked post may
  never cross LoRa, a spent window only delays it); the DM and group rules never read it, since the brake holds no
  DM. It can outlast a lifted brake by the linger sweep's minute (#240).
- **When touching `skipCovered` / `coverReason`, `suppressDataPath` / `linkIds`, `coveredByInternet` / `liftCover` /
  `liftedPeers` on this plane,
  `RideGate` (`authorId`, `direct`, `reachHeld`, `refusalHeld`, `unackedFrameId`), `staleReason` / `StaleAtSend`,
  `OutboundFrame.sigKey`, `sendFrame`, or `selfIdKnown`:** READ ADR 054 and ADR 2026-09.5dt2 (and ADR 2026-09.y5f3 for
  the Internet cover, ADR 2026-10.rp3s for the author, ADR 2026-10.x9fa for a compact DM's gates). A compact DM to us,
  or to a peer a short-range plane holds a **live link** to (links, never sightings; `suppressDataPath` is the only
  feed), or one a spool covers, never airs. The spool cover read is `internetPeers` (ScopeSync's set, its one writer)
  less `liftedPeers`: a live compact frame from the peer lifts it until the relay drops them, and a cover that lapses
  or lifts catches the stranded DM up (ADR 2026-09.y5f3's 2026-11-04 amendment, #294; both sets under one lock, never
  held across `publishStatus`). The queue is a time-delayed commitment, so each frame carries the gates its
  own path asked and `sendFrame` re-asks them before the first fragment — only those. The pacer parks until the self id
  is known (#141). `HELD` is asked only of an offer, never of a resend or re-probe, which fly to ask an unconfirmed
  board again; `ACKED` only of those two, and last, since it reads the database. A refusal at send hands back the
  dedup slot or the parked offers: it never flew.
- **When touching `noteReachable`, `isPresenceEvidence` (`mesh/FramePresence.kt`), `boardsHeard` / `boardsHeardAt`, or
  `RxQuality`:** READ ADR 2026-09.2ajk and the boxed notes under "Shape" in `lora-bridge.md`. `reachable` here is
  author-keyed **reach**, never proximity, fed only by fresh frames (15 min, a profile 13 h) and never by our own
  board's airing; `boardsHeard` counts radios. An older build's gateway still airs frames it did not write, and those
  still count as reach (ADR 2026-10.rp3s dropped the co-pocket withdrawal of ADR 2026-09.6gk8 with the OFFERs it read).
  The signal row reads only radios that sent a Knit frame on the bound channel, aged with the radio, cleared on
  `stop()` — never a reading taken in `MeshtasticSession`, ahead of the filter.
  **Before touching `AckSync.backOff` or `escalateRide`'s reach:** READ ADR 2026-09.6gk8 and ADR 2026-10.rp3s — a
  cleartext owed tick backs off like the sealed one, and the ride's air route reads `shortRangeReachable`, never the
  LoRa plane's reach.
- **When touching `LoraPlaneState`, `LoraPlaneStateStore`, `WallShift`, `awaitRestored`, `LoraReprobe.capped`,
  `STATE_REPROBE_CAP`, `LoraFramePolicy.predates` / `processStartWall`, or what `stop()` saves:** READ ADR 2026-09.7svb
  (its ledger half superseded by ADR 2026-10.ug4a) and "Limiters that outlive the process" in `lora-bridge.md`. The
  auto-reply memory and the re-probe schedule (`reprobes`, ADR 2026-10.rp3s, its keys kept by ADR 2026-10.x9fa)
  survive a restart, stamped on the wall clock; nothing transmits until they are back (5 s bound). The airtime ledger
  does **not** (ADR 2026-10.ug4a): an older blob's `air` is ignored, under the same key. The snapshot's re-probe cap
  keeps direct board holders first, then the soonest due. An own room post written before this process started never
  re-airs (`predates`): it is a re-serve, which a restart's empty seen windows would otherwise let through. The probe
  window and the re-offer's hour (`dmProbes`, `reoffered`) went with the reach gate; an older blob still loads.
  `sigSeen`, `lastHeardAt` and the compact DM's reach marks are **not** persisted, on purpose. The blob is user data: a
  renamed or retyped field drops a limiter, and a blob an older build wrote must still load every limiter this build
  keeps (`LoraPlaneStateStoreTest`).
- **When touching board setup — `provisionKnitChannel`, `BoardQuiet`, `ProvisionMode`, `BoardName`, `LoraSlot`,
  `KnitChannel`, `spliceVarintFields` / `spliceStringFields`, `BoardSettings`, `SettingsStore.loraBoardSetup`, or
  `BoardConfig.LORA`:** READ ADR 045, ADR 049, ADR 2026-09.emd7 and ADR 067, then "Board provisioning" and "Setting a
  board up" in `lora-bridge.md`. Setup is one read-modify-write admin transaction that never moves the radio or
  touches the primary (stock nodes repeat Knit only on the same params), quiets the board, sets `LOCAL_ONLY`, renames
  it `Knit abcd` (never the user's name) and marks it unmessagable; Restore puts back exactly what it recorded. The
  dedicated slot is opt-in and never the default. `KnitChannel`'s PSK is derived and pinned by `KnitChannelTest`.
- **When touching `MeshtasticSession` (handshake, heartbeat, drain, admin passkey, `routeNak`), `MeshtasticLink`
  (`LinkDestination`, `BoardAddr`, `PacketOutcome`), `ReceivedPacket` (`wantAck`, `hopLimit`, `nextHop`, `rxTime`),
  `PacketIdSource`, `BoardBattery`, `ChannelInfo.useAead`, `LinkState.Ready.signaturePolicy`, `aeadBlocks`, or
  `MeshtasticGatt` (`SETTLE_MS`, `BoardGatt.Link.negotiateMtu`):** READ ADR 038 §2, ADR 041, ADR 2026-10.x9fa (the board-neutral seam,
  `want_ack`, `rx_time`), ADR 2026-09.mhs5's 2026-10-30 amendment (the security config) and "The layers" / "Board
  facts" in `lora-bridge.md`. The handshake's `SecurityConfig` is read for `packet_signature_policy` alone, inside the
  decoder, never captured whole (it carries the board's private and admin keys); a session that streams none reads as
  unknown, priced as signing, and the policy is re-read only at a new handshake. A Knit slot with `use_aead` refuses
  only what rides it — a Knit broadcast, a ham-mode compact DM (`aeadBlocks`): AEAD changes the channel hash — while a
  PKI compact DM (channel 0) and the auto-reply still go; the setup rewrites the slot and so clears AEAD
  (`identityOnly`). The protobuf is hand-rolled and total (malformed →
  null, never a throw), its field numbers pinned by golden vectors in `MeshtasticProtoTest`. A `Routing` answer about
  a direct send is the destination's ACK only from the destination; anything else is implicit; `NONE` about a
  broadcast is no outcome. The battery is read off the handshake and the per-minute
  telemetry, never polled. A bonded board starts SMP on the ACL, so the Exchange MTU waits and retries, and the
  reported size beats the status (b407293d).
  **Before touching `MeshtasticSession.connectLoop`, `BoardDialPolicy`, `MeshtasticGatt.dial`'s modes, or what the
  pacer does while the link is down:** READ ADR 2026-09.hp88 — three direct dials, then the controller's
  `autoConnect` with an hourly direct net and no arbiter while waiting; a refusal backs off, only a spent window
  doesn't; the pacer parks on `link.state`, never its 1 s tick. Its 2026-11-06 amendment (draft): a throw costs one
  pass of the loop and never the loop (`runRound`, `threw <class>`), the connect and every GATT op go through
  `BoardGatt.stackCall` (under Meshtastic's own tag), the adapter going off ends a session at once from
  `dialer.adapterOn`, and a session's inbox ends with it, so every `send` / `provisionChannel` is answered. A dial whose
  discovery succeeded without the Meshtastic service (`DialResult.ServiceAbsent`: other firmware, or a GATT cache from
  before a reflash) backs off once and ends terminal on a second reading in a row — `StaleBond` on a bonded board,
  `NeedsPairing` otherwise (ADR 2026-11.gsqu, Stage 3d); a discovery that failed always backs off. `NotReady` means the packet is owed (unsent, or
  its write failed, which can rarely mean it got there), and `sendMessage` requeues on it (`FragmentOutcome.OWED`: a
  requeued Meshtastic room post or auto-reply is never counted refused, #307); `Timeout` means it likely went, and it is
  never re-aired.
- **When touching the Meshtastic room — `onLoraPacket`'s slot-0 branch, `PublicChannelPolicy`, `PublicPostPolicy`
  (`onAirText`, `onAirBudget`), `postToPublicChannel`, `Ceiling.HALF`, `mesh/crypto/XeddsaVerify`,
  `ProfileContent.loraNode` / `loraKey`, `BoardBinding` / `onBoardBound`, `LoraFacts.roomSignedUpTo`,
  `PeerRepository.findByLoraNode`, `messages.originPeerId` / `originSigned`, or `SettingsStore.loraRoomEnabled`:** READ ADR 2026-09.26q3 (its 2026-11-15 amendment: delivery is ordered, `OrderedRoomSink`), ADR 2026-09.9469, ADR 2026-09.ggq4, ADR 2026-09.sre4 and
  ADR 2026-09.x52a (and ADR 2026-10.ug4a for its half ceiling), then "The Meshtastic room" in `lora-bridge.md`. The room is a local mirror of the bound board's
  slot 0: nothing minted, custodied or fanned out, no gateway gate; a post leaves through this phone's own board as the
  words alone, no author name. A heard author is resolved once at ingest through the node number their profile claims
  and stays "unverified" unless the board key in that profile verifies the XEdDSA signature — in firmware 2.8.1's
  layout only; Meshtastic withdrew 2.8.0, so a 2.8.0 contact reads as a mismatch (ADR 2026-09.ggq4's 2026-10-29
  amendment). The profile's `loraKey` is the bound board's key whenever its node number is the key's CRC32, whether or
  not the board signs (ggq4's 2026-10-30 amendment, #243): it verifies a signed post and feeds a contact's `add_contact`
  (ADR 2026-10.aajj); an unsigned post stays `UNSIGNED`. The composer caps a post at slot 0's own signature cliff
  (`LoraFacts.roomSignedUpTo`: 166, 154 on a 2.8.1 board whose slot 0 is AEAD, the 200-byte convention where the board
  signs no room post). ADR 2026-09.cf7a and
  2026-09.7r4d are superseded: the `meshpost` type is burned, never bring a custodial frame back for this traffic.
  `loraNode` / `loraKey` are profile fields the iOS port reads too (paired change).
  **Before touching `DmAutoReplyPolicy`, `Destination.Reply`, `OutboundFrame.to`, or `MeshtasticLink.send`'s
  `LinkDestination.Direct(ack = false)`:** READ ADR 2026-09.4n5p and its two 2026-10-29 amendments, then ADR 2026-10.ug4a —
  a Meshtastic DM to a set-up board is answered once with a fixed unicast (once per sender per day, once per 30 s for
  anybody, under the window's half ceiling asked before the sender is stamped, priced unsigned, never from a stock or a
  dedicated-slot board), the plane's one direct send without `want_ack`. Its memory persists for the per-sender cap
  across a restart: the board hands a packet to the phone once, so a read DM never comes back (#241).
- **When touching the plane's face — `DeliveryPlane.LoRa`, `InboundFrame.kind`, `LoraPlane`, `LoraStatus` /
  `LoraStatusRepository`, `BoardFilter` / `BoardRef.kind`, `LoraSizeHint`, `ui/chat/LoraReach.kt` (`RoomSaturated`, `RoomBandBusy`, `GroupUnsupported`,
  `LoraOnlySaturated`, `loraHintFor`), or `ui/lora/`:** READ ADR 040 (its §1 collapse amended by ADR 2026-10.e42d), ADR
  2026-09.ursc and ADR 2026-09.6ww7. The kind is for presentation only — carry, relay and convergence never read it,
  and it is never folded into `fromNodeId`. A notice
  about a spent window is published on the send that spent it, and a group says LoRa will not carry it from its roster,
  never the directory. `publishStatus` is serialised on its own lock, taken outside the pacer's and the ledger's
  monitors: the field log's `LoraFieldLog` reads the order of the snapshots as the record, so a stale Ready landing
  over a newer Disconnected is a phantom down and up (ADR 2026-10.a239's 2026-10-18 amendment). Its reason words are
  `MeshtasticSession`'s companion constants, folded onto `LoraLinkEnd` and pinned by `LoraFieldLogTest`. The room's
  composer hint says a post past its one-packet budget, or one with a reply or an attachment, is not carried
  (`loraHintFor`, ADR 2026-10.rp3s); a DM's still hedges. The picker tags each bonded device with its `BoardKind`
  (`BoardFilter.kindOf`): the MeshCore rule first, on the `MeshCore-` name prefix alone and never the generic NUS UUID,
  then the Meshtastic name or cached service; until Stage 5 a MeshCore entry is listed but not pickable
  (`BoardOption.pickable`), so it never binds to the Meshtastic session (ADR 2026-11.gsqu's Stage 3c/3d amendment).

- **When touching the chat's "reached their LoRa radio" — `LoraMeshTransport.onRadioReached` (`frameId`, `peerId`,
  `late`), `radioReached`,
  `MeshManager.onLoraRadioReached`, `MeshController.loraRadioReached`, `LoraRadioReached`, `DeliveryStatus.RadioReached`
  / `ofTick`, `tickGlyph` / `tickGlyphTag`, `AckProof` / `PacketOutcome.Acked.proof` (`ack_proof_status`), or
  `LoraDirectPolicy.launch`'s eviction:** READ ADR 040's 2026-10-31 amendment and ADR 2026-10.x9fa, then "UI surfaces" in
  `lora-bridge.md`. Only a destination's ACK of a `DirectKind.MESSAGE`, on time or late, reports its row id; never an
  implicit ACK, a receipt or a reaction, and never an ack our board found `AckProof.INVALID` (`ABSENT` still counts:
  an unauthenticated routing header). The lambda runs on the plane's one outcome collector: the manager does one
  lock-free update, no database, no launch, no call back into the plane. In memory, bounded, first ack wins, never
  persisted or pruned; `received` always wins in every reader. A flight evicted from the full table is remembered, so
  its late ack still moves the marks.
- **Before adding a second board kind (MeshCore), making `MeshtasticLink` board-neutral, or touching `BoardLink`,
  `BoardKindHooks`, `BoardTraits`, `BoardKind`, `Ceiling.FLOOD`, `meshcoreKey` (there is no `CAP_MESHCORE_DIRECT`: the next entry) or anything
  under `mesh/lora/meshcore/` / `mesh/bluetooth/meshcore/`:** READ ADR 2026-11.gsqu (the build shape: one plane
  instance per board kind over a neutral core, a ledger per board; the compact DM as a COBS-framed contact text DM,
  routed by the firmware and retried by Knit up its own ladder since the 2026-11-19 amendment; own room posts in up to two elided packets under
  `Ceiling.FLOOD`; the zero-hop hearing hook), then ADR 2026-10.jend for the scope it amends, then "The layers" in
  `lora-bridge.md`. Stage 1 (built): the core reads the board through `BoardLink` / `MeshtasticBoardLink` (`BoardState`,
  `BoardPacket`, `SendPurpose`, `BoardSend`), a kind's numbers through `BoardTraits` / `MeshtasticTraits`, refusals through
  `BoardTraits.refusalOf` (`RefusalClass`), and everything only the kind does through `BoardKindHooks` (Stage 1c:
  `MeshtasticHooks`, made per instance with the kind's own `DmAutoReplyPolicy` / `LoraContactPolicy`, reaching back
  through `PlaneCore`; the dispatch `route` → `PacketRoute`, `bookDirectAck`, `onKindPacket`, `postToPublicChannel`,
  `sendKindFrame` / `FragmentOutcome`, `restoreState` / `snapshotState`, `directSlotRefused`, `checkSlot` / `SlotCheck`,
  `onReady`, `provision`, `teachKey` / `KeyTeach` / `KeyWrite`, `onDelivered`, `bookKeyReply`), so the core reads
  `MeshtasticLink` only where it builds the board link and the hooks; `LoraAirtime` prices from
  `RadioParams` and `DutyPolicy`; a compact DM's `DirectSend.airings` ride the frame; `BoardAddr` is kind-tagged
  (`BoardAddr.Meshtastic`, `BoardAddr.MeshCore`, `meshtasticNode`); `MeshManager` sizes compact copies by
  `LoraDirectSink.budgetFor` (`LoraDirectBudget`, `compactTickAcks`) and `compactCopyOf` gives shed LoRa-held acks back to
  the coalescer. Every Meshtastic number stays the constant it was, pinned by `LoraAirtimeParityTest` and
  `BoardTraitsTest`; a trait sets a factory default, never an injected value.
  Stage 2 (built, gsqu's 2026-11-13 Stage 2 amendment): every per-kind surface addresses a plane by `BoardKind`
  (`BoardTraits.kind`, `BoardKindHooks.kind`, the transport's `boardKind`, never `kind`). Koin qualifies each per-kind
  single with `loraPlane(kind)` and the composite takes one child per `BUILT_BOARD_KINDS` (Meshtastic's, and MeshCore's in a debug build since Stage 5a-2;
  `BuildConfig.MESHCORE_PLANE` is read only by that list's guard). `MeshManager` binds `LoraDirectRouter.sinkFor`, asked
  once per offer: the **bound** kind's plane (`SettingsStore.boundBoardKind`, `BoardKind.boundOf`), Meshtastic's with
  none, never the board map or a holder, and no arrival kind on the inbound side. Settings keys per kind are
  `BoardKeys` (the `lora_*` keys stay Meshtastic's; `meshcore_*` declared in `SettingsKeys`, no board contacts in
  DataStore); a bind goes through `BoardBindPolicy` (`SettingsStore.bindBoard`): the other kind's identity cleared,
  `lora_enabled` / `lora_dm_enabled` and the other kind's setup record kept. `LoraPlaneStateStore` takes its kind's key.
  `LoraStatusRepository` emits `perKind` and the bound kind's `facts`, carrying `LoraFacts.board` and
  `LoraFacts.directBudget` (`LoraPlaneStatus.directBudget`, read by `LoraSizeHint.dmBodyBytes` and `loraBudgetFor`, never
  off the transport). `TransportStatus.boardKind` (`BoardKindSource`) keys a LoRa child in the field log and in
  `transportRows`, which shows the bound kind's row alone. `FieldEvent.Lora*` / `Radio` write `board` only where it is not
  Meshtastic (`BoardKind.fieldTag`); streams are `BoardKind.fieldStream`. The `onLora*` counters stay unkeyed.
  On a MeshCore board only people's room posts and reactions flood, plus our own profile right before our first
  flooded post. Floods are scoped to the board's region when one is set, and booked as every repeater's airtime under
  `AirBucket.PUBLIC` (the ledger is one total since ADR 2026-10.ug4a; MeshCore's `GET_STATS` fills the same
  `BoardAir` the brake reads).
  <!-- doc-lint: allow AirBucket — jend's booking as it decided it, before ADR 2026-10.ug4a --> Every room tick and all
  machine traffic stays zero-hop. ADR 2026-10.rp3s narrows this on both boards: only the user's own frames air. ADR
  2026-10.x9fa built the board-neutral seam a second board needs (`LinkDestination`, `BoardAddr`, `PacketOutcome`) and a
  compact DM that fits MeshCore's 164-B cap (113 B of plaintext), settled by the destination board's ack and marked per
  peer (`LoraDirectPolicy`). Since ADR 2026-11.gsqu's 2026-11-19 amendment the compact DM (a message, a reaction, a ✓✓:
  one carrier) is no longer held to the neighbours: the firmware's own flood-then-learned-path routing carries it, zero
  hops tried first and at most one flood a flight (the routed-DM entry below), its floods under `Ceiling.FULL`, booked as
  our own board's air only, and a verified frame heard at any hop count lifts its board's mark. Every transmission carries
  a byte that varies per send, because MeshCore drops byte-identical packets network-wide. The Meshtastic plane's ADRs
  above do not carry over: no signing cliff, no `hop_limit`, no `BoardQuiet`.

- **Before touching what our profile claims about a board — `MeshManager.ownLora` / `OwnLora`, `Protocol.CAP_LORA_DIRECT` as
  we claim it, `MeshManager.bindMeshcoreDirect` / `ownMeshcoreKey` / `watchMeshcoreClaim` /
  `publishMeshcoreClaimIfChanged`, `MeshcoreClaimRule`, `MeshcoreClaimJournal` / `MeshcoreClaimStore`
  (`meshcore_sealed_version`, `meshcore_published`, `meshcore_board_bind`), `ProfilePayload.meshcoreKey` / `meshcoreSig` /
  `ProfileV2` labels 9 and 10, `PeerEntity.meshcoreKey`, `MeshManager.meshcoreBoardMap` / `MeshcoreBoardMap` /
  `MeshcoreBoardHolder` / `MeshcoreRank`, `MeshcoreKey`, `MeshcoreBind`, `MeshcoreConsent.sig`, `MeshcoreSession.bind` /
  `ownNode`, `di/writeMeshcoreClaim` / `meshcoreProofKept`, or the `meshcoreBindUnanswered` / `meshcoreBindRefused`
  counters (`meshcoreBindUnanswered` also counts a bind skipped for want of a node id; the `meshcore bind skipped` line
  tells the two apart):** READ ADR 2026-11.gsqu's Stage 4 amendment and its Stage 5d amendment (both parts), then
  `docs/WIRE_COMPAT.md`'s MeshCore-key precedent, the `MeshcoreBind` entry in `crypto.md`, "The bind" in `lora-bridge.md`'s
  MeshCore contacts section, and the contacts entry in `contacts.md`. **Since Stage 5d the claim is the key and its proof**:
  the board's signature over `knit-meshcore-bind/v1` ‖ our node id ‖ its key (`meshcoreSig`), asked by the session's actor
  after every proof that grants consent and carried on the consent it publishes (`MeshcoreConsent.sig`), so the hooks write
  `(key, sig)` to the claim store off the consent alone, one edit, latest wins; a key without a signature that verifies
  claims nothing, a failed bind publishes the consent unsigned (the store keeps a proof it holds for the same key) and is
  owed on a 10 s – 10 min backoff, and a proof never breaks a tie between two claimants of one key. Each kind claims its own door. `CAP_LORA_DIRECT` is Meshtastic's:
  claimed only beside a bound Meshtastic board (the reported node `loraNode` names), so a MeshCore bind, which clears that
  identity, takes the bit with it. MeshCore's door has **no bit**: the sealed profile's `meshcoreKey` is the claim, sent
  only while the plane and its DM switch are on, a MeshCore board reported a key `MeshcoreKey` accepts, and the build is
  bound to read a MeshCore DM (`bindMeshcoreDirect`, bound by nothing until Stage 5, which binds it from `di/MeshModule`
  beside `bindLoraDirect`). A claim change mints a sealed-only version (`MeshcoreClaimStore`, the badge's rule), never a
  cleartext one. **Owed by Stage 5:** `offerLoraDirect` and the compact-tick gate must read the claim of the kind
  `LoraDirectRouter.sinkFor` answered (`CAP_LORA_DIRECT` for a Meshtastic sink, a stored `meshcoreKey` for a MeshCore one),
  never the bit alone; bind `bindMeshcoreDirect` before `start()`; memoize `MeshcoreKey`'s point check before collecting
  the map; and the board map Stage 5 teaches from is `meshcoreBoardMap` (`DirectContacts`' set only, ranked, our own
  board excluded, contested keys among live claimants marked and never taught, `latestProfileVersion` never a re-teach
  order). The key and its signature are sent to direct contacts only (`MeshcoreAudience`). Regression: `MeshcoreClaimTest`,
  `MeshcoreKeyTest`, `MeshcoreBindTest`, `MeshManagerTest.theLoraDoorIsClaimedOnlyBesideABoundMeshtasticBoard` /
  `theMeshcoreClaimMintsOneSealedVersionPerChangeAndNeedsTheSeam`, `MeshcoreSessionTest`'s bind block,
  `MeshcoreHooksTest.theClaimCarriesTheConsentsBindAndAWithdrawalForgetsBoth`, `LoraPlaneWiringTest`,
  `MeshcoreProfileLabTest`.

- **Before touching the MeshCore client — `MeshcoreSession`, `MeshcoreProto` (`McFrame`, `McReject`, `ContactRecord`,
  `expectedAck`, `ENCODED_COMMANDS`, `KNIT_DATA_TYPE`, `MIN_FIRMWARE_VERSION_CODE`), `Cobs`, `MeshcoreDialer` /
  `NusChannel` / `NusEvent` / `NusDialResult`, `MeshcoreAckTable`, `MeshcorePins`, `MeshcoreHeadroom`, `MeshcoreAirRing`,
  `MeshcoreDetail`, `MeshcorePacket`, `MeshcoreAir`, `MeshcoreSetupRecord` / `MeshcoreSetupResult` /
  `MeshcoreRestoreResult`, `ContactWrite` / `ContactRead`, `MeshcoreRefusal`, `MeshcoreNames`, `MeshcoreCounters`,
  `MeshcoreLinkState`, `NusDialResult.BondRefused`, `MeshcoreGatt`, or `PacketOutcome.flown`:** READ ADR 2026-11.gsqu (§2–§6 and its Stage 3 amendment, which carries
  the 2026-11-13 design passes' binding rules), then "The MeshCore client" in `lora-bridge.md`. The board answers every
  connected client: drained data (7, 8, 16, 17, 27) is never matched to a request and never discarded; `SENT` is ours only
  by the ack code the phone computes, `SEND_CONFIRMED` only by our table, a bare `OK` / `ERR` is followed by a barrier on
  a timeout and every relied-on write is read back. A DM goes on the route the board holds, up the session's ladder (the
  routed-DM entry, next); its attempts 0..2 keep the timestamp and change `attempt`; its outcome carries the exact count that flew
  (`flown`), which the core's refund reads instead of the elapsed-time guess (Meshtastic leaves it null). The ack table is
  the board's circular eight, filled by every client. Teaching writes only into a confirmed free slot; setup reads first,
  erases (Stage 5b-2, the next entry), then writes `[38, 1]`, auto-add off, `Knit abcd` (only on a board this phone holds
  no record for: a re-setup keeps the name the board carries, ADR 2026-11.f4d3) and the channel, each read back;
  restore touches only what still reads as Knit's, so a handle is left. The encoder's command set is pinned (`MeshcoreProtoTest`): never a key export or import, a PIN, the radio, a raw
  packet or the CLI. Room posts ride `DATA_TYPE_DEV` until registered, and a release build may not ship on it. Pinned
  against the real firmware's frames (`src/test/resources/meshcore/`) and, through `MeshcoreFakeVsSimTest`, the fake.
  The Android half (Stage 3c, `mesh/bluetooth/meshcore/MeshcoreGatt` over the shared `BoardGatt`) is routed in
  `bluetooth.md`; a dial whose own subscribe failed the bond is terminal (`NusDialResult.BondRefused`), as a first write's; after a close
  under an op that started unbonded the bond is read settled (`BondSettle`, ≤ 2 s), never a `BONDING` the disconnect beat.

- **Before touching how a MeshCore DM is routed — `MeshcoreSession.nextRung` / `writeZeroHop` / `floodRung` / `afterFlood` /
  `runRead` / `taughtAndRouted` / `stillTaught`, `ROUTE_WRITE_WORST_MS`, `FLOOD_GATE_TIMEOUT_MS`, `MAX_FLIGHT_MS`, `MeshcorePins.routeOf` /
  `noteRoute` / `forgetRoute` / `queueRead` / `Job.READ` / `RETEACH_GAP_MS` / `spendLadderWrite` / `MAX_LADDER_WRITES_PER_DAY`, `MeshcoreContacts.isKnitRouting`, `ContactRecord.knit`,
  `MeshcoreAir.DirectRouted` / `ReciprocalPath`, `MeshcorePacket.senderRoute`, `MeshcoreTraits.directHopsOf` / `pathBytesOf` /
  `isZeroHopRoute` / `reciprocalPathPhyBytes` / `contactTextPhyBytes` / `ackPhyBytes`, `BoardTraits.heardRoute`,
  `MeshcoreSessionObserver.floodWanted`, `PlaneCore.directStillWanted`, `LoraDirectPolicy.waitingFlight` / `onHeardFar`,
  `LoraReprobe.gapOf`,
  `MeshcoreRefusal.ROUTE_UNWRITTEN` / `ROUTE_BUDGET_SPENT`, or the `meshcoreDirectFlooded` / `meshcoreRouteResets` / `meshcoreRouteHops` /
  `meshcoreZeroHopWrites` / `meshcoreFloodsSkipped` / `meshcoreRouteReads` / `meshcoreLadderWritesDeferred` / `meshcorePathSurcharges` /
  `meshcoreReciprocalsBooked` counters:** READ ADR 2026-11.gsqu's 2026-11-19 amendment (draft), then "The MeshCore client" in
  `lora-bridge.md`. No pin: the firmware routes a DM by flood with no path and along the path a PATH return taught it, and
  Knit keeps that path (a reconcile, a teach or a `0x81` never rewrites it; a `0x81` is one read). The session's ladder
  reads the record back after every unanswered attempt and picks the rung from it — a flight started on N > 0 hops: zero
  hops once (one `ADD_UPDATE_CONTACT`, never on `0x81`), then reset and flood; one started at 0 or UNKNOWN: zero hops twice,
  then reset and flood — and floods at most once a flight, asking the core first whether the frame is still wanted (its
  `RideGate`: link, spool cover, a direct holder still, ✓✓), re-checking the flight after every write (a late confirm stops
  the flood), and waiting one more `est_timeout` after an unanswered flood before it ends. A key no longer taught ends the
  flight `NoOutcome`; each key's zero-hop writes and resets are bounded a UTC day. A send reads the record whenever the cache
  is empty or UNKNOWN, and a `0x81` forgets the cache, so a learned path is never written over; a send whose record reads
  UNKNOWN writes zero hops before attempt 0.
  Booked: our own board's airing only (the route's surcharge over the zero-hop price, the reciprocal PATH), under
  `Ceiling.FULL`, never a repeater's (the user's decision). A verified, fresh frame lifts a mark at any hop count
  (`heardRoute`), one heard through repeaters at most once a re-probe gap (`onHeardFar`); only zero hops counts a radio, and a
  contact DM only by its own flood path length. Regression: `MeshcoreSessionTest`'s route block, `MeshcoreHooksTest`,
  `MeshcoreTraitsTest`, `MeshcoreFlushSwitchTest.theFloodRungAsksTheCoreTheFramesOwnGatesAgain`, `MeshcoreDirectLabTest`'s
  chain, dead-neighbour and diamond scenarios on the channel air.

- **Before touching MeshCore's price, band or law — `MeshcoreTraits` (`phyBytes`, `airMs`, `preambleSymbolsAt`, `FIXED_MS`,
  `pathReturnPlaintext`, `dutyPolicy`, `maxFlights`, `deadlineMs`), `LoraBand` (`holding`, `dutyPercent`,
  `STRICTEST_PERCENT`), `MeshcoreDutyLaw`, `MeshcoreAirRing`'s uptime reset, `MeshcoreRefusal.classOf`, `SessionOp`,
  `MeshcoreSession.WRITE_TOO_LONG` / `CommandRefused` / `MeshcoreCounters.commandsRefused`:** READ ADR 2026-11.gsqu's Stage
  5a-1 amendment (and the maintainer's Stage 5 decisions above it), then ADR 2026-10.ug4a, then "MeshCore's price, band and
  law" in `lora-bridge.md`. Pure and JVM-tested, wired in debug builds since Stage 5a-2 (the next entry). A MeshCore packet is priced by purpose off
  the firmware's own framing — 4 B of transport codes on a scoped **flood** only, never a zero-hop DM or an ack; AES to 16
  — at the formula with the firmware's preamble (32 symbols at SF ≤ 8) plus 17 ms, never Meshtastic's signing facts or
  LongFast. **The law is Knit's on this kind** (its firmware has a 50 % token bucket and no region): the duty comes off the
  frequency and bandwidth (`LoraBand`: the whole channel inside a row, the strictest holding row, else 0.1 %, never
  `FALLBACK_PERCENT`; EU edges to check against EN 300 220), and `MeshcoreDutyLaw` holds every Knit frame while the board's
  trailing-hour `tx_air` (every client, acks, repeats) reaches it — on the board's uptime clock, its ring the state to
  persist, a new boot dropping it, and with no hour-old baseline the counter since boot read as spent, so a restart never
  hands out a fresh hour, nor a board reboot (the old boot's hour and the unseen stretch to the reboot carried as a pre-boot term on the wall
  clock). Each EU
  duty assumes its row's power limit; the board's power is the user's. On a 0.1 % or unmatched band the plane is
  effectively dead, by design. Two flights (8 ack codes / 3 airings); a deadline never under `MAX_FLIGHT_MS`. A send its call
  deadline caught unstarted is `Busy`, never `Timeout`; the deadline and the actor's refusal each claim in one atomic step
  (`SessionOp`). A write over `MAX_NOTIFY` or refused as too long refuses that one command, never the session.

- **Before touching the MeshCore plane in the core — `MeshcoreHooks` (`route`, `bookDirectAck`, `checkSlot`, `boundSlot`,
  `PUBLIC_SLOT`, `NO_CONFIGURED_SLOT`, `cleanVersion`, `digest`), `MeshcorePlaneCounters`, `MeshcoreBoardOps`,
  `MeshcoreSessionObserver`, `meshcorePlane` / `meshtasticPlane` / `BoardPlane`, `BoardTraits.live` / `heardZeroHop` /
  `hearsSender`, `LoraAuthorCheck` / `pinnedAuthorCheck`, `AuthorRadio`, `LoraPacePolicy.holdForLaw` / `lawHeld`,
  `PlaneCore.holdForLaw`, `LoraAirtime.recordMs` / `airMsOfPhy` / `forgetRadio`, `LoraPlaneSnapshot.meshcoreLaws` / `MeshcoreLawStamp`, `LoraFacts.lawHeld` /
  `LoraStatus.lawHeld`, `NusDialResult.ServiceAbsent`, `MeshcoreSession.UNBONDED_POLL_MS`, `BoardKindHooks.extendSnapshot`, `BoardBinding.dialled` /
  `LinkState.Ready.address` / `MeshcoreDetail.dialled`, `di/builtBoardKinds` / `bindingAddress` / `loraPlaneOf`,
  `LoraDoorClaim`, `LoraFacts.floodSpent` / `roomBodyBytes` / `directReach`, `LoraSizeHint.MESHCORE_ROOM_BODY_BYTES`, or
  `…debug.LORA --es kind meshcore`:** READ ADR 2026-11.gsqu's Stage 5a-2 amendment (and the maintainer's Stage 5 decisions,
  the 5a-1 amendment), then "The MeshCore plane in the core" in `lora-bridge.md`. Debug builds only (`MESHCORE_PLANE`); a
  release build constructs no MeshCore child. The board is Knit's: `route` reads the slot and data type before decoding and
  counts and discards everything not Knit's as `DROPPED`, never a hearing (Public's channel data, and a `0x04` fragment of
  more than two parts or none that parses; Stage 5c-1 reads the rest, the next entry but one; a text on Public slot 0 is the
  kind's since Stage 6, the MeshCore Public entry below). Our board's answers are booked in `route` for every live acked contact DM, Knit's or not;
  a DM of ours books only what its route adds and its reciprocal PATH (the routed-DM entry). A hearing that lifts a mark on
  MeshCore is a verified, fresh, live Knit frame at any hop count; one that counts a radio is zero-hop as well,
  its radio counted by author; never a sender prefix. The ledger takes the board's radio and band before Ready (or forgets
  the last board's), and the duty law holds the pacer to its release, its rings persisted per board digest and its hold on the face (`lawHeld`). An unbonded board is never dialled in the
  background's place (only the hourly direct net), and a board without NUS twice in a row is terminal. An opened DM is a
  hearing only fresh. Release gate: presence on MeshCore is unverified (#283). A session's
  Ready carries the address it dialled and the binding is written under it. An offer reads the door claim of its sink's
  kind. Since 5b-2's second part the plane is fed `meshcoreBoardMap` (`di/meshcoreBoardHolders`, no placeholder first) and
  `bindMeshcoreDirect` is bound before start where the child is built (`bindsMeshcoreDirect`); no device name is stored.

- **Before touching MeshCore's contacts — `MeshcoreContacts` (`holdersOf`, `wanted`, `judgeTeach`, `plan`, `isKnitRecord`,
  `MAX_REMOVALS_PER_READY`, `MAX_OVERWRITES_PER_READY`), `MeshcorePins.keyFor` / `learn` / `contest` / `teaches`,
  `MeshcoreSession.setKnitContacts` / `teach` / `removeContact`, `MeshcoreProto.listContacts`, the setup's erase or Restore's
  contact clearing, `BoardKindHooks.onBoardMap`, `LoraBoardHolder.rank`, or `MeshcoreSetupResult.Done.erasedContacts`:** READ
  ADR 2026-11.gsqu's Stage 5b-2 amendment (and the maintainer's Stage 5 decisions), then "MeshCore's contacts" in
  `lora-bridge.md`. **And for `MeshcorePins.setTaught` / `queueTeach` / `retryTeachOnce` / `MAX_LIVE_TEACHES_PER_DAY`, the
  live teach (`runLiveTeach`) or `writeKnitRecord`'s taught checks:** the 5c-2b amendment too — a key the set gains while
  Ready is written at once, at most 4 times a key a UTC day (past it the next Ready's reconcile), never when contested, and
  a write is made and kept only for a key the set still holds before the write and after the read-back. The board is Knit's: its table is the taught set. The session sends to and writes only taught keys
  (security B1), and nothing at all — no DM, route write, teach or removal — off a board set up for Knit, which needs this phone's
  `meshcore_setup` record for the dialled address and the board's key digest, not pending (the single source of consent,
  read and written by the session's actor alone — in its handshake before Ready, and in its own setup, Restore and unbind —
  and only on a bonded link whose board signed a fresh challenge with the key it reports (`MeshcoreProto.signStart` /
  `signData` / `signFinish`, `MeshcoreSession.CONSENT_DOMAIN`; a clone of the address can report the key, never sign); the hooks observe `MeshcoreBoardOps.consent` for the offers and the claim; `MeshcoreSetupJournal`,
  `MeshcoreHooks.unbind`, the 5b-2 second part's amendment) besides the board's Knit slot and name; a holder the taught set
  lacks is never offered (`BoardKindHooks.offered`, `PlaneCore.holdersMoved`); a prefix two keys share is taught to neither and refused at `learn` (B3); the Ready reconcile runs only on
  a board set up for Knit and once the taught set is known, from **one** `GET_CONTACTS` listing a Ready (never one per key),
  bounded per Ready; setup erases before it writes, never `CMD_FACTORY_RESET`; Restore removes Knit's contacts and brings
  back nothing the erase removed. No key, prefix or `Knit xxxx` name in any line (`BoardAddr.MeshCore` and
  `LoraBoardHolder` print neither). Regression: `MeshcoreContactsTest`, `MeshcoreSessionTest`'s Stage 5b-2 block,
  `MeshcoreHooksTest`'s taught-set block, `MeshcorePoliciesTest`.

- **Before touching MeshCore's room posts or the reassembler — `LoraFragCandidates` (`capacityFor`, `perPartFor`,
  `MAX_BYTES`, `SHORTEST_FRAGMENT_BYTES`), `FastFrameCodec.TAG_BOUND_FRAG` / `fragmentBound` /
  `parseBoundFragment` / `commitmentOf` / `COMMITMENT_BYTES`, `LoraAuthorIds` / `LoraAuthorDirectory` / `di/loraAuthorIds`,
  `BoardTraits.elidesRoom`, `MeshcoreTraits.ROOM_PART_LIMIT`, `LoraFrameCodec.encodeBest`'s `elide` / `bind` /
  `Encoded.elided`, `LoraMeshTransport`'s `readVerified` / `verifiedElided` / `candidateCapacity` / `candidatesPerPart` /
  `fragIds`, `FastPathDrop.FRAG_CANDIDATES_FULL` / `FRAG_UNVERIFIED` / `ELIDED_UNVERIFIED`,
  `LoraSizeHint.MESHCORE_ROOM_BODY_BYTES` / `MESHCORE_ONE_PACKET_ROOM_BODY_BYTES`, `AirtimeSnapshot.roomPacketsFit`, or
  `LoraDirectSource.unackedTo`'s `maxBytes`:** READ ADR 2026-11.gsqu's Stage 5c-1 amendment (with its two review rounds),
  then "MeshCore's room posts and the reassembler" in `lora-bridge.md`, and `docs/WIRE_COMPAT.md`'s `0x07` precedent. Debug
  builds only. An own room post rides elided (`0x06`) in at most two bound packets (`0x07`, bound both ways: each part
  carries 8 bytes of SHA-256 over the other's slice; never padded), each under `Ceiling.FLOOD`, its id drawn fresh per frame
  (`SecureRandom`); 184 B of text, less where the band's flood ceiling admits fewer packets. A MeshCore receiver reads no
  unbound `0x04` and trusts no arrival order (the firmware can hold a flood for seconds: score delay, CAD, every hop): a head and a tail pair
  only when each names the other, one verification a pair, so a fake costs memory and never a check; each side keeps what
  the receiver can decode within the 60-s window (`perPartFor`, 306 at the companion default, off the shortest part's least airtime `LoraAirtime.leastRoomAirMs` since 5c-2a: CR 4/5 behind a 6-symbol preamble, an assumption a board owes), a random waiting part replaced past it; every
  part ages on its own arrival; entries and bytes capped. A prefix resolves against accepted contacts first, every pinned
  peer only when no contact's candidate verifies; a stranger's post never resolves, and on MeshCore only a verified frame is
  `reachable` presence. Meshtastic keeps its seeded counter and sender-keyed `FragReassembler`, and never reads `0x06` or
  `0x07`. Agree the bound form with iOS before MeshCore reaches iOS. `LoraDirectSource.unackedTo` takes `maxBytes` on every
  source. Regression: `LoraFragCandidatesTest`, `MeshcoreRoomPostTest`, `LoraFragVectorTest`, `BoardKindFramePolicyTest`,
  `LoraSizeHintTest`, `CoordinationPlaneSizeBudgetTest`'s MeshCore column.

- **Before touching `FakeMeshcoreAir`, `MeshcoreChannel`, `FakeRadio`, `AirPacket`, `quickMeshcoreAir`, `MeshcoreAirRig`,
  `MeshcoreAirPhysicsTest`, `FakeMeshcoreAirTest`, `MeshcoreFlushSwitchTest`, or `BoardTraits.leastRoomAirMs` /
  `LoraAirtime.leastRoomAirMs`:** READ ADR 2026-11.gsqu's Stage 5c-2a amendment, then "The fake" in `lora-bridge.md`'s
  MeshCore client section. The channel is opt-in (`FakeMeshcoreAir(channel = …)`): without one every Stage 3b behaviour
  `MeshcoreFakeVsSimTest` pins stays as it was; with one the air is modelled from the firmware source (an independent
  Semtech airtime checked against Q5, one reception at a time, the queue, budget and listen-before-talk, the repeater's
  drawn delay, the 160-entry ring, paths, the Q13 PATH). Never price the fake with `LoraAirtime`: its airtime is the
  oracle the plane's figures are checked against. Draws come from `Random(mix(runSeed, repeater, origin, n))`, never a
  shared source; order a race with `hold` / `release`, never a draw. The plane sizes `LoraFragCandidates` off the shortest
  part's **least** airtime (`leastRoomAirMs`: unscoped, no `FIXED_MS`), never the booked price.

- **Before touching `MeshLab.meshcoreAir`, `MeshLab.node(meshcore = …)`, `MeshLab.awaitTaught`, `MeshLab.meetOverMeshcore`,
  `LabNode.meshcoreOn` / `awaitMeshcoreBack` / `meshcoreWrites` / `postPublic` / `meshcorePostsHandled` /
  `meshcoreOutOfRange`, `StockMeshcoreNode`, `MeshcoreRoomLabTest`, `MeshcoreDirectLabTest`, `MeshcorePublicLabTest`,
  `MeshcoreSession.taughtAndRouted`, `MeshcoreCounters.routeReads` / `reconcilesDropped`, `MeshMetrics`' `loraBoundPartsTaken` /
  `loraNotOurs`, `LoraMeshTransport.resolvesAuthor`, `MeshLab.awaitAuthor`, `LabNode.meshcoreReadyFor`,
  `LabLimits.meshcoreBackoffMs` or
  `MeshManager.onDmAcksInlined`:** READ ADR 2026-11.gsqu's Stage 5c-2b amendment (and its Stage 6 amendment, part 6c, for
  the Public room's lab), then `testing.md`'s MeshCore lab bullet.
  A lab MeshCore node is the production child (no injected pace, the claim through `bindingAddress` under the hooks'
  `MeshcoreClaimEdits` lock, the setup and its consent through the real `setup()`), never the boards sweep's Meshtastic
  air, on three clocks (the session monotonic; the plane, hooks and session wall clock `LabClock`; the board's uptime the lab's calendar),
  its air on a lab-owned serial scope. Boot returns at Ready with consent and the claim minted; a pair that are each
  other's direct contacts is taught both ways before `awaitAcquainted` returns. Every wait reads the receiving side's event
  (a plane counter, the board read back, the session's route read), never the air's record.

- **Before touching `MeshMetrics`' `loraHearingsJudged` / `loraDirectDuplicates`, `LoraMeshTransport.hearAuthor`'s exits,
  `LoraMeshTransport.airtimeUsedMs`, `FakeMeshcoreAir.loseAck`, `FakeMeshcoreBoard.noPeerDrops` or
  `MeshcoreMixedFleetLabTest`:** READ ADR 2026-11.gsqu's Stage 8 (lab) amendment, then `testing.md`'s MeshCore lab bullet.
  `loraHearingsJudged` is bumped on **every** exit of `hearAuthor` — the synchronous refusal and the end of the author
  check, after its effects — and is a diagnostic, never a hearing count: a scenario waits past it before reading that a
  flooded post lifted nothing. `loraDirectDuplicates` counts a compact DM copy this sender's row or mark already held
  (`InboundPipeline`'s re-ack branch): a MeshCore retry after a lost board ack is new ciphertext. `airtimeUsedMs` is the
  ledger's total for the lab, no seam for production. The fake's `loseAck` is keyed by `(acker, sender, attempt)` — lose
  one flight's ack with a one-shot guard, never every attempt-0 ack both ways — and `noPeerDrops` is the firmware's
  `recv matches no peers` (`Mesh.cpp:186`). Both counters are in the `…debug.LORA` dump.

- **Before touching `LoraMeshTransport.onOutcome` / `launchFlight`, `LoraDirectPolicy.arrive` / `launchClaiming` /
  `EARLY_OUTCOME_TTL_MS` / `MAX_EARLY_OUTCOMES`, or the `onRadioReached` contract:** READ ADR 2026-11.gsqu's Stage 5c-2b
  amendment (round 2, B). The outcomes collector and the pacer run concurrently, so an answer can outrun its send's
  registration: one for a handle neither waiting nor ended is held (5 s, 16) under the policy's monitor and settled by the
  launch that registers it, whichever side takes it processing it. A refusal and an implicit ack are never held; a stale
  record never settles a reused handle.

- **When touching the MeshCore handle — `MeshcoreHandle`, `MeshcoreNames.check` / `isDefault` / `MeshcoreNameCheck`,
  `MeshcoreSession.rename`, `MeshcoreRenameResult`, `LoraPlaneStatus.renameMeshcore`, `MeshcoreDetail.namedDefault`,
  `MeshcoreBoardFace.handle` / `namedDefault` / `handleUnpostable`, `PublicPostRefusal.NAME_CHANGED` / `NAME_UNPOSTABLE`,
  `SettingsStore.meshcorePostConsentCustomName` / `clearMeshcoreCustomNameConsent`, `meshcoreCustomNameConsentReset`,
  `MeshcoreNaming`, `ChatUiState.meshcoreHandle` / `meshcoreBoardCustom`,
  `ChatViewModel.acceptPublicConsent`'s `customNameShown`, or the radio screen's name row:** READ ADR 2026-11.f4d3. The board's
  name is its owner's: setup writes `Knit abcd` only on a board this phone holds no record for, Knit never writes over a
  name it did not write, and the name is no part of the set-up gate. Post consent counts only while the live name is the
  default or `meshcorePostConsentCustomName` is set (the sheet or Knit's rename dialog, never an outside rename; cleared
  when the board is under its default again, at a Forget and at a bind of another MeshCore board); the
  composer's name rides to `post` and the frame, and a mismatch at either is `NAME_CHANGED`. The handle is memory-only:
  never DataStore, a backup, a log line or the bridge's reply (`named: default|custom|unpostable`).
- **Before touching MeshCore's Public mirror — `MeshcorePublicJudge`, `MeshcorePublicPost`, `MeshcorePublicSlot` /
  `MeshcoreDetail.publicSlot`, `MeshcoreProto.PUBLIC_GROUP_PSK`, `MeshcoreTraits.publicPrefixBytes` / `publicTextCap` /
  `PUBLIC_TEXT_CAP` / `publicCeiling`, `BoardTraits.publicCeiling`, `MeshcoreHooks.postPublic` / `onKindPacket` / `sendKindFrame` / `publicFace`,
  `meshcorePlane`'s `onPublicPost`, `MeshcoreSession.sendPublic`, `MeshcoreBoardOps.ready`, `MeshcoreRefusal.NOT_SET_UP` /
  `NO_PUBLIC_SLOT`, `BoardKindHooks.postPublic`, `LoraMeshTransport.postPublic`, `PublicPostResult` / `PublicPostKey`,
  `SendPurpose.PublicPost.timestampSecs`, `OutboundFrame.timestampSecs`, `PlaneCore.enqueuePublic` / `faceMoved`,
  `LoraAirtime.publicPostBytes` / `AirtimeSnapshot.publicPostBytes`, `LoraStatus.publicChannel` / `PublicChannelFace`,
  `LoraFacts.publicPostBytes`, `PublicPostRefusal.NOT_SET_UP` / `NO_CHANNEL`, `PublicPostEnded` /
  `LoraMeshTransport.publicPostsEnded` / `BoardKindHooks.publicPostsEnded`, `MeshcorePublicChannel`, `OrderedRoomSink`,
  `InboundPipeline.heardStamp`, `MESH_POST_ROOM_BACKLOG`, or the `meshcorePublic*` counters
  (`meshcorePublicBacklogDropped` among them):** READ ADR
  2026-11.gsqu's Stage 6 amendment (6a; 6b and 6c amend it) and ADR 2026-11.f4d3, then "MeshCore's Public channel" in
  `lora-bridge.md`. Debug
  builds only. Heard posts reach either bridged room in the order the board handed them over (`OrderedRoomSink`, one
  per plane, bounded and counted; stamps strictly increasing under `heardPostLock`), never a launch per post. Slot 0 is whatever it holds, read off the Ready (never a constant `Public`); a heard text there is judged
  (split at the first `": "` behind 1–31 B, cleaned, keyed on the sender's stamp and the raw bytes) and handed to the room as
  a stranger's, never a hearing, never a Knit frame, dropped where it lands while `meshcoreRoomEnabled` is off. A post leaves
  only from a board set up for Knit (slot and consent; the name is no gate, ADR 2026-11.f4d3) whose live name passes
  `MeshcoreNames.check` (`NAME_UNPOSTABLE`) and equals the one the composer's consent saw (`NAME_CHANGED`), re-read at the
  write (`SELF_INFO` and slot 0: a companion serves several clients at once), under `Ceiling.FLOOD` beside Knit's room
  posts, priced and capped from the name bytes the frame carries (`"<name>: "`, never a constant), one per 30 s, its
  stamp fixed at enqueue and carried by the frame so our own row's id matches what receivers read, fresh for
  `FRESH_MS` past it (never written later); a refusal at the write republishes the Ready, and every end without airing is
  reported by key on `publicPostsEnded`; never refused or deduped by name. Nothing about a post in a line: counters only. Regression: `MeshcorePublicJudgeTest`,
  `MeshcorePublicPostTest`, `MeshcoreHooksTest`'s Stage 6 block, `MeshcoreSessionTest`'s Public write block,
  `MeshcoreHearingTest.aPublicTextIsNoHearingEvenWhenItsBytesAreAVerifiedKnitPost`, `MeshcoreTraitsTest`, `FakeMeshcoreAirTest`,
  `LoraStatusRepositoryTest`.
- **Before touching the MeshCore room's DI seam — `di/LoraWiring`'s `postToMeshcorePublic`, `meshcorePostEnds`,
  `meshcoreRowId`, `meshcoreHeard` or `meshcoreBoardName`, or `MeshModule`'s `onPublicPost` /
  `meshcorePublicChannel` / `meshcorePostsEnded` wiring:** READ ADR 2026-11.gsqu's Stage 6 amendment (part 6b), then the bridged-rooms entry in
  `messages.md`. The plane's types stop at `di/`: a heard post crosses as `MeshcorePost`, a post's answer as
  `PublicChannelAnswer`, an end as `PublicPostEnd`, each keyed by `meshcoreRowId` (`FrameId.forMeshcorePost` over the
  `PublicPostKey`) so a heard post and our own meet on one row. A post reaches the MeshCore plane only while a MeshCore board
  is the bound one (`NO_BOARD` otherwise); the ended flow is read lazily, collected from `MeshManager.start`. Regression:
  `LoraPlaneWiringTest`.

- **Before touching the MeshCore board's face, Forget, a different board, the wrong-firmware cause or the raw send —
  `LoraStatus.meshcore` / `MeshcoreBoardFace`, `BoardSetupState`, `MeshcoreSetupStates`, `MeshcoreBoardOps.setupState` /
  `MeshcoreSetupView`, `MeshcoreProto.sharesPosition` / `MeshcoreDetail.sharesPosition`, `SetupPreview` /
  `LoraPlaneStatus.setupPreview`, `MeshcoreForget` / `MeshcoreForgetResult` / `di/meshcoreForget`, `MeshcoreForgetPolicy`
  (`SettingsStore.forgetMeshcoreBoard` / `withdrawMeshcoreClaim`), `MeshcoreHooks.forgetClaim` / `claimsFenced`,
  `MeshcoreConsent.seq`, `MeshcoreUnbind.MISMATCH`, `ReplaceToken`, `ProvisionResult.DifferentBoard`,
  `MeshcoreSetupResult.DifferentBoard`, `provisionReplacing` / `setupReplacing`, `MeshcoreSetupEntry.fingerprint`,
  `PairingCause`, `SendPurpose.Debug` / `Destination.Debug` / `PlaneCore.enqueueDebug` / `MeshcoreHooks.debugSend` /
  `MeshcoreRawSend` / `MeshcoreDebugSend`, `meshcoreFaceOf`, or the
  bridge's `prov preview|forget`, `--ez replace` and `LORATX --es kind meshcore`:** READ ADR 2026-11.gsqu's Stage 7a amendment,
  then "Stage 7: the radio screen's half" in `lora-bridge.md`. Debug builds only. Facts are counts and booleans; the
  setup state is never NONE for what was not read; the position is a boolean and the coordinates are read by nothing. The
  Forget is one function on the app's scope; PENDING keeps the binding and withdraws the claim; every edit is behind the
  hooks' claim fence, so no grant made before a Forget lands after it. A different board refuses the setup before any write
  and a replace runs every step under a token for the record as it was. The raw send goes through the pacer, the ledger,
  the law and the actor, on the Knit slot only, and never echoes its text. Regression: `MeshcoreProtoTest`,
  `MeshcoreSessionTest`'s Stage 7 block, `MeshcoreHooksTest`'s Stage 7 block, `MeshcoreForgetTest`,
  `MeshcoreForgetPolicyTest`, `MeshtasticServiceAbsentTest`, `MeshcoreForgetLabTest`.

- **Before touching the radio screen's MeshCore half — `LoraRadioViewModel`'s `meshcore` / `forget` / `meshcorePlane`,
  `MeshcoreRows` / `MeshcoreSetup` / `MeshcoreErase` / `MeshcoreForgetNotice`, `BoardOption.kind`, `pickBoard` / `forgetBoard`
  / `keepBoard` / `forgetAnyway` / `replaceBoard`, `LoraRadioUiState.anyKind` / `wrongFirmware`, or the screen's
  `MeshcoreSetupSection` / `MeshcoreSetupConfirmDialog` / `MeshcoreReplaceDialog` / `MeshcoreForgetPendingDialog`:** READ ADR
  2026-11.gsqu's Stage 7b amendment, then "Stage 7b: the screen" in `lora-bridge.md`. With the flag off (or the plane or
  Forget unresolved) the screen reads as a Meshtastic-only build's and reaches no MeshCore row or action. With a MeshCore
  board bound, a pick or a Forget runs `MeshcoreForget` first and waits on its answer; PENDING asks Keep it or Forget anyway.
  The confirmation shows the erase line always and the position line unless the board is known not to share. Regression:
  `LoraRadioViewModelMeshcoreTest`, `LoraRadioMeshcoreScreenTest`.

## Adding an entry

A new decision about this plane gets a bullet here — the symbols that trigger it, the ADR, and the invariant in a
sentence or two — never a bullet in `AGENTS.md`. A symbol listed nowhere is not exempt: `lora-bridge.md` and the
symbol's KDoc often carry the field trial that shaped it.
