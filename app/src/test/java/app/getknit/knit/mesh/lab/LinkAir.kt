package app.getknit.knit.mesh.lab

import app.getknit.knit.mesh.TransportKind
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray

/**
 * A lab link's airtime: how fast its records cross, as a phone's link has a rate. A pipe given one
 * ([LabTransport.connect], [MeshLab.link]) queues what it is handed, as `FramedLink.send` does (a send never waits), and a
 * writer per direction airs the queue in order: a record waits for its bytes at [bytesPerSecond], and for its share of
 * both ends' radios ([NodeAir]), before the far end has it.
 *
 * - **Frames, custody digests and files are aired**, in one queue a direction as a link's one stream carries them: a file
 *   waits for its bytes like a frame, a frame queued behind it waits for the file, and the receiver's stack has the file
 *   whole once its last byte is in. A held or streamed pipe ([LabTransport.hold], an intake stream) keeps its own path.
 * - **Nothing is lost on the way.** An L2CAP link is reliable: a weak link is a slow one or a dropped one, and both are the
 *   radio's to say. A link cut with records still queued loses them, as a torn-down `FramedLink` does
 *   ([LabTransport.aired] counts them), and a relink re-serves what custody holds. A file cut mid-air is lost whole
 *   ([AirStats.filesCut]) and asked for again from the start: the partial-blob resume a phone's link keeps is not modelled,
 *   so a long file across short contacts fares worse here than on a phone.
 * - **Time is the monotonic clock** (`System.nanoTime`), never the lab's jumpable calendar: airtime schedules a delivery,
 *   it decides nothing, and a calendar jump must not stretch it.
 * - **A send no longer means the far end has it.** [LabTransport.awaitInboundDrained] waits out what is on the air toward a
 *   node, and [LabTransport.awaitAirDrained] what one link had queued; [MeshLab.unlink] and the restarts refuse an air
 *   link rather than guess whether it drains or is cut.
 */
data class LinkAir(
    val bytesPerSecond: Long,
    /** What a record costs beyond its bytes: `LinkFraming`'s `[type][len32]` header. A sealed door's tag is not charged. */
    val recordOverheadBytes: Int = RECORD_HEADER_BYTES,
    /**
     * Whether its records take a share of each end's radio ([NodeAir]): a Bluetooth link's do, all of a phone's links
     * served from one controller; a Wi-Fi Aware data path's ride a radio of their own and do not.
     */
    val sharesNodeAir: Boolean = true,
    /**
     * The plane a phone's link at this rate is ([LabTransport.carrier]): what custody paces a round by, Bluetooth's or Wi-Fi
     * Aware's (the custody-carry-policy contract's §2). Null, the default, reports the lab transport's own kind (`Other`),
     * which custody paces as Bluetooth, as every lab link did before.
     */
    val plane: TransportKind? = null,
) {
    init {
        require(bytesPerSecond > 0) { "a link airs at a positive rate: $bytesPerSecond" }
    }

    companion object {
        const val RECORD_HEADER_BYTES = 5
    }
}

/**
 * One node's radio, shared by every air link it has, both ways: a record charges its sender's and its receiver's for its
 * bytes at [bytesPerSecond], as a phone's controller serves every connection, in and out, from one set of connection
 * events. A node gets one from `LabLimits.nodeAirBytesPerSecond`; without one, only its links' own rates bound it.
 */
class NodeAir(
    val bytesPerSecond: Long,
) {
    init {
        require(bytesPerSecond > 0) { "a radio airs at a positive rate: $bytesPerSecond" }
    }

    // When this radio is next free, in `System.nanoTime` terms: under [AirLedger]'s lock.
    internal var nextFreeNanos = Long.MIN_VALUE
}

/** One direction of an air link's own schedule: when it is next free, and how much airtime it has reserved in all. */
internal class LinkClock {
    // Under [AirLedger]'s lock.
    var nextFreeNanos = Long.MIN_VALUE

    /** The airtime this direction has reserved so far, in nanoseconds: the ledger a test of a shared radio reads. */
    val reservedNanos = AtomicLong()
}

/**
 * Every air reservation in the JVM, under one lock: a few sums, never held while anything waits, so no writer holds it
 * while another sleeps. The link and each radio a record uses are each a queue of their own at their own rate: the record
 * takes its bytes from each one after what that one already holds, and is through once all of them have served it.
 *
 * A radio is never held idle for a neighbour's busy one. A phone's controller interleaves its connections' events far
 * finer than a record, and `nodeAirBytesPerSecond` is already what one radio serves across all of them. A slot booked
 * jointly instead, once the link and both radios are free together and only ever forward, drags every radio in a dense
 * group out to the busiest one's horizon: a 48-node gather with 6 links each aired at a third of its radios' rate.
 */
