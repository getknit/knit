---
id: "2026-09.kb68"
slug: a-lonely-node-relaxes-its-discovery-cadence
title: "A lonely node relaxes its discovery cadence"
date: 2026-09-18
topics: [wifi-aware, battery, reliability]
---

# ADR 2026-09.kb68 — A lonely node relaxes its discovery cadence

Status: Accepted (2026-09-18; `mesh/wifiaware/NanLonelyPolicy`, device trial owed — see the end)

Found by the 2026-09-18 battery review, by reading rather than measuring. With no cue targets — a phone alone
in a bag, a drawer, an empty house — `rediscoverDelayMs` returned `REDISCOVER_LONELY_MS` (8 s; 16 s screen-off
on battery, the file's one use of `PowerState`), and `NanSyncPolicy.needsRediscovery` is `facts.isEmpty() ||
…`: an empty snapshot is "blind", so the discovery loop's re-arm branch fired on every tick past
`REARM_COOLDOWN_MS` (15 s). Each `rearmSubscribe` closes and reopens the subscribe session with
`setInstantCommunicationModeEnabled(true, 2.4 GHz)`, and the framework lights Instant Communication Mode for
30 s per configuration (`config_wifiAwareInstantCommunicationModeDurationMillis`; it caps it *because* of what
it draws). A re-arm every ~16 s kept ICM lit around the clock for as long as the phone was alone — the exact
state in which nothing could be gained by it — and each re-arm is also the subscribe close/reopen the file
already treats as wedge-prone (`onSessionConfigFailed`). The Bluetooth plane had solved the same problem for
its scan in `PowerPolicy.idleAfterScan`: hunt hard for three minutes, then a screen-off node on battery
relaxes to the duty cycle.

## What changed

**Only the cadence while lonely.** `NanLonelyPolicy` (pure, constants passed in like `NanWatchdogPolicy`)
gives the loop a `Cadence(tickMs, rearmCooldownMs, relaxed)`: not relaxed is exactly the old behaviour (8 s,
×2 screen-off, 15 s cooldown); relaxed — `PowerPolicy.lonelyRelaxed`, the predicate extracted from
`idleAfterScan` so the two radios relax together: on battery, screen off, alone ≥ 3 min — is the duty
cycle's base interval as the tick (120 s; 300 s on low battery) and that minus 15 s as the cooldown (105 s /
285 s), so a timed tick always clears the loop's `>` gate and a stray poke-wake adds at most one re-arm per
window. ICM duty drops from ~100 % to 25 % / 10 %. The transport's `rediscoverDelayMs` returns the cadence's
tick directly when `cueTarget` is empty (the `session == null` early returns stay ahead of it, so attach
retry is untouched), and the re-arm branch's cooldown is the relaxed one only with nobody to cue.

**The loneliness clock is observed, not maintained.** `lonelySince` is written in one place, by the loop,
through `NanLonelyPolicy.lonelySince`: 0 while a cue target exists, else the stamp already held or "now" on
the first empty observation. None of the five sites that remove a cue target know about it, and "last put"
would have been eaten by the 150 s linger before the three-minute window began. It resets where hunting
should restart: `onAttached` (every fresh session — start, NAN back up, a reattach, a session cycle), and a
rising edge in `onForeignReachable` for a peer BLE sights that we hold no cue target for, which also pokes
`healSignal` so a BLE walk-up relights discovery within one aggressive tick.

**A heal buys one aggressive re-arm.** `heal()` — the 15-minute alarm, every significant-motion trigger, an
app resume — sets `healRearmOwed`; the loop consumes it once per wake and uses the 15 s cooldown for that
pass. A phone being walked around re-arms once per trigger, ≥ 15 s apart, fewer than the old 16 s cadence in
every case; the loop's own pokes (a digest move, a link end, a power change) carry no token and cannot re-pin
ICM. `onCueReceived` now pokes the loop on first contact too: lonely → not lonely is a state change even when
the digests agree, and a loop asleep on a 300 s tick must not sleep it out with a peer in range.

**What was left alone, on purpose.** No new subscribe variant (re-arming without ICM would have been a
second code path through the wedge-prone session lifecycle for a saving the cadence already makes); no change
to `discoveryLoop`'s branch order; nothing shared with the watchdog's clocks (`lastLinkOrAcceptAt`,
`lastReattachAt`). The wedges the file guards against — the idle responder (state 101), the leaked request
(state 104) — need an owed reachable peer to be observed at all (`checkWedge`, the pinned-responder branch),
so a lonely node cannot hide one and detection latency once a peer appears is unchanged; `needsIcmRelight` is
false on empty facts, so `icmRelightDue` and `icmKeepaliveBroken` are inert while lonely and the re-arm is
the sole ICM source, which is what makes the duty figure exact. A relaxed re-arm that hits
`onSessionConfigFailed` → reattach → `onAttached` resets the window → recovery runs at the aggressive
cadence automatically.

The alternative a reader reaches for first is to reuse `PowerPolicy.idleAfterScan` wholesale. It would
import BLE's 12 s `LONELY_IDLE_MS`, whose justification is GATT radio contention, and silently change today's
8 s aggressive tick; the shared part is the *rule* (`lonelyRelaxed`) and the relaxed intervals, not the
aggressive one.

## What it costs, what it does not cover, and the traps

A lonely phone on battery is discoverable on 2.4 GHz discovery windows between re-arms (the screen-off power
profile wakes every 8th window, ~4 s latency), with ICM lit 30 s in every 120–300 s. The corner that was
already dark — a 5 GHz-associated, dozing phone whose 2.4 GHz dwell is too sparse for action-frame acks
(memory: `nan-icm-doze-5ghz-dark`) — is dark between relaxed re-arms too; BLE or the tick finds it. A
BLE-only peer flapping at BLE's linger period restarts the three-minute window each time; if a trace shows
that pinning a node aggressive, the fix is a 60 s `foreignHuntUntil` sub-clock instead of a full reset, not
dropping the edge. A re-arm swallowed by a pending subscribe now waits up to 285 s for the next instead of
15 s — a pending subscribe *is* a re-arm in progress. No JVM harness runs the loop itself, so the
token-and-conflated-channel interaction is reasoned; `NanLonelyPolicyTest` pins the cadence table, the
boundary and the invariants (cooldown under the tick, never under 15 s, ICM ≤ 25 %), `PowerPolicyTest` that
`lonelyRelaxed` is `idleAfterScan`'s rule, `NanSyncPolicyTest` the blind-is-true coupling that makes the
cooldown the lever.

**Device trial owed** (two Pixels, the current build first as a control; A screen off on battery, B nearby
then removed; grep the transport tag for `state ver=`, `re-arm subscribe`, `lonely:`, `discovered`,
`subscribe config failed`, `re-attaching`, sample `dumpsys wifiaware | grep -i instant` every 10 s):

1. Alone 10 min — `cue=[]` within 150 s of B leaving, `lonely: relaxed` ~3 min later, re-arms at 120 s ± 5 s,
   ICM lit in ≤ 35 % of samples (control ≈ 100 %), no config-failed / re-attach lines.
2. Walk-up after ≥ 10 min — B screen-on at 5 m: a discovery or first-contact cue on A within 10 s and a
   `cooldown=15000` re-arm within 15 s; B screen-off with BLE off ≤ 150 s; BLE on ≤ 135 s.
3. Walking with A — one `heal=true` re-arm per motion trigger, never two within 15 s.
4. Screen on — a re-arm within 15 s and `lonely: aggressive again`.
