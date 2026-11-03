package app.getknit.knit

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothSocket
import app.getknit.knit.mesh.bluetooth.BleLinkBoost
import app.getknit.knit.mesh.bluetooth.BluetoothSocketLink
import app.getknit.knit.mesh.bluetooth.LinkBoostPolicy
import app.getknit.knit.mesh.bluetooth.LinkParams
import app.getknit.knit.mesh.bluetooth.LinkParamsCallback
import app.getknit.knit.mesh.power.ElapsedWait
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.OutputStream

/**
 * The thin Bluetooth surfaces a link's bytes and parameters pass through, tested as they are: the L2CAP write cap
 * ([BluetoothSocketLink]), the units of a reported update ([LinkParamsCallback]), and the boost's earned state as the
 * tuner's reports set it ([BleLinkBoost], its loop left unstarted). Robolectric for `android.util.Log` and the framework
 * classes; the socket and the GATT handle are mocks.
 */
@RunWith(RobolectricTestRunner::class)
class BleLinkSurfaceTest {
    // --- BluetoothSocketLink ---

    private class Writes : OutputStream() {
        val sizes = mutableListOf<Int>()

        override fun write(b: Int) {
            sizes += 1
        }

        override fun write(
            b: ByteArray,
            off: Int,
            len: Int,
        ) {
            sizes += len
        }
    }

    private fun linkWritingThrough(reportedMax: Int): Writes {
        val writes = Writes()
        val socket =
            mockk<BluetoothSocket>(relaxed = true).also {
                every { it.maxTransmitPacketSize } returns reportedMax
                every { it.outputStream } returns writes
                every { it.inputStream } returns ByteArrayInputStream(ByteArray(0))
            }
        BluetoothSocketLink(socket).output.apply {
            write(ByteArray(20_000))
            flush()
        }
        return writes
    }

    @Test
    fun noL2capWriteExceedsTheCapWhateverTheStackReports() {
        // Some stacks report the 65535-byte SDU ceiling here; a 64 KiB record written whole crashed the Moto G's.
        assertEquals(listOf(8_192, 8_192, 3_616), linkWritingThrough(65_535).sizes)
        assertEquals("a report of nothing", listOf(8_192, 8_192, 3_616), linkWritingThrough(0).sizes)
        assertEquals("exactly the cap", listOf(8_192, 8_192, 3_616), linkWritingThrough(8_192).sizes)
    }

    @Test
    fun aSmallerReportedPacketSizeIsTheWriteSize() {
        val sizes = linkWritingThrough(4_000).sizes
        assertTrue(sizes.all { it <= 4_000 })
        assertEquals(20_000, sizes.sum())
    }

    @Test
    fun closingTheLinkClosesTheSocketAndSwallowsItsError() {
        val socket = mockk<BluetoothSocket>(relaxed = true)
        every { socket.close() } throws java.io.IOException("already closed")
        BluetoothSocketLink(socket).close()
        verify { socket.close() }
    }

    // --- LinkParamsCallback ---

    private class Heard : LinkParamsCallback() {
        val calls = mutableListOf<Triple<LinkParams, Boolean, Int>>()

        override fun onLinkParams(
            params: LinkParams,
            ok: Boolean,
            status: Int,
        ) {
            calls += Triple(params, ok, status)
        }
    }

    @Test
    fun anUpdateIsReportedInMicrosecondsAndMillisecondsNotHciUnits() {
        val cb = Heard()
        cb.onConnectionUpdated(mockk(), interval = 12, latency = 0, timeout = 500, status = BluetoothGatt.GATT_SUCCESS)
        assertEquals(Triple(LinkParams(intervalUs = 15_000, latency = 0, timeoutMs = 5_000), true, 0), cb.calls.single())
    }

    @Test
    fun aFailedUpdateIsReportedNotOk() {
        val cb = Heard()
        cb.onConnectionUpdated(mockk(), interval = 36, latency = 0, timeout = 500, status = 0x3B)
        assertFalse(cb.calls.single().second)
        assertEquals(0x3B, cb.calls.single().third)
    }

    // --- BleLinkBoost ---

    private var clock = 1_000L

    private fun boost() =
        BleLinkBoost(
            nodeId = "peer",
            feeding = { false },
            receiving = { false },
            observes = { true },
            eligible = { true },
            tuner = { null },
            scope = CoroutineScope(Job()),
            wait = ElapsedWait({ clock }),
            now = { clock },
        )

    private fun params(intervalUs: Int) = LinkParams(intervalUs, latency = 0, timeoutMs = 5_000)

    @Test
    fun aReportedIntervalAtTheThresholdEarnsAndASlowerOneDoesNot() {
        val b = boost()
        assertFalse(b.earned)
        b.onParams(params(LinkBoostPolicy.EARNED_INTERVAL_US), ok = true)
        assertTrue(b.earned)
        b.onParams(params(45_000), ok = true)
        assertFalse(b.earned)
    }

    @Test
    fun aFailedUpdateChangesNothing() {
        val b = boost()
        b.onParams(params(7_500), ok = true)
        b.onParams(params(45_000), ok = false)
        assertTrue(b.earned)
    }

    @Test
    fun aLinkThatLostTheBoostReadsAsEarnedRecentlyFromTheMomentItLostIt() {
        // Stamped on the way out: a link that earned at the start of a long stream and lost it at the release must not
        // read as earned minutes before.
        val b = boost()
        assertFalse("never earned", b.earnedWithin(60_000))
        b.onParams(params(7_500), ok = true)
        clock += 300_000 // a long stream
        b.onParams(params(45_000), ok = true)
        clock += 10_000
        assertTrue(b.earnedWithin(10_000))
        assertFalse(b.earnedWithin(9_999))
    }

    @Test
    fun aLostClientForgetsTheBoostAndStampsWhenItWasLastHeld() {
        val b = boost()
        b.onParams(params(7_500), ok = true)
        clock += 120_000
        b.forget()
        assertFalse(b.earned)
        clock += 5_000
        assertTrue(b.earnedWithin(5_000))
        assertFalse(b.earnedWithin(4_999))
    }

    @Test
    fun aTunerThatEndsForgetsWhatItHeard() {
        val b = boost()
        b.onParams(params(7_500), ok = true)
        b.onTunerEnded(attached = true)
        assertFalse(b.earned)
    }
}
