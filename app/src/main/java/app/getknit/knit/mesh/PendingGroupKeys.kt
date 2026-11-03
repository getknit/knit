package app.getknit.knit.mesh

/**
 * Short-lived, bounded, in-memory buffer of `CTL_GROUP_KEY` frames that arrived for a group we do not hold
 * yet — the seed-before-roster race. A group reaches a member as the roster on its first frame
 * (`InboundPipeline.reconcileGroup`), but the creator distributes the sender-key seed *before* that frame
 * floods (`MeshManager.sealGroupRatchet`), and custody serves the two in whatever order it likes. Adoption
 * is gated on holding the group, and the DM ratchet consumes a ctl frame whether or not its payload was
 * adopted — so without this buffer the seed lands, is discarded, and every re-serve of it is a
 * `RATCHET_DUPLICATE`; the group's first message then sits at `GROUP_RATCHET_NO_KEY` until a re-send
 * trigger the sender may never hit.
 *
 * Since work item #47 a seed carries its founding roster (`GroupKeyPayload.group`) and a receiver pins the
 * group from the seed itself (`InboundPipeline.pinRosterFromSeed`), so what parks here is a seed from a
 * build that predates the field, or one whose roster was refused — the fallback, no longer the founding
 * path.
 *
 * The pipeline parks the frame here **before the ratchet commit** (the lock-free peek decides), so the
 * chain never advances past it; when the group is first reconciled, `reconcileGroup` releases the parked
 * frames and re-runs them through the deliver path, where the same open now succeeds (a later frame from
 * that sender only leaves a skipped key behind, which the replay consumes). The race's second shape parks
 * here too: a sender we hold as *departed* whose seed for the re-created group floods ahead of the frame
 * that rejoins them. That frame waits on its sender's own rejoin, not on the roster, so [release] takes a
 * predicate — the pipeline releases only what the reconciled row can now adopt, and a frame whose sender is
 * still departed stays parked (its `parkedAt` untouched) across every other member's frame, until the
 * rejoin lifts the tombstone or the TTL ages it out. Releasing it regardless would re-park it with a fresh
 * clock on every group frame, so the hold never expired and the held/replayed metrics climbed for a seed
 * that was going nowhere.
 *
 * In-memory like [PendingInbound]: the frame is already authenticated (it passed `verifyInbound`), but a
 * process restart loses nothing that custody does not still hold — the seed frame was custodied on first
 * sight and its chain was never advanced, so a re-serve after restart takes this path again. Bounded three
 * ways — a per-group cap (a roster is at most eight members, each owed a root gossip and a seed), a global
 * cap, and a [holdTtlMs] — all oldest-first. Pure (no Android/Room; injected clock), JVM-tested directly
 * (`PendingGroupKeysTest`).
 */
class PendingGroupKeys(
    private val now: () -> Long = { System.currentTimeMillis() },
    private val metrics: MeshMetrics = MeshMetrics(),
    private val holdTtlMs: Long = HOLD_TTL_MS,
    maxFrames: Int = MAX_FRAMES,
    private val maxPerGroup: Int = MAX_PER_GROUP,
) {
    private class Parked(
        val groupId: String,
        val frame: HeldFrame,
    )

    // frame id -> parked frame, insertion-ordered so the eldest entry is the oldest parked; the global cap
    // evicts that eldest on overflow. Guarded by `this` (every method is @Synchronized), like PendingInbound.
    private val held =
        object : LinkedHashMap<String, Parked>(32, 0.75f, false) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Parked>): Boolean = size > maxFrames
        }

    /**
     * Parks [frame], a verified ctl DM carrying keys for [groupId]. Returns true when the frame is parked —
     * newly, or already (a custody re-serve of a held frame is swallowed the same way, so the caller skips
     * the ratchet commit either way) — and false when the group is at [maxPerGroup], in which case the
     * caller lets the frame take the ordinary path.
     */
    @Synchronized
    fun hold(
        groupId: String,
        frame: InboundFrame,
    ): Boolean {
        if (held.containsKey(frame.envelope.id)) return true
        if (held.values.count { it.groupId == groupId } >= maxPerGroup) return false
        held[frame.envelope.id] = Parked(groupId, HeldFrame(frame.wire, frame.envelope, frame.fromNodeId, now(), frame.kind))
        metrics.onGroupSeedHeld()
        return true
    }

    /**
     * Removes and returns every frame parked for [groupId] that [eligible] accepts, oldest first, to replay
     * now the group is held. A frame it refuses is left exactly where it was — same slot, same `parkedAt` —
     * so a caller can release what the roster can now adopt without restarting the clock on what it can't.
     */
    @Synchronized
    fun release(
        groupId: String,
        eligible: (HeldFrame) -> Boolean = { true },
    ): List<HeldFrame> {
        val out = ArrayList<HeldFrame>()
        val iterator = held.values.iterator()
        while (iterator.hasNext()) {
            val parked = iterator.next()
            if (parked.groupId == groupId && eligible(parked.frame)) {
                out.add(parked.frame)
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
            if (iterator.next().frame.parkedAt < cutoff) {
                iterator.remove()
                removed++
            }
        }
        return removed
    }

    private companion object {
        /**
         * How long a seed waits for its group's first frame. The two normally arrive seconds apart (the
         * creator floods them back to back; a custody round serves both), so this is a bound on a member
         * that heard the seed and then lost the frame's plane, not a design point. Well inside the ratchet's
         * 48 h skipped-key retention, which is what keeps the replay openable.
         */
        const val HOLD_TTL_MS = 60 * 60_000L

        /** Global cap — the memory bound. */
        const val MAX_FRAMES = 64

        /** Per-group cap; a roster is at most eight members, each owed at most a root gossip and a seed. */
        const val MAX_PER_GROUP = 16
    }
}
