---
id: "2026-10.f82h"
slug: no-coroutine-cancellation-cuts-a-delivery-the-router-admitted
title: "no coroutine cancellation cuts a delivery the router admitted"
date: 2026-10-27
topics: [mesh, custody, spool]
---

# ADR 2026-10.f82h — no coroutine cancellation cuts a delivery the router admitted

Status: Accepted (2026-10-27)

**What was observed.** `HomeSpoolsLabTest` failed 3 times in about 68 chaos runs (#141 finding 11). Alice's log
showed `drop v2 chat …: StandaloneCoroutine was cancelled`, counted as `DECRYPT_FAILED`. It looked like a decrypt
fault. It was a cancellation.

- A spool `Worker` delivers what it pulls on its own coroutine (`ScopeSync` → `deliver` → `MeshRouter.handleInbound`).
  `ScopeSync.reconcile` stops a worker whose relay left the scope's set (`Worker.stop` → `job.cancel()`). Reconciles
  are routine (ADR 2026-09.dcah, 2026-09.wa79). In this test the DM scope drops `public-1` once both homes are
  declared (ADR 2026-10.8kfx), while Alice's `public-1` worker is still pulling Bob's DM.
- `handleInbound` marks the frame seen and then runs `onDeliver` on the caller's coroutine. `onDeliver` custodies
  the frame before it dispatches (ADR 006, `InboundPipeline.onDeliver`). The decrypt arms' `runCatching` caught the
  cancellation and counted it as a drop.
- What was lost depended on where the cut fell. Before the ratchet+row commit, the commit rolled back and the
  message was lost. After it, the one-shot sealed receipt was lost (ADR 018), so Bob never saw ✓✓. At the custody
  write, the frame was never delivered and no drop was counted.
- In every case the frame was seen for the `SeenSet` window and held in our custody, so our digest agreed with every
  carrier's. Nobody re-served it. ADR 053's "a lost tick self-heals when the message re-serves" never fired; that
  sentence is amended there.
- `MeshManager.stop()` cuts a radio delivery the same way: it cancels the session scope, and with it the router's
  inbound collector. A pause in place is a stop.

**What changed.** Once `seen.add` admits a frame, `MeshRouter.handleInbound` runs `onDeliver` and `scheduleRelay`
under `withContext(NonCancellable)`. The router set the seen mark, so the router makes sure the delivery behind it
finishes. That covers every caller: the spool worker, the radio collector, and the `PendingInbound` replay nested
inside a delivery.

The pipeline's guards (the dispatch backstop, `verifyInbound`, the three decrypt arms) now use
`runCatchingUnlessCancelled` (`mesh/MeshCoroutines.kt`). It follows `runRound`'s rule: a `CancellationException`
while the caller is still active (an inner `withTimeout`) is a failure counted as today, and the caller's own
cancellation is rethrown. So a cancellation is never counted as `DECRYPT_FAILED` and never reaches
`onRatchetFailure`. Inside the router nothing is cancelled any more. The two callers that enter `onDeliver`
directly, `MeshManager.replayCustodiedGroupFrames` and `replayCustodiedSeedDms` (heal and startup), run each frame
under `NonCancellable` too: a stop between a group frame's commit and its tick would leave the row, and the backstop
stops at the exists-gate. The loop between frames stays cancellable, so a stop still ends the pass. The guard is
then the rule for any caller added later, not a path that fires today.

`RatchetSessions.commitOpen` now stamps `lastReplacementAt`, `confirmedAt` and clears `undecryptable` *after*
`onOpened()`, still under the lock. A row write that throws rolls back the transaction, and a stamp written before
it used to outlive the rollback. That started a replacement floor for a frame that still opened as new, so the
re-served copy was refused as a `DUPLICATE` (`RatchetSessionsResetTest.aReplacementWhoseRowWriteRollsBackStartsNoFloor`).

**Alternatives.**

- Wrap only the spool doors in `NonCancellable`, or make `Worker.stop` wait for an in-flight accept. Rejected: a
  stop or a pause cuts a radio delivery the same way.
- Rethrow the cancellation and clear the seen mark. Rejected: after a cut past the custody write, custody still
  holds the frame, so nothing re-serves it, and nothing re-enters the frame from our own custody.
- Bound the delivery with a timeout. Rejected: a timeout that fires before the durable writes (custody, then the
  commit, then the receipt seal) brings the bug back.

**Nothing a delivery awaits blocks without bound.** Every send `onDeliver` makes is an enqueue or a launch:

- `router.sendOwn` calls `transport.send` / `sendLive`. The composite picks one child. Wi-Fi Aware and Bluetooth call
  `FramedLink.send`, a `trySend` on the link's outbound channel. LoRa's `send` is `Unit`.
- `fastFanout`, `fastSend` and `longRangeFanout` are not `suspend`. The composite launches each link child's send on
  its own scope.
- `scheduleRelay` launches the jittered relay on the router's scope, and `handOn` is one `transport.send`.

The other waits are bounded local work: the ratchet mutex and the write transaction, the DataStore reads,
moderation's model load and classify, and `CallManager.onSignal`'s one-time `restored.await()` (a local file read).
`TransferManager.onSignal` launches its long work. A hung database would pin the delivery, but it would pin every
other writer too.

**What it costs.**

- A delivery in flight at `stop()` now outlives the session. That is safe:
  - Sign-out is `clearApplicationUserData`. No session of the old identity follows in the same process.
  - A restore is applied across a relaunch (`finishRestore` at the next start), never under a live delivery.
  - The database, `RatchetSessions`, `ForwardStore` and the receipt tables live as long as the process, not the
    session. The old delivery writes the same stores the next session reads.
  - The next session's router has a fresh `SeenSet`, so it can admit the same frame again while the old delivery is
    still running. The ratchet refuses the second open as a `DUPLICATE`, and the exists-gate catches a later copy.
    The bounded cost is a possible second notification for that one message.
- The relay the old delivery schedules launches on the cancelled router scope and never fires. The frame is in
  custody, and the next session's digest exchange re-serves it.
- A test fixture that holds a delivery forever now holds it through a stop. `LabCustodyGate` must be released before
  a scenario ends, and `MeshLab.close` releases every gate first as a safety net.

**What it does not cover.** Process death. `NonCancellable` cannot keep a process alive, and a death between the
custody write and the receipt seal still loses the message or the tick. That is #56, which proposes a durable
"owed delivery" marker on the custody row. Two related gaps are follow-ups too:

- The persist hook does not report `committed`, so it cannot hold back a second notification and ack.
- The spool's `account` gate reads "seen but not custodied" as "custody refused".

**What keeps it true.**

- `MeshRouterTest.aDeliveryWhoseCallerIsCancelledStillFinishes`.
- `MeshCoroutinesTest`'s two guard cases.
- `SpoolWorkerStopLabTest`: a worker stopped at the inbound custody write, a worker stopped at the receipt custody
  write, and a receiver whose mesh stops mid-delivery over a link. Each ends in `assertConverged`.
