package app.getknit.knit.usbcontrol

import app.getknit.knit.data.changes.DataChange
import app.getknit.knit.data.changes.DataChangeSink
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import java.util.concurrent.CopyOnWriteArrayList

/** What changed, as the change feed reads it back: one object of the protocol, by id. */
internal sealed interface ChangeKey {
    /** A message row or its reactions; [conversation] when the write knew it. */
    data class Message(
        val id: String,
        val conversation: String?,
    ) : ChangeKey

    /** What a thread's chat object shows. */
    data class Chat(
        val conversation: String,
    ) : ChangeKey

    /** A thread's rows deleted in bulk: the client reads the thread again. */
    data class Thread(
        val conversation: String,
    ) : ChangeKey

    data class Contact(
        val nodeId: String,
    ) : ChangeKey

    /** Peer rows changed in bulk: the client reads contacts and chats again. */
    data object Contacts : ChangeKey

    /** Our own name or photo. */
    data object Self : ChangeKey

    /** A blob's bytes or its filter verdict: every message naming [hash] may show it held, gone or flagged. */
    data class Attachment(
        val hash: String,
    ) : ChangeKey

    /** Transport health and who is reachable. Never logged: it is current state, read again on every subscribe. */
    data object Status : ChangeKey

    /**
     * Who the chat shows typing, in every thread. Never logged, like [Status]: a cue lives seconds, so a reconnect has
     * nothing to replay, only the state as it stands, sent on every subscribe and after every gap.
     */
    data object Typing : ChangeKey

    /** Something no key can say (the blocked set moved): the client reads everything again. */
    data class Resync(
        val reason: String,
    ) : ChangeKey

    companion object {
        fun of(change: DataChange): ChangeKey =
            when (change) {
                is DataChange.Message -> Message(change.id, change.conversationId)
                is DataChange.Conversation -> Chat(change.id)
                is DataChange.ThreadTrimmed -> Thread(change.id)
                is DataChange.Peer -> Contact(change.nodeId)
                DataChange.Peers -> Contacts
                is DataChange.Blob -> Attachment(change.hash)
            }
    }
}

/** Where a session's feed stands in the [ChangeLog]: every change up to [seq] of the log's [epoch] has been sent. */
internal data class ChangeCursor(
    val epoch: String,
    val seq: Long,
)

/**
 * The process's record of what changed, for every USB control session: the newest [capacity] keys, numbered. Kept
 * only while [recording] (USB control switched on), so with it off a repository write costs one volatile read. A
 * session that reconnects within the ring picks up from its cursor ([after]); one whose cursor is from another
 * [epoch] — this process before a restart, or before recording last stopped — or has left the ring starts over.
 *
 * Repositories report through [sink]; the settings watcher and the server [record] directly.
 */
internal class ChangeLog(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val newEpoch: () -> String,
) {
    private val lock = Any()
    private val ring = ArrayDeque<Pair<Long, ChangeKey>>()
    private val listeners = CopyOnWriteArrayList<Channel<Unit>>()
    private var seq = 0L

    var epoch: String = newEpoch()
        private set

    @Volatile var recording: Boolean = false
        private set

    val sink: DataChangeSink = DataChangeSink { change -> if (recording) record(ChangeKey.of(change)) }

    /** Starts or stops keeping keys. Starting begins a new [epoch] over an empty ring: what was missed while off is lost. */
    fun setRecording(on: Boolean) {
        synchronized(lock) {
            if (on == recording) return
            if (on) {
                epoch = newEpoch()
                ring.clear()
            }
            recording = on
        }
        wake()
    }

    fun record(key: ChangeKey) {
        if (!recording) return
        synchronized(lock) {
            ring.addLast(++seq to key)
            while (ring.size > capacity) ring.removeFirst()
        }
        wake()
    }

    fun latest(): ChangeCursor = synchronized(lock) { ChangeCursor(epoch, seq) }

    /**
     * The keys after [cursor], oldest first; null when they can't all be had — another epoch, a cursor past the
     * newest, or keys that have left the ring.
     */
    fun after(cursor: ChangeCursor): List<Pair<Long, ChangeKey>>? =
        synchronized(lock) {
            if (cursor.epoch != epoch || cursor.seq > seq) return null
            if (cursor.seq == seq) return emptyList()
            val oldest = ring.firstOrNull()?.first ?: return null
            if (cursor.seq < oldest - 1) return null
            ring.filter { it.first > cursor.seq }
        }

    /** A signal that fires (conflated) whenever a key is recorded or recording changes. */
    fun listen(): ReceiveChannel<Unit> = Channel<Unit>(Channel.CONFLATED).also { listeners += it }

    fun unlisten(channel: ReceiveChannel<Unit>) {
        listeners.removeIf { it === channel }
    }

    private fun wake() {
        listeners.forEach { it.trySend(Unit) }
    }

    companion object {
        const val DEFAULT_CAPACITY = 2_048
    }
}
