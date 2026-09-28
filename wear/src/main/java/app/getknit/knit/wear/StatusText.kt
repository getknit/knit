package app.getknit.knit.wear

import app.getknit.knit.wearstatus.MeshState
import app.getknit.knit.wearstatus.Plane
import app.getknit.knit.wearstatus.WearLink
import app.getknit.knit.wearstatus.WearStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** What one short-text complication shows: [text] (≤ 7 chars), an optional [title] under it, and [icon]. */
data class Face(
    val text: String,
    val title: String?,
    val icon: Glyph,
    val tone: Tone = Tone.Muted,
)

/** What one long-text complication (or a tile's line) shows: a sentence and an optional title above it. */
data class Line(
    val text: String,
    val title: String?,
    val icon: Glyph,
    val tone: Tone = Tone.Muted,
)

/** One glyph per mesh state, plus the metric glyphs. Drawables live in `res/drawable/ic_*.xml`. */
enum class Glyph { Linked, Alone, Weak, Paused, Off, NoRadio, NoPhone, Radios, Relayed, Carrying, Day, Bluetooth, Aware, LoRa, Relay }

/**
 * The semantic colour of a state or a plane, shared by every surface so a watch face, the tile and the app
 * agree: [Good] is a live link, [Calm] a healthy mesh with nobody near, [Warn] a degraded radio, [Bad] a radio
 * that is down, [Muted] off, paused or unknown. [argb] is the fixed dark-theme colour complications are
 * tinted with; the app and the tile map [Calm] and [Muted] onto their own (possibly dynamic) scheme.
 */
@Suppress("MagicNumber") // ARGB literals, from :app's dark theme (PositiveDark, CoralPrimaryDark, ErrorDark)
enum class Tone(
    val argb: Int,
) {
    Good(0xFF72DAA0.toInt()),
    Calm(0xFFFFB5A0.toInt()),
    Warn(0xFFF4C361.toInt()),
    Bad(0xFFFFB4AB.toInt()),
    Muted(0xFF8A8584.toInt()),
}

/** A plane with its display name, in the order every surface lists them. */
data class PlaneRow(
    val name: String,
    val letter: Char,
    val plane: Plane,
)

/**
 * A reading as the surfaces draw it: the phone's [status], and — once it is past [StatusText.STALE_MS] — the
 * time it was taken ([agedSinceMs]), so a surface can show its age and mute its colour instead of passing it off
 * as live.
 */
data class Shown(
    val status: WearStatus,
    val agedSinceMs: Long? = null,
)

/**
 * The words and colours every Wear surface draws, pure so they are unit-tested. A reading within [STALE_MS] is
 * drawn as it stands. Past it, it is drawn with its age rather than as [NO_DATA]: Wear OS does not ask a data
 * source while the watch dozes, so an old reading usually means nobody asked, not that the phone went away.
 * "Phone out of reach" is kept for a read that found no phone (ADR 2026-09.wetm, fourth amendment).
 */
object StatusText {
    /** How long a reading counts as live: one 300 s complication period plus slack. */
    const val STALE_MS = 6 * 60_000L
    const val NO_DATA = "–"

    /** The ranged-value gauge's full scale: eight peers in reach is a busy room; the text still says 23. */
    const val NEARBY_SCALE = 8

    /**
     * What the surfaces draw at [nowMs]: the reading, aged once it is past [STALE_MS] — or null ("Phone out of
     * reach") with no reading, or when a read after it found no phone ([failedAtMs], 0 for none) and it is past
     * [STALE_MS]. A failure inside the live window leaves the reading live until the window closes.
     */
    fun shown(
        snapshot: Snapshot?,
        failedAtMs: Long,
        nowMs: Long,
    ): Shown? {
        snapshot ?: return null
        if (nowMs - snapshot.fetchedAtMs in 0..STALE_MS) return Shown(snapshot.status)
        if (failedAtMs > snapshot.fetchedAtMs) return null
        return Shown(snapshot.status, agedSinceMs = snapshot.fetchedAtMs)
    }

    /** What [shown] will be once [snapshot]'s live window has closed — the entry a timeline switches to. */
    fun afterLive(
        snapshot: Snapshot,
        failedAtMs: Long,
    ): Shown? = shown(snapshot, failedAtMs, snapshot.fetchedAtMs + STALE_MS + 1)

