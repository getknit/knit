package app.getknit.knit.mesh.lab

import app.getknit.knit.mesh.FileKind
import app.getknit.knit.mesh.FileMeta
import app.getknit.knit.mesh.LinkOutbound
import app.getknit.knit.mesh.PartialBlobs
import app.getknit.knit.mesh.Peer
import app.getknit.knit.mesh.link.FeedMeter
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * What a lab pipe tells custody it still holds for a peer ([LabTransport.carrier]'s [LinkOutbound], the custody-carry-policy
 * contract's §2, release on drain): the read the lab and the simulator answer custody's rounds with, as each phone plane
 * answers with its own. Two transports and their pipes alone, no stacks.
 */
class LabTransportOutboundTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val made = mutableListOf<LabTransport>()

    private fun transport(id: String) = LabTransport(id, tmp.newFolder(), PartialBlobs(tmp.newFolder(), minBytes = 1)).also { made += it }

    @After
    fun cut() = made.forEach { it.disconnectAll() }

    private fun frame(
        n: Int,
        bytes: Int = 200,
    ): WireEnvelope {
        val env =
            RelayEnvelope(
                type = FrameType.CHAT,
                id = "f$n",
                senderId = "s",
                sentAt = n.toLong(),
                recipientId = "q",
                payload = ByteArray(bytes),
            )
        return WireEnvelope(sig = ByteArray(SIG_BYTES) { (n + it).toByte() }, signed = WireCodec.encodeEnvelope(env))
    }

    private fun bytesOf(vararg wires: WireEnvelope) = wires.sumOf { WireCodec.encodeWire(it).size.toLong() + LinkAir.RECORD_HEADER_BYTES }

    private fun LabTransport.outbound(to: String): LinkOutbound = checkNotNull(carrier(to)?.outbound) { "no link to $to" }

    @Test
    fun aPlainPipeHoldsOnlyWhatAHoldParkedAndSignalsTheRelease() =
        runBlocking {
            val (a, b) = transport("aaaa") to transport("bbbb")
            a.connect(b)
            a.send(frame(1), Peer("bbbb"))
            assertEquals("a plain pipe delivers as it sends", 0L, a.outbound("bbbb").unwritten)

            a.hold(b)
            val held = frame(2)
            a.send(held, Peer("bbbb"))
            assertEquals(bytesOf(held), a.outbound("bbbb").unwritten)
            assertNull("a plain pipe names no drain rate", a.outbound("bbbb").drainBytesPerSec)

            val drained = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { a.outboundDrained.first() }
            a.release(b)
            assertEquals("bbbb", withTimeout(WAIT_MS) { drained.await() })
            assertEquals(0L, a.outbound("bbbb").unwritten)
        }

    @Test
    fun anAirPipeCountsItsQueueUntilDeliveredAndReadsNotDrainingOnceCut() =
        runBlocking {
            val (a, b) = transport("aaaa") to transport("bbbb")
            a.connect(b, air = LinkAir(bytesPerSecond = SLOW_BYTES_PER_SECOND))
            val frames = (1..3).map { frame(it) }
            frames.forEach { a.send(it, Peer("bbbb")) }
            val unwritten = a.outbound("bbbb").unwritten
            assertTrue(
                "the queue is counted: $unwritten of ${bytesOf(*frames.toTypedArray())}",
                unwritten in 1..bytesOf(*frames.toTypedArray()),
            )
            assertFalse(a.outbound("bbbb").fileMoving)

            a.disconnect(b)
            assertNull("no link, no carrier", a.carrier("bbbb"))
        }

    @Test
    fun aDeliveredFrameSignalsTheDrainAndLeavesTheCount() =
        runBlocking {
            val (a, b) = transport("aaaa") to transport("bbbb")
            a.connect(b, air = LinkAir(bytesPerSecond = FAST_BYTES_PER_SECOND))
            val drained = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { a.outboundDrained.first() }
            a.send(frame(1), Peer("bbbb"))
            assertEquals("bbbb", withTimeout(WAIT_MS) { drained.await() })
            a.awaitAirDrained(b)
            assertEquals(0L, a.outbound("bbbb").unwritten)
        }

    @Test
    fun aBufferBelowTheCountHidesTheHeadUnlessThePipeEstimatesIt() =
        runBlocking {
            val (a, b) = transport("aaaa") to transport("bbbb")
            a.connect(b, air = LinkAir(bytesPerSecond = SLOW_BYTES_PER_SECOND))
            val frames = (1..3).map { frame(it) }
            val total = bytesOf(*frames.toTypedArray())

            a.stackBelow(b, bytes = 1_000_000L, estimated = false)
            frames.forEach { a.send(it, Peer("bbbb")) }
            assertEquals("a stack deeper than the queue takes all of it below the count", 0L, a.outbound("bbbb").unwritten)

            a.stackBelow(b, bytes = 1_000_000L, estimated = true)
            val estimated = a.outbound("bbbb")
            assertEquals("the estimate drains at the air's rate", SLOW_BYTES_PER_SECOND, estimated.drainBytesPerSec)
            assertTrue("the estimate puts back what is below: ${estimated.unwritten} of $total", estimated.unwritten in 1..total)
        }

    @Test
    fun aParkedFileMovesUntilTheBlobPlanesStallThenNot() =
        runBlocking {
            val (a, b) = transport("aaaa") to transport("bbbb")
            var now = 1_000_000L
            a.clock = { now }
            b.clock = { now }
            a.connect(b)
            a.holdFiles(b)
            val file = tmp.newFile("photo").apply { writeBytes(ByteArray(FILE_BYTES)) }
            assertTrue(a.sendFile(file, Peer("bbbb"), FileMeta(FileKind.ATTACHMENT, "hash", "image/jpeg")))

            assertTrue("a file parked on the link is moving", a.outbound("bbbb").fileMoving)
            assertTrue("either way: the receiver's read sees it too", b.outbound("aaaa").fileMoving)
            assertEquals("a file's bytes are never counted", 0L, a.outbound("bbbb").unwritten)

            now += FeedMeter.STALL_MS
            assertFalse("nothing moved for the stall: it frees the other tiers", a.outbound("bbbb").fileMoving)
        }

    private companion object {
        const val SIG_BYTES = 64
        const val SLOW_BYTES_PER_SECOND = 10L
        const val FAST_BYTES_PER_SECOND = 1_048_576L
        const val FILE_BYTES = 4_096
        const val WAIT_MS = 5_000L
    }
}
