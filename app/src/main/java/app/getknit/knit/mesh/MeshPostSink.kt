package app.getknit.knit.mesh

/**
 * Delivers a post the bound board heard on its own primary (slot 0) channel into the Meshtastic room —
 * locally, and nowhere else.
 *
 * It is a seam for the same reason [BridgeFrameSource] and [FarPeerFrameSource] are: `mesh/lora/` is pure and
 * imports neither Android nor the data layer, while delivering a post means writing a Room row. The transport
 * decides *whether* a packet is a post ([app.getknit.knit.mesh.lora.LongFastPolicy]); this decides what a
 * message row made of one looks like.
 *
 * Nothing that passes through here is signed, originated, custodied or fanned out. The room is this phone's
 * own window onto its own radio's channel: a phone with no board never sees these posts, and two phones with
 * boards each hear the channel for themselves. That is what makes the room a mirror rather than a bridge, and
 * what keeps a foreign mesh's traffic off Knit's custody digests entirely.
 *
 * Implemented by [MeshManager]. Late-bound, like the two seams above — the transport is constructed first.
 */
interface MeshPostSink {
    /**
     * Delivers one heard post into the room.
     *
     * Idempotent by construction rather than by bookkeeping. The row id is derived from the post
     * ([app.getknit.knit.mesh.protocol.FrameId.forMeshPost]), so the board replaying a packet it queued while
     * the phone was away lands on the row it already wrote (`MessageDao.insertIfAbsent`). Calling it twice
     * for one packet changes nothing.
     */
    suspend fun onPublicPostHeard(post: MeshPost)
}

/**
 * One post heard on the board's primary channel, as the board heard it — what `mesh/lora/LongFastPolicy`
 * produces out of a raw packet and [MeshPostSink] turns into a message row.
 *
 * Node numbers are Meshtastic's unsigned 32 bits, widened to `Long` here at the boundary where they stop
 * being the radio's and start being the row's — the type the origin columns are written against.
 *
 * It lives beside the sink rather than in `mesh/lora/`, where it is produced, because the sink is the seam:
 * the whole point of that package being internal is that nothing above it needs to know a Meshtastic board
 * exists, and a value the seam passes across cannot be one of the things it hides.
 */
data class MeshPost(
    val node: Long,
    val packetId: Long,
    val body: String,
    val name: String?,
    val channel: String?,
    val hops: Int?,
    val snrDeci: Int?,
    val viaMqtt: Boolean,
)

/**
 * The heard-post attribution as it reaches storage: what the channel said about the speaker, and how the post
 * reached this board. [MeshPost] once the packet id has done its one job — deriving the row id — and what is
 * left is exactly what the row keeps.
 *
 * Passed to `InboundPipeline.deliverChat` as one value rather than six loose parameters, for the reason the
 * sealed [app.getknit.knit.mesh.crypto.MessageContent] is: it keeps the ordinary delivery path one shape,
 * with the heard case as a nullable beside it rather than a second body.
 */
data class MeshPostOrigin(
    val node: Long,
    val name: String?,
    val channel: String?,
    val hops: Int?,
    val snrDeci: Int?,
    val viaMqtt: Boolean,
    /**
     * The Knit contact whose profile claims [node] as its bound board, resolved once at ingest and frozen on
     * the row — null for a stranger. A node-number match against a self-asserted profile field, never a
     * signature, so the UI keeps the unverified styling for it.
     */
    val peerId: String? = null,
)

/**
 * How Meshtastic writes a node number: `!` and eight lowercase hex digits, zero-padded (`NodeInfo`'s
 * `User.id`). What every Meshtastic client shows for a node it has no name for, so it is what Knit shows too —
 * a post from a stranger reads the same here as it does there.
 *
 * It lives beside the heard-post types rather than in `mesh/lora/`, where the packets are, because its callers
 * are all on the far side of the seam — the chat list, the bubble, the notification — and none of them may
 * depend on the radio package.
 */
fun meshNodeLabel(node: Long): String = "!%08x".format(node and MESH_NODE_MASK)

/** A Meshtastic node number is 32 unsigned bits; mask so a widened negative can never print sixteen digits. */
private const val MESH_NODE_MASK = 0xFFFFFFFFL
