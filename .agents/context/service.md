# Mesh service and power — which decision governs which symbol

The symbol → ADR routing for the foreground service that runs the mesh, its pause, its foreground-service types, the
heal basket, low-power mode and the lonely cadence both radios share.

Each entry names the symbols that trigger it and the ADRs to read (`.agents/memory/decisions.md` maps an id to its
file, amendments included). An entry is an index into its ADRs, not a substitute: where they disagree, the ADR wins
and the entry is fixed. `grep` this file for every symbol you touch; `AGENTS.md` routes here instead of carrying these
lines.

## Entries

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
  The mode is **not offered for now** (`LOW_POWER_OFFERED`, d44b's 2026-10-24 amendment): its row is hidden and its
  posture inactive, and the visible control is Settings › Wi-Fi Aware (`AwareSwitch`, ADR 2026-10.2t49), whose dormancy
  and switch route through `.agents/context/wifi-aware.md`.
- **When touching `mesh/power/PowerPolicy` (`idleAfterScan` / `lonelyRelaxed` / the `LONELY_*` constants),
  `mesh/wifiaware/NanLonelyPolicy`, or the cadence in `BluetoothMeshTransport.scanLoop`:** READ ADR
  2026-09.kb68 and ADR 2026-09.w3xk. The shared rule is "alone ≥ 3 min, not charging"; each radio picks its
  relaxed cadence — the BLE scan opens a screen-on node's gap to 60 s (12 s BALANCED window kept), the NAN
  re-arm keeps a screen-on node aggressive on purpose (a 30 s tick leaves ICM lit) — so don't flip one radio's
  interactive case without the other's device trial. Charging never relaxes. Tests: `PowerPolicyTest`,
  `NanLonelyPolicyTest`.
