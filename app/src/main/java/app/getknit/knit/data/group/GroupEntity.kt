package app.getknit.knit.data.group

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.json.Json

/**
 * A group chat as stored on this device. [groupId] is derived from the member set (see
 * [app.getknit.knit.data.message.Conversations.groupIdFor]) and carried on every group message (it
 * doubles as the message [app.getknit.knit.data.message.MessageEntity.conversationId]). [name] is the
 * explicit group name, or blank (`""`) when unnamed — an unnamed group's title is generated locally per
 * device from its members (see [app.getknit.knit.data.message.groupTitle]). [nameUpdatedAt] is the
 * name's last-writer-wins clock (the `sentAt` of the routing envelope that last set it, or the wall
 * clock for a local rename) so concurrent renames converge.
 *
 * [members] is a JSON-encoded `List<String>` of node ids (the fixed roster, capped at 8 incl. the
 * creator); kept as a TEXT column so Room needs no TypeConverter and (de)serialization lives with the
 * type via [GroupMembersStore]. [createdBy] is the creator's node id, used to refuse a group a blocked
 * user tries to start here.
 *
 * [photoHash] is the content hash (in the encrypted `blobs` table) of the group's photo, or null for the
 * default people glyph. Any member can set it; it converges last-writer-wins on [photoUpdatedAt] (its own
 * clock, distinct from [nameUpdatedAt], so a stale chat message can't revert a newer photo). Mirrors a
 * peer avatar: the hash is stored only once its bytes are present locally and pass on-device screening
 * (`MeshManager.adoptAdvertisedGroupPhoto`), so a non-null [photoHash] always renders. Set/replace only —
 * there is no wire convention to clear it back to the glyph (a null carries "no change", like [name]).
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
)

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
