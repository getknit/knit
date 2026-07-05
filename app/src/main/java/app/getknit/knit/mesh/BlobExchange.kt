package app.getknit.knit.mesh

import app.getknit.knit.mesh.protocol.BlobReqContent
import app.getknit.knit.mesh.protocol.FrameId
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope

/**
 * Demand-driven, content-addressed image fetch over the mesh. A node that needs a blob it lacks asks
 * its direct neighbors ([want]); a neighbor that holds it serves it back over the file channel, and
 * one that doesn't recurses the request and forwards the bytes on once obtained ([onRequest] /
 * [onReceived]). The blob therefore walks hop-by-hop to any requester using only direct-neighbor file
 * transfer — no file-relay-by-destination is needed.
 *
 * The `blobreq` that drives [onRequest] is **unsigned** (see `MeshManager.verifyInbound`), so, like
 * [KeyExchange]/[PendingInbound], the bookkeeping is **bounded**: [fetching] and [wanters] are capped
 * (oldest-first eviction) and [fetching] is TTL-swept ([sweepExpired]); the [recentlyServed] serve-memo
 * keeps its 45 s TTL plus a size cap. A peer flooding requests for hashes we don't hold therefore costs
 * bounded memory and work.
 *
 * Pure (no Android/Room): the transport, blob storage, and identity are injected, so the recursion
 * can be unit-tested with [FakeLoopTransport] and a fake [BlobStore].
 */
