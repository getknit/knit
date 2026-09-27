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
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.MonochromaticImageComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.SmallImage
import androidx.wear.watchface.complications.data.SmallImageComplicationData
import androidx.wear.watchface.complications.data.SmallImageType
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

/** What a complication is drawn from: the phone's snapshot (null: no fresh one) and the watch's own day. */
data class WatchView(
    val status: WearStatus?,
    val today: Today?,
)

/**
 * One Knit complication over the phone's snapshot and the watch's history, in every type its data suits (the
 * manifest lists them, the face picks). The system asks every `UPDATE_PERIOD_SECONDS` (300, the manifest) and
 * whenever a fresh read lands; each ask reads through [PhoneStatusReader], whose cache makes the sources share
 * one connection.
 *
 * A source over live state ([goesStale]) answers with a timeline: the live data now, and from
 * [StatusText.STALE_MS] after the read the no-data face — a watch face renders the timeline on its own clock, so
 * a phone that stops answering greys out even if the system never asks again (a face too old for timelines
 * keeps the live data). A source over the day's history stays true when the phone goes quiet, so it answers
 * plainly. A tap opens the app.
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
        val snapshot = PhoneStatusReader.read(this)
        val now = System.currentTimeMillis()
        val today = StatusHistory.today(this, now)
        if (!goesStale) return build(type, WatchView(snapshot?.status, today))?.let { ComplicationDataTimeline(it, emptyList()) }
        val stale = build(type, WatchView(null, today)) ?: return null
        val status = StatusText.fresh(snapshot, now)
        if (snapshot == null || status == null) return ComplicationDataTimeline(stale, emptyList())
        val live = build(type, WatchView(status, today)) ?: return null
        val goesStaleAt = Instant.ofEpochMilli(snapshot.fetchedAtMs + StatusText.STALE_MS)
        val after = TimeInterval(goesStaleAt, goesStaleAt.plus(STALE_FOREVER))
        return ComplicationDataTimeline(live, listOf(TimelineEntry(after, stale)))
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

        /** How long the no-data entry lasts; long enough that nothing reads past it before the next ask. */
        private val STALE_FOREVER: Duration = Duration.ofDays(365)

        private val ALL =
            listOf(
                NearbyComplicationService::class.java,
                HealthComplicationService::class.java,
                DayComplicationService::class.java,
                RelayedComplicationService::class.java,
                CarryingComplicationService::class.java,
            )

        /** Ask the system to re-query every Knit complication on the face (after a fresh read). */
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
            ComplicationType.SHORT_TEXT -> shortText(StatusText.nearby(view.status))
            ComplicationType.LONG_TEXT -> longText(StatusLines.nearbyLine(view.status))
            ComplicationType.RANGED_VALUE -> nearbyGauge(view.status)
            else -> null
        }
}

class HealthComplicationService : StatusComplicationService() {
    override fun Context.build(
        type: ComplicationType,
        view: WatchView,
    ): ComplicationData? {
        val line = StatusLines.healthLine(view.status)
        return when (type) {
            ComplicationType.SHORT_TEXT -> {
                shortText(StatusText.health(view.status))
            }

            ComplicationType.LONG_TEXT -> {
                longText(line)
            }

            ComplicationType.SMALL_IMAGE -> {
                SmallImageComplicationData
                    .Builder(smallImage(line.icon, line.tone), text("${line.title}: ${line.text}"))
                    .setTapAction(openApp())
                    .build()
            }

            ComplicationType.MONOCHROMATIC_IMAGE -> {
                MonochromaticImageComplicationData
                    .Builder(monochrome(line.icon), text("${line.title}: ${line.text}"))
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
            ComplicationType.SHORT_TEXT -> shortText(StatusText.carrying(view.status))
            ComplicationType.LONG_TEXT -> longText(StatusLines.carryingLine(view.status))
            else -> null
        }
}

// --- builders shared by the four sources ------------------------------------------------------------------------

private fun Context.shortText(face: Face): ComplicationData =
    ShortTextComplicationData
        .Builder(text(face.text), text(listOfNotNull(face.text, face.title).joinToString(" ")))
        .apply { face.title?.let { setTitle(text(it)) } }
        .setMonochromaticImage(monochrome(face.icon))
        .setSmallImage(smallImage(face.icon, face.tone))
        .setTapAction(openApp())
        .build()

private fun Context.longText(line: Line): ComplicationData =
    LongTextComplicationData
        .Builder(text(line.text), text(listOfNotNull(line.title, line.text).joinToString(": ")))
        .apply { line.title?.let { setTitle(text(it)) } }
        .setMonochromaticImage(monochrome(line.icon))
        .setSmallImage(smallImage(line.icon, line.tone))
        .setTapAction(openApp())
        .build()

/**
 * Nearby peers as a gauge filling toward [StatusText.NEARBY_SCALE], coloured from "alone" to "linked". The
 * text is the true count, so a crowd of 23 fills the ring and still says 23.
 */
private fun Context.nearbyGauge(status: WearStatus?): ComplicationData {
    val face = StatusText.nearby(status)
    val count = status?.takeUnless { StatusText.resting(it) }?.nearby ?: 0
    val ramp =
        if (StatusText.resting(status)) {
            intArrayOf(Tone.Muted.argb)
        } else {
            intArrayOf(Tone.Calm.argb, Tone.Good.argb)
        }
    return RangedValueComplicationData
        .Builder(
            value = count.coerceAtMost(StatusText.NEARBY_SCALE).toFloat(),
            min = 0f,
            max = StatusText.NEARBY_SCALE.toFloat(),
            contentDescription = text(StatusLines.nearbyLine(status).text),
        ).setText(text(face.text))
        .apply { face.title?.let { setTitle(text(it)) } }
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
