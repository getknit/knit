---
id: "2026-10.pj9w"
slug: ble-discovery-waits-run-on-the-elapsed-clock
title: "BLE discovery waits run on the elapsed clock, and a dial wakes when its dwell ripens"
date: 2026-10-02
topics: [ble, transport, power]
---

# ADR 2026-10.pj9w — BLE discovery waits run on the elapsed clock, and a dial wakes when its dwell ripens

Status: Accepted (2026-10-02), JVM-tested (`ElapsedWaitTest`, `PromotionPolicyTest`'s `msUntilDue` cases). Not yet
device-trialled: the lab phones were taken back before the change was built. No wire byte moves.

**What was observed.** The first Android-to-Android link-time trial (2026-10-02, `scripts/ble-link-trial.py`, P7 and
P3 on main's 2.7.0 debug build, Wi-Fi Aware dark, other phones in range) timed how long the pair took to link again
after a stimulus on the P3, the dialer:

| stimulus | reps | linked | median | p90 | max |
|---|---|---|---|---|---|
| links shed by the debug cap, Bluetooth on | 10 | 10 | 9.4 s | 20.2 s | 29 s |
| Bluetooth off 10 s, then on | 5 | 4 | 78 s | 89 s | 92 s, one past 66 min |

The connect itself took about 0.3 s, so nearly all of that is the wait before the dial. Two causes in the timing,
beside the advert power (ADR 2026-10.ryak):

- **The ordinary dial never woke when its dwell ripened.** A candidate needs 12 s of presence
  (`PromotionConfig.dwellThresholdMs`), and a gap of more than 8 s between sightings (`presenceGapResetMs`) restarts
  it. A screen-off phone scans in 8 s windows with gaps of 12 s or more, so every window restarts the dwell, and the
  sighting that could have ripened it never comes. The dial then waited for the connect loop to happen to run inside
  a gap after the dwell had ripened, which in practice meant the loop's 60 s ceiling: the first dials came 58–84 s
  after the adapter came back. ADR 2026-09.hj4a found exactly this for the lonely dial and gave it
  `LonelyDialPolicy.msUntilDue`; the ordinary dial never had the same.
- **Every wait stopped while the CPU slept.** A coroutine `delay` runs on the monotonic clock, which does not count
  suspend. The P3's 60 s `bt state` line landed at a median 110 s and p90 147 s, while the P7's, which does not
  suspend, landed at 60 s on the dot. The 12 s dial watchdog ran 14–26 s of wall time on the P3. The scan's window,
  its gap (12 s hunting, 240 s settled with one link) and the connect loop's ceiling all stretched the same way, so
  a suspending phone ran a slower cadence than `PowerPolicy` says, by however much it slept.

**What changed.**

- **`PromotionPolicy.msUntilDue`**: the time until the first candidate the floor and the backoff admit reaches its
  dwell, or null. `BluetoothMeshTransport.nextConnectWaitMs` takes the earliest of it, the backoff deadline and the
  lonely dial's, so the connect loop runs when the dwell ripens, between scan windows, before the next sighting can
  restart it. A candidate wakes the loop once: after it ripens, nothing waits on it. Under the six-link budget the
  wake may find nothing to do, and costs one pass.
- **`mesh/power/ElapsedWait`**: `sleep(ms)` and `receiveWithin(channel, ms)` measured on `elapsedRealtime`, which
  counts suspend. Each looks at the clock at least every `CHECK_MS` (2 s) of awake time, so a wait that came due while
  the phone slept ends within 2 s of the CPU next running. The transport uses it for the waits a relink waits on:
  the connect loop's wait, the dial watchdog and both HELLO watchdogs, the scan's pause behind a dial, the scan
  window (a dial does not stop a window already running, so a window stretched by sleep holds the radio against the
  dial), and the scan's gap only while it is the **hunting** gap (`PowerPolicy.hunting`: no link and not yet
  relaxed, the 12 s gap). `receiveWithin` steps with `select { onReceive; onTimeout }`, so a wake sent as a step ends stays in the
  channel; a `withTimeoutOrNull { receive() }` per step would risk losing it each time.
- The ordinary dial's candidate filter is one function, `ordinaryCandidates`, shared by `driveConnections` and the
  wake, so the two cannot disagree about who is waiting.

The alternative a reader reaches for first is an alarm: `AlarmManager` on `ELAPSED_REALTIME_WAKEUP` would end each
wait on time even on a sleeping phone. It costs a binder call per wait (the scan loop alone waits every 20 s while
hunting), wakes the CPU on purpose, and sits under Doze's deferral and the standby buckets' alarm quotas. These waits
never wake a phone; they stop losing the time it slept. Another is to widen `presenceGapResetMs` past the scan gap
so the next window's sighting ripens the dwell. That would make "continuous presence" mean "seen twice in a few
minutes", which is not what the passer-by filter is for.

**What it costs.**

- While the CPU is awake, each waiting loop resumes every 2 s to read the clock: two or three loops, a comparison
  each. A minute's wait is thirty looks instead of one. A look holds no wakelock and does no other work.
- **The other scan gaps still stretch, on purpose.** The settled floor (`120 s × (1 + links)` screen-off), a linked
  node's gap and the relaxed lonely gap are power budgets, and the battery nights that sized them (ADRs
  2026-09.kb68, 2026-09.w3xk) ran on phones whose gaps stretched with sleep: the P3's settled 240 s ran at about
  440 s. Moving them onto wall time would scan up to ~1.8× as often on such a phone, a battery cost nobody
  measured, for a latency that matters less once a node holds a link. Only the hunting gap, the one a user waits on
  to rejoin, keeps wall time. The window keeping wall time can only shorten scanning against today.

**What it does not cover.** A wait still ends only when the CPU runs. A phone that sleeps through its deadline with
nothing waking it ends the wait at its next wake, within `CHECK_MS` of it. A settled phone's scan gap still
stretches (above), so a newcomer that only a settled, sleeping phone can dial waits longer than `PowerPolicy` says;
hj4a's lonely dial is that newcomer's own way in. The other waits in the transport keep plain `delay`: the advert
re-assert net (ADR 2026-10.9utz, a net, said so there), the side channel's tick, the Coded
pace and the 60 s `bt state` line, whose stretch is the measure of how much a phone sleeps.

**The trap.** A new wait a relink waits on that uses `delay` or `withTimeoutOrNull` silently brings the stretch back,
and only a suspending phone shows it. Use `elapsedWait`. The reverse holds for a power budget: moving one onto wall
time is a battery change, and wants a battery night. And a new rule that gates a dial on the clock (a dwell,
a window, a hold) needs a wake when it comes due, as `msUntilDue` and `LonelyDialPolicy.msUntilDue` are: on a
screen-off phone the next sighting is not that wake.
