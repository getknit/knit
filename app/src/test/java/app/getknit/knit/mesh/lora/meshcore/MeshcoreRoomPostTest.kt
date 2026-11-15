package app.getknit.knit.mesh.lora.meshcore

import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.FastPathDrop
import app.getknit.knit.mesh.MeshMetrics
import app.getknit.knit.mesh.TransportHealth
import app.getknit.knit.mesh.link.FastFrameCodec
import app.getknit.knit.mesh.lora.BoardAddr
import app.getknit.knit.mesh.lora.BoardAir
import app.getknit.knit.mesh.lora.BoardBattery
import app.getknit.knit.mesh.lora.BoardLink
import app.getknit.knit.mesh.lora.BoardPacket
import app.getknit.knit.mesh.lora.BoardPlane
import app.getknit.knit.mesh.lora.BoardSend
import app.getknit.knit.mesh.lora.BoardState
import app.getknit.knit.mesh.lora.DirectDm
import app.getknit.knit.mesh.lora.DirectKind
import app.getknit.knit.mesh.lora.LORA_DIRECT_TAG
import app.getknit.knit.mesh.lora.LoraAirtime
import app.getknit.knit.mesh.lora.LoraAuthorCheck
import app.getknit.knit.mesh.lora.LoraAuthorIds
import app.getknit.knit.mesh.lora.LoraConfig
import app.getknit.knit.mesh.lora.LoraDirectCrypto
import app.getknit.knit.mesh.lora.LoraDirectOpen
import app.getknit.knit.mesh.lora.LoraDirectSource
import app.getknit.knit.mesh.lora.LoraFragCandidates
import app.getknit.knit.mesh.lora.LoraFrameCodec
import app.getknit.knit.mesh.lora.LoraFramePolicy
import app.getknit.knit.mesh.lora.LoraMeshTransport
import app.getknit.knit.mesh.lora.LoraPacePolicy
import app.getknit.knit.mesh.lora.PacketOutcome
import app.getknit.knit.mesh.lora.ProvisionResult
import app.getknit.knit.mesh.lora.ProvisionSpec
import app.getknit.knit.mesh.lora.QueueInfo
import app.getknit.knit.mesh.lora.RadioParams
import app.getknit.knit.mesh.lora.SendPurpose
import app.getknit.knit.mesh.lora.SendResult
import app.getknit.knit.mesh.protocol.ChatContent
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Own room posts and the reassembler on a MeshCore plane (ADR 2026-11.gsqu §4 and its Stage 5c-1 amendment; the security
 * design pass's B2 and lora-reviewer finding 5): the plane core over [MeshcoreTraits] and [MeshcoreHooks] and a fake board
 * link. An own post leaves in at most two elided packets, each booked under the flood ceiling; an inbound one is joined by
 * the combination whose signature verifies against a pinned author, so a forged part with an observed `fragId`, a flush of
 * fresh ids, two authors under one colliding id, a stranger's post and a stale replay each come out as they must. A
 * "verifying" signature here is one whose first byte is [HONEST] from an author whose key we hold.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MeshcoreRoomPostTest {
    private class FakeBoard : BoardLink {
        val ready =
            BoardState.Ready(
                own = BoardAddr.MeshCore(ByteArray(6) { 1 }),
                payloadCap = MeshcoreProto.CHANNEL_DATA_CAP,
                radio = RadioParams(8, 62_500, 5),
                channel = KNIT_SLOT,
                detail =
                    MeshcoreDetail(
                        publicKey = ByteArray(32) { 1 },
                        versionCode = 11,
                        version = "v1.18.0",
                        defaultScope = false,
                        multiAcks = 0,
                        clientRepeat = false,
                        freqKhz = 869_525,
                        contactCapacity = 100,
                        slots = emptyList(),
                        namedForKnit = true,
                        manualAdd = 1,
                        dialled = BOARD_ADDRESS,
                    ),
            )
        override val state = MutableStateFlow<BoardState>(BoardState.Down(TransportHealth.Unavailable, MeshcoreLinkState.Idle))
        val inbound = MutableSharedFlow<BoardPacket>(extraBufferCapacity = 512)
        override val packets = inbound
        val outcome = MutableSharedFlow<PacketOutcome>(extraBufferCapacity = 64)
        override val outcomes = outcome
        override val headroom = MutableSharedFlow<Int>()
        override val headroomNow: Int? = null
        override val battery = MutableStateFlow<BoardBattery?>(null)
        override val boardAir = MutableStateFlow<BoardAir?>(null)
        val sent = mutableListOf<Pair<SendPurpose, ByteArray>>()
        private var nextId = 1u

        override suspend fun send(
            payload: ByteArray,
            purpose: SendPurpose,
        ): BoardSend {
            sent += purpose to payload
            return BoardSend(SendResult.Queued(nextId++, QueueInfo(10, 16, 0)), 0)
        }

        override suspend fun provision(spec: ProvisionSpec): ProvisionResult = ProvisionResult.Failed("not here")

        override fun start(address: String) {
            state.value = ready
        }

        override fun stop() = Unit
    }

    private class Ops : MeshcoreBoardOps {
        override val air = MutableSharedFlow<MeshcoreAir>()
        override val counters = MeshcoreCounters()
        override var observer: MeshcoreSessionObserver? = null

        override fun slotReading(): MeshcoreSlotReading = MeshcoreSlotReading(KNIT_SLOT, knit = true, atMs = 0)

        override suspend fun setup(): MeshcoreSetupResult = MeshcoreSetupResult.NotReady

        override suspend fun restore(): MeshcoreRestoreResult = MeshcoreRestoreResult.NotReady

        override suspend fun unbind(address: String): MeshcoreUnbind = MeshcoreUnbind.NOTHING

        override val consent =
            MutableStateFlow<MeshcoreConsent?>(
                MeshcoreConsent(BOARD_ADDRESS, MeshcoreHooks.digest(ByteArray(32) { 1 }), true, ByteArray(32) { 1 }),
            )

        override fun setKnitContacts(
            ranked: List<ByteArray>,
            withdrawn: Boolean,
        ) = Unit

        override suspend fun teach(key: ByteArray): ContactWrite = ContactWrite.NOT_READY

        override suspend fun removeUntaught(key: ByteArray): ContactWrite = ContactWrite.NOT_READY
    }

    /** Seals readably and opens nothing: these tests read the room. */
    private object Seal : LoraDirectCrypto {
        override fun seal(
            peerId: String,
            frameId: String,
            sentAtMs: Long,
            plaintext: ByteArray,
        ): ByteArray = byteArrayOf(LORA_DIRECT_TAG) + ByteArray(60) + plaintext

        override fun open(packet: ByteArray): LoraDirectOpen = LoraDirectOpen.Stranger
    }

    private object Source : LoraDirectSource {
        override suspend fun unackedTo(
            peerId: String,
            limit: Int,
            untakenOnly: Boolean,
            maxBytes: Int,
        ): List<DirectDm> = if (peerId == BOB) listOf(DirectDm("dm-1", WALL, byteArrayOf(1, 2, 3))) else emptyList()

        override suspend fun isUnacked(frameId: String): Boolean = frameId == "dm-1"
    }

    private class Node(
        val transport: LoraMeshTransport,
        val board: FakeBoard,
        val metrics: MeshMetrics,
        val airtime: LoraAirtime,
        val logs: MutableList<String>,
    )

    private fun TestScope.node(
        self: String = ALICE,
        fragIds: () -> Int = { 7 },
    ): Node {
        val board = FakeBoard()
        val ops = Ops()
        val logs = mutableListOf<String>()
        val metrics = MeshMetrics()
        val airtime = LoraAirtime(dedicatedUnlocksDuty = true, traits = MeshcoreTraits)
        val transport =
            LoraMeshTransport(
                selfId = { self },
                plane =
                    BoardPlane(MeshcoreTraits, board) { core ->
                        MeshcoreHooks(ops, core, backgroundScope, { testScheduler.currentTime }, { logs += it })
                    },
                config = MutableStateFlow(LoraConfig("AA:1", 0, dms = true)),
                crypto = Seal,
                source = Source,
                boardMap =
                    flowOf(
                        MeshcoreContacts.holdersOf(
                            mapOf(
                                BOB to
                                    app.getknit.knit.mesh.MeshcoreBoardHolder(
                                        java.util.Base64
                                            .getEncoder()
                                            .encodeToString(BOB_KEY),
                                        app.getknit.knit.mesh.MeshcoreRank.ACCEPTED,
                                        1,
                                        false,
                                    ),
                            ),
                        ),
                    ),
                scope = backgroundScope,
                metrics = metrics,
                clock = { testScheduler.currentTime },
                wallClock = { WALL + testScheduler.currentTime },
                processStartWall = WALL,
                log = { logs += it },
                pace = LoraPacePolicy(minGapMs = MeshcoreTraits.minGapMs, airtime = airtime),
                authorCheck = LoraAuthorCheck { wire, env -> wire.sig[0] == HONEST && env.senderId in PINNED },
                authorIds = flowOf(LoraAuthorIds(accepted = listOf(BOB, CAROL, ALICE), pinned = PINNED)),
                fragIds = fragIds,
            )
        requireNotNull(ops.observer).beforeReady(board.ready)
        transport.start()
        runCurrent()
        return Node(transport, board, metrics, airtime, logs)
    }

    private fun TestScope.wall(): Long = WALL + testScheduler.currentTime

    /** A signed room post by [author], [bytes] of text that does not deflate. */
    private fun postBy(
        author: String,
        sentAt: Long,
        bytes: Int = LONG_POST,
        sig0: Byte = HONEST,
        seed: Int = 1,
    ): WireEnvelope {
        val random = kotlin.random.Random(seed)
        val body = String(CharArray(bytes) { (0x21 + random.nextInt(0x5E)).toChar() })
        val env =
            RelayEnvelope(
                type = FrameType.CHAT,
                id = "post-$author-$sentAt-$sig0",
                senderId = author,
                sentAt = sentAt,
                payload = WireCodec.encodePayload(ChatContent(body = body)),
            )
        return WireEnvelope(
            relay = true,
            sig =
                ByteArray(64) {
                    (it * seed + sentAt).toByte()
                }.also { it[0] = sig0 },
            signed = WireCodec.encodeEnvelope(env),
        )
    }

    /** [wire] as a MeshCore phone airs it: elided, in one or two packets under [fragId]. */
    private fun packetsOf(
        wire: WireEnvelope,
        fragId: Int = 7,
    ): List<ByteArray> {
        val encoded =
            requireNotNull(
                LoraFrameCodec.encodeBest(wire, fragId, MeshcoreProto.CHANNEL_DATA_CAP, transcode = true, elide = true, bind = true),
            )
        assertTrue(encoded.elided)
        return encoded.parts
    }

    private fun channelPacket(
        payload: ByteArray,
        pathLen: Int = 0,
        live: Boolean = true,
    ) = BoardPacket(
        from = null,
        toUs = false,
        channel = KNIT_SLOT,
        payload = payload,
        rxSnr = 6f,
        rxRssi = null,
        hopsAway = pathLen and MeshcoreProto.PATH_HOPS_MASK,
        rxTimeMs = null,
        kind =
            MeshcorePacket(
                MeshcorePacket.Type.CHANNEL_DATA,
                KNIT_SLOT,
                pathLen,
                null,
                MeshcoreProto.KNIT_DATA_TYPE,
                null,
                live,
                false,
                null,
            ),
    )

    private fun TestScope.hear(
        n: Node,
        vararg payloads: ByteArray,
    ) {
        payloads.forEach { n.board.inbound.tryEmit(channelPacket(it)) }
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
    }

    /** The posts that reached the router, by author. */
    private fun Node.decodedBy(author: String): Int = logs.count { it.startsWith("lora rx ") && it.contains("id=post-$author-") }

    private fun Node.drops(reason: FastPathDrop): Long =
        metrics
            .snapshot()
            .fast.fastDropsByReason[reason] ?: 0L

    private fun header(
        tag: Byte,
        fragId: Int,
        part: Int,
        count: Int = 2,
    ) = byteArrayOf(tag, (fragId shr 8).toByte(), fragId.toByte(), ((part shl 4) or count).toByte())

    /** A bound part 1 under [fragId] carrying [slice] and naming a part 0 of [to]. */
    private fun boundTail(
        fragId: Int,
        slice: ByteArray,
        to: ByteArray = ByteArray(1),
    ): ByteArray = header(FastFrameCodec.TAG_BOUND_FRAG, fragId, 1) + FastFrameCodec.commitmentOf(to) + slice

    /** A bound part's own slice and the hash it names. */
    private fun partOf(packet: ByteArray): FastFrameCodec.Fragment = checkNotNull(FastFrameCodec.parseBoundFragment(packet))

    /** A bound part 0 under [fragId] carrying [slice] and committing to [to]. */
    private fun boundHead(
        fragId: Int,
        slice: ByteArray,
        to: ByteArray,
    ): ByteArray = header(FastFrameCodec.TAG_BOUND_FRAG, fragId, 0) + FastFrameCodec.commitmentOf(to) + slice

    /** The fragment id a bound part carries. */
    private fun idOf(part: ByteArray): Int = ((part[1].toInt() and 0xFF) shl 8) or (part[2].toInt() and 0xFF)

    // --- an own post ---

    @Test
    fun anOwnLongPostAirsInTwoElidedPacketsEachBookedUnderTheFloodCeiling() =
        runTest {
            val n = node()
            n.transport.fastFanout(postBy(ALICE, wall(), bytes = 150))
            runCurrent()
            advanceTimeBy(5_000)
            runCurrent()
            val room =
                n.board.sent
                    .filter { it.first is SendPurpose.KnitFrame }
                    .map { it.second }
            assertEquals("two packets", 2, room.size)
            assertTrue("bound parts", room.all { it[0] == FastFrameCodec.TAG_BOUND_FRAG })
            val head = checkNotNull(FastFrameCodec.parseBoundFragment(room[0]))
            val tail = checkNotNull(FastFrameCodec.parseBoundFragment(room[1]))
            assertEquals("of an elided frame", FastFrameCodec.TAG_ELIDED, head.payload[0])
            assertArrayEquals("part 0 commits to part 1", FastFrameCodec.commitmentOf(tail.payload), head.commitment)
            assertTrue(room.all { it.size <= MeshcoreProto.CHANNEL_DATA_CAP })
            val booked = n.airtime.snapshot(testScheduler.currentTime)
            assertTrue("booked as floods", booked.floodUsedMs > 0)
            assertEquals("every part under the flood ceiling", booked.usedMs, booked.floodUsedMs)
            assertTrue(n.logs.any { it.startsWith("lora tx ") && it.endsWith("parts=2") })
        }

    @Test
    fun anOwnPostPastTwoPacketsIsTooLongForTheRoom() =
        runTest {
            val n = node()
            n.transport.fastFanout(postBy(ALICE, wall(), bytes = 300))
            runCurrent()
            advanceTimeBy(5_000)
            runCurrent()
            assertTrue(n.board.sent.none { it.first is SendPurpose.KnitFrame })
            assertTrue(n.logs.any { it.startsWith("lora room too long ") && it.endsWith("parts=3") })
        }

    // --- the reassembler ---

    @Test
    fun threeForgedPartOnesWithAnObservedFragIdStillLetTheHonestPostDeliver() =
        runTest {
            val n = node()
            val (part0, part1) = packetsOf(postBy(BOB, wall()))
            // Round 1's B1: three distinct junk part-1s aired while the honest one was still on the air, one naming the
            // honest part 0. They wait, cost memory, and pair with nothing.
            val forged =
                List(3) { i ->
                    boundTail(
                        idOf(part0),
                        ByteArray(21) { (0x50 + i).toByte() },
                        to =
                            if (i ==
                                0
                            ) {
                                partOf(part0).payload
                            } else {
                                junk(i)
                            },
                    )
                }
            hear(n, part0, *forged.toTypedArray())
            assertEquals(0, n.decodedBy(BOB))
            hear(n, part1)
            assertEquals("the honest post still delivers", 1, n.decodedBy(BOB))
            assertEquals("no fake took a check", 0L, n.drops(FastPathDrop.FRAG_UNVERIFIED))
            assertEquals(0L, n.drops(FastPathDrop.FRAG_CANDIDATES_FULL))
        }

    @Test
    fun aFakePartZeroThatOvertakesTheHonestOneBehindARepeaterNeverTakesItsPairing() =
        runTest {
            val n = node()
            val (part0, part1) = packetsOf(postBy(BOB, wall()))
            val honest = partOf(part0)
            // Round 2's (a) and (b): the repeater held the honest part 0 while a forger's short part-0s landed first — one
            // copying the honest commitment, two of their own.
            val copying = header(FastFrameCodec.TAG_BOUND_FRAG, honest.fragId, 0) + checkNotNull(honest.commitment) + junk(1)
            val own = List(2) { i -> boundHead(honest.fragId, junk(i + 2), junk(i + 5)) }
            hear(n, copying, *own.toTypedArray(), part0, part1)
            assertEquals("the honest post delivers", 1, n.decodedBy(BOB))
            assertEquals("no fake took a check", 0L, n.drops(FastPathDrop.FRAG_UNVERIFIED))
        }

    @Test
    fun aReorderedHonestPartOneBesideFakeTailsDeliversWhenItsPartZeroLands() =
        runTest {
            val n = node()
            val (part0, part1) = packetsOf(postBy(BOB, wall()))
            val id = idOf(part0)
            hear(n, boundTail(id, junk(1), to = partOf(part0).payload), part1, boundTail(id, junk(2)))
            assertEquals(0, n.decodedBy(BOB))
            hear(n, part0)
            assertEquals("round 2's (d): delivers", 1, n.decodedBy(BOB))
        }

    @Test
    fun aFrameThatDoesNotElideIsBoundAndNeverPadded() =
        runTest {
            // The round-2 security follow-up: a frame the elision cannot reproduce (a sender that is no node id) keeps the
            // transcoder's pick, which may be deflated — and a pad would break both commitments. A cost that would pad
            // every deflated packet to the cap leaves the bound parts as committed.
            val padsAll =
                object : app.getknit.knit.mesh.lora.PacketCost {
                    override fun timeOnAirMs(payloadBytes: Int): Long = if (payloadBytes >= MeshcoreProto.CHANNEL_DATA_CAP) 1 else 1_000

                    override fun padTo(
                        payloadBytes: Int,
                        cap: Int,
                    ): Int = cap
                }
            val words = kotlin.random.Random(3).let { r -> List(90) { "w" + r.nextInt(40) }.joinToString(" ") }
            val env =
                RelayEnvelope(
                    type = FrameType.CHAT,
                    id = "post-bob-not-a-node-id",
                    senderId = "bob",
                    sentAt = wall(),
                    payload = WireCodec.encodePayload(ChatContent(body = words)),
                )
            val wire = WireEnvelope(relay = true, sig = ByteArray(64) { 1 }, signed = WireCodec.encodeEnvelope(env))
            val encoded =
                requireNotNull(
                    LoraFrameCodec.encodeBest(
                        wire,
                        7,
                        MeshcoreProto.CHANNEL_DATA_CAP,
                        transcode = true,
                        cost = padsAll,
                        elide = true,
                        bind = true,
                    ),
                )
            assertFalse("it does not elide", encoded.elided)
            assertEquals(2, encoded.parts.size)
            val (head, tail) = encoded.parts.map(::partOf)
            assertTrue("a deflated frame", FastFrameCodec.deflated(head.payload + tail.payload))
            assertArrayEquals(FastFrameCodec.commitmentOf(tail.payload), head.commitment)
            assertArrayEquals(FastFrameCodec.commitmentOf(head.payload), tail.commitment)
            assertEquals("never grown", 0, encoded.grewBy)
            assertTrue("and it reads back", FastFrameCodec.decodeCompact(head.payload + tail.payload) != null)
        }

    @Test
    fun anAuthorsNextIdsCannotBePrefilledFromOneObservedPost() =
        runTest {
            // The 5c-1 reviews' B2: each fragmented frame draws a fresh id. Alice's plane draws from a seeded source here;
            // production's is SecureRandom.
            val random = kotlin.random.Random(42)
            val alice = node(fragIds = { random.nextInt(0x10000) })
            val bob = node(self = ZED)
            alice.transport.fastFanout(postBy(ALICE, wall()))
            runCurrent()
            advanceTimeBy(5_000)
            runCurrent()
            val first = alice.board.sent.map { it.second }
            hear(bob, *first.toTypedArray())
            assertEquals(1, bob.decodedBy(ALICE))
            // A forger who saw that post fills the next eight ids a counter would take, three junk part-0s each.
            val seen = idOf(first[0])
            for (next in 1..8) {
                repeat(3) { i -> bob.board.inbound.tryEmit(channelPacket(boundHead((seen + next) and 0xFFFF, junk(i), junk(i + 9)))) }
            }
            runCurrent()
            alice.transport.fastFanout(postBy(ALICE, wall(), seed = 5))
            runCurrent()
            advanceTimeBy(5_000)
            runCurrent()
            val second =
                alice.board.sent
                    .map { it.second }
                    .drop(first.size)
            assertEquals(2, second.size)
            assertTrue("not the next id a counter would take", idOf(second[0]) != ((seen + 1) and 0xFFFF))
            hear(bob, *second.toTypedArray())
            assertEquals("the author's next post delivers", 2, bob.decodedBy(ALICE))
        }

    private fun junk(seed: Int) = ByteArray(20) { (seed * 13 + it).toByte() }

    @Test
    fun aStrangersWholePostIsNoPresence() =
        runTest {
            val n = node()
            // A whole 0x05 is read unverified (the router verifies it): on MeshCore it is presence only once it verifies.
            val strangers = postBy(DAVE, wall(), bytes = 20)
            val dave =
                requireNotNull(LoraFrameCodec.encodeBest(strangers, 1, MeshcoreProto.CHANNEL_DATA_CAP, transcode = true)).parts.single()
            val bobs =
                requireNotNull(
                    LoraFrameCodec.encodeBest(postBy(BOB, wall(), bytes = 20), 1, MeshcoreProto.CHANNEL_DATA_CAP, transcode = true),
                ).parts
                    .single()
            hear(n, dave, bobs)
            assertEquals("both reached the router", 1, n.decodedBy(DAVE))
            val reachable =
                n.transport.reachable.value
                    .map { it.nodeId }
            assertFalse("a stranger's id is never made LoRa-reachable", DAVE in reachable)
            assertTrue("a verified author's is", BOB in reachable)
        }

    @Test
    fun aFreshFragIdFlushPastTheOldCapacityStillLetsTheHonestPostDeliver() =
        runTest {
            val n = node()
            val (part0, part1) = packetsOf(postBy(BOB, wall()))
            hear(n, part0)
            // Every fresh id one radio can air in the window at the shortest fragment's airtime, and then some of the old cap.
            val shortest = n.airtime.timeOnAirMs(LoraFragCandidates.SHORTEST_FRAGMENT_BYTES)
            val flood = (LoraMeshTransport.FRAG_TIMEOUT_MS / shortest).toInt() - 1
            assertTrue(flood > LoraMeshTransport.FRAG_CAP)
            for (i in 0 until flood) n.board.inbound.tryEmit(channelPacket(boundTail(1_000 + i, byteArrayOf(9))))
            runCurrent()
            hear(n, part1)
            assertEquals(0L, n.drops(FastPathDrop.FRAG_OVERFLOW))
            assertEquals("the honest post still delivers", 1, n.decodedBy(BOB))
        }

    @Test
    fun twoAuthorsInterleavedUnderACollidingFragIdBothDeliver() =
        runTest {
            val n = node()
            val (bob0, bob1) = packetsOf(postBy(BOB, wall()), fragId = 7)
            val (carol0, carol1) = packetsOf(postBy(CAROL, wall(), seed = 2), fragId = 7)
            hear(n, bob0, carol0, bob1, carol1)
            assertEquals(1, n.decodedBy(BOB))
            assertEquals(1, n.decodedBy(CAROL))
        }

    @Test
    fun aStrangersPostNeverResolves() =
        runTest {
            val n = node()
            // Dave signs "honestly" — but nobody here holds his key, so his prefix resolves to no one.
            hear(n, *packetsOf(postBy(DAVE, wall())).toTypedArray())
            hear(n, packetsOf(postBy(DAVE, wall(), bytes = 20)).single())
            assertEquals(0, n.decodedBy(DAVE))
            assertEquals(1L, n.drops(FastPathDrop.FRAG_UNVERIFIED))
            assertEquals(1L, n.drops(FastPathDrop.ELIDED_UNVERIFIED))
        }

    @Test
    fun aPinnedAuthorWhoIsNoContactResolvesOnTheMiss() =
        runTest {
            val n = node()
            hear(n, *packetsOf(postBy(ERIN, wall())).toTypedArray())
            assertEquals("past the contacts, every pinned peer", 1, n.decodedBy(ERIN))
        }

    @Test
    fun aReplayedStaleSignedTwoPartPostIsNoHearing() =
        runTest {
            val n = node()
            n.transport.offer(BOB, "dm-1", WALL, byteArrayOf(1, 2, 3), DirectKind.MESSAGE)
            runCurrent()
            n.board.outcome.tryEmit(PacketOutcome.Unreachable(1u, flown = 3))
            runCurrent()
            assertEquals(1, n.transport.status.value.direct.unreachable)
            val stale = wall() - LoraFramePolicy.FRESH_MS - 60_000
            hear(n, *packetsOf(postBy(BOB, stale)).toTypedArray())
            assertEquals("the post reached the router", 1, n.decodedBy(BOB))
            assertFalse(n.logs.any { it == "lora direct heard $BOB" })
            assertEquals(1, n.transport.status.value.direct.unreachable)
            assertEquals(0, n.transport.status.value.boardsHeard)
            // A fresh one at zero hops is: the same path, so the stale one failed on its age alone.
            hear(n, *packetsOf(postBy(BOB, wall(), seed = 3), fragId = 8).toTypedArray())
            assertTrue(n.logs.any { it == "lora direct heard $BOB" })
        }

    @Test
    fun anUnboundFragmentIsRefusedAtTheDispatch() =
        runTest {
            val n = node()
            // A plain 0x04 two-part post — the form a forger can crowd — is read on no MeshCore plane.
            val wire = postBy(BOB, wall())
            val plain = requireNotNull(LoraFrameCodec.encodeBest(wire, 9, MeshcoreProto.CHANNEL_DATA_CAP, transcode = true, elide = true))
            assertEquals(2, plain.parts.size)
            hear(n, *plain.parts.toTypedArray())
            assertEquals(0, n.decodedBy(BOB))
            assertTrue(n.logs.none { it.startsWith("lora rx ") })
        }

    private companion object {
        const val KNIT_SLOT = 1
        const val HONEST: Byte = 7
        const val WALL = 1_800_000_000_000L
        const val BOARD_ADDRESS = "AA:1"

        /** A post that needs both packets: 150 B of noise rides two elided packets (one holds about 57 B). */
        const val LONG_POST = 150

        private fun nodeId(seed: Int): String = NodeId.fromBytes(ByteArray(NodeId.BYTES) { (seed * 31 + it).toByte() })

        val ALICE = nodeId(1)
        val BOB = nodeId(2)
        val CAROL = nodeId(3)
        val DAVE = nodeId(4)
        val ERIN = nodeId(5)
        val ZED = nodeId(6)

        /** The authors whose key this phone holds: alice, bob and carol are contacts, erin only pinned; dave is a stranger. */
        val PINNED = listOf(BOB, CAROL, ERIN, ALICE)

        val BOB_KEY: ByteArray = FakeMeshcoreAir.keyOf("bob")
    }
}
