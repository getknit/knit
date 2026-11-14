package app.getknit.knit.usbcontrol

import android.content.Context
import app.getknit.knit.BuildConfig
import app.getknit.knit.TextLimits
import app.getknit.knit.clampToUtf16
import app.getknit.knit.contacts.DirectContacts
import app.getknit.knit.data.AttachmentStore
import app.getknit.knit.data.BlobRepository
import app.getknit.knit.data.FileTypes
import app.getknit.knit.data.GroupRepository
import app.getknit.knit.data.LocalMessageDelete
import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.PeerDirectory
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.PinRepository
import app.getknit.knit.data.ReactionRepository
import app.getknit.knit.data.blob.BlobVerdictDao
import app.getknit.knit.data.commons.CommonsRepository
import app.getknit.knit.data.draft.DraftRepository
import app.getknit.knit.data.group.GroupMembersStore
import app.getknit.knit.data.message.ConversationKind
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.message.quoteHasAttachment
import app.getknit.knit.data.message.quoteSnippet
import app.getknit.knit.data.relay.FreeUploads
import app.getknit.knit.data.relay.RelayStatusRepository
import app.getknit.knit.data.relay.photoFitBytes
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.debug.DebugReact
import app.getknit.knit.debug.DebugSend
import app.getknit.knit.identity.Alias
import app.getknit.knit.identity.Identity
import app.getknit.knit.identity.NodeId
import app.getknit.knit.identity.displayNameFor
import app.getknit.knit.mesh.MeshController
import app.getknit.knit.mesh.crypto.AttachmentCrypto
import app.getknit.knit.mesh.crypto.b64d
import app.getknit.knit.mesh.lora.LoraStatusRepository
import app.getknit.knit.mesh.protocol.BodyStyle
import app.getknit.knit.mesh.protocol.FrameId
import app.getknit.knit.mesh.protocol.Mention
import app.getknit.knit.mesh.protocol.ReplyRef
import app.getknit.knit.mesh.protocol.StyleSpan
import app.getknit.knit.normalizeSingleLine
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
import kotlinx.serialization.json.addJsonObject
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
    private val blobs: BlobRepository by inject()
    private val attachments: AttachmentStore by inject()
    private val relays: RelayStatusRepository by inject()
    private val verdicts: BlobVerdictDao by inject()

    override suspend fun identity(): JsonObject {
        val me = identity.nodeId()
        return buildJsonObject {
            put("node", me)
            put("name", selfName(me))
            put("alias", Alias.aliasFor(me))
            put("avatarHash", settings.ownAvatarHash.first())
            put("status", ownStatus())
            put("app", BuildConfig.VERSION_NAME)
        }
    }

    // Folded and capped again, as the profile frame carries it, whatever the caller already did.
    override suspend fun setStatus(status: String) {
        settings.setStatus(normalizeSingleLine(status).clampToUtf16(TextLimits.STATUS))
    }

    /** Our status as the profile frame carries it (`MeshManager.currentProfilePayload`): one line, capped. */
    private suspend fun ownStatus(): String = normalizeSingleLine(settings.status.first()).clampToUtf16(TextLimits.STATUS)

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
        val readable = considered.filter { it.senderId !in blocked }
        val facts = attachmentFacts(readable)
        val items =
            readable.map {
                ControlJson.message(it, speakers, reacted[it.id].orEmpty(), attachments = facts) to
                    HistoryCursor(it.sentAt, it.id)
            }
        return HistoryPage(items, considered.lastOrNull()?.let { HistoryCursor(it.sentAt, it.id) }, exhausted = raw.size <= limit)
    }

    override suspend fun message(id: String): JsonObject? = messages(listOf(id))[id]

    override suspend fun messages(ids: Collection<String>): Map<String, JsonObject> {
        val blocked = blocked()
        val speakers = speakers()
        val me = identity.nodeId()
        val rows = ids.distinct().mapNotNull { id -> messages.find(id)?.takeIf { it.senderId !in blocked } }
        val facts = attachmentFacts(rows)
        return rows.associate { row ->
            row.id to ControlJson.message(row, speakers, reactions.observeReactionsFor(row.id, me).first(), attachments = facts)
        }
    }

    override suspend fun contact(nodeId: String): JsonObject =
        contactJson(nodeId, peers.observeDirectory().first(), reachable(), blocked(), contact = nodeId in directContacts.read())

    override suspend fun groupsWith(nodeId: String): List<String> = groups.groupsWith(nodeId).map { it.groupId }

    override suspend fun namingAttachment(
        hash: String,
        limit: Int,
    ): List<Pair<String, String>> = messages.namingAttachment(hash, limit).map { it.id to it.conversationId }

    /** Fires (debounced) when the transport's health or who is reachable changes: the `status` event's trigger. */
    @OptIn(FlowPreview::class)
    fun statusChanges(): Flow<Unit> =
        combine(mesh.transportHealth, mesh.neighbors.map { list -> list.mapTo(HashSet()) { it.nodeId } }) { health, ids -> health to ids }
            .distinctUntilChanged()
            .drop(1)
            .debounce(STATUS_DEBOUNCE_MS)
            .map { }

    override suspend fun typing(): JsonObject {
        val shown = shownTyping(mesh.typing.value, settings.typingIndicatorsEnabled.first(), blocked())
        if (shown.isEmpty()) return JsonObject(emptyMap())
        val directory = peers.observeDirectory().first()
        return buildJsonObject {
            shown.toSortedMap().forEach { (conversation, nodes) ->
                putJsonArray(conversation) {
                    nodes.sorted().forEach { node ->
                        addJsonObject {
                            put("node", node)
                            put("label", directory.label(node).text)
                        }
                    }
                }
            }
        }
    }

    /** Fires when who the chat shows typing changes, the switch and the blocked set included: the `typing` event's trigger. */
    fun typingChanges(): Flow<Unit> =
        combine(mesh.typing, settings.typingIndicatorsEnabled, settings.blockedNodeIds, ::shownTyping)
            .distinctUntilChanged()
            .drop(1)
            .map { }

    override suspend fun canType(conversation: String): Boolean {
        val kind = Conversations.kindFor(conversation)
        // The bridged room and a commons carry no typing frame: a cue minted for one would land as a Nearby cue (§7.4).
        if (kind == ConversationKind.MESHTASTIC || kind == ConversationKind.COMMONS) {
            fail(Codes.REFUSED, "this thread carries no typing cue")
        }
        // The chat list shows no group this phone left and no blocked peer's DM.
        val state = chatList().entries.firstOrNull { it.first.id == conversation }?.second ?: fail(Codes.NOT_FOUND, "no such thread")
        // A cue would tell whoever asked that someone is there before the person has said yes to them.
        if (state == STATE_REQUEST) fail(Codes.REFUSED, "a message request gets no typing cue until it is accepted")
        return settings.typingIndicatorsEnabled.first()
    }

    override suspend fun sendTyping(conversation: String) = mesh.sendTyping(conversation)

    override suspend fun send(request: SendRequest): JsonObject {
        val conversation = request.conversation
        val kind = Conversations.kindFor(conversation)
        val me = identity.nodeId()
        if (conversation == me) fail(Codes.BAD_REQUEST, "that is this phone")
        // Any id that names no room, group or commons is taken for a DM's peer, so it must be one: a node id.
        if (kind == ConversationKind.DM && !isNodeId(conversation)) fail(Codes.BAD_REQUEST, "no such thread: $conversation is no node id")
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
        val styles = spansOf(request.styles, request.text)
        if (request.picture != null) refusePictureIn(conversation, kind)
        if (kind == ConversationKind.GROUP &&
            groups.find(conversation) == null
        ) {
            fail(Codes.UNKNOWN_GROUP, "not a group this phone has joined")
        }
        // Every refusal that needs no bytes is above: from here a picture is stored, and goes unless the send does.
        val picture = request.picture?.let { picturePrepared(it, kind) }
        val frameId = FrameId.new()
        var sent = false
        val outcome =
            try {
                DebugSend
                    .send(
                        mesh,
                        groups,
                        conversation,
                        request.text,
                        replyTo = replyTo,
                        styles = styles,
                        mentions = mentions,
                        frameId = frameId,
                        attachment = picture,
                    ).also { sent = it == DebugSend.Outcome.Sent }
            } finally {
                // A picture that did not go is dropped, as the composer drops what it never sends (unless something names it).
                if (!sent) picture?.let { blobs.deleteIfUnreferenced(it.hash) }
            }
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

    override suspend fun attachment(id: String): BlobSource {
        val row = readable(id)
        val hash = row.attachmentHash ?: fail(Codes.NOT_FOUND, "message $id has no attachment")
        if (!blobs.exists(hash)) fail(Codes.NOT_HELD, "this phone does not hold it; fetch asks the mesh")
        val key = row.attachmentKey
        // Asked only when the bytes are opened, once per file: a request never turns back from accepted, and a decline
        // deletes the row, which the check above meets on every slice.
        return BlobSource(hash, row.attachmentMime, row.attachmentName) {
            refuseRequest(row)
            val stored = blobs.bytes(hash) ?: fail(Codes.NOT_HELD, "this phone no longer holds it")
            // A DM's or a group's bytes are sealed under the row's key, which stays on the phone.
            if (key == null) stored else AttachmentCrypto.open(stored, b64d(key)) ?: fail(Codes.INTERNAL, "the row's key does not open it")
        }
    }

    override suspend fun avatar(hash: String): BlobSource {
        if (hash !in shownPhotos()) fail(Codes.NOT_FOUND, "not a photo this phone shows")
        if (!blobs.exists(hash)) fail(Codes.NOT_HELD, "this phone does not hold it")
        return BlobSource(hash, blobs.mimeFor(hash), name = null) {
            blobs.bytes(hash) ?: fail(Codes.NOT_HELD, "this phone no longer holds it")
        }
    }

    override suspend fun fetch(id: String): JsonObject {
        val row = readable(id)
        val hash = row.attachmentHash ?: fail(Codes.NOT_FOUND, "message $id has no attachment")
        val reply = buildJsonObject { put("messageId", id) }
        refuseRequest(row)
        if (blobs.exists(hash)) return JsonObject(reply + ("held" to JsonPrimitive(true)))
        mesh.downloadAttachment(hash)
        return JsonObject(reply + ("asked" to JsonPrimitive(true)))
    }

    /**
     * Refuses [row]'s attachment while its thread is a message request: its bytes may have landed (as a carrier's, or a
     * contact's of the same hash), but the inbox shows none and our pull waits for the acceptance (ADR 2026-11.qu98).
     * The chat list's own predicate, as `accept` reads it.
     */
    private suspend fun refuseRequest(row: MessageEntity) {
        val state = chatList().entries.firstOrNull { it.first.id == row.conversationId }?.second
        if (state == STATE_REQUEST) fail(Codes.REFUSED, "a message request's attachment waits until it is accepted")
    }

    /** Which of [rows]' attachments this phone holds, and the filter's verdicts, read once for the batch. */
    private suspend fun attachmentFacts(rows: Collection<MessageEntity>): AttachmentFacts {
        val hashes = rows.mapNotNullTo(HashSet()) { it.attachmentHash }
        if (hashes.isEmpty()) return AttachmentFacts.NONE
        val filtering = settings.contentFilteringEnabled.first()
        return AttachmentFacts(
            held = blobs.sizes(hashes).keys,
            flagged = if (filtering) verdicts.observeFlaggedHashes().first().filterTo(HashSet()) { it in hashes } else emptySet(),
            filtering = filtering,
        )
    }

    /** Message [id]'s row when `message` would serve it: it exists and its sender is not blocked. */
    private suspend fun readable(id: String): MessageEntity =
        messages.find(id)?.takeIf { it.senderId !in blocked() } ?: fail(Codes.NOT_FOUND, "no such message")

    /** Every photo the phone shows as someone's: our own, an unblocked peer's, and a group's it shows. */
    private suspend fun shownPhotos(): Set<String> {
        val blocked = blocked()
        val peersShown =
            peers
                .observeDirectory()
                .first()
                .byNode.values
                .filter { it.nodeId !in blocked }
                .mapNotNull { it.avatarHash }
        val groupsShown =
            groups
                .observeGroups()
                .first()
                .filter { !it.left }
                .mapNotNull { it.photoShownHash }
        return (peersShown + groupsShown + listOfNotNull(settings.ownAvatarHash.first())).toSet()
    }

    /** The threads the chat's picker offers nothing in (`ChatViewModel.stage`): the Meshtastic room, a text-only commons. */
    private suspend fun refusePictureIn(
        conversation: String,
        kind: ConversationKind,
    ) {
        if (kind == ConversationKind.MESHTASTIC) fail(Codes.REFUSED, "the Meshtastic room carries text only")
        if (kind == ConversationKind.COMMONS && conversation in relays.facts.first().textOnlyRooms) {
            fail(Codes.REFUSED, "this commons' relay takes no attachments")
        }
    }

    /**
     * [bytes] made ready to send the way the chat's picker makes a picture ready (`ChatViewModel.attachPicture` and
     * `stage`): the Nearby room screens it and refuses a flagged one, metadata is stripped or the picture re-encoded,
     * and it is fitted to the relays' tickets outside the rooms. Stored on success; the caller drops it if unsent.
     */
    private suspend fun picturePrepared(
        bytes: ByteArray,
        kind: ConversationKind,
    ): AttachmentStore.Ingested {
        val room = kind == ConversationKind.NEARBY
        val fit =
            if (room) {
                null
            } else {
                getKoin()
                    .getOrNull<FreeUploads>()
                    ?.photoChunks
                    ?.first()
                    ?.let(::photoFitBytes)
            }
        val result = attachments.ingestPicked(bytes, FileTypes.imageMimeOf(bytes), screen = room, fitBytes = fit)
        val picked =
            when (result) {
                is AttachmentStore.IngestResult.Success -> result
                is AttachmentStore.IngestResult.Failed -> fail(Codes.BAD_REQUEST, "not a picture this phone reads (${result.reason.name})")
            }
        val ingested = picked.ingested
        // The picker's identity reuse can answer with what this phone once sent under those bytes: only a picture goes.
        if (!ingested.mime.startsWith("image/")) {
            blobs.deleteIfUnreferenced(ingested.hash)
            fail(Codes.BAD_REQUEST, "not a picture")
        }
        if (room && picked.flagged) {
            blobs.deleteIfUnreferenced(ingested.hash)
            fail(Codes.BLOCKED, "the on-device content filter refused the picture")
        }
        return ingested
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
        val peer = Conversations.kindFor(node) == ConversationKind.DM && isNodeId(node)
        if (!peer || node == identity.nodeId()) fail(Codes.BAD_REQUEST, "not a peer")
    }

    /**
     * Whether [id] is a node id in its one canonical spelling ([NodeId.toBytesOrNull]): a second spelling of a peer's id
     * would open a second thread, or a second block entry, for the same peer.
     */
    private fun isNodeId(id: String): Boolean = NodeId.toBytesOrNull(id) != null

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
            attachments = attachmentFacts(table.heads.values),
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
            put("last", head?.let { ControlJson.message(it, list.speakers, preview = true, attachments = list.attachments) } ?: JsonNull)
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
        val attachments: AttachmentFacts,
    )

    private companion object {
        /** The chat list's own kinds: what a row's preview, time and sort are read from. */
        val CHAT_KINDS = setOf(MessageEntity.KIND_NORMAL, MessageEntity.KIND_FILE_TRANSFER, MessageEntity.KIND_CALL)
        const val STATE_ACCEPTED = "accepted"
        const val STATE_REQUEST = "request"
        const val STATUS_DEBOUNCE_MS = 1_000L
    }
}

/**
 * Who the chat shows typing (`ChatViewModel`'s typing combine): nobody while typing indicators are off, and never a
 * blocked sender, whose cue the tracker may still hold for a few seconds after the block.
 */
internal fun shownTyping(
    typing: Map<String, Set<String>>,
    on: Boolean,
    blocked: Set<String>,
): Map<String, Set<String>> = if (!on) emptyMap() else typing.mapValues { (_, nodes) -> nodes - blocked }.filterValues { it.isNotEmpty() }
