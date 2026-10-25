---
id: "2026-10.s7wg"
slug: a-developer-view-reads-only-what-this-phone-already-holds
title: "a developer view reads only what this phone already holds, behind one build flag and one switch"
date: 2026-10-25
topics: [diagnostics, ui, privacy]
---

# ADR 2026-10.s7wg — a developer view reads only what this phone already holds, behind one build flag and one switch

Status: Proposed (2026-10-25). This is the first slice, the message inspector and the mesh HUD. Nothing is built
yet, and no wire byte, column or log line moves. The slice ships with ADR 2026-10.e42d, under which the inbound path
records which phone radio a frame arrived on. That is the one fact the inspector needs and the phone did not keep.

**What was asked.** A developer view, throughout the app, of the state and events underneath it. What exists today
is either global or reachable only over adb:

- Diagnostics shows the planes, the `MeshMetrics` counters, peers by reach tier and the spools.
- The field log (ADR 2026-10.a239) keeps a redacted record for the user to hand over. It is off by default and is
  read on a laptop.
- The call diagnostics card shows one call while it runs.
- The debug bridge's `STATE`, `STORE` and `RATCHET` answer the per-message and per-frame questions, but only as
  JSON over adb, from a laptop beside the phone.

On a walk, or at a table with three phones, the questions are about one thing on the screen. Did this message
leave, and over what? Who has it? Is its picture here, and is anyone carrying it? Is the mesh under this screen
alive at all right now? Each one needs adb today.

**What is proposed.** Two surfaces behind one gate.

- **The message inspector** is a section at the foot of the Message details screen, the long-press → Details the
  chat already has. It shows what this phone holds about that one message, raw, in five groups:
  - *Identity:* the frame id, the conversation id and kind, the sender and recipient node ids and the row's kind.
    It also shows `sentAt` (the author's clock), `arrivedAt` (ours) and their difference. The difference is
    labelled as clock difference plus transit, the field log's caveat for its `IN` latency.
  - *Delivery:* `received`, `receivedVia` by plane and `pendingKey`. The plane is the radio, `Bluetooth` or
    `WifiAware`, for every row written once ADR 2026-10.e42d is in, and `Nearby (radio not recorded)` for a row
    written before it. Then every `message_receipts` row as stored (acker, `notedAt`, `via`), including the
    ackers the screen's roster split leaves out.
  - *Custody:* whether `forward_store` holds the frame live (`ForwardDao.liveRow`). If it does: its origin, type,
    `receivedAt`, the TTL left, and the sizes of the signed bytes and the signature. A chat frame's envelope is
    decoded from those bytes with `WireCodec`, showing the crypto version, the form (wrapped keys, DM ratchet or
    group sender key) and the header's counters: `se`, `pe`, `n`, and the init and reset flags for a DM; `se` and
    `n` for a group. This is a decode only, with no key touched and no session read. Bytes that do not decode show
    their sizes alone.
  - *Attachment:* the travel hash, the plaintext and source hashes, whether it is keyed, the MIME type, name,
    declared size and video facts. Then:
    - whether the bytes are here and their size (`BlobRepository.sizes`);
    - progress while they arrive (`MeshController.arrivals`);
    - the screening verdict and its score (`blob_verdicts`);
    - the holder proofs (`attachment_holders`: holder, plane, `provedAt`);
    - the fetch approval;
    - how many custodied frames pin the hash (`ForwardDao.countByAttachmentHash`).
  - *Checks:* `moderation` and `hiddenByFilter`; the quote verdict (`replyCheck`, `replyToFlagged`, ADR
    2026-10.gpa3); the raw `styles` and `mentions`; and a LoRa message's origin fields (node, hops, SNR, MQTT, the
    signature verdict).

  The inspector has its own view-model, `MessageInspectViewModel`, so `MessageDetailsViewModel` reads nothing more
  in any build. Live rows (the message, its receipts, the arrivals) are flows. The rest are one-shot reads on a 2 s
  tick while the screen is started, never a table subscription: Room invalidates per table, which is ADR
  2026-09.fjcw's lesson.
- **The mesh HUD** is a 44 dp bubble drawn over every NavHost destination, from `KnitApp`, inside the safe area. Its
  ring says whether the mesh is alive (green linked, neutral searching, red down, dim off) and its number counts the
  neighbours a short-range radio holds. A tap opens a panel whose first line reads like `BLE 2/5 · NAN dormant · LoRa up · relay 1/2 · cust 41 · 12↓ 3↑ 0✕ /min`:
  - Each plane shows its `TransportStatus` linked/nearby counts, or, when it is not healthy, its health,
    `switchedOff` or `dormant`.
  - Then the connected spools, the live custody count (`ForwardDao.count`, read every fifth tick) and the deltas of
    `MeshMetrics.frames()` over the last minute: delivered, sent (originated plus relayed plus handed on) and
    dropped.
  - A stopped, paused or deferred mesh says so in place of the planes.

  Below the line, the panel has one row per plane carrying every `TransportStatus` field, the neighbour and
  indirect-reach counts, the mesh's posture (`meshEnabled`, the pause deadline, `MeshStartGate.deferred`) and a
  button to Diagnostics. It opens beside the bubble, toward the larger side, and scrolls inside that room, so it
  never covers the bubble. The bubble can be dragged anywhere and settles on the nearer side edge. Its position is
  held in memory and resets with the process. It is one accessibility node, a button whose description is the line.
  It reads on a 1 s tick only while composed and started (`collectAsStateWithLifecycle`). The line's formatter, the
  minute's rate ring, the ring's signal and the bubble's and panel's placement (`HudPlacement`) are pure classes.

  The first build drew the line itself as a pill under the status bar. On a phone it covered the top bar's title,
  and one line at that width cut off the frame counts. The bubble covers 44 dp and keeps the line a tap away. A strip
  inside the layout, pushing every screen down, was set aside: it would cost every screen a row, for good, and stack
  with the call bar.
