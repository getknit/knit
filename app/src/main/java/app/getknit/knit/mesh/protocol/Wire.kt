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
    const val TYPING = "typing"

    /**
     * Whether a frame of [type] is worth parking for replay when it's dropped for a missing sender key
     * (see `app.getknit.knit.mesh.PendingInbound`): the locally-delivered types only. PROFILE and KEY_REQ
     * are excluded (they bootstrap keys, never wait on one), as are the point-to-point BLOB_REQ and any
     * unknown future type — none of which `dispatchByType` would deliver on replay, so holding them would
     * just occupy a slot.
     */
    fun isReplayable(type: String): Boolean =
        type == CHAT || type == REACTION || type == RECEIPT || type == GROUP_UPDATE || type == GROUP_LEAVE

    /**
     * Whether a frame of [type] is carried for store-and-forward custody and eligible for the
     * coordination-plane fast-fanout (see [isStorable] / `MeshManager.shouldFastFanout`): every floodable
     * type — the locally-delivered [isReplayable] family plus [PROFILE] (which self-certifies its key
     * in-band, so it can be authenticated and re-served without a prior pin). Excludes the point-to-point
     * [BLOB_REQ]/[KEY_REQ] requests (relayed hop-by-hop, not flooded, and transient — nothing to custody),
     * the best-effort [TYPING] cue (single-hop, fire-and-forget presence — worthless a moment later, so it
     * is never carried, parked, or re-served), and any unknown future type (a carrier can't authenticate
     * what it can't place).
     */
    fun isCustodial(type: String): Boolean = isReplayable(type) || type == PROFILE
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
    fun relayed(): WireEnvelope = WireEnvelope(ttl = minOf(ttl, DEFAULT_TTL), hops = hops + 1, relay = relay, sig = sig, signed = signed)
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
 * Every floodable type qualifies ([FrameType.isCustodial]), so the whole mesh converges on the same state
 * rather than only in-range/one-hop peers seeing a change:
 *  - **chat** — a 1:1 DM (single cleartext [RelayEnvelope.recipientId] to deliver toward), a group message
 *    (cleartext [GroupInfo.members] roster), and the plaintext **broadcast room** (no destination: both
 *    [RelayEnvelope.recipientId] and [RelayEnvelope.group] null) so two phones that meet only briefly still
 *    backfill each other;
 *  - **reaction / receipt / group-update / group-leave / profile** — small metadata that also flood once and
 *    would otherwise be lost to any peer that wasn't connected at that instant. Carried as immutable frames
 *    keyed by id (no compaction): a superseded version — a re-toggled reaction, a second rename, an edited
 *    profile — simply lingers until its TTL, and the receiver resolves last-writer-wins on `sentAt`.
 * None have a single recipient/ack (a DM does), so they are bounded by TTL + cap only (never vaccine-purged).
 */
fun RelayEnvelope.isStorable(): Boolean = FrameType.isCustodial(type)

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
    // Quoted-reply reference, set directly here ONLY for the plaintext broadcast room. For an encrypted
    // DM/group the quote rides inside [enc] ([MessageContent.replyTo]) so it stays private, and this is
    // left null — mirroring how [body]/[mentions] are blank on an encrypted frame.
    val replyTo: ReplyRef? = null,
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
data class GroupLeaveContent(
    val groupId: String,
)

/** Content of a [FrameType.RECEIPT] frame: the id of the message being acknowledged. */
@Serializable
data class ReceiptContent(
    val ackId: String,
)

/** Content of a [FrameType.REACTION] frame: the target message and the chosen emoji (null = retract). */
@Serializable
data class ReactionContent(
    val messageId: String,
    val emoji: String? = null,
)

/** Content of a [FrameType.BLOB_REQ] frame: the content hash of the requested image blob. */
@Serializable
data class BlobReqContent(
    val hash: String,
)

/**
 * Content of a [FrameType.KEY_REQ] frame: the node ids whose public-key bundle (i.e. profile) the sender
 * is missing and can't otherwise verify frames from. A holder replies by re-serving each peer's cached,
 * already-signed [FrameType.PROFILE] frame verbatim (the response rides the existing profile path, which
 * self-certifies on pin), so no separate response type is needed. A list — not a single id — so a node
 * that's missing several peers (e.g. just after a restart) asks in one frame; v1 senders may use a
 * single-element list. The request itself is signed and point-to-point (`relay = false`), never flooded.
 */
@Serializable
data class KeyReqContent(
    val nodeIds: List<String>,
)

/**
 * Content of a [FrameType.TYPING] cue — a best-effort, fire-and-forget "now typing" presence ping. Scoped
 * to a conversation the same way a chat is: a DM by [RelayEnvelope.recipientId], the broadcast room by both
 * that and [groupId] being null, and a group by [groupId] (carried here, NOT in the heavy
 * [RelayEnvelope.group] roster, so a signed typing frame stays under the ~255 B coordination-plane cap). The
 * cue is single-hop (`relay = false`) and never custodied ([isStorable] is false for it), so no field ever
 * needs to survive store-and-forward. [groupId] is the only field and is defaulted, so a DM/broadcast frame
 * encodes an empty payload.
 */
@Serializable
data class TypingContent(
    val groupId: String? = null,
)

