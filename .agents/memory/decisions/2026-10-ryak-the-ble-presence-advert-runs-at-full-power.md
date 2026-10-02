---
id: "2026-10.ryak"
slug: the-ble-presence-advert-runs-at-full-power
title: "The BLE presence advert runs at full power"
date: 2026-10-02
topics: [ble, mesh, power]
---

# ADR 2026-10.ryak — The BLE presence advert runs at full power

Status: Accepted (2026-10-02), JVM-tested (`BleAdvertiserTest.everyDiscoverySetAdvertisesAtFullPower`). Not yet
device-trialled: the lab phones were taken back before the change was built. No wire byte moves, and the iOS port
needs nothing, though it now reads Android adverts 8 dB stronger.

**What was observed.** The first Android-to-Android link-time trial (2026-10-02, `scripts/ble-link-trial.py`, P7 and
P3 on main's 2.7.0 debug build, Wi-Fi Aware dark) put two Pixels about four metres apart, in one room, in line of
sight. The P3 heard the P7 at −79 to −90 dBm, right on the −90 promotion floor. The same P3 heard the P9 at −68 to −76
and the Moto at −61 to −69. After one Bluetooth toggle the pair did not link again for over 66 minutes: the P7 has
the smaller id and never dials, and the P3's smoothed reading of the P7 stayed under −90, so it was never a
candidate. A pair four metres apart is not at the edge of range. The readings were the problem:

- **Every −90 floor reads the presence advert, not the link.** Promotion, the scan's boost gate and the lonely dial
  all gate on the presence advert's smoothed reading. The link runs at the controller's own transmit
  power, which can be higher (ADR 2026-10.yvn6 saw a link read −70 where the adverts read in the −90s).
- **The advert ran at `TX_POWER_MEDIUM`, nominally −7 dBm.** That is 8 dB under `TX_POWER_HIGH` (+1 dBm), the most
  the API allows. Nobody chose MEDIUM. The legacy `AdvertiseSettings` path set no power, so it got that API's default,
  MEDIUM, and the move to advertising sets in July (8fb6179a) copied it over as an explicit value.
  `PromotionConfig`'s comment sized −90 as "a small margin above typical BLE 1M-PHY sensitivity", a figure about
  links, measured against an advert running 8 dB low.
- At 4 m, free-space loss at 2.4 GHz is about 52 dB. From −7 dBm, with phone antenna and body losses, the advert
  should read in the −65 to −75 band. The P7→P3 path lost another 10–20 dB, most likely a multipath null at a fixed
  spot: stationary phones can sit in one for as long as they lie there. Raising the advert power does not remove a
  fade, but it moves the floor 8 dB further from one.

**What changed.**

- `BleAdvertiser.presenceParams()` goes out at `TX_POWER_HIGH`. The −90 floors are unchanged, so every reading
  clears them by 8 dB more at the same spot.
- **The side channel's pages follow it to HIGH** (`sideParams()`, ADR 2026-09.sjaa). sjaa chose the pages' power so
  that a page reaches no further than the sighting that gated it. At equal power it reaches as far, and left at MEDIUM
  a peer sighted 8 dB further out could never hear a page.
- **The Coded credit stays 12 dB, and its meaning gets cleaner** (`CodedPhyPolicy.CODED_RSSI_CREDIT_DB`, ADR
  2026-10.yvn6). The credit is a receiver's margin: Coded S=8 hears about 12 dB deeper than 1M. That holds only while
  both adverts go out at one power. The Coded set was already HIGH, so while the 1M advert was MEDIUM a peer heard on
  both PHYs scored 20 dB above its 1M reading, not 12. A Coded-only peer is promotable at the same physical range as
  before; only the 1M advert's reach grew.
- **Left as they were:** the floors themselves; `rssiHysteresisDb` (a difference between two readings); the
  `PhyStepper` thresholds, which read the link's RSSI, not an advert's; and the watch status server's own advert,
  which `WearStatusServer` airs only while the mesh is paused, for a watch on the wrist. The never-sighted link's
  eviction score (`BleAdmissionPolicy.UNSIGHTED_LINK_RSSI`, ADR 2026-09.shzv) is the floor by definition and stays
  −90. A sighted link at the same spot now outscores it by 8 dB more, so at the six-link budget an unsighted (iPhone)
  link is shed ahead of a sighted one more often than before. It stays at the floor because moving it changes what
  shzv settled with the iOS interop harness, and that wants its own trial.

The alternative a reader reaches for first is lowering the floor to −95 or −98. It is one line, but it leaves the
advert under-powered against the link and pushes the floor into receiver sensitivity, where a reading is mostly
noise. Raising the power keeps −90's meaning and gives every reading the same 8 dB.

**What it costs.**

- **Power, directly: microamps.** One legacy event a second is about 1.1 ms of transmit over three channels, or
  0.12 % duty. Going from −7 to +1 dBm costs some 5–15 mA more while transmitting, depending on the chip, which
  averages to about 6–18 µA, or 0.15–0.45 mAh a day. The side pages, on air about 1 % of the time while something is
  airing, add about 50–150 µA while they air. Both are small next to the scan.
- **Power, indirectly: that is the point, and it is what to measure.** Peers that read −90 to −98 before now clear
  the floor, so a phone holds more links (up to `DEFAULT_MAX_LINKS`), boosts its scan for more candidates, and dials
  more peers at the margin. One held link likely costs more than the advert change. The device trial should compare
  links held, dials an hour, failed dials and boost time from the `bt state` / `bt scan →` lines, HIGH against MEDIUM.
- **Reach outruns sightings' meaning a little.** A peer sighted at −89 now is 8 dB further away than one sighted at
  −89 before. A dial to it opens a link at the controller's power, so a link there should hold as well as one at −81
  did. Whether it does is part of the trial.

**The trap.** Anything that compares an advert reading against a fixed number was sized against a MEDIUM advert,
and now reads 8 dB high at the same spot. The floors are meant to move that way. A new threshold must be sized
against HIGH, and a new advertising set that gates or is gated by presence should go out at the presence advert's
power, which the test above pins for the side and Coded sets.
