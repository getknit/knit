package app.getknit.knit.wear

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.wear.watchface.complications.data.ColorRamp
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationText
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.CountUpTimeReference
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.MonochromaticImageComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.SmallImage
import androidx.wear.watchface.complications.data.SmallImageComplicationData
import androidx.wear.watchface.complications.data.SmallImageType
import androidx.wear.watchface.complications.data.TimeDifferenceComplicationText
import androidx.wear.watchface.complications.data.TimeDifferenceStyle
import androidx.wear.watchface.complications.data.WeightedElementsComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.ComplicationDataTimeline
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingTimelineComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.TimeInterval
import androidx.wear.watchface.complications.datasource.TimelineEntry
import app.getknit.knit.wearstatus.MeshState
import app.getknit.knit.wearstatus.Plane
import app.getknit.knit.wearstatus.WearExtra
import app.getknit.knit.wearstatus.WearStatus
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * What a complication is drawn from: the phone's reading (null: none, or the phone is out of reach), when it was
 * taken if it is past the live window ([agedSinceMs], shown as its age in a muted tone), and the watch's own day.
 */
data class WatchView(
    val status: WearStatus?,
    val today: Today?,
    val agedSinceMs: Long? = null,
) {
    constructor(shown: Shown?, today: Today?) : this(shown?.status, today, shown?.agedSinceMs)
}

/**
 * One Knit complication over the phone's snapshot and the watch's history, in every type its data suits (the
 * manifest lists them, the face picks). The system asks every `UPDATE_PERIOD_SECONDS` (300, the manifest) while
 * the watch is awake — never while it dozes, and a raised wrist does not wake it from Doze — and whenever a read
 * lands, so while a source is on the face [StatusAlarm] reads on Knit's own five-minute clock. An ask never
 * waits on Bluetooth: it answers from [PhoneStatusReader]'s cache and, when that is due, starts a read
 * ([StatusRefresh], run in [StatusReadJob]) whose landing asks every source again, so the sources share one
 * connection.
 *
 * A source over live state ([goesStale]) answers with a timeline: the live reading now, and from
 * [StatusText.STALE_MS] after the read the same reading muted with its age counting up — or "Phone out of reach"
 * if a read since found no phone ([StatusText.shown]). A watch face renders the timeline on its own clock, so it
 * turns without an ask, which never comes while the watch dozes (a face too old for timelines keeps the live
 * entry). A source over the day's history stays true when the phone goes quiet, so it answers plainly. A tap
 * opens the app.
 */
abstract class StatusComplicationService : SuspendingTimelineComplicationDataSourceService() {
    /** [type] drawn for [view], or null if this source does not offer [type]. */
    protected abstract fun Context.build(
        type: ComplicationType,
        view: WatchView,
    ): ComplicationData?

    /** False for a source drawn from the watch's own history, which does not age with the phone's reading. */
    protected open val goesStale: Boolean = true

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationDataTimeline? {
        val type = request.complicationType
        StatusAlarm.asked(this, request.complicationInstanceId)
        val snapshot = cachedAfterKick()
        val failedAt = PhoneStatusReader.failedAt(this)
        val now = System.currentTimeMillis()
        val today = StatusHistory.today(this, now)
        if (!goesStale) return build(type, WatchView(snapshot?.status, today))?.let { ComplicationDataTimeline(it, emptyList()) }
        val shown = StatusText.shown(snapshot, failedAt, now)
        val current = build(type, WatchView(shown, today)) ?: return null
        // A live reading turns, at the close of its window, into what it will be then: aged, or out of reach.
        val closes =
            snapshot?.takeIf { shown != null && shown.agedSinceMs == null }?.let { live ->
                val at = Instant.ofEpochMilli(live.fetchedAtMs + StatusText.STALE_MS)
                build(type, WatchView(StatusText.afterLive(live, failedAt), today))
                    ?.let { TimelineEntry(TimeInterval(at, at.plus(STALE_FOREVER)), it) }
            }
        return ComplicationDataTimeline(current, listOfNotNull(closes))
    }

