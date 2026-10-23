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
     * "Do you hold these blobs?" — a [BlobHaveContent] asked of one linked neighbour, answered with a [BLOB_HAS]
     * (ADR 2026-10.6e5t). Unsigned and point-to-point like [BLOB_REQ], and asked only of a neighbour whose link
     * advertises `Protocol.CAP_BLOB_HAVE`: a build without it would refuse the unsigned frame and the asker would
     * wait for nothing. A query is not an ask — it serves nothing, pulls nothing, and stamps no serve memo.
     */
    const val BLOB_HAVE = "blobhave"

    /** The answer to a [BLOB_HAVE]: a [BlobHasContent], sent back on the link the query came in on. */
    const val BLOB_HAS = "blobhas"

    /**
     * How much of a file streaming in over Bluetooth the receiver holds: a [BlobProgressContent], sent back to the
     * sender every couple of seconds of chunks, so the sender measures the drain its stack's queue hides from it and
     * times the asks it parked behind the feed by it (ADR 2026-10.nsr7). Unsigned and point-to-point like [BLOB_HAVE],
     * and sent only to a neighbour whose link advertises `Protocol.CAP_BLOB_HAVE`; a build that does not know the type
     * refuses the unsigned frame, which costs it nothing.
     */
    const val BLOB_PROGRESS = "blobprog"

    /**
     * An asker withdraws its ask for a blob: a [BlobCancelContent], sent to a neighbour it asked once it moves on or
     * holds the blob (ADR 2026-10.nsr7's cancel amendment). The holder drops the ask if it is parked, and takes our copy
     * off the link's queue if it has not started; a copy already streaming goes on (a link cannot cut a file short
     * without a new record type). Unsigned and point-to-point like [BLOB_HAVE], and sent only to a neighbour whose link
     * advertises `Protocol.CAP_BLOB_HAVE`; a build that does not know the type refuses it, and the copy comes as before.
     */
    const val BLOB_CANCEL = "blobcancel"

    /**
     * A post in a **commons** — a spool's shared room (docs/SPOOL_PROTOCOL.md §7.4). Payload is a
     * [CommonsPost]. Deliberately **not** replayable and **not** custodial: a commons post lives on the
     * spool that runs the room and enters the app through the Internet plane's own door, never the
     * radios — so an older build that meets one on a link decodes it, relays it like any unknown type,
     * and delivers nothing, while `isCustodial` stays the fixed list it is (the `meshpost` lesson below).
     */
    const val COMMONS = "commons"

    /**
     * One chunk of a walkie-talkie burst (experimental, docs/VOICE_CALLS.md §3.11): opaque audio sealed on the DM
     * ratchet, its payload a [TalkSeal]. A type of its own so that nobody custodies it — audio is live or lost, and a
     * carrier that held a minute of it would spend its sender's custody quota on history. Unsigned (the seal
     * authenticates it at its addressee, the ADR 059 argument) and flooded a few hops at most (none to a live
     * neighbour); not custodial, replayable or fanned out, so an older build relays it like any unknown type and
     * delivers nothing.
     */
    const val TALK = "talk"

    /**
     * Whether a frame of [type] is worth parking for replay when it's dropped for a missing sender key
     * (see `app.getknit.knit.mesh.PendingInbound`): the locally-delivered types only. PROFILE and KEY_REQ
     * are excluded (they bootstrap keys, never wait on one), as are the point-to-point BLOB_REQ and any
     * unknown future type — none of which `dispatchByType` would deliver on replay, so holding them would
     * just occupy a slot.
     */
    fun isReplayable(type: String): Boolean =
        type == CHAT ||
            type == REACTION ||
            type == RECEIPT ||
            type == GROUP_UPDATE ||
            type == GROUP_LEAVE

    /**
     * Whether a frame of [type] is carried for store-and-forward custody and eligible for the
     * coordination-plane fast-fanout (see [isStorable] / `MeshManager.shouldFastFanout`): every floodable
     * type — the locally-delivered [isReplayable] family plus [PROFILE] (which self-certifies its key
     * in-band, so it can be authenticated and re-served without a prior pin). Excludes the point-to-point
     * [BLOB_REQ]/[KEY_REQ] requests (relayed hop-by-hop, not flooded, and transient — nothing to custody),
     * the best-effort [TYPING] cue (single-hop, fire-and-forget presence — worthless a moment later, so it
     * is never carried, parked, or re-served), and any unknown future type (a carrier can't authenticate
     * what it can't place).
     *
     * **A new custodial type is a flag-day, not an additive change.** This list is fixed on every build
     * already in the field, so a build without the new type holds none of those rows while a build with it
     * holds them all — two nodes with continuously different live sets, which is exactly the custody-digest
     * divergence ADR 006 exists to prevent. Nothing has been added here since the v1 baseline; the one
     * attempt (`meshpost`, the Meshtastic room's first design) was withdrawn before it shipped for this
     * reason. Do not widen this list without re-reading `docs/WIRE_COMPAT.md`.
     */
    fun isCustodial(type: String): Boolean = isReplayable(type) || type == PROFILE

    /**
     * The unsigned point-to-point blob traffic: [BLOB_REQ], the [BLOB_HAVE]/[BLOB_HAS] query and answer, the
     * receiver's [BLOB_PROGRESS] report and the asker's [BLOB_CANCEL]. Taken
     * without a signature (`InboundPipeline.verifyInbound`), so its [RelayEnvelope.senderId] is a claim nobody checked:
     * none of it is ever evidence that a node was heard (`HeardFrom`, `RelayedPresence`), and none of it is carried,
     * parked or flooded.
     */
    fun isUnsignedPointToPoint(type: String): Boolean = type in UNSIGNED_POINT_TO_POINT

    private val UNSIGNED_POINT_TO_POINT = setOf(BLOB_REQ, BLOB_HAVE, BLOB_HAS, BLOB_PROGRESS, BLOB_CANCEL)
}

