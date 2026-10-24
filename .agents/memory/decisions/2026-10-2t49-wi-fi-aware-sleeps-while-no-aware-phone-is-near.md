---
id: "2026-10.2t49"
slug: wi-fi-aware-sleeps-while-no-aware-phone-is-near
title: "Wi-Fi Aware sleeps while no Aware phone is near, and the person can switch it off"
date: 2026-10-24
topics: [wifi-aware, ble, battery, reliability]
---

# ADR 2026-10.2t49 — Wi-Fi Aware sleeps while no Aware phone is near, and the person can switch it off

Status: Accepted (2026-10-24; `mesh/wifiaware/NanDormancyPolicy`, `data/settings/AwareSwitch`; device trials F–O owed,
see the end)

**What was observed.** GitHub getknit/knit#44: on a Pixel 8a, Wi-Fi throughput fell from about 250 to about 50 Mbps
while Knit ran, and Bluetooth A2DP audio cut out on public transit. ADR 2026-10.486r took Instant Communication Mode for
the cause. Its Pixel 9 trial (Wi-Fi on 5180, 300 MiB LAN pulls, PHY at 1200 Mbps throughout) said otherwise: Aware off
732–788 Mbit/s, Aware attached with ICM off 227–266, ICM on 2.4 GHz the same, ICM on 5 GHz 76–238, and Aware attached
with the screen off 570–633. With Pixel Buds streaming, the controller logged no `A2DP Choppy` event with Aware off and
1–9 per 35 s with it attached, ICM or not. The cost is Aware being **attached** with the screen on, most likely the
framework's screen-on 5 GHz discovery window on channel 149 pulling a STA on another channel off it twice a second. No
app API turns that window off (the attach's `ConfigRequest` and the discovery-window intervals are system API), so the
one thing an app controls is whether it is attached.

**What changed.**

1. **Dormancy.** `NanDormancyPolicy` puts a started plane to sleep after `DORMANT_AFTER_MS` (3 min) with nothing to do:
   no cue target, no link activity, no sync, bulk or ICM relight owed, no call, no walkie link hold, not paused or in a
   cycle's settle, no responder re-file pending, no Aware candidate heard over Bluetooth, and a Bluetooth plane that
   demonstrably sees newcomers (`MeshTransport.seesNewcomers`). Charging does not prevent it: the cost is airtime, not
   only battery. A dormant plane closes its own sessions through a close of its own, never the NAN-down branch and never
   `sessionCycleWithSettle`: it refunds nothing of the attach budget (ADR 055; a refund the dormancy cycle could drive is
   the #9 binder leak), writes no `lastAvailable`, stamps no `lastReattachAt`, and records no `nan_cycle`. It wakes on a
   rising edge of the Aware candidates **near**, a bulk transfer for a candidate, the app coming on screen (which also
   keeps ADR 2026-09.535d's API 31–32 retry), the Bluetooth plane going blind, or a duty attach: 30 s from `onAttached`
   every 12 min, so an Aware-only peer whose Bluetooth is off is found within that time. It never wakes on `heal()`: the
   motion heal fires about once a minute on transit and would undo it. A wake re-attaches only through the loop's settle
   after the close (the `role=1 state=104` ghost race, 7e457907). While dormant the plane reads Healthy unless real
   evidence says otherwise (Unavailable on Wi-Fi off, deep Doze, or an `isAvailable` false held 30 s outside the close's
   own beat; Degraded once the attach budget has given up, as an awake plane reads), so neither the Wi-Fi banner nor the
   soak's health alert fires; `TransportStatus.dormant` carries it, and Diagnostics says
   "Idle — no Wi-Fi Aware phone nearby".
2. **The advert says whether a phone can be woken for.** `BleAdvertPayload.FLAG_NO_AWARE` (bit 2 of the flags byte,
   additive) is set while this phone **will not run Aware**: no hardware, the person's switch, `NANOFF`, Wi-Fi off,
   deep Doze, an abandoned attach. Inverted polarity on purpose: every older build advertises the bit clear and still
   wakes us. Never set while merely dormant or paused for a Wi-Fi Direct hand-over. A peer is a candidate only on
   positive evidence (a service-data sighting with the bit clear); a peer read over GATT (an iPhone; knit-ios has no
   Wi-Fi Aware) or one whose HELLO claims `CAP_DOORBELL` never is. Two sets, both aged at the end of every scan window:
   near to wake, heard (lingering at least one scan gap) to stay; a Coded link counts in neither.
3. **Settings › Wi-Fi Aware**, default on, phone-bound and backup-transient (`AwareSwitch`). Off stops the plane through
   `SwitchableTransport`, as `NANOFF` does; Diagnostics says it is off in Settings. It is the tool for a device whose
   Wi-Fi or Bluetooth audio struggles anyway.
4. **Low-power mode is dormant** (`LOW_POWER_OFFERED = false`, ADR 2026-10.d44b's 2026-10-24 amendment): its row is
   hidden and nothing it reads turns Aware off, so nothing the person cannot see does. A phone that had it on is moved
   to the switch set off, once.

**The alternatives.** ICM was the first suspect, and the 486r trial ruled it out as the throughput cost. Standing Aware
down automatically while Bluetooth audio plays was built in the plan and declined by the maintainer: with peers near it
would trade photo speed for a symptom the trial measured as small, and the person's switch covers the phone that needs
it. Keeping low-power mode visible beside the switch would offer two controls that do the same thing today; it stays in
the code for a future posture that does more. Waking on any Bluetooth sighting (a "has Aware" bit) would never wake for
a 2.9.0 phone, hence the inverted bit. Leaving the Bluetooth-off peer to chance was the cheaper option; the maintainer
chose the duty attach, which on a working radio leaks nothing (ADR 052) and on a refusing one goes through every gate of
`attach()`.

**What it costs.** A lone phone finds a newcomer through the Bluetooth scan's current gap (60 s screen-on, 120–300 s
screen-off, the settled floor under audio) or the newcomer's own dial, then an attach and a discovery. Bluetooth carries
chat in the meantime; Aware is the bulk lane. With a peer near, nothing changes: dormancy only fixes the alone case, the
reporter's transit case for both symptoms, and a speed test beside another Knit phone is helped only by the switch or by
the screen-off profile. On a phone where another app also holds Aware, the dormancy close does not take NAN down, so a
responder request left at zero data paths can survive as a ghost until a later NAN-down; the watchdog's Tier 2 is the
eventual cure. `seesNewcomers` keeps the last window's value while no window runs (a dial or arbiter pause), so a
newcomer arriving then waits for the pause to end or for the duty attach; letting it fall instead would wake the plane
on every such pause. A peer that links to us while our scan is paused is not a candidate until a window has sighted it,
which the duty attach also bounds.

**What keeps it true.** `NanDormancyPolicyTest` (every stay reason, the wake triggers, the duty walk, the radio evidence,
and a budget walk pinning that the attach streak and `attachAbandoned` survive dormant → duty → dormant),
`CompositeMeshTransportTest` (the push, and dormancy never firing with no Bluetooth plane), `AwareSwitchTest` (the key,
the migration), `RadioWarningTest` (no banner while dormant and available), `DiagnosticsAwareRowTest`. Logs: `aware
dormant: …`, `aware awake: <WHY> …`, `aware duty attach: …`; field log `nan_dormant` and `aware_switch`; the bridge's
`…debug.NANDORM` (hold awake, force dormant, shorten the duty) and `…debug.AWARE`.

**Device trials owed.** Never toggle Wi-Fi on a network-adb lab phone. Oracles: the log lines above, `NANDORM`, the
framework's `WifiAwareNativeApi: enableAndConfigure` and NAN-down lines, `dumpsys wifiaware`, and `dumpsys
bluetooth_manager`'s `A2DP Choppy` events. Trial F runs with no knit-peer harness near.

- **F. #44.** The P9 alone: dormant after 3 min; the 300 MiB pull at Aware-off speed between duty windows; Buds streaming
  at the Aware-off choppy count; NAN down and no request cached after the close; `NANDORM`'s `available` against
  `mUsageEnabled` every minute for 10 dormant minutes, available throughout outside Doze.
- **G. Walk-up**, within the gap the dormant phone is on or the newcomer's dial; a photo then rides an NDP.
- **H. Bluetooth-off peer**, found within one duty interval plus the window, screen off on the smallest node.
- **I. Flag.** A peer with the switch or Wi-Fi off does not wake us; an older build does; an iPhone link keeps both sets
  empty and its photos wake nothing.
- **J. No churn.** Motion heals leave a dormant phone dormant; no `coordination plane stalled` after a wake; no banner.
- **K. Switch** off and on; a beta phone with `low_power_mode` on migrates.
- **L. Budget.** `NANFAIL` armed, `NANDORM --el duty 60000`, forced dormant: the streak never drops at a `nan_dormant`
  line and `abandoned` stays set.
- **M. Wake after close**: no `state=104`, the attach after the settle, a `NANDIAL` initiate forms an NDP.
- **N. Health** through `deviceidle force-idle` / `unforce` and a Wi-Fi Direct pause and resume: no `WifiOff` banner,
  still dormant; flag flips per hour recorded.
- **O. Walk-away and the edges.** A flag-clear peer carried off leaves the sets within about 90 s plus one gap; a peer
  held about −95 dBm in every window keeps a screen-off smallest node awake for 15 min; a linked Coded peer walked to
  about −95 dBm leaves the heard set.
