package app.getknit.knit.mesh

import app.getknit.knit.mesh.protocol.FrameId
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.KeyReqContent
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import java.util.concurrent.ConcurrentHashMap

/**
 * Demand-driven recovery of a peer's public key (i.e. its profile) over the mesh. The mesh floods a
 * profile once on connect/edit and relays it under a one-shot, [SeenSet]-deduped id, so a node that
 * joined late — or is simply more than one hop from the originator — can permanently miss it and then
 * drop every frame that peer floods with `NO_SENDER_KEY` (see `MeshManager.verifyInbound`). This closes
 * that gap: a node that drops a frame for a missing key asks its neighbors for it ([want]); a neighbor
 * that holds the peer's profile re-serves it, and one that doesn't recurses the request and forwards the
 * profile on once it arrives ([onRequest] / [onProfilePinned]). The profile therefore walks hop-by-hop
 * to any requester using only direct-neighbor sends — deliberately the same point-to-point recursion as
 * [BlobExchange], NOT another flood, so recovery never re-introduces the flood-timing fragility that
 * caused the gap in the first place.
 *
 * The request is **signed** and `relay = false`: signed so a responder authenticates it against the
 * requester's pinned key (always present — direct neighbors exchange profiles on connect) and can ignore
 * blocked/unknown askers; point-to-point so it never floods (which would also have a verify-bootstrap
 * problem at relays that lack the requester's key). The *response* needs no signing or new type — the
 * peer's cached, originator-signed [FrameType.PROFILE] frame is replayed verbatim ([signed]/[sig]
 * byte-for-byte), and the existing profile path self-certifies it on pin (the nodeId must derive to the
 * advertised key), exactly as content-addressing secures a [BlobExchange] reply.
 *
 * The bookkeeping is keyed by **unauthenticated** senderIds — [want] is called from the `NO_SENDER_KEY`
 * drop path with a not-yet-pinned (so unverified) sender id — so, like [PendingInbound], it is **bounded**:
 * [missing] and [wanters] are capped (oldest-first eviction) and [missing] is TTL-swept ([sweepExpired]);
 * an outbound batch is chunked ([maxIdsPerReq]) so it can never exceed the link's payload ceiling and crash
 * the writer; and an inbound request's id list is capped ([maxRequestIds]) so it can't drive unbounded
 * recursion/growth. A peer flooding forged sender ids therefore costs bounded memory and work.
 *
 * Pure (no Android/Room): the transport, identity, raw signer, and clock are injected, so the recursion
 * is unit-tested with [FakeLoopTransport] (see `KeyExchangeTest`).
 */
