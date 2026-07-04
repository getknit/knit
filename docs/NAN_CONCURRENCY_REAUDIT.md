# NAN concurrency re-audit: the one-NDP limit is per-role, and both wedges are one framework bug

**Status:** findings **verified on-device 2026-07-04** (Pixel 7/8/9 Pro XL, Android 16 `CP1A.260405.005`,
Wi-Fi mainline module `com.google.android.wifi` vc `370547060`), on branch `perf/wifi-aware-experiments`
(experiment build: after-serve reattach disabled, concurrent inbound accepts admitted). **Production `main`
still runs the single-slot ephemeral design** — this doc is the evidence base plus the proposal (§5) to
change it. It **supersedes the "★ load-bearing hardware facts" in `docs/DIGEST_PULL_REATTACH.md`** (kept
for history) and corrects the "one NDP at a time" claims in `AGENTS.md` / `docs/ARCHITECTURE.md`.

Node/device map used throughout: **P9** = Pixel 9 Pro XL = `a4gjrq5w` (smallest id ⇒ everyone's responder),
**P8** = Pixel 8 = `qjzzpz9a`, **P7** = Pixel 7 = `v4t88vbu` (largest ⇒ pure initiator).

## TL;DR

| Old belief | Verdict |
|---|---|
| `maxNdiInterfaces == 1` ⇒ one NDP at a time, period | **Wrong as stated.** True per *initiator* request and across *roles*; **false for the responder**: one accept-any request officially multiplexes many concurrent NDPs on the one NDI. E1: 30+ consecutive serves, zero re-attach. E2: two *simultaneous* inbound NDPs on one request. |
| `dumpsys` `mMaxNdpInApp=1` = a per-app NDP cap | **Misread.** It's a `WifiAwareMetrics` *high-water mark* (max concurrent NDPs ever **observed** per app) — 1 because our own gating never held 2. Firmware budget is `maxNdpSessions=8` (all three Pixels). |
| ★ "Serving wedges the session; only a full re-attach restores serve-ability" | **Refuted.** Serve-ability never degrades. What serving *does* do: the responder request keeps the NDI **assigned at 0 NDPs** (state 101), which blocks the node's **own initiator role** — that is the only thing the after-serve reattach was actually fixing. |
| ★ "Leaked `state=104` responder request; cause = orphaned callback; only process death clears it" | **Root-caused as an AOSP framework bug** reachable with perfect callback hygiene: unregistering an accept-any request whose NDPs have all ended leaks an immortal TERMINATING cache entry that pins the NDI (§2). `reattach()` cures it only by *racing* NAN-down; the race is a **coin flip on every serve** (captured live, §3.0). |
| "Only a subscribe-session handle can initiate; a publish handle silently times out" | **Confirmed — and it's documented role semantics**, not a chipset quirk: the specifier's role derives from the session type (`WifiAwareNetworkSpecifier.Builder.build()`: subscribe ⇒ INITIATOR, publish ⇒ RESPONDER), so a publish-session specifier files a peer-specific *responder* request that waits forever. |
| "ICM relights only via a subscribe re-arm" | **Better lever found:** the 30 s ICM clock is per-session `mUpdateTime`, and `updatePublish()`/`updateSubscribe()` **refresh it** — a same-config `updatePublish` heartbeat relights ICM with zero session churn (E5, untested on-device). |

## 1. The corrected model

Framework references are `packages/modules/Wifi` — verified **byte-identical on the quoted paths between
`main` and `android16-release`**; the shipped Pixel module adds dump fields (`mUsedNdis`, `Active NDPs`)
but matched AOSP semantics in every behavior we observed.

- **One aware *Network* per NDI.** `WifiAwareDataPathStateManager.selectInterfaceForRequest` picks an
  *unused* interface per network request — comment: *"the network stack does not support multiple networks
  per interface"* — and fails with the exact log `selectInterfaceForRequest: req=… - no interfaces
  available!` when none is free. Overlay `config_wifiAllowMultipleNetworksOnSameAwareNdi` (legacy sharing)
  defaults **false**. `createAllInterfaces` creates `maxNdiInterfaces` NDIs — **1** on Pixel 7/8/9.
- **Every initiator `requestNetwork` is its own Network** ⇒ on 1-NDI devices: one outbound NDP at a time,
  and no outbound while any *other* aware Network (e.g. the responder's) holds the interface.
- **The accept-any responder is ONE Network that accepts many NDPs.** Public javadoc
  (`WifiAwareNetworkSpecifier.Builder(PublishDiscoverySession)`, API 31+): *"allows connections to any
  peers or to multiple peers … **Multiple connections can be triggered by this configuration and using a
  single request** … Each successful connection will be signaled via … onAvailable. Calling
  unregisterNetworkCallback will terminate **all** connections."* Developer guide (Android 12 section):
  the accept-any responder *"enables **multiple point-to-point links with only one network request**."*
  Framework: the incoming-NDP match loop comment reads *"For Accept any, multiple NDP may setup in the
  same time"*; the 2nd+ NDP reuses the already-assigned `interfaceName`.
- **Budget:** firmware `maxNdpSessions` (**8** on all three Pixels), exposed via
  `Characteristics.getNumberOfSupportedDataPaths()` (API 33) and
  `WifiAwareManager.getAvailableAwareResources()` (API 31). `getNumberOfSupportedDataInterfaces()` = the
  NDI count. Caveat for other OEMs: Samsung Exynos (S.LSI) kernels ship `nan_max_ndp_instances = 1` —
  any multi-NDP design must capability-gate, not assume.
- **Role model:** `WifiAwareNetworkSpecifier.Builder.build()` sets role from the session type
  (`SubscribeDiscoverySession ⇒ INITIATOR`, publish ⇒ RESPONDER). Our "only a subscribe handle can
  initiate" rule is exactly this contract.
- **Discovery is framework-forced one-shot:** subscribe is hard-coded to `NanMatchAlg.MATCH_ONCE` at the
  HAL boundary (`WifiNanIfaceAidlImpl`), publish to `MATCH_NEVER`. The HAL contract suppresses repeats
  only *"with no new data"* — so an **SSI change should re-fire `onServiceDiscovered`** (E5 probe;
  firmware-dependent, would give a passive cue channel).
- **ICM:** capped by `config_wifiAwareInstantCommunicationModeDurationMillis = 30000`; at each config
  completion the framework `postDelayed(reconfigure, 30 s)` and a session counts as ICM-wanting only while
  `now − mUpdateTime ≤ 30 s`. **`updatePublish`/`updateSubscribe` refresh `mUpdateTime`** — the churn-free
  relight (§5, E5).
- **Power reality:** deep Doze **disables Aware entirely** by default
  (`PARAM_ON_IDLE_DISABLE_AWARE_DEFAULT = 1`, shell-only override); screen-off drops 2.4 GHz discovery
  windows to a wake every 8th DW (~4 s worst-case latency) and **disables 5/6 GHz DWs**. The NAN discovery
  MAC re-randomizes every **1800 s** (`mac_random_interval_sec`) — a predictable ~30-min stale-handle churn
  source that looks like "the peer restarted".
- **Capabilities (E0, all three Pixels):** `maxNdiInterfaces=1, maxNdpSessions=8, maxPublishes=8,
  maxSubscribes=8, maxServiceSpecificInfoLen=255 (extended 255), maxQueuedTransmitMessages=8`; ICM
  supported on all; suspension P8+P9; NAN pairing P9 only. The follow-up message limit **is**
  `getMaxServiceSpecificInfoLength()` (its javadoc covers `sendMessage`); framework adds a per-UID queue of
  50 with a 10 s per-message timeout, and the public `sendMessage` has service-level `retryCount=0`
  ("send succeeded" = MAC-level ACK). Field-measured elsewhere (4× Pixel 6a study): ~389 ms discovery,
  **~1.5–1.7 s average follow-up latency without ICM**, ~286 Mbps TCP over one NDP.

## 2. Both wedges are one framework bug (the 0-NDP TERMINATING ghost)

The cleanup state machine in `WifiAwareDataPathStateManager` is asymmetric:

1. When an accept-any responder's **last NDP ends**, `onDataPathEnd` early-returns for non-TERMINATING
   accept-any requests: the request keeps its NetworkAgent and its `interfaceName` (`aware_data0`)
   **at zero NDPs** and keeps accepting. `selectInterfaceForRequest` counts *any* cached request with an
   assigned interface as in-use — no state filter — so the node's own **initiator** requests are refused
   from the first serve until the request is recycled. That is the old "serving wedge" (`state=101`):
   real, but it never blocked *serving*.
2. If the app then **unregisters** that request (our `stopResponder()` during the after-serve
   `reattach()`): `releaseNetworkFor` sees the agent and *"defer[s] ending data-path to
   agent.unwanted()"*; `onNetworkUnwanted` issues `endDataPath` per live NDP — **zero of them** — sets
   `state=TERMINATING`, and waits for NDP-end callbacks that can never arrive. **Nothing reaps a stuck
   TERMINATING entry**; it pins the NDI for both roles forever. That is the leaked-request wedge
   (`state=104`) — reachable with perfect single-owner callback hygiene.
3. The only other cleaner is `onAwareDownCleanupDataPaths` (NAN fully disabling). Our `reattach()` works
   because `session.close()` (last aware client) triggers the **deferred** disable that wipes the cache;
   when the inline `attach()` wins that race, the ghost survives into the new session.
4. Unregistering **while ≥1 NDP is still alive** takes the clean path (`endDataPath` → `onDataPathEnd` →
   size==0 → cache removed, interface torn down). This is why the pure-initiator role never wedges — its
   callback is unregistered while its NDP is up — and it is the **ghost-proof recycle** the proposal in §5
   builds on. Candidate upstream bug report: *"unregistering an accept-any responder request after its last
   NDP ended leaks a TERMINATING cache entry that permanently pins the NDI"* (we have dumpsys + repro).

## 3. On-device evidence (2026-07-04)

### 3.0 The ghost captured live, from a routine serve (production-behavior build)

Minutes after a fleet restart (crash-fix build `22d1ee4`, original after-serve reattach): P9 served P8 once
(`re-attach after serving qjzzpz9a` 01:03:23.846), the reattach ran, and the framework was left with exactly
one cache entry — `role=1 state=104 interfaceName=aware_data0 NdpInfos[]` (port 41015) — plus
**`requests=0` at the aware NetworkFactory**: the fresh responder request filed after the reattach was being
*permanently refused* (`acceptRequest` → waiting-for-termination behind an immortal ghost). The node was
silently serve-dead while the coordination plane looked healthy (digests converged ⇒ nothing owed ⇒ the
wedge watchdog correctly silent until the next bulk sync would be owed — then a 3-min outage + process
kill). Conclusion: **the reattach-vs-deferred-disable race is a coin flip on every serve**, not a rare
corner. A "responder listening" log line printed even when `requestNetwork` failed (no early return) —
fixed in `22d1ee4`'s follow-up; "listening" ≠ "the framework accepted the request".

### 3.1 E0 — dumpsys ground truth

Capability values in §1. The decisive resolution: `mMaxNdpInApp:1` sits in the **`WifiAwareMetrics`** dump
among `mMaxNdiInApp / mMaxSecureNdpInApp / mMaxNdpPerNdi` and NDP histograms — observed high-water marks,
**not limits**. An idle accept-any responder shows `state=103, interfaceName=null, NdpInfos[]` — holding no
interface, exactly as AOSP predicts.

### 3.2 E1 — a responder serves indefinitely; the reattach's real job is the initiator role

Setup: after-serve reattach disabled, Bluetooth off fleet-wide (else DMs ride BLE and no NDP forms).

- **P9 accepted and served 30+ consecutive NDPs on the same accept-any request** — accept → link up → sync
  → quiescence teardown → accept again, every ~5 s, zero re-attaches, zero session cycles, zero ghosts.
  A mid-run dumpsys showed the live `NdpInfo` (ndpId 217); NDPs negotiate onto **5 GHz (5180 + 5745 MHz,
  2 streams)** even with ICM on the 2.4 GHz band.
- **Cross-role block confirmed exactly as §2 predicts:** P8 served P7 once early, then its CONFIRMED
  responder request sat at `state=101, NdpInfos[], interfaceName=aware_data0` and P8 accrued **22
  framework refusals** (`role=0 … no interfaces available!`) trying to initiate to P9. The mesh routed
  around it: P8's stranded DM reached P9 via **P7 custody relay**.
- **Churn exposed:** P7↔P9 never converged (254 vs 171 carried frames — the known multi-day-store
  non-convergence, and both digests *move* every round), so without the reattach's accidental damping the
  serve loop ran continuously at ~5 s cadence. "Delete the reattach" is not shippable until store
  convergence is fixed or `DigestTracker` gets a no-progress throttle (§5).

### 3.3 E2 — two concurrent inbound NDPs on one request (the hub decider)

Setup: E1 build + `beginAccept` relaxed to admit concurrent serves (`accepting` became a counter), DBs
wiped for a clean baseline, Bluetooth off.

- **Confirmed.** P9's single responder request at `state=101` held **`ndpId=140` (peer `02:83:4C…`) and
  `ndpId=141` (peer `EE:DD:5A…`) simultaneously**, both multiplexed on `aware_data0` on the same 5 GHz
  channels; app-side both framed TCP links were live at once (`link up: qjzzpz9a` 01:43:48.5,
  `link up: v4t88vbu` 01:43:50.1, no teardown between), and each peer's DM delivered over its own link
  (P8's drip ✓✓ flipped `received:true`). `maxNdpSessions=8` suggests headroom to ~7 spokes; 2 is all the
  hardware we own.
- **Cold-start deadlock found (and why the wipe exposed it):** with every custody store empty, all digests
  equal ⇒ **no sync ever wanted**; the drip DMs parked as `pendingKey` (no pinned keys post-wipe) so they
  never flooded; and profiles only flood on link-up — which never came. Discovery/cues were perfect
  throughout; the mesh just had no reason to link and no way to exchange keys. Production masks this
  (onboarding's profile edit seeds custody; BLE links form unconditionally and trigger the exchange), but
  it is a real gap for the digest-gated NAN-only path: **two already-onboarded nodes with empty stores
  never key-exchange over NAN alone.** One plaintext broadcast broke it exactly as the custody rules
  predict (recipients can't custody an unpinned sender's frame ⇒ digests diverge ⇒ link ⇒ profiles/keys ⇒
  all 13 parked DMs flushed).

**Ghost tally across E1+E2: zero `state=104` events** — with nothing ever unregistered at 0 NDPs, the ghost
never forms. Clean corroboration of §2.

## 4. Remaining experiments (cheap, gate specific phases of §5)

> **P0 RESULTS (2026-07-04, run as debug-toggled code on `feat/nan-concurrency` — `NanExperiments` +
> `…debug.NANEXP`):** both experiments **passed**, plus a third failure mode was found and fixed.
>
> - **E4b PASS.** The EOF-discriminated recycle (an EOF/reset proves packets just flowed ⇒ the NDP is
>   alive; a silent NDP ends via quiescence instead, which keeps the reattach fallback) + a 750 ms
>   initiator release-grace (socket-close FIN first, `unregisterNetworkCallback` deferred so the FIN can
>   ride an NDL window) delivered the FIN **8/8** times to the pure responder (P9: 8 serves → 8 in-place
>   recycles) and unlocked the full middle-node cycle on P8: serve → **recycle in 7 ms** (vs ~3.3 s for
>   the session-cycle reattach) → **successful initiate 1.5 s after serving** with zero
>   "no interfaces available" pin refusals (E1's same pattern accrued 22). Fleet after ~15 mixed cycles:
>   every cache exactly one `state=103`, **zero `state=104` ghosts**. Gates P2 ✓.
> - **NEW third failure mode (latent in production), found + fixed in P0:** when an inbound NDP request
>   arrives while the node's own *initiator* link holds the NDI, `onDataPathRequest → selectInterfaceFor
>   Request → null` makes the framework **remove the standing accept-any responder request** and fire
>   `onUnavailable` on it — which the app never overrode, leaving the node silently serve-dead until the
>   next reattach (observed live: P8's request cache empty mid-drive). Fixed: the responder callback now
>   handles `onUnavailable` (generation-guarded stop+re-file; observed self-heal in 9 ms).
> - **E5 keepalive PASS.** `updatePublish(sameConfig)` at the 25 s cadence: 5/5 `onSessionConfigUpdated`,
>   zero failures, zero subscribe re-arms, and framework ICM stayed `instantModeChannel=2437` across
>   **five consecutive 30 s reconfigure windows** (the pure-responder sawtooth gone). Gates P4 ✓.
> - **E5 SSI probe PASS.** Each keepalive's `|p<n>` SSI bump re-fired `onServiceDiscovered` on **both**
>   subscribers, **5/5** — `MATCH_ONCE` re-indicates on changed SSI as the HAL contract implies, so the
>   passive SSI-borne digest cue (P4's optional fold) is viable on this fleet's firmware.
> - **Open item for P4:** the literal mid-serve `updatePublish` disturbance check couldn't run — the
>   discovery loop's `slotBusy()` branch precedes the ICM branch, so keepalives never fire while a link
>   is live (a structural detail P4's restructure must address and then test; indirect evidence — 7
>   updates interleaved with live serve/recycle cycles on the same publish session, zero disruptions).

- **E4b — ghost-proof recycle.** At a serve's quiescence, `unregisterNetworkCallback` the responder request
  **while the NDP is still alive** (before closing the socket), then re-file `startResponder()`. Expect:
  clean framework teardown (no 104), fresh request accepted (possibly after a brief
  waiting-for-termination → `tickleConnectivityIfWaiting`), and the node's initiator role usable
  immediately after. Control: unregister at 0 NDPs reproduces the ghost (§3.0 already showed it in prod).
  Caveat from the javadoc: unregister terminates **all** connections on the request — recycle only when the
  quiescing link is the *last* live inbound. Gates Phase 2.
- **E5 — `updatePublish` ICM relight + SSI probe.** Call `publishSession.updatePublish(sameConfig)` on a
  ~25 s demand-gated cadence; verify ICM stays lit (no subscribe re-arm, no session churn). Then change one
  SSI byte and watch a subscriber for a re-fired `onServiceDiscovered` (HAL `MATCH_ONCE` = suppress repeats
  *"with no new data"*) — if it re-fires, the digest cue can ride the publish SSI passively. Gates Phase 4.
- **>2 spokes** (needs more Aware-capable devices) and **other-OEM sweep** (Samsung S.LSI `maxNdpSessions=1`
  degradation; older 2-NDI Qualcomm) — still open.

## 5. Production shape (proposal)

Principles: keep the two-plane design, the digest/cue anti-entropy, custody, the tie-break, HELLO, PSK, and
per-link quiescence (**concurrency ≠ persistence** — an idle mesh must still do zero data-path work). What
changes is the NDI lifecycle, which stops being "one link at a time + a session cycle per serve".

1. **Serve concurrently, always** (productionize E2) — **✅ implemented + fleet-validated 2026-07-04**
   (`NanServePolicy` + JVM tests; on-device: `inbound=2 acc=0 cap=4` with two framework NdpInfos, per-serve
   recycles only on the last live inbound, `nanServesPeak=2`, zero refused accepts, zero ghosts):
   `accepting` stays a counter; admit inbound serves
   whenever no *initiator* handshake is in flight; cap concurrent serves at
   `min(getNumberOfSupportedDataPaths() − 1, SERVE_CAP)` (reserve one NDP session for our own outbound;
   `SERVE_CAP` ~4 for sanity). Upstream layers are already N-neighbor-ready (BLE-tested). On
   `getNumberOfSupportedDataPaths() == 1` hardware (Samsung S.LSI), fall back to today's single-slot gate.
2. **Ghost-proof responder recycle replaces the after-serve reattach** (gated on E4b) — **✅ implemented +
   fleet-validated 2026-07-04** (owed-gated recycle default-on; pure responders never recycle and re-serve on
   one request; the pinned-at-0-NDPs corner takes a **settle-based session cycle** — NOT the availability-flap
   handshake as drafted below: the framework's disable + cache wipe ran **29–49 ms** after `session.close()`
   in measurement, while the availability *broadcasts* lag many seconds on a screen-off device, so the cycle
   waits a fixed `SESSION_CYCLE_SETTLE_MS = 2 s` instead of a broadcast; drill B: pinned → cycle → wipe@29 ms
   → settle → fresh responder → initiate, zero ghosts. The expensive cycle is additionally gated on the owed
   peer being coordination-plane-reachable, so a departed peer's stale digest can't burn cycles): when the *last* live
   inbound link hits quiescence **and** an initiate is owed (a sync-wanted smaller peer exists), unregister
   the responder request *before* closing the socket (NDP still alive ⇒ clean framework teardown), then
   immediately re-file. A pure responder with nothing to initiate skips recycle entirely — its
   CONFIRMED-at-0-NDPs request keeps serving (E1) and pins nothing it needs. Fallback for the
   0-NDPs-and-initiate-owed corner (recycle missed): session cycle that **waits for the availability flap**
   (`isAvailable == false` / `ACTION_WIFI_AWARE_STATE_CHANGED`) before re-attaching, turning the §2 race
   into a handshake. `checkWedge` stays as last resort and should ~never fire.
3. **Convergence throttle** (interim for §3.2's churn) — **✅ implemented 2026-07-04** (pure, JVM-tested;
   convergence-only reset — the movement-based reset in the original text is self-defeating for the target
   pathology, see `DigestTracker`'s kdoc; field drill deferred to the P2 co-validation session):
   `DigestTracker` backs a peer off after N completed
   syncs without convergence inside a window (e.g. 3 syncs / 60 s ⇒ cool-down with exponential growth,
   reset on convergence or on either digest changing *for another reason than our own sync*). Root-causing
   why multi-day stores' digests keep *moving* every round is a separate investigation (likely
   quota-eviction or uncarryable-frame asymmetry — anything the digest folds over must be bounded by a rule
   identical on every node, which per-node `canCarry` gates violate by design).
4. **ICM keepalive via `updatePublish`** (gated on E5): replace `needsIcmRelight()`'s `rearmSubscribe()`
   with a demand-gated same-config `updatePublish` heartbeat (<30 s cadence while a sync is owed to a
   reachable peer) — no discovery churn, works on the pure responder, removes the deliberate use of the one
   API path we know wedges. If the E5 SSI probe passes, fold the digest version into the SSI on the same
   call (passive cue to every subscriber in range).
5. **Cold-start bootstrap** (closes §3.3's deadlock): flood our own profile once per peer-epoch on **first
   cue contact** (not only on link-up), and/or re-seed custody with the self-profile whenever the store is
   empty at startup. Profiles ≤255 B ride the fast plane; larger ones create the digest divergence that
   pulls a link up — either way the key exchange bootstraps without BLE.
6. **Hygiene batch** (independent): capability-driven `COORD_MSG_MAX`
   (`characteristics.maxServiceSpecificInfoLength`), `onMessageSendSucceeded/Failed` counters in
   `MeshMetrics`, reject accepted sockets whose remote address isn't IPv6 link-local (the wildcard
   `ServerSocket` is reachable from any network), `driveSync` round-robin instead of HashMap-order
   `firstOrNull`, optional fixed 32-byte `setPmk` instead of the passphrase (skips per-NDP derivation,
   same public-constant security).

**Wire/compat:** none of this touches the wire or the cue format — `SERVICE_NAME` stays `.v6`. Topology
becomes an emergent property of the same tie-break: every node holds ≤1 outbound (to a smaller,
sync-wanted peer) while serving any number of larger peers; middle nodes alternate roles via the Phase-2
recycle instead of a session cycle; chains degrade gracefully to today's behavior (a node can still never
hold outbound + inbound simultaneously on 1 NDI — custody relay covers it, as E1 demonstrated live).

**Phasing:** P0 = E4b + E5 (one short device session). P1 = concurrent serves (+ cap + metrics). P2 =
ghost-proof recycle, retire `scheduleServeReattach`, demote the watchdog. P3 = convergence throttle. P4 =
ICM keepalive (+ SSI cue if E5 allows). P5 = bootstrap + hygiene. Each phase validated on the 3-Pixel
fleet like `DIGEST_PULL_REATTACH.md` Phases 0–3 were.
