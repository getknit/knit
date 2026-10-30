package app.getknit.knit.ui.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.webrtc.NetworkChangeDetector.ConnectionType
import org.webrtc.PeerConnection.AdapterType

class DefaultPathTest {
    @Test
    fun aNetworkIsPreferredByTheTypeLibwebrtcItselfGivesIt() {
        assertEquals(AdapterType.WIFI, DefaultPath.adapterFor(ConnectionType.CONNECTION_WIFI))
        // Every generation is plain CELLULAR: the JNI aborts on CELLULAR_4G and its kin (a Play Console crash).
        listOf(
            ConnectionType.CONNECTION_5G,
            ConnectionType.CONNECTION_4G,
            ConnectionType.CONNECTION_3G,
            ConnectionType.CONNECTION_2G,
            ConnectionType.CONNECTION_UNKNOWN_CELLULAR,
        ).forEach { assertEquals(AdapterType.CELLULAR, DefaultPath.adapterFor(it)) }
    }

    @Test
    fun everyPreferenceIsOneTheJniParses() {
        // sdk/android/src/jni/pc/ice_candidate.cc JavaToNativeNetworkPreference: RTC_CHECK(false) on any other name.
        val parsed =
            setOf(
                AdapterType.UNKNOWN,
                AdapterType.ETHERNET,
                AdapterType.WIFI,
                AdapterType.CELLULAR,
                AdapterType.VPN,
                AdapterType.LOOPBACK,
            )
        ConnectionType.entries.forEach { type ->
            ConnectionType.entries.forEach { under ->
                DefaultPath.adapterFor(type, under)?.let { assertTrue("$type/$under prefers $it", it in parsed) }
            }
        }
    }

    @Test
    fun aVpnIsPreferredByTheNetworkUnderItAndNothingUnknownIsPreferred() {
        assertEquals(AdapterType.WIFI, DefaultPath.adapterFor(ConnectionType.CONNECTION_VPN, ConnectionType.CONNECTION_WIFI))
        assertNull(DefaultPath.adapterFor(ConnectionType.CONNECTION_VPN, ConnectionType.CONNECTION_VPN))
        assertNull(DefaultPath.adapterFor(ConnectionType.CONNECTION_NONE))
        assertNull(DefaultPath.adapterFor(ConnectionType.CONNECTION_BLUETOOTH))
    }
}
