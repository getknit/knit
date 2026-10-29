---
id: "2026-09.7svb"
slug: a-lora-rate-limiter-that-resets-on-launch-is-not-a-rate-limiter
title: "A LoRa rate limiter that resets on launch is not a rate limiter"
date: 2026-09-10
topics: [lora, airtime, reliability]
---

# ADR 2026-09.7svb — A LoRa rate limiter that resets on launch is not a rate limiter

Status: Accepted (2026-09-10; `LoraPlaneState`, `LoraPlaneStateStore`, `LoraAirtime.bookings`/`restore`,
`LoraGossipPolicy.snapshot`/`restore`, `SeenSet.stamps`/`restore`)

**Superseded in part by ADR 2026-10.ug4a (2026-10-29):** the ledger half. The airtime ledger is no longer persisted,
and nothing seeds it: the regional duty cycle is the firmware's to enforce (`Router::send`'s `DUTY_CYCLE_LIMIT`), so "the
ledger is the only thing enforcing it" below was false, and the only autonomous traffic left (the re-probe) has its
own persisted backoff. The auto-reply memory and the re-probe schedule stay persisted through this seam.
<!-- doc-lint: allow LoraAirtime.bookings — the status line names what this ADR shipped -->

Every limiter on this plane was process-scoped, so each launch handed it a fresh 45-second allowance and
re-ran every "first time" behaviour against it. Reported from the lab, where a reinstall lands many times a
day and each startup pegged the board.

## What was observed

Six pieces of state, all in memory, all reset by a process restart — and `installDebug` keeps app data, so
the board stays paired and the whole sequence fires on every launch:

| State | Where | What the reset buys |
|---|---|---|
| The airtime ledger | `LoraAirtime.samples` | A brand-new window. Reinstall every ten minutes and the 15-minute cap is never reached, let alone enforced. |
| The beacon floor | `lastSelfProfileAt` (`NEVER`) | Session-up always beacons the self profile; `PROFILE_FLOOR_MS` is void. |
| The Trickle interval | `LoraGossipPolicy.intervalStart` | A pair of gateways that had doubled out to 15 minutes drops back to the 5-minute floor. |
| The serve cap | `ServeBudget.windowStart` | `SERVE_CAP_PER_HOUR` refills to 12 per publisher. |
| `profileSeen` | 12-hour `SeenSet` | Relayed profiles are re-fanned — the ADR 057 failure, one lapse at a time. |
| `lastHeardAt` | 45-minute linger | Every peer is "first heard": `beaconProfile(60 s)` plus a `reofferTo` batch, per peer. |

The ledger is the load-bearing one, because it is the only thing that bounds the rest. In a four-node
pocket a restart is a self beacon, then a beacon and a re-offer batch per peer, then an OFFER at the floor —
all against a budget that believes it has spent nothing.

**Mistaken for** a lab artefact, which is the reason it sat. It is not: the regional duty cycle is law
rather than politeness (ADR 067) and this ledger is the only thing enforcing it, so a crash loop, a
force-stop, or a user who swipes the app away buys the same free air in the field. The lab merely does it
forty times a day.

## What changed

**The limiters outlive the process.** One seam, `LoraPlaneState`, with a `LoraPlaneSnapshot` read at
`start()` and written behind a debounce; the implementation is a JSON blob under one settings key
(`LoraPlaneStateStore`, `SettingsStore.loraPlaneState`). `mesh/lora/` stays Android-free, and every rule
about what a snapshot means is unit-testable without a DataStore.

**Wall-clock stamps, converted at each end** (`WallShift`). The plane's own `clock` is `elapsedRealtime`,
which restarts at zero every boot: a stamp persisted in it reads as the far future on the next boot and
would age nothing out for as long as the device stayed up. An age is all any of these limiters asks for, so
the offset between the two clocks *right now* is enough to carry one across. A snapshot stamped **ahead** of
the wall clock is refused whole (`applyState`) — the clock moved backwards, every age would read as
negative, and one fresh window is much the smaller error.

**Nothing transmits until the ledger is back** (`awaitRestored`, on the pacer, the gossip loop and the
beacon). A packet sent against the empty ledger is exactly the free air this closes, and the pacer is the
one place all outbound air passes through. It releases on failure too, and behind a 5-second timeout: a
store that never answers must cost the plane a window, not its voice.

