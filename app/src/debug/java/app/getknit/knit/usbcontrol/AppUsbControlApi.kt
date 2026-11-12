package app.getknit.knit.usbcontrol

import android.content.Context
import app.getknit.knit.BuildConfig
import app.getknit.knit.contacts.DirectContacts
import app.getknit.knit.data.GroupRepository
import app.getknit.knit.data.LocalMessageDelete
import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.PeerDirectory
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.PinRepository
import app.getknit.knit.data.ReactionRepository
import app.getknit.knit.data.commons.CommonsRepository
import app.getknit.knit.data.draft.DraftRepository
import app.getknit.knit.data.group.GroupMembersStore
import app.getknit.knit.data.message.ConversationKind
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.message.quoteHasAttachment
import app.getknit.knit.data.message.quoteSnippet
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.debug.DebugReact
import app.getknit.knit.debug.DebugSend
import app.getknit.knit.identity.Alias
import app.getknit.knit.identity.Identity
import app.getknit.knit.identity.displayNameFor
import app.getknit.knit.mesh.MeshController
import app.getknit.knit.mesh.lora.LoraStatusRepository
import app.getknit.knit.mesh.protocol.BodyStyle
import app.getknit.knit.mesh.protocol.FrameId
import app.getknit.knit.mesh.protocol.Mention
import app.getknit.knit.mesh.protocol.ReplyRef
import app.getknit.knit.mesh.protocol.StyleSpan
import app.getknit.knit.notifications.Notifier
import app.getknit.knit.ui.ConversationTable
import app.getknit.knit.ui.ConversationTitle
import app.getknit.knit.ui.MeshRoomInputs
import app.getknit.knit.ui.chat.quoteLabels
import app.getknit.knit.ui.conversationTitle
import app.getknit.knit.ui.isPending
import app.getknit.knit.ui.observeConversationTable
import app.getknit.knit.ui.visibleConversations
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * The USB control's view of the app, through the entry points the screens and the debug bridge already use: the chat
 * list's own table, titles and request predicate (`ui/ConversationTable`, `ui/ConversationTitles`), the read
 * watermarks, the label index (ADR 058), and [DebugSend] for sending.
 */
