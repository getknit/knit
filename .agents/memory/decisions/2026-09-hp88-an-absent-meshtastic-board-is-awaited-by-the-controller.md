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
