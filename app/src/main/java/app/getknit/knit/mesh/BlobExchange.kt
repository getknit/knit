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
 * one that doesn't pulls it on the asker's behalf ([onRequest] → [want]) so the asker's *next* ask finds
 * a holder one hop closer. The blob therefore walks hop-by-hop to any requester using only direct-neighbor
 * file transfer — no file-relay-by-destination is needed. The re-ask is the 60 s neighbor re-offer
 * ([onNeighborAdded], re-armed from the database by `MeshManager.rewantMissingBlobs`, ADR 2026-09.ptv8);
 * nothing is ever pushed to a peer that did not just ask, because the serving side cannot tell a peer
 * that still lacks the bytes from one whose copy is already on the way from somebody else — a clique
 * that pushed bought every recipient a copy per neighbor (work item #79, ADR 2026-09.4tx5).
 *
 * A blob whose bytes are already streaming in on a link ([MeshTransport.arrivingFiles]) is neither
 * wanted nor re-asked for; a re-ask for one already queued toward that peer ([MeshTransport.fileInFlightTo])
 * ships nothing. Both are reads of the link, not memos here — a torn link clears its own state.
 *
 * The `blobreq` that drives [onRequest] is **unsigned** (see `MeshManager.verifyInbound`), so, like
 * [KeyExchange]/[PendingInbound], the bookkeeping is **bounded**: [fetching] is capped (oldest-first
 * eviction) and TTL-swept ([sweepExpired]); the [recentlyServed] serve-memo keeps its 45 s TTL plus a size
 * cap. A peer flooding requests for hashes we don't hold therefore costs bounded memory and work.
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
    private val maxServeMemo: Int = MAX_SERVE_MEMO,
    private val fetchTtlMs: Long = FETCH_TTL_MS,
) {
    // Guards fetching + recentlyServed (both mutated across coroutines); held only for short map ops, never
    // across a suspend send (the send-outside-the-lock methods below).
    private val lock = Any()

    // hash -> last time we (re)wanted it — dedups outbound requests and orders eviction. Insertion-ordered so
    // eviction is oldest-wanted-first; TTL-swept so a never-arriving fetch is reclaimed. Guarded by [lock].
    private val fetching =
        object : LinkedHashMap<String, Long>(64, 0.75f, false) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>): Boolean = size > maxFetching
        }

    // "hash|nodeId" -> when we last enqueued that blob to that peer. The bound on an unsigned request flood
    // for a blob we hold, and the cover for the composite's fast-link grace, when a serve is held for the
    // NDP and sits on no link yet. A copy already queued or streaming on the link is refused by
    // [MeshTransport.fileInFlightTo] instead, whatever its age. The memo TTL is deliberately shorter than
    // the 60 s re-offer so a serve whose bytes were lost (link died mid-stream) still retries on the next
    // round; a size cap bounds it against a request flood between prunes. Guarded by [lock].
    private val recentlyServed =
        object : LinkedHashMap<String, Long>(64, 0.75f, false) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>): Boolean = size > maxServeMemo
        }

    /**
     * Requests [hash] from every direct neighbor, unless we already hold it, are already fetching it, or its
     * bytes are streaming in on a link right now. An arriving hash is not even marked: if the transfer dies
     * the database re-arms it on the next tick (`rewantMissingBlobs`), and the memo is never the source.
     */
    suspend fun want(hash: String) {
        if (store.has(hash) || hash in transport.arrivingFiles()) return
        if (!recordFetch(hash, now())) return // already fetching — don't re-broadcast (onNeighborAdded re-asks)
        val req = blobRequest(selfId(), hash)
        transport.neighbors.value.forEach { transport.send(req, it) } // outside the lock
    }

    /**
     * A neighbor appeared, or the 60 s re-offer tick came round for one: re-ask it for everything we're still
     * missing (handles late-joining holders, and is the re-ask that makes the hop-by-hop walk move).
     */
    suspend fun onNeighborAdded(peer: Peer) {
        // Drop the marks whose bytes we have since obtained, then ask for the rest. [onReceived] clears its
        // own, but the mesh is not the only plane that can satisfy a want: the spool saves an attachment and
        // fires `onAttachmentObtained` (`ScopeSync.fetchAttachment`), and a direct avatar push is ingested by
        // `InboundPipeline.onAvatarReceived` — neither routes through [onReceived], so without this the blob
        // is re-requested on every link-up for the whole [FETCH_TTL_MS] window and the neighbor re-serves
        // bytes we already hold. Asking the store here is the same guard [want] applies before it broadcasts.
        // A hash whose bytes are on the way stays in the memo (the transfer may still die) but is not asked
        // for: the re-ask against a slow BLE transfer is what bought a second copy from every holder (#79).
        val arriving = transport.arrivingFiles()
        val missing =
            snapshotFetching()
                .filterNot { hash -> store.has(hash).also { if (it) clearFetching(hash) } }
                .filterNot { it in arriving }
        if (missing.isEmpty()) return
        val me = selfId()
        missing.forEach { hash -> transport.send(blobRequest(me, hash), peer) }
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

    /**
     * A neighbor asked us for [hash]: serve it if held, else pull it ourselves so its next ask finds us
     * holding it. Nothing is remembered about the asker — it asks again on its own 60 s tick while it
     * still lacks the bytes, and stops the moment they arrive from anyone.
     */
    suspend fun onRequest(
        hash: String,
        fromNodeId: String,
    ) {
        val peer = Peer(fromNodeId)
        val file = store.fileFor(hash)
        if (file != null) {
            // Holding the bytes is what decides a serve; a missing mime row only costs us the type.
            val mime = store.mimeFor(hash) ?: FALLBACK_MIME
            // Its copy is still on the link (queued or streaming) — an older build's 60 s re-ask, or one
            // whose serve waits behind a multi-minute blob to the same peer — or was enqueued inside the
            // memo: don't ship a second.
            if (transport.fileInFlightTo(fromNodeId, hash) || servedRecently(hash, fromNodeId)) return
            if (!transport.sendFile(file, peer, FileMeta(FileKind.ATTACHMENT, hash, mime))) {
                forgetServed(hash, fromNodeId) // nothing went out — let the next ask retry at once
            }
            return
        }
        want(hash)
    }

    /** A blob we wanted arrived over a link: persist it and notify. */
    suspend fun onReceived(
        hash: String,
        mime: String,
        srcPath: String,
    ) {
        val stored = store.saveIncoming(hash, mime, srcPath) ?: return
        clearFetching(hash)
        onObtained(hash, stored.absolutePath)
    }

    /** Drops fetches whose last-want time has aged past the TTL — a never-arriving blob is reclaimed and
     *  re-armed from the database on the next tick. The cap, not this, is the security bound. Returns the
     *  number reclaimed. */
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

        // Size cap on the in-flight serve memo (on top of its 45 s TTL).
        private const val MAX_SERVE_MEMO = 512

        // A never-arriving fetch ages out of [fetching] after this (hygiene; the cap is the bound). Generous
        // so a slow-but-live transfer isn't reclaimed, since blobs can be large.
        private const val FETCH_TTL_MS = 30 * 60_000L

        // What a served blob is called when our own store names no type for it. Matches MeshBlobStore.fileFor,
        // which materializes the temp file under the same fallback.
        private const val FALLBACK_MIME = "image/jpeg"
    }
}