internal class AppUsbControlApi :
    UsbControlApi,
    Hydrator,
    KoinComponent {
    private val context: Context by inject()
    private val identity: Identity by inject()
    private val settings: SettingsStore by inject()
    private val mesh: MeshController by inject()
    private val messages: MessageRepository by inject()
    private val peers: PeerRepository by inject()
    private val groups: GroupRepository by inject()
    private val reactions: ReactionRepository by inject()
    private val commons: CommonsRepository by inject()
    private val lora: LoraStatusRepository by inject()
    private val directContacts: DirectContacts by inject()
    private val notifier: Notifier by inject()
    private val localDelete: LocalMessageDelete by inject()
    private val drafts: DraftRepository by inject()
    private val pins: PinRepository by inject()

    override suspend fun identity(): JsonObject {
        val me = identity.nodeId()
        return buildJsonObject {
            put("node", me)
            put("name", selfName(me))
            put("alias", Alias.aliasFor(me))
            put("avatarHash", settings.ownAvatarHash.first())
            put("app", BuildConfig.VERSION_NAME)
        }
    }

    override suspend fun status(): JsonObject =
        buildJsonObject {
            put("node", identity.nodeId())
            put("name", settings.displayName.first())
            put("health", mesh.transportHealth.value.name)
            put("neighbors", mesh.neighborCount.value)
            put("meshEnabled", settings.meshEnabled.first())
            put("meshPausedUntil", settings.meshPausedUntil.first())
            putJsonArray("reachable") { reachable().sorted().forEach { add(JsonPrimitive(it)) } }
        }

    override suspend fun contacts(): List<JsonObject> {
        val directory = peers.observeDirectory().first()
        val reachable = reachable()
        val blocked = blocked()
        return directContacts
            .read()
            .sortedBy { directory.label(it).text.lowercase() }
            .map { id -> contactJson(id, directory, reachable, blocked, contact = true) }
    }

    override suspend fun chats(): List<JsonObject> {
        val list = chatList()
        return list.entries.map { (title, state) -> summary(list, title, state) }.sortedByDescending { activityOf(it) }
    }

    override suspend fun chat(conversation: String): JsonObject? = chats(listOf(conversation))[conversation]

    override suspend fun chats(conversations: Collection<String>): Map<String, JsonObject> {
        val list = chatList()
        val wanted = conversations.toSet()
        return list.entries
            .filter { it.first.id in wanted }
            .associate { (title, state) -> title.id to summary(list, title, state) }
    }

    override suspend fun history(
        conversation: String,
        before: HistoryCursor?,
        limit: Int,
    ): HistoryPage {
        val blocked = blocked()
        val speakers = speakers()
        // One past the limit says whether anything older is left without a second query.
        val raw = messages.page(conversation, before?.sentAt, before?.id, limit + 1)
        val considered = raw.take(limit)
        val reacted = reactions.observeReactionsIn(conversation, identity.nodeId()).first().groupBy { it.messageId }
        val items =
            considered
                .filter { it.senderId !in blocked }
                .map { ControlJson.message(it, speakers, reacted[it.id].orEmpty()) to HistoryCursor(it.sentAt, it.id) }
        return HistoryPage(items, considered.lastOrNull()?.let { HistoryCursor(it.sentAt, it.id) }, exhausted = raw.size <= limit)
    }

    override suspend fun message(id: String): JsonObject? = messages(listOf(id))[id]

    override suspend fun messages(ids: Collection<String>): Map<String, JsonObject> {
        val blocked = blocked()
        val speakers = speakers()
        val me = identity.nodeId()
        return ids
            .distinct()
            .mapNotNull { id -> messages.find(id)?.takeIf { it.senderId !in blocked } }
            .associate { row -> row.id to ControlJson.message(row, speakers, reactions.observeReactionsFor(row.id, me).first()) }
    }

    override suspend fun contact(nodeId: String): JsonObject =
        contactJson(nodeId, peers.observeDirectory().first(), reachable(), blocked(), contact = nodeId in directContacts.read())

    override suspend fun groupsWith(nodeId: String): List<String> = groups.groupsWith(nodeId).map { it.groupId }

    /** Fires (debounced) when the transport's health or who is reachable changes: the `status` event's trigger. */
    @OptIn(FlowPreview::class)
    fun statusChanges(): Flow<Unit> =
        combine(mesh.transportHealth, mesh.neighbors.map { list -> list.mapTo(HashSet()) { it.nodeId } }) { health, ids -> health to ids }
            .distinctUntilChanged()
            .drop(1)
            .debounce(STATUS_DEBOUNCE_MS)
            .map { }

    override suspend fun send(request: SendRequest): JsonObject {
        val conversation = request.conversation
        val kind = Conversations.kindFor(conversation)
        val me = identity.nodeId()
        if (conversation == me) fail(Codes.BAD_REQUEST, "that is this phone")
        val plainOnly = request.replyTo != null || request.mentions.isNotEmpty() || request.styles.isNotEmpty()
        if (kind == ConversationKind.MESHTASTIC && plainOnly) fail(Codes.BAD_REQUEST, "the Meshtastic room takes plain text only")
        if (kind == ConversationKind.COMMONS && commons.find(conversation) == null) fail(Codes.NOT_FOUND, "not in that commons")
        val directory = peers.observeDirectory().first()
        val replyTo = request.replyTo?.let { quoteOf(it, conversation, me, directory) }
        // A mention is the label the sender rendered after its "@" (ADR 058), so the body must carry exactly that.
        val mentions =
            request.mentions.distinct().map { node ->
                val label = directory.label(node).text
                if ("@$label" !in request.text) fail(Codes.BAD_REQUEST, "mention @$label is not in the text")
                Mention(node, label)
            }
        val frameId = FrameId.new()
        val outcome =
            DebugSend.send(
                mesh,
                groups,
                conversation,
                request.text,
                replyTo = replyTo,
                styles = spansOf(request.styles, request.text),
                mentions = mentions,
                frameId = frameId,
            )
        return when (outcome) {
            DebugSend.Outcome.Sent -> sendReply("sent", frameId)
            DebugSend.Outcome.Queued -> sendReply("queued", null)
            DebugSend.Outcome.Blocked -> fail(Codes.BLOCKED, "the on-device content filter refused it")
            DebugSend.Outcome.UnknownGroup -> fail(Codes.UNKNOWN_GROUP, "not a group this phone has joined")
            is DebugSend.Outcome.Refused -> fail(Codes.REFUSED, "the board refused it: ${outcome.reason.name}")
        }
    }

    override suspend fun react(
        id: String,
        emoji: String?,
    ): JsonObject {
        // Hidden from every read when its sender is blocked, so hidden here too.
        messages.find(id)?.takeIf { it.senderId !in blocked() } ?: fail(Codes.NOT_FOUND, "no such message")
        val current = reactions.currentEmoji(id, identity.nodeId())
        // The send toggles, as the screen's tap does: our own emoji again takes it off.
        val toggle = emoji ?: current
        if (emoji == current || toggle == null) return reactReply(id, current, changed = false)
        return when (DebugReact.react(mesh, messages, groups, id, toggle)) {
            DebugReact.Outcome.Sent -> reactReply(id, emoji, changed = true)
            DebugReact.Outcome.NoMessage -> fail(Codes.NOT_FOUND, "no such message")
            DebugReact.Outcome.Refused -> fail(Codes.REFUSED, "no reaction can go to this thread")
        }
    }

    override suspend fun markRead(
        conversation: String,
        upTo: Long?,
    ): JsonObject {
        val newest = messages.newestSentAt(conversation)
        if (newest == null && groups.find(conversation) == null) fail(Codes.NOT_FOUND, "no such thread")
        val current = settings.lastReadAll.first()[conversation] ?: 0L
        // Never back, and never past the newest message: a watermark ahead of it would hide what arrives next.
        val ceiling = newest ?: System.currentTimeMillis()
        val target = maxOf(current, minOf(upTo ?: ceiling, ceiling))
        if (target != current) settings.setLastReadAt(conversation, target)
        notifier.clearConversation(conversation)
        return buildJsonObject {
            put("conv", conversation)
            put("lastReadAt", target)
        }
    }

    override suspend fun delete(id: String): JsonObject {
        val row = messages.find(id) ?: fail(Codes.NOT_FOUND, "no such message")
        localDelete.delete(id, row.attachmentHash)
        return buildJsonObject {
            put("messageId", id)
            put("conv", row.conversationId)
        }
    }

    override suspend fun accept(conversation: String): JsonObject {
        requirePending(conversation)
        settings.accept(conversation)
        return buildJsonObject { put("conv", conversation) }
    }

    override suspend fun decline(conversation: String): JsonObject {
        requirePending(conversation)
        drafts.clear(conversation)
        if (Conversations.kindFor(conversation) == ConversationKind.GROUP) {
            groups.delete(conversation)
        } else {
            messages.deleteByConversation(conversation)
        }
        return buildJsonObject { put("conv", conversation) }
    }

    override suspend fun block(node: String): JsonObject {
        requirePeer(node)
        settings.block(node, peers.find(node)?.deviceTag)
        pins.unpinFrom(node)
        return blockReply(node, blocked = true)
    }

    override suspend fun unblock(node: String): JsonObject {
        requirePeer(node)
        settings.unblock(node, peers.find(node)?.deviceTag)
        return blockReply(node, blocked = false)
    }

    /** The quote a reply to [id] carries, built as the chat screen builds it: somebody's words, in this very thread. */
    private suspend fun quoteOf(
        id: String,
        conversation: String,
        me: String,
        directory: PeerDirectory,
    ): ReplyRef {
        val row = messages.find(id)?.takeIf { it.senderId !in blocked() } ?: fail(Codes.NOT_FOUND, "no message $id to reply to")
        if (row.conversationId != conversation) fail(Codes.BAD_REQUEST, "that message is in another thread")
        if (row.kind != MessageEntity.KIND_NORMAL || row.originNode != null) fail(Codes.BAD_REQUEST, "only somebody's words can be quoted")
        return ReplyRef(
            messageId = row.id,
            authorId = row.senderId,
            author = if (row.senderId == me) selfName(me) else directory.label(row.senderId).name,
            snippet =
                quoteSnippet(
                    row.body,
                    row.attachmentName,
                    row.attachmentMime,
                    row.attachmentAnimated,
                    flagged = row.moderation == MessageEntity.MODERATION_TEXT_FLAGGED,
                    labels = quoteLabels(context),
                ),
            hasAttachment = quoteHasAttachment(row.attachmentHash, row.attachmentMime),
        )
    }

    /** `[start, length, code]` triples as spans over [text]; any that is not one is refused, not dropped. */
    private fun spansOf(
        triples: List<List<Int>>,
        text: String,
    ): List<StyleSpan> =
        triples.map { triple ->
            val (start, length, code) = triple
            val style = BodyStyle.ofCode(code) ?: fail(Codes.BAD_REQUEST, "unknown style code $code")
            val inside = start >= 0 && length > 0 && start <= text.length - length
            if (!inside) fail(Codes.BAD_REQUEST, "style [$start, $length] is outside the text")
            StyleSpan(start, start + length, style)
        }

    private suspend fun requirePending(conversation: String) {
        val entry = chatList().entries.firstOrNull { it.first.id == conversation } ?: fail(Codes.NOT_FOUND, "no such thread")
        if (entry.second != STATE_REQUEST) fail(Codes.NOT_PENDING, "not a message request")
    }

    private suspend fun requirePeer(node: String) {
        if (Conversations.kindFor(node) != ConversationKind.DM || node == identity.nodeId()) fail(Codes.BAD_REQUEST, "not a peer")
    }

    private fun sendReply(
        status: String,
        id: String?,
    ): JsonObject =
        buildJsonObject {
            put("status", status)
            put("messageId", id)
        }

    private fun reactReply(
        id: String,
        emoji: String?,
        changed: Boolean,
    ): JsonObject =
        buildJsonObject {
            put("messageId", id)
            put("emoji", emoji)
            put("changed", changed)
        }

    private fun blockReply(
        node: String,
        blocked: Boolean,
    ): JsonObject =
        buildJsonObject {
            put("node", node)
            put("blocked", blocked)
        }

    private fun fail(
        code: String,
        message: String,
    ): Nothing = throw UsbControlError(code, message)

    /** Every thread the chat list shows (accepted) and every message request, titled, read at one moment. */
    private suspend fun chatList(): ChatList {
        val me = identity.nodeId()
        val blocked = blocked()
        val table = messages.observeConversationTable(CHAT_KINDS, flowOf(blocked), flowOf(me)).first()
        val directory = peers.observeDirectory().first()
        val groupRows = groups.observeGroups().first()
        val accepted = settings.acceptedConversations.first()
        val facts = lora.facts.first()
        val meshRoom =
            MeshRoomInputs(
                enabled = facts.room,
                plane = facts.plane,
                liveChannel = facts.primaryChannel,
                newestChannel = messages.observeNewestOriginChannel(Conversations.MESHTASTIC).first(),
            )
        val visible = visibleConversations(context, table, groupRows, directory, accepted, meshRoom, commons.observeAll().first())
        val visibleIds = visible.mapTo(HashSet()) { it.id }
        val groupsById = groupRows.associateBy { it.groupId }
        // The requests inbox's own candidates (MessageRequestsViewModel): every thread with a row and every active
        // group, pending by the shared predicate; a group we left or hold no row for is not offered.
        val requests =
            (table.conversations + groupRows.filter { !it.left }.map { it.groupId })
                .asSequence()
                .distinct()
                .filter { it != Conversations.NEARBY && it !in visibleIds && table.isPending(it, accepted, directory.verified) }
                .mapNotNull { id ->
                    val group = groupsById[id]
                    if (Conversations.kindFor(id) == ConversationKind.GROUP && (group == null || group.left)) {
                        null
                    } else {
                        conversationTitle(context, id, group, directory, me, null)
                    }
                }.toList()
        return ChatList(
            me = me,
            table = table,
            speakers = Speakers(me, selfName(me)) { directory.label(it).text },
            lastRead = settings.lastReadAll.first(),
            entries = visible.map { it to STATE_ACCEPTED } + requests.map { it to STATE_REQUEST },
        )
    }

    /** One thread's chat object, its unread badge counted the way the chat list counts it. */
    private suspend fun summary(
        list: ChatList,
        title: ConversationTitle,
        state: String,
    ): JsonObject {
        val head = list.table.heads[title.id]
        val lastRead = list.lastRead[title.id] ?: 0L
        val unread = messages.countUnreadIn(title.id, lastRead, list.me, list.table.blocked)
        return buildJsonObject {
            put("conv", title.id)
            put("kind", title.kind.name.lowercase())
            put("title", title.text)
            put("discriminator", title.discriminator)
            put("avatarHash", title.avatarHash)
            title.group?.let { group ->
                putJsonArray("members") { GroupMembersStore.decode(group.members).forEach { add(JsonPrimitive(it)) } }
            }
            put("state", state)
            put("unread", unread)
            put("lastReadAt", lastRead)
            put("last", head?.let { ControlJson.message(it, list.speakers, preview = true) } ?: JsonNull)
            put("lastActivity", head?.sentAt ?: title.group?.createdAt)
        }
    }

    private fun contactJson(
        id: String,
        directory: PeerDirectory,
        reachable: Set<String>,
        blocked: Set<String>,
        contact: Boolean,
    ): JsonObject {
        val label = directory.label(id)
        val row = directory.byNode[id]
        return buildJsonObject {
            put("id", id)
            put("label", label.text)
            put("name", label.name)
            put("alias", label.alias)
            put("discriminator", label.discriminator)
            put("contact", contact)
            put("verified", row?.verified == true)
            put("reachable", id in reachable)
            put("blocked", id in blocked)
            put("avatarHash", row?.avatarHash)
            put("openToChat", row?.openToChat == true)
            put("lastSeenAt", row?.lastSeenAt?.takeIf { it > 0 })
        }
    }

    private suspend fun speakers(): Speakers {
        val me = identity.nodeId()
        val directory = peers.observeDirectory().first()
        return Speakers(me, selfName(me)) { directory.label(it).text }
    }

    private suspend fun selfName(me: String): String = displayNameFor(settings.displayName.first(), me)

    private suspend fun blocked(): Set<String> = settings.blockedNodeIds.first()

    private fun reachable(): Set<String> = mesh.neighbors.value.mapTo(HashSet()) { it.nodeId }

    private fun activityOf(chat: JsonObject): Long = (chat["lastActivity"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L

    private class ChatList(
        val me: String,
        val table: ConversationTable,
        val speakers: Speakers,
        val lastRead: Map<String, Long>,
        val entries: List<Pair<ConversationTitle, String>>,
    )

    private companion object {
        /** The chat list's own kinds: what a row's preview, time and sort are read from. */
        val CHAT_KINDS = setOf(MessageEntity.KIND_NORMAL, MessageEntity.KIND_FILE_TRANSFER, MessageEntity.KIND_CALL)
        const val STATE_ACCEPTED = "accepted"
        const val STATE_REQUEST = "request"
        const val STATUS_DEBOUNCE_MS = 1_000L
    }
}
