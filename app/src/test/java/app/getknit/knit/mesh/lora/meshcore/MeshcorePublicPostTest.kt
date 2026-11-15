package app.getknit.knit.mesh.lora.meshcore

import app.getknit.knit.mesh.MeshMetrics
import app.getknit.knit.mesh.TransportHealth
import app.getknit.knit.mesh.lora.AirPurpose
import app.getknit.knit.mesh.lora.BoardAddr
import app.getknit.knit.mesh.lora.BoardAir
import app.getknit.knit.mesh.lora.BoardBattery
import app.getknit.knit.mesh.lora.BoardLink
import app.getknit.knit.mesh.lora.BoardPacket
import app.getknit.knit.mesh.lora.BoardPlane
import app.getknit.knit.mesh.lora.BoardSend
import app.getknit.knit.mesh.lora.BoardState
import app.getknit.knit.mesh.lora.DirectDm
import app.getknit.knit.mesh.lora.LoraAirtime
import app.getknit.knit.mesh.lora.LoraConfig
import app.getknit.knit.mesh.lora.LoraDirectCrypto
import app.getknit.knit.mesh.lora.LoraDirectSource
import app.getknit.knit.mesh.lora.LoraMeshTransport
import app.getknit.knit.mesh.lora.PacketOutcome
import app.getknit.knit.mesh.lora.ProvisionResult
import app.getknit.knit.mesh.lora.ProvisionSpec
import app.getknit.knit.mesh.lora.PublicPostKey
import app.getknit.knit.mesh.lora.PublicPostResult
import app.getknit.knit.mesh.lora.QueueInfo
import app.getknit.knit.mesh.lora.RadioParams
import app.getknit.knit.mesh.lora.SendPurpose
import app.getknit.knit.mesh.lora.SendResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The user's MeshCore Public post through the plane core (ADR 2026-11.gsqu's Stage 6 amendment; lora N3, N4, security N5):
 * queued under the flood ceiling with the sender timestamp fixed at enqueue, written under that stamp on every attempt — a
 * `Busy` board's retry included, however much later — and booked as a flood at the price with the board's 11-B prefix.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MeshcorePublicPostTest {
    private class FakeBoard : BoardLink {
        val ready =
            BoardState.Ready(
                own = BoardAddr.MeshCore(KEY.copyOf(MeshcoreProto.PREFIX_BYTES)),
                payloadCap = MeshcoreProto.CHANNEL_DATA_CAP,
                radio = RADIO,
                channel = KNIT_SLOT,
                detail =
                    MeshcoreDetail(
                        publicKey = KEY,
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
                        dialled = ADDRESS,
                        publicSlot = MeshcorePublicSlot("Public", keyIsPublic = true, empty = false, knit = false),
                    ),
            )
        override val state = MutableStateFlow<BoardState>(BoardState.Down(TransportHealth.Unavailable, MeshcoreLinkState.Idle))
        override val packets = MutableSharedFlow<BoardPacket>(extraBufferCapacity = 8)
        override val outcomes = MutableSharedFlow<PacketOutcome>(extraBufferCapacity = 8)
        override val headroom = MutableSharedFlow<Int>()
        override val headroomNow: Int? = null
        override val battery = MutableStateFlow<BoardBattery?>(null)
        override val boardAir = MutableStateFlow<BoardAir?>(null)

        /** What each write carried; [answers] scripts the board's answers, `Queued` once they run out. */
        val sent = mutableListOf<Pair<ByteArray, SendPurpose>>()
        val answers = ArrayDeque<SendResult>()
        private var nextId = 1u

        override suspend fun send(
            payload: ByteArray,
            purpose: SendPurpose,
        ): BoardSend {
            sent += payload to purpose
            return BoardSend(answers.removeFirstOrNull() ?: SendResult.Queued(nextId++, QueueInfo(10, 16, 0)), 0)
        }

        override suspend fun provision(spec: ProvisionSpec): ProvisionResult = ProvisionResult.Failed("not here")

        override fun start(address: String) {
            state.value = ready
        }

        override fun stop() = Unit
    }

    private class Ops(
        private val board: FakeBoard,
    ) : MeshcoreBoardOps {
        override val air = MutableSharedFlow<MeshcoreAir>()
        override val counters = MeshcoreCounters()
        override var observer: MeshcoreSessionObserver? = null
        override val ready: BoardState.Ready? get() = board.state.value as? BoardState.Ready

        override fun slotReading(): MeshcoreSlotReading = MeshcoreSlotReading(KNIT_SLOT, knit = true, atMs = 0)

        override suspend fun setup(): MeshcoreSetupResult = MeshcoreSetupResult.NotReady

        override suspend fun restore(): MeshcoreRestoreResult = MeshcoreRestoreResult.NotReady

        override suspend fun unbind(address: String): MeshcoreUnbind = MeshcoreUnbind.NOTHING

        override val consent = MutableStateFlow<MeshcoreConsent?>(MeshcoreConsent(ADDRESS, MeshcoreHooks.digest(KEY), true, KEY))

        override fun setKnitContacts(
            ranked: List<ByteArray>,
            withdrawn: Boolean,
        ) = Unit

        override suspend fun teach(key: ByteArray): ContactWrite = ContactWrite.NOT_READY

        override suspend fun removeUntaught(key: ByteArray): ContactWrite = ContactWrite.NOT_READY
    }

    private object Source : LoraDirectSource {
        override suspend fun unackedTo(
            peerId: String,
            limit: Int,
            untakenOnly: Boolean,
            maxBytes: Int,
        ): List<DirectDm> = emptyList()

        override suspend fun isUnacked(frameId: String): Boolean = false
    }

    private class Node(
        val transport: LoraMeshTransport,
        val board: FakeBoard,
        val hooks: () -> MeshcoreHooks,
    )

    private fun TestScope.node(): Node {
        val board = FakeBoard()
        val ops = Ops(board)
        var made: MeshcoreHooks? = null
        val transport =
            LoraMeshTransport(
                selfId = { "alice" },
                plane =
                    BoardPlane(MeshcoreTraits, board) { core ->
                        MeshcoreHooks(
                            ops,
                            core,
                            backgroundScope,
                            { testScheduler.currentTime },
                            {},
                            { WALL + testScheduler.currentTime },
                        ).also { made = it }
                    },
                config = MutableStateFlow(LoraConfig(ADDRESS, MeshcoreHooks.NO_CONFIGURED_SLOT, dms = true)),
                crypto = LoraDirectCrypto.None,
                source = Source,
                boardMap = flowOf(emptyMap()),
                scope = backgroundScope,
                metrics = MeshMetrics(),
                clock = { testScheduler.currentTime },
                wallClock = { WALL + testScheduler.currentTime },
                processStartWall = WALL,
                log = {},
            )
        requireNotNull(ops.observer).beforeReady(board.ready)
        transport.start()
        runCurrent()
        return Node(transport, board, { requireNotNull(made) })
    }

    /** What a [bytes]-byte Public post books, priced off the same board on a ledger of its own. */
    private fun priceOf(bytes: Int): Long {
        val air = LoraAirtime(traits = MeshcoreTraits)
        air.onRadio(RADIO, MeshcoreTraits.dutyPolicy(869_525, RADIO.bandwidthHz))
        return air.timeOnAirMs(bytes, air.price(AirPurpose.PUBLIC_POST))
    }

    @Test
    fun aPostRidesTheQueueUnderItsStampAndIsBookedAsAFloodWithItsPrefix() =
        runTest {
            val n = node()
            val result = n.transport.postPublic("north gate") as PublicPostResult.Queued
            runCurrent()
            val (payload, purpose) = n.board.sent.single()
            assertArrayEquals("the words alone: the board writes the name", "north gate".encodeToByteArray(), payload)
            val stamp = (purpose as SendPurpose.PublicPost).timestampSecs!!
            assertEquals(PublicPostKey(stamp, "${MeshcoreNames.forKey(KEY)}: north gate".encodeToByteArray()), result.key)
            val air = requireNotNull(n.transport.status.value.airtime)
            assertEquals("the 11-B prefix priced in", priceOf(10), air.usedMs)
            assertEquals("a flood, sharing Knit's room posts' share", air.usedMs, air.floodUsedMs)
            assertEquals(
                1L,
                n
                    .hooks()
                    .counters.publicPostsSent
                    .get(),
            )
            assertEquals("the composer's budget on a 10 % band: the whole 149 B", 149, air.publicPostBytes)
        }

    @Test
    fun aBusyBoardsRetryAirsTheSameStampMinutesLater() =
        runTest {
            val n = node()
            n.board.answers += SendResult.Busy
            n.board.answers += SendResult.Busy
            val result = n.transport.postPublic("north gate") as PublicPostResult.Queued
            runCurrent()
            repeat(30) {
                advanceTimeBy(5_000)
                runCurrent()
            }
            val stamps = n.board.sent.map { (it.second as SendPurpose.PublicPost).timestampSecs }
            assertEquals("two owed, then taken: $stamps", 3, stamps.size)
            assertTrue("one stamp, the one fixed at enqueue", stamps.all { it == result.key!!.timestampSecs })
            assertEquals(WALL / 1_000, result.key!!.timestampSecs)
            assertEquals(
                "booked once, when the board took it",
                priceOf(10),
                n.transport.status.value.airtime!!
                    .usedMs,
            )
        }

    /** The 6a review: a post held past `FRESH_MS` (the board away) is never written under its old stamp; its end is reported. */
    @Test
    fun aPostHeldPastItsFreshnessEndsUnairedAndSaysSo() =
        runTest {
            val n = node()
            val ends = mutableListOf<app.getknit.knit.mesh.lora.PublicPostEnded>()
            backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) {
                n.transport.publicPostsEnded.collect { ends += it }
            }
            // Queued the instant the link drops: the pacer parks on the link, the frame waits out the outage.
            val result = n.transport.postPublic("north gate") as PublicPostResult.Queued
            n.board.state.value = BoardState.Down(TransportHealth.Unavailable, MeshcoreLinkState.Idle)
            advanceTimeBy(app.getknit.knit.mesh.lora.LoraFramePolicy.FRESH_MS + 60_000)
            runCurrent()
            n.board.state.value = n.board.ready
            advanceTimeBy(60_000)
            runCurrent()
            assertTrue("nothing written under the old stamp", n.board.sent.isEmpty())
            assertEquals(
                listOf(
                    app.getknit.knit.mesh.lora
                        .PublicPostEnded(result.key!!, app.getknit.knit.mesh.lora.PublicPostEnded.Reason.STALE),
                ),
                ends,
            )
            assertEquals(
                "never booked",
                0L,
                n.transport.status.value.airtime!!
                    .usedMs,
            )
        }

    private companion object {
        const val ADDRESS = "AA:1"
        const val KNIT_SLOT = 1
        const val WALL = 1_790_000_000_000L
        val KEY = ByteArray(32) { (0xB0 + it).toByte() }
        val RADIO = RadioParams(8, 62_500, 5)
    }
}
