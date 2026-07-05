# Knit — Architecture Review

**Date:** 2026-07-04
**Status updates:** findings **#1** (`3df428a`), **#2** (`7b2c6ba`), and **#3** (`e18b1f4`) have been
fixed since the review was written; they are marked ✅ below.
**Scope:** Full codebase (~25k LOC main across `mesh/`, `data/`, `ui/`, `moderation/`, `identity/`,
`notifications/`; ~6.3k LOC / 50 JVM test files; ~1.8k lines of design docs). Reviewed against the
intended design in `AGENTS.md`, `docs/ARCHITECTURE.md`, `docs/WIRE_COMPAT.md`,
`docs/NAN_CONCURRENCY_REAUDIT.md`, and `docs/DIGEST_PULL_REATTACH.md`.

This review is **evaluative** and complements the existing `docs/ARCHITECTURE.md` (which is descriptive).
It grades the design, calls out genuine defects, and distinguishes them from the author's many
deliberately-documented tradeoffs. Findings were produced by eight parallel subsystem audits and the
load-bearing ones were re-verified against source by hand.

---

## 1. Verdict

Knit is a **genuinely strong, unusually well-engineered codebase** — well above the norm for an app of
this ambition. The hardest thing here (an offline, dual-radio, delay-tolerant, end-to-end-encrypted mesh
on top of Android's notoriously constrained Wi-Fi Aware stack) is done with real sophistication: a
frozen, forward-compatible wire format; a clean transport seam that composes two radios; disciplined
coroutine hygiene; convergent store-and-forward custody; and a pure-logic testing strategy that covers
the algorithmic core well. The documentation is exceptional — field-evidence-dated, honest about
tradeoffs, and rich enough that a new engineer could onboard from it.

The weaknesses are concentrated and mostly **not** in the clever parts. They cluster in five areas:

1. **A few genuine correctness defects** that contradict the design's own stated invariants — most
   notably a non-convergent custody TTL and a moderation failure path that can throw out of the inbound
   handler.
2. **Structural gravity** — `MeshManager` (1838 lines) and `WifiAwareTransport` (2233 lines) are
   god-objects where correctness rides on prose-enforced ordering contracts.
3. **A cryptographic-identity foundation that is under-sized** — the 8-char nodeId gives ~41 bits, which
   is brute-forceable and undercuts the self-certifying-identity claim the whole trust model rests on.
4. **A test blind spot at the integration seam** — the security-critical `MeshManager` inbound pipeline
   and every Room DAO query are unverified by any executing test.
5. **An un-productionized release/build posture** — R8 fully disabled, demo code shipping, no signing
   config, thin CI gating.

None of these is fatal; all are fixable. The rest of this document details them, prioritized.

---

## 2. Prioritized findings

Severity reflects impact × likelihood **for the app's own stated goals** (a privacy-oriented, offline,
viral-distribution messenger). "Documented tradeoff" items from `AGENTS.md`/§18 are *not* re-listed as
defects; only where the code diverges from its own docs, or a consequence is unstated, do they appear.

