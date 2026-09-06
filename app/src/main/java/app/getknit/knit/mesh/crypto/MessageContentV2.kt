@file:OptIn(ExperimentalSerializationApi::class) // Cbor and @CborLabel are experimental kotlinx APIs

package app.getknit.knit.mesh.crypto

import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.protocol.CanonicalText
import app.getknit.knit.mesh.protocol.EncEnvelope
import app.getknit.knit.mesh.protocol.FrameId
import app.getknit.knit.mesh.protocol.Mention
import app.getknit.knit.mesh.protocol.ProfilePayload
import app.getknit.knit.mesh.protocol.ReactionPayload
import app.getknit.knit.mesh.protocol.ReplyRef
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.CborLabel
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray

/**
 * The v3 sealed plaintext (crypto scheme v3, ADR 059): the same facts as [MessageContent], laid out for a
 * size-capped radio — integer map keys in place of the named ones, and every id, hash and key as its raw
 * bytes rather than the text the domain carries (a 22-char frame id is 16 bytes, a 26-char node id 16, a
 * 64-hex hash 32). A tick's plaintext drops from 39 B to 21, a twelve-ack batch by 72. The domain object
 * is unchanged: this is a codec, chosen per frame by the peer's capability, and both directions convert.
 *
 * **Discriminated by the envelope**: a v3 [EncEnvelope] carries this schema, a v2 one the named schema. The
 * label-0 version is the compact schema's own version axis (elided while it is the default, so it costs
 * nothing) — kept so the two layers stay independently versioned the way `MessageContent.v` and
 * `EncEnvelope.v` are, and mirrored by [MAX_SUPPORTED] on decode.
 *
 * **Canonical or not at all.** Every raw field round-trips: [encodeOrNull] refuses (returns null, and the
 * caller seals the named v2 form instead) any id, hash or key whose decoded bytes do not re-encode to the
 * exact string — the JDK base64 decoder and `NodeId`'s base32 both discard stray trailing bits, so a
 * pattern match would accept strings that come back different. No shipped build ever minted a
 * non-canonical id for anything that gets acked, reacted to or quoted, so the fallback is hostile-input
 * hygiene rather than a compatibility path, but it is what keeps this codec unable to lose a frame.
 * [MessageContent.gk] is not modelled (the group-key ctls stay v2; label 13 is reserved for a raw-seed form)
 * and refuses the same way.
 *
 * Nested types are this codec's own rather than the domain's: `Mention`/`ReplyRef`/`ReactionPayload`/
 * `ProfilePayload` are shared with the cleartext `ChatContent`, whose shape is frozen (docs/WIRE_COMPAT.md
 * rule 2). Plain classes, since they hold `ByteArray`s. Pure — JVM-tested in `MessageContentV2Test`.
 */
internal object MessageContentV2 {
    /** The compact schema version this build writes (label 0, elided while default). */
    const val VERSION = 1

    /** The highest compact schema version this build reads; a higher one decodes to null. */
    const val MAX_SUPPORTED = 1

    /** [content] in the compact layout, or null when something in it has no canonical raw form (see the kdoc). */
    fun encodeOrNull(content: MessageContent): ByteArray? {
        if (content.v != MessageContent.VERSION || content.gk != null) return null
        return try {
            compactCbor.encodeToByteArray(wireOf(content))
        } catch (_: NonCanonical) {
            null
        }
    }

    /** Signals one non-canonical id/hash/key inside [wireOf]: the whole content falls back to the named form. */
    private class NonCanonical : RuntimeException()

