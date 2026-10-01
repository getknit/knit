package app.getknit.knit.data.group

import androidx.room3.Entity
import androidx.room3.PrimaryKey
import app.getknit.knit.mesh.protocol.GroupInfo
import kotlinx.serialization.json.Json

/**
 * A group chat as stored on this device. [groupId] is derived from the member set (see
 * [app.getknit.knit.data.message.Conversations.groupIdFor]) and carried on every group message (it
 * doubles as the message [app.getknit.knit.data.message.MessageEntity.conversationId]). [name] is the
 * explicit group name, or blank (`""`) when unnamed — an unnamed group's title is generated locally per
 * device from its members (see [app.getknit.knit.data.message.groupTitle]). [nameUpdatedAt] is the
 * name's last-writer-wins clock (the `sentAt` of the routing envelope that last set it, or the wall
 * clock for a local rename) so concurrent renames converge. Both it and [photoUpdatedAt] are sender-supplied
 * numbers, so the inbound path bounds them to `Protocol.MAX_FUTURE_SKEW_MS` past our own clock before they
 * are stored — one far-future stamp must not hold the name or photo against every honest change after it.
 *
 * [members] is a JSON-encoded `List<String>` of node ids (the fixed roster, capped at 8 incl. the
 * creator); kept as a TEXT column so Room needs no TypeConverter and (de)serialization lives with the
 * type via [GroupMembersStore]. [createdBy] is the creator's node id, used to refuse a group a blocked
 * user tries to start here.
 *
 * The group's photo is two hashes (ADR 2026-09.nxcq). [photoHash] is the photo this device has **decided** on:
 * the content hash every frame we send advertises, last-writer-wins on [photoUpdatedAt] (its own clock,
 * distinct from [nameUpdatedAt], so a stale chat message can't revert a newer photo), stored the moment a
 * newer one is heard — before its bytes are here — so a member still pulling them re-asserts the new photo,
 * never the old one at the new clock (#108). [photoShownHash] is the photo that **renders**: it follows
 * [photoHash] once that blob is local and has passed on-device screening (`InboundPipeline.groupPhotoDecision`
 * and `settleArrivedGroupPhoto`), so a non-null [photoShownHash] always renders and every surface reads it,
 * never [photoHash]. Until then the previous photo keeps showing; one screening refused never shows. Both
 * null is the default people glyph. Set/replace only — there is no wire convention to clear it back to the
 * glyph (a null carries "no change", like [name]).
 *
 * [left] is the leave tombstone: once true, inbound group frames are dropped and never re-upserted, so
 * a self-describing frame can't resurrect a group the user left. The row is kept (not deleted) so that
 * tombstone survives; its messages are deleted on leave.
 *
 * [departed] is a JSON-encoded `List<String>` of node ids of members who have *left* this group (each
 * recorded from that member's own signed `groupleave` frame). [members] always holds the *effective*
 * roster (the original set minus [departed]); the tombstone is what makes a departure stick when a
 * straggler re-broadcasts the old full roster — `reconcileGroup` re-subtracts [departed] every time. It
 * only ever grows (there is no add-member feature), so it stays bounded by the cap. (De)serialized by
 * [GroupMembersStore], same as [members].
 */
@Entity(tableName = "groups")
data class GroupEntity(
    @PrimaryKey val groupId: String,
    val name: String,
    val members: String,
    val createdBy: String,
    val createdAt: Long,
    val nameUpdatedAt: Long = 0L,
    val left: Boolean = false,
    val departed: String = "[]",
    val photoHash: String? = null,
    val photoUpdatedAt: Long = 0L,
    val photoShownHash: String? = null,
)

/**
 * The self-describing [GroupInfo] flooded on every group frame, built from the local row so each
 * message/update re-asserts the current name **and** the decided photo (both converge last-writer-wins, by
 * their own clocks; never [GroupEntity.photoShownHash], which may still trail it) and carries the
 * [GroupEntity.departed] tombstones that make the founding roster — the set the group id is derived from —
 * reconstructible by a first-time receiver (see `InboundPipeline.vetRoster`).
 * The one mapper for the chat-send, rename, set-photo, and notification-reply paths, so they can't drift.
 */
fun GroupEntity.toGroupInfo(): GroupInfo =
    GroupInfo(
        id = groupId,
        // Only a renamed group carries a shared name; unnamed groups stay locally-titled.
        name = name.takeIf { it.isNotBlank() },
        members = GroupMembersStore.decode(members),
        createdBy = createdBy,
        photoHash = photoHash,
        photoUpdatedAt = photoUpdatedAt.takeIf { it > 0L },
        departed = GroupMembersStore.decode(departed).ifEmpty { null },
    )

/**
 * The roster half of [toGroupInfo], for the `CTL_GROUP_KEY` seed (`GroupKeyPayload.group`): the founding
 * set, the creator and the name — what a member holding no row needs to pin the group and title it — and
 * never the photo, which is a blob to pull and rides the group's own frames once the roster has let the
 * receiver read them.
 */
fun GroupEntity.toFoundingInfo(): GroupInfo = toGroupInfo().copy(photoHash = null, photoUpdatedAt = null)

/**
 * Encodes/decodes the [GroupEntity.members] JSON column. Its own [Json] instance (WireCodec's is
 * private); a malformed/legacy value decodes to an empty list rather than crashing rendering — mirrors
 * [app.getknit.knit.data.message.MentionStore].
 */
object GroupMembersStore {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(members: List<String>): String = json.encodeToString(members)

    fun decode(stored: String): List<String> = runCatching { json.decodeFromString<List<String>>(stored) }.getOrDefault(emptyList())
}
