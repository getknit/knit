# Diagnostics — which decision governs which symbol

The symbol → ADR routing for the crash store, the field log and the developer view: everything that records how the
phone behaved and leaves it only by the user's hand.

Each entry names the symbols that trigger it and the ADRs to read (`.agents/memory/decisions.md` maps an id to its
file, amendments included). An entry is an index into its ADRs, not a substitute: where they disagree, the ADR wins
and the entry is fixed. `grep` this file for every symbol you touch; `AGENTS.md` routes here instead of carrying these
lines.

## Entries

- **When touching `crash/` (the crash store, `CrashRedactor`, `AnrTrace`, `importAnrs`) or the Diagnostics crash
  screens:** READ ADR 028 and ADR 2026-10.fzsy. Reports are written redacted and leave the phone only by the user's
  hand; ANRs are imported from `ApplicationExitInfo` at start, once each (the `anr-seen` watermark), and `AnrTrace`
  is the privacy boundary for native frames, which the redactor passes verbatim.
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
