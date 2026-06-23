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
}

@Serializable
@SerialName("chat")
data class ChatFrame(
    override val id: String,
    val senderId: String,
    val sentAt: Long,
    val body: String,
    val recipientId: String? = null,
    val sig: String? = null,
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

/** Returns a copy of this frame with its hop count incremented (used when relaying). */
fun Frame.incrementHop(): Frame = when (this) {
    is ChatFrame -> copy(hops = hops + 1)
    is ProfileFrame -> copy(hops = hops + 1)
    is ReceiptFrame -> copy(hops = hops + 1)
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