**A booking carries its cost, never its size.** `LoraAirtime.restore` takes the recorded milliseconds — a
board swapped between two processes has a different preset, and the air the last process spent was spent at
the old one.

Three limiters are deliberately left out. **`sigSeen`** is the 10-minute "is this frame in flight" window, and
restoring it would carry a record that we *transmitted* across a restart — which on a plane with no acks is
not evidence anyone heard, the reasoning that already exempts `serveOne` from it (ADR 2026-09.y8pu). And
**`lastHeardAt`** feeds `reachable`, which only fresh frames may write (ADR 2026-09.2ajk): a peer restored
from disk would be claimed as reachable on evidence this process never saw. Neither costs much, because the
restored ledger bounds what they would otherwise let through — the re-offer batch is `AirBucket.BRIDGE`, and
a window that was spent before the restart is spent after it.

The third is the **gossip Trickle interval**, and that one was persisted first, shipped to the lab, and
taken back out — see the trial below.

**Not a debug-only affordance, and not a longer floor.** Making the beacon floor an hour, or gating any of
this on `BuildConfig.DEBUG`, would have quietened the lab and left the compliance hole exactly where it was
— the failing mechanism is that the limiter forgets, not that it is too generous.

## What it costs, and what it does not cover

**One debounce of exposure.** `STATE_SAVE_DEBOUNCE_MS` is 2 s against a pacer floor of 3 s, so a process
killed mid-window loses at most the last packet or two of the ledger — the same order as the governor's own
estimate of what a packet costs. `stop()` writes synchronously-snapshotted state on the surviving scope for
the orderly case; the debounce is what covers a kill.

**A snapshot survives unpairing on purpose.** `clearLoraDevice` does not clear it. Keeping it can only
over-charge (a new board may have a different preset); clearing it would hand a fresh allowance to anyone
who re-pairs, which is the hole this closes wearing a different hat.

**The trap:** the restore is once per instance (`if (restored.value) return`). A stop/start inside one
process still holds the live ledger, and re-reading would replace it with a blob up to one debounce older —
or, if `stop()`'s write has not landed yet, with the one before that. Anything that adds a limiter here must
add it to *both* `snapshotState` and `applyState`; a half-restored snapshot is worse than none, which is why
it is one blob and not a key per limiter.

Pinned by `LoraMeshTransportTest.aRestartDoesNotBeaconAgainInsideTheProfileFloor`,
`aRestartInheritsTheSpentWindowRatherThanAFreshAllowance` and
`aSnapshotFromAheadOfTheWallClockIsRefusedRatherThanTrusted`;
`LoraAirtimeTest.aRestoredLedgerStillOwesTheAirTheLastProcessSpent`,
`aRestoredLedgerGivesBackWhatTheWindowHasOutlived` and `aRestoredBookingKeepsTheCostItWasChargedAtNotTodaysPreset`;
`LoraGossipPolicyTest.aRestoredIntervalKeepsTheBackoffARestartUsedToThrowAway` and `aSpentSlotStaysSpentAcrossARestart`;
`SeenSetTest.aRestoredSetRemembersWhatTheLastProcessSaw`.

## Verified on the lab P7 (2026-09-10)

The P9 could not be the subject: it sits `PASSIVE` behind another gateway, and a passive gateway transmits
nothing role-gated, so neither half of this is observable there. The P7 is `ACTIVE` with its own board
(`!64761b18`, US/LONG_FAST, fw 2.8.0), which makes it the one lab phone that can show the failure.

**Control, on the shipped build.** Ledger before a restart `totalMs 1951`; after `am force-stop` + relaunch,
`totalMs 0` — the window forgotten whole. Session-up then put a fresh profile on the air:

```
15:21:24  lora ready board=1685461784 … signing=true
15:21:47  lora tx profile-self parts=3          ->  bootstrapMs 7354 / 11250
```

7.35 s of a 45 s window, on every launch, against a budget that believed it had spent nothing.

**With the change.** Launch 1 spends `liveMs 7804` + `bootstrapMs 5280` = `totalMs 13084`. Force-stop,
relaunch:

```
15:29:06  lora state restored: air=13084/22500ms serve=1 profiles=2
15:29:14  lora ready board=1685461784 …
```