internal object AirLedger {
    private val lock = Any()

    /** Reserves [bytes] on [link] at [rate] and on both radios; returns when all of them will have aired them. */
    fun reserve(
        link: LinkClock,
        rate: Long,
        sender: NodeAir?,
        receiver: NodeAir?,
        bytes: Long,
        nowNanos: Long,
    ): Long =
        synchronized(lock) {
            val linkNanos = nanosFor(bytes, rate)
            link.nextFreeNanos = maxOf(nowNanos, link.nextFreeNanos) + linkNanos
            link.reservedNanos.addAndGet(linkNanos)
            var end = link.nextFreeNanos
            for (radio in listOfNotNull(sender, receiver)) {
                radio.nextFreeNanos = maxOf(nowNanos, radio.nextFreeNanos) + nanosFor(bytes, radio.bytesPerSecond)
                end = maxOf(end, radio.nextFreeNanos)
            }
            end
        }

    private fun nanosFor(
        bytes: Long,
        rate: Long,
    ): Long = bytes * NANOS_PER_S / rate

    private const val NANOS_PER_S = 1_000_000_000L
}

/**
 * What one node's air links did on the way out: what they aired, what a cut lost with them ([cut], [cutKeys]) and what
 * they refused because the link was already going down ([refused]), the deepest one link's queue ran from a moment on
 * ([peaksFromNow]) and how long each record waited from its send to its delivery ([waitPercentileMs]).
 */
class AirStats {
    val bytes = AtomicLong()
    val records = AtomicLong()
    val cut = AtomicLong()

    /** The files among [cut]: queued or airing when their link went down, so the receiver never had them. */
    val filesCut = AtomicLong()
    val refused = AtomicLong()

    // Every [peaksFromNow] asked so far, each noting from its own start.
    private val windows = java.util.concurrent.CopyOnWriteArrayList<Peaks>()

    /** The crossing keys of the frames a cut lost, in the order they were cut: what a relink must re-serve. */
    val cutKeys = java.util.concurrent.CopyOnWriteArrayList<String>()

    // Each record's wait, by bucket: bucket b holds waits under 2^b ms (bucket 0, under 1 ms).
    private val waits = AtomicLongArray(WAIT_BUCKETS)

    internal fun recordWait(ms: Long) {
        waits.incrementAndGet(bucketOf(ms))
    }

    /**
     * The deepest queue and backlog one of these links reaches from now on: what one part of a run reads as its own. A
     * queue is noted as a record joins it, so one already deep when this is asked counts once its next record is queued.
     */
    fun peaksFromNow(): Peaks = Peaks().also { windows += it }

    // One link's queue as a record joined it: [depth] records, [backlogMs] of its bytes at the link's own rate.
    internal fun noteQueue(
        depth: Long,
        backlogMs: Long,
    ) {
        for (window in windows) {
            window.queue.accumulateAndGet(depth, ::maxOf)
            window.backlogMs.accumulateAndGet(backlogMs, ::maxOf)
        }
    }

    /** The deepest queue in records ([queue]) and in airtime ([backlogMs]) one link reached since [peaksFromNow]. */
    class Peaks {
        val queue = AtomicLong()
        val backlogMs = AtomicLong()
    }

    /** Every record's wait so far, by bucket ([waitPercentileMs] reads them; [merge] sums several nodes'). */
    fun waitBuckets(): LongArray = LongArray(WAIT_BUCKETS) { waits.get(it) }

    companion object {
        const val WAIT_BUCKETS = 32

        internal fun bucketOf(ms: Long): Int =
            (64 - java.lang.Long.numberOfLeadingZeros(ms.coerceAtLeast(0))).coerceAtMost(WAIT_BUCKETS - 1)

        /** Several nodes' [waitBuckets], summed. */
        fun merge(all: List<LongArray>): LongArray = LongArray(WAIT_BUCKETS) { b -> all.sumOf { it[b] } }

        /**
         * The [p]th percentile of the waits in [buckets], as the upper bound of the bucket it falls in (a power of two, in
         * ms), or null with no wait recorded.
         */
        fun waitPercentileMs(
            buckets: LongArray,
            p: Int,
        ): Long? {
            val total = buckets.sum()
            if (total == 0L) return null
            val rank = (p * total + PERCENT - 1) / PERCENT
            var seen = 0L
            for ((b, n) in buckets.withIndex()) {
                seen += n
                if (seen >= rank) return 1L shl b
            }
            return 1L shl (WAIT_BUCKETS - 1)
        }

        private const val PERCENT = 100
    }
}
