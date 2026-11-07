---
id: "2026-09.hp88"
slug: an-absent-meshtastic-board-is-awaited-by-the-controller
title: "An absent Meshtastic board is awaited by the controller, not dialled"
date: 2026-09-30
topics: [lora, ble, power]
---

# ADR 2026-09.hp88 — An absent Meshtastic board is awaited by the controller, not dialled

Status: Accepted (2026-09-30)

**What was observed.** The 2026-09-18 battery review (work item #67) found two loops that keep running as long
as a paired board is off or out of range. `MeshtasticSession.connectLoop` redialled with
`connectGatt(autoConnect = false)` and a 30 s connect timeout, on a 5 s → 180 s ± 20 % backoff that never
stopped. A direct connect is a high-duty scan for its whole window, and the dial holds `BleConnectArbiter`,
so the mesh's presence scan was also blacked out for those 30 s. In steady state that was 30 s of every
~210 s, indefinitely. The streak reset only on a board reboot, so after days of drops it stayed at the 180 s
ceiling even after hours of good link. Separately, `LoraMeshTransport.pacerLoop` took nothing while the link
was down but still went through `waitForNextSend`. With a frame queued and its due time in the past, that
woke every `IDLE_TICK_MS` (1 s) for the whole outage: 3,600 wakeups an hour that could send nothing.

**What changed.** `BoardDialPolicy` (pure) now picks the mode of each dial:

- The first three dials after a drop are **direct**, with the 30 s window and the arbiter held, because
  most drops are blips: a reboot, a pocket, a wall.
- After that the board is taken to be away. The dial becomes **background** (`autoConnect = true`): the
  address goes on the controller's accept list, the controller connects at low duty when the board next
  advertises, and the arbiter is taken only for the settle/MTU/discover setup after the connect.
- **One direct dial an hour** stays as the net, because autoConnect is slow or never fires on some stacks. It
  needs the address in the stack's cache, which a Bluetooth restart can clear.
- A session that held Ready for **five minutes** resets the streak.
- While it waits in the background the link reads as `Disconnected("waiting for the board", <net due>)`, so
  the UI says Reconnecting rather than an hour of Connecting.
- The pacer now parks on `link.state.first { it is Ready }` while the link is down. It doesn't wait on the
  wake channel, and reading the state flow cannot miss the session-up.

The first alternative anyone would reach for is shortening the direct connect timeout (the work item's
fallback, to ~10 s). It cuts the cost by two thirds but keeps a blackout of the mesh scan every few minutes,
forever. Only the controller can wait for a board without scanning hard, so the fix hands the wait to it.

**What it costs, and the traps.** A board that returns after the third failure is found by the controller,
which is usually as fast as a direct dial but depends on the stack. The worst case is the hourly net, and
the device trial is what tells us which case we're in. Two refusal cases must not be confused:

- A background dial the stack **refuses** at once (status 133 for an uncached address) is
  `DialResult.Failed`, not `Timeout`, so it takes the normal backoff. Only a window that genuinely ran out
  skips the backoff and goes straight to the net's direct dial. Otherwise a refusing stack would redial in
  a loop.
- `MeshtasticGatt.connectAndConfigure` now closes the GATT client on every exit except `Opened`,
  cancellation included. Before this, a `stop()` inside the connect wait leaked the client for up to 30 s.
  Under autoConnect it would leak until the board came back, and then connect a session nobody owned.

The adapter going off during the wait ends it as `AdapterOff`, so the session parks on `Unavailable` as
before. Pinned by `BoardDialPolicyTest`, and in `MeshtasticSessionTest` by
`afterThreeFailedDirectDialsTheSessionWaitsInTheBackground`,
`aBackgroundWindowThatRunsOutFallsBackToOneDirectDialThenBackgroundAgain`,
`aFastFailingBackgroundDialStillBacksOff`, `aSessionReadyForFiveMinutesResetsTheStreak` and
`aShortSessionKeepsTheStreak`. The pacer is pinned by `LoraMeshTransportTest`'s
`aPacerWithAQueuedFrameSleepsThroughABoardOutage` (659 clock reads in ten minutes before the fix). The
`MeshtasticGatt` half has no host test (there is no GATT stack on the JVM), and its device trial is owed:
board off → three direct dials → background, with the mesh scan running; board on → `lora ready` within
seconds; unpair during the wait → no leftover background connection in `dumpsys bluetooth_manager`.

## Amendment (2026-11-06): the loop outlives the adapter going off

Status: Accepted (2026-11-07), after its device trials.

**What was observed.** On a Pixel 9 (2.11.0-alpha.3) Bluetooth went off from quick settings and no
`GattEvent.Disconnected` reached the session. Ninety seconds later the heartbeat's `writeCharacteristic` threw
`RuntimeException(DeadObjectException)`, which nothing caught: it ended `connectLoop` at `meshExceptionHandler`. Nothing
restarts that loop, and `_state` was never moved, so the link read Ready, the plane `Healthy`, for the 88 minutes until
somebody looked, while every mesh peer came back when Bluetooth did. A `send()` in that state queues a command nobody
reads and awaits an answer that never comes.

**What changed.**

- **A call into the stack cannot throw out of the channel.** The connect, every GATT op and the bond read go through
  `MeshtasticGatt.stackCall`; `dial`'s own adapter reads and the receiver's `isEnabled` do not, and what `dial` throws
  the loop below catches. An op that threw is `GattResult.Failed(-2)` (`STATUS_STACK_THREW`, beside `-1` for one the
  stack refused), `pending` cleared, so the session's write-failure path ends the session, a provisioning write's
  included: an admin write's failure otherwise leaves the session up, so `-2` alone ends it, as `send write`.
- **A throw costs one pass of the loop, never the loop.** `connectLoop` runs each pass through `runRound`: only its own
  cancellation ends it. Anything that does throw reads as `threw <class>`, folded onto `LoraLinkEnd.IO`, and takes the
  normal backoff, with a healthy session's streak reset, like a disconnect. An `Error` is not caught, by `runRound`'s rule.
- **The adapter going off ends the session at once, from `dialer.adapterOn`**, in any phase and with an op in flight,
  since the stack need not say so and an in-flight op's callback may never come. `Unavailable` is published first and the
  session cancelled, and no backoff follows: the top of the loop parks on the adapter, then dials direct with the streak at
  zero. The field log reads `Unavailable` as `ADAPTER_OFF`, so a new end reason was not needed for it.
- **A command cannot outlive its session, and what it refuses is requeued.** The inbox is one per session, closed as the
  session ends, and what it held is refused through `onUndeliveredElement`, which also covers an element a cancelled
  `select` took. Before, a command caught by a session's end waited through the outage and took its answer from the next
  session, or never, and its frame flew on gates asked before the outage. `SendResult.NotReady` now means the packet is
  owed, and `LoraMeshTransport.sendMessage` requeues on it: the frame waits out the outage, resumes at its
  cursor, has its gates asked again, books nothing until it flies, and a compact DM keeps what is parked behind it
  parked. A packet that likely reached the board is `Timeout(id)` however the session ended: a write that timed out, a
  compact DM's write lost to `Closed`, or a cancel inside the write or the drain, which the transport books and awaits,
  and never re-airs. A broadcast's `Closed`, or a `Failed` from the write's callback, stays `NotReady`: it may have got
  there, so a rare second airing is the price of not losing the frames that did not. The inbox is retired only after the
  state has left Ready: a refusal under a link still reading Ready costs a pointless take and requeue per pacing gap, and
  hung `aFrameBehindTheActorWhenTheSessionEndsAirsOnceAfterTheReconnect` at its `minGapMs = 0` until the order was fixed.
  A new session replaces the box rather than overwriting it, since a flag read false then true passes both retirements.
  A command taken is
  answered in `serve`'s `finally`: a provisioning reply is held nowhere else, so a throw or a `stop()` stranded it.
- **A dial reporting `AdapterOff` while the flag still reads on backs off** (`adapter off`, folded onto `ADAPTER_OFF`).
  The dial reads the stack and the flag trails it by a broadcast, and that dial returns without suspending, so the loop
  would otherwise spin until the broadcast landed.

Pinned in `MeshtasticSessionTest`: `aHeartbeatOnADeadStackEndsTheSessionWithItsStatus` for the Pixel 9's path, a phone
logging `heartbeat write Failed(status=-2)`; `aHeartbeatThatThrowsWhileReadyEndsTheSessionAndRedials` for the backstop,
with `aHandshakeThatNeverNamesTheBoardBacksOffRatherThanEndingTheLoop` the backstop's test with a throw that can really
happen; `theAdapterGoingOffEndsTheSessionAtOnceAndItRedialsWhenItReturns`,
`aSendQueuedAsTheSessionEndsIsAnsweredAndNeverAiredByTheNextOne` and their neighbours. The requeue's pin against the old
code is `LoraDirectTransportTest`'s `aSendTheSessionEndedAheadOfIsOwedUnbookedAndKeepsWhatIsParkedBehindIt`.
`LoraMeshTransportTest`'s `aFrameBehindTheActorWhenTheSessionEndsAirsOnceAfterTheReconnect` is green on the old code,
where the next session aired the frame, and pins the requeue's premise and the order above. `LoraFieldLogTest` pins the
words. `stackCall` has no host test.

Device trials, all passed on 2026-11-07 (below):

- **T1, the adapter path.** A bound board Ready, Bluetooth off from quick settings, then on. Pass is `…debug.LORA` reading
  `Unavailable` within a second or two of off, and `lora ready` within seconds of on, with no `MeshScope` line. This
  passes with `stackCall` deleted, because the adapter flag drops at TURNING_OFF, so it says nothing about that path.
- **T2, the stack path.** A trial-only uncommitted build with the `stateReceiver` registration removed, so the flag never
  moves. Bluetooth off, then a board send or the heartbeat. Pass is `lora gatt write threw … DeadObjectException`, then a
  link end of `heartbeat write Failed(status=-2)` or `send write Failed(status=-2)`, then `lora ready` after Bluetooth on
  with no app restart. It needs an API 33+ phone: on API 29–32 the deprecated `writeCharacteristic(ch)` catches the throw
  and returns false, so the line is `Failed(status=-1)` with no `lora gatt write threw`.
- **T3, frames at a session's end.** Stream room posts and DMs while the board power-cycles, with Bluetooth off during a
  send, and once during a Setup. Pass is every frame not in flight at the cut arriving after the reconnect, and each
  aired frame airing exactly once and booked; a broadcast in flight at the cut may be lost.
- **T4, repeated cuts.** About ten Bluetooth off/on cycles with DMs flowing, a Pixel 9 with a bound board and a second
  phone. Pass is the pacer sending after every cycle: the `…debug.LORA` send counters climb, and the second phone receives
  each DM.

**The trials (2026-11-07).** Build bf2472969 on a Pixel 9 (API 37) with its bound board, and a Pixel 7 as the second
phone, held to LoRa alone (link cap 0, Wi-Fi Aware off, relays off) so that its DMs rode the board.

- **T1.** `Unavailable` 0.6 s after off, `lora ready` 7.9 s after on, no `MeshScope` line.
- **T2.** `lora gatt write threw: … DeadObjectException`, then `send write Failed(status=-2)`, then `lora ready` 34 s after
  on, in the same process. With the flag never moving, the first dial after on still met the stack coming up
  (`dial connect 147`) and took its backoff.
- **T3.** Twelve frames across a Bluetooth cut and thirty across the board switched off, each aired once and each reaching
  the Pixel 7 once. DM 5, cut in its write, was requeued and aired once after the reconnect. A cut during a Setup left the
  board's settings as they were, and a clean Setup after it found the channel in place.
- **T4.** Ten cycles and twenty DMs, each aired and acked once, the board Ready again 11 to 31 s after each on. The last
  four DMs waited for the airtime ledger's 15-minute window, which the trials before had nearly spent
  (`loraAirtimeHeld`), not for the pacer.
- **A trap for the next Setup trial.** `…debug.LORAPROV` holds its broadcast (`goAsync`) until the Setup answers, and the
  evidence reads as the adapter's `STATE_CHANGED` queued behind it: with the bridge's Setup in flight the flag did not
  move, no `lora adapter off mid-session` was logged, and the session read Ready until an op timed out, 45 s in one run.
  A Setup started from the app holds no broadcast. Since #308 the bridge answers at once and runs the Setup off the
  broadcast, so a bridge-driven Setup now reaches the adapter path too.