    fun tone(state: MeshState?): Tone =
        when (state) {
            MeshState.Linked -> Tone.Good
            MeshState.Alone -> Tone.Calm
            MeshState.Degraded -> Tone.Warn
            MeshState.NoRadio -> Tone.Bad
            MeshState.Off, MeshState.Paused, null -> Tone.Muted
        }

    fun glyph(state: MeshState?): Glyph =
        when (state) {
            MeshState.Linked -> Glyph.Linked
            MeshState.Alone -> Glyph.Alone
            MeshState.Degraded -> Glyph.Weak
            MeshState.Paused -> Glyph.Paused
            MeshState.Off -> Glyph.Off
            MeshState.NoRadio -> Glyph.NoRadio
            null -> Glyph.NoPhone
        }

    /** True while the mesh is not running, so there is no radio state or peer count to show. */
    fun resting(s: WearStatus?): Boolean = s == null || s.state == MeshState.Off || s.state == MeshState.Paused

    fun planes(s: WearStatus): List<PlaneRow> =
        listOf(
            PlaneRow("Bluetooth", 'B', s.ble),
            PlaneRow("Wi-Fi Aware", 'N', s.nan),
            PlaneRow("LoRa", 'L', s.lora),
            PlaneRow("Internet relay", 'S', s.spool),
        )

    // --- short text (≤ 7 characters) -------------------------------------------------------------------------

    fun nearby(s: WearStatus?): Face =
        when {
            s == null -> Face(NO_DATA, "nearby", Glyph.NoPhone)
            resting(s) -> Face(NO_DATA, word(s.state).lowercase(Locale.ROOT), glyph(s.state))
            else -> Face(s.nearby.toString(), "nearby", glyph(s.state), tone(s.state))
        }

    fun health(s: WearStatus?): Face =
        when (s?.state) {
            null -> Face(NO_DATA, "mesh", Glyph.NoPhone)
            MeshState.NoRadio -> Face("Radios", "off", Glyph.NoRadio, Tone.Bad)
            else -> Face(word(s.state), "mesh", glyph(s.state), tone(s.state))
        }

    /** What the phone holds for other people — known even while the mesh rests; unknown from an older phone. */
    fun carrying(s: WearStatus?): Face =
        s?.extra?.let { Face(Counts.compact(it.carrying.toLong()), "held", Glyph.Carrying, Tone.Calm) }
            ?: Face(NO_DATA, "held", Glyph.Carrying)

    /** The day's rise in the relayed counter, from the watch's own history. */
    fun relayedToday(today: Today?): Face =
        today?.relayed?.let { Face("+" + Counts.compact(it), "today", Glyph.Relayed, Tone.Calm) }
            ?: Face(NO_DATA, "today", Glyph.Relayed)

    /** How long the mesh was linked today; "–" before the watch has any reading today. */
    fun day(today: Today?): Face {
        val share = today?.share
        if (share == null || share.totalMs == 0L) return Face(NO_DATA, "linked", Glyph.Day)
        return Face(Counts.durationShort(share.linkedMs), "linked", Glyph.Day, if (share.linkedMs > 0) Tone.Good else Tone.Calm)
    }

    fun word(state: MeshState): String =
        when (state) {
            MeshState.Off -> "Off"
            MeshState.Paused -> "Paused"
            MeshState.Alone -> "Alone"
            MeshState.Linked -> "Linked"
            MeshState.Degraded -> "Weak"
            MeshState.NoRadio -> "No radio"
        }
}

/**
 * The long-text sentences — a long complication, the tile's descriptions, TalkBack — and the radio problems,
 * pure like [StatusText]. Radios are only ever named when one the phone has is weak or down: a healthy radio
 * is the normal case, and one the user never set up is not a problem.
 */
object StatusLines {
    fun nearbyLine(s: WearStatus?): Line =
        when {
            s == null -> Line("Phone out of reach", "Knit", Glyph.NoPhone)
            StatusText.resting(s) -> Line("Mesh ${StatusText.word(s.state).lowercase(Locale.ROOT)}", "Knit", StatusText.glyph(s.state))
            s.state == MeshState.NoRadio -> Line("Radios off", "Knit", Glyph.NoRadio, Tone.Bad)
            s.nearby == 0 -> Line("No one nearby", "Knit", StatusText.glyph(s.state), StatusText.tone(s.state))
            else -> Line(peers(s.nearby) + " nearby", "Knit", StatusText.glyph(s.state), StatusText.tone(s.state))
        }

