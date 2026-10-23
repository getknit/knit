package app.getknit.knit.mesh.spool

import app.getknit.knit.mesh.protocol.ScopeConfigPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/** A group as the issuer reads it: its effective members and who created it. */
data class IssuerGroup(
    val groupId: String,
    val members: Set<String>,
    val createdBy: String,
)

/**
 * Tells this phone's peers where its conversations live (ADR 2026-10.8kfx, knit-spool-coordinator ADR 2026-10.txjf):
 * issues the scope configs ([send], which is `MeshManager.sendScopeConfig`) the union rule in [ScopeSpools] calls for.
 * Nothing here is issued while this phone's home is `public-1`, the home of every phone today.
 *
 * - **A DM** gets a config when this phone's home is not one it names ([ScopeSpools.mustIssueDm]): after a move, or
 *   when the peer's config left our home out, and when it still names an old home of ours that drains
 *   ([ScopeSpools.dmNamesStale]). It names the home and the peer's, read from the newest config the peer issued itself
 *   ([peerOwn], [ScopeSpools.dmSpools]).
 * - **A group** gets one when this phone created it while homed off `public-1`, and again when a member rejoins while
 *   a config of this phone's stands: a group's roster is fixed by its id, so a member who comes back is the only one
 *   who can lack the config, and only its issuer re-sends it, once. A rejoin this phone was not running for is
 *   missed: that member is still declared, so until the next config it may sit on `public-1` while the rest left it.
 *   When its relay drains or leaves the directory, any member moves it to
 *   its own home after a random wait up to [GROUP_MOVE_JITTER_MS], skipped if a newer config lands first
 *   ([ScopeSpools.groupMoveTo]).
 * - **Our own config again**, at a version one higher, after a DM root changes (a config sealed in the old era may
 *   never open) and for a conversation [HomeJournal.owed] still lists at start.
 *
 * Every issue is written to [HomeJournal.owed] before it is sent and cleared once a send went out, so a process that
 * dies between `ScopeConfigRepository.reserve` and the flood finishes the job at its next start (ADR 2026-10.m4fk's
 * open window). A send that seals nothing (no confirmed session yet, a member's among them) stays owed and waits
 * [RETRY_MS] or the next root change, whichever comes first: its reservation and release are themselves a change of
 * the winners, and would otherwise wake the next sweep at once.
 *
 * Sweeps run on start, on every change of an input, and every [SWEEP_INTERVAL_MS] as a net under the events. Only
 * while this phone uses the public relays ([ScopeSpools.publicOn]); never on the inbound path.
 */
