package app.getknit.knit.wear

import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import app.getknit.knit.wearstatus.MeshState
import app.getknit.knit.wearstatus.Plane
import app.getknit.knit.wearstatus.WearLink
import app.getknit.knit.wearstatus.WearStatus
import java.text.NumberFormat
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
    @param:StringRes val name: Int,
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
 * The words and colours every Wear surface draws, worded from the watch's string resources ([Resources] carries
 * the watch's language) and otherwise pure, so they are unit-tested under Robolectric against the English. A
 * reading within [STALE_MS] is drawn as it stands. Past it, it is drawn with its age rather than as [NO_DATA]:
 * Wear OS does not ask a data source while the watch dozes, so an old reading usually means nobody asked, not that
 * the phone went away. "Phone out of reach" is kept for a read that found no phone (ADR 2026-09.wetm, fourth
 * amendment).
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
            PlaneRow(R.string.plane_bluetooth, 'B', s.ble),
            PlaneRow(R.string.plane_aware, 'N', s.nan),
            PlaneRow(R.string.plane_lora, 'L', s.lora),
            PlaneRow(R.string.plane_relay, 'S', s.spool),
        )

    // --- short text (≤ 7 characters) -------------------------------------------------------------------------

    fun nearby(
        res: Resources,
        s: WearStatus?,
    ): Face =
        when {
            s == null -> {
                Face(NO_DATA, res.getString(R.string.short_nearby), Glyph.NoPhone)
            }

            resting(s) -> {
                Face(
                    NO_DATA,
                    res.getString(
                        if (s.state ==
                            MeshState.Paused
                        ) {
                            R.string.short_paused
                        } else {
                            R.string.short_off
                        },
                    ),
                    glyph(s.state),
                )
            }

            else -> {
                Face(s.nearby.toString(), res.getString(R.string.short_nearby), glyph(s.state), tone(s.state))
            }
        }

    fun health(
        res: Resources,
        s: WearStatus?,
    ): Face =
        when (s?.state) {
            null -> {
                Face(NO_DATA, res.getString(R.string.short_mesh), Glyph.NoPhone)
            }

            MeshState.NoRadio -> {
                Face(
                    res.getString(R.string.short_radios),
                    res.getString(R.string.short_radios_off),
                    Glyph.NoRadio,
                    Tone.Bad,
                )
            }

            else -> {
                Face(word(res, s.state), res.getString(R.string.short_mesh), glyph(s.state), tone(s.state))
            }
        }

    /** What the phone holds for other people — known even while the mesh rests; unknown from an older phone. */
    fun carrying(
        res: Resources,
        s: WearStatus?,
    ): Face {
        val label = res.getString(R.string.short_held)
        return s?.extra?.let { Face(Counts.compact(it.carrying.toLong(), res.locale), label, Glyph.Carrying, Tone.Calm) }
            ?: Face(NO_DATA, label, Glyph.Carrying)
    }

    /** The day's rise in the relayed counter, from the watch's own history. */
    fun relayedToday(
        res: Resources,
        today: Today?,
    ): Face {
        val label = res.getString(R.string.short_today)
        return today?.relayed?.let { Face("+" + Counts.compact(it, res.locale), label, Glyph.Relayed, Tone.Calm) }
            ?: Face(NO_DATA, label, Glyph.Relayed)
    }

    /** How long the mesh was linked today; "–" before the watch has any reading today. */
    fun day(
        res: Resources,
        today: Today?,
    ): Face {
        val share = today?.share
        val label = res.getString(R.string.short_linked)
        if (share == null || share.totalMs == 0L) return Face(NO_DATA, label, Glyph.Day)
        return Face(Counts.durationShort(res, share.linkedMs), label, Glyph.Day, if (share.linkedMs > 0) Tone.Good else Tone.Calm)
    }

    fun word(
        res: Resources,
        state: MeshState,
    ): String =
        res.getString(
            when (state) {
                MeshState.Off -> R.string.state_word_off
                MeshState.Paused -> R.string.state_word_paused
                MeshState.Alone -> R.string.state_word_alone
                MeshState.Linked -> R.string.state_word_linked
                MeshState.Degraded -> R.string.state_word_weak
                MeshState.NoRadio -> R.string.state_word_no_radio
            },
        )

    /** "Mesh paused" or "Mesh off", for a mesh that is not running. */
    fun meshResting(
        res: Resources,
        state: MeshState,
    ): String = res.getString(if (state == MeshState.Paused) R.string.mesh_paused else R.string.mesh_off)
}

