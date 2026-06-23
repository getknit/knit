# CLAUDE.md

Project guidance for Claude Code. The canonical instructions live in `AGENTS.md` (build commands,
toolchain constraints, architecture, conventions, and known gotchas) and are imported below. Detailed
design notes are in `docs/ARCHITECTURE.md`.

@AGENTS.md

## Quick reminders for Claude

- Run `./gradlew :app:testDebugUnitTest` after touching `mesh/`, `protocol/`, or `data/`; run
  `./gradlew :app:assembleDebug` to validate a build (it does **not** compile test sources).
- This is bleeding-edge tooling (AGP 9.2.1 / Kotlin 2.2.10): **Koin not Hilt**, **Coil pinned to
  3.3.0**, and `android.disallowKotlinSourceSets=false` is required — see `AGENTS.md` for why before
  changing dependencies or build config.
- Keep all `com.google.android.gms.*` imports inside `mesh/nearby/`; everything else talks to the
  `MeshTransport` interface.
