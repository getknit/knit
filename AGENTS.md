# AGENTS.md — Knit

Router for coding agents. Knit is an offline Android **mesh messenger** (Kotlin/Compose) that runs
**Wi-Fi Aware (NAN) + Bluetooth LE** simultaneously behind one `MeshTransport` seam
(`CompositeMeshTransport`), no Google Nearby / GMS. This file *points* to context — load a `.agents/`
file only when its trigger matches. Full design detail lives in `docs/`.

## Identity

You are a senior Android/Kotlin engineer on a deliberately bleeding-edge toolchain (AGP 9.3.0 /
Kotlin 2.4.0, Koin DI). Favor correctness, wire/convergence safety, and matching the surrounding style
over cleverness. Start with `.agents/context/architecture.md` for the subsystem map and data flow.

## Context routing

- **Before any build / dependency / tooling change:** READ `.agents/context/toolchain.md` — the
  bleeding-edge choices (Koin-not-Hilt, the Kotlin-2.4 override, detekt/ktlint/Kover/Room as Gradle
  plugins) are deliberate; don't "fix" them.
- **Before changing any dependency version** (catalog, wrapper, plugin, lockfile, a CI action or image):
  run the `upgrade-notes` skill (`.agents/skills/upgrade-notes/SKILL.md`). It reads each vendor's breaking,
  migration and security notes for the exact interval through the `whatsnew` MCP server in `.mcp.json`.
- **Before / after running Gradle:** obey `.agents/rules/build-and-test.md` (which task when, JDK 21,
  lockfile regen). Command list: `.agents/context/commands.md`.
- **Before touching `app/src/main/baseline-prof.txt`, the `:baselineprofile` module, or the
  `nonMinifiedRelease` build type:** READ `.agents/context/baseline-profile.md` — the generator is quarantined
  behind `-Pknit.baselineProfile=true` on purpose, so that the release build consumes a committed text file
  and stays byte-reproducible for F-Droid. Don't wire the plugin into `:app`.
- **Before touching release signing, `packaging`/`ndk` config, `.gitattributes`, or anything else that
  changes release-APK bytes:** READ `.agents/context/distribution.md` — Play and F-Droid ship different
  artifacts under different keys, and F-Droid byte-compares its own rebuild against ours, so the release
  build must not depend on the build machine (no NDK on the APK path, no foojay JDK download, no Git LFS on the
  release path — the screenshot references are its one test-only LFS rule).
- **When editing any Kotlin/Compose/data code:** obey `.agents/rules/coding.md`.
- **When adding or changing text the user reads, a string resource, or a `values-<locale>/` translation:** READ
  `.agents/context/translations.md` — every user-facing word is a resource (detekt's `HardcodedUiText`), a locale
  lands whole because `MissingTranslation` stays a lint error, and some text is deliberately English.
- **When renaming, moving or removing a class, member, file, constant or ADR, or editing anything under `.agents/` or
  `docs/`:** run `python3 scripts/doc-lint.py` and fix what it names. It checks every code span, path and ADR id in
  the docs against the tracked sources and their history (a retired symbol, a missing member or path, a line ref past
  the end, an unknown ADR). A hook runs it on every doc edit, the Stop hook on a dirty tree and both CIs on every
  push. A deliberate mention of an old name carries `<!-- doc-lint: allow <name> — <why> -->` in its paragraph; a
  dated review opts out whole with `<!-- doc-lint: record — <why> -->`.
- **When adding to `CHANGELOG.md`'s `## Unreleased`, or writing a fastlane changelog:** obey
  `.agents/rules/changelog.md` — two sentences, about forty words, run through the `humanizer` skill.
  A `PreToolUse` hook blocks the edit otherwise. Shipped sections are a record; never restyle them.
- **When touching `mesh/`, `protocol/`, or `data/`:** obey `.agents/rules/mesh.md`, then READ the
  relevant reference — `.agents/context/mesh-transport.md` (radios / NAN / BLE),
  `.agents/context/wire-format.md` (CBOR wire), `.agents/context/store-and-forward.md` (custody /
  convergence), `.agents/context/e2e-encryption.md` (crypto; its symbol → ADR routing is
  `.agents/context/crypto.md`). If a change can only be made by *breaking*
  the wire, don't — park it in `docs/NEXT_WIRE_BREAK.md` (the staging list, so a future break carries them
  all at once) and find the additive route per `docs/WIRE_COMPAT.md`.
- **When touching `vectors/`, `GoldenVectorTest`, `KeyedVectorTest` or `IosEmittedVectorTest`, or when a
  change moves a wire byte:** READ `vectors/README.md` and ADR 2026-09.fzh7. The iOS port tests against the
  same files; regenerate with `KNIT_WRITE_VECTORS=1`, never by hand, and never edit `ios-emitted-v1.json`.
- **When filing or rewriting a GitLab issue:** obey `.agents/rules/issues.md` — one template
  (`.gitlab/issue_templates/Agent.md`), one title shape, fixed headings, existing labels only.
- **When your first prompt opens with "# Paired change", or a message arrives from the `knit-pair` supervisor
  or a `knit-ios-*` worker:** obey `.agents/rules/paired-change.md`. The iOS half runs in its own session
  under knit-ios's rules; never edit that repo.
- **When touching the Bluetooth mesh transport** — `mesh/bluetooth/` outside `meshtastic/` and `wear/`, or
  `BluetoothMeshTransport`'s loops, links, adverts, scans, pace or PHY: READ `.agents/context/bluetooth.md`, the
  symbol → ADR routing for the plane (side channel, Coded PHY, pace / feed window / link boost, admission and the
  lonely dial, discovery clocks and TX power, advert re-assert, doorbell, GATT payloads). `grep` it for every symbol
  you touch. For implementation work there, DELEGATE (Claude Code: spawn) the `bluetooth-owner` persona in
  `.agents/personas/bluetooth-owner.md` with a self-contained brief — the goal, the issue or ADR, whether a wire,
  lab or cross-platform change is in scope — then run `bluetooth-reviewer` on what it wrote (below), apply the
  findings and relay the device trial both name.
