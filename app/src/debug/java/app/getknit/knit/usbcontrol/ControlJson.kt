package app.getknit.knit.usbcontrol

import app.getknit.knit.data.message.MentionStore
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.message.StyleStore
import app.getknit.knit.data.message.receivedPlane
import app.getknit.knit.data.message.writtenBy
import app.getknit.knit.data.reaction.ReactionEntity
import app.getknit.knit.mesh.meshNodeLabel
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Who a message object names, from the reader's side: our own node id and name, and the label (ADR 058, `Name
 * (Alias)` where needed) for anyone else.
 */
internal class Speakers(
    val me: String,
    val selfName: String,
    private val labelOf: (String) -> String,
) {
    fun label(nodeId: String): String = if (nodeId == me) selfName else labelOf(nodeId)
}

/** The protocol's objects, built from the app's rows: pure, so the session and the feed share one shape. */
internal object ControlJson {
    /** How much of a body a chat summary's `last` carries; the thread itself carries the whole of it. */
    const val PREVIEW_CHARS = 160

    /** How many emoji a message object lists, and how many reactors under each. */
    const val MAX_REACTION_GROUPS = 32
    const val MAX_REACTORS = 16

    /**
     * A message: the row, its live reactions ([reactions], tombstones left out), and its speaker named for this
     * reader. [preview] trims the body for a chat summary and says so (`bodyTruncated`), so a client never stores a
     * summary's copy as the message.
     */
    fun message(
        row: MessageEntity,
        speakers: Speakers,
        reactions: List<ReactionEntity> = emptyList(),
        preview: Boolean = false,
    ): JsonObject =
        buildJsonObject {
            put("id", row.id)
            put("conv", row.conversationId)
            put("kind", kindName(row.kind))
            put("from", row.senderId)
            put("fromLabel", fromLabel(row, speakers))
            put("mine", row.writtenBy(speakers.me))
            val truncated = preview && row.body.length > PREVIEW_CHARS
            put("body", if (truncated) row.body.take(PREVIEW_CHARS) else row.body)
            if (truncated) put("bodyTruncated", true)
            put("sentAt", row.sentAt)
            put("arrivedAt", row.arrivedAt)
            put("received", row.received)
            put("via", row.receivedPlane.name)
            put("pending", row.pendingKey)
            put("flagged", row.moderation == MessageEntity.MODERATION_TEXT_FLAGGED)
            StyleStore.decode(row.styles).takeIf { it.isNotEmpty() }?.let { spans ->
                putJsonArray("styles") {
                    spans.forEach { add(ints(it.start, it.end - it.start, it.style.code)) }
                }
            }
            MentionStore.decode(row.mentions).takeIf { it.isNotEmpty() }?.let { mentions ->
                putJsonArray("mentions") {
                    mentions.forEach {
                        add(
                            buildJsonObject {
                                put("node", it.nodeId)
                                put("name", it.name)
                            },
                        )
                    }
                }
            }
            row.replyToId?.let { quoted ->
                putJsonObject("replyTo") {
                    put("id", quoted)
                    val authorId = row.replyToAuthorId.orEmpty()
                    put("authorId", authorId)
                    put("author", if (authorId.isNotEmpty()) speakers.label(authorId) else row.replyToAuthor.orEmpty())
                    put("snippet", row.replyToSnippet.orEmpty())
                    put("hasAttachment", row.replyToHasAttachment)
                    put("check", replyCheckName(row.replyCheck))
                    put("flagged", row.replyToFlagged)
                }
            }
            reactions(row.id, reactions, speakers.me)
            row.attachmentHash?.let { hash ->
                putJsonObject("attachment") {
                    put("hash", hash)
                    put("mime", row.attachmentMime)
                    put("name", row.attachmentName)
                    put("size", row.attachmentSize)
                }
            }
            row.originNode?.let { node ->
                putJsonObject("origin") {
                    put("node", meshNodeLabel(node))
                    put("name", row.originName)
                }
            }
        }

    /**
     * The live reactions on [messageId] (tombstones left out), one entry per emoji with its count, whether we are among
     * its reactors, and the first of them by node id. Bounded however many identities react: a message object must
     * stay far under the frame cap, so past [MAX_REACTION_GROUPS] emoji or [MAX_REACTORS] reactors it says
     * `reactionsTruncated`, and the counts stay whole.
     */
    private fun JsonObjectBuilder.reactions(
        messageId: String,
        reactions: List<ReactionEntity>,
        me: String,
    ) {
        val byEmoji =
            reactions
                .filter { it.messageId == messageId }
                .mapNotNull { r -> r.emoji?.let { it to r.reactorNodeId } }
                .groupBy({ it.first }, { it.second })
        if (byEmoji.isEmpty()) return
        val ordered = byEmoji.entries.sortedWith(compareByDescending<Map.Entry<String, List<String>>> { it.value.size }.thenBy { it.key })
        putJsonArray("reactions") {
            ordered.take(MAX_REACTION_GROUPS).forEach { (emoji, nodes) ->
                add(
                    buildJsonObject {
                        put("emoji", emoji)
                        put("count", nodes.size)
                        put("mine", me in nodes)
                        putJsonArray("nodes") { nodes.sorted().take(MAX_REACTORS).forEach { add(JsonPrimitive(it)) } }
                    },
                )
            }
        }
        if (ordered.size > MAX_REACTION_GROUPS || ordered.any { it.value.size > MAX_REACTORS }) put("reactionsTruncated", true)
    }

    /**
     * Who said it, as the thread names them: us for our own words; for a heard Meshtastic post the contact whose
     * profile claims that board, else the name the board had, else its `!hex` id (the sender column is this phone's
     * by convention, so it never names us); anyone else by their label. A notice names its subject.
     */
    fun fromLabel(
        row: MessageEntity,
        speakers: Speakers,
    ): String {
        row.originNode?.let { node ->
            return row.originPeerId?.let { speakers.label(it) }
                ?: row.originName?.takeIf { it.isNotBlank() }
                ?: meshNodeLabel(node)
        }
        return speakers.label(row.senderId)
    }

    fun kindName(kind: Int): String =
        when (kind) {
            MessageEntity.KIND_NORMAL -> "text"
            MessageEntity.KIND_MEMBER_LEFT -> "member_left"
            MessageEntity.KIND_PEER_RENAMED -> "peer_renamed"
            MessageEntity.KIND_PEER_AVATAR -> "peer_avatar"
            MessageEntity.KIND_GROUP_RENAMED -> "group_renamed"
            MessageEntity.KIND_GROUP_PHOTO -> "group_photo"
            MessageEntity.KIND_GROUP_CREATED -> "group_created"
            MessageEntity.KIND_KEY_PIN_REFUSED -> "key_pin_refused"
            MessageEntity.KIND_FILE_TRANSFER -> "file_transfer"
            MessageEntity.KIND_MEMBER_REJOINED -> "member_rejoined"
            MessageEntity.KIND_CALL -> "call"
            else -> "notice_$kind"
        }

    private fun replyCheckName(check: Int): String =
        when (check) {
            MessageEntity.REPLY_VERIFIED -> "verified"
            MessageEntity.REPLY_MISMATCH -> "mismatch"
            else -> "unchecked"
        }

    private fun ints(vararg values: Int): JsonArray = buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }
}

/** A list reply's items, cut to what one frame carries. */
internal object FrameBudget {
    /** Under [UsbControlFrames.MAX_FRAME] with room for the reply's own fields. */
    const val BYTES = 56 * 1024

    /**
     * The longest prefix of [items], at most [limit], whose encoded size fits [budget]; always at least one item when
     * there is one. An item over the budget by itself goes as its [stub], so a page can always move past it.
     */
    fun fit(
        items: List<JsonObject>,
        limit: Int,
        budget: Int = BYTES,
    ): List<JsonObject> {
        var used = 0
        val kept = ArrayList<JsonObject>(minOf(limit, items.size))
        for (raw in items.take(limit)) {
            val item = if (sizeOf(raw) > budget) stub(raw) else raw
            val size = sizeOf(item)
            if (kept.isNotEmpty() && used + size > budget) break
            used += size
            kept += item
        }
        return kept
    }

    /** [item] cut to the fields that name and place it, marked `oversized`: the client reads it alone, or not at all. */
    fun stub(item: JsonObject): JsonObject = JsonObject(item.filterKeys { it in STUB_KEYS } + ("oversized" to JsonPrimitive(true)))

    private fun sizeOf(item: JsonObject): Int = item.toString().encodeToByteArray().size + 1

    private val STUB_KEYS = setOf("id", "conv", "kind", "from", "sentAt", "arrivedAt", "state", "lastActivity")
}
