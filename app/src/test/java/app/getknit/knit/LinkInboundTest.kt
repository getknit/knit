package app.getknit.knit

import app.getknit.knit.mesh.InboundFrame
import app.getknit.knit.mesh.MeshMetrics
import app.getknit.knit.mesh.PartialBlobs
import app.getknit.knit.mesh.Peer
import app.getknit.knit.mesh.ReceivedDigest
import app.getknit.knit.mesh.ReceivedFile
import app.getknit.knit.mesh.bluetooth.BleInbound
import app.getknit.knit.mesh.bluetooth.LinkInbound
import app.getknit.knit.mesh.link.FrameKey
import app.getknit.knit.mesh.link.FramedLink
import app.getknit.knit.mesh.link.LinkCallbacks
import app.getknit.knit.mesh.link.LinkCrossings
import app.getknit.knit.mesh.link.LinkFraming
import app.getknit.knit.mesh.link.LinkSocket
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * [LinkInbound] behind a real [FramedLink] (#331, ADR 2026-11.43f7): a link whose router is behind waits for room
 * instead of dropping, its wait ends with the link, and a crossing is marked only for a frame the router was handed off
 * the link the plane holds now. The router is a collector the test holds inside its first frame; the plane's link table
 * is a map, swapped and emptied as `registerLink` and `teardownLink` do it, the memo forgotten after.
 */
class LinkInboundTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val router = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val crossings = LinkCrossings()

    // The plane's `links`: which link holds the peer's node id now.
    private val links = ConcurrentHashMap<String, FramedLink>()

    // Two slots, and a router held inside the first frame it takes: the third frame the links hand over waits.
    private val inbound = MutableSharedFlow<InboundFrame>(extraBufferCapacity = SLOTS)
    private val opened = CompletableDeferred<Unit>()
    private val handled = LinkedBlockingQueue<String>()
    private val logs = CopyOnWriteArrayList<String>()
    private val waits = CopyOnWriteArrayList<Long>()

    // Every link a test opened: closed at teardown, so no reader is left blocked in a read into the next test.
    private val made = CopyOnWriteArrayList<FramedLink>()

    @After
    fun tearDown() {
        made.forEach { it.close() }
        opened.complete(Unit)
        router.cancel()
        scope.cancel()
    }

    /** The plane's callbacks for one link: frames through a [LinkInbound], the end recorded. */
    private inner class Plane(
        val nodeId: String,
        into: MutableSharedFlow<InboundFrame>,
    ) : LinkCallbacks {
        lateinit var link: FramedLink
        val downs = LinkedBlockingQueue<String>()
        private val handOff =
            LinkInbound(
                nodeId = nodeId,
                inbound = into,
                crossings = crossings,
                current = { links[nodeId] === link },
                now = System::currentTimeMillis,
                log = { logs += it },
                onWaited = { waits += it },
            )

        // How many frames this link's reader is inside a hand-off with (0 or 1), and how many it has come out of: a reader
        // inside one with the queue full is waiting, and the stepped router reads both to know the links have settled.
        val inHand = AtomicInteger()
        val out = AtomicInteger()

        override suspend fun onInbound(frame: InboundFrame) {
            inHand.incrementAndGet()
            try {
                handOff.hand(frame)
            } finally {
                inHand.decrementAndGet()
                out.incrementAndGet()
            }
        }

        override fun onDigest(digest: ReceivedDigest) = Unit

        override fun onFile(file: ReceivedFile) = Unit

        fun readEnded() = handOff.end()

        override fun onLinkDown(nodeId: String) {
            downs.add(nodeId)
        }
    }

    /** One link to [nodeId] over a pipe the test writes the peer's records into, registered in [links] and started. */
    private fun open(
        nodeId: String,
        into: MutableSharedFlow<InboundFrame> = inbound,
    ): Pair<Plane, OutputStream> {
        val toLink = PipedOutputStream()
        val input = PipedInputStream(toLink, 1 shl 16)
        val socket =
            object : LinkSocket {
                override val input = input
                override val output: OutputStream = OutputStream.nullOutputStream()

                // Both ends of the pipe, as a socket's close ends its read: a reader blocked in `read` returns.
                override fun close() {
                    runCatching { toLink.close() }
                    runCatching { input.close() }
                }
            }
        val plane = Plane(nodeId, into)
        plane.link =
            FramedLink(
                nodeId = nodeId,
                peer = Peer(nodeId),
                socket = socket,
                scope = scope,
                cacheDir = tmp.root,
                partials = PartialBlobs(tmp.newFolder(), minBytes = 1),
                metrics = MeshMetrics(),
                callbacks = plane,
                now = System::currentTimeMillis,
                proven = false,
                onReadEnd = plane::readEnded,
            )
        made += plane.link
        links.put(nodeId, plane.link)
        crossings.forget(nodeId) // after the put, as registerLink: a replaced link's late mark cannot land past it
        plane.link.start()
        return plane to toLink
    }

    /** The router: takes the first frame and holds inside it until [opened], then takes the rest as they come. */
    private fun startRouter() {
        router.launch {
            inbound.collect {
                handled.add(it.envelope.id)
                opened.await()
            }
        }
    }

    private fun frame(
        id: String,
        senderId: String = PEER,
    ): ByteArray {
        val env = RelayEnvelope(type = FrameType.CHAT, id = id, senderId = senderId, payload = ByteArray(0))
        return WireCodec.encodeWire(WireEnvelope(sig = id.toByteArray(), signed = WireCodec.encodeEnvelope(env)))
    }

    private fun key(id: String): String {
        val wire = WireCodec.decodeWire(frame(id))!!
        return FrameKey.of(wire, WireCodec.decodeEnvelope(wire.signed)!!)
    }

    private fun send(
        out: OutputStream,
        vararg ids: String,
        from: String = PEER,
    ) {
        ids.forEach { LinkFraming.write(out, LinkFraming.Type.FRAME, frame(it, from)) }
        out.flush()
    }

    private fun awaitUntil(
        timeoutMs: Long = AWAIT_MS,
        cond: () -> Boolean,
    ): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (cond()) return true
            Thread.sleep(5)
        }
        return cond()
    }

    /** Fails with what the links logged and what the router read, unless [cond] comes true within [AWAIT_MS]. */
    private fun await(
        what: String,
        cond: () -> Boolean,
    ) = assertTrue("$what\n  logs: $logs\n  handled: $handled", awaitUntil(cond = cond))

    // The router holds f0, f1 and f2 fill the two slots, and f3 is the frame the reader waits with.
    private fun parkOn(out: OutputStream) {
        startRouter()
        await("the router never subscribed") { inbound.subscriptionCount.value == 1 }
        send(out, "f0")
        await("the router never took the first frame") { handled.size == 1 }
        send(out, "f1", "f2", "f3")
        await("the reader never waited") { logs.any { it.startsWith("bt inbound wait $PEER") } }
    }

    @Test
    fun aFullQueueMakesTheReaderWaitAndLosesNothing() {
        val (_, out) = open(PEER)
        parkOn(out)
        opened.complete(Unit)
        await("a frame the queue had no room for was lost") { handled.size == 4 }
        assertEquals(listOf("f0", "f1", "f2", "f3"), handled.toList())
        // The spell ends at the next frame that goes straight in. Its line is written after f3's mark and its wait's count,
        // which the router taking f3 does not prove: the reader's resume runs after that.
        send(out, "f4")
        await("the spell never ended at a frame with room") {
            logs.any { it.startsWith("bt inbound waited $PEER frames=1 ") && it.endsWith("(room)") }
        }
        // Every frame the router was handed counts as crossed: the echo toward the peer is skipped.
        listOf("f0", "f1", "f2", "f3").forEach { assertFalse("$it never marked", crossings.firstCrossing(PEER, key(it))) }
        assertEquals(1, waits.size)
    }

    @Test
    fun closeCancelsAReaderWaitingForRoom() {
        val (plane, out) = open(PEER)
        parkOn(out)
        // Closed while still in the table: what is left to judge is the wait itself, not the link check.
        plane.link.close()
        assertEquals("the waiting reader never ended", PEER, plane.downs.poll(AWAIT_MS, TimeUnit.MILLISECONDS))
        await("the cancelled wait never closed its spell") {
            logs.any { it.startsWith("bt inbound waited $PEER frames=1 ") && it.endsWith("(closed)") }
        }
        // The frame it waited with never reached the router, so it never counted as crossed; what the router was handed did.
        assertTrue("a frame the router never had counts as crossed", crossings.firstCrossing(PEER, key("f3")))
        listOf("f0", "f1", "f2").forEach { assertFalse("$it never marked", crossings.firstCrossing(PEER, key(it))) }
        opened.complete(Unit)
        // The router drains what it was handed before the end, and never the frame the closed link's reader held.
        await("the router never drained what it was handed") { handled.size == 3 }
        Thread.sleep(SETTLE_MS)
        assertEquals(listOf("f0", "f1", "f2"), handled.toList())
    }

    @Test
    fun aReplacedLinksWaitingReaderMarksNothingOnTheReplacement() {
        val (old, out) = open(PEER)
        parkOn(out)
        // registerLink: the replacement takes the node id and the memo starts clean, then the old link is closed. Here
        // room frees before the close, the worst order: the old reader's wait ends with its link already replaced.
        val (fresh, _) = open(PEER)
        send(out, "late") // read off the old link after its replacement
        opened.complete(Unit)
        // f3's hand-off returns once f3 is in the buffer, before the router takes it, so the log below does not prove
        // the router has it: wait for that first.
        await("the router never took f3") { handled.size == 4 }
        // Written once `late` was read and refused, after f3's hand-off returned: the barrier for both negatives below.
        await("the old reader never refused a frame off its replaced link") {
            logs.any { it.startsWith("bt inbound waited $PEER frames=1 ") && it.endsWith("(replaced)") }
        }
        assertEquals("a frame read off the replaced link reached the router", listOf("f0", "f1", "f2", "f3"), handled.toList())
        // f3 was handed over in the instant before the close, and is the router's to drop as seen. Its mark would have
        // landed in the new link's memo, withholding it there for ten minutes: none did.
        assertTrue("the replaced link marked the replacement's memo", crossings.firstCrossing(PEER, key("f3")))
        old.link.close()
        assertEquals(PEER, old.downs.poll(AWAIT_MS, TimeUnit.MILLISECONDS))
        assertTrue(links[PEER] === fresh.link)
    }

    @Test
    fun aSpellStillOpenWhenTheLinkEndsIsClosedByTheReadersEnd() {
        val (plane, out) = open(PEER)
        parkOn(out)
        opened.complete(Unit)
        // The burst's last frame waited, and nothing goes straight in after it: the link just ends.
        await("the router never took the frame that waited") { handled.size == 4 }
        out.close()
        assertEquals(PEER, plane.downs.poll(AWAIT_MS, TimeUnit.MILLISECONDS))
        await("the reader's end never closed its spell") {
            logs.any { it.startsWith("bt inbound waited $PEER frames=1 ") && it.endsWith("(ended)") }
        }
    }

    @Test
    fun aReaderWaitingOnAFullLinksLaneNeverShutsAPageOut() {
        // The plane's two lanes (BleInbound), the router held inside the first frame and the links' lane full behind it.
        val lanes = BleInbound(linkSlots = SLOTS, pageSlots = SLOTS)
        router.launch {
            lanes.flow.collect {
                handled.add(it.envelope.id)
                opened.await()
            }
        }
        await("the router never subscribed to the links' lane") { lanes.links.subscriptionCount.value == 1 }
        // A first page through the merge proves its lane's collector is up, and becomes the frame the router holds.
        assertTrue(lanes.page(pageFrame("probe")))
        await("the pages' lane never reached the router") { handled.size == 1 }
        val (_, out) = open(PEER, into = lanes.links)
        send(out, *Array(FLOOD) { "f$it" })
        await("the reader never waited") { logs.any { it.startsWith("bt inbound wait $PEER") } }
        // A page heard now has room of its own: a shared queue gave every freed slot to the waiting reader first.
        assertTrue("the page was shut out", lanes.page(pageFrame("page")))
        opened.complete(Unit)
        await("the router never read everything") { handled.size == FLOOD + 2 }
        val got = handled.toList()
        // And it is read in turn, not behind the whole flood.
        assertTrue("the page waited behind the flood: $got", got.indexOf("page") < got.indexOf("f${FLOOD - 1}"))
    }

    @Test
    fun aFloodingLinkTakesOnlyItsShareOfASlowRouter() {
        // One link floods far past the queue; another, honest, sends a few frames once the flood waits. The router reads a
        // frame per permit the test hands it, and the test steps it only once the links have settled: both readers inside a
        // hand-off with the queue full. A fair hand-off (MutableSharedFlow's emitter queue) then takes the two waiting
        // readers in the order they began to wait, so the honest frames come exactly every other frame.
        val permits = Channel<Unit>(Channel.UNLIMITED)
        router.launch {
            inbound.collect {
                handled.add(it.envelope.id)
                permits.receive()
            }
        }
        await("the router never subscribed") { inbound.subscriptionCount.value == 1 }
        val (flood, floodOut) = open("flood001")
        val (honest, honestOut) = open("honest01")
        val planes = listOf(flood to FLOOD, honest to HONEST)

        // Settled: every frame out of a hand-off is the router's or fills the queue, and each reader with frames left is
        // inside its next hand-off, waiting.
        fun settled(sent: List<Pair<Plane, Int>>) =
            sent.sumOf { (p, _) -> p.out.get() } == handled.size + SLOTS &&
                sent.all { (p, n) -> p.inHand.get() == 1 || p.out.get() == n }
        send(floodOut, *Array(FLOOD) { "f$it" }, from = "flood001")
        await("the flood never settled waiting") { settled(listOf(flood to FLOOD)) && flood.inHand.get() == 1 }
        val mine = Array(HONEST) { "h$it" }
        send(honestOut, *mine, from = "honest01")
        await("the honest link never settled waiting") { settled(planes) && honest.inHand.get() == 1 }
        while (mine.last() !in handled) {
            val read = handled.size
            permits.trySend(Unit)
            await("the links never settled after step ${read + 1}") { handled.size == read + 1 && settled(planes) }
        }
        val got = handled.toList()
        val at = mine.map { got.indexOf(it) }
        // Between two honest frames, the one flood frame ahead of it in the waiters' line: the flood gets its share only.
        at.zipWithNext().forEach { (a, b) -> assertEquals("the flood took more than its share: $got", 2, b - a) }
    }

    private fun pageFrame(id: String): InboundFrame {
        val wire = WireCodec.decodeWire(frame(id, senderId = "author01"))!!
        return InboundFrame(wire, WireCodec.decodeEnvelope(wire.signed)!!, "author01")
    }

    private companion object {
        const val PEER = "peer0001"
        const val SLOTS = 2
        const val SETTLE_MS = 200L
        const val FLOOD = 100
        const val HONEST = 5

        /** Every wait's bound, sized for a loaded runner rather than a workstation (as `MeshManagerTest`). */
        const val AWAIT_MS = 10_000L
    }
}
