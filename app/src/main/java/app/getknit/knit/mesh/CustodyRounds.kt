package app.getknit.knit.mesh

/**
 * One live custody row as an answer reads it, without its bytes ([ForwardStore.liveIndex]): what orders the serve tiers
 * and what a round's byte budget charges, so a round loads only the frames it sends (the custody-carry-policy contract's
 * §1 and §2: an empty digest would otherwise decode up to a thousand rows of 60 KiB). [cost] is the frame's encoded
 * `WireEnvelope(sig, signed)`, as the link writes it ([wireCost]).
 */
data class IndexedFrame(
    val id: String,
    val type: String,
    val senderId: String,
    val recipientId: String?,
    val groupId: String?,
    val sentAt: Long,
    val receivedAt: Long,
    val cost: Int,
) {
    companion object {
        /**
         * The encoded length of a re-served `WireEnvelope(sig = …, signed = …)` with [sigBytes] and [signedBytes]: the
         * fresh wrapper custody stamps (full ttl, hops 0, relayed) leaves every default out (`encodeDefaults = false`), so
         * the CBOR is a two-entry map of the keys `sig` and `signed` and their byte strings. A pure function of the two
         * lengths, pinned against `WireCodec.encodeWire` in a test, so the index needs no bytes to charge a round.
         */
        fun wireCost(
            sigBytes: Int,
            signedBytes: Int,
        ): Int = MAP_HEADER + KEY_SIG + byteStringHeader(sigBytes) + sigBytes + KEY_SIGNED + byteStringHeader(signedBytes) + signedBytes

        // CBOR's major-type head for a byte string of [length]: one byte under 24, then one, two or four length bytes.
        private fun byteStringHeader(length: Int): Int =
            when {
                length < CBOR_INLINE -> 1
                length <= UBYTE_MAX -> 2
                length <= USHORT_MAX -> HEAD_U16
                else -> HEAD_U32
            }

        private const val MAP_HEADER = 1
        private const val KEY_SIG = 1 + 3 // a text string head and "sig"
        private const val KEY_SIGNED = 1 + 6 // a text string head and "signed"
        private const val CBOR_INLINE = 24
        private const val UBYTE_MAX = 0xFF
        private const val USHORT_MAX = 0xFFFF
        private const val HEAD_U16 = 1 + 2
        private const val HEAD_U32 = 1 + 4
    }
}

/**
 * One plane's round of a custody answer (the custody-carry-policy contract's §2, release on drain): at most [bytes] a
 * round, the first pick going whatever its size, and the next cut as soon as the link that carries the answer holds less
 * than [bytes] unwritten for the peer ([LinkOutbound.unwritten]). No clock paces it (the maintainer's ruling of
 * 2026-11-20: custody must not cap how much or how fast linked phones sync); [bytes] is also the line a hand-off is
 * credited under (§3).
 */
class RoundPlan(
    val bytes: Int,
) {
    /**
     * How long until a link holding [out] may hold under this round, at most [CustodyPace.SIGNAL_FALLBACK_MS]: the plane's
     * estimate, (unwritten − round) at [LinkOutbound.drainBytesPerSec], where it gives a rate; else the fallback beside the
     * link's own drain signal ([MeshTransport.outboundDrained]: a plane that gives no rate signals, as [LinkOutbound]
     * says). Never under a millisecond, so a look on an estimate never spins.
     */
    fun lookIn(out: LinkOutbound): Long {
        val rate = out.drainBytesPerSec?.takeIf { it > 0 } ?: return CustodyPace.SIGNAL_FALLBACK_MS
        val over = out.unwritten - bytes + 1
        if (over > Long.MAX_VALUE / MS_PER_SECOND) return CustodyPace.SIGNAL_FALLBACK_MS
        return ((over * MS_PER_SECOND + rate - 1) / rate).coerceIn(1L, CustodyPace.SIGNAL_FALLBACK_MS)
    }

    private companion object {
        const val MS_PER_SECOND = 1_000L
    }
}

/**
 * The round each plane's answers are cut to (§2), chosen by the plane that carries the round, never the one the digest
 * crossed: Bluetooth whenever a Bluetooth link holds the peer ([MeshTransport.carrier]). A plane of any other kind (the
 * lab's, a fake's) is cut as Bluetooth is. [CONTRACT] is production's table.
 */
class CustodyPace(
    val bluetooth: RoundPlan,
    val wifiAware: RoundPlan,
    val directLink: RoundPlan,
) {
    /** The round of [kind], the carrying plane. */
    fun plan(kind: TransportKind): RoundPlan =
        when (kind) {
            TransportKind.WifiAware -> wifiAware
            TransportKind.DirectLink -> directLink
            else -> bluetooth
        }

    companion object {
        /** Bluetooth's round, and the line its link signals a drain under (`FramedLink`'s `drainLine`). */
        const val BLUETOOTH_ROUND_BYTES = 16_384

        /** Wi-Fi Aware's round (Android only). */
        const val WIFI_AWARE_ROUND_BYTES = 524_288

        /** A contact's direct link's round (Android only). */
        const val DIRECT_LINK_ROUND_BYTES = 262_144

        /**
         * How often custody looks at a link that neither signals a drain ([MeshTransport.outboundDrained]) nor gives a
         * drain rate to estimate from (§2's `ROUND_CHECK_MS`), and at a link a moving file holds: 100 ms, under what any
         * radio writes of a round, so the look is never what limits the link.
         */
        const val ROUND_CHECK_MS = 100L

        /**
         * The look beside a drain signal or an estimate: a signal missed, or an estimate whose rate moved, costs at most
         * this. A look reads the link's count before it touches the store, so it costs no index read while the link is full.
         * Android's own choice, not the contract's (knit-ios looks every [ROUND_CHECK_MS] and has no signal).
         */
        const val SIGNAL_FALLBACK_MS = 1_000L

        /** The contract's table (§2), identical on every Android build; knit-ios mirrors the Bluetooth row. */
        val CONTRACT =
            CustodyPace(
                bluetooth = RoundPlan(BLUETOOTH_ROUND_BYTES),
                wifiAware = RoundPlan(WIFI_AWARE_ROUND_BYTES),
                directLink = RoundPlan(DIRECT_LINK_ROUND_BYTES),
            )
    }
}
