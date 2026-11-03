package app.getknit.knit.badge

import app.getknit.knit.data.peer.PeerBadgeEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest

/**
 * The badges every surface draws (docs/SUPPORTER_BADGE.md C-3-3, ADR 2026-10.r99s): the stored rows, verified against
 * [keys] at the local clock — on every change of the rows, and again at the next moment one of them would enter or
 * leave its window, so a badge lapses on a phone that has been offline since it arrived. Nothing here logs a peer.
 *
 * One instance per process, shared on [scope] while any screen collects it.
 */
class PeerBadges(
    rows: Flow<List<PeerBadgeEntity>>,
    scope: CoroutineScope,
    // Defaulted to the production pins: the DI passes this build's own list (`BadgeKeys.forBuild`).
    keys: List<PinnedBadgeKey> = BadgeKeys.forBuild(debug = false),
    clock: () -> Long = { System.currentTimeMillis() },
    private val maxSleepMs: Long = MAX_SLEEP_MS,
) {
    /** Each peer's verified badges in its own order, by node id; a peer with none is absent. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val shown: StateFlow<Map<String, List<VerifiedBadge>>> =
        rows
            .transformLatest { held ->
                while (true) {
                    val nowMs = clock()
                    emit(verifyAll(held, nowMs / MS_PER_S, keys))
                    // A delay does not count the time a phone sleeps, so the wait is capped and the clock re-read.
                    val next = nextBoundaryS(held, nowMs / MS_PER_S) ?: return@transformLatest
                    delay((next * MS_PER_S - nowMs).coerceIn(MIN_SLEEP_MS, maxSleepMs))
                }
            }.distinctUntilChanged()
            .stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyMap())

    /** [nodeId]'s badge ids, for a surface that reads one peer. */
    fun idsOf(
        shown: Map<String, List<VerifiedBadge>>,
        nodeId: String?,
    ): List<String> = nodeId?.let { shown[it] }?.map { it.b }.orEmpty()

    companion object {
        const val MAX_SLEEP_MS = 15 * 60 * 1_000L
        private const val MIN_SLEEP_MS = 1_000L
        private const val STOP_TIMEOUT_MS = 5_000L
        private const val MS_PER_S = 1_000L

        /** Every row verified at [nowS], grouped by node id in each sender's order. Pure. */
        fun verifyAll(
            held: List<PeerBadgeEntity>,
            nowS: Long,
            keys: List<PinnedBadgeKey>,
        ): Map<String, List<VerifiedBadge>> =
            held
                .sortedWith(compareBy({ it.nodeId }, { it.position }))
                .mapNotNull { row -> Badges.verify(row.wrapper, row.r, row.nodeId, nowS, keys)?.let { row.nodeId to it } }
                .groupBy({ it.first }, { it.second })
                .mapValues { (_, badges) -> badges.distinctBy { it.b } }

        /**
         * The next second past [nowS] at which a held badge could enter its window (`nbf - 300`) or leave it
         * (`exp + 300`), or null when none ever will. Read from the claims alone: it only schedules the next check.
         */
        fun nextBoundaryS(
            held: List<PeerBadgeEntity>,
            nowS: Long,
        ): Long? =
            held
                .mapNotNull { Badges.claimsOf(it.wrapper) }
                .flatMap { listOf(it.nbf - Badges.SKEW_S, it.exp + Badges.SKEW_S) }
                .filter { it > nowS }
                .minOrNull()
    }
}
