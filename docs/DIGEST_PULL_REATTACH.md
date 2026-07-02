# Proposal: digest-pull anti-entropy + deliberate re-attach

**Status:** design proposal (not yet implemented). Follow-up to `b6293ae`
(*fix(mesh): harden NAN transport against single-NDI chipset wedge cases*).

This reworks how the Wi-Fi Aware transport decides **when** to sync, **what** to transfer, and **how it
manages the one NAN data interface** — replacing the coarse-epoch push-all model + reactive re-attach patches
with (A) a content-digest pull that only syncs real diffs, and (B) a deliberate re-attach policy that turns
the chipset's NDI limits from a source of wedges into a predictable lifecycle.

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
  client role** too. `requestNetwork` re-arm does *not* clear it — only a full **re-attach** does.
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

## Wire / compat

- The cue and the `DIGEST` record are **transport-internal** (inside the NDP socket / discovery messages), not
  the flooded `WireEnvelope`, so `docs/WIRE_COMPAT.md` doesn't gate them. But both ends must agree ⇒ bump a
  transport marker. `SERVICE_NAME` already carries `.v2`; a hard cut goes `.v3` (old/new builds simply don't
  discover each other, which is cleaner than mixed cue formats). Decide: hard cut vs. version-negotiated cue.

## Testing

- Pure logic (`StoreDigest` XOR add/remove/version, `DigestTracker` two-sided decision, ID-set diff) is
  JVM-unit-tested like `CueTrackerTest`/`SeenSetTest`, with `FakeLoopTransport` for the reconciliation frames.
- Re-attach lifecycle + no-op-skip + propagation timing need the 3 physical Pixels (emulator can't do NAN).

## Open decisions

1. **Digest in the cue:** content-hash **version** (simple, exact "differ?" gate — *recommended*) vs. a Bloom
   filter (also hints *direction*, but FP-prone and won't fit a large store in 255 B).
2. **Data-path digest:** full sorted ID list (exact, fine for a TTL/cap-bounded store — *recommended for v1*)
   vs. Merkle/IBLT set-reconciliation (scales to huge stores, much more code).
3. **Transport version:** hard `.v3` cut vs. negotiate the cue format for a rolling upgrade.
4. **Phase 0(b) outcome** decides whether Phase 2 ships or we stay on reactive re-attach.
