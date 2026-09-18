package app.getknit.knit.mesh.link

import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireEnvelope

/**
 * The per-frame dedup key the Bluetooth plane's carriers share — the side channel's heard set
 * ([app.getknit.knit.mesh.bluetooth.SideCarousel.frameKey]) and the per-link crossing memo ([LinkCrossings]).
 *
 * The signature's first bytes, not the envelope id: a re-seal keeps its id but carries a fresh signature, and
 * the id would suppress it. An unsigned frame (the blob request, the v3 point-to-point tick — ADR 059) has no
 * signature, so it keys on its id under its own namespace, which needs the decoded envelope. The same rule as
 * the LoRa plane's `dedupKey`.
 */
object FrameKey {
    private const val SIG_KEY_BYTES = 8

    /** The key for [wire], whose routing envelope [env] the caller has already decoded. */
    fun of(
        wire: WireEnvelope,
        env: RelayEnvelope,
    ): String = if (wire.sig.isEmpty()) "u:${env.id}" else ofSig(wire.sig)

    /**
     * The key for a wire whose envelope was not decoded, or null when it is unsigned — its id would be needed,
     * and decoding for it is not worth what the key buys on a point-to-point frame.
     */
    fun ofSigned(wire: WireEnvelope): String? = if (wire.sig.isEmpty()) null else ofSig(wire.sig)

    private fun ofSig(sig: ByteArray): String {
        val n = minOf(SIG_KEY_BYTES, sig.size)
        return buildString(n * 2) { for (i in 0 until n) append("%02x".format(sig[i])) }
    }
}
