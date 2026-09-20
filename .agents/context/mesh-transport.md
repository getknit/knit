# Mesh transport (radios, NAN concurrency, BLE scan) — gotchas that have already bitten us

Deep behaviour of the two-radio transport. The **import-boundary rule** (keep `android.net.wifi.aware.*`
in `mesh/wifiaware/`, `android.bluetooth.*` in `mesh/bluetooth/`) lives in `rules/mesh.md`. This file
is the hard-won operational detail behind that seam.

## Per-peer responders DON'T compose — use one persistent accept-any responder

The intuitive design (each incoming peer gets its own
`WifiAwareNetworkSpecifier.Builder(session, peerHandle).setPort(...)` responder + `ServerSocket`) works
for *two* devices but silently fails for a third: a device already acting as responder for one peer
cannot stand up a second per-peer responder, so a phone joining an existing pair is stranded (its client
`requestNetwork` just times out; verified — 7 couldn't join an 8+9 pair). The fix in
`WifiAwareTransport`: each node runs **one** responder built from its *publish* session with **no peer
handle** (`WifiAwareNetworkSpecifier.Builder(publishSession).setPort(port)`), which accepts a data path
from **any** initiator over a single `ServerSocket`; all clients share it. Because an accept-any
responder doesn't know who connected, the **initiator sends its advert as the first
`LinkFraming.Type.HELLO` record** over the socket (`mesh/link/LinkHandshake`, shared with BLE), and the
responder reads it to identify the peer. Tie-break gives one link per pair (larger nodeId =
client/initiator, smaller = server). The responder is anchored to the publish session, so **only
*subscribe* is ever re-armed** (publish/responder stay up), respecting the "one data interface" rule
below.

## One NAN data interface (`maxNdiInterfaces == 1`) → one aware *network* at a time → cue-driven ephemeral sync

The single hardest constraint, confirmed on Pixel 7/8/9 (`dumpsys wifiaware` → `maxNdiInterfaces=1`) —
but the limit is **per-role**, not "one NDP, period" (re-audited on-device 2026-07-04; evidence +
corrected model in `docs/NAN_CONCURRENCY_REAUDIT.md`): every **initiator** `requestNetwork` is its own
aware Network and needs its own NDI, so a second concurrent *initiate* is refused with
`WifiAwareDataPathStMgr: ... NdpInfos[] - no interfaces available!` (verified: Pixel 7, largest, couldn't
reach Pixel 9 while linked to Pixel 8) — while the **accept-any responder is ONE network that officially
multiplexes many concurrent inbound NDPs** on the same NDI (E1: 30+ consecutive serves on one request
with zero re-attaches; E2: two *simultaneous* inbound NDPs; firmware budget `maxNdpSessions=8`; the
`dumpsys` `mMaxNdpInApp=1` once read as a per-app cap is a metrics high-water mark, not a limit). Each
node's *outbound* is still single, so "everyone links to everyone they're larger than" still can't work,
and the shipped design runs **two planes** (a concurrent-serve redesign is proposed in
`docs/NAN_CONCURRENCY_REAUDIT.md` §5):

- **Coordination plane** — Wi-Fi Aware *messages* (`DiscoverySession.sendMessage` / `onMessageReceived`,
  ~255 B, best-effort, `maxQueuedTransmitMessages=8`) ride discovery follow-up frames and need **no data
  path**, so they reach every neighbor at once *and* keep working while the one NDP is busy. Each node
  cues `nodeId|version` — a `StoreDigest` **content digest** (XOR over its **live** custody frame-id set, so
  it is O(1)-incremental and **restart-stable**: same store ⇒ same version, unlike the old monotone
  `SyncEpoch` counter it replaced; expired-but-unswept rows are excluded, folded out **lazily** by
  `StoreDigest.current()` at every read/cue — expiry is frame-global `sentAt + TTL`, so all nodes flip
  together modulo clock skew instead of diverging for up to a sweep period, work item #8) — and
  `DigestTracker` (pure, JVM-tested) flags a peer *sync-wanted* when either side's digest changed since
  the last sync (an identical-digest pair skips the NDP entirely). Small floodable frames (broadcast
  chat, reactions, receipts, group-meta, profiles) *also* ride this plane as a best-effort **fast
  fan-out** (`fastFanout`/`fastSend`), deduped by the receiver's `SeenSet`, so they propagate with zero
  NDP. The framing is chosen **per peer** (`mesh/link/FastFramePick`, from the SSI-advert capability copy):
  toward a `CAP_FRAME_TRANSCODE` peer the frame rides the transcoded `0x05` tag (`mesh/link/FrameTranscoder`,
  ADR 060 — `signed` re-encoded with integer labels and raw ids, rebuilt byte-exact at the receiver before
  the signature is verified; the signed v3 ✓✓ tick is one 221-B message this way) or `0x03` when that is
  smaller; toward a `CAP_FAST_COMPACT` peer the compact `0x03` tag (`mesh/link/FastFrameCodec` — outer
  envelope stripped to 3 B + preset-dict deflate over `signed`, sig/signed byte-exact); either splits into
  ≤3 `0x04` fragments when one ~255 B message won't hold it (this is what lets AckSync's sealed ticks,
  sealed reactions, and full profiles ride at all — they measure 374-554 B legacy, see
  `CoordinationPlaneSizeBudgetTest`); toward legacy/cue-only peers the old `0x01` tagged-CBOR framing is
  kept, so no wire break. Counters: `fastCompactSent`/`fastTranscodedSent`/`fastLegacySent`/`fastFragSent`/
  `fastReassembled`/`fastTooBig`/`transcodeFallbacks`/`fastDropsByReason` on `…debug.STATE`; grep
  `fast-fanout`/`fast-send`/`fast-frame` logcat lines for per-frame routing. A cue also bootstraps
  the reverse handle, so a node whose own *subscribe* is broken (e.g. Pixel 9 post-kill) still cues
  larger peers to pull from it. A fast frame is a **sighting of the hop that delivered it, never of its
  author** — `NanHopTable` maps the (session, `PeerHandle`) a message arrived on to the node the last
  cue/advert named there, and that hop is what `noteReachable` and `InboundFrame.fromNodeId` get. The
  envelope's `senderId` is the originator (relays keep the signed bytes verbatim), and every node re-fans each
  first-seen custody frame, so crediting it put a peer miles away — and a BLE-only phone with no Aware
  radio — in the "directly connected" list for the 150 s linger (ADR 061). An unnamed handle is no
  sighting at all.
- **Data plane** — one ephemeral NDP, brought up **only** when a peer is sync-wanted (the larger id
  initiates, via the unchanged `initiateTo` + accept-any responder). On link-up each side advertises the
  custody ids it holds (a `LinkFraming.Type.DIGEST` record) and pushes back only the frames the peer lacks
  (`ForwardSync.onDigest`, replacing the old push-all backfill), then the NDP is torn down on
  **quiescence** (no data for `QUIESCENCE_MS`, never mid-file) — freeing the NDI for the next pair. The
  initiator drives teardown and records the sync in `DigestTracker` (it alone consults it); the responder
  just sees the socket close, with a longer `RESPONDER_MAX_HOLD_MS` safety cap for a dead initiator.

Net: an **idle mesh does zero data-path work** (just beacons + occasional cues); a new message triggers a
targeted sync with only the peers that need it; and everything stays delay-tolerant (store-and-forward
custody carries what one flood doesn't reach — a rotating series of pairwise syncs propagates
epidemically). Single-slot admission (`beginConnect`/`beginAccept`: at most one link/handshake/accept,
plus a `SETTLE_MS` gap after a link ends so the NDI is released before the next `requestNetwork`) keeps
the radio off the "no interfaces available" wedge. `discoveryLoop`/`rearmSubscribe()` re-fire one-shot
discovery **only while the slot is free** (never with a live NDP, whose client side rides the subscribe
session and would be dropped by a re-arm).

## A lonely node relaxes its discovery cadence (ADR 2026-09.kb68)

With no cue targets `NanSyncPolicy.needsRediscovery` is true on every tick (an empty snapshot is "blind"), so
the loop re-armed subscribe every `REARM_COOLDOWN_MS` for as long as the phone was alone, and every re-arm
relights Instant Communication Mode for the framework's 30 s — a phone in a drawer kept ICM lit around the
clock. `mesh/wifiaware/NanLonelyPolicy` gives the loop its cadence while lonely: the old 8 s / 15 s for the
first three minutes and whenever the screen is on or the charger is in, then — the same
`PowerPolicy.lonelyRelaxed` rule the BLE scan uses — the duty cycle's base interval as the tick (120 s / 300 s)
with the cooldown 15 s under it (ICM 25 % / 10 %). `lonelySince` is observed by the loop, never maintained at
the cue-target removal sites; it resets on `onAttached` and on a BLE sighting of a peer we hold no cue target
for (`onForeignReachable` rising edge, which also pokes the loop). `heal()` buys exactly one re-arm at the
aggressive cooldown (`healRearmOwed`), so walking re-arms once per motion trigger. Nothing else in the loop
changes: branch order, the watchdog's clocks, the sync and ICM-relight paths all read as before, and every
wedge the file guards against needs an owed peer to be observed, which a lonely node has none of. Oracles:
`re-arm subscribe (lonely=…ms cooldown=…ms heal=…)` and `lonely: relaxed …` / `lonely: aggressive again` on
the transport tag, `lonely=` on the state line. The device trial is listed in the ADR and still owed.

## `requestNetwork` with no timeout leaks the one interface forever — always time-box it

The 3-arg `requestNetwork(request, cb, handler)` has no timeout, so a request that can't be fulfilled
stays pending forever — its `NetworkCallback` never unregisters and its NDI reservation never frees, so
the node exhausts its single interface and can never connect again (observed: a Pixel 7 stuck in a
`terminate: already terminated` loop). Always use the **timeout** overload
`requestNetwork(req, cb, handler, HANDSHAKE_TIMEOUT_MS)`, clean up in `onUnavailable`, and back a failed
peer off (`CONNECT_BACKOFF_MS`) so a different sync-wanted peer gets the slot next. Note `neighbors` is the
≤1 live link (send routing + the `onNeighborAdded` sync hooks); the **UI reads the smoothed `reachable`
set** (coordination-plane sightings, lingered `REACHABLE_LINGER_MS`) so it doesn't blink as ephemeral
syncs come and go.

The accept-any **responder** request is the one request that is *not* time-boxed — it stands for the life
of the publish session — and its `onUnavailable` is the framework declaring it unfulfillable and dropping
it from its cache. `refileResponder` re-files it on `NanResponderPolicy`'s terms (ADR 2026-09.bgk3): a
verdict while a link, handshake or accept of ours is live (or inside `SETTLE_MS`) is the documented knock
refusal — the interface really was busy — and is re-filed after a 500 ms floor without being counted; a
verdict with the interface free is about the request, backs off along 0.5 → … → 60 s, and at five in a
row gives the request up for `sessionCycleWithSettle()`, three cycles per episode, refunded only by the
responder's `onAvailable`. Before this the re-file was immediate and the Pixel 3 filed 174 in 130 ms
(work item #77). Never count the contended case: a hub serving a long sync collects knocks for the life
of the link.

## An initiate that knocks the phone off its Wi-Fi is given up on (ADR 2026-09.m8kc)

On the Pixel 3 (blueline, API 31) every NDP we initiate ends in a firmware REJECT that also tears the phone's
own STA down (`DEAUTH_LEAVING`, eight drops in two hours, band-independent), and each drop silently kills the
Aware client; the request never reaches the peer (a byte-swapped publish id inside `system_server`). The
largest node id initiates to everyone and `NanConnectPolicy` never gives up, so that was a Wi-Fi drop a minute.
`mesh/wifiaware/NanInitiatorPolicy` (pure, JVM-tested) is the failsafe: the transport keeps a **passive**
`registerNetworkCallback` on `TRANSPORT_WIFI`, a loss followed by an available within 15 s is a **blip**, and a
blip whose loss fell within 120 s *after* an initiate of ours with no link since is a **strike** — never a
`NanConnectPolicy` streak (the P7/P8/P9 fleet runs long streaks against a wedged responder with no STA harm).
Three strikes **hold the initiator role**: `driveSync` stops initiating, and the responder, discovery, cues and
the fast plane keep running, so nearby phones still connect to this one and Bluetooth carries custody. The hold
acts through **one choke point** — `digestSyncWanted` / `bulkSyncWanted`, where the BLE `suppressed` set already
lives — so every admission *and* recovery site (the wedge watchdog's owed clock included; Tier-2 is a process
kill, and the hold is journaled) stops seeing a sync it would have to initiate; `PeerFacts.initiator` stays the
tie-break so `needsIcmRelight` is unaffected; `expectBulkTransfer` refuses a held peer so a photo goes to BLE
without the composite's 10 s grace. Refunded by **exactly one** field event — an initiator link forming —
plus the user's "Try again" (Diagnostics, `MeshTransport.releaseInitiatorHold()` through the controller and
composite) and the build+ROM stamp (`data/settings/NanInitiatorJournal`, the ADR 055 shape). Never by the Aware
edge, `heal()`, `stop()` or a fresh session; `pause()` and a genuine Aware-off void the initiate in flight as
not evidence, but our own session cycle's NAN-down does not (on the Pixel 3 that cycle is bgk3's recovery from
the very drop being judged). A held role takes **one probe initiate a day** (wall clock, journaled) through
`driveSync` alone — `syncWantedForProbe`; the recovery sites keep the held view. Surface:
`MeshTransport.initiatorHeld` → `TransportStatus.initiatorHeld` → an "on hold" tag and a section under
Transports; `TransportHealth` unchanged, the plane is healthy. Oracles on the transport tag: `Wi-Fi dropped and
came back … — strike n/3`, `holding the initiator role`, `daily initiator probe`, `initiator link formed under
the hold — releasing it`; `init=` on the state line; `…debug.NANINIT` (`context/debug-bridge.md`).