    fun healthLine(s: WearStatus?): Line =
        when (s?.state) {
            null -> Line("Phone out of reach", "Knit mesh", Glyph.NoPhone)
            MeshState.Linked -> Line("Linked · ${s.nearby} nearby", "Knit mesh", Glyph.Linked, Tone.Good)
            MeshState.Alone -> Line("Alone · listening", "Knit mesh", Glyph.Alone, Tone.Calm)
            MeshState.Degraded -> Line("Weak · ${problemShort(s) ?: "check radios"}", "Knit mesh", Glyph.Weak, Tone.Warn)
            MeshState.NoRadio -> Line("Radios off", "Knit mesh", Glyph.NoRadio, Tone.Bad)
            MeshState.Paused -> Line("Paused", "Knit mesh", Glyph.Paused)
            MeshState.Off -> Line("Off", "Knit mesh", Glyph.Off)
        }

    fun carryingLine(s: WearStatus?): Line {
        val n = s?.extra?.carrying ?: return Line("Phone out of reach", "Carrying", Glyph.Carrying)
        val text = if (n == 0) "Nothing held for others" else "${Counts.grouped(n.toLong())} held for others"
        return Line(text, "Carrying", Glyph.Carrying, Tone.Calm)
    }

    fun relayedLine(
        s: WearStatus?,
        today: Today?,
    ): Line {
        val title = s?.let { "${Counts.grouped(it.relayed)} in all" } ?: "Relayed"
        val text = today?.relayed?.let { "${Counts.grouped(it)} relayed today" } ?: "No readings yet today"
        return Line(text, title, Glyph.Relayed, Tone.Calm)
    }

    fun dayLine(today: Today?): Line {
        val share = today?.share
        if (share == null || share.totalMs == 0L) return Line("No readings yet today", "Your day", Glyph.Day)
        val text = if (share.linkedMs == 0L) "Not linked yet today" else "Linked ${Counts.duration(share.linkedMs)} today"
        return Line(text, "Your day", Glyph.Day, if (share.linkedMs > 0) Tone.Good else Tone.Calm)
    }

    /** "Linked for 2 h 10 min" while linked; "Last peer 14 min ago" after; null with no peer today. */
    fun streak(
        today: Today?,
        nowMs: Long,
    ): String? {
        today?.linkedSinceMs?.let { return "Linked for ${Counts.duration(nowMs - it)}" }
        today?.lastPeerAtMs?.let { return "Last peer ${Counts.ago(nowMs - it)}" }
        return null
    }

    /** One line per radio the phone has that is weak or down, worst first; none while the mesh rests. */
    fun problems(s: WearStatus?): List<Line> =
        issues(s).map { (row, down) ->
            val text =
                when (row.letter) {
                    'B' -> if (down) "Bluetooth is off" else "Bluetooth is weak"
                    'N' -> if (down) "Wi-Fi Aware is off" else "Wi-Fi Aware is limited"
                    'L' -> "LoRa board disconnected"
                    else -> "Internet relay unreachable"
                }
            val icon =
                when (row.letter) {
                    'B' -> Glyph.Bluetooth
                    'N' -> Glyph.Aware
                    'L' -> Glyph.LoRa
                    else -> Glyph.Relay
                }
            Line(text, row.name, icon, if (down) Tone.Bad else Tone.Warn)
        }

    /** The worst problem in a few words, for a title: "LoRa offline", "Aware weak". */
    fun problemShort(s: WearStatus?): String? =
        issues(s).firstOrNull()?.let { (row, down) ->
            when (row.letter) {
                'B' -> if (down) "Bluetooth off" else "Bluetooth weak"
                'N' -> if (down) "Aware off" else "Aware weak"
                'L' -> "LoRa offline"
                else -> "Relay offline"
            }
        }

    fun peers(n: Int): String = if (n == 1) "1 peer" else "$n peers"

    /** (plane, down?) for every weak or down plane, down ones first. */
    private fun issues(s: WearStatus?): List<Pair<PlaneRow, Boolean>> {
        if (s == null || StatusText.resting(s)) return emptyList()
        val rows = StatusText.planes(s)
        return rows.filter { it.plane == Plane.Down }.map { it to true } +
            rows.filter { it.plane == Plane.Degraded }.map { it to false }
    }
}

/** How a line in the peer map is drawn, by the best plane that reaches the peer. */
enum class LinkStyle(
    val label: String,
) {
    /** A solid line. */
    Bluetooth("Bluetooth"),

    /** Two parallel lines: the wider pipe. Wins over Bluetooth for a peer on both. */
    Aware("Wi-Fi Aware"),

    /** A wave: radio over distance. */
    LoRa("LoRa"),

    /** Dots: hops through the internet relay. */
    Relay("Relay"),
}

