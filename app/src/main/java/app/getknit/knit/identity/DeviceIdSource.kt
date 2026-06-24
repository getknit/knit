package app.getknit.knit.identity

import android.content.Context
import android.provider.Settings

/**
 * Supplies a stable, app-data-wipe-surviving device identifier used to seed [NodeId.derive].
 *
 * This is the platform seam: Android reads `Settings.Secure.ANDROID_ID` (see [AndroidDeviceIdSource]).
 * A future iOS port would implement this against `UIDevice.identifierForVendor` — or a
 * Keychain-stored UUID, which survives reinstall on iOS — and feed the same [NodeId.derive].
 */
fun interface DeviceIdSource {
    /** A stable per-device id, or `null`/blank if the platform cannot provide one. */
    fun rawDeviceId(): String?
}

/**
 * Android [DeviceIdSource] backed by `Settings.Secure.ANDROID_ID`.
 *
 * `ANDROID_ID` needs no permission and (on minSdk 29) is scoped to the app signing key + user, so it
 * is stable across reinstalls of the same-signed app and across app-data clears. It does reset on
 * factory reset and changes if the signing key changes (e.g. debug vs release) or across user/work
 * profiles — acceptable for a soft anti-abuse deterrent.
 */
class AndroidDeviceIdSource(private val context: Context) : DeviceIdSource {
    override fun rawDeviceId(): String? =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
}
