package app.getknit.knit.identity

import android.content.Context
import android.provider.Settings

/**
 * Supplies a stable, reinstall-surviving per-device identifier — the seed for the soft block-list
 * [DeviceTag] (NOT the cryptographic [nodeId][NodeId], which is derived from the E2E keypair).
 *
 * This is the platform seam: Android reads `Settings.Secure.ANDROID_ID` (see [AndroidDeviceIdSource]).
 * A future iOS port would implement this against a Keychain-stored UUID (which survives reinstall on
 * iOS) and feed the same [DeviceTag.derive].
 */
fun interface DeviceIdSource {
    /** A stable per-device id, or `null`/blank if the platform cannot provide one. */
    fun rawDeviceId(): String?
}

/**
 * Android [DeviceIdSource] backed by `Settings.Secure.ANDROID_ID`.
 *
 * `ANDROID_ID` needs no permission and (on minSdk 29) is scoped to the app signing key + user, so it
 * is stable across reinstalls of the same-signed app and across app-data clears, while staying private
 * to this app. It does reset on factory reset and changes if the signing key changes (e.g. debug vs
 * release) or across user/work profiles — acceptable for a *soft* anti-abuse block-continuity signal.
 */
class AndroidDeviceIdSource(
    private val context: Context,
) : DeviceIdSource {
    override fun rawDeviceId(): String? = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
}
