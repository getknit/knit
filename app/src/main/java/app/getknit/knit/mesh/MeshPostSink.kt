package app.getknit.knit.mesh

import app.getknit.knit.mesh.crypto.XeddsaVerify
import java.util.Locale

/**
 * Delivers a post the bound board heard on its own primary (slot 0) channel into the Meshtastic room —
 * locally, and nowhere else.
 *
 * It is a seam for the same reason the compact-DM seams are (`mesh/lora/LoraDirect.kt`): `mesh/lora/` is pure and
 * imports neither Android nor the data layer, while delivering a post means writing a Room row. The transport
 * decides *whether* a packet is a post ([app.getknit.knit.mesh.lora.PublicChannelPolicy]); this decides what a
 * message row made of one looks like.
 *
 * Nothing that passes through here is signed, originated, custodied or fanned out. The room is this phone's
 * own window onto its own radio's channel: a phone with no board never sees these posts, and two phones with
 * boards each hear the channel for themselves. That is what makes the room a mirror rather than a bridge, and
 * what keeps a foreign mesh's traffic off Knit's custody digests entirely.
 *
 * Implemented by [MeshManager]. Late-bound, like the compact-DM seams — the transport is constructed first.
 */
interface MeshPostSink {
    /**
     * Delivers one heard post into the room.
     *
     * Idempotent by construction rather than by bookkeeping. The row id is derived from the post
     * ([app.getknit.knit.mesh.protocol.FrameId.forMeshPost]), so the board replaying a packet it queued while
     * the phone was away lands on the row it already wrote (`MessageDao.insertIfAbsent`). Calling it twice
     * for one packet changes nothing.
     */
    suspend fun onPublicPostHeard(post: MeshPost)

    /**
     * Delivers one post heard on a bound MeshCore board's Public channel (slot 0) into the MeshCore room
     * (`Conversations.MESHCORE_PUBLIC`, ADR 2026-11.gsqu's Stage 6 amendment) — a stranger's, always.
     *
     * Idempotent the same way: the row id is derived from what every drain of the post carries
     * ([app.getknit.knit.mesh.protocol.FrameId.forMeshcorePost]), so the board's offline-queue replay, and a copy racing
     * it at connect, land on one row and notify once.
     */
    suspend fun onMeshcorePostHeard(post: MeshcorePost)
}

/**
 * One post heard on a MeshCore board's Public channel, judged by the plane (`mesh/lora/meshcore/MeshcorePublicJudge`) and
 * handed across the seam into the MeshCore room — the MeshCore twin of [MeshPost], with far less in it: a channel text
 * names no sender and carries no packet id and no signature.
 *
 * [name] is the self-chosen display name the sender's board put in front of the text (cleaned, null when there was none)
 * and proves nothing: anyone on the key can post under any name, `You` and our own board's `Knit xxxx` included, so it is
 * never resolved to a contact. [body] is the words, cleaned and clamped. [timestampSecs] and [raw] — the sender's stamp and
 * the text exactly as drained, before any cleaning — are what the row id is derived from and nothing else: a row orders
 * and ages by its arrival. [mentionedAs] is the `@[name]` token in [body] that names the user's own board, as written
 * ([MeshcoreMentions.mentionOf], read against the board's name when the post was heard), or null: the room notifies it as
 * a mention. It is a claim like the name: anyone can type it. [tag] is the post's MeshCore One reaction tag
 * ([MeshcoreReactions.tag], ADR 2026-11.gsqu's 2026-11-21 amendment), computed by the plane over the body's bytes as drained
 * and the sender's stamp — what a reaction to this post names it by — or null where none was computed; the room never
 * hashes heard text itself. [underOwnName] says [name] keys ([app.getknit.knit.identity.NameKey]) as the bound board's own
 * name did when the post was heard: our echo, or a namesake's — the room drops a reaction under it either way (lora's
 * design pass, 2026-11-21: losing a namesake's tapback is the cheaper error). A claim like the name, read for nothing else.
 * [hops] is the repeaters' hop count a flooded channel text's `path_len & 0x3F` carries (null when it arrived direct,
 * `0xFF`, which carries no count), and [snrDeci] the last hop's SNR to our board in tenths of a dB (null off a V1 frame):
 * the row's `originHops` / `originSnrDeci`, frozen with the first copy, as the Meshtastic room's are.
 *
 * Equal by content. [toString] names neither the text nor the name: a stranger's words never reach a log.
 */
