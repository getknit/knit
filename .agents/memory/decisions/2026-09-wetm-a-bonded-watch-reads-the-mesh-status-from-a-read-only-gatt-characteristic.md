---
id: "2026-09.wetm"
slug: a-bonded-watch-reads-the-mesh-status-from-a-read-only-gatt-characteristic
title: "A bonded watch reads the mesh status from a read-only GATT characteristic"
date: 2026-09-26
topics: [wear, ble, privacy]
---

# ADR 2026-09.wetm — A bonded watch reads the mesh status from a read-only GATT characteristic

Status: Accepted as a **prototype** (2026-09-26; `wearstatus/`, `mesh/wear/WearStatusSource`,
`mesh/bluetooth/wear/WearStatusServer`, the opt-in `:wear` module). Dark in release behind
`BuildConfig.WEAR_STATUS`; amends 2026-09.2v2t's "stays on the phone" for a bonded watch only, and only while lit.

**What was asked.** A Wear OS watch face showing the mesh at a glance: short-range peers nearby, one word for
its health, which planes are up (BLE, Wi-Fi Aware, LoRa, the spool) and — for fun — the Your mesh screen's
"passed along" total. A watch face cannot compute any of that; it can only draw what a complication data source
on the watch hands it, so the real question was how the numbers cross from the phone to the watch.

**What changed, and what the alternatives were.** The phone opens a **read-only GATT service** (one
characteristic, `wearstatus/WearStatusUuids`) while `MeshService` runs, paused included, and the watch app
connects to the phone it is already bonded to, reads once, and closes. The snapshot is a fixed 13-byte
little-endian layout (`WearStatusCodec`, under a default ATT MTU's 20-byte payload, so no MTU exchange and no
long read), computed at read time from the flows the app's own surfaces read — `neighborCount`,
`transportStatuses`, `LoraStatusRepository.facts`, `RelayStatusRepository.facts`, `ContributionLedger.totals`,
the mesh-enabled and pause keys — so the watch can never say anything the header would not.

- The **Wear Data Layer** (`play-services-wearable`) is the standard route and the first one a reader reaches
  for. It puts GMS in the phone APK, which the app is built without and which F-Droid's
  reproducible build cannot carry; it would have needed the build's first product flavor.
- The **ongoing notification** already carries a count, but Wear does not bridge ongoing notifications, and it is
  not a complication.
- A **custom watch face** instead of complications: Play takes only Watch Face Format faces, which run no code.
  Four complication data sources (nearby, health, radios, relayed) put the numbers on any face, a Knit one
  included later.

The watch finds the phone **without a scan**: its remembered address, then every bonded device, phones first,
keeping the one that exposes the service, over LE (twice, 750 ms apart) and then GATT over BR/EDR.

**What the device trial found** (Pixel 9 Pro XL + Pixel Watch 3 at API 37, 2026-09-26):

- The watch's bond to its phone is **BR/EDR only** (`dumpsys bluetooth_manager`: `Pairing Algorithm … LE:N/A`,
  `ACL BR/EDR:Y LE:N`), yet it reads the service over **LE** whenever the phone is LE-connectable, and the stack
  satisfies the encrypted read without a prompt. **GATT over the BR/EDR link fails** with 133 every time.
- While the mesh runs, the phone's connectable presence advert is what makes it reachable. **A pause takes that
  advert down**, and the first build then timed out and kept drawing the last "Linked · 4 nearby" from its
  cache. So the server raises **its own advert for exactly the length of a pause** (legacy, connectable,
  empty, one-second interval, medium power; down on resume or at the deadline), and the watch now reads
  "Paused". Only then, so it never contends with the mesh's presence and side-channel sets for a controller slot.
