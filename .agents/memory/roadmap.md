# Roadmap / out of scope (deferred, by design)

What's deliberately deferred, and what has since shipped. Update this as scope lands (the BLE + digest-pull
notes below moved from "deferred" to "implemented" — that evolution is why this is memory, not a static
doc). **Don't start a deferred item without explicit direction.**

## Already shipped (was deferred)

- **A screen-on lonely node relaxes its Bluetooth scan BUILT** (2026-09-21, ADR 2026-09.w3xk, work item #65) —
  past the three-minute window a screen-on node on battery idles 60 s between its 12 s BALANCED windows
  (≈ 4 % receiver duty from 12.5 %); charging never relaxes. **Still owed:** the device trial in the ADR (the
  `bt scan lonely:` lines are its oracle). **Deferred from it:** the same for the Wi-Fi Aware re-arm —
  `NanLonelyPolicy` keeps a screen-on lonely node at 8 s / 15 s (ICM ≈ 100 %) on purpose, because the only
  relaxed tick it has for that case is the 30 s duty cycle and ICM is lit 30 s per re-arm; it needs a tick of its
  own (≥ 60–120 s) and a re-run of ADR 2026-09.kb68's trial.

- **The mesh pauses from its notification BUILT** (2026-09-20, ADR 2026-09.wz99) — `Pause 15 min · Pause 1 hour
  · Stop` on the running notification, `Resume · Stop` on the paused one, a "Mesh paused until …" banner on the
  chat list; the service stays foreground, `MeshManager` goes down, and two inexact alarms plus every start
  bring it back. Stop is sticky the same day (the ADR's amendment): opening the app no longer restarts a
  stopped mesh, and the banner's stopped form carries Start. Device-verified the same day on the Pixel 3 (resume 32 s past the deadline on an awake phone;
  reinstall mid-pause, banner Resume and Stop-while-paused all as designed). **Still owed:** the resume delay
  from deep Doze and a reboot mid-pause. **Deferred from it:** a 12 h / "until tomorrow" span — the shade shows three
  actions and Stop keeps its seat, so a third span needs an in-app surface (a Settings row or the banner's
  menu); a "Paused until …" line on Diagnostics' status (it still reads the transports' last health).

