package app.getknit.knit.mesh.wifiaware

import app.getknit.knit.mesh.InboundFrame
import app.getknit.knit.mesh.LinkCarrier
import app.getknit.knit.mesh.LinkOutbound
import app.getknit.knit.mesh.ReceivedDigest
import app.getknit.knit.mesh.TransportKind
import app.getknit.knit.mesh.link.FramedLink

/**
 * What a Wi-Fi Aware data path stamps on the frames and digests it delivers, and the token custody compares them with
 * (the custody-carry-policy contract's §3 and §6, `mesh/CarryLedger`): the rule the Bluetooth plane follows
 * (`mesh/bluetooth/LinkStamp`), over this plane's `peers`, whose entries wrap the [FramedLink]. Pure of Android, so
 * `NanLinkStampTest` drives it over real [FramedLink]s and a map standing for `peers`.
 *
 * **The token is the data path's [FramedLink]**, read off `peers` (a `ConcurrentHashMap`: a lock-free read that never
 * calls into custody, as `MeshTransport.linkToken` requires). Every data path is a new [FramedLink] built in
 * `registerConn`, so a path replaced or torn down stops comparing `===` to the token. Custody never reads a new Aware
 * path as a relink (contract §6: a new path clears nothing); the token says only which path carried what.
 *
 * **Proof is the path's own** ([FramedLink.proven], the handshake's verdict fixed at registration, ADR 2026-11.264w). A
 * digest is stamped proven only while its path is still the peer's current one: a reader that finished a record after
 * its path was displaced (R3) or torn down still emits it, so it is answered, but it feeds custody nothing. A hop-less
 * coordination-plane frame (`emitFastWire`) came off no path and is never stamped.
 *
 * **What the path holds unwritten is its [FramedLink]'s own count** ([outbound], contract revision 13), read off the same
 * entry as the token and the proof. Nothing is estimated for the TCP send buffer below it: the plane sets no
 * `SO_SNDBUF`, so the kernel sizes and autotunes it from the system's `tcp_wmem`, and a write that returned is counted
 * sent. The plane signals each drain itself (`outboundDrained`), so no rate is given.
 */
internal object NanLinkStamp {
    fun frame(
        frame: InboundFrame,
        link: FramedLink,
    ): InboundFrame = frame.copy(proven = link.proven, link = link)

    fun digest(
        digest: ReceivedDigest,
        link: FramedLink,
        current: Boolean,
    ): ReceivedDigest = digest.copy(proven = link.proven && current, link = link)

    /** The live path's token for [nodeId] in [peers], whose entries [linkOf] unwraps; null with no path. */
    fun <L : Any> token(
        peers: Map<String, L>,
        nodeId: String,
        linkOf: (L) -> FramedLink,
    ): Any? = peers[nodeId]?.let(linkOf)

    /** Whether the live path for [nodeId] in [peers] proved the peer's id ([FramedLink.proven]); false with no path. */
    fun <L : Any> proven(
        peers: Map<String, L>,
        nodeId: String,
        linkOf: (L) -> FramedLink,
    ): Boolean = peers[nodeId]?.let(linkOf)?.proven == true

    /**
     * The live path for [nodeId] as a [LinkCarrier] of [kind]: its token, its proof and what it holds ([outbound]) from one
     * read; null with no path.
     */
    fun <L : Any> carrier(
        peers: Map<String, L>,
        nodeId: String,
        kind: TransportKind,
        linkOf: (L) -> FramedLink,
    ): LinkCarrier? = peers[nodeId]?.let(linkOf)?.let { LinkCarrier(kind, it, it.proven, outbound(it)) }

    /**
     * What [link] holds for its peer: [FramedLink.queuedBytes] ([LinkOutbound.NOT_DRAINING] or more once the path is
     * closed or its writer ended), no rate, and whether a file is moving on it either way ([FramedLink.fileMoving]: queued
     * or streaming, and not stalled by the blob plane's rule).
     */
    fun outbound(link: FramedLink): LinkOutbound =
        LinkOutbound(unwritten = link.queuedBytes, drainBytesPerSec = null, fileMoving = link.fileMoving)
}
