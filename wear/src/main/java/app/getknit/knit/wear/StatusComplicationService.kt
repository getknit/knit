package app.getknit.knit.wear

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import app.getknit.knit.wearstatus.MeshState
import app.getknit.knit.wearstatus.Plane
import app.getknit.knit.wearstatus.WearStatus

/**
 * One short-text complication over the phone's snapshot. The system asks every `UPDATE_PERIOD_SECONDS` (300,
 * the manifest) and whenever the status screen refreshes; each ask reads through [PhoneStatusReader], whose
 * cache makes the four services share one connection. A tap opens the status screen.
 */
abstract class StatusComplicationService : SuspendingComplicationDataSourceService() {
    protected abstract fun face(status: WearStatus?): Face

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        if (request.complicationType != ComplicationType.SHORT_TEXT) return null
        val snapshot = PhoneStatusReader.read(this)
        return build(face(StatusText.fresh(snapshot, System.currentTimeMillis())))
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        if (type == ComplicationType.SHORT_TEXT) build(face(PREVIEW)) else null

    private fun build(face: Face): ComplicationData {
        val text = PlainComplicationText.Builder(face.text).build()
        val description = PlainComplicationText.Builder(listOfNotNull(face.text, face.title).joinToString(" ")).build()
        return ShortTextComplicationData
            .Builder(text, description)
            .apply { face.title?.let { setTitle(PlainComplicationText.Builder(it).build()) } }
            .setMonochromaticImage(MonochromaticImage.Builder(Icon.createWithResource(this, face.icon.res)).build())
            .setTapAction(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, StatusActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            ).build()
    }

    companion object {
        private val PREVIEW =
            WearStatus(
                state = MeshState.Linked,
                nearby = 3,
                ble = Plane.Live,
                nan = Plane.Live,
                lora = Plane.Live,
                spool = Plane.Absent,
                relayed = 1_240,
                stampSec = 0,
            )

        private val ALL =
            listOf(
                NearbyComplicationService::class.java,
                HealthComplicationService::class.java,
                TransportsComplicationService::class.java,
                RelayedComplicationService::class.java,
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

private val Glyph.res: Int
    get() =
        when (this) {
            Glyph.Mesh -> R.drawable.ic_mesh
            Glyph.MeshOff -> R.drawable.ic_mesh_off
            Glyph.Radios -> R.drawable.ic_radios
            Glyph.Relayed -> R.drawable.ic_relayed
        }

class NearbyComplicationService : StatusComplicationService() {
    override fun face(status: WearStatus?) = StatusText.nearby(status)
}

class HealthComplicationService : StatusComplicationService() {
    override fun face(status: WearStatus?) = StatusText.health(status)
}

class TransportsComplicationService : StatusComplicationService() {
    override fun face(status: WearStatus?) = StatusText.transports(status)
}

class RelayedComplicationService : StatusComplicationService() {
    override fun face(status: WearStatus?) = StatusText.relayed(status)
}
