package app.getknit.knit.data.message

import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import app.getknit.knit.mesh.protocol.BodyStyle
import app.getknit.knit.mesh.protocol.Mention
import app.getknit.knit.mesh.protocol.ReplyRef
import app.getknit.knit.mesh.protocol.StyleSpan
import app.getknit.knit.mesh.protocol.VideoInfo
import kotlinx.serialization.json.Json
import java.util.Base64

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
 * On an inbound reply those columns start as the **sender's claim** and are judged against the original this
 * phone holds (ADR 2026-10.gpa3): [replyCheck] says whether that happened and how it went, and a verified quote's
 * snippet is rebuilt from the original, so it outlives the original's retention sweep. [replyToAuthor] is the
 * sender's string and is never rendered for somebody else's reply — the reader's own label for
 * [replyToAuthorId] is. [replyToFlagged] is this phone's verdict on the quoted words: the original's own
 * moderation once it is judged, else the snippet's own screening. Our own replies keep what we sent: a re-seal
 * re-ships `replyRef()`, and we quoted a row we held.
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
 *
 * Two composite indices, both led by `conversationId`, replace the single-column index they are prefixes of:
 *  - `(conversationId, sentAt, id)` *orders* a thread as well as finding it. With `conversationId` pinned by
 *    equality the `(sentAt, id)` suffix is scanned backward, so the chat screen's newest-first window needs
 *    no sort and stops at its LIMIT rather than reading the whole thread. The retention sweep's
 *    `ORDER BY sentAt DESC, id DESC LIMIT :keep` rides it too.
 *  - `(conversationId, kind, senderId)` *covers* the sender queries: both constrained columns are equalities
 *    and `senderId` is the scanned suffix, so `observeSendersIn` and `hasMessagesIn` are answered out of the
 *    index with no row fetches. Without it, the live @-mention query would walk every row of a thread on
 *    every write to the table — worse than the whole-thread read the window removes.
 *
 * `attachmentHash` is indexed for the reads keyed by it on the inbound path (ADR 2026-10.js2p): a delivery judges
 * its attachment's fetch over every row naming the hash (`MessageDao.attachmentFetchRowsFor`).
 */