`liveMs 7804`, `bootstrapMs 5280`, `totalMs 13084` — **identical across the process**, and no `profile-self`
in the 85 s that followed: the floor held where the control had beaconed. The plane stayed live throughout
(two more `far:chat` fan-outs, `liveMs` climbing 7804 → 15608 *on top of* the restored figure rather than
from zero). `air=…/22500` in the restore line is the fallback allowance, not a second bug: the restore runs
before the board reports its region, and the budget re-prices to 45000 at `lora ready`.

## Persisting the gossip timer stalls the election, so it isn't persisted

The first cut carried `LoraGossipPolicy`'s Trickle interval too, on the reasoning that a converged pair of
gateways should not drop back to the five-minute floor on every restart. Installing that on the lab fleet
showed what it costs.

An OFFER is the only evidence that anyone has a board, and `LoraGatewayPolicy` is **not** persisted —
`quiesce` calls `forget` — so a restarted node knows of no rival until it hears one. Carrying the timer
delays exactly that packet, and worst where the back-off is largest: a restored interval that has since run
out sends `ensureInterval` down its "slept through the boundary" path, doubling the interval and drawing a
fresh transmit point in its second half. Measured on the P7 after a fleet install (2026-09-10):

```
15:52:49  lora state restored: air=39062/22500ms gossip=600000ms serve=1 profiles=47
          … no OFFER in the next 90 s; P7 and P9 both role=ACTIVE,
          both gatewaysHeard: 0 beside boardsHeard: 1
```

A ten-minute interval came back, had already elapsed, and became a fifteen-minute one with its point up to
fifteen minutes out — every gateway in the pocket ACTIVE and fanning out everything meanwhile. Wrongly ACTIVE
costs duplicate air on two boards; wrongly passive costs silence; the back-off was buying neither.

Dropping only the `spent` flag was tried first and is not enough: that frees the slot inside a *running*
interval, and the case that matters is the elapsed one. So the timer is not persisted at all. A restart
starts a fresh interval at the floor and offers within 2.5–5 min, and the OFFER it costs is charged to the
window this ADR *does* persist — a reinstall loop cannot outspend its allowance on gossip, which was the
whole reason to want the back-off across restarts. Gossip state and gateway state are now both session-local:
remembering half of that pair is what caused this.

## The crash this uncovered, and the lock order that fixes it

The first device run died on launch: `NullPointerException: … OutboundFrame.getBucket()` at
`LoraPacePolicy.admitBest`, out of `take` on the pacer loop. Nothing restarts that coroutine, so the plane
went silent while still reporting `state: Ready` — `bootstrapMs 0`, no `lora tx` at all.

`admitBest` walks `queue.indices` and reads `queue[i]`. `ArrayDeque` is not thread-safe and nulls a slot on
removal, so a structural change under that walk hands it a null through a non-null type. The queue has **six
writers on five coroutines**: `take` on the pacer, `enqueue` on whichever coroutine sent (the composite's
fan-out, the ctl handler's backfill, the gossip loop's OFFER, the link-state collector's beacon),
`onQueueStatus` and `evictOversize` on the link and queue collectors, `onNak` on the outcomes collector.
That is **pre-existing** — none of it is this change's code, and it shipped in 2.5.0 — but this change made
it reproducible by parking the pacer and the gossip loop on one `awaitRestored` and releasing both in the
same dispatch.

`LoraPlaneState` also added a seventh toucher of its own, and that one *is* this change's: `snapshotState`
reads `pace.airtime.bookings(now)` from the save loop, and `bookings` prunes, so it mutates a ledger the
pacer is asking. `stop()` has the same shape against a pacer that has been cancelled but has not yet
suspended.

So every entry point on `LoraPacePolicy`, `LoraAirtime` and `LoraGossipPolicy` is now `@Synchronized` — the
idiom already used by `SeenSet` and `ServeBudget` beside them. **The pacer's monitor is taken outside the
ledger's, never the other way round**, which is the whole lock order: `take` → `admits` nests, and nothing
nests the reverse. `LoraPacePolicyTest.aQueueDrainedWhileOtherThreadsEnqueueDoesNotTearItself` is the
regression — four writer threads against one drainer, which tears within a run or two once the monitors come
off (confirmed by removing them).
