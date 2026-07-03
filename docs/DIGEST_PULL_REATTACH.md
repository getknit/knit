# Proposal: digest-pull anti-entropy + deliberate re-attach

**Status:** Parts A (digest-pull) and B (deliberate re-attach) are **implemented and on-device-validated**
(Phases 1a/1b/2, commits after `b6293ae`). A subsequent field diagnosis found the reactive-re-attach model
had a **second, more severe wedge** it could neither fix nor avoid causing; the transport lifecycle was then
hardened to eliminate it at the root (see **Two distinct wedges** below). Follow-up to `b6293ae`
(*fix(mesh): harden NAN transport against single-NDI chipset wedge cases*).

> **Naming note (post-doc):** the socket framing called `AwareFraming` below was later renamed
> **`LinkFraming`** and made transport-neutral — moved to `mesh/link/`, now shared by the Wi-Fi Aware NDP
> socket **and** the Bluetooth LE L2CAP socket (`AwareFramingTest` → `LinkFramingTest`). `SyncEpoch` and
> `CueTracker` are the retired predecessors this proposal replaces with `StoreDigest` / `DigestTracker`,
> referenced here as history. Otherwise the design below is the shipped one.

This reworks how the Wi-Fi Aware transport decides **when** to sync, **what** to transfer, and **how it
manages the one NAN data interface** — replacing the coarse-epoch push-all model + reactive re-attach patches
with (A) a content-digest pull that only syncs real diffs, and (B) a deliberate re-attach policy that turns
the chipset's NDI limits from a source of wedges into a predictable lifecycle.

## Two distinct wedges (correction — this supersedes the "serving wedge / re-attach" framing below)

Field forensics (`dumpsys wifiaware` + logcat, 3 Pixels, 2026-07-01) showed the transport was conflating
**two** different failures under "the responder wedges after serving":

1. **Session-serving wedge (the ★ fact below, real).** After the accept-any responder serves one client,
   its *tracked* `requestNetwork` sits at `state=104` holding `aware_data0` and won't accept a second client.
   A bare `startResponder()` re-arm doesn't clear it; a full **`reattach()`** (closing the whole Aware
   session) does. Part B's re-attach-after-serve handles this correctly.