/**
 * Layer 1 — the frozen on-radio unit. [ttl] (hop limit) and [hops] (current count) are mutable,
 * unsigned routing metadata a relay rewrites in flight; [relay] is whether [MeshRouter] floods it
 * onward (false for point-to-point control frames like a blob request — carried in the wrapper, not
 * derived from the type, so even an old relay honors a future point-to-point type). [sig] is the raw
 * Ed25519 signature over [signed] (empty for the two unsigned forms: the blob request, and the v3
 * point-to-point sealed delivery tick, which its ratchet AEAD authenticates instead — ADR 059); [signed] is the canonical
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
 * blob (fetched by content hash) travels in [attachmentHash], with [attachmentMime] beside it in the
 * room.
 *
 * **The asymmetry, ADR 035:** an encrypted frame sets [attachmentHash] but leaves [attachmentMime]
 * null. Custody and the Internet plane's attachment pass address bytes by hash and never needed the type,
 * so the cleartext copy told a blind carrier only whether the message was a photo or a voice note; the
 * real value rides sealed in [app.getknit.knit.mesh.crypto.MessageContent.attachmentMime] and is what
 * `InboundPipeline.plaintextContent` substitutes in on delivery. The field itself stays (the broadcast
 * room fills it, and an older peer still sends it) — this is an un-populating, not a removal.
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
    // The room attachment's plaintext length (additive; ADR 2026-10.hmfe), declared by the rule a sealed
    // `MessageContent.attachmentSize` follows (`BluetoothFetchGate.declaresSize`): a label for "Ask before large
    // Bluetooth downloads" (ADR 2026-10.js2p), which had no size to judge a room post by — never a bound. Room only;
    // an encrypted frame carries its size sealed.
    val attachmentSize: Long? = null,
    // A room video's duration, size and poster (additive; ADR 2026-10.hmfe). Sealed for a DM or a group
    // (`MessageContent.video`), cleartext here like the rest of a room post.
    val video: VideoInfo? = null,
    // A commons file's name (additive; spec §7.4), so a document keeps it. Sealed for a DM or a group
    // (`MessageContent.attachmentName`); the Nearby room takes no files, so only a commons post sets it.
    val attachmentName: String? = null,
    // A commons attachment's own key (additive; spec §7.4), base64 as `MessageContent.attachmentKey`: its blob is the
    // ciphertext a fresh key sealed, as a group's is, so its address correlates with nothing — a phone holding the
    // same picture cannot compute it and ask the radios who else does. Cleartext only inside the room's seal; a
    // Nearby room post is key-less by design and never sets it.
    val attachmentKey: String? = null,
)

/**
 * What a short video's bubble needs before its bytes arrive (ADR 2026-10.hmfe): its [durationMs], its upright
 * [width] × [height] and a small JPEG [poster], carried inside the message so a video waiting on "Tap to download"
 * (ADR 2026-10.js2p) or still fetching shows a picture of itself. All of it is the sender's claim, a label: once the
 * bytes land the receiver derives the duration and size from them, and screens the poster like any picture it is
 * shown.
 *
 * Every field is required (WIRE_COMPAT rule 6, and its rule against a defaulted `ByteArray`), so it is a nested type
 * of its own rather than four nullable fields. A plain `class` with a content `equals` (see [WrappedKey] for why not
 * `data`).
 */
@Serializable
class VideoInfo(
    val durationMs: Long,
    val width: Int,
    val height: Int,
    @ByteString val poster: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is VideoInfo &&
            durationMs == other.durationMs &&
            width == other.width &&
            height == other.height &&
            poster.contentEquals(other.poster)

    override fun hashCode(): Int = ((durationMs.hashCode() * 31 + width) * 31 + height) * 31 + poster.contentHashCode()

    override fun toString(): String = "VideoInfo(durationMs=$durationMs, width=$width, height=$height, poster=${poster.size} B)"

    companion object {
        /** The largest poster a receiver keeps; a bigger one is dropped (the bubble draws without it). */
        const val MAX_POSTER_BYTES = 8 * 1024
    }
}

/**
 * Content of a [FrameType.COMMONS] frame: one post in a spool's commons (docs/SPOOL_PROTOCOL.md §7.4).
 * [scope] is the commons' 32-byte scope id, inside the signed bytes on purpose: the outer seal's aad
 * already binds the *blob* to its scope, but a member of two rooms holds both keys and could re-seal
 * room X's signed post into room Y — naming the room here is what makes that fail on open.
 *
 * Exactly one of the two contents is set:
 * - [chat] — an ordinary [ChatContent] (body, mentions, reply quote, an attachment), cleartext inside the
 *   scope's seal, exactly the broadcast room's shape: every member shares the room key, so there is no
 *   recipient to seal for and [ChatContent.enc] MUST be null. An attachment is sealed to its own key as a
 *   group's is, the key beside its hash ([ChatContent.attachmentKey]); its ciphertext rides the scope's chunk
 *   seal (§4.5).
 * - [reaction] — a member's reaction to a post in this room (additive; C-7.4-13), applied latest-`sentAt`-wins
 *   per (message, reactor) like every other reaction. A build that predates it fails to decode a post with no
 *   [chat] and quarantines it, so it never draws an empty bubble.
 *
 * `class` rather than `data class` for the same reason as [WrappedKey]: the byte-string field.
 */
@Serializable
class CommonsPost(
    @ByteString val scope: ByteArray,
    val chat: ChatContent? = null,
    val reaction: ReactionContent? = null,
)

