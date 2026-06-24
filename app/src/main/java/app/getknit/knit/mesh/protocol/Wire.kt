package app.getknit.knit.mesh.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Default hop limit for a flooded frame before relays stop forwarding it. */
const val DEFAULT_TTL: Int = 8

/**
 * A unit of data flooded across the mesh. Every frame carries a globally-unique [id] (the dedup
 * key), a [ttl] hop limit, and the current [hops] count (incremented at each relay).
 *
 * The format is designed once to cover the broadcast-room MVP and future 1:1 DMs + end-to-end
 * encryption: [ChatFrame.recipientId] / [ChatFrame.sig] and [ProfileFrame.pubKey] are reserved now
 * and unused until those phases land.
 */
@Serializable
sealed interface Frame {
    val id: String
    val ttl: Int
    val hops: Int

    /**
     * Whether [MeshRouter] should flood this frame onward to other neighbors. True for the
     * broadcast-room traffic (chat/profile/receipt); false for point-to-point control frames like
     * [BlobRequestFrame] whose propagation is handled hop-by-hop by [MeshManager]. A computed getter
     * (not a constructor property) so it never lands in the serialized wire form.
     */
    val relayable: Boolean get() = true
}

/**
 * A structured "@" mention inside a [ChatFrame.body]. [nodeId] is the canonical 8-char id used for
 * reliable "did this mention me" detection (display names aren't unique); [name] is the exact display
 * name string the sender rendered, so the receiver can locate the "@name" span in the body for
 * highlighting (handles names containing spaces, which a whitespace split would miss). A plain nested
 * `@Serializable` value — never a [Frame] on its own, so no [SerialName] discriminator.
 */
@Serializable
data class Mention(
    val nodeId: String,
    val name: String,
)

/** True when these mentions target [nodeId] (typically the receiver's own id). */
fun List<Mention>.mention(nodeId: String): Boolean = any { it.nodeId == nodeId }

@Serializable
@SerialName("chat")
data class ChatFrame(
    override val id: String,
    val senderId: String,
    val sentAt: Long,
    val body: String,
    val recipientId: String? = null,
    val sig: String? = null,
    val mentions: List<Mention> = emptyList(),
    // Reference to an out-of-band image blob (fetched by content hash); null for text-only messages.
    val attachmentHash: String? = null,
    val attachmentMime: String? = null,
    override val ttl: Int = DEFAULT_TTL,
    override val hops: Int = 0,
) : Frame

@Serializable
@SerialName("profile")
data class ProfileFrame(
    override val id: String,
    val senderId: String,
    val sentAt: Long,
    val name: String,
    val status: String,
    val avatarHash: String? = null,
    val pubKey: String? = null,
    override val ttl: Int = DEFAULT_TTL,
    override val hops: Int = 0,
) : Frame

@Serializable
@SerialName("receipt")
data class ReceiptFrame(
    override val id: String,
    val senderId: String,
    val ackId: String,
    override val ttl: Int = DEFAULT_TTL,
    override val hops: Int = 0,
) : Frame

/**
 * An emoji reaction to the message identified by [messageId]. Flooded like chat so every device that
 * holds the message converges on the same reaction state. Each reactor ([senderId]) has at most one
 * reaction per message: [emoji] is the chosen emoji, or null to retract. [sentAt] is the last-writer-
 * wins clock — a receiver applies this frame only if it is newer than the reaction it already holds
 * for ([messageId], [senderId]), so out-of-order add/retract/replace frames converge deterministically.
 *
 * [id] is a fresh random UUID per emit (NOT derived from messageId/senderId/emoji): the dedup
 * [SeenSet] keys on it, so a later retract or replace must carry a new id to re-flood rather than be
 * swallowed as a duplicate. State dedup is [sentAt]'s job, not the frame id's.
 */
@Serializable
@SerialName("reaction")
data class ReactionFrame(
    override val id: String,
    val senderId: String,
    val messageId: String,
    val emoji: String? = null,
    val sentAt: Long,
    override val ttl: Int = DEFAULT_TTL,
    override val hops: Int = 0,
) : Frame

/**
 * A point-to-point request for the image blob identified by [hash]. Sent only to direct neighbors
 * and never flooded ([relayable] is false); a neighbor that holds the blob serves it back over the
 * file channel, and one that doesn't recurses the request to its own neighbors. This hop-by-hop
 * pull is how attachments reach peers beyond the originator's direct range.
 */
@Serializable
@SerialName("blobreq")
data class BlobRequestFrame(
    override val id: String,
    val senderId: String,
    val hash: String,
    override val ttl: Int = DEFAULT_TTL,
    override val hops: Int = 0,
) : Frame {
    override val relayable: Boolean get() = false
}

/** Returns a copy of this frame with its hop count incremented (used when relaying). */
fun Frame.incrementHop(): Frame = when (this) {
    is ChatFrame -> copy(hops = hops + 1)
    is ProfileFrame -> copy(hops = hops + 1)
    is ReceiptFrame -> copy(hops = hops + 1)
    is ReactionFrame -> copy(hops = hops + 1)
    is BlobRequestFrame -> copy(hops = hops + 1)
}

/** Serializes [Frame]s to/from the bytes carried in a transport payload. */
object WireCodec {
    private val json = Json {
        classDiscriminator = "t"
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(frame: Frame): ByteArray =
        json.encodeToString<Frame>(frame).encodeToByteArray()

    /** Decodes a frame, or returns null if the bytes are malformed/unrecognized. */
    fun decode(bytes: ByteArray): Frame? = runCatching {
        json.decodeFromString<Frame>(bytes.decodeToString())
    }.getOrNull()
}