2. **Leaked-request wedge (the real mesh-killer, undocumented until now).** A framework RESPONDER
   `requestNetwork` (`role=1`) gets **orphaned** — registered with the framework but no longer referenced by
   the transport (so never `unregisterNetworkCallback`'d) — stuck at `state=104` on a **stale port** while the
   NDI is *actually free* (`Active NDPs{}`, `mUsedNdis:[]`). With `mMaxNdpInApp=1` that ghost reserves the
   app's one NDP slot, so every incoming NDP is refused and the responder can never accept. **`reattach()`
   cannot clear it** (the callback ref is lost); only **process death** frees it. And re-attach *churn* is
   what leaks it — concurrent triggers (subscribe-wedge + after-serve + the re-attach hint) racing a
   non-single-flight `reattach()` and an unguarded `onLost → startResponder` re-arm.

So Part B's premise — *"a served responder is wedged; a fresh session is the only reliable reset"* — is only
half true: `reattach()` clears wedge 1 but is powerless against wedge 2, and Part B's own machinery caused
wedge 2. **Fix (implemented):**
- **Prevention.** The whole attach→publish→subscribe→responder lifecycle now runs on the single callback
  handler thread with a **generation token** (stale `onAttached`/`onPublishStarted` close their session and
  never arm a responder); `reattach()` is **single-flight**; `startResponder()` is **idempotent** (one
  responder request per publish session, `responderCallback` always unregistered before replacement); the
  racy `onLost → startResponder` re-arm is removed. The responder request can no longer be orphaned ⇒ wedge 2
  cannot form. All in `WifiAwareTransport.kt`.
- **The re-attach hint is removed** (`SERVICE_NAME` → `.v6`): its premise is false for wedge 2 and its churn
  during a wedge is what leaked the request. The subscribe-wedge and after-serve re-attaches remain, now
  single-flight + generation-guarded.
- **Recovery safety net.** A watchdog (`checkWedge`) detects the leaked-request signature — Aware healthy, a
  sync owed to a reachable peer, NDI slot free, yet no data-path link for `WEDGE_RESTART_MS` — and, since
  `reattach()` can't help, self-heals via `Process.killProcess` (MeshService is `START_STICKY`). It should
  ~never fire once prevention holds; rate-limited so it can't restart-storm.

**Also note (supersedes the latency/churn analysis below):** since this doc was written, the
**coordination-plane fast-path** (`fastFanout`/`fastSend`) + general custody (`FrameType.isCustodial`)
propagate **all small floodable frames** (broadcast chat, reactions, receipts, group-meta, profiles ≤255 B)
with **zero NDP**. The wedge-prone NDP/serving path is now needed only for **bulk** (large encrypted
DMs/groups >255 B, media blobs, backlog catch-up), so serving — hence re-attach churn — is already far rarer
than the model below assumes.

## Why

The current model (commit `8488d87`, hardened in `b6293ae`) works but is **slow and re-attach-churny** on 3+
nodes. Two root causes:

1. **Coarse epoch.** `SyncEpoch` is a monotone counter meaning "something in my store changed." It can't say
   *what* changed, so every sync **pushes the whole custody store** (`ForwardSync.onNeighborAdded`), the
   receiver dedups. Worse, it triggers a data-path op even when the peer already has everything (a **no-op
   NDP**) — and NDP ops are the scarcest resource here. It's also **session-local** (resets to 0 on restart),
   so `onCue`'s monotone-max ignores a restarted peer's lower epoch until it climbs back — a latent
   missed-sync bug.
2. **Reactive re-attach.** `b6293ae` bolted on several independent re-attach triggers (share-stuck,
   subscribe-wedge, plus long-backoff) to work *around* the NDI wedging after each op. It's a pile of
   safety nets, not a lifecycle.

## Load-bearing hardware facts (measured, `dumpsys wifiaware` + logcat, 3 Pixels)

These drive the design; **Phase 0 re-verifies the two starred ones before we build on them.**

- One NDI ⇒ **one NDP at a time**.
- **★ Initiating is repeatable within an Aware session.** The largest node (pure initiator, never accepts —
  the tie-break rejects clients) synced with *both* peers back-to-back in one session, zero re-attach.
- **★ Serving (responding) wedges the session.** After a node accepts **one** client its responder sits at
  `state=104/101` holding `aware_data0`; it won't accept a second client, and it **blocks that node's own
  client role** too. `requestNetwork` re-arm does *not* clear it — only a full **re-attach** does. **(This is
  wedge 1 — see "Two distinct wedges" above. It is real, but re-attach's role as "the reliable reset" holds
  ONLY for this wedge, not for the leaked-request wedge 2.)**
- Only a **subscribe**-session `PeerHandle` can initiate an NDP (a publish handle from a cue silently times
  out). NDP teardown delivers no FIN to the responder. A half-open NDP (`peerIpv6=null`) pins the NDI.
  (All three already handled in `b6293ae`.)

**The reframing:** the wedge is caused by **serving**, not by data-path ops in general. A session that *never
serves* can initiate indefinitely. So the efficient shape is a **pure-initiator hub** (the largest reachable
node) that does every sync in one session and never re-attaches, while the nodes it syncs with **serve once,
then re-attach**. Digest-pull then minimizes how often anyone has to serve.

## Design overview

```
Coordination plane (cues, ~255 B, no NDI)     Data plane (one NDP, the scarce op)
────────────────────────────────────────     ─────────────────────────────────────
cue = nodeId | digestVersion                  on link-up: exchange full ID lists,
DigestTracker: reconcile-wanted iff my OR      compute exact set diff, pull only the
their version changed since last reconcile     messages each side lacks, then tear down
        │                                              │
        └── larger id initiates ──────────────────────┘
                                              server side re-attaches; pure initiator does not
```

## Part A — digest-pull anti-entropy

### A1. Store digest + cue

- **`StoreDigest`** (replaces `SyncEpoch`): maintains a 64-bit **version** = XOR of `hash(id)` over every ID
  currently in `ForwardStore`, folded with a small profile/key generation counter. XOR is **incremental**
  (add/remove an ID ⇒ XOR it in/out, O(1)) and **content-derived** ⇒ *restart-stable* (same store ⇒ same
  version, killing the session-local-epoch bug). Collision-negligible at 64 bits; go 128 if paranoid.
- **Cue payload** becomes `nodeId | digestVersion` (still well under 255 B). Sent on the same triggers as
  today (change + discovery + heartbeat).

### A2. `DigestTracker` (pure, JVM-tested — replaces `CueTracker`)

Per peer, remember the `(myVersion, theirVersion)` snapshot **as of our last completed reconcile**. A peer is
**reconcile-wanted** iff `myVersionNow != snap.mine || peerVersionNow != snap.theirs`. This two-sided check
(mirrors today's `CueTracker`) fixes the *"I gained data from C, must push to unchanged B"* case that a
"their-version-changed" test alone would miss. On reconcile completion, snapshot the current pair. Equality
compare only (a content hash isn't ordered — no monotone-max). Same `@Synchronized`, no Android deps.

### A3. Data-path reconciliation (replaces push-all)

New `AwareFraming.Type.DIGEST` record. On link-up, instead of `ForwardSync` blasting the whole store:

1. Both sides send `DIGEST` = their sorted list of held message IDs (bounded store ⇒ a few KB; bandwidth is
   cheap, it's NDI *availability* that's scarce). Chunk if it ever exceeds one record.
2. Each computes the exact set difference and sends **only the frames the peer lacks** (reusing the existing
   `CarriedFrame` re-serve path — deliver + relay unchanged).
3. Profiles / keys / blobs keep their current `onNeighborAdded` exchange (already diff-ish: blobs pull by
   hash, keys by `keyreq`); the digest folds a profile-gen counter so a profile change still triggers a sync.

Win: a sync transfers exactly the diff, completes fast (short NDP hold ⇒ fewer serves held open), and
**identical stores exchange digests then do nothing** — but see A4, we want to skip the NDP entirely.

### A4. Skipping no-op NDPs (the real prize)

Because the cue carries the digest **version**, a would-be initiator skips the NDP when
`peerVersion == snap.theirs && myVersion == snap.mine` (nothing changed either side since last reconcile).
That's the whole point: **spend an NDP op only when the version says there's a genuine diff.** (Exactness of
*what* differs still happens on the data path in A3; the version is the cheap "differ at all?" gate.)

## Part B — deliberate re-attach

Replace the reactive re-attach triggers with one rule derived from the ★ facts:

- **Re-attach after serving.** When a **server** link ends (`teardownPeer`, `wasServerLink`), re-attach
  instead of `startResponder()` — a served responder is wedged; a fresh session is the only reliable reset.
- **Pure initiators never re-attach.** A client-link teardown does *not* re-attach; the initiator keeps its
  session and syncs the next peer. The largest reachable node is a pure initiator ⇒ a stable **hub** that
  fans a message out to all peers in one session.
- **Middle nodes** (serve a larger peer *and* initiate to a smaller one) re-attach on the serve; the
  subsequent initiate then runs on the fresh session. Role order falls out naturally.
- **Keep** the half-open-NDP watchdog, subscribe-`onSessionConfigFailed` re-attach, and availability
  handling from `b6293ae`. **Drop** share-stuck and the long-backoff escalation — digest-pull + serve-reattach
  make "my new message is stuck" impossible: the hub notices the version diff and initiates to pull it, and
  the leaf's post-serve re-attach keeps it serve-able.
- **Also dropped (post-hardening): the coordination-plane re-attach hint** (`60fd6c5`, tag `0x02`). It was
  net-negative — it can't clear the leaked-request wedge 2, and its firing *during* a wedge is what leaked the
  request. The surviving re-attaches (subscribe-wedge, after-serve) now run through a **single-flight,
  generation-guarded** `reattach()`, so they can neither cascade nor orphan a responder request. See
  **Two distinct wedges** above for the full prevention design + the `checkWedge` recovery watchdog.

### Fast re-attach

The cost is the re-discovery gap after each serve. Minimize it: re-publish immediately, re-cue proactively
once the first peer is re-discovered, and keep the coordination plane (which never wedges) carrying cues so
the mesh keeps deciding what to sync while a node cycles. Batch matters — because A3 transfers the *whole*
diff in one NDP, a leaf serves (and re-attaches) once per **batch of new data**, not once per message.

### Propagation example (leaf L1 → leaf L2 via hub H, H largest)

```
1. L1 stores msg → digest version changes → cues H
2. H sees version diff → initiates to L1 → digest-diff pulls msg   (L1 served → L1 re-attaches)
3. H's version now changed → cues L2 → initiates to L2 → pushes msg (L2 served → L2 re-attaches)
   H did both initiates in ONE session, never re-attached.
```

O(N) syncs to cover N nodes, pipelined through the hub; each leaf re-attaches once. Compare to today, where
the message can strand on its author until a share-stuck re-attach.

## Phasing

- **Phase 0 — verify the ★ facts (physical).** ✅ **DONE — both confirmed under real 4-message load, all
  messages propagated to all 3 nodes** (experimental build = baseline + "re-attach after serving" in
  `teardownPeer`, reverted after):
  - **(a) ✓✓** the largest node (pure initiator) did **7 successful initiates in one session, 0 serves,
    0 re-attaches** — multiple initiates per session is solid, the hub never wedges.
  - **(b) ✓✓** the smallest node **served 5 clients, re-attaching after each** — re-attach reliably restores
    serve-ability; and a peer that previously *couldn't* pull from it now could, because it stayed serve-able.
  - **⚠ churn caveat:** with the coarse epoch still in place, that node re-attached **~10× in 2 min** (5
    after-serve + 5 subscribe/share-stuck). Functional but wasteful — **this is exactly why Phase 1 must land
    first**: digest-pull skips no-op syncs and batches all pending data into *one* serve, collapsing the serve
    (hence re-attach) count. It also means **Phase 2 must de-duplicate re-attach triggers** (suppress the
    subscribe-wedge / any residual reactive re-attach during an after-serve re-attach's settle window) so they
    don't cascade.
- **Phase 1 — digest-pull.**
  - **1a ✅ DONE** — `StoreDigest` (XOR-of-ids content version, **message-only**: profiles are per-node and
    would defeat the identical-skip; they still ride `pushProfileTo` on every connect), `DigestTracker`
    (equality compare + **identical-digest skip**, even on first contact), the cue now carries the version,
    wired through the store impl / DI. Unit-tested (`StoreDigestTest`, `DigestTrackerTest`), detekt-clean.
    On-device: versions are content hashes and **identical across restarts** (the restart-stability fix,
    confirmed). Kills no-op NDPs and the session-local-epoch bug. Cue format ⇒ `SERVICE_NAME` bumped `.v3`.
  - **1b ✅ DONE** — the data-path `AwareFraming.Type.DIGEST` id-list diff. On link-up each side advertises the
    ids it holds (`ForwardSync.onNeighborAdded` → `transport.sendDigest`) and pushes back only the frames the
    peer lacks (`onDigest`), replacing push-all. Bandwidth-only (NDP *availability*, not throughput, is the
    scarce resource), so it does **not** move the single-NDI latency floor — it shrinks each sync (and the NDP
    hold) as stores grow, and makes re-advertising on every contact cheap. Transport-internal record ⇒
    `SERVICE_NAME` hard cut `.v4` → `.v5` (a `.v4` node has no `DIGEST` case). The advertised list is the
    **live** id set; `StoreDigest`/`DigestTracker` and the version gate are unchanged. JVM-tested
    (`ForwardSyncTest` diff/filter cases, `AwareFramingTest` digest round-trip).
  - Note: 1a's *on-device convergence* across 3 nodes is gated on **Phase 2** — the responders still wedge
    after one serve (a bare `startResponder` re-arm doesn't clear it), which strands the pure-initiator hub.
    Phase 1a is correct in isolation (the digest decides right); it just can't be *demonstrated* converging
    until the responders stay serve-able.
- **Phase 2 — deliberate re-attach.** ✅ **DONE.** `teardownPeer` now re-attaches after a **server** link ends
  (a served responder is wedged; only a full re-attach clears it) and a pure initiator never re-attaches;
  retired share-stuck and the long-backoff escalation (kept the fast-fail handle-drop for re-discovery), and
  the after-serve re-attach stamps `lastReattachAt` so the subscribe-wedge recovery doesn't cascade on top.
  **On-device (Phase 1+2 together):** the hub does back-to-back syncs with **0 re-attach**; a responder
  **serves one client → re-attaches → serves the next** in a clean cycle; and a **freshly composed broadcast
  from the smallest node reached all three** (`PHASE2OK`, P9 → P7 & P8). Remaining cost: during active
  propagation a responder re-attaches once per serve (plus the occasional chipset subscribe-wedge re-attach) —
  bounded, and it quiesces once the digest shows stores match. Accumulated multi-day test stores don't fully
  converge to an identical version (key gaps / DM addressing), but new traffic propagates cleanly.
- **Phase 3 — leaked-request wedge fix.** ✅ **Implemented** (this session). The reactive-re-attach machinery
  could neither fix nor avoid *causing* the leaked-request wedge (wedge 2, see **Two distinct wedges**). Root
  cause: the responder/re-attach lifecycle wasn't single-owner — concurrent triggers + a non-single-flight
  `reattach()` + an unguarded `onLost → startResponder` re-arm could orphan a responder `requestNetwork`
  (`state=104`, stale port) that `reattach()` never unregisters, pinning the single NDI until process death.
  Fix: serialize the whole attach/publish/subscribe/responder lifecycle on the callback handler thread with a
  **generation token**, make `reattach()` **single-flight** and `startResponder()` **idempotent**, remove the
  `onLost` re-arm, **remove the re-attach hint** (`SERVICE_NAME` → `.v6`), and add a `checkWedge` **process-
  restart watchdog** as a last-resort safety net (MeshService is `START_STICKY`).
  - **On-device 3-Pixel validation (2026-07-02) surfaced a regression the fix introduced, now fixed too.** The
    watchdog worked (P7 self-restarted after `no link in 207291ms` and recovered), but the data plane was still
    *trivially* wedgeable. Root cause: after `reattach()` tore the session down, its inline `attach()` couldn't
    re-enable NAN immediately (the chipset needs a beat post-teardown; `isAvailable` is transiently false and
    closing our own session fires no availability broadcast), so recovery fell to the discovery loop's
    `session == null -> attach()` — but the loop was already blocked in a **long** `withTimeoutOrNull` computed
    while the session was live, so it slept a full `REDISCOVER_IDLE_MS` (~120 s) with **no responder** before
    retrying (`dumpsys` showed `mNetworkRequestsCache: {}` — no `role=1` responder — on the wedged nodes).
    **Fixes:** `reattach()` now **pokes `healSignal`** after nulling the session so the loop re-evaluates
    immediately; `rediscoverDelayMs()` returns a short `ATTACH_RETRY_MS` (3 s) while `session == null`;
    `attach()` **self-heals a stuck `attaching` guard** past `ATTACH_WATCHDOG_MS`; and the after-serve
    re-attach **reschedules** (never drops) if the slot is momentarily busy. **Result (measured, 3 Pixels):**
    post-serve responder recovery **~120 s → ~3.3 s** (`re-attach after serving` → `responder listening`),
    validated under both single and simultaneous-restart contention; all three nodes hold their `role=1`
    responder, converge to one digest, go idle, and **no watchdog restart is needed**.
  - **Watchdog false-positive fixed (2026-07-02).** After the above, sending a message on a long-idle,
    converged node **instantly killed the app** (`sync owed ... no link in 583429ms`). Cause: the watchdog
    measured *time since last data-path link*, which grows unbounded during healthy idle (a converged mesh does
    zero data-path work). The moment a fresh message made a sync owed, "no link in N minutes" was already true,
    so it fired before the mesh could even sync. **Fix:** the watchdog now times an **owed-episode**
    ([syncOwedSince]) — how long a sync has been owed *with no link forming* — starting the clock when
    divergence appears and **resetting it on any link (progress) or on convergence**. So a restart happens only
    if the mesh genuinely can't sync for the whole window, never as a reflex to a normal send. **Validated
    (3 Pixels):** an idle-converged node (last link ~4 min stale) sent an encrypted group message → **not
    killed** → message reached both peers and the mesh re-converged in ~30 s; 0 watchdog fires across all three.
  - **Second watchdog false-positive fixed (2026-07-02).** The owed-episode fix above still killed a node when
    its peers **left**: a P9 (the *smaller* id of its pair, so pure responder — never an initiator) was linked
    to two peers, they walked out of range on **every** plane (BLE then NAN), the user sent a group message,
    and ~3 min later the app self-killed (`sync owed 180013ms with no link`). Cause: `anySyncOwed()` was a
    **digest-only** check over `cueTarget`, with no reachability test despite its docstring claiming one. A
    departed peer lingers in `cueTarget` with a stale divergent digest — its cue handle only prunes when a
    *send* to it throws, which an out-of-range peer's best-effort cue never does — so the owed clock ran
    unopposed to the kill with **no peer to sync with and, for a responder, nothing it could even do**. The
    data plane wasn't wedged; the peers were just gone. **Fix:** `anySyncOwed()` now counts a peer only if it
    is a **live link or was heard on the coordination plane within `REACHABLE_LINGER_MS`** (150 s = 5 cue
    heartbeats). `REACHABLE_LINGER_MS` (150 s) < `WEDGE_RESTART_MS` (180 s), so a peer that goes *silent* can
    never sustain a full owed-while-reachable window, while a genuine leaked-request wedge keeps the peer
    cueing us over the NDP-free coordination plane, stays reachable, and still trips the restart.
  - **Stale-`cueTarget` root cause reaped (2026-07-02).** The ghost the watchdog fix defends against also
    caused real churn on the *other* side: `cueTarget` was only ever pruned when a *cue send threw*, which a
    best-effort cue to an out-of-range peer never does — so a departed peer's cue handle, `reachablePeers`
    entry, and `DigestTracker` digest lingered forever, despite `needsRediscovery`'s doc asserting "a truly-gone
    peer is pruned within a heartbeat." Left stale, an **initiator** (larger id) fast-ticks (`SYNC_RETRY_IDLE_MS`
    3 s) and re-arms subscribe (`needsRediscovery`) hunting the ghost, and every node heartbeat-cues it every
    `CUE_HEARTBEAT_MS`. **Fix:** a `pruneAbsentPeers()` staleness backstop, run from the cue heartbeat on the
    handler thread (so it serializes with the `onDiscovered`/`onCueReceived` add sites), reaps any peer with no
    live link and no coordination-plane sighting within `REACHABLE_LINGER_MS`; a reappearing peer is re-added
    normally and correctly re-syncs. This makes reality match the "gone peers are pruned" claim the engine
    already relied on, and complements the watchdog gate (which covers the 150–180 s window before the reap).
  - Unit tests + `assembleDebug` + detekt green.

## Wire / compat

- The cue and the `DIGEST` record are **transport-internal** (inside the NDP socket / discovery messages), not
  the flooded `WireEnvelope`, so `docs/WIRE_COMPAT.md` doesn't gate them. But both ends must agree ⇒ bump the
  `SERVICE_NAME` transport marker on every such change (a **hard cut**: old/new builds simply don't discover
  each other, cleaner than mixing formats). History: `.v3` cue format → `.v4` E2E byte-string re-type → `.v5`
  `DIGEST` record → **`.v6`** removed the re-attach hint (Phase 3). Hard-cut chosen throughout over a
  version-negotiated cue.

## Testing

- Pure logic (`StoreDigest` XOR add/remove/version, `DigestTracker` two-sided decision, ID-set diff) is
  JVM-unit-tested like `CueTrackerTest`/`SeenSetTest`, with `FakeLoopTransport` for the reconciliation frames.
- Re-attach lifecycle + no-op-skip + propagation timing need the 3 physical Pixels (emulator can't do NAN).

## Open decisions

1. **Digest in the cue:** content-hash **version** (simple, exact "differ?" gate — *recommended*) vs. a Bloom
   filter (also hints *direction*, but FP-prone and won't fit a large store in 255 B).
2. **Data-path digest:** full sorted ID list (exact, fine for a TTL/cap-bounded store — *recommended for v1*)
   vs. Merkle/IBLT set-reconciliation (scales to huge stores, much more code).
3. ~~**Transport version:** hard `.v3` cut vs. negotiate the cue format for a rolling upgrade.~~ **Resolved:**
   hard cut throughout; now at `.v6`.
4. ~~**Phase 0(b) outcome** decides whether Phase 2 ships or we stay on reactive re-attach.~~ **Resolved:**
   Phase 2 shipped; Phase 3 then eliminated the leaked-request wedge that reactive re-attach caused.
