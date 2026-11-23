package app.getknit.knit.mesh.bluetooth

import app.getknit.knit.mesh.crypto.BlindTag

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
 *   A **blinded** payload ([BleAdvertPayload.Parsed.blinded], a blinding iPhone's, ADR 2026-11.89gg) names a tag, not a
 *   node: the HELLO rule compares it with the HELLO's `t` and the tag that node announced, never with an id
 *   ([helloNamed]), and a fourth rule forgets it past its slot's edge ([forgetPastEdge]): a blinding writer rotates its
 *   tag there, and in the foreground, where its address is never quiet, the old tag would otherwise be kept until iOS
 *   rotates the address. Knit-ios ADR 2026-11.prsp's rule 4, line for line: 35 to 65 s past the first edge after the
 *   read (an announced rotation's 30 s lead plus 5 s for a writer's clock, then a per-read jitter), so each address
 *   costs at most one read per slot and no reader re-reads the old tag in the 30 s it may still air.
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
 *   read's charge ([confirmed]), once; a blinded read's charge only a **proven** link whose HELLO `t` or dialed tag is
 *   the charged tag (ADR 2026-11.89gg), never by comparing a tag with an id. An honest iPhone that links costs nothing
 *   net; a charge nothing refunds ages out of the window. A cancelled read is free. And while the transport has a dial
 *   to make ([yieldTo]), no new read starts: the dial waits at most for the read already in flight, never for a second.
 * - **A payload without the seal flag is forgotten once after a plain dial drew no reply** ([forgetUnsealed], #286
 *   contract §7 revision 1, ADR 2026-11.8mhs): the peer may have upgraded since the read, and a §7 refusal reaches the
 *   dialer as no reply. Once per payload: a re-read naming the same node, PSM and flags is not forgotten again, so a peer
 *   that really does not seal costs one re-read, not one per dial. iOS checks its own forget rules for the same case.
 *   Never a blinded payload: a tag dial's silence says nothing of a seal flag the contract's §7 pins to a clear read.
 *
 * Pure and thread-safe (the scan callback runs on the main thread, a read ends on the transport's scope, a failed
 * dial on an IO thread), so the rules are a JVM test ([app.getknit.knit.GattPayloadsTest]).
 */
internal class GattPayloads(
    val configuration: Configuration = Configuration(),
    // The wall clock a blinded payload's slot edge is read on (rule 4), and the seed of its per-read jitter: the transport
    // draws one at random when it is made, so the readers of one writer spread their re-reads (knit-ios ADR 2026-11.prsp).
    private val wallMs: () -> Long = System::currentTimeMillis,
    jitterSeed: Long = 0L,
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
        /** How long past its slot's edge a blinded payload is kept at least (rule 4). */
        val blindedEdgeSlackMs: Long = BLINDED_EDGE_SLACK_MS,
        /** The spread of a blinded payload's forget past that, drawn per read (rule 4). */
        val blindedEdgeJitterMs: Long = BLINDED_EDGE_JITTER_MS,
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

    // A payload and the scan-clock time its address was last heard (an advert, the read itself, or a link up). A blinded
    // one also carries its span on the wall clock (rule 4): when it was read, and when it is forgotten. Forgetting the
    // payload takes the span with it.
    private class Held(
        val payload: BleAdvertPayload.Parsed,
        var heardAt: Long,
        val readAtWall: Long = 0L,
        val untilWall: Long = Long.MAX_VALUE,
    ) {
        fun pastEdge(wall: Long): Boolean = payload.blinded && wall !in readAtWall..<untilWall

        /** The slot a blinded payload was read in, null for a clear one: what `GattTargets.withheld` reads. */
        val readSlot: Long? get() = if (payload.blinded) BlindTag.slotOf(readAtWall) else null
    }

    /** What an advert at a read address found: the payload to sight it by, or that a blinded one passed its edge. */
    data class Heard(
        val payload: BleAdvertPayload.Parsed?,
        /** A blinded payload was forgotten past its slot's edge (rule 4): the advert is due a read instead. */
        val pastEdge: Boolean = false,
        /** The slot [payload] was read in, when it is blinded ([readSlot]). */
        val readSlot: Long? = null,
    )

    private val payloads = lru<Held>()

    // The payload each address last had forgotten by [forgetUnsealed], its digest cue zeroed (the cue moves with the
    // peer's store, not with its build): an identical re-read is the same payload, and is not forgotten again.
    private val unsealedForgotten = lru<BleAdvertPayload.Parsed>()

    // How long the scan has run in all: the clock [Configuration.quietMs] is counted on.
    private var scanClock = 0L
    private val retryAt = lru<Long>()

    // A read's charge: when it ended, the address it read, and the form its payload named (null for a failure or a
    // stranger, which nothing refunds): a node id, or a tag when [blinded]. An id can share bytes with a tag (the tag
    // prefix is not an id's to avoid), so the two are never compared ([confirmed]).
    private class Charge(
        val at: Long,
        val address: String,
        val named: String?,
        val blinded: Boolean,
    )

    /** Which of [confirmed]'s two rules refunded a read. */
    enum class Refund {
        /** A clear payload's charge: the HELLO named its node. */
        ID,

        /** A blinded payload's charge: a proven link told or was dialed by its tag (ADR 2026-11.89gg). */
        TAG,
    }

    // The charges still inside the budget's window. A link refunds one, its address's latest read naming its node: the
    // re-reads before it (a failed dial forgets the payload) stay charged until they age out.
    private val charged = ArrayDeque<Charge>()

    // Whether the transport has a dial to make: no new read starts until it has gone.
    private var dialWaiting = false

    // Rule 4's jitter: SplitMix64 from the seed, one draw per blinded read, as the iOS port's.
    private var jitterState = jitterSeed

    /** The payload [address] served, if a read found one. */
    @Synchronized
    fun payload(of: String): BleAdvertPayload.Parsed? = payloads[of]?.payload

    /**
     * The wall-clock slot [of]'s blinded payload was read in, null for a clear payload or none: a dial target built from
     * it is stale in any other slot while its id holds a proven link (`GattTargets.withheld`).
     */
    @Synchronized
    fun readSlot(of: String): Long? = payloads[of]?.readSlot

    /**
     * An advert from [address] was heard: its payload, if a read found one, now counted as heard. A blinded payload past
     * its slot's edge (rule 4) is forgotten here instead, unless [address] is in [spared] (a link, a dial or the read
     * holds it, as [forgetPastEdge] spares one), so a payload past its deadline is never served and the advert is read.
     */
    @Synchronized
    fun heard(
        address: String,
        spared: Collection<String> = emptySet(),
    ): Heard {
        val held = payloads[address] ?: return Heard(null)
        if (held.pastEdge(wallMs()) && address !in spared && address != current?.address) {
            forget(address)
            return Heard(null, pastEdge = true)
        }
        held.heardAt = scanClock
        return Heard(held.payload, readSlot = held.readSlot)
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

    /**
     * The read of [address] ended, at [now] on the elapsed clock. An outcome for any other address answers a read already
     * given up on, and is ignored. A blinded payload's span is stamped on the wall clock (rule 4): read now, forgotten
     * [Configuration.blindedEdgeSlackMs] plus a jitter past the first slot edge after now.
     */
    @Synchronized
    fun finish(
        address: String,
        outcome: Outcome,
        now: Long,
    ) {
        if (current?.address != address) return
        current = null
        val read = (outcome as? Outcome.Read)?.payload
        charged.addLast(Charge(now, address, read?.nodeId, blinded = read?.blinded == true))
        when (outcome) {
            is Outcome.Read -> payloads[address] = held(outcome.payload)
            Outcome.Failed -> retryAt[address] = now + configuration.retryMs
            Outcome.Stranger -> retryAt[address] = now + configuration.strangerRetryMs
        }
    }

    private fun held(payload: BleAdvertPayload.Parsed): Held {
        if (!payload.blinded) return Held(payload, scanClock)
        val wall = wallMs()
        val edge = (BlindTag.slotOf(wall) + 1) * BlindTag.SLOT_MS
        return Held(payload, scanClock, readAtWall = wall, untilWall = edge + configuration.blindedEdgeSlackMs + jitter())
    }

    /**
     * A link at [address] registered, its HELLO naming [nodeId]: a read of the address is vouched for, and its charge is
     * refunded, once (the charge goes); a no-op once it has aged out of the window. Two rules, never mixed:
     *  - a clear payload's charge, when it named [nodeId] ([Refund.ID]);
     *  - a blinded payload's, only on a [proven] link and when [tag] is the charged tag ([Refund.TAG]): [tag] is the
     *    caller's explicit evidence, the tag our dial went to or the proven HELLO's `t`, never a form read back from
     *    presence. A tag is never compared with an id, nor an id with a tag.
     * Answers which rule refunded a charge, or null.
     */
    @Synchronized
    fun confirmed(
        address: String,
        nodeId: String,
        tag: String? = null,
        proven: Boolean = false,
        now: Long,
    ): Refund? {
        prune(now)
        val charge =
            charged.lastOrNull {
                it.address == address && if (it.blinded) proven && tag != null && it.named == tag else it.named == nodeId
            } ?: return null
        charged.remove(charge)
        return if (charge.blinded) Refund.TAG else Refund.ID
    }

    /**
     * Rule 2: the HELLO on a link to [address] named [nodeId] and told [told] (its `t`), and [announced] is the tag that
     * node announced to us (`TAG_NEXT`, [AdvertAliases.announced]) and we hold. A clear payload there naming another node is
     * forgotten; a blinded one whose tag is neither [told] nor [announced] too: the node rotated since the read, or airs
     * its id now (knit-ios `GattPayloads.helloNamed`, ADR 2026-11.prsp). A blinded payload's tag is never compared with
     * [nodeId]. Answers whether a payload went.
     */
    @Synchronized
    fun helloNamed(
        nodeId: String,
        told: String?,
        announced: String?,
        address: String,
    ): Boolean {
        val held = payloads[address]?.payload ?: return false
        val names = if (held.blinded) held.nodeId == told || held.nodeId == announced else held.nodeId == nodeId
        if (names) return false
        forget(address)
        return true
    }

    /**
     * Rule 4: forgets every blinded payload whose slot edge, with its slack and jitter, has passed at [wall], or that was
     * read at a later wall time than [wall] (the clock went back), and answers those addresses, sorted. An address in
     * [spared] keeps its payload, as does the one being read: a link holds it (its own claims attribute the tag it names,
     * and no address a link holds is read, `DialPlan.mayReadGatt`, so dropping it would end its GATT sightings for the
     * link's life), or a dial is in flight to it. The forget then comes at the first check after it ends.
     */
    @Synchronized
    fun forgetPastEdge(
        spared: Collection<String>,
        wall: Long = wallMs(),
    ): List<String> {
        val reading = current?.address
        val past = payloads.entries.filter { (address, held) -> held.pastEdge(wall) && address !in spared && address != reading }
        val forgotten = past.map { it.key }.sorted()
        forgotten.forEach(::forget)
        return forgotten
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
        // A blinded payload is a tag dial's: §7's no-reply forget is the clear contract's alone (ADR 2026-11.89gg).
        if (held.linkSeal || held.blinded) return false
        val same = held.copy(digestCue = 0)
        if (unsealedForgotten[address] == same) return false
        unsealedForgotten[address] = same
        forget(address)
        return true
    }

    // A draw in [0, blindedEdgeJitterMs) (SplitMix64: Steele, Lea and Flood, 2014), as the iOS port draws it.
    private fun jitter(): Long {
        val span = configuration.blindedEdgeJitterMs.coerceAtLeast(1L)
        jitterState += SPLITMIX_GAMMA
        var mixed = jitterState
        mixed = (mixed xor (mixed ushr SPLITMIX_SHIFT_1)) * SPLITMIX_MUL_1
        mixed = (mixed xor (mixed ushr SPLITMIX_SHIFT_2)) * SPLITMIX_MUL_2
        mixed = mixed xor (mixed ushr SPLITMIX_SHIFT_3)
        return java.lang.Long.remainderUnsigned(mixed, span)
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

        /** How far behind a writer's clock may run and still have rotated before rule 4 reads it again. */
        const val WRITER_CLOCK_SLACK_MS = 5_000L

        /**
         * Rule 4's slack: an announced rotation lands at the later of the edge and its announcement plus
         * [AdvertRotation.MIN_LEAD_MS], so a forget any sooner would read the old tag again and keep it a whole slot; and
         * [WRITER_CLOCK_SLACK_MS] more.
         */
        const val BLINDED_EDGE_SLACK_MS = AdvertRotation.MIN_LEAD_MS + WRITER_CLOCK_SLACK_MS
        const val BLINDED_EDGE_JITTER_MS = 30_000L

        // SplitMix64's increment, mixing multipliers and shifts (the iOS port's `GattPayloads.jitter`).
        private const val SPLITMIX_GAMMA = -0x61c8864680b583ebL // 0x9E3779B97F4A7C15
        private const val SPLITMIX_MUL_1 = -0x40a7b892e31b1a47L // 0xBF58476D1CE4E5B9
        private const val SPLITMIX_MUL_2 = -0x6b2fb644ecceee15L // 0x94D049BB133111EB
        private const val SPLITMIX_SHIFT_1 = 30
        private const val SPLITMIX_SHIFT_2 = 27
        private const val SPLITMIX_SHIFT_3 = 31

        private const val INITIAL_CAPACITY = 16
        private const val LOAD_FACTOR = 0.75f
    }
}
