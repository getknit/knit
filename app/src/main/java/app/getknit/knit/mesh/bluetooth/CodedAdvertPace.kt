package app.getknit.knit.mesh.bluetooth

/**
 * When the Coded set advertises fast (ADR 2026-10.yvn6, amendment 3). A link at range that drops on its own leaves its
 * far peer just out of reach, and the peer dials back only on this phone's Coded advert: once a second, it gave the
 * 2026-10-01 walk's dialer one link in four 12 s dials. So for [PhyTuning.fastHoldMs] after such a drop
 * ([CodedPhyPolicy.fastAdvertAfterDrop]) the set goes out every [PhyTuning.fastAdvertMs], until the peer links again or
 * the hold runs out.
 *
 * A fast set costs the controller more than a slow one, and the controller can refuse an enable outright (0x0d beside
 * a busy link, ADR 2026-10.9utz). [maxRefusals] refused fast enables give the rest of the window up: a slow advert on
 * the air beats a fast one refused. The count clears once no drop is wanted back, so the next drop tries again.
 *
 * Pure and thread-safe: the transport's link edges, the stack's binder callbacks and the revert timer all write it.
 */
internal class CodedAdvertPace(
    private val maxRefusals: Int = MAX_REFUSALS,
) {
    private val fastUntil = HashMap<String, Long>()
    private var refusals = 0

    /** [peerNodeId]'s link just dropped at range: advertise fast until [now] + [holdMs]. */
    @Synchronized
    fun onDrop(
        peerNodeId: String,
        now: Long,
        holdMs: Long,
    ) {
        prune(now)
        fastUntil[peerNodeId] = now + holdMs
    }

    /** [peerNodeId] linked again: it is no longer wanted back. */
    @Synchronized
    fun onLinkUp(peerNodeId: String) {
        fastUntil.remove(peerNodeId)
    }

    /** The controller refused a fast enable. Returns true when that gives the rest of the window up. */
    @Synchronized
    fun onFastRefused(): Boolean {
        refusals += 1
        return refusals == maxRefusals
    }

    /** Whether the Coded set should advertise fast at [now]. */
    @Synchronized
    fun fast(now: Long): Boolean {
        prune(now)
        return fastUntil.isNotEmpty() && refusals < maxRefusals
    }

    /** The peers wanted back at [now], for the log. */
    @Synchronized
    fun wanted(now: Long): Set<String> {
        prune(now)
        return fastUntil.keys.toSet()
    }

    /** When the last wanted peer's hold runs out, or null when none is wanted. */
    @Synchronized
    fun endsAt(): Long? = fastUntil.values.maxOrNull()

    private fun prune(now: Long) {
        fastUntil.values.removeAll { it <= now }
        if (fastUntil.isEmpty()) refusals = 0
    }

    private companion object {
        const val MAX_REFUSALS = 2
    }
}
