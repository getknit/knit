# Driving the app on a device (debug builds)

> **First obey `rules/devices.md`:** never drive a non-emulator (physical) device without the user's
> explicit go-ahead for that specific session. This file is the *how*; that rule is the *whether*.

Debug builds carry three affordances so an agent can drive the send→verify loop **without** screenshots
or hunting the (unlabeled, state-dependent) send button's pixel bounds. All are **debug-only** — the
bridge receiver and its manifest entry live in `app/src/debug/` (so the release APK has neither), and the
route extra is gated on `BuildConfig.DEBUG`. `app/build.gradle.kts` is untouched.

## Headless bridge

`app/src/debug/.../debug/DebugBridgeReceiver.kt` — an exported `BroadcastReceiver` that calls
`MeshManager` directly and returns JSON. Fire with `am broadcast` (target the package with
`-p app.getknit.knit`); the reply prints on stdout as `Broadcast completed: … data="{…}"` and is also
logged one-line under tag `KnitBridge` (`adb logcat -d -s KnitBridge:I`). **A new action must be added in
*two* places** — the `when` in `DebugBridgeReceiver` *and* the `<intent-filter>` in
`app/src/debug/AndroidManifest.xml`; a package-targeted broadcast for an action missing from the filter is
silently not delivered (the receiver never runs, and you get `Broadcast completed: result=0` with no
`data=` and nothing under `KnitBridge`). Actions:

- `…debug.SEND` — `--es text <body>` + a target: `--es conv <id>` (`nearby` room, a peer node id for a
  DM, or a `g-…` group id) or `--es to <peerNodeId>` (DM shorthand). No target ⇒ broadcast room. Text is
  passed verbatim — spaces/emoji survive (unlike `adb shell input text`) **provided you quote for the
  on-device shell**: `adb` re-parses the command on the device, so a bare `--es text "hi there"` is
  word-split and truncated to `hi`. Wrap the whole remote command in double quotes and single-quote the
  value (see the example).
- `…debug.SENDIMG` — sends a real **image attachment** with no UI (a locked device can't drive the photo
  picker): `--es path <file the app can read>` plus the same `conv`/`to` targeting as SEND and optional
  `--es text`. Stage the file into the app's own storage first (scoped storage — the app can't read
  /sdcard paths): `adb push img.jpg /data/local/tmp/ && adb shell "cat /data/local/tmp/img.jpg | run-as
  app.getknit.knit sh -c 'cat > files/img.jpg'"`, then pass `--es path /data/data/app.getknit.knit/files/img.jpg`.
  Runs the production pipeline (AttachmentStore.ingest → sendChat), and the reply carries the attachment
  `hash` to poll for on receivers.
- `…debug.STATE` — self id/name, transport health, reachable peers, and mesh metrics. Add `--es conv <id>`
  to also dump that thread's latest messages (`--ei limit N`, default 20), each with its `received`
  delivery tick — this is how you **verify receipt on the other device without a screenshot**.
- `…debug.STORE` — dumps the store-and-forward carry set (the **live** rows are the id set the cue-plane
  content digest is folded over; expired-unswept rows are digest/quota/serve-invisible residue awaiting the
  sweep), for diagnosing why two nodes never converge their digests (the churn from a carried-set delta):
  `digestVersion` (what the transport actually cues, read via the same lazy-folding `StoreDigest.current()`),
  `allFingerprint`/`liveFingerprint` (the digest recomputed over all rows vs. non-expired rows — the
  invariant is **`digestVersion == liveFingerprint`, always**; a mismatch is an in-memory-digest drift bug,
  while `allFingerprint` legitimately lags by the expired residue until the sweep), `counts`,
  `expiredIds`, the full `allIds`, and capped per-row detail (`--ei limit N`, default 100). Diff `allIds`
  across devices to find the stranded frame(s): `… STORE | sed -n 's/.*data="//;s/"$//p' | jq -r '.allIds[]'
  | sort` per device, then `comm`/`diff` the files. **`liveFingerprint` matching across devices = converged**
  (`allFingerprint` is NOT fleet-comparable at a TTL boundary — soak oracles must compare `liveFingerprint`).
