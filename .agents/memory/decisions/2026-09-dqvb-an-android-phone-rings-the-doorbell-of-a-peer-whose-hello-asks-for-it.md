---
id: "2026-09.dqvb"
slug: an-android-phone-rings-the-doorbell-of-a-peer-whose-hello-asks-for-it
title: "An Android phone rings the doorbell of a peer whose HELLO asks for it"
date: 2026-09-27
topics: [ble, transport, interop, wire]
---

# ADR 2026-09.dqvb — An Android phone rings the doorbell of a peer whose HELLO asks for it

Status: Accepted (2026-09-27). Companion change A4 for the iOS port (`knit-ios`, ADR 2026-09.khjj there).
JVM-tested; not yet run on hardware, which waits on the port setting the bit.

**What was observed.** iOS suspends Knit a few seconds after it leaves the screen, and on iOS 27 data arriving
on an open L2CAP channel does not resume it. In the port's MVP item 9 test an iPhone 12 stayed locked for 14
minutes with its link to the Pixel 3 up the whole time, and Knit read none of ten DMs until someone opened it. All
ten then arrived within a second, from the link's buffer. What does resume a suspended app is a delegate event,
and the one a peer can cause at will is a write to the app's own GATT server: iOS lets Knit run about 9 s for each.
The port now serves such a **doorbell**, a characteristic `f34c056b-5830-4243-a888-01f92f49e446` in a primary
`0xFE30` service, whose value means nothing. Its Linux peer rings after the frames it sends, and against it every
DM of ten locked minutes was read within 0.5 s. Android did not ring, so a locked iPhone linked only to Android
phones received nothing, and relayed nothing, until it was next opened.

**What changed.** The Bluetooth transport rings a peer that asks to be rung:

- **Which links.** A node that serves a doorbell sets a new capability bit in its HELLO, `CAP_DOORBELL = 0x1000`
  (`Protocol`). `registerLink` gives such a link a `BleDoorbell`, built from the link's `BluetoothDevice`: the
  accepted socket's `remoteDevice`. Android never sets the bit (`LOCAL_CAPABILITIES` leaves it out), so a link
  between two Android phones never opens a GATT client.
- **Which frames.** `writeOnce` pokes the doorbell after a frame is enqueued, past the `LinkCrossings` check, so
  `send`, `fastFanout` and `fastSend` all ring. The exceptions are `typing`, `blobreq` and `keyreq`
  (`DoorbellPolicy.rings`), because they are the link's upkeep. A typing cue is worthless to a locked phone, and the
  60 s tick re-sends the two requests for as long as a blob or a key is missing, which would wake the iPhone every
  minute. DIGEST records, FILE records and the HELLO never ring.
- **When.** `DoorbellPolicy.Schedule` is a line-for-line port of the iOS port's schedule: a ring at most every 5 s
  while frames go out, and one more once a burst ends. A burst costs two wakes, and a busy link at most twelve a
  minute.
