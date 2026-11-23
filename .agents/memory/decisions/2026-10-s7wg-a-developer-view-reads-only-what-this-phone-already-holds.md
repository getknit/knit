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

## Amendment 2026-10-25 — slice 2: an event console, in memory only

**What was asked.** The next slice: a live record of what the mesh did, on the phone, without adb. The field log keeps
that record already, but only once its owner switches it on, it renders every peer as a pseudonym, and it is read on a
laptop.

**What changed.** The console is fed from the one door every plane's events already pass through, `FieldLog.record`.
That door gains a tap (`fieldlog/FieldTap`), called with each event as it is recorded, ahead of the field log's own
switch. So the console sees every event whether or not the field log is on, and the field log keeps exactly what it
kept before.

- **What it holds.** The console (`ui/devview/EventConsole`) keeps the last 2,000 events in memory, newest first, while
  the developer view is on. It keeps nothing while the view is off, and switching the view off empties it. It is
  never written, shared or exported, and nothing new leaves the phone.
- **How it reads.** Each event renders through the console's own `FieldWriter`: peers by the reader's label and the
  first eight characters of their node id, relays by host. `FieldWriter` gains no method, and no event changes its
  line in the field log (`FieldEventSchemaTest` stands as it is).
- **Where it is.** The HUD's panel gains an Events button that opens a full-screen console, filterable by plane (BLE,
  NAN, LoRa, relay, calls, mesh). It can be paused while it is read, and cleared. The route exists only where the view
  is wired.

**What it does not cover.** Frame-level events (a frame delivered, relayed or dropped, and why) are not field events.
The HUD's counters and Diagnostics' drop reasons keep that. Samplers (`bt_link`, the LoRa sample) and the counters
line run only while the field log is on, so the console shows them only then. A Bluetooth relink time
(`BtUp.relinkMs`) is filled in by the field log's queue, so the console's copy of a `bt_up` does not carry it.

**What keeps it true.**

- `FieldLogTest`: the tap sees every event while the field log is off, and the journal still keeps nothing.
- `EventConsoleTest`: nothing kept while the view is off; the newest 2,000 kept, oldest dropped; switching off
  empties it; the plane of each kind; and each field rendered, a peer by its label and a relay by its host.
- The console screen's preview is a screenshot test.

## Amendment 2026-11-22 — slice 3: a LoRa trace, in memory only

**What was asked.** More LoRa detail in the message inspector, without a database migration. Before this slice the
inspector said only `receivedVia = LoRa`, which names the last hop, and a bridged room post's origin fields. A
MeshCore room post showed its hops and SNR as `—`, because the room wrote them as null although the drained frame
carries both. Every other per-packet fact died in the plane:

- inbound: the packet's SNR, RSSI, hops, packet id, the board's `rx_time` and the fragment count;
- outbound: what was queued, what the board took and what it cost in air, and how the send ended.

**What changed.**

- **A MeshCore room post keeps its hops and SNR.** `MeshcorePost` carries the drained frame's hop count, read the
  way the plane already reads it, and its V3 SNR in tenths of a dB. `InboundPipeline.deliverMeshcorePost` writes both
  into the existing `originHops` and `originSnrDeci` columns. A `path_len` of `0xFF` means the frame arrived direct
  with its hop count unknown, so `originHops` stays null. The first copy wins, as in the Meshtastic room. The bubble
  is unchanged: `ChatViewModel` still draws no provenance line in the MeshCore room. No column or migration moves.
