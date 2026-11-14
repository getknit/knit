package app.getknit.knit.usbcontrol

import kotlinx.serialization.json.JsonObject

/**
 * What the USB control remembers about sends across sessions: the reply each recent `clientMsgId` got, so a host that
 * lost its link mid-send can ask again and not send twice, and a token bucket over sends and reactions, so a host
 * cannot spend the custody quota and the radios as fast as it can write (a burst of [burst], then [perMinute]).
 * Status changes have a bucket of their own, far tighter ([profileBurst], then [profilesPerMinute]): each one publishes
 * the profile to the mesh and a sealed copy to every contact, 1 + N frames for one token, a cost the Profile screen
 * keeps rare by being a person's edit. Typing cues have their own as well ([typingBurst], then [typingPerMinute]), beside
 * the composer's own cadence of one cue per thread every [typingIntervalMs]: a cue is a radio frame to every neighbour
 * (or the DM's peer) and a relay signal, and a loop over every thread must not turn that into a stream. Process-wide,
 * like the link it outlives.
 */
internal class SendLedger(
    private val clock: () -> Long,
    private val capacity: Int = DEFAULT_CAPACITY,
    burst: Int = DEFAULT_BURST,
    perMinute: Int = DEFAULT_PER_MINUTE,
    profileBurst: Int = DEFAULT_PROFILE_BURST,
    profilesPerMinute: Int = DEFAULT_PROFILES_PER_MINUTE,
    typingBurst: Int = DEFAULT_TYPING_BURST,
    typingPerMinute: Int = DEFAULT_TYPING_PER_MINUTE,
    private val typingIntervalMs: Long = TYPING_INTERVAL_MS,
) {
    private val lock = Any()
    private val replies = LinkedHashMap<String, JsonObject>(capacity, LOAD_FACTOR, true)
    private val sends = Bucket(burst, perMinute)
    private val profiles = Bucket(profileBurst, profilesPerMinute)
    private val cues = Bucket(typingBurst, typingPerMinute)

    /** When each thread's last cue went, oldest first: only those inside [typingIntervalMs] are kept. */
    private val typed = LinkedHashMap<String, Long>()

    /** The reply [clientMsgId] already got, or null for one this phone has not sent (or has forgotten). */
    fun replay(clientMsgId: String): JsonObject? = synchronized(lock) { replies[clientMsgId] }

    fun record(
        clientMsgId: String,
        reply: JsonObject,
    ) {
        synchronized(lock) {
            replies[clientMsgId] = reply
            while (replies.size > capacity) replies.remove(replies.keys.first())
        }
    }

    /** Takes one send token; null when it had one, else how many ms until it will. */
    fun take(): Long? = synchronized(lock) { sends.take(clock()) }

    /** Takes one status-change token, from the profile's own bucket; null when it had one, else ms until it will. */
    fun takeProfile(): Long? = synchronized(lock) { profiles.take(clock()) }

    /**
     * Whether a typing cue may go into [conversation] now: not when one went there within [typingIntervalMs] (the
     * cue still shows on the far side, so nothing is lost), nor when the cue bucket is empty. A cue taken here is
     * recorded; one refused spends nothing. A clock that stepped back reads as the interval elapsed.
     */
    fun takeTyping(conversation: String): Boolean =
        synchronized(lock) {
            val now = clock()
            val iterator = typed.values.iterator()
            while (iterator.hasNext()) {
                if (now - iterator.next() in 0 until typingIntervalMs) break
                iterator.remove()
            }
            if (conversation in typed || cues.take(now) != null) return false
            typed[conversation] = now
            true
        }

    /** A burst of [burst], refilled at [perMinute]; the ledger's lock guards it. */
    private inner class Bucket(
        private val burst: Int,
        perMinute: Int,
    ) {
        private val rate = perMinute / MINUTE_MS
        private var tokens = burst.toDouble()
        private var filledAt = clock()

        fun take(now: Long): Long? {
            // A wall clock that stepped back refills nothing, and takes nothing away.
            tokens = minOf(burst.toDouble(), tokens + (now - filledAt).coerceAtLeast(0) * rate)
            filledAt = now
            return if (tokens >= 1) {
                tokens -= 1
                null
            } else {
                ((1 - tokens) / rate).toLong().coerceAtLeast(1)
            }
        }
    }

    companion object {
        const val DEFAULT_CAPACITY = 256
        const val DEFAULT_BURST = 10
        const val DEFAULT_PER_MINUTE = 30
        const val DEFAULT_PROFILE_BURST = 2
        const val DEFAULT_PROFILES_PER_MINUTE = 1

        /** Room for a person typing in two or three windows at the composer's cadence, and no more. */
        const val DEFAULT_TYPING_BURST = 4
        const val DEFAULT_TYPING_PER_MINUTE = 20

        /** The composer's own cadence (`ChatViewModel.TYPING_SEND_INTERVAL_MS`): one cue per thread this often. */
        const val TYPING_INTERVAL_MS = 8_000L
        private const val MINUTE_MS = 60_000.0
        private const val LOAD_FACTOR = 0.75f
    }
}