    override fun onComplicationDeactivated(complicationInstanceId: Int) {
        StatusAlarm.deactivated(this, complicationInstanceId)
    }

    /** The cached copy — an ask never waits on Bluetooth — after starting a read if that copy is due. */
    private fun cachedAfterKick(): Snapshot? {
        StatusRefresh.kick(this, force = false)
        return PhoneStatusReader.cached(this)
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? = build(type, WatchView(PREVIEW, PREVIEW_TODAY))

    companion object {
        /** The editor's preview: a phone meshing with three others, and a day with some of everything in it. */
        val PREVIEW =
            WearStatus(
                state = MeshState.Linked,
                nearby = 3,
                ble = Plane.Live,
                nan = Plane.Live,
                lora = Plane.Live,
                spool = Plane.Absent,
                relayed = 1_240,
                stampSec = 0,
                extra = WearExtra(carrying = 12, far = 1, links = listOf(3, 1, 1, 4)),
            )

        private const val MINUTE = 60_000L

        val PREVIEW_TODAY =
            Today(
                relayed = 42,
                share = DayShare(linkedMs = 130 * MINUTE, aloneMs = 240 * MINUTE, weakMs = 20 * MINUTE, restingMs = 60 * MINUTE),
                busiest = 6 to 0L,
                linkedSinceMs = null,
                lastPeerAtMs = null,
            )

        /** How long the entry after the live window lasts; long enough that nothing reads past it before the next ask. */
        private val STALE_FOREVER: Duration = Duration.ofDays(365)

        private val ALL =
            listOf(
                NearbyComplicationService::class.java,
                HealthComplicationService::class.java,
                DayComplicationService::class.java,
                RelayedComplicationService::class.java,
                CarryingComplicationService::class.java,
            )

        /** Ask the system to re-query every Knit complication on the face (after a read changed what they show). */
        fun requestUpdateAll(context: Context) {
            for (service in ALL) {
                ComplicationDataSourceUpdateRequester
                    .create(context, ComponentName(context, service))
                    .requestUpdateAll()
            }
        }
    }
}

class NearbyComplicationService : StatusComplicationService() {
    override fun Context.build(
        type: ComplicationType,
        view: WatchView,
    ): ComplicationData? =
        when (type) {
            ComplicationType.SHORT_TEXT -> shortText(StatusText.nearby(view.status), view.agedSinceMs)
            ComplicationType.LONG_TEXT -> longText(StatusLines.nearbyLine(view.status), view.agedSinceMs)
            ComplicationType.RANGED_VALUE -> nearbyGauge(view.status, view.agedSinceMs)
            else -> null
        }
}

class HealthComplicationService : StatusComplicationService() {
    override fun Context.build(
        type: ComplicationType,
        view: WatchView,
    ): ComplicationData? {
        val line = StatusLines.healthLine(view.status)
        val aged = view.agedSinceMs
        val description = describe("${line.title}: ${line.text}", aged)
        return when (type) {
            ComplicationType.SHORT_TEXT -> {
                shortText(StatusText.health(view.status), aged)
            }

            ComplicationType.LONG_TEXT -> {
                longText(line, aged)
            }

            ComplicationType.SMALL_IMAGE -> {
                SmallImageComplicationData
                    .Builder(smallImage(line.icon, if (aged != null) Tone.Muted else line.tone), description)
                    .setTapAction(openApp())
                    .build()
            }

            ComplicationType.MONOCHROMATIC_IMAGE -> {
                MonochromaticImageComplicationData
                    .Builder(monochrome(line.icon), description)
                    .setTapAction(openApp())
                    .build()
            }

            else -> {
                null
            }
        }
    }
}

/** How the day went, from the watch's own history: a pie of linked / alone / weak / resting, or its linked time. */
class DayComplicationService : StatusComplicationService() {
    override val goesStale = false

