package app.getknit.knit.data.message

import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import app.getknit.knit.mesh.protocol.Mention
import app.getknit.knit.mesh.protocol.ReplyRef
import kotlinx.serialization.json.Json

/**
 * A chat message as stored on this device. [id] is the globally-unique wire id (also the dedup key
 * across the mesh). [recipientId] is null for broadcast-room messages and set for 1:1 DMs.
 * [conversationId] groups messages into a thread ([Conversations.NEARBY] for the room, the other
 * party's node id for a DM) and is indexed for the per-thread chat queries. [received] is the
 * delivery-ack flag for messages this device sent (drives the ✓/✓✓ tick).
 *
 * [receivedVia] records which plane this message crossed to get here (a [DeliveryPlane] code), so the UI
 * can show a globe for one that came over the Internet. The evidence differs by direction: an **inbound**
 * message is written with the plane its own frame arrived on — it is its own proof — while one of **our
 * sends** learns its plane from the receipt that flips [received], and only from the first such receipt (a
 * duplicate re-served later on another plane never rewrites it, so the mark keeps describing the delivery
 * that actually happened).
 *
 * [arrivedAt] is **our own** clock when this row was first persisted — "when it got here" — and is the local
 * complement of [sentAt], which is the *author's* clock off the wire frame. The gap between the two is the
 * store-and-forward latency, the one part of a message's journey nothing else records. Deliberately not the
 * peer's clock, for the reason [app.getknit.knit.data.receipt.MessageReceiptEntity.notedAt] gives one table
 * over: mesh devices have no time sync, so a peer-clock value can render an arrival *earlier* than the send
 * it belongs to. Null in three honest cases — a message **we** authored (only the inbound path stamps it), a
 * row that predates the column (never backfilled: we did not observe when those landed and must not invent
 * it), and a room post of ours looping back to us. Stamped once and never rewritten, so a custody re-serve
 * keeps the first crossing; the UI renders its absence rather than a zero. Display only — [sentAt] remains
 * the retention comparator.
 *
 * [mentions] is a JSON-encoded `List<Mention>` ("[]" when none); kept as a string so Room stays a
 * plain TEXT column and (de)serialization lives with the [Mention] type via [MentionStore].
 *
 * [attachmentHash]/[attachmentMime] reference an out-of-band image blob fetched by content hash; the
 * bytes live in the encrypted `blobs` table (see [app.getknit.knit.data.blob.BlobEntity]), keyed by
 * [attachmentHash], and are null until the blob has been pulled from the mesh.
 *
 * [attachmentKey] is the base64 AES key for an end-to-end-encrypted attachment: in a DM/group the blob
 * bytes are ciphertext (content-addressed by their ciphertext hash), so the UI must decrypt them with
 * this key before decoding. Null for plaintext (broadcast-room) attachments and text-only messages.
 *
 * The `replyTo*` columns snapshot the message this one is a quoted reply to (all null when it isn't a
 * reply): [replyToId] the quoted message's id, [replyToAuthorId] its sender's node id (the UI swaps it to
 * "You" when it's the viewer's own), [replyToAuthor] a display-name snapshot, [replyToSnippet] a capped
 * copy of the quoted body (blank for an attachment-only original), and [replyToHasAttachment] whether the
 * original carried an image (so the quote can show a "photo" placeholder). They are denormalized from
 * [app.getknit.knit.mesh.protocol.ReplyRef] so the quote renders even when the original was never
 * received, was deleted, or scrolled out of history.
 *
 * [voiceDurationMs]/[voicePeaks] describe a voice-note attachment for the bubble: its playing time in
 * milliseconds, and a Base64 [ByteArray] of bar heights for the waveform. Both are **local only** — they are
 * derived from the audio itself by [app.getknit.knit.data.VoiceAudio] (the sender at ingest, the recipient
 * once the blob lands) and never cross the wire, which is why voice notes cost no wire field at all. Null on
 * every non-voice message, and null on a voice note whose bytes haven't arrived yet — the bubble shows the
 * same loading placeholder an image does until they do. Base64 rather than a `BLOB` column so this stays a
 * plain `data class`: a [ByteArray] property would give it a reference-identity `equals`.
 *
 * [attachmentName]/[attachmentSize] describe an arbitrary-**file** attachment (ADR 2026-09.qq2r): the name
 * the sender gave it and how many bytes it claimed. Unlike the voice columns these do cross the wire, sealed
 * on [app.getknit.knit.mesh.crypto.MessageContent] — a name is the one thing about a file that is not a
 * function of bytes both ends hold, so it cannot be derived the way a waveform is. Null on images and voice
 * notes, whose bubbles describe themselves. [attachmentSize] is what the bubble says *before* the blob
 * arrives; once it has, the stored blob's own length is the authority and this is never used to size an
 * allocation.
 *
 * [moderation] records an on-device content-moderation verdict for the [body] ([MODERATION_NONE] or
 * [MODERATION_TEXT_FLAGGED]). A flagged inbound message is still stored, but the UI collapses it behind
 * a tap-to-reveal rather than dropping it (so a false positive never loses content).
 *
 * [pendingKey] marks an outgoing DM that was saved locally but could not yet be sealed/flooded because
 * the recipient's public key wasn't known (distinct from [received], which can't tell "never sent" from
 * "sent, awaiting ack"). It stays true until the recipient's profile arrives and `MeshManager` re-seals
 * and floods it (see `flushPendingFor`). Always false for received messages and broadcast/group sends.
 *
 * [kind] discriminates an ordinary chat message ([KIND_NORMAL]) from a locally-generated **status
 * notice** — a `KIND_`-prefixed value rendered as a centered, muted line rather than a bubble
 * ("Alice left the chat", "Alice is now Bob"). Every notice is derived on this device from a change
 * two peers can both see in state they already hold, so none of them costs a wire field; see
 * [isStatusNotice] and `ChatScreen.statusNoticeText`.
 *
 * A status row's [senderId] is the **subject** of the event (the member who left, the peer who
 * renamed themselves, the member who renamed a group), never an author — which is why status rows are
 * excluded from unread counts, delivery ticks, the chat-list preview, and the "who has spoken here"
 * signal that decides whether a conversation is an accepted chat or a message request.
 *
 * Its [body] carries the **name or names the localized string needs that live state cannot supply**, and
 * is empty when the string needs none. A peer rename stores both the old name and the new one (a
 * [PeerRename]), so the line stays a record of that one step after a later rename instead of re-ending
 * in whatever the peer is called now; a group rename stores only the *new* name (the old one is gone from
 * live state, and "Alice renamed the group to Book Club" then stays a correct historical record after a
 * later rename).
 *
 * The row's [id] is always **deterministic** for its event, so a custody replay or a re-served frame
 * upserts the same row instead of duplicating the notice, and its [sentAt] comes from the frame (or the
 * sender's profile version), never the local wall clock, so notices order identically on every device.
 */
