package app.getknit.knit.mesh.bluetooth

/**
 * Whose GATT payload the Bluetooth transport reads, when, and what each read found (companion change A3). A peer
 * whose advert carries only the `0xFE30` UUID — a foreground iPhone, which cannot advertise service data — serves its
 * [BleAdvertPayload] from [DoorbellPolicy.PAYLOAD_UUID] instead, and [BleGattPayloadReader] reads it. A line-for-line
 * port of the iOS port's `GattPayloads.swift` (knit-ios ADR 2026-09.xzpt), so both platforms read on the same rules:
 *
 * - **Once per address, while it is the same peer.** The value changes only with the peer's PSM and node, so what one
 *   read found is the payload of every advert after it until the transport [forget]s the address: a dial to it failed
 *   before its channel opened (a peer that restarted listens on a new PSM), a HELLO on a link to it named another node
 *   (a new node took the address), or it went [Configuration.quietMs] of scanning unheard with no link up ([scanned]:
 *   a node that left, whose address another may take up later). Before the last two rules (the contract's payload
 *   lifetime, revised after the first device gate) a new node at a reused address was sighted as the old one for good.
 * - **One at a time.** Every read is a connection the controller must make, and a second waits for the first.
 * - **Tried again later.** A read that fails waits [Configuration.retryMs]. A peer that answers but serves no payload
 *   waits [Configuration.strangerRetryMs]: `0xFE30` is not Knit's alone, and a stranger's device should not be
 *   connected to twice a minute.
 *
 * One Android addition: each map holds at most [Configuration.maxAddresses] addresses, the least recently used going
 * first. An iPhone rotates its resolvable private address about every fifteen minutes, and iOS never prunes.
 *
 * Pure and thread-safe (the scan callback runs on the main thread, a read ends on the transport's scope, a failed
 * dial on an IO thread), so the rules are a JVM test ([app.getknit.knit.GattPayloadsTest]).
 */
internal class GattPayloads(
    val configuration: Configuration = Configuration(),
) {
    /** The reads' tunables. */
    data class Configuration(
        /** How long one read may take, its connection included: the L2CAP connect watchdog's 12 s. */
        val readTimeoutMs: Long = READ_TIMEOUT_MS,
        /** How long a peer whose read failed waits before it is read again. */
        val retryMs: Long = RETRY_MS,
        /** How long a peer that serves no Knit payload waits before it is read again. */
        val strangerRetryMs: Long = STRANGER_RETRY_MS,
        /** How much scanning an address with a payload may go unheard, no link up, before the payload is forgotten. */
        val quietMs: Long = QUIET_MS,
        /** How many addresses each of the payloads and the waits keeps. */
        val maxAddresses: Int = MAX_ADDRESSES,
    )

    /** How a read ended. */
    sealed interface Outcome {
        /** The characteristic held a payload. */
        data class Read(
            val payload: BleAdvertPayload.Parsed,
        ) : Outcome

        /** No connection, no answer or an error, which a later read may not meet. */
        data object Failed : Outcome

        /** The peer answered, but serves no Knit payload. */
        data object Stranger : Outcome
    }

    /** The read in progress: whose payload it reads, and when it must have ended on the monotonic clock. */
    data class Read(
        val address: String,
        val deadline: Long,
    )

    /** The read in progress, if any. */
    @get:Synchronized
    var current: Read? = null
        private set

    // A payload and the scan-clock time its address was last heard (an advert, the read itself, or a link up).
    private class Held(
        val payload: BleAdvertPayload.Parsed,
        var heardAt: Long,
    )

    private val payloads = lru<Held>()

    // How long the scan has run in all: the clock [Configuration.quietMs] is counted on.
    private var scanClock = 0L
    private val retryAt = lru<Long>()

    /** The payload [address] served, if a read found one. */
    @Synchronized
    fun payload(of: String): BleAdvertPayload.Parsed? = payloads[of]?.payload

    /** An advert from [address] was heard: its payload, if a read found one, now counted as heard. */
    @Synchronized
    fun heard(address: String): BleAdvertPayload.Parsed? =
        payloads[address]?.let {
            it.heardAt = scanClock
            it.payload
        }

    /**
     * The scan ran [ms] more. Every address in [linked] counts as heard (a link up keeps its payload), and the payloads
     * of the others unheard for [Configuration.quietMs] of scanning are forgotten, their addresses answered for the log.
     */
    @Synchronized
    fun scanned(
        ms: Long,
        linked: Collection<String>,
    ): List<String> {
        scanClock += ms.coerceAtLeast(0L)
        linked.forEach { payloads[it]?.heardAt = scanClock }
        val quiet = payloads.entries.filter { scanClock - it.value.heardAt >= configuration.quietMs }.map { it.key }
        quiet.forEach(::forget)
        return quiet
    }

    /** How many addresses have a payload, for the debug state line. */
    @get:Synchronized
    val payloadCount: Int get() = payloads.size

    /**
     * Starts reading [address], and answers true, when nothing else is being read, it has no payload yet, and it is
     * not waiting to be tried again.
     */
    @Synchronized
    fun begin(
        address: String,
        now: Long,
    ): Boolean {
        retryAt.entries.removeAll { it.value <= now }
        if (current != null || payloads[address] != null || retryAt[address] != null) return false
        current = Read(address, now + configuration.readTimeoutMs)
        return true
    }

    /** The read of [address] ended. An outcome for any other address answers a read already given up on, and is ignored. */
    @Synchronized
    fun finish(
        address: String,
        outcome: Outcome,
        now: Long,
    ) {
        if (current?.address != address) return
        current = null
        when (outcome) {
            is Outcome.Read -> payloads[address] = Held(outcome.payload, scanClock)
            Outcome.Failed -> retryAt[address] = now + configuration.retryMs
            Outcome.Stranger -> retryAt[address] = now + configuration.strangerRetryMs
        }
    }

    /** Ends the read in progress without learning anything and without a wait: the radio went down under it. */
    @Synchronized
    fun cancel() {
        current = null
    }

    /**
     * Forgets what [address] served, and any wait before its next read: a dial to it failed, or the radio lost it. A
     * read of it in progress runs on, since it is fetching the payload afresh.
     */
    @Synchronized
    fun forget(address: String) {
        payloads.remove(address)
        retryAt.remove(address)
    }

    // Access-ordered, so a payload read on every advert stays and a rotated-away address ages out first.
    private fun <V> lru(): LinkedHashMap<String, V> =
        object : LinkedHashMap<String, V>(INITIAL_CAPACITY, LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, V>?): Boolean = size > configuration.maxAddresses
        }

    companion object {
        const val READ_TIMEOUT_MS = 12_000L
        const val RETRY_MS = 30_000L
        const val STRANGER_RETRY_MS = 600_000L
        const val QUIET_MS = 60_000L
        const val MAX_ADDRESSES = 64

        private const val INITIAL_CAPACITY = 16
        private const val LOAD_FACTOR = 0.75f
    }
}
