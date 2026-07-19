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
- **When editing any Kotlin/Compose/data code:** obey `.agents/rules/coding.md`.
- **When touching `mesh/`, `protocol/`, or `data/`:** obey `.agents/rules/mesh.md`, then READ the
  relevant reference — `.agents/context/mesh-transport.md` (radios / NAN / BLE),
  `.agents/context/wire-format.md` (CBOR wire), `.agents/context/store-and-forward.md` (custody /
  convergence), `.agents/context/e2e-encryption.md` (crypto).
- **When writing or running tests, or checking accessibility:** READ `.agents/context/testing.md` (unit +
  Robolectric Room + seeded UI / FTL + black-box UIAutomator + the accessibility/ATF suite that mirrors the
  Play pre-launch report).
- **When driving the app on a device:** obey `.agents/rules/devices.md` first, then use
  `.agents/context/debug-bridge.md`.
- **Before an architectural choice:** CONSULT `.agents/memory/decisions.md`; for what's deliberately
  deferred, CHECK `.agents/memory/roadmap.md`.
- **For maintainer-only workflows a public clone doesn't include** (release testing on physical devices,
  soak/convergence trials, store/marketing capture — and more over time): a local, gitignored **`.private/`
  overlay** may be present. If `.private/AGENTS.md` exists, load it as a nested router (nearest-wins); it is
  absent from public clones.

## Capabilities

- RUN skills in `.agents/skills/` — `kotlin-patterns` (idiomatic Kotlin), `material-3` (Compose M3),
  and `dotagents-standard` (maintain this AGENTS.md router / `.agents/` layout). Skills are vendored in
  the repo (real files under `.agents/skills/`, surfaced to Claude Code via `.claude/skills/` symlinks),
  so cloners get them without any global install.
- APPEND durable decisions to `.agents/memory/decisions.md`; update `.agents/memory/roadmap.md` as
  deferred scope ships.
- If a task needs context this router doesn't point to, treat the missing routing as a bug — do the work,
  then add the routing line here.
