package app.getknit.knit.mesh.crypto

import app.getknit.knit.mesh.protocol.LinkSignal
import app.getknit.knit.mesh.protocol.WireCodec

/**
 * The plaintext of a `link` frame (ADR 2026-10.a5yj, experimental): one [LinkSignal]'s CBOR, sealed on the DM ratchet
 * as it is, with no `MessageContent` around it (the walkie chunk's precedent). The one shape check both ends apply, so
 * a sender never spends a chain key on a signal the receiver would refuse:
 *  - [LinkSignal.ph] is an OFFER or an ANSWER;
 *  - [LinkSignal.id] is [LinkSignal.ID_BYTES] and [LinkSignal.k] is [LinkSignal.KEY_BYTES], what `LinkKeys.lane` takes;
 *  - at most [MAX_CANDIDATES] candidates, each with a 4- or 16-byte address. Whether a candidate is worth punching
 *    (`PunchPolicy.sane`) is the link layer's call, not this one's.
 *
 * Text is checked as every other peer payload is ([WireCodec.decodeChecked]); a signal carries none today.
 */
object LinkSignals {
    /** Far more than a phone has addresses worth offering; keeps the sealed cast well inside a spool's 2 KiB bound. */
    const val MAX_CANDIDATES = 16

    private const val V4_BYTES = 4
    private const val V6_BYTES = 16

    /** [signal]'s plaintext, or null when it is not a signal [decode] would take. */
    fun encode(signal: LinkSignal): ByteArray? = if (valid(signal)) WireCodec.encodePayload(signal) else null

    /** The signal a `link` frame's opened [plaintext] carries, or null when it is not a well-formed one. */
    fun decode(plaintext: ByteArray): LinkSignal? = WireCodec.decodeChecked<LinkSignal>(plaintext)?.takeIf(::valid)

    private fun valid(signal: LinkSignal): Boolean =
        (signal.ph == LinkSignal.PHASE_OFFER || signal.ph == LinkSignal.PHASE_ANSWER) &&
            signal.id.size == LinkSignal.ID_BYTES &&
            signal.k.size == LinkSignal.KEY_BYTES &&
            signal.c.size <= MAX_CANDIDATES &&
            signal.c.all { it.a.size == V4_BYTES || it.a.size == V6_BYTES }
}
