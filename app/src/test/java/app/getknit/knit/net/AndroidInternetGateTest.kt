package app.getknit.knit.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo

/** The gate against Robolectric's connectivity shadows: only a validated Internet default network reads as online. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class AndroidInternetGateTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val connectivity = requireNotNull(context.getSystemService(ConnectivityManager::class.java))

    // `DEPRECATION`: Robolectric's connectivity shadows are still modelled on the pre-API-29 NetworkInfo
    // surface, so setting up an active network can only be expressed through the deprecated types.
    @Suppress("DEPRECATION")
    private val network = ShadowNetwork.newInstance(ConnectivityManager.TYPE_WIFI)

    @Suppress("DEPRECATION") // as above: ShadowNetworkInfo.newInstance takes the deprecated enums
    private fun activate(vararg capabilities: Int) = activate(ConnectivityManager.TYPE_WIFI, network, *capabilities)

    // The shadow keys its networks by the legacy type, so a second default network is a second type.
    @Suppress("DEPRECATION")
    private fun activate(
        type: Int,
        net: Network,
        vararg capabilities: Int,
    ) {
        shadowOf(connectivity).setActiveNetworkInfo(
            ShadowNetworkInfo.newInstance(
                NetworkInfo.DetailedState.CONNECTED,
                type,
                0,
                true,
                NetworkInfo.State.CONNECTED,
            ),
        )
        val caps = ShadowNetworkCapabilities.newInstance()
        capabilities.forEach { shadowOf(caps).addCapability(it) }
        val wifi = type == ConnectivityManager.TYPE_WIFI
        shadowOf(caps).addTransportType(if (wifi) NetworkCapabilities.TRANSPORT_WIFI else NetworkCapabilities.TRANSPORT_CELLULAR)
        shadowOf(connectivity).setNetworkCapabilities(net, caps)
    }

    private val validated =
        intArrayOf(
            NetworkCapabilities.NET_CAPABILITY_INTERNET,
            NetworkCapabilities.NET_CAPABILITY_VALIDATED,
            NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED,
        )

    private fun fireCapabilities(net: Network) {
        shadowOf(connectivity).networkCallbacks.forEach {
            it.onCapabilitiesChanged(net, requireNotNull(connectivity.getNetworkCapabilities(net)))
        }
    }

    @Test
    @Suppress("DEPRECATION") // ShadowNetwork keys a network by the legacy type, like the helper above
    fun theRouteKindNamesTheTransportOfAValidatedNetwork() =
        runTest(UnconfinedTestDispatcher()) {
            // The spool dialer paces its keepalive by this: a 25 s ping is free on Wi-Fi and keeps a cellular
            // modem out of idle all day.
            val gate = AndroidInternetGate(context, backgroundScope)
            activate(*validated)
            assertEquals(InternetGate.RouteKind.WIFI, gate.routeKind())

            val cellular = ShadowNetwork.newInstance(ConnectivityManager.TYPE_MOBILE)
            activate(ConnectivityManager.TYPE_MOBILE, cellular, *validated)
            assertEquals(InternetGate.RouteKind.CELLULAR, gate.routeKind())
        }

    @Test
    fun anUnvalidatedOrAbsentRouteHasNoKind() =
        runTest(UnconfinedTestDispatcher()) {
            val gate = AndroidInternetGate(context, backgroundScope)
            shadowOf(connectivity).setActiveNetworkInfo(null)
            assertEquals(InternetGate.RouteKind.NONE, gate.routeKind())
            activate(NetworkCapabilities.NET_CAPABILITY_INTERNET, NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            assertEquals("a captive portal is no route to pace a socket by", InternetGate.RouteKind.NONE, gate.routeKind())
        }

    @Test
    fun aRouteOnNeitherRadioReadsAsOther() =
        runTest(UnconfinedTestDispatcher()) {
            // A VPN: the platform does not say what it rides on, so the dialer keeps the Wi-Fi cadence.
            val gate = AndroidInternetGate(context, backgroundScope)
            activate(*validated)
            val caps = ShadowNetworkCapabilities.newInstance()
            validated.forEach { shadowOf(caps).addCapability(it) }
            shadowOf(caps).addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            shadowOf(connectivity).setNetworkCapabilities(network, caps)
            assertEquals(InternetGate.RouteKind.OTHER, gate.routeKind())
        }

    @Test
    fun noActiveNetworkReadsAsOffline() =
        runTest(UnconfinedTestDispatcher()) {
            shadowOf(connectivity).setActiveNetworkInfo(null)
            val gate = AndroidInternetGate(context, backgroundScope)
            assertFalse(gate.isOnline())
            assertNull(gate.currentNetwork())
            assertFalse(gate.online.first())
        }

    @Test
    fun aNetworkWithInternetButNotYetValidatedReadsAsOffline() =
        runTest(UnconfinedTestDispatcher()) {
            activate(NetworkCapabilities.NET_CAPABILITY_INTERNET, NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            val gate = AndroidInternetGate(context, backgroundScope)
            assertFalse("a captive portal claims Internet it does not deliver", gate.isOnline())
        }

    @Test
    fun aValidatedInternetNetworkReadsAsOnlineAndIsHandedOutForBinding() =
        runTest(UnconfinedTestDispatcher()) {
            activate(
                NetworkCapabilities.NET_CAPABILITY_INTERNET,
                NetworkCapabilities.NET_CAPABILITY_VALIDATED,
                NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED,
            )
            val gate = AndroidInternetGate(context, backgroundScope)
            assertTrue(gate.isOnline())
            assertTrue(gate.currentNetwork() != null)
            assertTrue(gate.online.first())
            assertFalse("no Data Saver by default", gate.isDataRestricted())
        }

    @Test
    fun aRestrictedOrAwareOnlyNetworkReadsAsOffline() =
        runTest(UnconfinedTestDispatcher()) {
            // A fresh NetworkCapabilities already carries the platform defaults (NOT_RESTRICTED among them), so the
            // restricted case has to be made, not merely left out.
            activate(NetworkCapabilities.NET_CAPABILITY_INTERNET, NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            val restricted = requireNotNull(connectivity.getNetworkCapabilities(network))
            shadowOf(restricted).removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            shadowOf(connectivity).setNetworkCapabilities(network, restricted)
            assertFalse("NOT_RESTRICTED is required", AndroidInternetGate(context, backgroundScope).isOnline())
            val aware = ShadowNetworkCapabilities.newInstance()
            listOf(
                NetworkCapabilities.NET_CAPABILITY_INTERNET,
                NetworkCapabilities.NET_CAPABILITY_VALIDATED,
                NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED,
            ).forEach { shadowOf(aware).addCapability(it) }
            shadowOf(aware).addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
            shadowOf(connectivity).setNetworkCapabilities(network, aware)
            assertFalse("the mesh's own link is never a route to the Internet", AndroidInternetGate(context, backgroundScope).isOnline())
        }

    @Test
    fun theStreamFollowsTheDefaultNetworksCallbacks() =
        runTest(UnconfinedTestDispatcher()) {
            shadowOf(connectivity).setActiveNetworkInfo(null)
            val gate = AndroidInternetGate(context, backgroundScope)
            val seen = ArrayList<Boolean>()
            val job = backgroundScope.launch { gate.online.collect { seen += it } }
            assertTrue(shadowOf(connectivity).networkCallbacks.isNotEmpty())
            activate(
                NetworkCapabilities.NET_CAPABILITY_INTERNET,
                NetworkCapabilities.NET_CAPABILITY_VALIDATED,
                NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED,
            )
            shadowOf(connectivity).networkCallbacks.forEach {
                it.onCapabilitiesChanged(network, requireNotNull(connectivity.getNetworkCapabilities(network)))
            }
            assertTrue(seen.last())
            shadowOf(connectivity).setActiveNetworkInfo(null)
            shadowOf(connectivity).networkCallbacks.forEach { it.onLost(network) }
            assertFalse(seen.last())
            job.cancel()
        }

    @Test
    @Suppress("DEPRECATION") // as above: the shadow keys a second network by the deprecated legacy type
    fun aRouteChangeIsOnlyEverANewValidatedNetwork() =
        runTest(UnconfinedTestDispatcher()) {
            // Work item 50: the Internet plane re-dials on a *new* default network, so the stream must stay
            // quiet for everything that is not one — the same Wi-Fi re-validating or changing signal, and the
            // loss of the route — or a relay in backoff would be dialled on every capability flicker.
            activate(*validated)
            val gate = AndroidInternetGate(context, backgroundScope)
            var changes = 0
            val online = backgroundScope.launch { gate.online.collect { } }
            val routes = backgroundScope.launch { gate.routeChanges.collect { changes++ } }
            assertEquals("one callback serves both readers", 1, shadowOf(connectivity).networkCallbacks.size)
            assertEquals("subscribing is not an event", 0, changes)

            fireCapabilities(network)
            fireCapabilities(network)
            assertEquals("the same network again is nothing", 0, changes)

            shadowOf(connectivity).setActiveNetworkInfo(null)
            shadowOf(connectivity).networkCallbacks.forEach { it.onLost(network) }
            assertEquals("losing the route is nothing to dial on", 0, changes)

            val cellular = ShadowNetwork.newInstance(ConnectivityManager.TYPE_MOBILE)
            activate(ConnectivityManager.TYPE_MOBILE, cellular, *validated)
            shadowOf(connectivity).networkCallbacks.forEach { it.onAvailable(cellular) }
            fireCapabilities(cellular)
            assertEquals("a different validated network is exactly one event", 1, changes)
            fireCapabilities(cellular)
            assertEquals(1, changes)

            online.cancel()
            routes.cancel()
        }
}
