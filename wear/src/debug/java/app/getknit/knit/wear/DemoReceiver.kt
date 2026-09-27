package app.getknit.knit.wear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Debug builds only: stores the snapshot in the `hex` extra (the phone's characteristic value, as
 * `WearStatusCodecTest`'s golden vector is written) as if the phone had just answered, then redraws the
 * complications and the tile. For screenshots and design passes on an emulator, which has no phone:
 *
 * `adb shell am broadcast -a app.getknit.knit.wear.DEMO -p app.getknit.knit --es hex 010305001bd2040000803bb16a`
 *
 * `--el ageMs 330000` backdates the read, to watch a face or the tile go stale on their own clocks.
 */
class DemoReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val hex = intent.getStringExtra("hex") ?: return
        val bytes = runCatching { hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray() }.getOrNull() ?: return
        val readAt = System.currentTimeMillis() - intent.getLongExtra("ageMs", 0L)
        val stored = PhoneStatusReader.store(context, bytes, null, readAt)
        Log.i("KnitWear", "demo snapshot ${if (stored == null) "rejected" else "stored: ${stored.status}"}")
        StatusComplicationService.requestUpdateAll(context)
        StatusRefresh.requestTileUpdate(context)
    }
}
