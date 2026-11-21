# Direct file transfer (`transfer/`)

A file handed straight to one nearby contact over a Wi-Fi Direct group the two phones raise between
themselves and then tear down (`MAX_TRANSFER_BYTES` = 8 GiB, against the 8 MiB an attachment gets).
**The bytes never touch the mesh and are never custodied**; the mesh carries only the conversation about
them, and the chat keeps a small record of what happened.

Decisions: ADR 2026-09.wtmz (the carrier and the protocol), ADR 2026-09.37ce (the seal), ADR 2026-09.7uqe
(the surfaces), ADR 2026-10.5hzt (READY sent until the receiver connects; the sender's Retry), ADR 2026-11.29m7
(Knit's own update between nearby contacts, `update/`), ADR 2026-11.8zm5 (the group's channel: `DirectChannels`,
`TransferPayload.ch`, `DirectWifi.channels`). Read those before changing behaviour — this file is the map, they are the
reasoning.

## Shape

- `TransferManager` — the whole state machine, **pure**: no Android imports, no IO of its own. Everything
  platform-shaped sits behind `DirectWifi` (the radio), `TransferFiles` (source and sink) and
  `TransferSignals` (the mesh). That is what lets `TransferManagerTest` run two managers back to back over
  loopback sockets and drive every failure path on a virtual clock.
- `TransferStream` — the byte protocol, pure and JVM-tested. Proof → header → sealed chunks → trailer →
  verdict byte.
- `TransferListener` — binds one `ServerSocket` per group address and races the accepts. Extracted from
  `TransferManager` when detekt's `LargeClass` fired; keep it that way.
- `AndroidDirectWifi` — **the only `android.net.wifi.p2p.*` importer** (detekt-enforced, exempted by name in
  `config/detekt/detekt.yml`). Hosting, joining, the Aware pause/resume, and the leftover-group sweep.
- `DirectWifiLease` — **the radio is shared with calls** (ADR 2026-10.kxnw: a call between neighbours rides a group
  too, `call/DirectCallPath`). Nothing holds `AndroidDirectWifi` directly: the transfer gets the view
  `leasedTo("transfer")` as its `DirectWifi`, a call the one leased to its call id. The first host or join takes
  the lease; the other view is refused `TransferRefusal.Busy` and its `release()` is a no-op — which matters,
  because `TransferManager.finish` releases on every end path, hosted or not.
- `DirectHostGuard` — **a phone where hosting takes the phone down never hosts** (ADR 2026-10.w3dw). The lease asks
  it before every group of ours goes up, so a refused host never reaches `createGroup`; `DirectWifi.hostRefusal()`
  answers `TransferRefusal.CannotHost` for a sender or a caller, while a receiver or a callee asks `refusal()` alone
  and is never refused for it. Two sources: a generic system image over an older vendor (`device/SystemImage`, a
  TrebleDroid GSI's `system_server` dies when a client joins its group), and a latch after two unexplained deaths
  while hosting in one boot (`DirectHostStore`'s marker under the `direct_host_` keys, a reboot told apart by
  `elapsedRealtime`). Diagnostics shows which, and resets a latch.
- `AndroidTransferFiles` — MediaStore `Downloads/Knit` with `IS_PENDING`, committed only after the trailer
  verifies.
- Signaling is one sealed DM ctl (`MessageContent.CTL_TRANSFER` + `xf`), gated on
  `Protocol.CAP_DIRECT_TRANSFER`. The chat row is `MessageEntity.KIND_FILE_TRANSFER` carrying a JSON
  `TransferRecord`. No DB migration; no wire break.
- **Translation models ride it too** (ADR 2026-11.ejn8): `offerModels`, the `TransferModels` seam (null in a build
  without translation), `TransferPayload.md`, `TransferState`/`TransferRecord.models`, `TransferSink.check()` before
  the verdict, `OfferReading` (the pin lists and the subset ACCEPT), `TransferEnds` (file or bundle, never one for
  the other) and `TransferDialer` (the receiver's join and connect retries, split out for LargeClass like
  `TransferListener`). The OFFER is a whole file offer of a tar with the pins beside it, so a build that ignores `md`
  takes a file and its ACCEPT without pins gets the whole archive. A models offer gets no automatic answer. One
  that brings nothing new is admitted at size 0, and the card shows no size: nothing would cross
  (`OfferReading.read`).
- **`onSignal` refuses an id that is not a canonical `FrameId`, in every phase** (ADR 2026-11.ejn8). The id keys the
  row and, before that check, nothing validated it.
- **Knit's own update rides it too** (ADR 2026-11.29m7, `BuildConfig.PEER_UPDATE`: on in debug, off in a shipped
  artifact, never in a bundle). The older phone asks over a link-local sealed `MessageContent.CTL_APP_UPDATE` (`au`,
  `AppUpdatePayload`: ANNOUNCE, REQUEST, DECLINE), sent like a presence key (`MeshManager.sendAppUpdate`, never
  custodied) and acted on only from its sender's own link (`InboundPipeline.onAppUpdateSignal`,
  `DropReason.APP_UPDATE_NOT_FROM_LINK`). `update/PeerUpdates` holds the announces, asks and requests in memory
  (`UpdateEligibility`, `VersionNames`). Its one write is the newer phone's card for each ask it put to the person, a
  `KIND_APP_UPDATE_REQUEST` row (`UpdateRequestRecord`, drawn by `ui/update/UpdateRequestCard`), which absorbs the
  transfer Send started (`ui/chat/foldAskedTransfers`, by `AppUpdateRecord.requestId`); a transfer that ends
  short of done frees the asker's ask at once (`PeerUpdates.ended`), so its hint offers Ask again. The newer phone answers with `TransferManager.offerApp`, an ordinary file
  offer plus `TransferPayload.ap` (`AppPin`). **An `ap` offer accepts itself only when it answers this phone's own ask**
  (`TransferAppUpdate.expects`, spent once, read last in `OfferReading.readApp`); any other is dropped without a row.
  The bytes stage under `noBackupFilesDir/update/<transfer id>/` (`UpdateStaging`), `check()` hashes them against the
  pin and reads the archive (`AndroidAppUpdateFiles`), and `TransferRecord.app` keeps the pin for the card's Install
  and restart (`UpdateInstaller`: one `PackageInstaller` session, our own package only, the status at the unexported
  `UpdateStatusReceiver`, `InstallStatusPolicy`). `BootReceiver` hands `MY_PACKAGE_REPLACED` to `UpdateLanded`. The
  chat's half is `ui/update/PeerUpdateViewModel`, apart from `ChatViewModel`.

