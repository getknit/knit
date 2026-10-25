@file:OptIn(ExperimentalSerializationApi::class) // @ByteString on the styles field is an experimental kotlinx API

package app.getknit.knit.mesh.crypto

import app.getknit.knit.data.message.MentionStore
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.message.StyleStore
import app.getknit.knit.data.message.replyRef
import app.getknit.knit.data.message.videoInfo
import app.getknit.knit.mesh.protocol.AttachmentName
import app.getknit.knit.mesh.protocol.CallPayload
import app.getknit.knit.mesh.protocol.GroupKeyPayload
import app.getknit.knit.mesh.protocol.Mention
import app.getknit.knit.mesh.protocol.ProfilePayload
import app.getknit.knit.mesh.protocol.ReactionPayload
import app.getknit.knit.mesh.protocol.ReplyRef
import app.getknit.knit.mesh.protocol.ScopeConfigPayload
import app.getknit.knit.mesh.protocol.StyleRunsSerializer
import app.getknit.knit.mesh.protocol.StyleSpan
import app.getknit.knit.mesh.protocol.TransferPayload
import app.getknit.knit.mesh.protocol.VideoInfo
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.ByteString
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray

/**
 * The plaintext payload of an encrypted DM/group message — everything that must stay private. It is
 * CBOR-serialized and AES-256-GCM-encrypted into [app.getknit.knit.mesh.protocol.EncEnvelope.ct]; the
 * cleartext [app.getknit.knit.mesh.protocol.RelayEnvelope] only keeps the routing metadata (id, sender,
 * recipientId/group) that relays need.
 *
 * [v] versions the *decrypted plaintext schema* — deliberately independent of
 * [app.getknit.knit.mesh.protocol.EncEnvelope.v] (the crypto scheme), so the two layers move
 * separately. It rides inside the authenticated ciphertext (it isn't on the wire). An unsupported
 * version is dropped on delivery (see `MeshManager.decrypt`).
 *
 * [attachmentKey] is the base64 AES key for the (separately encrypted, content-addressed by ciphertext
 * hash) image blob referenced by [attachmentHash]; null for text-only messages.
 *
 * Note [v] is elided on the wire while it equals [VERSION] (`encodeDefaults = false`), so it cannot
 * discriminate a second layout. The compact layout crypto scheme v3 carries — the same facts with integer
 * keys and raw ids, [MessageContentV2] — is therefore discriminated by `EncEnvelope.v` and keeps a
 * reserved label-0 version of its own; this class stays the domain object both layouts convert to.
 */
