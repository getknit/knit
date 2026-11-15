package app.getknit.knit.notifications

import app.getknit.knit.data.BlobRepository
import app.getknit.knit.data.GroupRepository
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.group.GroupMembersStore
import app.getknit.knit.data.message.ConversationKind
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.groupFaceIds
import app.getknit.knit.data.message.groupTitle
import app.getknit.knit.identity.PeerLabelIndex

/**
 * The title and avatar a conversation wears outside the app — its notification, and the conversation shortcut the
 * launcher's long-press menu and the system's conversation surfaces show — resolved from the conversation alone,
 * with no message in hand. One resolver for both, so a shortcut refreshed between notifications never reads
 * differently from the one the next notification pushes (ADR 2026-10.jbsa).
 *
 * A DM wears its peer's label and avatar; a group its stored name (else one generated from the other members'
 * labels, [groupTitle], so two same-named members read apart) and its shown photo, and without a photo the members
 * the shade draws as a cluster — [groupFaceIds]'s pick, the same one the chat list makes, with each face's avatar
 * bytes (ADR 2026-09.zapp). The Nearby and Meshtastic rooms leave both null so the notifier substitutes its own
 * defaults — deliberately not a heard speaker's name or face for the Meshtastic room: a bridged author has neither
 * an avatar nor an authenticated name, and a notification title is the one place an unverified one would read as a
 * person Knit vouches for. A commons wears its relay's advertised name ([commonsTitle]) and the room glyph.
 */
class ConversationFaces(
    private val groups: GroupRepository,
    private val peers: PeerRepository,
    private val blobs: BlobRepository,
    private val commonsTitle: suspend (conversationId: String) -> String? = { null },
) {
    /** [conversationId]'s face, its names read off [labels] (one index for every member, not a query each). */
    suspend fun resolve(
        conversationId: String,
        me: String,
        labels: PeerLabelIndex,
    ): NotifConversation =
        when (Conversations.kindFor(conversationId)) {
            ConversationKind.NEARBY -> {
                NotifConversation(conversationId, null, null, ConversationKind.NEARBY)
            }

            ConversationKind.MESHTASTIC -> {
                NotifConversation(conversationId, null, null, ConversationKind.MESHTASTIC)
            }

            ConversationKind.MESHCORE -> {
                NotifConversation(conversationId, null, null, ConversationKind.MESHCORE)
            }

            ConversationKind.COMMONS -> {
                NotifConversation(conversationId, commonsTitle(conversationId), null, ConversationKind.COMMONS)
            }

            // A DM thread is keyed by its peer's node id, so the peer is the conversation.
            ConversationKind.DM -> {
                val avatar = peers.find(conversationId)?.avatarHash?.let { blobs.bytes(it) }
                NotifConversation(conversationId, labels.labelFor(conversationId).text, avatar, ConversationKind.DM)
            }

            ConversationKind.GROUP -> {
                groupFace(conversationId, me, labels)
            }
        }

    private suspend fun groupFace(
        groupId: String,
        me: String,
        labels: PeerLabelIndex,
    ): NotifConversation {
        val group = groups.find(groupId)
        val memberIds = group?.let { GroupMembersStore.decode(it.members) }.orEmpty()
        // Pre-resolve member names off the index, since groupTitle's nameOf is non-suspend.
        val namesByNode = LinkedHashMap<String, String>()
        for (id in memberIds) namesByNode[id] = labels.labelFor(id).text
        val title =
            group?.let {
                groupTitle(labels.shownGroup(it.name), memberIds, me, fallback = "") { id -> namesByNode[id] ?: id }.ifBlank { null }
            }
        val photo = group?.photoShownHash?.let { blobs.bytes(it) }
        // Only a photo-less group draws its members, so the reads (at most four peer rows and four blobs) are skipped
        // when a photo will cover them. The order is groupFaceIds's; the shade places faces in cells as given.
        val faces =
            if (photo != null) {
                emptyList()
            } else {
                groupFaceIds(memberIds, me).map { id ->
                    NotifFace(id, namesByNode[id] ?: id, peers.find(id)?.avatarHash?.let { blobs.bytes(it) })
                }
            }
        return NotifConversation(groupId, title, photo, ConversationKind.GROUP, faces)
    }
}