class MeshcorePost(
    val name: String?,
    val body: String,
    val timestampSecs: Long,
    raw: ByteArray,
    val mentionedAs: String? = null,
    val tag: String? = null,
    val underOwnName: Boolean = false,
    val hops: Int? = null,
    val snrDeci: Int? = null,
) {
    private val bytes = raw.copyOf()

    /** A copy of the text as the receiving board drained it. */
    val raw: ByteArray get() = bytes.copyOf()

    override fun equals(other: Any?): Boolean =
        other is MeshcorePost &&
            other.name == name &&
            other.body == body &&
            other.timestampSecs == timestampSecs &&
            other.bytes.contentEquals(bytes) &&
            other.mentionedAs == mentionedAs &&
            other.tag == tag &&
            other.underOwnName == underOwnName &&
            other.hops == hops &&
            other.snrDeci == snrDeci

    override fun hashCode(): Int {
        var h = name.hashCode()
        h = h * PRIME + body.hashCode()
        h = h * PRIME + timestampSecs.hashCode()
        h = h * PRIME + bytes.contentHashCode()
        h = h * PRIME + mentionedAs.hashCode()
        h = h * PRIME + tag.hashCode()
        h = h * PRIME + underOwnName.hashCode()
        h = h * PRIME + hops.hashCode()
        return h * PRIME + snrDeci.hashCode()
    }

    override fun toString(): String =
        "MeshcorePost(named=${name != null}, ${body.length} ch, mention=${mentionedAs != null}, hops=${hops != null}, snr=${snrDeci != null})"

    private companion object {
        const val PRIME = 31
    }
}

/**
 * One post heard on the board's primary channel, as the board heard it — what `mesh/lora/PublicChannelPolicy`
 * produces out of a raw packet and [MeshPostSink] turns into a message row.
 *
 * Node numbers are Meshtastic's unsigned 32 bits, widened to `Long` here at the boundary where they stop
 * being the radio's and start being the row's — the type the origin columns are written against.
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
    /**
     * `Data.payload` exactly as heard. [body] is for reading — trimmed and clamped — while this is what the
     * sender's board signed, byte for byte; a signature is checked over these, never over [body].
     */
    val payload: ByteArray = body.encodeToByteArray(),
    /** The sender board's XEdDSA signature (64 bytes), or null when the packet came unsigned. */
    val signature: ByteArray? = null,
    /** Our own board verified [signature] against the key its NodeDB holds for [node] (`MeshPacket.xeddsa_signed`). */
    val boardVerified: Boolean = false,
    /**
     * What firmware 2.8.1 signs beyond [node], [packetId], the port and [payload] — the packet's `to` and the rest of
     * its `Data` envelope — as decoded off this packet, or null when they were not carried (then no signature can
     * be checked). A value of the crypto package's, not the radio's, so nothing above the seam learns a Meshtastic
     * type.
     */
    val signedFields: XeddsaVerify.SignedFields? = null,
) {
    /** The scalar members, for [equals]/[hashCode]: the byte arrays are compared by content beside them. */
    private fun scalars(): List<Any?> = listOf(node, packetId, body, name, channel, hops, snrDeci, viaMqtt, boardVerified, signedFields)

    /** The byte-array members make the generated `equals`/`hashCode` identity-based; compare the bytes instead. */
    override fun equals(other: Any?): Boolean =
        this === other ||
            (
                other is MeshPost &&
                    scalars() == other.scalars() &&
                    payload.contentEquals(other.payload) &&
                    (signature ?: EMPTY).contentEquals(other.signature ?: EMPTY)
            )

    override fun hashCode(): Int {
        var h = scalars().hashCode()
        h = h * PRIME + payload.contentHashCode()
        h = h * PRIME + (signature?.contentHashCode() ?: 0)
        return h
    }

    private companion object {
        const val PRIME = 31
        val EMPTY = ByteArray(0)
    }
}

