package app.getknit.knit.usbcontrol

import app.getknit.knit.BuildConfig
import app.getknit.knit.contacts.DirectContacts
import app.getknit.knit.data.GroupRepository
import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.message.ConversationKind
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.debug.DebugSend
import app.getknit.knit.identity.Identity
import app.getknit.knit.mesh.MeshController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * The USB control spike's view of the app, through the entry points the debug bridge already drives: the identity
 * and settings, the message and peer repositories, and [DebugSend] for sending. Read-only apart from `send`.
 */
internal class AppUsbControlApi :
    UsbControlApi,
    KoinComponent {
    private val identity: Identity by inject()
    private val settings: SettingsStore by inject()
    private val mesh: MeshController by inject()
    private val messages: MessageRepository by inject()
    private val peers: PeerRepository by inject()
    private val groups: GroupRepository by inject()
    private val directContacts: DirectContacts by inject()

    override suspend fun identity(): JsonObject =
        buildJsonObject {
            put("node", identity.nodeId())
            put("name", settings.displayName.first())
            put("app", BuildConfig.VERSION_NAME)
        }

    override suspend fun status(): JsonObject =
        buildJsonObject {
            put("node", identity.nodeId())
            put("name", settings.displayName.first())
            put("health", mesh.transportHealth.value.name)
            put("neighbors", mesh.neighborCount.value)
            put("meshEnabled", settings.meshEnabled.first())
            put("meshPausedUntil", settings.meshPausedUntil.first())
        }

    override suspend fun contacts(): JsonArray {
        val reachable = mesh.neighbors.value.mapTo(HashSet()) { it.nodeId }
        return buildJsonArray {
            directContacts.read().sorted().forEach { id ->
                add(
                    buildJsonObject {
                        put("id", id)
                        put("name", peers.find(id)?.name.orEmpty())
                        put("reachable", id in reachable)
                    },
                )
            }
        }
    }

    override suspend fun chats(): JsonArray {
        val me = identity.nodeId()
        val heads = messages.observeNewestPerConversation(setOf(MessageEntity.KIND_NORMAL), blocked()).first()
        return buildJsonArray {
            heads.forEach { (conversation, head) ->
                add(
                    buildJsonObject {
                        put("conv", conversation)
                        put("kind", kindOf(conversation))
                        put("title", titleOf(conversation))
                        put("last", buildJsonObject { message(head, me) })
                    },
                )
            }
        }
    }

    override suspend fun history(
        conversation: String,
        limit: Int,
    ): JsonArray {
        val me = identity.nodeId()
        val rows = messages.observeNewestMessages(conversation, limit).first()
        return buildJsonArray { rows.forEach { add(buildJsonObject { message(it, me) }) } }
    }

    override suspend fun send(
        conversation: String,
        text: String,
    ): JsonObject {
        val (status, message) =
            when (val outcome = DebugSend.send(mesh, groups, conversation, text)) {
                DebugSend.Outcome.Sent -> "ok" to "sent"
                DebugSend.Outcome.Queued -> "queued" to "queued on the board"
                DebugSend.Outcome.Blocked -> "blocked" to "blocked by on-device content filter"
                DebugSend.Outcome.UnknownGroup -> "error" to "unknown group (not joined on this device)"
                is DebugSend.Outcome.Refused -> "refused" to "the board refused it: ${outcome.reason.name}"
            }
        return buildJsonObject {
            put("status", status)
            put("message", message)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun messageEvents(): Flow<JsonObject> =
        flow {
            val me = identity.nodeId()
            val heads =
                NewHeads<MessageEntity>(
                    since = System.currentTimeMillis(),
                    idOf = { it.id },
                    stampOf = { it.arrivedAt ?: it.sentAt },
                )
            settings.blockedNodeIds
                .flatMapLatest { blocked -> messages.observeNewestPerConversation(setOf(MessageEntity.KIND_NORMAL), blocked) }
                .collect { snapshot ->
                    heads.next(snapshot.values).forEach { row ->
                        emit(
                            buildJsonObject {
                                put("event", "message")
                                put("kind", kindOf(row.conversationId))
                                message(row, me)
                            },
                        )
                    }
                }
        }

    private suspend fun blocked(): Set<String> = settings.blockedNodeIds.first()

    private suspend fun JsonObjectBuilder.message(
        row: MessageEntity,
        me: String,
    ) {
        val mine = row.senderId == me
        put("id", row.id)
        put("conv", row.conversationId)
        put("from", row.senderId)
        put("fromName", if (mine) settings.displayName.first() else peers.find(row.senderId)?.name.orEmpty())
        put("mine", mine)
        put("body", row.body)
        put("sentAt", row.sentAt)
        put("arrivedAt", row.arrivedAt)
        put("received", row.received)
        put("system", row.kind != MessageEntity.KIND_NORMAL)
    }

    private fun kindOf(conversation: String): String = Conversations.kindFor(conversation).name.lowercase()

    private suspend fun titleOf(conversation: String): String =
        when (Conversations.kindFor(conversation)) {
            ConversationKind.NEARBY -> "Nearby"
            ConversationKind.MESHTASTIC -> "Meshtastic"
            ConversationKind.DM -> peers.find(conversation)?.name?.ifBlank { null } ?: conversation
            ConversationKind.GROUP -> groups.find(conversation)?.name?.ifBlank { null } ?: conversation
            ConversationKind.COMMONS -> conversation
        }
}