/**
 * The long-text sentences — a long complication, the tile's descriptions, TalkBack — and the radio problems,
 * pure like [StatusText]. Radios are only ever named when one the phone has is weak or down: a healthy radio
 * is the normal case, and one the user never set up is not a problem.
 */
object StatusLines {
    fun nearbyLine(
        res: Resources,
        s: WearStatus?,
    ): Line {
        val knit = res.getString(R.string.app_name)
        return when {
            s == null -> {
                Line(res.getString(R.string.phone_out_of_reach), knit, Glyph.NoPhone)
            }

            StatusText.resting(s) -> {
                Line(StatusText.meshResting(res, s.state), knit, StatusText.glyph(s.state))
            }

            s.state == MeshState.NoRadio -> {
                Line(res.getString(R.string.radios_off), knit, Glyph.NoRadio, Tone.Bad)
            }

            s.nearby == 0 -> {
                Line(res.getString(R.string.no_one_nearby), knit, StatusText.glyph(s.state), StatusText.tone(s.state))
            }

            else -> {
                Line(
                    res.getQuantityString(R.plurals.peers_nearby, s.nearby, s.nearby),
                    knit,
                    StatusText.glyph(s.state),
                    StatusText.tone(s.state),
                )
            }
        }
    }

    fun healthLine(
        res: Resources,
        s: WearStatus?,
    ): Line {
        val title = res.getString(R.string.title_knit_mesh)
        return when (s?.state) {
            null -> {
                Line(res.getString(R.string.phone_out_of_reach), title, Glyph.NoPhone)
            }

            MeshState.Linked -> {
                Line(
                    res.count(R.plurals.linked_nearby, R.string.linked_nearby_zero, s.nearby.toLong(), s.nearby),
                    title,
                    Glyph.Linked,
                    Tone.Good,
                )
            }

            MeshState.Alone -> {
                Line(res.getString(R.string.alone_listening), title, Glyph.Alone, Tone.Calm)
            }

            MeshState.Degraded -> {
                val text =
                    problemShort(res, s)?.let { res.getString(R.string.weak_problem, it) } ?: res.getString(R.string.weak_check_radios)
                Line(text, title, Glyph.Weak, Tone.Warn)
            }

            MeshState.NoRadio -> {
                Line(res.getString(R.string.radios_off), title, Glyph.NoRadio, Tone.Bad)
            }

            MeshState.Paused -> {
                Line(StatusText.word(res, s.state), title, Glyph.Paused)
            }

            MeshState.Off -> {
                Line(StatusText.word(res, s.state), title, Glyph.Off)
            }
        }
    }

    fun carryingLine(
        res: Resources,
        s: WearStatus?,
    ): Line {
        val title = res.getString(R.string.title_carrying)
        val n = s?.extra?.carrying ?: return Line(res.getString(R.string.phone_out_of_reach), title, Glyph.Carrying)
        val text =
            if (n == 0) {
                res.getString(R.string.nothing_held)
            } else {
                res.getQuantityString(R.plurals.held_for_others, n, Counts.grouped(n.toLong(), res.locale))
            }
        return Line(text, title, Glyph.Carrying, Tone.Calm)
    }

    fun relayedLine(
        res: Resources,
        s: WearStatus?,
        today: Today?,
    ): Line {
        val title = s?.let { relayedInAll(res, it.relayed) } ?: res.getString(R.string.title_relayed)
        val text = today?.relayed?.let { relayedToday(res, it) } ?: res.getString(R.string.no_readings_today)
        return Line(text, title, Glyph.Relayed, Tone.Calm)
    }

