@file:OptIn(ExperimentalSerializationApi::class) // Cbor + @ByteString are experimental kotlinx APIs

package app.getknit.knit.mesh.protocol

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.ByteString
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray

/** Default hop limit for a flooded frame before relays stop forwarding it. */
const val DEFAULT_TTL: Int = 8

/**
 * The wire is layered so the format can evolve additively without ever forcing another break:
 *
 * - [WireEnvelope] (layer 1, the on-radio unit) is **frozen forever** and is the ONLY thing a relay
 *   re-encodes. It carries the mutable routing counters ([ttl]/[hops]) plus the signature and the
 *   opaque [signed] blob it covers. A relay rewrites only ttl/hops ([WireEnvelope.relayed]); [signed]
 *   and [sig] pass through byte-for-byte, so the originator's exact signed bytes survive every hop.
 * - [RelayEnvelope] (layer 2, what [signed] decodes to) carries only the cleartext fields a relay or
 *   store-and-forward carrier needs to route ([type]/[id]/[senderId]/[recipientId]/[group]) plus an
 *   opaque [payload]. Because relays never re-encode it, new fields can be added here too: an old relay
 *   ignores them on decode ([WireCodec] uses `ignoreUnknownKeys`) yet still forwards the original bytes.
 * - The per-type content (layer 3: [ChatContent], [ProfileContent], …) lives inside [payload] and is
 *   parsed only by endpoints, so it evolves freely — new fields and even new [type] values are invisible
 *   to relays, which forward them verbatim instead of dropping them.
 *
 * The single [sig] over [signed] authenticates every type (the previous split between a frame signature
 * and a separate envelope signature is gone). [type]/[id]/[senderId] ride inside [signed], so a
 * signature cannot be lifted across frames, replayed under a fresh id, or moved between types.
 */
object FrameType {
    const val CHAT = "chat"
    const val GROUP_UPDATE = "groupupdate"
    const val GROUP_LEAVE = "groupleave"
    const val PROFILE = "profile"
    const val RECEIPT = "receipt"
    const val REACTION = "reaction"
    const val BLOB_REQ = "blobreq"
    const val KEY_REQ = "keyreq"
}

/**
 * Layer 1 — the frozen on-radio unit. [ttl] (hop limit) and [hops] (current count) are mutable,
 * unsigned routing metadata a relay rewrites in flight; [relay] is whether [MeshRouter] floods it
 * onward (false for point-to-point control frames like a blob request — carried in the wrapper, not
 * derived from the type, so even an old relay honors a future point-to-point type). [sig] is the raw
 * Ed25519 signature over [signed] (empty for the unsigned blob request); [signed] is the canonical
 * [RelayEnvelope] CBOR, forwarded byte-for-byte so every verifier reproduces the bytes the originator
 * signed. A plain (non-data) class: it holds [ByteArray]s, so value equality would be by reference
 * anyway; tests compare by decoding.
 */
@Serializable
class WireEnvelope(
    val ttl: Int = DEFAULT_TTL,
    val hops: Int = 0,
    val relay: Boolean = true,
    @ByteString val sig: ByteArray,
    @ByteString val signed: ByteArray,
) {
    /**
     * A relay copy: ttl capped to the local [DEFAULT_TTL] and hops incremented, with [sig]/[signed]
     * reused by reference (never re-encoded). [ttl] is attacker-controlled, so capping it bounds
     * propagation by hop count regardless of what a peer claims.
     */
    fun relayed(): WireEnvelope =
        WireEnvelope(ttl = minOf(ttl, DEFAULT_TTL), hops = hops + 1, relay = relay, sig = sig, signed = signed)
}

/**
 * Layer 2 — the signed routing envelope. Carries only what a relay/carrier needs in cleartext to route
 * without reading content: [type] (the discriminator, a plain string so an unknown future type still
 * decodes instead of throwing), [id] (dedup key + message/ack id), [senderId], [sentAt], and the
 * addressing fields [recipientId] (DM) / [group] (group roster). [payload] is the opaque per-type
 * content; relays never parse it. A plain class for the same reason as [WireEnvelope].
 */
