package app.getknit.knit.mesh.inet

import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.inet.FakeLinkNet.Nat
import app.getknit.knit.mesh.link.LinkFraming
import app.getknit.knit.mesh.link.LinkHandshake
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.random.Random

/**
 * The byte stream over a direct link (ADR 2026-10.a5yj, `docs/DIRECT_LINKS.md` §6) on virtual time, over a path that
 * delays, reorders, duplicates and loses datagrams, and one round over real threads so `FramedLink`'s handshake and
 * framing run on its blocking streams unchanged.
 */
@OptIn(ExperimentalCoroutinesApi::class) // the test scheduler's clock and advanceTimeBy
class LinkArqTest {
    private val alice = NodeId.derive("alice")
    private val bob = NodeId.derive("bob")

    // One direction of a path: each datagram is lost at [loss], else delivered after [delayMs] plus up to [jitterMs]
    // (so later ones can pass earlier ones), and delivered twice at [dup]. [blocked] drops everything while it holds.
    private class Path(
        val scope: CoroutineScope,
        val delayMs: Long,
        val jitterMs: Long = 0,
        val loss: Double = 0.0,
        val dup: Double = 0.0,
        val random: Random = Random(7),
    ) {
        lateinit var to: LinkArq
        var blocked: (ByteArray) -> Boolean = { false }

        // Extra delay for a datagram sent now: a stall that holds datagrams back without losing them.
        var held: () -> Long = { 0L }
        var datagrams = 0
        var largest = 0

        // A bottleneck when set: one datagram leaves it every [serviceMs], and one that finds [buffer] queued is dropped.
        var serviceMs = 0L
        var buffer = 0
        var clock: () -> Long = { 0L }
        var overflows = 0
        private val departures = ArrayDeque<Long>()

        // Each data segment's seq the first time it is sent, in the order it reached the path.
        val firstSends = java.util.Collections.synchronizedList(ArrayList<Long>())
        private val seen = java.util.Collections.synchronizedSet(HashSet<Long>())

        fun send(datagram: ByteArray): Boolean {
            datagrams++
            largest = maxOf(largest, datagram.size)
            LinkArq.Segment
                .decode(datagram)
                ?.seq
                ?.let { if (seen.add(it)) firstSends += it }
            if (blocked(datagram) || random.nextDouble() < loss) return true
            val queued = if (serviceMs > 0) queue() ?: return true else 0L
            val copies = if (random.nextDouble() < dup) 2 else 1
            repeat(copies) {
                val at = queued + held() + delayMs + if (jitterMs > 0) random.nextLong(jitterMs) else 0L
                scope.launch {
                    delay(at)
                    to.take(datagram)
                }
            }
            return true
        }

        // How long a datagram waits in the bottleneck, or null when its queue is full.
        private fun queue(): Long? {
            val now = clock()
            while (departures.isNotEmpty() && departures.first() <= now) departures.removeFirst()
            if (departures.size >= buffer) return null.also { overflows++ }
            val leaves = maxOf(now, departures.lastOrNull() ?: now) + serviceMs
            departures.addLast(leaves)
            return leaves - now
        }
    }

    private class Ends(
        val a: LinkArq,
        val b: LinkArq,
        val ab: Path,
        val ba: Path,
    )

    private fun TestScope.ends(
        delayMs: Long = 20,
        jitterMs: Long = 0,
        loss: Double = 0.0,
        dup: Double = 0.0,
        window: Int = LinkArq.RECV_WINDOW,
    ): Ends {
        val clock = { testScheduler.currentTime }
        val ab = Path(backgroundScope, delayMs, jitterMs, loss, dup, Random(11))
        val ba = Path(backgroundScope, delayMs, jitterMs, loss, dup, Random(13))
        val a = LinkArq(ab::send, backgroundScope, clock, window = window)
        val b = LinkArq(ba::send, backgroundScope, clock, window = window)
        ab.to = b
        ba.to = a
        return Ends(a, b, ab, ba)
    }

