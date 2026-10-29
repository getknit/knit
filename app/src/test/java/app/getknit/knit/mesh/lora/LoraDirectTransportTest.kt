package app.getknit.knit.mesh.lora

import app.getknit.knit.mesh.MeshMetrics
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

    /** Our own DMs without their ✓✓, newest first, per peer; a test acks one by removing it. */
    private class FakeSource : LoraDirectSource {
        val unacked = mutableMapOf<String, MutableList<DirectDm>>()

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
        ): List<DirectDm> = unacked[peerId].orEmpty().take(limit)

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
    )

    private val wallBase = 1_700_000_000_000L

    private fun TestScope.node(
        air: FakeMeshtasticAir,
        nodeNum: UInt,
        id: String,
        boards: Map<String, LoraBoardHolder>? = emptyMap(),
        knitIndex: Int = 1,
        scope: CoroutineScope = backgroundScope,
        state: LoraPlaneState = LoraPlaneState.None,
    ): Node {
        val link = FakeMeshtasticLink(nodeNum, air, scope = scope).apply { readyOnStart = false }
        val metrics = MeshMetrics()
        val boardFlow = MutableStateFlow(boards)
        val seal = FakeSeal(id) { boardFlow.value.orEmpty().keys }
        val source = FakeSource()
        val delivered = mutableListOf<OpenedDirect>()
        val config = MutableStateFlow<LoraConfig?>(LoraConfig("AA:$nodeNum", knitIndex))
        val pace = LoraPacePolicy(minGapMs = 0)
        val transport =
            LoraMeshTransport(
                selfId = { id },
                link = link,
                config = config,
                crypto = seal,
                // A compact ✓✓ in these tests is a body "ack:<id>", applied to our own unacked set as the pipeline would.
                inbox = { opened ->
                    delivered += opened
                    opened.plaintext
                        .decodeToString()
                        .takeIf { it.startsWith("ack:") }
                        ?.let { source.ack(it.removePrefix("ack:")) }
                },
                source = source,
                boardMap = boardFlow.filterNotNull(),
                scope = scope,
                metrics = metrics,
                state = state,
                clock = { testScheduler.currentTime },
                wallClock = { wallBase + testScheduler.currentTime },
                pace = pace,
            )
        return Node(id, transport, link, metrics, seal, source, delivered, boardFlow, config, pace)
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

    private fun TestScope.pair(air: FakeMeshtasticAir): Pair<Node, Node> {
        val a = node(air, 1u, "alice", mapOf("bob" to holder(2u)))
        val b = node(air, 2u, "bob", mapOf("alice" to holder(1u)))
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
                "one unsigned airing: a unicast is never signed",
                ledger.timeOnAirMs(size, LoraAirtime.UNSIGNED),
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
            val dmCost = LoraDirectPolicy.AIRINGS * ledger.timeOnAirMs(size, LoraAirtime.UNSIGNED)
            val total = ledger.allowanceMs()
            // Into the last quarter, with room left for both frames' every airing and not one airing more.
            val one = ledger.timeOnAirMs(size, LoraAirtime.UNSIGNED)
            while (ledger.snapshot(now).usedMs + 2 * dmCost + one <= total) ledger.record(size, now, LoraAirtime.UNSIGNED)
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

    @Test
    fun aBoardThatIsOffEndsInUnreachableAfterEveryRetryAndHoldsTheRest() =
        runTest {
            val air = FakeMeshtasticAir()
            val a = node(air, 1u, "alice", mapOf("bob" to holder(2u)))
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
            assertEquals("the send and every retry aired", FakeMeshtasticAir.UNICAST_ATTEMPTS, air.knitAirings(1u))
            val size =
                a.link.sent
                    .single()
                    .size
            assertEquals(
                "and every retry is booked",
                LoraDirectPolicy.AIRINGS * LoraAirtime().timeOnAirMs(size, LoraAirtime.UNSIGNED),
                a.pace.airtime
                    .snapshot(testScheduler.currentTime)
                    .usedMs,
            )
            assertEquals("the parked offers are left to the source", 0, a.transport.status.value.direct.parked)
            assertEquals(1, a.transport.status.value.direct.unreachable)

            a.dm("bob", "f4")
            settle()
            assertEquals("held while unreachable", 1, a.link.sent.size)
            assertEquals(1L, a.lora.loraDirectHeld)
        }

    @Test
    fun aBoardThatComesBackAndAirsGetsTheNewestDmThenTheRestAfterItsAck() =
        runTest {
            val air = FakeMeshtasticAir()
            val a = node(air, 1u, "alice", mapOf("bob" to holder(2u)))
            up(a)
            a.dm("bob", "f1")
            settle(air.untilGiveUp())
            a.dm("bob", "f2")
            a.dm("bob", "f3")
            settle()
            assertEquals(1L, a.lora.loraDirectUnreachable)
            assertEquals(2L, a.lora.loraDirectHeld)

            // A gap after the failure — the re-probe deferred meanwhile by a spool cover — bob's board comes back and
            // airs anything at all.
            a.transport.coveredByInternet(setOf("bob"))
            settle(LoraReprobe.BASE_MS + LoraMeshTransport.LINGER_SWEEP_MS)
            a.transport.coveredByInternet(emptySet())
            assertEquals("nothing aired while covered", 1, a.link.sent.size)
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

    @Test
    fun noOutcomeAtAllEndsAtTheDeadlineAsUnreachableAndBooksEveryRetry() =
        runTest {
            val air = FakeMeshtasticAir().apply { silent = { _, _ -> true } }
            val (a, _) = pair(air)
            a.dm("bob", "f1")
            settle(LoraDirectPolicy.ANSWER_SLACK_MS)
            assertEquals(1, a.transport.status.value.direct.inFlight)
            settle(LoraDirectPolicy.deadlineMs(LoraAirtime().timeOnAirMs(hiPacket, LoraAirtime.UNSIGNED), LoraAirtime().slotTimeMs()))
            assertEquals(1L, a.lora.loraDirectNoOutcome)
            assertEquals(0, a.transport.status.value.direct.inFlight)
            assertEquals("never reachable on silence", 1, a.transport.status.value.direct.unreachable)
            assertEquals(
                LoraDirectPolicy.AIRINGS *
                    LoraAirtime().timeOnAirMs(
                        a.link.sent
                            .single()
                            .size,
                        LoraAirtime.UNSIGNED,
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

    @Test
    fun anAsymmetricLinkStaysBoundedToOneSendAndItsRetries() =
        runTest {
            val air = FakeMeshtasticAir().apply { lossy = { from, _ -> from == 2u } } // bob hears alice, alice never hears bob
            val (a, b) = pair(air)
            repeat(5) { a.dm("bob", "f$it") }
            settle(air.untilGiveUp())
            assertEquals("one send and its retries, not one per DM", FakeMeshtasticAir.UNICAST_ATTEMPTS, air.knitAirings(1u))
            assertEquals(listOf("f0"), b.delivered.map { it.frameId })
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
            a.transport.coveredByInternet(setOf("bob")) // the re-probe defers while the gap runs out
            settle(LoraReprobe.BASE_MS + LoraMeshTransport.LINGER_SWEEP_MS)
            a.transport.coveredByInternet(emptySet())
            // Bob's compact ✓✓ for f1 is delivered before his board counts as heard, so the resend the hearing would make
            // asks a source that already has f1 acked, and nothing is re-aired.
            b.dm("alice", "t1", body = "ack:f1", kind = DirectKind.RECEIPT)
            settle()
            assertEquals(listOf("t1"), a.delivered.map { it.frameId })
            assertEquals(0, a.transport.status.value.direct.unreachable)
            assertEquals(0L, a.lora.loraDirectResent)
            assertEquals(1, a.link.sent.size)
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
            val one = ledger.timeOnAirMs(size, LoraAirtime.UNSIGNED)
            val total = ledger.allowanceMs()
            // Leave room for two airings of it, short of the five (`LoraDirectPolicy.AIRINGS`) it is admitted against.
            while (total - ledger.snapshot(now).usedMs >= 3 * one) ledger.record(size, now, LoraAirtime.UNSIGNED)
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
                    LoraAirtime().timeOnAirMs(
                        a.link.sent
                            .single()
                            .size,
                        LoraAirtime.UNSIGNED,
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
            settle(LoraDirectPolicy.deadlineMs(LoraAirtime().timeOnAirMs(hiPacket, LoraAirtime.UNSIGNED), LoraAirtime().slotTimeMs()))
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
    fun aSendInFlightHoldsItsRetriesAgainstTheWindowUntilItsAckRefundsThem() =
        runTest {
            val air = FakeMeshtasticAir()
            val a = node(air, 1u, "alice", mapOf("bob" to holder(2u), "carol" to holder(3u)))
            val b = node(air, 2u, "bob", mapOf("alice" to holder(1u)))
            val c = node(air, 3u, "carol", mapOf("alice" to holder(1u)))
            up(a, b, c)
            val ledger = a.pace.airtime
            val one = ledger.timeOnAirMs(hiPacket, LoraAirtime.UNSIGNED)
            val total = ledger.allowanceMs()
            val now = testScheduler.currentTime
            // Room for one DM's five airings and three more, not two DMs' ten.
            while (total - ledger.snapshot(now).usedMs >= 9 * one) ledger.record(hiPacket, now, LoraAirtime.UNSIGNED)
            a.dm("bob", "f1")
            a.dm("carol", "g1")
            settle(500)
            assertEquals("bob's retries are still owed: carol's DM waits", 1, a.link.sent.size)
            settle(5_000) // bob's board acks: four unflown airings come back
            assertEquals(listOf(2u, 3u), a.link.sentTos)
            assertEquals(listOf("g1"), c.delivered.map { it.frameId })
        }

    @Test
    fun ourBoardsAckIsBookedForEveryCompactDmAddressedToUsOpenedOrNot() =
        runTest {
            val air = FakeMeshtasticAir()
            val (_, b) = pair(air)
            val mallory = FakeSeal("mallory") { emptySet() }
            b.link.deliver(9u, 1, MeshtasticProto.PORT_PRIVATE_APP, mallory.seal("bob", "m1", wallBase, byteArrayOf(1)), to = 2u)
            settle(1_000)
            assertEquals(1L, b.lora.loraDirectStranger)
            assertEquals(
                LoraAirtime().timeOnAirMs(LoraDirectPolicy.ACK_PAYLOAD_BYTES, LoraAirtime.UNSIGNED),
                b.pace.airtime
                    .snapshot(testScheduler.currentTime)
                    .usedMs,
            )
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
                LoraDirectPolicy.AIRINGS * LoraAirtime().timeOnAirMs(hiPacket, LoraAirtime.UNSIGNED),
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
            settle(LoraDirectPolicy.deadlineMs(LoraAirtime().timeOnAirMs(hiPacket, LoraAirtime.UNSIGNED), LoraAirtime().slotTimeMs()))
            assertEquals(1L, a.lora.loraDirectNoOutcome)
            assertEquals(1, a.transport.status.value.direct.unreachable)
        }

    // --- helpers ---

    /** A compact DM of the default body "hi": the overhead and two bytes. */
    private val hiPacket = LORA_DIRECT_OVERHEAD + 2

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
