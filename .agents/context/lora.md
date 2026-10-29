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
  `StaleAtSend.NOT_OWN`, `fastSend`, the one-packet room rule (`loraRoomTooLong`), `RETIRED_CTL_TAG` /
  `loraRetiredCtl`, `LoraSizeHint.ROOM_BODY_BYTES`, or a "Forward over LoRa":** READ ADR 2026-10.rp3s, then "Shape" in
  `lora-bridge.md`. The board is an endpoint: it airs only frames this phone's user wrote (one predicate,
  `LoraFramePolicy.airs`; the deferred "Forward over LoRa" is a second clause there and nowhere else), and nothing
  before our own id is read — a frame fanned then carries its author on `RideGate.authorId` and `sendFrame` refuses
  it as `NOT_OWN`. An own room `chat` rides only in one packet at the current `maxPayload`; a room `reaction`, a
  `profile`, a receipt and anything somebody else wrote never ride, and `fastSend` refuses everything (no room ✓✓
  airs). Inbound is unchanged: a heard frame still floods into the pocket over BLE/NAN. Tag `0x10` (the bridge's
  OFFER) is burned; it is dropped before decode under `loraRetiredCtl`, never as `UNKNOWN_TAG`.
- **When touching `LoraMeshTransport`'s shape — `fastFanout` / `longRangeFanout`, `neighbors`, `shortRange`,
  `LoraFramePolicy` (`isFresh`, `FRESH_MS`), `shouldLongRangeFanout`, `FarPeerFrameSource`, `reofferTo`,
  `SettingsStore.loraDmEnabled`, or `BuildConfig.LORA_PLANE`:** READ ADR 038, ADR 039, ADR 2026-09.6gtm and ADR
  2026-10.rp3s, then the "Shape" and "DMs" sections of `lora-bridge.md`. The plane is a fast-plane-only composite child
  (`neighbors` always empty, `send`/`sendFile`/`sendDigest`/`fastSend` no-ops), carrying its own user's room posts and
  DM-form chat — never group-form chat, `typing`, `blobreq`, `keyreq`, a `profile` or a room reaction. DM-form rides
  `longRangeFanout`, a seam of its own: never widen `shouldFastFanout` for it. Not a wire change: `sig`/`signed` pass
  byte-exact. The flag is on everywhere since 2.5.0, `loraEnabled` still defaults false.
  **Before touching the DM reach gate — `reachGate`, `unheardRecipient`, `FanoutHint.CHAT`, `dmProbeAt` /
  `LoraDmProbe`, `DM_PROBE_WINDOW_MS`, `LoraReprobe` / `reprobeDue`, `frameKey` / `LoraPacePolicy.hasQueued`,
  `reofferedAt` / `REOFFER_MEMORY_MS`, `reofferOne`, `MeshManager.framesFor`, `FAR_REOFFER_OWN_LIMIT`, or
  `ForwardStore.liveFramesByIds`:** READ ADR 2026-10.a4pm (it amends ADR 039 §7) and ADR 2026-10.rp3s. An own DM to a
  recipient not in `reachable` is held before its dedup slot, keyed on the originator's hint: it may probe (one per
  recipient per 15 min); our own `TICK`s, our `CONTENT` ctls and the post-reset re-seal are not gated, nor is anything
  before our id is known. Every own DM puts its recipient on the re-probe's schedule (`LoraReprobe`): the linger sweep
  re-airs the newest unacked one on `LIVE` a window after the hold, then at a gap doubling to 4 h, sharing the probe
  window, deferred while the recipient is covered, a copy is queued or they are not known to hold a board, reset by a
  first hearing or an ack, and dropped only when no own unacked DM is left — never on a NodeInfo or telemetry hearing.
  Only a board holder (`isBoardHolder`: heard on this plane, or `PeerEntity.loraNode` via
  `MeshManager.loraBoardHolders` → `boardHolders`) is probed or re-probed, but every held recipient is scheduled, so a
  `loraNode` that lands later is probed on the next due round. A holder source that has not reported reads "not now"
  (defer), never "never" (forget); a failing one is counted (`loraBoardHoldersFailed`). A recipient who stays heard is
  not re-probed: once the newest own unacked DM to them is a gap old and not re-offered in the hour, it is re-aired as a
  re-offer (`repairHeard`, `lora repair`). Before our id, the fan-out admits at most `PRE_ID_ADMISSIONS` (4)
  frames (`loraPreIdRefused` past it), charging a slot only to a frame that reaches the queue (`reservePreId`). A frame dropped before its first fragment,
  on any path, is `abandon`ed: its dedup slot, probe window and re-offer hour come back. The re-offer returns our own
  unacked DMs by id (≤ 6, `LIVE`), each at most once an hour, and neither it nor the re-probe asks `sigSeen` (a
  transmission is not a hearing); both ask `hasQueued`, which counts the frame in flight. A requeue
  (`LoraPacePolicy.requeue`) supersedes nothing. The probe window, the re-offer memory and the re-probe schedule are
  persisted limiters, not cleared by `quiesce`.
  **Before touching `start()`'s loops, `awaitConfigured`, `quiesce`, or what runs with no board bound:** READ ADR
  2026-09.5bqu — nothing ticks, transcodes or queues until a board is configured, and losing the board quiesces
  exactly as `stop()` does.
