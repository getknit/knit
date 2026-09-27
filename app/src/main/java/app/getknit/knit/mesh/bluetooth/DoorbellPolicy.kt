package app.getknit.knit.mesh.bluetooth

import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.Protocol
import java.util.UUID

/**
 * When the Bluetooth transport rings a peer's GATT doorbell (ADR 2026-09.dqvb). iOS does not resume a suspended app
 * for data arriving on an open L2CAP channel, and does for a write to its own GATT server, so a frame this phone
 * writes to an iPhone's link waits for the iPhone's next foreground unless the doorbell rings. [BleDoorbell] does the
 * GATT; this decides which links, which frames and when. Pure, so the rules are a JVM test
 * ([app.getknit.knit.DoorbellPolicyTest]).
 *
 * The schedule is the iOS port's own (`LinkManager+Doorbell.swift` in knit-ios, ADR 2026-09.khjj there), so both
 * platforms wake an iPhone equally often: a ring at most every [RING_INTERVAL_MS] while frames go out, and one more
 * after the last of a burst.
 */
internal object DoorbellPolicy {
    /**
     * The primary GATT service a node that cannot advertise service data serves: `0xFE30`, the presence advert's
     * service UUID ([BleConstants.SERVICE_UUID]), as a full UUID. Android serves no GATT; it only looks in here.
     */
    val SERVICE_UUID: UUID = UUID.fromString("0000fe30-0000-1000-8000-00805f9b34fb")

    /**
     * The doorbell: a characteristic in [SERVICE_UUID], written without a response, whose value means nothing.
     * Minted by the iOS port on 2026-09-27 and cross-platform law from the first Android build that rings it —
     * never change it.
     */
    val DOORBELL_UUID: UUID = UUID.fromString("f34c056b-5830-4243-a888-01f92f49e446")

    /** The least time between two rings of one link. A ring keeps an iPhone running about 9 s (iOS 27). */
    const val RING_INTERVAL_MS = 5_000L

    /** How long after a failed lookup the next may start, and how many may fail on one link before it stops asking. */
    const val LOOKUP_RETRY_MS = 60_000L
    const val MAX_FAILED_LOOKUPS = 3

    /**
     * Whether the peer on a link asked to be rung: its HELLO carries [Protocol.CAP_DOORBELL]. Android never sets
     * the bit, so a link between two Android phones never opens a GATT client.
     */
    fun serves(helloCapabilities: Long): Boolean = helloCapabilities and Protocol.CAP_DOORBELL != 0L

    /**
     * Whether writing a frame of [type] rings. Everything does except the link's upkeep, which waits for the next
     * wake as the digest does: the typing cue (worthless to a locked phone) and the point-to-point requests the
     * 60 s tick re-sends for as long as a blob or a key is missing (`BlobExchange`, `KeyExchange`), which would
     * otherwise wake the iPhone every minute. A frame whose type could not be read rings.
     */
    fun rings(type: String?): Boolean = type == null || type !in UPKEEP

    private val UPKEEP = setOf(FrameType.TYPING, FrameType.BLOB_REQ, FrameType.KEY_REQ)

    /** One link's ring times. Not thread-safe: a [BleDoorbell]'s loop is its only caller. */
    class Schedule(
        private val intervalMs: Long = RING_INTERVAL_MS,
    ) {
        /** When the link last rang, null before its first ring. */
        var rangAt: Long? = null
            private set

        /** When the ring owed for frames written since [rangAt] falls due, null when none is owed. */
        var dueAt: Long? = null
            private set

        /**
         * A frame went out. True: ring now. False: a ring went out less than [intervalMs] ago, and one more is owed
         * at [dueAt] — so a burst costs one ring at its start and one after it.
         */
        fun afterFrame(now: Long): Boolean {
            val last = rangAt
            if (last != null && now - last < intervalMs) {
                dueAt = last + intervalMs
                return false
            }
            return ring(now)
        }

        /** True when the owed ring's time has come; it counts as rung. */
        fun onDue(now: Long): Boolean {
            val due = dueAt ?: return false
            return now >= due && ring(now)
        }

        private fun ring(now: Long): Boolean {
            rangAt = now
            dueAt = null
            return true
        }
    }

    /**
     * How often one link may look its doorbell up again after a lookup failed (a timeout, the GATT client
     * dropped, or none free to register): once every [LOOKUP_RETRY_MS], and not after [MAX_FAILED_LOOKUPS]
     * failures. A lookup that found the doorbell, or found none, costs nothing here. Not thread-safe, like [Schedule].
     */
    class Lookups(
        private val retryMs: Long = LOOKUP_RETRY_MS,
        private val maxFailures: Int = MAX_FAILED_LOOKUPS,
    ) {
        private var failures = 0
        private var lastFailedAt: Long? = null

        fun mayTry(now: Long): Boolean {
            val last = lastFailedAt
            return failures < maxFailures && (last == null || now - last >= retryMs)
        }

        fun failed(now: Long) {
            failures += 1
            lastFailedAt = now
        }
    }
}
