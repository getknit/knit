package app.getknit.knit.mesh.bluetooth

/**
 * When the Bluetooth transport enables its presence set again (#112). The stack disables its advertising sets around
 * each connection it makes or takes and enables them after, and the controller can refuse that enable — 0x0d, limited
 * resources, beside a new link at a 15 ms interval. The app hears of the refusal only when the re-enable followed a
 * connection *to the set*; one around a connection the phone made itself is silent. The stack never retries either, so
 * the presence advert stayed dark until its next pause and resume, 10 to 60 s on the Pixel 7, while an iPhone could
 * neither find the phone nor dial it.
 *
 * [BleAdvertiser.reassert] enables the live set again; this decides when: [SETTLE_MS] after any connection opens or
 * closes, on a doubling wait after a refusal the stack did report, and every [ALONE_PERIOD_MS] (no link) or
 * [LINKED_PERIOD_MS] for a refusal it did not. Pure, so the schedule is a JVM test
 * ([app.getknit.knit.AdvertReassertPolicyTest]).
 */
internal object AdvertReassertPolicy {
    /**
     * How long after a connection opens or closes the set is enabled again. Long enough for the stack's own pause and
     * resume around it to finish — an enable inside that pause can make the controller refuse the resolving-list write
     * the pause is for — and short beside the 30 s an iPhone waits for a first link.
     */
    const val SETTLE_MS = 2_500L

    /** The wait after a first refusal, doubled for each one after, up to [RETRY_CAP_MS]. */
    const val RETRY_BASE_MS = 2_500L
    const val RETRY_CAP_MS = 20_000L

    /**
     * The net under the edges, for a refusal no callback reports (a silent re-enable, or a controller on the legacy
     * HCI path, whose enable always reports success). Ten seconds while this node holds no link, which is when a
     * newcomer has to find it; a minute while it holds one. Each is one binder call and one HCI command, and the
     * loop's wait never wakes a suspended phone.
     */
    const val ALONE_PERIOD_MS = 10_000L
    const val LINKED_PERIOD_MS = 60_000L

    /** The shortest wait the loop takes, so a deadline it cannot meet yet never spins it. */
    const val MIN_WAIT_MS = 1_000L

    // Past this many doublings the wait is at its cap anyway; it bounds the shift.
    private const val MAX_DOUBLINGS = 16

    /**
     * One set's schedule. Thread-safe: the transport's loop reads it while the stack's binder callbacks and the ACL
     * receiver write it. It never calls out, so a caller may hold its own lock across a call.
     */
    class Keeper(
        private val settleMs: Long = SETTLE_MS,
        private val retryBaseMs: Long = RETRY_BASE_MS,
        private val retryCapMs: Long = RETRY_CAP_MS,
        private val alonePeriodMs: Long = ALONE_PERIOD_MS,
        private val linkedPeriodMs: Long = LINKED_PERIOD_MS,
        private val minWaitMs: Long = MIN_WAIT_MS,
    ) {
        private var lastAt = 0L
        private var edgeAt: Long? = null
        private var retryAt: Long? = null
        private var refusals = 0
        private var refusedSince: Long? = null

        /** A fresh schedule at the transport's start: the set was just brought up, so the net runs from [now]. */
        @Synchronized
        fun start(now: Long) {
            lastAt = now
            edgeAt = null
            retryAt = null
            refusals = 0
            refusedSince = null
        }

        /** A connection opened or closed: enable the set [settleMs] on, unless that is already due sooner. */
        @Synchronized
        fun onConnectionEdge(now: Long) {
            val at = now + settleMs
            edgeAt = edgeAt?.let { minOf(it, at) } ?: at
        }

        /** Enable the set at the loop's next turn (a heal). */
        @Synchronized
        fun dueNow(now: Long) {
            edgeAt = now
        }

        /**
         * The stack refused an enable or a start: retry on the doubling wait. Returns that wait while it is still
         * growing, and once more when it reaches the cap; null for a refusal repeated at the cap, which the caller
         * need not log again.
         */
        @Synchronized
        fun onRefused(now: Long): Long? {
            refusals += 1
            if (refusedSince == null) refusedSince = now
            val wait = backoff(refusals)
            retryAt = now + wait
            return wait.takeIf { refusals == 1 || backoff(refusals - 1) < retryCapMs }
        }

        /** The stack enabled the set (or started it). Returns how long it had been refusing, if it had; else null. */
        @Synchronized
        fun onEnabled(now: Long): Long? {
            refusals = 0
            retryAt = null
            val since = refusedSince
            refusedSince = null
            return since?.let { now - it }
        }

        /** Whether the set is due an enable at [now]. */
        @Synchronized
        fun isDue(
            now: Long,
            linked: Boolean,
        ): Boolean = now >= dueAt(linked)

        /** An enable went out at [now]: the edge and the retry it answered are spent, and the net runs from here. */
        @Synchronized
        fun reasserted(now: Long) {
            lastAt = now
            if (edgeAt?.let { it <= now } == true) edgeAt = null
            if (retryAt?.let { it <= now } == true) retryAt = null
        }

        /** How long the loop may sleep before the next enable is due. */
        @Synchronized
        fun waitMs(
            now: Long,
            linked: Boolean,
        ): Long = (dueAt(linked) - now).coerceAtLeast(minWaitMs)

        private fun dueAt(linked: Boolean): Long {
            val net = lastAt + if (linked) linkedPeriodMs else alonePeriodMs
            return minOf(net, edgeAt ?: Long.MAX_VALUE, retryAt ?: Long.MAX_VALUE)
        }

        private fun backoff(n: Int): Long = minOf(retryBaseMs shl minOf(n - 1, MAX_DOUBLINGS), retryCapMs)
    }
}