- One LE connect in six failed with a fast 133 (the stack's generic error); a second LE attempt 750 ms later
  cleared it — 8 of 8 reads after the retry, 0.7-6.4 s each.

**What it costs, what it does not cover, and the traps.**

- **Privacy.** The relayed count and the rest now leave the phone — to a *bonded* central, over an *encrypted*
  read (`PERMISSION_READ_ENCRYPTED`, and the read handler re-checks the bond), never framed, never on the mesh.
  That is the amendment to 2v2t, and it is why the flag stays off in release until this ADR is revisited with
  a device trial and a user-facing switch.
- **Off and Paused report no radio.** A stopped transport keeps its last health (the `MeshOffBanner` rule), so
  `WearStatusPolicy` zeroes the planes and the count whenever the mesh is off or paused. Pinned by
  `WearStatusPolicyTest`.
- **One codec, compiled twice.** `:wear` compiles `app/.../wearstatus/` through a source-directory reference, so
  that package imports nothing but `java.*`/`kotlin.*`. `WearStatusCodecTest` and `:wear`'s `StatusTextTest`
  decode the same golden vector; a layout change moves both, and a new field is appended (decode ignores
  trailing bytes; a new major version decodes to "no data").
- **A Stop is not a pause.** Stop destroys `MeshService` and the server with it, so the watch cannot read "Off":
  its last copy ages out after `StatusText.STALE_MS` (six minutes — one missed 300 s refresh plus slack) and
  it draws "–". The same holds for Bluetooth off and out of range.
- **The pause advert bends the pause.** A pause means radios down; this keeps one quiet, dataless advert up (no
  scan, no mesh link) so the wearer can see the pause. It is a debug-only prototype cost until the release
  decision.
- **Cost on the phone:** while the server is open the relay facts poll on their 5 s ticker (a second collector),
  and a watch's LE link takes one controller connection slot for the few seconds of a read, every five minutes
  per face refresh (the four complications share one read through a 60 s cache).
- `:wear` is in the build only under `-Pknit.wear=true`, so F-Droid's rebuild and CI never configure it; its
  lockfile is `wear/gradle.lockfile`, and `ReleaseClasspathNoticesTest` does not see it (nothing of it ships).

## Amendment 2026-09-27 — the watch surfaces, Material 3 Expressive

The prototype's plain four short texts and text-list screen were redesigned against the Wear OS 6/7 guidance
(Material 3 Expressive). Nothing on the phone or the wire moved; the snapshot and its golden vector are
unchanged.

- **Complications offer every type their data suits**, so a face picks the richest it has a slot for: radios as
  `WEIGHTED_ELEMENTS` (one equal slice per plane the phone *has*, green up / amber weak / red down, "live/have"
  in the middle — Wear OS 4+), nearby as `RANGED_VALUE` (a gauge to 8 peers, the text keeps the true count),
  health as a tinted `SMALL_IMAGE` badge or a `MONOCHROMATIC_IMAGE`, and a `LONG_TEXT` sentence for all four.
  The colours are one semantic `Tone` per state and plane, shared by every surface.
- **Staleness is a complication timeline**, not only the next request: the live data now, the no-data face
  from `STALE_MS` after the read, so a face greys out on its own clock (verified on the Wear OS 4 emulator's
  Utilita face). A face too old for timelines keeps the live data.
- **A tile** (`MeshTileService`, ProtoLayout Material3 1.4 / Tiles 1.6): the state as its title, three data
  cards (nearby, radios up, relayed — numerals autosize, at most nine steps and never below zero, or the
  renderer rejects the layout), a Refresh edge button, dynamic colour with Knit's coral as the fallback. A tile
  request never waits on Bluetooth: it draws the cache and starts a background read (`StatusRefresh`) that asks
  for a redraw. The last click id can ride along on that redraw, so Refresh is debounced 15 s from the last
  forced read or it would loop.
- **The app** is `AppScaffold` + `ScreenScaffold` + `TransformingLazyColumn` with an `EdgeButton`, dynamic colour
  (Wear OS 5+) over a coral fallback, and a hero ring: one arc per plane in its tone around the nearby count.
- **Wear Widgets** (Wear OS 7, `androidx.glance.wear` on Remote Compose) are the successor to tiles, but alpha
  only (glance-wear 1.0.0-alpha19, 2026-09-23), so the stable-only rule keeps the tile. Revisit when they ship
  stable: the tile's three cards are the 2x2 widget's content.
- **The reader has a failure floor** (`FAIL_FLOOR_MS`, 45 s): an unforced read after one that found no phone
  returns the cache, so four complications, the tile and the app asking together cost one round of connects.
- A debug-only `DemoReceiver` (shell-only, `DUMP`) stores a snapshot as if the phone had answered, for design
  passes on an emulator with no phone.

## Amendment 2026-09-27 (2) — people, not radios

A ring of four radio states looked half-finished on the common BLE-only or BLE+NAN phone, and radio health is
not what a wearer glances for. The surfaces now lead with who is reachable, and name a radio only when it
needs attention.

- **The snapshot grew to exactly 20 bytes** (still one default-MTU read, no long read): after the 13-byte
  prefix, `carrying` (u16, the Your mesh screen's "carrying for others"), `far` (u8, peers reached only over
  LoRa or the relay), and eight 4-bit `WearLink` masks, one per peer the watch draws (BLE, NAN, LoRa, relay
  bits; a zero nibble ends the list). Pinned by a second golden vector, `GOLDEN_EXTENDED`, in both modules;
  a 13-byte read from an older phone decodes with `extra == null` and the watch falls back to plain lines.
- **Privacy:** a mask says which planes reach *a* peer, in the phone's node-id order; no id, name or key
  crosses. The near / far split is Diagnostics' own (`neighbors` vs `reachable` + `spoolPresentPeers`), so the
  map never draws a peer nearer than that screen lists it. Up to two of the eight slots go to far peers.
- **The app's hero is a peer map**, drawn soft (M3 Expressive): this phone a slowly turning "cookie" shape
  (`graphics-shapes`) with the nearby count in it, short-range peers as filled bubbles close in, far ones
  hollow further out, each joined by a curved line in its plane's style (fine Bluetooth, wide ribbon Wi-Fi
  Aware, wave LoRa, dotted relay) with a legend of only the styles drawn. Placement is jittered by slot, never
  randomly, and the far ring turns to clear the near spokes, so the map is the same every reading. Alone, the
  cookie breathes into a circle; reduce-motion stills it all. Radios appear as cards only
  when one the phone has is weak or down; one never set up is not a problem.
