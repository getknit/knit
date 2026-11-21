package app.getknit.knit.mesh.lora

import app.getknit.knit.mesh.MeshMetrics
import app.getknit.knit.mesh.crypto.b64
import app.getknit.knit.mesh.crypto.b64d
import app.getknit.knit.mesh.protocol.ChatContent
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Compact DMs over the fake air (ADR 2026-10.x9fa): one pair-sealed unicast to the recipient's board, its outcome, the
 * marks it leaves, the resend on hearing and the re-probe — against fakes of the four seams. The fake air models the
 * firmware's unicast: delivery to the destination alone, its board's ACK, the retries and the give-up in real seconds.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("LargeClass") // one rig, many scenarios
class LoraDirectTransportTest {
    /**
     * A stand-in for the pair seal: the real packet's size (the 51-byte overhead) around readable fields, so a test can
     * see who sealed what for whom. A packet for anyone else opens as a reflection would (auth failed); one from a node
     * this side does not know is a stranger's.
     */
    private class FakeSeal(
        private val self: String,
        private val known: () -> Set<String>,
    ) : LoraDirectCrypto {
        var seals = 0
        var clockRefuses = false

        override fun seal(
            peerId: String,
            frameId: String,
            sentAtMs: Long,
            plaintext: ByteArray,
        ): ByteArray {
            seals++
            val header = "$self|$peerId|$frameId|$sentAtMs|".encodeToByteArray()
            check(header.size < LORA_DIRECT_OVERHEAD)
            return byteArrayOf(LORA_DIRECT_TAG) + header.copyOf(LORA_DIRECT_OVERHEAD - 1) + plaintext
        }

        override fun open(packet: ByteArray): LoraDirectOpen {
            if (packet.size < LORA_DIRECT_OVERHEAD) return LoraDirectOpen.Malformed
            val fields =
                packet
                    .copyOfRange(1, LORA_DIRECT_OVERHEAD)
                    .decodeToString()
                    .trimEnd('\u0000')
                    .split('|')
            val from = fields[0]
            val plaintext = packet.copyOfRange(LORA_DIRECT_OVERHEAD, packet.size)
            return when {
                from !in known() -> LoraDirectOpen.Stranger
                fields[1] != self -> LoraDirectOpen.AuthFailed
                clockRefuses -> LoraDirectOpen.ClockRefused
                else -> LoraDirectOpen.Opened(OpenedDirect(from, fields[2], fields[3].toLong(), plaintext))
            }
        }
    }

    /**
     * Our own DMs without their ✓✓, newest first, per peer; a test acks one by removing it. [took] is what the plane's
     * `onRadioReached` reported, read into [DirectDm.boardTook] as `MeshManager` reads `loraRadioReached`.
     */
    private class FakeSource : LoraDirectSource {
        val unacked = mutableMapOf<String, MutableList<DirectDm>>()
        val took = mutableSetOf<String>()

        fun hold(
            peer: String,
            dm: DirectDm,
        ) {
            unacked.getOrPut(peer) { mutableListOf() }.add(0, dm)
        }

        fun ack(frameId: String) = unacked.values.forEach { list -> list.removeAll { it.frameId == frameId } }

        override suspend fun unackedTo(
            peerId: String,
            limit: Int,
            untakenOnly: Boolean,
            maxBytes: Int,
        ): List<DirectDm> =
            unacked[peerId]
                .orEmpty()
                .filter { (!untakenOnly || it.frameId !in took) && it.plaintext.size <= maxBytes }
                .take(limit)
                .map { DirectDm(it.frameId, it.sentAtMs, it.plaintext, boardTook = it.frameId in took) }

        override suspend fun isUnacked(frameId: String): Boolean = unacked.values.any { list -> list.any { it.frameId == frameId } }
    }

    private class Node(
        val id: String,
        val transport: LoraMeshTransport,
        val link: FakeMeshtasticLink,
        val metrics: MeshMetrics,
        val seal: FakeSeal,
        val source: FakeSource,
        val delivered: MutableList<OpenedDirect>,
        val boards: MutableStateFlow<Map<String, LoraBoardHolder>?>,
        val config: MutableStateFlow<LoraConfig?>,
        val pace: LoraPacePolicy,
        edges: Edges,
    ) {
        val reached = edges.reached
        val reachedPeers = edges.reachedPeers
        val undelivered = edges.undelivered
        val boardAcked = edges.boardAcked
    }

    /** What the plane told the app through its constructor edges, in order. */
    private class Edges {
        /** Every `onRadioReached` the plane made: the frame id, and whether the ack came late. */
        val reached = mutableListOf<Pair<String, Boolean>>()

        /** The peer each `onRadioReached` named, in the same order. */
        val reachedPeers = mutableListOf<String>()

        /** Every `onDirectUndelivered` the plane made: the ✓✓'s frame id and its peer. */
        val undelivered = mutableListOf<Pair<String, String>>()

        /** Every `onBoardAcked` the plane made: the compact frame of ours, of any kind, and the peer whose own board acked it. */
        val boardAcked = mutableListOf<Pair<String, String>>()
    }

    private val wallBase = 1_700_000_000_000L

    /** A compact ✓✓ in these tests is a body "ack:<id>", applied to our own unacked set as the pipeline would. */
    private fun inboxOf(
        delivered: MutableList<OpenedDirect>,
        source: FakeSource,
    ) = LoraDirectInbox { opened ->
        delivered += opened
        opened.plaintext
            .decodeToString()
            .takeIf { it.startsWith("ack:") }
            ?.let { source.ack(it.removePrefix("ack:")) }
    }

    private fun TestScope.node(
        air: FakeMeshtasticAir,
        nodeNum: UInt,
        id: String,
        boards: Map<String, LoraBoardHolder>? = emptyMap(),
        knitIndex: Int = 1,
        scope: CoroutineScope = backgroundScope,
        state: LoraPlaneState = LoraPlaneState.None,
        publicKey: String? = null,
        firmware: String = "2.5.0",
        signaturePolicy: SignaturePolicy? = null,
        knitAead: Boolean = false,
        licensed: Boolean = false,
        log: (String) -> Unit = {},
    ): Node {
        val link =
            FakeMeshtasticLink(
                nodeNum,
                air,
                firmware = firmware,
                publicKey = publicKey,
                scope = scope,
                signaturePolicy = signaturePolicy,
                knitAead = knitAead,
                licensed = licensed,
            ).apply { readyOnStart = false }
        val metrics = MeshMetrics()
        val boardFlow = MutableStateFlow(boards)
        val seal = FakeSeal(id) { boardFlow.value.orEmpty().keys }
        val source = FakeSource()
        val delivered = mutableListOf<OpenedDirect>()
        val config = MutableStateFlow<LoraConfig?>(LoraConfig("AA:$nodeNum", knitIndex))
        val pace = LoraPacePolicy(minGapMs = 0)
        val edges = Edges()
        val transport =
            LoraMeshTransport(
                selfId = { id },
                link = link,
                config = config,
                crypto = seal,
                inbox = inboxOf(delivered, source),
                source = source,
                boardMap = boardFlow.filterNotNull(),
                onRadioReached = { frameId, peerId, late ->
                    edges.reached += frameId to late
                    edges.reachedPeers += peerId
                    source.took += frameId
                },
                onBoardAcked = { frameId, peerId -> edges.boardAcked += frameId to peerId },
                onDirectUndelivered = { frameId, peerId -> edges.undelivered += frameId to peerId },
                scope = scope,
                metrics = metrics,
                state = state,
                clock = { testScheduler.currentTime },
                wallClock = { wallBase + testScheduler.currentTime },
                pace = pace,
                log = log,
            )
        return Node(
            id,
            transport,
            link,
            metrics,
            seal,
            source,
            delivered,
            boardFlow,
            config,
            pace,
            edges,
        )
    }

    private fun TestScope.up(vararg nodes: Node) {
        nodes.forEach { it.transport.start() }
        runCurrent()
        nodes.forEach { it.link.readyProvisioned(knitIndex = it.config.value?.channelIndex ?: 1) }
        runCurrent()
    }

    private fun holder(
        node: UInt,
        direct: Boolean = true,
        version: Long = 1,
    ) = LoraBoardHolder(BoardAddr(node), direct, version)

    private fun TestScope.pair(
        air: FakeMeshtasticAir,
        firmware: String = "2.5.0",
        signaturePolicy: SignaturePolicy? = null,
        log: (String) -> Unit = {},
    ): Pair<Node, Node> {
        val a = node(air, 1u, "alice", mapOf("bob" to holder(2u)), firmware = firmware, signaturePolicy = signaturePolicy, log = log)
        val b = node(air, 2u, "bob", mapOf("alice" to holder(1u)), firmware = firmware, signaturePolicy = signaturePolicy)
        up(a, b)
        return a to b
    }

    private fun TestScope.settle(ms: Long = 5_000) {
        advanceTimeBy(ms)
        runCurrent()
    }

    private fun Node.dm(
        to: String,
        frameId: String,
        body: String = "hi",
        kind: DirectKind = DirectKind.MESSAGE,
        sentAt: Long = 0,
    ) {
        val plaintext = body.encodeToByteArray()
        if (kind == DirectKind.MESSAGE) source.hold(to, DirectDm(frameId, sentAt, plaintext))
        transport.offer(to, frameId, sentAt, plaintext, kind)
    }

    private val Node.lora get() = metrics.lora()

    // --- the send ---

    @Test
    fun aDmRidesAsOneAckedUnicastOnTheKnitSecondaryAndLandsOnlyWithItsRecipient() =
        runTest {
            val air = FakeMeshtasticAir()
            val (a, b) = pair(air)
            val c = node(air, 3u, "carol", mapOf("alice" to holder(1u)))
            up(c)

            a.dm("bob", "f1", sentAt = wallBase + testScheduler.currentTime)
            settle()

            assertEquals("one packet", 1, a.link.sent.size)
            assertEquals(listOf(2u), a.link.sentTos)
            assertEquals(listOf(true), a.link.sentWantAcks)
            assertEquals("on the bound Knit secondary, never slot 0", listOf(1), a.link.sentChannels)
            assertEquals(listOf(MeshtasticProto.PORT_PRIVATE_APP), a.link.sentPortnums)
            assertEquals(listOf<Int?>(LoraMeshTransport.HOP_LIMIT), a.link.sentHopLimits)
            assertEquals("one Knit airing, no retry", 1, air.knitAirings(1u))
            assertEquals("and the destination's ACK", 1, air.ackAirings(2u))
            assertEquals(1L, a.lora.loraDirectSent)
            assertEquals(1L, a.lora.loraDirectAcked)
            assertEquals(1, a.transport.status.value.direct.reachable)

            assertEquals(listOf("f1"), b.delivered.map { it.frameId })
            assertEquals("alice", b.delivered.single().peerId)
            assertEquals(
                "hi",
                b.delivered
                    .single()
                    .plaintext
                    .decodeToString(),
            )
            assertTrue(
                "a fresh authenticated DM is alice's reach on bob's side",
                b.transport.reachable.value
                    .any { it.nodeId == "alice" },
            )
            assertTrue(
                "a board's ACK is never reach",
                a.transport.reachable.value
                    .isEmpty(),
            )
            assertTrue("carol's phone never sees a unicast to bob", c.delivered.isEmpty())
            assertEquals(0L, c.lora.loraDirectStranger + c.lora.loraDirectAuthFailed)
        }

    @Test
    fun aDmStillAirsWhenTheKnitSlotIsAeadBecausePkiNeverTouchesTheSlot() =
        runTest {
            // #243: a compact DM is PKI-encrypted and its channel set to 0 (`Router.cpp:1189-1210`, `:1366` at v2.8.1), so
            // the Knit slot's `use_aead` and hash never reach it. It airs, lands on bob's index 0, and his board's ACK
            // comes back; only a broadcast on that slot is refused.
            val air = FakeMeshtasticAir()
            val a = node(air, 1u, "alice", mapOf("bob" to holder(2u)), knitAead = true)
            val b = node(air, 2u, "bob", mapOf("alice" to holder(1u)), knitAead = true)
            up(a, b)
            a.dm("bob", "f1")
            settle()
            assertEquals("one packet, written to the bound slot", listOf(1), a.link.sentChannels)
            assertEquals(listOf("f1"), b.delivered.map { it.frameId })
            assertEquals("bob's board acked it", 1, a.transport.status.value.direct.reachable)
            assertEquals(0L, a.metrics.lora().loraSuppressed)
        }

    @Test
    fun aHamBoardsDmIsNeverWrittenToAnAeadKnitSlot() =
        runTest {
            // #243: a board in ham mode does no PKI, so its compact DM really is encrypted under the Knit slot's key and
            // hash — on an AEAD slot, a channel no other Knit board shares. It is refused at send like a broadcast.
            val air = FakeMeshtasticAir()
            val logs = mutableListOf<String>()
            val a = node(air, 1u, "alice", mapOf("bob" to holder(2u)), knitAead = true, licensed = true, log = { logs += it })
            val b = node(air, 2u, "bob", mapOf("alice" to holder(1u)))
            up(a, b)
            a.dm("bob", "f1")
            settle()
            assertTrue("nothing written to the board", a.link.sent.isEmpty())
            assertEquals(1L, a.metrics.lora().loraSuppressed)
            assertTrue(logs.any { it.startsWith("lora send skipped: slot 1 is AEAD") })
            assertTrue(b.delivered.isEmpty())
        }

    @Test
    fun aDmIsPricedUnsignedAndTheReceiverBooksItsBoardsAck() =
        runTest {
            val air = FakeMeshtasticAir()
            val (a, b) = pair(air)
            a.dm("bob", "f1")
            settle()
            val size =
                a.link.sent
                    .single()
                    .size
            val ledger = LoraAirtime()
            assertEquals(
                "one unsigned airing with the PKC overhead: a unicast is never signed, and 2.8 PKI-encrypts it on any slot",
                priced(size),
                a.pace.airtime
                    .snapshot(testScheduler.currentTime)
                    .usedMs,
            )
            assertEquals(
                "bob's board acked on bob's behalf",
                ledger.timeOnAirMs(LoraDirectPolicy.ACK_PAYLOAD_BYTES, LoraAirtime.UNSIGNED),
                b.pace.airtime
                    .snapshot(testScheduler.currentTime)
                    .usedMs,
            )
        }

