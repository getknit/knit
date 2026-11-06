package app.getknit.knit

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.content.Context
import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.bluetooth.BleAdvertPayload
import app.getknit.knit.mesh.bluetooth.BleConnectArbiter
import app.getknit.knit.mesh.bluetooth.BleGattPayloadReader
import app.getknit.knit.mesh.bluetooth.DoorbellPolicy
import app.getknit.knit.mesh.bluetooth.GattPayloads
import app.getknit.knit.mesh.crypto.BlindTag
import app.getknit.knit.mesh.power.ElapsedWait
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [BleGattPayloadReader]'s give-up (#127): the read holds [BleConnectArbiter], so the scan and every dial wait on it,
 * and its 12 s are counted on the elapsed clock (ADR 2026-10.pj9w). Virtual time stands in for the monotonic clock
 * and a separate elapsed clock jumps ahead of it, the phone asleep. The device is a mock whose GATT client never
 * hears back unless a test says so: a connect the stack leaves pending, as it does for 30 s.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class BleGattPayloadReaderTest {
    /** Elapsed = virtual time + whatever the "phone" slept through. */
    private class Clock(
        private val scope: TestScope,
    ) {
        var slept = 0L

        fun now(): Long = scope.currentTime + slept
    }

    private val arbiter = BleConnectArbiter()
    private val gatt = mockk<BluetoothGatt>(relaxed = true)
    private var callback: BluetoothGattCallback? = null

    @Suppress("DEPRECATION") // the overload the reader calls; see BleDoorbell.Client.open
    private val device =
        mockk<BluetoothDevice>().also { device ->
            every { device.connectGatt(any(), any(), any(), any<Int>()) } answers {
                callback = arg(2)
                gatt
            }
        }

    private fun reader(clock: Clock) =
        BleGattPayloadReader(mockk<Context>(relaxed = true), arbiter, ElapsedWait(clock::now, checkMs = 2_000))

    @Test
    fun aReadLeftHangingWhileThePhoneSleptGivesUpWithinOneCheckOfWaking() =
        runTest {
            val clock = Clock(this)
            var result: BleGattPayloadReader.Result? = null
            var endedAt = -1L
            launch {
                result = reader(clock).read(device)
                endedAt = currentTime
            }
            advanceTimeBy(1_000)
            assertTrue("the read holds the arbiter, so the scan pauses for it", arbiter.busy.value)
            clock.slept = 20_000 // the phone slept 20 s; the monotonic clock saw none of it
            advanceTimeBy(5_000)
            // A withTimeoutOrNull(12_000) would still have 11 s of awake time to go; this ends at its first look.
            assertEquals(BleGattPayloadReader.Result(GattPayloads.Outcome.Failed, "timeout@connect"), result)
            assertEquals(2_000, endedAt)
            assertFalse("the radio is free again for the scan and the dials", arbiter.busy.value)
            verify { gatt.disconnect() }
            verify { gatt.close() }
        }

    @Test
    fun withoutSuspendTheReadGivesUpAtItsLength() =
        runTest {
            val reader = reader(Clock(this))
            var result: BleGattPayloadReader.Result? = null
            launch { result = reader.read(device) }
            advanceTimeBy(11_999)
            assertNull(result)
            assertTrue(arbiter.busy.value)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(BleGattPayloadReader.Result(GattPayloads.Outcome.Failed, "timeout@connect"), result)
            assertEquals(GattPayloads.READ_TIMEOUT_MS, currentTime)
            assertFalse(arbiter.busy.value)
        }

    @Test
    fun aRefusedConnectEndsTheReadWhenTheStackSaysSo() =
        runTest {
            val reader = reader(Clock(this))
            var result: BleGattPayloadReader.Result? = null
            launch { result = reader.read(device) }
            advanceTimeBy(3_000)
            callback!!.onConnectionStateChange(gatt, GATT_ERROR, BluetoothProfile.STATE_DISCONNECTED)
            runCurrent()
            assertEquals(BleGattPayloadReader.Result(GattPayloads.Outcome.Failed, "connect"), result)
            assertEquals(3_000, currentTime)
            assertFalse(arbiter.busy.value)
        }

    // --- The read's phases: a payload found, a stranger, a failure (the verdict GattPayloads forgets by) ---

    private val characteristic = mockk<BluetoothGattCharacteristic>(relaxed = true)
    private val service =
        mockk<BluetoothGattService>().also { every { it.getCharacteristic(DoorbellPolicy.PAYLOAD_UUID) } returns characteristic }
    private val payload = BleAdvertPayload.encode(NodeId.derive("iphone"), capabilities = 0L, digestVersion = 7L, psm = 0x0081)

    /** Runs a read the stack answers as [stack] says, from the connect on, and returns how it ended. */
    private fun readWith(stack: (BluetoothGattCallback) -> Unit): BleGattPayloadReader.Result? {
        var result: BleGattPayloadReader.Result? = null
        runTest {
            val reader = reader(Clock(this))
            launch { result = reader.read(device) }
            runCurrent()
            stack(callback!!)
            runCurrent()
            assertFalse("the radio is free again", arbiter.busy.value)
        }
        return result
    }

    private fun connected(cb: BluetoothGattCallback) = cb.onConnectionStateChange(gatt, 0, BluetoothProfile.STATE_CONNECTED)

    private fun discovered(
        cb: BluetoothGattCallback,
        status: Int = BluetoothGatt.GATT_SUCCESS,
    ) {
        every { gatt.discoverServices() } returns true
        connected(cb)
        cb.onServicesDiscovered(gatt, status)
    }

    @Test
    fun aGoodReadFindsThePayload() {
        every { gatt.getService(DoorbellPolicy.SERVICE_UUID) } returns service
        every { gatt.readCharacteristic(characteristic) } returns true
        val result =
            readWith {
                discovered(it)
                it.onCharacteristicRead(gatt, characteristic, payload, BluetoothGatt.GATT_SUCCESS)
            }
        assertEquals(GattPayloads.Outcome.Read(BleAdvertPayload.parse(payload)!!), result!!.outcome)
        assertNull(result.phase)
    }

    @Test
    fun aRefusedOrFailedDiscoveryIsAFailure() {
        every { gatt.discoverServices() } returns false
        assertEquals(BleGattPayloadReader.Result(GattPayloads.Outcome.Failed, "discover"), readWith { connected(it) })
        assertEquals(
            BleGattPayloadReader.Result(GattPayloads.Outcome.Failed, "discover"),
            readWith { discovered(it, status = GATT_ERROR) },
        )
    }

    @Test
    fun aDeviceWithoutTheServiceOrItsCharacteristicIsAStranger() {
        every { gatt.getService(DoorbellPolicy.SERVICE_UUID) } returns null
        assertEquals(BleGattPayloadReader.Result(GattPayloads.Outcome.Stranger, "service"), readWith { discovered(it) })
        val bare = mockk<BluetoothGattService>().also { every { it.getCharacteristic(any()) } returns null }
        every { gatt.getService(DoorbellPolicy.SERVICE_UUID) } returns bare
        assertEquals(BleGattPayloadReader.Result(GattPayloads.Outcome.Stranger, "characteristic"), readWith { discovered(it) })
    }

    @Test
    fun aRefusedOrFailedReadIsAFailure() {
        every { gatt.getService(DoorbellPolicy.SERVICE_UUID) } returns service
        every { gatt.readCharacteristic(characteristic) } returns false
        assertEquals(BleGattPayloadReader.Result(GattPayloads.Outcome.Failed, "read"), readWith { discovered(it) })
        every { gatt.readCharacteristic(characteristic) } returns true
        assertEquals(
            BleGattPayloadReader.Result(GattPayloads.Outcome.Failed, "read"),
            readWith {
                discovered(it)
                it.onCharacteristicRead(gatt, characteristic, payload, GATT_ERROR)
            },
        )
    }

    @Test
    fun aValueThatIsNotAPayloadIsAStranger() {
        every { gatt.getService(DoorbellPolicy.SERVICE_UUID) } returns service
        every { gatt.readCharacteristic(characteristic) } returns true
        val result =
            readWith {
                discovered(it)
                it.onCharacteristicRead(gatt, characteristic, byteArrayOf(1, 2, 3), BluetoothGatt.GATT_SUCCESS)
            }
        assertEquals(BleGattPayloadReader.Result(GattPayloads.Outcome.Stranger, "value"), result)
    }

    @Test
    fun aBlindedPayloadIsAStrangerOfItsOwnPhase() {
        // ADR 2026-11.xp6d: no port serves a blinded payload, and its tag would be forgotten and re-read at every dial.
        val tag = NodeId.fromBytes(BlindTag.tag(ByteArray(BlindTag.KEY_BYTES) { 7 }, ByteArray(NodeId.BYTES), slot = 1, restart = 0))
        val blinded = BleAdvertPayload.advert(tag, 0L, 0L, 0x83, sideLive = false, gattPeers = false, aware = false, blinded = true)
        every { gatt.getService(DoorbellPolicy.SERVICE_UUID) } returns service
        every { gatt.readCharacteristic(characteristic) } returns true
        val result =
            readWith {
                discovered(it)
                it.onCharacteristicRead(gatt, characteristic, blinded, BluetoothGatt.GATT_SUCCESS)
            }
        assertEquals(BleGattPayloadReader.Result(GattPayloads.Outcome.Stranger, BleGattPayloadReader.PHASE_BLINDED), result)
    }

    @Test
    fun aDisconnectMidReadFailsTheStepStillWaiting() {
        every { gatt.getService(DoorbellPolicy.SERVICE_UUID) } returns service
        every { gatt.readCharacteristic(characteristic) } returns true
        val result =
            readWith {
                discovered(it)
                it.onConnectionStateChange(gatt, GATT_ERROR, BluetoothProfile.STATE_DISCONNECTED)
            }
        assertEquals(BleGattPayloadReader.Result(GattPayloads.Outcome.Failed, "read"), result)
    }

    @Test
    @Config(sdk = [32])
    @Suppress("DEPRECATION") // the pre-33 callback this case pins
    fun beforeAndroid13TheValueComesFromTheCharacteristic() {
        every { gatt.getService(DoorbellPolicy.SERVICE_UUID) } returns service
        every { gatt.readCharacteristic(characteristic) } returns true
        every { characteristic.value } returns payload
        val result =
            readWith {
                discovered(it)
                it.onCharacteristicRead(gatt, characteristic, BluetoothGatt.GATT_SUCCESS)
            }
        assertEquals(GattPayloads.Outcome.Read(BleAdvertPayload.parse(payload)!!), result!!.outcome)
    }

    @Test
    @Suppress("DEPRECATION") // the pre-33 callback, which 13 and later also call
    fun fromAndroid13ThePre33CallbackIsIgnored() {
        every { gatt.getService(DoorbellPolicy.SERVICE_UUID) } returns service
        every { gatt.readCharacteristic(characteristic) } returns true
        every { characteristic.value } returns payload
        val result =
            readWith {
                discovered(it)
                it.onCharacteristicRead(gatt, characteristic, BluetoothGatt.GATT_SUCCESS)
                it.onCharacteristicRead(gatt, characteristic, byteArrayOf(9), BluetoothGatt.GATT_SUCCESS)
            }
        assertEquals("the 33+ callback's value wins", BleGattPayloadReader.Result(GattPayloads.Outcome.Stranger, "value"), result)
    }

    private companion object {
        /** The stack's catch-all status (`GATT_ERROR`, 133), which `BluetoothGatt` does not name publicly. */
        const val GATT_ERROR = 133
    }
}
