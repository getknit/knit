package app.getknit.knit.mesh

import app.getknit.knit.mesh.protocol.BlobReqContent
import app.getknit.knit.mesh.protocol.FrameId
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import java.util.concurrent.ConcurrentHashMap

/**
 * Demand-driven, content-addressed image fetch over the mesh. A node that needs a blob it lacks asks
 * its direct neighbors ([want]); a neighbor that holds it serves it back over the file channel, and
 * one that doesn't recurses the request and forwards the bytes on once obtained ([onRequest] /
 * [onReceived]). The blob therefore walks hop-by-hop to any requester using only direct-neighbor file
 * transfer — no file-relay-by-destination is needed.
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
) {
    // hash -> neighbors awaiting the blob from us (forwarded to once we obtain it).
    private val wanters = ConcurrentHashMap<String, MutableSet<Peer>>()

    // Hashes we've requested and not yet obtained — dedups outbound requests.
    private val fetching = ConcurrentHashMap.newKeySet<String>()

    // "hash|nodeId" -> when we last enqueued that blob to that peer. A transfer slower than the requester's
    // re-ask cadence (the 60 s re-offer, or the onNeighborAdded re-ask when a new link — e.g. an on-demand
    // fast-plane NDP — comes up mid-transfer) would otherwise queue a second full copy behind the first.
    // The memo TTL is deliberately shorter than the 60 s re-offer so a genuinely lost serve still retries.
    private val recentlyServed = ConcurrentHashMap<String, Long>()

    /** Requests [hash] from every direct neighbor, unless we already hold it or are already fetching it. */
    suspend fun want(hash: String) {
        if (store.has(hash)) return
        if (!fetching.add(hash)) return
        val req = blobRequest(selfId(), hash)
        transport.neighbors.value.forEach { transport.send(req, it) }
    }

    /** A new neighbor appeared: re-ask it for everything we're still missing (handles late-joining holders). */
    suspend fun onNeighborAdded(peer: Peer) {
        if (fetching.isEmpty()) return
        val me = selfId()
        fetching.forEach { hash -> transport.send(blobRequest(me, hash), peer) }
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
                recentlyServed.remove("$hash|$fromNodeId") // nothing went out — let the next ask retry at once
            }
            return
        }
        wanters.getOrPut(hash) { ConcurrentHashMap.newKeySet() }.add(peer)
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
        fetching.remove(hash)
        onObtained(hash, stored.absolutePath)
        val targets = wanters.remove(hash) ?: return
        // Don't bounce the blob back to whoever just gave it to us.
        targets
            .filter { it.nodeId != fromNodeId }
            .forEach {
                if (!servedRecently(hash, it.nodeId)) {
                    transport.sendFile(stored, it, FileMeta(FileKind.ATTACHMENT, hash, mime))
                }
            }
    }

    /**
     * True if we already enqueued [hash] to [nodeId] within [SERVE_MEMO_MS]; otherwise stamps the pair and
     * returns false (the caller serves). Opportunistically prunes — the map only ever holds in-flight pairs.
     */
    private fun servedRecently(
        hash: String,
        nodeId: String,
    ): Boolean {
        val t = now()
        recentlyServed.entries.removeAll { t - it.value >= SERVE_MEMO_MS }
        return recentlyServed.putIfAbsent("$hash|$nodeId", t) != null
    }

    companion object {
        // Shorter than MeshManager's 60 s neighbor re-offer, so the periodic re-ask always passes the memo
        // and a serve whose bytes were lost (link died mid-stream) is retried on the next round.
        const val SERVE_MEMO_MS = 45_000L
    }
}
