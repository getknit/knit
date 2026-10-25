# LoRa plane — which decision governs which symbol

The symbol → ADR routing for Knit's LoRa (Meshtastic-over-BLE) plane: `mesh/lora/` and `mesh/bluetooth/meshtastic/`.
The `lora-owner` reads it before it writes and the `lora-reviewer` before it judges (both in `.agents/personas/`);
`AGENTS.md` points here instead of carrying these lines. Each entry names the symbols that trigger it, the ADRs to read
(`.agents/memory/decisions.md` maps an id to its file, amendments included) and, where there is one, the section of
`.agents/context/lora-bridge.md` that explains the mechanism. An entry is an index into its ADRs, not a substitute:
where they disagree, the ADR wins and the entry is fixed.

LoRa-adjacent routing that stays in `AGENTS.md`, because another subsystem owns it: how a Nearby-room ✓✓ gets home
(`AckSync`'s ride hold, `MeshRouter.handOn`, `coveredByInternet` — ADR 2026-09.y5f3 / 2026-09.wkbk), a link preview on
a LoRa thread (ADR 2026-09.7x8k), a blob re-wanted at link-up (ADR 2026-09.ptv8), the presence tiers (`ui/Reach.kt`,
ADR 2026-09.2ajk), and the field log's `FieldEvent.Lora` sample and the link edges `LoraFieldLog` follows (ADR
2026-10.a239 and its 2026-10-18 amendment). The GATT client
(`MeshtasticGatt`) is also Bluetooth code: a change there owes `bluetooth-reviewer` as well as `lora-reviewer`.

## Entries

