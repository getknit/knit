# Driving the app on a device (debug builds)

> **First obey `rules/devices.md`:** never drive a non-emulator (physical) device without the user's
> explicit go-ahead for that specific session. This file is the *how*; that rule is the *whether*.
>
> **For the procedure, RUN the `debug-bridge` skill** (`.agents/skills/debug-bridge/SKILL.md`). It covers the
> send→verify loop, which oracle answers which question, and `scripts/bridge.sh`, which does the quoting,
> the stopped-package flag and the reply parsing for you. This file is the per-action reference.

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
`data=` and nothing under `KnitBridge`). The same empty reply comes back from a package in the **stopped
state** (a fresh install, `adb install -r`, a force-stop) until someone opens the app, unless the broadcast
carries `-f 0x20` (`FLAG_INCLUDE_STOPPED_PACKAGES`); `scripts/bridge.sh` always adds it. Actions:

- `…debug.SEND` — `--es text <body>` + a target: `--es conv <id>` (`nearby` room, a peer node id for a
  DM, or a `g-…` group id) or `--es to <peerNodeId>` (DM shorthand). No target ⇒ broadcast room. Text is
  passed verbatim — spaces/emoji survive (unlike `adb shell input text`) **provided you quote for the
  on-device shell**: `adb` re-parses the command on the device, so a bare `--es text "hi there"` is
  word-split and truncated to `hi`. Wrap the whole remote command in double quotes and single-quote the
  value (see the example).
  `--es styles <start,length,code,…>` formats it (ADR 2026-10.p95v): triples over the text as sent, codes 0 bold,
  1 italic, 2 strikethrough, 3 monospace, normalised by `sendChat` as the composer's are. A malformed list is an
  `error`, and the Meshtastic room ignores it.
