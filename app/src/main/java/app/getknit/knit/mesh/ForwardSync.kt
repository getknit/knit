package app.getknit.knit.mesh

import app.getknit.knit.mesh.protocol.ChatFrame
import app.getknit.knit.mesh.protocol.DEFAULT_TTL
import app.getknit.knit.mesh.protocol.Frame
import app.getknit.knit.mesh.protocol.ReceiptFrame
import java.util.concurrent.ConcurrentHashMap

/**
 * Store-and-forward custody for 1:1 DMs. The mesh floods a frame once and drops it, so a DM whose
 * recipient (or a path to them) isn't connected at that instant never arrives. [ForwardSync] persists
 * the DMs a node originates or relays ([onSeen]) and, when a neighbor joins ([onNeighborAdded]),
 * unicasts the carried DMs to it: if it's the recipient it delivers + acks; otherwise its normal relay
 * floods the frame onward through the new topology. A delivery receipt from the addressed recipient
 * purges the carried copy mesh-wide ([onAck]); a TTL/cap sweep bounds the rest.
 *
 * Pure (no Android/Room): the transport and store are injected and authentication is a lambda, so the
 * whole flow is unit-testable with [FakeLoopTransport] and a fake [ForwardStore].
 */
class ForwardSync(
    private val transport: MeshTransport,
    private val store: ForwardStore,
    private val clock: () -> Long = { System.currentTimeMillis() },
    // Authenticates a relayed DM before we carry it (sender pinned + not blocked + envelope signature
    // valid), so a node never stores unauthenticated junk. Our own sends skip this (trivially authentic).
    private val authenticate: suspend (ChatFrame) -> Boolean = { true },
) {
    // nodeId -> ids already offered to that neighbor this connection, so a flapping peer isn't re-sent
    // the same backlog repeatedly. Cleared when the peer departs (see MeshManager.watchNeighbors).
    private val offeredTo = ConcurrentHashMap<String, MutableSet<String>>()

    // Ids of DMs purged by a delivery receipt: a short-lived tombstone (≤ carry TTL) so a still-
    // circulating copy from an unvaccinated peer isn't re-stored after we've already delivered it.
    private val acked = SeenSet(ttlMillis = ACK_TOMBSTONE_TTL_MS, clock = clock)

    /**
     * Capture a DM we originated ([ForwardStore.ORIGIN_SELF]) or relayed ([ForwardStore.ORIGIN_RELAY])
     * into the carry store. No-op for anything but a 1:1 DM, an already-carried/already-acked id, or a
     * relayed frame that fails authentication. The stored copy has its routing reset so it re-floods
     * with a full hop budget when later re-served (the signature covers neither ttl nor hops).
     */
    suspend fun onSeen(frame: Frame, origin: Int) {
        if (frame !is ChatFrame || frame.recipientId == null || frame.group != null) return
        if (acked.contains(frame.id) || store.has(frame.id)) return
        if (origin == ForwardStore.ORIGIN_RELAY && !authenticate(frame)) return
        store.store(frame.copy(ttl = DEFAULT_TTL, hops = 0), origin, clock())
    }

    /** A neighbor joined: unicast it every carried DM not yet offered this connection (skipping its own). */
    suspend fun onNeighborAdded(peer: Peer) {
        val offered = offeredTo.getOrPut(peer.nodeId) { ConcurrentHashMap.newKeySet() }
        store.liveFrames(clock()).forEach { frame ->
            if (frame.senderId == peer.nodeId) return@forEach // don't hand a DM back to its author
            if (offered.add(frame.id)) transport.send(frame, peer)
        }
    }

    /** A neighbor departed: forget what we offered it, so a reconnect re-offers anything still carried. */
    fun onNeighborRemoved(nodeId: String) {
        offeredTo.remove(nodeId)
    }

    /**
     * A delivery receipt arrived: drop the carried DM it acks — but only if the receipt's sender is that
     * DM's addressed recipient (the caller has already verified the receipt's signature). This makes the
     * purge recipient-authenticated, so a forged receipt can't evict an undelivered message. The id is
     * tombstoned so a copy still circulating from an unvaccinated peer isn't re-accepted by [onSeen].
     */
    suspend fun onAck(receipt: ReceiptFrame) {
        if (store.recipientOf(receipt.ackId) != receipt.senderId) return
        store.remove(receipt.ackId)
        acked.add(receipt.ackId)
    }

    /** Reclaims carried DMs whose TTL has elapsed (startup, periodic, and heartbeat sweeps). */
    suspend fun sweepExpired() {
        store.sweepExpired(clock())
    }

    private companion object {
        /** How long a delivered id stays tombstoned — matches the carry TTL so it can't outlive a copy. */
        const val ACK_TOMBSTONE_TTL_MS = 24 * 60 * 60_000L
    }
}
