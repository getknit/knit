package app.getknit.knit.mesh.bluetooth.meshcore

import app.getknit.knit.mesh.lora.meshcore.NusEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** `NusChannel.frames`' contract, as the MeshCore GATT channel keeps it: unlimited delivery, a cap that ends, never a drop. */
@OptIn(ExperimentalCoroutinesApi::class) // ReceiveChannel.isEmpty
class NusInboxTest {
    private fun frame(n: Int) = ByteArray(n) { it.toByte() }

    @Test
    fun framesArriveInOrderThenTheDisconnect() =
        runTest {
            val inbox = NusInbox()
            assertEquals(NusInbox.Take.HELD, inbox.frame(byteArrayOf(5, 1)))
            assertEquals(NusInbox.Take.HELD, inbox.frame(byteArrayOf(13, 2)))
            inbox.disconnected(19)
            assertArrayEquals(byteArrayOf(5, 1), (inbox.frames.receive() as NusEvent.Frame).bytes)
            assertArrayEquals(byteArrayOf(13, 2), (inbox.frames.receive() as NusEvent.Frame).bytes)
            assertEquals(NusEvent.Disconnected(19), inbox.frames.receive())
            assertTrue(inbox.frames.isEmpty)
        }

    @Test
    fun theFrameCapEndsTheConnectionWithItsOwnStatusBehindWhatIsHeld() =
        runTest {
            val inbox = NusInbox(maxFrames = 3, maxBytes = 1_000)
            repeat(3) { assertEquals(NusInbox.Take.HELD, inbox.frame(frame(10))) }
            assertEquals(NusInbox.Take.OVERFLOW, inbox.frame(frame(10)))
            assertEquals("nothing is taken after the end", NusInbox.Take.ENDED, inbox.frame(frame(10)))
            inbox.disconnected(8) // the stack's own disconnect, after ours: not a second end
            repeat(3) { assertTrue(inbox.frames.receive() is NusEvent.Frame) }
            assertEquals(NusEvent.Disconnected(NusInbox.STATUS_OVERFLOW), inbox.frames.receive())
            assertTrue("one end only", inbox.frames.isEmpty)
        }

    @Test
    fun theByteCapEndsItToo() =
        runTest {
            val inbox = NusInbox(maxFrames = 1_000, maxBytes = 25)
            assertEquals(NusInbox.Take.HELD, inbox.frame(frame(10)))
            assertEquals(NusInbox.Take.HELD, inbox.frame(frame(15)))
            assertEquals(NusInbox.Take.OVERFLOW, inbox.frame(frame(1)))
            inbox.frames.receive()
            inbox.frames.receive()
            assertEquals(NusEvent.Disconnected(NusInbox.STATUS_OVERFLOW), inbox.frames.receive())
        }

    @Test
    fun aSessionThatKeepsUpIsNeverEnded() =
        runTest {
            val inbox = NusInbox(maxFrames = 4, maxBytes = 100)
            repeat(10_000) {
                assertEquals(NusInbox.Take.HELD, inbox.frame(frame(20)))
                inbox.frames.receive()
            }
        }

    @Test
    fun theCountRestartsOnceTheSessionHasCaughtUp() =
        runTest {
            val inbox = NusInbox(maxFrames = 3, maxBytes = 1_000)
            repeat(3) { inbox.frame(frame(1)) }
            repeat(3) { inbox.frames.receive() }
            repeat(3) { assertEquals(NusInbox.Take.HELD, inbox.frame(frame(1))) }
        }

    @Test
    fun theDefaultsAreTheContractsCap() {
        assertEquals(1_024, NusInbox.MAX_FRAMES)
        assertEquals(256L * 1_024, NusInbox.MAX_BYTES)
        // The real board's largest notify fits the frame cap well inside the byte cap: 1,024 × 173 B < 256 KiB.
        assertTrue(NusInbox.MAX_FRAMES.toLong() * (NusLinkPolicy.MIN_MTU - NusLinkPolicy.ATT_HEADER) <= NusInbox.MAX_BYTES)
    }

    @Test(expected = ClosedReceiveChannelException::class)
    fun closeEndsTheSessionsSide() =
        runTest {
            val inbox = NusInbox()
            inbox.close()
            inbox.frames.receive()
        }
}