class ScopeConfigIssuer(
    private val journal: HomeJournal,
    private val selfId: suspend () -> String,
    private val active: Flow<Set<String>>,
    private val winners: Flow<Map<String, ScopeConfig>>,
    // The newest eligible config [issuer] issued for a conversation (`ScopeConfigRepository.newestOf`).
    private val peerOwn: suspend (conversationId: String, issuer: String) -> ScopeConfig?,
    private val groups: Flow<List<IssuerGroup>>,
    // Peers whose DM session root moved (`RatchetSessions.rootChanges`).
    private val rootChanges: Flow<String>,
    // DM peers holding a confirmed session: the only ones a config can be sealed to.
    private val dmPeers: suspend () -> Set<String>,
    private val send: suspend (String, ScopeConfigPayload) -> Boolean,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val jitter: () -> Long = { Random.nextLong(GROUP_MOVE_JITTER_MS) },
    private val log: (String) -> Unit = {},
    private val sweepIntervalMs: Long = SWEEP_INTERVAL_MS,
) {
    private val pokes = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var job: Job? = null

    // The groups and their members at the last sweep, null before the first: a group or a member that appears since is
    // what owes a config. A start takes what it finds as known, so a group created while the app was down owes none.
    private var knownGroups: Map<String, Set<String>>? = null

    // When a group whose relay drains may move, per group id: the jitter that keeps every member from issuing at once.
    private val moveDueAt = HashMap<String, Long>()

    // When a conversation whose last send sealed nothing may be tried again; any root change clears every entry.
    private val retryAt = ConcurrentHashMap<String, Long>()

    fun start(session: CoroutineScope) {
        if (job?.isActive == true) return
        job =
            session.launch {
                launch { changes().collect { poke() } }
                launch { rootChanges.collect { onRootChanged(it) } }
                while (isActive) {
                    val wait = sweep()?.let { (it - clock()).coerceIn(0L, sweepIntervalMs) } ?: sweepIntervalMs
                    withTimeoutOrNull(wait) { pokes.receive() }
                }
            }
    }

    fun stop() {
        job?.cancel()
        job = null
        knownGroups = null
        moveDueAt.clear()
        retryAt.clear()
    }

    /**
     * [peerId]'s DM root moved: a config of ours sealed in the old era may never open, so it is owed again, and every
     * send that sealed nothing may now find a confirmed session.
     */
    suspend fun onRootChanged(peerId: String) {
        if (winners.first()[peerId]?.issuer == selfId()) journal.owe(peerId)
        retryAt.clear()
        poke()
    }

    /** Looks again now. */
    fun poke() {
        pokes.trySend(Unit)
    }

    /** Issues what is due; returns when a group move waiting out its jitter, or a send waiting to retry, is next due. */
    suspend fun sweep(): Long? {
        val active = active.first()
        val home = journal.home.first()
        if (!ScopeSpools.publicOn(active) || home == null) return null
        val me = selfId()
        val now = clock()
        retryAt.values.removeIf { it <= now }
        val winners = winners.first()
        val directory = journal.directory.first()
        val listed = directory.mapTo(HashSet()) { it.host }
        noteGroupChanges(groups.first(), winners, me)
        val owed = journal.owed()
        for (peerId in dmPeers()) {
            val winner = winners[peerId]
            val peerOwn = peerOwn(peerId, peerId)?.spools
            val due =
                ScopeSpools.mustIssueDm(winner?.spools, home) ||
                    ScopeSpools.dmNamesStale(winner?.spools, home, ScopeSpools.peerHome(peerOwn, listed), directory)
            val spools =
                when {
                    due -> ScopeSpools.dmSpools(peerOwn, home, listed)
                    peerId in owed && winner?.issuer == me -> winner.spools
                    else -> null
                }
            if (spools != null) {
                issue(peerId, null, spools, winner, owed, now)
            } else if (peerId in owed) {
                journal.paid(peerId)
            }
        }
        val moves = sweepGroups(me, home, directory, winners, owed, now)
        return (listOfNotNull(moves) + retryAt.values).minOrNull()
    }

    private suspend fun sweepGroups(
        me: String,
        home: String,
        directory: List<PublicSpool>,
        winners: Map<String, ScopeConfig>,
        owed: Set<String>,
        now: Long,
    ): Long? =
        groups
            .first()
            .filter { me in it.members }
            .mapNotNull { sweepGroup(it.groupId, winners[it.groupId], home, directory, owed, now) }
            .minOrNull()

    /** One group's share of [sweepGroups]: what it owes, else a move off a draining relay; when that move is due. */
    private suspend fun sweepGroup(
        id: String,
        winner: ScopeConfig?,
        home: String,
        directory: List<PublicSpool>,
        owed: Set<String>,
        now: Long,
    ): Long? {
        if (id in owed) {
            val spools = winner?.spools ?: listOf(PublicSpools.urlOf(home)).takeIf { home != PublicSpools.LEGACY_HOST }
            if (spools != null) issue(id, id, spools, winner, owed, now) else journal.paid(id)
            return null
        }
        val target = ScopeSpools.groupMoveTo(winner?.spools, home, directory)
        if (target == null) {
            moveDueAt.remove(id)
            return null
        }
        val due = moveDueAt.getOrPut(id) { now + jitter() }
        if (due > now) return due
        moveDueAt.remove(id)
        log("scope config: ${PublicSpools.urlOf(home)} takes a group whose relay drains")
        issue(id, id, target, winner, owed, now)
        return null
    }

    // A group this phone just created owes its config, and so does one a member rejoined while our config stands.
    private suspend fun noteGroupChanges(
        current: List<IssuerGroup>,
        winners: Map<String, ScopeConfig>,
        me: String,
    ) {
        val known = knownGroups
        knownGroups = current.associate { it.groupId to it.members }
        if (known == null) return
        for (group in current) {
            if (me !in group.members) continue
            val before = known[group.groupId]
            val owes =
                if (before == null) {
                    group.createdBy == me
                } else {
                    winners[group.groupId]?.issuer == me && !before.containsAll(group.members)
                }
            if (owes) journal.owe(group.groupId)
        }
    }

    private suspend fun issue(
        conversationId: String,
        groupId: String?,
        spools: List<String>,
        winner: ScopeConfig?,
        owed: Set<String>,
        now: Long,
    ) {
        if ((retryAt[conversationId] ?: 0L) > now) return
        if (conversationId !in owed) journal.owe(conversationId)
        val payload =
            ScopeConfigPayload(
                groupId = groupId,
                spools = spools,
                maxFrames = winner?.maxFrames ?: ScopeRegistry.DEFAULT_MAX_FRAMES,
                ttlMs = winner?.ttlMs ?: ScopeRegistry.DEFAULT_TTL_MS,
                maxBlob = winner?.maxBlob ?: ScopeRegistry.DEFAULT_MAX_BLOB,
                version = ScopeConfigPolicy.nextVersion(winner, now),
            )
        if (send(conversationId, payload)) {
            retryAt.remove(conversationId)
            journal.paid(conversationId)
            log("scope config: issued v${payload.version} naming ${spools.size} relay(s)")
        } else {
            retryAt[conversationId] = now + RETRY_MS
        }
    }

    private fun changes(): Flow<Unit> =
        merge(
            active.map { },
            winners.map { },
            groups.map { },
            journal.home.map { },
            journal.directory.map { },
        )

    companion object {
        /** How long a member may wait before moving a group off a draining relay, so members do not all issue at once. */
        const val GROUP_MOVE_JITTER_MS = 3 * 60 * 1_000L

        /** How long a send that sealed nothing waits before it is tried again, short of a root change. */
        const val RETRY_MS = 10 * 60 * 1_000L

        /** The net under the events: a sweep at least this often. */
        const val SWEEP_INTERVAL_MS = 30 * 60 * 1_000L
    }
}
