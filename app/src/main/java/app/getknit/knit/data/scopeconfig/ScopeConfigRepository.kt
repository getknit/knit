package app.getknit.knit.data.scopeconfig

import androidx.room3.withWriteTransaction
import app.getknit.knit.data.KnitDatabase
import app.getknit.knit.mesh.spool.ScopeConfig
import app.getknit.knit.mesh.spool.ScopeConfigPolicy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest
import kotlinx.serialization.json.Json

/**
 * The scope configs this phone holds (`CTL_SCOPE_CONFIG`, `docs/SPOOL_PROTOCOL.md` §5), per (conversation, issuer,
 * version), and the nodes that have declared they speak the ctl. The rules are `ScopeConfigPolicy`'s; this is where they
 * meet the rows and [clock].
 *
 * Every version is held; a row is read only once it is eligible at [clock] (C-5-4), so the reads — [winning],
 * [observeWinning], [observeWinners] — take the clock, and the flows look again when a deferred row's minute comes,
 * at least every [RECHECK_MS] so a stepped clock is noticed too.
 *
 * [offer] is a read-decide-write, so it runs in one write transaction (`.agents/rules/coding.md`): two configs for one
 * conversation can arrive on two paths at once (the inbound commit and our own send), and the issuer's rows must be
 * read and written as one step. Called from inside the ratchet commit — the outer transaction is already open and the
 * ratchet mutex held — the nested transaction joins it, and nothing here takes a lock of its own, so the order stays
 * transaction outer, mutex inner (`SessionTransactor`).
 */
