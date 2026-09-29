---
id: "2026-09.kwq2"
slug: an-android-phone-finds-an-iphone-through-its-gatt-payload-and-dials-it
title: "An Android phone finds an iPhone through its GATT payload and dials it"
date: 2026-09-29
topics: [ble, transport, interop]
---

# ADR 2026-09.kwq2 — An Android phone finds an iPhone through its GATT payload and dials it

Status: Accepted (2026-09-29). Companion change A3 for the iOS port (`knit-ios`, ADR 2026-09.xzpt there), knit-next issue
101, with #102 for the link-parameter motive. Dark in release behind `BuildConfig.BLE_GATT_PEERS` until its device
trial. The flag bit lands in a commit of its own, after the device gate.

**What was observed.** A foreground iPhone advertises the `0xFE30` UUID and nothing else: iOS cannot advertise service
data, and `BleScanner` filtered on it, so an Android phone never sighted an iPhone and never dialed one. The iPhone
dialed instead, which A1 (ADR 2026-09.shzv) made work from either id order. That leaves the iPhone the central of every
iPhone–Android link, and an iPhone central runs a link at a 720 ms supervision timeout (#102). A link Android dials is
Android's to set, and runs at 5 s. The iOS port has served its advert payload from a GATT characteristic since xzpt,
so it can be found by reading it.

**What changed.** Android ports xzpt's reader. Nothing moves on the wire.

- **The flag.** `BleAdvertPayload.FLAG_DIALS_GATT_PEERS = 0x02`, bit 1 of the flags byte (byte 23), means "I read
  this payload, so I can find you". An iPhone whose id sorts below a flagged phone waits to be dialed rather than
  dialing. It is set only while the reader runs, since a flag without a reader strands the pair. Every shipped
  Android parser tests only bit 0 and ignores the rest, so the bit is additive (`docs/WIRE_COMPAT.md` now says so).
  Pinned against the iOS vector `09294213fa67091e03c2f2ae8d071b9d6700000000008002` in `BleAdvertPayloadTest`.
- **The identifier.** `DoorbellPolicy.PAYLOAD_UUID`, `848eedcd-a2e3-4fb2-86f9-e2c80821a497`, a read-only
  characteristic in the same `0xFE30` service as the doorbell. Its value is the 24-byte advert payload with the digest
  cue zeroed. It is looked up by UUID.
- **The pacing.** `GattPayloads` is `GattPayloads.swift` line for line. One read runs at a time, and each gets 12 s,
  the L2CAP connect watchdog. A failed read waits 30 s, and a stranger waits 10 min: one that connects but serves no
  `0xFE30` service, no payload characteristic, or a value under 23 bytes. `cancel()` runs on radio stop. Android adds
  one thing: each of the two maps keeps at most 64 addresses, least recently used first out, because an iPhone rotates
  its address about every 15 min and iOS never prunes. `GattPayloadsTest` mirrors `GattPayloadsTests.swift` case for
  case, plus the cap and the quiet clock.
- **The payload lifetime** (the contract, revised after the first device gate, on both sides). A payload serves every
  advert from its address until the transport forgets the address, which it does on any of three rules:
  1. A dial to that address fails before its channel opens (`failConnect`, never on HANDSHAKE). This catches an
     iPhone that restarted onto a new PSM.
  2. A HELLO on a link to that address names a node other than the payload's: the HELLO of a peer that dialed us
     (`superviseAccepted`, against `socket.remoteDevice.address`, before the verdict) or the reply to our own dial.
     A refusal, where the responder closes without a reply, keeps the payload.
  3. The address has gone unheard for 60 s of *scanning* (`GattPayloads.QUIET_MS`; only the time the presence scan
     ran counts, so a floored scan's idle gaps don't force a re-read), while no link to it is up. An advert, the read
     itself and a link up all count as hearing it.

  The first gate found the case these close: rule 1 alone never forgets an address this phone does not dial, so when
  knit-peer relaunched on the same adapter address under a new node id, the Pixel 7 and the Pixel 3 sighted the old
  node there for good (`reach=[…, xdibkr…]` for five minutes, `gattReads` frozen) and admitted the real dialer as
  unsighted.
- **The scan.** The presence `BleScanner` gets a second filter, OR'd with the service-data one, on `0xFE30` in a
  service-UUID list. `onScanResult` splits: service data is sighted as before (`sight`). A UUID-only advert with a
  cached payload is sighted with it at this advert's RSSI. Without a cached payload, it launches a read on the
  transport's scope and wakes nothing. The read's own sighting is what can boost the scan.
- **The reader.** `BleGattPayloadReader`: `connectGatt(TRANSPORT_LE)`, discovery, the characteristic, a read (the
  API-33 and pre-33 callbacks, as `MeshtasticGatt` has them), then `BleAdvertPayload.parse`. There is no ACL yet, so
  the read is a dial and gets the whole 12 s, not the doorbell's 2 s attach. It holds `BleConnectArbiter` for the
  read, so both scans pause. It never reads an address a link holds, and never while an L2CAP dial is in flight or
  the arbiter is held, and a promotion waits while a read runs. The client is closed after every read. It sends no
  `requestMtu`: the stack's Read Blob fetches the 24 bytes at MTU 23, and an MTU request races SMP on a Pixel
  (`MeshtasticGatt`'s settle).
- **The dial.** The rule is unchanged: the larger id dials, and a sighted dialer below us is refused. A read
  iPhone is sighted, so it is judged exactly as an Android peer is.
- **The dialed link's capabilities.** `registerLink` takes the HELLO reply's `PeerWire` on the dialer's side, not
  the presence advert's. The advert carries only the low capability byte, which never holds
  `CAP_DOORBELL = 0x1000`, so a dialed iPhone would never have been rung (ADR 2026-09.dqvb's amendment).

The alternative a reader reaches for first is to keep the iPhone the dialer and ask for better parameters over the
doorbell's client. That is #102, and it ships. It depends on iOS granting each request and on the lookup
succeeding, while a link Android dials is Android's own. The other alternative is dropping A1 once Android can
find iPhones. It stays whole (see the shzv amendment): it is the fallback that makes a clear flag safe.

**Diagnostics.** At info: `bt gatt read <addr> → <id> (psm <psm>)`, `bt gatt read <addr> failed (<phase>)`,
`bt gatt read <addr> stranger (<phase>)`, and `bt gatt forget <addr> (dial|hello <nodeId>|quiet)` for each rule of
the payload lifetime that forgets a payload. At debug: `bt gatt reading <addr>`, and `gattPayloads=` / `gattReads=` on the `bt state` line
while the gate is on. The iOS worker's gate scenario greps these lines.

**What it costs, and its limits.**

- **The floored scan.** When the iPhone's id sorts below ours, the first link waits on this phone's scan cadence,
  which is floored while it already holds links. The trial measures first-link times in that order. A flag that
  follows the scan tier is the follow-up only if they miss MVP item 2.
- **A failed first read.** The pacing's 30 s wait after a failed read is paid in full by a first link: in the first
  gate a read that failed at connect put a peer's first link at 71.0 s. A first link is at least the read, the
  promotion dwell counted from the read's sighting, and the connect.
- **An identity change at an address that never goes quiet** stays open, on both sides. When the new node sorts below
  a flagged phone and the old one above it, nobody dials: the phone never dials the old node, and the new one waits
  to be dialed. No HELLO arrives to fire rule 2 and the address is never quiet for rule 3, so the old sighting lasts
  until the address rotates or goes quiet.
- **The service-replace race.** A read that lands while iOS is replacing its service for a PSM change finds no
  characteristic, and that counts as a 10-minute stranger. It is kept line for line with iOS.
- **A backgrounded iPhone.** Its UUID moves to Apple's overflow area, which only iOS can read, so this phone cannot
  sight it. One whose id sorts below a flagged phone waits for a dial that cannot come. That costs nothing today,
  because a backgrounded iPhone's unfiltered scan finds no new peers either. Once iOS gains background discovery,
  its `weDial` must ignore a service-data peer's flag while backgrounded. Both sides record this limit, and neither
  changes code for it here.
- **`0xFE30` is not Knit's alone.** A stranger costs one connection per 10 min per address, and 64 addresses at most
  are remembered.

The mesh lab has no radio layer, so no lab scenario covers this. `GattPayloadsTest`, `BleAdvertPayloadTest` and
`DoorbellPolicyTest` pin the rules and identifiers. The reader is device-verified only, like `BleDoorbell`.

**Device gate (owed).** knit-peer's `ios` profile on `hci1` against a Pixel (API 37, the API-33 read callback) and
the Moto G (API 30, the pre-33 one), in both id orders, with the flag clear. It checks for the `bt gatt read … →`
lines, that the phone dials only when its id is larger, `bt doorbell found` on the link it dialed, and that no
`bt gatt read` names an Android phone. Then the flag, and the iPhone checks against the iPhone 12 in both orders.
