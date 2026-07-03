# CLAUDE.md

Project guidance for Claude Code. The canonical instructions live in `AGENTS.md` (build commands,
toolchain constraints, architecture, conventions, and known gotchas) and are imported below. Detailed
design notes are in `docs/ARCHITECTURE.md`.

@AGENTS.md

## Quick reminders for Claude

- Run `./gradlew :app:testDebugUnitTest` after touching `mesh/`, `protocol/`, or `data/`; run
  `./gradlew :app:assembleDebug` to validate a build (it does **not** compile test sources).
- This is bleeding-edge tooling (AGP 9.2.1 / Kotlin 2.4.0): **Koin not Hilt**, **built-in Kotlin
  overridden to 2.4.0** (KGP on the root buildscript classpath, since AGP bundles 2.2.10), and
  `android.disallowKotlinSourceSets=false` is required — see `AGENTS.md` for why before changing
  dependencies or build config.
- The transport runs **two radios at once — Wi-Fi Aware (NAN) + Bluetooth LE** — behind
  `CompositeMeshTransport`, not Google Nearby/GMS. Keep `android.net.wifi.aware.*` (and the NAN
  data-path `ConnectivityManager`/`NetworkRequest`) imports inside `mesh/wifiaware/`, and
  `android.bluetooth.*` inside `mesh/bluetooth/`; everything else talks to the `MeshTransport`
  interface. minSdk is 33.
