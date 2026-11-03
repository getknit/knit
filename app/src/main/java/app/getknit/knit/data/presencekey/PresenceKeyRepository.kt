package app.getknit.knit.data.presencekey

import androidx.room3.withWriteTransaction
import app.getknit.knit.data.KnitDatabase
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
 *  - **The read side** ([presenceKeysFor], [ownCurrent], [markStore]) reads; the mark store writes the mark columns
 *    alone.
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
     * The keys of each of [direct] that we hold (up to three each, newest first), less every key byte-equal to another
     * listed contact's or to one of our own (security N7; [PresenceKeyPolicy.listing]). The second value counts what was
     * left out. Read only for direct contacts: a stored key of anyone else is never listed.
     */
    suspend fun presenceKeysFor(direct: Set<String>): Pair<Map<String, List<ContactKey>>, Int> {
        if (direct.isEmpty()) return emptyMap<String, List<ContactKey>>() to 0
        val rows = direct.toList().chunked(SQL_CHUNK).flatMap { dao.peerKeys(it) }
        val byPeer = rows.groupBy { it.nodeId }
        val (listed, excluded) =
            PresenceKeyPolicy.listing(
                byPeer.mapValues { (_, keys) -> keys.map { PresenceKeyPolicy.Held(it.generation, it.key, it.rootTag) } },
                dao.ownKeys().map { it.key },
            )
        val received = rows.associate { (it.nodeId to it.generation) to it.receivedAt }
        return listed
            .mapValues { (peer, keys) -> keys.map { ContactKey(it.generation, it.key, received.getValue(peer to it.generation)) } }
            .filterValues { it.isNotEmpty() } to excluded
    }

    /**
     * Ticks whenever a peer's keys or our own change (the rows' generations, never the keys): with the direct contacts'
     * own flow, what re-reads [presenceKeysFor] for the Bluetooth plane's resolver.
     */
    fun keyChanges(): Flow<List<String>> = dao.observeKeyRows().distinctUntilChanged()

    /** Our own current key — the one the advertiser airs — as (generation, key), or null before the first mint. */
    suspend fun ownCurrent(): Pair<Long, ByteArray>? = PresenceKeyPolicy.keysOf(ownKeys()).current?.let { it.generation to it.key }

    /**
     * [TagSchedule.MarkStore] over the own key rows: the mark of a generation lives in that key's row, written and deleted
     * with it, never in a backup. [TagSchedule.MarkStore.read] throws on a store error and answers null only for a row
     * with no mark (or no row); [TagSchedule.MarkStore.write] throws when the row is gone, so nothing is aired under a
     * mark that did not land. Blocking (the contract's): the advertiser calls it on the transport's worker, never on the
     * main thread. Durable against power loss is the wiring's to add with it (step 5): the row is written in an
     * ordinary WAL commit here.
     */
    internal fun markStore(): TagSchedule.MarkStore =
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
                val written = runBlocking { db.withWriteTransaction { dao.setMark(generation, mark.slot, mark.restart) } }
                check(written == 1) { "no presence key of generation $generation to mark" }
            }
        }

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
    }
}

/** An own key's audience as its row stores it: the node ids, sorted and comma-joined (node ids never hold a comma). */
object PresenceKeyAudience {
    fun encode(ids: Set<String>): String = ids.sorted().joinToString(",")

    fun decode(stored: String): Set<String> = if (stored.isEmpty()) emptySet() else stored.split(',').toSet()
}
