package app.getknit.knit.mesh

import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireEnvelope
import app.getknit.knit.mesh.protocol.isStorable

/**
 * Store-and-forward custody for chat messages — 1:1 DMs, group messages, and the broadcast room. The
 * mesh floods a frame once and drops it, so a message whose recipient (or a path to them) isn't
 * connected at that instant never arrives. [ForwardSync] persists the messages a node originates or
 * relays ([onSeen]) and, when a neighbor joins ([onNeighborAdded]), unicasts the carried ones to it:
 *
 * - **DMs** are offered to any newcomer: if it's the recipient it delivers + acks; otherwise its normal
 *   relay floods the frame onward through the new topology. A delivery receipt from the addressed
 *   recipient purges the carried copy mesh-wide ([onAck]).
 * - **Group messages** are offered only to a roster member (the member set rides in cleartext on the
 *   frame); once any member receives it, the normal flood re-distributes it to the rest. A group has no
 *   single recipient and no reliable per-member ack, so it is never vaccine-purged — the TTL/cap sweep
 *   is its only bound.
 * - **Broadcast-room** messages are offered to every newcomer (no destination to target), so two phones
 *   that meet only briefly backfill each other's ambient history. Like a group they have no ack, so the
 *   (shorter) TTL/cap sweep is their only bound.
 *
 * Pure (no Android/Room): the transport and store are injected and authentication is a lambda, so the
 * whole flow is unit-testable with [FakeLoopTransport] and a fake [ForwardStore].
 */
class ForwardSync(
    private val transport: MeshTransport,
    private val store: ForwardStore,
    private val clock: () -> Long = { System.currentTimeMillis() },
    // Authenticates a relayed DM before we carry it (sender pinned + not blocked + signature valid), so a
    // node never stores unauthenticated junk. Our own sends skip this (trivially authentic).
    private val authenticate: suspend (WireEnvelope, RelayEnvelope) -> Boolean = { _, _ -> true },
) {
    // Ids of DMs purged by a delivery receipt: a short-lived tombstone (≤ carry TTL) so a still-
    // circulating copy from an unvaccinated peer isn't re-stored after we've already delivered it.
    private val acked = SeenSet(ttlMillis = ACK_TOMBSTONE_TTL_MS, clock = clock)

    /**
     * Capture a chat frame we originated ([ForwardStore.ORIGIN_SELF]) or relayed
     * ([ForwardStore.ORIGIN_RELAY]) into the carry store — a DM, group, or broadcast-room message (all
     * [isStorable]). No-op for a non-chat frame, an already-carried/already-acked id, or a relayed frame
     * that fails authentication. Only the immutable signed blob + signature are stored — a fresh wrapper
     * is stamped on re-serve, so the frame re-floods with a full hop budget (the signature covers neither
     * ttl nor hops).
     */
    suspend fun onSeen(wire: WireEnvelope, envelope: RelayEnvelope, origin: Int) {
        if (!envelope.isStorable()) return
        if (acked.contains(envelope.id) || store.has(envelope.id)) return
        if (origin == ForwardStore.ORIGIN_RELAY && !authenticate(wire, envelope)) return
        // The store impl folds the new id into the StoreDigest, whose version change re-cues neighbors that
        // we now hold something they may want to pull.
        store.store(CarriedFrame(envelope, wire.sig, wire.signed), origin, clock())
    }

    /**
     * A neighbor joined: **advertise the ids we hold** so it replies (over [MeshTransport.incomingDigests] →
     * [onDigest]) with only the frames we lack — and it does the same to us. A sync then transfers the set
     * *difference*, not the whole store (the data-path id-diff; see `docs/DIGEST_PULL_REATTACH.md`).
     *
     * Re-advertises on **every** join, deliberately. The digest cue plane brings a data-path link up only when
     * the two stores differ ([DigestTracker.reconcileWanted]'s identical-skip), so a link forming at all means
     * there is something to reconcile — and re-advertising is what lets an offer lost to an ephemeral link's
     * teardown self-heal on the *next* contact (seconds) rather than stalling for a timer. It is now cheap: the
     * peer's reply is only the diff, and a duplicate that did land is dropped by the receiver's SeenSet.
     */
    suspend fun onNeighborAdded(peer: Peer) {
        transport.sendDigest(peer, store.liveIds(clock()))
    }

    /**
     * A neighbor advertised the custody ids it holds ([theirIds]): unicast it every carried frame **it lacks** —
     * the set difference that replaces pushing the whole store. Same targeting as before: never hand a message
     * back to its author, and offer a group message only to a roster member (a DM/broadcast goes to any
     * newcomer). Each frame is re-wrapped in a fresh [WireEnvelope] (full ttl, hops 0) around its verbatim
     * signed blob; a duplicate that races in is still dropped by the receiver's SeenSet, so a stale digest only
     * ever costs bytes, never correctness.
     */
    suspend fun onDigest(fromNodeId: String, theirIds: List<String>) {
        val peer = transport.neighbors.value.find { it.nodeId == fromNodeId } ?: Peer(fromNodeId)
        val have = theirIds.toHashSet()
        store.liveFrames(clock()).forEach { carried ->
            val env = carried.envelope
            if (env.id in have) return@forEach // the diff: skip frames the peer already holds
            if (env.senderId == fromNodeId) return@forEach // don't hand a message back to its author
            val members = env.group?.members
            if (members != null && fromNodeId !in members) return@forEach // group: members only (DM/broadcast: anyone)
            transport.send(WireEnvelope(sig = carried.sig, signed = carried.signed), peer)
        }
    }

    /**
     * A delivery receipt arrived ([ackId] acked by [senderId]): drop the carried DM it acks — but only if
     * [senderId] is that DM's addressed recipient (the caller has already verified the receipt's
     * signature). This makes the purge recipient-authenticated, so a forged receipt can't evict an
     * undelivered message. The id is tombstoned so a copy still circulating from an unvaccinated peer
     * isn't re-accepted by [onSeen].
     */
    suspend fun onAck(ackId: String, senderId: String) {
        if (store.recipientOf(ackId) != senderId) return
        store.remove(ackId)
        acked.add(ackId)
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
