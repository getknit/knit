# AGENTS.md — Knit

Router for coding agents. Knit is an offline Android **mesh messenger** (Kotlin/Compose) that runs
**Wi-Fi Aware (NAN) + Bluetooth LE** simultaneously behind one `MeshTransport` seam
(`CompositeMeshTransport`), no Google Nearby / GMS. This file only *points*: load a `.agents/` file when its
trigger matches. Full design detail lives in `docs/`.

## Identity

You are a senior Android/Kotlin engineer on a deliberately bleeding-edge toolchain (AGP 9.3.0 /
Kotlin 2.4.0, Koin DI). Favor correctness, wire/convergence safety, and matching the surrounding style
over cleverness. Start with `.agents/context/architecture.md` for the subsystem map and data flow.

## Context routing

**Before touching any class, member, constant, flag or settings key:** `grep -rn '<Symbol>' .agents/context/` and
READ every entry that names it, then the ADRs it cites. Each subsystem file below is a symbol → ADR index, and a
symbol can be governed by a file whose directory you are not in.

Build, tooling and process:

- **Before any build / dependency / tooling change:** READ `.agents/context/toolchain.md` (the bleeding-edge choices
  are deliberate; don't "fix" them).
- **Before changing any dependency version** (catalog, wrapper, plugin, lockfile, CI action or image): RUN the
  `upgrade-notes` skill, then regenerate the lockfile and verification files per `.agents/rules/build-and-test.md`.
- **Before / after running Gradle:** obey `.agents/rules/build-and-test.md`; commands in `.agents/context/commands.md`.
- **Before touching `baseline-prof.txt`, `:baselineprofile` or `nonMinifiedRelease`:** READ
  `.agents/context/baseline-profile.md`.
- **Before touching release signing, `packaging`/`ndk`, `.gitattributes` or anything that changes release-APK
  bytes:** READ `.agents/context/distribution.md` (F-Droid byte-reproduces the release).
- **When editing any Kotlin/Compose/data code:** obey `.agents/rules/coding.md`.
- **When adding or changing user-facing text, a string resource or a `values-<locale>/` file, or syncing Weblate's
  screenshots:** READ `.agents/context/translations.md`.
- **When starting a machine pass for a language, a final pass over one, or translating new English strings into the
  shipped locales:** RUN the `machine-translation` skill.
- **When renaming/moving/removing a symbol, file or ADR, or editing `.agents/` or `docs/`:** RUN
  `python3 scripts/doc-lint.py` and fix what it names (an intended old name carries
  `<!-- doc-lint: allow <name> — <why> -->`).
- **When adding to `CHANGELOG.md`'s `## Unreleased` or a fastlane changelog:** obey `.agents/rules/changelog.md`.
- **When filing or rewriting a GitLab issue:** obey `.agents/rules/issues.md`.
- **When a prompt opens with "# Paired change", or a `knit-pair` / `knit-ios-*` message arrives:** obey
  `.agents/rules/paired-change.md`.
- **Before an architectural choice:** CONSULT `.agents/memory/decisions.md` (generated; open the ADR files whose rows
  match). For deferred scope, CHECK `.agents/memory/roadmap.md`.

Mesh core (an owned subsystem's implementation is DELEGATED to its owner per `.agents/rules/personas.md`):

- **When touching `mesh/`, `protocol/` or `data/`:** obey `.agents/rules/mesh.md`, then READ the matching reference —
  `.agents/context/mesh-transport.md`, `wire-format.md`, `store-and-forward.md`, `e2e-encryption.md`. Never break
  the wire: park the change in `docs/NEXT_WIRE_BREAK.md` and take the additive route in `docs/WIRE_COMPAT.md`.
- **When touching `vectors/` or a golden/keyed/iOS vector test, or moving a wire byte:** READ `vectors/README.md`.
- **Bluetooth** (`mesh/bluetooth/` outside `meshtastic/` and `wear/`): READ `.agents/context/bluetooth.md`.
- **Wi-Fi Aware** (`mesh/wifiaware/`, the NAN journals): READ `.agents/context/wifi-aware.md`.
- **LoRa** (`mesh/lora/`, `mesh/bluetooth/meshtastic/`): READ `.agents/context/lora.md` and
  `.agents/context/lora-bridge.md`.
- **End-to-end crypto** (`mesh/crypto/` outside `scope/`, `data/ratchet/`, seeds, resets, the roster): READ
  `.agents/context/crypto.md`.
- **Blob exchange and custody** (`BlobExchange`, `ForwardStore` / `ForwardSync`, `data/forward/`, …): READ
  `.agents/context/blobs.md`.

Features and surfaces:

- **Spool / internet relay** (`mesh/spool/`, `mesh/crypto/scope/`, commons, relay invites, tickets, the room ✓✓):
  READ `.agents/context/spool.md`.
- **Calls** (`call/`, `ui/call/`, walkie, STT, TURN/STUN, casts, the call flags): READ `.agents/context/calls.md`.
- **Wi-Fi Direct transfer** (`transfer/`, `MeshTransport.pause` / `resume`, the shared radio lease): READ
  `.agents/context/direct-transfer.md`.
- **Mesh service and power** (`MeshService`, pause, foreground types, heal, low power, `PowerPolicy`): READ
  `.agents/context/service.md`.
- **Messages and attachments** (composer, drafts, formatting, quotes, link previews and `net/`, location, images,
  video, saved files, search, translation): READ `.agents/context/messages.md`.
- **Contacts and peers** (presence, labels, profiles, group photos, contact cards, removal, badges): READ
  `.agents/context/contacts.md`.
- **Moderation** (models, word lists, send- and receive-side screening): READ `.agents/context/moderation.md`.
- **Storage** (SQLCipher, Keystore secrets, backup/restore, clones, sign-out): READ `.agents/context/storage.md`.
- **Diagnostics** (crashes, ANRs, the field log, the developer view): READ `.agents/context/diagnostics.md`.
- **App shell** (onboarding, permissions, supervision, About/licenses, Your mesh, avatars, shortcuts): READ
  `.agents/context/app-shell.md`.
- **Wear OS** (`wearstatus/`, `mesh/wear/`, `:wear`, the watch faces): READ `.agents/context/wear.md`.

Tests, devices and review:

- **When writing or running tests, or checking accessibility:** READ `.agents/context/testing.md`. A change to what
  two nodes exchange gets a `mesh/lab/` scenario ending in `assertConverged`.
- **When adding/renaming a `@Preview` or touching screenshot tests:** READ the "Compose preview screenshot tests"
  section of `.agents/context/testing.md`.
- **When creating or modifying a test, or code in an owned subsystem:** obey `.agents/rules/personas.md` — which
  reviewer to ADOPT before the change is done (a Stop hook enforces it).
- **When delegating to an owner, spawning a reviewer or relaying findings:** obey `.agents/rules/review-rounds.md`.
- **When driving the app on a device:** obey `.agents/rules/devices.md`, then RUN the `debug-bridge` skill.
- **For maintainer-only workflows:** if `.private/AGENTS.md` exists, load it as a nested router.

## Capabilities

- RUN skills in `.agents/skills/` (vendored; surfaced to Claude Code via `.claude/skills/`): `kotlin-patterns`,
  `material-3`, `debug-bridge`, `weblate-cli`, `machine-translation`, `upgrade-notes`, `dotagents-standard`, `adr`.
- SPAWN reviewer and DELEGATE to owner personas in `.agents/personas/` as `.agents/rules/personas.md` says.
- ADD a decision with `python3 scripts/adr.py new "<title>" --topics a,b`, then `python3 scripts/adr.py index`; never
  hand-edit `decisions.md` or pick an ADR number. The `adr` skill has the format, writing and lint rules, and its own
  copy runs `lint`. Update `.agents/memory/roadmap.md` as deferred scope ships.

## Maintenance

- This file holds routing lines only. A new invariant, ADR pointer or symbol entry goes in the matching
  `.agents/context/`, `.agents/rules/` or `.agents/memory/` file — never here, never in host-local agent memory.
  Add a line here only when no existing line routes to that file.
- Missing routing is a bug: do the work, then add the entry (or, for a new file, the line).