- **The gate** is two steps:
  - `BuildConfig.DEV_VIEW` is on in debug and off in release, and `-PdevView=` overrides it, the `commons` flag's
    pattern. It is read once, as `DEV_VIEW_WIRED` in `di/`. The two view-models' Koin definitions and the
    Diagnostics switch row sit behind it, so R8 drops the view from a build without it.
  - A runtime switch, Diagnostics › Controls › Developer view (the `debug_dev_view` key, off by default), turns
    both surfaces on. The key is backup-transient under the existing `debug_` prefix
    (`SettingsKeys.TRANSIENT_PREFIXES`).

**What it never shows or does.** The view shows no attachment key (only whether there is one), no ratchet header's
`ek`, no TURN credential, no ticket, no badge's `r` or `sub`, and no key material. It does not repeat the body,
which is on the screen above it. Values are selectable text (`SelectionContainer`), so an id can be copied, but
there is no copy-all and no export. The view writes nothing, logs nothing and sends nothing.

A fact the phone does not hold shows as `—` and is never inferred. The arrival radio was the standing example. It is
now recorded under ADR 2026-10.e42d, through the column and codes that were reserved for it. Rows from before keep
`Nearby`, and the inspector labels them as unrecorded rather than guessing. The path within a radio (side channel
or message plane) is still not held, so the inspector does not show it.

**The alternatives.**

- *A new Inspect screen or sheet on the long-press menu.* The details screen is already "the per-message metadata
  the bubble has no room for". It is reached from the same long-press and already loads the row and its receipts.
  A second route keyed by message id would duplicate its vanished-row handling for nothing.
- *Folding the facts into `MessageDetailsUiState`.* Every Details open, in every build, would then read custody and
  blobs, and the definition could not be dropped from release.
- *`BuildConfig.DEBUG` alone.* A testers' alpha, which is a release build, could not carry the view. And
  `BuildConfig.DEBUG` is no compile-time constant (`Boolean.parseBoolean`), so the gate could not be a `const val`.
- *On in every build behind a hidden developer mode* (seven taps on the version). That would ship a surface which
  puts node ids, frame ids and hashes on a screen people photograph and share. It needs its own privacy pass first,
  so it is deferred.
- *The debug bridge's JSON as the inspector's model.* The bridge's field names are harness contracts that the soak
  scripts read, and the inspector's rows need to stay free to change. The bridge stays as it is. If it ever reads
  the inspector's builder, that is a new action, never a change to an old one.

**Text.** The two surfaces' chrome is string resources, translated with each locale: the section header, the
switch's label and body, the HUD's Diagnostics button. `MissingTranslation` stays an error. The rows use the code's
own names (`forward_store.expiresAt`, `receivedVia`) with their values in log notation. They are built by the pure
classes and never handed to `Text` as literals, so `HardcodedUiText` holds by construction. They join "Deliberately
English" in `.agents/context/translations.md`, beside the call diagnostics card.

**What it costs.**

- A HUD that is on reads one SQLCipher count every 5 s and allocates one `Frames` snapshot a second. A HUD that is
  off composes nothing and runs nothing.
- The bubble covers 44 dp of whatever is under it, and the open panel more. Dragging is the answer, not insets on
  every screen.
- A ratchet header read from custody is the frame's as it was sent. It says nothing about the session's state now;
  the bridge's `RATCHET` stays the answer for that.
- A debug build's screenshots, demo films, seeded FTL suite and accessibility suite stay unchanged only while the
  switch is off, which is its default.

**What keeps it true.**

- `MessageInspectionTest` covers:
  - each group, built from a row;
  - an absent fact shown as `—`;
  - a v3 DM and a v2 group frame decoded from custody bytes;
  - undecodable bytes shown as their sizes only;
  - a pin that no row's value contains the `attachmentKey`.
- `HudLineTest` covers the line for each plane state, the minute's deltas across a counter reset (a mesh restart),
  and a stopped mesh.
- The previews of both surfaces are screenshot tests (`scripts/gen-screenshot-tests.py`).
- The release build carries none of the view. R8 renames classes, so a class listing proves nothing: the check is
  the view's own literals in the release dex (`compact DM ratchet`, `not held (live)`, the HUD's and the switch's
  test tags), and none was there on 2026-10-25. The one string left is the setting's key, `debug_dev_view`:
  `SettingsStore.debugDevView` is built in every build, and nothing collects it where the view is not wired. The
  first build missed the switch row and Diagnostics' read of the setting, until both tested `DEV_VIEW_WIRED` first.
  The drop is R8's work, so no unit test can see it: repeat the check after a change to the gate.

**Later slices**, which this one leaves room for:

- an in-memory event console, never persisted, which must not add a string field to `FieldWriter`;
- reasons returned by the pure policies ("why NAN is dormant", "why the tick went to the spool");
- contact and group inspectors;
- deeper pages per plane;
- a bridge action that flips the switch on the lab phones.
