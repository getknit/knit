package app.getknit.knit.mesh.inet

import app.getknit.knit.mesh.PairView
import app.getknit.knit.mesh.protocol.BlobCancelContent
import app.getknit.knit.mesh.protocol.BlobHasContent
import app.getknit.knit.mesh.protocol.BlobHaveContent
import app.getknit.knit.mesh.protocol.BlobProgressContent
import app.getknit.knit.mesh.protocol.BlobReqContent
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.KeyReqContent
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.spool.ScopeFrames

/**
 * What a direct link may look up about the pair it joins (ADR 2026-10.a5yj): the mesh's half of [LinkPairFilter], so the
 * rule stays pure and the link learns nothing it could keep.
 */
interface LinkScope {
    /** The active groups [peerId] is in with us, by id, each with its founding roster (members and departed). */
    suspend fun sharedGroups(peerId: String): Map<String, Set<String>>

    /**
     * Whom each conversation naming blob [hash] is addressed to — a DM's two ends, a group's members — one set per
     * conversation; empty when none we know names it. Every one: a picture forwarded under its sealed identity (ADR
     * 2026-10.mptm) is named by more than one, and the link carries it when any is the pair's.
     */
    suspend fun addresseeSetsOf(hash: String): List<Set<String>>

    /** Our own avatar's content hash, if we have one. */
    suspend fun ownAvatarKey(): String?

    /** The envelopes of the frames among [ids] this phone carries in custody, by id: what a digest is filtered by. */
    suspend fun custodyEnvelopes(ids: List<String>): Map<String, RelayEnvelope>
}

/**
 * [LinkPairFilter] for one link — [self] to [peer] — over one read of the groups they share ([groups]): a [PairView], so
 * a digest, the frames pushed against it and the link's own drops all read the same snapshot. A blob is admitted when a
 * conversation naming it is addressed to both ends (L-7-3, as the far end checks it too).
 */
class LinkView(
    val self: String,
    val peer: String,
    private val groups: Map<String, Set<String>>,
    private val scope: LinkScope,
) : PairView {
    override fun admits(env: RelayEnvelope): Boolean =
        LinkPairFilter.blobHashes(env) == null && LinkPairFilter.admits(env, self, peer, groups)

    override suspend fun admitsBlob(hash: String): Boolean = scope.addresseeSetsOf(hash).any { self in it && peer in it }

    /** [env] crossing the link one way: a blob control frame by its hashes going out, always coming in; else [admits]. */
    suspend fun admitsFrame(
        env: RelayEnvelope,
        outbound: Boolean,
    ): Boolean {
        val hashes = LinkPairFilter.blobHashes(env) ?: return admits(env)
        return !outbound || hashes.all { admitsBlob(it) }
    }
}

/**
 * The one rule for what crosses a contact's direct link, in both directions (ADR 2026-10.a5yj, `docs/DIRECT_LINKS.md`
 * §7): the pair's own traffic and nothing else. The Nearby room never crosses, nor a stranger's DM this phone carries,
 * nor a group the two do not share — a link is a way for two contacts to reach each other, never a way into either's
 * custody. Pure; [LinkScope] answers the lookups.
 *
 * A frame is admitted when it is
 *  - addressed between the two ([pairAddressed]): a DM, its typing cue, a receipt or a walkie chunk, either way;
 *  - either one's profile, or a ratcheted DM between them ([ScopeFrames.eligibleForDm], the spool's own DM rule);
 *  - a frame of a group both are in, by the spool's group rule against that group's founding roster
 *    ([ScopeFrames.eligibleForGroup]): its chat, updates, leaves, and its members' profiles;
 *  - a frame addressed between two members of such a group ([coMemberAddressed]): what makes a shared group work across
 *    a link — a member's sender-key seed to another, a delivery tick back to a post's author — and, sealed alike and so
 *    never told apart, the members' DMs to each other;
 *  - a key request naming only the two and their shared groups' members;
 *  - a blob control frame ([blobHashes]) whose every hash is addressed to the peer — checked on the way out only: an
 *    ask that comes in serves nothing until the answer goes out, and that is checked.
 *
 * A file goes out only when it is an attachment addressed to the peer, or our own avatar.
 */
object LinkPairFilter {
    /** Whether [env] is addressed from one of [self] and [peer] to the other. */
    fun pairAddressed(
        env: RelayEnvelope,
        self: String,
        peer: String,
    ): Boolean {
        val to = env.recipientId ?: return false
        return (env.senderId == self && to == peer) || (env.senderId == peer && to == self)
    }

    /**
     * Whether [env] concerns only the pair, for every rule that needs no blob lookup: [groups] is [LinkScope.sharedGroups].
     * A blob control frame is never admitted here ([blobHashes] says which hashes to check instead).
     */
    fun admits(
        env: RelayEnvelope,
        self: String,
        peer: String,
        groups: Map<String, Set<String>>,
    ): Boolean =
        pairAddressed(env, self, peer) ||
            ScopeFrames.eligibleForDm(env, self, peer) ||
            groups.any { (id, roster) -> ScopeFrames.eligibleForGroup(env, id, roster) } ||
            coMemberAddressed(env, groups) ||
            keyRequestWithin(env, self, peer, groups)

    /**
     * Whether [env] is addressed between two members of one group in [groups] (founding rosters, which hold both ends of
     * the link): a seed or a tick between co-members, which a sealed frame cannot be told from their DMs to each other.
     */
    fun coMemberAddressed(
        env: RelayEnvelope,
        groups: Map<String, Set<String>>,
    ): Boolean {
        val to = env.recipientId ?: return false
        return env.group == null && groups.values.any { roster -> env.senderId in roster && to in roster }
    }

    // A key request whose every named node is one of the pair or a member of a group they share.
    private fun keyRequestWithin(
        env: RelayEnvelope,
        self: String,
        peer: String,
        groups: Map<String, Set<String>>,
    ): Boolean {
        if (env.type != FrameType.KEY_REQ) return false
        val named = WireCodec.decodePayload<KeyReqContent>(env.payload)?.nodeIds ?: return false
        val known =
            groups.values
                .flatten()
                .toHashSet()
                .apply { add(self) }
                .apply { add(peer) }
        return named.isNotEmpty() && named.all { it in known }
    }

    /** The blob hashes an unsigned blob control frame names, or null when [env] is not one (or does not decode). */
    fun blobHashes(env: RelayEnvelope): List<String>? =
        when (env.type) {
            FrameType.BLOB_REQ -> WireCodec.decodePayload<BlobReqContent>(env.payload)?.let { listOf(it.hash) }
            FrameType.BLOB_HAVE -> WireCodec.decodePayload<BlobHaveContent>(env.payload)?.hashes
            FrameType.BLOB_HAS -> WireCodec.decodePayload<BlobHasContent>(env.payload)?.items?.map { it.hash }
            FrameType.BLOB_PROGRESS -> WireCodec.decodePayload<BlobProgressContent>(env.payload)?.let { listOf(it.hash) }
            FrameType.BLOB_CANCEL -> WireCodec.decodePayload<BlobCancelContent>(env.payload)?.let { listOf(it.hash) }
            else -> null
        }
}
