package app.getknit.knit.ui.call

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import org.webrtc.NetworkChangeDetector.ConnectionType
import org.webrtc.PeerConnection.AdapterType

/**
 * The network Android routes the phone's own traffic over, as the adapter type a call should prefer
 * (`RTCConfiguration.networkPreference`). libwebrtc ranks a working pair on that network ahead of every other
 * consideration but whether it works — ahead of network cost and candidate priority — so a call follows Android's
 * own judgement: Wi-Fi when Android routes over it, cellular while the Wi-Fi is unvalidated (a captive portal not
 * yet passed, no internet) or while the platform has moved off a Wi-Fi it judges bad. A preference, not a filter:
 * the other networks still gather, and win when theirs is the only pair that works (a LAN with no internet).
 *
 * The type is read from libwebrtc's own network list ([DirectNetworkDetector.current]) for the default network's
 * handle. Every cellular generation is preferred as plain `CELLULAR`: the JNI parses `networkPreference` by name and
 * aborts the process on any but UNKNOWN, ETHERNET, WIFI, CELLULAR, VPN and LOOPBACK (`RTC_CHECK`, so a `CELLULAR_4G`
 * killed every call that met an LTE default), and without the `WebRTC-SurfaceCellularTypes` field trial libwebrtc
 * types each cellular network `CELLULAR` itself, which the comparison needs. A VPN is preferred by the network under it.
 */
internal class DefaultPath(
    context: Context,
) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    /** The preferred adapter for the default network now, or null while it is unknown or not one libwebrtc lists. */
    fun preference(): AdapterType? = connectivity?.activeNetwork?.let(::preferenceFor)

    fun preferenceFor(network: Network): AdapterType? {
        val info = DirectNetworkDetector.current?.networks()?.firstOrNull { it.handle == network.networkHandle } ?: return null
        return adapterFor(info.type, info.underlyingTypeForVpn)
    }

    /** Calls [onChange] with every new default network for as long as the returned handle is not closed. */
    fun watch(onChange: (Network) -> Unit): AutoCloseable {
        val manager = connectivity ?: return AutoCloseable {}
        val callback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = onChange(network)
            }
        runCatching { manager.registerDefaultNetworkCallback(callback) }.onFailure { return AutoCloseable {} }
        return AutoCloseable { runCatching { manager.unregisterNetworkCallback(callback) } }
    }

    companion object {
        /**
         * Whether calls follow Android's default network at all — on unless the bridge's `call op=pathpref` turned it
         * off for an A/B. In memory, read when a call opens and on each default-network change.
         */
        @Volatile
        var enabled: Boolean = true

        /**
         * The preference for a network libwebrtc lists as [type] (a VPN by [underlying]); null for none to prefer. Only
         * a type the JNI parses: any other aborts `setConfiguration` and `createPeerConnection`.
         */
        fun adapterFor(
            type: ConnectionType,
            underlying: ConnectionType = ConnectionType.CONNECTION_NONE,
        ): AdapterType? =
            when (type) {
                ConnectionType.CONNECTION_WIFI -> AdapterType.WIFI

                ConnectionType.CONNECTION_ETHERNET -> AdapterType.ETHERNET

                ConnectionType.CONNECTION_5G,
                ConnectionType.CONNECTION_4G,
                ConnectionType.CONNECTION_3G,
                ConnectionType.CONNECTION_2G,
                ConnectionType.CONNECTION_UNKNOWN_CELLULAR,
                -> AdapterType.CELLULAR

                ConnectionType.CONNECTION_VPN -> if (underlying == ConnectionType.CONNECTION_VPN) null else adapterFor(underlying)

                else -> null
            }
    }
}
