package app.getknit.knit.mesh.protocol

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray

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

/**
 * Group-chat metadata carried on every group [ChatFrame] so the message is self-describing: any node
 * that receives one can (re)construct the group from scratch, with no separate invite/create frame —
 * robust against flood loss and late joiners on a lossy mesh. [id] is derived deterministically from
 * the member set (see [app.getknit.knit.data.message.Conversations.groupIdFor]) so the same people
 * always resolve to the same group — no duplicate threads — and it can't collide in conversation-id
 * space; [name] is set ONLY by an explicit rename (converges last-writer-wins by the frame's
 * [ChatFrame.sentAt]) and is null for an unnamed group, where each device renders its own default from
 * the members ([app.getknit.knit.data.message.groupTitle]); [members] is the fixed roster (capped at 8
 * incl. the creator); [createdBy] is the creator's node id, used to refuse a group a blocked user tries
 * to start on this device. A plain nested `@Serializable` value (like [Mention]) — never a [Frame], so
 * no [SerialName] discriminator.
 */
@Serializable
data class GroupInfo(
    val id: String,
    val name: String? = null,
    val members: List<String>,
    val createdBy: String,
)

/**
 * One recipient's copy of the per-message content key [wk] (base64), wrapped (Tink hybrid / HPKE) to
 * that recipient's published encryption key and tagged by their node id [to]. A group message carries
 * one [WrappedKey] per member (minus the sender); a DM carries exactly one.
 */
@Serializable
data class WrappedKey(
    val to: String,
    val wk: String,
)

/**
 * The end-to-end encryption envelope carried on an encrypted [ChatFrame]. A random per-message content
 * key encrypts the [app.getknit.knit.mesh.crypto.MessageContent] (body + mentions + attachment refs)
 * with AES-256-GCM into [ct] under [nonce] (both base64); that content key is then wrapped once per
 * recipient into [keys]. Only the addressed recipients can unwrap it, so relays — which never read
 * frame contents anyway — carry pure ciphertext. [v] is the scheme version (omitted on the wire while
 * it equals the default). Present only on DM/group messages; the broadcast room is never encrypted.
 */
@Serializable
data class EncEnvelope(
    val v: Int = 1,
    val nonce: String,
    val ct: String,
    val keys: List<WrappedKey>,
)

@Serializable
@SerialName("chat")
data class ChatFrame(
    override val id: String,
    val senderId: String,
    val sentAt: Long,
    // Plaintext body for the broadcast room. Empty when [enc] is set: the real body lives encrypted in
    // the envelope, and mentions/attachment fields below are likewise blank/null on the wire.
    val body: String,
    val recipientId: String? = null,
    val sig: String? = null,
    val mentions: List<Mention> = emptyList(),
    // Reference to an out-of-band image blob (fetched by content hash); null for text-only messages.
    val attachmentHash: String? = null,
    val attachmentMime: String? = null,
    // Set on group-chat messages; null for the broadcast room and 1:1 DMs (recipientId stays null for
    // groups — the group field, not recipientId, addresses the thread). Omitted on the wire when null.
    val group: GroupInfo? = null,
    // End-to-end encryption envelope. Non-null ⇒ this is an encrypted DM/group message: [body] is empty,
    // [mentions]/[attachment*] are blank/null, and the plaintext lives in [enc] + is authenticated by
    // [sig]. Null ⇒ a plaintext broadcast-room message (unchanged legacy path).
    val enc: EncEnvelope? = null,
    override val ttl: Int = DEFAULT_TTL,
    override val hops: Int = 0,
) : Frame

/**
 * Floods a group's metadata ([group]) on its own, independent of any chat message — so a rename (or a
 * just-created group) reaches members immediately rather than waiting for the next [ChatFrame]. Carries
 * no body; receivers reconcile their stored group (last-writer-wins on [sentAt] for the name) without
 * persisting a message. A member who doesn't yet know the group will create it from the carried roster.
 */
@Serializable
@SerialName("groupupdate")
data class GroupUpdateFrame(
    override val id: String,
    val senderId: String,
    val sentAt: Long,
    val group: GroupInfo,
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
    // Key-independent device tag for soft block-list continuity (a blocked peer that regenerates its
    // key returns under a new senderId but the same tag). Optional, so this is a backward-compatible
    // wire addition (missing → null). Never used for routing/trust — only the local block list.
    val deviceTag: String? = null,
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
    is GroupUpdateFrame -> copy(hops = hops + 1)
    is ProfileFrame -> copy(hops = hops + 1)
    is ReceiptFrame -> copy(hops = hops + 1)
    is ReactionFrame -> copy(hops = hops + 1)
    is BlobRequestFrame -> copy(hops = hops + 1)
}

/**
 * Returns a copy of this frame with its [ttl] capped at [max] (a no-op when already within bounds).
 * [ttl] is an attacker-controlled wire field, so a forged oversized value (e.g. [Int.MAX_VALUE]) would
 * otherwise let a frame outlive the dedup window and flood the mesh indefinitely; the relayer caps it
 * to the local [DEFAULT_TTL] so the hop count alone bounds propagation.
 */
fun Frame.cappedTtl(max: Int): Frame = if (ttl <= max) this else when (this) {
    is ChatFrame -> copy(ttl = max)
    is GroupUpdateFrame -> copy(ttl = max)
    is ProfileFrame -> copy(ttl = max)
    is ReceiptFrame -> copy(ttl = max)
    is ReactionFrame -> copy(ttl = max)
    is BlobRequestFrame -> copy(ttl = max)
}

/**
 * Serializes [Frame]s to/from the bytes carried in a transport payload, using compact binary CBOR
 * rather than JSON text. CBOR encodes numbers as binary, length-prefixes strings (no quotes/braces),
 * and — with [CborBuilder.encodeDefaults] off — omits defaulted fields entirely, so a typical text
 * frame loses the ~150 bytes of `"recipientId":null,…,"ttl":8,"hops":0` framing JSON carried on the
 * wire. Polymorphism is carried by CBOR's own structure (the [SerialName] discriminators are
 * preserved); the JSON-only `classDiscriminator` option no longer applies.
 *
 * This is a deliberate wire-format break: all nodes must run a CBOR-speaking build to interoperate.
 */
@OptIn(ExperimentalSerializationApi::class) // Cbor is an experimental kotlinx-serialization API
object WireCodec {
    private val cbor = Cbor {
        ignoreUnknownKeys = true // forward-compat: tolerate fields added by newer peers
        encodeDefaults = false   // drop default ttl/hops, empty mentions, and null reserved fields
    }

    fun encode(frame: Frame): ByteArray = cbor.encodeToByteArray<Frame>(frame)

    /** Decodes a frame, or returns null if the bytes are malformed/unrecognized. */
    fun decode(bytes: ByteArray): Frame? = runCatching {
        cbor.decodeFromByteArray<Frame>(bytes)
    }.getOrNull()
}
