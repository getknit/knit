---
id: "2026-10.2t49"
slug: wi-fi-aware-sleeps-while-no-aware-phone-is-near
title: "Wi-Fi Aware sleeps while no Aware phone is near, and the person can switch it off"
date: 2026-10-24
topics: [wifi-aware, ble, battery, reliability]
---

# ADR 2026-10.2t49 — Wi-Fi Aware sleeps while no Aware phone is near, and the person can switch it off

Status: Accepted (2026-10-24; `mesh/wifiaware/NanDormancyPolicy`, `data/settings/AwareSwitch`; device trials F–O owed,
see the end). Amended 2026-10-24 (deep idle), 2026-10-26 (the on-screen probe, draft) and 2026-10-27 (the advert says
Aware positively, draft)

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
   rising edge of the Aware candidates **near**, a bulk transfer for a candidate, the Bluetooth plane going blind, or a
   duty attach that finds someone: 30 s from `onAttached` every 12 min, so an Aware-only peer whose Bluetooth is off is
   found within that time. The app coming on screen no longer wakes it: it runs one floored probe window instead (the
   2026-10-26 amendment, which also corrects what this list once said about ADR 2026-09.535d). It never wakes on `heal()`: the
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
dormant: …`, `aware awake: <WHY> …`, `aware duty attach: …` (`aware duty attach: probe (on screen) — …` for a probe,
`aware probe skipped: <VERDICT> …` for a refused one, since the 2026-10-26 amendment); field log `nan_dormant` and `aware_switch`; the bridge's
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

## Trial results 2026-10-24 — P9 Pro XL (API 37), P8 (API 37), P3 (API 31), all on 5ceba1c; no walking

A Moto G (`fd5onyke…`, not on adb) and the P7 (`24sd5jd4…`, a translate branch) run older builds, so they advertise
the no-Aware bit clear and stay Aware candidates: the P9 could not fall dormant by itself while they were near, and the
single-phone trials forced dormancy with `…debug.NANDORM --ez now true` (the FORCED reason, exempt from the near
self-heal). The P7's NAN plane was switched off (`NANOFF`) for the empty-window trials.

- **F (#44), passed.** Forced dormant: `dumpsys wifiaware` shows no client and `mCurrentAwareConfiguration: null`,
  `mUsageEnabled: true`, the row Healthy and dormant. A 300 MiB LAN pull read 760, 789 and 786 Mbit/s dormant against
  280 and 162 awake (Wi-Fi on 5180). The natural 3-minute dormancy was not observed (the candidates above).
- **J, passed.** Two `HEAL`s left a dormant plane dormant.
- **Bluetooth toggles, passed.** Each adapter-off edge logged `bt newcomers unwatched (adapter off)` within 1 ms and woke
  a dormant plane `BLIND`; the scan was watched again about 12 s after the adapter came back.
- **I, passed.** `NANOFF` on the P3 set its bit (`bt advert noAware=true` 15:02:49), and the P9 dropped it from both sets
  by 15:03:28. Switched back on, the bit cleared about 60 s later and the P3 rejoined the sets.
- **Duty-window regression, passed.** With the P7's NAN off and a 60 s duty, a `NANICM --ez on` 26 s into a window was
  refused (`session cycle: dormant (duty=true) — nothing to cycle`), the window closed `DUTY_EMPTY` at its end, there was
  no fast-tick run, and the near-candidate self-heal then woke the plane (`SIGHTING`). The bridge's reply still says
  "(session cycled)" for a refused cycle: cosmetic.
- **L, passed.** `NANFAIL --ei count 40` across a duty window, its `DUTY_EMPTY` close and a `SIGHTING` wake: the attach
  streak ran 1–4 in the window and 5–6 after the wake, the backoff grew 3.5 → 98 s, `failTotal` 6. Nothing refunded it.
- **H, passed.** The P3 with Bluetooth off and NAN on: the dormant P9's duty attach discovered it 7 s into the window and
  stayed awake on it (`aware awake: DUTY`, stay reason cue targets).
- **M, passed.** A close and a wake 605 ms later re-attached after the settle; no `state=104` request in `dumpsys
  wifiaware` afterwards.
- **K, passed.** `AWARE --ez on false` on the P3 stopped its plane (`switchedOff`, NAN down) and the P9 dropped it from
  its sets within 30 s; on again read Healthy.
- **N — a defect.** Deep idle does not disable Aware on these phones. Under `dumpsys deviceidle force-idle` the P9 kept
  `mUsageEnabled: true`, and the P8 in real deep idle (`mForceIdle=false`, `mState=IDLE`, within 20 s of its screen going
  off) kept Aware available with its plane attached. AOSP main agrees: `WifiAwareStateManager` answers
  `ACTION_DEVICE_IDLE_MODE_CHANGED` with `reconfigure()` (the idle power profile: every 8th 2.4 GHz window, no 5 GHz),
  and `PARAM_ON_IDLE_DISABLE_AWARE` is set but never read. Yet this change counts deep idle as "won't run Aware": the
  plane read Unavailable (the Wi-Fi-off banner's state) for the whole idle and up to ~20 s after it, and the advert
  carried the no-Aware bit, so every dozing phone (on the P8, every screen-off minute) drops out of its peers' candidate
  sets while its Aware still works. Fix owed: drop `deviceIdle` from the radio evidence; keep Wi-Fi off, an
  `isAvailable` false held 30 s, and an abandoned attach. (Decided otherwise for the bit: see the amendment below.)
- **P, the natural dormancy, passed** (later, with the Moto G off and the P7 and P8 out of the lab; the P9 kept
  Bluetooth links to the P3 and an iPhone throughout). With no candidate near, the P9 slept by itself twice (`IDLE`
  after 184 s and 180 s, `heard=0 near=0`), and each 12-minute duty window closed `DUTY_EMPTY`. The P3's screen coming
  on woke it `SIGHTING` 82 s later: the P3 had dozed (API 31 does turn Aware off there), so its bit cleared 60 s after
  Aware came back, and the next scan saw it. Two Aware phones then held each other awake for over 5 minutes. With the
  P3's plane off, the P9 slept 6 min 32 s later. The candidate lapsed in 31–42 s, the cue target stayed until the
  150 s `REACHABLE_LINGER_MS` prune, and then the 180 s idle ran. NAN was down afterwards (no client, no configuration).
- **P in deep Doze — a latency, not a defect.** With the P9 dozing, the P3's plane coming back woke it 7 min 11 s
  later. Knit's scanner ran no scan between 17:14:28 and 17:22:17, because a settled node's scan gap stretches with
  sleep (ADR 2026-10.pj9w), and the first scan after the gap saw the P3 and woke the plane within 1 s. So a dozing
  phone wakes on a sighting no sooner than its next scan, with the duty attach as the bound. Before this change its
  attached NAN would have been found by the firmware's discovery. Messages are unaffected, since the Bluetooth link
  stayed up. Awake again in deep idle, the P9 discovered the P3 over NAN while `wontRun` read true and its advert had
  carried the no-Aware bit since 17:16:21. That is N again.
- **A2DP while dormant, passed.** Pixel Buds streaming from the P9, screen on, two reps of three 60 s arms, counting
  the stack's `A2DP Choppy` quality reports (`dumpsys bluetooth_manager`): awake (held, the P3's Aware on) 2 and 8,
  dormant (forced, the P3's Aware off, NAN down) 0 and 0, Aware switched off 0 and 1. The two minutes of streaming
  awake before the arms logged 14. Dormant matches Aware off, as 486r's trial had Aware off at 0 and attached at 3–9.
  The buffer holds only the latest dozen or so reports, so the first awake arm's count may be low. The dormant and off
  windows sit inside one dump with reports on both sides, so their counts are complete.
- **Q, Aware in deep Doze (does N's `deviceIdle` earn its place?).** The P7 (API 37, 2.10.0-beta.0), the P8 and the
  P9, all on battery on a 5180 STA, all in natural deep idle the whole time (the P7 past its first hour, with
  `mNextIdleDelay` at 2 h). Each kept `mUsageEnabled: true` and an attached client. Results:
  - **The P7 worked in deep idle.** It was discovered 3–6 s after its plane re-attached, found the P8 in 3 s, and
    took four photos from the P9 over NAN with ICM on and off. Each photo was an NDP up 0.7–1.3 s after the arm, and
    700 KB landed in 158–1067 ms.
  - **The P9 was slow to be found with its screen off.** After any re-attach it found both others in 2.6–4.5 s, but
    they took 59–99 s to find it, and the P8 never did within 140 s. With its screen on, and still in deep idle (on
    these Pixels deep idle holds until unlock), the others found it in 5.5–9 s.

  So deep idle is not the property that breaks NAN here. The P9's partial failure follows its screen, while
  `isDeviceIdleMode` and `isAvailable` both read the same through it.
- **R, the amendment below, passed** (20:08–20:16, on the amendment's build). The P7, still on 2.10.0-beta.0, has no
  bit and always counts as a candidate, so both planes were forced dormant.
  - **The P9 (API 37).** Dormant in deep idle, it read Healthy throughout, with `radioOff: false` and
    `wontRun: true`; on 5ceba1c this read Unavailable. A stepped maintenance window logged
    `device idle edge (idle=false)` within 3 s, and both windows ended on their own inside 30 s with the bit still
    set. Held out of idle with `dumpsys deviceidle disable deep`, the bit cleared exactly 60 s after the edge. Forced
    back in, `noAware=true` followed the `idle=true` edge 2 ms later.
  - **The P3 (API 31).** Under `force-idle` its bit went up on the edge, `isAvailable` went false, and `radioOff`
    turned true once the 30 s hold had run after the 30 s beat of the dormancy close just before. The dormant row read
    Unavailable within the next tick, about 24 s later. Unlocked, it logged the `idle=false` edge and read available
    and Healthy within 2 s.
- **Not run:** G, O and the Coded and −95 dBm cells (they need phones moved).

## Amendment 2026-10-24 — deep idle is in the advert's bit, not in the health

Trials N and Q found deep idle harmless to Aware on the API 37 Pixels: `mUsageEnabled` and `isAvailable` stay true,
discovery and NDPs work (four 700 KB photos to a P7 more than an hour into deep idle), and AOSP main's
`WifiAwareStateManager` answers `ACTION_DEVICE_IDLE_MODE_CHANGED` with `reconfigure()` alone. Counting deep idle as
"the radio is off" raised the "turn on Wi-Fi" banner on every dozing dormant phone. Not every phone was measured, so
the evidence splits into two readers in `NanDormancyPolicy`:

1. **Health** (`radioOff`, read by `dormantHealth`): Wi-Fi off, or `isAvailable` false held 30 s outside the close's
   beat. Deep idle alone never makes a dormant plane Unavailable. A phone whose Aware does go off in Doze (the Pixel 3,
   API 31) reads `isAvailable` false, and the hold covers it.
2. **The advert's bit** (`wontRun`, written into "won't run here" and from there into `FLAG_NO_AWARE`): the health's
   evidence, deep idle, or an abandoned attach. The bit now means "don't count on my Aware right now", not "my Aware is
   off". A wrong set only delays a peer's wake, where a wrong Unavailable tells the person to fix a radio that works.
3. **Prompt edges.** `refreshAwareEvidence` runs on the idle broadcast, through a receiver of its own registered and
   unregistered beside the availability receiver (`ContextCompat.registerReceiver`, `RECEIVER_NOT_EXPORTED`, guarded).
   It never goes through `handleAvailabilityChanged`, whose refunds, attaches and teardown an idle edge must not run.
   Before this, Doze-stretched watchdog ticks raised the bit two minutes into idle on the P9, and a stale Unavailable
   outlived idle by about 20 s.

Accepted costs. On these Pixels deep idle holds until unlock, so a lit lock screen still sets the bit. A deep
maintenance window reads not-idle, so its start begins the bit's 60 s clear (`NoAwareFlag`): a window shorter than
that leaves the bit set, and a longer one clears it until idle resumes and sets it again. No debounce is added beyond
`NoAwareFlag`'s.

## Amendment 2026-10-26 — Knit coming on screen runs a probe, not a wake (GitHub getknit/knit#44)

**What was observed.** The #44 reporter is alone on transit and opens Knit often. Each open woke a dormant plane
(`Why.VISIBLE`), which then stayed attached with the screen on for at least `DORMANT_AFTER_MS` (3 min): the state this
ADR's trials measured at about 250 against 750 Mbit/s of STA throughput, with A2DP choppy events. So every open brought
the slowdown back.

**Why the wake is not needed.** Resume runs `meshManager.heal()` (`KnitApp`), which wakes the Bluetooth scan loop; a
candidate it sights wakes Aware through `SIGHTING`, and older builds are candidates because `FLAG_NO_AWARE` has inverted
polarity. The one Aware peer Bluetooth cannot find is one with Bluetooth off, which the duty attach finds within
`DUTY_INTERVAL_MS`.

**What changed.**

1. **Opening Knit wakes nothing.** `Why.VISIBLE` is retired. The rising edge of `appVisible` runs
   `NanDormancyPolicy.probe` on the handler thread instead, in one block with every write, then pokes the loop.
2. **The probe is one duty window, now.** It runs only while dormant between duties (no window running, no session,
   not paused), never while a candidate is already near (its rising edge lost to a queued close: the dormant tick's
   `nearWhileDormant` self-heal, FORCED exempt, so the open wakes `SIGHTING` instead of probing), never within
   `PROBE_FLOOR_MS` (5 min) of the later of the last window's start (a duty's or a probe's,
   `lastWindowStartedAt`, never reset by a wake or a close) and the last dormancy close (`dormantClosedAt`), both on
   `elapsedRealtime`, and only when the attach budget lets a duty run now (`attachBudgetAllows`: not given up, past the
   streak's backoff). Counting from the close too means a reopen right after an IDLE close, the plane just attached
   three minutes and found nobody, does not attach again. Any other verdict drops the probe (`aware probe skipped:
   <VERDICT> …`): nothing is armed, so a backed-off probe never fires later off screen and never pulls the duty
   schedule in. The window started is a duty window in every way: 30 s from `onAttached`, the same ICM demand stamp
   (`NanIcmPolicy` still refuses under Bluetooth audio), a wake on a find keeping the session, otherwise a `DUTY_EMPTY`
   close that refunds nothing (ADR 052, 055; getknit/Knit#9), and the next duty scheduled from that close. It leaves
   `dutyDueAt` as it was until that close.
3. **Telling a probe from a duty.** No new close reason: `closeStands` accepts only IDLE, DUTY_EMPTY and FORCED, so any
   other would be refused on every tick and leave the plane attached with a window running for good. The start logs
   `aware duty attach: probe (on screen) — …` (the `aware duty attach: ` prefix byte-identical), and a probe that finds
   someone wakes as `PROBE` (a wake reason only), which the `nan_dormant` field line carries. `probeActive` is cleared
   wherever `dutyActive` is (the close, a wake, `pause`, a stop), so a later duty or a plane woken by a sighting is never
   labelled a probe. `…debug.NANDORM` reports `probeActive` and `nextProbeAllowedInMs` (the floor alone).

**A correction.** Item 1 above said the screen-on wake "keeps ADR 2026-09.535d's API 31–32 retry". It never was that
retry. 535d's cure is `KnitApp`'s ON_RESUME `MeshService.start` from a visible activity, plus `heal()` →
`retryOffScreenBlocked`, and both still run on every resume; an awake plane blocked off screen stays awake through the
`offScreenBlocked` stay reason. A probe attaches while the app is visible, so on API 29–32 it publishes as that retry
needs.

**What it costs.** A peer in deep idle advertises `FLAG_NO_AWARE` (the 2026-10-24 amendment), so it is not a candidate
and its sighting wakes nothing; a photo to it rides Bluetooth until the next probe or duty, up to 12 min, where the
screen-on wake used to attach at once. A person who opens Knit often pays one 30 s attached window per 5 min at most,
not three minutes per open. A probe pushes the next scheduled duty out to 12 min after its own close.

**What keeps it true.** `NanDormancyPolicyTest`: the verdict table (first open, again inside 5 min, after it, a
scheduled duty or an IDLE close inside it, awake, mid-duty, paused, the budget, a candidate already near), a day of opens every minute (at most one
window per floor, the schedule never pulled in) and a week's budget walk with opens every 5 min (no window inside the
backoff, none after the give-up). Removing the floor fails three of them, removing the budget gate two, and removing the near check one.

**Device trials owed.** On the P9, alone and dormant: open Knit → one `aware duty attach: probe` window, then `aware
dormant: DUTY_EMPTY` and no client in `dumpsys wifiaware`; reopen within 5 min → `aware probe skipped: FLOOR`, no
attach; reopen after 5 min → a probe. Beside a Bluetooth-off Aware peer, an open finds it through a probe (`aware awake:
PROBE`). Beside an Aware peer with Bluetooth on, an open wakes through `SIGHTING` within a scan. And:

- **Re-open after an IDLE close:** reopen within 5 min of it → no `aware duty attach` line.
- **Budget:** `NANFAIL --ei count 40`, force dormant, open Knit 3× over 15 min → the streak never drops, no probe starts
  while backed off (`aware probe skipped: BUDGET`), and none fires later off screen.
- **Stale label:** wake on `SIGHTING` before the probe starts → `NANDORM` shows `probeActive: false`, and the next
  scheduled window logs as a plain duty.
- **Pixel 3 (API 31) dormant, reopen:** the probe publishes with no `publish refused off screen` line.
- **Oracles:** `aware dormant: DUTY_EMPTY` after each empty probe, and `dumpsys wifiaware` shows no client after it.

### Probe trial results 2026-10-26 — P9 (API 37), P8 (API 37), P3 (API 31), all on 263c771d

The other phones' Aware was switched off with `NANOFF` (the P7 too, on the same build) so the subject was alone. No
walking. Every subject went dormant by itself (`IDLE` after 180 s once the last candidate had lapsed).

- **Probe and floor, passed (P9).** An open 25 s after an IDLE close logged `aware probe skipped: FLOOR`, with no attach.
  An open after `nextProbeAllowedInMs` read 0 ran `aware duty attach: probe (on screen)`, then `aware dormant:
  DUTY_EMPTY` 30 s later; `dumpsys wifiaware` showed a configuration during the window and `null` after it. An open
  75 s after that probe's close skipped on `FLOOR`, with 255 s left, counted from the close.
- **Behind a password keyguard (P8).** An open behind the lock screen still raises the visible edge and is judged
  like any other.
- **Budget (P8), partly.** With `NANFAIL --ei count 40`, the probe's attaches failed at streak 1–4. The `DUTY_EMPTY`
  close refunded nothing, the next probe failed at streak 5, and nothing attached after either close. The `BUDGET` skip
  itself was not reached: the backoff (44 s at most here) is always over before the 5-min floor ends, so on a phone
  it needs a streak long enough to give up. The table tests cover it.
- **Bluetooth-off peer, passed (P8 beside the P3 with Bluetooth off).** The probe discovered the P3 within 1.2 s
  (`aware awake: PROBE`, keeping the session). The NDP then failed its handshake repeatedly (`stale=true`). No Aware
  link had formed between any of the lab phones before the install either, so this is not the probe's doing.
- **Label after a wake, passed (P8).** After the `PROBE` wake, the next scheduled duty (interval 90 s) logged a
  plain `aware duty attach: dormant …` and woke `DUTY`.
- **API 31, passed (P3, screen on, Doze held off).** The probe re-filed the responder and the publish (`publish config
  updated`) with no off-screen refusal, then closed `DUTY_EMPTY`.
- **Bluetooth-on peer (P3 beside the P8).** The P8 dozing with its screen off, and then with it on but locked,
  stayed in deep idle (on these Pixels deep idle holds until unlock), so its advert kept `FLAG_NO_AWARE` and the
  dormant P3 never woke for it: the cost named above. With deep idle lifted (`dumpsys deviceidle disable deep`), the
  bit cleared, and the P3 woke `SIGHTING` about two minutes later, one or two of its scan gaps.
- **Heavy opener, passed (P3, one open a minute for 30 min).** Five probe windows ran, at 18:00:01, 18:05:51,
  18:11:41, 18:17:51 and 18:23:38: one every 5 min 50 s to 6 min, each closing `DUTY_EMPTY` after 30 s, so the plane was
  attached about 9% of the time. Every other open skipped on `FLOOR`. `nextDutyInMs` only ever counted down
  between windows, and the scheduled duty never ran, being pushed out by each probe's close. STA throughput was not
  measured.
- **Not run:** the lost-edge `NEAR` wake (it needs a candidate's rising edge lost to a queued close), and its repeats
  under a Wi-Fi Direct pause and under backoff.

## Amendment 2026-10-27 — the advert says Aware positively (GitHub getknit/knit#44)

**What changed.** Bit 2 of the advert's flags byte flips meaning in place: `BleAdvertPayload.FLAG_AWARE` (0x04, the
bit `FLAG_NO_AWARE` held) is set while a peer may count on this phone's Aware now. Reusing the bit is safe because the
inverted form never shipped in a release: it reached main on 2026-10-24 and no tag carries it, and every released build
(2.10.0 and older) leaves bit 2 clear. The evidence behind it is unchanged (item 2 and the 2026-10-24 amendment), and so
is the debounce, mirrored: the bit comes down at once on "won't run" and goes up only after 60 s held "will run", on
`ElapsedWait` (`AwareFlag`, which replaces `NoAwareFlag`). At the mesh's start it reads the complement of
`awareWontRun`, which is true by default and before the plane says otherwise, so a phone that cannot vouch for its
Aware starts with the bit clear. A peer is an Aware candidate only on a service-data sighting with bit 2 set
(`AwareCandidateTracker.note`); the GATT `forget`, the `CAP_DOORBELL` and Coded-link rules and the heard set's lapse are
unchanged. The transport keeps one state (`awareAdvertised`), and `BleAdvertPayload.flags(…, aware)` is the only place
the bit is built. Logs: `bt advert aware=<bool>` replaces `bt advert noAware=`, and `bt state` carries `aware=` in the
old field's place (no harness reads either).

**Why.** Under the inverted bit, any older build counted as a candidate even with its Aware off or absent, because it
could not say "don't count on me". An older peer whose Aware is running and in range holds us awake through
`cueTargets` regardless, once discovery finds it, so the change makes only one kind of peer newly harmless: an older
one whose Aware is not running. The maintainer accepts the costs below as temporary.

It supersedes three statements above: item 2's "Inverted polarity on purpose: every older build advertises the bit
clear and still wakes us"; the alternatives paragraph's rejection of a "has Aware" bit because it would never wake for
an older phone; and the 2026-10-26 amendment's "older builds are candidates because `FLAG_NO_AWARE` has inverted
polarity". Trial I's quoted `bt advert noAware=true`, and the other trial lines that name the old bit, stay as the
history they record.

**What it costs.**

- Every released build (2.10.0 and older) never sets bit 2, so it is never a candidate. A dormant plane reaches such a
  peer only by the 12-min duty attach (`DUTY_INTERVAL_MS`, skipped while the attach budget says no) or the on-screen
  probe floored at 5 min (`PROBE_FLOOR_MS`). A Bluetooth dial makes no candidate, and a dormant plane has no session to
  be found on.
- Once discovery finds the peer, `cueTargets` and `linkActivity` hold both planes awake while their cues land within
  `REACHABLE_LINGER_MS`.
- A photo in the meantime rides Bluetooth.
- An older peer has no dormancy and stays attached whatever we do.
- Temporary: lab phones on main builds from 2026-10-24 until this change read the bit backwards until reflashed: such
  a phone counts a new build whose Aware is off as a candidate, and never counts one whose Aware runs. The reverse
  holds too: a new build counts such a phone as a candidate while its Aware is off or in deep idle (a dormant plane
  wakes on its sighting for nothing) and never while its Aware runs.
- knit-ios reads the flags byte without validating it and should reserve bit 2 (a paired follow-up, not in this change).

**What keeps it true.** `BleAdvertPayloadTest` (`FLAG_AWARE` is 0x04 and its own; `aware = true` adds bit 2 alone and
`aware = false` sets neither bit 2 nor bit 3, over every `flags()` input and in the bytes `advert()` builds, the
function `readvertise` encodes with; a 23-byte advert and flags 0x03 and 0x00 read not-aware), `AwareCandidateTrackerTest`
(those older adverts, parsed, are not candidates; bit 2 set is), `AwareFlagTest` (down at once, up after 60 s held, a
flicker restarts the wait).

**Device trials owed.** Every lab phone runs this build or one older than 2026-10-24, never a mix with a main build
from 2026-10-24 until this change. Oracles: `bt advert aware=`, `bt state`'s `awareNear=` / `awareHeard=` / `aware=`,
`aware dormant: …` and `aware awake: <WHY> …`, `NANDORM`'s stay reason.

- **S. Dormancy beside an older build with its Aware off.** The P9 on this build beside the P7 on an older build,
  Bluetooth on and Aware off (`NANOFF`). The P7 is absent from the P9's `awareNear=` / `awareHeard=`, and the P9 logs
  `aware dormant: IDLE` within about 180 s.
- **V. The older peer's Aware comes back.** Then lift the P7's `NANOFF`: the P9 logs `aware awake: DUTY` or `PROBE`,
  then `NANDORM` reads stay reason `cue targets`, held for 15 min with no `aware dormant:` line.
- **T. Sighting wake.** The P3 on this build at `bt advert aware=true` wakes a dormant P9 `SIGHTING` within one of the
  P9's scan gaps.
