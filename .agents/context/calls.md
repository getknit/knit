# Voice calls — which decision governs which symbol

The symbol → ADR routing for live calls, mesh (walkie) calls, speech-to-text and the TURN/STUN and spool-ticket
machinery they lean on: `call/`, `ui/call/`, `di/CallWiring`, `app/src/calls/`, `app/src/liveCalls/`, `app/src/stt/`
and the call flags. The mechanism lives in `docs/VOICE_CALLS.md`.

Each entry names the symbols that trigger it and the ADRs to read (`.agents/memory/decisions.md` maps an id to its
file, amendments included). An entry is an index into its ADRs, not a substitute: where they disagree, the ADR wins
and the entry is fixed. `grep` this file for every symbol you touch; `AGENTS.md` routes here instead of carrying these
lines.

## Entries

- **When touching `call/`, `MessageContent.CTL_CALL` / `cl`, `CallPayload`, `Protocol.CAP_CALL`,
  `MeshManager.extraCapabilities`, the spool's `turnreq`/`turncred` (`SpoolConnection.turnCredential`,
  `ScopeSync.turnCredentials`), `di/CallWiring`, `data/settings/CallTombStore`, `SettingsStore.callsRelayAll`,
  `ui/call/`, `app/src/calls/`, `app/src/liveCalls/`, `app/src/stt/`, `di/AnywhereWiring` or the `liveCalls` /
  `meshCalls` / `stt` / `knitAnywhere` flags in `app/build.gradle.kts`:** READ `docs/VOICE_CALLS.md`, ADR 2026-10.3tee
  (its 2026-10-07 amendment is this branch's minimal build; its 2026-10-14 one is the first flag) and ADR 2026-10.swvp
  (the four flags; its 2026-10-22 amendment ships three). Behind four flags, each overridden by its own `-P` (`-Pcalls=`
  sets both call kinds): live calls, mesh calls and Knit Anywhere are on in every build since 2.10.0-beta.0, speech is
  on in debug and off in release. Each is read once in `di/`: `LIVE_CALLS_WIRED` (`BuildConfig.LIVE_CALLS`),
  `WALKIE_WIRED` (`BuildConfig.MESH_CALLS`), `STT_WIRED` (`BuildConfig.STT`, only with a call kind) and
  `ANYWHERE_WIRED` (`BuildConfig.KNIT_ANYWHERE`); `CALLS_WIRED` is either call kind. WebRTC, core-telecom and Moonshine
  are `compileOnly`; a build packages WebRTC and `app/src/liveCalls/keepRules` only with live calls, core-telecom and
  `app/src/calls/AndroidManifest.xml` with either kind, and Moonshine with `app/src/stt/` only with speech — so release
  ships WebRTC (a `THIRD-PARTY-NOTICES.md` row) and core-telecom but no speech runtime. Never make any of the three
  `implementation`: a `-P` that turns a kind off must still drop its library. A kind's or speech's own definitions sit behind its constant in `di/CallWiring` (and every lookup of
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
  `ui/call/WalkieTalk` / `TalkRecorder` / `TalkPlayer` / `call/TalkCapture` or `BuildConfig.MESH_CALLS`:** READ ADR 2026-10.79yb and
  `docs/VOICE_CALLS.md` §3.11 — a call with no live path is a media-less walkie session whose bursts stream as Opus in
  unsigned, compactly sealed `talk` frames nobody custodies, sized for Coded PHY (a pin in `TalkSealTest`), so nothing
  lands in the thread; a chunk stays on the radios while `TalkRadioPaths` holds fresh, link-bound evidence of a path,
  and is cast beside them otherwise or while the listener's per-burst reports say they lose it (C-3.11-10, the twelfth
  amendment; never `heardIndirectly`); a live call drops to one, without a frame, only when both OFFER and ANSWER carried the walkie
  bit (parked since 2026-10-09: `CallManager.dropToWalkie` is off, so a lost live call ends `no_route`); the flag is read once, as `CallWiring.WALKIE_WIRED`, and needs no live calls.
  **Before touching `call/CaptureHealth` (`CaptureProfile`, `CapturePolicy`, `CaptureWatch`), `ui/call/CaptureTap`,
  the audio module in `WebRtcCallMedia`, `call/TalkCapture` or the `call_capture_` keys:** READ ADR 2026-10.kw29 (and
  79yb's thirteenth amendment: the walkie's phone microphone follows the same profile) and `docs/VOICE_CALLS.md`
  C-3.13-12/13 — a generic image (and a phone where a call went silent) records calls from `VOICE_RECOGNITION` with
  WebRTC's own canceller, the module is rebuilt per profile between calls only, and the tap watches every 10 ms buffer
  and moves a call whose path hands it only zeros onto its own recorder mid-call.
  **Before touching `call/OverridingTurnSource`, `CustomIce` / `OwnIceServers` / `TurnUrls`, `call/IceProbe`
  (`WebRtcCallMedia.probe`), `data/settings/OwnIceStore`, the `calls_stun_` / `calls_turn_` keys or `ui/callsettings/`:**
  READ ADR 2026-10.mr88 and `docs/VOICE_CALLS.md` §3.14 — the person's own STUN and TURN each replace the spools' for
  that kind (an own TURN sends no `turnreq`), a stored value is re-validated on every read, the password is never
  logged, and the Test opens no microphone and never runs under a call. Debug builds only for now (`OWN_ICE_WIRED`, the
  ADR's 2026-10-20 amendment): elsewhere no server row, no Test and no `OverridingTurnSource`.
  **Before touching `call/TurnRanking`, `TurnMeasure`, `StunBinding`, `ui/call/UdpTurnProbe`, `mesh/spool/TurnHosts`,
  `SpoolHello.turnHosts`, `SpoolTurnReq.host`, `ScopeSync.turnCredentialFor` / `onTurnHosts`, `CallManager.oneServer` /
  `afterRefusal`, `MediaEvent.TurnRefused` or `…debug.CALL --es op turnpin`:** READ ADR 2026-10.pfzn and
  `docs/SPOOL_PROTOCOL.md` §7.6's "Several TURN servers" — a call hands libwebrtc one TURN server, the one its spools'
  `turnHosts` rank best by a STUN Binding probe that runs on a network change or a new entry only (never at call time,
  never with no target, no timer); an entry is derived exactly as knit-spool's `TurnUris` derives it; a log names a
  server by its slot, never its host; and a refusal moves a side to the next server only before its OFFER or ANSWER
  leaves, because libwebrtc reads a session's servers once.
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
  live call's peer, out as text while it plays, behind `BuildConfig.STT` (`STT_WIRED`) and never on the wire, the sender's own "You" line in debug builds only (`WALKIE_OWN_LINES`) (a live call's streams
  always split at pauses, stop 8 s behind, and this phone's own voice is never captioned); the model (Moonshine v2 Small Streaming alone, through
  Moonshine's own runtime, called by its `JNI` class alone and taken without its transitive dependencies) is a
  tap-to-download from getknit.app (the walkie card, whose offer can be dismissed for good, or the Calls screen, held
  past a hang-up by a `dataSync` service; the Calls screen's switch turns transcripts off), pinned by size and SHA-256 (never hand-edit the pins: the script derives them),
  fetched with `Network.openConnection` on the gate's network, not okhttp; each burst is one stream fed as the audio
  comes, looked at on `PassGate`'s cadence, one model call at a time, and every stream call holds the lease. A chat voice note
  is written out only on the person's tap, kept on the row, never during a call, and never on the wire (ADR 2026-10.am8d).