@Entity(tableName = "messages", indices = [Index("conversationId")])
data class MessageEntity(
    @PrimaryKey val id: String,
    val senderId: String,
    val recipientId: String? = null,
    val conversationId: String = Conversations.NEARBY,
    val body: String,
    val sentAt: Long,
    val received: Boolean = false,
    val receivedVia: Int = DeliveryPlane.Unknown.code,
    val arrivedAt: Long? = null,
    val mentions: String = "[]",
    val attachmentHash: String? = null,
    val attachmentMime: String? = null,
    val attachmentKey: String? = null,
    val replyToId: String? = null,
    val replyToAuthorId: String? = null,
    val replyToAuthor: String? = null,
    val replyToSnippet: String? = null,
    val replyToHasAttachment: Boolean = false,
    val voiceDurationMs: Int? = null,
    val voicePeaks: String? = null,
    val attachmentName: String? = null,
    val attachmentSize: Long? = null,
    val moderation: Int = MODERATION_NONE,
    val pendingKey: Boolean = false,
    val kind: Int = KIND_NORMAL,
    /**
     * The speaker's Meshtastic node number when this row is a post heard on the paired board's primary
     * channel, else null — which is also the flag for "this is a heard post" everywhere that reads it.
     *
     * This and the `origin*` columns below are the heard-post attribution: who said it on the radio channel,
     * and how it reached this board. Denormalized onto the message for the same reason the `replyTo*`
     * snapshot is, and for a stronger one: the values come off a channel this device may never hear again,
     * and a Meshtastic speaker has no Knit identity to resolve them against.
     *
     * [senderId] is **this phone** by convention — there is no frame and no signer, the row is written by the
     * phone whose board heard it — and this attribution beside it is what says the words are somebody else's.
     * The UI must render the difference (a heard author is never tappable, never verified, never a DM target).
     * Its `!hex` id is derived for display, never stored.
     */
    val originNode: Long? = null,
    /** `User.long_name` as the board's NodeDB had it when heard; null when it had never heard the speaker's name. */
    val originName: String? = null,
    /** The channel the post was heard on, as the board names it — `LongFast`, `LongTurbo`, `MediumFast`, or its own name. */
    val originChannel: String? = null,
    /** Hops from the speaker to this board. */
    val originHops: Int? = null,
    /** Signal-to-noise at this board, in tenths of a dB. */
    val originSnrDeci: Int? = null,
    /** The post entered the foreign mesh through an MQTT uplink, so it may have come from anywhere. */
    val originViaMqtt: Boolean = false,
) {
    companion object {
        /** [moderation]: text passed (or was not checked). */
        const val MODERATION_NONE = 0

        /** [moderation]: the body was flagged as abusive by the on-device text moderator. */
        const val MODERATION_TEXT_FLAGGED = 1

        /** [kind]: an ordinary chat message, shown as a sender bubble. */
        const val KIND_NORMAL = 0

        // [kind] status-notice values. Append-only like every other registry in this codebase: a value
        // is stored in the database, so recycling one would re-render an old row as a different event.
        // A build that does not know a value renders the row as an ordinary bubble (see
        // `ChatScreen.statusNoticeText`), which is why an unknown kind degrades rather than vanishing.

        /** [kind]: a "member left the group" status notice, shown as a centered line. */
        const val KIND_MEMBER_LEFT = 1

        /** [kind]: a contact changed their display name. [body] holds a [PeerRename]: the previous name and the new one. */
        const val KIND_PEER_RENAMED = 2

        /** [kind]: a contact changed their avatar. [body] is empty. */
        const val KIND_PEER_AVATAR = 3

        /** [kind]: a member renamed the group. [body] holds the **new** group name. */
        const val KIND_GROUP_RENAMED = 4

        /** [kind]: a member changed the group photo. [body] is empty. */
        const val KIND_GROUP_PHOTO = 5

        /**
         * [kind]: the group was created. [senderId] is its creator (ourselves for a group we made).
         * A group's id is the hash of its founding roster and membership only ever shrinks, so this is
         * the only join-shaped event that exists — there is no "member joined".
         */
        const val KIND_GROUP_CREATED = 6

        /**
         * [kind]: a profile update for this contact was refused because it did not match their pinned
         * key. A safety net rather than a routine notice: a profile whose key does not derive to its
         * sender's nodeId is already dropped earlier, so reaching this needs a 128-bit nodeId collision
         * or local pin corruption.
         */
        const val KIND_KEY_PIN_REFUSED = 7
    }
}