@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["conversationId", "sentAt", "id"]),
        Index(value = ["conversationId", "kind", "senderId"]),
        Index(value = ["attachmentPlainHash"]),
        Index(value = ["replyToId", "replyCheck"]),
        Index(value = ["attachmentHash"]),
    ],
)
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
    /** The post entered the mesh through an MQTT uplink, so it may have come from anywhere. */
    val originViaMqtt: Boolean = false,
    /**
     * The Knit contact whose profile claimed [originNode] as their board when this post was heard, resolved
     * **once, at ingest**, and never again: boards change hands, and re-resolving at render time would put
     * old words under whoever holds the board now. Null for a stranger's radio. An attribution, not a verified
     * identity — the UI keeps the unverified styling for it — unless [originSigned] says the post was verified
     * under the key that contact's own profile advertises.
     */
    val originPeerId: String? = null,
    /**
     * What the XEdDSA signature on a heard post proved, one of the `ORIGIN_*` values below. Decided **once,
     * at ingest**, like [originPeerId], and frozen with it: a contact re-keying their board later must not
     * re-judge what their old board said. [ORIGIN_SIGNED_BY_CONTACT] is the only value that lets the UI drop
     * the unverified styling; the rest keep it, and an unsigned post is never held against anybody.
     */
    val originSigned: Int = ORIGIN_UNSIGNED,
    /**
     * The image attachment holds more than one frame — a GIF, which the ingest re-encodes to an animated WebP
     * under the same MIME a still sticker gets, so only the frames tell them apart. **Local only**, derived
     * from the bytes by [app.getknit.knit.data.AnimatedImage] exactly as [voicePeaks] is: the sender at
     * ingest, the recipient once the blob lands. It picks a preview label ("GIF" over "Photo") and nothing
     * else, so false — also the value while a received blob is still on its way — is always a safe answer.
     */
    val attachmentAnimated: Boolean = false,
    /**
     * The SHA-256 of a **sealed** attachment's plaintext — the name the same bytes go by in the room, where an
     * attachment is addressed by its plaintext. Null on a key-less row, whose [attachmentHash] already is that
     * name, and on a sealed row whose blob has not landed or been opened yet. **Local only**: the sender writes it
     * at send, a recipient once the blob lands or is saved (ADR 2026-10.mptm). It is what lets a phone forward
     * a file under the identity it arrived under, and make a room copy of a picture it holds sealed instead of
     * pulling it; indexed, because the second is asked on every blob want.
     */
    val attachmentPlainHash: String? = null,
    /**
     * The SHA-256 of the bytes the picker handed over for one of **our own** sends, when the ingest changed them
     * (a photo re-encoded, a GIF made a WebP); null otherwise and on every received row. **Local only**: it is
     * how picking the same original again finds the identity it went out under (ADR 2026-10.mptm).
     */
    val attachmentSourceHash: String? = null,
    /**
     * Whether this reply's quote was checked against the original it names — one of the `REPLY_*` values below,
     * written by [app.getknit.knit.data.QuoteVerifier] on an inbound reply, once: before the insert when the
     * original is already held, else when it lands. **Local only**. Indexed with [replyToId], the column the
     * late check looks replies up by.
     */
    val replyCheck: Int = REPLY_UNCHECKED,
    /**
     * The quoted words are flagged on this phone: the original's own [moderation] once the quote is verified,
     * else the on-device moderator's verdict on the sender's snippet. Hides the whole reply behind the Content
     * filtering toggle, like a flagged body ([hiddenByFilter]). **Local only.**
     */
    val replyToFlagged: Boolean = false,
    /**
     * The user tapped "Tap to download" on this row's attachment: its fetch may ride Bluetooth whatever its declared
     * [attachmentSize] (ADR 2026-10.js2p, `mesh/BluetoothFetchGate`). Written on every row naming the hash at once
     * ([MessageDao.approveAttachmentFetch]), so the database re-arm never gates a tapped attachment again. **Local only.**
     */
    val attachmentFetchApproved: Boolean = false,
    /**
     * A short video's duration, upright width and height (ADR 2026-10.hmfe): the sender's claim from
     * [app.getknit.knit.mesh.protocol.VideoInfo] until the bytes land, then what the bytes themselves say (derived
     * on arrival like [voiceDurationMs]). All null for anything but a video.
     */
    val videoDurationMs: Long? = null,
    val videoWidth: Int? = null,
    val videoHeight: Int? = null,
    /**
     * The video's poster, a small JPEG, base64: carried inside the message so the bubble has a picture before the
     * bytes arrive. Base64 text rather than a BLOB so the row keeps a value `equals` (a `ByteArray` compares by
     * identity, and every Flow of rows would re-emit). Never in the blob table, where any neighbour could ask for it
     * by hash. Empty when the sender had none.
     */
    val videoPoster: String? = null,
    /**
     * This phone's screen judged [videoPoster] explicit (ADR 2026-10.hmfe): the bubble draws it hidden like a flagged
     * picture, under the Content filtering toggle. On the row, not in `blob_verdicts`, so it goes with the message
     * rather than outliving it: the poster is no blob, and nothing would ever collect a verdict keyed by it.
     * **Local only.**
     */
    val videoPosterFlagged: Boolean = false,
    /**
     * A voice note's words, written out on this phone by the speech model when the person tapped Transcribe (ADR
     * 2026-10.am8d): derived from the held bytes like [voicePeaks] and keyed by [attachmentHash] across rows, never on
     * the wire. Null until transcribed; empty when the model heard no speech. Debug builds only (`STT_WIRED`).
     * **Local only.**
     */
    val voiceTranscript: String? = null,
    /**
     * The body's formatting (ADR 2026-10.p95v): [StyleStore]'s readable JSON triples `[[start, length, code], …]` in
     * UTF-16 units over [body], or null for an unformatted message (every row before v25). Inbound rows hold the
     * spans normalised against the clamped body; our own hold what was sealed, and a re-seal reads them back
     * (`sealedContent`). On the wire as one byte string (`StyleSpans`), never this text.
     */
    val styles: String? = null,
) {
    companion object {
        // [originSigned] values. Append-only like every other registry here: the value is stored on the row.

        /**
         * [originSigned]: no usable signature — the packet was unsigned (pre-2.8 firmware, or a post past the
         * signature cliff), or nothing on this phone could check it. Says nothing either way.
         */
        const val ORIGIN_UNSIGNED = 0

        /**
         * [originSigned]: our own board verified the signature against the key *its* NodeDB holds for the
         * speaker's number — "the radio that has been using this number sent it". No Knit identity behind it.
         */
        const val ORIGIN_SIGNED_BY_BOARD = 1

        /** [originSigned]: verified on this phone against the key the resolved contact's own signed profile advertises. */
        const val ORIGIN_SIGNED_BY_CONTACT = 2

        /**
         * [originSigned]: the number resolved to a contact advertising a key, the packet was signed, and it
         * did not verify under that key — some other radio is using their number. The row is **not**
         * attributed ([originPeerId] stays null) and is shown as a stranger that says so.
         */
        const val ORIGIN_SIGNATURE_MISMATCH = 3

        /** [moderation]: text passed (or was not checked). */
        const val MODERATION_NONE = 0

        /** [moderation]: the body was flagged as abusive by the on-device text moderator. */
        const val MODERATION_TEXT_FLAGGED = 1

        // [replyCheck] values. Append-only: the value is stored on the row (`QuoteCheckTest` pins them).

        /** [replyCheck]: the original is not held, so the quote is the sender's unverified snapshot. */
        const val REPLY_UNCHECKED = 0

        /** [replyCheck]: the original was held, in this thread, under the author the quote names; the quote is rebuilt from it. */
        const val REPLY_VERIFIED = 1

        /** [replyCheck]: a held message answers to the quoted id but not to its author or thread; nothing of the quote is shown. */
        const val REPLY_MISMATCH = 2

        /** [kind]: an ordinary chat message, shown as a sender bubble. */
        const val KIND_NORMAL = 0

        // [kind] status-notice values. Append-only like every other registry in this codebase: a value
        // is stored in the database, so recycling one would re-render an old row as a different event.
        // A build that does not know a value renders the row as an ordinary bubble (see
        // `ChatScreen.statusNoticeText`), which is why an unknown kind degrades rather than vanishing.
        // Kotlin does not object to two constants sharing a value (or to the dead `when` branch that
        // leaves behind), so `MessageKindTest` pins them distinct.

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
         * A group's id is the hash of its founding roster and nobody can be added to it, so this is the
         * only join-shaped event for a *new* face — there is no "member joined"; a founding member who
         * left and came back is [KIND_MEMBER_REJOINED].
         */
        const val KIND_GROUP_CREATED = 6

        /**
         * [kind]: a profile update for this contact was refused because it did not match their pinned
         * key. A safety net rather than a routine notice: a profile whose key does not derive to its
         * sender's nodeId is already dropped earlier, so reaching this needs a 128-bit nodeId collision
         * or local pin corruption.
         */
        const val KIND_KEY_PIN_REFUSED = 7

        /**
         * [kind]: a direct Wi-Fi file transfer with this contact — the chat's record that it was offered and
         * how it ended. [body] holds a [TransferRecord]; [senderId] is whoever offered the file. The bytes
         * themselves never rode the mesh and are not in the blob store: a received file sits in the user's
         * Downloads, and the record is local to each side. Drawn as a card (`ui/chat/TransferCard`), not a line.
         */
        const val KIND_FILE_TRANSFER = 8

        /**
         * [kind]: a founding member who had left re-added themselves (only their own signed frame can —
         * the mirror of [KIND_MEMBER_LEFT]), shown as a centered line.
         */
        const val KIND_MEMBER_REJOINED = 9

        /**
         * [kind]: a voice or walkie call with this contact — how it ended (docs/VOICE_CALLS.md §3.12). [body] holds a
         * [CallRecord]; [senderId] is whoever placed the call. Written by this phone when the call ends here, never
         * carried on the wire, so each side keeps its own. Drawn as a line with the call's time (`ui/chat/CallNotice`).
         */
        const val KIND_CALL = 10
    }
}

