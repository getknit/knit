package app.getknit.knit.mesh.lora

import app.getknit.knit.data.settings.SettingsStore
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pushed sibling of `RelayStatusRepository`: settings + the transport's live status folded into the
 * facts the chat header reads. Runs against the debug build, where `BuildConfig.LORA_PLANE` is on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LoraStatusRepositoryTest {
    private val enabled = MutableStateFlow(true)
    private val address = MutableStateFlow<String?>("AA:BB:CC:DD:EE:FF")
    private val dms = MutableStateFlow(true)
    private val status = MutableStateFlow(LoraStatus())
    private val settings =
        mockk<SettingsStore> {
            every { loraEnabled } returns enabled
            every { loraDeviceAddress } returns address
            every { loraDmEnabled } returns dms
        }
    private val lora =
        object : LoraPlaneStatus {
            override val status: StateFlow<LoraStatus> = this@LoraStatusRepositoryTest.status

            override suspend fun provisionKnitChannel(
                mode: ProvisionMode,
                previous: BoardSettings?,
            ): ProvisionResult = ProvisionResult.NotReady(LinkState.Idle)
        }
    private val repo = LoraStatusRepository(settings, lora)
    private val ready =
        LinkState.Ready(
            board = BoardInfo(myNodeNum = 7u, pioEnv = "heltec-v4", firmwareVersion = "2.5.0"),
            channels = emptyList(),
            mtu = 512,
        )

    @Test
    fun `facts follow the link and the switches`() =
        runTest {
            // Bound but idle: the board is expected to be working, so the glyph is drawn struck through.
            assertEquals(LoraFacts(LoraPlane.Down, dms = true), repo.facts.first())

            status.value = LoraStatus(state = ready)
            assertEquals(LoraFacts(LoraPlane.Live, dms = true, canPost = true), repo.facts.first())

            dms.value = false
            assertEquals(LoraFacts(LoraPlane.Live, dms = false, canPost = true), repo.facts.first())

            // Off outranks a still-ready link, and the DM switch reads false once the plane is off.
            dms.value = true
            enabled.value = false
            assertEquals(LoraFacts(LoraPlane.Off, dms = false), repo.facts.first())
        }

    @Test
    fun `status churn that leaves the facts unchanged emits nothing`() =
        runTest {
            val seen = mutableListOf<LoraFacts>()
            val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { repo.facts.collect { seen += it } }

            status.value = LoraStatus(state = LinkState.Connecting) // still Down
            status.value = LoraStatus(state = LinkState.Connecting, heard = 3) // still Down — a peer count is not the header's business
            status.value = LoraStatus(state = ready)

            assertEquals(listOf(LoraFacts(LoraPlane.Down, dms = true), LoraFacts(LoraPlane.Live, dms = true, canPost = true)), seen)
            job.cancel()
        }

    @Test
    fun `a spent airtime window rides the facts only while the link is live`() =
        runTest {
            val spent =
                AirtimeSnapshot(
                    ModemPreset.LONG_FAST,
                    LoraRegion.OTHER,
                    known = true,
                    liveUsedMs = 40_000,
                    liveBudgetMs = 45_000,
                    bridgeUsedMs = 1_000,
                    bridgeBudgetMs = 13_500,
                    bootstrapUsedMs = 0,
                    bootstrapBudgetMs = 11_250,
                )
            val roomy = spent.copy(liveUsedMs = 20_000)
            status.value = LoraStatus(state = LinkState.Idle, airtime = spent)
            assertEquals(LoraFacts(LoraPlane.Down, dms = true), repo.facts.first())
            status.value = LoraStatus(state = ready, airtime = spent)
            assertEquals(LoraFacts(LoraPlane.Live, dms = true, airtimeSpent = true, canPost = true), repo.facts.first())
            // Live and bridge spending count together, against the live budget — as the governor admits them.
            status.value = LoraStatus(state = ready, airtime = roomy)
            assertEquals(LoraFacts(LoraPlane.Live, dms = true, airtimeSpent = false, canPost = true), repo.facts.first())
        }

    @Test
    fun `the primary channel's name and the posting verdict ride the facts only while the link is live`() =
        runTest {
            val radio =
                LoraRadioConfig(
                    usePreset = true,
                    modemPreset = ModemPreset.MEDIUM_FAST,
                    region = LoraRegion.US,
                    hopLimit = 3,
                    overrideDutyCycle = false,
                )
            val stock = ready.copy(channels = listOf(ChannelInfo(0, "", role = 1)), radio = radio)
            status.value = LoraStatus(state = LinkState.Idle)
            assertEquals(LoraFacts(LoraPlane.Down, dms = true), repo.facts.first())
            status.value = LoraStatus(state = stock)
            assertEquals(LoraFacts(LoraPlane.Live, dms = true, primaryChannel = "MediumFast", canPost = true), repo.facts.first())

            // A named slot 0 is called what the user called it.
            status.value = LoraStatus(state = stock.copy(channels = listOf(ChannelInfo(0, "Ridge", role = 1))))
            assertEquals("Ridge", repo.facts.first().primaryChannel)

            // Knit itself at slot 0 — the lab binding — leaves nothing to post on.
            status.value = LoraStatus(state = stock.copy(channels = listOf(ChannelInfo(0, KnitChannel.NAME, role = 1))))
            assertEquals(LoraFacts(LoraPlane.Live, dms = true, primaryChannel = KnitChannel.NAME, canPost = false), repo.facts.first())

            // A board that never reported its preset names no channel but may still post.
            status.value = LoraStatus(state = ready)
            assertEquals(LoraFacts(LoraPlane.Live, dms = true, canPost = true), repo.facts.first())
        }

    @Test
    fun `slot 0's key rides the facts, and an unreadable board reads as public`() =
        runTest {
            // What picks the room's wording between "unencrypted" and "not end-to-end encrypted". The
            // fallback is the load-bearing half: every state that is not a live board with a real key on
            // slot 0 has to answer public, or a link that drops mid-session would quietly upgrade the claim.
            val keyed = ready.copy(channels = listOf(ChannelInfo(0, "Ridge", role = 1, psk = ByteArray(32) { 9 })))
            status.value = LoraStatus(state = keyed)
            assertFalse(repo.facts.first().primaryKeyIsPublic)

            status.value = LoraStatus(state = ready.copy(channels = listOf(ChannelInfo(0, "", role = 1))))
            assertTrue("the stock key everybody holds", repo.facts.first().primaryKeyIsPublic)

            // The link drops with the keyed board still bound: the claim falls back rather than persisting.
            status.value = LoraStatus(state = LinkState.Idle)
            assertTrue(repo.facts.first().primaryKeyIsPublic)
        }

    @Test
    fun `the battery rides the facts only while the link is live`() =
        runTest {
            val battery = BoardBattery(percent = 78, voltage = 3.92f, powered = false)
            status.value = LoraStatus(state = LinkState.Idle, battery = battery)
            assertEquals(LoraFacts(LoraPlane.Down, dms = true), repo.facts.first())
            status.value = LoraStatus(state = ready, battery = battery)
            assertEquals(LoraFacts(LoraPlane.Live, dms = true, battery = battery, canPost = true), repo.facts.first())
        }
}
