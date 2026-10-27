# Bluetooth plane — which decision governs which symbol

The symbol → ADR routing for Knit's BLE mesh transport: `mesh/bluetooth/` outside `meshtastic/` and `wear/`, with
`mesh/link/FramedLink.kt` and `mesh/power/ElapsedWait.kt`. The `bluetooth-owner` reads it before it writes and the
`bluetooth-reviewer` before it judges (both in `.agents/personas/`); `AGENTS.md` points here instead of carrying these
lines. Each entry names the symbols that trigger it, the ADRs to read (`.agents/memory/decisions.md` maps an id to its
file, amendments included) and, where there is one, the section of `mesh-transport.md` that explains the mechanism.
An entry is an index into its ADRs, not a substitute: where they disagree, the ADR wins and the entry is fixed.

Bluetooth-adjacent routing that lives in another subsystem's file under `.agents/context/`, because that subsystem owns it: the LoRa/Meshtastic bridge
(`mesh/bluetooth/meshtastic/`), the Wear status server (`mesh/bluetooth/wear/`), the lonely cadence shared with NAN
(`PowerPolicy`, kb68/w3xk), and the blob exchange's reads of the link (`BlobExchange`, `fileInFlightTo`, 4tx5 and on).

## Entries

- **When touching `mesh/bluetooth/BleSideChannel`, `SideCarousel`, `SideScanPolicy`, `SideCapableTracker`,
  `BleFastRoutePolicy`, `BleAdvertPayload`'s flags byte, or `BluetoothMeshTransport.fastFanout`/`fastSend`:**
  READ ADR 2026-09.sjaa and the "page carousel" section of `.agents/context/mesh-transport.md`. The BLE side
  channel carries `shouldFastFanout` frames on non-connectable extended-advertising pages — one
  `FastFrameCodec` unit per 236-B page, never a chain, never presence, never a DM-form frame — gated per
  peer on the advert's flags byte and behind `BuildConfig.BLE_SIDE_PLANE`, on in release since 2026-10-08 with
  trials still owed (CHECK `.agents/memory/roadmap.md`). The receive scan is Off for an all-linked clique with nothing
  streaming (ADR 2026-09.u8qj) — the link copy already reaches every linked peer; don't widen that gate. Its slots are
  enabled again on the presence set's re-assert turns (`BleSideChannel.reassert`, ADR 2026-10.9utz's 2026-10-09
  amendment); the channel's lock is taken before a slot's, never inside a slot's callback. `hasFastPlane` is now true for Bluetooth: the link copy the
  composite used to send lives inside the transport's `fastFanout`/`fastSend`; don't add it back upstream.
