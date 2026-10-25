package app.getknit.knit.ui.devview

import app.getknit.knit.mesh.MeshMetrics
import app.getknit.knit.mesh.TransportHealth
import app.getknit.knit.mesh.TransportKind
import app.getknit.knit.mesh.TransportStatus
import app.getknit.knit.ui.devview.InspectFormat.ABSENT

/** Whether the mesh is up, and if not, why not, read the way the bridge's `STATE` reads it. */
enum class MeshPosture { Running, Stopped, Paused, StartDeferred }

/** The frame counters the HUD follows: delivered here, sent (originated, relayed, handed on) and dropped. */
data class FrameCounts(
    val delivered: Long,
    val sent: Long,
    val dropped: Long,
) {
    companion object {
        fun of(frames: MeshMetrics.Snapshot.Frames): FrameCounts =
            FrameCounts(
                delivered = frames.framesDelivered,
                sent = frames.framesOriginated + frames.framesRelayed + frames.framesHandedOn,
                dropped = frames.framesDropped,
            )
    }
}

/** How far [FrameCounts] moved over [spanMs], the window actually covered. */
data class HudRates(
    val moved: FrameCounts,
    val spanMs: Long,
)

/** One HUD tick, read off the mesh by [MeshHudViewModel]. Pure data, so the line is a function of it. */
data class HudReading(
    val posture: MeshPosture,
    val statuses: List<TransportStatus> = emptyList(),
    val neighbors: Int = 0,
    val indirect: Int = 0,
    val spoolsConnected: Int = 0,
    val spools: Int = 0,
    // Live custody rows, read every few ticks; null until the first read.
    val custody: Int? = null,
    // Null until the window holds two samples.
    val rates: HudRates? = null,
)

/**
 * The last minute's movement of the frame counters, from samples taken on the HUD's tick. One per collection: a
 * resubscribed HUD starts a new window rather than reading a gap as a minute.
 */
class RateWindow(
    private val windowMs: Long = WINDOW_MS,
) {
    private val samples = ArrayDeque<Pair<Long, FrameCounts>>()

    /** Adds the sample [counts] taken at [at] and answers the movement since the window's oldest sample. */
    fun add(
        at: Long,
        counts: FrameCounts,
    ): HudRates? {
        // A counter that went backwards is a new MeshMetrics, so the old samples measure nothing.
        val last = samples.lastOrNull()?.second
        if (last != null && counts.wentBackFrom(last)) samples.clear()
        samples.addLast(at to counts)
        // Drop the oldest sample while the next one already spans the window, so the span is the whole window once it can be.
        while (samples.size > 1 && at - samples[1].first >= windowMs) samples.removeFirst()
        val (firstAt, first) = samples.first()
        if (firstAt >= at) return null
        return HudRates(
            FrameCounts(counts.delivered - first.delivered, counts.sent - first.sent, counts.dropped - first.dropped),
            spanMs = at - firstAt,
        )
    }

    private fun FrameCounts.wentBackFrom(earlier: FrameCounts): Boolean =
        delivered < earlier.delivered || sent < earlier.sent || dropped < earlier.dropped

    companion object {
        const val WINDOW_MS = 60_000L
    }
}

/**
 * The HUD's line, its bubble's number and its panel (ADR 2026-10.s7wg), in log notation: deliberately English, built here and never
 * handed to `Text` as a literal. Pinned by `HudLineTest`.
 */
object HudLine {
    /** What the pill reads before the first tick. */
    const val WAITING = "mesh …"

    private const val SECOND_MS = 1_000L

    /** For example `BLE 2/5 · NAN dormant · LoRa 1/1 · relay 1/2 · cust 41 · 12↓ 3↑ 0✕ /min`. */
    fun line(reading: HudReading): String =
        buildList {
            when (reading.posture) {
                MeshPosture.Running -> {
                    if (reading.statuses.isEmpty()) add("no radios")
                    reading.statuses.forEach { add("${short(it.kind)} ${state(it)}") }
                }

                MeshPosture.Stopped -> {
                    add("mesh stopped")
                }

                MeshPosture.Paused -> {
                    add("mesh paused")
                }

                MeshPosture.StartDeferred -> {
                    add("mesh start deferred")
                }
            }
            if (reading.spools > 0) add("relay ${reading.spoolsConnected}/${reading.spools}")
            add("cust ${reading.custody ?: "…"}")
            reading.rates?.let { add("${it.moved.delivered}↓ ${it.moved.sent}↑ ${it.moved.dropped}✕ ${per(it)}") }
        }.joinToString(" · ")

    /** The bubble's number: the neighbours a short-range radio holds, or the absent mark while the mesh is not running. */
    fun badge(reading: HudReading?): String = reading?.takeIf { it.posture == MeshPosture.Running }?.neighbors?.toString() ?: ABSENT

    /** The panel a tap opens: every field of each plane's status, then the mesh as a whole. */
    fun panel(reading: HudReading): List<InspectGroup> =
        listOf(
            InspectGroup(
                "planes",
                reading.statuses.map { InspectRow(short(it.kind), detail(it)) }.ifEmpty { listOf(InspectRow("planes", "none")) },
            ),
            InspectGroup(
                "mesh",
                listOf(
                    InspectRow("posture", reading.posture.name),
                    InspectRow("neighbors", reading.neighbors.toString()),
                    InspectRow("heardIndirectly", reading.indirect.toString()),
                    InspectRow("spools", if (reading.spools == 0) "none" else "${reading.spoolsConnected} of ${reading.spools} connected"),
                    InspectRow("forward_store live", reading.custody?.toString() ?: ABSENT),
                    InspectRow(
                        "frames",
                        reading.rates?.let {
                            "${it.moved.delivered} delivered · ${it.moved.sent} sent · ${it.moved.dropped} dropped ${per(it)}"
                        } ?: ABSENT,
                    ),
                ),
            ),
        )

    private fun short(kind: TransportKind): String =
        when (kind) {
            TransportKind.Bluetooth -> "BLE"
            TransportKind.WifiAware -> "NAN"
            TransportKind.LoRa -> "LoRa"
            TransportKind.Other -> "other"
        }

    /** A plane's word on the line: why it carries nothing when it does not, else linked/nearby (`!` when degraded). */
    private fun state(status: TransportStatus): String =
        when {
            status.switchedOff -> "off"
            status.dormant -> "dormant"
            status.health == TransportHealth.Unavailable -> "down"
            status.health == TransportHealth.ForegroundOnly -> "fg-only"
            status.health == TransportHealth.Degraded -> "${status.linked}/${status.nearby}!"
            else -> "${status.linked}/${status.nearby}"
        }

    private fun detail(status: TransportStatus): String =
        buildList {
            add(status.health.name)
            add("linked ${status.linked}")
            add("nearby ${status.nearby}")
            if (status.contended) add("contended")
            if (status.initiatorHeld) add("initiator held")
            if (status.switchedOff) add("switched off")
            if (status.dormant) add("dormant")
        }.joinToString(" · ")

    /** The span the counts moved over: `/min` once the window is whole, its seconds before that. */
    private fun per(rates: HudRates): String = if (rates.spanMs >= RateWindow.WINDOW_MS) "/min" else "/${rates.spanMs / SECOND_MS}s"
}