- **When touching `LoraFrameCodec` (`encodeBest`, `PacketCost`), `LoraAirtime.padTo`, `MeshtasticProto.MAX_PAYLOAD` /
  `MAX_SIGNED_PAYLOAD` / `LORA_DATA_MAX`, `PRE_READY_PAYLOAD`, `LoraMeshTransport.HOP_LIMIT`, or the `0x05` form on
  this plane:** READ ADR 2026-09.mhs5 (and ADR 030 / 060 for the codec it reuses), then "Board facts" in
  `lora-bridge.md`. A Knit payload is ≤ 231 B on the air (≤ 228 at MTU 255, the MTU-255 floor before Ready), ≤ 3
  fragments; a 2.8 board signs anything ≤ 165 B, so the **last** packet of a **deflated** frame is padded to 166 —
  never a stored `0x03`/`0x05` body, never an earlier fragment. `hop_limit` is stated (3) on every send: omitted
  means zero on 2.8, not the node's default (`everyPacketLeavesWithHopsToSpend`).
- **When touching `LoraAirtime` (`AirBucket`, `AirBucket.restoredFrom`, `admits`, `refusal`, `allowanceMs`,
  `signsPackets`, `onFirmware`, `dedicatedUnlocksDuty`), `LoraPacePolicy` (`FrameClass`, `enqueue`, `take`, `admitBest`,
  `onQueueStatus`, `onNak`), `OutboundFrame.supersedes`, or `MeshTransport.FanoutHint`:** READ ADR 044 (its allowance
  and 2026-10-04 amendment; the rest is superseded), ADR 054, ADR 2026-09.xdm2, ADR 067 and ADR 2026-10.rp3s, then
  "Pacing" in `lora-bridge.md`. One allowance = `min(region duty, 10 % politeness) × 0.5` of a 15-minute window (45 s
  at LongFast); the duty cycle is law and only Knit's own dedicated slot lifts the politeness half. Two buckets: `LIVE`
  may spend it all, `PUBLIC` 15 % and the total; `TICK` never spends the last 25 %. Every air spend is booked — no class
  is exempt and unmetered. Dequeue is by class (`DM`, `ROOM`, `TICK`) then FIFO, a refused frame is skipped, except
  that a live frame the total refused holds everything ranked behind it. A retired bucket's persisted booking is
  restored as `LIVE` (`BRIDGE`, `GOSSIP`) or dropped (`BOOTSTRAP`). Every entry point of the pacer and the ledger is
  `@Synchronized`, and the pacer's monitor is taken outside the ledger's.
- **When touching `coveredByLink`, `suppressDataPath` / `linkIds`, `coveredByInternet` on this plane, `RideGate`
  (`reachGated`, `authorId`), `staleReason` / `StaleAtSend` (`UNHEARD`, `NOT_OWN`), `OutboundFrame.sigKey`,
  `sendFrame`, or `selfIdKnown`:** READ ADR 054 and ADR 2026-09.5dt2 (and ADR 2026-09.y5f3 for the Internet cover, ADR
  2026-10.a4pm for the reach gate, ADR 2026-10.rp3s for the author). A DM-form frame to us, or to a peer a short-range
  plane holds a **live link** to (links, never sightings; `suppressDataPath` is the only feed), is skipped before its
  dedup slot is spent; the queue is a time-delayed commitment, so each frame carries the gates its own path asked and
  `sendFrame` re-asks them before the first fragment — only those. The pacer parks until the self id is known (#141).
  `UNHEARD` is asked only of a frame that passed the reach gate (never a probe or re-probe). Any refusal at send hands
  back what the frame held (`OutboundFrame.sigKey`, the probe window, the re-offer hour): it never flew.
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
- **When touching `LoraPlaneState`, `LoraPlaneStateStore`, `WallShift`, `awaitRestored`, `LoraAirtime.bookings` /
  `restore`, or what `stop()` saves:** READ ADR 2026-09.7svb and "Limiters that outlive the process" in
  `lora-bridge.md`. The ledger, the auto-reply memory, the DM probe window, the re-offer's per-frame hour (`dmProbes`,
  `reoffered`, ADR 2026-10.a4pm) and the re-probe schedule (`reprobes`, ADR 2026-10.rp3s) survive a restart, stamped on
  the wall clock; nothing transmits until they are back (5 s bound). `sigSeen` and `lastHeardAt` are **not** persisted,
  on purpose. The blob is user data: a renamed or retyped field drops a limiter, and a blob an older build wrote must
  still load every limiter this build keeps (`LoraPlaneStateStoreTest`).
