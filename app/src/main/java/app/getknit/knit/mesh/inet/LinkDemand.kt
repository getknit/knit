package app.getknit.knit.mesh.inet

import app.getknit.knit.mesh.everyRound
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Opens direct links on demand, the spool first and a link as the last resort (ADR 2026-10.a5yj, `docs/DIRECT_LINKS.md`
 * L-7-7): every [ROUND_MS] while the link plane runs ([active]), reads which of our recent messages contacts have not
 * ticked ([pending]) and offers a link to each contact [LinkDemandPolicy] finds due, one offer per contact at a time. A
 * contact the links refused before anything went out (not qualified, no cast, no port, off) rests [QUIET_MS], so a round
 * never asks again for what it was just told; a punch that failed rests on the links' own backoff. Thin: the rule is
 * [LinkDemandPolicy], the attempt `DirectLinks`.
 */
@Suppress("LongParameterList") // the seams the rule reads and the one it acts through
class LinkDemand(
    private val pending: suspend (since: Long, until: Long) -> List<LinkDemandPolicy.Pending>,
    private val offer: suspend (peerId: String) -> LinkOutcome,
    private val up: () -> Set<String>,
    private val lastUpAt: (peerId: String) -> Long?,
    private val radioLinked: (peerId: String) -> Boolean,
    private val active: () -> Boolean,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val log: (String) -> Unit = {},
) {
    private val offering = ConcurrentHashMap.newKeySet<String>()
    private val quietUntil = ConcurrentHashMap<String, Long>()

    /** Runs a round every [ROUND_MS], the first one interval from now, until the scope ends. */
    fun start(): Job = scope.everyRound(ROUND_MS, onFailure = { log("link demand round failed: ${it.javaClass.simpleName}") }) { round() }

    /** One round: offers each contact due now a link, without waiting for the attempts. */
    suspend fun round() {
        if (!active()) return
        val now = clock()
        val rows = pending(now - LinkDemandPolicy.WINDOW_MS, now - LinkDemandPolicy.TICK_WAIT_MS)
        if (rows.isEmpty()) return
        val due = LinkDemandPolicy.due(rows, now, radioLinked, up(), offering.toSet(), lastUpAt, HashMap(quietUntil))
        due.forEach { peer ->
            if (!offering.add(peer)) return@forEach
            scope.launch {
                try {
                    val outcome = offer(peer)
                    if (outcome in QUIET) quietUntil[peer] = clock() + QUIET_MS
                    log("link demand ${peer.take(SHORT)}: $outcome")
                } finally {
                    offering.remove(peer)
                }
            }
        }
    }

    companion object {
        /** How often the demand is read. */
        const val ROUND_MS = 10_000L

        /** How long a contact the links refused before anything went out rests before the next offer. */
        const val QUIET_MS = 60_000L

        // The refusals that sent nothing, and carry no backoff of their own.
        private val QUIET = setOf(LinkOutcome.NOT_QUALIFIED, LinkOutcome.NO_CAST, LinkOutcome.NO_PORT, LinkOutcome.OFF)
        private const val SHORT = 6
    }
}