    @Test
    fun aReceiptAndAReactionAreDirectFramesAndNeitherIsReprobed() =
        runTest {
            val air = FakeMeshtasticAir()
            val (a, _) = pair(air)
            a.dm("bob", "r1", kind = DirectKind.RECEIPT)
            a.dm("bob", "x1", kind = DirectKind.REACTION)
            settle()
            assertEquals(listOf("r1", "x1"), a.link.sent.map { frameIdOf(it) })
            assertTrue("nothing of ours awaits a ✓✓", a.transport.lingerReprobes().isEmpty())
        }

    /**
     * ADR 2026-10.ug4a folded ADR 054's tick into DIRECT and dropped the tick tail: in a window's last quarter a ✓✓ airs
     * like the DM it answers — one unsigned packet, and losing it would cost a re-probe of the whole DM later.
     */
    @Test
    fun inTheWindowsLastQuarterAReceiptAirsLikeADm() =
        runTest {
            val air = FakeMeshtasticAir()
            // Two recipients: to one peer the second offer would only park behind the first, never reach the gate.
            val a = node(air, 1u, "alice", mapOf("bob" to holder(2u), "carol" to holder(3u)))
            up(a)
            val ledger = a.pace.airtime
            val now = testScheduler.currentTime
            val size = LORA_DIRECT_OVERHEAD + 2
            val dmCost = LoraDirectPolicy.AIRINGS * priced(size)
            val total = ledger.allowanceMs()
            // Into the last quarter, with room left for both frames' every airing and not one airing more.
            val one = priced(size)
            while (ledger.snapshot(now).usedMs + 2 * dmCost + one <= total) ledger.record(size, now, PacketPrice.UNSIGNED)
            assertTrue("past three quarters of the window", ledger.snapshot(now).usedMs > total * 3 / 4)
            a.dm("carol", "r1", kind = DirectKind.RECEIPT)
            a.dm("bob", "x1", kind = DirectKind.REACTION)
            settle()
            assertEquals("both went, in their turn", listOf("r1", "x1"), a.link.sent.map { frameIdOf(it) })
            assertEquals(0, a.pace.pending)
        }

    /**
     * ADR 2026-10.ug4a's shared-band brake: with the board reporting a busy channel, a Knit room post waits in the queue
     * while a compact DM still airs.
     */
    @Test
    fun aBusyBandHoldsARoomPostWhileADmStillAirs() =
        runTest {
            val air = FakeMeshtasticAir()
            val (a, _) = pair(air)
            a.link.boardAir.value = BoardAir(channelUtilPercent = 40f, airUtilTxPercent = 2f, atMs = testScheduler.currentTime)
            runCurrent()
            val post =
                RelayEnvelope(
                    type = FrameType.CHAT,
                    id = "room-1",
                    senderId = "alice",
                    sentAt = wallBase + testScheduler.currentTime,
                    payload = WireCodec.encodePayload(ChatContent(body = "north gate")),
                )
            a.transport.fastFanout(WireEnvelope(relay = true, sig = ByteArray(64) { 7 }, signed = WireCodec.encodeEnvelope(post)))
            a.dm("bob", "f1")
            settle()
            assertEquals("only the DM aired", listOf("f1"), a.link.sent.map { frameIdOf(it) })
            assertEquals("the room post waits", 1, a.pace.pending)
            assertEquals(1L, a.lora.loraBandBraked)
        }

    /** The frame id [FakeSeal] wrote into [packet]'s header. */
    private fun frameIdOf(packet: ByteArray): String =
        packet
            .copyOfRange(1, LORA_DIRECT_OVERHEAD)
            .decodeToString()
            .trimEnd('\u0000')
            .split('|')[2]

    @Test
    fun theGatesKeepADmOffTheAir() =
        runTest {
            val air = FakeMeshtasticAir()
            val a =
                node(
                    air,
                    1u,
                    "alice",
                    mapOf("bob" to holder(2u), "carol" to holder(3u, direct = false), "dave" to holder(4u), "erin" to holder(5u)),
                )
            up(a)
            a.transport.suppressDataPath(setOf("dave"))
            a.transport.coveredByInternet(setOf("erin"))
            a.dm("carol", "c1") // no capability: her board would ack what her app throws away
            a.dm("zed", "z1") // no board at all
            a.dm("dave", "d1") // a live link carries it
            a.dm("erin", "e1") // a spool carries it
            a.dm("alice", "s1") // to ourselves
            a.dm("bob", "b1", body = "x".repeat(MeshtasticProto.MAX_PAYLOAD)) // past one packet
            settle()
            assertTrue("nothing aired: ${a.link.sent.size}", a.link.sent.isEmpty())
            assertEquals("dave; a DM to ourselves names no board and stops before the covers", 1L, a.lora.loraSkippedLinked)
            assertEquals(1L, a.lora.loraSkippedInternet)
            assertEquals(1L, a.lora.loraDirectTooLong)

            a.config.value = LoraConfig("AA:1", 1, dms = false)
            settle(1_000)
            a.dm("bob", "b2")
            settle()
            assertTrue("DMs switched off", a.link.sent.isEmpty())
        }

    @Test
    fun aBoardBoundToSlotZeroSendsNoDmAtAll() =
        runTest {
            val air = FakeMeshtasticAir()
            val a = node(air, 1u, "alice", mapOf("bob" to holder(2u)), knitIndex = 0)
            a.transport.start()
            runCurrent()
            a.link.ready() // the lab shape: Knit at index 0, where the firmware would PKI-encrypt a unicast
            runCurrent()
            a.dm("bob", "f1")
            settle()
            assertTrue(a.link.sent.isEmpty())
            assertEquals(1L, a.lora.loraDirectSlotZero)
        }

    @Test
    fun oneCopyPerFrameWhateverOffersIt() =
        runTest {
            val air = FakeMeshtasticAir()
            val (a, _) = pair(air)
            a.dm("bob", "f1")
            a.transport.offer("bob", "f1", 0, byteArrayOf(1), DirectKind.MESSAGE)
            settle()
            a.transport.offer("bob", "f1", 0, byteArrayOf(1), DirectKind.MESSAGE) // acked by now: a new copy may go
            settle()
            assertEquals(2, a.link.sent.size)
        }

    @Test
    fun aFanOutOfOurOwnDmFormFrameNeverAirs() =
        runTest {
            val air = FakeMeshtasticAir()
            val (a, _) = pair(air)
            // The long-range fan-out it used to take is gone (ADR 2026-10.x9fa); the one fan-out left admits room posts only.
            a.transport.fastFanout(dmForm("alice", "bob"))
            settle()
            assertTrue(a.link.sent.isEmpty())
        }

    // --- outcomes ---

    /**
     * ADR 2026-10.x9fa's 2026-11-19 amendment: a board that is off ends in unreachable after every retry, and of the DMs
     * typed while that send was out only the newest gets the next attempt — past the mark, at once — the older left to the
     * source. A DM typed with nothing in flight gets its own attempt too: never held for the re-probe.
     */
    @Test
    fun aBoardThatIsOffEndsInUnreachableAfterEveryRetryAndTheNewestDmTypedMeanwhileGetsTheNextAttempt() =
        runTest {
            val air = FakeMeshtasticAir()
            val logs = mutableListOf<String>()
            val a = node(air, 1u, "alice", mapOf("bob" to holder(2u)), log = { logs += it })
            up(a) // bob's board is not on the air
            a.dm("bob", "f1")
            settle(1_000)
            a.dm("bob", "f2")
            a.dm("bob", "f3")
            settle(1_000)
            assertEquals("one send in flight to an unconfirmed board", 1, a.link.sent.size)
            assertEquals(2, a.transport.status.value.direct.parked)

            settle(air.untilGiveUp())
            assertEquals(1L, a.lora.loraDirectUnreachable)
            assertEquals("the newest typed meanwhile went next, past the mark", listOf("f1", "f3"), sentIds(a))
            assertEquals(1L, a.lora.loraDirectPastHold)
            assertTrue(logs.contains("lora direct next f3 -> bob (typed during the failed send, +1 left)"))
            assertEquals("the older one is left to the source", 0, a.transport.status.value.direct.parked)

            settle(air.untilGiveUp())
            assertEquals(2L, a.lora.loraDirectUnreachable)
            assertEquals("each send and every retry aired", 2 * FakeMeshtasticAir.UNICAST_ATTEMPTS, air.knitAirings(1u))
            assertEquals(
                "and every retry is booked",
                2 * LoraDirectPolicy.AIRINGS * priced(hiPacket),
                a.pace.airtime
                    .snapshot(testScheduler.currentTime)
                    .usedMs,
            )
            assertEquals(1, a.transport.status.value.direct.unreachable)
            assertEquals("f2 waits on the source: nothing re-aired it", listOf("f1", "f3"), sentIds(a))

            a.dm("bob", "f4")
            settle()
            assertEquals("a typed DM gets its own attempt while unreachable", listOf("f1", "f3", "f4"), sentIds(a))
            assertEquals(2L, a.lora.loraDirectPastHold)
            assertEquals(0L, a.lora.loraDirectHeld)
        }

    @Test
    fun aBoardThatComesBackAndAirsGetsTheNewestDmThenTheRestAfterItsAck() =
        runTest {
            val air = FakeMeshtasticAir()
            val a = node(air, 1u, "alice", mapOf("bob" to holder(2u)))
            up(a)
            a.dm("bob", "f1")
            settle(air.untilGiveUp())
            // Typed while bob is marked: f2 gets its own attempt, f3 its turn after f2 fails; all three stay unacked.
            a.dm("bob", "f2")
            a.dm("bob", "f3")
            settle(air.untilGiveUp())
            settle(air.untilGiveUp())
            assertEquals(3L, a.lora.loraDirectUnreachable)
            assertEquals(2L, a.lora.loraDirectPastHold)
            assertEquals(0L, a.lora.loraDirectHeld)

            // A gap after the failure — the re-probe deferred meanwhile by a link cover (a spool cover's lapse would
            // catch up on its own, #294) — bob's board comes back and airs anything at all.
            a.transport.suppressDataPath(setOf("bob"))
            settle(LoraReprobe.BASE_MS + LoraMeshTransport.LINGER_SWEEP_MS)
            a.transport.suppressDataPath(emptySet())
            assertEquals("nothing aired while covered", 3, a.link.sent.size)
            val b = node(air, 2u, "bob", mapOf("alice" to holder(1u)))
            up(b)
            b.link.deliverNothingButAir(air, from = 2u, to = 1u)
            settle(10_000)
            settle(10_000)

            assertEquals("the newest first, the rest after its ack", listOf("f3", "f2", "f1"), b.delivered.map { it.frameId })
            assertEquals(3L, a.lora.loraDirectResent)
            assertEquals(0, a.transport.status.value.direct.unreachable)
        }

    @Test
    fun aHearingTooSoonAfterTheFailurePullsTheReprobeForwardInsteadOfResending() =
        runTest {
            val air = FakeMeshtasticAir()
            val a = node(air, 1u, "alice", mapOf("bob" to holder(2u)))
            up(a)
            a.dm("bob", "f1")
            settle(air.untilGiveUp())
            val b = node(air, 2u, "bob", mapOf("alice" to holder(1u)))
            up(b)
            b.link.deliverNothingButAir(air, from = 2u, to = 1u)
            settle()
            assertEquals("cleared, but no resend inside the gap", 1, a.link.sent.size)
            assertEquals(0, a.transport.status.value.direct.unreachable)
            // The re-probe, pulled forward to one gap after the failure, asks.
            settle(LoraReprobe.BASE_MS + LoraMeshTransport.LINGER_SWEEP_MS)
            assertEquals(listOf("f1"), b.delivered.map { it.frameId })
        }

    /**
     * f1 given up on, f2 typed past its mark and given up on too (ADR 2026-10.x9fa's 2026-11-19 amendment), then bob's
     * board heard inside the re-probe's gap: the 11-17 strand.
     */
    private fun TestScope.strandTwoThenHear(
        air: FakeMeshtasticAir,
        a: Node,
    ): Node {
        up(a)
        a.dm("bob", "f1")
        settle(air.untilGiveUp())
        a.dm("bob", "f2")
        settle(air.untilGiveUp())
        assertEquals(2L, a.lora.loraDirectUnreachable)
        assertEquals(1L, a.lora.loraDirectPastHold)
        val b = node(air, 2u, "bob", mapOf("alice" to holder(1u)))
        up(b)
        b.link.deliverNothingButAir(air, from = 2u, to = 1u)
        settle()
        assertEquals("cleared, but no resend inside the gap", 2, a.link.sent.size)
        return b
    }

    @Test
    fun aReprobePulledForwardByAHearingBringsTheOlderStrandedDmBehindItsAck() =
        runTest {
            val air = FakeMeshtasticAir()
            val logs = mutableListOf<String>()
            val a = node(air, 1u, "alice", mapOf("bob" to holder(2u)), log = { logs += it })
            val b = strandTwoThenHear(air, a)

            // The pulled-forward round asks with f2, and its board ack brings f1 in the same round (#343): before, f1
            // waited another, doubled gap.
            settle(LoraReprobe.BASE_MS + LoraMeshTransport.LINGER_SWEEP_MS)
            assertEquals(listOf("f2", "f1"), b.delivered.map { it.frameId })
            assertEquals(1L, a.lora.loraReprobed)
            assertEquals(2L, a.lora.loraDirectResent)
            assertTrue(logs.contains("lora reprobe f2 -> bob (+1 after its ack)"))
            assertEquals(
                "the round earned its doubled gap, and the flushed resend's ack neither resets nor doubles it",
                listOf(2 * LoraReprobe.BASE_MS),
                a.transport.reprobeSchedule().map { it.gapMs },
            )
        }

