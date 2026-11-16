package app.getknit.knit.mesh.crypto

/**
 * The read mask a receipt carries ([MessageContent.rm], ADR 2026-11.z6jw): bit i says the sender has also read the i-th id
 * of the list the frame acks, as sent and before any dedupe — `listOfNotNull(ack) + acks` on a `CTL_RECEIPT`, `acks`
 * alone on a plain DM's inline acks. Pure; both sides of the wire go through here, pinned by `ReadMaskTest` and the
 * golden vectors.
 *
 * Only the first [MAX_IDS] ids can carry a bit, so a sender keeps a list whose mask has any bit set at or under that.
 * Bits at or past the list's size mean nothing, which is what lets [encode] pick the shorter CBOR spelling.
 */
object ReadMask {
    /** The most ids a mask can speak for: one bit each in a `Long`. */
    const val MAX_IDS = 64

    /** At most this many ids, every mask spelling is one CBOR byte, so the shorter-of form costs nothing in width. */
    const val SHORT_IDS = 4

    /**
     * The mask for [ids] when [read] is the set of them read here, written so its **encoded width is a function of the
     * list's length alone** — a compact ✓✓'s length is visible on the public band, and a signed tick's ciphertext length
     * to every carrier, so a width that moved with the read pattern would tell which were read (review round 1, B1):
     * - up to [SHORT_IDS] ids, the shorter of the plain mask `m` and `m` with every bit past the list set (the smaller
     *   CBOR argument on a tie): every value is one CBOR byte, "all read" `-1`, "none" `0`;
     * - from 5 to 62 ids, `m or (1L shl n)`: the first bit past the list set, so the value lies in `[2^n, 2^(n+1))` and
     *   the width is fixed per n (5–7 ids 2 B, 8–15 3 B, 16–31 5 B, 32–62 9 B); "none read" is `1L shl n`, never `0`;
     * - at 63 ids or more, `m` as it is (no bit past the list left in a `Long`; Kotlin masks a shift count, so
     *   `-1L shl 64` is `-1L`). The one residual: only a 63- or 64-id escalated group batch takes it, custodied and never
     *   on LoRa.
     * The reader ignores every bit at or past the list's size, so all three read alike ([readIn]).
     */
    fun encode(
        ids: List<String>,
        read: Set<String>,
    ): Long {
        val n = minOf(ids.size, MAX_IDS)
        var m = 0L
        for (i in 0 until n) if (ids[i] in read) m = m or (1L shl i)
        return when {
            n == 0 || n >= MAX_IDS - 1 -> {
                m
            }

            n <= SHORT_IDS -> {
                val filled = m or (-1L shl n)
                if (cborArgument(filled) < cborArgument(m)) filled else m
            }

            else -> {
                m or (1L shl n)
            }
        }
    }

    /**
     * Which of [ids] the mask [rm] says were read: bit i tested directly for each `i < min(ids.size, MAX_IDS)` — never
     * through `(1L shl n) - 1`, which is `0` at n = 64. A null mask (an older sender, or one toward an author it does not
     * think reads them) reads nothing.
     */
    fun readIn(
        ids: List<String>,
        rm: Long?,
    ): Set<String> {
        if (rm == null || rm == 0L) return emptySet()
        val n = minOf(ids.size, MAX_IDS)
        val out = HashSet<String>()
        for (i in 0 until n) if ((rm ushr i) and 1L == 1L) out += ids[i]
        return out
    }

    /**
     * The argument a CBOR integer head carries for [v] (major type 0 with `v`, or 1 with `-1 - v`), whose size alone sets
     * the encoded length: under 24 inline, then 1, 2, 4 or 8 more bytes. Never negative.
     */
    private fun cborArgument(v: Long): Long = if (v >= 0) v else -1L - v
}
