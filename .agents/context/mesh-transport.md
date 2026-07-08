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
  chat, reactions, receipts, group-meta, profiles ≤255 B) *also* ride this plane as a best-effort **fast
  fan-out** (`fastFanout`/`fastSend`), deduped by the receiver's `SeenSet`, so they propagate with zero
  NDP. A cue also bootstraps the reverse handle, so a node whose own *subscribe* is broken (e.g. Pixel 9
  post-kill) still cues larger peers to pull from it.
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

## Wi-Fi Aware availability flaps, and may be absent entirely

`WifiAwareManager.isAvailable()` goes false when Wi-Fi is off or Wi-Fi Direct / SoftAP / hotspot seizes
the radio; the transport watches `ACTION_WIFI_AWARE_STATE_CHANGED`, flips `health` to `Degraded`, tears
links down, and re-attaches on recovery. `PackageManager.FEATURE_WIFI_AWARE` can be missing outright
(some budget/older + certain Samsung models) — but the **Bluetooth LE plane still meshes** on those
devices, since `CompositeMeshTransport` merges whichever radios are present, so the UI shows the
"unsupported" state only when *neither* Wi-Fi Aware nor BLE hardware exists
(`hasWifiAwareHardware || hasBleHardware`, in onboarding).

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
scan is floored.