- **When touching the LoRa plane** — `mesh/lora/` or `mesh/bluetooth/meshtastic/`, a Meshtastic board over BLE GATT
  that extends the Nearby room and 1:1 DMs over LoRa as a fast-plane-only `MeshTransport` child (visible since 2.5.0
  behind `BuildConfig.LORA_PLANE`, off until the user pairs a board): READ `.agents/context/lora.md`, the symbol → ADR
  routing for the plane (frame policy and codec, airtime and pacing, send-time gates, reach, the bridge, persisted
  limiters, board setup and dial, the Meshtastic room and its DM auto-reply, the plane's face), and
  `.agents/context/lora-bridge.md` for the mechanism. `grep` the routing for every symbol you touch. `mesh/lora/` is
  pure/JVM-tested; the only `android.bluetooth.*` importer is `mesh/bluetooth/meshtastic/MeshtasticGatt`. For
  implementation work there, DELEGATE (Claude Code: spawn) the `lora-owner` persona in `.agents/personas/lora-owner.md`
  with a self-contained brief — the goal, the issue or ADR, whether a wire, paired-profile or lab change is in scope —
  then run `lora-reviewer` on what it wrote (and `bluetooth-reviewer` too when `meshtastic/` changed), apply the
  findings and relay the trial both name.
- **When touching `linkpreview/`, `net/`, `mesh/protocol/LinkPreviewBlob`, or anything that opens an
  Internet socket outside the spool plane:** READ ADR 2026-09.n752 (and 2026-09.7x8k: a send holds up to 5 s
  for the card its link is fetching, or the share sheet never carries one; a LoRa thread takes a card exactly
  as it takes a photo — no `loraCarry` gate on the fetch). A link preview is a card the
  **sender** fetches and sends as an ordinary attachment under its own MIME (no wire field, no DB change); the receiver
  never fetches, both ends screen the card's picture and text into one verdict, and the fetch is gated on
  `net/InternetGate` (a validated route, never the NAN link), bound to that `Network`, https-only, with a
  private-address DNS guard. `okhttp3` stays confined to the three files `rules/mesh.md` names.
  Anything new that can open an Internet socket, or a default that turns one on, must keep
  `scripts/egress-check.sh` green: a fresh install with default settings sends no byte over IP (the "Egress
  check" section of `.agents/context/testing.md`).
- **When touching `transfer/`, `MeshTransport.pause`/`resume`, or anything that hands the Wi-Fi radio to a
  second role:** READ `.agents/context/direct-transfer.md` — a large file goes to one nearby contact over a
  Wi-Fi Direct group the two phones raise, never over the mesh and never custodied (ADR 2026-09.wtmz, the
  seal 2026-09.37ce, the surfaces 2026-09.7uqe, READY re-sent and the sender's Retry 2026-10.5hzt).
  `TransferManager`/`TransferStream` are pure behind the `DirectWifi`/`TransferFiles`/`TransferSignals` seams,
  and `AndroidDirectWifi` is the one
  `android.net.wifi.p2p` importer. Wi-Fi Aware does **not** yield to our own P2P on Android 12+, so it must
  be paused explicitly — that is what `pause`/`resume` are for. The radio is shared with calls through
  `DirectWifiLease` (ADR 2026-10.kxnw): every user holds a leased view, never `AndroidDirectWifi` itself.
  **Before touching `DirectHostGuard`, `DirectWifi.hostRefusal`, `TransferRefusal.CannotHost`, `device/SystemImage` /
  `DeviceImage` or the `direct_host_` keys:** READ ADR 2026-10.w3dw — a generic system image over an older vendor
  never hosts a group (its `system_server` dies when a client joins), nor does a phone latched after two unexplained
  deaths while hosting in one boot; the lease refuses the host before `createGroup`, and a joiner is never refused.
- **When touching `call/`, `MessageContent.CTL_CALL` / `cl`, `CallPayload`, `Protocol.CAP_CALL`,
  `MeshManager.extraCapabilities`, the spool's `turnreq`/`turncred` (`SpoolConnection.turnCredential`,
  `ScopeSync.turnCredentials`), `di/CallWiring`, `data/settings/CallTombStore`, `SettingsStore.callsRelayAll`,
  `ui/call/`, `app/src/calls/`, `app/src/liveCalls/`, `app/src/stt/`, `di/AnywhereWiring` or the `liveCalls` /
  `meshCalls` / `stt` / `knitAnywhere` flags in `app/build.gradle.kts`:** READ `docs/VOICE_CALLS.md`, ADR 2026-10.3tee
  (its 2026-10-07 amendment is this branch's minimal build; its 2026-10-14 one is the first flag) and ADR 2026-10.swvp
  (the four flags). Experimental, behind four flags, each on in debug, off in release and overridden by its own `-P`
  (`-Pcalls=` sets both call kinds), each read once in `di/`: `LIVE_CALLS_WIRED` (`BuildConfig.LIVE_CALLS`),
  `WALKIE_WIRED` (`BuildConfig.MESH_CALLS`), `STT_WIRED` (`BuildConfig.STT`, only with a call kind) and
  `ANYWHERE_WIRED` (`BuildConfig.KNIT_ANYWHERE`); `CALLS_WIRED` is either call kind. WebRTC, core-telecom and Moonshine
  are `compileOnly`; a build packages WebRTC and `app/src/liveCalls/keepRules` only with live calls, core-telecom and
  `app/src/calls/AndroidManifest.xml` with either kind, and Moonshine with `app/src/stt/` only with speech — so release
  ships no call code, no `libjingle`, no call permission and advertises no `CAP_CALL`. Never make any of the three
  `implementation`. A kind's or speech's own definitions sit behind its constant in `di/CallWiring` (and every lookup of
  one elsewhere), so R8 drops what a build does not wire; a build without live calls hands `CallManager`
  `CallMedia.None`, which places every call as a walkie and declines a live offer, and `CAP_CALL` means "takes a live
  call".
  The Calls screen's "Take live calls" / "Take mesh calls" (`data/settings/IncomingCallStore`, `CallManager`'s
  `takesIncoming`) answer a switched-off kind `DECLINE rsn=timeout` — the caller's "No answer" — without ringing, busy
  or not; a blocked contact's offer never reaches the manager, since every DM of theirs drops before decrypt (ADR
  2026-10.fcrd).
  `CallManager.onSignal` runs on the inbound path — never make it wait on TURN or the media — and a TURN credential is
  never logged or put in a frame. A call's line in its thread (`data/message/CallRecord`, `MessageEntity.KIND_CALL`,
  `call/CallLog`) is §3.12: a record this phone writes when the call ends, never sent, saved off the inbound path.
  **Before touching `call/SystemCalls` (`SystemCallSync`, `CallControl`), `call/CallAudioRoute` (`CallAudioPolicy`),
  `ui/call/TelecomCalls`, `AudioRouteButton`, the call notification's route action, `TalkPlayer`'s usage,
  `TalkRecorder`'s sources or `ui/call/ProximityLock` (`CallAudioPolicy.screenOffNearEar`):** READ ADR 2026-10.wbua
  and `docs/VOICE_CALLS.md` §3.13. Every call, live or walkie, is
  a self-managed Telecom call through core-telecom (packaged only with calls, an RC by a recorded exception in
  `context/toolchain.md`), and Telecom owns its route, mode and focus. Never set a route beside it
  (`setCommunicationDevice`, `startBluetoothSco`, a track's preferred device). A walkie differs only in starting on
  the loudspeaker. The sync follows `CallManager.state` and is never on the inbound path. **Before touching `CallPayload.dw` / `dx`, `call/CallDirect`, `DirectCallPath`,
  `CallLane`, `MediaEvent.DirectCandidates`, `ui/call/DirectNetworkDetector`, `transfer/DirectWifiLease` or
  `DirectWifi.join`'s `ipv4`:** READ ADR 2026-10.kxnw and `docs/VOICE_CALLS.md` §3.10 — a call to a mesh neighbour
  rides a Wi-Fi Direct group: the caller offers, the callee says it is joining (and joins while it rings, with an
  IPv4 lease), the caller hosts only then; the group reaches libwebrtc through our own detector as an Ethernet-typed
  network (never `setIncludeWifiDirect`, which leaks a P2P channel), and a sealed lane over it carries every signal
  beside the mesh, since Wi-Fi Aware is paused while the group is up. Relay-only goes direct nowhere.
  **Before touching `SpoolEnt` / `SpoolTicket`, `mesh/spool/SpoolTickets`, `SpoolConnection.present` /
  `ticketState` / `ticketedTurn`, the order in `ScopeSync.turnCredentials`, `stunUrls`, `TurnSource.stunUrls`,
  `call/StunServers`, the `spool_ticket` settings keys or `…debug.SPOOL --es ticket`:** READ ADR 2026-10.xkp4 and
  `docs/VOICE_CALLS.md` §2.6 (knit-spool ADR 2026-10.bxsg) — a ticket goes once per connection after the hello and
  only to the spool whose host name is its `aud` (a privacy rule), a refusal is a stored verdict, an `ent` spool gets
  no `turnreq` without a TURN-flagged ticket and a ticketed one is asked first, and every call gathers from the
  hellos' STUN under a URL rule narrower than the spec's, because one URL libwebrtc refuses ends the call.
  **Before touching `call/Walkie.kt` (`CallWalkie`, `WalkiePolicy`, `TalkWire`, `OpusPackets`, `TalkQueue`), `call/TalkGain`, `CallKind`,
  `CallPayload.MEDIA_WALKIE`, `FrameType.TALK`, `mesh/protocol/TalkSeal`, `MeshManager.sendCallTalk`,
  `InboundPipeline.handleTalk` / `isTalkShape`, `Protocol.CAP_WALKIE`, `call/TalkRoute` (`TalkRoute`, `HeardLedger`),
  `CallPayload.hr`, `mesh/TalkRadioPaths`, `CallManager`'s `fallback` / `walkieOk` /
  `onTalk`, `MeshTransport.sendLive` / `holdLink`, `mesh/wifiaware/NanLinkHold` (the hold in `superviseLink`), `TalkLink`,
  `ui/call/WalkieTalk` / `TalkRecorder` / `TalkPlayer` or `BuildConfig.MESH_CALLS`:** READ ADR 2026-10.79yb and
  `docs/VOICE_CALLS.md` §3.11 — a call with no live path is a media-less walkie session whose bursts stream as Opus in
  unsigned, compactly sealed `talk` frames nobody custodies, sized for Coded PHY (a pin in `TalkSealTest`), so nothing
  lands in the thread; a chunk stays on the radios while `TalkRadioPaths` holds fresh, link-bound evidence of a path,
  and is cast beside them otherwise or while the listener's per-burst reports say they lose it (C-3.11-10, the twelfth
  amendment; never `heardIndirectly`); a live call drops to one, without a frame, only when both OFFER and ANSWER carried the walkie
  bit (parked since 2026-10-09: `CallManager.dropToWalkie` is off, so a lost live call ends `no_route`); the flag is read once, as `CallWiring.WALKIE_WIRED`, and needs no live calls.
  **Before touching `call/CaptureHealth` (`CaptureProfile`, `CapturePolicy`, `CaptureWatch`), `ui/call/CaptureTap`,
  the audio module in `WebRtcCallMedia` or the `call_capture_` keys:** READ ADR 2026-10.kw29 and `docs/VOICE_CALLS.md`
  C-3.13-12/13 — a generic image (and a phone where a call went silent) records calls from `VOICE_RECOGNITION` with
  WebRTC's own canceller, the module is rebuilt per profile between calls only, and the tap watches every 10 ms buffer
  and moves a call whose path hands it only zeros onto its own recorder mid-call.
  **Before touching `call/OverridingTurnSource`, `CustomIce` / `OwnIceServers` / `TurnUrls`, `call/IceProbe`
  (`WebRtcCallMedia.probe`), `data/settings/OwnIceStore`, the `calls_stun_` / `calls_turn_` keys or `ui/callsettings/`:**
  READ ADR 2026-10.mr88 and `docs/VOICE_CALLS.md` §3.14 — the person's own STUN and TURN each replace the spools' for
  that kind (an own TURN sends no `turnreq`), a stored value is re-validated on every read, the password is never
  logged, and the Test opens no microphone and never runs under a call. Debug builds only for now (`OWN_ICE_WIRED`, the
  ADR's 2026-10-20 amendment): elsewhere no server row, no Test and no `OverridingTurnSource`.
  **Before touching casts — `SpoolCast`, `SpoolConnection.castOut` / `canCast` / `ticketedCast`, `ScopeSync.cast` /
  `canCast` / `handleCast`, `ScopeCrypto.sealCast` / `openCast`, `ScopeFrames.castEligible`, `CallPayload.ct` or
  `TalkLink.SPOOL`:** READ `docs/VOICE_CALLS.md` §2.7 and ADR 2026-10.79yb's sixth amendment — a chunk with no mesh path
  is cast through the spools, signed, never stored, never answered and never a request, and on an `ent` spool only
  where one subscriber's cast ticket unlocks the scope.
  **Before touching `call/WalkieTranscript.kt` (`BurstPcm`, `PassGate`, `SpeechStream`, `WalkieTranscriber`, `SttPolicy`),
  `call/CallCaptions.kt` (`CallCaptioner`, `CaptionPolicy`), `WebRtcSession.listen` (the captions' sink), `LiveCaptions`
  and the live call's CC button (`TranscriptStore.liveCaptions`),
  `call/WalkieClips.kt` and `WalkieClipFiles` (a debug build's saved bursts, off by default), `call/SttKeytermPolicy.kt` and
  `SttKeytermFeed` (the names the model listens for),
  `call/VoiceNoteTranscript.kt` (`VoiceNoteTranscriber`, `VoiceNoteSpeech`), `VoiceAudio.speechPcm`, `messages.voiceTranscript`
  and the voice bubble's Transcribe (also ADR 2026-10.am8d),
  `call/SttModel.kt` (the pins, `SttModelInstaller`), `ui/call/stt/` (`MoonshineSpeech`, `SttModelStore`,
  `SttDownloadService`), the Calls screen's transcripts section (`data/settings/TranscriptStore`), `TalkPlayer`'s `TalkPcmTap`,
  `WalkieTranscriptCard`, `ModelLoadGuard.STT`, the `moonshineVoice` pin or `scripts/stt-model.py`:** READ ADR
  2026-10.nz3g and `docs/VOICE_CALLS.md` C-3.11-9 and C-3.7-5 — the receiver writes each heard burst, and with CC on a
  live call's peer, out as text while it plays, behind `BuildConfig.STT` (`STT_WIRED`) and never on the wire (a live call's streams
  always split at pauses, stop 8 s behind, and this phone's own voice is never captioned); the model (Moonshine v2 Small Streaming alone, through
  Moonshine's own runtime, called by its `JNI` class alone and taken without its transitive dependencies) is a
  tap-to-download from getknit.app (the walkie card, whose offer can be dismissed for good, or the Calls screen, held
  past a hang-up by a `dataSync` service; the Calls screen's switch turns transcripts off), pinned by size and SHA-256 (never hand-edit the pins: the script derives them),
  fetched with `Network.openConnection` on the gate's network, not okhttp; each burst is one stream fed as the audio
  comes, looked at on `PassGate`'s cadence, one model call at a time, and every stream call holds the lease. A chat voice note
  is written out only on the person's tap, kept on the row, never during a call, and never on the wire (ADR 2026-10.am8d).
- **When touching `badge/`, `ProfilePayload.badges` / `BadgeEntry` / `ProfileV2` label 8, `peer_badges` /
  `PeerBadgeEntity`, `data/settings/BadgeStore`, `OkHttpTicketIssuer.issueBadge`, `ui/components/BadgeVisuals` (`BadgeMark`) or
  `Avatar`'s `badge`:** READ `docs/SUPPORTER_BADGE.md` and ADR 2026-10.r99s (knit-spool-coordinator owns the
  credential's bytes and the route). The badge rides the two sealed profile layouts only, never `ProfileContent`; a
  receiver stores it apart and only a sealed profile replaces it; it is verified when drawn, never only on arrival,
  against the pinned keys in `BadgeKeys`. The
  coordinator's vectors are copied from its SHA, never recomputed. Never log or dump an `r`, a `sub` or a wrapper.
- **When touching `location/`, the chat overflow's "Send location" item, or anything that reads the device's
  position:** READ ADR 2026-09.tss4. A shared position is a `geo:` line in the message body (no wire field,
  no capability bit); it is read only between the pin tap and the send, by `ChatViewModel.startLocation`,
  the one collector of `LocationSource.fixes`, and `location/AndroidLocationSource` is the one
  `android.location` importer (detekt-enforced). Never ask for the grant at onboarding.
- **When touching `mesh/MeshService` (`onCreate` / `onStartCommand` / `postForeground`), `MeshService.start`,
  `canReclaimForegroundService`, or the `KnitApp` effects that start the mesh:** READ ADR 043 (a refused claim
  makes a stillbirth; the caller-side guard and the resume retry) and ADR 2026-09.f69x (every non-Stop start
  re-claims the foreground state — the system demotes a background-restricted app's service silently, and a
  `startForegroundService` into that instance arms a deadline nothing else would meet) and ADR 2026-09.vztn
  (`onCreate` claims the state before anything, then resolves the graph on the **app scope**, never on the main
  thread — `onCreate` + the `onStartCommand` behind it share a separate 20 s "executing service" ANR budget, and a
  process born for the service is the one the UI never pre-built the graph for; every later callback keys on
  `meshStarted`, never on an injected field; a build failure is a crash on the main looper, except a
  `KeystoreUnavailableException` down its cause chain, which stands the service down with an alert — ADR
  2026-10.47rw). The foreground deadline is 30 s on Android 15 (10 s before), and
  `getForegroundServiceType()` cannot tell you it was lost. `BootReceiver` starts through
  `MeshService.startFromBoot`, never `start` — ADR 2026-09.29dw: the pre-check reads process state, a
  receiver's is `IMPORTANCE_SERVICE`, and the boot exemption is the platform's. Regression:
  `MeshServiceForegroundReclaimTest`, `MeshServiceGraphOffMainTest`, `GraphlessProcessTest`,
  `MeshServiceStartTest`, `BootReceiverTest`.
  **When touching the notification's Pause / Resume actions, `SettingsStore.meshPausedUntil`, `mesh/MeshPause`,
  `MeshService.applyPause`, or the resume alarms:** READ ADR 2026-09.wz99. A pause is one DataStore deadline
  the service applies idempotently in place — the service stays foreground, `MeshManager` goes down, every
  surface reads the key through `MeshPause.activeDeadline` — never `MeshTransport.pause` (that is the Wi-Fi
  Direct hand-over) and never a stop-and-restart (an alarm cannot start a foreground service from the
  background). Every plain start re-claims without resuming; the two resume alarms are inexact and bounded, not
  `SCHEDULE_EXACT_ALARM`; Stop cancels the store collector before `stopSelf()`. **Stop is sticky** (the
  amendment): `KnitApp`'s route effect and `ON_RESUME` observer both ask `ui/MeshStartPolicy.kt`'s
  `shouldStartMeshFromUi` with `SettingsStore.meshEnabled` read from the store at that moment (a
  lifecycle-collected copy is stale on the resume after a Stop — device-observed) — and the chat list's Start
  only writes the flag back; don't add a second starter or decide on a cached value.
  Regression: `MeshServicePauseTest`, `MeshStartPolicyTest`.
  **Before touching the type bitmask `postForeground` claims (`meshForegroundServiceTypes`), the manifest's
  `<service>`, or `TransportHealth.ForegroundOnly` / `NanSessionFault` in `mesh/wifiaware/`:** READ ADR
  2026-09.535d. The service claims `location` on exactly the tiers where `requiredRadioPermissions` rides the
  location grant (29-32) — the Wi-Fi Aware publish/subscribe are gated on the foreground-only location app-op
  there, and only the *runtime* type bit lifts it (the manifest is a bound, never a grant; `0` silently drops
  it). The manifest's `FOREGROUND_SERVICE_LOCATION` lint is suppressed on purpose (Play's declaration form). A
  location refusal off screen is held as `ForegroundOnly` and retried on `heal()`, never torn down through
  `onSessionDead`.
- **When touching `MeshManager.heal` (the basket, `healBasket`, `HealFloors`, `sweepKeyRetention`,
  `replayUndeliveredGroupCustody`), `MeshService.onSignificantMotion` / `MOTION_HEAL_FLOOR_MS`, or
  `ForwardStore.liveGroupChatFrames`:** READ ADR 2026-09.6st4. The radio poke (`transport.heal()`) runs on every call —
  a resume is what lifts the NAN location app-op (535d) and buys the lonely loop its re-arm (kb68) — so never floor
  `heal()` whole; the motion trigger is floored at its source (60 s), one basket runs at a time, and the retention
  sweeps and the custody replay run once an hour off the start's stamp. The replay reads the store's group-chat query,
  never `liveFrames`. Regression: `MeshServiceMotionTest`, `MeshManagerTest`'s heal cases, `ForwardDaoTest`.
- **When touching low-power mode — `mesh/power/LowPower.kt` (`LowPowerPolicy`, `LowPowerSource`,
  `awareSwitchedOff`, `BatterySaverState`), `data/settings/LowPowerSwitch`, `SwitchableTransport`'s `off` and
  `switchedOff`, `TransportStatus.switchedOff` / `radioWarningFor`, the motion gate in `MeshService.observeLowPower`,
  or `…debug.LOWPOWER`:** READ ADR 2026-10.d44b and its amendments. The mode is an overlay on the user's switch or
  Battery Saver, lifted after a minute on the charger; it writes one phone-bound key and nothing else. Phase 1 gives
  up exactly Wi-Fi Aware (through `SwitchableTransport`, in every build) and the motion heal (the sensor left
  unarmed, the heartbeat and resume heals kept); each further lever waits for a battery arm that shows it paying
  (`scripts/battery-trial.py`). A plane Knit switched off is never a "turn on Wi-Fi" banner. Regression:
  `LowPowerTest`, `SwitchableTransportTest`, `MeshServiceMotionTest`, `RadioWarningTest`, `LowPowerSwitchTest`.
- **When touching `mesh/power/PowerPolicy` (`idleAfterScan` / `lonelyRelaxed` / the `LONELY_*` constants),
  `mesh/wifiaware/NanLonelyPolicy`, or the cadence in `BluetoothMeshTransport.scanLoop`:** READ ADR
  2026-09.kb68 and ADR 2026-09.w3xk. The shared rule is "alone ≥ 3 min, not charging"; each radio picks its
  relaxed cadence — the BLE scan opens a screen-on node's gap to 60 s (12 s BALANCED window kept), the NAN
  re-arm keeps a screen-on node aggressive on purpose (a 30 s tick leaves ICM lit) — so don't flip one radio's
  interactive case without the other's device trial. Charging never relaxes. Tests: `PowerPolicyTest`,
  `NanLonelyPolicyTest`.
- **When touching the Wi-Fi Aware mesh transport** — `mesh/wifiaware/` (`WifiAwareTransport`, the `Nan*Policy`
  classes), `data/settings/NanAttachJournal` / `NanInitiatorJournal`, or the transport's attach, responder, initiator,
  watchdog, message plane, cues or ICM: READ `.agents/context/wifi-aware.md`, the symbol → ADR routing for the plane
  (two-plane design and the accept-any responder, the 0-NDP ghost, the attach leak budget, the wedge watchdog's tiers,
  responder re-files, the initiator hold, the message-plane watchdog, hop attribution). `grep` it for every symbol you
  touch. For implementation work there, DELEGATE (Claude Code: spawn) the `wifi-aware-owner` persona in
  `.agents/personas/wifi-aware-owner.md` with a self-contained brief — the goal, the issue or ADR, whether a wire,
  lab or hard-cut (`SERVICE_NAME`, `PMK`, SSI) change is in scope — then run `wifi-aware-reviewer` on what it wrote,
  apply the findings and relay the device trial both name. Never toggle Wi-Fi on a lab phone to test it.
- **When touching `moderation/MlTextModerator`, `NsfwImageModerator`, `ModelLease`, `TfLiteModels`,
  `ModelLoadGuard`, the `…debug.MODEL` bridge, or `noCompress` in `app/build.gradle.kts`:** READ ADR 037 and
  ADR 2026-09.cq9z. Each interpreter is mmapped from the APK's **Stored** `.tflite` entry (`noCompress "tflite"`
  is load-bearing; no `tensorflow-lite-support`), built with explicit options (2 threads, XNNPACK), and held
  on a `ModelLease`: the lease owns the mutex, the ten-minute idle reaper and the attempted/resident split — a
  load that returned nothing is spent for the process (that is how the poison-pill survives an unload), a
  reload goes through `ModelLoadGuard` again, and `Interpreter.close()` is never called outside the lease.
  Never put `NsfwImageModerator` on a warm-up path. Regression: `ModelLeaseTest`, `ModelLoadGuardTest`,
  `MlTextModeratorWarmUpTest`; on hardware `ToxicityInstrumentedTest` / `NsfwInstrumentedTest`.
- **When touching `LexicalTextFilter`, `ScopedTextModerator`, `WordList`, `MlTextModerator`'s block thresholds,
  `scripts/gen-profanity-list.py` or a list under `assets/moderation/`:** READ ADR 2026-10.d62w and
  `docs/CONTENT_MODERATION.md` §2. Profanity is the Nearby room's alone; identity slurs are flagged in every
  scope by `slurs_en.txt`, because the toxicity model scores a slur aimed at someone as an insult (a label it
  ignores) and its `severe_toxicity` never fires. Both lists are generated — change the script's dials, never
  the file — and `SlurListTest` pins what a private chat must not block (`nigga`, `coon`, `fag`, `dyke`).
- **When touching a send-side screen — `MeshManager.sendChat` / `sendPublicPost` / `sendCommons`'s
  `isTextFlagged`, `AttachmentStore.ingest`'s `screen`, `ChatViewModel.stage`, `LinkPreviewService.fetchCard`,
  `AvatarStore.saveOwnAvatar` — or the "Message hidden" stand-ins (`InboundPipeline.notificationBody`,
  `messagePreview`'s `hideFlagged`, all reading `MessageEntity.hiddenByFilter`):** READ ADR 2026-10.9rh6. Only the
  Nearby and Meshtastic rooms screen what is sent; a DM, a group and the commons never do, and the receiver's Content
  filtering toggle decides what is hidden. Avatars and group photos are the exception, screened when set. A spool
  requiring moderation (`docs/SPOOL_PROTOCOL.md` §7.5, client half unbuilt) is what would bring the private screen back.
- **When touching quoted replies — `ReplyRef`, the `replyTo*` / `replyCheck` / `replyToFlagged` columns,
  `data/message/QuoteCheck.kt`, `data/QuoteVerifier`, `InboundPipeline.deliverChat`'s quote path, `MessageDao.depthOf`,
  or `ChatViewModel.quoteViewFor` / `ChatScreen`'s `QuotedMessage`:** READ ADR 2026-10.gpa3. A quote is the sender's
  claim: the receiver judges it against the original it holds (same id, same thread, same author), rebuilds it from that
  original by the composer's own `quoteSnippet`, and stores the verdict, judged on whichever of the two lands second,
  through a once-only conditional update — never a whole-row upsert. A reply quoting words flagged on this phone is
  hidden like a flagged body (`hiddenByFilter`, non-transitive). The author is always the reader's own label for the
  id, never `ReplyRef.author`; our own replies are never rewritten. Regression: `QuoteCheckTest`, `QuoteVerifierTest`,
  `mesh/lab/QuoteLabTest`.
- **When touching receive-side screening — `MeshBlobStore.ingest` / `saveIncoming`, `MeshManager.scopeBlobs()`,
  `ScopeBlobs.refused`, or what adopts a received avatar or group photo (`InboundPipeline.avatarAdoptable`,
  `adoptAdvertisedAvatar`, `settleArrivedGroupPhoto`):** READ `docs/CONTENT_MODERATION.md` §3 and §7. Every blob
  that arrives from someone else, over the radios or a spool, goes through `ingest` before its obtained hook runs,
  and a key-less one is always screened there, before it is stored, so "held" means "screened" (#109). A held avatar is adopted only if screening has not refused
  it: a refused avatar's bytes can stay, pinned in custody by the sealed profile that names them.
- **When touching `crash/` (the crash store, `CrashRedactor`, `AnrTrace`, `importAnrs`) or the Diagnostics crash
  screens:** READ ADR 028 and ADR 2026-10.fzsy. Reports are written redacted and leave the phone only by the user's
  hand; ANRs are imported from `ApplicationExitInfo` at start, once each (the `anr-seen` watermark), and `AnrTrace`
  is the privacy boundary for native frames, which the redactor passes verbatim.
- **When touching `fieldlog/`, a `FieldEvent`, a `field.record(…)` / `field.sampler(…)` / `follower(…)` call in a
  transport, the spool plane (`mesh/spool/SpoolFieldLog.kt`), the LoRa plane (`mesh/lora/LoraFieldLog.kt`) or the call
  plane (`call/CallFieldLog.kt`, `CallMediaSampler`), `ui/diagnostics/FieldLog*`, or `MessageDao.inboundDelays` /
  `receiptDelays`:** READ ADR 2026-10.a239 (its 2026-10-18 amendments for the call lines and the LoRa, spool and route
  lines) and
  `docs/FIELD_LOG.md`. Every build can keep a bounded record of how the radios behaved (4 MiB, 72 h, in
  `noBackupFilesDir`) — off until the user switches it on — and only the user shares it from Diagnostics; no
  upload path, ever. `FieldWriter` has
  no string field (a peer or a relay is a salted pseudonym), so redaction holds by construction; never add one. A new event
  is a golden line in `FieldEventSchemaTest`, a row in the doc and a kind in `scripts/fieldlog.py`; a sampler
  runs on the log's tick and only reads.
- **When touching `legal/`, `ui/about/`, `app/src/main/assets/legal/`, `THIRD-PARTY-NOTICES.md`, or a shipped
  dependency:** READ ADR 2026-09.6eb6. The in-app Open-source licenses list is `legal/ThirdPartyNotices.kt`,
  pinned to the notices table by `ThirdPartyNoticesSyncTest` and to `app/gradle.lockfile`'s
  `releaseRuntimeClasspath` by `ReleaseClasspathNoticesTest` — a new shipped dependency is a row in both files
  (with the artifact prefixes that claim it) or a `NOT_SHIPPED` entry with its reason, and nothing else makes
  those tests pass. The license texts are committed copies (no Gradle task; `assets/legal/COPYING` is
  byte-pinned to root `COPYING`), and nothing from the build machine — git SHA, timestamp — goes into a
  release's About: the release APK is byte-reproduced by F-Droid, so only the debug variant fills
  `BuildConfig.GIT_SHA`. No license plugin; `legal/InstallSource.kt` is the app's one installer read.
- **When touching `ui/onboarding/`, `ui/Permissions.kt`, `ui/BackgroundBattery.kt`, or `BootReceiver`'s start
  decision:** READ ADR 2026-09.nzpr. Entry is gated on `hasRadioPermissions` alone — the transports assume
  those grants (`@SuppressLint("MissingPermission")` is a lint suppression, not a runtime guard), so the mesh
  never starts without them. `POST_NOTIFICATIONS` and the battery exemption are optional rows on the
  permissions page (`MessageNotifier` self-checks before posting), and `SettingsStore.onboardingSeen` only
  picks which page a returning phone opens on, never whether onboarding shows. The name page writes through
  the shared `ui/components/DisplayNameField`; a grant added to `requiredRadioPermissions` re-wedges the
  front door.
  The battery row (here and in Settings) reads `ui/BackgroundBattery.kt`'s three-position enum, not the bare
  exemption — READ ADR 2026-09.gc3m before touching it: Restricted wins over a stale exemption, and no prompt
  of ours can lift it, so that state always lands on "Open settings".
  The unused-app row beside it reads `ui/UnusedAppPause.kt` (`isAutoRevokeWhitelisted`, API 30+, null below
  so the row hides) — READ ADR 2026-09.54xg: it has no prompt, its default is not an error (`quietHint`), and
  its callback is `openUnusedAppPauseSettings`, not `openSettings`, because Android 11 keeps the switch on a
  page of its own.
- **When touching `ui/DeviceSupervision.kt`, `ui/components/PermissionDeniedDialog`, the `settingsHint` of a
  permission row, or the location / mic / camera gates' denial copy:** READ ADR 2026-09.a8ud. A parent's or
  an administrator's denial (`POLICY_FIXED`) is indistinguishable from "don't ask again" from inside the
  app, so the only signal is *who administers the phone* — Family Link by its pinned profile-owner package,
  else any management signal — and every hint is conditional ("if Settings greys it out as Disabled by
  admin", the platform's own wording). Family Link
  can hold only the classic groups (never Nearby devices), so `supervisedHint` names a parent on the radio
  row only where Location is in it; a managed phone is named everywhere. Family Link's pauses and downtime
  leave the running foreground service alone (device-verified 2026-09-16) — don't add restart machinery
  for them; `android.app.admin.*` / `UserManager` stay confined to that one file (detekt).
- **When touching `data/crypto/DatabaseKey`, `SqlCipherKey`, the driver or pool size in `KnitDatabase.build`, or
  anything else that opens a SQLCipher file:** READ ADR 2026-09.uzkm. The database is keyed with the passphrase's
  raw form (`x'<hex>'`, no KDF), and a file an older build keyed through the KDF is rekeyed once by
  `SqlCipherKey.upgrade` before Room opens it. Never hand SQLCipher the bare passphrase: every pooled connection
  would pay a 256,000-iteration derivation under the pool's lock (≈46 s of cold start on a 32-bit phone). The pool
  is capped at four. Regression: `SqlCipherKeyTest`, `SqlCipherRawKeyTest`.
- **When touching `data/crypto/KeystoreSecret`, `KeystoreCipher`, `KeystoreFailure`, `DatabaseKey.getOrCreate` /
  `current`, `IdentityKeyStore.loaded` / `generateAndStore`, the `.lost` slot, `ui/StorageGate`, the `StorageGate`
  half of `MainActivity`, or anything else that reads a Keystore-wrapped secret:** READ ADR 2026-10.47rw. Only
  proof a secret is gone may wipe or mint: a tag that fails, a key permanently invalidated, a key missing on
  every look, a wrap too short to be one — the same verdict on all three attempts. Everything else, unrecognised
  included, is a refusal: `KeystoreUnavailableException`, every file as it was, and the service and the UI stand
  down on it (alert / Try again) instead of crashing. The screen's Start over is the user's choice behind a
  confirmation (`SignOut.here`'s wipe); never trigger it from code or a retry count. Look keys up with `getKey`, never `getEntry` (keystore2
  answers null for a busy backend there); never generate under an alias a live wrap depends on without
  confirmed absence; read a mint back before using it; and a live reader (the backup writer) uses `current()` /
  `read()`, never `getOrCreate()`. Debug builds refuse on cue while `files/keystore-fault` holds a count.
  Regression: `KeystoreSecretTest`, `DatabaseKeyRecoveryTest`, `IdentityKeyStoreRecoveryTest`,
  `BackupWriterKeystoreTest`, `StorageGateTest`; on a device `DatabaseKeyTest`.
- **When touching `data/draft/`, what the composer keeps between visits, or the chat list's `Draft: …`
  preview:** READ ADR 2026-09.qtg9. An unsent draft is a row in the encrypted DB (never the DataStore —
  it is message text), written debounced on the *application* scope, and handed to the composer exactly
  once by `consumeRestoredDraft()`; nothing is persisted before that hand-over, because the field reports
  itself empty the moment it composes. The list shows it in place of the preview only while
  `updatedAt` beats the newest message, and never touches the row's time or sort. Text only: the staged
  attachment, the reply quote and the mention bindings stay draft-local to the screen.
- **When touching `saved_files` / `data/blob/SavedFileEntity`, `ChatViewModel.openAttachment` /
  `saveAttachmentTo`, or what a file bubble's tap does:** READ ADR 2026-09.7ad3. A received file opens from the
  copy the user saved it to (a persisted read grant on the picker's document, recorded by blob hash) and never
  from the blob store (ADR 029); a copy that is gone is forgotten and the picker comes back. A risky file is
  only ever saved. The table is device-local — never in a backup — and goes with its blob in the GC.
- **When touching `data/search/`, `messages_fts` / `MessageFtsEntity`, `ui/search/`, the chat route's
  `messageId` argument, or the shared list rules in `ui/ConversationTitles.kt` / `ui/contacts/ContactUniverse.kt`:**
  READ ADR 2026-09.wdfz (the FTS4 index: one bounded `MATCH` read, the `term*` builder, the rowid and
  no-VACUUM invariants) and ADR 2026-09.wnh6 (one screen over the chat list's own universe — its
  membership, titles, speaker and contacts rules live in those two shared files so search cannot drift
  from the list; a hit opens the thread through `chat/{id}?messageId=` and the quote-jump machinery,
  never a second thread view). "Search in this chat" is deferred — CHECK `.agents/memory/roadmap.md`.
- **When touching `wearstatus/`, `mesh/wear/`, `mesh/bluetooth/wear/WearStatusServer`, the `:wear` or `:watchface`
  module, or `BuildConfig.WEAR_STATUS`:** READ ADR 2026-09.wetm. A bonded Wear OS watch reads the mesh status the phone serves
  while `MeshService` runs — no Data Layer (GMS), never on the mesh wire — over a secure RFCOMM socket on the Classic
  link first (one `WearStatusFrame`; its third amendment: the phone's eight GATT slots fill with mesh links, so an LE
  read can be refused outright), then one read-only encrypted GATT characteristic over LE as the fallback, reachable
  through the mesh's own advert and, only while paused, the server's own. Keep both until other watches report. A prototype: dark in release, `:wear` only under `-Pknit.wear=true`. The watch surfaces (complications in every suited type with a staleness timeline, a
  Material3 tile that never blocks on Bluetooth, the M3 Expressive app) are the ADR's 2026-09-27 amendment; Wear Widgets wait for a stable release.
  The second amendment extends the snapshot to exactly 20 bytes (carrying, far peers, 4-bit per-peer plane masks — no ids; `GOLDEN_EXTENDED`
  in both modules) for the peer map, and adds the watch-only day history (`StatusHistory`/`DayStats`). `wearstatus/` is
  compiled into both modules, so it imports nothing but `java.*`/`kotlin.*`, and its layout is pinned by one golden
  vector in `WearStatusCodecTest` and `:wear`'s `StatusTextTest`; a stopped or paused mesh reports no radio.
  Every read of the phone runs in `StatusReadJob` and no surface waits on Bluetooth (the fourth amendment: the
  watch app is a cached process, frozen ten seconds after a complication or tile poke, and dozing defers the asks);
  past the six-minute window a reading is drawn aged (`StatusText.shown`), and only a failed read says out of reach.
  A raised wrist does not end Doze, so while a complication is active `StatusAlarm` reads every five minutes on an
  allow-while-idle alarm; don't lean on `UPDATE_PERIOD_SECONDS` for freshness.
  **The Knit watch face** (`:watchface`, built beside `:wear` under the same switch) is a resource-only Watch Face
  Format v4 face — READ ADR 2026-10.3y2s before touching it, `RingComplicationService` or `RingCode`. Its bezel ring
  reads one text complication whose 97-character code (`RingCode`, pinned by `RingCodeTest`) is the face's only
  contract with `:wear`; `watchface.xml` is generated by `scripts/gen-watchface.py`, never hand-edited, and
  `scripts/check-watchface.sh` validates it (freshness, no dex, Google's v4 validator and memory budget). Its
  analog sibling, the monogram (`:watchface-monogram`, the icon's K whose arms are the hands, lit at 1:22), is the
  ADR's amendment and comes out of the same generator and check.
- **When touching `ui/yourmesh/`, `mesh/ContributionLedger`, `data/settings/ContributionJournal`,
  `data/peer/MetPeer*`, `ForwardDao.observeCarriedForOthers`, or the `onRelayed` / `onServed` hooks in
  `MeshRouter` / `ForwardSync`:** READ ADR 2026-09.2v2t. The Your mesh screen's numbers are shown to the user
  as things their phone *did*, so a credit happens only at a hand-off (this phone sent someone else's chat
  frame to ≥ 1 peer), once per frame, never for our own frames or a DM addressed to us; "handed straight to"
  is a live-link send and is never worded "delivered". "Nearby" and "met" both derive from
  `MeshController.neighbors` (ADR 2026-09.2ajk) — don't add a second gate. Counters flush on the 60 s tick,
  never per frame, and nothing here leaves the phone.
- **When touching blob exchange or custody** — `mesh/BlobExchange` and what asks, serves, parks or resumes a blob
  (`CarrierPullPolicy`, `PartialBlobs`, `IncomingFiles`, `mesh/link/FileIntake` / `FeedMeter` / `LandingFiles`,
  `data/AttachmentIdentities`), or what custody keeps and serves (`ForwardStore`, `ForwardSync`, `StoreDigest`,
  `DigestTracker`, `SeenSet`, `PendingInbound`, `KeyExchange`, `data/forward/`): READ `.agents/context/blobs.md`, the
  symbol → ADR routing for the plane (fresh-ask serving, the first ask and the holder query, the carrier's pull and
  the parked ask, progress reports, the progress ring, resume, attachment identity, the carried profile, custody's
  bounds, carry gate, receipts, own frames, the stranger backlog, the digest slot, the room flood). `grep` it for
  every symbol you touch. For implementation work there, DELEGATE (Claude Code: spawn) the `blobs-owner` persona in
  `.agents/personas/blobs-owner.md` with a self-contained brief — the goal, the issue or ADR, whether a wire,
  convergence, schema, lab or iOS change is in scope — then run `blobs-reviewer` on what it wrote (and
  `bluetooth-reviewer` too if a `FramedLink` line moved), apply the findings and relay the trial both name.
- **When touching `data/ImageStrip` or `AttachmentStore.passThrough`:** READ ADR 2026-10.unqn — a picture already
  bounded, upright and no sharper than the q85 re-encode goes out as its own bytes with the metadata stripped, so the
  "no GPS leaves the phone" invariant rests on the strip's allow-list: anything it does not fully understand answers
  null (the long way), and a segment is kept only if a decoder needs it. Tests: `ImageStripTest`,
  `AttachmentStoreIngestTest`.
  **Before touching `ImageStrip.animatedWebp`, `AttachmentStore.animated`, `data/webp/AnimatedFrames` or
  `WebpTranscode.shrinkAnimated`:** READ ADR 2026-10.8hmx — the bytes, not the provider's type, pick the door; an
  animated WebP within 512 px / 200 frames / 1 MiB goes out stripped under the same allow-list rule, anything past
  it and every APNG (Android cannot play one) is composited at the output size into an animated WebP, and
  `identityFor` prefers an animated row so a send flattened before #133 is not reused. Tests: `AnimatedFramesTest`.
- **When touching `data/video/` (`VideoEncodePolicy`, `VideoTranscoder`, `MemoryMp4Muxer`, `MemoryMuxerOutput`,
  `VideoFrames`), `AttachmentStore.ingestVideo`, `VideoInfo` / `ChatContent.attachmentSize`, the `messages.video*`
  columns, `ImageScreeningService.isVideoExplicit` / `screenVideo` / `isPosterExplicit`, or `ui/chat/VideoBubbles`:** READ
  ADR 2026-10.hmfe. A picked video is re-encoded to 480p, 30 fps, about 400 kbps and 61 s by Media3 Transformer into
  memory (never a file, never passed through), in **CBR** (the platform's VBR quality floor raised a 490 kbps ask to
  1.23 Mbps), **not streamable** (a `free` pad was 16 % of the file), with the source's metadata dropped except the
  orientation. Its description and poster ride inside the message; a room post declares its size so js2p holds it.
  A video is its description on a video MIME, tested ahead of the name; a video picked with either button is
  re-encoded, and only an undescribed `.mp4` that arrives (an older build's) stays a file (the amendment).
  The NSFW screen reads the exact frame at a random instant in each twelfth and screens no clip longer than Knit sends
  (the 2026-10-19 amendment). Tests: `VideoEncodePolicyTest`, `MemoryMp4MuxerTest`, `AttachmentStoreVideoTest`; on
  hardware `VideoTranscoderInstrumentedTest` and `VideoScreeningInstrumentedTest` (which also screens a labelled
  folder of real clips pushed to the device; run one test class on one device with `am instrument`, never
  `connectedDebugAndroidTest` with lab phones attached).
- **When touching a presence dot or an online / offline label** (Profile Details, Diagnostics' node
  sections, the Contacts dot): the four evidence tiers live in `ui/Reach.kt` — `Direct` is
  `MeshController.neighbors` (a short-range radio saw the peer's own radio), `Indirect` is
  `MeshController.heardIndirectly` (another phone handed us the peer's fresh, signed frame over the radio mesh —
  `mesh/RelayedPresence.kt`, ADR 2026-10.fw8g; presentation only, never a route), `LongRange` is the long-range
  reach set or a spool scope its peer recently pushed to (`mesh/spool/SpoolPresence.kt`, one function the dot
  reads at `PRESENCE_LINGER_MS` = 15 min and the mesh at `SPOOL_COVER_MS` = 15 min), `Known` is a bare profile row — and both labelled
  surfaces derive from `reachOf` so they cannot disagree. READ ADR 2026-09.2ajk before loosening any tier;
  the Contacts list still draws a binary dot from `neighbors` alone.
- **When touching `identity/PeerLabels`' `stale` rule, `data/LabelAnchors`, `data/LabelFade`, `peers.lastSeenAt`,
  `PeerRepository.noteSeen` / `flushSeen`, or `mesh/HeardFrom`:** READ ADR 2026-10.k8ge (and ADR 058 /
  2026-09.wuqj for the labels themselves). A stranger not heard from for a week *gives way* — still labelled apart,
  starting from its whole-universe token count, but no longer suffixing anyone; anchors (accepted, written-to,
  group co-members, verified, self) never go stale, and a new contact signal goes into `labelAnchors` too
  (`LabelAnchorsTest`). The stamp is buffered from `noteVerified` and banked on the metrics tick, never written on
  the inbound path, and `lastSeenAt` is presentation only — never a presence tier, a route, custody or eviction.
- **When touching `MeshManager.watchReachable`, its `flooded` memo / `refloodKey`, or `PROFILE_REFLOOD_MIN_MS`:**
  READ ADR 2026-09.uc8p. The first-sighting profile flood is the NAN-only key bootstrap; it goes to a peer once
  per (frame id, peer) per `SeenSet.DEFAULT_TTL_MS` — the receivers' own window, so every copy it withholds is
  one they would drop — and a newcomer the 30 s floor skipped is never memoed. Keep `ackSync.onReachable` ahead
  of every throttle (ADR 2026-09.y5f3). Regression: `MeshManagerTest.aLingerFlap…` / `aProfileEditIsReflooded…`.
- **When touching `BlobDao.observeSizes`, `BlobRepository.observeSizes`, or `ChatViewModel`'s `heldHashes` /
  `heldSizes`:** READ ADR 2026-09.fjcw. The chat's one blob-table subscription is keyed to the window's
  attachment hashes plus the staged one (`IN (:hashes)`, primary-key seeks), and an empty ask never builds a
  query — don't bring back the whole-table `observeSizes()` or a second subscriber; Room invalidates per table,
  so either one re-runs on every blob write anywhere while a chat is open. Regression:
  `ChatViewModelTest.blobSizesAreAskedForTheWindowsAttachmentsAndTheStagedOneOnly`.
- **When touching how a Nearby-room post's ✓✓ gets home** — `AckSync`'s ride hold / `RIDE_HOLD_MS`,
  `MeshTransport.coveredByInternet`, `ScopeSync.pushDirect` / `presentPeers`, `MeshRouter.handOn`, or
  `MeshManager.ownProfile`:
  READ ADR 2026-09.y5f3 (aa27's ride hold now has a 60 s deadline; the tick then goes to a spool the author
  was recently seen on — a signed `relay = false` frame pushed direct and accounted, never custodied on the
  acker — else over LoRa's targeted path; every DM-form frame to a spool-present peer stays off the board;
  a node's own profile has one set of bytes per publish stamp) and ADR 2026-09.wkbk (`MeshRouter.handOn` —
  a point-to-point frame addressed to a peer we hold a **live link** to takes that one hop, DM-form chat
  only, which is how a far pocket's tick reaches a board-less author behind the gateway; an acker with no
  board and no spool is still stranded). Then `docs/ENCRYPTED_RECEIPTS_REACTIONS.md`
  §5, spec §9.4 C-9.4-3, and `RoomTickPlanesLabTest` in `mesh/lab/` for the end-to-end shape.
- **When touching `ui/components/Avatar`, `ui/components/GroupAvatar`, `ui/theme/AvatarTint.kt`,
  `data/message/GroupFaces.kt`, `ui/util/ClusterGeometry.kt`, or the notification avatars in
  `notifications/NotificationAvatars`:** READ ADR 2026-09.j8c7. A photo-less avatar's colour is keyed on the
  **node id** (`avatarTintIndex`, pinned by `ColorSchemeTest`), from a static twelve-hue palette that
  `scripts/gen-avatar-palette.py` generates; under Material You `KnitTheme` harmonizes it toward the
  wallpaper's primary (a 15°-capped Oklch hue turn, tone kept), and the shade draws the same slot the same
  way. Pass a name as the key only for a face with no identity behind it. A photo-less **group** is a
  cluster of its other members (`groupFaceIds`: self out, by node id, at most four, at least two — else a
  people glyph on a disc tinted by the group id) laid out by `clusterCells`, which the shade draws from the
  same cells; renderers never re-sort — READ ADR 2026-09.zapp.
- **When touching the end-to-end crypto** — `mesh/crypto/` outside `scope/`, `data/ratchet/`, `mesh/PendingGroupKeys`,
  `IntroSync`'s init answer, or the verify / decrypt / reset / seed / roster paths of `InboundPipeline` and
  `MeshManager`: READ `.agents/context/crypto.md`, the symbol → ADR routing for the plane (envelope and v3, two-phase
  decrypt and lock order, races and era effects, the reset heuristic and what a peer refuses, retention and the
  root tag, group seeds and key requests, the roster, sealed ctls, export, self). `grep` it for every symbol you
  touch. For implementation work there, DELEGATE (Claude Code: spawn) the `crypto-owner` persona in
  `.agents/personas/crypto-owner.md` with a self-contained brief — the goal, the issue or ADR, whether a wire,
  vectors, schema, lab or iOS change is in scope — then run `crypto-reviewer` on what it wrote, apply the findings
  and relay the lab scenario or device trial both name.
- **When touching a group's photo — `groupPhotoDecision` / `photoWins` / `settleArrivedGroupPhoto` in
  `InboundPipeline`, `GroupEntity.photoHash` / `photoShownHash`, `GroupDao.awaitingPhoto` /
  `photoHashesNeedingFetch`, `BlobRepository.dropRefusedGroupPhoto`, or `GroupDetailsViewModel`'s set path:**
  READ ADR 2026-09.nxcq. `photoHash` is the photo this device decided on — stored the moment it is heard, and
  the only one `toGroupInfo` advertises — and `photoShownHash` the one that renders, once its bytes are local
  and screened; every surface reads the shown one, and a frame carrying the shown one brings #108 back. A clock
  tie keeps the held photo. The want is the row (no in-memory map), and a refused photo stays decided and keeps
  its verdict so nothing pulls it again. Regression: `AttachmentLabTest`'s two group-photo-pull scenarios.
- **When touching `data/backup/`, `ui/backup/`, `RestartActivity`, `KnitApplication.onCreate`'s pre-Koin
  block, `MeshManager.finishRestore`, `SeenSet.reopen`, `SettingsKeys.TRANSIENT_PREFIXES`, or adding a table
  or a DataStore key:** READ `docs/BACKUP_FORMAT.md` and ADR 2026-09.6mj7. A backup is one file sealed by a
  recovery key the app shows once and never keeps; custody and both ratchets are never carried
  (`BackupTables`, test-pinned to the schema — a new table must be classified), a restore is staged and
  verified in full before `READY` (both readers on the other side fail destructively), applied by the
  relaunched process before Koin, and finished on the first mesh start by one session reset per peer whose
  session was wiped (DM peers and every group's other members, ADR 2026-09.qerd). A restore is a **move** —
  the mesh has no clone tolerance — and the copy says so. The database copy goes
  through a raw single-connection driver on purpose (`ATTACH` is read-only to SQLite and lands on a pool
  reader; the SQLCipher layer rewrites `BEGIN` to `EXCLUSIVE`) — don't move it onto the Room connection.
- **When touching `mesh/CloneWatch`, the self branch of `InboundPipeline.handleProfile`, the `clone_`
  settings keys, `ui/chatlist/CloneBanner`, the Settings clone row, or `ui/signout/`:** READ ADR
  2026-09.ypcc. One backup restored onto two phones is detected from the **profile stamp alone** — a
  `profile` under our own node id whose `sentAt` is past `SettingsStore.profilePublishedAt` was minted
  elsewhere; chat frames are not evidence (a `DatabaseKey` wipe empties the record they would be judged
  against) — gated on `restorePending`, undone by a dismissal only for frames stamped before it, and
  answered by one `broadcastProfile` per not-visible → visible edge so the other phone sees us too (never
  while lit: no ping-pong). "Sign out here" is `ActivityManager.clearApplicationUserData()` — the whole
  phone, grants included, so the next open is onboarding — not a file list and not the restore trampoline.
  A twin heard only over LoRa is invisible (the plane drops its own node id at ingest). Regression:
  `CloneWatchTest`, `CloneLabTest` (`MeshLab.node(sameIdentityAs)`, `MeshLab.retire`), `SignOutTest`.
- **When touching contact cards, the Add-by-link / share-link flow, deep links (`getknit.app/c`,
  `knit://`), or `mesh/IntroSync`:** READ `docs/CONTACT_CARD.md` (the card layout + golden vectors, the
  intro driver's rules, the assetlinks prerequisite) and `docs/SPOOL_PROTOCOL.md` §3.5 (the pair scope);
  decision record ADR 042. The card is versioned by `v` and additive under the WIRE_COMPAT rules; import
  never sets `verified`.
- **When touching `contacts/ContactRemover`, the profile's Remove contact (`ContactRemoval`),
  `SettingsStore.unaccept`, `IntroSync.cancel` / `MeshController.cancelIntro`, `contactStanding` /
  `groupsAcceptedOnlyThrough` in `ui/contacts/ContactUniverse.kt`, or what `MeshManager.onCommonsMember`
  accepts:** READ ADR 2026-09.adgd. A contact is derived, so removal clears our own signals — a group that
  rested on the peer is accepted first, the accept is cleared last, the DM thread goes — and never the peer
  row, the session, custody or the block list; nothing is sent. A group co-member stays a contact (only
  their own leave shrinks a roster), and a commons member is accepted on first sighting only. Regression:
  `ContactRemoverTest`, `ContactUniverseTest`, `BlockAndRequestLabTest`.
- **When touching `notifications/ConversationShortcuts`, `ConversationShortcutSync`, `ConversationFaces`,
  `ui/OfferedConversations`, `Notifier.retainConversations`, the `ConversationGate` checks in `KnitApp` /
  `NotificationActionReceiver`, or anything that publishes, removes or opens a conversation shortcut:** READ
  ADR 2026-10.jbsa. The launcher's conversation shortcuts are derived from the chat list's universe
  (`visibleConversations`), never hooked per removal path: a pass removes a gone thread's live copy, disables
  a pinned one, enables one that came back and refreshes a changed face by the fingerprint in its extras — and
  never creates one (only a notification's push does; a push stamped after the pass read its inputs wins). The
  messages table is read inside a pass, never watched, and the peer and group tables only for the shortcuts'
  own rows (`ShortcutWatch`, `IN (:ids)`) — never `observeDirectory`, which every profile frame re-runs. A
  route or an inline Reply under a thread the list no longer offers opens the chat list or sends nothing.
  Robolectric's shortcut shadow drops `removeLongLivedShortcuts`: test on `FakeShortcutStore`. Regression:
  `ConversationShortcutSyncTest`, `ConversationShortcutsPlanTest`.
- **When touching relay invites — `getknit.app/r` / `knit://r`, `mesh/spool/RelayInvite`,
  `data/relay/RelayInviteApplier`, `ui/relay/RelayInviteSheet` / `RelayInviteInbox`, the relay row's
  Share / Copy, or the Add-contact preview's relay "Add":** READ `docs/RELAY_INVITE.md` (layout, the
  bearer-link trust rules, golden vectors, the daemon contract) and ADR 2026-09.tmbq. One unsigned link
  carries the relay URL *with* its `?k=` token and the commons secret; it is applied only through the
  sheet (host named, cost stated, the master switch's disclosure folded in), by the one applier both doors
  share — `acceptSpoolConsent()` stays the only consent write (ADR 063), a relay is matched by
  `SpoolUrl.redact`, and a different room secret at the same relay is a rotation.
- **When touching `data/commons/`, `mesh/spool/Commons*`, `ConversationKind.COMMONS`, or the relay row's
  Join/Leave:** READ ADR 2026-09.wx8e and `docs/SPOOL_PROTOCOL.md` §7.4. The commons is a private relay's
  group chat: members' `profile` frames pin them through the ordinary door and make them accepted contacts,
  posts are a non-custodial `commons` frame that lives on the one spool that runs the room and never touches
  the radios (`ScopeSync`'s second door, `InboundPipeline.deliverCommonsPost`), and a post ahead of its
  author's profile is parked, never quarantined. `Conversations.isPublicRoom` is true for it on purpose.
  **Hidden in shipped builds** behind `BuildConfig.COMMONS` (on in debug, off in release, `-Pcommons=`
  overrides): the one seam is the `CommonsStore` the DI hands `MeshManager` and `InternetRelayViewModel`,
  null while dark — don't add a second gate downstream of it; flip the release default when it is introduced.
- **When touching `mesh/crypto/scope/`, `mesh/spool/`, or the spool/internet-relay plane:** READ
  `docs/SPOOL_PROTOCOL.md` (the normative public spec; its §13 vectors are pinned by
  `ScopeVectorTest`/`SpoolRecordsTest` — change them only together), then the `ScopeSync` invariants in
  `.agents/rules/mesh.md`. The client plane carries DM **and group** scopes, off by default, with the
  relay/spool-list editor shipped (`ui/relay/`); the scope-config ctl and Tor are still deferred — CHECK
  `.agents/memory/roadmap.md` before building either, and the spec's Appendix A for what runs today. The plane also carries **attachments**
  (`mesh/spool/ScopeAttachments`, spec §4.5/§6.5/§7.3/§9.5) as a separate object class kept out of the
  scope digest on purpose. A group scope derives from the shared
  **group root** (`GroupKeyPayload.gr`, `mesh/spool/GroupRootPolicy`): any member may mint it, and its
  mint / gossip / adopt / departure-re-mint rules are spec §3.2 — read that before touching them. The
  reference daemon lives in the separate `knit-spool` repo. Before touching `SpoolStatus.connected`,
  `OkHttpSpoolDialer.failureReason`, the `no_hello` / `unreachable` verdicts, `InternetGate.routeChanges`,
  or the chat placeholder's `attachmentWait` line: READ ADR 2026-09.vej5 — connected is a completed hello,
  a route that swallows the socket is `unreachable`, and the chat names the *connected* relays only.
  Before touching `RECONCILE_INTERVAL_MS`, who calls `ScopeSync.onScopeTableChanged`, `RatchetSessions.rootChanges`
  or `IntroSync.onPairsChanged`: READ ADR 2026-09.dcah — the scope table derives on its inputs' events and the
  60 s poll is only the net under the calendar; a hook that fires on an unchanged input is the old poll back.
  Before touching `SpoolPow`, `ScopeSync`'s `stampFor` / `stampDemanded`, or what a request does on `err pow`: READ
  ADR 2026-10.rn9j — a stamp goes out only after a spool asked for one (C-8-4), its first ask is retried at once
  and never parked, and the miner allocates nothing per hash (`SpoolPowTest` pins it).
  Before touching `SpoolHello.ticketGates` / `entGates`, `SpoolConnection.gates` / `uploads`, `SpoolUploads`,
  `ScopeSync`'s `uploadsOn` / `spentUploads` / `LocalPass.LOCKED`, `HeldTicket.pseudonym`, `AttachmentRelay.Locked` or
  `RelayFacts.uploadRooms`: READ ADR 2026-10.8t2y (knit-spool ADR 2026-10.h6pt) — each gate is its own bit of
  `entGates` (C-7.6-30); an attachment of ours on a connection the files gate locks is settled with no `ahave` and no
  `aput` while fetching is untouched; a `budget` spends the ticket's pseudonym per relay host in memory, never in the
  verdict journal; and the sender's "Nearby only" reads `Locked` only when no connected relay takes this phone's
  uploads. The gate reading and the marker ship in every build; getting a ticket and the readouts stay behind
  `ANYWHERE_WIRED` (ADR 2026-10.swvp).
  Before touching a ticket's `am` (`SpoolTicket.am` / `maxChunks`, `SpoolConnection.uploadLimitChunks`,
  `LocalPass.TOO_LARGE`, `ChunkPut`, `RelayFacts.uploadRooms` / `UploadRoom`), free tickets (`mesh/spool/FreeTickets`,
  `TicketRefresher.uploadWanted` / `freeDueAt`, `data/relay/FreeUploads`, `FreeUploadsNotice`, the `spool_free_` keys,
  `free_issuers` / `free_issuer_am`) or `AttachmentStore.fitted`: READ ADR 2026-10.s3ep and `docs/VOICE_CALLS.md` §2.6
  (C-7.6-32 to C-7.6-38) — an attachment over `am` is never `aput` and `size` locks nothing; a free key asks only
  while an upload waits (no 6 h margin, the hour floor kept), only for a relay in use, and never once the person turned
  free tickets off (on by default; the notice informs, s3ep's amendment); it never rides a backup
  or asks for a badge; a photo is fitted at staging, to the hint only with a free key or none.
  Before touching `DeviceProof`, `DeviceKeys` / `DeviceKeyring`, `data/crypto/AndroidDeviceKeystore`,
  `IssuerAnswer.BoundElsewhere`, `TicketRefresher.move`, the `spool_device:` keys or the relay screen's Move it here: READ
  ADR 2026-10.wk3m and `docs/VOICE_CALLS.md` "Device binding" (C-7.6-39 to C-7.6-46) — every customer-key request carries
  a proof signed afresh, a Keystore refusal sends nothing (an unsigned request to a bound account parks it behind the
  issuer's cooldown), no pair is ever made over or deleted on the Keystore's word (the accepted pair, else the oldest,
  signs), and a move is only ever the person's.
- **When creating or modifying anything under `app/src/test/java/app/getknit/knit/mesh/lab/`:** keep
  `scripts/lab-lint.py` clean (a `PostToolUse` hook runs it on every scenario edit, `lab-chaos.sh` before every sweep,
  both CIs as `lab-lint`; a deliberate finding carries `// lab-lint: allow <rule> — <why>`), then RUN
  `scripts/lab-chaos.sh --tests '<Class>'` on the changed classes (or `'<Class>.<method>'` for the changed
  scenarios of a large class) once your edits are in, then ADOPT (Claude Code: spawn) the `mesh-lab-reviewer`
  persona in `.agents/personas/mesh-lab-reviewer.md` before calling the change done — every lab flake has been a
  scenario assuming an order the mesh does not promise. A Stop hook enforces the review; the reviewer reuses your
  sweep (`--reuse`) while the tree has not moved, so sweep last, not between edits. A scenario the sweep names
  `slow` is sitting out a production timer: shorten it through `LabLimits`, never a longer await. Chaos mode is
  the "Chaos mode" bullet in `.agents/context/testing.md`.
- **When creating or modifying a JVM unit test outside the mesh lab** — anything under `app/src/test/` but
  `mesh/lab/`, or `wear/src/test/` (a test class, a rig, a fake): READ the "Unit tests that hold on a slow runner"
  section of `.agents/context/testing.md` while you write it, then ADOPT (Claude Code: spawn) the `unit-test-reviewer`
  persona in `.agents/personas/unit-test-reviewer.md` before calling the change done — every unit-test flake so far
  passed review and local runs, and CI's one-vCPU runner found it. It walks each test for the catalogue's flake shapes
  and for tests that cannot fail, and stress-runs the changed classes (`scripts/unit-stress.sh`). A Stop hook enforces
  the review.
- **When creating or modifying anything under `mesh/bluetooth/` (its `meshtastic/` and `wear/` included),
  `mesh/link/FramedLink.kt`, `mesh/power/ElapsedWait.kt` or `mesh/power/PowerState.kt` (`PowerPolicy`):** ADOPT (Claude Code:
  spawn) the `bluetooth-reviewer` persona in `.agents/personas/bluetooth-reviewer.md` before calling the change
  done — every Bluetooth fix so far was code that passed its JVM tests and broke on a phone. A Stop hook enforces
  the review. A `delay` or `withTimeout*` under `mesh/bluetooth/` either goes through `elapsedWait` or carries a
  `// stretches: <why>` line above it (`BluetoothWaitClockTest`, ADR 2026-10.pj9w's amendment).
- **When creating or modifying anything under `mesh/wifiaware/`, `data/settings/NanAttachJournal.kt` or
  `NanInitiatorJournal.kt`:** ADOPT (Claude Code: spawn) the `wifi-aware-reviewer` persona in
  `.agents/personas/wifi-aware-reviewer.md` before calling the change done — the Aware framework and firmware wedge in
  ways no JVM fake does. A Stop hook enforces the review.
- **When creating or modifying anything under `mesh/lora/` or `mesh/bluetooth/meshtastic/`:** ADOPT (Claude Code:
  spawn) the `lora-reviewer` persona in `.agents/personas/lora-reviewer.md` before calling the change done — every
  LoRa fix so far was code that passed over the fake air and failed on boards in the field. A Stop hook enforces the
  review.
- **When creating or modifying anything under `mesh/crypto/` (outside `scope/`), `data/ratchet/`,
  `mesh/PendingGroupKeys.kt` or `mesh/IntroSync.kt` — or the verify / decrypt / reset / seed / roster paths of
  `InboundPipeline` and `MeshManager`, which the hook cannot see:** ADOPT (Claude Code: spawn) the `crypto-reviewer`
  persona in `.agents/personas/crypto-reviewer.md` before calling the change done — every ratchet fix so far was code
  that passed single-node tests and stranded a pair. A Stop hook enforces the review for the gated paths.
- **When creating or modifying `mesh/BlobExchange.kt`, `BlobStore`, `BlobHash`, `CarrierPullPolicy`, `PartialBlobs`,
  `IncomingFiles`, `ArrivalTicker`, `ForwardStore`, `ForwardSync`, `StoreDigest`, `DigestTracker`, `SeenSet`,
  `PendingInbound`, `KeyExchange`, `mesh/link/FileIntake` / `FeedMeter` / `LandingFiles`, `data/forward/` or
  `data/AttachmentIdentities.kt` — or the blob and custody wiring of `MeshManager` / `InboundPipeline`, which the hook
  cannot see:** ADOPT (Claude Code: spawn) the `blobs-reviewer` persona in `.agents/personas/blobs-reviewer.md` before
  calling the change done — every blob and custody fix so far was code that passed single-node tests and cost the
  mesh a copy per neighbour, a stranded picture or a digest that never converged. A Stop hook enforces the review for
  the gated paths.
- **When adding, renaming or removing a `@Preview`, or touching `app/src/screenshotTest/`, a reference PNG under
  `app/src/screenshotTestDebug/reference/`, or the `composeScreenshot` plugin:** READ the "Compose preview
  screenshot tests" section of `.agents/context/testing.md` and ADR 2026-10.gtmm. Every public `@Preview` in main
  is a test: the wrappers are generated by `scripts/gen-screenshot-tests.py` (`--check` catches a preview without
  one), so after adding or renaming a preview run it, then `updateDebugScreenshotTest`, and review the PNGs —
  never hand-edit either. The render is one frame under a pinned UTC/en-US; the plugin is alpha by a recorded
  exception.
- **When writing or running tests, or checking accessibility:** READ `.agents/context/testing.md` (unit +
  Robolectric Room + the **mesh-in-a-box** multi-node JVM scenarios in `mesh/lab/` + seeded UI / FTL +
  black-box UIAutomator + the accessibility/ATF suite that mirrors the Play pre-launch report). A change to
  what two nodes exchange — a new ctl frame, a custody rule, a roster or key path — gets a `mesh/lab/`
  scenario ending in `assertConverged`, not only a single-SUT test against mocked repos.
- **When driving the app on a device:** obey `.agents/rules/devices.md` first, then RUN the `debug-bridge`
  skill (`scripts/bridge.sh`, the send→verify loop, which oracle answers what); per-action reference in
  `.agents/context/debug-bridge.md`. A new bridge action goes in the receiver's `when`, the debug manifest's
  filter, that file and the skill's index.
- **When delegating to an owner persona, spawning a reviewer persona, or relaying a reviewer's findings:** obey
  `.agents/rules/review-rounds.md`. The subsystem reviewer reviews the plan before the owner writes (the design
  pass). Subsystem reviewers go before test reviewers, and the Stop hook asks for them in that order. Every finding is
  Blocking or Non-blocking, and only a blocking one opens another round. After a stamp, a re-review is a follow-up
  pass over the delta the gate writes. Two rounds in a row that each bring a new blocking finding mean the design goes
  back to the maintainer.
- **Before an architectural choice:** CONSULT `.agents/memory/decisions.md` — a generated router table
  over one-file-per-decision ADRs in `.agents/memory/decisions/`; open the files whose row matches, don't
  work from the titles. For what's deliberately deferred, CHECK `.agents/memory/roadmap.md`.
- **For maintainer-only workflows a public clone doesn't include** (release testing on physical devices,
  soak/convergence trials, store/marketing capture — and more over time): a local, gitignored **`.private/`
  overlay** may be present. If `.private/AGENTS.md` exists, load it as a nested router (nearest-wins); it is
  absent from public clones.

## Capabilities

- RUN skills in `.agents/skills/` — `kotlin-patterns` (idiomatic Kotlin), `material-3` (Compose M3),
  `debug-bridge` (drive and verify the app on a device), and `dotagents-standard` (maintain this AGENTS.md
  router / `.agents/` layout). Skills are vendored in
  the repo (real files under `.agents/skills/`, surfaced to Claude Code via `.claude/skills/` symlinks),
  so cloners get them without any global install.
- SPAWN a reviewer persona from `.agents/personas/` when its routing bullet above says so; each hunts one class of
  defect from a catalogue of the ones this repo already shipped and fixed, and never edits. A new reviewer is a
  persona, its symlink in `.claude/agents/`, a line in `.claude/hooks/review-gate.conf` (its stage and the paths
  its Stop hook gate watches) and a routing bullet here. A contributor's GitHub pull request gets the same reviewers
  in CI when a maintainer applies the `review-gates` label: `.github/workflows/review-gates.yml` runs their checks
  (`scripts/review-checks.sh`) with no secrets, then `review-gates-report.yml` runs them read-only under the default
  branch's personas and posts one comment. A reviewer whose checks step changes needs its line in that script too.
- DELEGATE implementation in a subsystem to its owner persona from `.agents/personas/` when its routing bullet says
  so (today `bluetooth-owner`, `wifi-aware-owner`, `lora-owner`, `crypto-owner` and `blobs-owner`), after its
  reviewer's design pass on the brief (`.agents/rules/review-rounds.md`). An owner writes and tests code inside its
  scope, never commits, and reports what its subsystem's reviewer then checks — the reviewer is never the agent that
  wrote the change. A new owner is a persona and its reviewer, their symlinks in `.claude/agents/`, a
  `.agents/context/<subsystem>.md` both read (the subsystem's routing, moved out of this file), a review-gate line
  and one routing bullet here.
- ADD a durable decision with `python3 scripts/adr.py new "<title>" --topics a,b`, write the body it
  scaffolds, then `python3 scripts/adr.py index`. Never hand-edit `.agents/memory/decisions.md` (generated)
  and never pick an ADR number: ids are minted `YYYY-MM.suffix` so parallel worktrees can't collide, while
  `001`-`067` keep their sequence forever. Update `.agents/memory/roadmap.md` as deferred scope ships.
- If a task needs context this router doesn't point to, treat the missing routing as a bug — do the work,
  then add the routing line here.
