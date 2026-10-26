# Wi-Fi Aware plane — which decision governs which symbol

The symbol → ADR routing for Knit's Wi-Fi Aware (NAN) mesh transport: `mesh/wifiaware/` (`WifiAwareTransport` and
its pure policies), with the two journals it keeps in `data/settings/` (`NanAttachJournal`, `NanInitiatorJournal`).
The `wifi-aware-owner` reads it before it writes and the `wifi-aware-reviewer` before it judges (both in
`.agents/personas/`); `AGENTS.md` points here instead of carrying these lines. Each entry names the symbols that
trigger it, the ADRs to read (`.agents/memory/decisions.md` maps an id to its file, amendments included) and, where
there is one, the section of `.agents/context/mesh-transport.md` that explains the mechanism. An entry is an index
into its ADRs, not a substitute: where they disagree, the ADR wins and the entry is fixed. Never toggle Wi-Fi on a lab
phone to test the plane.

Wi-Fi Aware-adjacent routing that lives in another subsystem's file under `.agents/context/` (mostly `service.md`),
because that subsystem shares it: the lonely cadence
(`PowerPolicy`, `NanLonelyPolicy`, ADR 2026-09.kb68 / 2026-09.w3xk, shared with the BLE scan), the foreground type and
`TransportHealth.ForegroundOnly` / `NanSessionFault` (`.agents/context/service.md`'s ADR 2026-09.535d entry), the heal
basket that calls `transport.heal()` (`MeshManager.heal`, ADR 2026-09.6st4), the Wi-Fi Direct hand-over that calls
`MeshTransport.pause`/`resume` (`transfer/`, `.agents/context/direct-transfer.md`), the blob exchange's reads of the
link (`BlobExchange`, `fileInFlightTo`, 4tx5 and on) and the field log's `field.record(…)` calls (ADR 2026-10.a239).
The framework-internal evidence behind most of this file — the request cache, the 0-NDP ghost, the per-role NDI —
is in `docs/NAN_CONCURRENCY_REAUDIT.md` and `docs/DIGEST_PULL_REATTACH.md`.

## Entries

- **When touching `startResponder` / `stopResponder` / `recycleResponder`, `NanServePolicy`, `beginConnect` /
  `beginAccept` / `anyLinkActivity`, `responderPinsNdi`, `initiateTo`, `driveSync`, `SETTLE_MS`, the quiescence
  constants (`QUIESCENCE_MS`, `RESPONDER_QUIESCENCE_MS`, `RESPONDER_MAX_HOLD_MS`), `INITIATOR_RELEASE_GRACE_MS`, or
  `sessionCycleWithSettle` / `SESSION_CYCLE_SETTLE_MS`:** READ ADR 004 and the "Per-peer responders" and "One NAN data
  interface" sections of `.agents/context/mesh-transport.md`. One accept-any responder, built from the publish
  session with no peer handle, multiplexes inbound NDPs on the one NDI (capped by `NanServePolicy`, one data path
  reserved for our own outbound); each initiate is its own network and needs its own NDI, so outbound stays single
  and the larger id initiates. The responder request is unregistered **only while an NDP it serves is alive** (an
  EOF-driven end proves it; the framework then tears down cleanly) — unregistered at zero NDPs it becomes the
  `role=1 state=104` ghost that pins the NDI until NAN goes down. A pin the recycle missed is cleared by a session
  cycle (the last client's close is NAN-down, which wipes the request cache) after a fixed 2 s settle, never by
  waiting on the availability broadcast, which lags seconds on a dark screen. Only subscribe is ever re-armed; every
  `requestNetwork` but the responder's takes the timeout overload. The whole lifecycle runs on the `handler` thread,
  each attach stamped by `attachGen` so a stale callback closes what it was about to create.
- **When touching `NanConnectPolicy` (`dropHandleOnFastFail`, the fail streak and its backoff), `FAST_FAIL_MS`,
  `HANDSHAKE_TIMEOUT_MS`, `failConnect`, or the `discovered` map:** READ ADR 2026-09.9dnk (its handle half). A first
  fast `onUnavailable` is a stale handle (drop it, re-arm); a second in a row keeps the handle and backs off 2 → 60 s,
  because the fresh handle failing too means the responder, not the handle — dropping it drives the
  discover → initiate → fail livelock. A `PeerHandle` is valid only on the session it was learned on: **any path that
  closes `subscribeSession` clears `discovered` with it** (`rearmSubscribe`, `sessionCycleWithSettle`, the NAN-down
  branch, `stop`), or `initiateTo` pairs a fresh session with a dead handle and `needsRediscovery` reads the stale
  entry as "held" and suppresses its own repair. As of 2026-10-09 `reattach`, `onSessionDead`,
  `onAwareSessionTerminated`, `pause` and `onSessionConfigFailed` drop it without clearing the map: an open gap
  against the ADR (#144), not a precedent.
- **When touching `checkWedge`, `NanWatchdogPolicy`, `RESPONDER_REFRESH_MS` / `WEDGE_RESTART_MS` /
  `MAX_RESPONDER_REFRESHES` / `WEDGE_CHECK_MS` / `REATTACH_COOLDOWN_MS`, `lastReattachAt`, `syncOwedSince`,
  `anySyncOwed` / `anyReachableSyncOwed` / `corroboratedPresent`, `onForeignReachable` / `onForeignNear` /
  `foreignNear`, `NanSyncPolicy.corroborated` / `evidence`, `MeshTransport.near`, `Action.DeferRestart` (`decide`'s
  `visible` / `callLive`), `NanWatchdogPolicy.Tier2Notes`, or the Tier-2 kill's
  `canReclaimForegroundService` gate:** READ ADR 2026-09.9dnk (its 2026-10-20 amendment) and ADR 043. The watchdog's
  clock is an **owed episode** (a sync owed to a reachable peer with no link forming), never time since the last
  link. Tier 1 (45 s) is a session cycle, uncorroborated so the smallest id — everyone's pure responder — heals itself
  with Bluetooth dark, capped at three per episode so Tier 2 stays reachable; Tier 2 (180 s) is a process kill, only
  for a peer linked here or that another short-range plane hears **near** (`near`, never `reachable`: a Coded advert
  at −101 dBm reaches far past an NDP), never while Knit is in use (UI on screen or a call running: `DeferRestart`,
  clock and count kept), and only when the service can come back (043) — so never on a non-exempt API 31+ phone, by
  design. Neither deferral line may say `NAN data plane wedged` (the soak poller counts it as a kill); each logs once
  per episode through `Tier2Notes`. The cap is right
  only while it is spent before `WEDGE_RESTART_MS`: `NanWatchdogPolicyTest.tier1CannotCycleForeverAndTier2IsReached`
  walks the real cadence.
- **When touching `NanSyncPolicy` / `PeerFacts`, `syncWanted` / `digestSyncWanted` / `bulkSyncWanted`,
  `BulkWantTracker`, `expectBulkTransfer`, `BULK_FRESH_MS`, `suppressDataPath`, `initiateOwed` /
  `initiateOwedToReachable`, `needsRediscovery`, `needsIcmRelight` or `rediscoverDelayMs`:** READ the "Steady-state
  digest parity + BLE suppression" section of `.agents/context/mesh-transport.md` (and ADR 2026-09.m8kc for the
  initiator hold that lives in the same two gates). The bulk term reaches **only** the admission sites (`driveSync`,
  `initiateOwed*`); the watchdog's owed clock, the subscribe re-arm and the ICM relight read the digest-pure gate,
  because a pending image always has Bluetooth carrying it and is never an outage to heal. A BLE-linked peer is
  suppressed from every NDP decision. Marks need a fresh sighting (45 s), never the 150 s linger.
- **When touching `NanHopTable`, `emitFastWire`, `dispatchFramed`, `noteReachable`, `pruneAbsentPeers`,
  `REACHABLE_LINGER_MS`, `cueTarget`, `NanCueCodec` (the SSI `|d` digest segment), `icmKeepalive` /
  `ICM_REARM_COOLDOWN_MS`, `sendCoord`'s framing, or `shouldFastSend` on this plane:** READ ADR 061, ADR 030, ADR 060
  and ADR 2026-09.7463, then the coordination-plane bullet of `.agents/context/mesh-transport.md`. A coordination
  frame is a sighting of the **hop** — the (session, `PeerHandle`) it arrived on, named by the last cue there — never
  of `RelayEnvelope.senderId`, its author; an unnamed handle is no sighting. A peer with no link and no sighting for
  150 s is reaped on the heartbeat (a best-effort cue to a gone peer never fails). ICM is kept lit by
  `updatePublish(sameConfig)`, which refreshes the framework's 30 s instant-mode clock without the wedge-prone
  subscribe re-arm, demand-gated so a converged mesh does none. Sealed DM-form chat rides `fastSend`, **targeted**,
  never fanned. Framing is per peer by its advert's capability bits; the legacy `0x01` path stays for older builds.
- **When touching `NanIcmPolicy`, `noteIcmVerdict` / `icmVerdict` / `refreshIcm`, `maybePushIcmBand`, `followAudio`
  (`audioStreaming`), `fiveGhzRules`, the post-cure window (`postCureCycledAt` / `postCureSince`,
  `sessionCycleWithSettle`'s `postCure`, `POST_CURE_ATTACH_WITHIN_MS`), the Wi-Fi watch's frequency map
  (`onCapabilitiesChanged` on `wifiWatch`, `STA_SETTLE_MS`), `FieldEvent.NanIcm` / `NanIcmLit`, or `…debug.NANICM
  --es band`:** READ ADR 2026-10.486r (and kb68's 2026-10-24 amendment). One verdict gives every ICM-enabled config
  its allowed, band and reason, and both builders and every `updatePublish` read it: off while Bluetooth audio streams
  and while lonely (except the 30 s after a demand-driven Tier-1 cure), 2.4 GHz on a STA on 2437, 5 GHz on a STA on
  5745 (or 5220 with 5745 unusable) or with no Wi-Fi network — those two **debug builds only** (`fiveGhzRules`) until
  the cross-band delivery trial passes, since a 5 GHz phone shares only the 2.4 GHz windows with every 2.4 GHz peer —
  else 2.4 GHz. The verdict never decides *when* ICM is lit, except that the relight obeys an off verdict. Off pushes
  nothing; a band change pushes one `updatePublish` per demand-lit window, never under `icmKeepaliveBroken` or a live
  link. The frequency map lives in the callback object's own callbacks, never in `onStaLost` / `onStaAvailable`, and
  an unregistered or unsettled watch is unknown, never "no Wi-Fi". Tests: `NanIcmPolicyTest`.
- **When touching `NanDormancyPolicy`, `dormancyTick` / `goDormant` / `wake` / `startDuty` / `beginDutyWindow` /
  `onCameOnScreen` / `probeActive` / `lastWindowStartedAt` / `PROBE_FLOOR_MS`, `refreshAwareEvidence`,
  `MeshTransport.dormant` / `seesNewcomers` / `awareCandidatesNear` / `awareCandidatesHeard`, `onForeignAwareCandidates`,
  `TransportStatus.dormant`, `AwareSwitch`, `awareSwitchedOff` / `awareWontRun`, `LOW_POWER_OFFERED`, or
  `…debug.NANDORM` / `AWARE`:** READ ADR 2026-10.2t49 (and d44b's 2026-10-24 amendment). A started plane with nothing to
  do and no Aware candidate heard over Bluetooth sleeps after 3 min and wakes on a candidate near, a bulk transfer, a
  blind Bluetooth plane or a duty window that finds someone — never on `heal()`, and never on the app coming on screen,
  which instead runs one probe (`NanDormancyPolicy.probe`, `onCameOnScreen`, the ADR's 2026-10-26 amendment): a duty
  window now, dormant between duties only (a candidate already near wakes `SIGHTING` instead, `nearWhileDormant`), floored `PROBE_FLOOR_MS` (5 min) from the later of `lastWindowStartedAt` and
  `dormantClosedAt`, and dropped (never armed) unless the attach budget allows it now. A probe is a duty window in every
  way and closes `DUTY_EMPTY` (never a close reason of its own: `closeStands` would refuse it); it wakes as `PROBE`,
  logs `aware duty attach: probe …`, and `probeActive` is cleared wherever `dutyActive` is. The dormancy close is its own:
  it refunds nothing of the attach budget (ADR 055), records no `nan_cycle`, and a wake re-attaches only after its
  settle. Dormant reads Healthy unless real evidence (Wi-Fi off, or `isAvailable` false 30 s outside the close's beat)
  says otherwise (`radioOff`); deep idle alone never does, but it sets the advert's "won't run" (`wontRun`: that
  evidence, deep idle or an abandoned attach — "don't count on my Aware right now"), re-read on the idle broadcast
  through a receiver of its own, never `handleAvailabilityChanged` (the ADR's 2026-10-24 amendment). Without a Bluetooth plane that sees newcomers it never sleeps. Tests: `NanDormancyPolicyTest`,
  `CompositeMeshTransportTest`, `AwareSwitchTest`.
- **When touching `attach()`, `NanAttachPolicy`, `NanAttachJournal`, `attachFailStreak` / `attachFailTotal` /
  `attachAbandoned` / `attachStartedAt`, the availability receiver or `handleAvailabilityChanged`, or
  `…debug.NANFAIL` / `NANSTORM`:** READ ADR 052 and ADR 055. Each failed attach strands two binder objects in
  `system_server`, and AMS kills the uid at its watermark with no trace (getknit/Knit#9), so the retry budget is a
  leak budget with three independent bounds: the refundable streak (backoff to 30 min, give-up), a 3 s floor on
  `mgr.attach` that nothing refunds, and a 200-failure lifetime cap refunded only by an attach that succeeds. The
  streak is refunded only by a genuine `false→true` availability edge (a refusing chipset re-broadcasts `true` after
  every refusal), never by `heal()`. Giving up is journaled by build + `Build.FINGERPRINT`.
- **When touching the responder's `onUnavailable`, `refileResponder`, `NanResponderPolicy`, or what refunds
  `responderRefusals` / `responderCycles` in `WifiAwareTransport`:** READ ADR 2026-09.bgk3. A verdict with a
  link, handshake or accept of ours live is the documented knock refusal — re-filed after the floor, never
  counted; a verdict with the interface free is about the request — counted, backed off, and at five in a row
  given up for a session cycle, three per episode, refunded only by the responder's `onAvailable` (never a
  fresh session or the availability edge: the cycle produces both). The tests are in `NanResponderPolicyTest`.
- **When touching `NanInitiatorPolicy`, the `TRANSPORT_WIFI` watch in `WifiAwareTransport` (`onStaLost` /
  `onStaAvailable`), `initiatorHeld` / `releaseInitiatorHold`, `NanInitiatorJournal`, or what `digestSyncWanted`
  / `bulkSyncWanted` gate on:** READ ADR 2026-09.m8kc. A Wi-Fi blip (lost → available in 15 s) within two minutes
  *after* an unlinked initiate of ours is a strike; three hold the initiator role — the responder, discovery,
  cues and the fast plane keep running — refunded only by an initiator link, the user's Try again, or the
  build+ROM stamp, never by `stop`, `heal`, a session cycle or the Aware edge; one probe initiate a day on the
  wall clock. The hold lives in those two gates so the wedge watchdog never counts a held peer as owed (Tier-2 is
  a process kill and the hold is journaled): never read `reconcileWanted` / `bulkWanted.isWanted` around them.
  Not a `NanConnectPolicy` streak. Tests: `NanInitiatorPolicyTest`.
- **When touching `NanMessagePlanePolicy`, `checkMessagePlane`, `sendCoord` / `msgInFlight`, the
  `onMessageSendSucceeded` / `onMessageSendFailed` callbacks, or `…debug.NANMSG`:** READ ADR 2026-09.jjhg. The
  coordination plane (cues + fast path) freezes after a burst while discovery keeps working — a blocked framework
  send queue, or a firmware that fails every follow-up — and the one cure is a NAN restart, which our session
  cycle is because Knit is the sole Aware client. The watchdog reads acks, not health: a send unanswered for a
  watchdog tick with no ack since, or two heartbeats of failures with zero acks *and a fresh sighting*, cycles the
  session under Tier 1's budget (shared `lastReattachAt`, 3 per episode, never with a live link); the episode ends
  only on an ack after it began — never on the cycle or on its own NAN-down beat (that refund is the 9dnk
  livelock). A send on a closed session gets no callback ever, so it is never recorded as in flight. Tests:
  `NanMessagePlanePolicyTest`; on hardware the soak's `nan-outbound-dead` rule.
- **When touching `onSessionDead`, the `runCatching` around `publish` / `subscribe` / `updatePublish`,
  `onAwareSessionTerminated`, `reattach`, or `offScreenBlocked`:** READ ADR 2026-09.535d (the `MeshService` entries in
  `.agents/context/service.md`) and the "API 29–32" section of `.agents/context/mesh-transport.md`. A session call can throw when the
  framework dropped our client without telling us (`invalid uid+clientId mapping`): that is the terminate we missed,
  and `onSessionDead` mirrors `onAwareSessionTerminated`'s cleanup. A location refusal on 29–32 is not a dead session
  (`NanSessionFault.classify`): hold it as `ForegroundOnly`, tear nothing down, retry on `heal()`. `reattach` is
  single-flight and never drops a live sync.

## Adding an entry

A new decision about this plane gets a bullet here — the symbols that trigger it, the ADR, and the invariant in a
sentence or two — never a bullet in `AGENTS.md`. A symbol listed nowhere is not exempt: `WifiAwareTransport`'s field
comments carry the incident behind most of its state (`attachGen`, `attachFailTotal`, `lastAvailable`,
`responderRefusals`).
