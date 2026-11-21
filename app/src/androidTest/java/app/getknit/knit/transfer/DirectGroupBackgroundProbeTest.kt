package app.getknit.knit.transfer

import android.Manifest
import android.app.ActivityManager
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import app.getknit.knit.BuildConfig
import app.getknit.knit.call.DirectTimings
import app.getknit.knit.mesh.DemoTransport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What a phone below API 33 says when a group is asked for with nothing of Knit's on screen: the sender hosts when the
 * receiver's ACCEPT lands, which may be minutes after the person left the app. There the platform gates Wi-Fi Direct on
 * the foreground-only location AppOp and answers only `ERROR`, and `AndroidDirectWifi.backgrounded` turns that into
 * [TransferRefusal.Background] ("Keep Knit on screen"), not [TransferRefusal.NoWifiDirect]. A Galaxy A51 on Android 10
 * refused every group this way on Device Farm (2026-11-21), on the one level the mapping then left out.
 *
 * [DirectGroupProbeTest]'s sibling, run the same way (`TEST_TARGETS="package app.getknit.knit.transfer"`, an unseeded
 * build, Device Farm phones only), apart from it because that class holds a screen up below API 33 and this one must
 * not. It skips on API 33+, which gates on `NEARBY_WIFI_DEVICES` instead, and on a phone that hosts with nothing on
 * screen: neither can answer the question. The group, should one form, is `DIRECT-pr-…`, out of the app's sweep.
 */
@RunWith(AndroidJUnit4::class)
class DirectGroupBackgroundProbeTest {
    @get:Rule
    val grant: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.ACCESS_FINE_LOCATION)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val radio = AndroidDirectWifi(context, DemoTransport(MutableStateFlow(emptySet())))

    @Before
    fun aPhoneBelowThirtyThreeThatCanHost() {
        assumeFalse("a seeded build runs the UI suites; the probe runs from an unseeded one", BuildConfig.SEED_DEMO)
        val locationGated = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
        assumeTrue("API 33+ gates Wi-Fi Direct on NEARBY_WIFI_DEVICES, not on location", locationGated)
        val refusal = radio.refusal()
        assertNull("Wi-Fi Direct is not usable on this phone: $refusal", refusal)
    }

    @After
    fun release() = runBlocking { radio.release() }

    @Test
    fun withNothingOnScreenAGroupIsRefusedAsBackgroundNotAsNoWifiDirect() {
        val state = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(state)
        val refused =
            runBlocking {
                try {
                    radio.hostOn(credentials(), DirectTimings().groupUpMs, peerChannels = null, forced = GroupChannel.Auto)
                    null
                } catch (e: DirectWifiException) {
                    e
                }
            }
        log("no screen (importance ${state.importance}) -> ${refused?.refusal} (${refused?.message})")
        assumeTrue("this phone hosted a group with nothing of Knit's on screen", refused != null)
        assertEquals("the refusal a sender would see", TransferRefusal.Background, checkNotNull(refused).refusal)
    }

    private fun credentials(): GroupCredentials {
        val random = GroupCredentials.random()
        return random.copy(ssid = "${GroupCredentials.SSID_PREFIX}pr-${random.ssid.takeLast(PROBE_TAIL_CHARS)}")
    }

    private fun log(line: String) {
        Log.i(TAG, "${Build.MANUFACTURER} ${Build.MODEL} API ${Build.VERSION.SDK_INT}: $line")
    }

    private companion object {
        const val TAG = "KnitProbe"
        const val PROBE_TAIL_CHARS = 8
    }
}
