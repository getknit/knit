package app.getknit.knit.mesh.bluetooth

import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.RelayEnvelope

/**
 * Where a fast-path frame goes on the Bluetooth plane now that it declares a fast plane
 * (`BluetoothMeshTransport.hasFastPlane`): the link copy `CompositeMeshTransport` used to send on its behalf,
 * plus — for the fan-out arm only — an offer to the side channel. Pure, so the routing table is a JVM test.
 *
 * - [fanout] keeps the composite's behaviour exactly: every linked peer gets the frame over its L2CAP stream
 *   (that is how a typing cue, which is never flooded, reaches a linked peer). On top, when the side channel
 *   is available, the frame is offered to it — the copy that bypasses a head-of-line-blocked stream and the
 *   one a sighted-but-unlinked peer can hear. Eligibility is the caller's `shouldFastFanout`; nothing here
 *   narrows it, so the set of frames on the page can never drift from the Wi-Fi Aware plane's.
 * - [send] is the targeted arm: the linked addressee over its link, and never the page — a DM-form frame
 *   (a sealed tick, a DM, typing toward a DM) has one recipient and no business on a broadcast carrier.
 */
internal object BleFastRoutePolicy {
    /** How to offer the frame to [SideCarousel]. */
    data class SideOffer(
        val kind: SideCarousel.Kind,
        val coalesceKey: String?,
    )

    data class Route(
        /** Linked peers that get the frame over their L2CAP stream — never its author, who has it by definition. */
        val linkTargets: Set<String>,
        /** The side-channel offer, or null when the page is not to be used. */
        val side: SideOffer?,
    )

    fun fanout(
        env: RelayEnvelope,
        linked: Set<String>,
        sideAvailable: Boolean,
    ): Route {
        val side =
            when {
                !sideAvailable -> null

                // One typing cue per sender at a time: a later one from the same sender replaces or is absorbed.
                env.type == FrameType.TYPING -> SideOffer(SideCarousel.Kind.TYPING, "typing:${env.senderId}")

                else -> SideOffer(SideCarousel.Kind.CONTENT, null)
            }
        // A page carries no hop id, so a frame first heard off one is re-fanned with the author as its hop and
        // the router's split horizon cannot exclude the link the frame would have come by; the author never
        // needs its own frame back, so the route excludes it here (the per-link crossing memo catches the
        // rest — `LinkCrossings`).
        return Route(linked - env.senderId, side)
    }

    fun send(
        to: String,
        linked: Set<String>,
    ): Route = Route(if (to in linked) setOf(to) else emptySet(), side = null)
}
