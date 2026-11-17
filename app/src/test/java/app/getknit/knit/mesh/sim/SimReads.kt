package app.getknit.knit.mesh.sim

import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.mesh.lab.LabNode
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import java.lang.management.ManagementFactory

/** The ordinary messages [holder] has in [conversation], by body; [parked] keeps the ones still waiting for a key. */
internal suspend fun rows(
    holder: LabNode,
    conversation: String,
    parked: Boolean = false,
): Map<String, MessageEntity> =
    holder.messages
        .observeNewestMessages(conversation, THREAD_WINDOW)
        .first()
        .filter { it.kind == MessageEntity.KIND_NORMAL && (parked || !it.pendingKey) }
        .associateBy { it.body }

/** The bodies of [from]'s DMs to [to] whose tick has not come back. */
internal suspend fun untickedBodies(
    from: LabNode,
    to: LabNode,
): Set<String> = from.missingAcks(from.dmWith(to), listOf(from, to)).keys

/** The frames every router among [nodes] dropped, by reason, summed over the field. */
internal fun drops(nodes: List<LabNode>): Map<String, Long> =
    nodes
        .flatMap {
            it.metrics
                .frames()
                .dropsByReason.entries
        }.groupBy({ it.key.name }, { it.value })
        .mapValues { it.value.sum() }
        .filterValues { it > 0 }

/** The heap in use once two collections have run: what the nodes hold, not what they have let go. */
internal suspend fun usedHeapMb(): Long {
    repeat(2) {
        System.gc()
        delay(GC_PAUSE_MS)
    }
    return Runtime.getRuntime().let { (it.totalMemory() - it.freeMemory()) / BYTES_PER_MB }
}

/** The CPU this process has used, every thread together. */
internal fun cpuNanos(): Long = (ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean).processCpuTime

private const val THREAD_WINDOW = 5_000
private const val BYTES_PER_MB = 1_048_576L
private const val GC_PAUSE_MS = 150L