- **How.** The first ring looks the doorbell up:
  1. `connectGatt(…, TRANSPORT_LE)` on a device with an open LE link attaches a GATT client to that link. It does
     not dial (AOSP `gatt_connect`).
  2. `discoverServices`.
  3. The characteristic, if it takes a write without response.

  A ring is a one-byte write without response. The client stays open for the rest of the link and closes with it
  (`teardownLink`, and `registerLink`'s replace branch).

The first design a reader would reach for gates on A1's signal: a dialer this phone never sighted, which is every
iPhone. It needs nothing from the port, but a screen-off Android phone scans only every two to five minutes, so an
Android dialer is often unsighted when it is admitted. A good share of Android↔Android links would then have paid a
GATT attach and a service discovery each, on stacks (the Moto G's among them) this mesh has learned not to poke. It
would also have stopped working at A3, once Android dials iPhones it has found and they count as sighted. A bit the
peer sets says what the peer is, and it holds on either path.

The other alternative, ringing every frame exactly as the port's `LinkManager` does, keeps a locked iPhone waking
about once a minute for as long as this phone lacks some blob or key the iPhone cannot even serve.

**What it costs, and the traps.**

- **Nothing rings until the port sets the bit.** Its HELLO must carry `0x1000` whenever its radio serves the
  doorbell (KnitBLE, and knit-peer's `ios` profile), a knit-ios change still owed. The bit belongs to the radio,
  not the build, so it stays out of the port's `Capabilities.local`, its signed profile and its advert, and no
  pinned fixture or emitted vector moves.
- **A dialed link never rings,** because a dialed link's capabilities come from the advert's low byte. When A3
  lands, `registerLink` must take the reply HELLO's capabilities for the dialed iPhone.
- **Attaching can turn into dialing.** If the ACL is gone when `connectGatt` runs, the call dials the peer's
  address instead of attaching. The link is checked just before the call, the attach gets 2 s (a real attach
  reports connected within milliseconds), and the timeout closes the client, which cancels the dial. A failed
  lookup is retried at most once a minute, and three failures end the lookups for that link
  (`DoorbellPolicy.Lookups`). The 32 client registrations Android allows per device are shared with every app, so
  running out lands here too.
- **An open client holds the ACL.** So it must never outlive its link, and it doesn't. Closing one mid-link, for a
  peer with no doorbell, is safe: the stack starts the link's idle timer only when no dynamic channel is open, and
  the CoC is one.
- **No write with a response,** ever. The suspended app would have to answer it (7.7 s in the port's test), and
  an ATT timeout tears GATT down on the ACL. A characteristic without the write-without-response property counts
  as no doorbell.
- **A write refused as busy is treated as a wedged client.** Rings are seconds apart and nothing else runs on the
  client, so a refusal means a callback never came. The client is dropped and the doorbell looked up again.
- **The poke goes with the enqueue, not the socket write.** A suspended iPhone reads nothing, its channel's
  credits run out, and the writer loop blocks until a ring wakes it. Moving the hook after the write would
  deadlock the wake.
- **Anyone in range can make this phone attach.** A HELLO claiming the bit costs one GATT attach and one discovery
  per link. It carries nothing, and the HELLO was already unauthenticated.

`DoorbellPolicyTest` pins the two UUIDs (the twin of the port's `theGATTIdentifiersArePinned`), the schedule, the
frame filter and the lookup budget, and `ProtocolTest` pins the bit and its absence from `LOCAL_CAPABILITIES`.
`BleDoorbell` is verified on hardware only, like `MeshtasticGatt`. There is no host GATT stack, and the lab has no
radio layer.

On hardware, first run the Pixel 3 and the API-30 Moto G (the pre-33 write path) against knit-peer's `ios`
profile, which serves a doorbell and counts rings (`status.rung`), and check the log lines below. Then run the
port's `interop.py iphone-wake --sender phone` with the iPhone locked. The log oracle:

- `bt doorbell found <id> (<address>)`, `bt doorbell absent <id>`, `bt doorbell lookup failed <id> (<phase>)` and
  `bt doorbell wedged <id>` at info;
- `bt ring <id>` at debug;
- `doorbells=` and `rings=` on the debug `bt state` line;
- no doorbell line on any Android↔Android link.

**Amended 2026-09-29 (#102): the lookup runs at link-up and asks for a 5 s supervision timeout.** Every
iPhone↔Android link so far is one the iPhone dialed, so the iPhone is the central and iOS sets the parameters. On
the iPhone 12 (iOS 27.0) a link opens at 30 ms, no latency and a **720 ms** supervision timeout. Links an Android
phone dials run at 5 s. A walk on 2026-09-29 with the Pixel 7's snoop on showed how the lookup moved it:

- The stack's own update for the discovery asked for 10–20 ms and 5 s, and iOS granted 15 ms and 5 s.
- Once discovery found the doorbell, the stack asked for the link's first values back, and iOS granted 30 ms and
  720 ms.
- The link then ended with `Connection Timeout` (0x08), 0.73 s after the iPhone's last ACL packet, at about 10 m.
  Pixel↔Pixel links in the same walk held to about 30 m. The Pixel 9's and Pixel 3's links to the Pixel 7 ran at
  30 ms and 5 s, one of them on LE 2M too, so the PHY is not the difference.

Two changes follow:

- **Each lookup that finds the doorbell calls `requestConnectionPriority(CONNECTION_PRIORITY_BALANCED)`** on the
  client it keeps open. AOSP's BALANCED is 30–50 ms at no latency, with the 5 s timeout every priority carries.
  That is inside Apple's accessory limits, and the snoop showed iOS granting a 5 s request. The request follows
  every lookup, so a re-lookup's discovery (after a wedge or a services change) is followed by the request again,
  whatever values the stack restores when discovery ends.
- **The lookup runs at link-up** rather than at the first ring. It rings nothing, and it spends from the same
  `DoorbellPolicy.Lookups` budget. The link then spends no time at 720 ms waiting for a ring, and a replaced link,
  which gets no link-up push, still gets the request. It costs one GATT discovery per link that asks for a ring,
  and most of those rang at once anyway.

`Client` also logs the parameters the link settles on (`bt conn params <id> …`), through `onConnectionUpdated`.
That callback has been hidden since API 26, so it is declared without `override` and kept with `@Keep`. It is a
diagnostic only; the snoop log is the oracle. Links Android dials (companion change A3, #101) are unaffected:
Android is their central, and BALANCED matches what they already run.

Still owed on hardware: the walk again, with Wi-Fi Aware off on the Pixels, checking that the snoop shows the 5 s
update after `bt doorbell found` and none back to 720 ms for the life of the link, and comparing drop distance with
a Pixel↔Pixel Bluetooth link at the same spot.

**Amended 2026-09-29 (A3, ADR 2026-09.kwq2): a dialed link rings too.** A link this phone dials now registers with
the HELLO reply's `PeerWire` rather than the presence advert's, so its `Peer.capabilities` is full width and
carries `CAP_DOORBELL` when the peer set it. A dialed iPhone therefore gets a `BleDoorbell`, and its link-up lookup
asks for BALANCED like an accepted one. `DoorbellPolicy.serves` is unchanged. A dialed Android↔Android link's
capabilities become full width too, as the accepted side's already were. Their one consumer,
`CompositeMeshTransport.richer` (which record of a peer seen on two planes wins), only gains accuracy; `MeshManager`'s
`CAP_RATCHET` and `CAP_INLINE_ACK` checks read the pinned profile, not the link. Android never sets `CAP_DOORBELL`,
so no Android↔Android link opens a GATT client.

**Amended 2026-09-29 (#102, the put-back race): the BALANCED ask is repeated when the stack's put-back wins.** On
links a central dialed, A3's gate showed the ask losing to the put-back. On the Pixel 7 with knit-peer as central,
the link settled at 420 ms (`bt conn params … timeout=420ms`, 14:18:36.700) and dropped 4 s later. On the rerun, the
Pixel 3 sat at 420 ms for half a second before 5000 ms came back. AOSP (`l2c_ble_conn_params.cc`) explains both.
While discovery holds the parameters locked, the link control block keeps the connection's first values. The unlock
re-sends them through `l2cble_start_conn_update` before the app hears `onServicesDiscovered`, so our ask always
follows the put-back. When the peripheral's update goes as an HCI request, the stack sets `UPDATE_PENDING` and
queues our ask behind the put-back, and it lands last: the Pixel 3's trace shows the ask at .758, 420 ms at .837 and
5000 ms at 40.335. When the update goes as an L2CAP signalling request, no pending flag is set, both requests reach
the central back to back, and it can finish on the put-back. After our ask the control block holds BALANCED, so any
later re-send by the stack carries 5 s. Only the first put-back threatens the link.

`DoorbellPolicy.Balanced`, one per lookup, decides when to ask again:

- **On a put-back.** A timeout under 5 s that `onConnectionUpdated` reports after the ask gets an immediate re-ask.
  The put-back has completed by then, so the re-ask comes last. On the HCI path, a re-ask made while the first is
  still queued sends the same values once more.
- **At the settle time.** 2 s after the ask, it asks again unless a report since showed 5 s. That covers a framework
  that stops calling the hidden callback.
- **At most two re-asks per lookup,** so a central that lowers the timeout on purpose is not fought. Every ask
  carries the same values, so no collision among our own requests can end under 5 s.

The maintainer ruled that the hidden callback may drive this behaviour, with the timer as its net. `@Keep` keeps
the callback, and the framework calls it virtually, so no reflection is involved. A new info line,
`bt doorbell priority <id> again (put-back <ms>ms|settle) requested=<bool>`, logs each re-ask. The oracle is
unchanged: after the lookup, the last `bt conn params <id> …` line a link logs shows `timeout=5000ms`.
`DoorbellPolicyTest` pins the rule. Still owed on hardware: ten counted link-ups in each of three knit-ios runs.
Two have BlueZ as the central (`android-reads --order peer-above`), one on the Pixel 7 and one on the Pixel 3. The
third has the iPhone as the central (`interop.py iphone`) on the Pixel 7. The iPhone can't be the Pixel 3's central:
the Pixel 3's node id sorts above the iPhone's, so the Pixel 3 always dials, and the maintainer dropped that run on
2026-09-30.
