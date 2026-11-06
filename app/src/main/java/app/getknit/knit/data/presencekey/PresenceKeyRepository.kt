package app.getknit.knit.data.presencekey

import android.annotation.SuppressLint
import androidx.room3.Transactor
import androidx.room3.useWriterConnection
import androidx.room3.withWriteTransaction
import app.getknit.knit.data.KnitDatabase
import app.getknit.knit.mesh.MeshMetrics
import app.getknit.knit.mesh.PresenceKeyEngine
import app.getknit.knit.mesh.bluetooth.TagSchedule
import app.getknit.knit.mesh.crypto.PresenceKeyPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.runBlocking

/**
 * The presence-key rows (ADR 2026-11.xp6d): this phone's own keys, the keys its peers handed it, and the read side the
 * Bluetooth plane takes them from (step 5). Never logs a key, an audience or a root tag.
 *
 * Three callers, three lock rules:
 *  - **The key engine** (`mesh/PresenceKeyEngine`) writes the own rows, under its own lock, each write in a transaction
 *    of its own here — never inside another, and never under the ratchet's.
 *  - **The inbound arm** (`InboundPipeline`, a `CTL_PRESENCE_KEY`) reads and writes a peer's keys through [heldBy] and
 *    [applyHeld] inside the ratchet commit that opened the frame: Room only, the caller's transaction, never the
 *    engine's lock.
 *  - **The read side** ([presenceKeysFor], [markStore]) reads; the mark store writes the mark columns alone, outside
 *    any transaction. The advertiser's own key is never read here: it is the engine's published value
 *    (`PresenceKeyEngine.ownKey`), so no tag airs under a key the engine's first settle is about to replace.
 */
