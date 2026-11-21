package app.getknit.knit.mesh.sim

import app.getknit.knit.mesh.BulkRoute
import app.getknit.knit.mesh.TransportKind
import app.getknit.knit.mesh.lab.LinkAir
import app.getknit.knit.mesh.sim.SimSeeds.Purpose
import kotlin.math.exp
import kotlin.math.ln

/**
 * Bluetooth LE as [BleRadio] has it, with Wi-Fi Aware beside it, as a phone runs both behind one transport. A pair has
 * one lab link while either plane holds one, aired at the fastest plane up: Aware's data path while it is up, else
 * Bluetooth's. Its Bluetooth half draws exactly what [BleRadio] on the same seed draws, so a run on this radio and one on
 * `ble1m` meet over the same dials.
 *
 * Each approach within [nanRangeM] draws, from its own stream:
 * - **A discovery**, log-normal around [discoverMedianMs]: about 4 s with the screen off and 0.5 s with it on
 *   (ADR 2026-10.486r; ADR 2026-10.2t49 found 2.6 to 9 s). From then on a file between the two would go over Aware
 *   ([BulkRoute.Reachable]): a holder of a large photo waits for the data path (`CompositeMeshTransport.BULK_GRACE_MS`).
 * - **A data path**, up [NDP_MIN_MS] to [NDP_MAX_MS] after discovery (0.7 to 1.3 s from arm to up, ADR 2026-10.2t49),
 *   or never, with chance [failShare]: the 15 s handshake timeout fires with no path formed (unmeasured share). It is
 *   raised for the pair's sync as soon as it is found, holds while the two stay within [nanDropM], and ends after
 *   [windowMs] (`WifiAwareTransport.SYNC_MAX_WINDOW_MS`): one data path an approach.
 *
 * While it is up the link airs at [nanBytesPerSecond] (700 KB landed in 0.16 to 1.07 s, ADR 2026-10.2t49) on a radio of
 * its own, taking no share of either end's Bluetooth ([LinkAir.sharesNodeAir]).
 *
 * Not modelled: the one data path a phone dials at a time (one NDI), the serve cap, Aware's own range (never measured:
 * [nanRangeM] defaults to Bluetooth's), and a screen-on phone's faster discovery.
 */
