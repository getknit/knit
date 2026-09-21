---
id: "2026-09.w3xk"
slug: a-screen-on-lonely-node-relaxes-its-bluetooth-scan-after-three-minutes
title: "A screen-on lonely node relaxes its Bluetooth scan after three minutes"
date: 2026-09-21
topics: [bluetooth, battery]
---

# ADR 2026-09.w3xk — A screen-on lonely node relaxes its Bluetooth scan after three minutes

Status: Accepted (2026-09-21; `mesh/power/PowerPolicy.idleAfterScan`, device trial owed — see the end)

Work item knit/knit-next#65, from the 2026-09-18 battery review. `PowerPolicy.idleAfterScan` gave a zero-link
node the aggressive rejoin cadence — a 12 s BALANCED scan window, then the 12 s `LONELY_IDLE_MS` gap, about
12.5 % receiver duty — for as long as the screen was on or the charger was in; only a screen-off node on battery
relaxed after `LONELY_AGGRESSIVE_WINDOW_MS` (3 min). Screen on and alone is the most common state a phone is
in — the owner is using it, for something else, with nobody around — and it scanned half the time
indefinitely. ADR 2026-09.kb68 had already lifted the same three-minute rule into `PowerPolicy.lonelyRelaxed`
for the Wi-Fi Aware re-arm, with the screen-on exception carried along ("interactive or charging never
relaxes"), so the exception lived in the shared predicate and both radios inherited it.

## What changed

**The shared rule is "alone ≥ 3 min, not charging"; each radio picks its relaxed cadence.** `lonelyRelaxed`
lost its interactive clause. `idleAfterScan` past the window: a screen-on node idles
`LONELY_RELAXED_ACTIVE_IDLE_MS` = 60 s between its 12 s BALANCED windows (≈ 4 % receiver duty, from 12.5 %),
a screen-off node the duty cycle's base interval (120 s / 300 s) as before, a charging node never relaxes
(the wall pays). The scan window and the scan mode are untouched — the *gap* is what grows — so a sighting
inside a window is exactly as likely as today.

**The NAN loop keeps its screen-on exception on its own side.** `NanLonelyPolicy.cadence` now tests
`power.interactive || !lonelyRelaxed(…)`, so the Wi-Fi Aware re-arm is byte-identical to kb68 (8 s tick,
15 s cooldown while screen-on and lonely) and kb68's still-owed device trial is unchanged. This is not an
oversight: the only relaxed tick the policy has for an interactive node is the duty cycle's 30 s, and ICM is
lit 30 s per re-arm — that cadence would buy a subscribe close/reopen every 30 s and leave ICM at ~100 %.
A relaxed interactive cadence for NAN needs a tick of its own (≥ 60–120 s) and a re-run of kb68's trial;
it is deferred in `roadmap.md`, and `NanLonelyPolicyTest.interactiveOrChargingNeverRelaxes` pins the
divergence (`lonelyRelaxed` true, `Cadence.relaxed` false) so dropping the clause reads as a decision.

**A device oracle.** `BluetoothMeshTransport.scanLoop` logs `bt scan lonely: relaxed idle=…ms (alone …ms)`
and `bt scan lonely: aggressive again` once per edge (the twin of the NAN `lonely:` lines), and the 60 s
`bt state` line carries `lonely=`.

**What already covers the latency, left alone.** A walk-up while relaxed is found within one gap at worst
(≈ 72 s). The advert is always-on, so a smaller-id peer connects to us regardless of our scan; and the
loop's idle is a `withTimeoutOrNull(scanWake)`, ended early by `heal()` (app resume, motion, the 15-min
alarm), a `PowerState` edge (screen on, plug in), the adapter's `STATE_ON`, and NAN's `onForeignReachable`
rising edge — each runs an immediate 12 s scan. No fresh three-minute window on a screen-on edge: one
immediate scan is the same purchase kb68 makes for a heal, and a phone that cycles its screen all day would
otherwise never relax.

The alternatives: dropping the scan mode to LOW_POWER at 12 s / 12 s (≈ 5 %, but it lowers the odds a short
advert burst lands in a window and needs the mode plumbed through the policy); reusing the interactive duty
cycle's 30 s (≈ 7 %, no new constant — chosen against because the review asked for "12 s on / 60 s off"
and the extra 30 s of latency is inside what the wakes above already cover); relaxing NAN too (above).

## What it costs, what it does not cover, and the traps

A BLE-only walk-up (no NAN on either side, our id the larger) while the screen is on and we have been alone
three minutes is found 30–60 s later than before, up to ≈ 72 s. `lonelyForMs` on the BLE side is
`elapsed() − lastLinkOrStartAt` (start and link registration are its only writers), so a sighted-but-unlinked
peer does not restart the window — the same as before this ADR. The rule's semantics changed: **`lonelyRelaxed`
no longer implies screen-off.** Anything new that reads it must decide the interactive case for itself, as
`NanLonelyPolicy` does; `PowerPolicyTest.lonelyRelaxedIsTheRuleIdleAfterScanApplies` holds it to "not the
12 s gap" on the BLE side. No JVM harness runs `scanLoop`; the log lines are the trial's oracle.

**Device trial owed** (one Pixel alone, on battery, screen on, Knit in the background, adb disconnected
except to pull logs — memory `network-adb-keepalive-battery-drain`; grep the `BluetoothMeshTransport` tag):

1. Alone — `bt scan lonely: relaxed idle=60000ms (alone ≈180000ms)` about 3 min after start, then scanner
   starts ≈ 72 s apart; `bt state … lonely=` climbing on the 60 s line.
2. Plug in — `aggressive again` within one loop iteration; unplug — relaxed again 3 min later.
3. Screen off — the next idle is 120000 (kb68's path); screen on — one immediate scan, idle back to 60000,
   no `aggressive again`.
4. Walk-up B, larger id on A's side — A links within ≈ 72 s plus the connect; with NAN up on both, the
   `onForeignReachable` wake makes it well under that.
5. `WifiAwareTransport` tag unchanged from kb68: screen on and lonely still re-arms at `cooldown=15000`.
