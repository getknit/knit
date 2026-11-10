package app.getknit.knit.mesh.bluetooth

import app.getknit.knit.mesh.LinkSecurity
import app.getknit.knit.mesh.Peer
import app.getknit.knit.mesh.link.FramedLink

/**
 * What the transport's `refreshNeighbors` publishes, built from one snapshot of its live links: the neighbour set, which
 * routes sends, and each link's [FramedLink.security] by the node id it is registered under (ADR 2026-11.8mhs), which
 * only the Diagnostics screen's chip reads. One pass over one copy, so the two never describe different link sets — a
 * replaced link under the same id (an R3 swap of an unproven link for a sealed one) moves [security] while [neighbors]
 * stays put, and both must come from the same read.
 */
internal class NeighborView(
    val neighbors: Set<Peer>,
    val security: Map<String, LinkSecurity>,
) {
    companion object {
        /** [links] is read once, entry by entry (a `ConcurrentHashMap`'s weakly consistent view is copied first). */
        fun of(links: Map<String, FramedLink>): NeighborView {
            val live = links.entries.map { it.key to it.value }
            return NeighborView(
                neighbors = live.mapTo(HashSet()) { it.second.peer },
                security = live.associate { (id, link) -> id to link.security },
            )
        }
    }
}