- **When touching board setup — `provisionKnitChannel`, `BoardQuiet`, `ProvisionMode`, `BoardName`, `LoraSlot`,
  `KnitChannel`, `spliceVarintFields` / `spliceStringFields`, `BoardSettings`, `SettingsStore.loraBoardSetup`, or
  `BoardConfig.LORA`:** READ ADR 045, ADR 049, ADR 2026-09.emd7 and ADR 067, then "Board provisioning" and "Setting a
  board up" in `lora-bridge.md`. Setup is one read-modify-write admin transaction that never moves the radio or
  touches the primary (stock nodes repeat Knit only on the same params), quiets the board, sets `LOCAL_ONLY`, renames
  it `Knit abcd` (never the user's name) and marks it unmessagable; Restore puts back exactly what it recorded. The
  dedicated slot is opt-in and never the default. `KnitChannel`'s PSK is derived and pinned by `KnitChannelTest`.
- **When touching `MeshtasticSession` (handshake, heartbeat, drain, admin passkey), `MeshtasticLink`, `PacketIdSource`,
  `BoardBattery`, or `MeshtasticGatt` (`negotiateMtu`, `SETTLE_MS`):** READ ADR 038 §2, ADR 041 and "The layers" /
  "Board facts" in `lora-bridge.md`. The protobuf is hand-rolled and total (malformed → null, never a throw), its field
  numbers pinned by golden vectors in `MeshtasticProtoTest`. The battery is read off the handshake and the per-minute
  telemetry, never polled. A bonded board starts SMP on the ACL, so the Exchange MTU waits and retries, and the
  reported size beats the status (b407293d).
  **Before touching `MeshtasticSession.connectLoop`, `BoardDialPolicy`, `MeshtasticGatt.dial`'s modes, or what the
  pacer does while the link is down:** READ ADR 2026-09.hp88 — three direct dials, then the controller's
  `autoConnect` with an hourly direct net and no arbiter while waiting; a refusal backs off, only a spent window
  doesn't; the pacer parks on `link.state`, never its 1 s tick.
- **When touching the Meshtastic room — `onLoraPacket`'s slot-0 branch, `PublicChannelPolicy`, `PublicPostPolicy`
  (`onAirText`, `onAirBudget`), `postToPublicChannel`, `AirBucket.PUBLIC`, `mesh/crypto/XeddsaVerify`,
  `ProfileContent.loraNode` / `loraKey`, `PeerRepository.findByLoraNode`, `messages.originPeerId` / `originSigned`, or
  `SettingsStore.loraRoomEnabled`:** READ ADR 2026-09.26q3, ADR 2026-09.9469, ADR 2026-09.ggq4, ADR 2026-09.sre4 and
  ADR 2026-09.x52a, then "The Meshtastic room" in `lora-bridge.md`. The room is a local mirror of the bound board's
  slot 0: nothing minted, custodied or fanned out, no gateway gate; a post leaves through this phone's own board as the
  words alone, no author name. A heard author is resolved once at ingest through the node number their profile claims
  and stays "unverified" unless the board key in that profile verifies the XEdDSA signature — in firmware 2.8.1's
  layout only; Meshtastic withdrew 2.8.0, so a 2.8.0 contact reads as a mismatch (ADR 2026-09.ggq4's 2026-10-29
  amendment). ADR 2026-09.cf7a and
  2026-09.7r4d are superseded: the `meshpost` type is burned, never bring a custodial frame back for this traffic.
  `loraNode` / `loraKey` are profile fields the iOS port reads too (paired change).
  **Before touching `DmAutoReplyPolicy`, `Destination.Reply`, `OutboundFrame.to`, or `MeshtasticLink.send`'s
  `to`:** READ ADR 2026-09.4n5p — a Meshtastic DM to a set-up board is answered once with a fixed unicast
  (once per sender per day, once per 30 s for anybody, the room's air share, never from a stock or a
  dedicated-slot board), and that reply is the only unicast the plane sends.
- **When touching the plane's face — `DeliveryPlane.LoRa`, `InboundFrame.kind`, `LoraPlane`, `LoraStatus` /
  `LoraStatusRepository`, `BoardFilter`, `LoraSizeHint`, `ui/chat/LoraReach.kt` (`RoomSaturated`, `GroupUnsupported`,
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
  `AirBucket.PUBLIC`. Every DM-form frame, every room tick and all machine traffic stays zero-hop, or one hop through
  a named repeater. DM-form traffic is gated on a direct-reach set (frames heard at hop count 0), never on
  `reachable`. ADR 2026-10.rp3s narrows this on both boards: only the user's own frames air. Every transmission carries a byte that varies per send, because MeshCore drops byte-identical packets
  network-wide. The Meshtastic plane's ADRs above do not carry over: no signing cliff, no `hop_limit`, no
  `BoardQuiet`.

## Adding an entry

A new decision about this plane gets a bullet here — the symbols that trigger it, the ADR, and the invariant in a
sentence or two — never a bullet in `AGENTS.md`. A symbol listed nowhere is not exempt: `lora-bridge.md` and the
symbol's KDoc often carry the field trial that shaped it.
