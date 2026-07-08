# Knit — Architecture Review

**Date:** 2026-07-04
**Status updates (last revised 2026-07-05):** the following have been fixed since the review was written
and are marked ✅ below — **#1** (`3df428a`, completed by work item #8), **#2** (`7b2c6ba`), **#3**
(`e18b1f4`; the no-throw contract was later made mechanical in `265c79a`), **#4** (`8c291bb`), **#5**
(`c9cf64d`), **#7** (`53646bb`), **#9** (`db67f72`), **#13** (`db.withTransaction` + a `ForwardRepository`
mutex + transactional `reconcileGroup`), **#14** (immutable key pin — a differing key is refused),
and **#15** (`8a8f561` + `8c291bb`). **#8** was
**partially** fixed: the `InboundPipeline` extraction from `MeshManager` landed (`874e2ec`).
**Status updates (2026-07-07):** **#8** is now fully addressed — the `WifiAwareTransport` decision logic is
extracted into three pure, JVM-tested policies: `NanSyncPolicy` + `PeerFacts` (the eight "sync-owed"
predicates), `NanWatchdogPolicy` (the two-tier episode clock), and `NanCueCodec` (the cue/SSI codec). The
"half-landed round-robin" turned out **already landed** — `driveSync` selects via an LRU
`minWithOrNull(compareBy(…))`, not `firstOrNull`, and `lastInitiateAttemptAt` *is* read; the review's claim
was stale. **#16** is partially addressed: `ImageScreeningService` is extracted out of `BlobRepository`
(now 8 ctor deps, classifier-free); the `ChatScreen.kt` split (reviewability-only, no test gain) remains
deliberately deferred.
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
   handler. *(✅ Both since fixed — #1, #3.)*
2. **Structural gravity** — `MeshManager` (1838 lines) and `WifiAwareTransport` (2388 lines) are
   god-objects where correctness rides on prose-enforced ordering contracts. *(✅ Both addressed since the
   review: `MeshManager`'s inbound half is now an injectable, tested `InboundPipeline` (953 lines — #8/#4),
   and `WifiAwareTransport`'s decision logic is extracted into three pure, JVM-tested policies —
   `NanSyncPolicy`/`NanWatchdogPolicy`/`NanCueCodec`, 2026-07-07.)*
3. **A cryptographic-identity foundation that is under-sized** — the 8-char nodeId gives ~41 bits, which
   is brute-forceable and undercuts the self-certifying-identity claim the whole trust model rests on.
   *(✅ Since fixed: nodeId widened to 128 bits — #2.)*
4. **A test blind spot at the integration seam** — the security-critical `MeshManager` inbound pipeline
   and every Room DAO query are unverified by any executing test. *(✅ Since fixed — #4, #5.)*
5. **An un-productionized release/build posture** — R8 fully disabled, demo code shipping, no signing
   config, thin CI gating.

None of these is fatal; all are fixable. The rest of this document details them, prioritized.

---

## 2. Prioritized findings

Severity reflects impact × likelihood **for the app's own stated goals** (a privacy-oriented, offline,
viral-distribution messenger). "Documented tradeoff" items from `AGENTS.md`/§18 are *not* re-listed as
defects; only where the code diverges from its own docs, or a consequence is unstated, do they appear.

| # | Severity | Area | Finding                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              | Fix sketch |
|---|----------|------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-----------|
| 1 | **High** | Correctness | **✅ Fixed** (`3df428a`, completed by work item #8) — Custody **TTL is non-convergent**: `expiresAt = localReceiveTime + ttl` (`ForwardRepository.kt:73`), not `sentAt + ttl`. Late joiners expire a frame hours after originators; a still-live peer re-serves a swept frame via the id-diff and it re-stores with a **fresh full TTL** (no tombstone for TTL-swept ids). Broadcast/group frames effectively never die mesh-wide while nodes keep meeting — contradicting the stated 6h/24h retention *and* the repo's own "digest-folded state must be bounded identically on every node" rule.                                                                                                                                                                                                                                     | Use frame-global `expiresAt = sentAt + ttl`, or tombstone swept ids like `acked`. *(The tombstone became unnecessary: `sentAt + ttl` is itself a frame-global tombstone clock, and work item #8 added the dead-on-arrival store guard + live-only digest/quotas that close the residual sweep-phase divergence — no tombstone table, which would have been new per-node state, the very trap.)* |
| 2 | **High** | Security | **✅ Fixed** (`7b2c6ba`) — **nodeId is ~41 bits** (`NodeId.kt`: 8 chars × 36-symbol alphabet = 36⁸ ≈ 2⁴¹·⁴). The self-certifying-identity model (`verifyInbound`, `canCarry`, TOFU pin) rests on "a colliding bundle is computationally infeasible" — false at 41 bits. Targeted second-preimage ≈ days on a modest GPU/CPU farm; birthday collision ≈ 2²¹ (trivial).                                                                                                                                                                                                                                                                                                                                                                                                                                                                 | Widen nodeId to ≥128 bits of the digest (coordinated wire break — the repo already has a wire-break process). |
| 3 | **High** | Correctness / robustness | **✅ Fixed** (`e18b1f4`) — Moderation model-load can **throw out of the inbound handler**: `MlTextModerator.loadEngine()` catches only `IOException`/`IllegalStateException` (`:96,98`), but `Interpreter(model)` throws `IllegalArgumentException` on a bad flatbuffer and `SentencePieceTokenizer.fromJson` throws serialization errors; `classify()` wraps only `infer()`, not the load. A corrupt/LFS-pointer model asset propagates through `dispatchByType` (unwrapped) out of `onDeliver`, violating the load-bearing "never throw → keep relaying" contract, and crashes the send path (`ChatViewModel.send` try/**finally**).                                                                                                                                                                                                | `catch (e: Exception)` around load; wrap load in the `runCatching`. |
| 4 | **High** | Testing | **✅ Fixed** (`8c291bb`, unblocked by the `874e2ec` extraction) — the security gate is now *executed*, not mirrored: `InboundPipelineTest` drives the real `InboundPipeline.onDeliver` (verify → custody → dispatch → decrypt → deliver/ack) with real Tink keypairs, the real DTN services, a `FakeLoopTransport`, and mockk repo stand-ins, covering the gate and all three ordering contracts (custody-before-relay, replay-runs-last, no-throw-out-of-`onDeliver`). *(Was: `MeshManager` never instantiated by any test; `FrameSignatureTest` only mirrored `sign`/`verifyInbound`.)*                                                                                                                                                                                                                                             | ✅ `FakeLoopTransport` + fake-repo test drives `onDeliver` end-to-end. |
| 5 | **High** | Testing | **✅ Fixed** (`c9cf64d`) — `ForwardDaoTest`/`BlobDaoTest`/`ReactionDaoTest`/`MessageDaoTest` now execute the real eviction/orphan/GC SQL against in-memory Room under Robolectric; `exportSchema` is on with a checked-in baseline (`app/schemas/…/21.json`), a `MigrationTestHelper` harness (`KnitDatabaseMigrationTest`) is ready for the first real migration, and the stale "instrumented DAO test" comment is corrected.                                                                                                                                                                                                                                                                                                                                                                                                        | ✅ Robolectric + in-memory Room executing the real queries (see `.agents/context/testing.md`, "JVM Room/DAO + migration tests"). |
| 6 | **High** | Release | **R8 fully disabled** (`build.gradle.kts:40-43`): demo/fake classes ship in the release APK, ~30 MB tflite + unshrunk Compose bloat the *shareable* APK, and a privacy-marketed app ships zero obfuscation. No `signingConfig`; `versionCode = 1`.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   | Decide the R8 story now (enable + author keep rules, or record why-not); force `SEED_DEMO=false` in the release buildType; add a signing config. |
| 7 | Med-High | Security | **✅ Fixed** (`53646bb`) — the **checked-in APK-share signing key** (`res/raw/knit_share_key.pk8`+`.der`) was public, so anyone could sign a trojaned "Knit" that Android accepts as a **legitimate in-place update** for share-installed users. The key files are removed; `ApkMerger` now re-signs with a **per-install EC P-256 AndroidKeyStore key** (`ShareSigningKey`) generated on first share, so no signing identity is shared across installs and no attacker holds a key a victim's device would honor. Documented tradeoff: a share-installed Knit updates in place only from the device that produced its APK.                                                                                                                                                                                                           | ✅ Per-install Keystore key generated at first share (`ShareSigningKey`). |
| 8 | Med | Structure | **✅ Partially fixed** (`874e2ec`, prep in `96cbe09`) — the whole inbound half of `MeshManager` moved verbatim into a constructor-injectable `InboundPipeline` (onDeliver, every per-type handler, reconcileGroup + group-photo helpers, avatar/attachment screening), dropping `MeshManager` from **1838 → 953 LOC**; the no-throw ordering contract was then made *mechanical* (`265c79a` wraps the whole `dispatchByType`) and is now covered by `InboundPipelineTest` (#4). **✅ NAN half also done** (2026-07-07): the eight "sync-owed" predicates, the two-tier watchdog episode clock, and the cue/SSI codec are extracted into pure JVM-tested policies (`NanSyncPolicy` + `PeerFacts` / `NanWatchdogPolicy` / `NanCueCodec`); the decision logic is now testable off-Android and `WifiAwareTransport` is 2360 LOC. The "half-landed round-robin" was already landed (LRU `minWithOrNull`, not `firstOrNull`) — see §6 item 11.                                                                                                                                                                                       | ✅ `InboundPipeline` + the three NAN policies extracted. |
| 9 | Med | Security | **✅ Fixed** (`db67f72`) — `KeyExchange` (`missing`/`wanters`) and `BlobExchange` (`fetching`/`wanters`/serve-memo) are now capped with oldest-first eviction + TTL sweep, outbound keyreq batches are chunked under the link payload ceiling (`MAX_IDS_PER_REQ`) and inbound id lists capped against recursion (`MAX_REQUEST_IDS`); `FramedLink` now drops (rather than dies on) a codec-rejected record, and both mesh scopes carry a shared `meshExceptionHandler` backstop. JVM-tested (`KeyExchangeTest`/`BlobExchangeTest` eviction cases).                                                                                                                                                                                                                                                                                     | ✅ Capped/evicted + TTL-swept; keyreq chunked; mesh-scope `CoroutineExceptionHandler` added. |
| 10 | Med | Reliability | **Data-plane reliability rests on heuristic watchdogs.** The NAN single-NDI constraint has driven a long P0–P6 wedge-fighting campaign (two-tier watchdog, ghost-proof recycle, streak gates). It's impressively managed and documented, but self-heal loops around an opaque firmware resource are inherently fragile, and BLE has an analogous class of **sticky advertise/accept failures with no self-heal and `health` stuck `Healthy`**.                                                                                                                                                                                                                                                                                                                                                                                       | Add a health signal + re-advertise path to BLE `heal()`; continue extracting NAN decision logic into testable policies (below). |
| 11 | Med | Security / privacy | **Won't fix** **Metadata & tracking exposure beyond what's documented**: the full group roster + name + `createdBy` travel cleartext on every group frame (passive social-graph reconstruction), and the stable nodeId is broadcast in the clear over NAN SSI + BLE service data (a keypair-lifetime OTA device-tracking identifier). `deviceTag` further defeats nodeId rotation as a privacy measure.                                                                                                                                                                                                                                                                                                                                                                                                                              | Surface these as explicit privacy notes; consider rotating/short-lived advert identifiers. |
| 12 | Med | Correctness | **Fixed** — **Non-atomic key-file writes**: `DatabaseKey`/`KeystoreSecret` `writeBytes` (no temp+rename). A crash mid-write wipes the whole DB or **mints a new identity/nodeId** (breaks every peer's pin). Written once, high blast radius.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        | temp-file + `rename`. |
| 13 | Med | Correctness | **✅ Fixed** — **Read-then-write races** without transactions: `ReactionRepository.apply`, `ForwardRepository.store` (count→evict→digest), `BlobRepository.deleteIfUnreferenced`, `GroupRepository.leave/delete`. The "single-writer convention" was **false** — UI (`viewModelScope`), the notification receiver, and session-scope sweep/`watch` loops all mutate these repos off the inbound collector. Each multi-step mutation now runs in `db.withTransaction` (`BEGIN EXCLUSIVE` serializes the read-modify-write); `ForwardRepository` additionally holds a `Mutex` *outer* to the transaction so the in-memory `StoreDigest` stays in lockstep (a Room txn can't enroll it); and the inbound `reconcileGroup` find→upsert was made transactional too, so a UI `leave`/`delete` can't be resurrected by a racing group frame. | ✅ `db.withTransaction` + a `ForwardRepository` `Mutex` + transactional `reconcileGroup`; convention documented in `AGENTS.md`. |
| 14 | Med | Security (defense-in-depth) | **✅ Fixed** — was: pin overwrite kept `verified` across a key change (`handleProfile`, since moved to `InboundPipeline.kt`): a profile whose key derives to the senderId overwrote the pinned key while preserving the verified badge, with no reset. Now the pin is **immutable** — `handleProfile` refuses a profile whose key differs from the sender's already-pinned key (a change is only reachable via a nodeId hash collision), counts it `PIN_CHANGE_REFUSED`, and keeps the first-pinned key + its `verified` state, so a colliding key can't inherit a verified contact. (`verified` is UI-only; no mesh gate ever read it.) JVM-tested in `InboundPipelineTest`.                                                                                                                                                         | ✅ `handleProfile` refuses any pinned-key change (immutable pin); `verified` stays bound to it. |
| 15 | Med | Structure | **✅ Fixed** (`8a8f561`, test in `8c291bb`) — a narrow `MeshController` facade (the 6 read StateFlows + send/heal/restart/start/stop) now fronts `MeshManager`; all 6 ViewModels, `MeshService`, `KnitApp`, the notification receiver, and the debug bridge inject the interface, so a VM is testable against a fake — demonstrated by the first-ever VM test, `DiagnosticsViewModelTest` (a `FakeMeshController`).                                                                                                                                                                                                                                                                                                                                                                                                                   | ✅ `MeshController` read-facade + first VM test. |
| 16 | Low-Med | Structure | **✅ `BlobRepository` split done** (2026-07-07): the classifier + verdict-cache screening moved to a dedicated `ImageScreeningService` (`imageModerator` + `verdicts`, JVM-tested), so the data class no longer invokes moderation (9 → 8 ctor deps; it keeps `verdicts` only for the atomic GC verdict-row delete). **Remaining:** the mechanical `ChatScreen.kt` split (now ~2345 lines) — pure reviewability, no test gain — is **deliberately deferred**, with a ready seam map in the refactor plan.                                                                                                                                                                                                                                                                                                                                          | ✅ `ImageScreeningService` extracted; `ChatScreen` split deferred. |
| 17 | Low-Med | CI / process | CI gates only compile + unit tests. **No ktlint, no `./gradlew lint`, no `assembleRelease`, no instrumented tests**; detekt + trivy are `allow_failure`. Local (stop-hook) and CI enforcement have drifted.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                          | Add ktlint + lint + a release-build job; make the supply-chain scan gating. |
| 18 | Low | Docs | Drift: `AGENTS.md` still describes pre-P1 "single-slot" NAN admission though P1–P6 concurrent-serve shipped; `docs/CONTENT_MODERATION.md` omits the `ScopedTextModerator` DM/room split; NSFW threshold 0.9 in code vs 0.7 in docs; several renamed-symbol comments.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 | A doc-sync pass. |

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

- **The pure-policy extraction pattern** — `NanConnectPolicy`, `NanServePolicy`, `NanSyncPolicy`,
  `NanWatchdogPolicy`, `NanCueCodec`, `ScanDemandPolicy`, `PromotionPolicy`, `ConnectBackoffPolicy`,
  `PowerPolicy`, `BlePresenceTracker`, `Conversations`, `DigestTracker`, `ReviewPromptPolicy` — pulls the hard
  decisions out of Android-bound classes into JVM-tested units. This is the right strategy and pays for
  itself; the last three NAN policies (2026-07-07) applied it to the `WifiAwareTransport` decision logic
  the recommendation below called out.

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
in both `MlTextModerator` and `NsfwImageModerator`. The runtime sibling — a throwing `classify()` on the
relay path — was closed in `265c79a`, which wraps the whole `dispatchByType` so "never throw out of
`onDeliver`" is now mechanical rather than prose-enforced (regression-covered by `InboundPipelineTest`).*
Confirmed by reading
`MlTextModerator.classify`/`loadEngine`: the load is not inside the `runCatching`, and its `catch`
clauses miss the exception types a corrupt model/tokenizer actually throw. This only triggers on a
broken asset (e.g. a git-LFS pointer checked out as the model, a truncated build) — but when it does, it
both crashes the composer's send and throws out of `onDeliver`, stopping that device from relaying the
frame. The fix is one line; the value is upholding an invariant the rest of the code treats as sacred.

**[#12, Med] Non-atomic key-file persistence** remains — the identity-key write happens exactly once per
install and a torn write mints a new nodeId (breaking every pin), so temp-file+rename is cheap insurance
against a high-blast failure. **[#13, Med] untransacted read-then-write** is **✅ fixed**: the premise that
"the mesh serializes inbound handling" proved false (UI, notification, and sweep paths write these repos
concurrently off the inbound collector), so each multi-step mutation now runs in `db.withTransaction`, with a
`ForwardRepository` `Mutex` keeping the in-memory `StoreDigest` in lockstep and a transactional `reconcileGroup`
closing the leave-vs-reconcile group-resurrection race — see §2 #13 and the transactional-mutations rule in `.agents/rules/coding.md`.

### 4.2 Security & privacy

**[#2, High] Under-sized nodeId** *(✅ fixed in `7b2c6ba`: nodeId widened to 128 bits of SHA-256,
base32-encoded, as a coordinated wire break — SERVICE_NAME/SERVICE_UUID/DB/Protocol.VERSION bumped in
lockstep)* is the foundational issue: two independent audits and a hand-check
agree the id space is ~2⁴¹. Everything downstream — the "race-proof TOFU" claim, `canCarry`, the pin —
inherits this ceiling. The mitigations (safety-number/QR verification) are opt-in and, per **[#14]**,
can be silently overwritten for an already-verified contact. Widening the id is the highest-leverage
security change; it is a coordinated wire break, but the project already treats those as a normal,
documented operation.

**[#7, Med-High] The public APK-share key** was a real, matching RSA keypair in the repo used to re-sign
offline-shared universal APKs. Because Android treats the signing cert as package identity, any attacker
could sign malware that share-installed users' devices accept as a legitimate update, inheriting Knit's
permissions and data dir. Play-installed users are unaffected (Google signs those), but offline P2P
spread is precisely this app's distribution story. **Addressed (2026-07-05):** the checked-in key/cert
are removed and `ApkMerger.signApk` now re-signs with a **per-install EC P-256 key generated in
AndroidKeyStore on first share** (`ui/invite/ShareSigningKey`, StrongBox-preferred with TEE fallback;
apksig signs through the JCA `Signature` API so the non-extractable key never leaves the keystore, and
the keystore's auto-generated self-signed cert avoids needing a cert-builder dependency). Every install
now signs with its own device-private identity. The chosen tradeoff — that a share-installed Knit can be
updated in place only by the device (share-tree root) that produced its APK, since a differently-keyed
APK is rejected as a signature mismatch — is documented in `ShareSigningKey`'s KDoc; offline share is
primarily first-install distribution, which is unaffected.

**[#9, Med] keyreq amplification / unbounded state** *(✅ fixed in `db67f72`)* was the one place an
unauthenticated senderId drove unbounded work and map growth (with a plausible writer-crash chain via an
oversized batched keyreq). `PendingInbound` already showed how to bound exactly this, and that treatment
now extends to both `KeyExchange` and `BlobExchange`: `missing`/`wanters`/`fetching`/serve-memo are capped
(oldest-first) and TTL-swept, keyreq batches are chunked under the payload ceiling, inbound id lists are
capped against recursion, and a shared `meshExceptionHandler` backstops both mesh scopes.

**[#11, Med] metadata & OTA tracking** is the remaining security item. On privacy, the cleartext group
roster and the persistent over-the-air nodeId are legitimate (and largely documented) tradeoffs, but their
*consequences* — passive social-graph reconstruction and lifetime device tracking — deserve to be stated
plainly for a privacy-positioned app.

### 4.3 Structural gravity

**[#8, Med] `MeshManager`** (originally 1838 LOC, 15 ctor deps; **now 953** after the extraction below)
and the **`WifiAwareTransport`** (2388 → 2360 LOC) were the two mass centers. Neither is "bad code" — both
are heavily and honestly documented, and the size is a *consequence* of genuinely hard problems — but both
concentrated risk. **Both are now addressed:**

- **✅ In `MeshManager`, this is done** (`874e2ec` + prep `96cbe09`): the correctness of the DTN stack
  lived in ordering contracts expressed as comments ("must run last", "runs before the router schedules
  the relay"); the inbound half — `onDeliver`, every per-type handler, `reconcileGroup` + group-photo
  helpers, and the avatar/attachment screening — moved verbatim into a constructor-injectable
  `InboundPipeline`, and the "never throw out of `onDeliver`" contract is now *mechanical* (`265c79a`)
  and testable (`InboundPipelineTest`, #4). Called the highest-value refactor in the codebase; it landed.
- **✅ In `WifiAwareTransport`, this is done** (2026-07-07): the ~40% that was Android-free decision logic —
  the eight near-duplicate "sync-owed" predicates, the two-tier watchdog's episode clock, and the cue/SSI
  codec — is extracted into three pure policy objects beside `NanConnectPolicy`/`NanServePolicy`:
  `NanSyncPolicy` (a per-candidate `PeerFacts` snapshot the transport builds under its own locks/clock, so
  each fold is a pure boolean; the digest-pure-vs-bulk-aware split is now a *structural, tested* invariant),
  `NanWatchdogPolicy` (the episode-clock arithmetic → an `Action` + next-clock decision), and `NanCueCodec`
  (the pure cue/SSI parse/encode). Each has a JVM test; the transport keeps thin wrappers whose bodies now
  call the policies, preserving every call site and lock footprint. The **"half-landed round-robin"** claim
  was **stale** — `driveSync` already selects the least-recently-attempted peer via
  `minWithOrNull(compareBy(…))` and `lastInitiateAttemptAt` *is* read (landed in `7de4697`); there is no
  starvation bug and the NAN re-audit doc's "implemented" is correct.

`ChatScreen.kt` (~2345 lines) is a milder version of the same: every concern is already a private
composable, so the split into `MessageBubble.kt` / `MessageInput.kt` / `ReactionPicker.kt` is purely
mechanical and improves reviewability with no logic risk — **deliberately deferred** as reviewability-only
(no test gain).

**✅ `BlobRepository`'s image-moderation hub role is reversed** (2026-07-07): invoking the classifier and
policy-logging inside a data-layer class was scope creep; the screening (`isImageExplicit`/`screenImage`/
`isImageFlagged`/`observeFlaggedHashes` + the classifier) moved to a dedicated `ImageScreeningService`
(`imageModerator` + `verdicts`), JVM-tested against a fake moderator + in-memory verdict DAO. `BlobRepository`
drops the `imageModerator` dep (9 → 8) and keeps `verdicts` only for its GC — the blob delete and the
verdict-row delete stay atomic in one `db.withTransaction`.

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

- **[#4]** *✅ Fixed (`8c291bb`).* `MeshManager`'s inbound pipeline — the security gate and the no-throw
  contract — was only *mirrored* by a test, never executed. It is now the extracted `InboundPipeline`
  (#8) and is driven end-to-end by `InboundPipelineTest`: real Tink keypairs, the real DTN services, a
  `FakeLoopTransport`, and mockk repo stand-ins, covering valid/relayed DMs, bad-signature drop,
  unpinned-sender park + keyreq, profile-arrival replay, and the key/nodeId mismatch — the missing
  keystone, landed.
- **[#5]** No DAO query is ever executed by a test; eviction/orphan/GC SQL is validated against a
  hand-written fake that "mirrors the SQL," and a comment even references an instrumented test that
  doesn't exist. This is the exact category of test that stays green while the real query is wrong.
  `androidx-room-testing` is already available.
  **Addressed (2026-07-05, `c9cf64d`):** `app/src/test/java/app/getknit/knit/data/` now executes the real DAO SQL under
  Robolectric + in-memory Room (`ForwardDaoTest`, `BlobDaoTest`, `ReactionDaoTest`, `MessageDaoTest` — the
  orphan/anti-join queries the fakes skipped are now run), `exportSchema` is on with a checked-in baseline
  schema, a `MigrationTestHelper` harness (`KnitDatabaseMigrationTest`) is ready for the first real migration,
  and the stale "instrumented DAO test" comment is corrected. See `.agents/context/testing.md`, "JVM Room/DAO + migration tests".
- **[#15]** *✅ Fixed (`8a8f561` + `8c291bb`).* No ViewModel had a test, because each depended on the
  concrete `MeshManager`; a narrow `MeshController` facade now fronts the mesh and the first VM test
  (`DiagnosticsViewModelTest`, against a `FakeMeshController`) exists.

None of these needed hardware — Robolectric + in-memory Room + fakes cover all three on the JVM, and all
three have now landed (#4 `8c291bb`, #5 `c9cf64d`, #15 `8a8f561`).

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
| Wi-Fi Aware plane | B+ | Heroic, well-documented; the decision logic (sync-owed folds, watchdog clock, cue codec) is now extracted into pure, JVM-tested policies (#8). |
| Data / persistence | B+ | Coherent at-rest story; DAO/migration tests now execute (#5); untransacted races (#13) now transactional; atomicity gaps (#12) remain. |
| Identity | B | Self-certifying model is elegant but under-sized (#2). |
| Moderation | B | Good design; the load-failure path (#3) and asset/license loose ends. |
| UI / Compose | A− | Textbook UDF, strong a11y; concrete-VM coupling now behind `MeshController` (#15); ChatScreen size remains. |
| Notifications | A | Genuinely well-engineered MessagingStyle integration. |
| `MeshManager` orchestration | B | The correctness core; inbound half now an injectable, tested `InboundPipeline` (#4/#8), 953 LOC. |
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
3. ✅ Cap `KeyExchange`/`BlobExchange` bookkeeping and chunk keyreq batches; add a mesh-scope
   `CoroutineExceptionHandler` (#9). *Done in `db67f72`.*
4. ✅ temp-file+rename the key/DB-key writes (#12, `b251267`); refuse any pinned-key change so a
   colliding key can't inherit `verified` (#14).
5. Force `SEED_DEMO=false` in the release buildType; add a `signingConfig` (#6 partial).
6. A doc-sync pass (#18); ✅ the stale "instrumented DAO test" claim (#5) is deleted (`c9cf64d`).

**Next (moderate effort, closes the biggest blind spots):**

7. ✅ Add the `MeshManager` inbound-pipeline integration test and Robolectric DAO tests (#4, #5).
   *Done in `8c291bb` + `c9cf64d`.*
8. Add ktlint + lint + `assembleRelease` CI jobs; make trivy gating (#17).
9. Add a BLE health signal + re-advertise `heal()` path; fix the non-idempotent backoff bump (#10).

**Then (larger refactors, do behind the new tests):**

10. ✅ Extract an `InboundPipeline` from `MeshManager` and a UI read-facade (#8, #15). *Done in
    `874e2ec` + `8a8f561`; seam/VM tests in `8c291bb`.*
11. ✅ Pull the NAN "sync-owed" predicate family, watchdog clock, and cue codec into tested policies
    (`NanSyncPolicy`/`NanWatchdogPolicy`/`NanCueCodec`) (#8). The round-robin fairness change needed no
    action — it was already landed (`7de4697`). *Done 2026-07-07.*
12. ✅ Widen the nodeId to ≥128 bits as a coordinated wire break (#2) — schedule alongside the next
    planned wire bump. *Done in `7b2c6ba`.*
13. Decide and execute the R8 story with real keep rules (#6); ✅ extract `ImageScreeningService` (#16,
    *done 2026-07-07*) — `ChatScreen`'s split is deferred (reviewability-only).

---

## 7. Closing note

The instinct to protect here is the one already on display: **push hard decisions into small, pure,
testable units, and write down why.** The codebase's best parts (the wire, the router, the policies, the
custody convergence rule) all follow it, and its riskiest parts (the two god-objects, the untested
inbound seam) are exactly where it hasn't been applied *yet*. Most of the findings above are that same
principle, extended one layer further — into the inbound pipeline, the DAO queries, and the NAN decision
logic — plus a short list of genuine defects that contradict invariants the design already commits to.
This is a strong foundation; the work remaining is hardening, not redesign.
