package app.getknit.knit.usbcontrol

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** How the feed reads an object's current state: the protocol's objects, or null when there is none now. */
internal interface Hydrator {
    /** The message objects for [ids] that exist now and are readable; an id missing from the map is gone. */
    suspend fun messages(ids: Collection<String>): Map<String, JsonObject>

    /** The chat objects for [conversations] that the chat list shows now; one missing from the map is gone. */
    suspend fun chats(conversations: Collection<String>): Map<String, JsonObject>

    suspend fun contact(nodeId: String): JsonObject

    /** The groups [nodeId] is a member of: their titles and faces name them. */
    suspend fun groupsWith(nodeId: String): List<String>

    suspend fun identity(): JsonObject

    suspend fun status(): JsonObject
}

/** What a subscribe answered: where the feed starts, and whether the client must read everything again first. */
internal data class SubscribeResult(
    val cursor: ChangeCursor,
    val resync: Boolean,
)

/**
 * One session's change feed: it takes keys from the [log], merges those for the same object, and reads each object's
 * state when it falls due, sending an event when that state differs from what this feed last sent for it.
 *
 * **Read late, read again.** A repository reports a write the moment it returns, and some of those writes sit
 * inside a caller's transaction (an inbound message's persist, a receipt's tick) that has not committed yet; a read
 * then sees the old state. So a key is read [READS] after it arrives — a short wait, then twice more — and an event
 * goes out only when the state moved. What a read finds is the committed truth: a row absent is deleted (or never
 * was), so `message.deleted` / `chat.removed` is always safe to send, and a key whose write rolled back changes
 * nothing.
 *
 * **Cursor.** [watermark] is the newest log position all of whose keys have had every read: a client that
 * reconnects with it is replayed everything that might still change, never less. An overflow of [maxPending] keys,
 * or a log that moved on without this feed (a new epoch, keys out of the ring), sends `gap` and starts over.
 */
