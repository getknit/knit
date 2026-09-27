package app.getknit.knit.data

import app.getknit.knit.data.peer.PeerDao
import app.getknit.knit.data.peer.PeerEntity
import app.getknit.knit.data.settings.InboundSettings
import app.getknit.knit.identity.IdentitySource
import app.getknit.knit.identity.PeerLabelIndex
import app.getknit.knit.identity.PeerLabels
import app.getknit.knit.mesh.protocol.WireEnvelope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow

/**
 * Single source of truth for cached peer profiles. [profile] and [identity] contribute this device's own
 * name and id to the name-collision universe ([observeDirectory] / [labelIndex]) — a peer who adopts our
 * name is discriminated too.
 */
class PeerRepository(
    private val dao: PeerDao,
    private val profile: InboundSettings,
    private val identity: IdentitySource,
    private val maxPeers: Int = DEFAULT_MAX_PEERS,
) {
    fun observePeers(): Flow<List<PeerEntity>> = dao.observeAll()

    /**
     * The peer table with its collision-aware label index (ADR 058), rebuilt on every peer change and on a
     * change of our own display name. The name arm is de-duplicated on purpose: `SettingsStore.displayName`
     * re-emits on every DataStore write (read watermarks, intro state), and each of those would otherwise
     * rebuild every list screen.
     */
    fun observeDirectory(): Flow<PeerDirectory> =
        combine(
            dao.observeAll(),
            profile.displayName.distinctUntilChanged(),
            flow { emit(identity.nodeId()) },
        ) { peers, myName, me ->
            PeerDirectory(peers, PeerLabels.index(peers.map { it.nodeId to it.name }, me to myName))
        }

    /** A one-shot [PeerLabelIndex] for a suspend path (a notification, a contact-card preview). */
    suspend fun labelIndex(): PeerLabelIndex =
        PeerLabels.index(
            dao.namesAll().map { it.nodeId to it.name },
            identity.nodeId() to profile.displayName.first(),
        )

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

    /** [nodeId]'s kept proof of key, wrapped point to point as a key is served; null when none is held. */
    suspend fun profileFrame(nodeId: String): WireEnvelope? =
        dao.profile(nodeId)?.let { WireEnvelope(relay = false, sig = it.sig, signed = it.signed) }

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
     */
    suspend fun sweepCap(
        protected: Set<String>,
        cap: Int = maxPeers,
    ) {
        val over = dao.countCappable(protected) - cap
        if (over <= 0) return
        dao.evictOldestCappable(protected, over)
        dao.deleteOrphanProfiles() // the proof of key goes with the pin it proves
    }

    private companion object {
        /** High, conservative ceiling on cached peer rows — a Sybil profile-flood backstop, not a routine bound. */
        const val DEFAULT_MAX_PEERS = 2_000
    }
}
