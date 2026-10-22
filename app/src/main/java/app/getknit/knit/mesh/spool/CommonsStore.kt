package app.getknit.knit.mesh.spool

import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.mesh.CarriedFrame
import app.getknit.knit.mesh.protocol.CommonsPost
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.WireCodec

/** One joined commons as the plane sees it: where it lives, what unlocks it, what to call it. */
class CommonsRoom(
    val conversationId: String,
    val spoolUrl: String,
    val secret: ByteArray,
    val name: String?,
)

/**
 * Persistence seam for the commons — the spool plane's shared rooms (docs/SPOOL_PROTOCOL.md §7.4) —
 * abstracted like [app.getknit.knit.mesh.ForwardStore] so [ScopeSync] and `MeshManager` stay free of
 * Android/Room and run against an in-memory double in unit tests. The app's implementation is
 * `data/commons/CommonsRepository`, backed by the encrypted database.
 *
 * Three things live behind it, and the split is the design: the **rooms** (the invite secret each scope
 * derives from, bound to the one relay that runs it), the **outbox** (our own posts as the exact signed
 * bytes, because the deterministic seal must reproduce the same blob on every heal round and a post is
 * deliberately never custodied — custody is folded into the mesh digest, and a room only some nodes are in
 * can never be), and the **members** (every node whose frame this device has pulled from the room — the
 * roster a commons has instead of a pinned one).
 */
interface CommonsStore {
    /** Every joined room's derivation input, for the scope table. */
    suspend fun roots(): List<CommonsRoots>

    suspend fun find(conversationId: String): CommonsRoom?

    /** Our own posts in [conversationId] still worth pushing, as the frames they were signed as. */
    suspend fun frames(conversationId: String): List<CarriedFrame>

    /** Stores our own post: the message row and its signed bytes, in one transaction. */
    suspend fun post(
        row: MessageEntity,
        sig: ByteArray,
        signed: ByteArray,
    )

    /**
     * Stores our own reaction to [messageId] in [conversationId] as the frame it was signed as, and drops from the
     * outbox our earlier reaction to the same post ([commonsReactionTarget]): it is superseded latest-stamp-wins on
     * every reader (C-7.4-13), so pushing it again could only spend the room's frames. No message row: a reaction
     * lives in the reactions table, which the caller has already written.
     */
    suspend fun postReaction(
        conversationId: String,
        messageId: String,
        frameId: String,
        sentAt: Long,
        sig: ByteArray,
        signed: ByteArray,
    )

    /**
     * The attachments other members' posts in [conversationId] name and this device lacks, sent since [since], newest
     * first, at most [limit], none of [skip] (spec §7.4). A received post is never held, so the room's attachment pass
     * reads what to fetch from here, not from a frame; our own come from [frames], and are the only ones it pushes.
     */
    suspend fun attachmentRefs(
        conversationId: String,
        selfId: String,
        since: Long,
        skip: Set<String>,
        limit: Int,
    ): List<ScopeAttachments.Ref> = emptyList()

    /** Notes that [nodeId]'s frame was pulled from [conversationId] — idempotent, stamps the latest sighting. */
    suspend fun recordMember(
        conversationId: String,
        nodeId: String,
        now: Long,
    )

    /** Every member across every joined room, for the DM-bootstrap sweep. */
    suspend fun allMembers(): List<CommonsMember>

    /**
     * Whether [nodeId] is already a recorded member of [conversationId] — the first-sighting test that makes
     * a member a contact once, so a removal sticks across re-pulls (ADR 2026-09.adgd).
     */
    suspend fun isMember(
        conversationId: String,
        nodeId: String,
    ): Boolean = allMembers().any { it.conversationId == conversationId && it.nodeId == nodeId }

    /** Drops outbox rows older than [before] — the spool has long expired them. */
    suspend fun sweepOutbox(before: Long)
}

/** A node seen in a commons. */
class CommonsMember(
    val conversationId: String,
    val nodeId: String,
    val seenAt: Long,
)

/** The post a signed commons frame reacts to, when it is a reaction (C-7.4-13); null for a post or anything else. */
fun commonsReactionTarget(signed: ByteArray): String? {
    val env = WireCodec.decodeEnvelope(signed)?.takeIf { it.type == FrameType.COMMONS } ?: return null
    return WireCodec.decodePayload<CommonsPost>(env.payload)?.reaction?.messageId
}