- `…debug.INTRO` — drives the **contact-card** flow (ADR 042, `docs/CONTACT_CARD.md`) on a locked lab
  device: `--ez card true` mints and prints this device's link (`url` / `schemeUrl`), `--es import '<link>'`
  previews + imports one (single-quote it — the shell splits on spaces), no extras dumps the intro driver's
  `pending` / `grace` sets (`"<peerId>|<millis>"`) and the counters (`introsSent`, `introsAnswered`). The
  two-device recipe: `card` on each, `import` the other's on each, then `…debug.RATCHET` for `confirmed`
  and `…debug.SPOOL` for the shared DM scope id.
- `…debug.SPOOL` — configures and inspects the **Internet (spool) plane**; the relay editor is in the UI
  now (`ui/relay/`, ADR 019's M6 amendment), but this is still the only way to drive the plane on a
  locked lab device. `--es url <ws(s)://host:port/spool/v1[?k=token]>` adds a spool, `--es drop <url>`
  removes one, `--ez on <true|false>` flips the global opt-in (default **off**); no extras at all just
  dumps state. `--es park <url>` / `--es unpark <url>` flip one relay's own switch (ADR 063) — how a soak
  run takes a single spool out of the rotation **without losing its bearer token**, which `drop` would.
  The dump's `disabled` array is the parked set, and a parked URL stays in `configured` while leaving
  `spools[]` within one 15 s `ScopeSync` reconcile tick as its worker stops (that disappearance is the
  oracle that parking actually closed the socket). `drop` clears a parked flag too, so a re-added URL
  comes back in use. Debug builds accept plain `ws://` (a `knit-spool` daemon terminates no TLS of its
  own — that's a reverse-proxy job); release refuses it at dial time whatever is stored. The reply's per-scope
  `local` vs `spool` counts are the **convergence oracle** — they agree once the heal loop settles,
  exactly as `liveFingerprint` parity is for mesh custody — and `invalid` should stay 0 (a nonzero
  count means some uploader is putting blobs into a scope that fail validation). Two fields exist to
  stop you chasing ghosts: `retiring` marks a drained previous-session scope, where `local > spool`
  is correct rather than divergence; and `lastError` is the spool's most recent `err` code, which is
  the only thing distinguishing "connected and idle" from "connected and refusing us" (`quota`,
  `pow` and `rate` all otherwise present as a scope that simply never converges) — or the client's own
  verdict on the spool (ADR 2026-09.amzn): `unresponsive` (it stopped answering and was dropped),
  `overlong_list` (it listed more ids than a conforming spool can hold, and the round was refused), or
  `too_large` with nothing subscribed (its `maxRecord` could not carry our SUB), `unreachable` (nothing
  answered the dial at all — a timeout, DNS, refused, reset, or an undialable URL; a captive Wi-Fi the
  platform still calls validated looks exactly like this, ADR 2026-09.vej5), or `no_hello` (the socket
  opened and the spool never said hello). A client-side verdict stays through the next session, so a spool
  that answers the hello and nothing else reads as `unresponsive` rather than flickering `connected`, and
  a dead route stays `unreachable` through the next dial. `connected` is a **completed hello** on an open
  socket, never a socket that merely exists, and `dialFailures` counts the sessions in a row that never
  reached one (0 once one does): twelve under `unreachable` is the dead-route signature, one is a missed
  reconnect. `lastDialAt` (epoch ms, plus `lastDialAgeMs`) is when the worker last began a dial: poll it
  and the gaps between readings are the reconnect waits actually taken — the oracle for the backoff curve
  (ADR 2026-09.wa79: 1 s doubling to 60 s, then on to 15 min), which `dialFailures` alone cannot show. `accounted` is how
  much of `local` is the §9.6 band — blobs the spool still holds that our custody has aged out, counted
  as held on purpose (ADR 062) so `local == spool` keeps meaning converged; a scope stuck unconverged
  with a large `accounted` means the fold or the prune is broken, not the network.
- `…debug.COMMONS` — joins, leaves and inspects the **commons** (spec §7.4, ADR 2026-09.wx8e) — the relay
  row's Join/Leave, for a locked lab device. `--es url <spoolUrl> --es invite <knit-commons:v1:…>` joins the
  room that relay runs (add the relay with `…debug.SPOOL --es url` first; the reply carries the `c-…`
  conversation id), `--es leave <c-…>` leaves one, no extras dumps every joined room with its members.
  `…debug.SEND --es conv <c-…>` posts into it (with `--es replyTo <id>` for a quote), `…debug.STATE --es conv
  <c-…>` reads it back (`received` flips once a member's sealed tick lands over that pair's DM scope — the
  group's route, never the room's), and `…debug.SPOOL` shows the room's scope under the **bound relay only** plus the
  relay's advertised `commons` block. The convergence oracle is the same `local`/`spool` pair — everything a
  member pulls from a room is *accounted* (nothing is ever custodied), so `accounted` climbing to the room
  size on a restart is the expected re-pull, not a leak. Two traps from the first trial: the daemon's
  `commons-fanout` conformance check leaves a random blob in the room, which every client quarantines
  (`invalid 1`, and the scope then never reads `converged`) — restart an in-memory daemon before a
  convergence trial; and there is no `unsub` record, so the daemon's `knit_spool_commons_subscribers` still
  counts a member that left until its connection drops. Refused with `error` in a build that hides the
  commons (`BuildConfig.COMMONS` — off in release, and in a `-Pcommons=false` debug build).
- `…debug.BACKUP` — drives the **backup** half of Backup and restore (ADR 2026-09.6mj7, `docs/BACKUP_FORMAT.md`)
  where the document picker is out of reach. No extras writes a backup under a fresh recovery key to
  `files/backup-test.knitbackup` (`--es path <file>` for another) through the real `BackupWriter` and replies with
  the `key`, the manifest and `bytes`; `--es verify <file> --es key <key>` runs the restore's whole verification
  (`RestoreStager.stage`: decrypt, per-entry hashes, identity re-wrap under the live Keystore alias, node id,
  database open under the passphrase, settings parse) and then **discards** the staging, so nothing on the phone
  changes and no restart is armed. A wrong key replies `refused` with `WRONG_KEY_OR_DAMAGED`. Verify a file the UI
  saved to Downloads by copying it in first (`cat /sdcard/Download/x | run-as app.getknit.knit sh -c 'cat >
  files/x'`) — the app cannot read `/sdcard` paths. Applying a restore is the UI's job (it kills the process).
- `…debug.LORA` — configures and inspects the **LoRa (Meshtastic-over-BLE) plane** (ADR 038,
  `context/lora-bridge.md`), off by default and needing a paired board, so this is how you drive it on a
  locked lab device. `--es address <MAC>` (+ `--es name <n>`) binds a bonded board, `--ei channel <idx>`
  sets the channel index, `--ez on <true|false>` flips the switch; no extras dumps
  `state/boardNodeNum/snr/rssi/queueFree/heard/counters`. The counters (`loraSent`/`loraReceived`/
  `loraReassembled`/`loraNak`/`loraTooBig`) are the two-board range oracle; `loraNakByReason` (the board's
  `Routing.error_reason` names) says *why* a NAK happened — `TOO_LARGE` means the payload cap is wrong,
  `DUTY_CYCLE_LIMIT`/`RATE_LIMIT_EXCEEDED` mean airtime pressure — and every NAK is also logged as
  `lora nak id=… reason=…` under `LoraMeshTransport`. `…debug.LORATX --es text <s>`
  sends a raw payload straight to the board (bypassing the frame codec) to confirm the board transmits via
  `meshtastic --noproto`; `--ei hop <n>` sets `MeshPacket.hop_limit` explicitly, which the production path
  omits — the A/B that proved 2.8 does **not** substitute the node's configured default, so every Knit
  packet reaches the air unrelayable (`context/lora-bridge.md`, *Hops*). `--es mode dedicated` runs ADR 067's debug-only dedicated-frequency setup instead
  (the radio is pinned off the shared public slot; the `airtime.dedicated` flag in the `LORA` dump says so),
  and `--es mode shared` is its reverse — the radio back on the recorded slot with the setup kept, no
  restore between.
  `…debug.LORAPROV` writes the derived **Knit channel** onto the board over the
  Meshtastic admin API (the headless "Set up Knit channel") and binds the plane to the slot it lands in —
  run it on both phones so the boards converge. All need the plane enabled and the board Ready. (New action
  = add to BOTH the `when` and the debug manifest `<intent-filter>`.)
- `…debug.XFER` — drives a **direct file transfer** (`transfer/TransferManager`: a whole file over a one-shot
  Wi-Fi Direct link, never the mesh), the only way to run one on a locked lab device. `--es to <peerNodeId>
  --es path <file the app can read>` offers a file, `--es accept|decline|cancel <transferId>` answers one,
  `--ez sweep true` clears a group a dead process left on air, and no extras dumps `refusal` plus every live
  transfer — add `--es conv <peerId>` for that thread's `xfer:` rows. Stage the file the SENDIMG way (`run-as`
  into the app's own storage): scoped storage means the app cannot read a `/sdcard` path. The bridge steps
  around the file picker **and the first-use disclosure sheet**, so a phone nobody has driven by hand still
  transfers.
  - **Read `refusal` first when nothing happens.** `WifiOff`, `Hotspot`, `Permission`, `NoWifiDirect`,
    `Background` and `Busy` each fail the transfer before the radio is touched, and from outside all six look
    the same as a peer that never answered. Both ends also need `CAP_DIRECT_TRANSFER` pinned for each other
    (`…debug.STATE`), and both need to be in range at accept time, not just at offer time.
  - The trial oracle is logcat under `KnitTransfer` — `xfer <id> …` lines carry the group frequency, every
    address bound, and `done bytes= ms= MB/s=` — plus `sha256sum` on the receiver's
    `/sdcard/Download/Knit/<name>`. Expect the accept→first-byte gap to be a few seconds: most of it is the
    framework's own `connect()`, not ours.
  - A force-stop mid-transfer really does strand a group on air (`dumpsys wifip2p` shows
    `curState=GroupCreatedState` with the app dead). `--ez sweep true` is the same sweep the app runs 8 s into
    launch, on demand — the way to prove a leftover is gone without waiting out a restart.
- `…debug.RATCHET` — dumps the **DM ratchet's per-peer state** and, with `--es reset <peerNodeId>`, forces a
  session reset past the heuristic that guards it. Exists because every gate in the recovery path returns
  *silently*: a peer we hold no prekey for (`peerPrekeyPinned:false` — a reset from this side is impossible
  until their profile lands), one inside its 6 h floor (`lastResetSentAt` recent), and one whose heuristic
  has not yet counted three **distinct** undecryptable frames all present identically from outside, as a
  session that will not heal, and they need opposite remedies. `hasSession:true` with `confirmed:false` is
  the third state worth knowing: the scope table only exports confirmed sessions, so that thread also reads
  "Not covered by relays yet" while looking otherwise healthy. The force is the escape hatch for a pair that
  wedged *before* a fix shipped — the recovery path only runs when the heuristic fires, and a stuck pair may
  not be able to produce countable failures at all (a re-served frame repeats one id and never advances the
  distinct counter). Bypasses the floor, not the X3DH inputs: with no pinned prekey it still declines, and
  says so in `declined`.
- `…debug.MODEL` — dumps the on-device model **poison-pill** (ADR 037): the current build stamp, and per
  model its stored stamp, `pendingSince` marker, unexplained-death count and whether it is latched — plus
  what the platform recorded about the **previous process exit**, which is what decides a 1-strike latch.
  Also each model's lease (ADR 2026-09.cq9z): `resident` (the interpreter is in memory) and `lastUsedAt`,
  with `idleMs` at the top. `--ez reset true` clears every model; `--ez unload true` releases both engines
  now — the shortcut through the ten-minute idle cycle, so a memory trial can watch `dumpsys meminfo`
  drop and the next classify reload through the guard (`pendingSince` cycles back to 0). The fault itself is a build flag, not a bridge op
  (`-PmodelFaultOnLoad=segv|kill`), so `src/main` carries no arming seam. `segv` is the positive test
  (a real fault → latched on the next launch); `kill` is the **negative control** — SIGKILL is recorded
  exactly as a force-stop is, so it must never latch no matter how often it fires.
- `…debug.REACT` — `--es id <messageId> --es emoji <emoji>`. `…debug.HEAL` — nudge rescan/re-advertise.
- `…debug.PAUSE --ei minutes 15|60` / `…debug.RESUME` — the notification's Pause / Resume by their store
  write alone (`SettingsStore.meshPausedUntil`, which `MeshService` follows), so it is the chat list banner's
  path and works with the service down (its next start comes up paused). `…debug.STATE` reports
  `meshPausedUntil` / `meshPaused`; `adb shell dumpsys alarm | grep -A3 RESUME_MESH` shows the two resume alarms.
- `…debug.MKGROUP --es members <nodeId,…>` — creates (or re-opens a left) group with those members plus
  this device, **locally only** (no frame; members learn of it on its first `SEND`), and prints its id — the
  contacts UI cannot make a 2-member group (one selection = DM), and a locked device cannot drive it anyway.
  `…debug.LEAVE --es conv <g-…>` is the group-details "Leave" (the signed `groupleave` floods, then the local
  tombstone). Together they drive the leave → re-create → send flow of ADR 2026-09.v6fu headlessly: on the
  receiver expect `holding group key … sender departed`, a `rejoin:` notice, then the message.
- `…debug.PURGE --es prefix 'soak |burst '` — removes **injected soak traffic** from this phone: every message
  whose body starts with one of the `|`-separated prefixes, in every thread, through the chat's own local delete
  (row, reactions, receipt rows, the attachment blob once unreferenced) **plus the frame's custody row**
  (`ForwardStore.remove`, digest kept in lockstep) — custody lives 24 h and the SeenSet 10 min, so a purge that
  left it would watch the message walk back in from a peer's carry set; run it on every phone in one pass
  (`.private/scripts/soak/cleanup.sh` does). `--ez dry true` counts only, `--ei limit` bounds the newest-N
  window scanned per thread (default 5000), `--es group <g-…>` also drops that group row locally (no leave
  frame). Nothing goes over the mesh; a reaction's own custody frame is left to its TTL. The reply's
  `matched` / `deleted` / `custodyRemoved` / `byConversation` are the accounting.
- `…debug.NANFAIL` / `…debug.NANSTORM` — reproduce **getknit/Knit#9** (ADR 052 + 055) on hardware that does
  not have the bug. `NANFAIL --ei count N` arms N Wi-Fi Aware attaches to take their failure path without
  reaching `mgr.attach` (0 disarms) — the stand-in for a vendor HAL with no STA+NAN interface combination.
  `NANSTORM --ei count N --ei hz H` then replays the availability storm: synthetic Aware notifications
  straight into `WifiAwareTransport.handleAvailabilityChanged`. Both halves have to be injected, because
  neither is reachable from outside the app — another app holding an Aware session does **not** block ours
  (the framework multiplexes clients onto one interface, so "hold a lock on NAN" is aimed a layer too high),
  and `ACTION_WIFI_AWARE_STATE_CHANGED` is a protected broadcast that would not reach our
  `RECEIVER_NOT_EXPORTED` receiver even from the `shell` uid.
  - The measurement is the reply's `attachesAllowed` (`failuresAfter - failuresBefore`). Repeating
    `available` (the default) must let through roughly `elapsedMs / 3000` and no more — the `NanAttachPolicy`
    rate floor. Before ADR 055 it tracked the broadcast count instead: 3.5 ms apart, ~286 a second.
  - `--ez cycle true` alternates false/true — genuine radio recoveries, the **negative control**. Those must
    still refund the streak and reattach promptly, which is the behaviour the fix could plausibly break.
    A real Wi-Fi off→on is the other half of that control, and must be done **by hand on the device**: never
    toggle Wi-Fi over adb on a lab Pixel (`rules/devices.md`).
  - **Validate the harness against `v2.3.1` before trusting it.** That build has the bug; if `NANSTORM` does
    not kill it there, it proves nothing about a build where it doesn't.
  - What it does **not** reproduce is the leak: a forced failure returns before `mgr.attach`, so nothing is
    stranded in `system_server` and `dumpsys activity binder-proxies` stays flat. It measures attaches
    allowed, which is that count halved. The reply's `localBinders`/`binderDeathRecipients`
    (`android.os.Debug`) are this process's own counts, not the per-uid count AMS actually kills on.
- `…debug.NANREFUSE` — reproduces **work item #77** (ADR 2026-09.bgk3): `--ei count N` arms the next N
  responder requests to be declared unfulfillable ~10 ms after they are filed (the framework's own
  `onUnavailable` on our callback, so the request really is unregistered and re-filed) and delivers the first
  verdict to the live responder at once; `0` disarms, no extra just dumps `filed` / `refusals` (the
  uncontended streak) / `cycles` (this episode) / `armed`. The measurement is on logcat under
  `WifiAwareTransport`: `re-filing in Nms` per verdict along 0.5 → 1 → 2 → 4 s, `cycling the session (c/3 this
  episode)` at the fifth, a re-file at the curve's 8 / 16 s while the reattach cooldown holds, and no cycle past
  the third. `--ei count 25` walks every phase in ~110 s and then leaves a standing responder (P3, 2026-09-19).
  What it cannot reproduce is the framework's *reason*, which the field capture did not hold either; and a
  verdict injected while an inbound link is live tears that link down with the request (the real framework
  has already dropped the request by then), so the contended branch is better read from a natural knock.
- `…debug.NANICM` / `…debug.NANDIAL` — two interop knobs from the Pixel 3 investigation (2026-09-19; see the
  `nan-sta-drop-kills-aware-client` memory and ADR 2026-09.bgk3's addendum). `NANICM --ez on <bool>` turns Wi-Fi
  Aware Instant Communication Mode on or off for the running transport and cycles the session so the new
  publish/subscribe configs take; the hardware's answer bounds `on`, and a process restart reverts to it.
  `NANDIAL --es to <peerNodeId>` initiates an NDP to a peer currently in `discovered` **regardless of the id
  tie-break**, so the smaller node can knock on the larger node's responder — the far side's HELLO check still
  closes the socket; the NDP forming (`onDataPathRequest` there) is the trial. It answers `peer not in
  discovered ([…])` when the handle is gone, which on API 33+ happens seconds after the last SDF
  (`onServiceLost`), so poll it rather than wait.
- `…debug.NANINIT` — the Wi-Fi Aware **initiator failsafe** (work item #78, ADR 2026-09.m8kc). No extra dumps
  `strikes` / `latched` / `probeInMs` / `lastInitiateAgoMs` / `lossPendingMs`. `--ez blip true` injects one Wi-Fi
  drop-and-return through the transport's own `onStaLost` / `onStaAvailable`, so it is a strike only under the
  field's rule — an initiate of ours in the last 120 s (`NANDIAL` is the way to make one on demand) with no link
  since; three in a row with no link → `latched:true`, `init=held` on the state line, the Diagnostics tag and
  section. `--ez reset true` is Diagnostics' "Try again"; `--ez probe true` makes a held role's daily probe due on
  the next `driveSync` (the log says `daily initiator probe`). The negative control is a blip right after a
  *linked* initiate: `strikes` stays 0. Nothing here reproduces the STA drop itself — that needs the Pixel 3.
- `…debug.NANMSG` — the Wi-Fi Aware **coordination-plane watchdog** (work item #81, ADR 2026-09.jjhg). No extras
  dumps the ack bookkeeping behind `NanMessagePlanePolicy`: `unanswered` (sends still waiting for a callback),
  `oldestUnansweredMs`, `sinceAckMs`, `failsSinceAck`, `episodeMs` (0 = no stalled episode), `cycles` (session
  cycles spent in it) and `fault`. `--es fault swallow` drops every send callback from here on — the blocked
  framework queue of #81's first mechanism; `--es fault fail` turns every ack into a failure — the dead unicast
  of its second; `--es fault off` disarms. The watchdog's verdict and its session cycle then run for real, so
  the trial on one phone with a peer in range is: arm, watch `coordination plane stalled … — cycling the
  session (1/3 this episode)` within ~90 s (`swallow` needs one send to age 30 s; `fail` needs four failures and
  60 s), see the second and third cycle ~20–30 s apart and then quiet, disarm, and watch `episodeMs` return to 0
  on the next acked cue. The fault stays armed across cycles on purpose — a cure is only visible once it is off.
  The natural trigger is a chat burst (`burst.sh --kind nearby --size long` in the soak harness); a frozen phone
  reads `mSendQueueBlocked: true` in `dumpsys wifiaware` for the first mechanism and an idle queue with
  `NOTIFICATION_TYPE_ON_MESSAGE_SEND_FAIL` ~4 s after each `RESPONSE_TYPE_ON_MESSAGE_SEND_QUEUED_SUCCESS` for the
  second.
- `…debug.BLECAP` — the **Bluetooth link limit** (debug builds only; `SettingsStore.debugBleLinkCap`, also a
  stepper under Diagnostics' Transports). `--ei max N` caps held L2CAP links at N (0–6; 6 is the shipped budget and
  clears it), `--ez clear true` clears, no extras reads; the reply carries `cap` (null = shipped), `default`, and the
  Bluetooth row's `linked` / `nearby`. It persists across restarts (a `debug_` key, never in a backup) until
  cleared. The transport sheds the weakest links once they are 20 s old and refuses a dialer that would add one
  (`bt refused client <id> (… atCap=true)`); the side channel still reaches unlinked peers, and NAN is untouched.
- `…debug.FLAGMSG` — injects one inbound message **the text moderator flagged** (the UI collapses it behind a
  tap-to-reveal) as the newest row of `--es conv <id>` (default `nearby`), from `--es from <peerNodeId>`
  (default a synthetic sender) with body `--es text <body>`. The radio-less build never receives a real
  flagged message and the marketing seed carries none, so this is the only way to drive the
  `moderation_text_hidden` reveal path (used by `uiauto/ModerationRevealUiAutomatorTest`).
- `…debug.MSGNOTIF` — posts one **real incoming-message notification** for `--es conv <id>` (a `g-…` group or
  a DM peer id) from `--es from <peerNodeId>` (default the roster's first other member) with `--es text <body>`,
  resolving the conversation the way `InboundPipeline` would — title, photo, and for a photo-less group its
  members' faces (ADR 2026-09.zapp) — so the **shade's avatar** can be checked on the radio-less build, which
  never runs the pipeline. The reply names the `faces` it sent. Needs `POST_NOTIFICATIONS`
  (`pm grant app.getknit.knit android.permission.POST_NOTIFICATIONS`); expand the shade with
  `cmd statusbar expand-notifications` and screencap.

```
# send on A, then confirm it landed on B — no UI, no screenshots. Outer quotes matter: adb re-parses
# on the device, so quote the whole command and single-quote the text (a bare --es text is word-split).
adb -s A shell "am broadcast -a app.getknit.knit.debug.SEND  -p app.getknit.knit --es text 'hi there 😀' --es conv nearby"
adb -s B shell  am broadcast -a app.getknit.knit.debug.STATE -p app.getknit.knit --es conv nearby
# → data="{…,"messages":[{"from":"<A>","body":"hi there 😀","received":…}]}"
```

```
# Point both devices at a LAN spool and watch the scope converge. Start the daemon first:
#   cd ~/source/knit-spool && ./gradlew :daemon:run     # binds 0.0.0.0:9470, PoW off, in-memory
for d in A B; do adb -s $d shell "am broadcast -a app.getknit.knit.debug.SPOOL -p app.getknit.knit \
  --es url ws://<lan-ip>:9470/spool/v1 --ez on true"; done
adb -s B shell am broadcast -a app.getknit.knit.debug.SPOOL -p app.getknit.knit
# → data="{…,"spools":[{"url":…,"connected":true,"scopes":[{"peer":"<A>","local":7,"spool":7,
#          "converged":true,"invalid":0}]}],"counters":{…,"spoolBridged":7}}"
```

## Stable resource-ids

The root sets `testTagsAsResourceId` (in `KnitApp`), so `Modifier.testTag`s surface in `uiautomator dump`
as `resource-id="<tag>"` (the bare tag — some Android/uiautomator versions prefix it
`app.getknit.knit:id/<tag>`, so a matcher should accept either form). Tagged so far: `chat_input`, `chat_send`, `chat_row_<conversationId>` (e.g.
`chat_row_nearby`), `chatlist_fab`, `contacts_fab`, `contact_<nodeId>`, the onboarding stepper's
`screen_onboarding`, `onboarding_page_{welcome,name,permissions}`, `onboarding_next` (the footer CTA on the
first two pages — tap it twice to reach the permissions page), `onboarding_avatar` (the name page's live
preview), `onboarding_name`, `onboarding_grant` (the
radio row's Allow; `onboarding_grant_settings` once Android stops asking, `onboarding_grant_granted` the
check), `onboarding_notifications`, `onboarding_battery`, `onboarding_start`, `profile_name`, `profile_status`, `profile_save`
(in the top app bar since ADR 2026-09.hknx, so it is always on screen — a Compose test must not
`performScrollTo()` it), `profile_node_id`, `settings_profile_row` (Settings'
header row, which opens the profile editor), `settings_theme_mode` (the System/Light/Dark segmented
control; API 31+ only, absent below), `settings_relays`, `settings_lora`, `chat_group_avatar`
(opens group details), plus screen-root tags on the otherwise-untagged destinations — `screen_settings`,
`screen_profile`, `screen_diagnostics`, `screen_blocked_users`, `screen_add_contact`, `screen_donate`,
`screen_share_target`, `screen_profile_details` (whose badge strip is `profile_details_presence`,
`profile_details_verified`, `profile_details_open_to_chat` and `profile_details_blocked`, each present
only in its own state, over `profile_details_message` and the `profile_details_{first_met,
last_met,alias,node_id,lora_node}` rows and a `profile_details_group_<groupId>` row per shared group;
its top bar carries the contact's name, not a fixed title), plus the direct-transfer card's `transfer_card`,
`transfer_accept`, `transfer_decline`, `transfer_cancel` and `transfer_open`.
Use these when you must drive the real UI; add more with the same snake_case, screen-prefixed convention.

**Popups don't inherit `testTagsAsResourceId`.** A Compose `DropdownMenu` / `AlertDialog` renders in a
separate window whose semantics root is *not* the `KnitApp` node that sets `testTagsAsResourceId`, so a
`testTag` inside a menu or dialog does **not** surface as a `resource-id` to `uiautomator dump`. Drive
popup contents by their **text** (menu items, dialog titles) or **class** (an editable field is
`android.widget.EditText`) instead — and match a confirm button by *exact* text when its label is a
substring of the dialog title (e.g. the "Block" button under a "Block this person?" title).

The same holds for a `ModalBottomSheet`, which is where the consent disclosures live: `chat_send_large_file`
(an overflow item) and `chat_transfer_consent_accept` (the direct-transfer sheet) are tagged in source but do
**not** reach `uiautomator dump`. Drive those by their text, or skip them entirely — `…debug.XFER` does.

## Cold-start navigation

`adb shell am start -n app.getknit.knit/.MainActivity --es demo_route chat/<id>` opens a thread directly
(`chat/nearby`, `chat/<nodeId>`, `chat/g-…`). Cold-start only; for a running instance tap a `chat_row_*`
element instead.
