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
  `pow` and `rate` all otherwise present as a scope that simply never converges). `accounted` is how
  much of `local` is the §9.6 band — blobs the spool still holds that our custody has aged out, counted
  as held on purpose (ADR 062) so `local == spool` keeps meaning converged; a scope stuck unconverged
  with a large `accounted` means the fold or the prune is broken, not the network.
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
  (the radio is pinned off the shared public slot; the `airtime.dedicated` flag in the `LORA` dump says so).
  `…debug.LORAPROV` writes the derived **Knit channel** onto the board over the
  Meshtastic admin API (the headless "Set up Knit channel") and binds the plane to the slot it lands in —
  run it on both phones so the boards converge. All need the plane enabled and the board Ready. (New action
  = add to BOTH the `when` and the debug manifest `<intent-filter>`.)
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
  `--ez reset true` clears every model. The fault itself is a build flag, not a bridge op
  (`-PmodelFaultOnLoad=segv|kill`), so `src/main` carries no arming seam. `segv` is the positive test
  (a real fault → latched on the next launch); `kill` is the **negative control** — SIGKILL is recorded
  exactly as a force-stop is, so it must never latch no matter how often it fires.
- `…debug.REACT` — `--es id <messageId> --es emoji <emoji>`. `…debug.HEAL` — nudge rescan/re-advertise.
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
- `…debug.FLAGMSG` — injects one inbound message **the text moderator flagged** (the UI collapses it behind a
  tap-to-reveal) as the newest row of `--es conv <id>` (default `nearby`), from `--es from <peerNodeId>`
  (default a synthetic sender) with body `--es text <body>`. The radio-less build never receives a real
  flagged message and the marketing seed carries none, so this is the only way to drive the
  `moderation_text_hidden` reveal path (used by `uiauto/ModerationRevealUiAutomatorTest`).

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
`chat_row_nearby`), `chatlist_fab`, `contacts_fab`, `contact_<nodeId>`, `onboarding_grant`,
`onboarding_start`, `profile_name`, `profile_status`, `profile_save`, `chat_group_avatar` (opens group
details), plus screen-root tags on the otherwise-untagged destinations — `screen_diagnostics`,
`screen_blocked_users`, `screen_add_contact`, `screen_donate`, `screen_share_target`, `screen_profile_details`.
Use these when you must drive the real UI; add more with the same snake_case, screen-prefixed convention.

**Popups don't inherit `testTagsAsResourceId`.** A Compose `DropdownMenu` / `AlertDialog` renders in a
separate window whose semantics root is *not* the `KnitApp` node that sets `testTagsAsResourceId`, so a
`testTag` inside a menu or dialog does **not** surface as a `resource-id` to `uiautomator dump`. Drive
popup contents by their **text** (menu items, dialog titles) or **class** (an editable field is
`android.widget.EditText`) instead — and match a confirm button by *exact* text when its label is a
substring of the dialog title (e.g. the "Block" button under a "Block this person?" title).

## Cold-start navigation

`adb shell am start -n app.getknit.knit/.MainActivity --es demo_route chat/<id>` opens a thread directly
(`chat/nearby`, `chat/<nodeId>`, `chat/g-…`). Cold-start only; for a running instance tap a `chat_row_*`
element instead.
