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
2026-10.a239 and its 2026-10-18 amendment). The GATT client
(`MeshtasticGatt`, the plane's only `android.bluetooth.*` importer; `mesh/lora/` is pure and JVM-tested) is also
Bluetooth code: a change there owes `bluetooth-reviewer` as well as `lora-reviewer`.

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
- **When touching `LoraMeshTransport`'s shape — `fastFanout` / `longRangeFanout`, `neighbors`, `shortRange`,
  `LoraFramePolicy` (`isFresh`, `FRESH_MS`), `shouldLongRangeFanout`, `SettingsStore.loraDmEnabled`, or
  `BuildConfig.LORA_PLANE`:** READ ADR 038, ADR 2026-09.6gtm, ADR 2026-10.rp3s and ADR 2026-10.x9fa, then the "Shape"
  and "DMs" sections of `lora-bridge.md`. The plane is a fast-plane-only composite child (`neighbors` always empty,
  `send`/`sendFile`/`sendDigest`/`fastSend` no-ops), carrying its own user's one-packet room posts on `fastFanout` and
  its DMs as compact unicasts through `LoraDirectSink.offer` — never a DM-form mesh frame (`longRangeFanout` is a no-op
  until the seam is deleted), group-form chat, `typing`, `blobreq`, `keyreq`, a `profile` or a room reaction. The room
  post is not a wire change: `sig`/`signed` pass byte-exact. The flag is on everywhere since 2.5.0, `loraEnabled` still
  defaults false; `loraDmEnabled` gates our compact sends only, never what our board already acked.
  **Before touching a compact DM — `LoraDirectSink.offer`, `LoraDirectCrypto` / `LoraDirectOpen`, `LoraDirectInbox`,
  `LoraDirectSource`, `LoraBoardHolder` / the board map, `LORA_DIRECT_TAG`, `DirectKind`, `DirectSend`,
  `LoraDirectPolicy` (`mustWait`, `holds`, `onHeard`, `deadlineMs`, `retriesBefore`, `RETRANSMITS`, `boardAcks`,
  `ACK_PAYLOAD_BYTES`), `RideGate.direct`
  / `reachHeld` / `unackedFrameId`, `StaleAtSend.NOT_DIRECT` / `HELD` / `ACKED` / `SLOT_ZERO`, `noteHeard` /
  `onBoardHeard`, `onDirectPacket`, `LoraReprobe` / `reprobeDue`, `frameKey` / `LoraPacePolicy.hasQueued` /
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
  the deadline holds offers until a live hearing of that board (RSSI, not MQTT, not our board, fresh `rx_time`) or the
  re-probe; a destination's refusal holds until their profile changes or 4 h pass. Every send ends at its deadline. The re-probe
  keys on our DMs with no ✓✓, whatever the board said. A board-map source that has not reported reads "not now"; a
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
  `AirtimeSnapshot`, or `MeshTransport.FanoutHint`:** READ ADR 2026-10.ug4a, then
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
  and the unflown ones refunded on its ack (ADR 2026-10.x9fa).
  The ledger is not persisted and nothing seeds it (ADR 2026-10.ug4a): a restart starts the window empty.
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
- **When touching `skipCovered` / `coverReason`, `suppressDataPath` / `linkIds`, `coveredByInternet` on this plane,
  `RideGate` (`authorId`, `direct`, `reachHeld`, `unackedFrameId`), `staleReason` / `StaleAtSend`,
  `OutboundFrame.sigKey`, `sendFrame`, or `selfIdKnown`:** READ ADR 054 and ADR 2026-09.5dt2 (and ADR 2026-09.y5f3 for
  the Internet cover, ADR 2026-10.rp3s for the author, ADR 2026-10.x9fa for a compact DM's gates). A compact DM to us,
  or to a peer a short-range plane holds a **live link** to (links, never sightings; `suppressDataPath` is the only
  feed), or one a spool covers, never airs; the queue is a time-delayed commitment, so each frame carries the gates its
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
  `MeshtasticGatt` (`negotiateMtu`, `SETTLE_MS`):** READ ADR 038 §2, ADR 041, ADR 2026-10.x9fa (the board-neutral seam,
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
  doesn't; the pacer parks on `link.state`, never its 1 s tick.
- **When touching the Meshtastic room — `onLoraPacket`'s slot-0 branch, `PublicChannelPolicy`, `PublicPostPolicy`
  (`onAirText`, `onAirBudget`), `postToPublicChannel`, `Ceiling.HALF`, `mesh/crypto/XeddsaVerify`,
  `ProfileContent.loraNode` / `loraKey`, `BoardBinding` / `onBoardBound`, `LoraFacts.roomSignedUpTo`,
  `PeerRepository.findByLoraNode`, `messages.originPeerId` / `originSigned`, or `SettingsStore.loraRoomEnabled`:** READ ADR 2026-09.26q3, ADR 2026-09.9469, ADR 2026-09.ggq4, ADR 2026-09.sre4 and
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
  `LoraStatusRepository`, `BoardFilter`, `LoraSizeHint`, `ui/chat/LoraReach.kt` (`RoomSaturated`, `RoomBandBusy`, `GroupUnsupported`,
  `LoraOnlySaturated`, `loraHintFor`), or `ui/lora/`:** READ ADR 040 (its §1 collapse amended by ADR 2026-10.e42d), ADR
  2026-09.ursc and ADR 2026-09.6ww7. The kind is for presentation only — carry, relay and convergence never read it,
  and it is never folded into `fromNodeId`. A notice
  about a spent window is published on the send that spent it, and a group says LoRa will not carry it from its roster,
  never the directory. `publishStatus` is serialised on its own lock, taken outside the pacer's and the ledger's
  monitors: the field log's `LoraFieldLog` reads the order of the snapshots as the record, so a stale Ready landing
  over a newer Disconnected is a phantom down and up (ADR 2026-10.a239's 2026-10-18 amendment). Its reason words are
  `MeshtasticSession`'s companion constants, folded onto `LoraLinkEnd` and pinned by `LoraFieldLogTest`. The room's
  composer hint says a post past its one-packet budget, or one with a reply or an attachment, is not carried
  (`loraHintFor`, ADR 2026-10.rp3s); a DM's still hedges.

- **Before adding a second board kind (MeshCore) or making `MeshtasticLink` board-neutral:** READ ADR 2026-10.jend.
  On a MeshCore board only people's room posts and reactions flood, plus our own profile right before our first
  flooded post. Floods are scoped to the board's region when one is set, and booked as every repeater's airtime under
  `AirBucket.PUBLIC` (the ledger is one total since ADR 2026-10.ug4a; MeshCore's `GET_STATS` fills the same
  `BoardAir` the brake reads).
  <!-- doc-lint: allow AirBucket — jend's booking as it decided it, before ADR 2026-10.ug4a --> Every DM-form frame, every room tick and all machine traffic stays zero-hop, or one hop through
  a named repeater. DM-form traffic is gated on a direct-reach set (frames heard at hop count 0), never on
  `reachable`. ADR 2026-10.rp3s narrows this on both boards: only the user's own frames air. ADR 2026-10.x9fa built the
  board-neutral seam a second board needs (`LinkDestination`, `BoardAddr`, `PacketOutcome`) and a compact DM that fits
  MeshCore's 164-B cap (113 B of plaintext); the per-peer reach marks (`LoraDirectPolicy`) are where its "heard at hop 0"
  set plugs in. Every transmission carries a byte that varies per send, because MeshCore drops byte-identical packets
  network-wide. The Meshtastic plane's ADRs above do not carry over: no signing cliff, no `hop_limit`, no
  `BoardQuiet`.

## Adding an entry

A new decision about this plane gets a bullet here — the symbols that trigger it, the ADR, and the invariant in a
sentence or two — never a bullet in `AGENTS.md`. A symbol listed nowhere is not exempt: `lora-bridge.md` and the
symbol's KDoc often carry the field trial that shaped it.
