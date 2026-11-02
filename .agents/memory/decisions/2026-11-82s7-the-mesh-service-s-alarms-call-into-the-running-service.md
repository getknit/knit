---
id: "2026-11.82s7"
slug: the-mesh-service-s-alarms-call-into-the-running-service
title: "The mesh service's alarms call into the running service, never start it"
date: 2026-11-01
topics: [service, power, reliability]
---

# ADR 2026-11.82s7 — The mesh service's alarms call into the running service, never start it

Status: Accepted (2026-11-01). Amends ADR 043 (its "Not covered" heartbeat paragraph) and ADR 2026-09.wz99 (the
windowed resume alarm).

**What was observed.** Three Play issues, six ANRs from at least two users, all realme Note 60 / Note 60x
(Unisoc), all Android 16, versionCodes 24 to 33: "Executing service app.getknit.knit/.mesh.MeshService", cause
`nativePollOnce` or "[libc.so] read". GitLab #248. The one trace (`b6fb42f0`, 2.10.0) is a debuggerd native dump
whose main-thread stack is a copy of the `perfetto_hprof_` listener's, so Play's cause is an artifact. What the
dump does show: a long-lived process born for the service (no RenderThread, tids wrapped), idle except one worker
inside a Room write running the retention deletes that only the heal basket or `MeshManager.start` run. An alarm
start had just been run, after its 20 s had gone. It was not the graph build on the main thread (ADR 2026-09.vztn
fixed that in 2.7.0) and not a slow `onStartCommand`: the heal only signals the transports and launches a coroutine.
The likeliest reading is a vendor freezer holding a foreground-service process. The trace cannot prove it.

**Why the alarms were exposed.** The 15-minute heartbeat (`setInexactRepeating` + `PendingIntent.getService`,
`ACTION_HEAL`) and the windowed resume alarm (`setWindow` + `getService`, `ACTION_RESUME`) were each a
`startService` into the running service. AMS arms its 20 s `SERVICE_TIMEOUT` against every such start, covering
`onStartCommand` on the main thread. The heartbeat alone opened about 96 of these windows a day, nearly all in the
background, where a freezer is most likely to be holding the process.

**What changed.** The heartbeat and the windowed resume are `AlarmManager.OnAlarmListener` alarms called back on
the main looper (`MeshService.alarmHandler`). A listener delivery carries no ANR timer: AlarmManagerService logs a
listener that has not completed within 5 s and moves on. A frozen process takes the callback when it is thawed.

- **The heartbeat** is a one-shot `setWindow` with the window `setInexactRepeating` gave it (0.75 × 15 min),
  re-armed first thing in every tick (`onHeartbeat`). The next trigger keeps the nominal grid (`nextHeartbeatAt`),
  so a late delivery does not stretch the cadence below the floor ADR 2026-09.6st4 counts on. It still waits for a
  maintenance window in Doze, as before.
- **A broken chain repairs itself.** A repeating alarm sustained itself, but the one-shot is re-armed only by its
  own tick, so a tick the system never delivers (most plausibly into a frozen process, the case this decision is
  for) would end every later one silently. Every start (each app open and resume) and every motion trigger runs
  `rearmHeartbeatIfLapsed`. A heartbeat counts as lapsed only a whole interval past the end of its window
  (`heartbeatLapsed`), so one merely late is left alone. One held longer by Doze and still pending at the wake is
  replaced, at the cost of that one tick's heal, on a wake the app open or the move already heals.
- **The windowed resume** is `setWindow(…, TAG_RESUME, resumeWindowListener, alarmHandler)`. The while-idle resume
  stays `setAndAllowWhileIdle` + `getService`: there is no listener form of a non-exact while-idle alarm (only
  `setExactAndAllowWhileIdle`, which needs the `SCHEDULE_EXACT_ALARM` that wz99 turned down). That is one start per
  pause, a user action, against the heartbeat's 96 a day. It keeps sharing its token with the notification's Resume.
- **A listener tick posts the notification itself** when it ends a pause (`onHeartbeat`, `onResumeAlarm`). The f69x
  re-claim at the tail of `onStartCommand` used to, and no start follows a callback. The store collector's own
  apply finds nothing changed.
- **The revive alarm.** Listener alarms die with the process. That costs nothing after an ungraceful death: an
  exempt install comes back through `START_STICKY` (ADR 043), and a non-exempt one could not be revived by the
  heartbeat either. The stand-downs that call `stopSelf()` clear the sticky record, though: the `graphless` process
  (restricted backup mode) and the Keystore refusal (`declineStorage`, ADR 2026-10.47rw). They relied on the old
  heartbeat staying armed. Each now arms `armRevive`: a one-shot `set` + `getService` (`ACTION_HEAL`) 15 minutes out.
  It fires only when no service is running, so its start creates one rather than entering a running one.
  `startMesh` cancels it, and so does every `onDestroy` branch except those two.
- **Legacy tokens.** Alarms survive an app update, so a pre-82s7 build's repeating heartbeat would keep starting
  the running service after the update. The revive deliberately reuses its token (request code 2, `ACTION_HEAL`),
  and `startMesh` cancels that and the old windowed resume token (request code 6) on every start
  (`cancelStartAlarms`). `ACTION_HEAL` stays in `onStartCommand` for those deliveries.

**The alternative a reader reaches for first** is the one #248 recommended: deliver the alarms as broadcasts
(`getBroadcast`) to an unexported receiver that hands the tick to the live service. That swaps the 20 s service
window for the 60 s background-broadcast window, but `onReceive` still runs on the main thread under a timer, so a
freeze longer than a minute is still an ANR. It also adds a manifest component. Leaving it alone (one model family
on one release) was the other option. The cost of a fix is small, though, and the user sees a "Knit isn't
responding" dialog for a tick they never asked for.

**What it costs and does not cover.** The while-idle resume and the notification actions (Pause, Resume, Stop) are
still `getService` starts into the running service: a frozen process can still ANR on them, but they are user
actions or once per pause. The revive succeeds only where a background start may claim the foreground (the
battery exemption), exactly as the heartbeat did. The trap: an alarm armed with a `PendingIntent` while the service
runs is a start into it. A new periodic tick belongs on a listener and `alarmHandler`, re-armed in its own
callback, and any field it touches stays main-thread and behind `meshStarted`. A callback queued before `onDestroy`
can still run after it, so every listener checks `destroyed` first. Regression: `MeshServiceHeartbeatTest` (a tick
heals with no `startService` and re-arms on the grid; destroy cancels it, and a tick delivered after destroy neither
heals nor re-arms; a start or a move repairs a lost tick's chain and leaves a late one alone; the legacy tokens are
cleared), `NextHeartbeatAtTest` (the grid and the lapse rule), `MeshServicePauseTest` (the windowed resume resumes in process and posts the
running text), `GraphlessProcessTest` and `MeshServiceGraphOffMainTest` (the stand-downs keep a service-start
revive). Device trial (2026-11-02, API 37 emulator, a local 2-minute
heartbeat): the process stopped with `run-as app.getknit.knit kill -STOP` across a tick. The parent recorded
"ANR in app.getknit.knit … executing service …, waited 20001ms" and a `REASON_ANR` kill. The fix stayed stopped for
4.5 minutes with no ANR, healed once on `SIGCONT`, and re-armed. An install over the parent cleared its repeating
alarm on the first start. `am freeze --sticky` does not reproduce the ANR: AMS does not time out a start into a
process its own freezer holds, so the parent's control stays green.