class BlobExchange(
    private val transport: MeshTransport,
    private val store: BlobStore,
    private val selfId: suspend () -> String,
    private val onObtained: suspend (hash: String, path: String) -> Unit,
    private val newRequestId: () -> String = { FrameId.new() },
    private val now: () -> Long = System::currentTimeMillis,
    // Bounds (overridable so tests can exercise eviction with small values).
    private val maxFetching: Int = MAX_FETCHING,
    private val maxWanters: Int = MAX_WANTERS,
    private val maxServeMemo: Int = MAX_SERVE_MEMO,
    private val fetchTtlMs: Long = FETCH_TTL_MS,
) {
    // Guards fetching + wanters + recentlyServed (all mutated across coroutines); held only for short map
    // ops, never across a suspend send (the send-outside-the-lock methods below).
    private val lock = Any()

    // hash -> neighbors awaiting the blob from us (forwarded to once we obtain it). Key-capped oldest-first;
    // peers-per-key is naturally bounded by the direct-neighbor count. Guarded by [lock].
    private val wanters =
        object : LinkedHashMap<String, MutableSet<Peer>>(64, 0.75f, false) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MutableSet<Peer>>): Boolean = size > maxWanters
        }

    // hash -> last time we (re)wanted it — dedups outbound requests and orders eviction. Insertion-ordered so
    // eviction is oldest-wanted-first; TTL-swept so a never-arriving fetch is reclaimed. Guarded by [lock].
    private val fetching =
        object : LinkedHashMap<String, Long>(64, 0.75f, false) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>): Boolean = size > maxFetching
        }

    // "hash|nodeId" -> when we last enqueued that blob to that peer. A transfer slower than the requester's
    // re-ask cadence (the 60 s re-offer, or the onNeighborAdded re-ask when a new link — e.g. an on-demand
    // fast-plane NDP — comes up mid-transfer) would otherwise queue a second full copy behind the first.
    // The memo TTL is deliberately shorter than the 60 s re-offer so a genuinely lost serve still retries;
    // a size cap bounds it against a request flood between prunes. Guarded by [lock].
    private val recentlyServed =
        object : LinkedHashMap<String, Long>(64, 0.75f, false) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>): Boolean = size > maxServeMemo
        }

    /** Requests [hash] from every direct neighbor, unless we already hold it or are already fetching it. */
    suspend fun want(hash: String) {
        if (store.has(hash)) return
        if (!recordFetch(hash, now())) return // already fetching — don't re-broadcast (onNeighborAdded re-asks)
        val req = blobRequest(selfId(), hash)
        transport.neighbors.value.forEach { transport.send(req, it) } // outside the lock
    }

    /** A new neighbor appeared: re-ask it for everything we're still missing (handles late-joining holders). */
    suspend fun onNeighborAdded(peer: Peer) {
        val hashes = snapshotFetching()
        if (hashes.isEmpty()) return
        val me = selfId()
        hashes.forEach { hash -> transport.send(blobRequest(me, hash), peer) }
    }

    /**
     * A point-to-point, unsigned blob request wrapped for the transport: `relay = false` so the router
     * never floods it (it propagates hop-by-hop via [onRequest]), and an empty signature (blob requests
     * are unsigned by design — see `MeshManager.verifyInbound`).
     */
    private fun blobRequest(
        me: String,
        hash: String,
    ): WireEnvelope {
        val env =
            RelayEnvelope(
                type = FrameType.BLOB_REQ,
                id = newRequestId(),
                senderId = me,
                payload = WireCodec.encodePayload(BlobReqContent(hash)),
            )
        return WireEnvelope(relay = false, sig = ByteArray(0), signed = WireCodec.encodeEnvelope(env))
    }

    /** A neighbor asked us for [hash]: serve it if held, else record the wanter and pull it ourselves. */
    suspend fun onRequest(
        hash: String,
        fromNodeId: String,
    ) {
        val peer = Peer(fromNodeId)
        val file = store.fileFor(hash)
        val mime = store.mimeFor(hash)
        if (file != null && mime != null) {
            if (servedRecently(hash, fromNodeId)) return // its copy is (still) in flight — don't ship a second
            if (!transport.sendFile(file, peer, FileMeta(FileKind.ATTACHMENT, hash, mime))) {
                forgetServed(hash, fromNodeId) // nothing went out — let the next ask retry at once
            }
            return
        }
        recordWanter(hash, peer)
        want(hash)
    }

    /** A blob we wanted arrived from [fromNodeId]: persist it, notify, and forward to any other wanters. */
    suspend fun onReceived(
        hash: String,
        mime: String,
        srcPath: String,
        fromNodeId: String,
    ) {
        val stored = store.saveIncoming(hash, mime, srcPath) ?: return
        clearFetching(hash)
        onObtained(hash, stored.absolutePath)
        val targets = removeWanters(hash) ?: return // detached set — safe to iterate outside the lock
        // Don't bounce the blob back to whoever just gave it to us.
        targets
            .filter { it.nodeId != fromNodeId }
            .forEach {
                if (!servedRecently(hash, it.nodeId)) {
                    transport.sendFile(stored, it, FileMeta(FileKind.ATTACHMENT, hash, mime))
                }
            }
    }

    /** Drops fetches whose last-want time has aged past the TTL — a never-arriving blob is reclaimed and
     *  re-added on the next [want]. The cap, not this, is the security bound. Returns the number reclaimed. */
    fun sweepExpired(): Int =
        synchronized(lock) {
            val cutoff = now() - fetchTtlMs
            val iterator = fetching.values.iterator()
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

    /** Records a want for [hash] (insert/refresh + oldest-first eviction); true only when newly added, so a
     *  repeat want doesn't re-broadcast (preserving the dedup the old `fetching.add` gave). */
    private fun recordFetch(
        hash: String,
        nowMs: Long,
    ): Boolean =
        synchronized(lock) {
            val fresh = !fetching.containsKey(hash)
            fetching[hash] = nowMs // put on an existing key updates the value but keeps insertion order
            fresh
        }

    /** Oldest-wanted-first snapshot of the fetching hashes, copied under the lock so callers iterate safely. */
    private fun snapshotFetching(): List<String> = synchronized(lock) { fetching.keys.toList() }

    /** Removes [hash] from the fetching set (its blob arrived). */
    private fun clearFetching(hash: String) = synchronized(lock) { fetching.remove(hash) }

    /** Records [peer] as awaiting [hash] (new-key insert fires the oldest-first key-cap eviction). */
    private fun recordWanter(
        hash: String,
        peer: Peer,
    ) = synchronized(lock) { wanters.getOrPut(hash) { HashSet() }.add(peer) }

    /** Detaches and returns the peers awaiting [hash] (a fresh set, so it's safe to iterate unlocked). */
    private fun removeWanters(hash: String): Set<Peer>? = synchronized(lock) { wanters.remove(hash) }

    /**
     * True if we already enqueued [hash] to [nodeId] within [SERVE_MEMO_MS]; otherwise stamps the pair and
     * returns false (the caller serves). Prunes expired pairs first; the map's size cap bounds it against a
     * flood between prunes.
     */
    private fun servedRecently(
        hash: String,
        nodeId: String,
    ): Boolean =
        synchronized(lock) {
            val t = now()
            recentlyServed.entries.removeAll { t - it.value >= SERVE_MEMO_MS }
            val key = "$hash|$nodeId"
            if (recentlyServed.containsKey(key)) return@synchronized true
            recentlyServed[key] = t // triggers the size-cap eviction if over maxServeMemo
            false
        }

    /** Forgets a serve stamp (its send failed) so the next ask retries at once. */
    private fun forgetServed(
        hash: String,
        nodeId: String,
    ) = synchronized(lock) { recentlyServed.remove("$hash|$nodeId") }

    companion object {
        // Shorter than MeshManager's 60 s neighbor re-offer, so the periodic re-ask always passes the memo
        // and a serve whose bytes were lost (link died mid-stream) is retried on the next round.
        const val SERVE_MEMO_MS = 45_000L

        // Cap on distinct outstanding fetches — the memory bound on unsigned-blobreq-driven growth.
        private const val MAX_FETCHING = 256

        // Cap on distinct hashes we're relaying to wanters (same scale/rationale).
        private const val MAX_WANTERS = 256

        // Size cap on the in-flight serve memo (on top of its 45 s TTL).
        private const val MAX_SERVE_MEMO = 512

        // A never-arriving fetch ages out of [fetching] after this (hygiene; the cap is the bound). Generous
        // so a slow-but-live transfer isn't reclaimed, since blobs can be large.
        private const val FETCH_TTL_MS = 30 * 60_000L
    }
}
