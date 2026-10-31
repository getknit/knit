package app.getknit.knit.data

import app.getknit.knit.data.peer.PeerBadgeEntity
import app.getknit.knit.data.peer.PeerDao
import app.getknit.knit.data.peer.PeerEntity
import app.getknit.knit.data.peer.PeerFace
import app.getknit.knit.data.settings.InboundSettings
import app.getknit.knit.identity.IdentitySource
import app.getknit.knit.identity.PeerLabelIndex
import app.getknit.knit.identity.PeerLabels
import app.getknit.knit.mesh.protocol.BadgeEntry
import app.getknit.knit.mesh.protocol.WireEnvelope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Single source of truth for cached peer profiles. [profile] and [identity] contribute this device's own
 * name and id to the name-collision universe ([observeDirectory] / [labelIndex]) — a peer who adopts our
 * name is discriminated too. With a [fade] (production), a stranger quiet for a week stops adding an alias to
 * anyone else's label (ADR 2026-10.k8ge); without one every known identity counts forever, as before.
 */
class PeerRepository(
    private val dao: PeerDao,
    private val profile: InboundSettings,
    private val identity: IdentitySource,
    private val maxPeers: Int = DEFAULT_MAX_PEERS,
    private val fade: LabelFade? = null,
) {
    // The `lastSeenAt` stamps [noteSeen] collected since the last [flushSeen], the newest per sender.
    private val heard = ConcurrentHashMap<String, Long>()

    // The stamp the table holds per node id, as far as this process knows: read from it on the first flush, then
    // kept by every flush, so a stamp inside the gate is skipped before any query.
    private val written = ConcurrentHashMap<String, Long>()
    private var primed = false
    private val flushLock = Mutex()

    fun observePeers(): Flow<List<PeerEntity>> = dao.observeAll()

    /**
     * The peer table with its collision-aware label index (ADR 058), rebuilt on every peer change and on a
     * change of our own display name. The name arm is de-duplicated on purpose: `SettingsStore.displayName`
     * re-emits on every DataStore write (read watermarks, intro state), and each of those would otherwise
     * rebuild every list screen.
     *
     * With a [fade], the strangers whose clash has faded are judged on every input and on the fade's tick, and the
     * index is rebuilt only when the rows, our name or that set actually changed — an anchor edit or a tick that
     * moves nobody across the line costs a comparison, not an index.
     */
    fun observeDirectory(): Flow<PeerDirectory> {
        val myName = profile.displayName.distinctUntilChanged()
        val me = flow { emit(identity.nodeId()) }
        val fade =
            fade ?: return combine(dao.observeAll(), myName, me) { peers, name, id ->
                PeerDirectory(peers, PeerLabels.index(peers.map { it.nodeId to it.name }, id to name))
            }
        return combine(dao.observeAll(), myName, me, fade.anchors.observe(), fade.ticks) { peers, name, id, anchors, _ ->
            val now = fade.clock()
            val stale = peers.filter { fade.fades(it.nodeId, it.verified, it.lastSeenAt, anchors, now) }.mapTo(HashSet()) { it.nodeId }
            DirectoryInputs(peers, id to name, stale)
        }.distinctUntilChanged()
            .map { PeerDirectory(it.peers, PeerLabels.index(it.peers.map { p -> p.nodeId to p.name }, it.self, it.stale)) }
    }

    /**
     * The name and avatar of [ids] alone, as they change — the conversation-shortcut watch's read (ADR 2026-10.jbsa),
     * which must not pay [observeDirectory]'s whole-table read and label rebuild on every profile frame. An empty set
     * is answered without a query, so nothing subscribes to the peers table for it.
     */
    fun observeFaces(ids: Set<String>): Flow<List<PeerFace>> = if (ids.isEmpty()) flowOf(emptyList()) else dao.observeFaces(ids)

    /**
     * A one-shot [PeerLabelIndex] for a suspend path (a notification, a contact-card preview), by the same rule as
     * [observeDirectory] — the fade included, so a notification title never disagrees with the list it opens.
     */
    suspend fun labelIndex(): PeerLabelIndex {
        val rows = dao.namesAll()
        val stale =
            fade?.let { f ->
                val anchors = f.anchors.current()
                val now = f.clock()
                rows.filter { f.fades(it.nodeId, it.verified, it.lastSeenAt, anchors, now) }.mapTo(HashSet()) { it.nodeId }
            }
        return PeerLabels.index(
            rows.map { it.nodeId to it.name },
            identity.nodeId() to profile.displayName.first(),
            stale.orEmpty(),
        )
    }

    /**
     * Notes that a signed frame of [nodeId]'s stamped [at] was accepted (ADR 2026-10.k8ge); the caller has decided
     * the frame is evidence and capped [at] at its clock. Memory only — no query and nothing that throws — because
     * it runs on the inbound path ahead of the relay. [flushSeen] banks it.
     */
    fun noteSeen(
        nodeId: String,
        at: Long,
    ) {
        if (fade == null) return
        if (heard.size >= SEEN_BUFFER_CAP && !heard.containsKey(nodeId)) return
        heard.merge(nodeId, at, ::maxOf)
    }

    /**
     * Banks what [noteSeen] collected, in one transaction: the mesh runs it on its metrics tick and at stop. A stamp
     * within [LabelFade.SEEN_GATE_MS] of the table's is skipped before any query, so a live peer costs a write or
     * two a day, not one a frame. A stamp is remembered whether or not a row took it: a sender with no row yet gets
     * its first stamp from the profile that pins it (`InboundPipeline.storeProfileRow`), not from here.
     */
    suspend fun flushSeen() {
        val fade = fade ?: return
        flushLock.withLock {
            if (heard.isEmpty()) return
            val pending = HashMap<String, Long>()
            for (id in heard.keys.toList()) heard.remove(id)?.let { pending[id] = it }
            if (!primed) {
                dao.namesAll().forEach { written.merge(it.nodeId, it.lastSeenAt, ::maxOf) }
                primed = true
            }
            val due = pending.filter { (id, at) -> written[id].let { held -> held == null || at - held >= LabelFade.SEEN_GATE_MS } }
            if (due.isEmpty()) return
            fade.transact {
                for ((id, at) in due) dao.markSeen(id, at, olderThan = at - LabelFade.SEEN_GATE_MS)
            }
            for ((id, at) in due) written.merge(id, at, ::maxOf)
        }
    }

    fun observe(nodeId: String): Flow<PeerEntity?> = dao.observeByNodeId(nodeId)

    suspend fun find(nodeId: String): PeerEntity? = dao.findByNodeId(nodeId)

    /** The contact whose latest profile names LoRa board [node], newest claim first; null for a stranger's radio. */
    suspend fun findByLoraNode(node: Long): PeerEntity? = dao.findByLoraNode(node)

    suspend fun upsert(peer: PeerEntity) = dao.upsert(peer)

    /**
     * Keeps [wire] — a `profile` frame [nodeId]'s key was just pinned from — as the proof of that key this phone
     * hands on (ADR 2026-09.g64k), unless a copy stamped [sentAt] or later is already held. The caller has
     * already clamped [sentAt] to the skew window: the peer picks it.
     */
    suspend fun recordProfileFrame(
        nodeId: String,
        wire: WireEnvelope,
        sentAt: Long,
    ) = dao.recordProfile(nodeId, wire.signed, wire.sig, sentAt)

    /**
     * Replaces every supporter badge [nodeId] shows with [badges] (docs/SUPPORTER_BADGE.md C-6-2): what a sealed profile
     * that passed the version gate does, an empty list clearing them. Called inside the ctl commit's write transaction,
     * so the delete and the insert land together. The cleartext profile writer never calls this (C-6-3).
     */
    suspend fun replaceBadges(
        nodeId: String,
        badges: List<BadgeEntry>,
    ) {
        dao.deleteBadges(nodeId)
        if (badges.isEmpty()) return
        dao.insertBadges(badges.mapIndexed { i, b -> PeerBadgeEntity(nodeId, i, b.w, b.r) })
    }

    /** Every badge held, unverified: [app.getknit.knit.badge.PeerBadges] verifies them for display. */
    fun observeBadges(): Flow<List<PeerBadgeEntity>> = dao.observeBadges()

    /** [nodeId]'s kept proof of key, wrapped point to point as a key is served; null when none is held. */
    suspend fun profileFrame(nodeId: String): WireEnvelope? =
        dao.profile(nodeId)?.let { WireEnvelope(relay = false, sig = it.sig, signed = it.signed) }

    /**
     * Drops every kept proof of key whose `signed` is longer than [max] bytes (#234): a build before the profile cap
     * stored them, and [profileFrame] would go on serving them to every key request. The pin stays, as a contact card's
     * does without a proof. A function of the row alone; returns how many went.
     */
    suspend fun deleteOversizeProfileFrames(max: Int): Int = dao.deleteProfilesLongerThan(max)

    /**
     * Clears every face a peer shows from a held blob longer than [max] bytes (#234) and returns those hashes, for the
     * caller to release: a build before the photo ceiling adopted them, and a profile photo over it is never shown. The
     * peer keeps its pin and shows its monogram until it advertises another photo.
     */
    suspend fun clearAvatarsLongerThan(max: Long): List<String> =
        dao.avatarsLongerThan(max).also { if (it.isNotEmpty()) dao.clearAvatars(it) }

    /**
     * Drops the row a device pinned for *itself* — `nodeId` is our own — if one exists. A node never pins its
     * own key (`InboundPipeline.handleProfile` refuses its own profile), but builds before 2026-09-13 did when
     * their own profile looped back, and the row made every seal-to-a-pinned-peer path treat us as a peer.
     * Run once at mesh start; a no-op on a clean device. Returns whether a row was removed.
     */
    suspend fun forgetSelf(nodeId: String): Boolean {
        if (dao.findByNodeId(nodeId) == null) return false
        dao.delete(nodeId)
        dao.deleteOrphanProfiles()
        dao.deleteOrphanBadges()
        return true
    }

    /** Marks (or clears) the user's out-of-band verification of this peer's pinned key. */
    suspend fun setVerified(
        nodeId: String,
        verified: Boolean,
    ) = dao.setVerified(nodeId, verified)

    /** Node ids the user has out-of-band verified — exempt from [sweepCap] and the message-request queue. */
    suspend fun verifiedNodeIds(): List<String> = dao.verifiedNodeIds()

    /**
     * Bounds the `peers` table (any valid inbound profile upserts a row, uncapped) against a Sybil profile
     * flood: evicts the oldest-by-`updatedAt` **unverified** peers beyond [cap] that aren't [protected]
     * (verified, an accepted/known conversation id, or a peer the user has messaged — group ids / the Nearby id
     * in that set simply match no peer row, harmlessly). A dropped row only sheds a cached profile + pinned key,
     * which re-arrives / re-fetches (KeyExchange) on the peer's next frame — cheap, so keep [cap] high.
     *
     * Returns the node ids it evicted, so the owner drops what it keeps of them in memory too (`KeyExchange.forget`): a
     * proof of key held past its pin is what this bound exists to shed. An id verified between the read and the delete
     * stays and may still be named; dropping its cached proof costs one read of the pin store.
     */
    suspend fun sweepCap(
        protected: Set<String>,
        cap: Int = maxPeers,
    ): List<String> {
        val over = dao.countCappable(protected) - cap
        if (over <= 0) return emptyList()
        val evicted = dao.oldestCappable(protected, over)
        if (evicted.isEmpty()) return emptyList()
        evicted.chunked(SQL_CHUNK).forEach { dao.deleteCappable(it) }
        dao.deleteOrphanProfiles() // the proof of key goes with the pin it proves
        dao.deleteOrphanBadges()
        return evicted
    }

    /** One judgement of [observeDirectory]'s inputs; equal judgements build one index. */
    private data class DirectoryInputs(
        val peers: List<PeerEntity>,
        val self: Pair<String, String>,
        val stale: Set<String>,
    )

    private companion object {
        /** High, conservative ceiling on cached peer rows — a Sybil profile-flood backstop, not a routine bound. */
        const val DEFAULT_MAX_PEERS = 2_000

        /** Senders [noteSeen] holds between flushes; a minute's flood of fresh identities past it waits a tick. */
        const val SEEN_BUFFER_CAP = 1_024

        /** Ids per `IN (…)` delete: under SQLite's bound-parameter limit, whatever a flood made the overshoot. */
        const val SQL_CHUNK = 500
    }
}
