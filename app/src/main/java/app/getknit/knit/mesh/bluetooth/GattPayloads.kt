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
 * Three Android additions, the first two pacing on this side alone:
 *
 * - **An address cap.** Each map holds at most [Configuration.maxAddresses] addresses, the least recently used going
 *   first. An iPhone rotates its resolvable private address about every fifteen minutes, and iOS never prunes.
 * - **A budget across addresses, and a ripe dial goes first** (#154, ADR 2026-09.kwq2's amendment of 2026-10-11).
 *   The per-address waits never bound the reads in all: one radio rotating random addresses under `0xFE30`, or a
 *   crowd of strangers, kept a read in flight nearly all the time, and every read pauses the scans and holds every
 *   dial back. So at most [Configuration.budgetReads] charged reads run in any [Configuration.budgetWindowMs] of the
 *   elapsed clock. Every read that ends is charged, whatever it found: the payload is unauthenticated, so a payload
 *   read proves nothing (a rotating forger serving a valid payload that names an id above every real one is never
 *   dialed). Only a link vouches for it: a HELLO on a link at the address naming the payload's node refunds that
 *   read's charge ([confirmed]), once. An honest iPhone that links costs nothing net; a charge nothing refunds ages
 *   out of the window. A cancelled read is free. And while the transport has a dial to make ([yieldTo]), no new read
 *   starts: the dial waits at most for the read already in flight, never for a second.
 * - **A payload without the seal flag is forgotten once after a plain dial drew no reply** ([forgetUnsealed], #286
 *   contract §7 revision 1, ADR 2026-11.8mhs): the peer may have upgraded since the read, and a §7 refusal reaches the
 *   dialer as no reply. Once per payload: a re-read naming the same node, PSM and flags is not forgotten again, so a peer
 *   that really does not seal costs one re-read, not one per dial. iOS checks its own forget rules for the same case.
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
        /** How many charged reads (every read that ended, less those a link confirmed) any window may hold. */
        val budgetReads: Int = BUDGET_READS,
        /** The window [budgetReads] is counted over, on the elapsed clock. */
        val budgetWindowMs: Long = BUDGET_WINDOW_MS,
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

    /** The read in progress: whose payload it reads, and when it must have ended on the elapsed clock. */
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

    // The payload each address last had forgotten by [forgetUnsealed], its digest cue zeroed (the cue moves with the
    // peer's store, not with its build): an identical re-read is the same payload, and is not forgotten again.
    private val unsealedForgotten = lru<BleAdvertPayload.Parsed>()

    // How long the scan has run in all: the clock [Configuration.quietMs] is counted on.
    private var scanClock = 0L
    private val retryAt = lru<Long>()

    // A read's charge: when it ended, the address it read, and the node its payload named (null for a failure or a
    // stranger, which nothing refunds).
    private class Charge(
        val at: Long,
        val address: String,
        val nodeId: String?,
    )

    // The charges still inside the budget's window. A link refunds one, its address's latest read naming its node: the
    // re-reads before it (a failed dial forgets the payload) stay charged until they age out.
    private val charged = ArrayDeque<Charge>()

    // Whether the transport has a dial to make: no new read starts until it has gone.
    private var dialWaiting = false

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

    /** How many charged reads the budget's window holds at [now], for the debug state line. */
    @Synchronized
    fun budgetUsed(now: Long): Int {
        prune(now)
        return charged.size
    }

    /** Whether the budget's window is full at [now], so no read starts until its oldest charge leaves it. */
    @Synchronized
    fun budgetSpent(now: Long): Boolean = budgetUsed(now) >= configuration.budgetReads

    /**
     * Whether the transport has a dial to make ([dial]): while it has, no new read starts, and a read already in flight
     * runs on. The connect loop says so on every pass, so the read's end cannot hand the radio to the next read first.
     */
    @Synchronized
    fun yieldTo(dial: Boolean) {
        dialWaiting = dial
    }

    /**
     * Starts reading [address], and answers true, when nothing else is being read, no dial is waiting, the budget has
     * room, it has no payload yet, and it is not waiting to be tried again.
     */
    @Synchronized
    fun begin(
        address: String,
        now: Long,
    ): Boolean {
        retryAt.entries.removeAll { it.value <= now }
        if (current != null || dialWaiting || budgetSpent(now)) return false
        if (payloads[address] != null || retryAt[address] != null) return false
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
        charged.addLast(Charge(now, address, (outcome as? Outcome.Read)?.payload?.nodeId))
        when (outcome) {
            is Outcome.Read -> payloads[address] = Held(outcome.payload, scanClock)
            Outcome.Failed -> retryAt[address] = now + configuration.retryMs
            Outcome.Stranger -> retryAt[address] = now + configuration.strangerRetryMs
        }
    }

    /**
     * A link's HELLO at [address] named [nodeId]: a read of the address whose payload named that node is vouched for,
     * and its charge is refunded. Once per charge (the charge goes), and a no-op once it has aged out of the window or
     * when the HELLO names another node. Answers whether a charge was refunded.
     */
    @Synchronized
    fun confirmed(
        address: String,
        nodeId: String,
        now: Long,
    ): Boolean {
        prune(now)
        val charge = charged.lastOrNull { it.address == address && it.nodeId == nodeId } ?: return false
        return charged.remove(charge)
    }

    /**
     * Ends the read in progress without learning anything, without a wait and without a charge: the radio went down
     * under it. A dial said to be waiting goes with it, since the connect loop says so again on its next pass.
     */
    @Synchronized
    fun cancel() {
        current = null
        dialWaiting = false
    }

    /**
     * Forgets what [address] served, and any wait before its next read: a dial to it failed, a HELLO named another
     * node, or it went quiet. A read of it in progress runs on, since it is fetching the payload afresh. Nothing is
     * charged here: the re-read a forget makes due is charged when it ends. A charge of the address's earlier read
     * stays, and ages out of the window if no link confirms it.
     */
    @Synchronized
    fun forget(address: String) {
        payloads.remove(address)
        retryAt.remove(address)
    }

    /**
     * Forgets [address]'s payload as [forget] does, when it lacks [BleAdvertPayload.FLAG_LINK_SEAL] and is not the one
     * this already forgot for it: a plain dial made from it drew no reply ([LinkSealPolicy.forgetsUnsealedPayload] decided
     * that). Answers whether a payload went. A payload that carries the flag is never forgotten here.
     */
    @Synchronized
    fun forgetUnsealed(address: String): Boolean {
        val held = payloads[address]?.payload ?: return false
        if (held.linkSeal) return false
        val same = held.copy(digestCue = 0)
        if (unsealedForgotten[address] == same) return false
        unsealedForgotten[address] = same
        forget(address)
        return true
    }

    // Drops the charges older than the budget's window. A filter, not a pop from the front: the elapsed time a read's
    // end is stamped with is read before the lock, so the times need not arrive in order.
    private fun prune(now: Long) {
        charged.removeAll { now - it.at >= configuration.budgetWindowMs }
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
        const val BUDGET_READS = 12
        const val BUDGET_WINDOW_MS = 600_000L

        private const val INITIAL_CAPACITY = 16
        private const val LOAD_FACTOR = 0.75f
    }
}
