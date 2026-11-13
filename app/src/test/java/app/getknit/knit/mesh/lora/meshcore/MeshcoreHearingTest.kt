package app.getknit.knit.mesh.lora.meshcore

import app.getknit.knit.mesh.MeshMetrics
import app.getknit.knit.mesh.TransportHealth
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
import app.getknit.knit.mesh.lora.LoraAuthorCheck
import app.getknit.knit.mesh.lora.LoraBoardHolder
import app.getknit.knit.mesh.lora.LoraConfig
import app.getknit.knit.mesh.lora.LoraDirectCrypto
import app.getknit.knit.mesh.lora.LoraDirectOpen
import app.getknit.knit.mesh.lora.LoraDirectSource
import app.getknit.knit.mesh.lora.LoraFrameCodec
import app.getknit.knit.mesh.lora.LoraFramePolicy
import app.getknit.knit.mesh.lora.LoraMeshTransport
import app.getknit.knit.mesh.lora.OpenedDirect
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hearing on a MeshCore plane (ADR 2026-11.gsqu §5 and its Stage 5a-2 amendment; lora-reviewer finding 4, the security
 * pass's B6 and replay items): the plane core over [MeshcoreTraits] and [MeshcoreHooks], a fake board link, and a board
 * holder whose DM went unanswered. A hearing comes only from a verified, fresh Knit post its author's radio aired at zero
 * hops — never from a sender prefix, a non-Knit DM, a replayed post or a flood that took hops — and its radio is counted by
 * author.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MeshcoreHearingTest {
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
                    ),
            )
        override val state = MutableStateFlow<BoardState>(BoardState.Down(TransportHealth.Unavailable, MeshcoreLinkState.Idle))
        val inbound = MutableSharedFlow<BoardPacket>(extraBufferCapacity = 64)
        override val packets = inbound
        val outcome = MutableSharedFlow<PacketOutcome>(extraBufferCapacity = 64)
        override val outcomes = outcome
        override val headroom = MutableSharedFlow<Int>()
        override val headroomNow: Int? = null
        override val battery = MutableStateFlow<BoardBattery?>(null)
        override val boardAir = MutableStateFlow<BoardAir?>(null)
        val sent = mutableListOf<SendPurpose>()
        private var nextId = 1u

        override suspend fun send(
            payload: ByteArray,
            purpose: SendPurpose,
        ): BoardSend {
            sent += purpose
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

        override suspend fun restore(record: MeshcoreSetupRecord): MeshcoreRestoreResult = MeshcoreRestoreResult.NotReady
    }

    /** Seals readably; opens a DM from bob whose second byte is [OPENS], stamped at its next four bytes' seconds. */
    private object Seal : LoraDirectCrypto {
        override fun seal(
            peerId: String,
            frameId: String,
            sentAtMs: Long,
            plaintext: ByteArray,
        ): ByteArray = byteArrayOf(LORA_DIRECT_TAG) + ByteArray(60) + plaintext

        override fun open(packet: ByteArray): LoraDirectOpen {
            if (packet.size < 6 || packet[1] != OPENS) return LoraDirectOpen.Stranger
            val secs =
                java.nio.ByteBuffer
                    .wrap(packet, 2, 4)
                    .int
                    .toLong()
            return LoraDirectOpen.Opened(OpenedDirect(BOB, "in-$secs", WALL + secs * 1_000, byteArrayOf(1)))
        }
    }

    /** A compact DM from bob's board, sent [ageMs] before now, at [pathLen]. */
    private fun TestScope.dmFromBob(
        ageMs: Long,
        pathLen: Int,
    ): BoardPacket {
        val secs = ((testScheduler.currentTime - ageMs) / 1_000).toInt()
        val payload =
            byteArrayOf(LORA_DIRECT_TAG, OPENS) +
                java.nio.ByteBuffer
                    .allocate(4)
                    .putInt(secs)
                    .array() + ByteArray(60)
        return BoardPacket(
            from = BOB_BOARD,
            toUs = true,
            channel = MeshcoreSession.DM_CHANNEL,
            payload = payload,
            rxSnr = 6f,
            rxRssi = null,
            hopsAway = null,
            rxTimeMs = null,
            kind =
                MeshcorePacket(
                    MeshcorePacket.Type.CONTACT_TEXT,
                    null,
                    pathLen,
                    MeshcoreProto.TXT_TYPE_PLAIN,
                    null,
                    null,
                    true,
                    true,
                    null,
                ),
        )
    }

    private object Source : LoraDirectSource {
        override suspend fun unackedTo(
            peerId: String,
            limit: Int,
            untakenOnly: Boolean,
        ): List<DirectDm> = if (peerId == BOB) listOf(DirectDm("dm-1", WALL, byteArrayOf(1, 2, 3))) else emptyList()

        override suspend fun isUnacked(frameId: String): Boolean = frameId == "dm-1"
    }

    private class Node(
        val transport: LoraMeshTransport,
        val board: FakeBoard,
        val hooks: () -> MeshcoreHooks,
        val logs: MutableList<String>,
    )

    private fun TestScope.node(): Node {
        val board = FakeBoard()
        val ops = Ops()
        val logs = mutableListOf<String>()
        var made: MeshcoreHooks? = null
        val transport =
            LoraMeshTransport(
                selfId = { "alice" },
                plane =
                    BoardPlane(MeshcoreTraits, board) { core ->
                        MeshcoreHooks(ops, core, backgroundScope, { testScheduler.currentTime }, { logs += it }).also { made = it }
                    },
                config = MutableStateFlow(LoraConfig("AA:1", 0, dms = true)),
                crypto = Seal,
                source = Source,
                boardMap = flowOf(mapOf(BOB to LoraBoardHolder(BOB_BOARD, direct = true))),
                scope = backgroundScope,
                metrics = MeshMetrics(),
                clock = { testScheduler.currentTime },
                wallClock = { WALL + testScheduler.currentTime },
                processStartWall = WALL,
                log = { logs += it },
                authorCheck = LoraAuthorCheck { wire, _ -> wire.sig[0] == HONEST },
            )
        // The session's observer runs before Ready: the ledger at the board's 10 % band.
        requireNotNull(ops.observer).beforeReady(board.ready)
        transport.start()
        runCurrent()
        return Node(transport, board, { requireNotNull(made) }, logs)
    }

    /** Bob's board did not answer our DM: his mark is unreachable, as a lost board leaves it. */
    private fun TestScope.markBobUnreachable(n: Node) {
        n.transport.offer(BOB, "dm-1", WALL, byteArrayOf(1, 2, 3), DirectKind.MESSAGE)
        runCurrent()
        assertTrue("the DM went to bob's board", n.board.sent.any { it is SendPurpose.DirectDm })
        n.board.outcome.tryEmit(PacketOutcome.Unreachable(1u, flown = 3))
        runCurrent()
        assertEquals(1, n.transport.status.value.direct.unreachable)
    }

    private fun post(
        sentAt: Long,
        sig0: Byte = HONEST,
    ): ByteArray {
        val env =
            RelayEnvelope(
                type = FrameType.CHAT,
                id = "post-$sentAt-$sig0",
                senderId = BOB,
                sentAt = sentAt,
                payload = WireCodec.encodePayload(ChatContent(body = "north gate")),
            )
        val sig = ByteArray(64).also { it[0] = sig0 }
        val wire = WireEnvelope(relay = true, sig = sig, signed = WireCodec.encodeEnvelope(env))
        return requireNotNull(
            LoraFrameCodec.encodeBest(wire, 1, MeshcoreProto.CHANNEL_DATA_CAP, transcode = true, cost = null),
        ).parts.single()
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
        packet: BoardPacket,
    ) {
        n.board.inbound.tryEmit(packet)
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
    }

    private fun Node.heard(): Boolean = logs.any { it == "lora direct heard $BOB" }

    /** How many of bob's posts reached the router: the proof a negative case's packet got past the board's dispatch. */
    private fun Node.postsDecoded(): Int = logs.count { it.startsWith("lora rx ") && it.contains("id=post-") }

    /** How many compact DMs opened: the same proof for a DM. */
    private fun Node.dmsOpened(): Int = logs.count { it.startsWith("lora rx direct ") }

    @Test
    fun aFreshZeroHopVerifiedPostFromTheHoldersAuthorLiftsTheMarkAndCountsItsRadio() =
        runTest {
            val n = node()
            markBobUnreachable(n)
            hear(n, channelPacket(post(sentAt = WALL + testScheduler.currentTime)))
            assertEquals("the post reached the router", 1, n.postsDecoded())
            assertTrue(n.heard())
            assertEquals(0, n.transport.status.value.direct.unreachable)
            assertEquals("a radio, counted by author", 1, n.transport.status.value.boardsHeard)
        }

    @Test
    fun aReplayedStaleSignedPostAtZeroHopsIsNoHearing() =
        runTest {
            val n = node()
            markBobUnreachable(n)
            val stale = WALL + testScheduler.currentTime - LoraFramePolicy.FRESH_MS - 60_000
            hear(n, channelPacket(post(sentAt = stale)))
            assertEquals("the post reached the router", 1, n.postsDecoded())
            assertFalse(n.heard())
            assertEquals(1, n.transport.status.value.direct.unreachable)
            assertEquals(0, n.transport.status.value.boardsHeard)
        }

    @Test
    fun aMultiHopFloodIsNoHearing() =
        runTest {
            val n = node()
            markBobUnreachable(n)
            hear(n, channelPacket(post(sentAt = WALL + testScheduler.currentTime), pathLen = 2))
            assertEquals("the post reached the router", 1, n.postsDecoded())
            assertFalse(n.heard())
            assertEquals(1, n.transport.status.value.direct.unreachable)
            assertEquals(0, n.transport.status.value.boardsHeard)
        }

    @Test
    fun anUnverifiedPostOrAReplayOfTheOfflineQueueIsNoHearing() =
        runTest {
            val n = node()
            markBobUnreachable(n)
            // Anyone on the public Knit key can write bob's id on a post; only his pinned key's signature counts.
            hear(n, channelPacket(post(sentAt = WALL + testScheduler.currentTime, sig0 = 9)))
            hear(n, channelPacket(post(sentAt = WALL + testScheduler.currentTime + 1), live = false))
            assertEquals("both posts reached the router", 2, n.postsDecoded())
            assertFalse(n.heard())
            assertEquals(1, n.transport.status.value.direct.unreachable)
            assertEquals(0, n.transport.status.value.boardsHeard)
        }

    @Test
    fun aNonKnitDmWhosePrefixMatchesAnUnreachableContactLiftsNothing() =
        runTest {
            val n = node()
            markBobUnreachable(n)
            val text = "hello from bob's other app".encodeToByteArray()
            hear(
                n,
                BoardPacket(
                    from = BOB_BOARD,
                    toUs = true,
                    channel = MeshcoreSession.DM_CHANNEL,
                    payload = text,
                    rxSnr = 6f,
                    rxRssi = null,
                    hopsAway = null,
                    rxTimeMs = null,
                    kind =
                        MeshcorePacket(
                            MeshcorePacket.Type.CONTACT_TEXT,
                            null,
                            MeshcoreProto.PATH_UNKNOWN,
                            MeshcoreProto.TXT_TYPE_PLAIN,
                            null,
                            null,
                            live = true,
                            cobs = false,
                            text = text,
                        ),
                ),
            )
            assertFalse(n.heard())
            assertEquals(1, n.transport.status.value.direct.unreachable)
            assertEquals(0, n.transport.status.value.boardsHeard)
            assertEquals(
                1L,
                n
                    .hooks()
                    .counters.foreignDms
                    .get(),
            )
            assertFalse("never logged", n.logs.any { it.contains("other app") })
        }

    @Test
    fun aFreshOpenedDmAtZeroHopsHearsItsBoard() =
        runTest {
            val n = node()
            markBobUnreachable(n)
            hear(n, dmFromBob(ageMs = 0, pathLen = MeshcoreProto.PATH_UNKNOWN))
            assertEquals("the DM opened", 1, n.dmsOpened())
            assertTrue(n.heard())
            assertEquals(0, n.transport.status.value.direct.unreachable)
            assertEquals(1, n.transport.status.value.boardsHeard)
        }

    @Test
    fun aStaleOpenedDmReairedDirectIsNoHearing() =
        runTest {
            val n = node()
            markBobUnreachable(n)
            // The seal opens for a day; a DM re-aired direct once MeshCore's dedup forgot it is no hearing (security N4).
            hear(n, dmFromBob(ageMs = LoraFramePolicy.FRESH_MS + 60_000, pathLen = MeshcoreProto.PATH_UNKNOWN))
            assertEquals("the DM opened", 1, n.dmsOpened())
            assertFalse(n.heard())
            assertEquals(1, n.transport.status.value.direct.unreachable)
            assertEquals(0, n.transport.status.value.boardsHeard)
        }

    @Test
    fun anOpenedFloodWithHopsIsNoHearing() =
        runTest {
            val n = node()
            markBobUnreachable(n)
            hear(n, dmFromBob(ageMs = 0, pathLen = 2))
            assertEquals("the DM opened", 1, n.dmsOpened())
            assertFalse(n.heard())
            assertEquals(1, n.transport.status.value.direct.unreachable)
            assertEquals(0, n.transport.status.value.boardsHeard)
        }

    private companion object {
        const val OPENS: Byte = 0x55
        const val KNIT_SLOT = 1
        const val BOB = "bob"
        const val HONEST: Byte = 7
        const val WALL = 1_800_000_000_000L
        val BOB_BOARD = BoardAddr.MeshCore(ByteArray(6) { 0x42 })
    }
}