/**
 * The heard-post attribution as it reaches storage: what the channel said about the speaker, and how the post
 * reached this board. [MeshPost] once the packet id has done its one job — deriving the row id — and what is
 * left is exactly what the row keeps.
 *
 * Passed to `InboundPipeline.deliverChat` as one value rather than six loose parameters, for the reason the
 * sealed [app.getknit.knit.mesh.crypto.MessageContent] is: it keeps the ordinary delivery path one shape,
 * with the heard case as a nullable beside it rather than a second body.
 */
data class MeshPostOrigin(
    val node: Long,
    val name: String?,
    val channel: String?,
    val hops: Int?,
    val snrDeci: Int?,
    val viaMqtt: Boolean,
    /**
     * The Knit contact whose profile claims [node] as its bound board, resolved once at ingest and frozen on
     * the row — null for a stranger. A node-number match against a self-asserted profile field, which the UI
     * keeps the unverified styling for unless [signed] says the post verified under that contact's own key.
     */
    val peerId: String? = null,
    /**
     * What the post's signature proved at ingest — a `MessageEntity.ORIGIN_*` value, frozen on the row with
     * [peerId]. `ORIGIN_SIGNED_BY_CONTACT` is the one value that makes the attribution a verified one.
     */
    val signed: Int = 0,
) {
    companion object {
        /**
         * The `originNode` every post heard in the MeshCore room is stored under (ADR 2026-11.gsqu's Stage 6 amendment,
         * security B1): a channel text names no node, but a heard row needs a non-null `originNode` — it is the one mark
         * that says a row in our sender column holds somebody else's words, which `writtenBy`, the own-send and
         * authored-thread queries, the reaction and quote rules all read. Negative, so it is never a Meshtastic node
         * number (32 unsigned bits, widened). No reader may turn it into a label: every one asks the room first
         * ([isMeshcoreOrigin]) and draws the post's own name, or "unnamed" — never `!ffffffff`.
         */
        const val MESHCORE_NODE: Long = -1L

        /** Whether a stored `originNode` is [MESHCORE_NODE] — a MeshCore room post, which has no node to show. */
        fun isMeshcoreOrigin(node: Long?): Boolean = node == MESHCORE_NODE
    }
}

/**
 * How Meshtastic writes a node number: `!` and eight lowercase hex digits, zero-padded (`NodeInfo`'s
 * `User.id`). What every Meshtastic client shows for a node it has no name for, so it is what Knit shows too —
 * a post from a stranger reads the same here as it does there.
 *
 * It lives beside the heard-post types rather than in `mesh/lora/`, where the packets are, because its callers
 * are all on the far side of the seam — the chat list, the bubble, the notification — and none of them may
 * depend on the radio package.
 */
fun meshNodeLabel(node: Long): String = "!%08x".format(Locale.ROOT, node and MESH_NODE_MASK)

/**
 * Who a MeshCore room post's notification is keyed on (ADR 2026-11.gsqu's Stage 6 amendment, security N3): `mc:` and the
 * SHA-256 of the name it was posted under, in hex — never the raw name, which the system keeps in its conversation
 * store. Posts under one name group under one person, which is all a name can say; an unnamed post keys on the empty
 * name.
 */
fun meshcoreSpeakerKey(name: String?): String =
    "mc:" +
        java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(name.orEmpty().encodeToByteArray())
            .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and BYTE_MASK) }

private const val BYTE_MASK = 0xFF

/** A Meshtastic node number is 32 unsigned bits; mask so a widened negative can never print sixteen digits. */
private const val MESH_NODE_MASK = 0xFFFFFFFFL
