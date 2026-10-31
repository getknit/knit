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

    /**
     * Whether this phone's current default network is metered: true unless the platform says it is not
     * (`NET_CAPABILITY_NOT_METERED`), so no network at all reads as metered too. The one judgement of "the person
     * pays for these bytes" that a transfer the person did not tap for must defer to — a contact's direct link
     * fetching an attachment over the Bluetooth download limit (ADR 2026-10.js2p, a5yj) today, and any later
     * cellular gate (a Direct Send) — so it is a seam of its own rather than a rule inside one plane.
     *
     * Not `TranslateModelStore.metered()` / `SttModelStore.metered()`, which ask "cellular, or Data Saver on" to word
     * a model download's prompt, and stay as they are. A carrier's temporary unmetering
     * (`NET_CAPABILITY_TEMPORARILY_NOT_METERED`, set and cleared on some 5G plans without a network change) does not
     * count: the rule names a network defined as metered. With a VPN up, the default network is the VPN's, which reads
     * metered unless its app calls `VpnService.Builder.setMetered(false)`: over such a VPN a fast-only want waits for the
     * tap — conservative, accepted for now, a device check owed. A snapshot, cheap and non-blocking; the default (every
     * fake, every build without the platform gate) says no.
     */
    fun isMetered(): Boolean = false
}