    private suspend fun LinkArq.readExactly(n: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8 * 1024)
        while (out.size() < n) {
            val got = read(buf, 0, minOf(buf.size, n - out.size()))
            if (got < 0) break
            out.write(buf, 0, got)
        }
        return out.toByteArray()
    }

    private suspend fun LinkArq.writeAll(bytes: ByteArray) {
        write(bytes, 0, bytes.size)
        flush()
    }

    @Test
    fun bytesCrossInOrderInWholeDatagramsThatFitTheFloorMtu() =
        runTest {
            val ends = ends()
            val sent = Random(1).nextBytes(1_000_000)
            val got = async { ends.b.readExactly(sent.size) }
            ends.a.writeAll(sent)
            assertArrayEquals(sent, got.await())
            assertTrue(ends.a.awaitDrained())
            // A whole segment with no ranges to report: the 14-byte header and a full payload.
            assertEquals(LinkArq.Segment.FIXED_BYTES + 4 + LinkArq.MAX_PAYLOAD, ends.ab.largest)
            assertEquals(0L, ends.a.stats().retransmits)
        }

    @Test
    fun aWindowOfSegmentsIsInFlightAtOnceNotOneAtATime() =
        runTest {
            // 50 ms each way: stop-and-wait would need ~95 s for a megabyte; a window that grows needs well under 2.
            val ends = ends(delayMs = 50)
            val sent = Random(2).nextBytes(1_000_000)
            val started = testScheduler.currentTime
            val got = async { ends.b.readExactly(sent.size) }
            ends.a.writeAll(sent)
            ends.a.awaitDrained()
            assertArrayEquals(sent, got.await())
            assertTrue("took ${testScheduler.currentTime - started} ms", testScheduler.currentTime - started < 2_000)
            assertTrue(ends.a.stats().cwnd > LinkArq.INITIAL_CWND)
            // 100 ms out and back, and at most the delayed ack's 20 ms.
            assertTrue("srtt ${ends.a.stats().srttMs}", ends.a.stats().srttMs in 100L..120L)
        }

    @Test
    fun pacedSendsKeepAShallowBottleneckBusy() =
        runTest {
            // ~9.6 Mbit/s (one 1200-byte datagram a millisecond) behind a 12-datagram queue, 40 ms each way — a queue far
            // shallower than the round trip's worth, like an access point's for a phone in power save. A window sent back
            // to back overflows it every round and the window keeps halving (4.8 Mbit/s here); paced, it runs at 6.7.
            val ends = ends(delayMs = 40)
            ends.ab.serviceMs = 1
            ends.ab.buffer = 12
            ends.ab.clock = { testScheduler.currentTime }
            ends.a.seedRtt(80)
            val sent = Random(17).nextBytes(2_000_000)
            val started = testScheduler.currentTime
            val got = async { ends.b.readExactly(sent.size) }
            ends.a.writeAll(sent)
            ends.a.awaitDrained()
            assertArrayEquals(sent, got.await())
            val mbps = sent.size * 8.0 / (testScheduler.currentTime - started) / 1_000
            assertTrue("${"%.1f".format(mbps)} Mbit/s, ${ends.ab.overflows} overflowed", mbps > 6.0)
        }

    @Test
    fun aFrameWrittenBehindABulkOnADeepSlowPathWaitsAboutTheQueueBoundNotTheWindow() =
        runTest {
            // ~110 KB/s (a datagram every 10 ms) behind a queue that never fills, 40 ms each way: nothing is lost, so the
            // window grows to the receiver's limit, ~560 KB, five seconds of this path. A file fed in chunks as a link
            // feeds one, and ten seconds in a frame between two chunks: unbounded, the frame waited behind the whole
            // window (2026-10-30: a video from a phone whose signal sank held texts back for minutes).
            val ends = ends(delayMs = 40)
            ends.ab.serviceMs = 10
            ends.ab.buffer = 100_000
            ends.ab.clock = { testScheduler.currentTime }
            val chunk = ByteArray(4 * 1024)
            val frame = ByteArray(64) { 7 }
            var written = 0L
            var done = false
            var frameEnd = -1L
            var frameWrittenAt = -1L
            var frameReadAt = -1L
            val reader =
                async {
                    val buf = ByteArray(8 * 1024)
                    var read = 0L
                    while (!done || read < written) {
                        val n = ends.b.read(buf, 0, buf.size)
                        if (n < 0) break
                        read += n
                        if (frameEnd in 0..read && frameReadAt < 0) frameReadAt = testScheduler.currentTime
                    }
                    read
                }
            val started = testScheduler.currentTime
            while (testScheduler.currentTime - started < 20_000) {
                if (frameEnd < 0 && testScheduler.currentTime - started >= 10_000) {
                    frameWrittenAt = testScheduler.currentTime
                    ends.a.writeAll(frame)
                    written += frame.size
                    frameEnd = written
                }
                ends.a.writeAll(chunk)
                written += chunk.size
            }
            done = true
            assertEquals(written, reader.await())
            val waited = frameReadAt - frameWrittenAt
            assertTrue("the frame waited $waited ms", waited in 0..1_500)
            // The bound holds the queue, not the bulk: it still runs at the bottleneck.
            val bottleneck = LinkArq.MAX_PAYLOAD / 10.0
            val rate = written.toDouble() / (testScheduler.currentTime - started)
            assertTrue("${"%.1f".format(rate)} B/ms on a ${"%.1f".format(bottleneck)} B/ms path", rate > 0.9 * bottleneck)
        }

    @Test
    fun aPathThatLosesReordersAndDuplicatesStillDeliversEveryByteInOrder() =
        runTest {
            val ends = ends(delayMs = 30, jitterMs = 40, loss = 0.1, dup = 0.05)
            val sent = Random(3).nextBytes(500_000)
            val got = async { ends.b.readExactly(sent.size) }
            ends.a.writeAll(sent)
            assertArrayEquals(sent, got.await())
            assertTrue(ends.a.awaitDrained())
            assertTrue(ends.a.stats().retransmits > 0)
            // Full ranges on a whole segment: with the lane's 25 bytes and the type byte, QUIC's 1200-byte floor.
            assertTrue(ends.ab.largest + ends.ba.largest > 0)
            assertTrue(maxOf(ends.ab.largest, ends.ba.largest) <= LinkArq.MAX_DATAGRAM - LinkLane.OVERHEAD_BYTES - 1)
        }

    @Test
    fun bothDirectionsCrossAtOnceOverALossyPath() =
        runTest {
            val ends = ends(delayMs = 25, jitterMs = 10, loss = 0.05)
            val toB = Random(4).nextBytes(200_000)
            val toA = Random(5).nextBytes(150_000)
            val atB = async { ends.b.readExactly(toB.size) }
            val atA = async { ends.a.readExactly(toA.size) }
            val writeA = async { ends.a.writeAll(toB) }
            ends.b.writeAll(toA)
            writeA.await()
            assertArrayEquals(toB, atB.await())
            assertArrayEquals(toA, atA.await())
        }

    @Test
    fun aReaderThatStopsHoldsTheWriterAtTheWindowAndReadingReleasesIt() =
        runTest {
            val window = 16
            val ends = ends(window = window)
            val sent = Random(6).nextBytes(200 * LinkArq.MAX_PAYLOAD)
            val write = async { ends.a.writeAll(sent) }
            testScheduler.advanceTimeBy(30_000)
            // The receiver holds no more than its window; the rest waits on the writer's side.
            assertFalse(write.isCompleted)
            assertTrue(ends.a.stats().bytesAcked <= window.toLong() * LinkArq.MAX_PAYLOAD)
            assertArrayEquals(sent, ends.b.readExactly(sent.size))
            write.await()
        }

    @Test
    fun aLostWindowUpdateIsAskedForAgainSoTheWriterIsNeverWedged() =
        runTest {
            val window = 8
            val ends = ends(window = window)
            // More than the window and the writer's own buffer hold, so the writer waits on the window.
            val sent = Random(8).nextBytes((window + LinkArq.SEND_BUFFER + 48) * LinkArq.MAX_PAYLOAD)
            val write = async { ends.a.writeAll(sent) }
            testScheduler.advanceTimeBy(5_000)
            assertFalse(write.isCompleted)
            // Every bare ack the reader sends while it drains is lost: the window updates never arrive.
            ends.ba.blocked = { true }
            val first = ends.b.readExactly(window * LinkArq.MAX_PAYLOAD)
            testScheduler.advanceTimeBy(1_000)
            ends.ba.blocked = { false }
            val rest = ends.b.readExactly(sent.size - first.size)
            write.await()
            assertArrayEquals(sent, first + rest)
        }

    @Test
    fun aPathThatGoesDarkForSecondsResumesOnTheBackedOffTimer() =
        runTest {
            val ends = ends(delayMs = 40)
            val sent = Random(9).nextBytes(300_000)
            val got = async { ends.b.readExactly(sent.size) }
            val write = async { ends.a.writeAll(sent) }
            testScheduler.advanceTimeBy(200)
            ends.ab.blocked = { true }
            // Past the acks already on their way back, only the timer sends.
            testScheduler.advanceTimeBy(200)
            val before = ends.ab.datagrams
            testScheduler.advanceTimeBy(5_800)
            val dark = ends.ab.datagrams - before
            ends.ab.blocked = { false }
            write.await()
            assertArrayEquals(sent, got.await())
            assertTrue(ends.a.stats().retransmits > 0)
            // A probe timer doubling from at least 100 ms fires ~6 times in 5.8 s of dark, each sending PROBES past the
            // window; one that never backed off would fire ~58 times.
            assertTrue("$dark datagrams in the dark", dark in 1..8 * LinkArq.PROBES)
        }

    @Test
    fun acksThatStallForSecondsAreProbedNotTakenForLoss() =
        runTest {
            // A cellular path's delay spike: every ack the reader sends for one second arrives two seconds late, and none
            // is lost. Read as loss, the stall would send the whole window again and drop it to its floor.
            val ends = ends(delayMs = 40)
            val sent = Random(18).nextBytes(2_000_000)
            val started = testScheduler.currentTime
            val got = async { ends.b.readExactly(sent.size) }
            val write = async { ends.a.writeAll(sent) }
            testScheduler.advanceTimeBy(500)
            val stallFrom = testScheduler.currentTime
            ends.ba.held = { if (testScheduler.currentTime - stallFrom < 1_000) 2_000L else 0L }
            write.await()
            assertArrayEquals(sent, got.await())
            val stats = ends.a.stats()
            assertTrue("${stats.timeouts} probe timeouts", stats.timeouts > 0)
            // Only the probes went again, no loss was declared, and nothing was presumed lost that then arrived — read as
            // loss, the stall presumes ~500 segments lost, ends the run with a window of ~36 and takes twice as long.
            assertTrue("${stats.retransmits} resent", stats.retransmits <= LinkArq.PROBES * stats.timeouts)
            assertEquals(0L, stats.lossEvents)
            assertEquals(0L, stats.spurious)
            val ms = testScheduler.currentTime - started
            assertTrue("took $ms ms", ms < 3_500)
        }

    @Test
    fun aLostTailGoesAgainWithinARoundTripNotAfterATimeout() =
        runTest {
            // Two segments, the first lost: the second's ack shows it, and 9/8 of a round trip after the first went it
            // goes again (~112 ms + 50 ms to arrive), well before a probe timeout (~320 ms) would have sent it.
            val ends = ends(delayMs = 50)
            ends.a.seedRtt(100)
            var sends = 0
            ends.ab.blocked = { sends++ == 0 }
            val sent = Random(19).nextBytes(2 * LinkArq.MAX_PAYLOAD)
            val started = testScheduler.currentTime
            val got = async { ends.b.readExactly(sent.size) }
            ends.a.writeAll(sent)
            assertArrayEquals(sent, got.await())
            val ms = testScheduler.currentTime - started
            assertTrue("delivered after $ms ms", ms < 250)
            assertEquals(0L, ends.a.stats().timeouts)
        }

    // Grows a window through a slow, deep, lossless queue (so the run outlasts the blackout), blacks the path out until
    // [expiries] probe timeouts have fired, lets the next probes through, and returns the window just before the losses
    // the blackout caused were found and just after. The losses are found on the ack of the probe the next expiry sends,
    // so they are found after [expiries] + 1 expiries in a row.
    private suspend fun TestScope.windowAcrossABlackout(expiries: Int): Pair<Int, Int> {
        val ends = ends(delayMs = 40)
        ends.ab.serviceMs = 1
        ends.ab.buffer = 100_000
        ends.ab.clock = { testScheduler.currentTime }
        val sent = Random(20).nextBytes(8_000_000)
        val got = async { ends.b.readExactly(sent.size) }
        val write = async { ends.a.writeAll(sent) }
        testScheduler.advanceTimeBy(1_000)
        val before = ends.a.stats()
        ends.ab.blocked = { true }
        val until = testScheduler.currentTime + 60_000
        while (ends.a.stats().timeouts < before.timeouts + expiries && testScheduler.currentTime < until) {
            testScheduler.advanceTimeBy(1)
        }
        assertEquals("probe timeouts in the dark", before.timeouts + expiries, ends.a.stats().timeouts)
        ends.ab.blocked = { false }
        var last = ends.a.stats().cwnd
        while (ends.a.stats().lossEvents == before.lossEvents && testScheduler.currentTime < until) {
            last = ends.a.stats().cwnd
            testScheduler.advanceTimeBy(1)
        }
        assertTrue("the blackout's losses were found", ends.a.stats().lossEvents > before.lossEvents)
        val after = ends.a.stats().cwnd
        write.await()
        assertArrayEquals(sent, got.await())
        return last to after
    }

    @Test
    fun lossesFoundAfterFewerProbeTimeoutsThanPersistentHalveTheWindow() =
        runTest {
            val (before, after) = windowAcrossABlackout(expiries = LinkArq.PERSISTENT_PTOS - 2)
            // The ack that shows the losses also acknowledges the probes, which grow the window a little first.
            assertTrue("window $before → $after", after in before / 2 - 2..before / 2 + 2)
            assertTrue("window $before", before > 4 * LinkArq.MIN_CWND)
        }

    @Test
    fun lossesFoundAfterAPersistentRunOfProbeTimeoutsTakeTheWindowToItsFloor() =
        runTest {
            val (before, after) = windowAcrossABlackout(expiries = LinkArq.PERSISTENT_PTOS - 1)
            assertTrue("window $before", before > 4 * LinkArq.MIN_CWND)
            assertEquals(LinkArq.MIN_CWND, after)
        }

    @Test
    fun anAckIsOwedOnlyForData() =
        runTest {
            val ends = ends()
            ends.a.writeAll("hi".toByteArray())
            assertEquals("hi", String(ends.b.readExactly(2)))
            testScheduler.advanceTimeBy(60_000)
            // One segment, one (delayed) bare ack for it, and nothing else in a minute: an ack is never acked.
            assertEquals(1, ends.ab.datagrams)
            assertEquals(1, ends.ba.datagrams)
        }

    @Test
    fun anEndedStreamReadsWhatArrivedThenEndsAndRefusesWrites() =
        runTest {
            val ends = ends()
            ends.a.writeAll("last words".toByteArray())
            testScheduler.advanceTimeBy(100)
            ends.b.end()
            assertEquals("last words", String(ends.b.readExactly(10)))
            assertEquals(-1, ends.b.read(ByteArray(4), 0, 4))
            assertTrue(runCatching { ends.b.writeAll(byteArrayOf(1)) }.exceptionOrNull() is IOException)
        }

    @Test
    fun closingTheStreamClosesTheLinkOnce() =
        runTest {
            var closes = 0
            val arq = LinkArq({ true }, backgroundScope, { testScheduler.currentTime }, onClose = { closes++ })
            arq.close()
            arq.close()
            arq.input.close()
            assertEquals(1, closes)
            assertEquals(-1, arq.read(ByteArray(1), 0, 1))
        }

    @Test
    fun aSegmentRoundTripsAndAMalformedOneIsRefused() {
        val ranges = listOf(40L to 44L, 20L to 30L)
        val payload = Random(10).nextBytes(LinkArq.MAX_PAYLOAD)
        val seg = LinkArq.Segment(ack = 17, limit = 529, ranges = ranges, seq = 0xFFFFFFFEL, payload = payload, ask = true)
        val back = checkNotNull(LinkArq.Segment.decode(seg.encode()))
        assertEquals(17L, back.ack)
        assertEquals(529L, back.limit)
        assertEquals(ranges, back.ranges)
        assertEquals(0xFFFFFFFEL, back.seq)
        assertArrayEquals(payload, back.payload)
        assertTrue(back.ask)
        val bare = LinkArq.Segment(3, 515, emptyList(), null, ByteArray(0), false).encode()
        assertEquals(10, bare.size)
        assertNull(LinkArq.Segment.decode(bare + 0)) // a bare ack carries nothing after its ranges
        assertNull(LinkArq.Segment.decode(bare.copyOf(9))) // truncated
        val tooMany = LinkArq.Segment(0, 1, (0 until 9).map { 10L * it + 1 to 10L * it + 2 }, null, ByteArray(0), false)
        assertNull(LinkArq.Segment.decode(tooMany.encode()))
        assertNull(LinkArq.Segment.decode(LinkArq.Segment(0, 1, listOf(5L to 5L), null, ByteArray(0), false).encode()))
        val big = LinkArq.Segment(0, 1, emptyList(), 0, ByteArray(LinkArq.MAX_PAYLOAD + 1), false).encode()
        assertNull(LinkArq.Segment.decode(big))
        val empty = LinkArq.Segment(0, 1, emptyList(), 0, ByteArray(0), false).encode()
        assertNull(LinkArq.Segment.decode(empty)) // a numbered segment carries at least a byte
    }

    @Test
    fun theStreamRidesAPunchedLinkAndEndsWithIt() =
        runTest {
            val net = FakeLinkNet()
            net.stunServer("stun.example", byteArrayOf(198.toByte(), 51, 100, 7))
            val clock = { testScheduler.currentTime }
            val aPort = net.phone(Nat.PORT_RESTRICTED, byteArrayOf(81, 2, 3, 4)).open()!!
            val bPort = net.phone(Nat.ADDRESS_RESTRICTED, byteArrayOf(91, 5, 6, 7)).open()!!
            val stun = listOf("stun:stun.example:3478")
            val aCands = LinkGather.candidates(aPort, stun, clock)
            val bCands = LinkGather.candidates(bPort, stun, clock)
            val rng = Random(12)
            val aToB = rng.nextBytes(32)
            val bToA = rng.nextBytes(32)
            val aad = LinkLane.aad(rng.nextBytes(16), alice, bob)
            val a = LinkSession("b", aPort, LinkLane(aToB, bToA, aad), PunchPolicy.targets(bCands, true, true), backgroundScope, clock)
            val b = LinkSession("a", bPort, LinkLane(bToA, aToB, aad), PunchPolicy.targets(aCands, true, true), backgroundScope, clock)
            val streams = LinkStream.arq(backgroundScope, clock)
            val aStream = streams.open(a) as LinkArq
            val bStream = streams.open(b) as LinkArq
            a.start()
            b.start()
            assertTrue(a.awaitOutcome() is LinkSession.Outcome.Up)
            val sent = Random(13).nextBytes(100_000)
            val got = async { bStream.readExactly(sent.size) }
            aStream.writeAll(sent)
            assertArrayEquals(sent, got.await())
            assertTrue(a.carriedData)
            aStream.close()
            testScheduler.advanceTimeBy(100)
            assertEquals(LinkSession.State.CLOSED, b.state.value)
            assertEquals(-1, bStream.read(ByteArray(1), 0, 1))
        }

    @Test
    fun framedLinksHandshakeAndRecordsRunOnTheBlockingStreamsUnchanged() =
        runBlocking {
            // Real threads: the blocking streams park an IO thread each, as FramedLink's reader and writer do.
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val clock = { System.nanoTime() / 1_000_000 }
            val ab = Path(scope, delayMs = 2, jitterMs = 3, loss = 0.05, random = Random(14))
            val ba = Path(scope, delayMs = 2, jitterMs = 3, loss = 0.05, random = Random(15))
            val a = LinkArq(ab::send, scope, clock)
            val b = LinkArq(ba::send, scope, clock)
            try {
                ab.to = b
                ba.to = a
                val record = Random(16).nextBytes(300_000)
                // A wedge parks the IO threads inside runBlocking, which no cancellation reaches: ending the streams
                // returns every blocked read and write, so the test fails instead of hanging the task.
                scope.launch {
                    delay(30_000)
                    a.end()
                    b.end()
                }
                withTimeout(40_000) {
                    withContext(Dispatchers.IO) {
                        val responder =
                            async {
                                val hello = LinkHandshake.readHello(b.input)
                                LinkHandshake.replyHello(b.output, bob)
                                hello to LinkFraming.read(b.input)
                            }
                        LinkHandshake.writeHello(a.output, alice)
                        val reply = LinkHandshake.readHello(a.input)
                        LinkFraming.write(a.output, LinkFraming.Type.FRAME, record)
                        a.output.flush()
                        val (hello, message) = responder.await()
                        assertEquals(alice, hello?.nodeId)
                        assertEquals(bob, reply?.nodeId)
                        assertEquals(LinkFraming.Type.FRAME, message?.type)
                        assertArrayEquals(record, message?.payload)
                        // Two threads sent (the writer, and the reader's acks letting more go): the wire still carried
                        // every segment first in the order it was numbered. A canary, not a proof: with sends let out of
                        // the lock again it goes red about two runs in three on a many-core machine, almost never on one.
                        val order = synchronized(ab.firstSends) { ab.firstSends.toList() }
                        assertEquals(order.sorted(), order)
                    }
                }
            } finally {
                // Ended before the scope goes, so no IO thread stays parked in a blocking read into the next class.
                a.end()
                b.end()
                scope.cancel()
            }
        }
}
