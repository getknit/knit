package app.getknit.knit.mesh.bluetooth

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Decides whether one link's reported end counts as a flap on the peer's redial streak, and lets only the first report
 * through. `FramedLink`'s read loop and write loop each report `onLinkDown` when they end, and both end together when
 * the peer goes while a write is in flight (EOF or a truncated record on one, a broken pipe on the other), on two
 * threads, often in the same millisecond. The second report found nothing to release, and with no replacement link yet
 * `ConnectBackoffs.flapped(false, false)` still counted it, so one drop bumped the peer's redial streak twice: about
 * 20 s before the first redial instead of 10 s (#205, seen on P3 and P8 on 2026-10-19).
 *
 * One per link, held by its `LinkEvents`; a link replaced under the same node id has its own. A compare-and-set, since
 * the two loops race. The bump, the wake and the log stay in the transport.
 */
internal class LinkDownOnce {
    private val reported = AtomicBoolean(false)

    /**
     * Runs [teardown] (true when it released this link's slot) for this link's first reported end only; true when that
     * end counts as a flap. A later report returns false and calls nothing. [ownClose] is asked only when nothing was
     * released: a link this side closed itself is not the peer flapping. [replaced] is read after the teardown.
     */
    fun flapped(
        teardown: () -> Boolean,
        replaced: () -> Boolean,
        ownClose: () -> Boolean = { false },
    ): Boolean {
        if (!reported.compareAndSet(false, true)) return false
        val released = teardown()
        return (released || !ownClose()) && ConnectBackoffs.flapped(released, replaced())
    }
}
