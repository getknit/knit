package app.getknit.knit.mesh

import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireEnvelope

/**
 * A frame parked because its sender's key wasn't pinned when it arrived (the `NO_SENDER_KEY` drop in
 * `MeshManager.verifyInbound`). [wire] is the immutable signed unit replayed verbatim once the key
 * lands; [env] is its decoded routing envelope ([RelayEnvelope.senderId] keys recovery, [RelayEnvelope.id]
 * dedups); [fromNodeId] is the neighbor we received it from and [kind] the radio it arrived over, both
 * preserved so the replay re-runs the exact inbound path (and records the plane the frame really came in
 * on — a LoRa DM parked ahead of its sender's beacon must not replay as "nearby"). [parkedAt] drives the TTL
 * sweep. A plain class (it holds [ByteArray]s, like [CarriedFrame]).
 */
class HeldFrame(
    val wire: WireEnvelope,
    val env: RelayEnvelope,
    val fromNodeId: String,
    val parkedAt: Long,
    val kind: TransportKind = TransportKind.Other,
) {
    /** What holding it costs, near enough: the signed unit and its signature ([env] is decoded from [WireEnvelope.signed]). */
    val bytes: Long get() = (wire.signed.size + wire.sig.size).toLong()
}

/**
 * Short-lived, bounded, in-memory buffer of inbound frames awaiting their sender's key — the inbound
 * complement of the outbound `pendingKey`/`flushPendingFor` retransmit. When `MeshManager.verifyInbound`
 * drops a frame for a missing sender key it both requests the key ([KeyExchange.want]) and parks the
 * frame here ([hold]); when the key arrives, `MeshManager.handleProfile` re-runs every parked frame for
 * that sender through the normal deliver path ([release]), so a message that raced ahead of its sender's
 * profile still lands instead of being lost.
 *
 * In-memory by design: a parked frame is **unauthenticated** until its key arrives, so persisting
 * attacker-controlled bytes to disk would be a strictly worse abuse vector; the buffer evaporates on
 * restart (where store-and-forward re-serve still covers DM/group anyway). Bounded four ways — a
 * per-sender cap, a global frame cap and a global byte budget (the real bounds, since the senderId key is an
 * unauthenticated claim), and a short [holdTtlMs] — all with oldest-first eviction. Pure (no Android/Room;
 * injected clock), so it is JVM-tested directly (see `PendingInboundTest`).
 *
 * The per-sender cap is a carrier's whole custody quota for one sender (ADR 2026-09.9xuu): the backlog a
 * newcomer is served for someone it has never met arrives in one burst, and every frame of it the park turns
 * away has already been marked seen by the router — deduped for the ten-minute window on every re-serve, and
 * on Bluetooth not even re-written (`LinkCrossings`). At sixteen, a 41-frame backlog left 25 frames undelivered
 * and the two custodies disagreeing for that whole window. The byte budget is what keeps the larger cap
 * cheap: an attacker minting identities fills at most [maxBytes], never frame count × the link ceiling.
 */
class PendingInbound(
    private val now: () -> Long = { System.currentTimeMillis() },
    private val metrics: MeshMetrics = MeshMetrics(),
    private val holdTtlMs: Long = HOLD_TTL_MS,
    private val maxFrames: Int = MAX_FRAMES,
    private val maxPerSender: Int = MAX_PER_SENDER,
    private val maxBytes: Long = MAX_BYTES,
) {
    // frame id -> parked frame, insertion-ordered so the eldest entry is the oldest parked; the global caps
    // evict that eldest on overflow ([hold]). Guarded by `this` (every method is @Synchronized), like SeenSet.
    private val held = LinkedHashMap<String, HeldFrame>(64, 0.75f, false)

    // The bytes [held] keeps, against [maxBytes].
    private var heldBytes = 0L

    /**
     * Park [wire]/[env] received from [fromNodeId]. No-op if its id is already held (dedup) or this sender
     * is already at [maxPerSender] parked frames (so one claimed identity can't monopolize the buffer), or
     * if the frame alone is over [maxBytes]. The global caps evict the oldest frames on overflow.
     */
    @Synchronized
    fun hold(
        wire: WireEnvelope,
        env: RelayEnvelope,
        fromNodeId: String,
        kind: TransportKind = TransportKind.Other,
    ) {
        if (held.containsKey(env.id)) return
        if (held.values.count { it.env.senderId == env.senderId } >= maxPerSender) return
        val frame = HeldFrame(wire, env, fromNodeId, now(), kind)
        if (frame.bytes > maxBytes) return
        held[env.id] = frame
        heldBytes += frame.bytes
        metrics.onFrameHeld()
        while (held.size > maxFrames || heldBytes > maxBytes) remove(held.keys.first())
    }

    private fun remove(id: String) {
        held.remove(id)?.let { heldBytes -= it.bytes }
    }

    /** Remove and return every frame parked for [senderId], oldest first, to replay now its key is pinned. */
    @Synchronized
    fun release(senderId: String): List<HeldFrame> {
        val out = ArrayList<HeldFrame>()
        val iterator = held.values.iterator()
        while (iterator.hasNext()) {
            val frame = iterator.next()
            if (frame.env.senderId == senderId) {
                out.add(frame)
                iterator.remove()
                heldBytes -= frame.bytes
            }
        }
        return out
    }

    /** Drops frames whose [holdTtlMs] window has elapsed by [now]; returns how many were removed. */
    @Synchronized
    fun sweepExpired(): Int {
        val cutoff = now() - holdTtlMs
        var removed = 0
        val iterator = held.values.iterator()
        while (iterator.hasNext()) {
            val frame = iterator.next()
            if (frame.parkedAt < cutoff) {
                iterator.remove()
                heldBytes -= frame.bytes
                removed++
            }
        }
        return removed
    }

    private companion object {
        /** A parked frame waits at most this long for its key before the sweep reclaims it. */
        const val HOLD_TTL_MS = 2 * 60_000L

        /** Global frame cap, a bound beside [MAX_BYTES] — the per-sender key is an unauthenticated claim. */
        const val MAX_FRAMES = 512

        /**
         * Per-sender cap: a carrier's whole custody quota for one sender (`ForwardRepository.DEFAULT_MAX_PER_SENDER`,
         * which `MeshManager` injects, and `PendingInboundTest` pins this default to), so one served backlog parks
         * entire. Also bounds how many frames one profile-pin replays on the inbound coroutine — no more than the
         * burst that brought them.
         */
        const val MAX_PER_SENDER = 200

        /**
         * Global byte budget — the memory bound. Many backlogs of ordinary chat (a text frame is a few hundred bytes,
         * a message at its 2,000-character limit a few KiB); a handful of frames at the link's 512 KiB ceiling.
         */
        const val MAX_BYTES = 4L * 1024 * 1024
    }
}