    override fun Context.build(
        type: ComplicationType,
        view: WatchView,
    ): ComplicationData? =
        when (type) {
            ComplicationType.SHORT_TEXT -> {
                shortText(StatusText.day(view.today))
            }

            ComplicationType.LONG_TEXT -> {
                longText(StatusLines.dayLine(view.today))
            }

            else -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && type == ComplicationType.WEIGHTED_ELEMENTS) {
                    dayPie(view.today)
                } else {
                    null
                }
            }
        }
}

/** Relayed today — the rise in the phone's all-time count across the watch's readings since midnight. */
class RelayedComplicationService : StatusComplicationService() {
    override val goesStale = false

    override fun Context.build(
        type: ComplicationType,
        view: WatchView,
    ): ComplicationData? =
        when (type) {
            ComplicationType.SHORT_TEXT -> shortText(StatusText.relayedToday(view.today))
            ComplicationType.LONG_TEXT -> longText(StatusLines.relayedLine(view.status, view.today))
            else -> null
        }
}

/** What the phone is holding for other people right now — store-and-forward at a glance. */
class CarryingComplicationService : StatusComplicationService() {
    override fun Context.build(
        type: ComplicationType,
        view: WatchView,
    ): ComplicationData? =
        when (type) {
            ComplicationType.SHORT_TEXT -> shortText(StatusText.carrying(view.status), view.agedSinceMs)
            ComplicationType.LONG_TEXT -> longText(StatusLines.carryingLine(view.status), view.agedSinceMs)
            else -> null
        }
}

// --- builders shared by the four sources ------------------------------------------------------------------------

/**
 * A short text; an aged reading ([agedSinceMs]) keeps its value but trades the title for its age ("12m ago",
 * counted up by the face) and its colour for [Tone.Muted].
 */
private fun Context.shortText(
    face: Face,
    agedSinceMs: Long? = null,
): ComplicationData {
    val tone = if (agedSinceMs != null) Tone.Muted else face.tone
    val title = agedSinceMs?.let { ago(it, "^1 ago") } ?: face.title?.let(::text)
    return ShortTextComplicationData
        .Builder(text(face.text), describe(listOfNotNull(face.text, face.title).joinToString(" "), agedSinceMs))
        .apply { title?.let { setTitle(it) } }
        .setMonochromaticImage(monochrome(face.icon))
        .setSmallImage(smallImage(face.icon, tone))
        .setTapAction(openApp())
        .build()
}

/** A long text; an aged reading adds its age to the title ("Knit mesh · 12m ago") and mutes its colour. */
private fun Context.longText(
    line: Line,
    agedSinceMs: Long? = null,
): ComplicationData {
    val tone = if (agedSinceMs != null) Tone.Muted else line.tone
    val title = agedSinceMs?.let { ago(it, listOfNotNull(line.title, "^1 ago").joinToString(" · ")) } ?: line.title?.let(::text)
    return LongTextComplicationData
        .Builder(text(line.text), describe(listOfNotNull(line.title, line.text).joinToString(": "), agedSinceMs))
        .apply { title?.let { setTitle(it) } }
        .setMonochromaticImage(monochrome(line.icon))
        .setSmallImage(smallImage(line.icon, tone))
        .setTapAction(openApp())
        .build()
}

/**
 * Nearby peers as a gauge filling toward [StatusText.NEARBY_SCALE], coloured from "alone" to "linked". The
 * text is the true count, so a crowd of 23 fills the ring and still says 23. An aged reading keeps its count in
 * a muted ring, titled with its age.
 */
private fun Context.nearbyGauge(
    status: WearStatus?,
    agedSinceMs: Long?,
): ComplicationData {
    val face = StatusText.nearby(status)
    val count = status?.takeUnless { StatusText.resting(it) }?.nearby ?: 0
    val ramp =
        if (StatusText.resting(status) || agedSinceMs != null) {
            intArrayOf(Tone.Muted.argb)
        } else {
            intArrayOf(Tone.Calm.argb, Tone.Good.argb)
        }
    val title = agedSinceMs?.let { ago(it, "^1 ago") } ?: face.title?.let(::text)
    return RangedValueComplicationData
        .Builder(
            value = count.coerceAtMost(StatusText.NEARBY_SCALE).toFloat(),
            min = 0f,
            max = StatusText.NEARBY_SCALE.toFloat(),
            contentDescription = describe(StatusLines.nearbyLine(status).text, agedSinceMs),
        ).setText(text(face.text))
        .apply { title?.let { setTitle(it) } }
        .setMonochromaticImage(monochrome(face.icon))
        .setColorRamp(ColorRamp(ramp, interpolated = true))
        .setTapAction(openApp())
        .build()
}