    @Test
    fun aReprobesFlushReadsTheSourceAgainSoAnOlderDmWhoseTickLandedFirstStaysUnsent() =
        runTest {
            val air = FakeMeshtasticAir()
            lateinit var a: Node
            // f1's ✓✓ lands the moment the re-probe is queued, before its board ack: `flush` reads the source then.
            a =
                node(air, 1u, "alice", mapOf("bob" to holder(2u)), log = {
                    if (it.startsWith("lora reprobe f2")) a.source.ack("f1")
                })
            val b = strandTwoThenHear(air, a)

            settle(LoraReprobe.BASE_MS + LoraMeshTransport.LINGER_SWEEP_MS)
            settle(10_000)
            assertEquals("f1 aired only the once it gave up on", listOf("f1", "f2", "f2"), sentIds(a))
            assertEquals(listOf("f2"), b.delivered.map { it.frameId })
            assertEquals(1L, a.lora.loraDirectResent)
            assertEquals(
                "dropped at the flush's read, never queued for the send-time gate",
                emptyMap<String, Long>(),
                a.lora.loraStaleAtSendByReason,
            )
        }

    @Test
    fun aFlushedResendWhoseTickLandsWhileItWaitsIsDroppedAtSend() =
        runTest {
            val air = FakeMeshtasticAir()
            lateinit var a: Node
            // The board fills the moment it acks the re-probe: the flush queues f1, and f1 waits in the pacer.
            a =
                node(air, 1u, "alice", mapOf("bob" to holder(2u)), log = {
                    if (it == "lora direct acked f2 -> bob") a.link.free = 0
                })
            val b = strandTwoThenHear(air, a)

            settle(LoraReprobe.BASE_MS + LoraMeshTransport.LINGER_SWEEP_MS)
            assertEquals(listOf("f1", "f2", "f2"), sentIds(a))
            a.source.ack("f1") // bob's ✓✓ for f1 lands while it waits
            a.link.updateHeadroom(16)
            settle(10_000)
            assertEquals("asked again at send, so it never airs", listOf("f1", "f2", "f2"), sentIds(a))
            assertEquals(listOf("f2"), b.delivered.map { it.frameId })
            assertEquals(mapOf(StaleAtSend.ACKED.name to 1L), a.lora.loraStaleAtSendByReason)
        }

    @Test
    fun aReprobeNeverPromisesAnOlderDmItsBoardTook() =
        runTest {
            val air = FakeMeshtasticAir()
            val logs = mutableListOf<String>()
            val a = node(air, 1u, "alice", mapOf("bob" to holder(2u)), log = { logs += it })
            val b = node(air, 2u, "bob", mapOf("alice" to holder(1u)))
            up(a, b)
            a.dm("bob", "f1")
            settle()
            assertEquals("f1's board took it; its ✓✓ never came", 1L, a.lora.loraDirectAcked)
            air.lossy = { from, _ -> from == 1u } // bob's board stops hearing alice's
            a.dm("bob", "f2")
            settle(air.untilGiveUp())
            assertEquals(1L, a.lora.loraDirectUnreachable)
            air.lossy = { _, _ -> false }
            b.link.deliverNothingButAir(air, from = 2u, to = 1u)
            settle()

            settle(LoraReprobe.BASE_MS + LoraMeshTransport.LINGER_SWEEP_MS)
            // f1 waits on bob's phone, which the probe already asks: nothing rides behind it.
            assertTrue(logs.contains("lora reprobe f2 -> bob"))
            assertTrue(logs.none { it.startsWith("lora reprobe f2 -> bob (+") })
            assertEquals(1L, a.lora.loraReprobed)
            assertEquals(1L, a.lora.loraDirectResent)
        }

    @Test
    fun noOutcomeAtAllEndsAtTheDeadlineAsUnreachableAndBooksEveryRetry() =
        runTest {
            val air = FakeMeshtasticAir().apply { silent = { _, _ -> true } }
            val (a, _) = pair(air)
            a.dm("bob", "f1")
            settle(LoraDirectPolicy.ANSWER_SLACK_MS)
            assertEquals(1, a.transport.status.value.direct.inFlight)
            settle(LoraDirectPolicy.deadlineMs(priced(hiPacket), LoraAirtime().slotTimeMs()))
            assertEquals(1L, a.lora.loraDirectNoOutcome)
            assertEquals(0, a.transport.status.value.direct.inFlight)
            assertEquals("never reachable on silence", 1, a.transport.status.value.direct.unreachable)
            assertEquals(
                LoraDirectPolicy.AIRINGS *
                    priced(
                        a.link.sent
                            .single()
                            .size,
                    ),
                a.pace.airtime
                    .snapshot(testScheduler.currentTime)
                    .usedMs,
            )
        }

    @Test
    fun anImplicitAckIsCountedAndNeverEndsTheWait() =
        runTest {
            val air = FakeMeshtasticAir().apply { implicitAcks = true }
            val (a, _) = pair(air)
            a.dm("bob", "f1")
            settle(500)
            assertTrue(a.lora.loraDirectImplicitAck >= 1)
            assertEquals("still waiting on the destination", 1, a.transport.status.value.direct.inFlight)
            settle()
            assertEquals(1L, a.lora.loraDirectAcked)
        }

    @Test
    fun aLostAckWithTheDmLandedIsDeliveredOnceAndMarkedUnreachable() =
        runTest {
            val air = FakeMeshtasticAir().apply { ackLost = { _, _ -> true } }
            val (a, b) = pair(air)
            a.dm("bob", "f1")
            settle(air.untilGiveUp())
            assertEquals("the firmware drops the duplicates", listOf("f1"), b.delivered.map { it.frameId })
            assertEquals(1L, a.lora.loraDirectUnreachable)
            assertEquals("each retry drew an ACK that never arrived", FakeMeshtasticAir.UNICAST_ATTEMPTS, air.ackAirings(2u))
        }

    /**
     * A burst into an asymmetric link costs one send and its retries, then one attempt for the newest DM typed during it
     * (ADR 2026-10.x9fa's 2026-11-19 amendment) — never one per DM.
     */
    @Test
    fun anAsymmetricLinkStaysBoundedToOneSendAndItsRetriesAndOneForTheNewest() =
        runTest {
            val air = FakeMeshtasticAir().apply { lossy = { from, _ -> from == 2u } } // bob hears alice, alice never hears bob
            val (a, b) = pair(air)
            repeat(5) { a.dm("bob", "f$it") }
            settle(air.untilGiveUp())
            settle(air.untilGiveUp())
            settle(air.untilGiveUp())
            assertEquals("two sends and their retries, not one per DM", 2 * FakeMeshtasticAir.UNICAST_ATTEMPTS, air.knitAirings(1u))
            assertEquals(listOf("f0", "f4"), b.delivered.map { it.frameId })
        }

    @Test
    fun theDestinationRefusingHoldsThePeerUntilTheirProfileChanges() =
        runTest {
            val air = FakeMeshtasticAir().apply { silent = { _, _ -> true } }
            val (a, b) = pair(air)
            a.dm("bob", "f1")
            settle(1_000)
            a.link.emitOutcome(PacketOutcome.Refused(a.link.sentIds.single(), RoutingError.NO_CHANNEL, byDestination = true))
            settle(1_000)
            assertEquals(1, a.transport.status.value.direct.refused)
            b.link.deliverNothingButAir(air, from = 2u, to = 1u)
            settle(1_000)
            a.dm("bob", "f2")
            settle()
            assertEquals("hearing them does not lift a refusal", 1, a.link.sent.size)

            a.boards.value = mapOf("bob" to holder(2u, version = 2))
            settle(1_000)
            a.dm("bob", "f3")
            settle()
            assertEquals("a new profile does", 2, a.link.sent.size)
        }

    // --- the hearing hook ---

    @Test
    fun onlyALiveRadioClearsAnUnreachableMark() =
        runTest {
            val air = FakeMeshtasticAir()
            val a = node(air, 1u, "alice", mapOf("bob" to holder(2u)))
            up(a)
            a.dm("bob", "f1")
            settle(air.untilGiveUp())
            assertEquals(1, a.transport.status.value.direct.unreachable)
            val now = wallBase + testScheduler.currentTime
            a.link.replayNodeDb(2u) // the handshake's NodeDB replay: no RSSI
            a.link.replayQueued(2u, now, ageSeconds = 600) // the to-phone queue replayed at reconnect
            a.link.deliver(2u, 0, MeshtasticProto.PORT_TELEMETRY, byteArrayOf(1), viaMqtt = true) // off the internet
            a.link.deliver(1u, 0, MeshtasticProto.PORT_TELEMETRY, byteArrayOf(1)) // our own board
            a.link.deliver(9u, 0, MeshtasticProto.PORT_TELEMETRY, byteArrayOf(1)) // a board no contact claims
            settle(1_000)
            assertEquals("none of those is bob's board on the air now", 1, a.transport.status.value.direct.unreachable)
            a.link.deliver(2u, 0, MeshtasticProto.PORT_NODEINFO, byteArrayOf(1), rxTime = (now / 1_000).toUInt())
            settle(1_000)
            assertEquals("a live slot-0 packet off bob's board is", 0, a.transport.status.value.direct.unreachable)
        }

    // --- the re-probe ---

    @Test
    fun aDmItsBoardAckedButItsPhoneNeverTickedIsReairedAGapLater() =
        runTest {
            val air = FakeMeshtasticAir()
            val (a, b) = pair(air)
            a.dm("bob", "f1")
            settle()
            assertEquals(1L, a.lora.loraDirectAcked)
            settle(LoraReprobe.BASE_MS + LoraMeshTransport.LINGER_SWEEP_MS)
            assertEquals("re-aired: the board's ack is not bob's ✓✓", 1L, a.lora.loraReprobed)
            assertEquals(listOf("f1", "f1"), b.delivered.map { it.frameId })

            a.source.ack("f1") // bob's ✓✓ arrives
            settle(4 * LoraReprobe.BASE_MS)
            assertEquals("nothing left to ask with", 1L, a.lora.loraReprobed)
        }

    @Test
    fun aReprobeWhoseTickLandsWhileItWaitsIsDroppedAtSend() =
        runTest {
            val air = FakeMeshtasticAir()
            val (a, _) = pair(air)
            a.dm("bob", "f1")
            settle()
            a.link.free = 0 // the board is full: the re-probe waits in the queue
            settle(LoraReprobe.BASE_MS + LoraMeshTransport.LINGER_SWEEP_MS)
            assertEquals(1L, a.lora.loraReprobed)
            a.source.ack("f1")
            a.link.updateHeadroom(16)
            settle()
            assertEquals(1, a.link.sent.size)
            assertEquals(mapOf(StaleAtSend.ACKED.name to 1L), a.lora.loraStaleAtSendByReason)
        }

    @Test
    fun aRestoredScheduleForAPeerWithoutTheCapabilityEndsOnItsFirstRound() =
        runTest {
            val air = FakeMeshtasticAir()
            val state =
                object : LoraPlaneState {
                    override suspend fun load() =
                        LoraPlaneSnapshot(
                            savedAtWall = wallBase,
                            reprobes = listOf(ReprobeStamp("carol", dueAtWall = wallBase, gapMs = LoraReprobe.BASE_MS, lastFrameId = "c1")),
                        )

                    override suspend fun save(snapshot: LoraPlaneSnapshot) = Unit
                }
            val a = node(air, 1u, "alice", mapOf("carol" to holder(3u, direct = false)), state = state)
            a.source.hold("carol", DirectDm("c1", 0, byteArrayOf(1)))
            up(a)
            settle(LoraMeshTransport.LINGER_SWEEP_MS + 1_000)
            assertTrue(a.link.sent.isEmpty())
            assertTrue("forgotten", a.transport.lingerReprobes().isEmpty())
        }

    @Test
    fun aBoardMapThatHasNotReportedSendsNothingAndDefersTheReprobe() =
        runTest {
            val air = FakeMeshtasticAir()
            val a = node(air, 1u, "alice", boards = null)
            up(a)
            a.dm("bob", "f1")
            settle(LoraReprobe.BASE_MS + LoraMeshTransport.LINGER_SWEEP_MS)
            assertTrue(a.link.sent.isEmpty())
            assertEquals("deferred, not forgotten", listOf("bob"), a.transport.lingerReprobes())
        }

    // --- the receive ---

    @Test
    fun aCompactDmIsReadOnIndexZeroTooOnlyWhenAddressedToUsAndWhateverOurOwnSwitchSays() =
        runTest {
            val air = FakeMeshtasticAir()
            val (a, b) = pair(air)
            b.config.value = LoraConfig("AA:2", 1, dms = false)
            settle(1_000)
            val packet = a.seal.seal("bob", "f1", wallBase, "hi".encodeToByteArray())
            b.link.deliver(1u, 0, MeshtasticProto.PORT_PRIVATE_APP, packet, to = 2u) // a 2.5+ board reports PKI as index 0
            b.link.deliver(1u, 1, MeshtasticProto.PORT_PRIVATE_APP, packet, to = 7u) // somebody else's unicast
            b.link.deliver(1u, 4, MeshtasticProto.PORT_PRIVATE_APP, packet, to = 2u) // a channel we are not bound to
            settle(1_000)
            assertEquals(listOf("f1"), b.delivered.map { it.frameId })
        }

    /** A 0x11 packet our own board reports as from itself — our own unicast echoed back — is nobody's DM to us. */
    @Test
    fun aCompactPacketFromOurOwnBoardNeverOpensAndBooksNoReach() =
        runTest {
            val air = FakeMeshtasticAir()
            val (a, b) = pair(air)
            settle(1_000)
            // Sealed by alice for bob, so bob's seal would open it: only the from-our-own-board guard keeps it out.
            b.link.deliver(2u, 1, MeshtasticProto.PORT_PRIVATE_APP, a.seal.seal("bob", "echo", wallBase, "hi".encodeToByteArray()), to = 2u)
            settle(1_000)
            assertTrue(b.delivered.isEmpty())
            assertEquals(0L, b.lora.loraDirectDelivered)
            assertTrue(
                "no author-keyed reach from it",
                b.transport.reachable.value
                    .isEmpty(),
            )
        }

    @Test
    fun aStrangersAForgedAndAnOutOfWindowPacketAreEachCountedAndDropped() =
        runTest {
            val air = FakeMeshtasticAir()
            val (a, b) = pair(air)
            val mallory = FakeSeal("mallory") { emptySet() }
            b.link.deliver(9u, 1, MeshtasticProto.PORT_PRIVATE_APP, mallory.seal("bob", "m1", wallBase, byteArrayOf(1)), to = 2u)
            b.link.deliver(1u, 1, MeshtasticProto.PORT_PRIVATE_APP, a.seal.seal("carol", "r1", wallBase, byteArrayOf(1)), to = 2u)
            b.seal.clockRefuses = true
            b.link.deliver(1u, 1, MeshtasticProto.PORT_PRIVATE_APP, a.seal.seal("bob", "o1", wallBase, byteArrayOf(1)), to = 2u)
            settle(1_000)
            assertTrue(b.delivered.isEmpty())
            assertEquals(1L, b.lora.loraDirectStranger)
            assertEquals(1L, b.lora.loraDirectAuthFailed)
            assertEquals(1L, b.lora.loraDirectClockRefused)
        }