Device-verified 2026-09-19 on the P3 (the ADR has the log): one natural strike through the real `NetworkCallback`
(the drop is rarer than the issue's night — 1 in 26 initiates), two injected, the hold, a force-stop restore, the
forced probe and its `AlreadyHeld` verdict, the Diagnostics tag + section, and "Try again now". Two traps for the
next run: the P3 in deep Doze without the battery exemption has Aware **disabled by the framework**
(`dumpsys wifiaware` → `mUsageEnabled: false`, Knit reads `Unavailable`) — lift Doze first; and a real STA drop
takes network adb with it, so a `logcat` pipe dies at exactly the moment you want it — loop the reconnect. Still
owed: the initiator-link refund on hardware (needs a phone that can actually link), and the P9-side injected leg.

## Three sets, and only one of them means *nearby*

`MeshTransport.neighbors` is live links; `MeshTransport.reachable` is sightings. Above the composite the
split is different and easy to get backwards (ADR 2026-09.2ajk):

- **`MeshController.neighbors` = `CompositeMeshTransport.shortRangeReachable`** — the merged `reachable`
  set restricted to children whose `MeshTransport.shortRange` is true. This is what every *nearby* /
  *online* / *connected* surface reads: the foreground notification count, the chat-list status row, the
  Contacts online dot, Profile Details, the group member picker, and Diagnostics' *Directly connected*.
  Only a short-range plane sights the peer's **own** radio.
- **`MeshController.reachable`** — the full union, long-range planes included. A superset, read only by
  Diagnostics' *Reachable via relay*. A LoRa entry names the plane a frame arrived over, not proximity
  and not the peer's hardware: LoRa keys presence on the frame *author*, and a gateway carries other
  people's frames, so a phone with no board at all appears here (`context/lora-bridge.md`).
- **`MeshController.shortRangeKinds`** — which `TransportKind`s count as short-range, read off
  `MeshTransport.shortRange` rather than restated, so a UI telling a proximity tag from a relay one
  cannot drift from what the transports declare.

Adding a plane is where this bites: give it `shortRange = false` unless a sighting really does mean the
peer is in radio range, or every *nearby* surface in the app inherits the claim.

## Wi-Fi Aware availability flaps, and may be absent entirely

`WifiAwareManager.isAvailable()` goes false when Wi-Fi is off or **another app's** Wi-Fi Direct / SoftAP /
hotspot seizes the radio; the transport watches `ACTION_WIFI_AWARE_STATE_CHANGED`, flips `health` to
`Degraded`, tears links down, and re-attaches on recovery. **It does not go false for our own P2P.** Since
Android 12 `HalDeviceManager` gives same-app interface requests equal priority, so they never evict each
other: Knit's own `createGroup` returns `BUSY` while our Aware session is attached, and `isAvailable()`
stays true throughout. There is no edge to react to, which is why the direct-transfer path pauses this
transport by hand (`MeshTransport.pause`/`resume`, `context/direct-transfer.md`, ADR 2026-09.wtmz). `PackageManager.FEATURE_WIFI_AWARE` can be missing outright
(some budget/older + certain Samsung models) — but the **Bluetooth LE plane still meshes** on those
devices, since `CompositeMeshTransport` merges whichever radios are present, so the UI shows the
"unsupported" state only when *neither* Wi-Fi Aware nor BLE hardware exists
(`RadioSupport.probe(context).any`, in onboarding). The same verdict (`mesh/RadioSupport.kt`: `PlaneSupport`
Supported / NoHardware / NeedsAndroid12, the two transports' `isSupported` gates are expressed on it) fills in
the Diagnostics Transports row for a plane the composite never built — "Not supported by this phone" or "Needs
Android 12 or newer" — since an omitted row had looked identical to a radio switched off (work item 18). Never
as a synthetic `TransportStatus`: the chat list's `radioWarningFor` reads every entry there as present hardware.

## On API 29–32 publish/subscribe are location-gated, and the service's type is what lifts it (ADR 2026-09.535d)

`WifiAwareServiceImpl` enforces the **location app-op** on `publish` and `subscribe` only — `attach`,
`updatePublish`, `sendMessage` and `requestNetwork` are not gated — and a "While using the app" grant is a
foreground-only app-op, so a backgrounded uid is refused with `SecurityException: UID … does not have
Coarse/Fine Location permission`. What lifts it is the `location` **runtime** foreground-service type
(`meshForegroundServiceTypes`, tiered with `requiredRadioPermissions`; the manifest attribute alone grants
nothing), and on 30–32 also the while-in-use flag the system grants only to a start from a visible activity —
which `KnitApp`'s resume observer makes on every open. Until then a boot- or sticky-started service still
discovers only on screen. The transport tells that refusal from a dead client (`NanSessionFault.classify`,
tier-gated), **holds** it as `TransportHealth.ForegroundOnly` with nothing torn down (the publish, the
responder and every NDP keep serving) and retries once per `heal()`; routing it through `onSessionDead`
instead is the 45-attaches-in-four-minutes churn of work item #62, against `NanAttachPolicy`'s leak budget.
From 33 discovery rides `NEARBY_WIFI_DEVICES` and none of this applies.

## One file streams at a time per socket

`mesh/link/LinkFraming` (transport-neutral — the same codec runs over the Wi-Fi Aware NDP socket and the
BLE L2CAP socket) multiplexes frames + files over one connected byte stream; the writer serializes file
transfers and interleaves live frames *between* chunks (so an 8 MiB blob never stalls traffic), which is
why a `FILE_HEADER`→`FILE_CHUNK`s→`FILE_END` run needs no file id. Don't push two files down one socket
expecting them to interleave.

## Steady-state digest parity + BLE suppression means NAN has no NDP exactly when an image needs it

Large attachments go through the bulk-want escape hatch, and it must never feed the recovery machinery.
With both radios up, BLE holds the link, the composite suppresses NAN's sync to that peer
(`suppressDataPath`), and converged custody digests make `reconcileWanted` false — so
`childHoldingLinkTo` only ever finds BLE and a naive "prefer NAN in `sendFile`" silently falls back
~always, leaving images to crawl over untuned L2CAP. The fix: `CompositeMeshTransport.sendFile`
(ATTACHMENT only; any size rides an already-live NAN link, and one ≥ `BULK_MIN_BYTES` = 128 KiB also
arms a bring-up — the gate compares the **transcoded wire blob**, not the user's source file: a 1-5 MB
GIF lands at ~150-250 KB as a 480px q70 animated WebP, which is how the original 256 KiB gate quietly
routed "large GIFs" over BLE) and `MeshManager.deliverChat` (at `blobExchange.want`) call
`MeshTransport.expectBulkTransfer` on **both** sides of the pair — the requester marks the author, the
serving side marks the requester; only the larger nodeId can initiate, so whichever side that is has a
mark — which arms a TTL'd `BulkWantTracker` that `WifiAwareTransport.syncWanted` ORs in ahead of the
suppression + digest gates. The split is load-bearing: the bulk term reaches ONLY the admission sites
(`driveSync`/`initiateOwed`/`initiateOwedToReachable`), while `anyReachableSyncOwed` (the wedge watchdog's
owed clock — Tier-2 is a process kill), `needsRediscovery` (subscribe re-arm churn is its own wedge
trigger), `needsIcmRelight`, and `rediscoverDelayMs` read the digest-pure gate — a pending
image always has the BLE fallback carrying it, so it is never an outage to "heal". This whole predicate
family is now a **pure `NanSyncPolicy`** (per-candidate `PeerFacts` snapshots carry `digestWanted` and
`bulkWanted` as sibling flags so the split is structural and JVM-tested); the transport keeps thin wrappers
that build the snapshot and call the policy. The two-tier watchdog clock is `NanWatchdogPolicy` and the
cue/SSI codec is `NanCueCodec` — both pure and tested alongside `NanConnectPolicy`/`NanServePolicy`. Marks are gated on a
fresh sighting (`BULK_FRESH_MS` 45 s, not the 150 s linger), fail-cooled 120 s on a failed initiate, and
never bypass connect backoff / single-slot admission / SETTLE. The composite grace-waits ≤ 10 s for the
NDP **off the inbound dispatch coroutine** (`onRequest`→`sendFile` runs inline in the router's single
inbound collector — a suspension there stalls both radios) then falls back to the link holder;
`sendFile` now returns enqueue-acceptance so a link that died in the check→enqueue window falls back
instead of silently dropping the file, and `BlobExchange` keeps a per-(hash, peer) 45 s serve memo so
the re-ask storm around a slow transfer (60 s re-offer, post-link-up `onNeighborAdded`) can't ship a
second full copy (field-verified: the late-NDP re-ask after a BLE fallback is real, and the memo ate it).
Since ADR 2026-09.4tx5 (#79) the link itself answers the two questions the memo could not: a receiver
reads `MeshTransport.arrivingFiles()` (off `FramedLink.rxKey`) and does not ask for a blob whose header is
already in, and a holder reads `fileInFlightTo(peer, key)` (the link's pending-file count, from the enqueue
to the end of the stream) and refuses a re-ask for a copy still queued or streaming to that peer.
Frames, digests, avatars, and (when no NAN link is already up) sub-128 KiB blobs keep the BLE-first
route byte-for-byte. Every routing decision logs `file route: <kind>/<key> <N>B → <peer> <choice+why>`
(tag `CompositeMeshTransport`) and every arm accept/reject logs `bulk arm <peer> …` (tag
`WifiAwareTransport`), so "why did this ride BLE" is one grep away; the `FramedLink`
`file ATTACHMENT/<hash> <N>B in <ms>ms` line gives the per-plane timing, and `filesNan`/`filesBt`/
`bulkTimeouts` ride `…debug.STATE`. `bulkTimeouts` climbing much faster than `filesNan` means ghosts are
being armed.

## The BLE scan is demand-gated, and a *settled* clique used to scan continuously

`BluetoothMeshTransport.scanLoop` duty-cycles the scan, but `onScanResult` pokes the loop's wake channel
on **every** sighting — *including already-linked peers* — and the loop consumes a buffered wake
immediately, so whenever any peer is in range the idle gap collapsed to ~0 and the node scanned
back-to-back **forever** (the `PowerPolicy` idle intervals only ever bit when *nothing* was nearby). The
adaptive throttle (`ScanDemandPolicy`) fixes this by driving Boost/Floor from an explicit **demand**
check and splitting a dedicated `scanWake` channel (only `scanLoop` drains it; `connectLoop` keeps
`healSignal`) that `onScanResult` pokes **only for a genuine boost trigger** (a peer we'd initiate to,
above the RSSI floor, unlinked, off backoff). Floor (`settledIdleAfterScan`, ~2 min) engages only with
≥1 link and no candidate/chase — an isolated node still scans aggressively — or while A2DP audio contends
the radio. NAN acts as an **early-warning**: `CompositeMeshTransport.onForeignReachable` (the reverse of
`suppressDataPath`) tells BLE which peers another plane can see, and BLE boosts to chase them onto a link,
bounded by `PROMOTE_CHASE_MS` so a NAN-only / out-of-range peer can't pin Boost. Advertising is untouched
(always-on) so BLE-only devices still discover us. **Load-bearing invariant: `reachable ⊇ neighbors`.**
BLE `reachable` is fed only from scan presence (90 s linger), so once the floor stops re-sighting a
linked peer it would vanish from the "nearby" UI while still linked — `publishReachable` unions live
links back in (`_reachable` only, never `_neighbors`, which routes sends). Verify on-device via the
`bt scan → floor/boost` logcat lines and that a linked peer stays in `…debug.STATE` reachable while the
scan is floored. The side channel below runs a **second** scan with its own policy (`SideScanPolicy`):
continuous at LOW_POWER/BALANCED while a flagged peer is around, Off during a connect, and rationed to
one start per 30 s because Android's five-starts-per-30 s budget is per app and this scan shares it.

## A frame crosses a BLE link once, and the loops sleep until something can change

Two paths used to hand the Bluetooth plane the same frame for the same L2CAP stream — the router's flood copy
(`MeshRouter` → `CompositeMeshTransport.send`, `wire.relayed()`) and the fast path's link copy
(`fastFanout`, the immediate one, unrelayed) — and a relayed frame's fast copy went straight back over the link
it arrived on, because `fastFanout` has no hop id. Different bytes (`hops`), same `sig`, and the far end's
`SeenSet` dropped the second every time: room chat, reactions, receipts and profiles each crossed every link
twice. `mesh/link/LinkCrossings` is the per-link memo that stops it, keyed on `mesh/link/FrameKey` (the
sig-prefix key the side channel already used), marked on the way **in** as well as out, with the router
`SeenSet`'s ten-minute window and forgotten with the link — so what it skips is exactly what the receiver
would have dropped, and a peer that restarted with an empty `SeenSet` gets a clean stream. `BleFastRoutePolicy`
additionally never routes a frame to its own author (a page-first hearing re-fans with the author as hop,
which the router's split horizon can't exclude). Counter: `bleLinkDupSkipped`, about one per room frame per
link. The lab's `LabTransport` keeps the same memo (`dupSkipped`), so the box stays the plane as shipped
(`SideChannelLabTest.aFrameCrossesEachPipeOnce`). ADR 2026-09.6nmy.

The loops around the radio no longer poll on a fixed short tick: `scanLoop`'s paused branch waits 60 s while
the adapter is off (the `STATE_ON` receiver wakes it) and `CONNECT_TIMEOUT_MS + 3 s` while a connect is in
flight (its end wakes it), `connectLoop` sleeps until the earliest connect backoff expires (clamped to 1–60 s,
`ConnectBackoffPolicy.nextDueWaitMs`) instead of every 5 s, and both transports' diagnostic state line runs every
60 s and builds its string only in debug builds. Every wait is still a timeout, so a lost wake costs latency,
never liveness.

## The BLE side channel is a page carousel, not a message queue (ADR 2026-09.sjaa)

`mesh/bluetooth/BleSideChannel` is the BLE analogue of the NAN coordination plane's fast fan-out
(knit/knit-next#13): the `shouldFastFanout` frames — room chat, reactions, receipts, group meta, profiles,
plus the room typing cue — ride **non-connectable extended-advertising pages** under
`BleConstants.SIDE_SERVICE_UUID` (`0xFE38`), connectionless and scheduled by the controller apart from the
ACL, so they bypass a blob head-of-line-blocking the L2CAP stream and reach a sighted-but-unlinked peer.
It lives *inside* `BluetoothMeshTransport` (which now declares `hasFastPlane`; `fastFanout` keeps the link
copy the composite used to send and adds the page, `fastSend` is the link only — nothing DM-form rides a
broadcast carrier), gated by `BuildConfig.BLE_SIDE_PLANE` (debug on, release off) through one seam: the
`BleSideChannel?` DI hands the transport. A page is **one** `FastFrameCodec` unit (`0x03`/`0x05`, or one
`0x04` fragment) ≤ `PAGE_BYTES` = 236 B — one AUX PDU, because an AD structure caps at 252 B, an in-place
update on a live set must be one HCI operation, and a chained page is lost whole. `SideCarousel` (pure)
rotates the queue through `SLOTS` = 2 sets: a 12 s dwell from first air (one screen-off scan interval),
a 30 s linger when nothing waits, fewer-part frames first, a started frame finishes first, typing
coalesced per sender, 30 s freshness, capacity 32. The gate is a BLE-local **flags byte** the presence
advert grew (`BleAdvertPayload` 23 → 24 B, the last byte of the 31-byte budget; `FLAG_SIDE_CHANNEL` set
only while the controller passed its extended-advertising probe), tracked by `SideCapableTracker` with a
10-min linger *or* a live link (restamped at the link's end), since presence prunes at 90 s. The receive
scan is the channel's battery cost, so `SideScanPolicy` runs it only while a page could say something the
links will not — a flagged peer is sighted but **unlinked**, or a file is streaming on one of our links — and
is Off for an all-linked clique with nothing streaming (ADR 2026-09.u8qj; `SideCapableTracker.audience`
tells the two apart, and the sender's page offer still keys on `anyCapable`). A page carries no hop id and is never
presence (each set has its own RPA; `fromNodeId` is the author, ADR 038's rule); fragments reassemble by
fragment id, seeded at random per process. Grep `ble-side` (bring-up probe, `offer`, `heard`, `rx →`);
counters `bleSide*` on `…debug.STATE`. In the JVM, `mesh/lab/LabPages` is the pages' air and
`SideChannelLabTest` runs the author-as-hop, linkless-listener and never-DM shapes through the real
`BleFastRoutePolicy` and codec against the full oracle (`context/testing.md`). Device trial owed before the
release flag flips — the ADR lists it.