- **When touching `CodedPhyPolicy`, `PhyStepper`, `BlePhyControl`, `CodedPhyDiag`, `CodedAdvertPace`, `RangeHunt`,
  `ColdSightings`, `CodedPhyPolicy.coldHunt`, `PhyTuning.oneMWeakDbm`, `AppVisibility`,
  `BleAdvertiser.codedParams` / `setInterval`, `BleScanner.phys`, `BlePresenceTracker`'s per-PHY RSSI,
  `BuildConfig.BLE_CODED_PHY` or `…debug.PHY`:** READ ADR
  2026-10.yvn6 and the Coded PHY section of `.agents/context/mesh-transport.md`. On in release since 2026-10-08: a
  second presence set on Coded (the legacy advert stays), an all-PHY scan, a Coded sighting scored on the 1M scale
  (+12 dB) so every −90 floor is untouched, and per-link 1M ↔ Coded S=8 steps driven by the larger id through a GATT
  handle on the link's ACL; a link that flaps backs off its step-up, never its step-down (`PhyStepper.stepUpBackoffMs`,
  `PhyTuning.flapWindowMs` / `stepUpBackoffMaxMs`, #169 — the 2026-10-14 amendment: a late step-down at the edge costs
  the link, a late step-up only throughput). Capability is inferred from hearing the Coded advert — no flags-byte bit. A dialer heard on
  Coded alone is admitted as unsighted (shzv's tie-break holds for 1M), and OFF leaves a link on its PHY. A Coded dial
  gets a 25 s watchdog, and a link that drops at range makes the dialed side's Coded advert fast for 3 min, its
  interval moved in place (`CodedAdvertPace`, `BleAdvertiser.setInterval`; the amendment of 2026-10-02), while the
  dialing side hunts it — lonely gap, Coded windows, no settled floor, whatever other links it holds — for those
  3 min, or up to 30 while Knit is open on a lit screen, which hunts on its own too (`RangeHunt`, the 2026-10-04
  amendment). A 1M hit under `PhyTuning.oneMWeakDbm` (−90 smoothed, raw) leaves the Coded-only clock running, and a
  cold far sighting (unlinked, in `ScanPlan.far`, at or above −90 effective) gets the same hunt or fast advert for at
  most 3 min, gated by `CodedPhyPolicy.coldHunt` (promotion room for a hunt, off under low power, d44b) and bounded by
  `ColdSightings` (one per peer per 30 min, one of each at a time, 3 min of each kind per 30 min phone-wide, each its own budget; spent last) —
  the 2026-10-20 amendment, a draft whose walk trial is owed. CHECK
  `.agents/memory/roadmap.md` for the trials it still owes.
- **When touching `LinkFade`, `PhyLinkStatus.fading`, the `PhyTuning` fade fields, `BlePhyControl`'s read accounting,
  or `liveLinkFading` (`BluetoothMeshTransport`, `CompositeMeshTransport`, `MeshTransport`):** READ ADR 2026-10.79yb's
  ninth amendment. A link fades at a smoothed link RSSI of −95 or on three unanswered reads in a row (counted, never
  timed: the read wait stops across a CPU sleep), and recovers only after answered reads hold −90 or better for 10 s. While it fades, a walkie chunk goes through the relay beside its radio copy. The answer
  must stay cheap: it is asked per chunk, twice a second, on the send path. A link with no PHY handle never fades.
- **When touching `CodedPhyPolicy.pace`, `PhyTuning.filePaceBytesPerSec` / `BLE_PACE_BYTES_PER_SEC`, `PaceConfig` /
  `PaceWindow` / `TransferPacePolicy`, `BluetoothMeshTransport.paceFor`, or `FramedLink`'s `pace`:** READ ADR
  2026-10.8jwn. **Before touching `FeedWindow`, `PaceConfig.windowed`, `BluetoothMeshTransport.windowed`,
  `FramedLink.feedChunks` / `awaitReport`, `RxReports`, or `BuildConfig.BLE_FEED_WINDOW`:** READ ADR
  2026-10.5buf — a file to a peer that reports (an iPhone too since its 2026-10-09 amendment, paired with the iOS half) is clocked by those reports on any PHY (written and not yet held kept under
  the measured drain over the round trip plus a quarter second, BBR-style, no split, no earned budget; on Coded in 1 KiB
  chunks, its amendment of 2026-10-08 (2)), the old pace only for a silent receiver or an older build (silent: no report that lands a new whole chunk, its #149 amendment of 2026-10-27); on in release since 2026-10-08 (its A/B still owed), switched on a debug build by `…debug.LINKTUNE --es feed window|pace`. A Bluetooth file is fed below what the air drains, from one budget split evenly among the 1M/2M links
  feeding a file at once (`txInProgress`), in chunks of two seconds of the share; a paced Coded link keeps its own pace outside the
  split (yvn6's #114 amendment). The stack's queue hides the drain from the sender, so tune against the receiver's
  `rx … in <ms>ms` line — never the sender's `file … in`, which reads size ÷ pace. No wire change.
  **Before touching `LinkBoostPolicy`, `BleLinkBoost`, `BleLinkTuner`, `LinkParamsCallback`, `FramedLink`'s `onBusy` /
  `feeding` / `busy`, `PhyTuning.boostPaceBytesPerSec`, `BuildConfig.BLE_LINK_BOOST`, `LinkTuneDiag` or
  `…debug.LINKTUNE`, or measuring a drain:** READ ADR 2026-10.2da4 (and work item #120). A link's drain is the host's
  buffer share times the interval, so while a file moves either way, a side whose screen is on asks for
  `CONNECTION_PRIORITY_HIGH` (and the MTU once) through one GATT client per link, and lets go 15 s after neither
  direction streams, or at once on screen-off; a dark side only listens. A link's budget rises to 18 KiB/s (12 past four
  LE connections) only while that link *reported* ≤ 15 ms (`earned`; per link since ADR 2026-10.g24x), else 8jwn's
  4 KiB/s. On in release since 2026-10-08, its trial still owed. The trial is `scripts/ble-drain-trial.py --boost on|off`, and the oracle is both phones' HCI snoops read
  by `scripts/btsnoop-drain.py` — the sender's, since only it shows the queue.
- **When touching `BluetoothMeshTransport.superviseAccepted`, `BleAdmissionPolicy`, `teardownLink`, `LinkEvents`,
  `neverSighted`, `LonelyDialPolicy` / `noLinkSince`, or what a held link scores for eviction:** READ ADR
  2026-09.shzv and ADR 2026-09.hj4a. A dialer presence holds is judged by the old tie-break, unchanged — the
  Android mesh must not move. Only an unsighted dialer (an iPhone
  advertises no service data, so it is never sighted and never dialed) is admitted whatever the id order, and its
  second link replaces the first only once the held one is 30 s old. A `CAP_DOORBELL` dialer (an iPhone) counts as
  sighted only if heard within 12 s (`DOORBELL_HEARD_MS`, shzv's AND-3 amendment), so a backgrounded iPhone's re-dial
  gets in. A teardown passes the link it means (`only =`) — never re-key it by node id alone — and only a
  never-sighted link or a `CAP_DOORBELL` one scores the −90 floor out of presence; any other gone from presence −127. The one
  exception to "larger dials" is hj4a's lonely dial: after 180 s with **no** link (`noLinkSince`, the clock the
  scan's lonely cadence shares) a node dials the strongest larger id it sights, one at a time, and it rests on the unsighted admit:
  a responder that sighted the newcomer refuses it and dials it itself. Its oracle, `bt lonely dial <id> (…)`, is
  keyed by the iOS interop harness — don't reword it.
- **When touching a TX power in `BleAdvertiser` (`presenceParams` / `sideParams` / `codedParams`), an RSSI threshold
  on an advert reading, `PromotionPolicy.msUntilDue`, `nextConnectWaitMs`, `mesh/power/ElapsedWait`, or any wait in
  `BluetoothMeshTransport`'s scan loop, connect loop or dial/HELLO watchdogs or `BleGattPayloadReader`'s give-up:**
  READ ADR 2026-10.ryak and ADR 2026-10.pj9w, and the "Discovery keeps wall time" section of
  `.agents/context/mesh-transport.md`. The presence
  advert is `TX_POWER_HIGH` and every discovery set goes out at its power (pinned by `BleAdvertiserTest`); every −90
  floor reads it, so a new advert threshold is sized against HIGH. A dial gated on the clock gets a wake when it comes
  due — the next sighting is not one on a screen-off phone. The waits a relink waits on (connect loop, dial/HELLO
  watchdogs, scan window and pause, the hunting gap, the GATT payload read's give-up) go through `elapsedWait`, never
  `delay` / `withTimeoutOrNull`, which stop while the CPU sleeps; the settled, linked and relaxed scan gaps keep
  stretching on purpose (power budgets — moving one is a battery change). Measure with `scripts/ble-link-trial.py`.
- **When touching `ScanPlan.near`, `BlePresenceTracker.Snapshot.oneMFresh` / `codedFresh`, `BluetoothMeshTransport.near`,
  or `MeshTransport.near` / `onForeignNear`:** READ ADR 2026-09.9dnk (its 2026-10-20 amendment) and ADR 2026-10.yvn6.
  `near` is the set that corroborates Wi-Fi Aware's Tier-2 self-kill: link peers on any PHY, plus peers whose strongest
  reading heard in the latest burst, raw on its own PHY (no Coded credit), is ≥ −90 and that are not
  `CodedPhyPolicy.codedOnly`. A stored `rssi1m` outlives 1M going silent, so freshness is the tracker's own
  latest-burst rule, shared with `smoothed()`. It is built from the same `snapshots(now)` call as `reachable` (so
  `near ⊆ reachable`), cleared at stop and adapter off, and logged in `bt state` (`near=[…]`, with the dormancy
  fields after it). Since ADR 2026-10.2t49 it also ages: `publishReachable()` runs at the end of every presence window
  (`endWindow()`), so an expired entry leaves within one gap. There is still no wall-clock timer between windows.
- **When touching `BleAdvertPayload.FLAG_NO_AWARE` / `flags(noAware)`, `NoAwareFlag`, `AwareCandidateTracker`,
  `NewcomerWatch`, `BluetoothMeshTransport.awareCandidatesNear` / `awareCandidatesHeard` / `seesNewcomers` / `awareWontRun`,
  `endWindow()`, `publishLock`, or `BleAdvertiser`'s `dataStale` re-push:** READ ADR 2026-10.2t49. The flag is the
  absence of Aware (bit 2, set while a peer should not count on this phone's Aware: it will not run it, or it is in
  deep idle — the ADR's 2026-10-24 amendment), set at once and cleared only after 60 s held false, so
  every older build reads as a candidate and Doze windows do not wake neighbours. A peer is a candidate only on a
  service-data sighting with the bit clear; a GATT read forgets it, and a `CAP_DOORBELL` HELLO or a never-sighted link
  never makes one. A link counts only while its readback says 1M or 2M; Coded, unknown or no readback count as Coded.
  The heard set lapses only after 90 s and a whole 1M window without the peer, so it outlives one gap whatever the gap;
  near ⊆ heard. `seesNewcomers` falls at once on adapter off and `stop()` and is set at each window's end from whether
  the scan ran its length registered; `NewcomerWatch` owns it (value, flow and log callback under one lock), and a
  window's token taken before an off edge never writes at its end. A refused data write is re-pushed on the next
  reassert, never in a loop. Tests: `AwareCandidateTrackerTest`, `NoAwareFlagTest`, `NewcomerWatchTest`,
  `BleEndOfWindowTest`, `BleAdvertiserTest`, `BleAdvertPayloadTest`.
- **When touching `BleAdvertiser.reassert` / `onAdvertisingEnabled`, `AdvertReassertPolicy`, or the transport's
  `advertLoop` / ACL edge receiver:** READ ADR 2026-10.9utz. The stack re-enables its advertising sets around every
  connection and the controller can refuse that (0x0d), reporting it only for a connection *to* the set — never for
  one the phone made — and never retrying. So the transport enables the live presence set again itself: 2.5 s after
  each ACL edge, on a doubling wait after a reported refusal, on a 10 s (no link) / 60 s net, and at once on `heal()`.
  A stopped set must stay down (`stop()` clears what `reassert` would restart). The side channel's page slots ride the
  same turns through `BleSideChannel.reassert` (the amendment of 2026-10-09, #143) — only a slot with a page pushed on a
  live channel, never one sitting out a refused start — with per-slot refusal retries, never `advertKeeper`'s. The ACL receiver is
  `RECEIVER_EXPORTED` on purpose: the Bluetooth app (uid 1002), not `system_server`, sends `ACTION_ACL_*`, so a
  non-exported receiver never fires (the ADR's amendment). Tests: `AdvertReassertPolicyTest`, `BleAdvertiserTest`,
  `BleSideChannelReassertTest`.
- **When touching `mesh/bluetooth/BleDoorbell`, `DoorbellPolicy`, `Protocol.CAP_DOORBELL`, or what
  `BluetoothMeshTransport.writeOnce` rings:** READ ADR 2026-09.dqvb (companion change A4 for the iOS port). A link
  rings only when the peer's HELLO carries `CAP_DOORBELL`, which Android never claims, so Android↔Android links never
  ring (their GATT clients are b8yf's link-up arm, 2da4's link boost and yvn6's PHY control, never with discovery). The doorbell UUIDs are cross-platform law, and the schedule is the port's. `typing`, `blobreq`,
  `blobhave`, `blobhas`, `blobprog`, `blobcancel` and `keyreq` don't ring. The GATT client attaches to the link's own ACL, closes on a 2 s attach timeout, and lives
  and dies with the link. A ring is a write without response, poked at the enqueue, never after the socket write.
  The lookup runs at link-up and each one that finds the doorbell asks for `CONNECTION_PRIORITY_BALANCED`: an
  iPhone central runs the link at a 720 ms supervision timeout, BALANCED carries 5 s (#102, the ADR's amendment).
  The stack's put-back of the central's own timeout can land after that ask, so `DoorbellPolicy.Balanced` asks again,
  on the hidden `onConnectionUpdated` or a 2 s settle check, at most twice a lookup (the put-back amendment).
- **When touching `OrphanAclPolicy`, `BluetoothMeshTransport.armAcl` / `armUnlinked` / `checkOrphans` / `releasable` /
  `disarmAtStop`, the ACL edge receiver's `EXTRA_DEVICE` read, a refusal or failure path in `superviseAccepted` /
  `initiateTo`, `BleLinkTuner`'s `label` / `released` / `closeAgainLater` / `onNeverRegistered`, `BleDoorbell`'s `onAttached`,
  `LinkTuneDiag.aclArm` (`…debug.LINKTUNE --ez arm`), `FieldEvent.BtOrphan`, or `leLinkCount`:** READ ADR 2026-10.b8yf
  (#142). An LE ACL can outlive Knit's CoC: Android 12's ATT channel never idles out unless a local GATT client held it
  and left, and on Android 16/17 the side that dialed never gets the no-app idle timeout (`gatt_set_idle_timeout …
  status=false (not performed)` at connect, 13 of 13 central links; a peripheral's is set and idles out in 4 s). So every
  ACL a channel opened on is
  armed: a `BleLinkTuner` that asks nothing attaches and leaves, `autoConnect = false`, outside `tuners`, never cancelled
  at teardown — at link-up for every link not served by the doorbell (whose lookup's attach is the arm), and 2 s after
  a channel that never became a link (a refusal, a failed HELLO or dial), only while the ACL's edges say it is up and no
  link or dial of ours is on it. A late Android 12 registration is let go by the tuner's `released` guard. The net is the
  ACL broadcasts, never `getConnectedDevices(GATT)` (it lists other connections only while some GATT server is
  registered): an ended channel's address is watched only while its connect edge was seen and no disconnect followed, is
  released on `diagLoop`'s minute at most twice past a 15 s grace (once after an Android 12 accept-list re-dial; a
  disconnect counts only after 15 s down), re-checked for our own dials at the attach, and is recorded once
  (`bt_orphan`); `stop()` arms and records what is still up and unarmed. The arms are bounded (#150, the ADR's
  amendment): one settle per address in `armUnlinked` (keeping the latest node id), and every arm and release is a
  token-named flight holding one of `MAX_ARMS_IN_FLIGHT` (3) slots until it attaches, or `SECOND_CLOSE_MS` past a
  failure, and none once `BleLinkTuner`'s `onNeverRegistered` says it made no client; HELLO-less arms hold at most
  `MAX_UNNAMED_ARMS` (2), and `check()` releases only into a free slot. `OrphanAclPolicy.tryArm` refuses an unlinked
  arm while one is pending or attached on the address, for `ARM_RETRY_MS` after one failed, and while the slots are full; the last two
  are waited out on the elapsed clock (`msUntilOpen`, twice at most), and a HELLO-less channel still refused is watched
  (never over a named entry, at most `MAX_UNNAMED`). A link-up arm is never refused. The address's arm row outlives a
  channel's end, a superseded arm's failure never downgrades it, and an attached row goes with the disconnect edge, a
  connect edge past `EDGE_TRAIL_MS`, `clear()` and `stop()`; `skipped=` on `bt state` counts gate refusals (retries
  included), not channels. Tests:
  `OrphanAclPolicyTest`.
- **When touching `LinkSilence`, `SocketStamps`, `BluetoothSocketLink`'s streams, `BluetoothMeshTransport.silenceLoop` /
  `closeSilent` / `closeOffThread` / `onLinkEdge` / `suspectStack`, `BlePhyControl.lostAt` / `onLost`,
  `FramedLink.sendKeepalive` / `onKeepalive`, or what a `LinkFraming.Type.KEEPALIVE` does:** READ ADR 2026-10.697m
  (#203). A link this phone can show is dead goes down through the ordinary teardown, `neighbors` and `reachable` moved
  before its socket closes off-thread: probes toward a peer that announced itself with a `KEEPALIVE` (90 s quiet; the
  first probe rides the next record this side queues anyway, in practice the 60 s re-offer digest, through
  `LinkSilence.carry` in `sendDigest` / `writeOnce` / `sendFile`, else falls back after `CARRIER_WAIT_MS` on the uptime
  clock; the second 30 s after it on the timer; about 210 s); toward a peer that does not answer, its PHY client's loss after 90 s quiet; and on any
  non-doorbell link, one `write()` sitting 120 s. Toward an answering peer a client loss only probes early, as an edge.
  Unanswered link-RSSI reads close nothing: after a process freeze they timed out 27 in a row on healthy links (the
  2026-10-19 trial). A peer that has sent a `KEEPALIVE` answers each one except one meeting an announce or probe of its
  own still unmatched — a cross-platform rule, though iPhone (`CAP_DOORBELL`) links are exempt from all of it. Stamps are
  bytes on the raw socket streams, never `FramedLink.lastActivityAt` (Wi-Fi Aware's), and a keepalive is never data. An
  edge probes, never closes. `bt link silent`, `bt link probe … via=<trigger>`, `bt link answers`, `bt link edge`, `bt link close slow`
  and `bt stack suspect` (after `LOST` closes only, a soft signal) are new lines; `bt link down: <id> (silent)` is the
  harness's `down`. Tests: `LinkSilenceTest`, `SocketStampsTest`, `FramedLinkTest`.
- **When touching `mesh/bluetooth/GattPayloads`, `BleGattPayloadReader`, `BleAdvertPayload.FLAG_DIALS_GATT_PEERS`,
  `BuildConfig.BLE_GATT_PEERS`, `BleScanner`'s UUID filter, or `BluetoothMeshTransport.onScanResult` / `sight`:**
  READ ADR 2026-09.kwq2 (companion change A3; knit-ios ADR 2026-09.xzpt) and its section in
  `.agents/context/mesh-transport.md`. A foreground iPhone is found by reading its GATT payload and dialed by the
  unchanged rule (larger id dials); `GattPayloads` is the iOS pacing line for line (one read at a time, 12 s,
  30 s / 10 min waits) plus two Android additions: a 64-address LRU, and a budget across addresses with a yield to a
  ripe dial (the 2026-10-11 amendment, #154: at most 12 charged reads per 10 min; every read that ends is charged,
  a payload read included, because the payload is unauthenticated; only a link's HELLO at the address naming the
  payload's node refunds it, once (`GattPayloads.confirmed` in `registerLink`); a cancel is free and no forget is
  charged; and no new read while the connect loop has a dial it can make, `DialPlan.dialWaiting` over promoted peers
  with an address). A payload lives until one of the contract's three rules forgets it: a
  dial failing before its channel opens (never on HANDSHAKE), a HELLO on a link to the address naming another node,
  or 60 s of scanning unheard with no link up. The flag is set only while the reader runs — one build switch gates filter, reader and flag, on in
  release since 2026-10-08, the trial still owed. A1 (shzv) stays whole: it is the fallback that makes a clear flag safe.

## Adding an entry

A new decision about this plane gets a bullet here — the symbols that trigger it, the ADR, and the invariant in a
sentence or two — never a bullet in `AGENTS.md`. A symbol listed nowhere is not exempt: its KDoc often carries the
incident that shaped it (`BleAdvertiser`, `BleScanner`, `BluetoothMeshTransport.registerAvailability`).
