package app.getknit.knit.data.forward

import app.getknit.knit.mesh.CarrySnapshot

/**
 * A live row of a sender bucket tied for the largest live count: the global cap's candidates
 * ([ForwardDao.largestBucketRows]). Key 1 of the cap's order (the sender's live count, descending, ADR 2026-10.7mqm) is
 * already applied by the query, so only these rows can lose.
 */
data class CapCandidate(
    val id: String,
    val sentAt: Long,
)

/**
 * Which row the global cap evicts among the largest buckets' rows (custody-carry-policy §5, keys 2 to 4): the highest
 * `spread`, then the oldest `sentAt`, then the lowest id compared as unsigned UTF-8 bytes, which is SQLite's `BINARY` and
 * knit-ios's bytewise order. A row many neighbours already carry goes before one only this phone holds.
 *
 * Key 2 only picks *which* row of a largest bucket goes, never how many or from which smaller bucket, so a flood's bucket
 * sizes are what they were under ADR 2026-10.7mqm whatever [CarrySnapshot] says, faked holders included. With no holders
 * recorded every spread is 0 and keys 3 and 4 are that ADR's rule, the same on every node holding the same rows.
 */
object CapEviction {
    /** The row to evict among [candidates], or null when there are none. */
    fun choose(
        candidates: List<CapCandidate>,
        carry: CarrySnapshot,
    ): String? {
        var best: CapCandidate? = null
        var bestSpread = 0
        for (row in candidates) {
            val spread = carry.spread(row.id)
            val current = best
            if (current == null || before(row, spread, current, bestSpread)) {
                best = row
                bestSpread = spread
            }
        }
        return best?.id
    }

    private fun before(
        row: CapCandidate,
        spread: Int,
        other: CapCandidate,
        otherSpread: Int,
    ): Boolean =
        when {
            spread != otherSpread -> spread > otherSpread
            row.sentAt != other.sentAt -> row.sentAt < other.sentAt
            else -> compareBytes(row.id, other.id) < 0
        }

    /** [a] against [b] as unsigned UTF-8 bytes: `String.compareTo` compares UTF-16 units, which orders some ids otherwise. */
    fun compareBytes(
        a: String,
        b: String,
    ): Int {
        val x = a.encodeToByteArray()
        val y = b.encodeToByteArray()
        for (i in 0 until minOf(x.size, y.size)) {
            val d = (x[i].toInt() and BYTE_MASK) - (y[i].toInt() and BYTE_MASK)
            if (d != 0) return d
        }
        return x.size - y.size
    }

    private const val BYTE_MASK = 0xFF
}