class ScopeConfigRepository(
    private val db: KnitDatabase,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val dao: ScopeConfigDao get() = db.scopeConfigDao()

    /**
     * Holds [config] against the rows of its conversation and returns what was decided: [ScopeConfigPolicy.Decision.STORE]
     * wrote it and dropped the issuer's rows it supersedes, [ScopeConfigPolicy.Decision.KEEP] left everything (the same
     * config again, or one its issuer's held rows already supersede). Never a refusal, whatever the version.
     */
    suspend fun offer(
        config: ScopeConfig,
        receivedAt: Long,
    ): ScopeConfigPolicy.Decision = db.withWriteTransaction { apply(config, receivedAt).decision }

    /**
     * The sender's half of [offer]: holds [config], a config of this phone's own, in one transaction with the read
     * that says it is news, and returns what it replaced (a [Reservation]) — or null, writing nothing, when it is not
     * news and so not worth a chain key. The row goes in *before* the frame is sealed, so no config of ours is ever on
     * the wire without this phone reading it as its own; a seal that then fails hands the reservation to [release].
     */
    suspend fun reserve(
        config: ScopeConfig,
        at: Long,
    ): Reservation? =
        db.withWriteTransaction {
            val plan = apply(config, at)
            if (plan.decision == ScopeConfigPolicy.Decision.STORE) Reservation(config, plan.drop, at) else null
        }

    /**
     * Undoes a [reserve] whose frame was never sent: the row it wrote goes and the rows it dropped come back — but only
     * while the reserved row is still held, so a later reservation's work is never undone by an earlier one's.
     */
    suspend fun release(reservation: Reservation) {
        db.withWriteTransaction {
            val mine = reservation.config
            val held = dao.configsOf(mine.conversationId).map { it.toConfig() }
            if (mine !in held) return@withWriteTransaction
            dao.deleteRow(mine.conversationId, mine.issuer, mine.version)
            reservation.replaced.forEach { dao.put(it.toEntity(reservation.at)) }
        }
    }

    /** A config of our own [reserve] wrote, and the rows of ours it [replaced]. */
    class Reservation(
        val config: ScopeConfig,
        val replaced: List<ScopeConfig>,
        val at: Long,
    )

    private suspend fun apply(
        config: ScopeConfig,
        at: Long,
    ): ScopeConfigPolicy.Plan {
        val held = dao.configsOf(config.conversationId).map { it.toConfig() }
        val plan = ScopeConfigPolicy.decide(config, held, clock())
        if (plan.decision == ScopeConfigPolicy.Decision.STORE) {
            plan.drop.forEach { dao.deleteRow(it.conversationId, it.issuer, it.version) }
            dao.put(config.toEntity(at))
        }
        return plan
    }

    /** The config [conversationId] runs on now — the winner across its eligible rows — or null when none is. */
    suspend fun winning(conversationId: String): ScopeConfig? =
        ScopeConfigPolicy.winner(dao.configsOf(conversationId).map { it.toConfig() }, clock())

    /** The newest eligible config [issuer] issued for [conversationId], or null: where that issuer says it lives. */
    suspend fun newestOf(
        conversationId: String,
        issuer: String,
    ): ScopeConfig? = ScopeConfigPolicy.winner(dao.configsOf(conversationId).map { it.toConfig() }.filter { it.issuer == issuer }, clock())

    /** [winning] as it changes, by a write or by a deferred row's minute coming. */
    fun observeWinning(conversationId: String): Flow<ScopeConfig?> =
        dao
            .observeConfigsOf(conversationId)
            .map { rows -> rows.map { it.toConfig() } }
            .overTime { rows, now -> ScopeConfigPolicy.winner(rows, now) }

    /** Every conversation's winning config now, by conversation id: what the scope table is placed against. */
    suspend fun winners(): Map<String, ScopeConfig> = winnersOf(dao.all().map { it.toConfig() }, clock())

    /** [winners] as it changes — by a write or by the clock. */
    fun observeWinners(): Flow<Map<String, ScopeConfig>> =
        dao
            .observeAll()
            .map { rows -> rows.map { it.toConfig() } }
            .overTime(::winnersOf)

    private fun winnersOf(
        rows: List<ScopeConfig>,
        now: Long,
    ): Map<String, ScopeConfig> =
        rows
            .groupBy { it.conversationId }
            .mapNotNull { (id, issued) -> ScopeConfigPolicy.winner(issued, now)?.let { id to it } }
            .toMap()

    /**
     * [read] of each emission of rows, and again whenever a held row's minute comes ([ScopeConfigPolicy.eligibleAt]),
     * looked at no less often than every [RECHECK_MS] while one waits; unchanged answers are not re-emitted.
     */
    @OptIn(ExperimentalCoroutinesApi::class) // transformLatest
    private fun <T> Flow<List<ScopeConfig>>.overTime(read: (List<ScopeConfig>, Long) -> T): Flow<T> =
        transformLatest { rows ->
            while (true) {
                val now = clock()
                emit(read(rows, now))
                val next = rows.filterNot { ScopeConfigPolicy.eligible(it, now) }.minOfOrNull { ScopeConfigPolicy.eligibleAt(it) } ?: break
                delay((next - now).coerceIn(1L, RECHECK_MS))
            }
        }.distinctUntilChanged()

    /** Records that [nodeId] sent a config; the first stamp stands. */
    suspend fun noteDeclared(
        nodeId: String,
        at: Long,
    ) = dao.declare(nodeId, at)

    /** Every node that has sent this phone a config. */
    suspend fun declared(): Set<String> = dao.declared().toSet()

    /** [declared] as it changes. */
    fun observeDeclared(): Flow<Set<String>> = dao.observeDeclared().map { it.toSet() }.distinctUntilChanged()

    private fun ScopeConfigEntity.toConfig(): ScopeConfig =
        ScopeConfig(
            conversationId = conversationId,
            groupId = groupId,
            issuer = issuer,
            spools = decodeSpools(spools),
            maxFrames = maxFrames,
            ttlMs = ttlMs,
            maxBlob = maxBlob,
            version = version,
        )

    private fun ScopeConfig.toEntity(receivedAt: Long): ScopeConfigEntity =
        ScopeConfigEntity(
            conversationId = conversationId,
            issuer = issuer,
            groupId = groupId,
            spools = json.encodeToString(spools),
            maxFrames = maxFrames,
            ttlMs = ttlMs,
            maxBlob = maxBlob,
            version = version,
            receivedAt = receivedAt,
        )

    private companion object {
        /** The longest a flow waits before looking at a deferred row again: a clock stepped by NTP is seen within it. */
        const val RECHECK_MS = 60_000L

        val json = Json { ignoreUnknownKeys = true }

        /** A malformed column reads as no relays, which [ScopeConfigPolicy.winner] still orders by version and issuer. */
        fun decodeSpools(stored: String): List<String> =
            runCatching { json.decodeFromString<List<String>>(stored) }.getOrDefault(emptyList())
    }
}
