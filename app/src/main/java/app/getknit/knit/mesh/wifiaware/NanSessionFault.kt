package app.getknit.knit.mesh.wifiaware

import android.os.Build

/**
 * What a throw out of a Wi-Fi Aware session call (`publish` / `subscribe`) means — pure and JVM-tested,
 * because the two shapes want opposite responses and the transport used to give both the same one.
 *
 * - [DeadSession]: the framework-side client is gone (`SecurityException: Attempting to use invalid
 *   uid+clientId mapping` when NAN cycled under us, or anything we do not recognise). The only recovery is
 *   the one `WifiAwareTransport.onSessionDead` already does: drop the session and re-attach.
 * - [OffScreenLocation]: `WifiPermissionsUtil.enforceLocationPermission` refused us — `SecurityException:
 *   UID … does not have Coarse/Fine Location permission` — which on API 29-32 means the location **app-op**
 *   is foreground-only and Knit is off screen, not that the grant went away. The session, the publish and
 *   the responder are all still good (none of those calls is location-gated), so tearing them down and
 *   re-attaching only makes a blind node that was at least serviceable into one that is neither, forty-five
 *   times in four minutes on the lab Pixel 3 (work item #62). The transport latches
 *   `TransportHealth.ForegroundOnly` and retries on the next `heal()` instead. ADR 2026-09.535d.
 *
 * Gated on the tier, not just the text: from API 33 discovery rides `NEARBY_WIFI_DEVICES` and a location
 * refusal cannot mean "off screen", so it stays a dead session there whatever the message says.
 */
enum class NanSessionFault {
    DeadSession,
    OffScreenLocation,
    ;

    companion object {
        fun classify(
            cause: Throwable,
            sdkInt: Int = Build.VERSION.SDK_INT,
        ): NanSessionFault {
            if (sdkInt >= Build.VERSION_CODES.TIRAMISU) return DeadSession
            if (cause !is SecurityException) return DeadSession
            val message = cause.message ?: return DeadSession
            return if (message.contains("location", ignoreCase = true)) OffScreenLocation else DeadSession
        }
    }
}
