package app.getknit.knit

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetCallback.ADVERTISE_FAILED_DATA_TOO_LARGE
import android.bluetooth.le.AdvertisingSetCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED
import android.bluetooth.le.AdvertisingSetCallback.ADVERTISE_FAILED_INTERNAL_ERROR
import android.bluetooth.le.AdvertisingSetCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS
import android.bluetooth.le.AdvertisingSetCallback.ADVERTISE_SUCCESS
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import app.getknit.knit.mesh.MeshMetrics
import app.getknit.knit.mesh.bluetooth.AdvertReassertPolicy
import app.getknit.knit.mesh.bluetooth.BleSideChannel
import app.getknit.knit.mesh.bluetooth.SideCarousel
import app.getknit.knit.mesh.link.FastFrameCodec
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.ReceiptContent
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.random.Random

/**
 * The side channel's page sets ride the presence set's re-assert (ADR 2026-10.9utz, amendment 2026-10-09; #143): a
 * slot with a page on the air is enabled again on a turn, a refused enable comes back on the doubling wait, and nothing
 * raises a slot the carousel took down, one sitting out a refused start, or a dark channel. A rotation (ADR 2026-11.xp6d's
 * writer) retires every slot for a fresh set and never re-airs the page that was on the air. A start's reaction runs late,
 * on the channel's scope: a standard test dispatcher holds it until [runCurrent], so the window between the binder
 * callback and the reaction is open here as it is on `Dispatchers.Default`. Robolectric for the framework's real
 * `AdvertiseData` / `AdvertisingSetParameters` builders; the adapter, the advertiser and the sets are mocks.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class BleSideChannelReassertTest {
    private val callbacks = mutableListOf<AdvertisingSetCallback>()
    private val starts = mutableListOf<AdvertiseData>()
    private var refusedWakes = 0
    private var clock = 1_000L

    private val radio =
        mockk<BluetoothLeAdvertiser>(relaxed = true).also { adv ->
            every { adv.startAdvertisingSet(any(), any(), any(), any(), any(), any<AdvertisingSetCallback>()) } answers {
                starts += arg<AdvertiseData>(1)
                callbacks += arg<AdvertisingSetCallback>(5)
            }
        }

    private val adapter =
        mockk<BluetoothAdapter>(relaxed = true).also {
            every { it.isEnabled } returns true
            every { it.isLeExtendedAdvertisingSupported } returns true
            every { it.leMaximumAdvertisingDataLength } returns 1650
            every { it.bluetoothLeAdvertiser } returns radio
        }

    private val context =
        mockk<Context>(relaxed = true).also { ctx ->
            val manager = mockk<BluetoothManager>(relaxed = true).also { every { it.adapter } returns adapter }
            every { ctx.applicationContext } returns ctx
            every { ctx.getSystemService(BluetoothManager::class.java) } returns manager
        }

    private val scope = TestScope(StandardTestDispatcher())

    private val side =
        BleSideChannel(context, MeshMetrics(), scope, log = {}, now = { clock }, config = SideCarousel.Config())

    @Before
    fun setUp() {
        side.bind(
            object : BleSideChannel.Listener {
                override fun onFrame(
                    wire: WireEnvelope,
                    env: RelayEnvelope,
                ) = Unit

                override fun onAvailabilityChanged() = Unit

                override fun onAdvertRefused() {
                    refusedWakes++
                }
            },
        )
        side.bringUp()
        assertTrue(side.live)
    }

    private fun runCurrent() = scope.testScheduler.runCurrent()

    /** One small receipt: one page, so one slot. */
    private fun offerOne(id: String = "AAAAAAAAAAAAAAAAAAAAAA") {
        val env =
            RelayEnvelope(
                type = FrameType.RECEIPT,
                id = id,
                senderId = "abcdefghijklmnopqrstuvwxy2",
                sentAt = 1_755_700_000_000L,
                payload = WireCodec.encodePayload(ReceiptContent(ackId = "BBBBBBBBBBBBBBBBBBBBBB")),
            )
        val wire = WireEnvelope(sig = Random(id.hashCode()).nextBytes(FastFrameCodec.SIG_BYTES), signed = WireCodec.encodeEnvelope(env))
        assertEquals(SideCarousel.Offer.QUEUED, side.offer(wire, env, SideCarousel.Kind.CONTENT, coalesceKey = null))
    }

    /** Offers a page and hands its slot a live set. */
    private fun liveSlot(): AdvertisingSet {
        offerOne()
        assertEquals("one page, one slot", 1, starts.size)
        val set = mockk<AdvertisingSet>(relaxed = true)
        callbacks.last().onAdvertisingSetStarted(set, 0, ADVERTISE_SUCCESS)
        return set
    }

    @Test
    fun aSlotWithAPageOnTheAirIsEnabledAgainOnATurn() {
        val set = liveSlot()
        assertEquals("only the slot with a page", 1, side.reassert())
        verify(exactly = 1) { set.enableAdvertising(true, 0, 0) }
        assertEquals("no second start, and the empty slot is not raised", 1, starts.size)
    }

    @Test
    fun aRefusedEnableComesBackOnTheDoublingWait() {
        val set = liveSlot()
        val cb = callbacks.last()
        side.reassert()
        cb.onAdvertisingEnabled(set, true, ADVERTISE_FAILED_INTERNAL_ERROR) // our enable, refused (0x0d beside a link)
        assertEquals("the transport's loop is woken to re-read the wait", 1, refusedWakes)
        assertEquals(AdvertReassertPolicy.RETRY_BASE_MS, side.retryWaitMs())
        assertEquals("not due yet", 0, side.retryRefused())
        clock += AdvertReassertPolicy.RETRY_BASE_MS
        assertEquals(1, side.retryRefused())
        verify(exactly = 2) { set.enableAdvertising(true, 0, 0) }
        cb.onAdvertisingEnabled(set, true, ADVERTISE_SUCCESS)
        assertTrue("no retry pending once it is back on", side.retryWaitMs() > LONG_WAIT)
    }

    @Test
    fun aRetryDueOnASlotWithNothingToAirIsSpentNotSpun() {
        val set = liveSlot()
        callbacks.last().onAdvertisingEnabled(set, true, ADVERTISE_FAILED_INTERNAL_ERROR)
        side.tearDown()
        clock += AdvertReassertPolicy.RETRY_BASE_MS
        assertEquals(0, side.retryRefused())
        assertTrue("the spent retry no longer holds the loop's wait short", side.retryWaitMs() > LONG_WAIT)
    }

    @Test
    fun aSlotTheCarouselTookDownStaysDown() {
        val set = liveSlot()
        // Past the dwell and the linger with nothing waiting: the carousel takes the page down and stops the set.
        clock += 5 * SideCarousel.LINGER_MS
        scope.testScheduler.advanceTimeBy(5 * SideCarousel.LINGER_MS)
        scope.testScheduler.runCurrent()
        verify(exactly = 1) { radio.stopAdvertisingSet(any()) }
        assertEquals(0, side.reassert())
        verify(exactly = 0) { set.enableAdvertising(any(), any(), any()) }
        assertEquals(1, starts.size)
    }

    @Test
    fun aSlotSittingOutARefusedStartIsNotRestartedEarly() {
        offerOne()
        callbacks.last().onAdvertisingSetStarted(null, 0, ADVERTISE_FAILED_TOO_MANY_ADVERTISERS)
        runCurrent()
        val before = starts.size // the page may have moved to the other slot
        side.reassert()
        assertEquals("the refused slot keeps its bytes but sits out SLOT_RETRY_MS", before, starts.size)
    }

    @Test
    fun aDarkChannelRaisesNothing() {
        offerOne()
        callbacks.last().onAdvertisingSetStarted(null, 0, ADVERTISE_FAILED_FEATURE_UNSUPPORTED)
        runCurrent() // goes dark
        assertEquals(false, side.live)
        assertEquals(0, side.reassert())
        assertEquals(1, starts.size)
    }

    @Test
    fun aTornDownChannelRaisesNothing() {
        val set = liveSlot()
        side.tearDown()
        assertEquals(0, side.reassert())
        verify(exactly = 0) { set.enableAdvertising(any(), any(), any()) }
        assertEquals(1, starts.size)
    }

    @Test
    fun aLateSitOutStopsARestartThatSucceededInTheMeantime() {
        offerOne()
        val slot0 = callbacks.last()
        slot0.onAdvertisingSetStarted(null, 0, ADVERTISE_FAILED_TOO_MANY_ADVERTISERS) // its reaction is still queued
        side.reassert() // the page is still pushed, so the slot is started again
        assertEquals(2, starts.size)
        val set = mockk<AdvertisingSet>(relaxed = true)
        slot0.onAdvertisingSetStarted(set, 0, ADVERTISE_SUCCESS) // and this time it takes
        runCurrent() // the sit-out lands after the success
        verify(atLeast = 1) { radio.stopAdvertisingSet(slot0) }
        side.reassert()
        verify(exactly = 0) { set.enableAdvertising(true, any(), any()) }
    }

    @Test
    fun aSitOutFromBeforeAnAdapterBounceIsDropped() {
        offerOne()
        val slot0 = callbacks.last()
        slot0.onAdvertisingSetStarted(null, 0, ADVERTISE_FAILED_TOO_MANY_ADVERTISERS)
        side.tearDown()
        side.bringUp()
        runCurrent()
        offerOne(id = "CCCCCCCCCCCCCCCCCCCCCC")
        assertSame("the fresh session's first slot was not parked by the old refusal", slot0, callbacks.last())
    }

    @Test
    fun aHalvedPageFromBeforeAnAdapterBounceIsDropped() {
        offerOne()
        callbacks.last().onAdvertisingSetStarted(null, 0, ADVERTISE_FAILED_DATA_TOO_LARGE)
        side.tearDown()
        side.bringUp()
        runCurrent()
        assertEquals(BleSideChannel.PAGE_BYTES, side.pageMax)
    }

    @Test
    fun aFeatureRefusalFromBeforeAnAdapterBounceIsDropped() {
        offerOne()
        callbacks.last().onAdvertisingSetStarted(null, 0, ADVERTISE_FAILED_FEATURE_UNSUPPORTED)
        side.tearDown()
        side.bringUp()
        runCurrent()
        assertTrue(side.live)
    }

    @Test
    fun aHalvedPageStopsEverySetItForgets() {
        offerOne()
        val slot0 = callbacks.last()
        slot0.onAdvertisingSetStarted(null, 0, ADVERTISE_FAILED_DATA_TOO_LARGE)
        side.reassert() // started again before the reaction lands
        slot0.onAdvertisingSetStarted(mockk(relaxed = true), 0, ADVERTISE_SUCCESS)
        runCurrent()
        assertEquals(BleSideChannel.PAGE_BYTES / 2, side.pageMax)
        verify(atLeast = 1) { radio.stopAdvertisingSet(slot0) }
    }

    private companion object {
        // Past any retry or settle: what a keeper with no net and nothing refused reports.
        const val LONG_WAIT = 24 * 60 * 60_000L
    }

    @Test
    fun aRotationRetiresEverySlotAndNeverReairsThePageThatWasOnTheAir() {
        // ADR 2026-11.xp6d's writer (step 5b): fresh sets on new addresses, the old ones stopped, the old page not aired.
        val set = liveSlot()
        val oldCallback = callbacks.last()
        side.rotate()
        verify(exactly = 1) { radio.stopAdvertisingSet(oldCallback) }
        assertEquals("the page on the air at the edge is not restarted on a fresh set", 1, starts.size)
        assertEquals(0, side.reassert())
        // The old set's late word lands nowhere: no re-enable is asked of it, and no refusal wakes the transport.
        oldCallback.onAdvertisingEnabled(set, true, ADVERTISE_FAILED_INTERNAL_ERROR)
        assertEquals(0, refusedWakes)
        // A new frame airs on a fresh instance, with a callback of its own.
        offerOne("CCCCCCCCCCCCCCCCCCCCCC")
        assertEquals(2, starts.size)
        assertTrue("a fresh set, not the old instance restarted", callbacks.last() !== oldCallback)
    }

    @Test
    fun aLateStartOfARetiredSlotIsStoppedAndDoesNotSitTheNewSlotOut() {
        offerOne()
        val oldCallback = callbacks.last()
        side.rotate() // the start was still in flight
        oldCallback.onAdvertisingSetStarted(null, 0, ADVERTISE_FAILED_TOO_MANY_ADVERTISERS)
        runCurrent()
        offerOne("DDDDDDDDDDDDDDDDDDDDDD")
        assertEquals("the fresh slot was not sat out by the old one's refusal", 2, starts.size)
    }
}
