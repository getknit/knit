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
import java.util.UUID

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
    private val service = service(DoorbellPolicy.SERVICE_UUID, payload = characteristic)

    /** A discovered service of [uuid] holding [payload] as its payload characteristic, or none. */
    private fun service(
        uuid: UUID,
        payload: BluetoothGattCharacteristic? = null,
    ) = mockk<BluetoothGattService>().also {
        every { it.uuid } returns uuid
        every { it.getCharacteristic(any()) } returns null
        every { it.getCharacteristic(DoorbellPolicy.PAYLOAD_UUID) } returns payload
    }

    /** What discovery found: the reader searches every service, never `getService`, which answers the first 0xFE30 alone. */
    private fun serves(vararg services: BluetoothGattService) {
        every { gatt.services } returns services.toList()
    }

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
        serves(service)
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
        serves()
        assertEquals(BleGattPayloadReader.Result(GattPayloads.Outcome.Stranger, "service"), readWith { discovered(it) })
        serves(service(DoorbellPolicy.SERVICE_UUID))
        assertEquals(BleGattPayloadReader.Result(GattPayloads.Outcome.Stranger, "characteristic"), readWith { discovered(it) })
    }

    @Test
    fun thePayloadIsFoundInTheSecondOfTwoKnitServices() {
        // A blinding iPhone (knit-ios ADR 2026-11.prsp): D, the doorbell alone, added first; P, the payload alone, beside it.
        serves(service(DoorbellPolicy.SERVICE_UUID), service)
        every { gatt.readCharacteristic(characteristic) } returns true
        val result =
            readWith {
                discovered(it)
                it.onCharacteristicRead(gatt, characteristic, payload, BluetoothGatt.GATT_SUCCESS)
            }
        assertEquals(GattPayloads.Outcome.Read(BleAdvertPayload.parse(payload)!!), result!!.outcome)
    }

    @Test
    fun aPayloadCharacteristicOutsideAKnitServiceIsIgnored() {
        val elsewhere = mockk<BluetoothGattCharacteristic>(relaxed = true)
        serves(service(UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb"), payload = elsewhere))
        assertEquals(BleGattPayloadReader.Result(GattPayloads.Outcome.Stranger, "service"), readWith { discovered(it) })
        serves(service(UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb"), payload = elsewhere), service(DoorbellPolicy.SERVICE_UUID))
        assertEquals(BleGattPayloadReader.Result(GattPayloads.Outcome.Stranger, "characteristic"), readWith { discovered(it) })
        verify(exactly = 0) { gatt.readCharacteristic(elsewhere) }
    }

    @Test
    fun aRefusedOrFailedReadIsAFailure() {
        serves(service)
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
        serves(service)
        every { gatt.readCharacteristic(characteristic) } returns true
        val result =
            readWith {
                discovered(it)
                it.onCharacteristicRead(gatt, characteristic, byteArrayOf(1, 2, 3), BluetoothGatt.GATT_SUCCESS)
            }
        assertEquals(BleGattPayloadReader.Result(GattPayloads.Outcome.Stranger, "value"), result)
    }

    /** Reads [value] from the payload characteristic of the one combined 0xFE30 service a clear iPhone serves. */
    private fun readValue(value: ByteArray): BleGattPayloadReader.Result? {
        serves(service)
        every { gatt.readCharacteristic(characteristic) } returns true
        return readWith {
            discovered(it)
            it.onCharacteristicRead(gatt, characteristic, value, BluetoothGatt.GATT_SUCCESS)
        }
    }

    @Test
    fun aBlindedPayloadIsRead() {
        // ADR 2026-11.89gg: a blinding iPhone's payload (FLAG_BLINDED | FLAG_DIALS_GATT_PEERS, a tag, the cue zero) is a
        // blinded form the transport sights as a blinded advert, never a stranger.
        val tag = NodeId.fromBytes(BlindTag.tag(ByteArray(BlindTag.KEY_BYTES) { 7 }, ByteArray(NodeId.BYTES), slot = 1, restart = 0))
        val blinded = BleAdvertPayload.advert(tag, 0L, 0L, 0x83, sideLive = false, gattPeers = true, aware = false, blinded = true)
        val result = readValue(blinded)
        val read = result!!.outcome as GattPayloads.Outcome.Read
        assertTrue(read.payload.blinded)
        assertEquals(tag, read.payload.nodeId)
        assertNull(result.phase)
    }

    @Test
    fun aMalformedBlindedValueIsAStrangerOfItsOwnPhase() {
        // FLAG_BLINDED over bytes that lack the tag prefix: a stranger, as BleAdvertPayload.parse drops the advert, counted
        // apart (BleBlindDrop.GATT_BLINDED).
        val iphone = NodeId.derive("iphone")
        val malformed = BleAdvertPayload.advert(iphone, 0L, 0L, 0x83, sideLive = false, gattPeers = true, aware = false, blinded = true)
        assertTrue(BleAdvertPayload.isMalformedBlinded(malformed))
        assertEquals(
            BleGattPayloadReader.Result(GattPayloads.Outcome.Stranger, BleGattPayloadReader.PHASE_MALFORMED_BLINDED),
            readValue(malformed),
        )
    }

    @Test
    fun aDisconnectMidReadFailsTheStepStillWaiting() {
        serves(service)
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
        serves(service)
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
        serves(service)
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
