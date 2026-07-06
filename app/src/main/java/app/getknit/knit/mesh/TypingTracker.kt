package app.getknit.knit.mesh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Receiver-side state for the best-effort "now typing" indicator: which senders are currently typing in which
 * conversation, exposed as a [StateFlow] the chat UI observes. Purely in-memory and self-expiring — a typing
 * [app.getknit.knit.mesh.protocol.FrameType.TYPING] cue is fire-and-forget and never custodied, so nothing here
 * is persisted or survives a restart (a live typer simply re-cues within [ttlMs]).
 *
 * Two events drive it: [onTyping] when a typing frame arrives (show + arm a [ttlMs] auto-clear, since a sender
 * that stops typing sends no "stopped" frame), and [onMessageFrom] when a real message from that sender lands
 * (clear immediately — the message replaces the indicator). Each entry's auto-clear is a lone delayed coroutine
 * guarded by a monotonic token so a stale removal (superseded by a refresh, or pre-empted by an arriving
 * message) no-ops instead of clobbering fresh state — no per-entry [kotlinx.coroutines.Job] bookkeeping or
 * cancellation races. Pure (only a [CoroutineScope] + a TTL injected, no Android deps) ⇒ unit-tested on the JVM
 * with virtual time (see `TypingTrackerTest`), like [AckSync]/[KeyExchange].
 */
class TypingTracker(
    private val scope: CoroutineScope,
    private val ttlMs: Long = TYPING_TTL_MS,
    private val cap: Int = TYPING_CAP,
) {
    private data class Key(
        val conversationId: String,
        val senderId: String,
    )

    // conversationId -> the set of senderIds currently shown as typing there.
    private val _typing = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    val typing: StateFlow<Map<String, Set<String>>> = _typing.asStateFlow()

    // Latest schedule token per tracked key: the newest onTyping wins, so a delayed-removal whose token no
    // longer matches (a refresh bumped it, or onMessageFrom removed it) is stale and does nothing. Also the
    // set of currently-tracked keys, for the [cap].
    private val tokens = ConcurrentHashMap<Key, Long>()
    private val seq = AtomicLong(0)

    /**
     * A typing frame from [senderId] arrived for [conversationId]: show the indicator and (re)arm its [ttlMs]
     * auto-clear. Idempotent while the peer keeps typing — each frame just pushes the clear time out.
     */
    fun onTyping(
        conversationId: String,
        senderId: String,
    ) {
        val key = Key(conversationId, senderId)
        // Defensive bound on distinct typers tracked at once. Presence is ephemeral and comes only from direct
        // neighbors, so this is never approached in practice; refreshing an existing key is always allowed.
        if (!tokens.containsKey(key) && tokens.size >= cap) return
        val token = seq.incrementAndGet()
        tokens[key] = token
        addTyper(conversationId, senderId)
        scope.launch {
            delay(ttlMs)
            if (tokens[key] == token) { // still the latest schedule → nobody refreshed or cleared it
                tokens.remove(key, token)
                removeTyper(conversationId, senderId)
            }
        }
    }

    /**
     * A real message from [senderId] landed in [conversationId]: clear its indicator now and invalidate any
     * pending auto-clear (its token is dropped, so the delayed removal no-ops).
     */
    fun onMessageFrom(
        conversationId: String,
        senderId: String,
    ) {
        tokens.remove(Key(conversationId, senderId))
        removeTyper(conversationId, senderId)
    }

    /**
     * Demo-screenshot only: show [senderId] as typing in [conversationId] permanently. Unlike [onTyping] it
     * arms no [ttlMs] auto-clear (and no inbound message ever arrives in a demo build to fire [onMessageFrom]),
     * so the indicator survives however long a statically-captured screenshot takes to launch and render.
     */
    fun seedPersistent(
        conversationId: String,
        senderId: String,
    ) = addTyper(conversationId, senderId)

    private fun addTyper(
        conversationId: String,
        senderId: String,
    ) = _typing.update { cur -> cur + (conversationId to (cur[conversationId].orEmpty() + senderId)) }

    private fun removeTyper(
        conversationId: String,
        senderId: String,
    ) = _typing.update { cur ->
        val remaining = cur[conversationId].orEmpty() - senderId
        if (remaining.isEmpty()) cur - conversationId else cur + (conversationId to remaining)
    }

    companion object {
        /**
         * How long a receiver keeps showing the indicator after the last typing frame. Deliberately larger than
         * the sender's ~8 s cue cadence so a peer who keeps typing never blinks off between frames.
         */
        const val TYPING_TTL_MS = 12_000L

        /** Defensive cap on distinct (conversation, sender) pairs tracked at once (TTL-bounded regardless). */
        const val TYPING_CAP = 64
    }
}