/**
 * Content of a [FrameType.PROFILE] frame: the peer's display [name]/[status], optional [avatarHash]
 * (content hash of the avatar blob), [pubKey] (base64 [app.getknit.knit.mesh.crypto.PublicKeyBundle];
 * pins the peer's E2E key, and the nodeId must derive to it), and key-independent [deviceTag] for
 * block-list continuity. [protoVersion]/[capabilities] advertise the sender's protocol version and
 * feature bits (see [Protocol]) — additive, authenticated (the frame [sig] covers them); the
 * `CAP_RATCHET` bit is a send-time input (v2 DM gating), the rest are diagnostics. [prekey] is the
 * sender's current ratchet signed prekey (v2 session bootstrap); a *newer* profile carrying a null
 * [prekey] clears the pin (the peer downgraded — outbound falls back to v1).
 *
 * [version] is the sender's profile version — the LWW key both propagation paths order against (the same
 * number [ProfilePayload.version] carries). It used to be implicit in the envelope `sentAt`, which forced
 * `sentAt` to double as an edit stamp: custody expiry is `sentAt + ttl`, so a profile nobody had edited
 * for a day was refused as dead on arrival and fell out of custody entirely — invisible to a late joiner
 * and, since the Internet plane seals what custody holds, unable to reach a peer off the radios at all.
 * Splitting them lets `sentAt` be a *publish* stamp the sender refreshes on a cadence while [version]
 * stays put, so a re-publish is not mistaken for an edit and cannot advance a receiver's watermark.
 * Null on a peer predating this field — read `sentAt` for those, which is exactly what it meant.
 *
 * [openToChat] is the sender's "open to chat" availability flag. Defaulted rather than nullable: it is elided
 * from the wire while off, so an unset flag costs nothing and a peer predating the field reads false —
 * absence means off, which is also how a flip back to off propagates (a newer profile simply omits it, the
 * same shape as a newer profile carrying no [prekey] clearing the pin). Carried, not derived: nothing on
 * either end can compute it. Every presentation field rides **both** profile paths — this frame and the
 * sealed [ProfilePayload] — or a sealed update would silently revert it.
 *
 * [loraNode] is the Meshtastic node number of the board this phone currently holds (unsigned 32 bits,
 * widened), so a contact's phone can line a post its own board heard on the radio channel up with this
 * one. Nullable rather than defaulted because 0 is not a node number; elided while unbound, and absent on a
 * newer profile it **clears** — a board handed on or unpaired is unsaid by omission, the prekey's shape. A
 * presentation field: it rides all three layouts and moves under the same LWW watermark. Self-asserted and
 * unsigned by the board, so a receiver treats a match as an attribution, never a verified identity — unless
 * [loraKey] lets it check one.
 *
 * [loraKey] is that board's Curve25519 public key (base64 of the 32 raw bytes, the `pubKey`/`avatarHash`
 * string convention), advertised **only while the board's firmware signs**: Meshtastic 2.8 signs every
 * broadcast a board originates that fits, and a contact's phone verifies a heard post's signature against
 * this key (`mesh/crypto/XeddsaVerify`) before showing the post as ours. Same shape as [loraNode] — nullable,
 * elided while unbound or on a board that does not sign, absent clears, a presentation field on all three
 * layouts. What it proves is bounded: the radio named here transmitted the words, not that this person
 * typed them. Bound to this identity by the frame's own signature, which covers every field of this content.
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
    val prekey: PrekeyInfo? = null,
    val version: Long? = null,
    val openToChat: Boolean = false,
    val loraNode: Long? = null,
    val loraKey: String? = null,
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

/**
 * Content of a [FrameType.BLOB_REQ] frame: the content hash of the requested blob.
 *
 * [offset] is how many bytes of it the asker already holds from a transfer a link drop cut off, so a holder can
 * stream only the rest (work item #116, ADR 2026-10.wtyc). Null for a whole file, and omitted from the CBOR then,
 * so a plain ask is byte-identical to every older build's. A holder that predates the field skips it and serves
 * the whole file, which the asker takes as before; one that knows it echoes it in the link's `FILE_HEADER`.
 *
 * [fastOnly] true asks for the bytes over Wi-Fi Aware only (ADR 2026-10.js2p): the asker's user has not approved a
 * fetch of an attachment declared over their limit, so a holder serves it over a live Wi-Fi Aware link, or one it
 * raises for it, and otherwise not at all — never over Bluetooth. Null for every other ask, and omitted from the CBOR
 * then, so a plain ask stays byte-identical. A holder that predates the field skips it and serves as it always has.
 *
 * [pull] is how many phones deep this ask may still pull the blob through, the receiver counted (#195, ADR
 * 2026-10.2drc): a receiver that lacks the blob wants it with `pull − 1` and asks its own neighbours with that, and at
 * 0 only queries and fetches; asked with 0 it serves the blob if it holds it and makes no want. An asker sends its
 * want's budget, `BlobExchange.PULL_BUDGET` for a want of its own. Absent reads as 1 — a fetch only, never a pull
 * further — which is what an older build's ask and the iOS port's today get. [until] is the asker's root's deadline for
 * pulling through, epoch ms on the frame's own clock (a DM's `sentAt` plus custody's TTL): past it nobody pulls the blob
 * through or wants it for someone else. Null for no deadline. Both are omitted when null; an older reader skips them.
 */
@Serializable
data class BlobReqContent(
    val hash: String,
    val offset: Long? = null,
    val fastOnly: Boolean? = null,
    val pull: Int? = null,
    val until: Long? = null,
)

/**
 * Content of a [FrameType.BLOB_HAVE] frame: the content hashes the asker wants to know the holders of, at most
 * `BlobExchange.MAX_QUERY_HASHES` (a responder answers the first that many and ignores the rest). ADR 2026-10.6e5t.
 */
@Serializable
data class BlobHaveContent(
    val hashes: List<String>,
)

