package app.getknit.knit.mesh.spool

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Where the home and the directory it was picked from are kept (`data/settings/HomeStore`). */
interface HomeJournal {
    val home: Flow<String?>

    /** When the directory was last read (epoch millis), or 0 for never. */
    suspend fun directoryReadAt(): Long

    /** Picks or keeps the home for [nodeId]; the change as (previous, current), or null for none. */
    suspend fun settle(nodeId: String): Pair<String?, String>?

    suspend fun putDirectory(
        entries: List<PublicSpool>,
        at: Long,
    )

    suspend fun markDirectoryRead(at: Long)
}

/** The one read of the public directory (`net/AndroidPublicSpoolDirectory` on a phone). Never throws. */
fun interface PublicSpoolSource {
    suspend fun fetch(): DirectoryRead
}

sealed interface DirectoryRead {
    /** The document's text, still to go through [PublicSpools.parse]. */
    class Document(
        val text: String,
    ) : DirectoryRead

    /** No document: [reason] is a short word for the log (offline, a status, a size). */
    data class Failed(
        val reason: String,
    ) : DirectoryRead
}

/**
 * Keeps this phone's home relay (ADR 2026-10.8kfx, knit-spool-coordinator ADR 2026-10.txjf): settles it on start, reads
 * the public directory at most once a day while the Internet plane is on ([source] is null in a build that reads none,
 * `BuildConfig.SPOOL_DIRECTORY`), and settles it again after every read, telling [onHomeChanged] when it moved.
 *
 * A fresh install's home comes from the directory it holds, the shipped copy until a read succeeds, so it needs no
 * network. A read that failed is tried again after [RETRY_MS]; one that brought nothing this build reads (an unknown
 * schema) counts as a read, and the held copy stays. The plane off means no read at all: a fresh install with default
 * settings sends nothing (`scripts/egress-check.sh`).
 */
class HomeRefresher(
    private val journal: HomeJournal,
    private val source: PublicSpoolSource?,
    private val selfId: suspend () -> String,
    // Whether the Internet plane runs: some relay is active (`SettingsStore.activeSpoolUrls` non-empty).
    private val planeOn: Flow<Boolean>,
    private val online: () -> Boolean = { true },
    private val routeChanges: Flow<Unit> = emptyFlow(),
    private val onHomeChanged: suspend (previous: String?, current: String) -> Unit = { _, _ -> },
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val log: (String) -> Unit = {},
    private val maxSleepMs: Long = MAX_SLEEP_MS,
) {
    @Volatile
    private var job: Job? = null

    // When the last read failed, so the next try waits RETRY_MS rather than a day. In memory: a restart tries again.
    @Volatile
    private var failedAt = 0L

    fun start(session: CoroutineScope) {
        if (job?.isActive == true) return
        job =
            session.launch {
                while (isActive) {
                    val next = runOnce()
                    val wait = if (next == null) maxSleepMs else (next - clock()).coerceIn(0L, maxSleepMs)
                    withTimeoutOrNull(wait) { wakes().first() }
                }
            }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** Settles the home, reads the directory if a read is due and possible; returns when the next read is due, or null. */
    suspend fun runOnce(): Long? {
        settle()
        val read = source ?: return null
        if (!planeOn.first()) return null
        val due = nextRead()
        return when {
            due > clock() -> {
                due
            }

            !online() -> {
                clock() + maxSleepMs
            }

            else -> {
                read(read)
                nextRead()
            }
        }
    }

    // A day after the last read that brought a document, and never sooner than RETRY_MS after a failed one.
    private suspend fun nextRead(): Long = maxOf(journal.directoryReadAt() + READ_INTERVAL_MS, failedAt + RETRY_MS)

    private suspend fun read(source: PublicSpoolSource) {
        when (val read = source.fetch()) {
            is DirectoryRead.Document -> {
                failedAt = 0L
                val entries = PublicSpools.parse(read.text)
                if (entries == null) {
                    log("public spools: a directory this build does not read; the held copy stays")
                    journal.markDirectoryRead(clock())
                } else {
                    journal.putDirectory(entries, clock())
                    log("public spools: ${entries.size} relays (${entries.count { it.open }} open)")
                    settle()
                }
            }

            is DirectoryRead.Failed -> {
                failedAt = clock()
                log("public spools: no directory (${read.reason})")
            }
        }
    }

    private suspend fun settle() {
        val (previous, current) = journal.settle(selfId()) ?: return
        log("home relay: ${previous ?: "none"} -> $current")
        onHomeChanged(previous, current)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun wakes(): Flow<Unit> =
        merge(
            planeOn.distinctUntilChanged().drop(1).map { },
            planeOn.distinctUntilChanged().flatMapLatest { on -> if (on) routeChanges else emptyFlow() },
        )

    companion object {
        const val READ_INTERVAL_MS = 24 * 60 * 60 * 1_000L
        const val RETRY_MS = 60 * 60 * 1_000L

        /** The longest the loop sleeps without re-reading the clock: a `delay` does not count the time a phone sleeps. */
        const val MAX_SLEEP_MS = 15 * 60 * 1_000L
    }
}
