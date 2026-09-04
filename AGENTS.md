# AGENTS.md — Knit

Router for coding agents. Knit is an offline Android **mesh messenger** (Kotlin/Compose) that runs
**Wi-Fi Aware (NAN) + Bluetooth LE** simultaneously behind one `MeshTransport` seam
(`CompositeMeshTransport`), no Google Nearby / GMS. This file *points* to context — load a `.agents/`
file only when its trigger matches. Full design detail lives in `docs/`.

## Identity

You are a senior Android/Kotlin engineer on a deliberately bleeding-edge toolchain (AGP 9.3.0 /
Kotlin 2.4.0, Koin DI). Favor correctness, wire/convergence safety, and matching the surrounding style
over cleverness. Start with `.agents/context/architecture.md` for the subsystem map and data flow.

## Context routing

- **Before any build / dependency / tooling change:** READ `.agents/context/toolchain.md` — the
  bleeding-edge choices (Koin-not-Hilt, the Kotlin-2.4 override, detekt/ktlint/Kover/Room as Gradle
  plugins) are deliberate; don't "fix" them.
- **Before / after running Gradle:** obey `.agents/rules/build-and-test.md` (which task when, JDK 21,
  lockfile regen). Command list: `.agents/context/commands.md`.
- **Before touching `app/src/main/baseline-prof.txt`, the `:baselineprofile` module, or the
  `nonMinifiedRelease` build type:** READ `.agents/context/baseline-profile.md` — the generator is quarantined
  behind `-Pknit.baselineProfile=true` on purpose, so that the release build consumes a committed text file
  and stays byte-reproducible for F-Droid. Don't wire the plugin into `:app`.
- **Before touching release signing, `packaging`/`ndk` config, `.gitattributes`, or anything else that
  changes release-APK bytes:** READ `.agents/context/distribution.md` — Play and F-Droid ship different
  artifacts under different keys, and F-Droid byte-compares its own rebuild against ours, so the release
  build must not depend on the build machine (no NDK on the APK path, no foojay JDK download, no Git LFS).
- **When editing any Kotlin/Compose/data code:** obey `.agents/rules/coding.md`.
- **When adding to `CHANGELOG.md`'s `## Unreleased`, or writing a fastlane changelog:** obey
  `.agents/rules/changelog.md` — two sentences, about forty words, run through the `humanizer` skill.
  A `PreToolUse` hook blocks the edit otherwise. Shipped sections are a record; never restyle them.
- **When touching `mesh/`, `protocol/`, or `data/`:** obey `.agents/rules/mesh.md`, then READ the
  relevant reference — `.agents/context/mesh-transport.md` (radios / NAN / BLE),
  `.agents/context/wire-format.md` (CBOR wire), `.agents/context/store-and-forward.md` (custody /
  convergence), `.agents/context/e2e-encryption.md` (crypto). If a change can only be made by *breaking*
  the wire, don't — park it in `docs/NEXT_WIRE_BREAK.md` (the staging list, so a future break carries them
  all at once) and find the additive route per `docs/WIRE_COMPAT.md`.
- **When touching `mesh/lora/` or `mesh/bluetooth/meshtastic/` (the LoRa/Meshtastic bridge):** READ
  `.agents/context/lora-bridge.md` — a Meshtastic board over BLE GATT extends the **Nearby room and 1:1
  DMs** over LoRa as a fast-plane-only `MeshTransport` child, off by default behind `BuildConfig.LORA_PLANE`
  (ADR 038 + 039). `mesh/lora/` is pure/JVM-tested; the only `android.bluetooth.*` importer is
  `mesh/bluetooth/meshtastic/MeshtasticGatt`.
