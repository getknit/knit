package app.getknit.knit.data.message

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Builders for the status-notice rows described on [MessageEntity.kind] — the centered, muted lines a
 * thread shows for an event rather than a message ("Alice left the chat", "Alice is now Bob").
 *
 * They live here, together and pure, because every field of a notice is a convention rather than a
 * value: the id must be deterministic for its event (so a custody replay or a re-served frame upserts
 * the same row instead of stacking duplicate lines), `senderId` is the event's **subject** rather than
 * an author, `sentAt` comes off the frame so two devices order the same thread identically, and
 * `received` is true because the row was never sent anywhere. Four producers in three layers write
 * these — `InboundPipeline`, `GroupRepository`, and the group-creation path in the UI — and a
 * convention re-derived at four call sites is a convention that drifts.
 *
 * No notice costs a wire field. Each is a pure function of a change both ends can already see in state
 * they both hold, which is the same argument `docs/WIRE_COMPAT.md` records for voice notes and for
 * attachments over spools: when a value is derivable from bytes both ends have, deriving beats
 * carrying. That is also why the rendered *text* is never stored — only a kind, a subject and the name
 * or names the localized string cannot get from live state ([MessageEntity.body]) — so the line is
 * localized per device.
 */
object StatusNotices {
    /** [MessageEntity.KIND_MEMBER_LEFT] — [leaverId] left [groupId] at [leftAt] (their frame's `sentAt`). */
    fun memberLeft(
        groupId: String,
        leaverId: String,
        leftAt: Long,
    ): MessageEntity =
        notice(
            id = "leave:$groupId:$leaverId",
            subjectId = leaverId,
            conversationId = groupId,
            sentAt = leftAt,
            kind = MessageEntity.KIND_MEMBER_LEFT,
        )

    /**
     * [MessageEntity.KIND_PEER_RENAMED] — [peerId] changed their display name from [previousName] to
     * [newName]. Both names are stored (a [PeerRename] in the body) so the row stays a record of that one
     * step. Rendering the new half from the live directory label read fine after a single rename, but a
     * second rename rewrote the first line into "Old is now Newest", and the two lines then looked like a
     * duplicate (ADR 2026-09.995c). A row written before the new name was stored still renders its new
     * half from the live label.
     *
     * Keyed on [version] (the sender's profile version, which is also the row's `sentAt`) rather than on
     * the name, so a re-served or republished profile carrying the same version upserts this row.
     */
    fun peerRenamed(
        peerId: String,
        previousName: String,
        newName: String,
        version: Long,
    ): MessageEntity =
        notice(
            id = "rename:$peerId:$version",
            subjectId = peerId,
            conversationId = peerId,
            sentAt = version,
            kind = MessageEntity.KIND_PEER_RENAMED,
            body = PeerRename.encode(previousName, newName),
        )

    /**
     * [MessageEntity.KIND_PEER_AVATAR] — [peerId] changed their avatar, as of profile [version].
     *
     * Keyed on the version and **not** on the avatar hash on purpose: a hash is only adopted once its
     * blob has landed, so "the stored hash differs from the advertised one" stays true on every re-serve
     * until the bytes arrive. One notice per profile version is the idempotent form.
     */
    fun peerAvatarChanged(
        peerId: String,
        version: Long,
    ): MessageEntity =
        notice(
            id = "avatar:$peerId:$version",
            subjectId = peerId,
            conversationId = peerId,
            sentAt = version,
            kind = MessageEntity.KIND_PEER_AVATAR,
        )

    /**
     * [MessageEntity.KIND_KEY_PIN_REFUSED] — a profile for [peerId] stamped [sentAt] advertised a key
     * that isn't the one pinned for them, and was refused.
     *
     * A safety net, not a routine notice: a profile whose key does not derive back to its sender's
     * nodeId is dropped before the pin is ever consulted, so reaching this needs a 128-bit nodeId
     * collision or a corrupted local pin.
     */
    fun keyPinRefused(
        peerId: String,
        sentAt: Long,
    ): MessageEntity =
        notice(
            id = "pinrefused:$peerId:$sentAt",
            subjectId = peerId,
            conversationId = peerId,
            sentAt = sentAt,
            kind = MessageEntity.KIND_KEY_PIN_REFUSED,
        )

