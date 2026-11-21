package app.getknit.knit.mesh.bluetooth

import app.getknit.knit.mesh.InboundFrame
import app.getknit.knit.mesh.LinkCarrier
import app.getknit.knit.mesh.LinkOutbound
import app.getknit.knit.mesh.ReceivedDigest
import app.getknit.knit.mesh.TransportKind
import app.getknit.knit.mesh.link.FramedLink

/**
 * What a Bluetooth link stamps on the frames and digests it delivers, and the token custody compares them with (the
 * custody-carry-policy contract's §3 and §6, `mesh/CarryLedger`). Pure of Android, so `LinkStampTest` drives it over real
 * [FramedLink]s and a map standing for the plane's `links`.
 *
 * **The token is the link itself.** [linkToken] reads the plane's `links` map (a `ConcurrentHashMap`: a lock-free read
 * that never calls into custody, as `MeshTransport.linkToken` requires), and a stamp carries the same [FramedLink] the
 * map held when its reader decoded the record. A link replaced under the same node id is a new [FramedLink] after
 * `registerLink`'s `links.put`, so whatever the old one stamped stops comparing `===` to the token: the ledger's relink
 * clear, with no hook here.
 *
 * **Proof is the link's own.** [FramedLink.proven] is the handshake's verdict, fixed at registration (ADR 2026-11.264w);
 * a sealed link is always proven ([FramedLink.security]). Only what came off a link is stamped: a side-channel page
 * (`BleSideChannel`, ADR 2026-09.6nmy) names no link and stays `proven = false`, `link = null`.
 */
internal object LinkStamp {
    fun frame(
        frame: InboundFrame,
        link: FramedLink,
    ): InboundFrame = frame.copy(proven = link.proven, link = link)

    /**
     * A digest is stamped proven only while [current] says the plane's `links` still holds [link] for its node id. A
     * reader mid-decode across a replacement (#142) or an R3 displacement still emits it, so it is answered, but its old
     * link's proof would read to custody as a relink and clear the peer's marks and new pending: so it carries none.
     */
    fun digest(
        digest: ReceivedDigest,
        link: FramedLink,
        current: () -> Boolean,
    ): ReceivedDigest = digest.copy(proven = link.proven && current(), link = link)

    fun linkToken(
        links: Map<String, FramedLink>,
        nodeId: String,
    ): Any? = links[nodeId]

    /** Whether the link [links] holds to [nodeId] now proved its id: the proof [frame] stamps, false with no link. */
    fun linkProven(
        links: Map<String, FramedLink>,
        nodeId: String,
    ): Boolean = links[nodeId]?.proven == true

    /**
     * The carrier a send to [nodeId] would go over now: the token, the proof and [outbound]'s counts from **one** read of
     * [links], so a #142 replacement or an R3 displacement between two reads can never pair the old link with the new
     * link's proof or its queue (release on drain, contract §2). Null with no link held.
     */
    fun carrier(
        links: Map<String, FramedLink>,
        nodeId: String,
        kind: TransportKind,
        outbound: (FramedLink) -> LinkOutbound? = { null },
    ): LinkCarrier? = links[nodeId]?.let { LinkCarrier(kind, it, it.proven, outbound(it)) }
}