/**
 * The day as a pie: time linked (green), alone (the brand colour), weak (amber) and at rest (grey), sized by
 * how long each lasted as far as the watch saw; the middle says how long it was linked. Weighted elements are
 * Wear OS 4 (API 33)+; an older system never asks for the type.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun Context.dayPie(today: Today?): ComplicationData {
    val share = today?.share ?: DayShare()
    val elements =
        listOf(
            share.linkedMs to Tone.Good,
            share.aloneMs to Tone.Calm,
            share.weakMs to Tone.Warn,
            share.restingMs to Tone.Muted,
        ).filter { it.first > 0 }
            .map { (ms, tone) -> WeightedElementsComplicationData.Element(ms.toFloat(), tone.argb) }
            .ifEmpty { listOf(WeightedElementsComplicationData.Element(1f, Tone.Muted.argb)) }
    val face = StatusText.day(today)
    return WeightedElementsComplicationData
        .Builder(elements, text(StatusLines.dayLine(today).text))
        .setText(text(face.text))
        .apply { face.title?.let { setTitle(text(it)) } }
        .setMonochromaticImage(monochrome(Glyph.Day))
        .setTapAction(openApp())
        .build()
}

private fun text(s: String): ComplicationText = PlainComplicationText.Builder(s).build()

/** [template] with `^1` as the time since [sinceMs] ("12m", "3h"), counted up by the face on its own clock. */
private fun ago(
    sinceMs: Long,
    template: String,
): ComplicationText =
    TimeDifferenceComplicationText
        .Builder(TimeDifferenceStyle.SHORT_SINGLE_UNIT, CountUpTimeReference(Instant.ofEpochMilli(sinceMs)))
        .setText(template)
        .setMinimumTimeUnit(TimeUnit.MINUTES)
        .build()

/** What TalkBack reads: [s], and for an aged reading how old it is. */
private fun describe(
    s: String,
    agedSinceMs: Long?,
): ComplicationText = agedSinceMs?.let { ago(it, "$s, ^1 ago") } ?: text(s)

private fun Context.monochrome(glyph: Glyph): MonochromaticImage =
    MonochromaticImage.Builder(Icon.createWithResource(this, glyph.res)).build()

private fun Context.smallImage(
    glyph: Glyph,
    tone: Tone,
): SmallImage =
    SmallImage
        .Builder(Icon.createWithResource(this, glyph.res).setTint(tone.argb), SmallImageType.ICON)
        .build()

private fun Context.openApp(): PendingIntent =
    PendingIntent.getActivity(
        this,
        0,
        Intent(this, StatusActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE,
    )

val Glyph.res: Int
    get() =
        when (this) {
            Glyph.Linked -> R.drawable.ic_state_linked
            Glyph.Alone -> R.drawable.ic_state_alone
            Glyph.Weak -> R.drawable.ic_state_weak
            Glyph.Paused -> R.drawable.ic_state_paused
            Glyph.Off -> R.drawable.ic_state_off
            Glyph.NoRadio -> R.drawable.ic_state_no_radio
            Glyph.NoPhone -> R.drawable.ic_state_no_phone
            Glyph.Radios -> R.drawable.ic_radios
            Glyph.Relayed -> R.drawable.ic_relayed
            Glyph.Carrying -> R.drawable.ic_carrying
            Glyph.Day -> R.drawable.ic_day
            Glyph.Bluetooth -> R.drawable.ic_plane_ble
            Glyph.Aware -> R.drawable.ic_plane_nan
            Glyph.LoRa -> R.drawable.ic_plane_lora
            Glyph.Relay -> R.drawable.ic_plane_spool
        }
