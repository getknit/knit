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
     * A post overheard on a **foreign** mesh's public channel and re-published into Knit by the phone whose
     * board heard it (the Meshtastic LongFast bridge). Its author is not a Knit peer and has no node id: the
     * gateway signs the frame, and everything about the original speaker rides inside [MeshPostContent] as an
     * attribution, never as an identity.
     *
     * It is a type of its own rather than a [CHAT] with an extra field for two reasons, both about what an
     * *older* build would do with it. It would attribute the text to the gateway's own user; and it would
     * render it, which earns a sealed `CTL_RECEIPT` from every recipient — ticks that then ride the LoRa
     * plane from far pockets for a message nobody is waiting on. A new type is invisible to those paths
     * instead: `dispatchByType` never reaches `acknowledge`, so no receipt is ever owed.
     */
    const val MESH_POST = "meshpost"

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
            type == GROUP_LEAVE ||
            type == MESH_POST

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
     * **[MESH_POST] is the first type added to this list since the v1 baseline, and that has a cost worth
     * knowing.** This list is fixed on every build already in the field, so a build without it holds none of
     * these rows while we hold them all — two nodes with continuously different live sets, which is exactly
     * the custody-digest divergence ADR 006 exists to prevent. It is accepted only because the whole LoRa
     * plane is `BuildConfig.LORA_PLANE`-gated (debug on, release off), so no shipped build ever mints one;
     * it rides the same release gate the `0x05` transcoder flag-day already owes. Do not widen this list
     * again on the same reasoning without re-reading `docs/WIRE_COMPAT.md`.
     */
    fun isCustodial(type: String): Boolean = isReplayable(type) || type == PROFILE
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
 * Content of a [FrameType.MESH_POST] frame — one post overheard on a foreign mesh's public channel and
 * re-published into Knit by the gateway phone whose board heard it.
 *
 * **Everything here is an attribution, not an identity.** The frame's [RelayEnvelope.senderId] is the
 * gateway, which is who signed it and the only party any of this is authenticated against; [node] and [name]
 * are what an unauthenticated public channel said about itself, are trivially spoofable, and must never be
 * rendered as a Knit peer. Nothing in this payload creates a peer row, counts toward presence, or is
 * addressable.
 *
 * The fields split three ways. [body], [node] and [packetId] are the post itself — [packetId] rides because
 * the frame id is derived from it, so a receiver can check that derivation rather than take the id on trust.
 * [name] and [channel] are snapshots taken at mint, because the receiver has no way to look either up: it has
 * no NodeDB and no sight of the gateway board's channel table. [hops], [snrDeci] and [viaMqtt] describe how
 * the post reached *this pocket's* board — a property of the crossing, which is why they are carried rather
 * than derived, and the raw material of the volume measurement the receive-only phase exists to produce.
 *
 * [snrDeci] is deci-dB rather than the radio's own float so the encoding pins byte-exactly in
 * `GoldenVectorTest`; a tenth of a dB is well inside what the measurement can use.
 */
@Serializable
data class MeshPostContent(
    val body: String,
    /** The speaker's Meshtastic node number, widened from its unsigned 32 bits. Also renders its `!hex` id. */
    val node: Long,
    /** The Meshtastic packet id the frame id derives from, widened the same way. */
    val packetId: Long,
    /** `User.long_name` as the gateway's NodeDB had it at mint; null when the gateway had never heard one. */
    val name: String? = null,
    /** The public channel's name as the gateway's board reported it — `LongFast`, `LongTurbo`, `MediumFast`. */
    val channel: String? = null,
    val hops: Int? = null,
    val snrDeci: Int? = null,
    /** The post reached the gateway's mesh through somebody's MQTT uplink, so it may come from anywhere. */
    val viaMqtt: Boolean = false,
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
 */
@Serializable
data class GroupKeyPayload(
    val groupId: String,
    val keys: List<GroupSeed> = emptyList(),
    val ackEpoch: Int? = null,
    val gr: GroupRootPayload? = null,
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
 * field carried by the cleartext frame alone would be reverted by the next sealed update.
 */
@Serializable
data class ProfilePayload(
    val name: String,
    val status: String,
    val avatarHash: String? = null,
    val version: Long = 0L,
    val openToChat: Boolean = false,
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

    /** Decodes the outer wrapper, or null if the bytes are malformed/unrecognized. */
    fun decodeWire(bytes: ByteArray): WireEnvelope? = runCatching { cbor.decodeFromByteArray<WireEnvelope>(bytes) }.getOrNull()

    fun encodeEnvelope(env: RelayEnvelope): ByteArray = cbor.encodeToByteArray(env)

    /** Decodes the signed routing envelope from [signed], or null if malformed. */
    fun decodeEnvelope(signed: ByteArray): RelayEnvelope? = runCatching { cbor.decodeFromByteArray<RelayEnvelope>(signed) }.getOrNull()

    inline fun <reified T> encodePayload(content: T): ByteArray = cbor.encodeToByteArray(content)

    /** Decodes the opaque per-type [payload] into [T], or null if absent/malformed. */
    inline fun <reified T> decodePayload(payload: ByteArray): T? = runCatching { cbor.decodeFromByteArray<T>(payload) }.getOrNull()
}
