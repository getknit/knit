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
 * One plane's round of a paced custody answer (the custody-carry-policy contract's §2): at most [bytes] per round, the
 * first pick going whatever its size, and the next round due `max(intervalMs, released × intervalMs / bytes)` after the
 * last. [intervalMs] is also how long after a release its hand-off is credited (§3).
 */
class RoundPlan(
    val bytes: Int,
    val intervalMs: Long,
) {
    /** When the round after one that released [released] bytes at [cutAt] is due: a round past its budget waits longer. */
    fun nextDue(
        cutAt: Long,
        released: Long,
    ): Long = cutAt + maxOf(intervalMs, released * intervalMs / bytes)
}

/**
 * The round each plane's answers are cut to (§2), chosen by the plane that carries the round, never the one the digest
 * crossed: Bluetooth whenever a Bluetooth link holds the peer ([MeshTransport.carrier]). A plane of any other kind (the
 * lab's, a fake's) is paced as Bluetooth is. [CONTRACT] is production's table; [UNPACED] is the lab's default, where a
 * scenario about what crosses, not about the pace, sees every answer whole in its first round.
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
        /** Bluetooth: 16,384 B every 2,000 ms, 8 KiB/s. */
        const val BLUETOOTH_ROUND_BYTES = 16_384
        const val BLUETOOTH_INTERVAL_MS = 2_000L

        /** Wi-Fi Aware (Android only): 524,288 B every 1,000 ms, 512 KiB/s. */
        const val WIFI_AWARE_ROUND_BYTES = 524_288
        const val WIFI_AWARE_INTERVAL_MS = 1_000L

        /** A contact's direct link (Android only): 262,144 B every 1,000 ms, 256 KiB/s. */
        const val DIRECT_LINK_ROUND_BYTES = 262_144
        const val DIRECT_LINK_INTERVAL_MS = 1_000L

        /** The contract's table (§2), identical on every Android build; knit-ios mirrors the Bluetooth row. */
        val CONTRACT =
            CustodyPace(
                bluetooth = RoundPlan(BLUETOOTH_ROUND_BYTES, BLUETOOTH_INTERVAL_MS),
                wifiAware = RoundPlan(WIFI_AWARE_ROUND_BYTES, WIFI_AWARE_INTERVAL_MS),
                directLink = RoundPlan(DIRECT_LINK_ROUND_BYTES, DIRECT_LINK_INTERVAL_MS),
            )

        /** Every answer whole in one round, the next a millisecond later: the lab's default, never production's. */
        val UNPACED = RoundPlan(Int.MAX_VALUE, 1L).let { CustodyPace(it, it, it) }
    }
}