    /** "1,234 in all": the phone's all-time relayed count. */
    fun relayedInAll(
        res: Resources,
        n: Long,
    ): String = res.count(R.plurals.relayed_in_all, R.string.relayed_in_all_zero, n, Counts.grouped(n, res.locale))

    /** "42 relayed today". */
    fun relayedToday(
        res: Resources,
        n: Long,
    ): String = res.count(R.plurals.relayed_today, R.string.relayed_today_zero, n, Counts.grouped(n, res.locale))

    fun dayLine(
        res: Resources,
        today: Today?,
    ): Line {
        val share = today?.share
        val title = res.getString(R.string.title_your_day)
        if (share == null || share.totalMs == 0L) return Line(res.getString(R.string.no_readings_today), title, Glyph.Day)
        val text =
            if (share.linkedMs == 0L) {
                res.getString(R.string.not_linked_today)
            } else {
                res.getString(R.string.linked_duration_today, Counts.duration(res, share.linkedMs))
            }
        return Line(text, title, Glyph.Day, if (share.linkedMs > 0) Tone.Good else Tone.Calm)
    }

    /** "Linked for 2 h 10 min" while linked; "Last peer 14 min ago" after; null with no peer today. */
    fun streak(
        res: Resources,
        today: Today?,
        nowMs: Long,
    ): String? {
        today?.linkedSinceMs?.let { return res.getString(R.string.linked_for, Counts.duration(res, nowMs - it)) }
        today?.lastPeerAtMs?.let { return res.getString(R.string.last_peer, Counts.ago(res, nowMs - it)) }
        return null
    }

    /** One line per radio the phone has that is weak or down, worst first; none while the mesh rests. */
    fun problems(
        res: Resources,
        s: WearStatus?,
    ): List<Line> =
        issues(s).map { (row, down) ->
            val text =
                when (row.letter) {
                    'B' -> if (down) R.string.problem_bluetooth_off else R.string.problem_bluetooth_weak
                    'N' -> if (down) R.string.problem_aware_off else R.string.problem_aware_weak
                    'L' -> R.string.problem_lora
                    else -> R.string.problem_relay
                }
            val icon =
                when (row.letter) {
                    'B' -> Glyph.Bluetooth
                    'N' -> Glyph.Aware
                    'L' -> Glyph.LoRa
                    else -> Glyph.Relay
                }
            Line(res.getString(text), res.getString(row.name), icon, if (down) Tone.Bad else Tone.Warn)
        }

    /** The worst problem in a few words, for a title: "LoRa offline", "Aware weak". */
    fun problemShort(
        res: Resources,
        s: WearStatus?,
    ): String? =
        issues(s).firstOrNull()?.let { (row, down) ->
            res.getString(
                when (row.letter) {
                    'B' -> if (down) R.string.problem_short_bluetooth_off else R.string.problem_short_bluetooth_weak
                    'N' -> if (down) R.string.problem_short_aware_off else R.string.problem_short_aware_weak
                    'L' -> R.string.problem_short_lora
                    else -> R.string.problem_short_relay
                },
            )
        }

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
    @param:StringRes val label: Int,
) {
    /** A solid line. */
    Bluetooth(R.string.plane_bluetooth),

    /** Two parallel lines: the wider pipe. Wins over Bluetooth for a peer on both. */
    Aware(R.string.plane_aware),

    /** A wave: radio over distance. */
    LoRa(R.string.plane_lora),

    /** Dots: hops through the internet relay. */
    Relay(R.string.link_relay),
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
    fun summary(
        res: Resources,
        s: WearStatus?,
    ): String? {
        if (s == null || StatusText.resting(s)) return null
        val far = s.extra?.far ?: 0
        val nearby = res.getQuantityString(R.plurals.map_nearby, s.nearby, s.nearby)
        val farText = res.getQuantityString(R.plurals.map_far, far, far)
        return when {
            s.nearby == 0 && far == 0 -> res.getString(R.string.no_one_in_range)
            far == 0 -> nearby
            s.nearby == 0 -> farText
            else -> res.getString(R.string.joined_with_dot, nearby, farText)
        }
    }
}

/** The language the watch's strings are in, for the numbers written beside them. */
val Resources.locale: Locale get() = configuration.locales[0]

