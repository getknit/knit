package app.getknit.knit.mesh

import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireEnvelope

/**
 * A frame parked because its sender's key wasn't pinned when it arrived (the `NO_SENDER_KEY` drop in
 * `MeshManager.verifyInbound`). [wire] is the immutable signed unit replayed verbatim once the key
 * lands; [env] is its decoded routing envelope ([RelayEnvelope.senderId] keys recovery, [RelayEnvelope.id]
 * dedups); [fromNodeId] is the neighbor we received it from, preserved so the replay re-runs the exact
 * inbound path. [parkedAt] drives the TTL sweep. A plain class (it holds [ByteArray]s, like [CarriedFrame]).
 */
class HeldFrame(
    val wire: WireEnvelope,
    val env: RelayEnvelope,
    val fromNodeId: String,
    val parkedAt: Long,
)

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
 * restart (where store-and-forward re-serve still covers DM/group anyway). Bounded three ways — a
 * per-sender cap, a global cap (the real bound, since the senderId key is an unauthenticated claim), and
 * a short [holdTtlMs] — all with oldest-first eviction. Pure (no Android/Room; injected clock), so it is
 * JVM-tested directly (see `PendingInboundTest`).
 */
class PendingInbound(
    private val now: () -> Long = { System.currentTimeMillis() },
    private val metrics: MeshMetrics = MeshMetrics(),
    private val holdTtlMs: Long = HOLD_TTL_MS,
    private val maxFrames: Int = MAX_FRAMES,
    private val maxPerSender: Int = MAX_PER_SENDER,
) {
    // frame id -> parked frame, insertion-ordered so the eldest entry is the oldest parked; the global
    // cap evicts that eldest on overflow. Guarded by `this` (every method is @Synchronized), like SeenSet.
    private val held = object : LinkedHashMap<String, HeldFrame>(64, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, HeldFrame>): Boolean =
            size > maxFrames
    }

    /**
     * Park [wire]/[env] received from [fromNodeId]. No-op if its id is already held (dedup) or this sender
     * is already at [maxPerSender] parked frames (so one claimed identity can't monopolize the buffer).
     * The global cap evicts the oldest frame on overflow.
     */
    @Synchronized
    fun hold(wire: WireEnvelope, env: RelayEnvelope, fromNodeId: String) {
        if (held.containsKey(env.id)) return
        if (held.values.count { it.env.senderId == env.senderId } >= maxPerSender) return
        held[env.id] = HeldFrame(wire, env, fromNodeId, now())
        metrics.onFrameHeld()
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
            if (iterator.next().parkedAt < cutoff) {
                iterator.remove()
                removed++
            }
        }
        return removed
    }

    private companion object {
        /** A parked frame waits at most this long for its key before the sweep reclaims it. */
        const val HOLD_TTL_MS = 2 * 60_000L

        /** Global cap — the real memory bound, since the per-sender key is an unauthenticated claim. */
        const val MAX_FRAMES = 128

        /** Per-sender cap; also bounds how many frames one profile-pin replays on the inbound coroutine. */
        const val MAX_PER_SENDER = 16
    }
}
