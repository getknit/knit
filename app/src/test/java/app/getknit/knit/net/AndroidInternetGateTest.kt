package app.getknit.knit.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
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
    private val network = ShadowNetwork.newInstance(ConnectivityManager.TYPE_WIFI)

    private fun activate(vararg capabilities: Int) {
        shadowOf(connectivity).setActiveNetworkInfo(
            ShadowNetworkInfo.newInstance(
                NetworkInfo.DetailedState.CONNECTED,
                ConnectivityManager.TYPE_WIFI,
                0,
                true,
                NetworkInfo.State.CONNECTED,
            ),
        )
        val caps = ShadowNetworkCapabilities.newInstance()
        capabilities.forEach { shadowOf(caps).addCapability(it) }
        shadowOf(caps).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        shadowOf(connectivity).setNetworkCapabilities(network, caps)
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
}
