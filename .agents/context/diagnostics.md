# Diagnostics — which decision governs which symbol

The symbol → ADR routing for the crash store, the field log, the developer view and the node sections: everything
that records how the phone behaved and leaves it only by the user's hand, and what the screen shows of the mesh.

Each entry names the symbols that trigger it and the ADRs to read (`.agents/memory/decisions.md` maps an id to its
file, amendments included). An entry is an index into its ADRs, not a substitute: where they disagree, the ADR wins
and the entry is fixed. `grep` this file for every symbol you touch; `AGENTS.md` routes here instead of carrying these
lines.

## Entries

- **When touching `crash/` (the crash store, `CrashRedactor`, `AnrTrace`, `importAnrs`) or the Diagnostics crash
  screens:** READ ADR 028 and ADR 2026-10.fzsy. Reports are written redacted and leave the phone only by the user's
  hand; ANRs are imported from `ApplicationExitInfo` at start, once each (the `anr-seen` watermark), and `AnrTrace`
  is the privacy boundary for native frames, which the redactor passes verbatim.
- **When touching `crash/TraceText` or adding a caller:** READ ADR 2026-10.47rw's 2026-11-05 amendment. It is the one
  place a crash means to tell Google something: text in frames, which reach Play and pass the redactor unread, so a
  caller hands it structure and counts, never anything a peer or the user wrote.
- **When touching the Diagnostics share paths (`launchCrashShareChooser`, `FieldLogViewModel.sendInChat` /
  `CrashLogViewModel.sendInChat`, `FieldLogExport.fileName`):** READ ADR 2026-11.vdx4's 2026-11-02 amendment. The
  share sheet leaves Knit out (`EXTRA_EXCLUDE_COMPONENTS`), because the chat refuses a URI on Knit's own provider;
  Send to a chat hands the same rendered file to the chat picker as bytes (`OwnFile`), never a URI.
- **When touching `fieldlog/`, a `FieldEvent`, a `field.record(…)` / `field.sampler(…)` / `follower(…)` call in a
  transport, the spool plane (`mesh/spool/SpoolFieldLog.kt`), the LoRa plane (`mesh/lora/LoraFieldLog.kt`) or the call
  plane (`call/CallFieldLog.kt`, `CallMediaSampler`), `ui/diagnostics/FieldLog*`, or `MessageDao.inboundDelays` /
  `receiptDelays`:** READ ADR 2026-10.a239 (its 2026-10-18 amendments for the call lines and the LoRa, spool and route
  lines) and
  `docs/FIELD_LOG.md`. Every build can keep a bounded record of how the radios behaved (4 MiB, 72 h, in
  `noBackupFilesDir`) — off until the user switches it on — and only the user shares it from Diagnostics; no
  upload path, ever. `FieldWriter` has
  no string field (a peer or a relay is a salted pseudonym), so redaction holds by construction; never add one. A new event
  is a golden line in `FieldEventSchemaTest`, a row in the doc and a kind in `scripts/fieldlog.py`; a sampler
  runs on the log's tick and only reads.
- **When building or touching the developer view** — the message inspector at the foot of Message details, the mesh
  HUD over every screen, `BuildConfig.DEV_VIEW` / `-PdevView=`, its `di/` constant or the `debug_dev_view` switch:
  READ ADR 2026-10.s7wg (Proposed). It reads only what this phone already holds, and shows an absent fact as `—`,
  never inferred. The one fact it needed and the phone did not keep, the radio a frame arrived on, is recorded under
  ADR 2026-10.e42d: `InboundPipeline.planeOf` names `Bluetooth` / `WifiAware`, and every reader tests `shortRange`,
  never `== Nearby`. Its event console (the ADR's slice-2 amendment) is the field log's `FieldTap`, handed each event
  ahead of the field log's switch, in memory only: never written, and `FieldWriter` gains nothing for it. It writes, logs and sends nothing, shows no key material, ticket or credential, and its rows are
  deliberately English. It ships only where the flag is on, and shows only while the switch, off by default, is on.
- **When touching the Diagnostics node sections (`DiagnosticsViewModel.state`, `NodeInfo`, `KNOWN_LIMIT`,
  `hideableKnown`, the selection mode and Hide / Show hidden in `ui/diagnostics/DiagnosticsScreen.kt`, or
  `HiddenKnownStore`):** READ ADR 2026-09.2ajk (the four reach tiers; *Known, not reachable* is the
  peer table's remainder, newest profile first, five shown) and ADR 2026-11.b37x (a Known row can be hidden, pinned to
  the profile version it showed; presentation only, so no mesh code reads the key and the peer row is never touched).
  The tiers come from `reachOf`, shared with the profile line; never add a tier rule here.
