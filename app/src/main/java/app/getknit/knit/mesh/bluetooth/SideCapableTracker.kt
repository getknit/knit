package app.getknit.knit.mesh.bluetooth

/**
 * Which nearby peers can hear the BLE side channel — the sender's gate for spending airtime on it and the
 * receiver's gate for spending battery listening. Fed from every presence sighting's
 * [BleAdvertPayload.Parsed.sideChannel] flag.
 *
 * Presence alone would be the wrong source: [BlePresenceTracker] prunes a peer 90 s after its last sighting,
 * and a settled clique's scan is floored for minutes at a time, so a linked, flagged peer is routinely absent
 * from it. A flag therefore counts while its sighting is inside [lingerMs] **or** while that peer is
 * currently linked (the link keeps the flag it was sighted with for its lifetime), and lingers from the
 * link's end once the transport [touch]es it at teardown — the peer was provably here until then, and an
 * evicted or dropped link is exactly the unlinked case the channel exists for. A sighting *without* the
 * flag clears it — a build downgrade or a controller that lost the feature must stop us sending to it.
 *
 * [audience] says *who* is left after the prune, because the receive scan ([SideScanPolicy], ADR 2026-09.u8qj)
 * needs more than "anyone": a linked peer already gets the link copy, so an audience that is all linked is
 * worth listening for only while a stream blocks a link. Pure; JVM-tested.
 */
internal class SideCapableTracker(
    private val lingerMs: Long = CAPABLE_LINGER_MS,
) {
    /** Who could be sending pages right now, from the receiver's point of view. */
    enum class Audience {
        /** No flagged peer inside the linger or linked — nothing to hear. */
        Nobody,

        /** Every flagged peer around is linked — the link copy reaches them all. */
        AllLinked,

        /** At least one flagged peer is sighted but not linked — only a page reaches it. */
        SomeUnlinked,
    }

    // nodeId → elapsed-clock instant of its last flagged sighting (or link end, via [touch]).
    private val flagged = HashMap<String, Long>()

    @Synchronized
    fun note(
        nodeId: String,
        capable: Boolean,
        now: Long,
    ) {
        if (capable) flagged[nodeId] = now else flagged.remove(nodeId)
    }

    /**
     * Restamp a peer we already hold the flag for — called when its link goes down, so the flag lingers
     * from the link's end rather than from a sighting the floored scan may have made hours ago. A peer never
     * sighted with the flag stays unknown: this cannot make anyone capable.
     */
    @Synchronized
    fun touch(
        nodeId: String,
        now: Long,
    ) {
        if (nodeId in flagged) flagged[nodeId] = now
    }

    /** Whether any peer sighted with the flag inside the linger, or linked and once flagged, is around. */
    @Synchronized
    fun anyCapable(
        now: Long,
        linked: Set<String>,
    ): Boolean = audience(now, linked) != Audience.Nobody

    /** Who is around after the prune: nobody, only linked peers, or someone a page alone can reach. */
    @Synchronized
    fun audience(
        now: Long,
        linked: Set<String>,
    ): Audience {
        prune(now, linked)
        return when {
            flagged.isEmpty() -> Audience.Nobody
            flagged.keys.all { it in linked } -> Audience.AllLinked
            else -> Audience.SomeUnlinked
        }
    }

    @Synchronized
    fun forget(nodeId: String) {
        flagged.remove(nodeId)
    }

    @Synchronized
    fun clear() = flagged.clear()

    private fun prune(
        now: Long,
        linked: Set<String>,
    ) {
        val it = flagged.entries.iterator()
        while (it.hasNext()) {
            val (id, at) = it.next()
            if (now - at >= lingerMs && id !in linked) it.remove()
        }
    }

    companion object {
        /** How long a flagged sighting counts without a link — well past the presence scan's settled floor. */
        const val CAPABLE_LINGER_MS = 10 * 60_000L
    }
}