/**
 * One hash's answer inside a [BlobHasContent]. [state] is always on the wire (no default), one of the constants
 * below; a state this build does not know is never taken for [HAVE] (a later build may answer [PARTIAL]).
 *
 * [size] is the whole blob's length, given with [HAVE] and [SENDING], so the asker can tell whether the Wi-Fi Aware
 * link is worth raising for it. [held] is reserved for [PARTIAL] — the bytes held from 0 — and no build emits it yet.
 * [eta] rides [SENDING] when the holder can tell: the ms until the asker's copy should have landed, its own feed's
 * measured end or, parked behind another copy's feed, that feed's end plus its own (ADR 2026-10.nsr7). The asker holds
 * on the holder that long rather than ask another.
 */
@Serializable
data class BlobHasItem(
    val hash: String,
    val state: Int,
    val size: Long? = null,
    val held: Long? = null,
    val eta: Long? = null,
) {
    companion object {
        /** Not held, and not on its way here. */
        const val NONE = 0

        /** Held whole: a `blobreq` from the asker now is served. */
        const val HAVE = 1

        /** Held, and a copy is already queued or streaming to the asker (or was just enqueued): wait for it. */
        const val SENDING = 2

        /** Not held yet, but on its way here — streaming in, landing, or being stored: ask again shortly. */
        const val FETCHING = 3

        /** Reserved: a prefix of [held] bytes, kept from a cut transfer. Not emitted until a range ask exists. */
        const val PARTIAL = 4
    }
}

/**
 * Content of a [FrameType.BLOB_HAS] frame: the answer to one [BlobHaveContent], one [items] entry per hash asked.
 * [load] is how many files the responder is feeding right now (a Bluetooth holder's pace is split across them), and
 * [re] the query's envelope id, so an answer is matched to the query it answers. ADR 2026-10.6e5t.
 */
@Serializable
data class BlobHasContent(
    val items: List<BlobHasItem>,
    val load: Int? = null,
    val re: String? = null,
)

/**
 * Content of a [FrameType.BLOB_PROGRESS] frame: of the file under [hash] streaming in from the frame's recipient, the
 * bytes the sender of the frame [held] (the whole length so far, a resumed stream's kept prefix included) [ms] after its
 * header arrived, by the receiver's own clock — so the rate between two reports is the drain, whatever a report waited
 * for on the way back (ADR 2026-10.nsr7).
 */
@Serializable
data class BlobProgressContent(
    val hash: String,
    val held: Long,
    val ms: Long,
)

/**
 * Content of a [FrameType.BLOB_CANCEL] frame: the sender withdraws its ask for the blob under [hash] from the frame's
 * recipient (ADR 2026-10.nsr7's cancel amendment).
 */
