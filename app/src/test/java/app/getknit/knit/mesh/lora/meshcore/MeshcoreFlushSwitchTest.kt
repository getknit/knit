package app.getknit.knit.mesh.lora.meshcore

import app.getknit.knit.mesh.MeshMetrics
import app.getknit.knit.mesh.MeshcoreBoardHolder
import app.getknit.knit.mesh.MeshcoreRank
import app.getknit.knit.mesh.TransportHealth
import app.getknit.knit.mesh.lora.AckProof
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
import app.getknit.knit.mesh.lora.LORA_DIRECT_OVERHEAD
import app.getknit.knit.mesh.lora.LORA_DIRECT_TAG
import app.getknit.knit.mesh.lora.LoraConfig
import app.getknit.knit.mesh.lora.LoraDirectCrypto
import app.getknit.knit.mesh.lora.LoraDirectOpen
import app.getknit.knit.mesh.lora.LoraDirectSource
import app.getknit.knit.mesh.lora.LoraMeshTransport
import app.getknit.knit.mesh.lora.PacketOutcome
import app.getknit.knit.mesh.lora.ProvisionResult
import app.getknit.knit.mesh.lora.ProvisionSpec
import app.getknit.knit.mesh.lora.QueueInfo
import app.getknit.knit.mesh.lora.RadioParams
import app.getknit.knit.mesh.lora.SendPurpose
import app.getknit.knit.mesh.lora.SendResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * The ✓✓ sibling the lab has no seam for (mesh-lab-reviewer Q4 on the Stage 5c-2 design pass; ADR 2026-11.gsqu's Stage 5c-2a
 * amendment): the board is switched between a flush's chunking and its airing. `MeshManager` cuts a coalesced ✓✓ to the
 * kind's budget at its floor (`budgetFor`, never the bound board's Ready), so whichever MeshCore board airs it, it fits:
 * queued behind a send the old board holds, it airs on the new one whole, to the same peer, never too long.
 *
 * What this pins is that the budget does not depend on the board: on MeshCore `budgetFor` is the kind-constant
 * `directBudget` and every Ready's payload cap is `CHANNEL_DATA_CAP`, so the two boards here differ only in their key and
 * address. It would fail if the queued ✓✓ were dropped or re-addressed at the switch, or if a per-board budget ever cut it
 * past the new board's cap; it cannot show a budget that varies between two real boards, since none does yet.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MeshcoreFlushSwitchTest {
    /** A board link whose first send waits on [gate]: the send in flight while the board is switched. */
    private class GatedBoard : BoardLink {
        fun readyAs(seed: Byte): BoardState.Ready =
            BoardState.Ready(
                own = BoardAddr.MeshCore(ByteArray(6) { seed }),
                payloadCap = MeshcoreProto.CHANNEL_DATA_CAP,
                radio = RadioParams(8, 62_500, 5),
                channel = KNIT_SLOT,
                detail =
                    MeshcoreDetail(
                        publicKey = ByteArray(32) { seed },
                        versionCode = 11,
                        version = "v1.18.0",
                        defaultScope = false,
                        multiAcks = 0,
                        clientRepeat = false,
                        freqKhz = 869_525,
                        contactCapacity = 100,
                        slots = emptyList(),
                        manualAdd = 1,
                        dialled = ADDRESS,
                    ),
            )

        override val state = MutableStateFlow<BoardState>(BoardState.Down(TransportHealth.Unavailable, MeshcoreLinkState.Idle))
        override val packets = MutableSharedFlow<BoardPacket>(extraBufferCapacity = 64)
        override val outcomes = MutableSharedFlow<PacketOutcome>(extraBufferCapacity = 64)
        override val headroom = MutableSharedFlow<Int>()
        override val headroomNow: Int? = null
        override val battery = MutableStateFlow<BoardBattery?>(null)
        override val boardAir = MutableStateFlow<BoardAir?>(null)

        /** What each send was handed, with the board it went to (its own address's first byte). */
        val sent = mutableListOf<Triple<Byte, SendPurpose, ByteArray>>()
        var gate: CompletableDeferred<Unit>? = null
        var nextId = 1u

        /** Runs inside a send, after the board took it and before the send returns: an answer that outruns its send. */
        var beforeReturn: (suspend (UInt) -> Unit)? = null

        /** What a send answers instead of being taken, by its handle; null takes it. */
        var refuse: ((UInt) -> SendResult)? = null

        override suspend fun send(
            payload: ByteArray,
            purpose: SendPurpose,
        ): BoardSend {
            val on = ((state.value as? BoardState.Ready)?.own as? BoardAddr.MeshCore)?.prefix?.get(0) ?: 0
            sent += Triple(on, purpose, payload)
            gate?.let {
                gate = null
                it.await()
            }
            val id = nextId++
            refuse?.let { return BoardSend(it(id), 0) }
            beforeReturn?.invoke(id)
            return BoardSend(SendResult.Queued(id, QueueInfo(10, 16, 0)), 0)
        }

        override suspend fun provision(spec: ProvisionSpec): ProvisionResult = ProvisionResult.Failed("not here")

        override fun start(address: String) {
            state.value = readyAs(1)
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

        override val consent = MutableStateFlow<MeshcoreConsent?>(consentFor(1))

        override fun setKnitContacts(
            ranked: List<ByteArray>,
            withdrawn: Boolean,
        ) = Unit

        var teaches = 0

        override suspend fun teach(key: ByteArray): ContactWrite {
            teaches++
            return ContactWrite.NOT_READY
        }

        override suspend fun removeUntaught(key: ByteArray): ContactWrite = ContactWrite.NOT_READY
    }

    /** Seals by the tag and [LORA_DIRECT_OVERHEAD] − 1 bytes of header: a budget-sized plaintext seals to the cap exactly. */
    private object Seal : LoraDirectCrypto {
        override fun seal(
            peerId: String,
            frameId: String,
            sentAtMs: Long,
            plaintext: ByteArray,
        ): ByteArray = byteArrayOf(LORA_DIRECT_TAG) + ByteArray(LORA_DIRECT_OVERHEAD - 1) + plaintext

        override fun open(packet: ByteArray): LoraDirectOpen = LoraDirectOpen.Stranger
    }

    private fun TestScope.transportOver(
        board: GatedBoard,
        ops: Ops,
        metrics: MeshMetrics,
        logs: MutableList<String>,
        unacked: () -> Boolean = { true },
        config: kotlinx.coroutines.flow.Flow<LoraConfig?> =
            MutableStateFlow(LoraConfig(ADDRESS, MeshcoreHooks.NO_CONFIGURED_SLOT, dms = true)),
        holders: kotlinx.coroutines.flow.Flow<Map<String, app.getknit.knit.mesh.lora.LoraBoardHolder>> = flowOf(bobHeld()),
    ): LoraMeshTransport =
        LoraMeshTransport(
            selfId = { "alice" },
            plane =
                BoardPlane(MeshcoreTraits, board) { core ->
                    MeshcoreHooks(ops, core, backgroundScope, { testScheduler.currentTime }, { line -> logs += line })
                },
            config = config,
            crypto = Seal,
            source =
                object : LoraDirectSource {
                    override suspend fun unackedTo(
                        peerId: String,
                        limit: Int,
                        untakenOnly: Boolean,
                        maxBytes: Int,
                    ): List<DirectDm> = emptyList()

                    override suspend fun isUnacked(frameId: String): Boolean = unacked()
                },
            boardMap = holders,
            scope = backgroundScope,
            metrics = metrics,
            clock = { testScheduler.currentTime },
            wallClock = { WALL + testScheduler.currentTime },
            processStartWall = WALL,
            log = { logs += it },
        )

    @Test
    fun aTickFlushChunkedForOneBoardAirsWholeOnTheNextWhenTheBoardIsSwitchedUnderIt() =
        runTest {
            val board = GatedBoard()
            val ops = Ops()
            val metrics = MeshMetrics()
            val logs = mutableListOf<String>()
            val transport = transportOver(board, ops, metrics, logs)
            requireNotNull(ops.observer).beforeReady(board.readyAs(1))
            transport.start()
            runCurrent()
            // A DM the old board takes and holds; behind it the ✓✓ flush, chunked to the kind's budget at its floor.
            board.gate = CompletableDeferred()
            val held = checkNotNull(board.gate)
            transport.offer(BOB, "dm-1", WALL, byteArrayOf(1, 2, 3), DirectKind.MESSAGE)
            runCurrent()
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals("the DM is in the old board's hands", 1, board.sent.size)
            val budget = transport.budgetFor(BOB).plaintextBytes
            val ticks = ByteArray(budget) { (it % 7 + 1).toByte() }
            transport.offer(BOB, "tick-1", WALL, ticks, DirectKind.RECEIPT)
            runCurrent()
            // The board is switched: the old one goes down, another comes up under the same binding and consents.
            board.state.value = BoardState.Down(TransportHealth.Unavailable, MeshcoreLinkState.Idle)
            runCurrent()
            ops.consent.value = consentFor(2)
            requireNotNull(ops.observer).beforeReady(board.readyAs(2))
            board.state.value = board.readyAs(2)
            runCurrent()
            held.complete(Unit)
            runCurrent()
            // The old board's DM is acked by bob's board: bob is reachable, and what waited behind the flight goes.
            board.outcomes.tryEmit(PacketOutcome.Acked(1u, byDestination = true, proof = AckProof.VALID, flown = 1))
            runCurrent()
            advanceTimeBy(30_000)
            runCurrent()
            val tick = board.sent.filter { it.third.size == LORA_DIRECT_OVERHEAD + budget }
            assertEquals(
                "the ✓✓ aired once: ${board.sent.map { it.first to it.third.size }} ${logs.filter { it.startsWith("lora") }}",
                1,
                tick.size,
            )
            assertEquals("on the new board", 2.toByte(), tick.single().first)
            assertEquals(
                "to bob's board",
                BoardAddr.MeshCore(BOB_KEY.copyOf(6)),
                (tick.single().second as SendPurpose.DirectDm).to,
            )
            assertTrue("within the seal cap", tick.single().third.size <= MeshcoreProto.DIRECT_SEAL_CAP)
            assertEquals("never too long", 0L, metrics.snapshot().lora.loraDirectTooLong)
            assertTrue("nothing evicted at the new Ready", logs.none { it.startsWith("lora ready: evicted") })
        }

    /** Bob as the board map holds him: a direct contact with a MeshCore key, taught. */
    private fun bobHeld(): Map<String, app.getknit.knit.mesh.lora.LoraBoardHolder> =
        MeshcoreContacts.holdersOf(
            mapOf(BOB to MeshcoreBoardHolder(Base64.getEncoder().encodeToString(BOB_KEY), MeshcoreRank.ACCEPTED, 1, false)),
        )

    /**
     * The design pass's finding 4 (ADR 2026-11.gsqu's 2026-11-19 amendment): before the session's ladder floods a DM it asks
     * the core, through the hooks, whether the frame is still wanted — the questions its `RideGate` put at send: a link to
     * bob, a spool cover over him, a block (bob out of the board map), DMs switched off, and for a DM a person wrote its ✓✓
     * since. A ✓✓ of ours asks no ✓✓, and a send the core no longer waits on is wanted.
     */
    @Test
    fun theFloodRungAsksTheCoreTheFramesOwnGatesAgain() =
        runTest {
            val board = GatedBoard()
            val ops = Ops()
            val metrics = MeshMetrics()
            val logs = mutableListOf<String>()
            var unacked = true
            val config = MutableStateFlow<LoraConfig?>(LoraConfig(ADDRESS, MeshcoreHooks.NO_CONFIGURED_SLOT, dms = true))
            val holders = MutableStateFlow(bobHeld())
            val transport = transportOver(board, ops, metrics, logs, { unacked }, config, holders)
            requireNotNull(ops.observer).beforeReady(board.readyAs(1))
            transport.start()
            runCurrent()
            transport.offer(BOB, "dm-1", WALL, byteArrayOf(1, 2, 3), DirectKind.MESSAGE)
            runCurrent()
            advanceTimeBy(1_000)
            runCurrent()
            val gate = requireNotNull(ops.observer)
            assertTrue("waiting, unacked, no link, no cover", gate.floodWanted(1u))
            unacked = false
            assertTrue("its ✓✓ landed", !gate.floodWanted(1u))
            unacked = true
            transport.suppressDataPath(setOf(BOB))
            assertTrue("linked: the link carries it", !gate.floodWanted(1u))
            transport.suppressDataPath(emptySet())
            transport.coveredByInternet(setOf(BOB))
            runCurrent()
            assertTrue("covered by a spool", !gate.floodWanted(1u))
            transport.coveredByInternet(emptySet())
            runCurrent()
            assertTrue(gate.floodWanted(1u))
            assertTrue("a send the core does not wait on", gate.floodWanted(99u))
            // security NB2: blocked or removed, bob leaves the board map, and nothing floods to him.
            holders.value = emptyMap()
            runCurrent()
            assertTrue("bob no longer a direct holder", !gate.floodWanted(1u))
            holders.value = bobHeld()
            runCurrent()
            assertTrue(gate.floodWanted(1u))
            config.value = LoraConfig(ADDRESS, MeshcoreHooks.NO_CONFIGURED_SLOT, dms = false)
            runCurrent()
            assertTrue("DMs switched off", !gate.floodWanted(1u))
            config.value = LoraConfig(ADDRESS, MeshcoreHooks.NO_CONFIGURED_SLOT, dms = true)
            runCurrent()
            // A ✓✓ of ours never asks the ✓✓ read.
            unacked = false
            transport.offer(BOB, "tick-1", WALL, byteArrayOf(4, 5), DirectKind.RECEIPT)
            board.outcomes.tryEmit(acked(1u))
            runCurrent()
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals("the ✓✓ went once bob's board acked the DM", 2, board.sent.size)
            assertTrue(gate.floodWanted(2u))
        }

    /**
     * lora-reviewer's second follow-up (blocking): a DM the session refuses for a spent ladder budget on an UNKNOWN record is a
     * plain refusal. Were it a missing key, the core would teach, find the contact present, and re-offer the frame into the
     * same refusal, in a loop bounded by nothing but the BLE round trips. Here: one send, no teach, no missing-key count, and
     * nothing re-offered short of the re-probe's gap.
     */
    @Test
    fun aDmRefusedForASpentLadderBudgetIsAPlainRefusalNeverATeachLoop() =
        runTest {
            val board = GatedBoard()
            val ops = Ops()
            val metrics = MeshMetrics()
            val logs = mutableListOf<String>()
            val transport = transportOver(board, ops, metrics, logs)
            board.refuse = { id -> SendResult.Rejected(id, MeshcoreRefusal.ROUTE_BUDGET_SPENT) }
            requireNotNull(ops.observer).beforeReady(board.readyAs(1))
            transport.start()
            runCurrent()
            transport.offer(BOB, "dm-1", WALL, byteArrayOf(1, 2, 3), DirectKind.MESSAGE)
            runCurrent()
            advanceTimeBy(60_000) // well inside the re-probe's 15-min base gap
            runCurrent()
            assertEquals("one send, never re-offered: ${logs.filter { it.startsWith("lora") }}", 1, board.sent.size)
            assertEquals("no teach", 0, ops.teaches)
            assertEquals("no missing key", 0L, metrics.snapshot().lora.loraDirectNoKey)
        }

    // --- an answer that outruns its send (ADR 2026-11.gsqu's Stage 5c-2b amendment, the lab's B) ---

    /** A transport Ready on board 1, with bob's board held direct. */
    private fun TestScope.ready(
        board: GatedBoard,
        metrics: MeshMetrics,
        logs: MutableList<String>,
    ): LoraMeshTransport {
        val ops = Ops()
        val transport = transportOver(board, ops, metrics, logs)
        requireNotNull(ops.observer).beforeReady(board.readyAs(1))
        transport.start()
        runCurrent()
        return transport
    }

    private fun acked(id: UInt) = PacketOutcome.Acked(id, byDestination = true, proof = AckProof.VALID, flown = 1)

    @Test
    fun anAckThatArrivesBeforeItsSendReturnsSettlesTheSend() =
        runTest {
            // The lab's B: the session emits the board's ack the moment it comes, and the outcomes collector runs before the
            // pacer has resumed from `board.send` to register the flight. Before, the ack was dropped as unawaited and the
            // DM waited out its deadline, everything parked behind it with it.
            val board = GatedBoard()
            val metrics = MeshMetrics()
            val logs = mutableListOf<String>()
            val transport = ready(board, metrics, logs)
            board.beforeReturn = { id ->
                board.outcomes.emit(acked(id))
                kotlinx.coroutines.delay(1) // the collector runs first
            }
            transport.offer(BOB, "dm-1", WALL, byteArrayOf(1, 2, 3), DirectKind.MESSAGE)
            runCurrent()
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals("the DM was acked: ${logs.filter { it.startsWith("lora") }}", 1L, metrics.snapshot().lora.loraDirectAcked)
            assertTrue(logs.any { it == "lora direct acked dm-1 -> $BOB" })
            assertTrue(logs.any { it.startsWith("lora direct early Acked id=1: settled at its send") })
        }

    @Test
    fun aHeldAnswerPastItsTimeNeverSettlesASendThatReusesItsHandle() =
        runTest {
            val board = GatedBoard()
            val metrics = MeshMetrics()
            val logs = mutableListOf<String>()
            val transport = ready(board, metrics, logs)
            // An ack for handle 1, which no send holds yet: held, then left past its time.
            board.outcomes.emit(acked(1u))
            runCurrent()
            assertTrue(logs.any { it.startsWith("lora direct early Acked id=1: held") })
            advanceTimeBy(app.getknit.knit.mesh.lora.LoraDirectPolicy.EARLY_OUTCOME_TTL_MS + 1_000)
            runCurrent()
            // The board reuses handle 1 for a new DM: the stale record must not settle it.
            transport.offer(BOB, "dm-1", WALL, byteArrayOf(1, 2, 3), DirectKind.MESSAGE)
            runCurrent()
            advanceTimeBy(100)
            runCurrent()
            assertEquals(1, board.sent.size)
            assertEquals("never settled on the old answer", 0L, metrics.snapshot().lora.loraDirectAcked)
            assertTrue(logs.none { it.contains("settled at its send") })
            board.outcomes.emit(acked(1u))
            runCurrent()
            assertEquals("its own answer settles it", 1L, metrics.snapshot().lora.loraDirectAcked)
        }

    @Test
    fun anImplicitAckBehindTheDestinationsNeverReplacesItsHeldAnswer() =
        runTest {
            // The unit-test review's round 2: an implicit ack (a relay or our own board hearing it repeated) is never held,
            // so it cannot take the place of the destination's ack held for the same handle.
            val board = GatedBoard()
            val metrics = MeshMetrics()
            val logs = mutableListOf<String>()
            val transport = ready(board, metrics, logs)
            board.beforeReturn = { id ->
                board.outcomes.emit(acked(id))
                board.outcomes.emit(PacketOutcome.Acked(id, byDestination = false, proof = AckProof.ABSENT, flown = 1))
                kotlinx.coroutines.delay(1) // the collector runs both first
            }
            transport.offer(BOB, "dm-1", WALL, byteArrayOf(1, 2, 3), DirectKind.MESSAGE)
            runCurrent()
            advanceTimeBy(1_000)
            runCurrent()
            val lora = metrics.snapshot().lora
            assertEquals("the destination's ack settled it: ${logs.filter { it.startsWith("lora") }}", 1L, lora.loraDirectAcked)
            assertEquals("the implicit one was never counted at launch", 0L, lora.loraDirectImplicitAck)
        }

    @Test
    fun aConfirmationAfterTheSessionsGiveUpIsALateAck() =
        runTest {
            // lora-reviewer's round-3 follow-up: `MeshcoreSession.onConfirmed` emits `Acked` under the handle of a flight it
            // already ended `Unreachable`. The core now remembers a flight it ends with no answer, so this is a late ack —
            // before, it met neither a flight nor an ended send, and was dropped (held 5 s, then dropped).
            val board = GatedBoard()
            val metrics = MeshMetrics()
            val logs = mutableListOf<String>()
            val transport = ready(board, metrics, logs)
            transport.offer(BOB, "dm-1", WALL, byteArrayOf(1, 2, 3), DirectKind.MESSAGE)
            runCurrent()
            advanceTimeBy(100)
            runCurrent()
            board.outcomes.emit(PacketOutcome.Unreachable(1u, flown = 3))
            runCurrent()
            assertEquals(1, transport.status.value.direct.unreachable)
            board.outcomes.emit(acked(1u))
            runCurrent()
            assertTrue("a late ack: ${logs.filter { it.startsWith("lora") }}", logs.any { it == "lora direct late ack dm-1 -> $BOB" })
            assertTrue("never held as an early one", logs.none { it.startsWith("lora direct early") })
            assertEquals("the mark lifted", 0, transport.status.value.direct.unreachable)
        }

    private companion object {
        const val KNIT_SLOT = 1
        const val WALL = 1_800_000_000_000L
        const val ADDRESS = "AA:1"
        const val BOB = "bob"
        val BOB_KEY: ByteArray = FakeMeshcoreAir.keyOf("bob")

        fun consentFor(seed: Byte): MeshcoreConsent =
            MeshcoreConsent(ADDRESS, MeshcoreHooks.digest(ByteArray(32) { seed }), true, ByteArray(32) { seed })
    }
}
