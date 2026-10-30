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
    private val room = MutableStateFlow(true)
    private val status = MutableStateFlow(LoraStatus())
    private val settings =
        mockk<SettingsStore> {
            every { loraEnabled } returns enabled
            every { loraDeviceAddress } returns address
            every { loraDmEnabled } returns dms
            every { loraRoomEnabled } returns room
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
    fun `the room switch survives the plane going off, and takes canPost with it`() =
        runTest {
            status.value = LoraStatus(state = ready)
            assertTrue(repo.facts.first().room)

            room.value = false
            val off = repo.facts.first()
            assertFalse("the room is switched off", off.room)
            assertFalse("so there is nothing to post from", off.canPost)
            assertEquals("but the plane itself is untouched", LoraPlane.Live, off.plane)

            // The one place this differs from the DM switch: `room` is the user's answer alone, never folded
            // with the plane, because the room's row is drawn from stored history as well as from a board.
            room.value = true
            enabled.value = false
            val planeOff = repo.facts.first()
            assertTrue("a stored history still has a room to live in", planeOff.room)
            assertFalse(planeOff.dms)
        }

    @Test
    fun `a board on a dedicated slot has no room, and the answer holds through its reboot`() =
        runTest {
            // ADR 067: no public radio can hear a pinned board, so there is no room to mirror on it — the row
            // goes, history and all, and so does the posting verdict. The user's switch is untouched.
            val air =
                AirtimeSnapshot(
                    ModemPreset.LONG_FAST,
                    LoraRegion.US,
                    known = true,
                    usedMs = 0,
                    allowanceMs = 45_000,
                    dedicated = true,
                )
            status.value = LoraStatus(state = ready, airtime = air)
            val pinned = repo.facts.first()
            assertFalse("no room on a dedicated slot", pinned.room)
            assertFalse("so nothing to post from", pinned.canPost)
            assertEquals(LoraPlane.Live, pinned.plane)

            // Read off the governor's sticky answer, not the live radio: the setup ends in a board reboot,
            // and the row must not come back for the seconds the link is down in between.
            status.value = LoraStatus(state = LinkState.Disconnected("reboot", retryAtMs = 0, streak = 1), airtime = air)
            assertFalse(repo.facts.first().room)

            // A restore hands the slot back to the firmware and the room returns.
            status.value = LoraStatus(state = ready, airtime = air.copy(dedicated = false))
            assertTrue(repo.facts.first().room)
            assertTrue(repo.facts.first().canPost)
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
                    usedMs = 41_000,
                    allowanceMs = 45_000,
                    publicCeilingMs = 22_500,
                )
            val roomy = spent.copy(usedMs = 21_000)
            status.value = LoraStatus(state = LinkState.Idle, airtime = spent)
            assertEquals(LoraFacts(LoraPlane.Down, dms = true), repo.facts.first())
            status.value = LoraStatus(state = ready, airtime = spent)
            assertEquals(LoraFacts(LoraPlane.Live, dms = true, airtimeSpent = true, canPost = true), repo.facts.first())
            // One ledger against one allowance (ADR 2026-10.ug4a) — as the governor admits them.
            status.value = LoraStatus(state = ready, airtime = roomy)
            assertEquals(LoraFacts(LoraPlane.Live, dms = true, airtimeSpent = false, canPost = true), repo.facts.first())
        }

    @Test
    fun `the busy-band brake rides the facts only while the link is live`() =
        runTest {
            status.value = LoraStatus(state = LinkState.Idle, braked = true)
            assertEquals(LoraFacts(LoraPlane.Down, dms = true), repo.facts.first())
            status.value = LoraStatus(state = ready, braked = true)
            assertEquals(LoraFacts(LoraPlane.Live, dms = true, bandBusy = true, canPost = true), repo.facts.first())
            // The brake lifts: the facts follow the status.
            status.value = LoraStatus(state = ready, braked = false)
            assertEquals(LoraFacts(LoraPlane.Live, dms = true, bandBusy = false, canPost = true), repo.facts.first())
            // Switched off while braked: Off, never busy.
            status.value = LoraStatus(state = ready, braked = true)
            enabled.value = false
            assertFalse(repo.facts.first().bandBusy)
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

    @Test
    fun `the facts carry the cliff the board signs room posts under`() =
        runTest {
            // Read off the airtime governor's own answer, and not gated on the link being live: the room's
            // composer stays mounted through a BLE flap, and its cap should follow the board it posts through.
            assertEquals("unknown is the strict case", PublicPostPolicy.MAX_SIGNED_TEXT_BYTES, repo.facts.first().roomSignedUpTo)
            val air =
                AirtimeSnapshot(
                    ModemPreset.LONG_FAST,
                    LoraRegion.US,
                    known = true,
                    usedMs = 0,
                    allowanceMs = 45_000,
                    roomSignedUpTo = LoraAirtime.UNSIGNED,
                )
            status.value = LoraStatus(state = ready, airtime = air)
            assertEquals("a board that signs no room post", LoraAirtime.UNSIGNED, repo.facts.first().roomSignedUpTo)
            status.value = LoraStatus(state = LinkState.Disconnected("flap", retryAtMs = 0, streak = 1), airtime = air)
            assertEquals("and the answer survives the link dropping", LoraAirtime.UNSIGNED, repo.facts.first().roomSignedUpTo)
            status.value = LoraStatus(state = ready, airtime = air.copy(roomSignedUpTo = 154))
            assertEquals("2.8.1 on an AEAD slot 0", 154, repo.facts.first().roomSignedUpTo)
        }
}