/**
 * Encodes/decodes the [MessageEntity.mentions] JSON column. Its own [Json] instance (WireCodec's is
 * private); a malformed/legacy value decodes to an empty list rather than crashing rendering.
 */
object MentionStore {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(mentions: List<Mention>): String = json.encodeToString(mentions)

    fun decode(stored: String): List<Mention> = runCatching { json.decodeFromString<List<Mention>>(stored) }.getOrDefault(emptyList())
}

/**
 * Whether this row is a locally-generated status notice rather than a message someone sent (see
 * [MessageEntity.kind]). Tolerant of a `KIND_` value this build doesn't know: anything that isn't
 * [MessageEntity.KIND_NORMAL] is a notice, so a row written by a newer build is still kept out of
 * unread counts, ticks and previews even where its text can't be rendered.
 */
val MessageEntity.isStatusNotice: Boolean get() = kind != MessageEntity.KIND_NORMAL

/** The plane [receivedVia] names, tolerant of a code this build doesn't know (see [DeliveryPlane.fromCode]). */
val MessageEntity.receivedPlane: DeliveryPlane get() = DeliveryPlane.fromCode(receivedVia)

/** The quoted-reply reference this row snapshots (see the `replyTo*` columns), or null when it isn't a reply. */
fun MessageEntity.replyRef(): ReplyRef? =
    replyToId?.let {
        ReplyRef(
            messageId = it,
            authorId = replyToAuthorId.orEmpty(),
            author = replyToAuthor.orEmpty(),
            snippet = replyToSnippet.orEmpty(),
            hasAttachment = replyToHasAttachment,
        )
    }

/** A copy with the quoted-reply columns populated from [replyTo] (all cleared when it's null). */
fun MessageEntity.withReply(replyTo: ReplyRef?): MessageEntity =
    copy(
        replyToId = replyTo?.messageId,
        replyToAuthorId = replyTo?.authorId,
        replyToAuthor = replyTo?.author,
        replyToSnippet = replyTo?.snippet,
        replyToHasAttachment = replyTo?.hasAttachment ?: false,
    )
