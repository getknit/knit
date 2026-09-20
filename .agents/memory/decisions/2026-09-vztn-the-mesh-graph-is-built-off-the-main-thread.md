---
id: "2026-09.vztn"
slug: the-mesh-graph-is-built-off-the-main-thread
title: "The mesh graph is built off the main thread; the service only starts it there"
date: 2026-09-19
topics: [reliability, service, android]
---

# ADR 2026-09.vztn — The mesh graph is built off the main thread; the service only starts it there

Status: Accepted (2026-09-19)

**What was observed.** One Play ANR on 2.6.0 (vc22), one user, Android 14, 2026-09-18, issue
`d1ae4db91d393beb80a7f844c7b4769e`: type `APPLICATION_NOT_RESPONDING`, location *"Executing service
app.getknit.knit/.mesh.MeshService"*. The main thread was sampled in `Ed25519Constants.<clinit>` — Tink's
base-point table precompute, BigInteger `modInverse` running interpreted on a cold process — under
`IdentityKeyStore.parse`, under a chain of Koin singles (`MessageCrypto` ← `CompositeMeshTransport` ←
`MeshController`), under `MeshService.onCreate → observeStatus → lifecycleScope.launch`. A second thread was
in XNNPack `allocateTensors`: 2.6.0's `KnitApplication` still warmed the 16 MB toxicity model five seconds into
*every* process, and that process was born for the service. The line numbers match the `v2.6.0` tag exactly.

**What it was mistaken for.** The `startForegroundService` deadline again. It is not: ADR 043 / `5da56015`
already claim the foreground state before the graph is touched, and ADR 2026-09.f69x re-claims it on every
start. This is the *other* timer. `ActiveServices` arms `SERVICE_TIMEOUT` (20 s for a foreground-process
service, `SERVICE_BACKGROUND_TIMEOUT` = 200 s otherwise) around `scheduleCreateService` and releases it at
`serviceDoneExecuting`, so `onCreate` **and the `onStartCommand` dispatched right behind it in the same
main-thread batch** share one 20 s budget, independent of whether `startForeground` has been called. The graph
build was still synchronous on the main thread inside that budget: `lifecycleScope.launch` is
`Dispatchers.Main.immediate`, so `observeStatus`'s body — the first `meshManager` read, hence the whole mesh half
of the graph (keystore unwrap, CBOR decode, two Tink keyset parses, transports, `MeshManager`) — ran inline.

**Why only some processes see it.** On the Activity path `KnitApp`'s first composition calls
`koinInject<MeshController>()` *before* its route effect calls `MeshService.start`, so the service's own read is
a cache hit. Only a process born for the service — `BootReceiver`, the heartbeat alarm's `PendingIntent`, a
`START_STICKY` restart — pays the build inside `onCreate`, and those are exactly the moments a slow phone is
also busiest (a keystore and a TEE still settling after boot). 2.6.0's process-start warm-up made it worse by
saturating the cores at the same moment; `6c3d95d8` had already moved that to `ON_RESUME` and the first peer
sighting, which halves the problem and leaves the main-thread build.

**What changed.** `MeshService.onCreate` claims the foreground state as before, then launches on the **app
scope** (`Dispatchers.Default`): `resolveGraph()` reads `meshManager`, `powerMonitor` and `settings` — each a
`by inject()` lazy whose first read builds its subtree — and `withContext(Dispatchers.Main.immediate)` runs
`startMesh()`, the old tail of `onCreate` verbatim (`observeStatus`, `warmModelOnFirstPeer`, `powerMonitor.start`,
`meshManager.start`, the `meshEnabled` write, the heartbeat, the motion sensor), which sets `meshStarted`.
Every later callback keys on that flag rather than on the graph: `onStartCommand`'s f69x re-claim posts the
"searching" seed until it is set (reading `neighborCount.value` there would block the main thread on Koin's
single lock for the seconds the build was moved off it to save), `ACTION_HEAL` is a no-op on a mesh still
coming up, and `onDestroy` before it cancels the heartbeat and returns — `destroyed` is read by the build's
return to the main thread so a service the system took down mid-build never starts a mesh nothing will stop.
A build that throws is re-thrown as an uncaught exception on the main looper (`Handler.post { throw }`), past
the scope's log-only `meshExceptionHandler`, so a graph that cannot be built is the crash it always was and
`CrashHandler` records it, instead of a "searching" notification that never resolves.

**The alternative.** `lifecycleScope` for the build would cancel it on destroy, but a built graph is
process-wide and never wasted, `LifecycleService` dispatches `ON_DESTROY` through a posted runnable *after*
`onDestroy` returns (so the cancellation could land after a hop that had already been posted, and the mesh
would start anyway), and the Robolectric service tests bind the app scope on `Dispatchers.Unconfined`, which
keeps the launch and the hop inline — the synchronous shape `MeshServiceForegroundReclaimTest` and
`GraphlessProcessTest` were written against, untouched. Building the graph in `KnitApplication.onCreate` on a
worker would have the same effect for the service but would also pull the keystore and the database into
every process, including the restricted-backup one `isKoinStarted` / `GraphlessProcessTest` exist to keep it out of.

**What it costs and does not cover.** The mesh comes up a few hundred milliseconds later than the
notification on a fast phone, which nobody sees; the seed text was already what the notification showed until
the first count. A `MeshController` read from the UI while the build is in flight (the app opened seconds after
a boot start) still blocks on the single lock — a pre-existing hazard, since the UI built the same graph on
main before, and now bounded by the worker's progress rather than adding to it. `MeshServiceGraphOffMainTest`
pins the contract with a real worker-pool scope and a controller factory that blocks on a latch: `onCreate`
returns with the seed posted and nothing started, the following `onStartCommand` re-claims from the seed without
touching the graph, the start lands on the main looper once the latch opens, `destroy()` during the build starts
nothing, and a factory that throws surfaces as an uncaught exception on the main looper. Device-verified
2026-09-19 on the Pixel 3 (Android 12): with the battery exemption granted for the run, `am crash` on the
backgrounded app made the system restart the sticky service in a fresh, service-only process, which logged
`mesh graph resolved in 820 ms on DefaultDispatcher-worker-5`, keyed the database and had both radios listening
60 ms later, held `isForeground=true`, and raised no ANR — `resolveGraph`'s one info line is how to read this on
any device. (`am start-foreground-service` cannot reach the service from the shell — it is not exported — and
`BOOT_COMPLETED` is a protected broadcast, so a sticky restart is the lab's way into a service-first process.)
The trap: any new
read of an injected field from `onStartCommand`, `onDestroy` or a sensor callback must sit behind `meshStarted`,
or it is the ANR again in the one process type the UI never covers.
