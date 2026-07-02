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
) {
    // hash -> neighbors awaiting the blob from us (forwarded to once we obtain it).
    private val wanters = ConcurrentHashMap<String, MutableSet<Peer>>()
    // Hashes we've requested and not yet obtained — dedups outbound requests.
    private val fetching = ConcurrentHashMap.newKeySet<String>()

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
    private fun blobRequest(me: String, hash: String): WireEnvelope {
        val env = RelayEnvelope(
            type = FrameType.BLOB_REQ, id = newRequestId(), senderId = me,
            payload = WireCodec.encodePayload(BlobReqContent(hash)),
        )
        return WireEnvelope(relay = false, sig = ByteArray(0), signed = WireCodec.encodeEnvelope(env))
    }

    /** A neighbor asked us for [hash]: serve it if held, else record the wanter and pull it ourselves. */
    suspend fun onRequest(hash: String, fromNodeId: String) {
        val peer = Peer(fromNodeId)
        val file = store.fileFor(hash)
        val mime = store.mimeFor(hash)
        if (file != null && mime != null) {
            transport.sendFile(file, peer, FileMeta(FileKind.ATTACHMENT, hash, mime))
            return
        }
        wanters.getOrPut(hash) { ConcurrentHashMap.newKeySet() }.add(peer)
        want(hash)
    }

    /** A blob we wanted arrived from [fromNodeId]: persist it, notify, and forward to any other wanters. */
    suspend fun onReceived(hash: String, mime: String, srcPath: String, fromNodeId: String) {
        val stored = store.saveIncoming(hash, mime, srcPath) ?: return
        fetching.remove(hash)
        onObtained(hash, stored.absolutePath)
        val targets = wanters.remove(hash) ?: return
        // Don't bounce the blob back to whoever just gave it to us.
        targets.filter { it.nodeId != fromNodeId }
            .forEach { transport.sendFile(stored, it, FileMeta(FileKind.ATTACHMENT, hash, mime)) }
    }
}