class KeyExchange(
    private val transport: MeshTransport,
    private val selfId: suspend () -> String,
    // Raw Ed25519 over the canonical RelayEnvelope bytes — the same signer MeshManager.sign uses, injected
    // so the request authenticates like every other frame while this class stays free of the crypto stack.
    private val signRaw: (ByteArray) -> ByteArray,
    // Don't chase the key of a blocked peer (we drop its frames anyway); MeshManager passes its block set.
    private val isBlocked: suspend (String) -> Boolean = { false },
    private val now: () -> Long = { System.currentTimeMillis() },
    private val newRequestId: () -> String = { FrameId.new() },
    private val metrics: MeshMetrics = MeshMetrics(),
    private val requestCooldownMs: Long = REQUEST_COOLDOWN_MS,
    // Bounds (all overridable so tests can exercise eviction with small values, like PendingInbound).
    private val missingTtlMs: Long = MISSING_TTL_MS,
    private val maxMissing: Int = MAX_MISSING,
    private val maxWanters: Int = MAX_WANTERS,
    private val maxIdsPerReq: Int = MAX_IDS_PER_REQ,
    private val maxRequestIds: Int = MAX_REQUEST_IDS,
) {
    // nodeId -> the peer's verbatim signed profile frame, so we can re-serve it on request. Populated for
    // every profile we pin (onProfilePinned); in-memory, repopulated as profiles re-arrive after restart.
    // Not an attack surface (each entry required a self-certifying pin) → left unbounded/lock-free.
    private val cache = ConcurrentHashMap<String, WireEnvelope>()

    // Guards `missing` + `wanters` (both mutated across coroutines). A dedicated monitor, not `this`, and
    // held only for short map ops — never across a suspend send (see the send-outside-the-lock methods below).
    private val lock = Any()

    // nodeId -> last time we broadcast a request for it (so a burst of drops for one peer sends one request,
    // not one per dropped frame). Merges the old `missing` set + `lastAskedAt` map into one structure so the
    // two can't drift and an eviction drops both atomically. Insertion-ordered so eviction is oldest-wanted-
    // first; bounded because the keys are unauthenticated sender ids. Guarded by [lock].
    private val missing =
        object : LinkedHashMap<String, Long>(64, 0.75f, false) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>): Boolean = size > maxMissing
        }

    // nodeId -> neighbors awaiting that peer's profile from us (recorded when we couldn't answer and had to
    // recurse; forwarded to once the profile arrives). Mirrors BlobExchange.wanters. Key-capped oldest-first;
    // peers-per-key is naturally bounded by the direct-neighbor count. Guarded by [lock].
    private val wanters =
        object : LinkedHashMap<String, MutableSet<Peer>>(64, 0.75f, false) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MutableSet<Peer>>): Boolean = size > maxWanters
        }

    /**
     * We dropped a frame from [nodeId] because its key isn't pinned: request it from every direct neighbor,
     * unless it's us, blocked, already cached, or we've asked within the cooldown. The id is remembered as
     * missing so a later-joining neighbor is re-asked ([onNeighborAdded]) even while the cooldown holds.
     */
    suspend fun want(nodeId: String) {
        val me = selfId()
        if (nodeId == me || cache.containsKey(nodeId) || isBlocked(nodeId)) return
        val targets = transport.neighbors.value
        if (!recordWant(nodeId, now())) return // remembered-as-missing; cooldown says don't broadcast yet
        val req = keyRequest(me, listOf(nodeId))
        targets.forEach { transport.send(req, it) } // outside the lock
        if (targets.isNotEmpty()) metrics.onKeyRequested()
    }

    /** A new neighbor appeared: ask it for every key we're still missing — chunked so a big batch can't overflow. */
    suspend fun onNeighborAdded(peer: Peer) {
        val wanted = snapshotMissing()
        if (wanted.isEmpty()) return
        val me = selfId()
        wanted.chunked(maxIdsPerReq).forEach { transport.send(keyRequest(me, it), peer) }
        metrics.onKeyRequested()
    }

    /**
     * A neighbor asked us for [nodeIds] (from [fromNodeId]): for each, serve the peer's cached profile if we
     * hold it, otherwise record the requester as a wanter and recurse the request to our own neighbors — so
     * the profile walks back hop-by-hop once a holder is found. Our own id is skipped (a neighbor asking for
     * us would already hold our profile from the connect-time push). The list is capped ([maxRequestIds]) so a
     * hostile oversized request can't drive unbounded recursion/wanters growth.
     */
    suspend fun onRequest(
        nodeIds: List<String>,
        fromNodeId: String,
    ) {
        val me = selfId()
        val peer = Peer(fromNodeId)
        nodeIds.take(maxRequestIds).forEach { nodeId ->
            if (nodeId == me) return@forEach
            val held = cache[nodeId]
            if (held != null) {
                serve(held, peer)
            } else {
                recordWanter(nodeId, peer)
                want(nodeId)
            }
        }
    }

    /**
     * A profile for [nodeId] was pinned (delivered through [wire]): cache its verbatim signed frame for
     * future requests, clear it from the missing/cooldown bookkeeping (counting a recovery if we were
     * waiting on it), and re-serve it to any neighbors that asked us for it while we couldn't answer.
     */
    suspend fun onProfilePinned(
        nodeId: String,
        wire: WireEnvelope,
    ) {
        cache[nodeId] = wire
        if (clearMissing(nodeId)) metrics.onKeyRecovered() // one removal covers the merged missing + lastAskedAt
        val waiting = removeWanters(nodeId) ?: return // detached set — safe to iterate outside the lock
        waiting.forEach { serve(wire, it) }
    }

    /** Re-ask all neighbors for everything still missing (a heartbeat/heal hook; cooldown-exempt, chunked). */
    suspend fun retryMissing() {
        val wanted = snapshotMissing()
        val targets = transport.neighbors.value
        if (wanted.isEmpty() || targets.isEmpty()) return
        val me = selfId()
        val reqs = wanted.chunked(maxIdsPerReq).map { keyRequest(me, it) }
        targets.forEach { n -> reqs.forEach { transport.send(it, n) } }
        metrics.onKeyRequested()
    }

    /** Drops missing entries whose last-asked time has aged past the TTL (a heal-hook hygiene sweep; the cap,
     *  not this, is the security bound). An actively-dropping sender refreshes its entry every cooldown and so
     *  is retained; a sender that goes quiet ages out. Returns the number reclaimed. */
    fun sweepExpired(): Int =
        synchronized(lock) {
            val cutoff = now() - missingTtlMs
            val iterator = missing.values.iterator()
            var removed = 0
            while (iterator.hasNext()) {
                if (iterator.next() < cutoff) {
                    iterator.remove()
                    removed++
                }
            }
            removed
        }

    // --- Guarded bookkeeping (short, non-suspend; the suspend methods above send outside these) ---

    /** Records that we want [nodeId] (insert/refresh + oldest-first eviction) and returns whether to broadcast
     *  now: false while a prior ask is still within the cooldown. Atomic check-then-stamp, so two concurrent
     *  wants for one id emit exactly one request. */
    private fun recordWant(
        nodeId: String,
        nowMs: Long,
    ): Boolean =
        synchronized(lock) {
            val last = missing[nodeId]
            if (last != null && nowMs - last < requestCooldownMs) return@synchronized false
            missing[nodeId] = nowMs // put on an existing key updates the value but keeps insertion order
            true
        }

    /** Oldest-wanted-first snapshot of the missing ids, copied under the lock so callers iterate safely. */
    private fun snapshotMissing(): List<String> = synchronized(lock) { missing.keys.toList() }

    /** Removes [nodeId] from the missing/cooldown bookkeeping; true if it was present (i.e. a recovery). */
    private fun clearMissing(nodeId: String): Boolean = synchronized(lock) { missing.remove(nodeId) != null }

    /** Records [peer] as awaiting [nodeId]'s profile (new-key insert fires the oldest-first key-cap eviction). */
    private fun recordWanter(
        nodeId: String,
        peer: Peer,
    ) = synchronized(lock) { wanters.getOrPut(nodeId) { HashSet() }.add(peer) }

    /** Detaches and returns the peers awaiting [nodeId] (a fresh set, so it's safe to iterate unlocked). */
    private fun removeWanters(nodeId: String): Set<Peer>? = synchronized(lock) { wanters.remove(nodeId) }

    /** Replays a cached profile to [peer] in a fresh `relay = false` wrapper (signed bytes pass verbatim). */
    private suspend fun serve(
        profile: WireEnvelope,
        peer: Peer,
    ) {
        transport.send(WireEnvelope(relay = false, sig = profile.sig, signed = profile.signed), peer)
        metrics.onKeyServed()
    }

    /**
     * A signed, point-to-point key request for [nodeIds]: `relay = false` so [MeshRouter] never floods it
     * (it propagates hop-by-hop via [onRequest]), signed over the canonical envelope so a responder can
     * verify it against our pinned key — exactly the path every other signed frame takes.
     */
    private fun keyRequest(
        me: String,
        nodeIds: List<String>,
    ): WireEnvelope {
        val env =
            RelayEnvelope(
                type = FrameType.KEY_REQ,
                id = newRequestId(),
                senderId = me,
                payload = WireCodec.encodePayload(KeyReqContent(nodeIds)),
            )
        val signed = WireCodec.encodeEnvelope(env)
        return WireEnvelope(relay = false, sig = signRaw(signed), signed = signed)
    }

    private companion object {
        /** Min gap between broadcasts of a request for the same peer, so a flood of drops sends one ask. */
        const val REQUEST_COOLDOWN_MS = 30_000L

        /** A stale (never-refreshed) want ages out of [missing] after this — hygiene, not the bound (the cap is).
         *  Long enough not to reclaim an in-progress recovery (re-asked on every re-offer / 15-min heal). */
        const val MISSING_TTL_MS = 30 * 60_000L

        /** Cap on distinct outstanding wants — the security bound on unauthenticated-senderId growth. Profiles
         *  flood on connect, so this only holds the late-join/multi-hop gap; 256 is ~10x a dense neighborhood. */
        const val MAX_MISSING = 256

        /** Cap on distinct nodeIds we're relaying recovery for (same scale/rationale as [MAX_MISSING]). */
        const val MAX_WANTERS = 256

        /** Max ids per outbound keyreq. A nodeId is ~8 chars (~9 B CBOR), so 128 ids ≈ ~1.2 KB — far under the
         *  link's 512 KiB payload ceiling, keeping a batch small even if [MAX_MISSING] is later raised. */
        const val MAX_IDS_PER_REQ = 128

        /** Max ids processed from one inbound request — aligned with [MAX_IDS_PER_REQ] so a well-behaved peer's
         *  full chunk is served, while a hostile oversized list is truncated (bounds recursion + wanters growth). */
        const val MAX_REQUEST_IDS = 128
    }
}