- **When touching `LoraMeshTransport`'s shape — `fastFanout` / `longRangeFanout` / `fastSend`, `neighbors`,
  `shortRange`, `LoraFramePolicy` (`eligible`, `isFresh`, `FRESH_MS`), `shouldLongRangeFanout`, `FarPeerFrameSource`,
  `reofferTo`, `SettingsStore.loraDmEnabled`, or `BuildConfig.LORA_PLANE`:** READ ADR 038, ADR 039 and ADR
  2026-09.6gtm, then the "Shape" and "DMs" sections of `lora-bridge.md`. The plane is a fast-plane-only composite child
  (`neighbors` always empty, `send`/`sendFile`/`sendDigest` no-ops), carrying the broadcast room subset, `profile`
  and DM-form chat — never group-form chat, `typing`, `blobreq` or `keyreq`. DM-form rides `longRangeFanout`, a seam
  of its own: never widen `shouldFastFanout` for it. Not a wire change: `sig`/`signed` pass byte-exact. The flag is on
  everywhere since 2.5.0, `loraEnabled` still defaults false.
  **Before touching the DM reach gate — `reachGate`, `unheardRecipient`, `FanoutHint.CHAT`, `dmProbeAt` /
  `LoraDmProbe`, `DM_PROBE_WINDOW_MS`, `frameKey` / `LoraPacePolicy.upgradeQueued`, `reofferedAt` /
  `REOFFER_MEMORY_MS`, `reofferOne`'s bucket, `MeshManager.framesFor`, `FAR_REOFFER_OWN_LIMIT` /
  `FAR_REOFFER_RELAYED_LIMIT`, or `ForwardStore.liveFramesByIds` / `liveFramesToFromOthers`:** READ ADR 2026-10.a4pm
  (it amends ADR 039 §7). A DM-form fan-out to a recipient not in `reachable` is held before its dedup slot, keyed on
  the originator's hint: our own `CHAT` DM may probe (one per recipient per 15 min), as may a relayed frame from a
  linked pocket-mate (the gateway's case); other relayed frames are held and never probe; our own `TICK`s, our
  `CONTENT` ctls and the post-reset re-seal are not gated, nor is anything before our id is known. A frame dropped
  before its first fragment, on any path, is `abandon`ed: its dedup slot, probe window and re-offer hour come back. The
  bridge never queues a second copy (`hasQueued`, which counts the frame in flight), and a requeue
  (`LoraPacePolicy.requeue`) supersedes nothing. The re-offer returns our unacked DMs by id (≤ 6, `LIVE`)
  then relayed frames (≤ 4, `BRIDGE`, `LIVE` for a linked pocket-mate's), each frame at most once an hour, and lifts a
  bridge copy still queued onto its bucket — never a part-sent one. The probe window and the re-offer memory are
  persisted limiters, not cleared by `quiesce`. The room, `profile`, `fastSend` and the bridge backfill are not gated.
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
- **When touching `LoraAirtime` (`AirBucket`, `admits`, `refusal`, `allowanceMs`, `signsPackets`, `onFirmware`,
  `dedicatedUnlocksDuty`), `LoraPacePolicy` (`FrameClass`, `enqueue`, `take`, `admitBest`, `pendingSizes`,
  `onQueueStatus`, `onNak`), `OutboundFrame.supersedes`, or `MeshTransport.FanoutHint`:** READ ADR 044 (with its
  2026-10-04 amendment), ADR 054, ADR 056, ADR 2026-09.t8t8, ADR 2026-09.7c8n, ADR 2026-09.xdm2 and ADR 067, then
  "Bridging pockets" and "Pacing" in `lora-bridge.md`. One allowance = `min(region duty, 10 % politeness) × 0.5` of a
  15-minute window (45 s at LongFast); the duty cycle is law and only Knit's own dedicated slot lifts the
  politeness half. `LIVE` may spend it all, `BRIDGE` 30 %, `BOOTSTRAP` 25 % and judged outside the total, `GOSSIP`
  only against the total, `PUBLIC` 15 %; `TICK` never spends the last 25 %. Every air spend is booked — no class is
  exempt and unmetered. Dequeue is by class then FIFO, a refused frame is skipped, except that a live frame the total
  refused holds everything ranked behind it. A newer snapshot of a queued profile or OFFER replaces it in place. Every
  entry point of the pacer, the ledger and the gossip policy is `@Synchronized`, and the pacer's monitor is taken
  outside the ledger's.
- **When touching `coveredByLink`, `coveredByInternet` on this plane, `RideGate` (`reachGated`), `staleReason` /
  `StaleAtSend` (`UNHEARD`), `OutboundFrame.sigKey`, `sendFrame`, or `selfIdKnown`:** READ ADR 054 and ADR
  2026-09.5dt2 (and ADR 2026-09.y5f3 for the Internet cover, ADR 2026-10.a4pm for the reach gate).
  A DM-form frame to us, or to a peer a short-range plane holds a **live link** to (links, never sightings), is skipped
  before its dedup slot is spent; the queue is a time-delayed commitment, so each frame carries the gates its own path
  asked and `sendFrame` re-asks them before the first fragment — only those, so `fastSend` is never role-gated. The
  pacer parks until the self id is known (#141). `UNHEARD` is asked only of a frame that passed the reach gate (never
  the probe). Any refusal at send hands back the dedup slot the fan-out, serve or re-offer took (`OutboundFrame.sigKey`):
  the frame never flew, and the slot would turn the next re-offer or backfill away.
- **When touching `noteReachable`, `isPresenceEvidence` (`mesh/FramePresence.kt`), `boardsHeard` / `boardsHeardAt`,
  `RxQuality`, or the gateway's `suppressDataPath` / `pocketKeys`:** READ ADR 2026-09.2ajk and the boxed notes under
  "Shape" in `lora-bridge.md`. `reachable` here is author-keyed **reach**, never proximity, fed only by fresh frames
  (15 min, a profile 13 h); `boardsHeard` counts radios. The signal row reads only radios that sent a Knit frame on
  the bound channel, aged with the radio, cleared on `stop()` — never a reading taken in `MeshtasticSession`, ahead of
  the filter.
  **Before touching `noteReachable`, `airedByPocket` / `boardOwners` / `heardVia`, `fastSend`'s `targetedKey`, or
  `AckSync.backOff`:** READ ADR 2026-09.6gk8 — a co-pocket board's airing is not LoRa reach (but a far
  gateway's still is, ADR 2026-09.wkbk), and a cleartext owed tick backs off like the sealed one.
- **When touching the bridge — `LoraGatewayPolicy`, `LoraGossipPolicy` (`onOffer`, `reset`, `ensureInterval`),
  `LoraCtl`, `publishOffer`, `serveBackfill` / `serveOne` (`Serve.NO_AIR`), `LoraFramePolicy.backfillRank` /
  `bridgeOrder`, `BridgeFrameSource`, `sigSeen` / `profileSeen`, or `SettingsStore.loraBridgeEnabled`:** READ ADR 044,
  ADR 057, ADR 2026-09.qsj6, ADR 2026-09.rre4, ADR 2026-09.y8pu, ADR 2026-09.zkma, ADR 2026-09.t8t8 and ADR
  2026-09.7c8n, then "Bridging pockets" in `lora-bridge.md`. The role is elected on the **link set**, never
  `reachable`, and must recover without an event (lost link, a gateway stale past 45 min, the 60 s sweep). `LoraCtl`
  claims first byte `0x10`, which an older build drops as `UNKNOWN_TAG`. Trickle suppresses only on set equality and
  resets on a heard inconsistent OFFER. The offer names the head of the same ranked list the serve draws from
  (profile → room → DM, newest first, one profile per author), and `serveOne` consults neither dedup set: an OFFER is
  evidence the far side lacks the frame, and "we transmitted" is not "someone heard".
- **When touching `LoraPlaneState`, `LoraPlaneStateStore`, `WallShift`, `awaitRestored`, `LoraAirtime.bookings` /
  `restore`, or what `stop()` saves:** READ ADR 2026-09.7svb and "Limiters that outlive the process" in
  `lora-bridge.md`. The ledger, beacon floor, serve caps, `profileSeen`, the auto-reply memory, the DM probe window and
  the re-offer's per-frame hour (`dmProbes`, `reoffered`, ADR 2026-10.a4pm) survive a restart,
  stamped on the wall clock; nothing transmits until they are back (5 s bound). The Trickle interval, `sigSeen` and
  `lastHeardAt` are **not** persisted, on purpose. The blob is user data: a renamed or retyped field drops a ledger.
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
  and stays "unverified" unless the board key in that profile verifies the XEdDSA signature. ADR 2026-09.cf7a and
  2026-09.7r4d are superseded: the `meshpost` type is burned, never bring a custodial frame back for this traffic.
  `loraNode` / `loraKey` are profile fields the iOS port reads too (paired change).
  **Before touching `DmAutoReplyPolicy`, `Destination.Reply`, `OutboundFrame.to`, or `MeshtasticLink.send`'s
  `to`:** READ ADR 2026-09.4n5p — a Meshtastic DM to a set-up board is answered once with a fixed unicast
  (once per sender per day, once per 30 s for anybody, the room's air share, never from a stock or a
  dedicated-slot board), and that reply is the only unicast the plane sends.
- **When touching the plane's face — `DeliveryPlane.LoRa`, `InboundFrame.kind`, `LoraPlane`, `LoraStatus` /
  `LoraStatusRepository`, `BoardFilter`, `LoraSizeHint`, `ui/chat/LoraReach.kt` (`RoomSaturated`, `GroupUnsupported`,
  `LoraOnlySaturated`), or `ui/lora/`:** READ ADR 040 (its §1 collapse amended by ADR 2026-10.e42d), ADR
  2026-09.ursc and ADR 2026-09.6ww7. The kind is for presentation only — carry, relay and convergence never read it,
  and it is never folded into `fromNodeId`. A notice
  about a spent window is published on the send that spent it, and a group says LoRa will not carry it from its roster,
  never the directory. `publishStatus` is serialised on its own lock, taken outside the pacer's and the ledger's
  monitors: the field log's `LoraFieldLog` reads the order of the snapshots as the record, so a stale Ready landing
  over a newer Disconnected is a phantom down and up (ADR 2026-10.a239's 2026-10-18 amendment). Its reason words are
  `MeshtasticSession`'s companion constants, folded onto `LoraLinkEnd` and pinned by `LoraFieldLogTest`.

- **Before adding a second board kind (MeshCore) or making `MeshtasticLink` board-neutral:** READ ADR 2026-10.jend.
  On a MeshCore board only people's room posts and reactions flood, plus our own profile right before our first
  flooded post. Floods are scoped to the board's region when one is set, and booked as every repeater's airtime under
  `AirBucket.PUBLIC`. Every DM-form frame, every room tick and all machine traffic stays zero-hop, or one hop through
  a named repeater. DM-form traffic is gated on a direct-reach set (frames heard at hop count 0), never on
  `reachable`. Every transmission carries a byte that varies per send, because MeshCore drops byte-identical packets
  network-wide. The Meshtastic plane's ADRs above do not carry over: no signing cliff, no `hop_limit`, no
  `BoardQuiet`.

## Adding an entry

A new decision about this plane gets a bullet here — the symbols that trigger it, the ADR, and the invariant in a
sentence or two — never a bullet in `AGENTS.md`. A symbol listed nowhere is not exempt: `lora-bridge.md` and the
symbol's KDoc often carry the field trial that shaped it.
