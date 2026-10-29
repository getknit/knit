package app.getknit.knit.mesh.lab

import app.getknit.knit.mesh.InboundFrame
import app.getknit.knit.mesh.MeshTransport
import app.getknit.knit.mesh.ReceivedDigest
import app.getknit.knit.mesh.ReceivedFile
import app.getknit.knit.mesh.lora.LoraMeshTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onSubscription
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * The collectors a plane beside the radio has now, one count per shared flow it hands the composite. The composite
 * subscribes each child on a `merge` coroutine of its own, so the radio's [LabTransport.collecting] says nothing of a
 * board's or a link plane's; and a shared flow with no replay drops what it is handed before its collector registers
 * (a restarted Bob lost a link file so under a chaos stall, 2026-10-29). [LabNode]'s boot waits on [collecting] for every
 * plane in its composite, which lab-lint's `plane-collectors-at-boot` checks.
 */
internal class LabCollectors {
    private val counts = CopyOnWriteArrayList<AtomicInteger>()

    /** Whether every shared flow [watch] wrapped has a collector registered now. */
    val collecting: Boolean
        get() = counts.all { it.get() > 0 }

    /**
     * [source], counted while a collector holds it: from its registration on the shared flow until its collection ends.
     * A cold flow drops nothing it was never handed, so it goes back as it came. Hand it the plane's own flow, before any
     * `map` or `onEach`: a shared flow mapped first is no longer a [SharedFlow], goes back uncounted, and still drops.
     */
    fun <T> watch(source: Flow<T>): Flow<T> {
        if (source !is SharedFlow<T>) return source
        val subs = AtomicInteger().also { counts += it }
        return flow {
            var counted = false
            try {
                emitAll(
                    source.onSubscription {
                        subs.incrementAndGet()
                        counted = true
                    },
                )
            } finally {
                if (counted) subs.decrementAndGet()
            }
        }
    }
}

/** A node's LoRa board as its composite sees it: the real [LoraMeshTransport], with its flows' collectors counted. */
internal class LabBoardPlane(
    board: LoraMeshTransport,
) : MeshTransport by board {
    private val collectors = LabCollectors()

    /** Whether the manager collects the board's flows yet ([LabCollectors]). */
    val collecting: Boolean
        get() = collectors.collecting

    override val inbound: Flow<InboundFrame> = collectors.watch(board.inbound)

    override val incomingFiles: Flow<ReceivedFile> = collectors.watch(board.incomingFiles)

    override val incomingDigests: Flow<ReceivedDigest> = collectors.watch(board.incomingDigests)
}
