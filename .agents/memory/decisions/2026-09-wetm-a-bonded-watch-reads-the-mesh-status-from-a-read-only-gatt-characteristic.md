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

