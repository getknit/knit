# Owner and reviewer personas

Which persona in `.agents/personas/` writes a change, which one reviews it, and what the main session runs before it
calls the change done. How the rounds between them go — the design pass, the order of stages, Blocking against
Non-blocking, follow-up passes — is `.agents/rules/review-rounds.md`. The Stop hook (`.claude/hooks/review-gate.sh`,
mapped by `.claude/hooks/review-gate.conf`) enforces every review below.

## Owners: delegate implementation in an owned subsystem

Five subsystems have an owner: `bluetooth-owner`, `wifi-aware-owner`, `lora-owner`, `crypto-owner` and `blobs-owner`.
For implementation work in one (the scope is the owner's own frontmatter and its `.agents/context/<subsystem>.md`):

1. Run the subsystem's reviewer on the plan first — the design pass in `review-rounds.md`.
2. DELEGATE (Claude Code: spawn) the owner with a self-contained brief: the goal, the issue or ADR, and whether a wire,
   vectors, schema, convergence, lab, paired-profile, hard-cut (`SERVICE_NAME`, `PMK`, SSI), cross-platform or iOS
   change is in scope.
3. Run the subsystem's reviewer on what it wrote (and `bluetooth-reviewer` too when `mesh/bluetooth/meshtastic/` or a
   `FramedLink` line moved), apply the findings, and relay the lab scenario or device trial both name.

An owner writes and tests code inside its scope, never commits, and reports what its reviewer then checks — the
reviewer is never the agent that wrote the change.

## Reviewers: who must look before a change is done

Each reviewer hunts one class of defect from a catalogue of the ones this repo already shipped and fixed, and never
edits.

- **When creating or modifying anything under `app/src/test/java/app/getknit/knit/mesh/lab/`:** keep
  `scripts/lab-lint.py` clean (a `PostToolUse` hook runs it on every scenario edit, `lab-chaos.sh` before every sweep,
  both CIs as `lab-lint`; a deliberate finding carries `// lab-lint: allow <rule> — <why>`), then RUN
  `scripts/lab-chaos.sh --tests '<Class>'` on the changed classes (or `'<Class>.<method>'` for the changed
  scenarios of a large class) once your edits are in, then ADOPT (Claude Code: spawn) the `mesh-lab-reviewer`
  persona in `.agents/personas/mesh-lab-reviewer.md` before calling the change done — every lab flake has been a
  scenario assuming an order the mesh does not promise. A Stop hook enforces the review; the reviewer reuses your
  sweep (`--reuse`) while the tree has not moved, so sweep last, not between edits. A scenario the sweep names
  `slow` is sitting out a production timer: shorten it through `LabLimits`, never a longer await. Chaos mode is
  the "Chaos mode" bullet in `.agents/context/testing.md`.
- **When creating or modifying a JVM unit test outside the mesh lab** — anything under `app/src/test/` but
  `mesh/lab/`, or `wear/src/test/` (a test class, a rig, a fake): READ the "Unit tests that hold on a slow runner"
  section of `.agents/context/testing.md` while you write it, then ADOPT (Claude Code: spawn) the `unit-test-reviewer`
  persona in `.agents/personas/unit-test-reviewer.md` before calling the change done — every unit-test flake so far
  passed review and local runs, and CI's one-vCPU runner found it. It walks each test for the catalogue's flake shapes
  and for tests that cannot fail, and stress-runs the changed classes (`scripts/unit-stress.sh`). A Stop hook enforces
  the review.