internal class ChangeFeed(
    private val log: ChangeLog,
    private val hydrator: Hydrator,
    private val clock: () -> Long,
    private val maxPending: Int = MAX_PENDING,
) {
    private class Entry(
        val key: ChangeKey,
        var firstSeq: Long,
        var dueAt: Long,
        var reads: Int = 0,
        var sent: String? = null,
    )

    private val pending = LinkedHashMap<ChangeKey, Entry>()
    private var cursor: ChangeCursor? = null
    private val gaps = ArrayDeque<String>()
    private var restarts = 0

    val active: Boolean get() = cursor != null

    /** Starts the feed from [since] when the log still holds everything after it, else from now. */
    fun start(since: ChangeCursor?): SubscribeResult {
        pending.clear()
        gaps.clear()
        val replay = since?.let { log.after(it) }
        val from = if (replay != null) since else log.latest()
        cursor = from
        val before = restarts
        replay?.forEach { (seq, key) ->
            take(seq, key)
            if (restarts != before) return SubscribeResult(from, resync = true)
            cursor = from.copy(seq = seq)
        }
        // Presence is current state, never logged: every subscribe begins with it.
        touch(ChangeKey.Status)
        return SubscribeResult(from, resync = since != null && replay == null)
    }

    /** Marks [key] for a read without a log position: current state the log never holds, such as [ChangeKey.Status]. */
    fun touch(key: ChangeKey) {
        if (active) take(UNLOGGED, key)
    }

    fun stop() {
        cursor = null
        pending.clear()
        gaps.clear()
    }

    /** Takes what the log recorded since this feed last looked; a log that moved on without it is a gap. */
    fun pull() {
        val at = cursor ?: return
        val keys = log.after(at)
        if (keys == null) {
            restart("the change log moved on (app restarted or USB control switched off)")
            return
        }
        val before = restarts
        for ((seq, key) in keys) {
            take(seq, key)
            // A restart took the log as it stands, these keys included.
            if (restarts != before) return
            cursor = at.copy(seq = seq)
        }
    }

    /** When the next read falls due, or null with nothing to read or send. */
    fun nextDueAt(): Long? = if (gaps.isNotEmpty()) clock() else pending.values.minOfOrNull { it.dueAt }

    /** The newest position whose keys have all had every read: what a reconnect resumes from. */
    fun watermark(): ChangeCursor? {
        val at = cursor ?: return null
        val oldest = pending.values.minOfOrNull { it.firstSeq } ?: return at
        return at.copy(seq = minOf(at.seq, oldest - 1))
    }

    /**
     * Reads up to [max] due objects and returns the events to send, in order. Each carries the [watermark] from
     * before the batch, so a client that kept only part of it resumes before all of it: replayed, never skipped.
     */
    suspend fun runDue(max: Int = MAX_PER_RUN): List<JsonObject> {
        val seq = watermark()?.seq?.let { JsonPrimitive(it) }
        val events = ArrayList<JsonObject>()
        while (gaps.isNotEmpty()) events += event("gap", "reason" to JsonPrimitive(gaps.removeFirst()))
        val now = clock()
        val due =
            pending.values
                .filter { it.dueAt <= now }
                .sortedBy { it.dueAt }
                .take(max)
        // One read per kind for the whole batch: a chat read rebuilds the chat list, so never once per key.
        val messageIds = due.mapNotNull { (it.key as? ChangeKey.Message)?.id }
        val messages = if (messageIds.isEmpty()) emptyMap() else hydrator.messages(messageIds)
        val chatIds = due.mapNotNull { (it.key as? ChangeKey.Chat)?.conversation }
        val chats = if (chatIds.isEmpty()) emptyMap() else hydrator.chats(chatIds)
        for (entry in due) {
            if (pending[entry.key] !== entry) continue // a resync cleared it
            read(entry, messages, chats)?.let { events += it }
            entry.reads++
            val schedule = scheduleOf(entry.key)
            if (entry.reads >= schedule.size) {
                pending.remove(entry.key)
            } else {
                entry.dueAt = now + schedule[entry.reads] - schedule[entry.reads - 1]
            }
        }
        return if (seq == null) events else events.map { JsonObject(it + ("seq" to seq)) }
    }

    private fun take(
        seq: Long,
        key: ChangeKey,
    ) {
        if (key is ChangeKey.Resync) {
            restart(key.reason)
            return
        }
        val first = scheduleOf(key)[0]
        val now = clock()
        val entry = pending[key]
        if (entry != null) {
            // The full schedule again from this write, but never later than a read already due.
            entry.reads = 0
            entry.dueAt = minOf(entry.dueAt, now + first)
            entry.firstSeq = minOf(entry.firstSeq, seq)
            return
        }
        if (pending.size >= maxPending) {
            restart("more than $maxPending changes waiting")
            return
        }
        pending[key] = Entry(key, seq, now + first)
        // A message moves its thread's row (preview, time, unread) as well as itself.
        if (key is ChangeKey.Message && key.conversation != null) take(seq, ChangeKey.Chat(key.conversation))
        if (key is ChangeKey.Thread) take(seq, ChangeKey.Chat(key.conversation))
    }

    /** Drops what was waiting and tells the client to read everything again, from the log as it stands. */
    private fun restart(reason: String) {
        restarts++
        pending.clear()
        gaps.addLast(reason)
        cursor = log.latest()
    }

    private suspend fun read(
        entry: Entry,
        messages: Map<String, JsonObject>,
        chats: Map<String, JsonObject>,
    ): JsonObject? =
        when (val key = entry.key) {
            is ChangeKey.Message -> {
                val found = messages[key.id]
                // A key that didn't know its thread learns it from the row and moves that thread's row too.
                if (found != null && key.conversation == null) found.string("conv")?.let { take(entry.firstSeq, ChangeKey.Chat(it)) }
                if (found != null) {
                    changed(entry, found.toString())?.let { event("message", "message" to found) }
                } else {
                    changed(entry, ABSENT)?.let {
                        event("message.deleted", "id" to JsonPrimitive(key.id), "conv" to JsonPrimitive(key.conversation))
                    }
                }
            }

            is ChangeKey.Chat -> {
                val found = chats[key.conversation]
                if (found != null) {
                    changed(entry, found.toString())?.let { event("chat", "chat" to found) }
                } else {
                    changed(entry, ABSENT)?.let { event("chat.removed", "conv" to JsonPrimitive(key.conversation)) }
                }
            }

            is ChangeKey.Contact -> {
                val found = hydrator.contact(key.nodeId)
                if (entry.reads == 0) {
                    // Their name and face title their DM and every group they are in.
                    take(entry.firstSeq, ChangeKey.Chat(key.nodeId))
                    hydrator.groupsWith(key.nodeId).forEach { take(entry.firstSeq, ChangeKey.Chat(it)) }
                }
                changed(entry, found.toString())?.let { event("contact", "contact" to found) }
            }

            is ChangeKey.Thread -> {
                event("thread.reset", "conv" to JsonPrimitive(key.conversation))
            }

            ChangeKey.Contacts -> {
                event("contacts.reset")
            }

            ChangeKey.Self -> {
                val found = hydrator.identity()
                changed(entry, found.toString())?.let { event("self", "self" to found) }
            }

            ChangeKey.Status -> {
                val found = hydrator.status()
                changed(entry, found.toString())?.let { event("status", "status" to found) }
            }

            is ChangeKey.Resync -> {
                null
            }
        }

    /** [state] when it differs from what this entry last sent (and now records it), else null. */
    private fun changed(
        entry: Entry,
        state: String,
    ): Unit? {
        if (entry.sent == state) return null
        entry.sent = state
        return Unit
    }

    private fun event(
        name: String,
        vararg fields: Pair<String, JsonElement>,
    ): JsonObject = JsonObject(mapOf("event" to JsonPrimitive(name)) + fields)

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    companion object {
        /** When a key is read, in ms after it arrives: a wait for its transaction, then twice more for a slow one. */
        val READS = longArrayOf(100, 1_000, 10_000)

        /** Read once, at once: there is no write to wait for, or the event carries no state. */
        private val ONCE = longArrayOf(0)
        const val MAX_PENDING = 4_096
        const val MAX_PER_RUN = 32

        /** The log position of a [touch]ed key: past every real one, so it never holds the [watermark] back. */
        private const val UNLOGGED = Long.MAX_VALUE
        private const val ABSENT = "\u0000absent"

        private fun scheduleOf(key: ChangeKey): LongArray =
            when (key) {
                is ChangeKey.Message, is ChangeKey.Chat, is ChangeKey.Contact, ChangeKey.Self -> READS
                ChangeKey.Status, ChangeKey.Contacts, is ChangeKey.Thread, is ChangeKey.Resync -> ONCE
            }
    }
}