- **The watch keeps its own day** (`StatusHistory`, a private file of one line per good read, 36 h kept):
  relayed today (the sum of the all-time counter's rises across today's readings — a fall is a new baseline,
  a pair across midnight counts only within one 10-minute span), time linked / alone / weak / resting (each
  reading covers at most one span, so a gap is not painted), the busiest moment, the current linked run, and a
  12-hour nearby chart. None of it leaves the watch.
- **Complications:** Radios became **Your day** (a `WEIGHTED_ELEMENTS` pie of the day's states, or linked
  time); **Relayed** is today's rise; **Carrying** is new. The history-based ones answer without a staleness
  timeline — they stay true when the phone goes quiet. The tile's cards are nearby, today and held, and a radio
  problem ("LoRa offline") takes the title.


## Amendment 2026-09-27 (3) — RFCOMM over the Classic link first, LE the fallback

The Pixel Watch 3 went to "Phone out of reach" on a busy mesh phone and stayed there. The phone's host had
no GATT connection left to give it: `dumpsys bluetooth_manager` on the Pixel 9 read
`TCB (GATT_MAX_PHY_CHANNEL: 8) in_use: 8`. Every LE link takes one of those slots, the mesh's L2CAP links
included, and here five mesh links, the Meshtastic board, a ring and a tracker held all eight. Each LE connect
from the watch was dropped at once (`gatt_le_connect_cback: … due to out of resources`; the watch logs HCI 19,
the phone 22). The last good read came eleven minutes before the sixth and seventh LE links filled the table.
Nothing in the phone app can see or reserve a slot, and a mesh capped to leave one would lose a peer while
another app took the slot anyway.

The same logs showed a second cost the first trial missed. The bond carries no LE keys, so every successful
LE read ran a fresh LE pairing (a bond event on both sides per read, about every 90 s while the app polled),
and one link failed with a MIC error (61). "The stack satisfies the encrypted read without a prompt" was a
silent re-pairing that rewrote the watch's bond record on every read.

- **The watch now reads over RFCOMM first**, on the Classic link a paired watch already keeps to its phone: a
  secure (authenticated, encrypted) server socket under `WearStatusUuids.RFCOMM`'s SDP record, opened and
  closed with the GATT service, reopened on `STATE_ON`. It takes no LE connection and no GATT slot, needs no
  advert, and the existing bond encrypts it. Both ends re-check the bond, as the GATT read does.
- **The stream carries one `WearStatusFrame`**: a length byte, then the unchanged codec bytes (pure, in
  `wearstatus/`, pinned by `WearStatusFrameTest`). The watch closes once it holds the frame; the phone lingers
  up to 3 s for that close, because a close right behind the write can drop the tail.
- **LE GATT stays as the fallback**, twice as before, until other watches show which transport their bond
  carries; the pause advert stays with it. GATT over BR/EDR is dropped: it never answered on the lab pair, and
  RFCOMM is the Classic route now.
- **Order** (`ReadRoutes`, tested): RFCOMM, LE, LE; the route that answered last leads, so a watch whose phone
  predates the RFCOMM server does not pay for the SDP lookup on every read; a device the stack knows only over
  LE skips RFCOMM. The snapshot records the route (`Snapshot.via`), the reader logs it at info, and the status
  screen's footer names it ("Updated 1 min ago · Classic"), so a tester without adb can report it.
- **Privacy:** the SDP record names the service to any device that opens a Classic connection to the phone
  while the mesh runs. The phone is not discoverable and the mesh advert does not carry its Classic address,
  so a device has to know that address to ask.

## Amendment 2026-09-27 (4) — every read runs in a job

