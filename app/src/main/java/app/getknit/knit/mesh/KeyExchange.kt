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
) {
    // nodeId -> the peer's verbatim signed profile frame, so we can re-serve it on request. Populated for
    // every profile we pin (onProfilePinned); in-memory, repopulated as profiles re-arrive after restart.
    private val cache = ConcurrentHashMap<String, WireEnvelope>()

    // nodeIds we want and haven't pinned yet — re-asked of every newcomer until one of them resolves it.
    private val missing = ConcurrentHashMap.newKeySet<String>()

    // nodeId -> last time we broadcast a request for it, so a burst of drops for the same peer sends one
    // request, not one per dropped frame. Bypassed for a brand-new neighbor (it might be the holder).
    private val lastAskedAt = ConcurrentHashMap<String, Long>()

    // nodeId -> neighbors awaiting that peer's profile from us (recorded when we couldn't answer and had
    // to recurse; forwarded to once the profile arrives). Mirrors BlobExchange.wanters.
    private val wanters = ConcurrentHashMap<String, MutableSet<Peer>>()

    /**
     * We dropped a frame from [nodeId] because its key isn't pinned: request it from every direct neighbor,
     * unless it's us, blocked, already cached, or we've asked within the cooldown. The id is remembered as
     * missing so a later-joining neighbor is re-asked ([onNeighborAdded]) even while the cooldown holds.
     */
    suspend fun want(nodeId: String) {
        val me = selfId()
        if (nodeId == me || cache.containsKey(nodeId) || isBlocked(nodeId)) return
        missing.add(nodeId)
        val last = lastAskedAt[nodeId]
        if (last != null && now() - last < requestCooldownMs) return
        lastAskedAt[nodeId] = now()
        val req = keyRequest(me, listOf(nodeId))
        transport.neighbors.value.forEach { transport.send(req, it) }
        if (transport.neighbors.value.isNotEmpty()) metrics.onKeyRequested()
    }

    /** A new neighbor appeared: ask it, in one frame, for every key we're still missing (it may hold them). */
    suspend fun onNeighborAdded(peer: Peer) {
        val wanted = missing.toList()
        if (wanted.isEmpty()) return
        transport.send(keyRequest(selfId(), wanted), peer)
        metrics.onKeyRequested()
    }

    /**
     * A neighbor asked us for [nodeIds] (from [fromNodeId]): for each, serve the peer's cached profile if we
     * hold it, otherwise record the requester as a wanter and recurse the request to our own neighbors — so
     * the profile walks back hop-by-hop once a holder is found. Our own id is skipped (a neighbor asking for
     * us would already hold our profile from the connect-time push).
     */
    suspend fun onRequest(
        nodeIds: List<String>,
        fromNodeId: String,
    ) {
        val me = selfId()
        val peer = Peer(fromNodeId)
        nodeIds.forEach { nodeId ->
            if (nodeId == me) return@forEach
            val held = cache[nodeId]
            if (held != null) {
                serve(held, peer)
            } else {
                wanters.getOrPut(nodeId) { ConcurrentHashMap.newKeySet() }.add(peer)
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
        val wasMissing = missing.remove(nodeId)
        lastAskedAt.remove(nodeId)
        if (wasMissing) metrics.onKeyRecovered()
        val waiting = wanters.remove(nodeId) ?: return
        waiting.forEach { serve(wire, it) }
    }

    /** Re-ask all neighbors for everything still missing (a heartbeat/heal hook; cooldown-exempt, batched). */
    suspend fun retryMissing() {
        val wanted = missing.toList()
        if (wanted.isEmpty() || transport.neighbors.value.isEmpty()) return
        val req = keyRequest(selfId(), wanted)
        transport.neighbors.value.forEach { transport.send(req, it) }
        metrics.onKeyRequested()
    }

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
    }
}
