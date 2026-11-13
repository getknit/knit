package app.getknit.knit.mesh.lora.meshcore

import app.getknit.knit.mesh.TransportHealth
import app.getknit.knit.mesh.link.FastFrameCodec
import app.getknit.knit.mesh.lora.AirPurpose
import app.getknit.knit.mesh.lora.BoardAddr
import app.getknit.knit.mesh.lora.BoardPacket
import app.getknit.knit.mesh.lora.BoardState
import app.getknit.knit.mesh.lora.Ceiling
import app.getknit.knit.mesh.lora.Destination
import app.getknit.knit.mesh.lora.DirectKind
import app.getknit.knit.mesh.lora.DirectSend
import app.getknit.knit.mesh.lora.FrameClass
import app.getknit.knit.mesh.lora.KeyTeach
import app.getknit.knit.mesh.lora.LORA_DIRECT_OVERHEAD
import app.getknit.knit.mesh.lora.LORA_DIRECT_TAG
import app.getknit.knit.mesh.lora.LinkState
import app.getknit.knit.mesh.lora.LoraAirtime
import app.getknit.knit.mesh.lora.LoraBand
import app.getknit.knit.mesh.lora.LoraBoardHolder
import app.getknit.knit.mesh.lora.LoraConfig
import app.getknit.knit.mesh.lora.LoraMeshTransport
import app.getknit.knit.mesh.lora.LoraPlaneSnapshot
import app.getknit.knit.mesh.lora.LoraStatus
import app.getknit.knit.mesh.lora.OutboundFrame
import app.getknit.knit.mesh.lora.PacketRoute
import app.getknit.knit.mesh.lora.PlaneCore
import app.getknit.knit.mesh.lora.ProvisionMode
import app.getknit.knit.mesh.lora.ProvisionResult
import app.getknit.knit.mesh.lora.RadioParams
import app.getknit.knit.mesh.lora.SlotCheck
import app.getknit.knit.mesh.lora.WallShift
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * [MeshcoreHooks] (ADR 2026-11.gsqu, Stage 5a-2): the MeshCore kind's half of the plane core through its own seam — the
 * dispatch that reads the slot and the data type before a byte is decoded and discards and counts what is not Knit's,
 * our board's answers booked from one place, the slot guard, the ledger's radio before Ready, the band's law and its
 * persisted ring, the setup through the session, and that no line it writes names a key, a prefix or a text.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MeshcoreHooksTest {
    private class Ops : MeshcoreBoardOps {
        override val air = MutableSharedFlow<MeshcoreAir>(extraBufferCapacity = 8)
        override val counters = MeshcoreCounters()
        override var observer: MeshcoreSessionObserver? = null
        var reading: MeshcoreSlotReading? = MeshcoreSlotReading(KNIT_SLOT, knit = true, atMs = 0)
        var setupResult: MeshcoreSetupResult = MeshcoreSetupResult.NotReady
        val restored = mutableListOf<MeshcoreSetupRecord>()

        override fun slotReading(): MeshcoreSlotReading? = reading

        override suspend fun setup(): MeshcoreSetupResult = setupResult

        override suspend fun restore(record: MeshcoreSetupRecord): MeshcoreRestoreResult {
            restored += record
            return MeshcoreRestoreResult.Done(slotCleared = true, leftAsFound = emptyList(), failed = emptyList())
        }
    }

    private class Core : PlaneCore {
        override val airtime = LoraAirtime(traits = MeshcoreTraits)
        override val config: LoraConfig? = LoraConfig("AA:1", 0)
        override val maxPayload = MeshcoreProto.CHANNEL_DATA_CAP
        override val restored = true
        val holds = mutableListOf<Long?>()
        var moved = 0

        override fun enqueue(
            parts: List<ByteArray>,
            label: String,
            ceiling: Ceiling,
            destination: Destination,
            to: BoardAddr?,
        ): Boolean = error("the MeshCore hooks queue nothing of their own")

        override fun limitersMoved() {
            moved++
        }

        override fun onBoardKeyHeard(from: BoardAddr) = error("a MeshCore prefix never feeds a key hearing")

        override fun holdForLaw(untilMs: Long?) {
            holds += untilMs
        }
    }

    private val now = 10_000L
    private val ops = Ops()
    private val core = Core()
    private val logs = mutableListOf<String>()
    private val keys = mutableListOf<Pair<ByteArray, String?>>()
    private val scope = TestScope(UnconfinedTestDispatcher())
    private val hooks = hooks(ops, core)

    private fun hooks(
        ops: Ops,
        core: Core,
    ) = MeshcoreHooks(ops, core, scope, clock = { now }, log = { logs += it }, wallClock = { WALL_NOW }, onBoardKey = { k, d ->
        keys +=
            k to d
    })

    private val boardKey = ByteArray(32) { (0xA0 + it).toByte() }

    private fun detail(
        multiAcks: Int = 1,
        freqKhz: Long? = 869_525,
        version: String? = "v1.18.0",
        key: ByteArray = boardKey,
    ) = MeshcoreDetail(
        publicKey = key,
        versionCode = 11,
        version = version,
        defaultScope = false,
        multiAcks = multiAcks,
        clientRepeat = false,
        freqKhz = freqKhz,
        contactCapacity = 100,
        slots = emptyList(),
        namedForKnit = true,
        manualAdd = 1,
        // The session's own address, deliberately not the plane's configured one ("AA:1"): the key goes out under this.
        dialled = "DD:DD",
    )

    private fun ready(
        radio: RadioParams? = RadioParams(8, 62_500, 5),
        detail: MeshcoreDetail = detail(),
    ) = BoardState.Ready(
        own = BoardAddr.MeshCore(boardKey.copyOf(MeshcoreProto.PREFIX_BYTES)),
        payloadCap = MeshcoreProto.CHANNEL_DATA_CAP,
        radio = radio,
        channel = KNIT_SLOT,
        detail = detail,
    )

    private fun packet(
        type: MeshcorePacket.Type,
        slot: Int? = KNIT_SLOT,
        payload: ByteArray = byteArrayOf(FRAME_TAG, 1, 2),
        dataType: Int? = MeshcoreProto.KNIT_DATA_TYPE,
        pathLen: Int = 0,
        live: Boolean = true,
        cobs: Boolean = false,
        txtType: Int? = if (type == MeshcorePacket.Type.CONTACT_TEXT) MeshcoreProto.TXT_TYPE_PLAIN else null,
        text: ByteArray? = null,
    ) = BoardPacket(
        from = if (type == MeshcorePacket.Type.CONTACT_TEXT) BoardAddr.MeshCore(ByteArray(6) { 7 }) else null,
        toUs = type == MeshcorePacket.Type.CONTACT_TEXT,
        channel = slot ?: MeshcoreSession.DM_CHANNEL,
        payload = payload,
        rxSnr = 5f,
        rxRssi = null,
        hopsAway = null,
        rxTimeMs = null,
        kind = MeshcorePacket(type, slot, pathLen, txtType, dataType, null, live, cobs, text),
    )

    private val seal = byteArrayOf(LORA_DIRECT_TAG) + ByteArray(LORA_DIRECT_OVERHEAD + 10) { 1 }

    // --- route ---

    @Test
    fun theSlotAndTheDataTypeAreReadBeforeAByteIsDecoded() {
        val channel = MeshcorePacket.Type.CHANNEL_DATA
        assertEquals(PacketRoute.KNIT, hooks.route(packet(channel), KNIT_SLOT))
        assertEquals(
            PacketRoute.RETIRED,
            hooks.route(packet(channel, payload = byteArrayOf(LoraMeshTransport.RETIRED_CTL_TAG, 1)), KNIT_SLOT),
        )
        // A fragment waits for 5c's reassembler.
        assertEquals(PacketRoute.DROPPED, hooks.route(packet(channel, payload = byteArrayOf(FastFrameCodec.TAG_FRAG, 1, 2)), KNIT_SLOT))
        assertEquals(1L, hooks.counters.fragmentsRefused.get())
        // Everything else on the public Knit key, or anywhere else, is not a Knit frame.
        assertEquals("another data type on the Knit slot", PacketRoute.DROPPED, hooks.route(packet(channel, dataType = 0x1234), KNIT_SLOT))
        assertEquals("another slot", PacketRoute.DROPPED, hooks.route(packet(channel, slot = 2), KNIT_SLOT))
        assertEquals("no Knit slot on the board", PacketRoute.DROPPED, hooks.route(packet(channel), null))
        assertEquals("nothing in it", PacketRoute.DROPPED, hooks.route(packet(channel, payload = ByteArray(0)), KNIT_SLOT))
        assertEquals(4L, hooks.counters.foreignPosts.get())
        // Public is Stage 6's: counted and discarded, data or text.
        assertEquals(PacketRoute.DROPPED, hooks.route(packet(channel, slot = 0), KNIT_SLOT))
        assertEquals(PacketRoute.DROPPED, hooks.route(packet(MeshcorePacket.Type.CHANNEL_TEXT, slot = 0, dataType = null), KNIT_SLOT))
        assertEquals(2L, hooks.counters.publicDiscarded.get())
        assertEquals(
            PacketRoute.DROPPED,
            hooks.route(packet(MeshcorePacket.Type.CHANNEL_TEXT, slot = KNIT_SLOT, dataType = null), KNIT_SLOT),
        )
        assertEquals(5L, hooks.counters.foreignPosts.get())
    }

    @Test
    fun aContactDmIsACompactDmOnlyInASealsShapeAndEverythingElseIsAForeignDmDiscarded() {
        val dm = MeshcorePacket.Type.CONTACT_TEXT
        assertEquals(PacketRoute.DIRECT, hooks.route(packet(dm, slot = null, payload = seal, cobs = true, pathLen = 0xFF), null))
        // COBS decoding is no proof: the user's own text that happened to decode, a short one, a signed one, and one that
        // never decoded (its payload is the raw text) all fall back to a foreign DM — never thrown, never Knit's.
        val rawText = "hello from the old app".encodeToByteArray()
        assertEquals(PacketRoute.DROPPED, hooks.route(packet(dm, slot = null, payload = rawText, cobs = false, text = rawText), null))
        assertEquals(PacketRoute.DROPPED, hooks.route(packet(dm, slot = null, payload = byteArrayOf(9) + seal, cobs = true), null))
        assertEquals(PacketRoute.DROPPED, hooks.route(packet(dm, slot = null, payload = seal.copyOf(20), cobs = true), null))
        assertEquals(
            PacketRoute.DROPPED,
            hooks.route(packet(dm, slot = null, payload = seal, cobs = true, txtType = SIGNED_PLAIN), null),
        )
        assertEquals(4L, hooks.counters.foreignDms.get())
        // A packet the session did not describe is dropped and counted as nothing.
        val bare = BoardPacket(null, false, 1, seal, null, null, null, null, kind = null)
        assertEquals(PacketRoute.DROPPED, hooks.route(bare, KNIT_SLOT))
        assertEquals(4L, hooks.counters.foreignDms.get())
    }

    // --- our board's answers, booked from one place ---

    @Test
    fun everyLiveContactDmKnitsOrNotBooksTheAnswerOurBoardAiredAndAReplayBooksNothing() {
        hooks.beforeReady(ready(detail = detail(multiAcks = 3)))
        val dm = MeshcorePacket.Type.CONTACT_TEXT
        val air = core.airtime

        fun ms(
            payload: Int,
            flooded: Boolean,
        ) = air.airMsOfPhy(MeshcoreTraits.ackPhyBytes(payload, flooded))
        // A direct plain DM: the larger of one flooded 6-B ack and, along a path, one 7-B multi-ack and the 6-B ack —
        // never three multi-acks, whatever multi_acks reads (companion-v1.18.0's sendAckTo).
        val plainAcks = maxOf(ms(6, flooded = true), ms(7, flooded = false) + ms(6, flooded = false))
        hooks.route(packet(dm, slot = null, payload = seal, cobs = true, pathLen = 0xFF), null)
        assertEquals(plainAcks, air.usedMs(now))
        // A foreign one draws the same acks from our board (the security pass's KIND-ack item).
        hooks.route(packet(dm, slot = null, payload = "hi".encodeToByteArray(), pathLen = 0xFF), null)
        assertEquals(2 * plainAcks, air.usedMs(now))
        // A signed-plain one answers under a 4-B code.
        hooks.route(packet(dm, slot = null, payload = "hi".encodeToByteArray(), pathLen = 0xFF, txtType = SIGNED_PLAIN), null)
        val signedAcks = maxOf(ms(4, flooded = true), ms(5, flooded = false) + ms(4, flooded = false))
        assertEquals(2 * plainAcks + signedAcks, air.usedMs(now))
        // A flooded one over two hops: one flooded PATH return at its real size, never the acks.
        hooks.route(packet(dm, slot = null, payload = seal, cobs = true, pathLen = 2), null)
        val path = air.timeOnAirMs(MeshcoreTraits.pathReturnPlaintext(2, MeshcoreProto.TXT_TYPE_PLAIN), air.price(AirPurpose.PATH_RETURN))
        val booked = 2 * plainAcks + signedAcks + path
        assertEquals(booked, air.usedMs(now))
        // One from the offline queue was answered while we were away; a CLI text draws no answer at all.
        hooks.route(packet(dm, slot = null, payload = seal, cobs = true, pathLen = 0xFF, live = false), null)
        hooks.route(packet(dm, slot = null, payload = seal, cobs = true, pathLen = 0xFF, txtType = 1), null)
        assertEquals(booked, air.usedMs(now))
        assertEquals(3L, hooks.counters.acksBooked.get())
        assertEquals(1L, hooks.counters.pathReturnsBooked.get())
        assertEquals(1L, hooks.counters.answersReplayed.get())
        // The core's own ack hook books nothing more once the compact one is opened.
        hooks.bookDirectAck(packet(dm, slot = null, payload = seal, cobs = true, pathLen = 0xFF), BoardAddr.MeshCore(ByteArray(6)), false)
        assertEquals(booked, air.usedMs(now))
    }

    @Test
    fun aDmOurBoardFloodedBooksOnlyTheFloodsSurchargeAndAPathReturnIsNeverBookedTwice() {
        hooks.beforeReady(ready())
        ops.air.tryEmit(MeshcoreAir.DirectFlooded(1u, bytes = 120, atMs = now))
        val direct = core.airtime.airMsOfPhy(MeshcoreTraits.contactTextPhyBytes(120, flooded = false))
        val flooded = core.airtime.airMsOfPhy(MeshcoreTraits.contactTextPhyBytes(120, flooded = true))
        assertTrue("a flood costs more", flooded > direct)
        assertEquals(flooded - direct, core.airtime.usedMs(now))
        ops.air.tryEmit(MeshcoreAir.PathReturn(now))
        assertEquals(flooded - direct, core.airtime.usedMs(now))
        assertEquals(1L, hooks.counters.floodSurcharges.get())
        assertEquals(1L, hooks.counters.pathReturnsSeen.get())
    }

    // --- the slot guard ---

    @Test
    fun aCompactDmIsAlwaysClearAndARoomPostIsRefusedOnlyOnANoKnitReadingOrNone() {
        val ready = ready()
        val room = OutboundFrame(listOf(ByteArray(10)), "room", FrameClass.ROOM, Ceiling.FLOOD)
        val dm =
            OutboundFrame(
                listOf(ByteArray(10)),
                "dm",
                FrameClass.DIRECT,
                direct = DirectSend("bob", BoardAddr.MeshCore(ByteArray(6)), "f", DirectKind.MESSAGE),
            )
        assertEquals(SlotCheck.Clear, hooks.checkSlot(room, ready, KNIT_SLOT))
        // An old reading is the session's to re-read before the write: never a refusal here.
        ops.reading = MeshcoreSlotReading(KNIT_SLOT, knit = true, atMs = -1_000_000)
        assertEquals(SlotCheck.Clear, hooks.checkSlot(room, ready, KNIT_SLOT))
        ops.reading = MeshcoreSlotReading(KNIT_SLOT, knit = false, atMs = 0)
        assertTrue(hooks.checkSlot(room, ready, KNIT_SLOT) is SlotCheck.Refused)
        ops.reading = null
        assertTrue("none taken on this connection", hooks.checkSlot(room, ready, KNIT_SLOT) is SlotCheck.Refused)
        assertEquals("a DM touches no slot", SlotCheck.Clear, hooks.checkSlot(dm, ready, KNIT_SLOT))
        assertFalse(hooks.directSlotRefused(0))
        assertEquals(KNIT_SLOT, hooks.boundSlot(configured = 0, ready = ready))
        assertNull(hooks.boundSlot(configured = 0, ready = null))
    }

    // --- the ledger's radio, and the band's law ---

    @Test
    fun theLedgerReadsTheBoardsRadioAndBandBeforeReadyAndForgetsThePreviousBoardsWithoutOne() {
        hooks.beforeReady(ready(detail = detail(freqKhz = 868_100), radio = RadioParams(8, 125_000, 5)))
        assertTrue(core.airtime.dutyKnown())
        // 1 % × 0.5 of 15 minutes.
        assertEquals(4_500L, core.airtime.allowanceMs())
        // The next board reported no sane radio: the previous board's duty must not stand for it.
        hooks.beforeReady(ready(radio = null, detail = detail(freqKhz = null)))
        assertFalse(core.airtime.dutyKnown())
        assertEquals((15 * 60_000 * LoraBand.STRICTEST_PERCENT / 100 * 0.5).toLong(), core.airtime.allowanceMs())
    }

    @Test
    fun theLawHoldsEveryFrameUntilItsReleaseAndLiftsWithANewBoard() {
        hooks.onIdentified(boardKey)
        hooks.beforeReady(ready(detail = detail(freqKhz = 868_100), radio = RadioParams(8, 125_000, 5)))
        val wall = 1_800_000_000_000L
        // 40 s sent since boot, against the 1 % band's 36 s an hour: every frame is held, to the verdict's release.
        hooks.onRadioStats(wall, uptimeSecs = 100, txAirSecs = 40)
        val until = core.holds.last()
        assertNotNull(until)
        assertEquals(now + 3_600_000L, until)
        assertTrue(logs.any { it.startsWith("lora law hold ") })
        assertEquals(1L, hooks.counters.lawHolds.get())
        assertTrue(hooks.lawState().holding)
        // Another board identifies: the hold was about the old radio.
        hooks.onIdentified(ByteArray(32) { 3 })
        assertNull(core.holds.last())
        assertFalse(hooks.lawState().holding)
    }

    @Test
    fun aQuietBoardOnATenPercentBandIsNeverHeld() {
        hooks.onIdentified(boardKey)
        hooks.beforeReady(ready())
        hooks.onRadioStats(1_800_000_000_000L, uptimeSecs = 100, txAirSecs = 40)
        assertNull(core.holds.lastOrNull())
        assertEquals(0L, hooks.counters.lawHolds.get())
    }

    @Test
    fun theLawsRingIsPersistedUnderADigestOfTheBoardsKeyAndRestoredOnlyForThatBoard() {
        hooks.onIdentified(boardKey)
        val wall = 1_800_000_000_000L
        hooks.onRadioStats(wall, uptimeSecs = 100, txAirSecs = 40)
        hooks.onRadioStats(wall + 61_000, uptimeSecs = 161, txAirSecs = 41)
        assertTrue("the ring asked to be saved", core.moved > 0)
        val saved = hooks.extendSnapshot(LoraPlaneSnapshot(savedAtWall = wall))
        val stamp = saved.meshcoreLaws.single()
        assertEquals(MeshcoreHooks.digest(boardKey), stamp.board)
        assertFalse("never the key", stamp.board.contains(hex(boardKey).take(16)))
        assertEquals(listOf(100L, 161L), stamp.samples.map { it.uptimeSecs })
        val trailing = hooks.lawState().trailingTxSecs
        assertNotNull(trailing)

        // The next process: the ring waits for its board, then comes back.
        val again = hooks(Ops(), Core())
        again.restoreState(saved, WallShift(0, wall))
        assertNull("not before its board identifies", again.lawState().trailingTxSecs)
        assertEquals(
            "a ring not yet applied is kept as it came",
            listOf(stamp),
            again.extendSnapshot(LoraPlaneSnapshot(savedAtWall = wall)).meshcoreLaws,
        )
        again.onIdentified(boardKey)
        assertEquals(trailing, again.lawState().trailingTxSecs)

        // Another board's process: the ring is not this radio's, but it is kept for a rebind back.
        val other = hooks(Ops(), Core())
        other.restoreState(saved, WallShift(0, wall))
        other.onIdentified(ByteArray(32) { 3 })
        assertNull(other.lawState().trailingTxSecs)
        other.onIdentified(boardKey)
        assertEquals("A → B → A keeps A's ring", trailing, other.lawState().trailingTxSecs)
    }

    @Test
    fun aRebindAwayAndBackKeepsTheFirstBoardsRingAndTheRingsAreCapped() {
        val wall = 1_800_000_000_000L
        hooks.onIdentified(boardKey)
        hooks.onRadioStats(wall, uptimeSecs = 100, txAirSecs = 40)
        val trailing = hooks.lawState().trailingTxSecs
        assertNotNull(trailing)
        for (i in 1..MeshcoreHooks.LAW_BOARDS - 1) hooks.onIdentified(ByteArray(32) { i.toByte() })
        hooks.onIdentified(boardKey)
        assertEquals(trailing, hooks.lawState().trailingTxSecs)
        // Each board identified leaves a ring; past the cap the oldest goes.
        for (i in 1..MeshcoreHooks.LAW_BOARDS + 2) {
            hooks.onIdentified(ByteArray(32) { (20 + i).toByte() })
            hooks.onRadioStats(wall + i * 1_000L, uptimeSecs = 10L + i, txAirSecs = 1)
        }
        assertEquals(MeshcoreHooks.LAW_BOARDS, hooks.extendSnapshot(LoraPlaneSnapshot(savedAtWall = wall)).meshcoreLaws.size)
        // A's ring was evicted by the boards since: back on A, nothing is known of its hour.
        hooks.onIdentified(boardKey)
        assertNull(hooks.lawState().trailingTxSecs)
    }

    @Test
    fun aNewBoardsHeldRingTakesOverTheHoldWithNoLiftBetween() {
        // lora-reviewer L1: the old board's lift and the new board's hold are one step, never a lift landing after a hold.
        val wall = 1_800_000_000_000L
        val other = ByteArray(32) { 9 }
        hooks.beforeReady(ready(detail = detail(freqKhz = 868_100), radio = RadioParams(8, 125_000, 5)))
        hooks.onIdentified(other)
        hooks.onRadioStats(wall, uptimeSecs = 100, txAirSecs = 40)
        hooks.onIdentified(boardKey)
        hooks.onRadioStats(wall, uptimeSecs = 100, txAirSecs = 40)
        val before = core.holds.size
        // Back to the first board, whose ring holds too: the hold carries over and no lift is handed in between.
        hooks.onIdentified(other)
        assertTrue("no lift between two holds", core.holds.drop(before).none { it == null })
        assertNotNull(core.holds.last())
        // And a board with nothing known lifts it.
        hooks.onIdentified(ByteArray(32) { 11 })
        assertNull(core.holds.last())
    }

    @Test
    fun aRingRestoredAfterItsBoardIdentifiedHoldsThePacerAtOnce() {
        // The board identified before the plane state loaded (lora-reviewer N9): its restored ring's hold must not wait a poll.
        val wall = 1_800_000_000_000L
        val stamp =
            app.getknit.knit.mesh.lora.MeshcoreLawStamp(
                board = MeshcoreHooks.digest(boardKey),
                samples =
                    listOf(
                        app.getknit.knit.mesh.lora
                            .LawSampleStamp(100, 40, wall - 100_000),
                    ),
            )
        hooks.onIdentified(boardKey)
        hooks.beforeReady(ready(detail = detail(freqKhz = 868_100), radio = RadioParams(8, 125_000, 5)))
        assertNull("nothing known yet", core.holds.lastOrNull())
        hooks.restoreState(LoraPlaneSnapshot(savedAtWall = wall, meshcoreLaws = listOf(stamp)), WallShift(0, wall))
        assertNotNull("held at once", core.holds.last())
    }

    @Test
    fun aSameBoardRestoreWithOnlyALivePreBootTermKeepsTheHoldAndABoardChangeStillLifts() {
        // lora-reviewer's follow-up: a restore that leaves the law no ring of this boot must not read as "nothing known"
        // while an earlier boot's hour is still spent.
        hooks.onIdentified(boardKey)
        hooks.beforeReady(ready(detail = detail(freqKhz = 868_100), radio = RadioParams(8, 125_000, 5)))
        hooks.onRadioStats(WALL_NOW, uptimeSecs = 100, txAirSecs = 40)
        assertNotNull(core.holds.last())
        val before = core.holds.size
        val preBootOnly =
            app.getknit.knit.mesh.lora.MeshcoreLawStamp(
                board = MeshcoreHooks.digest(boardKey),
                samples = emptyList(),
                preBootTxSecs = 100,
                preBootUntilWall = WALL_NOW + 30 * 60_000L,
            )
        hooks.restoreState(LoraPlaneSnapshot(savedAtWall = WALL_NOW, meshcoreLaws = listOf(preBootOnly)), WallShift(0, WALL_NOW))
        assertTrue("no lift handed to the pacer", core.holds.drop(before).none { it == null })
        assertTrue(hooks.lawState().holding)
        assertEquals("held to the pre-boot term's deadline", 30 * 60L, hooks.lawState().releaseInSecs)
        // A board change with no ring still lifts.
        hooks.onIdentified(ByteArray(32) { 13 })
        assertNull(core.holds.last())
    }

    @Test
    fun aSampleThatMovesNothingWakesNothing() {
        hooks.onIdentified(boardKey)
        hooks.beforeReady(ready(detail = detail(freqKhz = 868_100), radio = RadioParams(8, 125_000, 5)))
        val wall = 1_800_000_000_000L
        hooks.onRadioStats(wall, uptimeSecs = 100, txAirSecs = 40)
        val told = core.holds.size
        // A second later, nothing new sent: the hold's end is where it was.
        hooks.onRadioStats(wall + 1_000, uptimeSecs = 101, txAirSecs = 40)
        hooks.onRadioStats(wall + 2_000, uptimeSecs = 102, txAirSecs = 40)
        assertEquals("the pacer is told once", told, core.holds.size)
    }

    // --- setup, teaching, the face ---

    @Test
    fun setupRunsThroughTheSessionAndARestoreNeedsTheRecordItLeft() =
        runTest {
            val record = MeshcoreSetupRecord(KNIT_SLOT, slotWritten = true, priorName = null, priorManualAdd = 0, priorAutoadd = null)
            assertTrue(hooks.provision(ProvisionMode.Restore, null) is ProvisionResult.Failed)
            ops.setupResult = MeshcoreSetupResult.Done(KNIT_SLOT, alreadyPresent = false, record = record)
            assertEquals(ProvisionResult.Provisioned(KNIT_SLOT, false), hooks.provision(ProvisionMode.Setup, null))
            assertEquals(ProvisionResult.Restored, hooks.provision(ProvisionMode.Restore, null))
            assertEquals(listOf(record), ops.restored)
            assertTrue(hooks.provision(ProvisionMode.SetupDedicated, null) is ProvisionResult.Failed)
            assertFalse(hooks.provisioning)
        }

    @Test
    fun teachingIsRefusedUntilStage5b() {
        val holder = LoraBoardHolder(BoardAddr.MeshCore(ByteArray(6) { 7 }), direct = true)
        assertEquals(KeyTeach.Refused, hooks.teachKey("bob", holder, BoardAddr.MeshCore(ByteArray(6)), 0))
        assertEquals(1L, hooks.counters.teachRefused.get())
    }

    @Test
    fun theStatusIsALinkStateWithNoMeshtasticIdentityAndACleanedVersion() {
        val status = hooks.statusOf(ready(detail = detail(version = "v1.18.0‮\u0007\nbuild")), LoraStatus())
        val state = status.state as LinkState.Ready
        assertEquals("v1.18.0 build", state.board.firmwareVersion)
        assertEquals(0u, state.board.myNodeNum)
        assertNull(status.boardNodeNum)
        val down = hooks.statusOf(BoardState.Down(TransportHealth.Degraded, MeshcoreLinkState.Connecting), LoraStatus())
        assertEquals(LinkState.Connecting, down.state)
    }

    @Test
    fun theReadyLineSaysTheInboxIsKnitsAndTheKeyGoesOutUnderTheDialledAddress() {
        hooks.beforeReady(ready())
        hooks.onReady(ready())
        assertTrue(logs.any { it.startsWith("lora ready kind=meshcore ") && it.contains("inbox=knit-only") })
        assertEquals("the address the session dialled, never the plane's configured one", "DD:DD", keys.single().second)
        assertTrue(keys.single().first.contentEquals(boardKey))
    }

    /**
     * Every line the hooks log, their law state and their counters. The MeshCore dump (`DebugBridgeReceiver.handleMeshcore`,
     * debug-only, `org.json` under Android) is checked by reading only: it prints these counters, the law state, the plane's
     * status counts and the board's version code, slot, radio, `multi_acks` and scope — no field that holds a key or a text.
     */
    @Test
    fun noLineTheHooksWriteNamesAKeyAPrefixOrAText() {
        val text = "meet me at the north gate"
        hooks.onIdentified(boardKey)
        hooks.beforeReady(ready(detail = detail(freqKhz = 868_100), radio = RadioParams(8, 125_000, 5)))
        hooks.onReady(ready())
        hooks.onRadioStats(1_800_000_000_000L, 100, 40)
        hooks.route(packet(MeshcorePacket.Type.CONTACT_TEXT, slot = null, payload = text.encodeToByteArray(), pathLen = 0xFF), null)
        hooks.route(packet(MeshcorePacket.Type.CHANNEL_TEXT, slot = 2, payload = text.encodeToByteArray(), dataType = null), KNIT_SLOT)
        hooks.teachKey("bob", LoraBoardHolder(BoardAddr.MeshCore(ByteArray(6) { 7 }), direct = true), BoardAddr.MeshCore(ByteArray(6)), 0)
        val everything = logs.joinToString("\n") + hooks.lawState().toString() + hooks.counters.toString()
        assertTrue("the counters say their counts", hooks.counters.toString().contains("foreignDms=1"))
        val prefix = ByteArray(6) { 7 }
        val base64 = java.util.Base64.getEncoder()
        assertFalse(everything.contains(hex(boardKey).take(12)))
        assertFalse(everything.contains(hex(prefix)))
        assertFalse(everything.contains(base64.encodeToString(boardKey).take(12)))
        assertFalse(everything.contains(base64.encodeToString(boardKey.copyOf(6)).trimEnd('=')))
        assertFalse(everything.contains(base64.encodeToString(prefix).trimEnd('=')))
        assertFalse(everything.contains(text))
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(Locale.ROOT, it) }

    private companion object {
        const val WALL_NOW = 1_800_000_000_000L
        const val KNIT_SLOT = 1
        const val SIGNED_PLAIN = 2
        const val FRAME_TAG: Byte = 0x05
    }
}
