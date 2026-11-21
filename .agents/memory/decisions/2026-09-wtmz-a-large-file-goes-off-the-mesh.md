---
id: "2026-09.wtmz"
slug: a-large-file-goes-off-the-mesh
title: "A large file goes off the mesh, over a Wi-Fi group the two phones raise"
date: 2026-09-10
topics: [transfer, mesh, wifi]
---

# ADR 2026-09.wtmz — A large file goes off the mesh, over a Wi-Fi group the two phones raise

Status: Accepted (2026-09-10; `transfer/TransferManager`, `TransferStream`, `TransferListener`, the
`DirectWifi`/`TransferFiles`/`TransferSignals` seams, `AndroidDirectWifi`, `AndroidTransferFiles`,
`MeshTransport.pause`/`resume`, `WifiAwareTransport`, `MessageContent.CTL_TRANSFER`,
`Protocol.CAP_DIRECT_TRANSFER`, `MessageEntity.KIND_FILE_TRANSFER`, `TransferRecord`) — its declined channel
pinning is amended by ADR 2026-11.8zm5: a host now pins a channel when its peer cannot join on its own

An attachment is capped at 8 MiB, sealed, content-addressed and custodied by every phone that carries it.
That is the right shape for a message and the wrong one for a video: two people standing next to each other
in an outage could not hand over a gigabyte at all. The existing NAN data path cannot be widened into one
either — `WifiAwareTransport` never exposes its `Network` above itself, admits one outbound NDP at a time,
tears the link down at 5 s idle and a 30 s hard cap, and `FramedLink` buffers whole payloads under that same
8 MiB ceiling. NAN is also API 31+ in this app, so a BLE-only phone would be excluded from the feature
outright.

So the bytes get their own carrier, and the mesh only ever carries the conversation about them.

Decisions worth not relitigating:

1. **Wi-Fi Direct, sender hosts, receiver joins by credentials.** The sender calls `createGroup` with a
   `WifiP2pConfig` naming a fresh `DIRECT-kn-xxxxxxxx` SSID and a 24-character passphrase; the receiver calls
   `connect` with the same two values. That is the API-29 join-a-known-group path: no `discoverPeers`, no
   `deviceAddress`, and **no dialog on either side**, which is what makes a one-tap Accept possible at all.
   *Rejected:* discovery-based pairing, which puts a system dialog in front of both people and turns a
   transfer into a negotiation.
2. **Wi-Fi Aware is paused explicitly, and this is not optional.** Since Android 12 `HalDeviceManager`
   gives same-app interface requests equal priority, so they do **not** evict each other: `createGroup`
   while our own Aware session is attached returns `BUSY`. `WifiAwareManager.isAvailable()` also stays true
   throughout, so there is no availability edge to react to — the transport has to be told. Hence
   `MeshTransport.pause()`/`resume()`, defaulted to no-ops and overridden only by the transport whose radio
   cannot share. BLE keeps running, so a CANCEL still reaches the other side while Aware is down.
   *Rejected:* waiting for `isAvailable()` to flip, which is the model `context/mesh-transport.md` used to
   describe and which is simply not true of our own P2P on 12+.
3. **The host binds every address the group interface carries, not `groupOwnerAddress`.** A receiver that
   joins with `GROUP_CLIENT_IP_PROVISIONING_MODE_IPV6_LINK_LOCAL` (API **34**, whatever AOSP's
   `@RequiresApi` says) takes **no IPv4 address at all**, and the provisioning mode is the *client's* choice
   that the host is never told. `TransferListener` therefore binds one `ServerSocket` per `GroupAddress` and
   races the accepts, admitting a peer inside any of the group's prefixes. Interface names differ per phone
   (`p2p-wlan1-0` vs `p2p-wlan0-0`), so a link-local scope id is re-pinned locally and never trusted as it
   arrives. Binding only the group-owner address would have failed every IPv6 transfer.
4. **READY is sent before the radio is taken away, and then given time to leave.** A fast-plane send returns
   when the frame is *queued*, not when it is delivered: READY was written at 23:38:46.377 and `mesh.pause()`
   closed the Aware socket 17 ms later, mid-fragment, after eleven clean runs. Bluetooth cannot cover it —
   a frame the fast plane already accepted is never re-queued for another plane. `TransferTimings.readyGraceMs`
   (500 ms) is the drain. **This is the shape of bug to expect anywhere the app hands the radio away right
   after a send.**
5. **One new sealed ctl, additive on the wire.** `MessageContent.ctl = CTL_TRANSFER` plus one nullable
   `xf: TransferPayload`, gated on `Protocol.CAP_DIRECT_TRANSFER`. Old builds no-op an unknown ctl. The
   credentials ride inside that sealed DM and live only in memory, which is what makes a single-use WPA2
   passphrase worth anything.
6. **The chat keeps a record, never the file.** A `KIND_FILE_TRANSFER` row holds a small JSON
   `TransferRecord` — name, size, phase, saved uri — and never the credentials or the stream key. Nothing is
   custodied, nothing enters the blob store, and `sentAt` stays the offer's time through every phase upsert
   so a thread does not jump up the chat list each time a phase turns over.
7. **A radio refusal is a sentence, not a stack trace.** `TransferRefusal` names what the user can act on
   (`WifiOff`, `Hotspot`, `Permission`, `Background`, `Busy`, …). The `WifiOff` gate earns its place: with it
   probe-disabled on a lab phone, `createGroup` fails `BUSY` and **Android does not auto-enable Wi-Fi** —
   the gate turns an opaque `BUSY` into "Turn on Wi-Fi".

Measured on a Pixel 7 → Pixel 9 Pro XL pair, both API 37: **~70 MB/s on 5.2 GHz** (1 GB in 15.2 s), screen-off
costing about 3%, sha256 exact every run. Accept→joined came down from 9.6 s to ~6.2 s once the numbers were
measured rather than guessed: Aware teardown is 120–146 ms (so `SETTLE_MS` 2000 → 750), `createGroup`→formed
~650 ms, and the framework's own `connect()`→joined is ~4.1 s **and flat regardless of when it starts**, which
is why `joinStartDelayMs` stopped at 1000 instead of 0 — a probe at 0 bought 0.8 s and spent the whole margin.
The remaining ~4 s is the framework's, not ours.

Cost and residuals (accepted): the group runs on whatever channel `GROUP_OWNER_BAND_AUTO` picks, and a GO on a
different channel from the STA collapses throughput about 9× to multi-channel time-slicing — pinning the
channel was considered and declined, since the failure is slow rather than broken. Disabling the **Wi-Fi
stack** mid-stream kills the group in about a second; the STA merely dropping (airplane mode, roaming) does
not, and a 24 s outage sat comfortably inside the 30 s read timeout. A force-stop really does strand a group
on air (`curState=GroupCreatedState` with the app dead), which the startup sweep in `KnitApplication` clears
8 s into launch — `…debug.XFER --ez sweep true` runs it on demand. Tests: `TransferManagerTest` (two managers
back to back over loopback sockets), `TransferStreamTest`, `InboundPipelineTest`, `MeshManagerTest`,
`CompositeMeshTransportTest` for the pause/resume forwarding. **Still owed:** the API 30-32 background
`createGroup` refusal, which no lab device can reach, and the hotspot refusal.
