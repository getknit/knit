package app.getknit.knit.mesh

import app.getknit.knit.data.AttachmentStore
import app.getknit.knit.mesh.protocol.GroupInfo
import app.getknit.knit.mesh.protocol.Mention
import app.getknit.knit.mesh.protocol.ReplyRef
import app.getknit.knit.mesh.spool.SpoolStatus
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Shared in-memory [MeshController] fake for ViewModel tests. Its read members are public
 * [MutableStateFlow]s a test pushes to (drive the radio banner, nearby set, typing map, …); its action
 * methods record their calls/arguments so a test can assert what the VM routed to the mesh.
 *
 * Promoted out of `DiagnosticsViewModelTest` (ARCHITECTURE_REVIEW #15) so every VM test binds the same
 * narrow mesh seam instead of the un-constructable [MeshManager] — mirrors the shared [FakeLoopTransport]
 * fixture living in this same test package.
 */
class FakeMeshController : MeshController {
    override val neighborCount = MutableStateFlow(0)
    override val neighbors = MutableStateFlow<Set<Peer>>(emptySet())

    /**
     * The full reach set (long-range planes included) — a superset of [neighbors] in production, but its own
     * flow here so a test can put a peer on LoRa without claiming a radio ever saw it.
     */
    override val reachable = MutableStateFlow<Set<Peer>>(emptySet())

    /** The proximity planes, as a two-radio phone reports them; a test may narrow or widen it. */
    override var shortRangeKinds: Set<TransportKind> = setOf(TransportKind.Bluetooth, TransportKind.WifiAware)
    override val transportHealth = MutableStateFlow(TransportHealth.Healthy)
    override val transportStatuses = MutableStateFlow<List<TransportStatus>>(emptyList())
    override val peerTransports = MutableStateFlow<Map<String, Set<TransportKind>>>(emptyMap())
    override val typing = MutableStateFlow<Map<String, Set<String>>>(emptyMap())

    var startCount = 0
    var stopCount = 0
    var healCount = 0
    var mintGroupRootsCount = 0
    var restartCount = 0

    /** One recorded [sendChat] call, in the order it was made. */
    data class SentChat(
        val text: String,
        val attachment: AttachmentStore.Ingested?,
        val mentions: List<Mention>,
        val recipientId: String?,
        val group: GroupInfo?,
        val replyTo: ReplyRef?,
    )

    val sentChats = mutableListOf<SentChat>()

    /** Bodies passed to [sendPublicPost] — the bridged room's own origination path, never [sendChat]. */
    val sentPublicPosts = mutableListOf<String>()
    val sentGroupUpdates = mutableListOf<GroupInfo>()
    val sentGroupLeaves = mutableListOf<String>()
    val sentReactions = mutableListOf<Pair<String, String>>()
    val sentTyping = mutableListOf<String>()

    /** When false, [sendChat] records the call but returns false (simulates the moderator flagging the text). */
    var sendChatResult = true

    /** The same, for [sendPublicPost]: the room moderator screens a public-channel post too. */
    var sendPublicPostResult = true

    /** What [spoolStatus] answers — the Internet plane, as Diagnostics and the relay screen read it. */
    var spools: List<SpoolStatus> = emptyList()

    override fun spoolStatus(): List<SpoolStatus> = spools

    override fun start() {
        startCount++
    }

    override fun stop() {
        stopCount++
    }

    override fun heal() {
        healCount++
    }

    override fun mintGroupRoots() {
        mintGroupRootsCount++
    }

    override fun restart() {
        restartCount++
    }

    override suspend fun sendChat(
        text: String,
        attachment: AttachmentStore.Ingested?,
        mentions: List<Mention>,
        recipientId: String?,
        group: GroupInfo?,
        replyTo: ReplyRef?,
    ): Boolean {
        sentChats += SentChat(text, attachment, mentions, recipientId, group, replyTo)
        return sendChatResult
    }

    override suspend fun sendPublicPost(text: String): Boolean {
        sentPublicPosts += text
        return sendPublicPostResult
    }

    override suspend fun sendGroupUpdate(group: GroupInfo) {
        sentGroupUpdates += group
    }

    override suspend fun sendGroupLeave(groupId: String) {
        sentGroupLeaves += groupId
    }

    override suspend fun sendReaction(
        messageId: String,
        emoji: String,
        recipientId: String?,
        group: GroupInfo?,
    ) {
        sentReactions += (messageId to emoji)
    }

    override suspend fun sendTyping(conversationId: String) {
        sentTyping += conversationId
    }
}
