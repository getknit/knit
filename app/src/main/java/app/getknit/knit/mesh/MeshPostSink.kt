package app.getknit.knit.mesh

/**
 * Publishes a post overheard on the foreign mesh's public channel into Knit as a signed
 * [app.getknit.knit.mesh.protocol.FrameType.MESH_POST] frame (the LongFast bridge's inbound half).
 *
 * It is a seam for the same reason [BridgeFrameSource] and [FarPeerFrameSource] are: `mesh/lora/` is pure and
 * imports neither Android nor the crypto stack, while minting a frame means signing it and delivering it means
 * writing a Room row. The transport decides *whether* a packet is a public post ([app.getknit.knit.mesh.lora.LongFastPolicy]);
 * this decides what a Knit frame made of one looks like.
 *
 * Implemented by [MeshManager]. Late-bound, like the two seams above — the transport is constructed first.
 */
interface MeshPostSink {
    /**
     * Mints, signs and floods one bridged post, and delivers it locally.
     *
     * The frame is ours: we sign it, and our node id is its `senderId`, because the Meshtastic speaker has no
     * Knit identity and nothing on an unauthenticated public channel could be held to one. What the speaker
     * said about itself rides inside the payload as an attribution.
     *
     * Idempotent by construction rather than by bookkeeping. The frame id is derived from the post
     * ([app.getknit.knit.mesh.protocol.FrameId.forMeshPost]), so a second board hearing the same packet mints
     * the same id, and the router's `SeenSet` plus `MessageDao.insertIfAbsent` collapse the copies. Calling it
     * twice for one packet costs a signature and changes nothing.
     */
    suspend fun publishMeshPost(post: MeshPost)
}

/**
 * One post overheard on the foreign mesh's public channel, as the gateway's board heard it — what
 * `mesh/lora/LongFastPolicy` produces out of a raw packet and [MeshPostSink] turns into a signed Knit frame.
 *
 * Node numbers are Meshtastic's unsigned 32 bits, widened to `Long` here at the boundary where they stop
 * being the radio's and start being the wire's — CBOR has no unsigned 32-bit form worth spending, and this is
 * the type both sides of that encoding are written against.
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
 * The bridged-post attribution as it reaches storage: what the foreign mesh said about the speaker, and how
 * the post got to this pocket. [MeshPost] once it has crossed a frame, so the packet id — which has already
 * done its one job, deriving the frame id — is gone, and what is left is exactly what the row keeps.
 *
 * Passed to `InboundPipeline.deliverChat` as one value rather than six loose parameters, for the reason the
 * sealed [app.getknit.knit.mesh.crypto.MessageContent] is: it keeps the ordinary delivery path one shape,
 * with the bridged case as a nullable beside it rather than a second body.
 */
data class MeshPostOrigin(
    val node: Long,
    val name: String?,
    val channel: String?,
    val hops: Int?,
    val snrDeci: Int?,
    val viaMqtt: Boolean,
)

/**
 * How Meshtastic writes a node number: `!` and eight lowercase hex digits, zero-padded (`NodeInfo`'s
 * `User.id`). What every Meshtastic client shows for a node it has no name for, so it is what Knit shows too —
 * a bridged post from a stranger reads the same here as it does there.
 *
 * It lives beside the bridged-post types rather than in `mesh/lora/`, where the packets are, because its
 * callers are all on the far side of a frame — the chat list, the bubble, the notification — and none of them
 * may depend on the radio package.
 */
fun meshNodeLabel(node: Long): String = "!%08x".format(node and MESH_NODE_MASK)

/** A Meshtastic node number is 32 unsigned bits; mask so a widened negative can never print sixteen digits. */
private const val MESH_NODE_MASK = 0xFFFFFFFFL
