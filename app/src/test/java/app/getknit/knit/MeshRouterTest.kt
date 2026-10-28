package app.getknit.knit

import app.getknit.knit.mesh.DropReason
import app.getknit.knit.mesh.FakeLoopTransport
import app.getknit.knit.mesh.FileMeta
import app.getknit.knit.mesh.InboundFrame
import app.getknit.knit.mesh.IngressBudget
import app.getknit.knit.mesh.MeshMetrics
import app.getknit.knit.mesh.MeshRouter
import app.getknit.knit.mesh.MeshTransport
import app.getknit.knit.mesh.Peer
import app.getknit.knit.mesh.ReceivedFile
import app.getknit.knit.mesh.TransportHealth
import app.getknit.knit.mesh.TransportKind
import app.getknit.knit.mesh.protocol.DEFAULT_TTL
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.GroupInfo
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import app.getknit.knit.mesh.protocol.frameIdOf
import app.getknit.knit.mesh.spool.ScopeSync
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class MeshRouterTest {
    /** Transport double that records what the router sends. */
    private class RecordingTransport(
        neighborIds: Set<String>,
    ) : MeshTransport {
        val sent = mutableListOf<Pair<WireEnvelope, Peer?>>()
        private val _neighbors = MutableStateFlow(neighborIds.map { Peer(it) }.toSet())
        override val neighbors = _neighbors.asStateFlow()
        override val health = MutableStateFlow(TransportHealth.Healthy).asStateFlow()
        private val inboundFlow = MutableSharedFlow<InboundFrame>(extraBufferCapacity = 16)
        override val inbound = inboundFlow.asSharedFlow()

        /** Hands the router a frame as if the radio had just received it. */
        fun emit(frame: InboundFrame) = inboundFlow.tryEmit(frame)

        override val incomingFiles = emptyFlow<ReceivedFile>()

        override fun start() = Unit

        override fun stop() = Unit

        override fun heal() = Unit

        override suspend fun send(
            wire: WireEnvelope,
            to: Peer?,
        ) {
            sent += wire to to
        }

        override suspend fun sendFile(
            file: File,
            to: Peer,
            meta: FileMeta,
        ): Boolean = true
    }

    /** Builds a (wrapper, envelope) pair for an addressed-or-broadcast frame, chat by default, under [label]'s id. */
    private fun frame(
        label: String,
        ttl: Int = DEFAULT_TTL,
        hops: Int = 0,
        recipientId: String? = null,
        relay: Boolean = true,
        type: String = FrameType.CHAT,
        group: GroupInfo? = null,
    ): Pair<WireEnvelope, RelayEnvelope> {
        val env =
            RelayEnvelope(
                type = type,
                id = frameIdOf(label),
                senderId = "a",
                sentAt = 0L,
                recipientId = recipientId,
                group = group,
                payload = ByteArray(0),
            )
        val wire = WireEnvelope(ttl = ttl, hops = hops, relay = relay, sig = ByteArray(0), signed = WireCodec.encodeEnvelope(env))
        return wire to env
    }

    @Test
    fun startForwardsTheFrameKindToOnDeliver() =
        runTest(UnconfinedTestDispatcher()) {
            // The regression this pins: the collect used to destructure the frame positionally, which silently
            // drops any component past the ones named — every frame would have read as TransportKind.Other.
            val transport = RecordingTransport(setOf("b"))
            val kinds = mutableListOf<TransportKind>()
            val router = MeshRouter(transport, backgroundScope, jitter = { 0L }) { _, _, _, kind -> kinds += kind }
            router.start()
            advanceUntilIdle()

            val (wire, env) = frame("m1")
            assertTrue(transport.emit(InboundFrame(wire, env, "b", kind = TransportKind.LoRa)))
            advanceUntilIdle()

            assertEquals(listOf(TransportKind.LoRa), kinds)
        }

    @Test
    fun handleInboundPassesTheKindToOnDeliverAndDefaultsToOther() =
        runTest {
            val kinds = mutableListOf<TransportKind>()
            val router = MeshRouter(RecordingTransport(setOf("b")), this, jitter = { 0L }) { _, _, _, kind -> kinds += kind }
            val (w1, e1) = frame("m1")
            val (w2, e2) = frame("m2")

            router.handleInbound(w1, e1, fromNodeId = "b", kind = TransportKind.LoRa)
            // A source with no radio (the Internet plane's bridge) calls the three-argument form.
            router.handleInbound(w2, e2, fromNodeId = "b")
            advanceUntilIdle()

            assertEquals(listOf(TransportKind.LoRa, TransportKind.Other), kinds)
        }

    @Test
    fun deliversNewFrameOnceAndDropsDuplicates() =
        runTest {
            val transport = RecordingTransport(setOf("b", "c"))
            val delivered = mutableListOf<String>()
            val router = MeshRouter(transport, this) { _, env, _, _ -> delivered += env.id }

            val (wire, env) = frame("m1")
            router.handleInbound(wire, env, "b")
            router.handleInbound(wire, env, "b")

            assertEquals(1, delivered.size)
        }

    @Test
    fun relaysToOtherNeighborsExcludingSourceAndIncrementsHop() =
        runTest {
            val transport = RecordingTransport(setOf("b", "c"))
            val router = MeshRouter(transport, this, jitter = { 0L }) { _, _, _, _ -> }

            val (wire, env) = frame("m1")
            router.handleInbound(wire, env, fromNodeId = "b")
            advanceUntilIdle() // relay is scheduled after a jitter window, so let it fire

            assertEquals(1, transport.sent.size)
            val (relayed, to) = transport.sent.single()
            assertEquals("c", to?.nodeId)
            assertEquals(1, relayed.hops)
        }

    @Test
    fun floodsAddressedDmFramesSoTheyReachAnOutOfRangeRecipient() =
        runTest {
            // DMs have no routing table: an addressed frame must still flood like room traffic, or it could
            // never reach a recipient that isn't a direct neighbor. (Recipient-only delivery is in MeshManager.)
            val transport = RecordingTransport(setOf("b", "c"))
            val router = MeshRouter(transport, this, jitter = { 0L }) { _, _, _, _ -> }

            val (wire, env) = frame("dm1", recipientId = "z")
            router.handleInbound(wire, env, fromNodeId = "b")
            advanceUntilIdle()

            val (relayed, to) = transport.sent.single()
            assertEquals("c", to?.nodeId)
            assertEquals(1, relayed.hops)
        }

    @Test
    fun doesNotRelayOnceTtlReached() =
        runTest {
            val transport = RecordingTransport(setOf("b", "c"))
            val router = MeshRouter(transport, this) { _, _, _, _ -> }

            val (wire, env) = frame("m1", ttl = 3, hops = 3)
            router.handleInbound(wire, env, fromNodeId = "b")

            assertEquals(0, transport.sent.size)
        }

    @Test
    fun clampsForgedOversizedTtlOnRelay() =
        runTest {
            val transport = RecordingTransport(setOf("b", "c"))
            val router = MeshRouter(transport, this, jitter = { 0L }) { _, _, _, _ -> }

            val (wire, env) = frame("m1", ttl = Int.MAX_VALUE, hops = 0)
            router.handleInbound(wire, env, fromNodeId = "b")
            advanceUntilIdle()

            val (relayed, _) = transport.sent.single()
            assertEquals(DEFAULT_TTL, relayed.ttl) // forwarded with a sane, bounded ttl
            assertEquals(1, relayed.hops)
        }

    @Test
    fun doesNotRelayForgedTtlOnceHopsReachLocalDefault() =
        runTest {
            val transport = RecordingTransport(setOf("b", "c"))
            val router = MeshRouter(transport, this, jitter = { 0L }) { _, _, _, _ -> }

            val (wire, env) = frame("m1", ttl = Int.MAX_VALUE, hops = DEFAULT_TTL)
            router.handleInbound(wire, env, fromNodeId = "b")
            advanceUntilIdle()

            assertEquals(0, transport.sent.size)
        }

    @Test
    fun deliversButDoesNotRelayNonRelayableControlFrame() =
        runTest {
            val transport = RecordingTransport(setOf("b", "c"))
            val delivered = mutableListOf<String>()
            val router = MeshRouter(transport, this) { _, env, _, _ -> delivered += env.id }

            val (wire, env) = frame("req1", relay = false)
            router.handleInbound(wire, env, fromNodeId = "b")

            assertEquals(1, delivered.size) // handled locally
            assertEquals(0, transport.sent.size) // but never flooded onward
        }

    /**
     * The blob traffic is point-to-point by type: a copy marked for relay is handed to the pipeline (which refuses it)
     * and flooded nowhere. A chat in the same wrapper, from the same neighbour, is relayed: the router would have.
     */
    @Test
    fun neverFloodsTheUnsignedBlobTrafficWhateverItsWrapperSays() =
        runTest {
            val transport = RecordingTransport(setOf("b", "c"))
            val delivered = mutableListOf<String>()
            val router = MeshRouter(transport, this, jitter = { 0L }) { _, env, _, _ -> delivered += env.type }
            val blobTypes =
                listOf(FrameType.BLOB_REQ, FrameType.BLOB_HAVE, FrameType.BLOB_HAS, FrameType.BLOB_PROGRESS, FrameType.BLOB_CANCEL)

            blobTypes.forEach { type ->
                val (wire, env) = frame("flooded-$type", relay = true, type = type)
                router.handleInbound(wire, env, fromNodeId = "b")
            }
            advanceUntilIdle()

            assertEquals(blobTypes, delivered)
            assertEquals(0, transport.sent.size)
            val (wire, env) = frame("control", relay = true)
            router.handleInbound(wire, env, fromNodeId = "b")
            advanceUntilIdle()
            assertEquals(listOf("c"), transport.sent.map { it.second?.nodeId })
        }

    /**
     * Issue #48: a point-to-point frame is never flooded, but it is not finished either. Addressed to a peer
     * this node holds a **live link** to, it takes that one hop — which is how a far pocket's ✓✓ reaches a
     * board-less author sitting behind the gateway that heard it off the air.
     */
    @Test
    fun handsAPointToPointFrameTheLastHopToALinkedAddressee() =
        runTest {
            val transport = RecordingTransport(setOf("b", "z"))
            val metrics = MeshMetrics()
            val router = MeshRouter(transport, this, metrics = metrics, jitter = { 0L }) { _, _, _, _ -> }

            val (wire, env) = frame("tick1", recipientId = "z", relay = false)
            router.handleInbound(wire, env, fromNodeId = "b")
            advanceUntilIdle()

            assertEquals(1, transport.sent.size)
            val (sent, to) = transport.sent.single()
            assertEquals(Peer("z"), to)
            assertEquals(1, sent.hops) // the hop is counted, exactly as a relay counts one
            assertEquals(false, sent.relay) // …and it still goes no further at the addressee
            assertEquals(1L, metrics.frames().framesHandedOn)
        }

    @Test
    fun neverHandsAPointToPointFrameBackToTheHopItCameFrom() =
        runTest {
            val transport = RecordingTransport(setOf("b"))
            val router = MeshRouter(transport, this, jitter = { 0L }) { _, _, _, _ -> }

            val (wire, env) = frame("tick1", recipientId = "b", relay = false)
            router.handleInbound(wire, env, fromNodeId = "b")
            advanceUntilIdle()

            assertEquals(0, transport.sent.size)
        }

    /** A link, never a sighting (ADR 044): a peer we are not linked to has no path we can hand anything over. */
    @Test
    fun doesNotHandOnToAnAddresseeWeHoldNoLinkTo() =
        runTest {
            val transport = RecordingTransport(setOf("b", "c"))
            val router = MeshRouter(transport, this, jitter = { 0L }) { _, _, _, _ -> }

            val (wire, env) = frame("tick1", recipientId = "z", relay = false)
            router.handleInbound(wire, env, fromNodeId = "b")
            advanceUntilIdle()

            assertEquals(0, transport.sent.size)
        }

    /**
     * DM-form chat only. A `typing` cue is worthless a moment later, a group-form frame is not this plane's
     * business, and `blobreq`/`keyreq` name no recipient at all — they propagate through their own handlers.
     */
    @Test
    fun doesNotHandOnAFrameThatIsNotDmFormChat() =
        runTest {
            val transport = RecordingTransport(setOf("b", "z"))
            val router = MeshRouter(transport, this, jitter = { 0L }) { _, _, _, _ -> }

            val typing = frame("t1", recipientId = "z", relay = false, type = FrameType.TYPING)
            val keyReq = frame("k1", relay = false, type = FrameType.KEY_REQ)
            val grouped =
                frame(
                    "g1",
                    recipientId = "z",
                    relay = false,
                    group = GroupInfo(id = "g", members = listOf("a", "z"), createdBy = "a"),
                )
            listOf(typing, keyReq, grouped).forEach { (wire, env) -> router.handleInbound(wire, env, fromNodeId = "b") }
            advanceUntilIdle()

            assertEquals(0, transport.sent.size)
        }

    /** The same forged-ttl cap the flood path applies, so a hand-on chain is bounded by hop count too. */
    @Test
    fun doesNotHandOnOnceHopsReachLocalDefault() =
        runTest {
            val transport = RecordingTransport(setOf("b", "z"))
            val router = MeshRouter(transport, this, jitter = { 0L }) { _, _, _, _ -> }

            val (wire, env) = frame("tick1", ttl = Int.MAX_VALUE, hops = DEFAULT_TTL, recipientId = "z", relay = false)
            router.handleInbound(wire, env, fromNodeId = "b")
            advanceUntilIdle()

            assertEquals(0, transport.sent.size)
        }

    /** The hand-on is a hand-off like any other: the Your mesh ledger hears about it (ADR 2026-09.2v2t). */
    @Test
    fun aHandedOnFrameReportsTheAddresseeToOnRelayed() =
        runTest {
            val transport = RecordingTransport(setOf("b", "z"))
            val relayed = mutableListOf<Pair<String, Set<String>>>()
            val router =
                MeshRouter(transport, this, jitter = { 0L }, onRelayed = { env, to -> relayed += env.id to to }) { _, _, _, _ -> }

            val (wire, env) = frame("tick1", recipientId = "z", relay = false)
            router.handleInbound(wire, env, fromNodeId = "b")
            router.handleInbound(wire, env, fromNodeId = "c") // a duplicate is never a second hand-on
            advanceUntilIdle()

            assertEquals(listOf(frameIdOf("tick1") to setOf("z")), relayed)
        }

    @Test
    fun multiHopRelayReachesNodeOutOfDirectRange() =
        runTest(UnconfinedTestDispatcher()) {
            // Topology: a — b — c  (a and c are NOT directly connected)
            val a = FakeLoopTransport("a")
            val b = FakeLoopTransport("b")
            val c = FakeLoopTransport("c")
            a.connect(b)
            b.connect(c)

            val deliveredAtC = mutableListOf<String>()
            val ra = MeshRouter(a, backgroundScope, jitter = { 0L }) { _, _, _, _ -> }
            val rb = MeshRouter(b, backgroundScope, jitter = { 0L }) { _, _, _, _ -> }
            val rc = MeshRouter(c, backgroundScope, jitter = { 0L }) { _, env, _, _ -> deliveredAtC += env.id }
            ra.start()
            rb.start()
            rc.start()

            val (wire, env) = frame("m1")
            ra.originate(wire, env.id)
            advanceUntilIdle() // let b's jittered relay fire so the frame reaches c

            assertEquals(listOf(frameIdOf("m1")), deliveredAtC)
        }

    /**
     * `onRelayed` is the fact behind the Your mesh screen's "passed along": a relay that fired AND went to at
     * least one neighbor reports the frame and exactly the neighbors it was sent to (split horizon applied);
     * a relay with nobody left to send to, or one the overhear suppression cancelled, reports nothing.
     */
    @Test
    fun aFiredRelayReportsTheEnvelopeAndTheNeighborsItWentTo() =
        runTest {
            val transport = RecordingTransport(setOf("b", "c", "d"))
            val relayed = mutableListOf<Pair<String, Set<String>>>()
            val router =
                MeshRouter(transport, this, jitter = { 0L }, onRelayed = { env, to -> relayed += env.id to to }) { _, _, _, _ -> }

            val (wire, env) = frame("m1")
            router.handleInbound(wire, env, fromNodeId = "b")
            advanceUntilIdle()

            assertEquals(listOf(frameIdOf("m1") to setOf("c", "d")), relayed)
        }

    @Test
    fun aRelayToNobodyIsNotReported() =
        runTest {
            val transport = RecordingTransport(setOf("b")) // the only neighbor is the one we heard it from
            val relayed = mutableListOf<String>()
            val router = MeshRouter(transport, this, jitter = { 0L }, onRelayed = { env, _ -> relayed += env.id }) { _, _, _, _ -> }

            val (wire, env) = frame("m1")
            router.handleInbound(wire, env, fromNodeId = "b")
            advanceUntilIdle()

            assertTrue(transport.sent.isEmpty())
            assertTrue(relayed.isEmpty())
        }

    @Test
    fun aSuppressedRelayIsNotReported() =
        runTest {
            val transport = RecordingTransport(setOf("b", "c", "d"))
            val relayed = mutableListOf<String>()
            val router =
                MeshRouter(
                    transport,
                    this,
                    jitterWindowMs = 150L,
                    suppressThreshold = 2,
                    jitter = { 100L },
                    onRelayed = { env, _ -> relayed += env.id },
                ) { _, _, _, _ -> }

            val (wire, env) = frame("m1")
            router.handleInbound(wire, env, fromNodeId = "b")
            advanceTimeBy(40)
            router.handleInbound(wire, env, fromNodeId = "c")
            advanceUntilIdle()

            assertTrue(relayed.isEmpty())
        }

    @Test
    fun suppressesRelayWhenDuplicateOverheardDuringJitterWindow() =
        runTest {
            val transport = RecordingTransport(setOf("b", "c", "d"))
            val metrics = MeshMetrics()
            val router =
                MeshRouter(
                    transport,
                    this,
                    metrics = metrics,
                    jitterWindowMs = 150L,
                    suppressThreshold = 2,
                    jitter = { 100L },
                ) { _, _, _, _ -> }

            val (wire, env) = frame("m1")
            router.handleInbound(wire, env, fromNodeId = "b")
            advanceTimeBy(40) // still inside the 100ms jitter window
            router.handleInbound(wire, env, fromNodeId = "c") // overheard duplicate → suppress
            advanceUntilIdle()

            assertEquals(0, transport.sent.size)
            assertEquals(1, metrics.frames().framesSuppressed)
        }

    /**
     * A second copy from the **same** neighbor is not an overhear. The link-up profile exchange produces
     * exactly this pair — the live push, then the custody re-serve of the same frame after the digest
     * exchange — and counting it cancelled the relay that carries a newcomer's profile past the first hop.
     */
    @Test
    fun doesNotSuppressOnADuplicateFromTheSameNeighbor() =
        runTest {
            val transport = RecordingTransport(setOf("b", "c", "d"))
            val metrics = MeshMetrics()
            val router =
                MeshRouter(
                    transport,
                    this,
                    metrics = metrics,
                    jitterWindowMs = 150L,
                    suppressThreshold = 2,
                    jitter = { 100L },
                ) { _, _, _, _ -> }

            val (wire, env) = frame("m1")
            router.handleInbound(wire, env, fromNodeId = "b")
            advanceTimeBy(40)
            router.handleInbound(wire, env, fromNodeId = "b") // the same source again → still one neighbor
            advanceUntilIdle()

            assertEquals(setOf("c", "d"), transport.sent.mapNotNull { it.second?.nodeId }.toSet())
            assertEquals(1, metrics.frames().framesRelayed)
            assertEquals(0, metrics.frames().framesSuppressed)
        }

    /**
     * A copy that came off a spool is not an overhear either (ADR 2026-09.dcah): it says a relay holds the
     * frame, not that any radio neighbour heard it — and it is routinely our own push echoed back. Once the
     * DM scope derived on the session's confirmation, that echo landed inside the jitter window and cancelled
     * the one radio hop a carrier behind us depended on (`InternetPlaneLabTest`'s photo-to-the-neighbour case).
     */
    @Test
    fun doesNotSuppressOnADuplicateOffASpool() =
        runTest {
            val transport = RecordingTransport(setOf("b", "c", "d"))
            val metrics = MeshMetrics()
            val router =
                MeshRouter(
                    transport,
                    this,
                    metrics = metrics,
                    jitterWindowMs = 150L,
                    suppressThreshold = 2,
                    jitter = { 100L },
                ) { _, _, _, _ -> }

            val (wire, env) = frame("m1")
            router.handleInbound(wire, env, fromNodeId = "b")
            advanceTimeBy(40)
            router.handleInbound(wire, env, fromNodeId = "${ScopeSync.SPOOL_SOURCE_PREFIX}wss://relay.example") // the echo
            advanceUntilIdle()

            assertEquals(setOf("c", "d"), transport.sent.mapNotNull { it.second?.nodeId }.toSet())
            assertEquals(1, metrics.frames().framesRelayed)
            assertEquals(1, metrics.frames().framesDeduped)
            assertEquals(0, metrics.frames().framesSuppressed)
        }

    /**
     * Issue #84, the other order: the relay's copy is our first sighting and the originator's radio copy lands
     * inside the jitter. Seeding the pending relay with the spool made that radio copy the second "neighbour"
     * and cancelled the one hop a radio-only carrier behind us depended on.
     */
    @Test
    fun doesNotSuppressWhenTheSpoolCopyWasFirst() =
        runTest {
            val transport = RecordingTransport(setOf("b", "c", "d"))
            val metrics = MeshMetrics()
            val router =
                MeshRouter(
                    transport,
                    this,
                    metrics = metrics,
                    jitterWindowMs = 150L,
                    suppressThreshold = 2,
                    jitter = { 100L },
                ) { _, _, _, _ -> }

            val (wire, env) = frame("m1")
            router.handleInbound(wire, env, fromNodeId = "${ScopeSync.SPOOL_SOURCE_PREFIX}wss://relay.example")
            advanceTimeBy(40)
            router.handleInbound(wire, env, fromNodeId = "b") // the originator's radio copy
            advanceUntilIdle()

            assertEquals(setOf("c", "d"), transport.sent.mapNotNull { it.second?.nodeId }.toSet())
            assertEquals(1, metrics.frames().framesRelayed)
            assertEquals(1, metrics.frames().framesDeduped)
            assertEquals(0, metrics.frames().framesSuppressed)
        }

    /**
     * C-7.8-15: a typing cue that reaches us over a radio link and as a sig off a relay (`ttl = 0`, `relay = false`) is
     * delivered once, whichever copy is first, and neither copy is handed on.
     */
    @Test
    fun aTypingCueOverARadioAndASigIsDeliveredOnceWhicheverIsFirst() =
        runTest {
            val transport = RecordingTransport(setOf("b", "c"))
            val delivered = mutableListOf<Pair<String, String>>()
            val router = MeshRouter(transport, this, jitter = { 0L }) { _, env, from, _ -> delivered += env.id to from }
            val spool = "${ScopeSync.SPOOL_SOURCE_PREFIX}wss://relay.example"

            val (radio, env) = frame("ty1", recipientId = "me", relay = false, type = FrameType.TYPING)
            val sig = WireEnvelope(ttl = 0, hops = 0, relay = false, sig = radio.sig, signed = radio.signed)
            router.handleInbound(radio, env, fromNodeId = "b")
            router.handleInbound(sig, env, fromNodeId = spool)
            val (radio2, env2) = frame("ty2", recipientId = "me", relay = false, type = FrameType.TYPING)
            router.handleInbound(WireEnvelope(ttl = 0, hops = 0, relay = false, sig = radio2.sig, signed = radio2.signed), env2, spool)
            router.handleInbound(radio2, env2, fromNodeId = "b")
            advanceUntilIdle()

            assertEquals(listOf(env.id to "b", env2.id to spool), delivered)
            assertEquals(0, transport.sent.size)
        }

    /** A spool-first relay is still suppressed — by two radio neighbours, exactly as a radio-first one is. */
    @Test
    fun aSpoolFirstRelayIsStillSuppressedByTwoRadioNeighbours() =
        runTest {
            val transport = RecordingTransport(setOf("b", "c", "d"))
            val metrics = MeshMetrics()
            val router =
                MeshRouter(
                    transport,
                    this,
                    metrics = metrics,
                    jitterWindowMs = 150L,
                    suppressThreshold = 2,
                    jitter = { 100L },
                ) { _, _, _, _ -> }

            val (wire, env) = frame("m1")
            router.handleInbound(wire, env, fromNodeId = "${ScopeSync.SPOOL_SOURCE_PREFIX}wss://relay.example")
            advanceTimeBy(20)
            router.handleInbound(wire, env, fromNodeId = "b")
            advanceTimeBy(20)
            router.handleInbound(wire, env, fromNodeId = "c")
            advanceUntilIdle()

            assertTrue(transport.sent.isEmpty())
            assertEquals(0, metrics.frames().framesRelayed)
            assertEquals(1, metrics.frames().framesSuppressed)
        }

    @Test
    fun relaysAfterJitterWhenNoDuplicateOverheard() =
        runTest {
            val transport = RecordingTransport(setOf("b", "c", "d"))
            val metrics = MeshMetrics()
            val router =
                MeshRouter(
                    transport,
                    this,
                    metrics = metrics,
                    jitterWindowMs = 150L,
                    suppressThreshold = 2,
                    jitter = { 100L },
                ) { _, _, _, _ -> }

            val (wire, env) = frame("m1")
            router.handleInbound(wire, env, fromNodeId = "b")
            advanceUntilIdle()

            assertEquals(setOf("c", "d"), transport.sent.mapNotNull { it.second?.nodeId }.toSet())
            assertTrue(transport.sent.all { it.first.hops == 1 })
            assertEquals(1, metrics.frames().framesRelayed)
            assertEquals(0, metrics.frames().framesSuppressed)
        }

    @Test
    fun aRoomPostOverTheLinksBudgetIsRefusedBeforeDeliveryRelayAndDedup() =
        runTest {
            val transport = RecordingTransport(setOf("b", "c"))
            val metrics = MeshMetrics()
            val delivered = mutableListOf<String>()
            var now = 0L
            val budget = IngressBudget(burst = 1, perMinute = 60, clock = { now })
            val router =
                MeshRouter(transport, this, metrics = metrics, jitter = { 0L }, budget = budget) { _, env, _, _ ->
                    delivered +=
                        env.id
                }

            val (w1, e1) = frame("m1")
            val (w2, e2) = frame("m2")
            router.handleInbound(w1, e1, fromNodeId = "b") // spends b's one token
            router.handleInbound(w2, e2, fromNodeId = "b") // refused: not delivered, not relayed, not seen
            advanceUntilIdle()

            assertEquals(listOf(frameIdOf("m1")), delivered)
            assertEquals(1, transport.sent.size)
            assertEquals(1L, metrics.frames().dropsByReason[DropReason.INGRESS_REFUSED])

            // A refused frame was never marked seen, so the custody re-serve brings it through once the bucket
            // refills — a delay, not a veto.
            now += 1_000L
            router.handleInbound(w2, e2, fromNodeId = "b")
            advanceUntilIdle()
            assertEquals(listOf(frameIdOf("m1"), frameIdOf("m2")), delivered)
        }

    @Test
    fun duplicatesAndAddressedFramesAreNotMetered() =
        runTest {
            val transport = RecordingTransport(setOf("b", "c"))
            val metrics = MeshMetrics()
            val delivered = mutableListOf<String>()
            val router =
                MeshRouter(
                    transport,
                    this,
                    metrics = metrics,
                    jitter = { 0L },
                    budget = IngressBudget(burst = 1, perMinute = 0),
                ) { _, env, _, _ ->
                    delivered +=
                        env.id
                }

            val (w1, e1) = frame("m1")
            router.handleInbound(w1, e1, fromNodeId = "b")
            // Every later copy of m1 is a dedup, never a token: a re-serve from the same link and an overhear
            // from another both cost nothing.
            router.handleInbound(w1, e1, fromNodeId = "b")
            router.handleInbound(w1, e1, fromNodeId = "c")
            // A DM over the exhausted link is not a room post, so it is not the meter's business.
            val (wd, ed) = frame("dm1", recipientId = "z")
            router.handleInbound(wd, ed, fromNodeId = "b")
            advanceUntilIdle()

            assertEquals(listOf(frameIdOf("m1"), frameIdOf("dm1")), delivered)
            assertEquals(null, metrics.frames().dropsByReason[DropReason.INGRESS_REFUSED])
            assertEquals(2L, metrics.frames().framesDeduped)
        }

    @Test
    fun aFrameWhoseIdIsNotCanonicalIsRefusedBeforeDeliveryRelayAndDedup() =
        runTest {
            // #178: SQLite drops a leading U+FEFF from every id Android binds, so this twin would pass the in-memory
            // dedup and then take the real frame's custody and message rows. The door refuses it first.
            val transport = RecordingTransport(setOf("b", "c"))
            val metrics = MeshMetrics()
            val delivered = mutableListOf<String>()
            val router = MeshRouter(transport, this, metrics = metrics, jitter = { 0L }) { _, env, _, _ -> delivered += env.id }
            val (wire, env) = frame("m1")
            val twin = RelayEnvelope(type = env.type, id = "\uFEFF" + env.id, senderId = "a", payload = ByteArray(0))
            val twinWire = WireEnvelope(sig = ByteArray(0), signed = WireCodec.encodeEnvelope(twin))

            router.handleInbound(twinWire, twin, fromNodeId = "b")
            advanceUntilIdle()
            assertEquals(emptyList<String>(), delivered)
            assertTrue("a refused frame is never relayed", transport.sent.isEmpty())
            assertEquals(1L, metrics.frames().dropsByReason[DropReason.NONCANONICAL_ID])

            // Refused, not seen: the real frame behind it is delivered and relayed as if the twin had never come, and
            // the twin is refused again, not deduplicated.
            router.handleInbound(wire, env, fromNodeId = "b")
            router.handleInbound(twinWire, twin, fromNodeId = "c")
            advanceUntilIdle()
            assertEquals(listOf(env.id), delivered)
            assertEquals(setOf("c"), transport.sent.mapNotNull { it.second?.nodeId }.toSet())
            assertEquals(2L, metrics.frames().dropsByReason[DropReason.NONCANONICAL_ID])
            assertEquals(0L, metrics.frames().framesDeduped)
        }

    /**
     * #141 finding 11: once the dedup has admitted a frame, its delivery is not the caller's to cut short. The Internet
     * plane hands a pulled frame to [MeshRouter.handleInbound] on its worker's own coroutine, and a reconcile that no
     * longer wants the relay cancels that coroutine; the frame is already seen, so the copy being delivered is the only
     * one this node takes for a SeenSet window. The radio's copy arriving after the cut must not be all that is left.
     */
    @Test
    fun aDeliveryWhoseCallerIsCancelledStillFinishes() =
        runTest {
            val parked = CompletableDeferred<Unit>()
            val proceed = CompletableDeferred<Unit>()
            val finished = mutableListOf<String>()
            val transport = RecordingTransport(setOf("b"))
            val router =
                MeshRouter(transport, backgroundScope, jitter = { 0L }) { _, env, _, _ ->
                    parked.complete(Unit)
                    proceed.await()
                    finished += env.id
                }
            val (wire, env) = frame("m1")
            val worker = launch { router.handleInbound(wire, env, fromNodeId = "${ScopeSync.SPOOL_SOURCE_PREFIX}wss://relay.example") }
            parked.await()
            worker.cancel()
            proceed.complete(Unit)
            advanceUntilIdle()
            // The relay runs past the cut too: a spool source puts nobody in heardFrom, so "b" is its one target. It is
            // launched on backgroundScope, which advanceUntilIdle leaves waiting once the foreground is idle.
            runCurrent()
            assertEquals("the cut delivery's relay never ran", 1, transport.sent.size)
            // The radio's copy, after the cut: deduplicated, as it must be once the first delivery has run.
            router.handleInbound(wire, env, fromNodeId = "b")
            advanceUntilIdle()

            assertEquals(listOf(env.id), finished)
        }
}