class NanRadio(
    private val seed: Long,
    connectM: Double,
    dropM: Double,
    initial: Set<Link> = emptySet(),
    private val nanRangeM: Double = connectM,
    private val discoverMedianMs: Long = DISCOVER_MEDIAN_MS,
    private val failShare: Double = FAIL_SHARE,
    private val nanBytesPerSecond: Long = NAN_BYTES_PER_S,
    private val windowMs: Long = WINDOW_MS,
) : Radio {
    private val ble = BleRadio(seed, connectM, dropM, initial)

    /** A data path holds as much further than it is found as a Bluetooth link holds past its dial. */
    private val nanDropM = nanRangeM * dropM / connectM
    private val nanAir = LinkAir(nanBytesPerSecond, sharesNodeAir = false, plane = TransportKind.WifiAware)

    override val name = NAME
    override val air: LinkAir = ble.air
    override val nodeAirBytesPerSecond = ble.nodeAirBytesPerSecond
    override val maxLinks = ble.maxLinks

    // A pair within Aware's reach: when it was found and when its data path comes up (null: never), this approach.
    private class Approach(
        val discoveredAtMs: Long,
        val pathAtMs: Long?,
    )

    private val approaches = HashMap<Link, Approach>()
    private val approachCount = HashMap<Link, Int>()
    private var paths: Set<Link> = emptySet()
    private var routes: Map<Link, BulkRoute> = emptyMap()
    private var up: Set<Link> = initial

    @Volatile
    override var links: Set<Link> = initial
        private set

    override fun airOf(link: Link): LinkAir = if (link in paths) nanAir else ble.air

    override fun summaryValues(): List<Pair<String, Any?>> =
        ble.summaryValues() +
            listOf(
                "nan_range_m" to nanRangeM,
                "nan_drop_m" to nanDropM,
                "nan_discover_ms" to discoverMedianMs,
                "nan_fail_share" to failShare,
                "nan_bps" to nanBytesPerSecond,
                "nan_window_ms" to windowMs,
            )

    override fun step(
        atMs: Long,
        positions: List<Point>,
        present: BooleanArray?,
    ): LinkChanges {
        ble.step(atMs, positions, present)
        val nowPaths = HashSet<Link>()
        val nowRoutes = HashMap<Link, BulkRoute>()
        for (a in positions.indices) {
            for (b in a + 1 until positions.size) {
                val link = Link(a, b)
                val away = present != null && !(present[a] && present[b])
                // A walker out of the field is out of everyone's reach: its pairs' approaches end, as leaving Aware's hold does.
                val distance = if (away) Double.POSITIVE_INFINITY else positions[a].distanceTo(positions[b])
                val route = approach(atMs, link, distance)
                if (route == BulkRoute.Linked) nowPaths += link
                if (route != null) nowRoutes[link] = route
            }
        }
        val nowUp = ble.links + nowPaths
        val raised = (nowUp - up).sortedWith(LINK_ORDER)
        val dropped = (up - nowUp).sortedWith(LINK_ORDER)
        val rerated = (nowUp intersect up).filter { (it in nowPaths) != (it in paths) }.sortedWith(LINK_ORDER)
        val changedRoutes =
            (nowRoutes.keys + routes.keys)
                .filter { nowRoutes[it] != routes[it] }
                .sortedWith(LINK_ORDER)
                .associateWith { nowRoutes[it] ?: BulkRoute.None }
        up = nowUp
        paths = nowPaths
        routes = nowRoutes
        links = nowUp
        return LinkChanges(raised, dropped, rerated, changedRoutes)
    }

    // [link]'s approach at [distanceM] apart: how a file between the two would go now, or null while Aware has not found
    // them. A pair that leaves Aware's hold ends its approach; the next draws afresh.
    private fun approach(
        atMs: Long,
        link: Link,
        distanceM: Double,
    ): BulkRoute? {
        val known = approaches[link]
        if (distanceM > nanDropM || (known == null && distanceM > nanRangeM)) {
            approaches.remove(link)
            return null
        }
        val held = known ?: draw(atMs, link).also { approaches[link] = it }
        if (atMs < held.discoveredAtMs) return null
        val path = held.pathAtMs
        return if (path != null && atMs >= path && atMs < path + windowMs) BulkRoute.Linked else BulkRoute.Reachable
    }

    // This approach's discovery and data path: the same for a seed, a pair and its nth approach.
    private fun draw(
        atMs: Long,
        link: Link,
    ): Approach {
        val n = approachCount.merge(link, 1, Int::plus) ?: 1
        val stream = SimSeeds.stream(seed, Purpose.NAN_APPROACH, link.a.toLong(), link.b.toLong(), n.toLong())
        val gaussian = java.util.Random(stream.nextLong()).nextGaussian()
        val discoverMs =
            exp(ln(discoverMedianMs.toDouble()) + DISCOVER_SIGMA * gaussian)
                .toLong()
                .coerceIn(DISCOVER_MIN_MS, DISCOVER_MAX_MS)
        val setupMs = stream.nextLong(NDP_MIN_MS, NDP_MAX_MS + 1)
        val fails = stream.nextDouble() < failShare
        val discovered = atMs + discoverMs
        return Approach(discovered, if (fails) null else discovered + setupMs)
    }

    companion object {
        const val NAME = "ble1m+nan"

        /** Screen-off discovery and exchange pace without ICM, about 4 s (ADR 2026-10.486r). */
        const val DISCOVER_MEDIAN_MS = 4_000L
        const val DISCOVER_SIGMA = 0.6
        const val DISCOVER_MIN_MS = 300L
        const val DISCOVER_MAX_MS = 60_000L

        /** Arm to data path up: 0.7 to 1.3 s on a Pixel 7 in deep idle (ADR 2026-10.2t49). */
        const val NDP_MIN_MS = 700L
        const val NDP_MAX_MS = 1_300L

        /** The share of data paths that never form. Never measured: a guess, and a knob (`knit.meshSimNanFail`). */
        const val FAIL_SHARE = 0.1

        /** About 1 MB/s: inside the 0.65 to 4.4 MB/s that 700 KB in 0.16 to 1.07 s gives (ADR 2026-10.2t49). */
        const val NAN_BYTES_PER_S = 1_000_000L

        /** `WifiAwareTransport.SYNC_MAX_WINDOW_MS`: a data path is torn down after 30 s. */
        const val WINDOW_MS = 30_000L

        private val LINK_ORDER = compareBy<Link>({ it.a }, { it.b })
    }
}