@Serializable
data class MessageContent(
    val v: Int = VERSION,
    val body: String,
    val mentions: List<Mention> = emptyList(),
    val attachmentHash: String? = null,
    val attachmentMime: String? = null,
    val attachmentKey: String? = null,
    // An arbitrary-file attachment's own name and byte count (additive; ADR 2026-09.qq2r). Both null for an image
    // or a voice note, whose bubbles describe themselves. Sealed rather than cleartext for ADR 035's
    // reason — a name is a far louder signal to a blind carrier than the mime that decision removed — and
    // spent as wire at all only because a name is the one thing about a file that is NOT a function of
    // bytes both ends hold, so `docs/WIRE_COMPAT.md`'s derive-don't-carry rule cannot reach it.
    //
    // [attachmentName] is normalized through [app.getknit.knit.mesh.protocol.AttachmentName] on decode, and
    // [attachmentSize] is a label the bubble draws before the bytes arrive — never an allocation bound
    // (docs/SPOOL_PROTOCOL.md C-4.5-8). Once the blob lands its stored length is the authority.
    val attachmentName: String? = null,
    val attachmentSize: Long? = null,
    // A short video's duration, upright size and poster (additive; ADR 2026-10.hmfe): what its bubble draws
    // before the bytes land, sealed like the name for the same reason. Labels only; the bytes are the authority.
    val video: VideoInfo? = null,
    // Quoted-reply reference for an encrypted DM/group, carried here (inside the ciphertext) so the
    // quoted author + snippet stay private — never on the cleartext [ChatContent.replyTo] for these.
    // Relies, like every field here, on cryptoCbor's `encodeDefaults = false` to stay off the wire when
    // null; do not enable encodeDefaults or every DM frame inflates with an empty reply.
    val replyTo: ReplyRef? = null,
    // Control marker for ratchet session management and sealed metadata (additive;
    // [CTL_SESSION_RESET], [CTL_GROUP_KEY], [CTL_GROUP_KEY_REQ], [CTL_GROUP_KEY_ACK], [CTL_RECEIPT],
    // [CTL_REACTION], [CTL_SCOPE_CONFIG], [CTL_PROFILE], [CTL_TRANSFER], [CTL_CALL]). A non-null value
    // means this frame is machinery, not conversation: it is never persisted as a message, never
    // acked-as-a-message, and notifies only as a reaction to a message we wrote, post-commit (ADR
    // 2026-10.59fu) — see docs/FORWARD_SECRECY_RATCHET.md §7. Inside the ciphertext deliberately: a relay cannot
    // distinguish machinery from an ordinary DM. An unknown value is consumed as a silent no-op,
    // which is what lets new ctl values ship additively.
    val ctl: Int? = null,
    // Group-key payload for the CTL_GROUP_KEY* values (additive; docs/GROUP_FORWARD_SECRECY.md §3).
    val gk: GroupKeyPayload? = null,
    // Acked frame id for [CTL_RECEIPT] (additive; docs/ENCRYPTED_RECEIPTS_REACTIONS.md).
    val ack: String? = null,
    // Reaction payload for [CTL_REACTION] (additive; docs/ENCRYPTED_RECEIPTS_REACTIONS.md).
    val rp: ReactionPayload? = null,
    // Profile payload for [CTL_PROFILE] (additive; docs/SEALED_PROFILE_UPDATES.md).
    val pr: ProfilePayload? = null,
    // Batched acked frame ids for [CTL_RECEIPT] (additive; docs/ENCRYPTED_RECEIPTS_REACTIONS.md §2).
    // Single-ack ticks keep [ack]; a batch is one custody-escalated group tick covering every id.
    val acks: List<String>? = null,
    // Direct-transfer signaling for [CTL_TRANSFER] (additive): the offer, answer and one-shot Wi-Fi Direct
    // credentials of a file transfer whose bytes never ride the mesh.
    val xf: TransferPayload? = null,
    // Voice-call signaling for [CTL_CALL] (additive; experimental, docs/VOICE_CALLS.md §3): the offer, answer,
    // late candidates and hang-up of a 1:1 call whose media never rides the mesh.
    val cl: CallPayload? = null,
    // A scope's relays and bounds for [CTL_SCOPE_CONFIG] (additive; docs/SPOOL_PROTOCOL.md §5). Named layout only:
    // the compact v3 codec refuses it, as it does `gk`, `xf` and `cl`.
    val sc: ScopeConfigPayload? = null,
    // The body's formatting as ranges (additive; ADR 2026-10.p95v): one CBOR byte string under "s", absent while
    // empty, so an unformatted message seals to the bytes it did before styles existed. Conversational: mapped by
    // [sealedContent], never set on a ctl. Label 17 in the compact layout ([MessageContentV2]). Normalised against
    // the clamped body on delivery (`InboundPipeline.deliverChat`), never trusted as sent.
    @SerialName("s")
    @ByteString
    @Serializable(with = StyleRunsSerializer::class)
    val styles: List<StyleSpan> = emptyList(),
) {
    @OptIn(ExperimentalSerializationApi::class)
    fun encode(): ByteArray = cryptoCbor.encodeToByteArray(this)

    /** Whether this build understands this content schema version. */
    fun isSupported(): Boolean = v <= MAX_SUPPORTED

    /**
     * This content with every open sender-supplied string brought inside its own rules — today just
     * [attachmentName] and the transfer offer's `xf.name`. Applied by **both** decoders ([decode] and [MessageContentV2.decode]) so the
     * repair happens once, at the boundary, and no call site downstream has to remember it.
     */
    internal fun normalized(): MessageContent {
        val name = attachmentName?.let(AttachmentName::sanitize)
        val transfer = xf?.let { it.copy(name = AttachmentName.sanitize(it.name)) }
        return if (name == attachmentName && transfer == xf) this else copy(attachmentName = name, xf = transfer)
    }

    companion object {
        /** Current plaintext-content schema version this build originates. */
        const val VERSION = 1

        /** Highest content schema version this build can read; a higher [v] is dropped on delivery. */
        const val MAX_SUPPORTED = 1

        /** [ctl]: this frame requests a ratchet session reset (carries a fresh init; not a message). */
        const val CTL_SESSION_RESET = 1

        /** [ctl]: [gk] distributes one or more group send-epoch seeds (docs/GROUP_FORWARD_SECRECY.md §3). */
        const val CTL_GROUP_KEY = 2

        /** [ctl]: [gk] asks the recipient to re-send its current seeds for [GroupKeyPayload.groupId]. */
        const val CTL_GROUP_KEY_REQ = 3

        /** [ctl]: [gk] acknowledges adopting the sender's seed ([GroupKeyPayload.ackEpoch]) — stops re-sends. */
        const val CTL_GROUP_KEY_ACK = 4

        /**
         * [ctl]: [ack] is a sealed delivery receipt for the named frame — the encrypted replacement
         * for the cleartext receipt frame. Flips the sender's tick only; deliberately does NOT
         * vaccine-purge custody (a carrier can't read it, so nobody purges — the delivered DM ages
         * out on the custody TTL uniformly; docs/ENCRYPTED_RECEIPTS_REACTIONS.md).
         */
        const val CTL_RECEIPT = 5

        /** [ctl]: [rp] is a sealed reaction (or retraction) — DM form or group form. */
        const val CTL_REACTION = 6

        /**
         * [ctl]: [sc] names the relays a conversation's scope lives on and the bounds its members assume
         * (docs/SPOOL_PROTOCOL.md §5) — DM form for the pair, group sender-key form for a group. Applied last-writer-wins
         * on `(version, issuer)`, the issuer being the frame's authenticated sender; a config the receiver refuses is
         * the usual chain-advancing silent no-op, as is the whole ctl on a build that predates it.
         */
        const val CTL_SCOPE_CONFIG = 7

        /**
         * [ctl]: [pr] is a sealed profile update (name/status/avatar) for an already-established
         * contact — the encrypted replacement for re-flooding a cleartext `profile` frame. The
         * cleartext frame keeps its one irreplaceable job, first contact: it is authenticated against
         * the `pubKey` inside its own payload, so it can never be encrypted.
         */
        const val CTL_PROFILE = 8

        /**
         * [ctl]: [xf] is direct-transfer signaling — the offer, answer and one-shot Wi-Fi Direct credentials of
         * a file transfer whose bytes never ride the mesh (`transfer/TransferManager`). Never persisted as a
         * message; a build without the feature consumes it as the usual silent no-op.
         */
        const val CTL_TRANSFER = 9

        /**
         * [ctl]: [cl] is voice-call signaling (`call/CallManager`, docs/VOICE_CALLS.md §3) — experimental, sent only
         * toward a pinned profile carrying `Protocol.CAP_CALL`. Never persisted as a message; a build without the
         * feature consumes it as the usual silent no-op.
         */
        const val CTL_CALL = 10

        @OptIn(ExperimentalSerializationApi::class)
        fun decode(bytes: ByteArray): MessageContent? =
            runCatching { cryptoCbor.decodeFromByteArray<MessageContent>(bytes) }.getOrNull()?.normalized()
    }
}

/**
 * The sealed content of one of our own DM or group messages, read off its stored row. The one builder
 * `MeshManager.sendChat` and its re-seal (`resealAndFlood`, behind both `flushPendingFor` and the reset's
 * `resealRecentDmsTo`) share, so a conversational field added to [MessageContent] is mapped once. Two hand-built
 * copies drifted twice: the re-seal dropped [MessageContent.attachmentName] / [MessageContent.attachmentSize]
 * (ADR 2026-09.qq2r), so the first copy a reset peer could open was a file with no name (#131).
 * `MessageContentTest` fails when a new field is neither mapped here nor named as machinery.
 */
internal fun MessageEntity.sealedContent(): MessageContent =
    MessageContent(
        body = body,
        mentions = MentionStore.decode(mentions),
        attachmentHash = attachmentHash,
        attachmentMime = attachmentMime,
        attachmentKey = attachmentKey,
        attachmentName = attachmentName,
        attachmentSize = attachmentSize,
        video = videoInfo(),
        replyTo = replyRef(),
        styles = StyleStore.decode(styles),
    )