/**
 * [plural] for [n], with [arg] (by default [n]) as its argument, or [zero] at 0: Android picks a plural's form by
 * CLDR, which files 0 under `one` in Portuguese and French, and a `<plurals>` cannot say otherwise.
 */
fun Resources.count(
    @PluralsRes plural: Int,
    @StringRes zero: Int,
    n: Long,
    arg: Any = n,
): String = if (n == 0L) getString(zero, arg) else getQuantityString(plural, quantity(n), arg)

/**
 * [n] as a plural quantity, which Android takes as an Int. CLDR's rules read only a number's last six digits
 * (and whether it is a whole million), so a count past [Int.MAX_VALUE] keeps its form.
 */
private fun quantity(n: Long): Int = if (n < MILLION_Q) n.toInt() else (n % MILLION_Q + MILLION_Q).toInt()

private const val MILLION_Q = 1_000_000L

/** Numbers and times as the watch writes them, in its language. */
object Counts {
    /** "just now", "42 s ago", "3 min ago", "2 h ago" — the age of a snapshot on the watch's clock. */
    fun ago(
        res: Resources,
        ageMs: Long,
    ): String {
        val s = (ageMs / SECOND).coerceAtLeast(0)
        return when {
            s < JUST_NOW_S -> res.getString(R.string.ago_just_now)
            s < MINUTE_S -> res.getString(R.string.ago_seconds, s)
            s < HOUR_S -> res.getString(R.string.ago_minutes, s / MINUTE_S)
            else -> res.getString(R.string.ago_hours, s / HOUR_S)
        }
    }

    /** "under a minute", "45 min", "2 h", "2 h 10 min" (each number bound to its unit by a no-break space, WO-V1). */
    fun duration(
        res: Resources,
        ms: Long,
    ): String {
        val m = (ms / MINUTE_MS).coerceAtLeast(0)
        return when {
            m < 1 -> res.getString(R.string.duration_under_a_minute)
            m < MINUTES_PER_HOUR -> res.getString(R.string.duration_minutes, m)
            m % MINUTES_PER_HOUR == 0L -> res.getString(R.string.duration_hours, m / MINUTES_PER_HOUR)
            else -> res.getString(R.string.duration_hours_minutes, m / MINUTES_PER_HOUR, m % MINUTES_PER_HOUR)
        }
    }

    /** The same in at most five characters, for a short complication: "0m", "45m", "2h", "2h10". */
    fun durationShort(
        res: Resources,
        ms: Long,
    ): String {
        val m = (ms / MINUTE_MS).coerceAtLeast(0)
        return when {
            m < MINUTES_PER_HOUR -> res.getString(R.string.duration_short_minutes, m)
            m % MINUTES_PER_HOUR == 0L || m >= TEN_HOURS_MIN -> res.getString(R.string.duration_short_hours, m / MINUTES_PER_HOUR)
            else -> res.getString(R.string.duration_short_hours_minutes, m / MINUTES_PER_HOUR, m % MINUTES_PER_HOUR)
        }
    }

    /** A wall-clock time in the watch's zone and locale: "12:40", "12:40 PM". */
    fun clock(
        atMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): String = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(Instant.ofEpochMilli(atMs).atZone(zone))

    /** 999 → "999", 1234 → "1.2k", 12345 → "12k", 1234567 → "1.2M": SI suffixes, [locale]'s digits ("1,2k" in German). */
    fun compact(
        n: Long,
        locale: Locale,
    ): String =
        when {
            n < THOUSAND -> String.format(locale, "%d", n)
            n < TEN_THOUSAND -> String.format(locale, "%.1fk", n / THOUSAND.toDouble())
            n < MILLION -> String.format(locale, "%dk", n / THOUSAND)
            n < TEN_MILLION -> String.format(locale, "%.1fM", n / MILLION.toDouble())
            else -> String.format(locale, "%dM", n / MILLION)
        }

    /** 3312 → "3,312" in English, "3.312" in German. */
    fun grouped(
        n: Long,
        locale: Locale,
    ): String = NumberFormat.getIntegerInstance(locale).format(n)

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