## What will bite you

- **A group on a channel the joiner cannot use is never found** (ADR 2026-11.8zm5). A group owner follows its own
  Wi-Fi's channel, and some phones host Wi-Fi Direct on DFS channels (5260–5720 MHz) that others cannot join: a
  Pixel 9 on an access point at 5500 put its group there and a Pixel 7 failed every join (`REASON_JOIN_FAILED`,
  `FORMATION_FAILED` in the supplicant). The receiver's ACCEPT now carries `ch`, its client frequencies, and the
  host's `DirectChannels.choose` keeps `GROUP_OWNER_BAND_AUTO` only when the peer can join there, and never pins a DFS
  channel the host is not already on (a Redmi Note 13 formed nothing there, 2026-11-21). A pinned frequency
  that is refused or forms nothing gets one more try on 2.4 GHz (`DirectChannels.raise`, JVM-tested; the radio's half
  is `AndroidDirectWifi.hostOn`). Where each kind of group actually forms on a phone is `DirectGroupProbeTest`'s
  question (`androidTest/…/transfer/`, Device Farm only: CI's emulators have no Wi-Fi Direct). When a transfer
  between two phones fails at the join, read `…debug.XFER`'s `channels` on both and `group channel=…` in the host's
  `KnitTransfer` log before anything else.
- **Below API 33 a sender off screen cannot host.** The platform gates Wi-Fi Direct on the foreground-only location
  AppOp there and answers only `ERROR`; `AndroidDirectWifi.backgrounded` reads that as `TransferRefusal.Background`
  ("Keep Knit on screen") on API 29–32. Android 10 included: a Galaxy A51 on 29 refused every group off screen on
  Device Farm (2026-11-21), and `DirectGroupBackgroundProbeTest` pins the refusal there.
- **Wi-Fi Aware must be paused explicitly.** On Android 12+ our own P2P does *not* evict our own NAN session
  — same-app interface requests have equal priority — so `createGroup` returns `BUSY` while Aware is
  attached, and `WifiAwareManager.isAvailable()` stays true the whole time. There is no availability edge to
  react to. `MeshTransport.pause()`/`resume()` exist for exactly this and nothing else.
- **A fast-plane send is not a delivery.** `sendTransferSignal` returns when the frame is queued. READY once
  went out 17 ms before `mesh.pause()` closed the Aware socket mid-fragment, and BLE could not recover it
  because the fast plane had already accepted the frame. `TransferTimings.readyGraceMs` is the drain. Expect
  this shape anywhere the app hands the radio away right after a send.
- **One READY is a single point of failure, so the host keeps sending it.** On 2026-10-05 the receiver could not
  open the first READY (a ratchet collision, #135). The session reset 17 s later, but a reset re-seals only
  stored DMs and a `CTL_TRANSFER` is never stored. The host now re-sends READY every `readyResendMs` while it
  waits for the connection, and the receiver ignores the copies. Never make the receiver repeat ACCEPT instead:
  the host answers that with CANCEL.
- **Retry reads the record's `sourceUri`, and the chat checks it before drawing the button.** The picker's grant is
  taken as persistable in `AndroidTransferFiles.openSource`, so it outlives a reboot. It is never released, and
  Retry depends on it. A photo sent with Cover faces on is offered as a `file://` copy of its covered bytes in the
  cache (`redact/CoveredCopies`, ADR 2026-10.whkk). `TransferFiles.release` deletes it once the transfer cannot be
  retried (Done, or declined by its receiver), and a copy older than a week goes when a chat opens: Retry after that
  finds the source gone.
- **Once every byte is out, the verdict decides the sender's card** (ADR 2026-11.ejn8, the maintainer's rule). The
  receiver writes its verdict before it commits, so a failed commit's CANCEL used to race the sender's Done.
  `awaitingVerdict` goes up as the last chunk leaves, before the trailer, and a CANCEL that lands then is kept
  (`peerCancel`) and used only if no OK verdict comes. The receiver's own card for a failed commit reads
  `REASON_NOT_SAVED` (a local outcome, 10: "arrived but couldn't be saved / installed"), never `REASON_CONNECTION`.
- **The receiver holds the link until the sender has its verdict** (`TransferDialer.answer`). The verdict is the
  stream's last byte, and releasing the radio straight after writing it took the group down with the byte in flight:
  in the 2026-11-20 peer-update trial the staged APK committed in milliseconds, the Pixel 7 left the group 5 ms after
  its verdict, and the Pixel 9 sat at 259/259 MB until its 20 s verdict wait failed a transfer that had landed. A
  file's commit into Downloads had hidden the race. The receiver now half-closes and reads until the sender closes,
  bounded by `TransferTimings.verdictLingerMs`; `TransferManagerTest.theReceiverHoldsTheLinkUntilTheSenderHasItsVerdict`
  runs it over a `LossyLink` that drops in-flight bytes on release.
- **The receiver may hold no IPv4 address at all.** IP provisioning mode is the *client's* choice (API 34,
  not 33 — lint is right and AOSP's `@RequiresApi` is wrong) and the host is never told which was used, so
  the host binds every address on the group interface. Interface names differ per phone, so re-pin a
  link-local scope id locally rather than trusting the one that arrives.
- **The stream key is never used directly.** HKDF splits it into an enc key and a mac key. Chunk nonces are
  the chunk index, which is only safe because the key is per-transfer; the AAD is derived by the receiver
  rather than read off the wire, which is what makes truncation and reordering fail their tags.
- **`setSmallIcon` is invisible in the Android 12+ shade** — that header circle is the launcher icon. The
  small icon reaches the status bar only.
- **A call joins with an IPv4 lease, a transfer may not.** `join(ipv4 = true)` skips the IPv6 link-local
  provisioning and returns only once the DHCP lease is on the interface: libwebrtc never gathers on an IPv6
  link-local. A transfer keeps the faster link-local path.
- **A cancelled host or join takes back what it asked for** (`undoOnCancel`): a `connect` still negotiating is
  cancelled and a group that formed is removed before the cancellation propagates, since `release()` can close the
  channel before the framework answers and leave a group with no one to remove it.
- **Aware stays paused across a mesh restart** while the radio is lent: `WifiAwareTransport.stop()` no longer clears
  `paused`, and `resume()` on a stopped transport only lifts the hold. A call's group easily outlives a notification
  Pause or a Stop and Start.
- **A received APK still never gets Open** (`TransferCard`), and Knit installs nothing but its own update, from its own
  staging, through its own session (ADR 2026-11.29m7). Never route a picked or shared package into `UpdateInstaller`:
  it takes a transfer id and builds the path itself on purpose. The install-status receiver acts only on the session
  `UpdateStaging`'s pending record names and launches only the installer's own confirm intent, grants stripped.
- **A force-stop mid-transfer strands a group on air.** `dumpsys wifip2p` shows `curState=GroupCreatedState`
  with the app dead. The startup sweep in `KnitApplication` clears it 8 s into launch; `…debug.XFER --ez
  sweep true` runs it on demand.

## Driving it

`…debug.XFER` — see `context/debug-bridge.md`. Read its `refusal` field first when nothing happens: six
different gates fail a transfer before the radio is touched and all of them look identical from outside.

## Numbers worth not re-deriving

Pixel 7 → Pixel 9 Pro XL, both API 37: **~70 MB/s on 5.2 GHz**, 1 GB in 15.2 s, screen-off costing ~3%.
Accept→joined ~6.2 s, of which ~4.1 s is the framework's own `connect()` and is flat regardless of when it
starts. Aware teardown 120–146 ms. `createGroup`→formed ~650 ms. Sealing runs 1765 MB/s on the host JVM,
about 25× the link, so the crypto is not the bottleneck. A GO on a different channel from the STA costs
about 9× to multi-channel time-slicing. 2026-11-20, 250 MB Pixel 9 → Pixel 7 with both stations on DFS 5500 and the
group pinned (8zm5): 4.3 MB/s on 5180, 2.4 MB/s on a 2.4 GHz group (2462). Off the station's channel, 5 GHz beats
2.4 GHz.