/** The peer map's logic, pure: which style each peer's line takes and what the legend and summary say. */
object PeerLinks {
    fun style(mask: Int): LinkStyle? =
        when {
            (mask and WearLink.NAN) != 0 -> LinkStyle.Aware
            (mask and WearLink.BLE) != 0 -> LinkStyle.Bluetooth
            (mask and WearLink.LORA) != 0 -> LinkStyle.LoRa
            (mask and WearLink.SPOOL) != 0 -> LinkStyle.Relay
            else -> null
        }

    fun near(mask: Int): Boolean = (mask and WearLink.SHORT_RANGE) != 0

    /** The styles on the map, in legend order — only those drawn, so a BLE-only phone shows one entry. */
    fun legend(links: List<Int>): List<LinkStyle> = links.mapNotNull(::style).distinct().sortedBy { it.ordinal }

    /** "5 nearby · 2 far", "No one in range"; null while the mesh rests or there is no reading. */
    fun summary(s: WearStatus?): String? {
        if (s == null || StatusText.resting(s)) return null
        val far = s.extra?.far ?: 0
        return when {
            s.nearby == 0 && far == 0 -> "No one in range"
            far == 0 -> "${s.nearby} nearby"
            s.nearby == 0 -> "$far far"
            else -> "${s.nearby} nearby · $far far"
        }
    }
}

/** Numbers and times as the watch writes them, pure. */
object Counts {
    /** "just now", "42 s ago", "3 min ago", "2 h ago" — the age of a snapshot on the watch's clock. */
    fun ago(ageMs: Long): String {
        val s = (ageMs / SECOND).coerceAtLeast(0)
        return when {
            s < JUST_NOW_S -> "just now"
            s < MINUTE_S -> "$s s ago"
            s < HOUR_S -> "${s / MINUTE_S} min ago"
            else -> "${s / HOUR_S} h ago"
        }
    }

    /** "under a minute", "45 min", "2 h", "2 h 10 min". */
    fun duration(ms: Long): String {
        val m = (ms / MINUTE_MS).coerceAtLeast(0)
        return when {
            m < 1 -> "under a minute"
            m < MINUTES_PER_HOUR -> "$m min"
            m % MINUTES_PER_HOUR == 0L -> "${m / MINUTES_PER_HOUR} h"
            else -> "${m / MINUTES_PER_HOUR} h ${m % MINUTES_PER_HOUR} min"
        }
    }

    /** The same in at most five characters, for a short complication: "0m", "45m", "2h", "2h10". */
    fun durationShort(ms: Long): String {
        val m = (ms / MINUTE_MS).coerceAtLeast(0)
        return when {
            m < MINUTES_PER_HOUR -> "${m}m"
            m % MINUTES_PER_HOUR == 0L || m >= TEN_HOURS_MIN -> "${m / MINUTES_PER_HOUR}h"
            else -> "${m / MINUTES_PER_HOUR}h%02d".format(Locale.ROOT, m % MINUTES_PER_HOUR)
        }
    }

    /** A wall-clock time in the watch's zone and locale: "12:40", "12:40 PM". */
    fun clock(
        atMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): String = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(Instant.ofEpochMilli(atMs).atZone(zone))

    /** 999 → "999", 1234 → "1.2k", 12345 → "12k", 1234567 → "1.2M". */
    fun compact(n: Long): String =
        when {
            n < THOUSAND -> n.toString()
            n < TEN_THOUSAND -> String.format(Locale.ROOT, "%.1fk", n / THOUSAND.toDouble())
            n < MILLION -> "${n / THOUSAND}k"
            n < TEN_MILLION -> String.format(Locale.ROOT, "%.1fM", n / MILLION.toDouble())
            else -> "${n / MILLION}M"
        }

    /** 3312 → "3,312". */
    fun grouped(n: Long): String = String.format(Locale.ROOT, "%,d", n)

    private const val SECOND = 1_000L
    private const val MINUTE_MS = 60_000L
    private const val MINUTES_PER_HOUR = 60L
    private const val TEN_HOURS_MIN = 600L
    private const val JUST_NOW_S = 5L
    private const val MINUTE_S = 60L
    private const val HOUR_S = 3_600L
    private const val THOUSAND = 1_000L
    private const val TEN_THOUSAND = 10_000L
    private const val MILLION = 1_000_000L
    private const val TEN_MILLION = 10_000_000L
}