/**
 * Encodes/decodes the [MessageEntity.mentions] JSON column. Its own [Json] instance (WireCodec's is
 * private); a malformed/legacy value decodes to an empty list rather than crashing rendering.
 */
object MentionStore {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(mentions: List<Mention>): String = json.encodeToString(mentions)

    fun decode(stored: String): List<Mention> {
        // Most rows mention nobody, and the column's default is the literal "[]" — parsing that back into an
        // empty list costs a kotlinx.serialization round trip per row, per emission, on the chat fold.
        if (stored.isBlank() || stored == "[]") return emptyList()
        return runCatching { json.decodeFromString<List<Mention>>(stored) }.getOrDefault(emptyList())
    }
}

/**
 * Encodes/decodes the [MessageEntity.styles] (and `DraftEntity.styles`) column: JSON triples `[[start, length, code],
 * …]`, legible in a debug-bridge dump, where the wire's packed bytes would not be. Null for none, so an unformatted
 * row stores nothing. A malformed value, a triple that is no span, or a code this build does not know decodes to
 * nothing rather than crashing rendering.
 */
object StyleStore {
    private val json = Json { ignoreUnknownKeys = true }

    /** [spans] as the column's text, or null when there are none. */
    fun encode(spans: List<StyleSpan>): String? =
        if (spans.isEmpty()) {
            null
        } else {
            json.encodeToString(spans.map { listOf(it.start, it.end - it.start, it.style.code) })
        }