| # | Severity | Area | Finding | Fix sketch |
|---|----------|------|---------|-----------|
| 1 | **High** | Correctness | **✅ Fixed** (`3df428a`) — Custody **TTL is non-convergent**: `expiresAt = localReceiveTime + ttl` (`ForwardRepository.kt:73`), not `sentAt + ttl`. Late joiners expire a frame hours after originators; a still-live peer re-serves a swept frame via the id-diff and it re-stores with a **fresh full TTL** (no tombstone for TTL-swept ids). Broadcast/group frames effectively never die mesh-wide while nodes keep meeting — contradicting the stated 6h/24h retention *and* the repo's own "digest-folded state must be bounded identically on every node" rule. | Use frame-global `expiresAt = sentAt + ttl`, or tombstone swept ids like `acked`. |
| 2 | **High** | Security | **✅ Fixed** (`7b2c6ba`) — **nodeId is ~41 bits** (`NodeId.kt`: 8 chars × 36-symbol alphabet = 36⁸ ≈ 2⁴¹·⁴). The self-certifying-identity model (`verifyInbound`, `canCarry`, TOFU pin) rests on "a colliding bundle is computationally infeasible" — false at 41 bits. Targeted second-preimage ≈ days on a modest GPU/CPU farm; birthday collision ≈ 2²¹ (trivial). | Widen nodeId to ≥128 bits of the digest (coordinated wire break — the repo already has a wire-break process). |
| 3 | **High** | Correctness / robustness | **✅ Fixed** (`e18b1f4`) — Moderation model-load can **throw out of the inbound handler**: `MlTextModerator.loadEngine()` catches only `IOException`/`IllegalStateException` (`:96,98`), but `Interpreter(model)` throws `IllegalArgumentException` on a bad flatbuffer and `SentencePieceTokenizer.fromJson` throws serialization errors; `classify()` wraps only `infer()`, not the load. A corrupt/LFS-pointer model asset propagates through `dispatchByType` (unwrapped) out of `onDeliver`, violating the load-bearing "never throw → keep relaying" contract, and crashes the send path (`ChatViewModel.send` try/**finally**). | `catch (e: Exception)` around load; wrap load in the `runCatching`. |
| 4 | **High** | Testing | The **security gate is untested**: `MeshManager` (1838 LOC) is never instantiated by any test; `FrameSignatureTest` only *mirrors* `sign`/`verifyInbound`. The real `verifyInbound → onDeliver → decryptAndDeliver → canCarry` pipeline and its no-throw contract can drift undetected. | Add a `FakeLoopTransport` + fake-repo integration test driving `verifyInbound`→`onDeliver` end-to-end (it's constructor-injectable). |
| 5 | **High** | Testing | **Zero executing DAO/migration tests.** All eviction/orphan/GC SQL is verified only by a hand-mirrored `FakeForwardDao`; a comment (`ForwardRepositoryTest.kt:60`) claims "the instrumented DAO test" — **which does not exist**. `androidx-room-testing` is on the classpath but unused. | Robolectric + in-memory Room to execute the real queries; delete the stale claim. |
| 6 | **High** | Release | **R8 fully disabled** (`build.gradle.kts:40-43`): demo/fake classes ship in the release APK, ~30 MB tflite + unshrunk Compose bloat the *shareable* APK, and a privacy-marketed app ships zero obfuscation. No `signingConfig`; `versionCode = 1`. | Decide the R8 story now (enable + author keep rules, or record why-not); force `SEED_DEMO=false` in the release buildType; add a signing config. |
| 7 | Med-High | Security | **Checked-in APK-share signing key** (`res/raw/knit_share_key.pk8`+`.der`) is public. `ApkMerger` re-signs offline-shared universal APKs with it, so anyone can sign a trojaned "Knit" that Android accepts as a **legitimate in-place update** for share-installed users (Play installs are unaffected). Undocumented threat. | Generate a per-install key at first share (Keystore), or document the tradeoff explicitly. |
| 8 | Med | Structure | `MeshManager` **god-object**: owns the transport, router, 5 DTN services, 8 repos, crypto, moderation; correctness of the whole stack rides on prose-enforced ordering (custody-before-relay, replay-runs-last, no-throw-out-of-onDeliver). | Extract an `InboundPipeline` / delivery collaborator; split profile, group-reconcile, and attachment-screen concerns. |
| 9 | Med | Security | **KeyExchange bookkeeping is unbounded** and keyed by *unauthenticated* senderIds: `missing`/`lastAskedAt`/`wanters` have no cap/TTL (contrast the deliberately-capped `PendingInbound`). A peer flooding many forged senderIds triggers a signed-keyreq storm (with hop-by-hop recursion) and unbounded map growth; a giant batched keyreq can exceed `MAX_PAYLOAD_BYTES` and crash the writer coroutine. | Cap/evict `missing`; chunk keyreq batches; add a `CoroutineExceptionHandler` to the mesh scope. |
| 10 | Med | Reliability | **Data-plane reliability rests on heuristic watchdogs.** The NAN single-NDI constraint has driven a long P0–P6 wedge-fighting campaign (two-tier watchdog, ghost-proof recycle, streak gates). It's impressively managed and documented, but self-heal loops around an opaque firmware resource are inherently fragile, and BLE has an analogous class of **sticky advertise/accept failures with no self-heal and `health` stuck `Healthy`**. | Add a health signal + re-advertise path to BLE `heal()`; continue extracting NAN decision logic into testable policies (below). |
| 11 | Med | Security / privacy | **Metadata & tracking exposure beyond what's documented**: the full group roster + name + `createdBy` travel cleartext on every group frame (passive social-graph reconstruction), and the stable nodeId is broadcast in the clear over NAN SSI + BLE service data (a keypair-lifetime OTA device-tracking identifier). `deviceTag` further defeats nodeId rotation as a privacy measure. | Surface these as explicit privacy notes; consider rotating/short-lived advert identifiers. |
| 12 | Med | Correctness | **Non-atomic key-file writes**: `DatabaseKey`/`KeystoreSecret` `writeBytes` (no temp+rename). A crash mid-write wipes the whole DB or **mints a new identity/nodeId** (breaks every peer's pin). Written once, high blast radius. | temp-file + `rename`. |
| 13 | Med | Correctness | **Read-then-write races** without transactions: `ReactionRepository.apply`, `ForwardRepository.store` (count→evict→digest), `BlobRepository.deleteIfUnreferenced`, `GroupRepository.leave/delete`. Safe only under an *implicit, undocumented* single-writer convention. | `@Transaction` the multi-step mutations; document the single-writer assumption. |
| 14 | Med | Security (defense-in-depth) | **Pin overwrite keeps `verified` across a key change** (`handleProfile`, `MeshManager.kt:1563`): a profile whose key derives to the senderId overwrites the pinned key and preserves the verified badge, with no `pubKey != pinned → verified=false` reset. Reachable given #2; a defense-in-depth miss regardless. | Refuse or un-verify on any pinned-key change. |
| 15 | Med | Structure | Untestable-by-construction UI: every ViewModel depends on the **concrete** `MeshManager`; **zero VM tests exist**. | A narrow read-facade interface over the UI's `MeshManager` surface (`neighbors`, `transportHealth`, `heal()`, sends). |
| 16 | Low-Med | Structure | `ChatScreen.kt` (1896 lines) and `BlobRepository` (moderation hub in a data class, 8 ctor deps) warrant mechanical splits. | Split `ChatScreen` along its 4 already-private-composable seams; extract an `ImageScreeningService`. |
| 17 | Low-Med | CI / process | CI gates only compile + unit tests. **No ktlint, no `./gradlew lint`, no `assembleRelease`, no instrumented tests**; detekt + trivy are `allow_failure`. Local (stop-hook) and CI enforcement have drifted. | Add ktlint + lint + a release-build job; make the supply-chain scan gating. |
| 18 | Low | Docs | Drift: `AGENTS.md` still describes pre-P1 "single-slot" NAN admission though P1–P6 concurrent-serve shipped; `docs/CONTENT_MODERATION.md` omits the `ScopedTextModerator` DM/room split; NSFW threshold 0.9 in code vs 0.7 in docs; several renamed-symbol comments. | A doc-sync pass. |

---

## 3. Strengths (what to preserve)

These are genuinely excellent and worth protecting through any refactor:

- **The layered CBOR wire protocol (`mesh/protocol/Wire.kt`)** is the standout. A frozen `WireEnvelope`
  whose `signed`+`sig` pass through relays **byte-for-byte**, a string `type` discriminator so unknown
  future types decode-and-relay instead of throwing, and a **single Ed25519 signature** over the signed
  bytes authenticating every frame type. This is textbook forward-compatible protocol design, and the
  "never re-encode on relay" discipline (which killed the classic "old relay breaks the signature" bug)
  is enforced consistently. `WIRE_COMPAT.md` is a model for how to document a wire format.

- **The transport seam (`MeshTransport` + `CompositeMeshTransport`)** cleanly runs two radios at once
  behind one interface, with well-chosen default methods (`hasFastPlane`, `suppressDataPath`,
  `onForeignReachable`, `fastFanout`) so each plane opts into only what it supports. Neighbor collapse
  by nodeId, health precedence, and the 0/1-child degenerate handling are all correct. The radio-import
  containment rule (`android.net.wifi.aware.*` only in `mesh/wifiaware/`, `android.bluetooth.*` only in
  `mesh/bluetooth/`) holds with **zero real violations** (the one grep hit is a doc comment).

- **`MeshRouter`** — jittered, overhear-suppressed flooding with split-horizon across every source,
  pure and injectable-jitter for virtual-time testing. Clean and correct.

- **`verifyInbound`** is a well-reasoned authentication gate: byte-exact verify, nodeId-derives-to-key
  check, no-throw `runCatching` contract, and a self-frame short-circuit to avoid custody echo noise.

- **Convergent custody quota** — evicting oldest-by-`(sentAt, id)` on *every* origin (including
  `ORIGIN_SELF`) so all nodes keep the identical newest-N set is a subtle, correct fix to a real field
  bug, and it's documented with the field evidence.

- **Store-and-forward + anti-entropy design** — `StoreDigest` (order-independent, O(1)-incremental,
  restart-stable XOR fingerprint), `DigestTracker`'s identical-digest skip, and the hub-and-spoke DTN
  services (`ForwardSync`, `AckSync`, `KeyExchange`, `PendingInbound`) are cleanly separated with no
  cycles; every cross-service edge is lambda-mediated through `MeshManager`.

- **Coroutine & crypto hygiene** — session-scoped `SupervisorJob` cancelled on `stop()`; **no
  `runBlocking`, no `GlobalScope`, zero `TODO`/`FIXME`** in main; correct AEAD (fresh per-message key +
  IV, header bound as AAD into both the body and each HPKE wrap, deterministic recipient ordering);
  keys wrapped under StrongBox-preferred AndroidKeyStore and kept *outside* the destructively-migrated
  DB; Coil disk cache disabled so decrypted bytes never touch disk.

- **The pure-policy extraction pattern** — `NanConnectPolicy`, `NanServePolicy`, `ScanDemandPolicy`,
  `PromotionPolicy`, `ConnectBackoffPolicy`, `PowerPolicy`, `BlePresenceTracker`, `Conversations`,
  `DigestTracker`, `ReviewPromptPolicy` — pulls the hard decisions out of Android-bound classes into
  JVM-tested units. This is the right strategy and pays for itself; the recommendation below is simply
  to apply it *further*.

- **Notifications, accessibility, and Compose UDF** are all above-average: MessagingStyle with
  conversation shortcuts/LocusId, correct summary-child cancel handling, `clearAndSetSemantics` row
  collapsing with custom actions, consistent state-hoisting, and ~30 `@Preview`s.

- **The bleeding-edge toolchain** (AGP 9.2.1 / Kotlin-2.4.0-override / Koin / KSP2) is a deliberate,
  load-bearing set of choices, each documented with its *why* in the version catalog and `AGENTS.md`.
  Dependency locking across all configurations and backup-exclusion of the key/DB files are correctly
  handled.

---

## 4. Detailed findings by theme

### 4.1 Correctness

**[#1, High] Non-convergent custody TTL.** *✅ Fixed in `3df428a`: expiry is now keyed off the
frame-global `sentAt`, not the local receive time.* Verified in `ForwardRepository.store`: `expiresAt` is
computed from the local `now` (line 73), while the frame-global `sentAt` (line 67) is stored but used
only for eviction ordering. Because `ForwardSync.onSeen` has no tombstone for TTL-swept ids (its `acked`
set covers only receipt-purged DMs), a frame that node A sweeps at its local expiry is re-offered by any
still-holding node B via the data-path id-diff and re-stored on A with a brand-new full TTL. The net
effect: group and broadcast frames (whose "only bound is TTL") do not actually reach end-of-life
mesh-wide while any pair keeps meeting — the real bound becomes the quota (which *is* frame-global). This
is self-limiting (syncs converge; counts stay bounded), but it falsifies the documented retention and
violates the very convergence rule the custody design is otherwise careful to honor. This is the single
most consequential correctness gap because it's subtle, untested (no staggered-TTL cross-node test
exists), and contradicts a stated invariant.

**[#3, High] Moderation can break the no-throw inbound contract.** *✅ Fixed in `e18b1f4`: the load
catch is broadened to `Exception` and the load call is wrapped in `runCatching` (absorbing Errors too),
in both `MlTextModerator` and `NsfwImageModerator`.* Confirmed by reading
`MlTextModerator.classify`/`loadEngine`: the load is not inside the `runCatching`, and its `catch`
clauses miss the exception types a corrupt model/tokenizer actually throw. This only triggers on a
broken asset (e.g. a git-LFS pointer checked out as the model, a truncated build) — but when it does, it
both crashes the composer's send and throws out of `onDeliver`, stopping that device from relaying the
frame. The fix is one line; the value is upholding an invariant the rest of the code treats as sacred.

**[#12, Med] Non-atomic key-file persistence** and **[#13, Med] untransacted read-then-write**
sequences are both "works because nothing races it today" — the mesh serializes inbound handling and the
files are written rarely. But the identity-key write happens exactly once per install and a torn write
mints a new nodeId (breaking every pin), so temp-file+rename is cheap insurance against a high-blast
failure.

### 4.2 Security & privacy

**[#2, High] Under-sized nodeId** *(✅ fixed in `7b2c6ba`: nodeId widened to 128 bits of SHA-256,
base32-encoded, as a coordinated wire break — SERVICE_NAME/SERVICE_UUID/DB/Protocol.VERSION bumped in
lockstep)* is the foundational issue: two independent audits and a hand-check
agree the id space is ~2⁴¹. Everything downstream — the "race-proof TOFU" claim, `canCarry`, the pin —
inherits this ceiling. The mitigations (safety-number/QR verification) are opt-in and, per **[#14]**,
can be silently overwritten for an already-verified contact. Widening the id is the highest-leverage
security change; it is a coordinated wire break, but the project already treats those as a normal,
documented operation.

**[#7, Med-High] The public APK-share key** is a real, matching RSA keypair in the repo used to re-sign
offline-shared universal APKs. Because Android treats the signing cert as package identity, any attacker
can sign malware that share-installed users' devices accept as a legitimate update, inheriting Knit's
permissions and data dir. Play-installed users are unaffected (Google signs those), but offline P2P
spread is precisely this app's distribution story. At minimum this needs an explicit threat-model note;
better, a per-install key generated at first share.

**[#9, Med] keyreq amplification / unbounded state** and **[#11, Med] metadata & OTA tracking** are the
remaining security items. The keyreq path is the one place an unauthenticated senderId drives unbounded
work and map growth (with a plausible writer-crash chain via an oversized batched keyreq); `PendingInbound`
shows the author already knows how to bound exactly this — the same treatment should extend to
`KeyExchange` and `BlobExchange`. On privacy, the cleartext group roster and the persistent
over-the-air nodeId are legitimate (and largely documented) tradeoffs, but their *consequences* —
passive social-graph reconstruction and lifetime device tracking — deserve to be stated plainly for a
privacy-positioned app.

### 4.3 Structural gravity

**[#8, Med] `MeshManager` (1838 LOC, 15 ctor deps)** and the **2233-line `WifiAwareTransport`** are the
two mass centers. Neither is "bad code" — both are heavily and honestly documented, and the size is a
*consequence* of genuinely hard problems — but both concentrate risk:

- In `MeshManager`, the correctness of the DTN stack lives in ordering contracts expressed as comments
  ("must run last", "runs before the router schedules the relay"). Extracting an `InboundPipeline`
  object that makes those orderings *mechanical* (and testable — see #4) is the highest-value refactor
  in the codebase.
- In `WifiAwareTransport`, ~40% is Android-free decision logic. A family of eight near-duplicate
  "sync-owed" predicates, the two-tier watchdog's episode clock, and the cue/SSI codec are all pure and
  belong in policy objects beside `NanConnectPolicy`/`NanServePolicy`. There is also a **half-landed
  round-robin fairness change** (`lastInitiateAttemptAt` is written and cleared but never read;
  `driveSync` still selects `firstOrNull` in HashMap order) that the NAN re-audit doc claims as
  "implemented" — a doc/code divergence plus a real initiate-starvation behavior.

`ChatScreen.kt` (1896 lines) is a milder version of the same: every concern is already a private
composable, so the split into `MessageBubble.kt` / `MessageInput.kt` / `ReactionPicker.kt` is purely
mechanical and improves reviewability with no logic risk.

`BlobRepository` doubling as the image-moderation hub (invoking the classifier and policy-logging inside
a data-layer class, with 8 positional `get()` dependencies) is scope creep worth reversing with an
`ImageScreeningService`.

### 4.4 Reliability

**[#10, Med]** The NAN single-NDI wedge saga is the clearest example of *inherent platform friction*
being managed well but not eliminated. The git history is a sequence of P0–P6 fixes — ghost-proof
responder recycle, two-tier wedge watchdog, streak-gated handle drop, ICM keepalive — each a real
firmware-observed failure. The engineering is impressive and the docs are honest. The residual risk is
structural: self-heal loops around an opaque, single-slot hardware resource are fragile by nature, and
the more of the decision logic that stays inline and untested (§4.3), the harder each new wedge is to
reason about. The Bluetooth plane has an *analogous* unaddressed class — sticky `startAdvertising` /
`listenUsingInsecureL2capChannel` / `accept()` failures leave the node silently un-discoverable with
`health` still reporting `Healthy` and `heal()` doing only a rescan (never a re-advertise), contradicting
the documented `HEAL` behavior. A BLE health signal + re-advertise path closes the most likely
silent-death mode.

Two more BLE items from the audit are worth fixing: a **non-idempotent `onLinkDown`** that double-bumps
the connect backoff on a single drop (and can pollute backoff state *after* `stop()` because reader
coroutines outlive it on the app scope), and a **floor-scan vs. reachable-linger** interaction where a
linked peer's presence can expire between floored scans and let a weaker new candidate evict a healthy
link.

### 4.5 Testing

The pure-logic test suite is a real strength: behavioral (not change-detector) tests, injected clocks,
virtual time, multi-node mini-meshes for the DTN services, and golden-value tests for crypto/identity.
The gap is precisely at the **integration seam and the persistence layer**:

- **[#4]** `MeshManager`'s inbound pipeline — the security gate and the no-throw contract — is only
  *mirrored* by a test, never executed. It is constructor-injectable; a `FakeLoopTransport` + fake repos
  test is the missing keystone.
- **[#5]** No DAO query is ever executed by a test; eviction/orphan/GC SQL is validated against a
  hand-written fake that "mirrors the SQL," and a comment even references an instrumented test that
  doesn't exist. This is the exact category of test that stays green while the real query is wrong.
  `androidx-room-testing` is already available.
  **Addressed (2026-07-05):** `app/src/test/java/app/getknit/knit/data/` now executes the real DAO SQL under
  Robolectric + in-memory Room (`ForwardDaoTest`, `BlobDaoTest`, `ReactionDaoTest`, `MessageDaoTest` — the
  orphan/anti-join queries the fakes skipped are now run), `exportSchema` is on with a checked-in baseline
  schema, a `MigrationTestHelper` harness (`KnitDatabaseMigrationTest`) is ready for the first real migration,
  and the stale "instrumented DAO test" comment is corrected. See AGENTS.md "JVM Room/DAO + migration tests".
- **[#15]** No ViewModel has a test, because each depends on the concrete `MeshManager`.

None of these needs hardware — Robolectric + in-memory Room + fakes cover all three on the JVM.

### 4.6 Build, release & CI

**[#6, High]** disabling R8 for release is the headline build issue: it ships the demo/fake transport
and seeder as dead bytecode, forgoes all shrinking on an app whose *own* offline-share feature makes APK
size matter, and gives a privacy-marketed messenger zero obfuscation. Coupled with **[#17]** — CI that
never compiles a release build, runs ktlint, runs `lint`, or exercises instrumented tests, and treats
detekt/trivy as advisory — the release path is effectively unverified. Additionally `SEED_DEMO` lives in
`defaultConfig`, so it isn't forced off for release, and there is no `signingConfig` and a hardcoded
`versionCode = 1`. These are all pre-first-release productionization items, cheap to fix now and painful
later (turning R8 on against the empty keep-rules file, with kotlinx-serialization/Tink/SQLCipher/LiteRT
reflection surfaces, is a minefield best defused before the graph grows).

### 4.7 Moderation (subsystem note)

Beyond the contract bug (#3), the moderation pipeline is solid in design (scoped lexical+ML for the
public room, ML-only for private, mesh-neutral so flagged content still relays, receiver-side hiding
that never drops content) but has several loose ends: it sits on the relay-critical path (an ALBERT pass
per chat frame behind a mutex during a backfill burst), the `noCompress "tflite"` mmap rationale is
defeated by `readBytes()` loading ~32 MB resident, the NSFW model's license is explicitly unvetted (a
release blocker), and the code/doc thresholds disagree (0.9 vs 0.7). The English-only lexical filter is
a structural limit, not a bug, but worth stating.

---

## 5. Subsystem scorecard

| Subsystem | Grade | One-line assessment |
|---|---|---|
| Wire protocol (`mesh/protocol`) | A | Frozen, layered, forward-compatible; a reference design. |
| Routing (`MeshRouter`, `SeenSet`) | A | Pure, correct, well-tested jittered flood. |
| Transport seam (`MeshTransport`, `Composite`) | A | Clean two-radio composition with sane defaults. |
| DTN custody / anti-entropy | A− | Elegant and well-tested; docked for the non-convergent TTL (#1). |
| E2E crypto (`mesh/crypto`) | A− | AEAD/HPKE done right; identity width (#2) is the caveat. |
| Bluetooth LE plane | B+ | Excellent policy extraction; sticky-failure self-heal gaps. |
| Wi-Fi Aware plane | B | Heroic, well-documented — but a god-file with un-extracted, untested decision logic. |
| Data / persistence | B+ | Coherent at-rest story; untransacted races, no DAO tests, atomicity gaps. |
| Identity | B | Self-certifying model is elegant but under-sized (#2). |
| Moderation | B | Good design; the load-failure path (#3) and asset/license loose ends. |
| UI / Compose | A− | Textbook UDF, strong a11y; ChatScreen size + concrete-VM coupling. |
| Notifications | A | Genuinely well-engineered MessagingStyle integration. |
| `MeshManager` orchestration | B− | The correctness core, but a 1838-line god-object, untested at the seam. |
| Build / CI / release | C+ | Great toolchain rationale; unproductionized release + thin CI. |
| Documentation | A | Field-evidence-dated, honest, thorough; minor drift from shipped work. |

---

## 6. Recommended sequencing

Ordered by value-to-effort, safe to tackle incrementally:

**Now (cheap, high-value, low-risk):**
1. ✅ Fix the moderation load-failure `catch` (#3) — one line, restores a load-bearing invariant.
   *Done in `e18b1f4`.*
2. ✅ Make custody TTL frame-global or tombstone swept ids (#1) — restores a stated convergence
   guarantee. *Done in `3df428a`.*
3. Cap `KeyExchange`/`BlobExchange` bookkeeping and chunk keyreq batches; add a mesh-scope
   `CoroutineExceptionHandler` (#9).
4. temp-file+rename the key/DB-key writes (#12); reset `verified` on any pinned-key change (#14).
5. Force `SEED_DEMO=false` in the release buildType; add a `signingConfig` (#6 partial).
6. A doc-sync pass (#18) and delete the stale "instrumented DAO test" claim (#5).

**Next (moderate effort, closes the biggest blind spots):**
7. Add the `MeshManager` inbound-pipeline integration test and Robolectric DAO tests (#4, #5).
8. Add ktlint + lint + `assembleRelease` CI jobs; make trivy gating (#17).
9. Add a BLE health signal + re-advertise `heal()` path; fix the non-idempotent backoff bump (#10).

**Then (larger refactors, do behind the new tests):**
10. Extract an `InboundPipeline` from `MeshManager` and a UI read-facade (#8, #15).
11. Pull the NAN "sync-owed" predicate family, watchdog clock, and cue codec into tested policies; land
    or remove the round-robin fairness change (#8).
12. ✅ Widen the nodeId to ≥128 bits as a coordinated wire break (#2) — schedule alongside the next
    planned wire bump. *Done in `7b2c6ba`.*
13. Decide and execute the R8 story with real keep rules (#6); split `ChatScreen` and extract
    `ImageScreeningService` (#16).

---

## 7. Closing note

The instinct to protect here is the one already on display: **push hard decisions into small, pure,
testable units, and write down why.** The codebase's best parts (the wire, the router, the policies, the
custody convergence rule) all follow it, and its riskiest parts (the two god-objects, the untested
inbound seam) are exactly where it hasn't been applied *yet*. Most of the findings above are that same
principle, extended one layer further — into the inbound pipeline, the DAO queries, and the NAN decision
logic — plus a short list of genuine defects that contradict invariants the design already commits to.
This is a strong foundation; the work remaining is hardening, not redesign.
