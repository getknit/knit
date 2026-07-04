package app.getknit.knit

import app.getknit.knit.mesh.AckSync
import app.getknit.knit.mesh.FakeLoopTransport
import app.getknit.knit.mesh.InboundFrame
import app.getknit.knit.mesh.Peer
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.ReceiptContent
import app.getknit.knit.mesh.protocol.WireCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Exercises [AckSync]'s delay-tolerant broadcast/group delivery tick on the JVM with [FakeLoopTransport].
 * The message author is a plain transport that just records the receipts it receives; the recipient runs the
 * [AckSync]. Note [FakeLoopTransport] inherits the interface's no-op [app.getknit.knit.mesh.MeshTransport.fastSend],
 * so a best-effort coordination-plane tick to a non-neighbor author is (correctly) not observed — which is
 * exactly the lost-tick case these tests then recover once the author becomes a live neighbor.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AckSyncTest {
    /** The message author: records every frame it receives, exposing the delivery-receipt ack ids. */
    private class Author(
        val id: String,
    ) {
        val transport = FakeLoopTransport(id)
        private val received = CopyOnWriteArrayList<InboundFrame>()

        fun start(scope: CoroutineScope) {
            scope.launch { transport.inbound.collect { received.add(it) } }
        }

        fun receipts(): List<InboundFrame> = received.filter { it.envelope.type == FrameType.RECEIPT }

        fun ackIds(): List<String> = receipts().mapNotNull { WireCodec.decodePayload<ReceiptContent>(it.envelope.payload)?.ackId }
    }

    private fun ackSyncOn(
        transport: FakeLoopTransport,
        id: String,
        clock: () -> Long = { 0L },
    ) = AckSync(
        transport = transport,
        selfId = { id },
        signRaw = { byteArrayOf(SIG_MARKER) },
        now = clock,
    )

    @Test
    fun tickReachesAuthorWhenAlreadyALiveNeighbor() =
        runTest(UnconfinedTestDispatcher()) {
            val author = Author("author")
            val recip = FakeLoopTransport("recip")
            recip.connect(author.transport)
            author.start(backgroundScope)
            val ack = ackSyncOn(recip, "recip")

            ack.owe("m1", "author")

            assertEquals(listOf("m1"), author.ackIds())
            val receipt = author.receipts().first()
            assertFalse("a delivery receipt must never be flooded", receipt.wire.relay)
            assertArrayEquals("a delivery receipt must be signed", byteArrayOf(SIG_MARKER), receipt.wire.sig)
        }

    @Test
    fun tickIsHeldWhileAuthorUnreachableThenDeliveredOnReconnect() =
        runTest(UnconfinedTestDispatcher()) {
            // The field case: the author was out of range when we delivered its broadcast/group message, so the
            // one-shot best-effort tick had nowhere to go — it must land once the author comes back.
            val author = Author("author")
            val recip = FakeLoopTransport("recip")
            author.start(backgroundScope)
            val ack = ackSyncOn(recip, "recip")

            ack.owe("m1", "author") // not connected → best-effort fast-send no-ops, the tick is remembered
            assertTrue("nothing delivered while the author is out of range", author.ackIds().isEmpty())

            recip.connect(author.transport)
            ack.onNeighborAdded(Peer("author")) // author reconnected as a live neighbor

            assertEquals(listOf("m1"), author.ackIds())
        }

    @Test
    fun retryPendingResendsToAReconnectedAuthor() =
        runTest(UnconfinedTestDispatcher()) {
            val author = Author("author")
            val recip = FakeLoopTransport("recip")
            author.start(backgroundScope)
            val ack = ackSyncOn(recip, "recip")

            ack.owe("m1", "author")
            assertTrue(author.ackIds().isEmpty())

            recip.connect(author.transport)
            ack.retryPending()

            assertEquals(listOf("m1"), author.ackIds())
        }

    @Test
    fun tickDeliveredOverALiveLinkIsNotResentForever() =
        runTest(UnconfinedTestDispatcher()) {
            val author = Author("author")
            val recip = FakeLoopTransport("recip")
            recip.connect(author.transport)
            author.start(backgroundScope)
            val ack = ackSyncOn(recip, "recip")

            ack.owe("m1", "author") // sent over the live link → dropped, nothing left to retry
            ack.retryPending()

            assertEquals("one tick, no perpetual resend once it has a live path home", listOf("m1"), author.ackIds())
        }

    @Test
    fun neverAcksOurOwnMessage() =
        runTest(UnconfinedTestDispatcher()) {
            val author = Author("author")
            val recip = FakeLoopTransport("recip")
            recip.connect(author.transport)
            author.start(backgroundScope)
            val ack = ackSyncOn(recip, "recip")

            ack.owe("m1", "recip") // author == us: never ack our own send
            ack.retryPending()

            assertTrue(author.ackIds().isEmpty())
        }

    @Test
    fun agedOutTickIsSweptNotResent() =
        runTest(UnconfinedTestDispatcher()) {
            var clock = 0L
            val author = Author("author")
            val recip = FakeLoopTransport("recip")
            author.start(backgroundScope)
            val ack = ackSyncOn(recip, "recip") { clock }

            ack.owe("m1", "author") // held while the author is unreachable
            clock += 25L * 60 * 60_000 // past the 24h owed TTL
            recip.connect(author.transport)
            ack.retryPending() // sweep drops it before any resend

            assertTrue("an aged-out tick is swept, not resent", author.ackIds().isEmpty())
        }

    private companion object {
        const val SIG_MARKER: Byte = 0x5A
    }
}