    @Test
    fun aReceivedDmClearsItsSendersMarkOnlyAfterItIsDelivered() =
        runTest {
            val air = FakeMeshtasticAir()
            val (a, b) = pair(air)
            air.lossy = { _, to -> to == 2u }
            a.dm("bob", "f1")
            settle(air.untilGiveUp())
            assertEquals(1, a.transport.status.value.direct.unreachable)
            air.lossy = { _, _ -> false }
            a.transport.suppressDataPath(setOf("bob")) // the re-probe defers while the gap runs out
            settle(LoraReprobe.BASE_MS + LoraMeshTransport.LINGER_SWEEP_MS)
            a.transport.suppressDataPath(emptySet())
            // Bob's compact ✓✓ for f1 is delivered before his board counts as heard, so the resend the hearing would make
            // asks a source that already has f1 acked, and nothing is re-aired.
            b.dm("alice", "t1", body = "ack:f1", kind = DirectKind.RECEIPT)
            settle()
            assertEquals(listOf("t1"), a.delivered.map { it.frameId })
            assertEquals(0, a.transport.status.value.direct.unreachable)
            assertEquals(0L, a.lora.loraDirectResent)
            assertEquals(1, a.link.sent.size)
        }

    // --- the catch-up: a fact says LoRa carries now (#294) ---

    /** The frame ids [node]'s board wrote, in order. */
    private fun sentIds(node: Node): List<String> = node.link.sent.map { frameIdOf(it) }

    @Test
    fun aDmTheRelayCoverKeptOffTheAirGoesTheMomentTheCoverLapses() =
        runTest {
            val air = FakeMeshtasticAir()
            val logs = mutableListOf<String>()
            val (a, b) = pair(air, log = { logs += it })
            a.transport.coveredByInternet(setOf("bob"))
            a.dm("bob", "f1") // sent 0.7 s before the sockets closed: the field case
            settle()
            assertTrue(a.link.sent.isEmpty())
            assertEquals(1L, a.lora.loraSkippedInternet)

            a.transport.coveredByInternet(emptySet()) // no clock advance: the lapse itself is the trigger
            runCurrent()
            settle()
            assertEquals(listOf("f1"), b.delivered.map { it.frameId })
            assertEquals(1L, a.lora.loraDirectResent)
            assertTrue(logs.any { it.startsWith("lora resend f1 -> bob (cover lapsed") })

            // Covered again, a DM kept off, then the same set republished: nothing lapsed, so nothing catches up.
            a.transport.coveredByInternet(setOf("bob"))
            a.dm("bob", "f2")
            settle()
            assertEquals(2L, a.lora.loraSkippedInternet)
            a.transport.coveredByInternet(setOf("bob"))
            settle()
            assertEquals("still one airing", 1, a.link.sent.size)
            assertEquals(2L, a.lora.loraSkippedInternet)
        }

    @Test
    fun aFreshDmsBoardAckBringsEveryOlderOneNoBoardTookAtOnceAndAResendsAckBringsNothing() =
        runTest {
            val air = FakeMeshtasticAir()
            val logs = mutableListOf<String>()
            val (a, b) = pair(air, log = { logs += it })
            // Four DMs a live link carried, so none went on the air; the link goes and none of them has its ✓✓.
            a.transport.suppressDataPath(setOf("bob"))
            listOf("f0", "f1", "f2", "f3").forEach { a.dm("bob", it) }
            settle()
            a.transport.suppressDataPath(emptySet())
            assertTrue(a.link.sent.isEmpty())

            a.dm("bob", "f4")
            settle(10_000)
            settle(10_000)
            // f4's ack proves the unicast path: every DM no board took goes at once, up to AFTER_ACK_LIMIT (ADR 2026-10.x9fa's
            // 2026-11-19 amendment); those resends' own acks queue nothing more.
            assertEquals(listOf("f4", "f3", "f2", "f1", "f0"), sentIds(a))
            assertEquals(listOf("f4", "f3", "f2", "f1", "f0"), b.delivered.map { it.frameId })
            assertEquals(4L, a.lora.loraDirectResent)
            assertTrue(logs.contains("lora resend f3 -> bob (after ack, +3 with it)"))
            assertEquals(
                "a board that just acked is no evidence the phone is away: the re-probe stays at the base gap",
                listOf(LoraReprobe.BASE_MS),
                a.transport.reprobeSchedule().map { it.gapMs },
            )
        }

    @Test
    fun newerDmsItsBoardTookNeverHideAnOlderStrandedOneFromTheCatchUp() =
        runTest {
            val air = FakeMeshtasticAir()
            val (a, b) = pair(air)
            listOf("f1", "f2", "f3").forEach {
                a.dm("bob", it)
                settle()
            }
            // An older DM nothing carried — queued under a cover before the three, say — still without its ✓✓, while the
            // three board-taken ones wait on bob's coalesced ✓✓.
            a.source.unacked
                .getValue("bob")
                .add(DirectDm("f0", 0, "hi".encodeToByteArray()))
            a.dm("bob", "f4")
            settle(10_000)
            assertEquals("the limit counts only what no board took", listOf("f1", "f2", "f3", "f4", "f0"), sentIds(a))
            assertEquals(listOf("f1", "f2", "f3", "f4", "f0"), b.delivered.map { it.frameId })
        }

    @Test
    fun losingTheBoardEndsEveryLift() =
        runTest {
            val air = FakeMeshtasticAir()
            val (a, b) = pair(air)
            a.transport.coveredByInternet(setOf("bob"))
            b.dm("alice", "g1")
            settle()
            assertEquals(0, a.transport.status.value.internetCovered)

            a.config.value = null // the board is unbound: what it heard says nothing about the next one
            settle(1_000)
            assertEquals("the relay's cover is back", 1, a.transport.status.value.internetCovered)
            a.config.value = LoraConfig("AA:1", 1)
            settle(1_000)
            a.link.readyProvisioned(knitIndex = 1)
            settle(1_000)
            a.dm("bob", "f1")
            settle()
            assertTrue("covered again until bob is heard anew", a.link.sent.isEmpty())
            assertEquals(1L, a.lora.loraSkippedInternet)
        }

    @Test
    fun twoDmsToAPresentPhoneNeverReAirTheFirst() =
        runTest {
            val air = FakeMeshtasticAir()
            val (a, _) = pair(air)
            // Ordinary chat: bob's coalescer holds the ✓✓ for f1 up to 45 s, so f1 is unacked when f2's board ack lands.
            a.dm("bob", "f1")
            settle()
            a.dm("bob", "f2")
            settle()
            assertEquals(listOf("f1", "f2"), sentIds(a))
            assertEquals(0L, a.lora.loraDirectResent)
        }

    @Test
    fun aDmItsBoardTookIsNeverCaughtUpOnlyTheOneTheCoverKeptOff() =
        runTest {
            val air = FakeMeshtasticAir()
            val (a, b) = pair(air)
            a.dm("bob", "f1")
            settle()
            a.transport.coveredByInternet(setOf("bob"))
            a.dm("bob", "f2")
            settle()
            a.transport.coveredByInternet(emptySet())
            runCurrent()
            settle()
            assertEquals("f1 waits on bob's phone: the re-probe's question", listOf("f1", "f2"), sentIds(a))
            assertEquals(listOf("f1", "f2"), b.delivered.map { it.frameId })
        }

    @Test
    fun aRelayFlappingAfterAFailureAirsOnceInsideTheReprobesGap() =
        runTest {
            val air = FakeMeshtasticAir()
            val (a, _) = pair(air)
            air.lossy = { _, to -> to == 2u } // bob's board is out of reach all along
            a.dm("bob", "f1")
            settle(air.untilGiveUp())
            assertEquals(1, a.transport.status.value.direct.unreachable)

            a.transport.coveredByInternet(setOf("bob"))
            a.transport.coveredByInternet(emptySet())
            settle()
            assertEquals("inside the gap since the failure: nothing", 1, a.link.sent.size)

            a.transport.coveredByInternet(setOf("bob")) // the re-probe defers while covered
            settle(LoraReprobe.BASE_MS + LoraMeshTransport.LINGER_SWEEP_MS)
            a.transport.coveredByInternet(emptySet())
            settle(air.untilGiveUp())
            assertEquals("past the gap: one resend, which fails again", 2, a.link.sent.size)

            a.transport.coveredByInternet(setOf("bob"))
            a.transport.coveredByInternet(emptySet())
            settle()
            assertEquals("the gap doubled: nothing", 2, a.link.sent.size)
        }

    @Test
    fun aLiveCompactDmLiftsItsSendersCoverAndCatchesUpWhatItKeptOff() =
        runTest {
            val air = FakeMeshtasticAir()
            val logs = mutableListOf<String>()
            val (a, b) = pair(air, log = { logs += it })
            a.transport.coveredByInternet(setOf("bob")) // bob pushed to the relay minutes ago, and left it
            a.dm("bob", "f1")
            settle()
            assertTrue(a.link.sent.isEmpty())

            b.dm("alice", "g1") // bob aired it: by his reckoning no relay joins us
            settle(10_000)
            assertEquals(listOf("g1"), a.delivered.map { it.frameId })
            assertEquals(0, a.transport.status.value.internetCovered)
            assertTrue(logs.contains("lora cover lifted bob"))
            assertTrue(logs.any { it.startsWith("lora resend f1 -> bob (lifted") })
            assertEquals(listOf("f1"), b.delivered.map { it.frameId })
        }

    @Test
    fun aTickAloneLiftsTheCoverSoWhatWeOweGoesOnTheAir() =
        runTest {
            val air = FakeMeshtasticAir()
            val (a, b) = pair(air)
            a.transport.coveredByInternet(setOf("bob"))
            b.dm("alice", "t1", body = "ack:nothing", kind = DirectKind.RECEIPT)
            settle()
            assertEquals(listOf("t1"), a.delivered.map { it.frameId })
            assertEquals(0, a.transport.status.value.internetCovered)
            a.dm("bob", "r1", kind = DirectKind.RECEIPT) // the ✓✓ we owe bob
            settle()
            assertEquals(listOf("r1"), sentIds(a))
            assertEquals(0L, a.lora.loraSkippedInternet)
        }

    @Test
    fun aLiftLastsUntilTheRelayLosesThatPeerWhateverElseItsPresentSetDoes() =
        runTest {
            val air = FakeMeshtasticAir()
            val (a, b) = pair(air)
            a.transport.coveredByInternet(setOf("bob"))
            b.dm("alice", "g1")
            settle()
            assertEquals(0, a.transport.status.value.internetCovered)

            a.transport.coveredByInternet(setOf("bob", "carol")) // carol appears on the relay
            settle()
            assertEquals("carol only: bob's lift holds", 1, a.transport.status.value.internetCovered)
            a.dm("bob", "f1")
            settle()
            assertEquals(listOf("f1"), sentIds(a))

            a.transport.coveredByInternet(setOf("carol")) // the relay lost bob: the lift ends with his presence
            a.transport.coveredByInternet(setOf("bob", "carol")) // and he is back on it
            a.dm("bob", "f2")
            settle()
            assertEquals(listOf("f1"), sentIds(a))
            assertEquals(1L, a.lora.loraSkippedInternet)
        }

    @Test
    fun neitherAnMqttCopyNorAReplayedOneNorABoardsAckLiftsTheCover() =
        runTest {
            val air = FakeMeshtasticAir()
            val (a, b) = pair(air)
            val now = wallBase + testScheduler.currentTime
            a.transport.coveredByInternet(setOf("bob"))
            val m1 = b.seal.seal("alice", "m1", now, "hi".encodeToByteArray())
            a.link.deliver(2u, 0, MeshtasticProto.PORT_PRIVATE_APP, m1, to = 1u, viaMqtt = true, pkiEncrypted = true)
            val q1 = b.seal.seal("alice", "q1", now, "hi".encodeToByteArray())
            val stale = (now / 1_000 - 600).toUInt() // the to-phone queue replayed at reconnect
            a.link.deliver(2u, 0, MeshtasticProto.PORT_PRIVATE_APP, q1, to = 1u, rxTime = stale, pkiEncrypted = true)
            settle()
            assertEquals("both delivered", listOf("m1", "q1"), a.delivered.map { it.frameId })
            assertEquals("neither lifted", 1, a.transport.status.value.internetCovered)

            // A board's ACK is a radio's word, never a person's: bob's board acks a DM that left before his cover came.
            a.transport.coveredByInternet(emptySet())
            a.transport.coveredByInternet(setOf("carol"))
            a.dm("bob", "f1")
            settle(500) // written; its ack lands at 2 s
            assertEquals(listOf("f1"), sentIds(a))
            assertEquals(0L, a.lora.loraDirectAcked)
            a.transport.coveredByInternet(setOf("bob", "carol"))
            settle()
            assertEquals(1L, a.lora.loraDirectAcked)
            assertEquals(2, a.transport.status.value.internetCovered)
        }

    // --- a typed DM never waits on the re-probe (ADR 2026-10.x9fa's 2026-11-19 amendment) ---

    /** Bob's board off: f1 given up on, so bob is marked unreachable. */
    private fun TestScope.markedUnreachable(log: (String) -> Unit = {}): Node {
        val air = FakeMeshtasticAir()
        val a = node(air, 1u, "alice", mapOf("bob" to holder(2u)), log = log)
        up(a)
        a.dm("bob", "f1")
        settle(air.untilGiveUp())
        assertEquals(1, a.transport.status.value.direct.unreachable)
        return a
    }

