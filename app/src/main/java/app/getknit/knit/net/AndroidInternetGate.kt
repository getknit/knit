package app.getknit.knit.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn

/**
 * The [InternetGate] over Android's default network — the one `ConnectivityManager` user outside
 * `mesh/wifiaware/` (`rules/mesh.md`), and deliberately a narrow one: it asks the platform whether the
 * **default** network is a validated route to the Internet and hands that [Network] out for a fetch to bind
 * to. It never requests a network and never calls `bindProcessToNetwork`, which is process-global and would
 * drag the mesh's own sockets onto whatever the default happens to be.
 *
 * "Validated" is the platform's word: `NET_CAPABILITY_VALIDATED` is set only once Android has confirmed the
 * network reaches the Internet, so a captive portal (Internet claimed, not delivered) and a Wi-Fi Aware link
 * (no upstream at all; excluded again by transport, belt and braces) both read as offline. Data Saver is
 * checked explicitly rather than through the blocked-status callback, which reports a foreground app as
 * unblocked — a preview is a convenience, and the user's byte preference wins.
 *
 * [online] and [routeChanges] share one network callback, registered only while either is collected and
 * dropped when the last collector leaves, so a phone that never turns link previews or relays on never
 * registers one. [isOnline] is the per-fetch snapshot and needs no subscription at all.
 */
class AndroidInternetGate(
    context: Context,
    scope: CoroutineScope,
) : InternetGate {
    private val connectivity: ConnectivityManager? = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    /** The validated default network this instant, for a fetch to bind its sockets and lookups to, or null. */
    fun currentNetwork(): Network? {
        val cm = connectivity ?: return null
        val network = cm.activeNetwork ?: return null
        val capabilities = cm.getNetworkCapabilities(network) ?: return null
        return network.takeIf { reachesTheInternet(capabilities) }
    }

    override fun isOnline(): Boolean = currentNetwork() != null

    override fun routeKind(): InternetGate.RouteKind {
        val cm = connectivity ?: return InternetGate.RouteKind.NONE
        val network = cm.activeNetwork ?: return InternetGate.RouteKind.NONE
        val capabilities = cm.getNetworkCapabilities(network) ?: return InternetGate.RouteKind.NONE
        if (!reachesTheInternet(capabilities)) return InternetGate.RouteKind.NONE
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> InternetGate.RouteKind.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> InternetGate.RouteKind.CELLULAR
            else -> InternetGate.RouteKind.OTHER
        }
    }

    override fun isDataRestricted(): Boolean {
        val cm = connectivity ?: return false
        return cm.isActiveNetworkMetered && cm.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
    }

    /**
     * The validated default network as a stream, re-read from the snapshot on every callback rather than
     * trusting the callback's own arguments: VALIDATED usually lands in a later onCapabilitiesChanged than
     * the onAvailable that announced the network, and onLost may precede the next default's onAvailable.
     * One registered callback serves both [online] and [routeChanges]; it is registered while either has
     * a collector and dropped [STOP_TIMEOUT_MS] after the last one leaves.
     */
    private val network: SharedFlow<Network?> =
        callbackFlow {
            val cm = connectivity
            if (cm == null) {
                trySend(null)
                awaitClose { }
                return@callbackFlow
            }
            val callback =
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        trySend(currentNetwork())
                    }

                    override fun onCapabilitiesChanged(
                        network: Network,
                        networkCapabilities: NetworkCapabilities,
                    ) {
                        trySend(currentNetwork())
                    }

                    override fun onLost(network: Network) {
                        trySend(currentNetwork())
                    }

                    override fun onBlockedStatusChanged(
                        network: Network,
                        blocked: Boolean,
                    ) {
                        trySend(currentNetwork())
                    }
                }
            // The registration can throw (too many callbacks in the process, or a build missing the permission);
            // the snapshot below still answers, and the stream simply stays at that value.
            val registered = runCatching { cm.registerDefaultNetworkCallback(callback) }.isSuccess
            trySend(currentNetwork())
            awaitClose { if (registered) runCatching { cm.unregisterNetworkCallback(callback) } }
        }.shareIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), replay = 1)

    override val online: StateFlow<Boolean> =
        network
            .map { it != null }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), isOnline())

    // `Network` equality is its netId, so the same Wi-Fi re-validating or changing signal collapses under
    // `distinctUntilChanged`; the replayed current network is dropped so subscribing is never an event.
    override val routeChanges: Flow<Unit> =
        network
            .distinctUntilChanged()
            .drop(1)
            .filterNotNull()
            .map { }

    private fun reachesTheInternet(capabilities: NetworkCapabilities): Boolean =
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED) &&
            !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI_AWARE)

    private companion object {
        /** How long the callback outlives the last collector, so a screen rotation does not re-register it. */
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
