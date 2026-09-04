package app.getknit.knit.net

import kotlinx.coroutines.flow.StateFlow

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
}