    @Test
    fun aTypedDmToAnUnreachablePeerAirsAndIsNotDroppedAtSend() =
        runTest {
            val logs = mutableListOf<String>()
            val a = markedUnreachable(log = { logs += it })
            a.link.free = 0 // the board full: it waits in the queue, and its gates are asked again at send
            a.dm("bob", "f2")
            settle()
            assertTrue(logs.contains("lora direct past hold f2 -> bob"))
            a.link.updateHeadroom(16)
            settle()
            assertEquals(listOf("f1", "f2"), sentIds(a))
            assertEquals(1L, a.lora.loraDirectPastHold)
            assertEquals(0L, a.lora.loraDirectHeld)
            assertEquals("the unreachable mark is not asked of it at send", emptyMap<String, Long>(), a.lora.loraStaleAtSendByReason)
            assertTrue("never told undelivered: it is no ✓✓", a.undelivered.isEmpty())
        }

    @Test
    fun aReceiptToAnUnreachablePeerIsStillHeldAndToldUndeliveredOnce() =
        runTest {
            val a = markedUnreachable()
            a.dm("bob", "r1", kind = DirectKind.RECEIPT)
            a.dm("bob", "x1", kind = DirectKind.REACTION)
            settle()
            assertEquals("neither aired", listOf("f1"), sentIds(a))
            assertEquals(2L, a.lora.loraDirectHeld)
            assertEquals(0L, a.lora.loraDirectPastHold)
            assertEquals("the ✓✓ alone, once", listOf("r1" to "bob"), a.undelivered)
        }

    @Test
    fun aRefusalStillHoldsATypedDmAtTheOfferAndAtSend() =
        runTest {
            // A refusal that arrives for a send that already ended (its late NAK) marks bob refused while f2 waits queued.
            val air = FakeMeshtasticAir().apply { silent = { _, _ -> true } }
            val (a, _) = pair(air)
            a.dm("bob", "f1")
            settle(1_000)
            val handle = a.link.sentIds.single()
            settle(LoraDirectPolicy.deadlineMs(priced(hiPacket), LoraAirtime().slotTimeMs()))
            assertEquals(1, a.transport.status.value.direct.unreachable)
            a.link.free = 0
            a.dm("bob", "f2") // past the unreachable mark, into the queue
            settle(1_000)
            a.link.emitOutcome(PacketOutcome.Refused(handle, RoutingError.NO_CHANNEL, byDestination = true))
            settle(1_000)
            assertEquals(1, a.transport.status.value.direct.refused)
            a.link.updateHeadroom(16)
            settle()
            assertEquals("dropped at send: refused while it waited", listOf("f1"), sentIds(a))
            assertEquals(mapOf(StaleAtSend.HELD.name to 1L), a.lora.loraStaleAtSendByReason)
            a.dm("bob", "f3")
            settle()
            assertEquals("and held at the offer", listOf("f1"), sentIds(a))
            assertEquals(1L, a.lora.loraDirectHeld)
            assertEquals(1L, a.lora.loraDirectPastHold)
        }

    @Test
    fun theDmTypedDuringAFailingSendAirsTheMomentItFails() =
        runTest {
            val air = FakeMeshtasticAir()
            val a = node(air, 1u, "alice", mapOf("bob" to holder(2u)))
            up(a)
            a.dm("bob", "f1")
            settle(1_000)
            a.dm("bob", "f2")
            settle(air.giveUpMs(hiPacket) - 3_000)
            assertEquals("not yet: f1 is still out", listOf("f1"), sentIds(a))
            settle(7_000)
            assertEquals("f2 went at f1's failure, no re-probe gap", listOf("f1", "f2"), sentIds(a))
            assertTrue(testScheduler.currentTime < LoraReprobe.BASE_MS)
        }

    @Test
    fun fiveDmsTypedDuringAFailingSendCostExactlyOneMoreFlight() =
        runTest {
            val air = FakeMeshtasticAir()
            val a = node(air, 1u, "alice", mapOf("bob" to holder(2u)))
            up(a)
            a.dm("bob", "f0")
            settle(1_000)
            (1..5).forEach { a.dm("bob", "f$it") }
            settle(air.untilGiveUp())
            settle(air.untilGiveUp())
            settle(air.untilGiveUp())
            assertEquals("the failed send, then the newest typed during it", listOf("f0", "f5"), sentIds(a))
            assertEquals(2 * FakeMeshtasticAir.UNICAST_ATTEMPTS, air.knitAirings(1u))
            assertEquals(2L, a.lora.loraDirectUnreachable)
        }

    @Test
    fun anIdlePeerSeesNothingMoreBeforeTheReprobesGap() =
        runTest {
            val air = FakeMeshtasticAir()
            val a = node(air, 1u, "alice", mapOf("bob" to holder(2u)))
            up(a)
            a.dm("bob", "f1")
            settle(LoraReprobe.BASE_MS - 5_000)
            assertEquals("one send and its retries, nothing typed after", listOf("f1"), sentIds(a))
            assertEquals(FakeMeshtasticAir.UNICAST_ATTEMPTS, air.knitAirings(1u))
            settle(LoraMeshTransport.LINGER_SWEEP_MS + 10_000)
            assertEquals("then the re-probe asks", 1L, a.lora.loraReprobed)
        }

    /** B1: a DM parked while the send it waits on is still queued in the pacer gets its own attempt when that one fails. */
    @Test
    fun aDmParkedWhileTheFailedSendWasStillQueuedAirsOnItsOwnAttempt() =
        runTest {
            val air = FakeMeshtasticAir()
            val a = node(air, 1u, "alice", mapOf("bob" to holder(2u)))
            up(a)
            a.link.free = 0
            a.dm("bob", "f1")
            settle(1_000)
            a.dm("bob", "f2") // parked behind f1, which has not flown yet
            settle(1_000)
            assertEquals(1, a.transport.status.value.direct.parked)
            assertTrue(a.link.sent.isEmpty())
            a.link.updateHeadroom(16)
            settle(air.untilGiveUp())
            settle(1_000)
            assertEquals(listOf("f1", "f2"), sentIds(a))
            assertEquals(1L, a.lora.loraDirectPastHold)
        }

    /** B1: a DM parked during the retry gap after the destination lacked our key airs when that retry fails. */
    @Test
    fun aDmParkedDuringAKeyRetryGapAirsOnItsOwnAttemptWhenTheRetryFails() =
        runTest {
            val air = FakeMeshtasticAir()
            val logs = mutableListOf<String>()
            val (a, b) = pair(air, log = { logs += it })
            b.link.forget(1u)
            a.dm("bob", "f1")
            settle(3_000)
            assertEquals(1L, a.lora.loraDirectPeerLacksOurKey)
            air.lossy = { _, to -> to == 2u } // and then bob's board goes out of reach
            a.dm("bob", "f2") // inside the re-offer's gap
            settle(1_000)
            assertEquals(1, a.transport.status.value.direct.parked)
            settle(30_000 + air.untilGiveUp())
            settle(1_000)
            assertEquals("the re-offer failed, and f2 went on its own attempt", listOf("f1", "f1", "f2"), sentIds(a))
            assertTrue(logs.any { it.startsWith("lora direct next f2 -> bob") })
        }

    @Test
    fun afterAFreshAckEveryHeldDmUpToTheLimitQueuesAtOnceNotBehindEachOthersAcks() =
        runTest {
            val air = FakeMeshtasticAir()
            lateinit var a: Node
            // Bob's board goes silent the moment it acks f10: a chain would stop at one resend, a batch sends them all.
            a =
                node(air, 1u, "alice", mapOf("bob" to holder(2u)), log = {
                    if (it == "lora direct acked f10 -> bob") air.silent = { _, _ -> true }
                })
            val b = node(air, 2u, "bob", mapOf("alice" to holder(1u)))
            up(a, b)
            a.transport.suppressDataPath(setOf("bob"))
            (0..9).forEach { a.dm("bob", "f$it") }
            settle()
            a.transport.suppressDataPath(emptySet())
            assertTrue(a.link.sent.isEmpty())

            a.dm("bob", "f10")
            settle(10_000)
            assertEquals(
                "f10, then the eight newest it held, none waiting on another's ack",
                listOf("f10") + (9 downTo 2).map { "f$it" },
                sentIds(a),
            )
            assertEquals(LoraMeshTransport.AFTER_ACK_LIMIT.toLong(), a.lora.loraDirectResent)
        }

    // --- a ✓✓ whose compact copy did not land (ADR 2026-10.x9fa's 2026-11-19 amendment) ---

    @Test
    fun aReceiptWhoseOwnSendGivesUpOrGoesUnansweredIsToldUndeliveredOnce() =
        runTest {
            val air = FakeMeshtasticAir()
            val a = node(air, 1u, "alice", mapOf("bob" to holder(2u)))
            up(a)
            a.dm("bob", "r1", kind = DirectKind.RECEIPT)
            settle(air.untilGiveUp())
            assertEquals(listOf("r1" to "bob"), a.undelivered)

            val quiet = FakeMeshtasticAir().apply { silent = { _, _ -> true } }
            val (c, _) = pair(quiet)
            c.dm("bob", "r2", kind = DirectKind.RECEIPT)
            settle(LoraDirectPolicy.ANSWER_SLACK_MS + LoraDirectPolicy.deadlineMs(priced(hiPacket), LoraAirtime().slotTimeMs()))
            assertEquals(1L, c.lora.loraDirectNoOutcome)
            assertEquals(listOf("r2" to "bob"), c.undelivered)
        }

    @Test
    fun aReceiptParkedBehindAFailedSendIsToldUndeliveredAndNeverTakesTheTurn() =
        runTest {
            val air = FakeMeshtasticAir()
            val a = node(air, 1u, "alice", mapOf("bob" to holder(2u)))
            up(a)
            a.dm("bob", "f1")
            settle(1_000)
            a.dm("bob", "r1", kind = DirectKind.RECEIPT)
            settle(air.untilGiveUp())
            assertEquals(listOf("r1" to "bob"), a.undelivered)
            assertEquals("nothing went next: no DM was typed", listOf("f1"), sentIds(a))
        }

    @Test
    fun losingTheBoardTellsEveryReceiptParkedOrInFlight() =
        runTest {
            val air = FakeMeshtasticAir().apply { silent = { _, _ -> true } }
            val (a, _) = pair(air)
            a.dm("bob", "r1", kind = DirectKind.RECEIPT)
            settle(1_000)
            a.dm("bob", "r2", kind = DirectKind.RECEIPT) // parked behind r1
            settle(1_000)
            a.config.value = null
            settle(1_000)
            assertEquals(setOf("r1" to "bob", "r2" to "bob"), a.undelivered.toSet())
            assertEquals(2, a.undelivered.size)
        }

    @Test
    fun aReceiptOurBoardRefusesForPaceIsToldUndeliveredAndOneTheirBoardRefusesIsNot() =
        runTest {
            val air = FakeMeshtasticAir().apply { silent = { _, _ -> true } }
            val a = node(air, 1u, "alice", mapOf("bob" to holder(2u), "carol" to holder(3u)))
            up(a)
            a.dm("bob", "r1", kind = DirectKind.RECEIPT)
            a.dm("carol", "r2", kind = DirectKind.RECEIPT)
            settle(1_000)
            val (toBob, toCarol) = a.link.sentIds
            a.link.emitOutcome(PacketOutcome.Refused(toBob, RoutingError.DUTY_CYCLE_LIMIT, byDestination = false))
            a.link.emitOutcome(PacketOutcome.Refused(toCarol, RoutingError.NO_CHANNEL, byDestination = true))
            settle(1_000)
            assertEquals("our board's pace, never their refusal", listOf("r1" to "bob"), a.undelivered)
        }

    @Test
    fun aReceiptDroppedAtTheWriteForPaceIsToldUndelivered() =
        runTest {
            val air = FakeMeshtasticAir()
            val (a, _) = pair(air)
            a.link.nakNext = RoutingError.DUTY_CYCLE_LIMIT
            a.dm("bob", "r1", kind = DirectKind.RECEIPT)
            settle()
            assertEquals(listOf("r1" to "bob"), a.undelivered)
        }

    @Test
    fun aQueuedReceiptWhosePeerIsMarkedUnreachableWhileItWaitsIsToldOnceAtSend() =
        runTest {
            val air = FakeMeshtasticAir()
            lateinit var a: Node
            // f1 acked: bob is reachable, so what follows queues straight; then bob's board goes quiet.
            a =
                node(air, 1u, "alice", mapOf("bob" to holder(2u)), log = {
                    if (it == "lora direct acked f1 -> bob") air.silent = { _, _ -> true }
                })
            val b = node(air, 2u, "bob", mapOf("alice" to holder(1u)))
            up(a, b)
            a.dm("bob", "f1")
            settle()
            a.dm("bob", "f2") // in flight, and it will go unanswered
            settle(1_000)
            a.link.free = 0
            a.dm("bob", "r1", kind = DirectKind.RECEIPT) // queued behind a full board
            settle(LoraDirectPolicy.deadlineMs(priced(hiPacket), LoraAirtime().slotTimeMs()) + LoraDirectPolicy.ANSWER_SLACK_MS)
            assertEquals(1, a.transport.status.value.direct.unreachable)
            a.link.updateHeadroom(16)
            settle()
            assertEquals(mapOf(StaleAtSend.HELD.name to 1L), a.lora.loraStaleAtSendByReason)
            assertEquals(listOf("r1" to "bob"), a.undelivered)
        }

    @Test
    fun aReceiptACoverASlotOrAnotherBoardCarriesIsNeverToldUndelivered() =
        runTest {
            val air = FakeMeshtasticAir()
            val a = node(air, 1u, "alice", mapOf("bob" to holder(2u), "dave" to holder(4u), "erin" to holder(5u)))
            up(a)
            a.transport.suppressDataPath(setOf("dave"))
            a.transport.coveredByInternet(setOf("erin"))
            a.dm("dave", "r1", kind = DirectKind.RECEIPT)
            a.dm("erin", "r2", kind = DirectKind.RECEIPT)
            a.dm("bob", "r3", kind = DirectKind.RECEIPT, body = "x".repeat(MeshtasticProto.MAX_PAYLOAD))
            a.link.free = 0
            a.dm("bob", "r4", kind = DirectKind.RECEIPT)
            settle()
            a.boards.value = mapOf("bob" to holder(2u, direct = false))
            a.link.updateHeadroom(16)
            settle()
            assertEquals(1L, a.lora.loraStaleAtSendByReason[StaleAtSend.NOT_DIRECT.name])
            assertTrue("a cover, a too-long seal and a dropped board: ${a.undelivered}", a.undelivered.isEmpty())
        }