/**
 * A structured "@" mention inside a chat body. [nodeId] is the canonical node id used for reliable
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
 * A quoted-reply reference (the "▎author / snippet" block a reply renders above its own body). The
 * quoted message is **denormalized** into the reply so the quote still renders when the original was
 * never received (store-and-forward), was deleted, or scrolled out of the local store — the receiver
 * never resolves [messageId] against its own history to draw the quote (it's used only for the optional
 * tap-to-scroll). [authorId] is the quoted message's sender node id — each viewer swaps it to "You" when
 * it's their own; [author] is a display-name snapshot (never the literal "You", so a peer that lacks the
 * author's profile still shows a real name); [snippet] is a capped copy of the quoted body (blank for an
 * attachment-only original); [hasAttachment] lets the UI show a "photo" placeholder when [snippet] is
 * blank even if the original isn't present locally. Additive/optional on both [ChatContent] (broadcast)
 * and [app.getknit.knit.mesh.crypto.MessageContent] (encrypted DM/group). A plain nested value.
 */
@Serializable
data class ReplyRef(
    val messageId: String,
    val authorId: String,
    val author: String,
    val snippet: String,
    val hasAttachment: Boolean = false,
)

/**
 * Group-chat metadata carried on every group chat/update frame so the message is self-describing: any
 * node that receives one can (re)construct the group from scratch, with no separate invite/create frame
 * — robust against flood loss and late joiners. [id] is derived deterministically from the member set
 * (see [app.getknit.knit.data.message.Conversations.groupIdFor]); [name] is set ONLY by an explicit
 * rename (converges last-writer-wins by the frame's sentAt) and is null for an unnamed group; [members]
 * is the fixed roster (capped at 8 incl. the creator); [createdBy] is the creator's node id, used to
 * refuse a group a blocked user tries to start on this device.
 *
 * [photoHash] is the content hash of the group's photo blob (pulled out of band like a peer avatar, via
 * [FrameType.BLOB_REQ]); null means "no change / unset" (never "clear", same as [name]). [photoUpdatedAt]
 * is the photo's own last-writer-wins clock (the wall clock at which a member set it) — distinct from the
 * frame's sentAt that clocks [name], so a stale chat message re-asserting an old photo can't revert a
 * newer one. Both are additive nullable fields (see `docs/WIRE_COMPAT.md`): an old peer ignores them and
 * still relays the frame verbatim.
 */
@Serializable
data class GroupInfo(
    val id: String,
    val name: String? = null,
    val members: List<String>,
    val createdBy: String,
    val photoHash: String? = null,
    val photoUpdatedAt: Long? = null,
)

/**
 * One recipient's copy of the per-message content key [wk] (raw HPKE-wrapped bytes), wrapped (Tink
 * hybrid / HPKE) to that recipient's published encryption key and tagged by their node id [to]. A group
 * message carries one [WrappedKey] per member (minus the sender); a DM carries exactly one. A plain
 * `class` (not `data class`, like [WireEnvelope]/[RelayEnvelope]): a `@ByteString` field only gets a
 * reference-identity `equals`/`hashCode` from `data`, so we omit them rather than ship a broken one.
 */
@Serializable
class WrappedKey(
    val to: String,
    @ByteString val wk: ByteArray,
)

/**
 * The end-to-end encryption envelope carried inside an encrypted [ChatContent]. A random per-message
 * content key encrypts the [app.getknit.knit.mesh.crypto.MessageContent] with AES-256-GCM into [ct]
 * under [nonce] (both raw byte strings — CBOR `@ByteString`, not base64: the envelope already rides a
 * binary CBOR frame, so base64 only inflated these ~33%); that content key is wrapped once per recipient
 * into [keys]. [v] is the crypto-scheme version (omitted on the wire while it equals the default); an
 * unsupported version is dropped on delivery (see `MeshManager.decrypt`). Authenticated by the frame
 * [sig] (which covers the whole [ChatContent] payload), so a wrapped key or ciphertext can't be replayed
 * into another message. A plain `class` (see [WrappedKey]) so the `@ByteString` fields don't inherit a
 * broken data-class `equals`.
 */
@Serializable
class EncEnvelope(
    val v: Int = 1,
    @ByteString val nonce: ByteArray,
    @ByteString val ct: ByteArray,
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
 * to interoperate (the [app.getknit.knit.mesh.wifiaware.WifiAwareTransport] service name is bumped in lockstep).
 */
object WireCodec {
    @PublishedApi
    internal val cbor: Cbor =
        Cbor {
            ignoreUnknownKeys = true
            encodeDefaults = false
            // Definite-length CBOR (kotlinx defaults to indefinite-length). The friendlier target for
            // non-kotlinx codecs (e.g. an iOS SwiftCBOR client); pinned at the v22 wire break. Every field
            // of the frozen contract is explicit — see docs/WIRE_COMPAT.md.
            useDefiniteLengthEncoding = true
        }

    fun encodeWire(wire: WireEnvelope): ByteArray = cbor.encodeToByteArray(wire)

    /** Decodes the outer wrapper, or null if the bytes are malformed/unrecognized. */
    fun decodeWire(bytes: ByteArray): WireEnvelope? = runCatching { cbor.decodeFromByteArray<WireEnvelope>(bytes) }.getOrNull()

    fun encodeEnvelope(env: RelayEnvelope): ByteArray = cbor.encodeToByteArray(env)

    /** Decodes the signed routing envelope from [signed], or null if malformed. */
    fun decodeEnvelope(signed: ByteArray): RelayEnvelope? = runCatching { cbor.decodeFromByteArray<RelayEnvelope>(signed) }.getOrNull()

    inline fun <reified T> encodePayload(content: T): ByteArray = cbor.encodeToByteArray(content)

    /** Decodes the opaque per-type [payload] into [T], or null if absent/malformed. */
    inline fun <reified T> decodePayload(payload: ByteArray): T? = runCatching { cbor.decodeFromByteArray<T>(payload) }.getOrNull()
}
