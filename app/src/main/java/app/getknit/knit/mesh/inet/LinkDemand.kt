package app.getknit.knit.mesh.inet

import app.getknit.knit.mesh.everyRound
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Opens direct links on demand, the spool first and a link as the last resort (ADR 2026-10.a5yj, `docs/DIRECT_LINKS.md`
 * L-7-7 and L-7-9): every [ROUND_MS] while the link plane runs ([active]), reads which of our recent messages contacts
 * have not ticked, which of our DM attachments no spool will take, and which DM attachments of theirs we want and nobody
 * brings ([pending], over the last [LinkDemandPolicy.FILE_WINDOW_MS]), and offers a link to each contact
 * [LinkDemandPolicy] finds due, one offer per contact at a time, saying why. A contact that declined (its person has links
 * off, [declinedAt]) is offered again only for something newer. A contact the links refused before anything went out
 * (not qualified, no sig lane, no port, off) rests [QUIET_MS], so a round never asks again for what it was just told; a
 * punch that failed rests on the links' own backoff. Each round also asks every contact a link holds now about our DM
 * attachments to them that no spool takes ([prove], `LinkHolderProof`), ahead of any offer: the link that carried such a
 * photo is the only path its proof can come back on. Thin: the rule is [LinkDemandPolicy], the attempt `DirectLinks`.
 */
@Suppress("LongParameterList") // the seams the rule reads and the one it acts through
class LinkDemand(
    private val pending: suspend (since: Long, until: Long) -> List<LinkDemandPolicy.Pending>,
    private val offer: suspend (peerId: String, why: LinkWhy) -> LinkOutcome,
    private val up: () -> Set<String>,
    private val lastUpAt: (peerId: String) -> Long?,
    private val radioLinked: (peerId: String) -> Boolean,
    private val active: () -> Boolean,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val log: (String) -> Unit = {},
    // The proof round over the contacts a link holds now (`MeshManager.proveStuckOverLinks`); it filters out the radio's.
    private val prove: suspend (linkPeers: Set<String>) -> Unit = {},
    // When each contact last declined an offer of ours (`DirectLinks.declinedAt`, L-2-6).
    private val declinedAt: (peerId: String) -> Long? = { null },
) {
    private val offering = ConcurrentHashMap.newKeySet<String>()
    private val quietUntil = ConcurrentHashMap<String, Long>()

    /** Runs a round every [ROUND_MS], the first one interval from now, until the scope ends. */
    fun start(): Job = scope.everyRound(ROUND_MS, onFailure = { log("link demand round failed: ${it.javaClass.simpleName}") }) { round() }

    /**
     * One round: asks the contacts a link holds now for the proof of a stuck photo ([prove]), then offers each contact due
     * now a link, without waiting for the attempts. The proof goes first: an up contact is never due, so the offer's
     * early exits would skip it.
     */
    suspend fun round() {
        if (!active()) return
        val linked = up()
        if (linked.isNotEmpty()) {
            try {
                prove(linked)
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception, // a failed proof read never costs the round its offers
            ) {
                log("link demand proof round failed: ${e.javaClass.simpleName}")
            }
        }
        val now = clock()
        val rows = pending(now - LinkDemandPolicy.FILE_WINDOW_MS, now)
        if (rows.isEmpty()) return
        val due = LinkDemandPolicy.offers(rows, now, radioLinked, up(), offering.toSet(), lastUpAt, HashMap(quietUntil), declinedAt)
        due.forEach { (peer, why) ->
            if (!offering.add(peer)) return@forEach
            scope.launch {
                try {
                    val outcome = offer(peer, why)
                    if (outcome in QUIET) quietUntil[peer] = clock() + QUIET_MS
                    // A contact waiting out its backoff is asked every round for a day: only an attempt is worth a line.
                    if (outcome != LinkOutcome.BACKOFF) log("link demand ${peer.take(SHORT)} $why: $outcome")
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
        private val QUIET = setOf(LinkOutcome.NOT_QUALIFIED, LinkOutcome.NO_SIG, LinkOutcome.NO_PORT, LinkOutcome.OFF)
        private const val SHORT = 6
    }
}