    @Test
    fun aReceiptToABoardBoundToSlotZeroIsNeverToldUndelivered() =
        runTest {
            val air = FakeMeshtasticAir()
            val a = node(air, 1u, "alice", mapOf("bob" to holder(2u)), knitIndex = 0)
            a.transport.start()
            runCurrent()
            a.link.ready()
            runCurrent()
            a.dm("bob", "r1", kind = DirectKind.RECEIPT)
            settle()
            assertEquals(1L, a.lora.loraDirectSlotZero)
            assertTrue(a.undelivered.isEmpty())
        }

    @Test
    fun theRadioReachedEdgeNamesThePeerTheSendWasFor() =
        runTest {
            val air = FakeMeshtasticAir()
            val (a, _) = pair(air)
            a.dm("bob", "f1")
            settle()
            assertEquals(listOf("f1" to false), a.reached)
            assertEquals(listOf("bob"), a.reachedPeers)
        }

    // --- the air ---

    @Test
    fun aDmIsAdmittedAgainstEveryAiringItMayCostSoANearlySpentWindowHoldsIt() =
        runTest {
            val air = FakeMeshtasticAir()
            val (a, _) = pair(air)
            val ledger = a.pace.airtime
            val now = testScheduler.currentTime
            val size = LORA_DIRECT_OVERHEAD + 2
            val one = priced(size)
            val total = ledger.allowanceMs()
            // Leave room for two airings of it, short of the five (`LoraDirectPolicy.AIRINGS`) it is admitted against.
            while (total - ledger.snapshot(now).usedMs >= 3 * one) ledger.record(size, now, PacketPrice.UNSIGNED)
            a.dm("bob", "f1")
            settle()
            assertTrue("one airing would fit; its retries would not", a.link.sent.isEmpty())
            assertEquals(1, a.pace.pending)
        }

    @Test
    fun losingTheBoardEndsEveryFlightAndBooksItsRetries() =
        runTest {
            val air = FakeMeshtasticAir().apply { silent = { _, _ -> true } }
            val (a, _) = pair(air)
            a.dm("bob", "f1")
            settle(1_000)
            assertEquals(1, a.transport.status.value.direct.inFlight)
            a.config.value = null
            settle(1_000)
            assertEquals(0, a.transport.status.value.direct.inFlight)
            assertEquals(
                LoraDirectPolicy.AIRINGS *
                    priced(
                        a.link.sent
                            .single()
                            .size,
                    ),
                a.pace.airtime
                    .snapshot(testScheduler.currentTime)
                    .usedMs,
            )
        }

    @Test
    fun aQueuedDmWhosePeerLinksOrLosesTheCapabilityIsDroppedAtSend() =
        runTest {
            val air = FakeMeshtasticAir()
            val (a, _) = pair(air)
            a.link.free = 0
            a.dm("bob", "f1")
            a.dm("bob", "f2") // parked behind f1: bob is not yet confirmed
            settle()
            a.transport.suppressDataPath(setOf("bob"))
            a.link.updateHeadroom(16)
            settle()
            assertTrue(a.link.sent.isEmpty())
            assertEquals(1L, a.lora.loraStaleAtSendByReason[StaleAtSend.LINKED.name])

            a.transport.suppressDataPath(emptySet())
            a.link.free = 0
            a.dm("bob", "f3")
            settle()
            a.boards.value = mapOf("bob" to holder(2u, direct = false))
            a.link.updateHeadroom(16)
            settle()
            assertTrue(a.link.sent.isEmpty())
            assertEquals(1L, a.lora.loraStaleAtSendByReason[StaleAtSend.NOT_DIRECT.name])
        }

    // --- the review's batch (ADR 2026-10.x9fa) ---

    @Test
    fun aBoardThatAcksWhileItsPhoneStaysAwayIsReprobedAt15Then30Then60Minutes() =
        runTest {
            val air = FakeMeshtasticAir()
            val (a, _) = pair(air)
            val start = testScheduler.currentTime
            a.dm("bob", "f1") // never ✓✓: bob's phone is away from his board, which acks every airing
            val at = mutableListOf<Long>()
            var seen = 0L
            repeat(130) {
                settle(LoraMeshTransport.LINGER_SWEEP_MS)
                if (a.lora.loraReprobed > seen) {
                    seen = a.lora.loraReprobed
                    at += testScheduler.currentTime
                }
            }
            val gaps = (listOf(start) + at).zipWithNext { x, y -> (y - x) / 60_000 }.take(3)
            val expected = listOf(15L, 30L, 60L)
            gaps.zip(expected).forEach { (got, want) -> assertTrue("gaps $gaps, want $expected", got in want..want + 2) }
            assertEquals(3, gaps.size)
            assertEquals("every re-airing was acked by the board", 1L + seen, a.lora.loraDirectAcked)
        }

    @Test
    fun aDestinationAckAfterTheDeadlineStillMarksTheBoardReachableAndBooksNothing() =
        runTest {
            val air = FakeMeshtasticAir().apply { silent = { _, _ -> true } }
            val (a, _) = pair(air)
            a.dm("bob", "f1")
            settle(1_000)
            val handle = a.link.sentIds.single()
            settle(LoraDirectPolicy.deadlineMs(priced(hiPacket), LoraAirtime().slotTimeMs()))
            assertEquals(1, a.transport.status.value.direct.unreachable)
            val booked =
                a.pace.airtime
                    .snapshot(testScheduler.currentTime)
                    .usedMs
            // The board's to-phone queue, replayed after a BLE drop, hands the destination's ack over at last.
            a.link.emitOutcome(PacketOutcome.Acked(handle, byDestination = true))
            settle(1_000)
            assertEquals(0, a.transport.status.value.direct.unreachable)
            assertEquals(1, a.transport.status.value.direct.reachable)
            assertEquals(
                "no refund, no re-booking",
                booked,
                a.pace.airtime
                    .snapshot(testScheduler.currentTime)
                    .usedMs,
            )
        }

    @Test
    fun anAckThatCountsItsAiringsRefundsExactlyTheOnesThatDidNotFly() =
        runTest {
            // ADR 2026-11.gsqu §2: a link that runs its own attempts (MeshCore) reports how many flew; the refund reads that
            // count, never the elapsed-time guess, whatever time has passed.
            val air = FakeMeshtasticAir().apply { silent = { _, _ -> true } }
            val (a, _) = pair(air)
            a.dm("bob", "f1")
            settle(1_000)
            val size =
                a.link.sent
                    .single()
                    .size
            a.link.emitOutcome(PacketOutcome.Acked(a.link.sentIds.single(), byDestination = true, flown = 2))
            settle(1_000)
            assertEquals(
                2 * priced(size),
                a.pace.airtime
                    .snapshot(testScheduler.currentTime)
                    .usedMs,
            )
            assertEquals(1, a.transport.status.value.direct.reachable)
        }

    @Test
    fun aGiveUpThatCountsItsAiringsRefundsTheUnflownAndIsStillAGiveUp() =
        runTest {
            val air = FakeMeshtasticAir().apply { silent = { _, _ -> true } }
            val (a, _) = pair(air)
            a.dm("bob", "f1")
            settle(1_000)
            val size =
                a.link.sent
                    .single()
                    .size
            val handle = a.link.sentIds.single()
            a.link.emitOutcome(PacketOutcome.Unreachable(handle, flown = 1))
            settle(1_000)
            assertEquals(
                "a flood cancelled the rest: one airing flew, the others come back",
                priced(size),
                a.pace.airtime
                    .snapshot(testScheduler.currentTime)
                    .usedMs,
            )
            assertEquals("still a give-up", 1L, a.lora.loraDirectUnreachable)
        }

    @Test
    fun aSendInFlightHoldsItsRetriesAgainstTheWindowUntilItsAckRefundsThem() =
        runTest {
            val air = FakeMeshtasticAir()
            val a = node(air, 1u, "alice", mapOf("bob" to holder(2u), "carol" to holder(3u)))
            val b = node(air, 2u, "bob", mapOf("alice" to holder(1u)))
            val c = node(air, 3u, "carol", mapOf("alice" to holder(1u)))
            up(a, b, c)
            val ledger = a.pace.airtime
            val one = priced(hiPacket)
            val total = ledger.allowanceMs()
            val now = testScheduler.currentTime
            // Room for one DM's five airings and three more, not two DMs' ten.
            while (total - ledger.snapshot(now).usedMs >= 9 * one) ledger.record(hiPacket, now, PacketPrice.UNSIGNED)
            a.dm("bob", "f1")
            a.dm("carol", "g1")
            settle(500)
            assertEquals("bob's retries are still owed: carol's DM waits", 1, a.link.sent.size)
            settle(5_000) // bob's board acks: four unflown airings come back
            assertEquals(listOf(2u, 3u), a.link.sentTos)
            assertEquals(listOf("g1"), c.delivered.map { it.frameId })
        }

    @Test
    fun ourBoardsAckIsBookedForEveryCompactDmAddressedToUsThatAskedForOneOpenedOrNot() =
        runTest {
            val air = FakeMeshtasticAir()
            val (_, b) = pair(air)
            val mallory = FakeSeal("mallory") { emptySet() }
            b.link.deliver(
                9u,
                1,
                MeshtasticProto.PORT_PRIVATE_APP,
                mallory.seal("bob", "m1", wallBase, byteArrayOf(1)),
                to = 2u,
                wantAck = true,
            )
            settle(1_000)
            assertEquals(1L, b.lora.loraDirectStranger)
            assertEquals(
                "one ACK, booked once",
                LoraAirtime().timeOnAirMs(LoraDirectPolicy.ACK_PAYLOAD_BYTES, LoraAirtime.UNSIGNED),
                b.pace.airtime
                    .snapshot(testScheduler.currentTime)
                    .usedMs,
            )
        }

    @Test
    fun aCompactDmThatAskedForNoAckBooksNothing() =
        runTest {
            val air = FakeMeshtasticAir()
            val (a, b) = pair(air)
            // Opened and delivered, but sent without want_ack and naming no next hop: the firmware ACKs nothing
            // (`ReliableRouter::sniffReceived`), so the window owes nothing.
            b.link.deliver(1u, 1, MeshtasticProto.PORT_PRIVATE_APP, a.seal.seal("bob", "q1", wallBase, "hi".encodeToByteArray()), to = 2u)
            settle(1_000)
            assertEquals(listOf("q1"), b.delivered.map { it.frameId })
            assertEquals(
                0L,
                b.pace.airtime
                    .snapshot(testScheduler.currentTime)
                    .usedMs,
            )
        }

    @Test
    fun aSendTheSessionEndedAheadOfIsOwedUnbookedAndKeepsWhatIsParkedBehindIt() =
        runTest {
            val air = FakeMeshtasticAir()
            val (a, b) = pair(air)
            val up = a.link.state.value
            a.link.sessionEndsNext = true
            a.dm("bob", "f1")
            a.dm("bob", "f2") // parked behind f1: bob is not yet confirmed
            settle(500)
            assertEquals("nothing reached the board", 0, a.link.sent.size)
            assertEquals(
                "and nothing is booked",
                0L,
                a.pace.airtime
                    .snapshot(testScheduler.currentTime)
                    .usedMs,
            )

            a.link.resume(up)
            settle(500)
            assertEquals("f1 airs after the reconnect, and f2 still waits on its answer", 1, a.link.sent.size)
            settle()
            assertEquals(listOf("f1", "f2"), b.delivered.map { it.frameId })
            assertEquals("each once", 2, a.link.sent.size)
        }

    @Test
    fun aSendWhoseQueueStatusNeverCameIsAwaitedUnderItsHandleNotAbandoned() =
        runTest {
            val air = FakeMeshtasticAir()
            val (a, b) = pair(air)
            a.link.timeoutNext = true
            a.dm("bob", "f1")
            a.dm("bob", "f2") // parked behind f1: bob is not yet confirmed
            settle(500)
            assertEquals("f2 still waits on f1's answer", 1, a.link.sent.size)
            assertEquals(1, a.transport.status.value.direct.inFlight)
            assertEquals(
                LoraDirectPolicy.AIRINGS * priced(hiPacket),
                a.pace.airtime
                    .snapshot(testScheduler.currentTime)
                    .usedMs,
            )
            settle()
            assertEquals(listOf("f1", "f2"), b.delivered.map { it.frameId })
        }

    @Test
    fun anImplicitAckFromARelayEndsTheRetriesAndSilenceEndsTheSendAtItsDeadline() =
        runTest {
            val air = FakeMeshtasticAir().apply { implicitAcks = true }
            val a = node(air, 1u, "alice", mapOf("bob" to holder(2u)))
            up(a) // bob's board is off; a relay repeats alice's packet
            a.dm("bob", "f1")
            settle(air.untilGiveUp())
            assertEquals("the relay's repeat stopped the retries", 1, air.knitAirings(1u))
            assertEquals("and no give-up comes", 0L, a.lora.loraDirectUnreachable)
            assertEquals(1, a.transport.status.value.direct.inFlight)
            settle(LoraDirectPolicy.deadlineMs(priced(hiPacket), LoraAirtime().slotTimeMs()))
            assertEquals(1L, a.lora.loraDirectNoOutcome)
            assertEquals(1, a.transport.status.value.direct.unreachable)
        }

    // --- their radio has it: the chat's middle state (ADR 040's 2026-10-31 amendment) ---

    @Test
    fun theDestinationsAckOfOurDmTellsTheChatItsRadioHasItButAReceiptsOrAReactionsNever() =
        runTest {
            val air = FakeMeshtasticAir()
            val (a, _) = pair(air)
            a.dm("bob", "f1")
            a.dm("bob", "r1", kind = DirectKind.RECEIPT)
            a.dm("bob", "x1", kind = DirectKind.REACTION)
            settle(10_000)
            assertEquals("every one of them acked by bob's board", 3L, a.lora.loraDirectAcked)
            assertEquals("only the DM, once, on time", listOf("f1" to false), a.reached)
        }