@Serializable
data class BlobCancelContent(
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
 * "did this mention me" detection (display names aren't unique); [name] is the exact label text after
 * the "@" that the sender rendered — the display name, or `Name (Alias)` when the sender knew two people
 * by that name (ADR 058) — so the receiver can locate the "@name" span for highlighting. A plain nested
 * value.
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
 * never received (store-and-forward), was deleted, or scrolled out of the local store. It is the sender's
 * **claim**, not a fact: [messageId] is a random id bound to no author, so a receiver judges the quote
 * against the original it holds under that id — same thread, same [authorId] — and rebuilds it from that
 * original, or shows the snapshot marked unverified when it holds none (ADR 2026-10.gpa3). [authorId] is the
 * quoted message's sender node id, which the receiver names by its own label for it ("You" when it is its
 * own); [author] is a display-name snapshot the composer still writes (never the literal "You") but no
 * receiver renders. [snippet] is a capped copy of the quoted body (blank for an attachment-only original);
 * [hasAttachment] lets the UI show a "photo" placeholder when [snippet] is blank even if the original isn't
 * present locally. Additive/optional on both [ChatContent] (broadcast) and
 * [app.getknit.knit.mesh.crypto.MessageContent] (encrypted DM/group). A plain nested value.
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
 *
 * [departed] (additive nullable, like the photo fields) lists members who have left, so the frame's
 * *founding* roster — [members] ∪ [departed], the set [id] is derived from — stays reconstructible after
 * a departure. Receivers verify that derivation before pinning a first-seen group and treat [departed]
 * as arithmetic only: local leave tombstones grow solely from signed `groupleave` frames, never from this
 * list (a member could otherwise "kick" someone by asserting they left). Null/absent on frames from
 * pre-field builds; a first sight through such a frame after a departure is refused as unverifiable (the
 * narrow trade documented in `InboundPipeline.vetRoster`).
 */
@Serializable
data class GroupInfo(
    val id: String,
    val name: String? = null,
    val members: List<String>,
    val createdBy: String,
    val photoHash: String? = null,
    val photoUpdatedAt: Long? = null,
    val departed: List<String>? = null,
) {
    companion object {
        /**
         * Roster cap (founding members incl. the creator), enforced on inbound first sight as well as in
         * the create UI — an unbounded wire roster would be an unbounded storage/fan-out surface.
         */
        const val MAX_MEMBERS = 8
    }
}

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
 * The X3DH-style session initiation attached to a v2 DM's [RatchetHeader] on **every** frame until the
 * session confirms (any one of them may be the first to arrive): the initiator's ephemeral X25519 pub
 * [eph], the responder signed-prekey id [pkid] it was derived against, and the session-establishment
 * clock [at] (constant across re-sends; the receiver's replacement/idempotence anchor — see
 * docs/FORWARD_SECRECY_RATCHET.md §7). The initiator's identity key is NOT repeated here: it is the
 * `hpkePub` already pinned from their profile, which `verifyInbound` requires before any DM is
 * processed. A plain `class` (see [WrappedKey]).
 */
@Serializable
class RatchetInit(
    @ByteString val eph: ByteArray,
    val pkid: Int,
    val at: Long,
)

/**
 * The v2 epoch-ratchet header inside an [EncEnvelope] (crypto scheme v2 — docs/FORWARD_SECRECY_RATCHET.md).
 * [se] is the sender's epoch number, [ek] the sender's epoch X25519 pub (the receiver's next DH base —
 * carried on every frame because custody eviction makes any single frame's arrival unreliable), [pe]
 * which of the *receiver's* epochs supplied the sender's DH base (0 = their signed prekey, only legal
 * with [init] attached), and [n] the index in the epoch's forward-only message chain. [flags] bit 0
 * marks a session reset request. Integrity needs no extra MAC: tampering changes the derived AEAD key,
 * and the whole payload is under the frame signature. A plain `class` (see [WrappedKey]).
 */
@Serializable
class RatchetHeader(
    val se: Int,
    @ByteString val ek: ByteArray,
    val pe: Int,
    val n: Int,
    val init: RatchetInit? = null,
    val flags: Int = 0,
) {
    companion object {
        /** [flags] bit: this frame initiates a session *replacement* (reset request). */
        const val FLAG_RESET = 0x1
    }
}

/**
 * A ratchet signed prekey as published in [ProfileContent.prekey]: the raw X25519 public key [pub],
 * its monotonically increasing [id], and a detached Ed25519 signature [sig] by the owner's identity
 * signing key over `RatchetCrypto.spkSigningBytes(id, pub)`. Detached — even though the profile frame
 * signature also covers this field — so the prekey stays re-verifiable once stored apart from its
 * frame (the peers table), and so a non-Knit implementation can verify one in isolation. A plain
 * `class` (see [WrappedKey]).
 */
@Serializable
class PrekeyInfo(
    val id: Int,
    @ByteString val pub: ByteArray,
    @ByteString val sig: ByteArray,
)

/**
 * The group sender-key header inside an [EncEnvelope] (crypto scheme v2, group form —
 * docs/GROUP_FORWARD_SECRECY.md): the sender's group epoch [se] and index [n] in that epoch's
 * forward-only message chain. Tiny by design — the groupId rides [RelayEnvelope.group], the sender on
 * the envelope, and the epoch seed itself never appears on a group frame (it travels pairwise inside
 * ctl DMs, [app.getknit.knit.mesh.crypto.MessageContent] `CTL_GROUP_KEY`). Integrity needs no
 * extra MAC: tampering changes the derived AEAD key, and the whole payload is under the frame
 * signature.
 */
@Serializable
class GroupRatchetHeader(
    val se: Int,
    val n: Int,
)

/**
 * One group send-epoch seed as distributed inside a v2 ctl DM: the sender's [epoch] number, the raw
 * 32-byte [seed] its chain derives from, and the [mintedAt] stamp receivers key their rows by
 * (idempotence across custody re-serves; last-writer-wins across a wiped sender's re-mint). A plain
 * `class` (see [WrappedKey]).
 */
@Serializable
class GroupSeed(
    val epoch: Int,
    @ByteString val seed: ByteArray,
    val mintedAt: Long,
)

/**
 * The shared **group root** the spool plane's group scopes derive from (`docs/SPOOL_PROTOCOL.md` §3.2):
 * the raw 32-byte [root], its [version] (which doubles as the scope epoch — a departure re-mint rotates
 * root and version together, so scope id and seal keys rotate as one), and the [minter] that originated
 * it. Ordering is `(version, minter)` lexicographic; a receiver adopts only a strictly greater pair, and
 * only from a founding-roster minter within the spec's version ceiling.
 *
 * Its own type rather than fields on [GroupKeyPayload] because `@ByteString ByteArray` fields are kept
 * non-default — docs/WIRE_COMPAT.md rule 1's exception, the same reason [GroupSeed] and [RatchetInit]
 * are types.
 */
@Serializable
class GroupRootPayload(
    @ByteString val root: ByteArray,
    val version: Int,
    val minter: String,
)

/**
 * The `gk` payload for the group-key ctl values (rides inside the encrypted
 * [app.getknit.knit.mesh.crypto.MessageContent], additive): a distribution (`CTL_GROUP_KEY`) carries
 * one or more [keys] (the current epoch, plus the still-draining previous one on a key-request
 * response); a key request (`CTL_GROUP_KEY_REQ`) carries [groupId] with [keys] empty; an adoption
 * ack (`CTL_GROUP_KEY_ACK`) echoes [groupId] + [ackEpoch].
 *
 * [gr] is the shared group root gossiped on this same channel (docs/SPOOL_PROTOCOL.md §3.2) — additive,
 * and deliberately **independent of [keys]**: a root-only distribution carries [keys] empty, so a
 * receiver must adopt [gr] outside whatever short-circuit its seed path applies to an empty key list.
 *
 * [group] is the group's **founding roster** — [GroupInfo.id], [GroupInfo.members], [GroupInfo.departed],
 * [GroupInfo.createdBy] and the [GroupInfo.name], never the photo (a blob to pull, which rides the group's
 * own frames) — carried on every distribution so a member holding no row for the group can pin it from
 * the seed itself, through the same door and derivation check a group frame goes through. Without it a
 * member reachable only over a relay never learns the roster at all: the seed rides their DM scope, the
 * roster rides only the group scope, and that scope derives from the root inside the seed they could not
 * adopt without the roster (work item #47). Additive (docs/WIRE_COMPAT.md rule 1): an older receiver
 * ignores it and parks the seed as before; an older sender omits it and the receiver parks as before.
 */
@Serializable
data class GroupKeyPayload(
    val groupId: String,
    val keys: List<GroupSeed> = emptyList(),
    val ackEpoch: Int? = null,
    val gr: GroupRootPayload? = null,
    val group: GroupInfo? = null,
)

/**
 * The `rp` payload for a `CTL_REACTION` ctl frame (rides inside the encrypted
 * [app.getknit.knit.mesh.crypto.MessageContent], additive): the sealed replacement for the cleartext
 * [ReactionContent] frame wherever a ratchet session (DM) or group chain (group form) can carry it —
 * docs/ENCRYPTED_RECEIPTS_REACTIONS.md. Same semantics as the cleartext form: [emoji] null is a
 * retraction, the reactor is the frame's senderId, and last-writer-wins convergence keys on the
 * frame's `sentAt` (never on the frame id, which is fresh per emit).
 */
@Serializable
data class ReactionPayload(
    val messageId: String,
    val emoji: String? = null,
)

/**
 * The `sc` payload for a `CTL_SCOPE_CONFIG` ctl frame (`docs/SPOOL_PROTOCOL.md` §5, additive): the relays a
 * conversation's scope lives on and the retention bounds its members assume, sealed inside the conversation like any
 * message — the DM form for a pair, the group sender-key form for a group. [groupId] is null for the DM pair of the
 * frame's two endpoints and the group's id otherwise; the receiver refuses one that disagrees with the conversation
 * the frame arrived in. The issuer is the frame's authenticated sender (C-5-2), never a field here, and two configs
 * order on `(version, issuer)` (C-5-4).
 *
 * Every field but [groupId] is required (`docs/WIRE_COMPAT.md` rule 6): a config that encodes as `{}` would read as
 * null here and as a config of defaults on a port that fills them in, and a port must never invent a spool list or a
 * bound. [spools] are `wss://` URLs, and one may carry a `?k=` bearer token (§7.1), so a log line redacts each.
 */
@Serializable
data class ScopeConfigPayload(
    val groupId: String? = null,
    val spools: List<String>,
    val maxFrames: Int,
    val ttlMs: Long,
    val maxBlob: Int,
    val version: Int,
)

/**
 * A sealed profile update, carried as [app.getknit.knit.mesh.crypto.MessageContent.pr] under
 * `CTL_PROFILE` — the encrypted replacement for re-flooding a cleartext [ProfileContent] frame at an
 * *established* contact.
 *
 * [version] is the sender's own profile version — the same monotonic value a cleartext profile frame
 * puts in its `sentAt`. Carrying it explicitly, rather than reusing the carrying chat frame's `sentAt`,
 * is what lets the two propagation paths order against **one** number: a re-sent ctl would otherwise
 * arrive stamped later than a genuinely newer cleartext profile and gate it out.
 *
 * Deliberately narrower than [ProfileContent]: only the presentation fields move. [ProfileContent.pubKey]
 * stays out because an identity re-pin is a trust-on-first-use event that must ride the authenticated
 * cleartext frame, and [ProfileContent.prekey] stays out because its whole job is to *start* a session —
 * sealing it under a session that must already exist is circular. Neither is a size decision; both are
 * bootstrap ones.
 *
 * [avatarHash] names the same content-addressed blob the cleartext form does. The carrying chat frame
 * repeats it in [ChatContent.attachmentHash] — the hash alone, never a mime (ADR 035) — so a blind
 * carrier, and the Internet plane's attachment pass, can fetch the bytes; see docs/WIRE_COMPAT.md's
 * DB v19 precedent.
 *
 * [openToChat] mirrors [ProfileContent.openToChat] — a presentation field, so it rides here too (defaulted,
 * elided while off): the sealed path overwrites the whole presentation set under a newer version, and a
 * field carried by the cleartext frame alone would be reverted by the next sealed update. [loraNode]
 * mirrors [ProfileContent.loraNode] for the same reason (nullable, elided while unbound, absent clears),
 * and [loraKey] mirrors [ProfileContent.loraKey] (the board's signing key, base64; elided unless the board
 * signs).
 *
 * [badges] is the one field here that is **not** on [ProfileContent], on purpose (docs/SUPPORTER_BADGE.md,
 * ADR 2026-10.r99s): the supporter credentials the sender chose to show, at most [BadgeEntry.MAX_PER_PROFILE],
 * in their display order. Sealed-only, so payment status reaches contacts and never a carrier or the LoRa
 * bootstrap; a receiver keeps them in a store of their own that only a sealed profile replaces, which is what
 * keeps a cleartext profile from reverting them. Nullable, elided while none is shown, and absent clears.
 */
@Serializable
data class ProfilePayload(
    val name: String,
    val status: String,
    val avatarHash: String? = null,
    val version: Long = 0L,
    val openToChat: Boolean = false,
    val loraNode: Long? = null,
    val loraKey: String? = null,
    val badges: List<BadgeEntry>? = null,
)

/**
 * One supporter badge a sealed profile carries ([ProfilePayload.badges], docs/SUPPORTER_BADGE.md): [w] is the
 * credential's wrapper exactly as the issuer signed it (the CBOR `{bc, sig}`, never its `knit-badge:v1:` text) and
 * [r] the 16 random bytes the holder committed to in its `sub`, which a contact needs to bind the badge to the
 * sender's node id. Both required, per docs/WIRE_COMPAT.md rule 6. A plain class equal by its bytes, whose
 * [toString] names neither: `r` is the one secret that keeps the issuer from naming the holder, so it never
 * reaches a log line.
 */
@Serializable
class BadgeEntry(
    @ByteString val w: ByteArray,
    @ByteString val r: ByteArray,
) {
    override fun equals(other: Any?): Boolean = other is BadgeEntry && w.contentEquals(other.w) && r.contentEquals(other.r)

    override fun hashCode(): Int = 31 * w.contentHashCode() + r.contentHashCode()

    override fun toString(): String = "BadgeEntry(${w.size} B)"

    companion object {
        /** The most a profile carries, and the most a receiver keeps from one. */
        const val MAX_PER_PROFILE = 4
    }
}

/**
 * The sealed `CTL_TRANSFER` payload — the signaling for a direct Wi-Fi file transfer between two contacts
 * (`transfer/TransferManager`). The bytes themselves never touch the mesh: this carries the offer ([name],
 * [size], [mime]), the answer, and — on the sender's READY — the one-shot Wi-Fi Direct group the receiver
 * joins ([ssid]/[passphrase]), the TCP [port] the sender listens on, and the per-transfer [key] (base64,
 * 32 bytes) the stream's client proof and trailer are keyed with. Every field but [id]/[phase] is nullable
 * and phase-specific, per docs/WIRE_COMPAT.md rule 1; [key] is base64 rather than `@ByteString` for that
 * document's no-defaulted-ByteArray rule. [name] is sender-supplied text and is normalized through
 * [AttachmentName] at the decode boundary, exactly like `MessageContent.attachmentName`.
 */
@Serializable
data class TransferPayload(
    val id: String,
    val phase: Int,
    val name: String? = null,
    val size: Long? = null,
    val mime: String? = null,
    val ssid: String? = null,
    val passphrase: String? = null,
    val port: Int? = null,
    val key: String? = null,
    val reason: Int? = null,
) {
    companion object {
        const val PHASE_OFFER = 1
        const val PHASE_ACCEPT = 2
        const val PHASE_DECLINE = 3
        const val PHASE_CANCEL = 4
        const val PHASE_READY = 5

        const val REASON_USER = 1
        const val REASON_BUSY = 2
        const val REASON_NO_WIFI = 3
        const val REASON_JOIN_FAILED = 4
        const val REASON_TIMEOUT = 5

        // Local-only outcomes, numbered in the same registry so a record has one vocabulary for "why".
        const val REASON_CORRUPT = 6
        const val REASON_CONNECTION = 7
        const val REASON_NO_SPACE = 8
        const val REASON_FOREGROUND = 9
    }
}

/**
 * The sealed `CTL_CALL` payload — the signaling of one 1:1 voice call (`call/CallManager`, docs/VOICE_CALLS.md
 * §3.2). Experimental, and spoken only by builds that advertise `Protocol.CAP_CALL`. [id] names one call attempt in
 * both directions; [ph] is its phase; [gen] the negotiation generation (> 0 is the caller's ICE restart, §3.8).
 * [sdp] rides the OFFER and the ANSWER with the gathered candidates folded in, [cand] carries the late ones in a
 * single CANDS (an empty list ends them), and [rsn] says why a DECLINE, CANCEL or END happened. [m] is the media
 * bitmask (1 audio; 2 video is reserved). Every field but [id]/[ph] is nullable or defaulted, per
 * docs/WIRE_COMPAT.md rule 1. The media is DTLS-SRTP, so the `a=fingerprint` inside [sdp] — sealed and signed by
 * the peer — is what authenticates it (§3.7).
 *
 * A call between neighbours can carry its media over a Wi-Fi Direct group (§3.10, `call/DirectCallPath`): the
 * caller's OFFER names the group it will host in [dw], and the callee answers [dx] = [DIRECT_JOINING] on its
 * RINGING (or on its ANSWER, when it could only commit at answer) once it is joining. A build that knows neither
 * ignores both, and the call rides its Internet path as before.
 *
 * [ct] rides an OFFER or ANSWER that carries the walkie bit (§3.11), set when the sender holds a cast ticket on a spool
 * it is connected to (§2.7): a spool that sells casts carries them on a scope one ticketed member holds, both ways, so
 * the flag tells a free peer its own chunks will be carried there too. A hint for the sender's uploads and its link
 * notice, never a grant: the spool checks the ticket it holds, and a build that ignores the flag only casts less.
 *
 * [hr] rides a walkie OFFER or ANSWER too (C-3.11-10): the sender reports each burst it hears back to the burst's
 * speaker, in a `talk` frame of its own, so the speaker can tell a radio path that loses its chunks and cast them
 * through a relay as well. Only a peer that set it is sent a report; a build that ignores it only never casts on one.
 */
@Serializable
data class CallPayload(
    val id: String,
    val ph: Int,
    val gen: Int = 0,
    val sdp: String? = null,
    val cand: List<String>? = null,
    val rsn: Int? = null,
    val m: Int = MEDIA_AUDIO,
    val dw: DirectInvite? = null,
    val dx: Int? = null,
    val ct: Boolean = false,
    val hr: Boolean = false,
) {
    companion object {
        const val PHASE_OFFER = 1
        const val PHASE_RINGING = 2
        const val PHASE_ANSWER = 3
        const val PHASE_DECLINE = 4
        const val PHASE_CANCEL = 5
        const val PHASE_END = 6
        const val PHASE_CANDS = 7

        const val REASON_USER = 1
        const val REASON_BUSY = 2
        const val REASON_TIMEOUT = 3
        const val REASON_NO_ROUTE = 4
        const val REASON_MEDIA = 5
        const val REASON_ERROR = 6

        const val MEDIA_AUDIO = 1

        /**
         * [m]'s walkie bit (§3.11, ADR 2026-10.79yb; 2 stays video's). Alone, on an OFFER with no [sdp], it is a
         * walkie session — no media, each burst streamed over the mesh in [FrameType.TALK] frames — which a build
         * without it declines for its media, the audio bit being absent. Beside [MEDIA_AUDIO] on a live OFFER, and
         * echoed on the ANSWER, it says that side can drop to a walkie when the call's path is lost; a build that
         * ignores it only never does.
         */
        const val MEDIA_WALKIE = 4

        /** [dx]: the callee is joining the caller's group, so the caller hosts it. */
        const val DIRECT_JOINING = 1
    }
}

/**
 * The Wi-Fi Direct group a caller offers to host for a call between neighbours (`CallPayload.dw`, §3.10): the group's
 * name [s] and passphrase [p], the TCP port [o] its call lane listens on, and the lane's 32-byte secret [k] (base64,
 * per docs/WIRE_COMPAT.md's no-defaulted-ByteArray rule). Single-use, minted per call, and sealed with the OFFER like
 * a transfer's READY. Every field is nullable so a malformed invite costs the invite, never the frame; the reader
 * validates all four before it joins anything.
 */
@Serializable
data class DirectInvite(
    val s: String? = null,
    val p: String? = null,
    val o: Int? = null,
    val k: String? = null,
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
 *
 * **v2 (the ratchet schemes — both landed in the same, never-released bump, so they share one
 * version)**: the AEAD key is *derived* from ratchet state, never wrapped — [keys] is empty. The two
 * forms are discriminated by addressing, not by [v]: a **DM** ([RelayEnvelope.recipientId] set)
 * carries the epoch-ratchet header [r] (docs/FORWARD_SECRECY_RATCHET.md); a **group** frame
 * ([RelayEnvelope.group] set) carries the sender-key header [g] (docs/GROUP_FORWARD_SECRECY.md).
 * v1 ↔ v2 discrimination is [v] alone; [r]/[g] are additive (nullable, ignored by older builds) per
 * docs/WIRE_COMPAT.md rule 1, with `@ByteString` bytes living inside the new [RatchetHeader]/
 * [RatchetInit] types rather than as defaulted fields here (rule 1's exception).
 *
 * **v3 (the compact DM form, ADR 059)** is v2's DM form with two things removed from the bytes and
 * nothing removed from the shape: [nonce] rides **empty** (the AEAD nonce is derived from the single-use
 * message key, `RatchetCrypto.messageNonce`), and the plaintext inside [ct] is the labeled
 * [app.getknit.knit.mesh.crypto.MessageContentV2] schema rather than `MessageContent`'s named one. The
 * ratchet header is additionally bound into the AEAD's associated data, which is what lets a
 * `relay = false` v3 tick travel with no frame signature at all. [nonce] stays a required field rather
 * than becoming nullable because a build that cannot decode the envelope cannot **carry** it either
 * (`InboundPipeline.canCarry` decodes the chat payload), and custody must converge across builds
 * (ADR 006) — so a v3 frame is shaped so that every fielded build decodes it, refuses it at the
 * version gate, and keeps custodying it. Group form stays v2.
 */
@Serializable
class EncEnvelope(
    val v: Int = 1,
    @ByteString val nonce: ByteArray,
    @ByteString val ct: ByteArray,
    val keys: List<WrappedKey>,
    val r: RatchetHeader? = null,
    val g: GroupRatchetHeader? = null,
) {
    companion object {
        /** Highest crypto-scheme version this build understands; a higher [v] is dropped on delivery. */
        const val MAX_SUPPORTED_VERSION = 3

        /** The ratchet schemes (DM form requires [r]; group form requires [g] — see the class kdoc). */
        const val VERSION_RATCHET = 2

        /**
         * The compact DM form: v2's ratchet, an empty (derived) [nonce], the labeled plaintext — DM form
         * only. A group-addressed v3 envelope is malformed (`RATCHET_BAD_HEADER`) on every v3 build, so a
         * compact group form is a new version, never a v3 variant (ADR 059).
         */
        const val VERSION_DM_V3 = 3

        /** Whether [v] names a DM epoch-ratchet scheme (v2 or v3 — both carry [r]; the group form is v2 only). */
        fun isDmRatchetVersion(v: Int): Boolean = v == VERSION_RATCHET || v == VERSION_DM_V3
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
            // non-kotlinx codecs (e.g. an iOS SwiftCBOR client); pinned as the launch-baseline wire layout. Every field
            // of the frozen contract is explicit — see docs/WIRE_COMPAT.md.
            useDefiniteLengthEncoding = true
        }

    fun encodeWire(wire: WireEnvelope): ByteArray = cbor.encodeToByteArray(wire)

    /**
     * Decodes the outer wrapper, or null if the bytes are malformed/unrecognized. A negative `ttl` or `hops` is refused:
     * no encoder writes one, kotlinx reads a 32-bit unsigned `0xFFFFFFFF` as -1, and iOS's `int32` refuses it.
     */
    fun decodeWire(bytes: ByteArray): WireEnvelope? =
        runCatching { cbor.decodeFromByteArray<WireEnvelope>(bytes) }.getOrNull()?.takeIf { it.ttl >= 0 && it.hops >= 0 }

    fun encodeEnvelope(env: RelayEnvelope): ByteArray = cbor.encodeToByteArray(env)

    /**
     * Decodes the signed routing envelope from [signed], or null if malformed — and null if any text string in it isn't
     * valid UTF-8, which kotlinx would repair silently and iOS refuses ([CborText], ADR 2026-10.qjap). Every plane
     * decodes `signed` here, so such a frame is never delivered, carried, relayed or listed in a digest.
     */
    fun decodeEnvelope(signed: ByteArray): RelayEnvelope? =
        if (!CborText.validUtf8(signed)) null else runCatching { cbor.decodeFromByteArray<RelayEnvelope>(signed) }.getOrNull()

    inline fun <reified T> encodePayload(content: T): ByteArray = cbor.encodeToByteArray(content)

    /**
     * Decodes the opaque per-type [payload] into [T], or null if absent/malformed. Text isn't checked here: a caller that
     * acts on a peer's payload uses [decodeChecked].
     */
    inline fun <reified T> decodePayload(payload: ByteArray): T? = runCatching { cbor.decodeFromByteArray<T>(payload) }.getOrNull()

    /** [decodePayload], and null if any text string in [payload] isn't valid UTF-8, as iOS refuses it (ADR 2026-10.qjap). */
    inline fun <reified T> decodeChecked(payload: ByteArray): T? = if (CborText.validUtf8(payload)) decodePayload<T>(payload) else null
}