@Serializable
class RelayEnvelope(
    val type: String,
    val id: String,
    val senderId: String,
    val sentAt: Long = 0L,
    val recipientId: String? = null,
    val group: GroupInfo? = null,
    @ByteString val payload: ByteArray,
)

/**
 * Whether this frame is carried for store-and-forward delivery (see `app.getknit.knit.mesh.ForwardSync`).
 * An addressed chat qualifies — a 1:1 DM (single cleartext [RelayEnvelope.recipientId] to deliver
 * toward) or a group message (cleartext [GroupInfo.members] roster). Only the plaintext broadcast room
 * (no destination: both [RelayEnvelope.recipientId] and [RelayEnvelope.group] null) is excluded.
 */
fun RelayEnvelope.isStorable(): Boolean =
    type == FrameType.CHAT && (recipientId != null || group != null)

// --- Layer 3: per-type content payloads (parsed only by endpoints; evolve additively) ---

/**
 * Content of a [FrameType.CHAT] frame. For the plaintext broadcast room [body]/[mentions]/[attachment*]
 * are filled in directly; for an encrypted DM/group message they are blank/null and the real content
 * lives encrypted in [enc] (which the frame [sig] authenticates). A reference to an out-of-band image
 * blob (fetched by content hash) travels in [attachmentHash]/[attachmentMime].
 */
@Serializable
data class ChatContent(
    val body: String = "",
    val mentions: List<Mention> = emptyList(),
    val attachmentHash: String? = null,
    val attachmentMime: String? = null,
    val enc: EncEnvelope? = null,
)

/**
 * Content of a [FrameType.PROFILE] frame: the peer's display [name]/[status], optional [avatarHash]
 * (content hash of the avatar blob), [pubKey] (base64 [app.getknit.knit.mesh.crypto.PublicKeyBundle];
 * pins the peer's E2E key, and the nodeId must derive to it), and key-independent [deviceTag] for
 * block-list continuity. [protoVersion]/[capabilities] advertise the sender's protocol version and
 * feature bits (see [Protocol]) — additive, authenticated (the frame [sig] covers them), and currently
 * recorded for diagnostics only.
 */
@Serializable
data class ProfileContent(
    val name: String,
    val status: String,
    val avatarHash: String? = null,
    val pubKey: String? = null,
    val deviceTag: String? = null,
    val protoVersion: Int? = null,
    val capabilities: Long? = null,
)

/** Content of a [FrameType.GROUP_LEAVE] frame: the group the (self-asserted) sender is leaving. */
@Serializable
data class GroupLeaveContent(val groupId: String)

/** Content of a [FrameType.RECEIPT] frame: the id of the message being acknowledged. */
@Serializable
data class ReceiptContent(val ackId: String)

/** Content of a [FrameType.REACTION] frame: the target message and the chosen emoji (null = retract). */
@Serializable
data class ReactionContent(val messageId: String, val emoji: String? = null)

/** Content of a [FrameType.BLOB_REQ] frame: the content hash of the requested image blob. */
@Serializable
data class BlobReqContent(val hash: String)

/**
 * Content of a [FrameType.KEY_REQ] frame: the node ids whose public-key bundle (i.e. profile) the sender
 * is missing and can't otherwise verify frames from. A holder replies by re-serving each peer's cached,
 * already-signed [FrameType.PROFILE] frame verbatim (the response rides the existing profile path, which
 * self-certifies on pin), so no separate response type is needed. A list — not a single id — so a node
 * that's missing several peers (e.g. just after a restart) asks in one frame; v1 senders may use a
 * single-element list. The request itself is signed and point-to-point (`relay = false`), never flooded.
 */
@Serializable
data class KeyReqContent(val nodeIds: List<String>)