    fun decode(stored: String?): List<StyleSpan> {
        // Nearly every row is unformatted: no parse for those on the chat fold.
        if (stored.isNullOrBlank()) return emptyList()
        val triples = runCatching { json.decodeFromString<List<List<Int>>>(stored) }.getOrNull() ?: return emptyList()
        return triples.mapNotNull { triple ->
            val (start, length, code) = triple.takeIf { it.size == TRIPLE } ?: return@mapNotNull null
            val style = BodyStyle.ofCode(code) ?: return@mapNotNull null
            if (start < 0 || length <= 0 || start > Int.MAX_VALUE - length) null else StyleSpan(start, start + length, style)
        }
    }

    private const val TRIPLE = 3
}

/**
 * Whether this row is a locally-generated status notice rather than a message someone sent (see
 * [MessageEntity.kind]). Tolerant of a `KIND_` value this build doesn't know: anything that isn't
 * [MessageEntity.KIND_NORMAL] is a notice, so a row written by a newer build is still kept out of
 * unread counts, ticks and previews even where its text can't be rendered.
 */
val MessageEntity.isStatusNotice: Boolean get() = kind != MessageEntity.KIND_NORMAL

/**
 * Whether [me] wrote this message: our sender id, an ordinary message rather than a notice, and no Meshtastic
 * origin — a heard post sits in our sender column by convention (the phone whose board heard it writes the row),
 * but not a word of it is ours. What earns a chat-list tick, and what a reaction must target to notify us
 * (ADR 2026-10.59fu); `ReactionDao.observeNewestOnOwnMessages` mirrors it in SQL.
 */
fun MessageEntity.writtenBy(me: String?): Boolean = senderId == me && originNode == null && !isStatusNotice

/**
 * Whether the reader's Content filtering ([filterOn]) hides this message: its own body was flagged, or the words
 * it quotes were ([MessageEntity.replyToFlagged], ADR 2026-10.gpa3). The one predicate every surface that hides a
 * flagged message reads — the bubble, the details screen, the list previews and the notification — and the
 * search query's SQL mirrors it.
 */
fun MessageEntity.hiddenByFilter(filterOn: Boolean): Boolean =
    filterOn && (moderation == MessageEntity.MODERATION_TEXT_FLAGGED || replyToFlagged)

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

/** The video description this row carries, for the sealed content and the room frame; null for a non-video. */
fun MessageEntity.videoInfo(): VideoInfo? =
    videoDurationMs?.let { duration ->
        VideoInfo(
            durationMs = duration,
            width = videoWidth ?: 0,
            height = videoHeight ?: 0,
            poster = videoPoster?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() } ?: ByteArray(0),
        )
    }

/** A copy with the video columns set from [video] (all cleared when it's null); a poster over the cap is dropped. */
fun MessageEntity.withVideo(video: VideoInfo?): MessageEntity =
    copy(
        videoDurationMs = video?.durationMs,
        videoWidth = video?.width,
        videoHeight = video?.height,
        videoPoster =
            video
                ?.poster
                ?.takeIf { it.isNotEmpty() && it.size <= VideoInfo.MAX_POSTER_BYTES }
                ?.let { Base64.getEncoder().encodeToString(it) },
    )

/** A copy with the quoted-reply columns populated from [replyTo] (all cleared when it's null). */
fun MessageEntity.withReply(replyTo: ReplyRef?): MessageEntity =
    copy(
        replyToId = replyTo?.messageId,
        replyToAuthorId = replyTo?.authorId,
        replyToAuthor = replyTo?.author,
        replyToSnippet = replyTo?.snippet,
        replyToHasAttachment = replyTo?.hasAttachment ?: false,
    )
