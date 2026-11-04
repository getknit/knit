package app.getknit.knit.mesh.bluetooth

/**
 * A fresh L2CAP PSM at every blinding rotation (ADR 2026-11.xp6d's 5b amendment; the maintainer's decision of
 * 2026-11-03). Bytes 21–22 of the presence advert name the responder's PSM, and it otherwise survives every rotation:
 * a scanner that sees tag A at PSM p go off the air and tag B at PSM p come on, milliseconds apart, links the two (the
 * security design pass). The LE dynamic PSM range is 0x80–0xFF, shared by every app on the phone, and AOSP hands PSMs
 * out round-robin from the last one allocated, so a plain re-open is usually p + 1: linkable too. So a rotation first
 * allocates and closes a random number of throwaway servers (0–15, the caller's `SecureRandom` draw), then opens
 * the one it advertises. The residual: 128 values, so it helps in a crowd and little alone.
 *
 * The old server keeps accepting for [graceMs] (one dial timeout, Coded's 25 s watchdog plus margin), so a dial that
 * read the old advert just before it stopped still lands; a socket it accepted outlives its server socket (to verify on
 * the lab phones). The wiring closes the servers [due] hands back once their grace is over.
 *
 * The order is the contract (`PsmRotationTest`): throwaways open and close first, then the new server opens, and only
 * then is the old one handed to its grace; a new server that fails to open leaves the old one current (the advert keeps
 * its PSM rather than naming none). Pure: the opener is passed in; not thread-safe, so the owner guards every call with one monitor.
 */
internal class PsmRotation<S : Any>(
    private val open: () -> S?,
    private val close: (S) -> Unit,
    private val graceMs: Long = GRACE_MS,
) {
    /** The server the advert names now, or null before the first [rotate] or after [closeAll]. */
    var current: S? = null
        private set

    private val retiring = ArrayList<Pair<S, Long>>()

    /**
     * Opens a fresh server behind [throwaways] throwaway allocations (the caller's draw, 0 for a clear bring-up), makes it
     * [current], and hands the old one to its grace (closed by [due] from [now] + [graceMs]). Returns the new server, or
     * null when none opened (then [current] is unchanged).
     */
    fun rotate(
        now: Long,
        throwaways: Int,
    ): S? {
        repeat(throwaways.coerceIn(0, MAX_THROWAWAYS)) { open()?.let(close) }
        val fresh = open() ?: return null
        current?.let { retiring += it to now + graceMs }
        current = fresh
        return fresh
    }

    /** The retired servers whose grace is over at [now], removed: the caller closes each. */
    fun due(now: Long): List<S> {
        val out = retiring.filter { it.second <= now }.map { it.first }
        retiring.removeAll { it.second <= now }
        return out
    }

    /** When the next retired server's grace ends, or null when none is waiting. */
    fun nextDueAt(): Long? = retiring.minOfOrNull { it.second }

    /** Every server, current and retiring, for the caller to close (adapter off, stop); nothing is left. */
    fun closeAll(): List<S> {
        val out = retiring.map { it.first } + listOfNotNull(current)
        retiring.clear()
        current = null
        return out
    }

    companion object {
        /** The most throwaway servers before the advertised one: a draw in 0..[MAX_THROWAWAYS]. */
        const val MAX_THROWAWAYS = 15

        /** How long a retired server keeps accepting: Coded's dial watchdog, the longer one, plus margin. */
        const val GRACE_MS = CodedPhyPolicy.CODED_CONNECT_TIMEOUT_MS + 5_000L
    }
}
