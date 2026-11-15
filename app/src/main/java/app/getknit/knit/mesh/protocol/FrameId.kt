package app.getknit.knit.mesh.protocol

import app.getknit.knit.identity.NodeId
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Mints a compact, globally-unique frame/request id: 128 random bits ([SecureRandom]) rendered as a
 * 22-char unpadded base64url string. Replaces `UUID.randomUUID().toString()` (36 chars — hex with
 * hyphens — for the same entropy). The id is opaque and forwarded verbatim (it is the dedup key, the
 * custody key, and what a receipt/reaction references), so only uniqueness matters; a tighter encoding
 * just trims every chat/reaction/receipt frame that carries one. base64url (`[A-Za-z0-9_-]`, no padding)
 * keeps it CBOR-text-, URL-, and filename-safe.
 *
 * **Not opaque on the way in** (ADR 2026-10.9qr4, #178). Every inbound door refuses a frame whose id is not one of the
 * three shapes [admits] names, so changing this format, or minting any other shape, is a wire break: every build
 * that ships before the change drops the new ids. The gate exists because Android's database binds every string as
 * UTF-16, and SQLite drops one leading U+FEFF there (and byte-swaps the rest after a leading U+FFFE): an id that is
 * opaque to Kotlin and an iPhone is one custody and message row with its unmarked twin on this phone.
 */
object FrameId {
    /** The raw width of an id minted here. */
    const val ID_BYTES = 16

    /** The text width of an id minted here (`ceil(128 / 6)`, unpadded). */
    const val LENGTH = 22

    /**
     * A walkie chunk's id width: the first characters of a fresh id, 66 random bits — ample for a dedup key that lives
     * minutes, and 11 bytes off every chunk (`MeshManager.sendCallTalk`).
     */
    const val TALK_LENGTH = 11

    private const val BASE64URL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    private val rng = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    /** A fresh random id. Thread-safe: [SecureRandom] and the stateless [Base64] encoder allow concurrent use. */
    fun new(): String = encoder.encodeToString(ByteArray(ID_BYTES).also { rng.nextBytes(it) })

    /**
     * The 16 raw bytes behind [id] when it is exactly what [new] mints — 22 base64url chars whose
     * re-encoding reproduces the string byte-for-byte — else null. The round trip, not a pattern, is the
     * test: the JDK decoder silently discards the two leftover bits of the last char, so a non-canonical
     * string would otherwise decode "successfully" to bytes that encode back to a *different* id. Ids
     * are opaque strings everywhere else (the class kdoc); only the v3 compact plaintext
     * ([app.getknit.knit.mesh.crypto.MessageContentV2]) carries them raw, and it falls back to text for
     * anything this refuses.
     */
    fun toBytesOrNull(id: String): ByteArray? {
        if (id.length != LENGTH) return null
        val bytes = runCatching { decoder.decode(id) }.getOrNull() ?: return null
        return bytes.takeIf { it.size == ID_BYTES && encoder.encodeToString(it) == id }
    }

    /**
     * Whether an inbound [env] carries an id of the shape its type is minted with — the gate every door asks before
     * anything dedups, custodies, stores or relays the frame (`MeshRouter.handleInbound`, `ScopeFrames.open`):
     *  - a `profile`: exactly `profile-<senderId>-<sentAt>`, the sender a canonical node id and the stamp in decimal,
     *    which is what every build and the iOS port mint (`MeshManager.ownProfile`). Bound to the sender, so nobody
     *    can file a frame under another node's profile id;
     *  - a `talk`: [TALK_LENGTH] base64url characters, the prefix of a fresh id a walkie chunk carries;
     *  - anything else, an unknown type included: exactly what [new] mints ([toBytesOrNull]).
     *
     * Every shape is ASCII, so no id this admits has a leading U+FEFF or U+FFFE for SQLite to drop or swap: two ids
     * that differ in Kotlin differ in every table. It does not stop a frame filed under another frame's exact id
     * (`docs/NEXT_WIRE_BREAK.md` §10): that needs ids bound to their author.
     */
    fun admits(env: RelayEnvelope): Boolean =
        when (env.type) {
            FrameType.PROFILE -> NodeId.toBytesOrNull(env.senderId) != null && env.id == profileId(env.senderId, env.sentAt)
            FrameType.TALK -> env.id.length == TALK_LENGTH && env.id.all { it in BASE64URL }
            else -> toBytesOrNull(env.id) != null
        }

    /** The id of [senderId]'s profile published at [publishedAt] — the frame's `sentAt` is the same stamp. */
    fun profileId(
        senderId: String,
        publishedAt: Long,
    ): String = "profile-$senderId-$publishedAt"

    /** The id string for [bytes] (inverse of [toBytesOrNull]; [bytes] must be [ID_BYTES] wide). */
    fun fromBytes(bytes: ByteArray): String {
        require(bytes.size == ID_BYTES) { "a frame id is $ID_BYTES bytes, got ${bytes.size}" }
        return encoder.encodeToString(bytes)
    }

    /**
     * The **row** id for a post heard on the board's primary channel (the Meshtastic room): the first
     * [ID_BYTES] of `SHA-256` over the speaker's node number and the packet id it assigned.
     *
     * Derived rather than random so that one packet is one row however many times the board hands it up —
     * the firmware replays the packets it queued while the phone was away, and `MessageDao.insertIfAbsent`
     * keys on this. `(node, packetId)` is exactly the pair Meshtastic's own firmware dedups on, so this
     * inherits its uniqueness rather than inventing any.
     *
     * Not a wire concern: nothing about a heard post is ever framed, signed or relayed, so this id lives only
     * in the local `messages` table. It keeps the frame-id shape (canonical base64url over [ID_BYTES]) because
     * a message row's id is a frame id everywhere else, and the row must look like every other row.
     */
    fun forMeshPost(
        node: Long,
        packetId: Long,
    ): String =
        fromBytes(
            MessageDigest
                .getInstance("SHA-256")
                .digest("$MESH_POST_SALT$node:$packetId".encodeToByteArray())
                .copyOf(ID_BYTES),
        )

    /**
     * The **row** id for a post on a MeshCore board's Public channel (the MeshCore room, ADR 2026-11.gsqu's Stage 6
     * amendment): the first [ID_BYTES] of `SHA-256` over a domain string, the sender's timestamp in decimal, a `:`, and
     * [raw] — the text exactly as the receiving board hands it up, the sending board's `"<name>: "` prefix included,
     * before any cleaning or clamping (`mesh/lora/PublicPostKey`).
     *
     * A channel text names no sender and no packet id, so these two are all a receiver can key on, and they are stable
     * across the board's offline-queue replay: the same frame, drained again, lands on the row it already wrote. Our own
     * post's row is written under the same id from what we queued (`"Knit xxxx: "` + the text, stamped at enqueue), so a
     * re-air of it after the board's seen ring forgot it dedups here, never by name. [timestampSecs] is the sender's
     * claim and is read for nothing else: a row orders and ages by its arrival.
     *
     * Domain-separated from [forMeshPost], and the domain string is **frozen once shipped**: a change would give every
     * replayed post a second row. Not a wire concern — nothing about a heard post is framed, signed or relayed.
     */
    fun forMeshcorePost(
        timestampSecs: Long,
        raw: ByteArray,
    ): String =
        fromBytes(
            MessageDigest
                .getInstance("SHA-256")
                .apply {
                    update("$MESHCORE_POST_SALT$timestampSecs:".encodeToByteArray())
                    update(raw)
                }.digest()
                .copyOf(ID_BYTES),
        )

    private const val MESH_POST_SALT = "knit-meshpost-id-v1:"

    /** [forMeshcorePost]'s domain: frozen once shipped. */
    private const val MESHCORE_POST_SALT = "knit-meshcore-public-id-v1:"
}