- **The plane notes what it already holds into a trace** (`mesh/lora/LoraTrace`). The notes are keyed by the row id
  the inspector looks up:
  - an inbound Knit frame, once it decodes, passes `FrameId.admits` and is past our own echo; never a fragment
    before it is whole (the completing packet's facts and the part count). The plane checks an author's signature only
    on MeshCore's elided frames and fragment sets (`readVerified`). Every other frame is noted `AUTHOR_UNVERIFIED`:
    the router verifies it later and may drop it, and the note stays;
  - an opened compact DM. Its ✓✓s and reactions are noted too, because the plane never reads the plaintext that would
    tell them apart. Their ids match no row, so they sit among the heard-only ids, which are evicted first;
  - a heard room post, under the id the room derives (`FrameId.forMeshPost` / `forMeshcorePost`);
  - our own frames, through `OutboundFrame.traceId`, which no policy reads. Our own Meshtastic room post is not
    traced: its row id is minted after it is queued.

  The events are Heard, Queued, Aired, Refused, Requeued and Ended. Aired comes once per packet the board takes, with
  its handle, the air it booked, and whether the window or the band brake held the frame. A broadcast's late NAK or
  implicit ack names only its handle, and the book maps it back to a row from every Aired's handles. The trace is write-only:
  nothing in the plane reads it, so no gate, pacing or dedup moves. Every call goes through one guarded helper that
  returns at once while the trace is off. A trace that throws is logged by exception class and never stops the
  pacer, the packet collector or the outcome collector. Release builds and the lab pass `LoraTrace.NONE`.
- **The book** (`ui/devview/LoraTraceBook`) holds the notes. It follows the switch, as the event console does: it
  keeps nothing while the view is off, and switching off empties it. All its state is behind one monitor.
  - **Per id** it keeps the first event of each kind: at most 8 Heards and 6 of each other kind (a newer one replaces
    the second-oldest), and the first requeue with the rest counted. A flood of copies cannot push out the first, and
    what is not kept is counted.
  - **A Heard never lands on an id this phone sent under.** The plane notes our own echo nowhere, so a Heard there is
    another radio's claim: a forged frame, or a contact's compact frame naming our row. It is counted and never kept.
    An id heard before this phone sent under it drops its Heards into the same count.
  - **Book-wide** it keeps ids this phone originated apart from heard-only ids, and evicts the heard-only ones first.
    An id counts as originated once it has any event but a Heard, because the plane notes those only about our own
    sends; that includes a refusal before the queue. A busy public channel cannot push out the DM under trial.
    Evictions are counted.
  - **A room post** heard again under the same id with a different payload is flagged on that Heard (`payload differs
    from the first heard`). Its id takes radio-written inputs, so the first Heard may be the forger's, and the reader
    weighs each copy by its vouched mark. The book compares a 32-bit payload tag and never shows it.
- **The inspector** gains a `lora` group: the events oldest first, in log notation. An id the book does not hold
  reads `—`, with the book's own facts beside it (when it was switched on, how many ids it has evicted), never a
  guessed cause.

**How to read it.**

- A Heard's `from` is the radio's claim, not the author. It is the board that aired the packet, which for a re-aired
  frame is the relayer. It is shown beside whether our board vouched for it (`pki` or signed), never as sender, and
  never resolved to a contact. A MeshCore channel packet names none.
- `rx_time` is the board's clock, so its gap to ours includes their skew.
- `live` says whether the plane read the packet as live. A to-phone-queue replay reads not live.
- A second Heard for one row is a re-air under a new packet (a resend, a re-probe, a re-fan or a queue replay), not
  a second path: both firmwares drop a repeat of a packet id before the phone sees it.

**What it shows that nothing did before.** A contact's per-message RSSI, SNR, hops and `rx_time` place their board in
direct radio range of ours at a minute. That is a proximity fact about a third party. It is allowed here only under
this ADR's two-step gate, in memory, while the switch is on. The deferred "on in every build behind a hidden developer
mode" alternative must count it in its privacy pass.

**What it never holds or shows**, beyond this ADR's list: payload or text bytes, names, a channel PSK or channel hash,
a full PKI or MeshCore public key (only the source prefix as the frame carries it), signature bytes (their verdict
only), nonces, seal bytes, a position, NodeInfo or telemetry field, an MQTT gateway id (`via_mqtt` is a flag),
per-fragment byte lengths. No trace event reaches the field log, `FieldTap` or the event console, the debug bridge,
logcat or a crash report. The event classes are plain classes with no `toString`, so a release dex carries no event
literal. The book is read only through a message row's Details: a row that no longer exists reads nothing, and
sign-out ends the process.

**What keeps it true.**

- `MeshcorePublicJudgeTest` and the pipeline's MeshCore room test cover the hops and SNR, including `0xFF`.
- The plane's trace tests use a recording fake. They cover each route in and out, the completing fragment, the
  refusals, that nothing is built while the trace is off, and that a throwing trace leaves the pacer and the packet
  collector working.
- `LoraTraceBookTest` covers:
  - nothing kept while off, and off empties it;
  - the first event of each kind survives 24 repeats;
  - a Heard under an originated id is counted and never kept, and an id heard and then sent under drops its Heards;
  - a refusal before the queue is kept on the protected side;
  - a different payload under one id is flagged;
  - a flood of heard-only ids keeps every originated id and counts the evictions;
  - handle mapping;
  - two writers and a reader.
- `MessageInspectionTest` covers the `lora` group's rows, the absent row with the book's facts, the per-Heard payload
  mark, and that no row carries a payload tag.
- The plane's trace tests also cover a forged whole frame under an id we queued (noted `AUTHOR_UNVERIFIED` and kept
  off our row by the book) and a frame whose id fails `FrameId.admits` (not noted).
- `di/LoraWiring` resolves the book with `getOrNull`. A graph without the view's module still builds the LoRa plane,
  which must never depend on a debug surface (`LoraRadioReachedWiringTest`).
- The release check gains the trace's literals: none of the book's row words appears in the release dex.