/**
 * A structured "@" mention inside a chat body. [nodeId] is the canonical 8-char id used for reliable
 * "did this mention me" detection (display names aren't unique); [name] is the exact display name the
 * sender rendered, so the receiver can locate the "@name" span for highlighting. A plain nested value.
 */
@Serializable
data class Mention(
    val nodeId: String,
    val name: String,
)

/** True when these mentions target [nodeId] (typically the receiver's own id). */
fun List<Mention>.mention(nodeId: String): Boolean = any { it.nodeId == nodeId }

/**
 * Group-chat metadata carried on every group chat/update frame so the message is self-describing: any
 * node that receives one can (re)construct the group from scratch, with no separate invite/create frame
 * — robust against flood loss and late joiners. [id] is derived deterministically from the member set
 * (see [app.getknit.knit.data.message.Conversations.groupIdFor]); [name] is set ONLY by an explicit
 * rename (converges last-writer-wins by the frame's sentAt) and is null for an unnamed group; [members]
 * is the fixed roster (capped at 8 incl. the creator); [createdBy] is the creator's node id, used to
 * refuse a group a blocked user tries to start on this device.
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
 * The end-to-end encryption envelope carried inside an encrypted [ChatContent]. A random per-message
 * content key encrypts the [app.getknit.knit.mesh.crypto.MessageContent] with AES-256-GCM into [ct]
 * under [nonce] (both base64); that content key is wrapped once per recipient into [keys]. [v] is the
 * crypto-scheme version (omitted on the wire while it equals the default); an unsupported version is
 * dropped on delivery (see `MeshManager.decrypt`). Authenticated by the frame [sig] (which covers the
 * whole [ChatContent] payload), so a wrapped key or ciphertext can't be replayed into another message.
 */
@Serializable
data class EncEnvelope(
    val v: Int = 1,
    val nonce: String,
    val ct: String,
    val keys: List<WrappedKey>,
) {
    companion object {
        /** Highest crypto-scheme version this build understands; a higher [v] is dropped on delivery. */
        const val MAX_SUPPORTED_VERSION = 1
    }
}

/**
 * Serializes the wire layers to/from CBOR. [encodeWire]/[decodeWire] handle the outer [WireEnvelope];
 * [encodeEnvelope]/[decodeEnvelope] the signed [RelayEnvelope]; [encodePayload]/[decodePayload] the
 * per-type content. All three share one [Cbor] configured with `ignoreUnknownKeys = true` (forward-
 * compat: tolerate fields a newer peer added) and `encodeDefaults = false` (drop defaulted fields so a
 * typical frame stays compact). Compact binary CBOR rather than JSON: numbers binary, strings
 * length-prefixed (no quotes/braces).
 *
 * This is a deliberate wire-format break from the pre-layered format: all nodes must run a layered build
 * to interoperate (the [app.getknit.knit.mesh.nearby.NearbyTransport] service id is bumped in lockstep).
 */
object WireCodec {
    @PublishedApi
    internal val cbor: Cbor = Cbor {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    fun encodeWire(wire: WireEnvelope): ByteArray = cbor.encodeToByteArray(wire)

    /** Decodes the outer wrapper, or null if the bytes are malformed/unrecognized. */
    fun decodeWire(bytes: ByteArray): WireEnvelope? =
        runCatching { cbor.decodeFromByteArray<WireEnvelope>(bytes) }.getOrNull()

    fun encodeEnvelope(env: RelayEnvelope): ByteArray = cbor.encodeToByteArray(env)

    /** Decodes the signed routing envelope from [signed], or null if malformed. */
    fun decodeEnvelope(signed: ByteArray): RelayEnvelope? =
        runCatching { cbor.decodeFromByteArray<RelayEnvelope>(signed) }.getOrNull()

    inline fun <reified T> encodePayload(content: T): ByteArray = cbor.encodeToByteArray(content)

    /** Decodes the opaque per-type [payload] into [T], or null if absent/malformed. */
    inline fun <reified T> decodePayload(payload: ByteArray): T? =
        runCatching { cbor.decodeFromByteArray<T>(payload) }.getOrNull()
}
