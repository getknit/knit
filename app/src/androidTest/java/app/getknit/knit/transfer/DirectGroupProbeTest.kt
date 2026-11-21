package app.getknit.knit.transfer

import android.Manifest
import android.net.wifi.WifiManager
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Where this phone's platform puts each kind of Wi-Fi Direct group a host can ask for (ADR 2026-11.8zm5), asked of the
 * phone itself. A group owner needs no peer, so one phone answers what the JVM cannot: whether `getUsableChannels`
 * says, whether `setGroupOperatingFrequency` is honoured, how a frequency the phone will not own is refused (at once, or
 * only by the timeout, which a call's direct leg cannot afford), and whether `GROUP_OWNER_BAND_AUTO` follows the phone's
 * own Wi-Fi. Every probe logs one `KnitProbe` line, so a farm run's logcat reads as a table across phones.
 *
 * It runs on Device Farm phones from an unseeded build (`TEST_TARGETS="package app.getknit.knit.transfer"`), never on
 * CI's emulators, which have no Wi-Fi Direct: this package is in no pass of `ci.yml`. A phone needs Wi-Fi on, and
 * location on below API 33, where P2P is gated on it; neither skips, since a skip reads green. It skips in a seeded build
 * (`BuildConfig.SEED_DEMO`, testing.md's I3): the seeded suites' default filter (`notPackage app.getknit.knit.uiauto`)
 * takes in this package, and their runs hunt UI on phones, not radios. The unseeded passes name their packages, so only
 * the probe's own run reaches it, and that run never skips here. The one probe that skips says why in the report, and
 * only where the phone cannot answer the question. Below API 34 a phone cannot say which frequencies it can own, so
 * there the pin and `Auto` probes log where the group formed without asserting it.
 *
 * The groups are named `DIRECT-pr-…`, which is not ours (`GroupCredentials.isOurs`), so the app's start-up sweep (8 s
 * into this very process, `KnitApplication.SWEEP_DELAY_MS`) never removes one under a probe. [release] removes it.
 */
@RunWith(AndroidJUnit4::class)
class DirectGroupProbeTest {
    @get:Rule
    val grant: GrantPermissionRule =
        GrantPermissionRule.grant(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.NEARBY_WIFI_DEVICES
            } else {
                Manifest.permission.ACCESS_FINE_LOCATION
            },
        )

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    // A transport that never touches a radio: no MeshService runs in this process, so there is no Wi-Fi Aware to pause.
    private val radio = AndroidDirectWifi(context, DemoTransport(MutableStateFlow(emptySet())))

    @Before
    fun thePhoneCanHost() {
        assumeFalse("a seeded build runs the UI suites; the probe runs from an unseeded one", BuildConfig.SEED_DEMO)
        val refusal = radio.refusal()
        assertNull("Wi-Fi Direct is not usable on this phone: $refusal", refusal)
    }

    @After
    fun release() = runBlocking { radio.release() }

    @Test
    fun thePlatformSaysWhereThisPhoneCanJoinAndOwnAGroup() {
        val view = radio.channels()
        log("channels sta=${view.sta} client=${view.client?.sorted()} go=${view.go?.sorted()} mask=${DirectChannels.mask(view.client)}")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // No getUsableChannels below 34: this phone's ACCEPT carries no `ch`, and as a host it cannot list its own.
            assertNull(view.client)
            assertNull(view.go)
            return
        }
        // What a receiver sends as `ch`, and what a host intersects it with. A phone that answers null here sends no `ch`.
        val client = view.client ?: throw AssertionError("API 34+, and the platform did not list the client frequencies")
        val go = view.go ?: throw AssertionError("API 34+, and the platform did not list the group-owner frequencies")
        assertTrue("no 2.4 GHz frequency to join a group on: $client", client.any(::is24))
        assertTrue("no 2.4 GHz frequency to own a group on: $go", go.any(::is24))
        assertNotNull("the table names none of the client frequencies $client", DirectChannels.mask(client))
    }

    @Test
    fun aGroupPinnedToAFrequencyThePhoneCanOwnFormsOnIt() {
        val view = radio.channels()
        val target = pinnable(view)
        val (group, ms) = host(GroupChannel.Frequency(target))
        log("pinned $target -> freq=${group.frequencyMhz} in ${ms}ms (sta=${view.sta})")
        // Asserted only where the phone listed [target] as one it can own (API 34+); below that it is a measurement.
        if (view.go?.contains(target) == true && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            assertEquals("asked for $target; the group formed elsewhere (or on the 2.4 GHz retry)", target, group.frequencyMhz)
        }
    }

    @Test
    fun aFrequencyThePhoneWillNotOwnCostsOneTryAndFormsAGroupOnTwoPointFour() {
        val go = radio.channels().go
        assumeTrue("below API 34 the phone does not say which frequencies it will not own", go != null)
        // The table's 5 GHz frequencies this phone does not list as ownable: a DFS channel, on most.
        val refused = TABLE.firstOrNull { it >= BAND_5_LOW && it !in go.orEmpty() }
        assumeTrue("this phone can own a group on every 5 GHz frequency the table names", refused != null)
        val asked = checkNotNull(refused)
        val (group, ms) = host(GroupChannel.Frequency(asked))
        val path = if (group.frequencyMhz == asked) "honoured anyway" else "moved"
        log("refused $asked -> freq=${group.frequencyMhz} in ${ms}ms ($path)")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            assertTrue("asked for $asked, formed on ${group.frequencyMhz}", group.frequencyMhz == asked || is24(group.frequencyMhz))
        }
        // What the call's direct leg can afford: its callee stops joining about 20 s after its RINGING
        // (DirectTimings.joinWindowMs), so a refusal the platform reports only by the timeout brings the 2.4 GHz group up
        // too late for a call. A transfer's receiver joins for 75 s and still finds it.
        assertTrue(
            "the pinned try took ${ms}ms: this phone refuses a frequency only by the timeout",
            group.frequencyMhz == asked || ms < DirectTimings().groupUpMs,
        )
    }

    @Test
    fun aGroupOnTwoPointFourFormsThere() {
        val (group, ms) = host(GroupChannel.Band24)
        log("band24 -> freq=${group.frequencyMhz} in ${ms}ms")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            assertTrue("asked for 2.4 GHz, formed on ${group.frequencyMhz}", is24(group.frequencyMhz))
        }
    }

    @Test
    fun aGroupLeftToThePlatformFollowsThePhonesOwnWifi() {
        val view = radio.channels()
        val (group, ms) = host(GroupChannel.Auto)
        log("auto -> freq=${group.frequencyMhz} in ${ms}ms (sta=${view.sta})")
        val sta = view.sta
        // The premise `Auto` rests on (8zm5): beside a peer that can join there, the group shares the station's channel,
        // and with it single-channel speed. A miss here costs speed, not the join. Asserted only where the phone lists
        // its station's frequency as one it can own a group on (API 34+).
        if (sta != null && view.go?.contains(sta) == true && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            assertEquals("the group did not follow this phone's Wi-Fi", sta, group.frequencyMhz)
        }
    }

    // What a host on DFS pins for a peer that did not say (DirectChannels.choose): the best 5 GHz frequency off DFS the
    // phone can own. Below 34 the phone cannot list them, and 5180 (UNII-1) is the one every 5 GHz region allows.
    private fun pinnable(view: DirectChannelView): Int {
        val wifi = context.getSystemService(WifiManager::class.java)
        if (wifi?.is5GHzBandSupported != true) return CHANNEL_6
        if (view.go == null) return UNII_1_LOW
        val pick = DirectChannels.choose(hostSta = DFS_STATION, client = null, hostGo = view.go).channel
        return (pick as? GroupChannel.Frequency)?.mhz ?: CHANNEL_6
    }

    private fun host(channel: GroupChannel): Pair<HostedGroup, Long> =
        runBlocking {
            val started = System.nanoTime()
            val group =
                try {
                    radio.hostOn(credentials(), DirectTimings().groupUpMs, peerChannels = null, forced = channel)
                } catch (e: DirectWifiException) {
                    // Below API 33 the platform gates Wi-Fi Direct on location being on, and says only ERROR when it is off.
                    val hint = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) " (below API 33: is location on?)" else ""
                    throw AssertionError("no group on $channel: ${e.message}$hint", e)
                }
            group to (System.nanoTime() - started) / NANOS_PER_MS
        }

    private fun credentials(): GroupCredentials {
        val random = GroupCredentials.random()
        return random.copy(ssid = "${GroupCredentials.SSID_PREFIX}pr-${random.ssid.takeLast(PROBE_TAIL_CHARS)}")
    }

    private fun is24(mhz: Int): Boolean = mhz in BAND_24

    private fun log(line: String) {
        Log.i(TAG, "${Build.MANUFACTURER} ${Build.MODEL} API ${Build.VERSION.SDK_INT}: $line")
    }

    private companion object {
        const val TAG = "KnitProbe"
        const val NANOS_PER_MS = 1_000_000L
        const val PROBE_TAIL_CHARS = 8
        const val CHANNEL_6 = 2437
        const val UNII_1_LOW = 5180
        const val DFS_STATION = 5500
        const val BAND_5_LOW = 5000
        val BAND_24 = 2400..2500

        /** Every frequency `DirectChannels`' table names: a mask with every bit set, read back. */
        val TABLE: List<Int> = DirectChannels.freqs(-1L).orEmpty().sorted()
    }
}