    @Test
    fun anImplicitAckNeverSaysTheirRadioHasIt() =
        runTest {
            val air = FakeMeshtasticAir().apply { implicitAcks = true }
            val a = node(air, 1u, "alice", mapOf("bob" to holder(2u)))
            up(a) // bob's board is off; a relay repeats alice's packet
            a.dm("bob", "f1")
            settle(air.untilGiveUp())
            settle(LoraDirectPolicy.deadlineMs(priced(hiPacket), LoraAirtime().slotTimeMs()))
            assertTrue(a.lora.loraDirectImplicitAck >= 1)
            assertTrue("a relay's repeat is nobody's radio having it", a.reached.isEmpty())
        }

    @Test
    fun aDestinationsAckAfterTheSendEndedSaysTheirRadioHasItAsLate() =
        runTest {
            val air = FakeMeshtasticAir().apply { silent = { _, _ -> true } }
            val (a, _) = pair(air)
            a.dm("bob", "f1")
            settle(1_000)
            val handle = a.link.sentIds.single()
            settle(LoraDirectPolicy.deadlineMs(priced(hiPacket), LoraAirtime().slotTimeMs()))
            assertEquals(1L, a.lora.loraDirectNoOutcome)
            assertTrue(a.reached.isEmpty())
            // The board's to-phone queue, replayed after a BLE drop, hands the destination's ack over at last.
            a.link.emitOutcome(PacketOutcome.Acked(handle, byDestination = true))
            settle(1_000)
            assertEquals("late: its time is not when their board took it", listOf("f1" to true), a.reached)
        }

    /**
     * A pacing refusal our board reports for a send after its own give-up still paces as a late NAK (lora-reviewer's round 4
     * on ADR 2026-11.gsqu's Stage 5c-2b): the give-up is remembered now, so the refusal finds its send, and a class with no
     * send-attached meaning keeps the late NAK's handling rather than falling through unread.
     */
    @Test
    fun aPacingRefusalAfterAGiveUpStillPacesAsALateNak() =
        runTest {
            val air = FakeMeshtasticAir().apply { silent = { _, _ -> true } }
            val logs = mutableListOf<String>()
            val a = node(air, 1u, "alice", mapOf("bob" to holder(2u)), log = { logs += it })
            up(a)
            a.dm("bob", "f1")
            settle(1_000)
            val handle = a.link.sentIds.single()
            a.link.emitOutcome(PacketOutcome.Unreachable(handle, flown = 1))
            settle(1_000)
            val naks = a.lora.loraNak
            a.link.emitOutcome(PacketOutcome.Refused(handle, RoutingError.DUTY_CYCLE_LIMIT, byDestination = false))
            settle(1_000)
            assertEquals("counted as a NAK", naks + 1, a.lora.loraNak)
            assertTrue("paced as a late one: $logs", logs.any { it == "lora nak id=$handle reason=DUTY_CYCLE_LIMIT (late)" })
        }

    @Test
    fun aReprobesAckReportsTheSameDm() =
        runTest {
            val air = FakeMeshtasticAir()
            val (a, _) = pair(air)
            a.dm("bob", "f1")
            settle()
            settle(LoraReprobe.BASE_MS + LoraMeshTransport.LINGER_SWEEP_MS)
            assertEquals(1L, a.lora.loraReprobed)
            assertEquals("the re-airing carries the row id: the same DM, twice", listOf("f1" to false, "f1" to false), a.reached)
        }

    /**
     * `byDestination` is only the ack's `from`, a cleartext header anyone holding the channel key can write. Our board
     * says when the proof failed: that ack still ends the send and moves the marks as before (a follow-up), but the chat
     * never hears of it. An absent proof (2.7, ham mode) still counts.
     */
    @Test
    fun anAckWhoseProofOurBoardFoundForgedNeverReachesTheChat() =
        runTest {
            val air = FakeMeshtasticAir().apply { silent = { _, _ -> true } }
            val logs = mutableListOf<String>()
            val a = node(air, 1u, "alice", mapOf("bob" to holder(2u), "carol" to holder(3u)), log = { logs += it })
            up(a)
            a.dm("bob", "f1")
            a.dm("carol", "f2")
            settle(1_000)
            val (forged, absent) = a.link.sentIds
            a.link.emitOutcome(PacketOutcome.Acked(forged, byDestination = true, proof = AckProof.INVALID))
            a.link.emitOutcome(PacketOutcome.Acked(absent, byDestination = true, proof = AckProof.ABSENT))
            settle(1_000)
            assertEquals(2L, a.lora.loraDirectAcked)
            assertEquals(listOf("f2" to false), a.reached)
            assertTrue(logs.any { it == "lora direct ack proof invalid f1 -> bob" })
        }

    // --- their board acked a frame of ours, of any kind: the ride-only ✓✓'s release (ADR 2026-10.x9fa, 2026-11-19) ---

    /**
     * Field trial 2026-11-20: a ✓✓ held for bob waited 70 min because only a typed DM's ack released it, while a later ✓✓
     * that bob's board did ack released nothing. Every kind's ack by the destination's own board says so, once each.
     */
    @Test
    fun theDestinationsAckOfAnyKindTellsTheAppTheirBoardTookIt() =
        runTest {
            val air = FakeMeshtasticAir()
            val (a, _) = pair(air)
            a.dm("bob", "r1", kind = DirectKind.RECEIPT)
            settle(10_000)
            assertEquals("a ✓✓'s ack, once", listOf("r1" to "bob"), a.boardAcked)
            assertTrue("and never the chat's middle state", a.reached.isEmpty())
            a.dm("bob", "f1")
            settle(10_000)
            assertEquals("a DM's ack too, once", listOf("r1" to "bob", "f1" to "bob"), a.boardAcked)
            assertEquals(listOf("f1" to false), a.reached)
            a.dm("bob", "x1", kind = DirectKind.REACTION)
            settle(10_000)
            assertEquals("and a reaction's", listOf("r1" to "bob", "f1" to "bob", "x1" to "bob"), a.boardAcked)
        }

    @Test
    fun aDestinationsAckAfterTheSendEndedStillTellsTheAppTheirBoardTookIt() =
        runTest {
            val air = FakeMeshtasticAir().apply { silent = { _, _ -> true } }
            val (a, _) = pair(air)
            a.dm("bob", "r1", kind = DirectKind.RECEIPT)
            settle(1_000)
            val handle = a.link.sentIds.single()
            settle(LoraDirectPolicy.deadlineMs(priced(hiPacket), LoraAirtime().slotTimeMs()))
            assertEquals(1L, a.lora.loraDirectNoOutcome)
            assertTrue(a.boardAcked.isEmpty())
            a.link.emitOutcome(PacketOutcome.Acked(handle, byDestination = true))
            settle(1_000)
            assertEquals("the late ack of a ✓✓, by its frame id", listOf("r1" to "bob"), a.boardAcked)
        }

    @Test
    fun anImplicitAckNeverSaysTheirBoardTookIt() =
        runTest {
            val air = FakeMeshtasticAir().apply { implicitAcks = true }
            val a = node(air, 1u, "alice", mapOf("bob" to holder(2u)))
            up(a) // bob's board is off; a relay repeats alice's packets
            a.dm("bob", "r1", kind = DirectKind.RECEIPT)
            a.dm("bob", "f1")
            settle(air.untilGiveUp())
            settle(LoraDirectPolicy.deadlineMs(priced(hiPacket), LoraAirtime().slotTimeMs()))
            assertTrue(a.lora.loraDirectImplicitAck >= 1)
            assertTrue("a relay's repeat is nobody's board having it", a.boardAcked.isEmpty())
        }

    @Test
    fun anAckWhoseProofOurBoardFoundForgedNeverSaysTheirBoardTookIt() =
        runTest {
            val air = FakeMeshtasticAir().apply { silent = { _, _ -> true } }
            val a = node(air, 1u, "alice", mapOf("bob" to holder(2u), "carol" to holder(3u)))
            up(a)
            a.dm("bob", "r1", kind = DirectKind.RECEIPT)
            a.dm("carol", "r2", kind = DirectKind.RECEIPT)
            settle(1_000)
            val (forged, absent) = a.link.sentIds
            a.link.emitOutcome(PacketOutcome.Acked(forged, byDestination = true, proof = AckProof.INVALID))
            a.link.emitOutcome(PacketOutcome.Acked(absent, byDestination = true, proof = AckProof.ABSENT))
            settle(1_000)
            assertEquals(2L, a.lora.loraDirectAcked)
            assertEquals("an absent proof still counts; a forged one never", listOf("r2" to "carol"), a.boardAcked)
        }

    // --- PKI: firmware 2.8 encrypts every compact DM to the destination board's key (ADR 2026-10.aajj) ---

    /**
     * The cap is the firmware's PKI size check, not the BLE write's: a seal one byte past 219 B is refused at the offer
     * and never written, where the old cap (229 at this board's MTU) handed the board a packet it refuses `TOO_LARGE`.
     */
    @Test
    fun aSealPastThePkiCapIsRefusedAtTheOfferAndOneAtItFlies() =
        runTest {
            val air = FakeMeshtasticAir()
            val (a, b) = pair(air)
            val cap = MeshtasticProto.MAX_PAYLOAD - MeshtasticProto.PKC_OVERHEAD_BYTES
            assertEquals(cap, LoraDirectPolicy.payloadCap(MeshtasticProto.MAX_PAYLOAD))
            a.dm("bob", "long", body = "x".repeat(cap + 1 - LORA_DIRECT_OVERHEAD))
            settle()
            assertTrue("refused at the offer, never written", a.link.sent.isEmpty())
            assertEquals(1L, a.lora.loraDirectTooLong)
            a.dm("bob", "full", body = "x".repeat(cap - LORA_DIRECT_OVERHEAD))
            settle()
            assertEquals(listOf(cap), a.link.sent.map { it.size })
            assertEquals("no TOO_LARGE: delivered and acked", listOf("full"), b.delivered.map { it.frameId })
            assertEquals(1L, a.lora.loraDirectAcked)
        }

    /** A PKI DM lands on the receiver's index 0, whatever slot it was sent on; once it opens, its radio is counted. */
    @Test
    fun aPkiDmOnIndexZeroIsDeliveredAndItsRadioCountsOnceItOpens() =
        runTest {
            val air = FakeMeshtasticAir()
            val (a, b) = pair(air)
            a.dm("bob", "f1")
            settle()
            assertEquals(listOf("f1"), b.delivered.map { it.frameId })
            assertEquals("alice's board, heard through a DM that authenticated", 1, b.transport.status.value.boardsHeard)
            // A forged one on index 0 opens as nobody's: no radio is counted for it.
            b.link.deliver(
                9u,
                0,
                MeshtasticProto.PORT_PRIVATE_APP,
                FakeSeal("mallory") {
                    emptySet()
                }.seal("bob", "m1", wallBase, byteArrayOf(1)),
                to = 2u,
                pkiEncrypted = true,
            )
            settle(1_000)
            assertEquals(1L, b.lora.loraDirectStranger)
            assertEquals(1, b.transport.status.value.boardsHeard)
        }

    private class Keyed(
        val node: UInt,
        val key: String,
    )

    /** A board whose node number is the CRC32 of its key, as on 2.8. */
    private fun keyed(seed: Int): Keyed {
        val key = ByteArray(MeshtasticProto.NODE_PUBLIC_KEY_BYTES) { (seed * 31 + it).toByte() }
        val node = LoraContactPolicy.crc32(key)
        check(node != 0u && node != MeshtasticProto.BROADCAST)
        return Keyed(node, b64(key))
    }

    private fun keyedHolder(
        board: Keyed,
        key: String? = board.key,
        contact: Boolean = true,
    ) = LoraBoardHolder(BoardAddr(board.node), direct = true, profileVersion = 1, loraKey = key, contact = contact)

    private fun TestScope.keyedPair(air: FakeMeshtasticAir): Triple<Node, Node, Pair<Keyed, Keyed>> {
        val ka = keyed(1)
        val kb = keyed(2)
        val a = node(air, ka.node, "alice", mapOf("bob" to keyedHolder(kb)), publicKey = ka.key)
        val b = node(air, kb.node, "bob", mapOf("alice" to keyedHolder(ka)), publicKey = kb.key)
        up(a, b)
        return Triple(a, b, ka to kb)
    }

    /**
     * Our board holds no key for bob's (`--remove-node`): it refuses the DM with `39` and nothing airs. The frame is
     * held, one `add_contact` teaches our board bob's key, and the DM flies on that write's own res 0 — a DM offered
     * meanwhile waits behind it. The self-NAK that follows the `queueStatus` is the same refusal, acted on once, never a
     * pacing NAK. A second DM writes nothing more.
     */
    @Test
    fun aMissingKeyIsTaughtOnceAndTheHeldDmFliesOnTheWrite() =
        runTest {
            val air = FakeMeshtasticAir()
            val (a, b, boards) = keyedPair(air)
            val kb = boards.second
            a.link.forget(kb.node)
            a.dm("bob", "f1")
            a.dm("bob", "f2")
            settle(10_000)
            settle(10_000)

            assertEquals(
                "one add_contact, for bob's board, with bob's key",
                listOf(kb.node to kb.key),
                a.link.contactsWritten.map {
                    it.node to
                        it.key
                },
            )
            assertEquals(1L, a.lora.loraDirectNoKey)
            assertEquals(1L, a.lora.loraDirectContactAdded)
            assertEquals("the late self-NAK is that refusal, not a pacing NAK", 0L, a.lora.loraNak)
            assertEquals(listOf("f1", "f2"), b.delivered.map { it.frameId })
            assertEquals("only what flew after the write aired", 2, air.knitAirings(boards.first.node))
            assertEquals(0, a.transport.status.value.direct.noKey)

            a.dm("bob", "f3")
            settle()
            assertEquals("the board holds the key now: no second write", 1, a.link.contactsWritten.size)
            assertEquals(listOf("f1", "f2", "f3"), b.delivered.map { it.frameId })
        }

    /** The rarer order: the self-NAK reaches the phone before the `queueStatus`. The same, once. */
    @Test
    fun aMissingKeyReportedNakFirstIsActedOnOnce() =
        runTest {
            val air = FakeMeshtasticAir()
            val (a, b, boards) = keyedPair(air)
            a.link.forget(boards.second.node)
            a.link.noKeyNakFirst = true
            a.dm("bob", "f1")
            settle(10_000)
            assertEquals(1, a.link.contactsWritten.size)
            assertEquals(1L, a.lora.loraDirectNoKey)
            assertEquals(listOf("f1"), b.delivered.map { it.frameId })
        }

