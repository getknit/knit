package app.getknit.knit.mesh.bluetooth

/**
 * The key a blinded advertised form is held under in every per-peer map while it resolves to no id (ADR 2026-11.xp6d's
 * reader, obligation 8): `~zzzz…<n>`, a handle numbered per tag on this phone, in a namespace no node id can take (a
 * node id is 26 characters of `[a-z2-7]`, and `~` is none of them). So a clear advert carrying an unresolved stranger's
 * tag bytes is its own id and never lands on that tag's entry, and an unresolved form never answers an id lookup
 * (`judge`'s presence read included).
 *
 * The handle is also the redaction (obligation 22): it shows a tag's first four characters (always `zzzz`) and a local
 * number, never the tag, so every log line, collection `toString()` and debug read-back that prints a per-peer key is
 * safe without a helper at each site. The number means nothing off this phone and is never paired with an id: a form
 * that resolves moves its entries to the id ([BluetoothMeshTransport]'s re-key) and the handle is not printed again.
 * The full tag stays in [BlePresenceTracker.Snapshot.advertised], whose `toString()` redacts it too.
 *
 * Bounded, least recently used out first, and a handle goes only together with its entries ([onEvict]: the transport
 * forgets them and counts it), so a form is never held under two handles; its next sighting starts afresh under a new
 * one. Thread-safe (the scan callback, the worker and the connect loop all ask).
 */
internal class FormKeys(
    cap: Int = DEFAULT_CAP,
    // Called with a handle the cap lets go, under this table's monitor: the owner forgets its entries with it.
    onEvict: (String) -> Unit = {},
) {
    private val byTag =
        object : LinkedHashMap<String, String>(INITIAL, LOAD, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
                val evict = size > cap
                if (evict && eldest != null) onEvict(eldest.value)
                return evict
            }
        }
    private var next = 0L

    /** The handle [tag] (a blinded form's 26-char string) is held under: the same one while it is remembered. */
    @Synchronized
    fun keyFor(tag: String): String = byTag.getOrPut(tag) { "$MARK${shown(tag)}${next++}" }

    /** The handle [tag] holds now, or null when it has none (never heard, or forgotten). */
    @Synchronized
    fun existing(tag: String): String? = byTag[tag]

    /** Forgets every handle: the radio went down, and every entry under one with it. Numbering carries on. */
    @Synchronized
    fun clear() = byTag.clear()

    companion object {
        /** What starts every handle, and no node id. */
        const val MARK = '~'
        const val DEFAULT_CAP = 512
        private const val SHOWN = 4
        private const val INITIAL = 16
        private const val LOAD = 0.75f

        /** Whether a per-peer [key] is an unresolved form's handle rather than a node id. */
        fun isForm(key: String): Boolean = key.startsWith(MARK)

        /** A form as a log line may show it: its first four characters, then `…`. */
        fun shown(form: String): String = form.take(SHOWN) + "…"
    }
}

/**
 * The hop from the scan callback to the resolver's confined worker (ADR 2026-11.xp6d's reader, obligation 5): the
 * latest sighting per form, at most [cap] forms waiting. A form already waiting is replaced by its newer sighting
 * ([conflated]); a new form past the cap is dropped ([dropped]). [offer] says whether the worker must be started
 * ([Offer.START]: no drain is pending). Thread-safe; the clear path never touches it.
 */
internal class BlindQueue<T : Any>(
    private val cap: Int = DEFAULT_CAP,
) {
    private val waiting = LinkedHashMap<String, T>()
    private var draining = false

    var conflated = 0L
        private set

    var dropped = 0L
        private set

    /** How an [offer] went. */
    enum class Offer {
        /** Queued, and no drain is pending: the caller starts one. */
        START,

        /** Queued behind a drain already pending. */
        QUEUED,

        /** Replaced the form's older sighting still waiting. */
        CONFLATED,

        /** A new form past the cap: dropped. */
        DROPPED,
    }

    /** Queues [item] under [form]. */
    @Synchronized
    fun offer(
        form: String,
        item: T,
    ): Offer {
        val outcome =
            when {
                waiting.containsKey(form) -> Offer.CONFLATED.also { conflated++ }
                waiting.size >= cap -> return Offer.DROPPED.also { dropped++ }
                draining -> Offer.QUEUED
                else -> Offer.START
            }
        waiting[form] = item
        draining = true
        return outcome
    }

    /** Takes everything waiting; an empty answer ends the drain, so the next [offer] starts another. */
    @Synchronized
    fun take(): List<T> {
        if (waiting.isEmpty()) {
            draining = false
            return emptyList()
        }
        return waiting.values.toList().also { waiting.clear() }
    }

    /** Drops everything waiting (the radio went down); a drain in progress ends at its next [take]. */
    @Synchronized
    fun clear() = waiting.clear()

    companion object {
        /** A cold rebuild can hold the worker for seconds while adverts arrive at hundreds a second. */
        const val DEFAULT_CAP = 256
    }
}

/**
 * Each peer's last link event (a link up or down) on the elapsed clock, so a sighting resolved after a link event it
 * preceded cannot undo it (AdvertAliases' ordering contract, obligation 5): a sighting captured at `at` before the
 * peer's last link event is [late], and feeds presence and its RSSI only. Entries older than [horizonMs] go: no
 * sighting waits on the worker that long. Thread-safe.
 */
internal class LinkEventStamps(
    private val horizonMs: Long = DEFAULT_HORIZON_MS,
) {
    private val last = HashMap<String, Long>()

    @Synchronized
    fun onLinkEvent(
        nodeId: String,
        at: Long,
    ) {
        last.entries.removeAll { at - it.value > horizonMs }
        last[nodeId] = maxOf(last[nodeId] ?: Long.MIN_VALUE, at)
    }

    /** Whether a sighting of [nodeId] captured at [at] came before the peer's last link event. */
    @Synchronized
    fun late(
        nodeId: String,
        at: Long,
    ): Boolean = (last[nodeId] ?: return false) > at

    @Synchronized
    fun clear() = last.clear()

    companion object {
        const val DEFAULT_HORIZON_MS = 60_000L
    }
}