class PresenceKeyRepository(
    private val db: KnitDatabase,
) {
    private val dao = db.presenceKeyDao()
    private val owedDao = db.linkOwedResetDao()

    // --- own keys (the engine) ---

    /** Every own key, as the policy reads it. */
    suspend fun ownKeys(): List<PresenceKeyPolicy.Own> = dao.ownKeys().map { it.toOwn() }

    /**
     * Applies one rotation in one transaction: [inserted] are new generations, [updated] move an existing key's role or
     * audience (never its mark), and every own key whose generation is not in [keep] goes.
     */
    suspend fun applyOwn(
        inserted: List<PresenceKeyPolicy.Own>,
        updated: List<PresenceKeyPolicy.Own>,
        keep: Set<Long>,
    ) {
        db.withWriteTransaction {
            updated.forEach { dao.updateOwn(it.generation, it.activatedAt, PresenceKeyAudience.encode(it.audience)) }
            inserted.forEach {
                dao.insertOwn(
                    OwnPresenceKeyEntity(
                        generation = it.generation,
                        key = it.key,
                        mintedAt = it.mintedAt,
                        activatedAt = it.activatedAt,
                        audience = PresenceKeyAudience.encode(it.audience),
                    ),
                )
            }
            dao.deleteOwnExcept(keep.toList())
        }
    }

    // --- peers' keys ---

    /** The keys held for [nodeId], newest first. Inside the caller's transaction on the inbound path. */
    suspend fun heldBy(nodeId: String): List<PresenceKeyPolicy.Held> =
        dao.peerKeys(listOf(nodeId)).map { PresenceKeyPolicy.Held(it.generation, it.key, it.rootTag) }

    /**
     * Writes [rows] as every key held for [nodeId] — the policy's verdict, at most three — inside the caller's
     * transaction (the ratchet commit). A row already held keeps its first [PeerPresenceKeyEntity.receivedAt].
     */
    suspend fun applyHeld(
        nodeId: String,
        rows: List<PresenceKeyPolicy.Held>,
        now: Long,
    ) {
        val before = dao.peerKeys(listOf(nodeId)).associateBy { it.generation }
        dao.deletePeerKeysExcept(nodeId, rows.map { it.generation })
        dao.putPeerKeys(
            rows.map {
                val kept = before[it.generation]?.takeIf { row -> row.rootTag == it.rootTag && row.key.contentEquals(it.key) }
                PeerPresenceKeyEntity(
                    nodeId = nodeId,
                    generation = it.generation,
                    key = it.key,
                    receivedAt = kept?.receivedAt ?: now,
                    rootTag = it.rootTag,
                )
            },
        )
    }

    /** Every key a peer handed us, newest first per peer, without the key bytes: the debug bridge's `KEYS`. */
    suspend fun peerKeyRows(): List<PeerKeyRow> = dao.peerKeyRows()

    /** Every peer we hold a key for. */
    suspend fun peersWithKeys(): List<String> = dao.peersWithKeys()

    /** Deletes every key [nodeIds] handed us (people who stopped being direct contacts), in one transaction. */
    suspend fun deletePeerKeys(nodeIds: Collection<String>) {
        if (nodeIds.isEmpty()) return
        db.withWriteTransaction { nodeIds.forEach { dao.deletePeerKeys(it) } }
    }

    // --- a restore's link-owed resets (the engine) ---

    /** Peer → its kept reset, or null before the first seal. */
    suspend fun linkOwedResets(): Map<String, PresenceKeyEngine.KeptReset?> =
        owedDao.linkOwedResets().associate { row ->
            val wire = row.wire
            val tag = row.rootTag
            row.nodeId to if (wire != null && tag != null) PresenceKeyEngine.KeptReset(wire, tag) else null
        }

    /** Starts a restore's link-owed resets afresh: [peers], none sealed, owed since [now]. */
    suspend fun setLinkOwedResets(
        peers: Set<String>,
        now: Long,
    ) {
        db.withWriteTransaction {
            owedDao.clearLinkOwedResets()
            owedDao.putLinkOwedResets(peers.map { LinkOwedResetEntity(it, wire = null, rootTag = null, owedSince = now) })
        }
    }

    /** Keeps the sealed reset for [peer]; nothing when [peer] is no longer owed one. */
    suspend fun recordLinkOwedReset(
        peer: String,
        kept: PresenceKeyEngine.KeptReset,
    ) {
        db.withWriteTransaction { owedDao.recordLinkOwedReset(peer, kept.wire, kept.rootTag) }
    }

    suspend fun dropLinkOwedReset(peer: String) {
        db.withWriteTransaction { owedDao.dropLinkOwedReset(peer) }
    }

    // --- the read side (step 5's resolver and advertiser) ---

    /** One key a direct contact handed us, as the resolver lists it. */
    class ContactKey(
        val generation: Long,
        val key: ByteArray,
        val receivedAt: Long,
    ) {
        override fun toString(): String = "ContactKey(g=$generation)"
    }

    /**
     * Every key of each of [direct] that we hold (up to three each, newest first). Read only for direct contacts: a stored
     * key of anyone else is never listed. A key equal to another's needs no rule here: a tag is bound to its owner's id
     * ([app.getknit.knit.mesh.crypto.BlindTag]), so a key re-sent as someone else's lists tags of that sender's alone.
     */
    suspend fun presenceKeysFor(direct: Set<String>): Map<String, List<ContactKey>> =
        direct
            .toList()
            .chunked(SQL_CHUNK)
            .flatMap { dao.peerKeys(it) }
            .groupBy({ it.nodeId }) { ContactKey(it.generation, it.key, it.receivedAt) }

    /**
     * Ticks whenever a peer's keys or their era change (each row's generation and arrival, never a key or a root tag: a new
     * era can reuse a generation, and [applyHeld] restamps only a row whose bytes or root moved): with the direct contacts'
     * own flow, what re-reads [presenceKeysFor] for the Bluetooth plane's resolver. Our own keys list nothing there, so they
     * never tick it.
     */
    fun keyChanges(): Flow<List<String>> = dao.observeKeyRows().distinctUntilChanged()

    /**
     * [TagSchedule.MarkStore] over the own key rows: the mark of a generation lives in that key's row, written and deleted
     * with it, never in a backup. [TagSchedule.MarkStore.read] throws on a store error and answers null only for a row
     * with no mark (or no row). Blocking (the contract's): the advertiser calls it on the transport's worker, never on the
     * main thread. [metrics] counts the writes ([MeshMetrics.onPresenceMarkWrite]), the ones that committed at FULL
     * ([MeshMetrics.onPresenceMarkDurable]), the commits refused for not running at FULL
     * ([MeshMetrics.onPresenceMarkNotFull]) and the writes refused because the key was gone or demoted
     * ([MeshMetrics.onPresenceMarkRefused], an expected race with a rotation): the device check the JVM cannot make is
     * `presenceMarkNotFull == 0` with `presenceMarksDurable > 0`. No refusal message names the generation.
     *
     * [TagSchedule.MarkStore.write] (ADR 2026-11.xp6d, step 5b, A3) is durable against power loss and conditional:
     *  1. Never inside a transaction: SQLite refuses to change the safety level inside one, and a write that joined an
     *     enclosing transaction would commit only with it. One there throws at once ([check]).
     *  2. `PRAGMA synchronous=FULL`, outside any transaction. It carries an argument, so the pool sends it to the primary
     *     connection, the one that commits.
     *  3. The write transaction. Its first statement reads `PRAGMA synchronous` and throws unless it is FULL: inside the
     *     transaction it runs on the connection that will commit, so this is the guarantee whatever the pool did with
     *     step 2 (another reader, a reopened primary). Then the mark, only while [generation] is still the current key
     *     ([PresenceKeyDao.setCurrentMark]): a rotation that demoted it (a fresh pair after a removal or a block) leaves 0
     *     rows, which throws, so nothing airs under a key the leaver holds. In WAL mode a FULL commit syncs the log, and
     *     with it every earlier commit not yet synced.
     *  4. Always, `PRAGMA synchronous=NORMAL` again; a failure there is counted and swallowed (the connection stays at
     *     FULL: slower, never unsafe).
     * Not `wal_checkpoint` (a reader holding an old snapshot stops a PASSIVE one; a FULL one blocks on readers), and not
     * a separate synced file (the mark is its key row's). Cost: one fsync per mark write, ~100–200 a day.
     */
    internal fun markStore(metrics: MeshMetrics): TagSchedule.MarkStore =
        object : TagSchedule.MarkStore {
            override fun read(generation: Long): TagSchedule.Mark? =
                runBlocking {
                    val mark = dao.mark(generation) ?: return@runBlocking null
                    val slot = mark.markSlot ?: return@runBlocking null
                    TagSchedule.Mark(slot, mark.markRestart ?: 0)
                }

            override fun write(
                generation: Long,
                mark: TagSchedule.Mark,
            ) {
                metrics.onPresenceMarkWrite()
                check(!inRoomTransaction()) { "a presence-key mark is never written inside a transaction" }
                runBlocking { writeDurably(generation, mark) }
                metrics.onPresenceMarkDurable()
            }

            private suspend fun writeDurably(
                generation: Long,
                mark: TagSchedule.Mark,
            ) = db.useWriterConnection { connection ->
                connection.usePrepared("PRAGMA synchronous=FULL") { it.step() }
                try {
                    val written =
                        connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                            val level = usePrepared("PRAGMA synchronous") { if (it.step()) it.getLong(0) else -1L }
                            if (level != SYNCHRONOUS_FULL) {
                                metrics.onPresenceMarkNotFull()
                                error("the mark's commit would not run at synchronous=FULL")
                            }
                            dao.setCurrentMark(generation, mark.slot, mark.restart)
                        }
                    // Never the generation in the message: a logged "generation N refused" line dates a removal or block.
                    if (written != 1) {
                        metrics.onPresenceMarkRefused()
                        error("the presence key to mark is gone or no longer the current one")
                    }
                } finally {
                    runCatching { connection.usePrepared("PRAGMA synchronous=NORMAL") { it.step() } }
                        .onFailure { metrics.onPresenceMarkSyncRestoreFailed() }
                }
            }
        }

    /**
     * Whether the calling thread runs inside one of Room's suspending transactions: the thread-local Room's own blocking
     * DAO path reads to join one. A blocking call from there that hopped to the query dispatcher would wait for the
     * connection the transaction holds while the transaction waits for it — a wedge, not an error — so [markStore]'s
     * write refuses it first.
     */
    @SuppressLint("RestrictedApi") // the one signal Room offers for "this thread is inside a transaction" (DBUtil.android)
    private fun inRoomTransaction(): Boolean = db.suspendingTransactionContext.get() != null

    private fun OwnPresenceKeyEntity.toOwn() =
        PresenceKeyPolicy.Own(
            generation = generation,
            key = key,
            mintedAt = mintedAt,
            activatedAt = activatedAt,
            audience = PresenceKeyAudience.decode(audience),
            markSlot = markSlot,
        )

    private companion object {
        const val SQL_CHUNK = 500

        /** `PRAGMA synchronous`'s FULL (2); NORMAL is 1. */
        const val SYNCHRONOUS_FULL = 2L
    }
}

/** An own key's audience as its row stores it: the node ids, sorted and comma-joined (node ids never hold a comma). */
object PresenceKeyAudience {
    fun encode(ids: Set<String>): String = ids.sorted().joinToString(",")

    fun decode(stored: String): Set<String> = if (stored.isEmpty()) emptySet() else stored.split(',').toSet()
}