    /**
     * [MessageEntity.KIND_GROUP_RENAMED] — [actorId] renamed [groupId] to [newName] at [sentAt].
     *
     * Stores the **new** name, the mirror image of [peerRenamed]: a group's old name is gone from live
     * state once the rename applies, so the notice has to carry the value that makes it readable, and
     * carrying the new one keeps the line a correct record of history after a *later* rename.
     */
    fun groupRenamed(
        groupId: String,
        actorId: String,
        newName: String,
        sentAt: Long,
    ): MessageEntity =
        notice(
            id = "grename:$groupId:$sentAt",
            subjectId = actorId,
            conversationId = groupId,
            sentAt = sentAt,
            kind = MessageEntity.KIND_GROUP_RENAMED,
            body = newName,
        )

    /**
     * [MessageEntity.KIND_GROUP_PHOTO] — [actorId] set [groupId]'s photo to [photoHash] at [sentAt].
     *
     * Keyed on the hash rather than the clock: the photo carries its own last-writer-wins stamp, and
     * the same photo re-asserted by a straggler must not post a second line. Written when the change is
     * *decided*, not when the bytes land — "they changed the photo" is true either way, and the image
     * fills in behind it.
     */
    fun groupPhotoChanged(
        groupId: String,
        actorId: String,
        photoHash: String,
        sentAt: Long,
    ): MessageEntity =
        notice(
            id = "gphoto:$groupId:$photoHash",
            subjectId = actorId,
            conversationId = groupId,
            sentAt = sentAt,
            kind = MessageEntity.KIND_GROUP_PHOTO,
        )

    /**
     * [MessageEntity.KIND_GROUP_CREATED] — [groupId] was created by [creatorId] at [createdAt].
     *
     * The only join-shaped event there is: a group's id is the hash of its founding roster and
     * membership only ever shrinks, so nobody ever joins one. Written both by the creator (locally, at
     * creation) and by every other member (on first sight of the group), which is why the id carries no
     * actor — the two paths must converge on one row.
     */
    fun groupCreated(
        groupId: String,
        creatorId: String,
        createdAt: Long,
    ): MessageEntity =
        notice(
            id = "created:$groupId",
            subjectId = creatorId,
            conversationId = groupId,
            sentAt = createdAt,
            kind = MessageEntity.KIND_GROUP_CREATED,
        )

    private fun notice(
        id: String,
        subjectId: String,
        conversationId: String,
        sentAt: Long,
        kind: Int,
        body: String = "",
    ): MessageEntity =
        MessageEntity(
            id = id,
            senderId = subjectId,
            conversationId = conversationId,
            body = body,
            sentAt = sentAt,
            // Never "sent", so nothing is owed a delivery tick and nothing may await one.
            received = true,
            kind = kind,
        )
}

/**
 * What a [MessageEntity.KIND_PEER_RENAMED] row's [MessageEntity.body] records: the name the peer changed
 * [from] and the one they changed [to]. Stored as a small JSON object (`{"from":"Old","to":"New"}`), the
 * [MessageEntity.mentions] convention, so the second name rides in the TEXT column the row already has and
 * the schema is untouched.
 *
 * [to] is null when the row cannot say: a body written before the new name was stored is the bare
 * previous name, and a peer who cleared their name has nothing to be called by yet. The renderer falls
 * back to the live directory label for those, which is what every rename row rendered with before.
 */
@Serializable
data class PeerRename(
    val from: String,
    val to: String? = null,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** The body for a rename from [from] to [to]; a blank [to] is stored as "unknown" rather than as "". */
        fun encode(
            from: String,
            to: String,
        ): String = json.encodeToString(PeerRename(from, to.takeIf { it.isNotBlank() }))

        /** Reads [body], taking anything that is not an encoded pair as a legacy bare previous name. */
        fun decode(body: String): PeerRename {
            val decoded = runCatching { json.decodeFromString<PeerRename>(body) }.getOrNull() ?: PeerRename(from = body)
            return decoded.copy(to = decoded.to?.takeIf { it.isNotBlank() })
        }
    }
}