- **When creating or modifying an instrumented test** — anything under `app/src/androidTest/`: READ the
  "Instrumented tests that hold on an emulator" section of `.agents/context/testing.md` beside the slow-runner one,
  then ADOPT the same `unit-test-reviewer` persona — the test runs in the app's own process, beside its startup, and
  a fresh, empty app comes up for every test (`BackupRoundTripTest` lost the Keystore alias to the app's own mint). It
  reads for the emulator's shapes too and names the managed-device run the change owes rather than running it. The
  same Stop hook enforces the review.
- **When creating or modifying anything under `mesh/bluetooth/` (its `meshtastic/` and `wear/` included),
  `mesh/link/FramedLink.kt`, `mesh/power/ElapsedWait.kt` or `mesh/power/PowerState.kt` (`PowerPolicy`):** ADOPT (Claude Code:
  spawn) the `bluetooth-reviewer` persona in `.agents/personas/bluetooth-reviewer.md` before calling the change
  done — every Bluetooth fix so far was code that passed its JVM tests and broke on a phone. A Stop hook enforces
  the review. A `delay` or `withTimeout*` under `mesh/bluetooth/` either goes through `elapsedWait` or carries a
  `// stretches: <why>` line above it (`BluetoothWaitClockTest`, ADR 2026-10.pj9w's amendment).
- **When creating or modifying anything under `mesh/wifiaware/`, `data/settings/NanAttachJournal.kt` or
  `NanInitiatorJournal.kt`:** ADOPT (Claude Code: spawn) the `wifi-aware-reviewer` persona in
  `.agents/personas/wifi-aware-reviewer.md` before calling the change done — the Aware framework and firmware wedge in
  ways no JVM fake does. A Stop hook enforces the review.
- **When creating or modifying anything under `mesh/lora/` or `mesh/bluetooth/meshtastic/`:** ADOPT (Claude Code:
  spawn) the `lora-reviewer` persona in `.agents/personas/lora-reviewer.md` before calling the change done — every
  LoRa fix so far was code that passed over the fake air and failed on boards in the field. A Stop hook enforces the
  review.
- **When creating or modifying anything under `mesh/crypto/` (outside `scope/`), `data/ratchet/`,
  `mesh/PendingGroupKeys.kt` or `mesh/IntroSync.kt` — or the verify / decrypt / reset / seed / roster paths of
  `InboundPipeline` and `MeshManager`, which the hook cannot see:** ADOPT (Claude Code: spawn) the `crypto-reviewer`
  persona in `.agents/personas/crypto-reviewer.md` before calling the change done — every ratchet fix so far was code
  that passed single-node tests and stranded a pair. A Stop hook enforces the review for the gated paths.
- **When creating or modifying `mesh/BlobExchange.kt`, `BlobStore`, `BlobHash`, `CarrierPullPolicy`, `PartialBlobs`,
  `IncomingFiles`, `ArrivalTicker`, `ForwardStore`, `ForwardSync`, `StoreDigest`, `DigestTracker`, `SeenSet`,
  `PendingInbound`, `KeyExchange`, `mesh/link/FileIntake` / `FeedMeter` / `LandingFiles`, `data/forward/` or
  `data/AttachmentIdentities.kt` — or the blob and custody wiring of `MeshManager` / `InboundPipeline`, which the hook
  cannot see:** ADOPT (Claude Code: spawn) the `blobs-reviewer` persona in `.agents/personas/blobs-reviewer.md` before
  calling the change done — every blob and custody fix so far was code that passed single-node tests and cost the
  mesh a copy per neighbour, a stranded picture or a digest that never converged. A Stop hook enforces the review for
  the gated paths.

## Adding a persona

- A new reviewer is a persona, its symlink in `.claude/agents/`, a line in `.claude/hooks/review-gate.conf` (its stage
  and the paths its Stop hook gate watches) and an entry in this file. A reviewer whose checks step changes needs its
  line in `scripts/review-checks.sh` too.
- A new owner is a persona and its reviewer, their symlinks in `.claude/agents/`, a `.agents/context/<subsystem>.md`
  both read (the subsystem's symbol → ADR routing), a review-gate line, an entry here and one routing line in
  `AGENTS.md`.
- A contributor's GitHub pull request gets the same reviewers in CI when a maintainer applies the `review-gates`
  label: `.github/workflows/review-gates.yml` runs their checks (`scripts/review-checks.sh`) with no secrets, then
  `review-gates-report.yml` runs them read-only under the default branch's personas and posts one comment.
