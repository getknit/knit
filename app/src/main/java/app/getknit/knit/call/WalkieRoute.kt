package app.getknit.knit.call

import app.getknit.knit.mesh.protocol.Protocol

/**
 * When a call is placed as a walkie (§3.11, C-3.11-3): the [CallWalkie] the app runs. The peer must run one, and a live
 * call must look impossible: the peer takes none (a build may take mesh calls only, ADR 2026-10.swvp), or there is no
 * Internet route here (no spool, no STUN or TURN) and [direct] would offer no Wi-Fi Direct leg (C-3.10-1). A wrong guess
 * costs the call: a live call with no path ends `no_route`, since the drop to a walkie is parked
 * (`CallManager.dropToWalkie`). Pure: the peer's pinned capabilities, the Internet route and the cast ticket are seams.
 */
class WalkieRoute(
    // What [peerId]'s pinned profile carries; 0 for a peer this phone has none of.
    private val capabilities: suspend (peerId: String) -> Long,
    // A validated Internet route this instant (`InternetGate.isOnline`).
    private val online: () -> Boolean,
    private val direct: CallDirect,
    private val ticketed: () -> Boolean = { false },
) : CallWalkie {
    override val enabled = true

    override suspend fun capable(peerId: String) = capabilities(peerId) and Protocol.CAP_WALKIE != 0L

    override suspend fun liveLikely(peerId: String): Boolean {
        if (capabilities(peerId) and Protocol.CAP_CALL == 0L) return false
        if (online()) return true
        // The leg's own answer, not a copy of its gates: a copy missed a switched-off Wi-Fi radio (#63).
        return direct.canOffer(peerId)
    }

    override fun castTicketed() = ticketed()
}