- **When touching `linkpreview/`, `net/`, `mesh/protocol/LinkPreviewBlob`, or anything that opens an
  Internet socket outside the spool plane:** READ ADR 2026-09.n752. A link preview is a card the **sender**
  fetches and sends as an ordinary attachment under its own MIME (no wire field, no DB change); the receiver
  never fetches, both ends screen the card's picture and text into one verdict, and the fetch is gated on
  `net/InternetGate` (a validated route, never the NAN link), bound to that `Network`, https-only, with a
  private-address DNS guard. `okhttp3` stays confined to the two files `rules/mesh.md` names.
- **When touching contact cards, the Add-by-link / share-link flow, deep links (`getknit.app/c`,
  `knit://`), or `mesh/IntroSync`:** READ `docs/CONTACT_CARD.md` (the card layout + golden vectors, the
  intro driver's rules, the assetlinks prerequisite) and `docs/SPOOL_PROTOCOL.md` §3.5 (the pair scope);
  decision record ADR 042. The card is versioned by `v` and additive under the WIRE_COMPAT rules; import
  never sets `verified`.
- **When touching `mesh/crypto/scope/`, `mesh/spool/`, or the spool/internet-relay plane:** READ
  `docs/SPOOL_PROTOCOL.md` (the normative public spec; its §13 vectors are pinned by
  `ScopeVectorTest`/`SpoolRecordsTest` — change them only together), then the `ScopeSync` invariants in
  `.agents/rules/mesh.md`. The client plane carries DM **and group** scopes, off by default, with the
  relay/spool-list editor shipped (`ui/relay/`); the scope-config ctl and Tor are still deferred — CHECK
  `.agents/memory/roadmap.md` before building either, and the spec's Appendix A for what runs today. The plane also carries **attachments**
  (`mesh/spool/ScopeAttachments`, spec §4.5/§6.5/§7.3/§9.5) as a separate object class kept out of the
  scope digest on purpose. A group scope derives from the shared
  **group root** (`GroupKeyPayload.gr`, `mesh/spool/GroupRootPolicy`): any member may mint it, and its
  mint / gossip / adopt / departure-re-mint rules are spec §3.2 — read that before touching them. The
  reference daemon lives in the separate `knit-spool` repo.
- **When writing or running tests, or checking accessibility:** READ `.agents/context/testing.md` (unit +
  Robolectric Room + seeded UI / FTL + black-box UIAutomator + the accessibility/ATF suite that mirrors the
  Play pre-launch report).
- **When driving the app on a device:** obey `.agents/rules/devices.md` first, then use
  `.agents/context/debug-bridge.md`.
- **Before an architectural choice:** CONSULT `.agents/memory/decisions.md` — a generated router table
  over one-file-per-decision ADRs in `.agents/memory/decisions/`; open the files whose row matches, don't
  work from the titles. For what's deliberately deferred, CHECK `.agents/memory/roadmap.md`.
- **For maintainer-only workflows a public clone doesn't include** (release testing on physical devices,
  soak/convergence trials, store/marketing capture — and more over time): a local, gitignored **`.private/`
  overlay** may be present. If `.private/AGENTS.md` exists, load it as a nested router (nearest-wins); it is
  absent from public clones.

## Capabilities

- RUN skills in `.agents/skills/` — `kotlin-patterns` (idiomatic Kotlin), `material-3` (Compose M3),
  and `dotagents-standard` (maintain this AGENTS.md router / `.agents/` layout). Skills are vendored in
  the repo (real files under `.agents/skills/`, surfaced to Claude Code via `.claude/skills/` symlinks),
  so cloners get them without any global install.
- ADD a durable decision with `python3 scripts/adr.py new "<title>" --topics a,b`, write the body it
  scaffolds, then `python3 scripts/adr.py index`. Never hand-edit `.agents/memory/decisions.md` (generated)
  and never pick an ADR number: ids are minted `YYYY-MM.suffix` so parallel worktrees can't collide, while
  `001`-`067` keep their sequence forever. Update `.agents/memory/roadmap.md` as deferred scope ships.
- If a task needs context this router doesn't point to, treat the missing routing as a bug — do the work,
  then add the routing line here.
