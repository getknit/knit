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
  Four `SHORT_TEXT` complications (nearby, health, radios, relayed) put the numbers on any face, a Knit one
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