    /**
     * A board that takes the `add_contact` and keeps no key (`is_managed`, locked storage) answers the re-offer with a
     * second `39`: only then is the peer held as keyless — counted, never silent, nothing more written. A NodeInfo from
     * bob's board with a key lifts the hold and the newest DM goes once more; still keyless, it is held again with
     * nothing written, since no DM reached bob since the write.
     */
    @Test
    fun aBoardThatDropsTheContactIsHeldKeylessAfterOneWrite() =
        runTest {
            val air = FakeMeshtasticAir()
            val (a, b, boards) = keyedPair(air)
            val kb = boards.second
            a.link.forget(kb.node)
            a.link.isManaged = true
            a.dm("bob", "f1")
            settle(10_000)
            assertEquals(1, a.link.contactsWritten.size)
            assertEquals("the re-offer's 39 too", 2L, a.lora.loraDirectNoKey)
            assertEquals(1, a.transport.status.value.direct.noKey)
            assertEquals("nothing aired", 0, air.knitAirings(boards.first.node))
            assertTrue(b.delivered.isEmpty())

            a.dm("bob", "f2")
            settle()
            assertEquals(1L, a.lora.loraDirectHeld)

            val user =
                ProtoWriter()
                    .string(
                        MeshtasticProto.USER_LONG_NAME,
                        "Knit beef",
                    ).bytes(
                        MeshtasticProto.USER_PUBLIC_KEY,
                        ByteArray(32) {
                            2
                        },
                    ).toByteArray()
            a.link.deliver(kb.node, 0, MeshtasticProto.PORT_NODEINFO, user)
            settle(10_000)
            assertEquals("asked once more, and keyless again", 3L, a.lora.loraDirectNoKey)
            assertEquals("with no second write", 1, a.link.contactsWritten.size)
            assertEquals(1, a.transport.status.value.direct.noKey)
        }

    /**
     * Once a DM reached bob's board under the key we wrote, our board may be taught it again should it lose it: a board
     * reflashed, factory reset or evicted must be repairable.
     */
    @Test
    fun aKeyThatReachedItsBoardMayBeTaughtOnceMoreAfterOurBoardLosesIt() =
        runTest {
            val air = FakeMeshtasticAir()
            val (a, b, boards) = keyedPair(air)
            val kb = boards.second
            a.link.forget(kb.node)
            a.dm("bob", "f1")
            settle(10_000)
            a.link.forget(kb.node)
            a.dm("bob", "f2")
            settle(10_000)
            assertEquals(2, a.link.contactsWritten.size)
            assertEquals(listOf("f1", "f2"), b.delivered.map { it.frameId })
        }

    /**
     * Nothing is written for a stranger, a profile whose board number is not its key's CRC32, or a board our board's
     * NodeInfo stream showed another key for after the send was issued — each peer held keyless and counted, the
     * conflict by name.
     */
    @Test
    fun noContactIsWrittenForAStrangerAnUnboundKeyOrAKeyTheBoardShowsOtherwise() =
        runTest {
            val air = FakeMeshtasticAir()
            val ka = keyed(1)
            val stranger = keyed(3)
            val unbound = keyed(4)
            val conflicted = keyed(5)
            val a =
                node(
                    air,
                    ka.node,
                    "alice",
                    mapOf(
                        "sam" to keyedHolder(stranger, contact = false),
                        "uma" to keyedHolder(unbound, key = keyed(6).key),
                        "cy" to keyedHolder(conflicted),
                    ),
                    publicKey = ka.key,
                )
            up(a)
            listOf(stranger, unbound, conflicted).forEach { a.link.forget(it.node) }
            // Our board streams cy's NodeInfo with another key as it refuses cy's DM: the race the 39 cannot cover.
            a.link.onPkiRefusal =
                { to -> if (to == conflicted.node) a.link.streamUser(conflicted.node, user("Meshtastic 1234", ByteArray(32) { 9 })) }
            a.dm("sam", "s1")
            a.dm("uma", "u1")
            a.dm("cy", "c1")
            settle()
            assertTrue("no add_contact at all", a.link.contactsWritten.isEmpty())
            assertEquals(3L, a.lora.loraDirectNoKey)
            assertEquals(3, a.transport.status.value.direct.noKey)
            assertEquals(1L, a.lora.loraDirectBoardKeyConflict)
            assertEquals(0, air.knitAirings(ka.node))
        }

    private fun user(
        name: String,
        key: ByteArray?,
    ): ByteArray =
        ProtoWriter()
            .string(MeshtasticProto.USER_LONG_NAME, name)
            .string(MeshtasticProto.USER_SHORT_NAME, "BOB")
            .varint(userRole, 1)
            .apply { key?.let { bytes(MeshtasticProto.USER_PUBLIC_KEY, it) } }
            .toByteArray()

    /**
     * `--remove-node` drops bob from our board, and the firmware never tells the phone: the session's copy of bob's
     * NodeInfo still carries his key. The 39 is the board's word, so the key is set aside and exactly one `add_contact`
     * goes, with bob's stored names and nothing else of that `User`.
     */
    @Test
    fun aKeyRememberedFromBeforeTheBoardForgotItIsSetAsideAndTheContactWritten() =
        runTest {
            val air = FakeMeshtasticAir()
            val (a, b, boards) = keyedPair(air)
            val kb = boards.second
            a.link.streamUser(kb.node, user("Bob's board", b64d(kb.key)))
            a.link.forget(kb.node)
            a.dm("bob", "f1")
            settle(10_000)
            assertEquals(1, a.link.contactsWritten.size)
            assertEquals(listOf("f1"), b.delivered.map { it.frameId })
            assertEquals(0L, a.lora.loraDirectBoardKeyConflict)
            val written =
                BoardOwnerRaw(
                    a.link.contactsWritten
                        .single()
                        .user,
                )
            assertEquals("Bob's board", written.owner.longName)
            assertEquals("BOB", written.owner.shortName)
            assertEquals("never role, hw_model or what else came off the air", null, readVarintField(written.raw, userRole))
        }

    /** A stranger's NodeInfo claiming bob's number with another key, streamed before the send, blocks nothing. */
    @Test
    fun aForgedNodeInfoBeforeTheSendCannotStopTheContact() =
        runTest {
            val air = FakeMeshtasticAir()
            val (a, b, boards) = keyedPair(air)
            val kb = boards.second
            a.link.streamUser(kb.node, user("Bob", ByteArray(32) { 7 }))
            a.link.forget(kb.node)
            a.dm("bob", "f1")
            settle(10_000)
            assertEquals(listOf(kb.node to kb.key), a.link.contactsWritten.map { it.node to it.key })
            assertEquals(listOf("f1"), b.delivered.map { it.frameId })
            assertEquals(0L, a.lora.loraDirectBoardKeyConflict)
        }

    /**
     * An `add_contact` whose `queueStatus` never comes back is re-offered once without recording the write; a board that
     * dropped it answers that re-offer with a second 39, which is then terminal — no second write, no loop.
     */
    @Test
    fun aContactWriteThatTimesOutIsReofferedOnceAndNeverLoops() =
        runTest {
            val air = FakeMeshtasticAir()
            val (a, b, boards) = keyedPair(air)
            a.link.forget(boards.second.node)
            a.link.isManaged = true
            a.link.adminTimeoutNext = true
            a.dm("bob", "f1")
            settle(10_000)
            settle(10_000)
            assertEquals(1, a.link.contactsWritten.size)
            assertEquals("a timeout is not a recorded write", 0L, a.lora.loraDirectContactAdded)
            assertEquals(2L, a.lora.loraDirectNoKey)
            assertEquals(1, a.transport.status.value.direct.noKey)
            assertTrue(b.delivered.isEmpty())
        }

    /**
     * Bob's board holds no key for ours: it answers `PKI_UNKNOWN_PUBKEY` (35), ours sends it our NodeInfo — booked — and
     * the DM goes once more a retransmit gap on and lands. Never a refusal: a 35 heals within seconds.
     */
    @Test
    fun aPeerLackingOurKeyIsSentOursAndTheDmGoesOnceMoreNeverRefused() =
        runTest {
            val air = FakeMeshtasticAir()
            val (a, b) = pair(air)
            b.link.forget(1u)
            a.dm("bob", "f1")
            a.dm("bob", "f2")
            settle(3_000)
            assertEquals(1L, a.lora.loraDirectPeerLacksOurKey)
            assertEquals("never refused", 0, a.transport.status.value.direct.refused)
            assertEquals("our board sent bob's ours", 1, air.nodeInfoAirings(1u))
            assertTrue(b.delivered.isEmpty())
            settle(20_000)
            assertEquals(listOf("f1", "f2"), b.delivered.map { it.frameId })
            val nodeInfo = LoraAirtime().timeOnAirMs(LoraDirectPolicy.NODEINFO_USER_BYTES, LoraAirtime.UNSIGNED)
            assertEquals(
                "one airing for each DM that flew, and our NodeInfo",
                3 * priced(hiPacket) + nodeInfo,
                a.pace.airtime
                    .snapshot(testScheduler.currentTime)
                    .usedMs,
            )
        }

    /**
     * #243: under 2.8.2's `BALANCED` our board signs the NodeInfo it sends bob's even though it is a unicast
     * (`Router.cpp:1222-1227` at `34cb8d5`), so it is booked signed; the compact DMs stay PKI, unsigned.
     */
    @Test
    fun theNodeInfoAfterA35IsBookedSignedUnderABalanced282Board() =
        runTest {
            val air = FakeMeshtasticAir()
            val (a, b) = pair(air, firmware = "2.8.2.34cb8d5", signaturePolicy = SignaturePolicy.BALANCED)
            b.link.forget(1u)
            a.dm("bob", "f1")
            settle(3_000)
            settle(20_000)
            assertEquals(listOf("f1"), b.delivered.map { it.frameId })
            val nodeInfo =
                LoraAirtime().timeOnAirMs(
                    LoraDirectPolicy.NODEINFO_USER_BYTES,
                    MeshtasticProto.maxSignedPayload(MeshtasticProto.PORT_NODEINFO),
                )
            assertTrue(nodeInfo > LoraAirtime().timeOnAirMs(LoraDirectPolicy.NODEINFO_USER_BYTES, LoraAirtime.UNSIGNED))
            assertEquals(
                "one airing for each send of the DM, and our signed NodeInfo",
                2 * priced(hiPacket) + nodeInfo,
                a.pace.airtime
                    .snapshot(testScheduler.currentTime)
                    .usedMs,
            )
        }

    /** The one re-offer after a 35 drawing a second 35 is a give-up, held until bob's board is heard. */
    @Test
    fun aSecondPeerKeyRefusalOfTheReofferIsAGiveUp() =
        runTest {
            val air = FakeMeshtasticAir()
            val (a, b) = pair(air)
            b.link.forget(1u)
            a.dm("bob", "f1")
            settle(3_000)
            b.link.forget(1u)
            settle(20_000)
            assertEquals(2L, a.lora.loraDirectPeerLacksOurKey)
            assertEquals(1, a.transport.status.value.direct.unreachable)
            assertEquals(0, a.transport.status.value.direct.refused)
            assertTrue(b.delivered.isEmpty())
        }

    /**
     * lora-reviewer's round 2: the second 35's give-up marks bob unreachable, not keyless, so the DM typed during that send
     * gets the next attempt like any failure's — afresh, so its own 35 sends our NodeInfo once more and it goes again.
     */
    @Test
    fun aSecondPeerKeyRefusalHandsItsTurnToTheDmTypedMeanwhile() =
        runTest {
            val air = FakeMeshtasticAir()
            val logs = mutableListOf<String>()
            val (a, b) = pair(air, log = { logs += it })
            b.link.forget(1u)
            a.dm("bob", "f1")
            settle(3_000)
            b.link.forget(1u)
            a.dm("bob", "f2") // inside the re-offer's gap: parked behind it
            settle(1_000)
            assertEquals(1, a.transport.status.value.direct.parked)
            settle(20_000)
            assertEquals(2L, a.lora.loraDirectPeerLacksOurKey)
            assertTrue(logs.any { it.startsWith("lora direct next f2 -> bob") })
            settle(20_000)
            assertEquals("f2 landed on its own attempt, and f1 followed its fresh ack", listOf("f2", "f1"), b.delivered.map { it.frameId })
            assertEquals(0, a.transport.status.value.direct.parked)
        }

    // --- helpers ---

    /** `User.role` (`mesh.proto`): a field an `add_contact` never echoes. */
    private val userRole = 7

    /** A compact DM of the default body "hi": the overhead and two bytes. */
    private val hiPacket = LORA_DIRECT_OVERHEAD + 2

    /** One airing of a compact DM of [size] bytes as the ledger prices it: unsigned, with the PKC overhead (PKI on 2.8). */
    private fun priced(size: Int): Long = LoraAirtime().timeOnAirMs(size + MeshtasticProto.PKC_OVERHEAD_BYTES, LoraAirtime.UNSIGNED)

    /** Past the fake firmware's `MAX_RETRANSMIT` for a "hi" DM nobody answers, with a little slack. */
    private fun FakeMeshtasticAir.untilGiveUp(): Long = giveUpMs(hiPacket) + 5_000

    private fun dmForm(
        from: String,
        to: String,
    ): WireEnvelope {
        val env =
            RelayEnvelope(
                type = FrameType.CHAT,
                id = "dm-$from-$to",
                senderId = from,
                recipientId = to,
                sentAt = wallBase,
                payload = WireCodec.encodePayload(ChatContent(body = "sealed")),
            )
        return WireEnvelope(relay = true, sig = ByteArray(64) { 7 }, signed = WireCodec.encodeEnvelope(env))
    }

    /** Bob's board puts something on the air that alice's board hears: a telemetry broadcast, with an RSSI. */
    private fun FakeMeshtasticLink.deliverNothingButAir(
        air: FakeMeshtasticAir,
        from: UInt,
        to: UInt,
    ) {
        check(nodeNum == from)
        air.broadcast(from, 0, MeshtasticProto.PORT_TELEMETRY, byteArrayOf(1))
        check(to != from)
    }

    private fun LoraMeshTransport.lingerReprobes(): List<String> = reprobeSchedule().map { it.recipientId }
}
