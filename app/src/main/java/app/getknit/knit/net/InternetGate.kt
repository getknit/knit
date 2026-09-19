package app.getknit.knit.net

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Whether this phone has a route to the Internet right now — the question the mesh never had to ask, and
 * the one thing a link-preview fetch must know before it opens a socket.
 *
 * "Online" means a **validated** default network: one the platform has confirmed reaches the Internet, not
 * merely a Wi-Fi association. A phone that is only on the mesh sits on a Wi-Fi Aware network with no upstream,
 * and a captive portal advertises Internet it does not provide; both read as offline here. The Android
 * implementation (`AndroidInternetGate`) is the one `ConnectivityManager` user outside the NAN data path
 * (`rules/mesh.md`); this seam keeps everything above it fakeable on the JVM.
 */
interface InternetGate {
    /** A snapshot, re-read before every fetch: true when a validated Internet route exists this instant. */
    fun isOnline(): Boolean

    /**
     * True when the system Data Saver restricts this app on a metered network. A preview is a convenience the
     * user never asked for byte by byte, so it defers to that setting even though a foreground app could
     * technically ignore it.
     */
    fun isDataRestricted(): Boolean

    /** [isOnline] as a stream, so work skipped while offline can be re-armed when a route appears. */
    val online: StateFlow<Boolean>

    /**
     * What kind of route [isOnline] is answering about — the one thing a long-lived socket needs to pace
     * its keepalive by: a ping that costs a Wi-Fi radio nothing keeps a cellular modem out of idle all day.
     * A VPN reads [RouteKind.OTHER]; the platform does not expose what it rides on.
     */
    enum class RouteKind { NONE, WIFI, CELLULAR, OTHER }

    /** The route's kind this instant; the default only knows whether there is one. */
    fun routeKind(): RouteKind = if (isOnline()) RouteKind.OTHER else RouteKind.NONE

    /**
     * One event each time the validated default network becomes a *different* network — never for the
     * same one re-validating or changing capabilities, and never for losing it. A socket dialled over the
     * old route says nothing about the new one, so a plane sitting out a reconnect backoff against a
     * route that swallowed its socket can dial again the moment the phone has left it. Defaulted to
     * silence so a fake need not model it.
     */
    val routeChanges: Flow<Unit> get() = emptyFlow()
}
