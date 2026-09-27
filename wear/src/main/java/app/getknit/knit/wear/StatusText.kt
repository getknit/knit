package app.getknit.knit.wear

import app.getknit.knit.wearstatus.MeshState
import app.getknit.knit.wearstatus.Plane
import app.getknit.knit.wearstatus.WearStatus
import java.util.Locale

/** What one short-text complication shows: [text] (≤ 7 chars), an optional [title] under it, and [icon]. */
data class Face(
    val text: String,
    val title: String?,
    val icon: Glyph,
)

enum class Glyph { Mesh, MeshOff, Radios, Relayed }

/**
 * The complications' words, pure so they are unit-tested. A snapshot older than [STALE_MS] — or none at all —
 * is drawn as [NO_DATA] rather than as numbers that may no longer be true. [STALE_MS] is one missed refresh
 * (the 300 s complication period) plus slack: a phone that stopped answering — a Stop, Bluetooth off, out of
 * range — reads as "no data" within six minutes rather than showing its last "Linked" for a quarter hour.
 */
object StatusText {
    const val STALE_MS = 6 * 60_000L
    const val NO_DATA = "–"

    fun fresh(
        snapshot: Snapshot?,
        nowMs: Long,
    ): WearStatus? = snapshot?.takeIf { nowMs - it.fetchedAtMs in 0..STALE_MS }?.status

    fun nearby(s: WearStatus?): Face =
        when (s?.state) {
            null -> Face(NO_DATA, "near", Glyph.MeshOff)
            MeshState.Off, MeshState.Paused -> Face(NO_DATA, word(s.state).lowercase(Locale.ROOT), Glyph.MeshOff)
            else -> Face(s.nearby.toString(), "near", Glyph.Mesh)
        }

    fun health(s: WearStatus?): Face =
        when (s?.state) {
            null -> Face(NO_DATA, "mesh", Glyph.MeshOff)
            MeshState.NoRadio -> Face("Radios", "off", Glyph.MeshOff)
            MeshState.Off, MeshState.Paused -> Face(word(s.state), "mesh", Glyph.MeshOff)
            else -> Face(word(s.state), "mesh", Glyph.Mesh)
        }

    /**
     * One letter per plane that is up — B(luetooth), N(AN / Wi-Fi Aware), L(oRa), S(pool) — upper case when
     * live, lower case when degraded, absent when down or off. "B·N·L·S" is exactly the seven characters a
     * short text holds.
     */
    fun transports(s: WearStatus?): Face {
        if (s == null) return Face(NO_DATA, "radios", Glyph.Radios)
        val letters =
            listOf(s.ble to 'B', s.nan to 'N', s.lora to 'L', s.spool to 'S').mapNotNull { (plane, letter) ->
                when (plane) {
                    Plane.Live -> letter.toString()
                    Plane.Degraded -> letter.lowercase()
                    Plane.Down, Plane.Absent -> null
                }
            }
        val text =
            when {
                letters.isNotEmpty() -> letters.joinToString("·")
                s.state == MeshState.Off || s.state == MeshState.Paused -> word(s.state)
                else -> "none"
            }
        return Face(text, "radios", Glyph.Radios)
    }

    fun relayed(s: WearStatus?): Face = Face(s?.relayed?.let(::compact) ?: NO_DATA, "relayed", Glyph.Relayed)

    fun word(state: MeshState): String =
        when (state) {
            MeshState.Off -> "Off"
            MeshState.Paused -> "Paused"
            MeshState.Alone -> "Alone"
            MeshState.Linked -> "Linked"
            MeshState.Degraded -> "Weak"
            MeshState.NoRadio -> "No radio"
        }

    /** 999 → "999", 1234 → "1.2k", 12345 → "12k", 1234567 → "1.2M". */
    fun compact(n: Long): String =
        when {
            n < THOUSAND -> n.toString()
            n < TEN_THOUSAND -> String.format(Locale.ROOT, "%.1fk", n / THOUSAND.toDouble())
            n < MILLION -> "${n / THOUSAND}k"
            n < TEN_MILLION -> String.format(Locale.ROOT, "%.1fM", n / MILLION.toDouble())
            else -> "${n / MILLION}M"
        }

    private const val THOUSAND = 1_000L
    private const val TEN_THOUSAND = 10_000L
    private const val MILLION = 1_000_000L
    private const val TEN_MILLION = 10_000_000L
}