    private fun wireOf(content: MessageContent): Wire =
        Wire(
            body = content.body,
            mentions = content.mentions.map { MentionV2(nodeId = nodeIdBytes(it.nodeId), name = it.name) },
            attachmentHash = content.attachmentHash?.let(::hashBytes),
            attachmentMime = content.attachmentMime,
            attachmentKey = content.attachmentKey?.let(::keyBytes),
            attachmentName = content.attachmentName,
            attachmentSize = content.attachmentSize,
            replyTo = content.replyTo?.let(::replyRefOf),
            ctl = content.ctl,
            ack = content.ack?.let(::frameIdBytes),
            acks = content.acks?.map(::frameIdBytes),
            rp = content.rp?.let { ReactionV2(messageId = frameIdBytes(it.messageId), emoji = it.emoji) },
            pr =
                content.pr?.let {
                    ProfileV2(
                        name = it.name,
                        status = it.status,
                        avatarHash = it.avatarHash?.let(::hashBytes),
                        version = it.version,
                        openToChat = it.openToChat,
                        loraNode = it.loraNode,
                        loraKey = it.loraKey?.let(::b64d),
                    )
                },
        )

    private fun replyRefOf(ref: ReplyRef): ReplyRefV2 =
        ReplyRefV2(
            messageId = frameIdBytes(ref.messageId),
            authorId = nodeIdBytes(ref.authorId),
            author = ref.author,
            snippet = ref.snippet,
            hasAttachment = ref.hasAttachment,
        )

    private fun frameIdBytes(id: String): ByteArray = FrameId.toBytesOrNull(id) ?: throw NonCanonical()

    private fun nodeIdBytes(id: String): ByteArray = NodeId.toBytesOrNull(id) ?: throw NonCanonical()

    private fun hashBytes(hex: String): ByteArray = CanonicalText.hashBytesOrNull(hex) ?: throw NonCanonical()

    private fun keyBytes(key: String): ByteArray = CanonicalText.base64BytesOrNull(key) ?: throw NonCanonical()

    /** The domain [MessageContent] for compact [bytes], or null when malformed or a newer compact schema. */
    fun decode(bytes: ByteArray): MessageContent? =
        runCatching {
            val w = compactCbor.decodeFromByteArray<Wire>(bytes)
            if (w.v > MAX_SUPPORTED) return@runCatching null
            MessageContent(
                body = w.body,
                mentions = w.mentions.map { Mention(nodeId = nodeIdText(it.nodeId), name = it.name) },
                attachmentHash = w.attachmentHash?.let(CanonicalText::hashText),
                attachmentMime = w.attachmentMime,
                attachmentKey = w.attachmentKey?.let(::b64),
                attachmentName = w.attachmentName,
                attachmentSize = w.attachmentSize,
                replyTo =
                    w.replyTo?.let {
                        ReplyRef(
                            messageId = FrameId.fromBytes(it.messageId),
                            authorId = nodeIdText(it.authorId),
                            author = it.author,
                            snippet = it.snippet,
                            hasAttachment = it.hasAttachment,
                        )
                    },
                ctl = w.ctl,
                ack = w.ack?.let(FrameId::fromBytes),
                acks = w.acks?.map(FrameId::fromBytes),
                rp = w.rp?.let { ReactionPayload(messageId = FrameId.fromBytes(it.messageId), emoji = it.emoji) },
                pr =
                    w.pr?.let {
                        ProfilePayload(
                            name = it.name,
                            status = it.status,
                            avatarHash = it.avatarHash?.let(CanonicalText::hashText),
                            version = it.version,
                            openToChat = it.openToChat,
                            loraNode = it.loraNode,
                            loraKey = it.loraKey?.let(::b64),
                        )
                    },
            )
        }.getOrNull()?.normalized()

    private fun nodeIdText(bytes: ByteArray): String {
        require(bytes.size == NodeId.BYTES) { "a node id is ${NodeId.BYTES} bytes, got ${bytes.size}" }
        return NodeId.fromBytes(bytes)
    }