- `…debug.SENDIMG` — sends a real **image attachment** with no UI (a locked device can't drive the photo
  picker): `--es path <file the app can read>` plus the same `conv`/`to` targeting as SEND and optional
  `--es text`. `--ez fit true` fits a photo to the relays' ticket `am` as the composer does (ADR 2026-10.s3ep); the
  reply's `plainBytes` is the staged plaintext and `fitBytes` the size it was fitted to (null when not asked or
  nothing to fit to). Stage the file into the app's own storage first (scoped storage — the app can't read
  /sdcard paths): `adb push img.jpg /data/local/tmp/ && adb shell "cat /data/local/tmp/img.jpg | run-as
  app.getknit.knit sh -c 'cat > files/img.jpg'"`, then pass `--es path /data/data/app.getknit.knit/files/img.jpg`.
  Runs the production pipeline (AttachmentStore.ingest → sendChat), and the reply carries the attachment
  `hash` to poll for on receivers (the plaintext's, as staged) and `reused`: the identity a pick of bytes this phone
  already holds went out under, else null (ADR 2026-10.mptm). `--ez file true` takes the file picker's door
  (`ingestFile`) instead, for a PDF or any non-image; a file:// path has no display name, so the file's own name stands in.
  `--ez voice true` sends the file's bytes as a recorded voice note (`ingestVoice`, AAC-LC ADTS), for a transcript
  trial (`…debug.STT`'s `note` op).
  `--ez video true` takes the video door (`ingestVideo`) as the composer does: re-encoded, and for the Nearby room
  screened (poster and 12 frames, ADR 2026-10.hmfe) and refused when flagged — the reply is `blocked` with `flagged:
  true` and nothing is sent. The image door never screens; only this one does. A sent video's reply adds
  `videoDurationMs` and `posterBytes`. The room-send half of a video screening trial; the receiver's half is
  `STATE`'s `attachmentFlagged` / `videoPosterFlagged`, and the classifier itself is `VideoScreeningInstrumentedTest`.
- `…debug.SAVEATT` — the chat's Save, into the app's own storage instead of the gallery: `--es id <messageId>
  --es out /data/data/app.getknit.knit/files/<name>` writes the attachment's plaintext (opened with the row's key
  when sealed, recording its plaintext name as a real save does). Pair it with `SENDIMG --es path <out>` for the
  save-and-forward loop with no UI. Replies `hash` (the row's), `plainHash`, `bytes`, `mime`; an unheld blob or a
  key that will not open it is an `error`.
- `…debug.STATE` — self id/name, transport health, reachable peers, and mesh metrics. Each `transports[]` row carries
  `switchedOff` (Knit has the plane off: the person's Settings switch, `AWARE`, low-power mode or `NANOFF`) and
  `dormant` (Wi-Fi Aware resting, detached, while no phone nearby can use it — GitHub #44, `NANDORM`), and `lowPower`
  is low-power mode's switch and posture (as `LOWPOWER` reports it). Add `--es conv <id>`
  to also dump that thread's latest messages (`--ei limit N`, default 20) — this is how you **verify receipt
  on the other device without a screenshot**: the body turning up in the receiver's `messages[]`. Each row
  with an attachment carries `attachmentHash` (the hash the frame names), `attachmentKeyed` (sealed or not),
  `attachmentPlainHash`, `attachmentHeld` (the bytes are here) and `attachmentAnimated` — a forward trial compares
  the first across rows and reads the fourth instead of the blob store (ADR 2026-10.mptm). `attachmentFlagged` and
  `attachmentScore` are this phone's NSFW verdict on the attachment (null until it is screened; a video's score is its
  highest frame's), and `videoPosterFlagged` the verdict on a video's inline poster, judged on arrival (ADR
  2026-10.hmfe). Each row
  also carries `received`, which on the sender's own row (`mine: true`) is the ✓✓ — the delivery tick
  coming home — plus its `reactions`; the top-level `typing` map shows a cue that landed. A reply carries
  `replyToId` / `replyToAuthor` / `replyToSnippet` and the receiver's verdict on the quote (ADR 2026-10.gpa3):
  `replyCheck` (0 unchecked — the original is not here, 1 verified and rebuilt from it, 2 mismatch) and
  `replyToFlagged` (the quoted words are flagged on this phone, which hides the reply under content filtering).
  `styles` is the row's formatting as stored, `[[start, length, code], …]`, or null for none (ADR 2026-10.p95v).
  `badges` maps each peer node id to the supporter badges this phone would draw for it now (`[{b, exp}]`, verified at
  this phone's clock against this build's pinned keys), and `ownBadge` is our own side: `show` (the "Show my supporter
  badge" switch) and `held` (`[{b, exp, verifies}]`). Never an `r`, a `sub` or a wrapper (docs/SUPPORTER_BADGE.md).
  `indirect` lists the authors another phone handed us a fresh frame from within the linger
  (`{nodeId, name, via, agoMs}`), which is what Diagnostics shows as "Reachable indirectly" (ADR 2026-10.fw8g); the
  top-level `reachable` array is the *nearby* set (`neighbors`), not `MeshController.reachable`.
  The metrics carry the holder query's counters (ADR 2026-10.6e5t): `blobQueriesSent` / `blobQueriesAnswered` /
  `blobQueriesRefused` / `blobAnswersTaken` / `blobAnswersIgnored`, then what the fetch did — `blobFetches` (a holder that said so, asked),
  `blobConfirms`, `blobFallbacks`, `blobBlindAsks` (f7gg's round, for neighbours that cannot answer) and
  `blobPullThroughs`, plus ADR 2026-10.g24x's `blobAsksParked` (a holder's asks parked behind another copy's feed),
  ADR 2026-10.nsr7's `blobProgressSent` / `blobProgressTaken` (progress reports sent back to a file's sender, and taken
  from its receiver), `blobCancelsSent` / `blobCancelsTaken` (asks withdrawn, and withdrawals that dropped a parked ask
  or a queued copy) and `carrierPullsDeferred` (a carried DM's picture left to its linked recipient). The per-blob
  story is in logcat, tag `MeshManager`: `blob query`, `blob answer … in <ms> ms`, `blob fetch`, `blob confirm`,
  `blob fallback`, `blob pull`, and the holder's `blob has`, `blob park`, `blob beside` and `blob unpark`.
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
  `--es url <spoolUrl> --es ticket <knit-ticket:v1:…>` holds an **entitlement ticket** for that relay's host name
  (experimental, `docs/VOICE_CALLS.md` §2.6, ADR 2026-10.xkp4) — the storefront's stand-in, printed by knit-spool's
  `ticket-mint` — and refuses one whose `aud` is another host; `--es ticket clear` forgets it. With `ticket` the
  `url` only names the relay and is not added (re-adding it untokened would make a second row). A live connection
  presents an import at once; the reply's `ticketImport` names the host and `exp`, never the ticket. Each spool in
  the dump carries `ent` (its hello takes tickets), `stun` (how many STUN URIs its hello gave a call, after the
  client's URL rule), `turn` (its hello offers TURN), `cast` (its hello's `maxBytes` and `rate`, or null) and
  `ticket`: `state` (`none`/`pending`/`accepted`/`refused`) with `exp`, the grants `turn`, `files` and `cast` (from
  the accepted claims' `fl`, else the held ticket's) and the refusal `reason` for this connection, plus `held` and
  `verdict` for the stored ticket (`retry:early`, or the word that keeps it from going out again). Beside it,
  `entGates` is the `fl` bits this connection's hello gates behind a ticket (`ent` without `entGates` reads 9, no
  `ent` 0; knit-spool ADR 2026-10.h6pt) and `uploads` whether it takes this phone's attachments (C-7.6-24): `open`,
  `locked:<missing|expired|flags|pending>` (no files ticket accepted here, it lapsed, it lacks bit 1, or one is still
  unanswered — or the word an `aput` drew, held until a files ticket is next accepted) or `spent` (the ticket's
  pseudonym has sent its day's uploads here; held in memory across reconnects, cleared by a ticket with another
  pseudonym). A free phone on an Anywhere spool reads `ent: true`, `stun: 1`, `held: false`, and on one that gates
  files `entGates: 11`, `uploads: locked:missing`. Logcat under `ScopeSync`: `<host>: ticket presented|accepted|refused:
  <word>`, `<host>: uploads refused: <word>` and, per call, `<host>: TURN ask (ticketed): …` or `TURN ask skipped,
  no ticket (ent)`.
  `--es url <spoolUrl> --es issuer <https://host[:port]> --es key <kak1_…>` holds a **customer key** for that relay's
  host without an invite (ADR 2026-10.3sk7; debug builds only, where the ticket refresher runs): the refresher then
  trades it at that issuer for the host's tickets, at once and again before the held one is 6 h from `exp`, and
  imports what comes back exactly as `--es ticket` does. `--es key clear` forgets the key, its schedule and the
  host's ticket (as `drop` does when no other row shares the host). The issuer may be `http://` in a debug build — a
  bench issuer behind `adb reverse tcp:3000 tcp:3000` is `http://127.0.0.1:3000`. Each spool's `ticketKey` in the
  dump shows `held`, the `issuer` origin, the last `answer` (`ok`/`unknown-key`/`ended`, null before one), `endsAt`
  (epoch ms), `failures` (asks in a row with no verdict) and `nextAskAt` / `nextAskInMs` (null when parked by
  `unknown-key`, and for a `free` key unless an upload waits there with no ticket to present — a free key never asks
  on the 6 h margin) — never the key or the ticket. Device binding (ADR 2026-10.wk3m) adds `movableAt` (epoch ms, null
  for now), `parkedUnsigned` and `parkedPairs` (how many of this phone's device-key pairs a 409 refused; never their
  ids); a parked key's `answer` is `bound-elsewhere`. `--es move <spoolUrl>` is the relay screen's **Move it here**
  for a phone whose screen a trial does not drive (C-7.6-44): the reply's `move` is `moved`, `too-soon` (with
  `movableAt`), `unknown-key`, `ended`, `failed` (with a `reason` word: `offline`, `keystore`, `no verdict (…)`) or
  `unavailable`. Logcat under `ScopeSync`: `<host>: ticket fetched from <issuer> (exp …)`, `<host>: move: <word>` or
  `<host>: ticket ask: unknown-key|ended (endsAt …)|bound-elsewhere (movableAt …)|upgrade-required|bad-proof|no
  verdict (<reason>)|unavailable (retry after …)`.
  **Free tickets** (`docs/VOICE_CALLS.md` C-7.6-32 to C-7.6-38): `--es url <spoolUrl> --es freeIssuer
  <https://host[:port]|clear>` sets the **free issuer** for that relay's host (only the seeded
  `public-1.spool.getknit.app` ships with one, under its former name `lax.spool.getknit.app` too; the lab's
  `lab.lax.spool.getknit.app` takes `https://anywhere.getknit.app`), `clear` going back to the seeded one; `--es url <spoolUrl> --es freeConsent <allow|decline|clear>` answers the one-time notice for that
  host without the UI (`decline` forgets a free key held there, as the relay row's switch does; `clear` makes the
  notice ask again); `--ei mine <bits>` times one account stamp on this phone, off the network (the reply's `mine`:
  `bits`, `n`, `ms`). With `freeIssuer` or `freeConsent` the `url` only names the relay. Each spool then also carries
  `uploadLimitChunks` (the most chunks one of this phone's attachments may have there under the live files ticket's
  `am`, or null for no limit beyond `maxAttachBytes`), `freeIssuer` (the origin, or null) and `freeKey`: `none`,
  `asking` (an account request or a free key's swap is running), `held`, `declined` or `backoff:<epoch ms>` (the
  account route's next ask). `ticketKey.free` is true for a free key, and `ticket` shows no `am` — read it off the
  relay row's ticket line (`up to 262 kB each, 1.3 MB a day`). A free key asks only while an upload waits: logcat
  `<host>: an upload waits for a ticket`, then `<host>: mined a <bits>-bit account stamp in <ms> ms`, `<host>: free
  account made at <issuer>`, `<host>: ticket fetched …`; a refusal reads `<host>: free account not made: <word>; next
  ask after <epoch ms>`, a forgotten key `<host>: free key forgotten`. An attachment over the ticket's `am` reads
  `<host>: an attachment is over this ticket's limit (<n> chunks); not uploaded`, and a photo fitted at staging
  `AttachmentStore: photo fitted to <bytes> B: <before> → <after> B at <px> px, q<quality>`.
  The dump's `disabled` array is the parked set, and a parked URL stays in `configured` while leaving
  `spools[]` within one 60 s `ScopeSync` reconcile tick as its worker stops (that disappearance is the
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
- `…debug.COMMONS` — joins, leaves and inspects the **commons** (spec §7.4, ADR 2026-09.wx8e, ADR 2026-10.wqu2) — the
  relay row's Join/Leave, for a locked lab device. `--es url <spoolUrl> --es invite <knit-commons:v1:…>` joins the
  invite's room at that relay, beside any other room joined there, named as the relay lists that room's tag (add
  the relay with `…debug.SPOOL --es url` first; the reply carries the `c-…` conversation id), `--es leave <c-…>` leaves one, `--es react <messageId> --es emoji <e> --es room <c-…>` toggles
  a reaction in the room (C-7.4-13), and no extras dumps every joined room with its members, `attach` (whether the
  room's relay takes this room's attachments, S-7.4-5; null while it is down or does not run the room) and `stale`
  (the relay does not run this room: a rotated invite, its tag missing from `rooms`, or no room there).
  `…debug.SEND --es conv <c-…>` posts into it (with `--es replyTo <id>` for a quote), `…debug.SENDIMG --es conv
  <c-…>` posts an attachment (any of its doors; keyed, and carried by the room's relay alone), `…debug.STATE --es conv
  <c-…>` reads it back (`received` flips once a member's sealed tick lands over that pair's DM scope — the
  group's route, never the room's), and `…debug.SPOOL` shows the room's scope under the **bound relay only** plus the
  relay's `roomsAdvertised` count and a `rooms` block of each joined room's own advertised name and pinned bounds. The convergence oracle is the same `local`/`spool` pair — everything a
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
    the same as a peer that never answered. A sender also reads `hostRefusal`: `CannotHost` is a phone that never
    hosts a group (ADR 2026-10.w3dw; `directHost` says why — a generic system image, or a latch), so it receives
    and never sends. Both ends also need `CAP_DIRECT_TRANSFER` pinned for each other
    (`…debug.STATE`), and both need to be in range at accept time, not just at offer time.
  - The trial oracle is logcat under `KnitTransfer` — `xfer <id> …` lines carry the group frequency, every
    address bound, and `done bytes= ms= MB/s=` — plus `sha256sum` on the receiver's
    `/sdcard/Download/Knit/<name>`. Expect the accept→first-byte gap to be a few seconds: most of it is the
    framework's own `connect()`, not ours.
  - A force-stop mid-transfer really does strand a group on air (`dumpsys wifip2p` shows
    `curState=GroupCreatedState` with the app dead). `--ez sweep true` is the same sweep the app runs 8 s into
    launch, on demand — the way to prove a leftover is gone without waiting out a restart.
- `…debug.CALL` — drives an **experimental voice call** (`call/CallManager`, a debug build with a call kind on,
  `BuildConfig.LIVE_CALLS` / `MESH_CALLS`; `op capture` and `op dropgroup` need live calls;
  `docs/VOICE_CALLS.md`). `--es op state` (the default) dumps the call, its phase, `reason` and the selected
  candidate pair as `route` (`host/host`, `relay/srflx`…), plus `relayOnly`. `--es op start --es peer <nodeId>`
  dials; `answer` opens the call screen, which answers; `decline` and `hangup` end it; `--es op relay --ez on true`
  sends every new call through TURN (C-3.7-3) — it writes Settings' "Relay all calls" (`SettingsStore.callsRelayAll`),
  so it survives a restart and the row shows it; `--es op turn --es peer <nodeId>` asks the connected spools for a
  credential and reports its URLs and expiry, never the username or password, plus `stun`: the URLs a call would
  gather from beside them (the hellos' `stun` lists through `call/StunServers`, C-7.6-20). It spends a real
  issue at each spool asked.
  - `start` and `answer` refuse unless Knit is in the foreground (`am start` it first): the microphone's
    foreground service and the call screen can only start from there. Pre-grant the mic with
    `pm grant app.getknit.knit android.permission.RECORD_AUDIO`; the prompt cannot show over the keyguard.
  - Both ends need `CAP_CALL` pinned for each other (`…debug.STATE`); a build that gains it republishes its profile
    at the next mesh start. TURN needs a spool whose HELLO carried `turn`, with the peer's DM scope subscribed — or,
    on a spool whose HELLO carried `ent`, an accepted ticket (`…debug.SPOOL --es ticket`) and no scope at all. The
    oracle is logcat under `KnitCall` (`call <id> …`, `call ICE: <n> TURN server(s), <m> STUN url(s)` and `webrtc: …`
    lines; credentials are never logged). Each `webrtc: route <local>/<remote> (<libwebrtc's reason>)` is followed by
    `webrtc: pairs …`: every answered candidate pair, fastest first, with its round trip and the selected one starred —
    what a switch to a relay pair was chosen on.
  - **The Wi-Fi Direct leg** (§3.10, ADR 2026-10.kxnw): `state` adds `directMode` and `direct` (the radio's owner,
    each leg as `<id>:host|join:Starting|Up|Over`, and the group interface), and a `route` ending `/wifi-direct` is a
    pair on the group. `--es op direct --es mode prewarm|answer|off` sets when a call between neighbours raises its
    group; `off` is Diagnostics' **Wi-Fi Direct calls (debug)** switch (`SettingsStore.debugDirectCallsOff`,
    persisted, read at the next call), so neighbours call over STUN/TURN as strangers would, and `prewarm` or `answer`
    turns it back on; `state`'s `directMode` reads `off` while it is set. Either phone off keeps the call off the group
    (logcat `call <id>: no direct leg (switched off)` on a caller); `--es op nettype --es as ethernet|wifi` sets what the group is reported to libwebrtc as from the next
    call on (the steering A/B); `--es op dropgroup` closes the live leg's lane as if the peer walked away, so the
    radio goes back and ICE must fail over. `--es op pathpref --es mode android|off` sets whether a call prefers the network Android
    routes over (`DefaultPath`, on by default; logcat `webrtc: opened (…, prefer wifi|cellular_4g|…)` and `webrtc: prefer …
    (android default)` when the default moves mid-call); the `pairs` line names each pair's local network. `--es op turntls --es check off|on` opens the next calls' `turns:` legs without
    checking the certificate (in memory, `state`'s `turnTls`; a diagnosis switch, never a default — libwebrtc checks
    TURN/TLS against built-in roots that hold no Let's Encrypt root); `webrtc: relays udp N, tcp N, tls N` after each
    offer or answer says which legs to the TURN server allocated. `--es op turnprune --es prune off|on` opens the next
    calls with TURN pruning off (C-7.6-26's "before"; in memory, `state`'s `turnPrune`). `--es op turnpin --es slot
    1-4|off` puts the TURN server at that place in a spool's `turnHosts` first from the next call on, whatever its score
    or refusal (C-7.6-51; in memory, `state`'s `turnPin`): a trial pins a refusing box and watches the call move on
    (logcat `call <id>: turn slot N of M refused (486) before our offer; gathering on slot K of M`). `--es op turn`
    gives each credential's `slot`, `of`, `score` and whether a call would take it (`chosen`). Each relay logs `webrtc:
    relay gathered udp|tcp|tls/<wifi|cellular|vpn>#<network-id>/ipv4|ipv6|?` (the
    family is `?` under relay-only, which blanks what it is read from) and `webrtc: relays removed N (…)` when pruned, and the call ends on `webrtc:
    allocations gathered N, pruned M, held K` (with pruning on, a relay pruned the moment it was ready never surfaces,
    so `gathered` is a floor and `held` exact). Logcat lines: `the peer is joining; hosting the group`, `group up on …`,
    `lane up`, `lane over (…)`, `direct leg over; radio back to the mesh`, `webrtc: the group is a network …`.
  - **The walkie-talkie** (§3.11, ADR 2026-10.79yb). Both ends need `CAP_WALKIE` pinned (`BuildConfig.MESH_CALLS`, on
    in debug).
    - `start --es kind auto|live|walkie`: `auto` is what the Call button does. It picks a walkie only with no Internet
      route here and no Wi-Fi Direct leg to a neighbour, so `walkie` is how a lab phone on Wi-Fi gets one.
    - `--es op talk --ei ms 500-8000`: holds the talk button that long in a connected walkie session, streaming the
      burst in chunks as it records. The call screen must have started the microphone service, so answer from the
      screen, not the shade.
    - `state`: adds `walkie`, `degraded` and `peerCastTicketed` (the peer's walkie signal said it holds a cast
      ticket, §2.7) to the call, and a `walkie` block: the button's `talk` state, the bursts `sent` and `heard`
      (played to their end), the chunks `chunksOut`, `chunksIn` and `lost` (skipped as never come), `castTicketed`
      (this phone holds a cast ticket on a connected relay that carries casts), and `link`: `DIRECT`, `MESH`,
      `SPOOL` or `NONE`, the link a chunk to the peer would take (null outside a walkie call). `SPOOL` is a peer the
      mesh has no path to, whose chunks are cast through a relay (`docs/VOICE_CALLS.md` §2.7): the relay's `hello` must
      carry `cast`, the DM scope be subscribed there, and on a relay that takes tickets a cast ticket be held by one
      of the two (`…debug.SPOOL --es ticket`, minted with knit-spool's `ticket-mint --cast`). Its `stats` are the
      call screen's walkie diagnostics card, null until measured:
      - `transitMs`: arrival less the sender's stamp, the median of the latest 16 chunks. It is read off both phones'
        clocks, so their skew is in it.
      - `jitterMs`: RFC 3550's, from the stamps' spacing, so it has no skew in it.
      - `lossPercent`: chunks skipped of those expected.
      - `sendKbps` and `receiveKbps`: frame bytes per second of speech over the latest burst each way.
      - `bufferMs`: the latest burst heard, from its first chunk to its playing.
      - `handMs`: a chunk's cut to the radio taking it, retries included.
      - `frameOut` and `frameIn`: the average frame's envelope bytes.
      - `failed`: chunks no radio took.
  - **The microphone** (C-3.13-12/13, ADR 2026-10.kw29). `state` adds `capture`: `next` (the profile the next call
    opens on, `STANDARD` or `FALLBACK`), `why` (`GENERIC_IMAGE`, `SILENT_CALL`, `CHOSEN` or null), `fault`, and
    `open`, the call's microphone: its `profile`, whether it `switched` mid-call, and `health` (`WATCHING`, `LIVE`,
    `SILENCED`, `SWITCHED` or `DEAD`). `--es op capture --es set standard|fallback|reset` picks the next call's path
    (`reset` forgets every verdict, so the phone's image decides), and `--es fault silence|off` zeroes what the
    module's own path records: a dead microphone on a phone whose works, for the mid-call switch's trial. `--es pcm
    <file>` writes a raw 16 kHz mono s16le file (world-readable, e.g. under `/data/local/tmp`) over every buffer the
    module records, from now or the next call until it runs out (`MicFeed`; silence after), and `--es pcm off` gives the
    microphone back: two phones in one room call each other with no sound between them, the same words every run (the
    captions' trial, C-3.7-5). `state`'s `capture.feed` reads `playing`, `done` or null. `state` also
    carries `directHost` (ADR 2026-10.w3dw: `Allowed`, `GenericImage` or `Latched(at=…)`). Logcat under `KnitCall`:
    `webrtc: audio module on <profile> (<why>)` when one is built, `call capture: <HEALTH> on <profile>`, `call
    capture: own recorder on source <n>, <rate>Hz x<channels>`. The diagnostics card's **↑ Mic** and **↓ Heard** read
    `silent` for a microphone handing WebRTC zeros.
  - **The audio route** (§3.13, ADR 2026-10.wbua). Every call of either kind is a self-managed Telecom call, which
    owns where its audio goes.
    - `state`: the call's `audio` holds `current`, the route Telecom has it on (null until Telecom says), and
      `available`, every route it offers. Each route has a `kind` (`EARPIECE`, `SPEAKER`, `WIRED`, `BLUETOOTH` or
      `OTHER`), a `name` and a `key`. `audio` is null while the system holds no call.
    - `--es op audio --es to speaker|earpiece|wired|bluetooth`: moves the call to the first route of that kind and
      replies with the new `audio`. With no `to`, it only reports.
    - `--es op mute --ez on true|false`: the call screen's Mute, for a trial of two phones in one room, where a call
      both ways would howl; `state`'s `muted` shows it.
    - Logcat, under `KnitCall`:
      - `call <id>: added to the system (…)` and `call <id>: disconnected from the system (<cause>)`;
      - `telecom: …` for each refusal, or a verb Telecom left unanswered;
      - `walkie in: source <n>, <device>` per burst spoken, and `walkie out: <device>` per burst heard: where the walkie
        really recorded and played (`bluetooth sco` for a headset's call link);
      - `dumpsys telecom` lists the call itself.
      - `path`: for example `Wi-Fi Aware · direct · held`, `Bluetooth Coded · direct`, `Through a neighbour · flooded`,
        `Through the relay · cast` or `No link`.
    - To see a live call degrade, set `relay --ez on true` with no TURN spool: ICE fails and both phones drop to
      `degraded` without an END.
    - Logcat: `call <id>: no live path; a walkie session now`, then `walkie: session …`, and `walkie: Wi-Fi Aware
      link to … asked for` (or `not available now`) each time the call's hold changes its answer. A held link's end
      logs `sync with … done (… call=true)`: its window, not idleness. Each burst sent logs
      `walkie: burst <b> of <n>ms to … in <q> chunks (<B> B before the seal), <k> sealed, via <path>; cut>sent ms …` (when each chunk was cut and its send
      returned, from the press). Each burst heard logs `walkie: heard burst <b>: <q> chunks, <s> skipped, arrivals +…
      ms, began +<ms>` (arrivals from the first chunk's, and when playback began). Read the pair across both phones to
      place a gap at the sender, the link or the player.
    - `--es op talk --es pcm <file>`: the burst says what that file says (raw 16 kHz mono s16le, world-readable, e.g.
      under `/data/local/tmp`), read at the microphone's pace in place of it, held as long as the file lasts plus
      400 ms (an `--ei ms` still wins). Make one with `ffmpeg -i in.wav -ar 16000 -ac 1 -f s16le out.raw`. The
      transcript's end-to-end trial: send a known sentence and read it back on the receiver.
    - `--es op ns [--ez on true|false]`: the walkie microphone's platform noise suppressor (C-3.11-5), on by default;
      `false` turns it off for an A/B until the process ends, from the next burst. Replies with `on` and `suppressor`,
      what the last burst's microphone had: `on`, `off`, `unavailable` (the phone implements none) or `refused`. The
      `walkie in:` logcat line says the same per burst. A `--es pcm` burst bypasses the microphone, and with it this.
    - `state`'s `walkie` block also carries `transcript` and `stt`, as `…debug.STT` reports them (below).
- `…debug.STT` — the **walkie transcript** (C-3.11-9, ADR 2026-10.nz3g; debug builds with `BuildConfig.STT`, and
  `loop` and `clips` need the walkie too). `--es op status` (the default) replies with the model's id and size, `stt`
  (`absent`, `downloading` with `done`/`total`, `ready`, or `failed` with its `reason`), `resident` (a model in memory) and `transcript`: the session's lines, each
  `burst`, `heardAt`, `state` (`Listening`, `Pending`, `Text`, `NoSpeech`, `NoModel`, `Failed`), `text`, `live` (the
  words so far while it streams), `audioMs`, `tookMs` (its end to its final text), `looks` and `modelMs` (the model's
  time over the burst). The text appears here and on screen only, never in logcat.
  - `download` fetches the model from getknit.app as the card's and the Calls screen's buttons do (refused with no validated
    route; its foreground service may be refused from a broadcast, and the download then runs without it);
    `sideload --es dir <dir>` installs it from its nine files pushed there (`scripts/stt-model.py --out` stages
    them; `chmod 755` the directory and `644` the files), each checked against the same pins; `cancel`; `delete`;
    `unload` releases the model now (otherwise 2 min idle). The model is Moonshine Small Streaming alone; `status` names
    it (`model`).
  - `pcm --es path <file>` streams a raw 16 kHz mono s16le file through the model in 100 ms chunks as fast as it takes
    them, looking as a burst does — in the background, since a long clip outlasts a broadcast's timeout (an ANR). It
    replies at once; poll `status`, whose `pcm` is `running` until it carries `text`, `audioMs`, `tookMs`, `looks`,
    `modelMs` and `finishMs` (the last look: a burst's end to its text): the engine alone, no call. `lines --es mode
    whole|split` sets how a burst becomes lines (`LineMode`; whole by default; in memory, reloads the model),
    and `status` names it (`lines`).
    `pcm` also takes a saved clip's WAV (its 44-byte header skipped). `--ez continuous true` opens the stream a live
    call's captions do (C-3.7-5), split at every pause whatever `lines` says, for the two modes' cost on one clip.
  - **A live call's captions** (C-3.7-5). `status` also carries `captions` (the call's `status`, `ON`, `BEHIND` or
    `FAILED`, and its `lines` in `transcript`'s shape, with `settled` beside `live`) and `liveCaptions`, the CC
    button's choice. `captions --ez on true|false` sets it (persisted, as the button is; read as the peer's audio
    comes, so a call already up is captioned from then on). Logcat (`KnitCall`): `captions: run <n>: <ms> ms of audio,
    <n> look(s), <ms> ms in the model, <n> chars` per settled line, and `captions: stopped: behind|failed`.
  - **A chat voice note's text** (ADR 2026-10.am8d). `note --es hash <attachmentHash>` is the bubble's Transcribe:
    the note opened with its row's key and queued on `VoiceNoteTranscriber` (refused, with `notes`, during a call or
    while that note already runs; an error without the model). Every `status` carries `notes`, each note asked for in
    this process by hash, with `state` `running` (`settled`, `live`), `done` (`text`), `busy` or `failed`; `STATE`'s
    message rows carry the saved `voiceTranscript` and `voiceDurationMs`. Send the note with `SENDIMG --ez voice true`
    (an AAC-LC ADTS file, e.g. `ffmpeg -i in.wav -ac 1 -ar 16000 -c:a aac -b:a 32k -f adts note.aac`). Logcat
    (`KnitCall`): `voice note <hash>: <ms> ms of audio, whole|split, <n> look(s), <ms> ms, <n> chars`, never the text.
  - `keyterms --es terms 'Ada,Grace Hopper'` replaces the names the model listens for from the next burst
    (`MoonshineSpeech.keyterms`; a call's start sets its own through `SttKeytermFeed`, and its end clears them), `--ez
    clear true` empties them; `status` lists them (`keyterms`). For an A/B of the biasing on `pcm` clips.
  - `clips` reports Diagnostics' **Save walkie clips** switch (`SettingsStore.debugWalkieClips`, off by default): `on`,
    `dir`, `clips` (how many) and `bytes`; `--ez on true|false` sets it (persisted, read as each burst begins), `--ez
    clear true` deletes every clip. While on, each burst the transcript finishes — heard or spoken, a slipped thumb
    and a dropped burst excepted, one heard with no model included — is written by `WalkieClipFiles` to
    `Android/data/<package>/files/walkie-clips/` as `<UTC stamp>-heard|spoken-b<burst>.wav` (16 kHz mono s16, as the
    model got it) and a `.json` sidecar: the line's fields as `transcript` gives them, plus `model`, `lines`,
    `call` (the call's id, the same on both phones) and, for a spoken burst, `suppressor`. Unencrypted, capped at
    256 MiB (oldest first); pull with `adb pull /sdcard/Android/data/app.getknit.knit/files/walkie-clips`.
  - `loop --es path <file>` sends the file the whole walkie way but the radio (the recorder's Opus encoder at 8 kbps,
    the player's decoder and tap, the transcriber) as burst 0 of a fresh transcript, and replies at once; poll
    `status`. It plays aloud and refuses during a walkie call. This is the single-device check of the audio path, and
    the codec's cost in one go: the transcript gets two lines, `mine: true` (the file as the recorder's tap saw it,
    before Opus) and `mine: false` (as the player decoded it).
  - Logcat (`KnitCall`): `walkie stt: <what> of <model> (<bytes> B)`, `… installed` or `… failed: …`, `walkie stt:
    <model> loaded in <ms> ms`, and per burst `walkie: transcribed heard|spoken burst <b>: <ms> ms of audio, <n>
    look(s), <ms> ms in the model, final <ms> ms after its end, <n> chars`. A model that won't load latches `stt` under
    `…debug.MODEL` like the moderation models. The runtime's own `MoonshineJNI` debug lines carry no text.
- `…debug.TRANSLATE` — the **offline translation spike** (ADR 2026-10.t2h6; a debug build made with
  `-Ptranslate=true` only, else `error`). `--es op status` (the default) replies with each direction's model (`en->es`,
  `es->en`: `absent`, `downloading` with `done`/`total`, `ready`, `failed` with its `reason`), `resident` (which hold a
  model in memory), `bench` (the last bench) and `asked` (what the `translate` op asked for, text included: the bridge
  is the one place a translation leaves the screen; logcat `Translate` carries lengths and times only).
  - `sideload --es dir <dir>` installs both directions from `<dir>/<model id>/` (`scripts/translate-model.py --out`
    stages them; push under /data/local/tmp and `chmod -R a+rX`), each checked against the pins; `download --es
    direction en-es|es-en` fetches one from getknit.app as the bubble's Download does; `unload` lets both go.
  - `detect --es text <text>` returns CLD2's answer for the text without its links, positions and mentions, and the
    pick (`en`, `es` or `unsure`). `translate --es text <text> [--es from en|es]` runs the chat's own path (the spans,
    the loop check, the content screen) as `bridge-<n>`; poll `status`'s `asked`.
  - `bench --es file <corpus> --es from en|es [--ei runs N]` runs in the background (poll `status`'s `bench`): a cold
    load timed (`loadMs`), the native heap and PSS that one loaded direction adds, then every line through the model
    whole, with `ms` p50/p95/max by length (`short` ≤ 40 chars, `medium` ≤ 160, `long`) and the counts the chat's
    checks would refuse (`unsure`, `spansLost`, `unusable`). One JSON line per message, translation included, goes to
    the app's external files (`translate-bench/<from>-<to>.jsonl`, `out`). `scripts/translate-bench.py` drives it
    across phones and lays the results beside the desktop bench's.
  - `reader` reports the product rule's pick of this reader's language (`target`), from this phone's last 50 own
    messages (`written`, `unsure`) and the system's languages, beside the app's resolved locale, which the rule ignores.
  - `audit [--ei limit 300]` reads the language of this phone's newest messages from other people twice — by the
    words alone, then with the author's and the chat's language behind them (the ADR's short-line amendment) — and
    replies with counts only: `wordsAloneUnsure`, `resolvedBy` (`text`, `author`, `chat`, `unsure`), `changedByHint`
    (a confident read the hint flipped) and the same for lines of up to 20 characters (`short`).
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
- `…debug.DEMOGO` — cues a **trailer-director scene** that is holding for it: a `-PdemoDirector=true` build launched
  with `--es demo_scene call-out|call-in|walkie-out|walkie-in` seeds, opens the DM and waits, so the recording can
  start on a settled screen (its clock then begins at the scene's first beat). A no-op in any other build or scene;
  the maintainer overlay's `record-trailer.sh` fires it.
- `…debug.DEMOCALL` — the **other end of a demo build's calls** (`-PseedDemo` / `-PdemoDirector` with calls wired):
  the screen's own `CallManager` calls a scripted peer over loopback (`demo/DemoCalls`), and `…debug.CALL` drives
  this phone's side as it does a real call. `--es op ring --es peer <id> [--ez walkie true] [--ez open true]` (the
  peer dials this phone; `open` brings the call screen up without answering), `say --es line <name>` (the peer in the
  connected call says `demo/voice/<theme>/<name>.pcm` — into a live call's captions or as a walkie burst; in the
  background), `talk --es line <name>` (this phone's walkie burst says it), `mic --es line <name>` (the next burst a
  finger holds says it), `hangup` (the peer hangs up), `answer --el ms <n>` (how long a rung peer waits before it
  answers; negative lets it ring) and `captions --ez on true|false` (a live call's CC button). Errors in a non-demo
  build.
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
- `…debug.RESETDB` — **empties the database** for a fresh lab run and keeps who the phone is: the identity and
  prekeys (`files/identity.key`), the database key and the DataStore (name, status, profile stamps, settings, the
  accepted set) live outside it, and the own avatar's blob row is read out and put back under its hash. The blob
  caches (`cache/blobtx`, `cache/blob-partials`, `cache/link-rx-*`) go too. Refused (`error`) unless the mesh is
  paused, so `PAUSE --ei minutes 60` first; the process is killed a second after the reply (`avatarKept` names
  the hash kept, or null), and the next start comes up paused — `RESUME`, then start the service as after an
  install. Run it on every lab phone in one pass, or a peer's custody walks the old traffic back in. Take a
  `BACKUP` first if the history might be wanted.
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
  `NANICM --es band 2.4|5|auto` pins the band the configs ask for (GitHub #44, `NanIcmPolicy`'s override, which
  bypasses its audio and lonely rules so a band trial can light ICM on a lonely or audio-playing phone); `auto` hands
  the choice back to the policy, anything else is an error that changes nothing. A band never cycles the session: it
  rides the next config, or one in-place publish update while ICM is lit by demand (`icm band push — updatePublish
  asks …`, else `icm band now … — rides the next config (…)`). With both extras `on` goes first. No extras reads. The
  reply carries `instantMode`, `icmAllowed`, `icmBandAsked` (the band **asked**; the one **granted** is the
  `instantModeChannel` of the system log's `WifiAwareNativeApi: enableAndConfigure` line, which `dumpsys wifiaware` does
  not show), `icmReason`
  (OVERRIDE/AUDIO/LONELY/STA_CHANNEL/NO_STA/DEFAULT), `icmBandOverride`, `staFrequencyMhz` (-1 unknown),
  `band5PrimaryUsable`, `postCure` and `icmLitNow`; each verdict change logs `icm verdict: on=… band=… why=…`. The
  pinned band **survives `--ez on` and the session cycle** and goes only with the transport's stop (`NANOFF`,
  low-power mode, a mesh stop) or a process restart, so a trial or battery arm that follows a band trial starts
  with `--es band auto`. `--ez on`'s session cycle cannot re-attach a **dormant** plane (`NANDORM`): pin it awake
  with `NANDORM --ez hold true` first, as `battery-trial.py`'s arms do.
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
- `…debug.NANOFF` — the **Wi-Fi Aware switch** (debug builds' own; `SettingsStore.debugNanOff`, also a switch under
  Diagnostics' Transports), for running the mesh on Bluetooth alone without touching the phone's Wi-Fi. `--ez off true`
  stops the plane (its links, responder and session go, `neighbors`/`reachable` empty, the composite's file route
  skips it), `--ez off false` starts it again, no extras reads. The reply carries `off`, `userSwitchOff` (the person's
  Settings › Wi-Fi Aware switch is off, `AWARE` — it keeps the plane off whatever `off` says), `present` (false where the
  device has no Wi-Fi Aware plane — the switch is still stored) and the Wi-Fi Aware row's `health` (`Unavailable`
  while off) / `linked` / `nearby`. Applied live by `mesh/SwitchableTransport` (which low-power mode drives in every
  build too), which logs
  `debug switch: WifiAware off → stopped` / `on → started` under `WifiAwareTransport` (and `is off — not starting`
  on a mesh start while off). A switch edge is floored (ADR 2026-10.d44b): a start waits 5 s after the plane last
  stopped and a stop 10 s after it last started (`SwitchableTransport.RESTART_FLOOR_MS` / `UP_FLOOR_MS`), logged as
  `debug switch: WifiAware off in <n>ms (floor)`; the row's `health` and `switchedOff` follow what the plane is
  doing, not what was asked, so a trial waits for `off → stopped` before it measures. It persists across restarts (a `debug_` key, never in a backup) until switched back
  on. While it is off, a NAN action that needs the live transport (`NANICM`, say) finds nothing bound.
- `…debug.AWARE` — the person's **Settings › Wi-Fi Aware switch** (`AwareSwitch`, key `wifi_aware_enabled`, GitHub
  #44; every build has the switch, debug builds this action). `--ez on true|false` sets it, no extras reads. The reply
  carries `on`, `present` and the Wi-Fi Aware row's `health` / `switchedOff` / `dormant`, which follow the plane
  through the same `SwitchableTransport` and floors as `NANOFF` (`debug switch: WifiAware off → stopped`). The field
  log writes `aware_switch` at each session's start and change. The key is phone-bound (never in a backup); a phone
  that had low-power mode on reads the switch off until its one-time move (`wifi_aware_migrated`) is journaled.
- `…debug.NANDORM` — Wi-Fi Aware **dormancy** (`NanDormancyPolicy`, GitHub #44): with nothing to do for 3 min (no cue
  target, no link, nothing owed, no Aware candidate heard by Bluetooth, and Bluetooth demonstrably watching for
  newcomers) the plane closes its session, and NAN goes down with it (`aware dormant: IDLE — …`); a new candidate near,
  a bulk transfer to one, Bluetooth going blind or a duty attach that finds someone wakes it (`aware awake: <why>`),
  and every 12 min it attaches for 30 s (`aware duty attach: …`, then `aware dormant: DUTY_EMPTY` or `aware awake:
  DUTY`). `heal()` never wakes it, nor does Knit coming on screen: that runs one probe window (`aware duty attach: probe
  (on screen) — …`, then `DUTY_EMPTY` or `aware awake: PROBE`) at most once per 5 min from the later of the last
  window's start and the last dormancy close, and only when the attach budget allows it now; a refused one logs `aware
  probe skipped: <FLOOR|BUDGET|PAUSED|WINDOW_RUNNING|SETTLE> …` and arms nothing, and a candidate already near wakes
  the plane `SIGHTING` instead (ADR 2026-10.2t49's 2026-10-26
  amendment). `--ez hold true` pins the plane awake (a dormant one
  wakes, why `FORCED`), `--ez hold false` lets the policy decide again; `--ez now true` closes the session for dormancy
  now (an error string in `message` — `link activity in progress`, `paused …`, `already dormant` — when it will not);
  `--el duty <ms>` sets the duty interval for a trial (`0` hands it back; a dormant plane reschedules from its close).
  With several extras: hold, then duty, then now. No extras reads. The reply carries `dormant`, `why` (the last
  transition's: IDLE/SIGHTING/BULK/DUTY/PROBE/DUTY_EMPTY/BLIND/FORCED; VISIBLE is retired), `dormantForMs`,
  `nextDutyInMs` (null unless dormant between duties), `dutyActive`, `probeActive` (the running window is the on-screen
  probe), `nextProbeAllowedInMs` (the probe's floor alone, 0 once it has run; null while awake), `idleForMs` and `stayReason` (awake: what holds it attached, null when the idle
  clock runs), `candidatesNear` / `candidatesHeard` / `seesNewcomers` (what Bluetooth pushed), `held`,
  `dutyIntervalMs`, and the radio evidence the dormant health and the advert's `FLAG_NO_AWARE` read: `available`
  (`isAvailable` now — trial F samples it beside `dumpsys wifiaware`'s `mUsageEnabled`), `wifiEnabled`, `deviceIdle`,
  `radioOff` (the health's reader: Wi-Fi off, or `isAvailable` false for 30 s outside the 30 s beat after our own
  close; never deep idle alone) and `wontRun` (the advert's: `radioOff`, `deviceIdle` or an abandoned attach, plus no
  hardware). A dormant plane's row reads `Healthy` unless `radioOff`, so a dozing dormant phone reads `Healthy` with
  `wontRun: true`; an idle edge logs `aware evidence: device idle edge (idle=…)` and re-reads both at once. The field log writes `nan_dormant` per
  transition. Hold, interval and dormancy all go with the transport's stop (`NANOFF`, `AWARE`, a mesh stop). While
  `seesNewcomers` is false (no Bluetooth plane, or one not yet reporting) the plane never goes dormant. A candidate
  already near when `--ez now true` forces dormancy does not wake it (only a new one does, or the other triggers);
  after any other close a near candidate wakes it on the next dormant tick. The negative
  control is `--ez hold true`, which keeps `stayReason: "held"` for as long as it is set.
- `…debug.LOWPOWER` — **low-power mode** (ADR 2026-10.d44b): the user's switch (`LowPowerSwitch`, the Settings row).
  **Not offered since GitHub #44** (`LOW_POWER_OFFERED = false`): the row is hidden, the posture stays off whatever
  the key, Battery Saver or the charger say, and the reply's `offered` is false; the rest below is what the mode does
  when it is offered again.
  `--ez on true|false` sets it, no extras reads. The reply carries `lowPower` (`switch`, `batterySaver`, `charging`,
  the posture's `active` and `reason` SWITCH/BATTERY_SAVER, null while off) and the Wi-Fi Aware row's `awareHealth` /
  `awareSwitchedOff`, which follow the plane, not the switch (the floors under `NANOFF` apply). The posture also comes
  on with Battery Saver (`adb shell cmd power set-mode 1`, `0` to undo). A lift the switch did not make waits: a
  minute on the charger (`LowPowerPolicy.CHARGE_HOLD_MS`, Battery Saver's own plug-in off included), 5 s off it
  (`LIFT_SETTLE_MS`); the switch going off lifts at once. While it is on, Wi-Fi Aware stops
  through the same `SwitchableTransport` as `NANOFF` (the same `debug switch: WifiAware off → stopped` line) and
  `MeshService` leaves the significant-motion sensor unarmed (`motion: low-power mode, sensor disarmed`); `LowPower`
  logs each posture (`low-power posture: SWITCH`). The negative control is `--ez on false` with Battery Saver off and
  the Aware row back to `switchedOff: false`. The key is phone-bound (never in a backup); `battery-trial.py`'s
  `lowpower` arm sets it and the run's restore clears it.
- `…debug.PHY` — the **BLE Coded PHY experiment** (ADR 2026-10.yvn6; builds with `BuildConfig.BLE_CODED_PHY`, else
  an error; also a segmented row under Diagnostics' Transports). `--es mode off|auto|coded|1m` stores the mode
  (applied live, persists — a `debug_` key), `--es txpower high|medium` re-raises the Coded advert, `--ei stepDown
  N` / `stepUp` / `stepDownReads` / `minGapMs` / `stepUpHoldMs` / `stepUpFast` / `stepUpFastHoldMs` (the fast step-up
  tier, −62 held 10 s) / `flapWindowMs` / `stepUpBackoffMaxMs` (the step-up backoff of a link that flaps, 5 min each,
  #169) override the step thresholds and `--ei credit N` the
  dB a Coded advert reading gains on the 1M scale (default 12, what every −90 floor reads), and `--ei fastAdvertMs N`
  / `fastHoldMs N` the Coded advert's interval after a link at range drops (250) and how long it holds (180000) — on
  the dialing side, how long the scan hunts the lost peer — and `--ei huntInUseHoldMs N` that hunt's bound while Knit
  is open on a lit screen (1800000), `--ei oneMWeak N` the smoothed 1M reading under which a 1M hit leaves the
  Coded-only clock running (−90; −200 restores the old rule) and `--ez coldHunt false` no hunt or fast advert for a
  cold far sighting (the 2026-10-20 amendment's A/B; both echoed in `tuning`), and
  `--ei codedPace N` / `codedChunk N` a file's feed rate in B/s (1024; 0 unbounded) and chunk (2048, capped at 16384)
  on a link on Coded (#114; a windowed feed's fallback there), `--ei codedWindowChunk N` a windowed feed's smallest
  chunk on Coded, which sizes the window's floors (1024; 1024 to 8192 — ADR 2026-10.5buf's Coded amendment), and `--ei filePace N` the file budget the links on 1M or 2M share, split evenly among those
  feeding a file (4096; 0 unbounded; a share's chunk is two seconds of it, 2048 to 16384 — #117, ADR 2026-10.8jwn), and
  `--ei fade N` / `fadeExit N` / `fadeExitHoldMs N` / `fadeStaleReads N` the link-fade verdict that casts a walkie
  chunk beside a dying link (−95 link RSSI to enter, −90 held 10 s to leave, 3 unanswered reads in a row — ADR
  2026-10.79yb's ninth amendment), until the process dies (`--ez resetTuning true` restores them); no
  extras reads. The reply: `mode`, `supported`
  (the controller has Coded and extended advertising), `advert` (`off` / `starting` / `live` / `refused <status>` /
  `retrying` / `dark <status>`), `txPower`, `tuning` (with `fileChunk`, the chunk of a 1M/2M link feeding alone),
  `links[]` (`nodeId`, `phy` ONE_M|TWO_M|CODED|UNKNOWN, `linkRssi`, `drives`, `attached`, `switches`, `gaveUp`, `fading`) and
  `peers[]` (`nodeId`, `rssi` on the 1M scale, `oneMSeenAgoMs`, `codedSeenAgoMs`, each PHY's own `rssi1m` /
  `rssiCoded`, and `codedLagMs`: the 1M listening its Coded hits outlast its 1M ones by, over 8000 meaning Coded alone),
  then `hunting` (the last scan window hunted) and `hunted` (the peers lost at range it hunts). Oracle for a step: `links[].phy` plus `bt phy <id> ONE_M→CODED` in logcat (and the PHY chip on the
  Diagnostics row); for a flap, `bt phy <id> reversal <n>: step-up backoff <s>s (drives=true)` on the driver (the watching side logs the same with `drives=false`; count one side). Oracle for a file's pace: `file …/… <N>B in <ms>ms @<B/s> → <id>` on the sender (`@1024` on
  Coded, `@<share>` otherwise), which times only the hand-off to the stack; what the air drained is the receiver's
  `rx …/… <N>B in <ms>ms ← <id>`, and `bt file pace budget=… feeding=… share=… chunk=…` marks each change of the
  split. A walk's record is logcat: `bt coded heard <id> hits=… rssi=a..b 1m=… eff=… dwell=… promotable=…
  dials=…` once a minute per unlinked peer heard on Coded, `bt scan coded windows on|off`, `bt refused client … codedOnly=`,
  `bt initiating to … via=coded`, `bt phy <id> step-up hold none|slow|fast (rssi=…)` as a Coded link's smoothed RSSI
  crosses −72 / −62, and at a drop `bt phy <id> link dropped on <PHY> rssi=… (<reason>)` then
  `bt coded advert fast (…)` / `slow` and any `bt coded advert refused` on the dialed side, `bt hunt <id>: lost at
  range…` (or `bt hunt <id>: cold sighting on Coded, for <ms>ms (rssi=…)` / `bt hunt <id>: cold hunt over
  (linked|backoff|gone|off)` for a cold one, and `bt coded advert fast (cold <id>, …)` on the dialed side) then `bt scan → hunt (lost=[…] inUse=…)` / `bt scan hunt held (… a2dp)` / `bt scan hunt over` on the dialing side — raise the buffer first (`adb logcat -G 16M`; the default 256 KiB holds ~40 min).
  Negative control: on a phone without Coded (the Pixel 3) `supported` is false, `advert` stays `off` and no link
  gets a PHY handle; `mode off` restores the legacy scan and lets every handle go, leaving each link on its PHY.
- `…debug.LINKTUNE` — the **Bluetooth drain trial's lever** (#120; any debug build, no Coded flag needed, so a build
  with `-PbleSidePlane=false -PbleCodedPhy=false -PbleGattPeers=false` can run it). `--es node <id prefix>|all` names
  the links; `--es phy 1m|2m|1m2m`, `--es priority high|balanced|low` and `--ei mtu N` (23..517) are asked of each, in
  that order, through a GATT client `BleLinkTuner` attaches to the link's own ACL on first use (no service discovery),
  then `--ez read true` reads the PHY and link RSSI back; `--ez close true` lets the clients go. `--ei filePace N` sets
  the 1M/2M file budget as `…debug.PHY`'s does (until the process dies; 0 unbounded), `--ei boostPace N` the budget a
  boosted link earns (ADR 2026-10.2da4), `--ei crowdedPace N` that budget past four LE connections, and `--ez resetPace
  true` restores all three (4096, 18432, 12288), the feed window's switch and its queue. `--es feed window|pace` switches
  the feed window (ADR 2026-10.5buf: a 1M/2M file to an Android peer clocked by its progress reports, the budgets then
  only its fallback) in this process, and `--ei windowQueueMs N` (100..5000) sets the queue it keeps ahead of the
  receiver; a build without `BLE_FEED_WINDOW` starts with it off. `--ez boost false` stops the link boost in this process — every boost
  lets go at once, asks nothing and attaches no new client until `--ez boost true` (a client already attached stays:
  relink between cells) — the A/B switch on one build (a build without `BLE_LINK_BOOST` never boosts, and its reply
  says `boost: false`). `--ez arm false` stops the ACL arm (#142, ADR 2026-10.b8yf) in this process — nothing more is
  armed to idle out when its channel closes (at link-up, at a refused or failed channel's end, at stop), and no orphan
  is released, but each orphan is still logged (`bt acl orphan <id> <addr> kept tries=0 …`, or `… unarmed at stop`)
  and recorded as `bt_orphan` — the A/B switch for the orphan trial; `--ez arm true` puts it back (relink between
  cells: a link armed before the switch stays armed). Like every switch here, it is applied only while the Bluetooth
  transport runs: a paused mesh answers `Bluetooth transport is not running` and drops it, so send it after `RESUME`.
  Read whether the stack took the arm in logcat: `gatt_set_idle_timeout: idle_timeout=4 … status=true` right after
  `bt acl arm … attached` (Android 16/17). The client a
  boost made and the one this action makes are the same `BleLinkTuner`; `close` lets it go and the boost makes another
  at its next ask. No node reads the budget and the links' last-heard state without making a client. An iPhone link is refused
  (`refused`: its doorbell owns the parameters, ADR 2026-09.dqvb), and no PHY is asked while the Coded experiment runs
  on the asking phone. A 1M or 2M mask lasts the link's life, so it would also block the *peer's* stepper (a foreign
  answer makes it give up, and the mask keeps the link off Coded): set `…debug.PHY --es mode off` on both phones
  before a PHY ask; `codedOwned` says a Coded handle steps the link here. From Android 14 the first `mtu` ask on an ACL
  exchanges 517 whatever it names and a later one exchanges nothing, so read `mtu` (settled) against `mtuAsked`, and
  judge that lever by the snoop's data-length change, not the ATT MTU (which does not bound an L2CAP channel). The
  reply: `filePace`, `fileChunk`, `boostPace`, `crowdedPace`, `leLinks` (this phone's LE connections), `boost`, `arm`, `feed`,
  `windowQueueMs`,
  `feeding` (links feeding a file now), `earned` (every one of them earned the boost) and `links[]` (`nodeId`,
  `address`, `attached`,
  `txPhy` / `rxPhy` ONE_M|TWO_M|CODED, `intervalUs` / `latency` / `timeoutMs` — the framework reports them only on a
  change after the client attached, so an ask the link already satisfies leaves them null — `paramsStatus`, `mtu`,
  `mtuAsked`, `rssi`, `codedOwned`, `refused`, and the link's boost's `boosting` / `earned`, null before its first
  file). Logcat carries `bt tune <id> attached|ask …|params interval=…us latency=… timeout=…ms status=…|phy tx=… rx=…
  status=… (read|update)|mtu=… asked=…` and `bt boost <id> high|release|earned …|unearned …` (the arm's own lines read
  `bt acl arm <id> attached …` and a release's `bt acl release <id> …` instead, so the drain trial never counts them); the
  HCI snoop is the oracle, read with `scripts/btsnoop-drain.py summary <snoop> --logcat <dump>` (per-link parameter,
  PHY and data-length timelines with who asked, packets per connection event, the controller's queue, and a verdict
  per file). `scripts/ble-drain-trial.py run` drives a whole cell: budget, relink, ask, quiet wait, a fresh file, texts
  behind it, and both phones' logcat dumped per rep, then puts the budgets and the boost back, lets the tuners go and
  relinks a tuned link on the way out; `--boost on|off` runs one half of the boost's A/B (pace then defaults to 4096)
  and the summary adds `earned` and `releaseAfterS` (feed end to the tuner's served `ask priority=BALANCED`). An ask lasts the link's life: relink (`BLECAP --ei max 0`, then `--ez clear true`) between
  cells.
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
- `…debug.REQNOTIF --ei count N` (default 1, capped) — writes N synthetic **unaccepted inbound DMs** from fresh
  unknown peers, so each is a message request, then posts the coalesced "message request received" heads-up. The
  radio-less build never runs `InboundPipeline`, its only production caller, so this is the seam the UIAutomator
  notification test drives. Needs `POST_NOTIFICATIONS`, or the post silently does nothing.
- `…debug.REVIEW` — dumps the **rate prompt's gate** as the real prompt would evaluate it now (inputs from
  `ReviewPrompter.gateInputs` / `installedFromPlay`, so it cannot drift from production), plus the installer-aware
  `rateUrl` and `feedbackUrl`. `--ez reset true` clears the persisted state first; `--ez arm true` also backdates the
  engagement watermark past the age gate. The message-count gates still need real rows, e.g. `SEND` from a second
  device.
- `…debug.TYPING` — fires one best-effort **typing cue** for `--es conv <id>` (or `--es to <peer>`; default the room),
  exactly as the chat input's throttle does. Fire-and-forget: it answers `ok` whether or not anyone was reachable,
  so confirm on the receiver through `…debug.STATE`'s `typing` map.
- `…debug.SHAREAPK` — runs the offline **Share Knit app** prepare step with no share sheet (`prepareKnitApk`). For a
  Play App Bundle install it merges the on-disk splits into one re-signed APK; `splitInstall` says which path ran, and
  `files` lists what was staged under `cacheDir/apk`. Pull one with `adb shell run-as app.getknit.knit cat
  cache/apk/<name>` and verify it.
- `…debug.WEBPPROBE` / `…debug.WEBPCONV` / `…debug.WEBPCHECK` — the **GIF → animated WebP** path, each on `--es path
  <file the app can read>` (stage it the SENDIMG way). `WEBPPROBE` estimates what a re-encode would weigh without
  writing one. `WEBPCONV` runs the real send-side transcode (`WebpTranscode.shrink`), writes it to `--es out <file>`
  and reports `origBytes` / `outBytes` / `pctSmaller`. `WEBPCHECK` decodes a file through the same `ImageDecoder` path
  Coil's animated decoder uses and reports `animated` and its dimensions — the proof that a muxed WebP actually plays.
  `--ei dim` / `--ei fps` / `--ei q` override the production bounds on the first two.

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
