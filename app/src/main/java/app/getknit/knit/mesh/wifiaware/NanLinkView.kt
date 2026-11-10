package app.getknit.knit.mesh.wifiaware

import app.getknit.knit.mesh.LinkSecurity
import app.getknit.knit.mesh.Peer

/**
 * One read of the live data-path links, as the transport publishes it: the routing [neighbors] and the Diagnostics
 * chip's [security] by node id (ADR 2026-11.8mhs), both built from the same snapshot of `peers` so the two flows can
 * never name different link sets. A node with no live link has **no entry** in [security] — absence is "no link", never
 * a plain state. No Android deps ⇒ JVM-unit-tested ([NanLinkViewTest]).
 */
internal data class NanLinkView(
    val neighbors: Set<Peer>,
    val security: Map<String, LinkSecurity>,
) {
    companion object {
        val EMPTY = NanLinkView(emptySet(), emptyMap())

        /** The view of [links], one `(peer, security)` per live entry in `peers`. */
        fun of(links: List<Pair<Peer, LinkSecurity>>): NanLinkView =
            NanLinkView(
                neighbors = links.mapTo(LinkedHashSet()) { it.first },
                security = links.associate { (peer, security) -> peer.nodeId to security },
            )
    }
}