- **A phone whose Wi-Fi drops when it initiates stops initiating BUILT** (2026-09-19, ADR 2026-09.m8kc, work
  item #78) — `NanInitiatorPolicy` counts a Wi-Fi blip within two minutes of an unlinked initiate of ours as a
  strike, holds the initiator role at three, journals it by build+ROM stamp, probes once a day, and Diagnostics
  says so with a Try again. Device-verified the same day on the P3 (one natural strike, the rest injected; the ADR
  has the log). **Still owed:** the initiator-link refund on hardware. **Deferred from it:** bgk3's follow-up, re-attaching directly on the STA drop
  that silently kills the Aware client — `onStaLost` is now the signal, but it is a recovery for a blueline-only
  fault, and a session cycle on every healthy phone that walks out of AP range is the price; build it only if a
  second device class shows the silent death.

- **The mesh service asks for the location foreground type on API 29–32 BUILT** (2026-09-18, ADR
  2026-09.535d, work item #62) — `meshForegroundServiceTypes` claims `connectedDevice|location` on the tiers
  where `requiredRadioPermissions` rides the location grant, `NanSessionFault` tells a location refusal from
  a dead Aware client, and the transport holds it as `TransportHealth.ForegroundOnly` instead of re-attaching
  45 times in four minutes. Device-verified the same night on the Pixel 3 (`curCapability=L--N` off screen,
  zero refusals over 25 min, the latch and the heal retry exercised through a forced app-op; the ADR has the
  numbers). **Still owed:** a real reboot-without-opening (the P3's `adb tcpip` does not survive one), the
  Play upload check that the manifest's `location` attribute alone raises no location-FGS declaration, and a
  re-run of ADR 2026-09.kb68's lonely-node trial on the P3, which this masked (every re-attach reset
  `lonelySince`). Two suspects it surfaced, filed as #76 and #77: `canReclaimForegroundService` reads
  `PROCESS_STATE_RECEIVER` as `IMPORTANCE_SERVICE`, so `BootReceiver`'s start was refused on an unexempted
  phone despite ADR 043 calling it exempt — **fixed 2026-09-19** (ADR 2026-09.29dw, `MeshService.startFromBoot`
  skips the pre-check; the reboot-without-opening device run is still owed); and the NAN responder's
  `onUnavailable` re-file has no backoff (174 re-files in 130 ms on the P3).

- **Supervised and managed phones are named SHIPPED** (2026-09-16, ADR 2026-09.a8ud) —
  `ui/DeviceSupervision.kt` reads Family Link's supervision profile owner or any other management signal
  into one enum; the onboarding
  rows, the location / mic / camera gates (one shared `PermissionDeniedDialog`), Settings and Diagnostics say
  who may hold a greyed-out grant and where. Built on a Pixel 8 trial that showed Family Link's pauses and
  downtime leave the running foreground service alone, so no restart machinery was needed. Device-verified
  the same day (Settings, Diagnostics, the pin's dialog on the Location-denied Pixel 8), and the ATF suite
  passed 25/25 on a rooted API 34 `aosp_atd` AVD given a user restriction (`adb root` + `pm
  set-user-restriction no_config_date_time 1`) so the Managed lines rendered — the Play-store AVDs refuse
  that command. No finding on the new rows.

- **A Restricted battery setting is shown for what it is SHIPPED** (2026-09-14, ADR 2026-09.gc3m, the
  follow-up ADR 2026-09.f69x deferred) — `ui/BackgroundBattery.kt` reads the three-position setting
  (`ActivityManager.isBackgroundRestricted()` over the exemption), Settings' battery line says allowed /
  optimized / restricted with the one action that can move each, and the onboarding battery row lands on
  "Open settings" with its own hint instead of offering an exemption prompt that cannot lift Restricted.
  **Still owed:** the device trial (a Restricted phone opening Settings and the permissions page) and the ATF
  pass over the new rows.

- **An in-app light/dark override SHIPPED** (2026-09-09, ADR 2026-09.v5ck, superseding
  ADR 2026-09.m9h8's closing paragraph) — Settings carries a System / Light / Dark segmented control,
  defaulting to System. Built on `UiModeManager.setApplicationNightMode` rather than in Compose, so the
  choice moves the app's own `Configuration` and the launch window, `isSystemInDarkTheme()` and
  `enableEdgeToEdge()`'s bar polarity all follow it without `KnitTheme` or `MainActivity` changing at all;
  `MODE_NIGHT_AUTO` clears the override, which is what makes "System" reversible. **Hidden on API 29-30**,
  which have no per-app night mode and no route to the launch window, so an override there would re-create
  issue #2. **Still owed:** the device trial (a cold launch with Dark pinned on a light phone is the whole
  point) and the ATF pass over the segmented control.

- **Sealed DM-form chat rides the NAN coordination plane** (ADR 2026-09.7463,
  `docs/DM_COORDINATION_PLANE.md`) — `FrameFanout.shouldFastSend` admits the DM form `shouldFastFanout`
  excludes, wired at origination and the inbound re-fan (split-horizoned on `fromNodeId`), **targeted at the
  addressee, never fanned**. Closes the gap the ADR 2026-09.9dnk wedge exposed: with no NDP and no
  Bluetooth, broadcast room chat flowed while every DM, group-key seed and `CTL_GROUP_KEY_REQ` sat
  undelivered in custody. Shipped with `MeshMetrics.nanOwedNoLinkPeakMs` — **do not drop that gauge as
  unused**, it is the replacement for the symptom this removed (a wedge now presents as "works, slightly
  worse" rather than stopping DMs). Group-form chat and the spool plane are deliberately untouched.

- **The BLE side channel is BUILT** (2026-09-16, ADR 2026-09.sjaa; knit/knit-next#13) — the BLE analogue of
  the NAN coordination plane's fast fan-out: `shouldFastFanout` frames plus room typing on non-connectable
  extended-advertising pages (`mesh/bluetooth/BleSideChannel`, one `FastFrameCodec` unit per 236-B page,
  two sets, 12 s dwell), gated per peer on the flags byte the presence advert grew (`BleAdvertPayload`
  23 → 24 B). Internal to `BluetoothMeshTransport`, which now declares `hasFastPlane`. Debug-only until the
  trial below runs.
- **The Bluetooth LE plane is implemented** (`mesh/bluetooth/`) and runs *simultaneously* with Wi-Fi Aware
  behind `CompositeMeshTransport` (wired in `di/MeshModule.kt`): BLE advertise/scan presence + persistent
  L2CAP CoC data links, *preferred* over NAN's ephemeral NDP, with per-peer escalating connect backoff and
  A2DP-audio instrumentation. It is a co-plane, **not** a fallback, and BLE-capable devices use it
  regardless of Wi-Fi Aware support.
- **Digest/pull anti-entropy** — the cue-plane `StoreDigest`/`DigestTracker` + the data-path
  `LinkFraming.Type.DIGEST` id-diff (`docs/DIGEST_PULL_REATTACH.md`).
- **Inbound key-request** for a frame received from a not-yet-pinned sender (the inbound complement of
  retransmit-on-key-arrival) — now `KeyExchange`; see `context/store-and-forward.md`.
- **R8 obfuscation (name mangling)** is enabled on release/staging (was shrink + optimize only, behind
  `-dontobfuscate`). The wire stays safe by construction — kotlinx.serialization compile-time descriptors +
  the frozen wire/identity DTOs pinned unrenamed in `keepRules/knit-r8.keep` — and `FileKind`'s file-header
  token is decoupled from its enum constant name (`FileKind.wire`). See decisions ADR 012. The broad library
  `{ *; }` keeps are **no longer deferred**: ADR 050 dropped the Tink / ARSCLib / apksig ones (97% rates,
  dex 9.9 → 6.0 MB) and added `scripts/r8-dex-gate.sh` to CI so a library's consumer keep rule can't quietly
  undo it. Still pinned by choice: `net.zetetic.**`, `org.tensorflow.lite.**`, `org.xmlpull.v1.**`.
- **Forward secrecy for DMs is implemented** — the epoch-rekey ratchet (crypto scheme v2,
  `docs/FORWARD_SECRECY_RATCHET.md`; ADR 016): X3DH-style bootstrap off a signed prekey published in the
  profile, per-epoch X25519 rekeying, session state in the `ratchet_*` tables, capability-gated dual-stack
  (v1 static wrap remains for groups and pre-ratchet peers, and inbound v1 is accepted forever). Also
  supplies the `pairwiseRoot` export the internet-relay scope derivation consumes
  (`docs/SPOOL_PROTOCOL.md` §3, `ScopeCrypto`).
- **The spool (internet-relay) protocol is specified** — `docs/SPOOL_PROTOCOL.md` (ADR 019, public,
  normative) plus the pure reference implementation and vector anchors (`mesh/crypto/scope/`
  `ScopeCrypto`/`SpoolPow`, `mesh/spool/` `SpoolRecords`; API-only, zero runtime consumers). Names
  committed: spool / scope / `ScopeSync` / `knit-spool` (AGPL-3.0).

- **The spool heal is dirty-driven, and so is the scope derivation** (2026-09-18, ADR 2026-09.wa79). A
  round now runs on a moved digest, a delivery, a direct push, a custody change or the 60 s tick, with one
  custody read per round; a dead relay backs off to 15 min, no dial with no route, and the socket pings
  every 4 min on cellular. ~~**Deferred:** moving `ScopeSync.reconcile`'s 15 s scope derivation onto
  events~~ — **done 2026-09-20** (work item #75, ADR 2026-09.dcah): `RatchetSessions.rootChanges`,
  `IntroSync.onPairsChanged` (the pair set and a pending peer's pin), the own group-root mint, and the poll
  at 60 s as the net under the calendar-only transitions; no lab poke was needed. **Still deferred**, in
  `knit-spool`: the daemon's own 30 s ping still wakes a cellular modem; an additive client→spool hello
  hint (`pingS`, spool clamps, absent = today) plus a spec line would let the client's four-minute cadence
  hold end to end.

## Still deferred (by design)

- **Enforcing the mesh-lab chaos job** (`mesh-lab-chaos` in `.github/workflows/ci.yml`, 2026-09-24). The
  first whole-package heavy-tail sweep (seeds 1000–1009) failed nine scenarios on main: AttachmentLabTest
  `aPictureHeardOverTheBoard…` (seed 1005), CustodyLabTest `bothSidesSendWhileApartAndMerge` (1003),
  GroupFirstMessageLabTest `…RestartsBetweenTheSeedAndTheFrame` (1005), InternetPlaneLabTest `aPhotoTheRelayDelivered…`
  (1000), RestartLabTest `aSenderThatRestartsBeforeTheTickLands…` (1005), RestoreLabTest `…CarriesOnUnderANewSession`
  (1002) and `…ReMintsItsGroupChain…` (1007), RoomTickPlanesLabTest `aRoomTickWithNoRideIsPushed…` (1006),
  SideChannelLabTest `aStrangerOnThePagesParks…` (1006). Each is a latent flake, a mesh race, or a chaos
  artefact to fix in `LabChaos`. Triage every one, then drop the job's `continue-on-error` and mirror it in
  `.gitlab-ci.yml`.

- **A per-peer in-flight window on the Wi-Fi Aware coordination plane** (ADR 2026-09.jjhg, 2026-09-21, work
  item #81). The framework's send-queue deadlock needs eight follow-ups the framework has already timed out
  still sitting in the firmware's queue — which a burst to a peer that cannot ack fills. One message in flight
  per peer with a short, aging FIFO would keep our traffic from ever filling it. Not built with the watchdog
  because it is prevention for one of the two mechanisms only, it does nothing for a firmware that has stopped
  delivering, and it serialises a fragmented frame's parts (a latency cost that wants a device measurement
  before it is paid). Build it once a burst night with the watchdog in place still shows `mSendQueueBlocked:
  true` in the poll's `dumpsys wifiaware` — the cycle cures it either way, but a cycle mid-burst drops the burst.
- **Process recovery after a Family Link pause** (ADR 2026-09.a8ud, 2026-09-16). An
  `ACTION_MY_PACKAGE_UNSUSPENDED` receiver that restarts the mesh, with a "tap to reconnect" notification
  as the battery-Optimized fallback, was designed and then dropped: the trial showed `setPackagesSuspended`
  never stops a running foreground service and a kill while suspended comes back on the sticky restart.
  Revisit only with evidence of a suspended Knit staying dark — `dumpsys activity exit-info` is the oracle.

- **A BLE-only mesh under a parent-held Location grant on API 31–32** (ADR 2026-09.a8ud). Family Link can
  deny Location, which sits in `requiredRadioPermissions` on 29–32; on 31–32 BLE needs no location, so the
  composite *could* run one plane. ADR 2026-09.nzpr's objection stands — the transports are not
  permission-safe per plane — and the population is Android 12 kids' phones; the onboarding row names the
  parent instead.

- **A custodied frame whose delivery was cut short is never delivered here** (lab finding, 2026-09-16, GitLab
  job 4494; work item knit/knit-next#56). `InboundPipeline.onDeliver` custodies before it dispatches, on purpose (a decrypt failure still
  leaves the frame carried, the copy is durable before the flood), and `runCatching { dispatchByType }`
  swallows the cancellation too — so a session scope cancelled, or a process killed, between the custody
  insert and the message insert leaves the frame in *our own* store with no row: every peer's digest reads
  us as holding it, nobody re-serves it, and our custody never replays to our own delivery path. The lab hit
  it in `RestartLabTest.aRecipientThatRestartsWithAFrameParkedForAKeyRecoversIt` (a re-hold applied late let
  the served key through, and the restart landed mid-replay: `handler error on chat …: Job was cancelled`,
  then `carol: []` with the post in her custody). The fixture now holds through the release; the mesh gap
  stands. `replayCustodiedGroupFrames` / `replayCustodiedSeedDms` already do the recovery for group frames and
  seed DMs at heal and startup; the fix direction is the same startup sweep over every custodied chat frame
  addressed to this node or to the room that has no message row — deciding first what a *deleted* message's
  absent row should mean to it (a tombstone, or dropping the custody row on delete), since today the sweep
  would resurrect it.

- **Onboarding's follow-ons** (ADR 2026-09.nzpr, 2026-09-13 — three pages, the radio grants as the only gate,
  notifications and battery as optional rows, `onboardingSeen` for the returning phone). Named and left: a
  **Notifications row in Settings** beside `BatteryOptimizationRow` (hidden below 33, "Open settings" once
  Android stops asking) — the one gap the change opens, since a grant declined at onboarding has no in-app
  path back; a **photo step** (the crop dialog stays on the Profile screen on purpose); **per-plane degrade**
  when only one radio's grant is held (would need every `android.bluetooth.*` / `android.net.wifi.aware.*`
  call guarded against `SecurityException`); and swapping `KnitApp`'s inline nav transition for
  `KnitMotion.enterStep`/`exitStep`, the same recipe. **Still owed:** the emulator trial (deny twice → Open
  settings → grant → Start; `pm revoke` → relaunch on the permissions page; a cold contact link across the
  gate), the ATF pass over the three pages, and re-capturing the three onboarding shots (`onboarding`, `onboarding-name`, `onboarding-permissions` — website material since the 2026-09-14 listing swap; done 2026-09-14).

- **The Your mesh screen's follow-ons** (ADR 2026-09.2v2t, 2026-09-13 — the screen ships with four numbers:
  nearby now, carrying now, passed along / handed straight to all time, people met). Named and left: **streaks
  or per-week views** (two additive longs cannot answer "this week"; it needs daily buckets); a **"your phone
  carried N messages today" notification nudge** (a `Notifier` cue off the same ledger, with a cooldown like the
  open-to-chat cue's); a **home-screen widget**; **crediting `fastSend` / LoRa hand-offs** once those paths
  report whether anything left the radio (today they return `Unit`, so they are uncounted rather than
  over-claimed); **"met this week"** off `MetPeerEntity.lastMetAt` (the column is there; the collector
  conflates, so it is an eviction key today, not a last-seen surface); and **aligning the chat-list header's
  "Connected to N mesh nodes"** (`chat_connection_count`) with the screen's "people nearby" — one string, but
  `ChatScreen` reads it too and the KDoc and tests name it, so it was left out of the change on purpose.
  **Still owed:** the device trial (a third phone as carrier between P9 and P7, the carrier's numbers moving
  within a minute, a force-stop and relaunch keeping them — the same walk is pinned in the lab by
  `ContributionLabTest`, restart included) and the ATF pass over the screen.

- **"Search in this chat"** (app-wide search shipped 2026-09-10, ADR 2026-09.wnh6 + 2026-09.wdfz) — a
  scoped search from a thread's overflow menu, pre-filtered to that conversation. The data side is already
  there (`MessageRepository.search` takes an allow-list; pass one id). What is not: the hand-back. A hit
  must scroll the thread that is *already open*, so the search entry has to return the message id to its
  parent (`previousBackStackEntry.savedStateHandle` + `popBackStack`) and `ChatScreen` has to take it from
  the saved state as well as from the route's `messageId` argument — never `navigate(chat(id, messageId))`
  from there, which stacks a second entry for the same thread and two `ChatViewModel`s race the one-shot
  draft restore. The overflow menu is also hidden on rooms, so the entry point needs a room variant.
  **Still owed on the shipped feature:** the device trial (a hit beyond the first 60 rows, the migration's
  `'rebuild'` timed on real history, Back returning to the results) and the ATF pass over the field.

- **Direct file transfer's follow-ons** (ADR 2026-09.wtmz, `context/direct-transfer.md`). Shipped as a
  DM-only overflow item; four things were named and left. **The APK entry point** is one call away —
  `TransferManager.offer(peerId, prepareKnitApk(context).toString())`, since `ui/invite/ShareApk.kt` already
  stages the installable APK — and is held back only because handing someone an APK deserves its own
  thinking about what the receiving side says. **Receiver-side content screening**: nothing looks inside the
  file, which the consent sheet states plainly rather than papering over; the moderation pipeline is built
  for 8 MiB attachments in the blob store, not for a gigabyte in Downloads. **A 2.4 GHz re-host** if a
  screen-off client's throughput ever collapses (it did not — screen-off costs about 3%). **The API 30-32
  background `createGroup` gap**, surfaced as `TransferRefusal.Background` and never seen run: the lab has
  no device below 33. Also unbuilt: a progress or completion notification, which needs a seam out of the
  deliberately-pure `TransferManager`, and a chat-list preview for a *pending* offer, which
  `ChatListViewModel` declines by the same rule that keeps notices out (ADR 2026-09.7uqe decision 4 took the
  narrow exception it needed and no more).

- **Location sharing beyond the one-shot pin** (ADR 2026-09.tss4 — a `geo:` line in the body, read only
  between the pin tap and the send). Three follow-ons were named and left: **live location** (a stream of
  positions with a stop control — every one is a message today, so it needs a rule for superseding the last
  pin and a bound on custody), a receiver-side **"Guide me"** distance-and-bearing readout (it needs the
  *receiver's* position on demand, so it must go through the same `rememberLocationGate` + consent and be
  the second — and last — collector of `LocationSource.fixes`, or it breaks the promise the disclosure
  makes), and **the Meshtastic public room** (excluded: the token has to come off the room's hard 166-byte
  counter — `MaxUtf8Bytes`, `PostLengthCounter`, `PublicPostPolicy.fit` — before the pin can be offered
  there). A static map preview was never on the table: the phone drawing the card usually has no Internet.

- **The LoRa (Meshtastic-over-BLE) plane was hidden in shipped builds, INTRODUCED at 2.5.0**
  (2026-08-24, ADR 038; flipped 2026-09-06, ADR 2026-09.6gtm) — `BuildConfig.LORA_PLANE` is now true
  everywhere and `-PloraPlane=false` rebuilds the dark artifact 2.3.0–2.4.x shipped. It gates the LoRa
  child in `CompositeMeshTransport`, the `lora` settings route + Profile row, and
  `SettingsStore.loraEnabled`. The code is **not** stripped (R8 prunes the `if (LORA_PLANE)` branches).
  Visible is not enabled: `loraEnabled` still defaults false, and the plane is inert until the user pairs
  a Meshtastic board and sets it up. **The two device trials that gated the flip — ADR 044's four-device
  two-pocket bridge run and ADR 054's airtime three-phone run — were not run, and now ride as shipped
  risk** (ADR 2026-09.6gtm records what bounds it: airtime budgets, `LOCAL_ONLY` rebroadcast, `hop_limit`
  0, and a plane that is additive to custody).
  MVP shipped: `mesh/lora/` (pure, JVM-tested end-to-end over a fake board/air) + `mesh/bluetooth/meshtastic/`
  (the GATT client, device-verified only). Carries the Nearby-room broadcast subset (chat, reaction, ✓✓ tick,
  profile) and, since ADR 039 (2026-08-24), **sealed 1:1 DMs** — the whole DM form, receipts/reactions/ctl
  included — via `MeshTransport.longRangeFanout`, with class-aware queue shedding, a 15-min freshness gate,
  a bounded re-offer of carried DMs to a peer first heard, and a default-on `loraDmEnabled` switch (the
  metadata-exposure control). Group chat/meta, typing and files stay refused. **Still owed:** the
  **two-phone device trial** (pair both boards, verify a Nearby post + ✓✓ + reaction cross, then a DM + its
  ✓✓ + a reply, and a DM sent while the far board was off landing via the re-offer once it returns — all
  with the phones out of BLE/NAN range) — the GATT layer has no host test. **Knit-provisioned channel
  SHIPPED** (2026-08-24): "Set up Knit channel" (or `…debug.LORAPROV`) writes the derived `KnitChannel` as a
  secondary channel over the Meshtastic admin API — the user no longer hand-configures the boards;
  region/modem-preset still set once at flash. **Board setup REWORKED** (2026-08-26, ADR 045): a single
  "Set up this board for Knit" writes the Knit channel into a free secondary slot, stretches the board's
  node-info / position / telemetry broadcasts and sets `rebroadcast_mode = LOCAL_ONLY`, all as one
  read-modify-write admin transaction, with a Restore that puts the board's own values back and switches the
  plane off (`BoardQuiet`, `spliceVarintFields`, `SettingsStore.loraBoardSetup`, `…debug.LORAPROV [--es mode
  restore]`). There is deliberately **no lighter mode and no hand-set channel index**: a board is set up for
  Knit or it is a stock Meshtastic node. The board's **primary is never touched**, which keeps it on the
  public frequency where stock nodes repeat Knit's packets for free — the reason the frequency-move design
  was reverted before shipping. **Still owed:** the on-hardware trial in `context/lora-bridge.md` — the
  frequency must be *unchanged*, the battery row must survive the telemetry stretch, and a stock node between
  two boards should extend the range.
  **The Meshtastic room is a local mirror of the paired board's slot 0** (2026-09-05, ADR 2026-09.26q3,
  superseding 2026-09.cf7a and 2026-09.7r4d): whatever the user set slot 0 to — preset, name or key — is read
  into `Conversations.MESHTASTIC` as rows on this phone and this phone only, and a post typed there leaves
  through this phone's own board on channel 0 as `TEXT_MESSAGE_APP` carrying the words alone — no author
  name (2026-09-05, ADR 2026-09.9469, which withdrew ADR 049's single exception once the board became the
  identity), still behind the first-use consent sheet. Nothing crosses Knit's mesh: no frame,
  no custody, no fan-out, no gateway election on either direction; the `meshpost` type is withdrawn and
  burned. A heard post is lined up with a contact through the bound board's node number the profile now
  carries (`ProfileContent.loraNode` → `peers.loraNode`), resolved once at ingest and frozen on the row
  (`messages.originPeerId`) so a board changing hands never re-attributes history; still an unverified match,
  rendered as such. Metered by `AirBucket.PUBLIC` (15 % of the window) plus a 30 s per-board floor, and every
  refusal is shown at the composer with the draft kept. **Still owed:** the on-hardware trial in
  `context/lora-bridge.md` (two boards, one board-less phone: the board-less phone sees nothing, a contact's
  post wears their name off the node number alone, a second post inside 30 s is refused, the title follows a
  preset change). **A DM to a set-up board is auto-answered once** (2026-09-19, ADR 2026-09.4n5p): the
  stranger's text used to die as `NOT_BROADCAST` behind a delivered tick; now `DmAutoReplyPolicy` answers it
  with one fixed unicast on index 0 (PKI to the sender's key) — once per sender per day, once per 30 s for
  anybody, out of the room's `PUBLIC` share, only from a board that ran the setup and is not on a dedicated
  slot; the per-sender memory rides `LoraPlaneSnapshot.autoReplied`. **Still owed:** a device trial with a
  stock third node DMing a Knit board (the reply arrives PKI-encrypted and once; `autoReplySent` moves;
  a second DM is `REPLIED_RECENTLY`), and
  **signature-backed confidence SHIPPED** (2026-09-05, ADR 2026-09.ggq4): the "no verdict" premise was wrong
  — both lab tags hand the phone `MeshPacket.xeddsa_signed` and the signature itself — so the profile now
  carries the board's key (`ProfileContent.loraKey`, ninth additive profile field, only while the board
  signs), the phone verifies a heard post against it (`mesh/crypto/XeddsaVerify`, the firmware's own
  `from ‖ id ‖ portnum ‖ payload`) and freezes one of four verdicts on the row (`messages.originSigned`, DB
  v12); a verified match wears a shield and opens the profile directly, a mismatch is drawn as a stranger,
  and on a signing board the composer caps a post at the 166-byte text cliff so every post leaves signed.
  Verified on the two lab phones the same day (`context/lora-bridge.md`): a post each way arrived
  `signed=true boardVerified=true` and verified, a 166-byte post included. **Still owed:** the
  `packet_signature_policy` decision — `BALANCED` is safe for the padded plane, `STRICT` loses it, and the
  recommendation is now `BALANCED`; seeding our own board with a contact's key (`AdminMessage.add_contact`,
  66) so `BALANCED`/`STRICT` receivers keep the contact's posts and a stale NodeDB key cannot blackhole
  them; the `heartbeat{nonce=1}` NodeInfo ping after a bind, so peers learn a fresh board's key at once; a
  warning for the downgrade shape — an unsigned post under 166 B from a contact whose profile advertises a
  key; and a mismatch / board-only verdict seen on hardware, which needs a third signer on the mesh.
  **The room is now a switch, default on** (2026-09-06, ADR 2026-09.x52a): `SettingsStore.loraRoomEnabled`
  on the LoRa settings screen beside the DM and bridge switches. Off, the transport drops a slot-0 chat
  packet before `onPrimaryPacket`, so nothing is judged, verified, moderated, stored or notified
  (`meshPostRefusedByReason.ROOM_OFF` climbs, `meshPostHeard` does not); the row leaves the chat list
  *including* its history, a post cannot leave the device, and the shade is cleared on the off edge. Knit's
  own frames on the bound slot are untouched. **Still owed:** the device leg — switch the room off on one
  lab phone with a `LongFast` primary in earshot and watch the two counters diverge.
  **The setup also marks the board unmonitored** (2026-09-01, ADR 2026-09.emd7): `User.is_unmessagable`
  rides the same `set_owner` as the rename, so other people's clients stop offering a board whose inbound
  path keeps only `PRIVATE_APP` as a message target; Restore clears it, and firmware older than 2.6.9 is
  left alone because it drops the field and would leave the setup looking permanently unfinished.
  **Still owed:** the device half — on a 2.6.9+ board, confirm the Meshtastic app shows the node as
  unmonitored after a setup and messagable again after a Restore, and that a pre-2.6.9 board is never
  prompted to finish a setup it has already finished.
  **A dedicated-frequency setup EXISTS but is DEBUG-ONLY** (2026-08-31, ADR 067): `ProvisionMode.SetupDedicated`
  pins `lora.channel_num` to a slot derived from the Knit channel name, and `LoraAirtime` then drops the 10 %
  politeness ceiling (the region's legal duty cycle still stands), for the isolated-fleet case where there is
  no Meshtastic neighbourhood to borrow relaying from. Refused outside US/ANZ, whose bands are the only ones
  `LoraSlot` states exactly. **Still owed before it could ever ship to release:** an on-hardware two-board
  trial on a dedicated slot (nothing has been run on real radios yet), a story for a half-converted fleet —
  a board left on the shared slot is silently unreachable and looks identical to being out of range — and a
  decision on whether the 0.5 safety factor is still right once no third party is repeating us.
  Still deferred: a **user-set/shared private PSK** (the shipped
  channel is a public rendezvous; with DMs aboard it is also what would hide their metadata — needs
  out-of-band PSK sharing, QR/URL — and, since the name feeds the slot hash, a private deployment would also
  land on its own frequency), a **periodic self-profile beacon** (a peer that only listens never
  triggers a beacon exchange or a re-offer), **Meshtastic unicast + `want_ack`** for DMs (needs a
  nodeNum↔nodeId map and a Routing `NONE`-is-success branch), **re-offer beyond the heard peer** (a
  board-less recipient behind another board-holder — the "true DM routing" deferral), an **in-app scan + bond flow**
  (`MeshtasticScanner`/`MeshtasticBonder` are written but unwired — device-only verifiable, and the scan must
  go through `BleConnectArbiter`; the picker filters bonded devices instead, ADR 040), and a **per-message
  `loraTooBig` marker** (no persisted evidence; ADR 040's composer hint covers the sending side). **The plane's
  UI SHIPPED** (2026-08-25, ADR 040): `DeliveryPlane.LoRa` + bubble glyph, the header glyph, the board-only
  picker with a channel verdict, the LoRa-only DM notice and the long-message composer hint; the board's
  battery in the status + Profile rows followed (ADR 041). See
  `context/lora-bridge.md`. **Bridging between mesh pockets SHIPPED** (2026-08-25, ADR 044): a `LoraCtl`
  gossip OFFER (tag `0x10`, ≤ 48 id prefixes, one packet), a gateway election off `foreignReachable` that
  closes the **multi-board-per-clique** deferral above, an airtime governor reading the board's region and
  modem preset, and digest-driven backfill of what a far gateway's offer shows it lacks — behind
  `SettingsStore.loraBridgeEnabled` (default on). Live traffic already crossed before this and was not
  rebuilt. **Still owed:** the **four-device two-pocket trial** in `context/lora-bridge.md`. **The backfill
  no longer suppresses itself** (2026-09-02, ADR 2026-09.y8pu): `serveOne` consulted the same 10-min `sigSeen`
  set the fan-out spends, so a frame fanned out of range was skipped by the one path that could repair it —
  field-observed as a Nearby-room post that never arrived after the boards came back into range. Still owed:
  the two-board confirmation on hardware. **Airtime shaping
  SHIPPED** (2026-08-27, ADR 054): the recipient gate (a DM-form frame to a linked peer or to self never rides
  the board), a 15-min budget window at the same 5 %, a `TICK` class that sheds first and never spends a
  window's tail, coalesced DM receipts (`DmAckCoalescer`, ≤ 45 s hold, one tick per burst) piggybacked on a
  reply behind `CAP_INLINE_ACK`, and the saturated-chat notice. **Still owed:** its three-phone trial
  (`context/lora-bridge.md`). **The room ✓✓ over LoRa and the relay SHIPPED** (2026-09-13, ADR
  2026-09.y5f3, from a field day on which two room posts crossed the board in seconds and their ticks
  waited 48 min for Bluetooth): aa27's ride hold has a 60 s deadline, after which the tick goes to a spool
  the author was recently seen on (a signed `relay = false` frame pushed direct, spec §9.4 C-9.4-3) or over
  LoRa's targeted path; the instant DM receipt and the escalated group tick are rides too; every DM-form
  frame to a spool-present peer stays off the board (`coveredByInternet`, 15 min). Pinned by
  `RoomTickPlanesLabTest` over the real LoRa and spool planes in the lab. **Still owed:** the field re-run —
  a LoRa-only author and a spool-only author each get a room ✓✓ within ~1 min; `lora tx send:` appears on
  the acker; `receiptsSpooled` and `loraSkippedInternet` move in `…debug.LORA`. The residual is an author
  reachable only through a relay with no board and no spool. **Meshtastic 2.8 caught up with** (2026-08-31): `LoraAirtime` now charges for
  the 66-byte XEdDSA signature 2.8 bolts onto any broadcast under 165 B (gated on the board's firmware),
  `ModemPreset` names codes 9–16 (`LongTurbo` is 2.8's new US default and is deaf to `LongFast`), `LoraRegion`
  names the duty-limited regions that were collapsing into `OTHER`'s 100 % (`EU_866` 2.5 %, `EU_N_868` 10 %,
  `TH` 10 %), and the LoRa screen warns on a preset mismatch beside the renamed-primary warning. **Bench-verified on a
  Heltec V4 / 2.8.0.7239fe8 (2026-08-31):** a wiped US board really does come up `LONG_TURBO`; the signature
  cliff is at **exactly 165 B** and `LoraAirtime` now matches the firmware's own `Packet TX:` figure to
  **≤ 1 ms** across 140–231 B; the payload cap is **still 231**; and ADR 045's provisioning transaction is
  intact, with `Config.lora` byte-identical before and after (its "never writes the radio" promise, on
  hardware). **The cliff is now a saving rather than only a tax** (2026-09-01, ADR 2026-09.mhs5): a frame's last
  packet is grown to 166 B so the board sends it unsigned — a few bytes of pad instead of 66 of signature,
  ~20 % off both the one-packet tick and the room post. Legal only on a **deflated** body, and since the
  frames with most to gain *store* (the ADR 060 transcoder already took the compressible keys out), a stored
  one-packet frame is re-deflated first for a measured +5 B whenever `LoraAirtime` prices the result cheaper.
  Not covered: fragmented stored frames, and `LoraCtl` offers (whose byte-identity the gossip suppression
  depends on). **Device-verified the same day** on the Heltec / 2.8.0.7239fe8, in two halves: the codec pads a
  real room post (`lora pad fanout:chat +46B`, `loraPadded` 0 → 1, no NAK), and a 166-byte payload leaves the
  board **unsigned** — read off a *second* board over the air as `Lora RX … encrypted len=190` / `Packet RX:
  1262ms`, against `len=255` / `1655ms` at 165 B. (That `encrypted len` line is a better instrument than
  `Packet TX:`: `len = payload + 24` unsigned, +66 signed, so it reads the signature off directly.) **Still owed:** a decision on `packet_signature_policy` (defaults to `COMPATIBLE`, so nothing is
  broken — but a user who picks `STRICT` now loses *every* frame, not just those over the cliff);
  `BoardName.stock`, which
  computes the fallback name from the node number while 2.8's default name is still MAC-derived; a second
  board for the receive half (partly answered — the USB board turned out to be a separate node that does hear
  the phone's board, though nothing on this mesh runs anything but the `COMPATIBLE` signature policy); and
  **one packet observed being both padded and unsigned** — the two halves above were measured separately
  because ADR 044's pocket election put the phone `PASSIVE` before they could be caught together. Full write-up in the private overlay. Still deferred
  here: an **IBLT/Bloom offer body** (48 prefixes is a window — the upgrade if a busy pocket's oldest frames
  start falling off it), **acknowledged backfill** (a served frame lost to the air waits for the next round),
  and **faster passive-to-active takeover** when an active gateway's phone dies without leaving
  `foreignReachable` (the 45-min `STALE_MS` is the whole blind spot today).

- ~~**The spool plane is hidden in shipped builds**~~ (2026-08-22, ADR 031) — **introduced at 2.4.0**
  (2026-08-31, ADR 064): `BuildConfig.INTERNET_PLANE` now defaults true in release and staging too, so a
  shipped build seeds the default relay, shows the Profile row and the `relays` route, and lets
  `SettingsStore.spoolEnabled` mean what the user stored. `-PinternetPlane=false` puts a build back in
  the dark state. The user-facing default did **not** move: the plane is visible and switched **off**,
  behind the consent sheet. The three device trials below (group two-island, attachment deferral,
  contact-card intro) were **not** complete at the flip — see ADR 064 for what was, and for the residual
  risk that carries.

- **The commons is built but hidden in shipped builds** (2026-09-12, ADR 2026-09.wx8e) —
  `BuildConfig.COMMONS` is true in debug and false in release, `-Pcommons=true` lights it for a maintainer
  build. It gates the `CommonsStore` the DI hands `MeshManager` and `InternetRelayViewModel` (null while
  dark: no room in the scope table, no subscription, no post, no member sweep, no Join / Leave line on the
  relay row) and the room's notification channel. The code is **not** stripped (R8 prunes the
  `if (COMMONS)` branches), and DB v13's three tables ship empty. **To introduce it:** flip the release
  default in `app/build.gradle.kts`, the way ADR 064 / ADR 2026-09.6gtm did for the two planes, after the
  follow-ons the ADR lists (attachments, reactions, a members list, ~~deep-linked invites~~ — **done
  2026-09-14**, ADR 2026-09.tmbq / `docs/RELAY_INVITE.md`: one `getknit.app/r` link carries the relay, its
  token and the room secret, applied on one sheet; device trial still owed) are decided.
- **The spool plane beyond the spec** — everything that makes the protocol run, in order: ~~the
  `knit-spool` reference daemon + conformance suite~~ (**done 2026-08-16** in the `knit-spool`
  repo — full v1 daemon with SQLite persistence, rate limits, watermark, ops surface, plus the
  22-check TAP conformance CLI; its implementation pass fed eight semantic clarifications back
  into `docs/SPOOL_PROTOCOL.md` §6.2/§6.4/§7.1/§7.2/§12, no wire or vector change — ADR 019
  amendment); ~~the client `ScopeSync` plane~~ (**MVP done 2026-08-16**, `mesh/spool/` — DM scopes
  only, off by default, OkHttp behind the `SpoolLink` seam, the §9.1 heal loop, §9.3 quarantine,
  §9.4 bridge into `handleInbound`, metrics + Diagnostics rows + the `…debug.SPOOL` bridge action;
  ADR 019's M3 amendment records the four shape decisions). **What the client plane still
  owes**, roughly in order:
  - **the scope-config ctl** — `CTL_SCOPE_CONFIG = 7` / `MessageContent.sc` / `ScopeConfigPayload`
    with LWW on `(version, issuer)`. The one *wire* change the plane needs, so it lands additively
    per `docs/WIRE_COMPAT.md` with golden vectors and a precedent entry. Until it ships, the spool
    list is a device setting and bounds are §12 constants in `ScopeRegistry`.
  - ~~a spool-list editor~~ (**done 2026-08-16** — `ui/relay/InternetRelayScreen`, route `relays`,
    reached from a Profile summary row. **The switch is un-gated**: `BuildConfig.DEBUG` is gone from
    `ProfileScreen`, because the hard prerequisite is now met — a release user can edit or remove the
    seeded default. Ships with it: a one-time consent sheet (`SettingsStore.spoolConsented` /
    `acceptSpoolConsent`, which records consent and enables in one write), per-relay health rows off
    `SpoolStatus`, and the shared `SpoolUrl` validator so the editor refuses at entry exactly what
    `OkHttpSpoolDialer` refuses at dial time. ADR 019's M6 amendment records the UX rules.)
  - ~~a switch per relay~~ (**done 2026-08-30** — ADR 063. `SettingsStore.spool_urls_disabled` holds
    the parked subset, and the composed `activeSpoolUrls` is the single seam every consumer reads,
    the way `spoolEnabled` already gated the plane. No wire, DB or protocol change.)
  - ~~a validated-Internet `ConnectivityManager` seam~~ (**shipped 2026-09-03** as `net/InternetGate`
    for link previews, ADR 2026-09.n752 — `ACCESS_NETWORK_STATE` is declared and `rules/mesh.md` names
    the second `ConnectivityManager` user; **`ScopeSync` does not consume it yet** and still reconnects
    on backoff, so wiring the plane onto the gate is the remaining half); the Tor SOCKS toggle (the
    preview fetcher's `OkHttpPreviewFetcher.bound` is the second place a proxy would go);
    per-**conversation** opt-out (deliberately **not** built — which
    conversations ride the plane is all-or-nothing by product decision, 2026-08-16, and the consent
    sheet says so. Note the axis: ADR 063 ships a per-**relay** switch, which chooses *which third
    party* carries, not *which conversations* do — that decision is untouched).
  ~~Then: group scopes~~ (**done 2026-08-16** — the `GroupKeyPayload.gr` wire field, `group_roots`
  at DB v3, `GroupRootPolicy`/`GroupRootStore`, group scopes in `ScopeRegistry`/`ScopeFrames`, and
  the mint/gossip/adopt/re-mint wiring in `MeshManager`/`InboundPipeline`. The spec's §3.2 was
  amended in the same pass: **any member** may mint version 1, damped by preferred-minter-plus-grace
  rather than restricted to the creator — ADR 019's M4 amendment records why, plus the two mandatory
  adoption bounds and the never-rate-limit-adoption rule). Still owed on the group half: nothing
  structural, but it has **not been exercised on devices** — the lab bridge trial (two islands, one
  real spool, a departure rotating the scope) is the outstanding verification.
  ~~Then: sealed attachments over spools~~ (**done 2026-08-16** — spec §4.5/§6.5/§7.3/§9.5, the
  `ScopeCrypto` chunk seal + keyed `aid`, `mesh/spool/ScopeAttachments`, five records, and the
  attachment pass in `ScopeSync`; `knit-spool` gained both stores, the server handlers and four
  conformance checks. **No mesh wire change, no capability bit, no DB migration** — the cleartext
  `ChatContent.attachmentHash` of the DB v19 precedent is the whole reference a
  fetcher needs — the mime rode alongside it until ADR 035 withdrew it, and the fetcher now resolves the
  type from its own decrypted row. ADR 019's M5 amendment records the five shape decisions). Still owed on the
  attachment half: **persisted partial downloads** (they are in memory today, so a process death
  mid-transfer refetches — the upload half already resumes off the spool's bitmap), and the same
  two-island device trial the group half is waiting on. **Lab (2026-09-14):** `InternetPlaneLabTest` pins the
  two-island group with a departure (the scope *rotation* waits on the six-hour mint grace, a clock-tier
  scenario), ADR 032's receive-only DM scope, the §9.3 quarantine, a relay dropping every socket and ADR 020
  over the relay; a group *founded* while a member is relay-only never delivered its roster to them (root
  adoption needs the row, the roster rides the scope the root derives) — **fixed 2026-09-18** (work item
  #47, ADR 2026-09.mjaj): the seed carries the founding roster (`GroupKeyPayload.group`, spec C-3.2-16),
  the member pins the group from it through `reconcileGroup`, and the scenario is un-ignored and is the
  acceptance test. **Device-verified the same day** (Pixel 9 → Moto G with Bluetooth off over the public
  relay, Pixel 3 by radio): pinned from the root gossip, message across in 26 s, receipt back over the relay.

- **Attachment uploads are deferred while the radios carry them, SHIPPED 2026-08-17** (ADR 021,
  `mesh/spool/AttachmentDeferPolicy`, spec §9.5's MAY + §10): an attachment a short-range radio already
  carried between this DM's two members waits while that peer is still on `MeshTransport.reachable`, so
  a photo that already crossed a radio link is not copied to a relay as well. Deliberately **attachments
  only** — gating frames would make the scope digest a function of local mesh state and it would never
  converge again — and deliberately a **delay, not a veto**: it re-opens on the sighting expiring and
  ends 2 h before the frame leaves custody. Groups never defer (the sealed group tick flips on the first
  member's receipt). Counted as `spoolAttachDeferred` in Diagnostics and the `SPOOL` bridge.
  **The lab ran the owed trial on 2026-09-14 and it failed, twice over; both halves fixed 2026-09-15**
  (issue #46, ADR 2026-09.p7j8). The *sender* was judged in the round `onCustodyChanged` woke — the send
  itself — when the recipient's ack could not yet exist, so the chunk went up and the counter stayed at
  zero; `ACK_GRACE_MS` (60 s, on the frame's own age) now separates "not yet" from "never". The
  *recipient* then re-uploaded the photo BLE had just handed it, because the evidence was sender-shaped
  only; `attachmentCarriedByRadio` now reads the one fact from either end. `InternetPlaneLabTest`'s
  deferral scenario is un-ignored and is the acceptance test. Still owed: the same two-island trial on
  hardware — send a photo co-located (expect the deferred counter climbing and no `aput`), separate the
  devices, expect the upload within one 60 s heal round of the sighting lapsing.

- **Sealed profile updates SHIPPED 2026-08-16** (ADR 020, was never a roadmap item — the gap surfaced in
  field testing after M5): `CTL_PROFILE = 8` carries name/status/avatar to established contacts inside v2
  chat, so profile changes now cross the Internet plane and stay off the cleartext plane for
  ratchet-capable peers. Avatars ride the carrying frame's cleartext `attachmentHash` (the DB v19
  precedent), and group photos needed no wire change since `groupupdate` was already scope-carried. The
  cleartext `profile` frame keeps first contact permanently — it is self-certifying and cannot be
  encrypted — so ADR 018's "last cleartext flooded metadata" goal is advanced, not finished.

- **Contacts at a distance SHIPPED 2026-08-25** (ADR 042, `docs/CONTACT_CARD.md`): a signed contact
  link (share/copy on the Verify screen; import by tapping it, sharing it to Knit, or pasting it on the
  Add-by-link screen), the `CTL_PROFILE` intro driven by `IntroSync`, and the identity-derived **pair
  scope** (spec §3.5) so a pair that has only exchanged cards meets at a spool before a session exists.
  **Still owed:** the two-device trial (both import, out of radio range, one shared spool — expect
  `introsSent ≥ 1` both sides within ~2 heal rounds, `confirmed: true` in `…debug.RATCHET`, the same DM
  scope id in `…debug.SPOOL`, the pair scope gone ≤ 48 h later; then the LoRa variant — everything but the
  48 h expiry is pinned in the lab by `InternetPlaneLabTest.twoCardHoldersMeetAtThePairScopeWithNoRadio`), the
  `getknit.app` assetlinks + `/c` landing page (out of repo — until then Android 12+ opens the https link
  in the browser; `knit://` and share-to-Knit work regardless). **Deferred, by design:** the **one-sided
  invite** (a *profile-only* token-derived rendezvous plus a contact-request inbox — needs per-token
  caps, revoke, expiry, and the "other link holders can see who requested" caveat); a **prekey in the
  card** gated on `iat < 7 d` (seal at import, reach a LoRa listen-only peer; a stale prekey wedges
  silently at `EPOCH_GONE`); **node-id-only import** over the radios via `KeyExchange.want`; **session
  recovery over the pair scope** for existing contacts (needs a probing strategy — no `unsub` record,
  `maxScopes` pressure); a chat-thread intro notice (the profile status line covers it; the pair scope
  already reads as relay-covered).

- **Same-name disambiguation follow-ups** (ADR 058 shipped the `Name (Alias)` label, 2026-08-28; ADR
  2026-09.wuqj made the alias a 24-bit digest token that grows when matched, 2026-09-03): an
  **impersonation warning** when a non-contact adopts a contact's or your own name (the label makes it
  visible; nothing yet says so); **tinting the tokens past the first** in `PeerNameText`, so a label that
  grew because an alias was matched looks different from one that merely carries its alias; **last-seen
  pruning** of the collision universe (a stranger seen once can suffix a contact indefinitely — needs a
  `lastSeen` column); and resolving `ReplyRef.authorId` through the directory instead of rendering the
  sender's snapshot. The node-id-derived avatar hue shipped as ADR 2026-09.j8c7 (2026-09-10), including
  the Material You harmonization.

- **Audio and file moderation** — voice notes (ADR 034) and arbitrary files (ADR 2026-09.qq2r) ship
  **unscreened**: no on-device model classifies speech, nothing at all classifies a PDF or an archive, and
  the app has no cloud option, so `MODERATION_NONE` is the honest verdict and the screening hooks skip both
  by MIME. Mitigated rather than solved: neither is offered in the Nearby room (the one surface
  that floods unencrypted to strangers), app packages are refused on send and archives/executables are
  confirmed before saving, and block-sender plus the ADR 009 request gate are the remedies. The MIME-keyed
  skip itself is no longer a way past the classifier — `ingestFile` sniffs the bytes' own signature and
  routes a real image back through the image pipeline, and the receive side screens every keyed
  attachment's decrypted plaintext MIME-blind.
  If a small on-device speech classifier ever becomes practical, the hook point already exists —
  `InboundPipeline.onObtained` decrypts a landed attachment and is where the waveform derivation runs, so a
  verdict would cache under the same content hash the image path uses and the bubble's tap-to-reveal
  collapse would need no new UI. Gap recorded in `docs/CONTENT_MODERATION.md` §7.

- **Voice notes and files in the Nearby room** — deliberately not built, for the reason above. Reversing it
  is one flag each (`MessageInput`'s `voiceEnabled` / `fileEnabled`), and neither should be reversed without
  an answer to "what screens it". The room is the *only* thing that hides the file item: gating its
  visibility on the recipient's `CAP_FILES` was tried and reverted, because the bit arrives with the peer's
  next profile frame and until then the feature was indistinguishable from unbuilt (ADR 2026-09.qq2r).
  Capability belongs on the send, where it can explain itself. Two other things now rest on the room carrying only images:
  `MeshBlobStore.saveIncoming`'s screening skip, and `docs/NEXT_WIRE_BREAK.md`'s first parked item.

- **File attachment previews** — files ship with a typed icon, a name and a size, no thumbnail
  (ADR 2026-09.qq2r). The two cheap wins were left on the table deliberately: **video/audio poster frames**
  need only `MediaMetadataRetriever` over the existing `ByteArrayMediaSource` (already in the tree from the
  voice-note work — no disk, no new dependency), while a **PDF first page** needs `PdfRenderer`, which
  demands a *seekable* file descriptor and so needs `StorageManager.openProxyFileDescriptor` to stay off
  disk under ADR 029. `FileAttachmentBubble` is the one place to change; the row shape already reserves the
  slot.

- **Opening a received file in another app** — not built. It needs a content provider whose `openFile`
  serves decrypted bytes (the same proxy-descriptor machinery a PDF preview wants), because ADR 029's
  invariant forbids the plaintext staging file the obvious `FileProvider` route would need. Saving through
  the storage picker is the only exit today. **Before building it:** the provider must never expose an
  install-capable grant for a package, or the platform's unknown-sources gate stops being the last word.

- **BLE promotion gate on A2DP audio** — the adaptive scan throttle now drops the **scan** to its floor
  while streaming (`ScanDemandPolicy` / the demand-gated `scanLoop`), but **connects** are still not gated
  on `contended` (it remains diagnostic-only for the connect path). **Note before building it:** since
  ADR 034, *playing a voice note* also trips `AudioManager.isMusicActive`, so `contended` now goes true for
  a few seconds of local speaker playback that contends for nothing. Harmless while the flag is
  instrumentation-only; gating connects on it as-is would stall the mesh every time someone listens to a
  message. The gate needs to distinguish a real A2DP route from any active stream.
- **BLE side channel: the release flag** — the carrier is BUILT (2026-09-16, ADR 2026-09.sjaa;
  knit/knit-next#13) and device-trialled on three lab phones (2026-09-17: every controller 1650-B extended
  advertising, 20/20 and 12/12 pages caught screen-on, 11/12 screen-off, the #13 case delivering the
  bystander's posts off a page while the sender's link copies lagged, no link slowdown from airing pages —
  numbers in the ADR). Still dark in a shipped artifact (`BuildConfig.BLE_SIDE_PLANE`, debug on / release off)
  until: one night's battery on a settled clique against the dark build, and a sighted-but-unlinked peer
  (this lab links everyone — needs a link budget saturated or a phone in connect backoff). Trial knobs
  deferred with it: a third slot (two saturate at one part per 6 s), a shorter dwell for screen-on rooms,
  2M secondary PHY, hold-until-echoed dwell, DM-form frames on a page (issue scope: DMs stay on L2CAP).
- **Frame compaction: what round 2 (ADR 060, the `0x05` transcoder) left** — round 1 (ADR 059, crypto v3)
  and round 2 (ADR 060: a schema-aware re-encoding of `signed` the receiver rebuilds byte-exact before
  verifying) both landed 2026-08-29; measured after: signed v3 ✓✓ tick **221 B, one packet at 228/231/255**,
  unsigned tick 157, sealed reaction 229 with 👍 (one at 231/255; 261 with the longest RGI emoji sequence and
  290 at the `TextLimits.REACTION` cap — two packets on every plane, never three), 40-char DM 244 (one NAN message, two LoRa
  packets), 100-char DM 304 (two — the structural floor, sig 64 + ids 48 + ek 32 + ct 124), profile 352
  (3 → 2 parts at 228), 12-ack tick 409 (3 → 2). Still owed: **(a) the LoRa gate — RETIRED as a release
  blocker 2026-09-06 (ADR 2026-09.6gtm)**, because the plane shipped `0x05`-only: a dark build never joined
  the plane, so every release that can hear a LoRa frame is 2.5.0 or newer and speaks the transcoded form,
  and there is no older population to fall back for. The gate's design — "every peer heard on the plane
  within the 45-min linger advertised `CAP_FRAME_TRANSCODE` through the profile frame it beacons here,
  newest-`sentAt`-wins, closed when no one is heard" (~20 lines in
  `LoraMeshTransport.onFramePacket`/`recomputeReachable`/`encodeOrNull`), or a capability byte on a new
  `LoraCtl` kind — is what a *future* tag change needs; residuals then: an unheard far-pocket old build on a
  rebroadcasting channel, a downgraded peer until its older profile arrives. Building it now would only
  re-open the budget below. (The second flag-day this gate briefly owned —
  the `meshpost` custodial type, ADR 2026-09.cf7a — was withdrawn before shipping by ADR 2026-09.26q3; the
  Meshtastic room no longer puts anything on Knit's mesh.) The gate also owns a budget: `LoraSizeHint`'s
  `DM_BODY_BYTES` (320) is honest only in the `0x05` form — a budget DM carrying its four inline acks is 596 B
  against the 681-B ceiling there, but **675–683 B untranscoded** — so anything that ever falls back to `0x03`
  for an ungated peer must drop that budget ~20 B or accept a `loraTooBig` on a tenth of the DMs the composer
  just promised would fit (`CoordinationPlaneSizeBudgetTest` pins both budget tests on the transcoded form for
  exactly that reason); **(b) the compact group form as v4** (derived nonce + labeled plaintext for `g`,
  roster-gated on every member's pinned capability; ~12 + 20–40 B a post,
  no packet-count change on its own — v3 is the DM form by executable rule, ADR 059 amendment); **(c)** seed
  `profileVersion` from the wall clock on first run, so a reinstalled peer's stale `CAP_CRYPTO_V3` /
  `CAP_RATCHET` pin is cleared by its next profile rather than by its edit count climbing back; **(d)** re-tune
  `INLINE_ACK_BYTES` (23) for the 17-B compact ack; **(e)** `docs/NEXT_WIRE_BREAK.md` item 8 — make the
  transcoder's layout the canonical signed form at the break, reclaiming what a re-encode cannot (the 7-B
  nonce from the stored form, the text ids inside sealed payloads, the millisecond clock). Rejected on
  measurement, don't retry: `ek` elision (ratchet advance rule 2 rekeys on every conversational turnaround),
  any further dictionary work (`DICT_V2` bought 16 B on the profile, zero margin), dropping the signature on
  *flooded* frames (custody and relays verify `senderId`), and a typed `@CborLabel` mirror for the transcoder
  (it could not pass unknown fields through, and `fastFanout` re-fans frames other builds originated).
- **Crypto hardening that is policy, not scheme** (from the 2026-08-29 pre-release review of v3, ADR 059
  amendment) — neither needs a version number, so neither rode v3: (1) mark a frame *seen* only after it
  verifies — today a forged frame carrying a real id shadows the genuine one for the SeenSet window (10 min,
  every frame type, pre-existing); (2) shorten the DM epoch cadence (`RatchetEngine.MAX_EPOCH_AGE_MS` = 24 h
  bounds post-compromise recovery to a day; `ek` already rides every frame, so a shorter cadence costs
  nothing on the wire — check the epoch-row/TTL budget first). Rejected outright for the mesh: post-quantum
  KEM material (ML-KEM-768 keys/ciphertexts are 1.1–1.2 kB), key-committing AEAD, header encryption, length
  padding (label 12 exists if ever wanted), tag truncation.
- **True DM routing** — DMs still flood; only the addressed recipient delivers/acks. Store-and-forward now
  *carries* undelivered DMs (`context/store-and-forward.md`), but there is still no routing table.
- **Group key-gap retransmit (v1-fallback residual only)** — the group ratchet's outbox +
  key-request loop subsumed this for ratchet-capable groups (docs/GROUP_FORWARD_SECRECY.md §7); the
  original gap — a member whose key arrives later never gets a re-seal — persists only for groups
  still pinned at v1 by a pre-ratchet member, and shrinks as capability floods.
- **E2E hardening (what remains)** — encrypting the broadcast room (its fate is a deliberate
  separate decision — an Internet-wide plaintext room is a different product question), and the
  **attachment MIME on the blob transfer**. The flooded-frame half shipped 2026-08-23 (ADR 035: a sealed
  frame names the ciphertext hash and nothing else, so a relay or carrier no longer learns photo-vs-voice
  from the frame). The residual is `LinkFraming.FileHeaderWire.mime`: `BlobExchange.onRequest` serves a
  blob to **any** neighbour that asks, so a carrier that actually pulls the bytes still learns the type.
  **Before building it:** `mime` is a required non-null `String` under `encodeDefaults = true` and
  `decodeFileHeader` returning null sets `rxAborted = true`, so *omitting* it hard-breaks blob transfer
  against deployed builds — substitute a constant instead (`image/jpeg` is already the universal fallback
  at `ScopeSync.FALLBACK_MIME`, `MeshBlobStore.fileFor` and `AVATAR_MIME`, so old builds degrade by
  nothing). Do **not** gate it on a capability bit: `Protocol.capabilities` is unauthenticated advert data,
  so gating a privacy control on the carrier's own claim hands the adversary the off switch. The knock-on
  this used to carry is **already closed**: knit/knit-next#30 (fixed 2026-08-23) moved
  `MeshBlobStore.saveIncoming`'s screening skip off the header entirely — it reads
  `messages.attachmentMimeForHash` plus `attachmentKeyForHash`, and `BlobExchange.onReceived` now re-serves
  the stored mime rather than the wire's — so substituting a constant here can no longer weaken screening.
  Receipts and reactions shipped sealed 2026-08-15 as v2 ctl frames (ADR 018,
  docs/ENCRYPTED_RECEIPTS_REACTIONS.md — DM vaccine-purge retired for the sealed era; the residual is
  the cleartext fallback toward pre-ratchet peers, counted by `receiptsSealedFallback`/
  `reactionsSealedFallback`). Group delivery ticks escalate into custody since 2026-08-22 (ADR 033 —
  batched `MessageContent.acks` toward an absent capable author; the residual is the
  never-escalating cleartext/broadcast tick, by design). (Group forward secrecy shipped as the v2 group form — the sender-key
  ratchet over the pairwise sessions, ADR 017, docs/GROUP_FORWARD_SECRECY.md; it also supplies the
  per-sender `epochSeal` export reserved for the spool plane's `sealv = 2` extension; the shared
  group root is now specified by `docs/SPOOL_PROTOCOL.md` §3.2, client machinery deferred with the
  group-scope milestone above.) See `context/e2e-encryption.md`.
- **Compose test rules on the `junit4.v2` API** — `createComposeRule()` / `createEmptyComposeRule()` are
  deprecated as of Compose UI 1.12 in favour of `androidx.compose.ui.test.junit4.v2`, and the ~20 call sites
  (every `*ScreenContentTest` plus `SeededUiTest`) carry a `@Suppress("DEPRECATION")` pointing here. Not a
  rename: the v2 rules run composition on a `StandardTestDispatcher` instead of `UnconfinedTestDispatcher`,
  so work that used to run eagerly inside `setContent` now queues, and any test that asserts without an
  explicit sync can start failing. Do it as its own change — migrate one Robolectric screen test, run the
  suite, then the rest — not as a side effect of a dependency bump.