After the RFCOMM change the Pixel Watch 3 still went to "Phone out of reach" for stretches, and after a night in
Bedtime mode it stayed there until the app was opened. The transport was not the cause; the watch's scheduling
was.

- **The periodic asks wait for the watch to leave Doze.** Wear OS runs a data source's `UPDATE_PERIOD_SECONDS`
  as a `wearservices` job (`FutureUpdateJobService`) gated on `DEVICE_NOT_DOZING`. A watch dozes whenever its
  screen is off or ambient, so on a watch that is most of the day and all of the night. At the capture the
  watch was in deep Doze with Knit's five-minute job 31 s overdue. The staleness timeline still flips the face
  to no-data six minutes after the last read, on the face's own clock.
- **A poke is about ten seconds of CPU.** The watch app stays a *cached* process through a complication or tile
  request: the system unfreezes it for the call ("sync unfroze … for 6") and the freezer's 10 s debounce freezes
  it again, whatever it is still doing. At 15:10 the screen woke, the app was unfrozen at :33.9 and frozen at
  :44.3; the phone accepted the RFCOMM connection at :46.8, wrote the frame, lingered its 3 s for a close that
  never came, and closed. That read was lost, and the failure floor then held off the next one. At 15:25 the
  same path took four seconds and landed. Opening the app worked because a foreground process is not frozen.
- **So every read now runs in `StatusReadJob`**, a `JobService`: a running job keeps the process out of the
  cached state until the read ends. It is expedited, so it starts at once. When the expedited quota is spent the
  scheduler refuses it outright and it goes as a plain job with no constraints, as it always does on Wear OS 3,
  which has no expedited jobs; while the watch is awake that starts at once too. One job id, never rescheduled
  while running (that would stop it).
- **No surface waits on Bluetooth any more.** A complication ask answers from the cache like the tile already
  did, and starts a read when the cache is due (`PhoneStatusReader.due`: older than the fresh minute and outside
  the failure floor, pinned by `ReadDueTest`). A good read asks every source again. The same gate stops a read's
  own redraws from starting another.
- **Cost:** a good read holds a job for 1-5 s over Classic. A failed one runs to its end instead of being frozen
  partway, up to the routes' timeouts (8 s RFCOMM, then two 10 s LE attempts, per bonded device tried). Reads
  happen when a surface asks and, with the alarm below, about every five minutes while a complication is on the
  face.
- **Raising the wrist does not end Doze, so Knit keeps its own clock.** The first device run of the job found
  that a wrist-raise lights the screen with the display policy still `DOZE`: the device never left deep idle, the
  complication jobs stayed parked, and nothing asked Knit for 25 minutes of glances. Touching the screen did not
  end Doze either. So while a Knit complication is on the face, `StatusAlarm` runs an allow-while-idle alarm
  every five minutes (the maintainer's choice of cadence). The alarm kicks the expedited read, and a good read
  asks the face for fresh data. After a read that found no phone the alarm waits fifteen minutes instead.
  - The alarm is inexact, the price of not needing the exact-alarm grant. The system may hold it up to three
    quarters of its lead time to batch it, and in Doze it did so every time. In the first field test (2026-09-27,
    an hour off adb) an alarm asked for five minutes fired every 8 min 45 s, and each one read the phone over
    Classic 4-5 s later in under half a second. So the alarm now asks for 4/7 of the interval (`StatusAlarm.lead`,
    pinned by `StatusAlarmTest`), which puts the latest delivery at the interval itself. Targeting S+, the quota
    is 72 such alarms an hour.
  - The chain runs only while a complication is active. A complication counts as active once it asks
    (`complicationInstanceId`), and it stops counting when it is deactivated or after 24 h without an ask. A
    reboot or an update re-arms the chain.
- **An old reading is shown with its age, not as "Phone out of reach"** (the maintainer's call). The six-minute
  window assumed an ask every 300 s, which Doze breaks, so past it the reading usually means nobody asked, not
  that the phone went away. `StatusText.shown` keeps it, muted and dated, and returns "out of reach" only when a
  read *after* it found no phone. That failure time is persisted (`PhoneStatusReader.failedAt`, cleared by the
  next good read) so any process can draw it, and a failed read now redraws the complications as a good one
  does. How each surface shows the age:
  - A complication's timeline turns at the window's close into the aged entry. Its title becomes the age, a
    `TimeDifferenceComplicationText` ("12m ago") that the face counts up on its own clock. The colour and ring
    go muted, and TalkBack hears the age too.
  - The tile's title reads "As of 12:40", which stays true however long nothing asks.
  - The app mutes the hero and dates it. Every surface drops to "Phone out of reach" as soon as a read fails
    past the window. Pinned by `StatusTextTest`.