    /**
     * The top-level layout. Labels are append-only. **12 and 13 stay reserved** for the two additive
     * follow-ons — `12 = pad`, a length-hiding byte string a reader discards, and `13 = gk`, the group-key
     * payload with raw 32-byte seeds (44 B each as base64 today) — both readable by this build already,
     * since `ignoreUnknownKeys` skips a label it does not model: a new label is additive, a new *form* (the
     * group form) is a new envelope version. `14`/`15` are the file attachment's name and byte count (ADR
     * 2026-09.qq2r), which took the next free labels rather than the reserved pair. Never recycle a label.
     * The nested layouts are append-only too: `ProfileV2`'s label 5 is the open-to-chat flag (defaulted, so
     * elided while off — a profile without it decodes as before), label 6 the bound LoRa board's node
     * number (nullable, elided while unbound) and label 7 that board's signing key (32 raw bytes here, base64
     * on the public layouts; nullable, elided unless the board signs).
     */
    @Serializable
    @Suppress("MagicNumber") // the CBOR labels are the layout itself, pinned by GoldenVectorTest
    private class Wire(
        @CborLabel(0)
        val v: Int = VERSION,
        @CborLabel(1)
        val body: String = "",
        @CborLabel(2)
        val mentions: List<MentionV2> = emptyList(),
        @CborLabel(3)
        val attachmentHash: ByteArray? = null,
        @CborLabel(4)
        val attachmentMime: String? = null,
        @CborLabel(5)
        val attachmentKey: ByteArray? = null,
        @CborLabel(6)
        val replyTo: ReplyRefV2? = null,
        @CborLabel(7)
        val ctl: Int? = null,
        @CborLabel(8)
        val ack: ByteArray? = null,
        @CborLabel(9)
        val acks: List<ByteArray>? = null,
        @CborLabel(10)
        val rp: ReactionV2? = null,
        @CborLabel(11)
        val pr: ProfileV2? = null,
        @CborLabel(14)
        val attachmentName: String? = null,
        @CborLabel(15)
        val attachmentSize: Long? = null,
    )

    @Serializable
    @Suppress("MagicNumber") // the CBOR labels are the layout itself, pinned by GoldenVectorTest
    private class MentionV2(
        @CborLabel(1)
        val nodeId: ByteArray,
        @CborLabel(2)
        val name: String,
    )

    @Serializable
    @Suppress("MagicNumber") // the CBOR labels are the layout itself, pinned by GoldenVectorTest
    private class ReplyRefV2(
        @CborLabel(1)
        val messageId: ByteArray,
        @CborLabel(2)
        val authorId: ByteArray,
        @CborLabel(3)
        val author: String,
        @CborLabel(4)
        val snippet: String,
        @CborLabel(5)
        val hasAttachment: Boolean = false,
    )

    @Serializable
    @Suppress("MagicNumber") // the CBOR labels are the layout itself, pinned by GoldenVectorTest
    private class ReactionV2(
        @CborLabel(1)
        val messageId: ByteArray,
        @CborLabel(2)
        val emoji: String? = null,
    )

    @Serializable
    @Suppress("MagicNumber") // the CBOR labels are the layout itself, pinned by GoldenVectorTest
    private class ProfileV2(
        @CborLabel(1)
        val name: String,
        @CborLabel(2)
        val status: String,
        @CborLabel(3)
        val avatarHash: ByteArray? = null,
        @CborLabel(4)
        val version: Long = 0L,
        @CborLabel(5)
        val openToChat: Boolean = false,
        @CborLabel(6)
        val loraNode: Long? = null,
        @CborLabel(7)
        val loraKey: ByteArray? = null,
    )
}

/** What a DM-form seal site hands the ratchet: the plaintext bytes and the scheme they are laid out for. */
internal class SealBytes(
    val plaintext: ByteArray,
    val scheme: Int,
) {
    operator fun component1(): ByteArray = plaintext

    operator fun component2(): Int = scheme
}

/**
 * The bytes to seal this content under toward a peer, and the scheme: the compact v3 layout when the peer
 * reads it ([v3]) and the content has a canonical compact form, else the named layout under v2. A seal
 * site never picks v3 except through this, so a content the compact codec refuses can only ever fall
 * back — never be lost.
 */
internal fun MessageContent.sealBytes(v3: Boolean): SealBytes {
    if (v3) MessageContentV2.encodeOrNull(this)?.let { return SealBytes(it, EncEnvelope.VERSION_DM_V3) }
    return SealBytes(encode(), EncEnvelope.VERSION_RATCHET)
}
