package app.getknit.knit.mesh.lora.meshcore

import app.getknit.knit.mesh.MeshcoreBoardHolder
import app.getknit.knit.mesh.MeshcoreRank
import app.getknit.knit.mesh.PublicPostRefusal
import app.getknit.knit.mesh.TransportHealth
import app.getknit.knit.mesh.link.FastFrameCodec
import app.getknit.knit.mesh.lora.AirPurpose
import app.getknit.knit.mesh.lora.BoardAddr
import app.getknit.knit.mesh.lora.BoardBattery
import app.getknit.knit.mesh.lora.BoardPacket
import app.getknit.knit.mesh.lora.BoardSetupState
import app.getknit.knit.mesh.lora.BoardState
import app.getknit.knit.mesh.lora.Ceiling
import app.getknit.knit.mesh.lora.Destination
import app.getknit.knit.mesh.lora.DirectKind
import app.getknit.knit.mesh.lora.DirectSend
import app.getknit.knit.mesh.lora.FragmentOutcome
import app.getknit.knit.mesh.lora.FrameClass
import app.getknit.knit.mesh.lora.KeyTeach
import app.getknit.knit.mesh.lora.KeyWrite
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
import app.getknit.knit.mesh.lora.PublicPostEnded
import app.getknit.knit.mesh.lora.PublicPostKey
import app.getknit.knit.mesh.lora.PublicPostResult
import app.getknit.knit.mesh.lora.RadioParams
import app.getknit.knit.mesh.lora.RideGate
import app.getknit.knit.mesh.lora.SendPurpose
import app.getknit.knit.mesh.lora.SlotCheck
import app.getknit.knit.mesh.lora.WallShift
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
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
@Suppress("LargeClass") // the kind's whole seam over one fake session and core, as MeshcoreSessionTest is the session's
class MeshcoreHooksTest {
    private class Ops : MeshcoreBoardOps {
        override val air = MutableSharedFlow<MeshcoreAir>(extraBufferCapacity = 8)
        override val counters = MeshcoreCounters()
        override var observer: MeshcoreSessionObserver? = null
        var reading: MeshcoreSlotReading? = MeshcoreSlotReading(KNIT_SLOT, knit = true, atMs = 0)
        var setupResult: MeshcoreSetupResult = MeshcoreSetupResult.NotReady

        override fun slotReading(): MeshcoreSlotReading? = reading

        override suspend fun setup(): MeshcoreSetupResult = setupResult

        var restoreResult: MeshcoreRestoreResult =
            MeshcoreRestoreResult.Done(
                slotCleared = true,
                leftAsFound = emptyList(),
                failed = emptyList(),
            )
        var restores = 0

        override suspend fun restore(): MeshcoreRestoreResult {
            restores++
            return restoreResult
        }

        val taught = mutableListOf<List<ByteArray>>()
        val teaches = mutableListOf<ByteArray>()
        val removals = mutableListOf<ByteArray>()
        var teachResult = ContactWrite.WRITTEN

        val withdrawals = mutableListOf<Boolean>()

        override fun setKnitContacts(
            ranked: List<ByteArray>,
            withdrawn: Boolean,
        ) {
            taught += ranked
            withdrawals += withdrawn
        }

        override suspend fun teach(key: ByteArray): ContactWrite {
            teaches += key
            return teachResult
        }

        var pinned = false

        override fun pinnedZeroHop(from: BoardAddr.MeshCore): Boolean = pinned

        /** The board as the session published it: what the Public post's gate reads (Stage 6). */
        override var ready: BoardState.Ready? = null

        override val consent = MutableStateFlow<MeshcoreConsent?>(null)

        /** The setup state the session published (Stage 7): what the face reads. */
        override val setupState = MutableStateFlow<MeshcoreSetupView?>(null)

        override suspend fun unbind(address: String): MeshcoreUnbind = MeshcoreUnbind.NOTHING

        override suspend fun removeUntaught(key: ByteArray): ContactWrite {
            removals += key
            return ContactWrite.WRITTEN
        }
    }

    private class Core : PlaneCore {
        override val airtime = LoraAirtime(traits = MeshcoreTraits)
        override var config: LoraConfig? = LoraConfig(BOARD, 0)

        /** The Public posts queued (Stage 6): text, ceiling and the timestamp the frame carries. */
        val publicQueued = mutableListOf<Triple<ByteArray, Ceiling, Long>>()
        var queueTakes = true
        var faces = 0

        override fun enqueuePublic(
            text: ByteArray,
            label: String,
            ceiling: Ceiling,
            timestampSecs: Long,
        ): Boolean {
            if (queueTakes) publicQueued += Triple(text, ceiling, timestampSecs)
            return queueTakes
        }

        override fun faceMoved() {
            faces++
        }

        /** The debug bridge's raw payloads queued (Stage 7): payload, label, and the wall clock it is fresh until. */
        val debugQueued = mutableListOf<Triple<ByteArray, String, Long>>()

        override fun enqueueDebug(
            payload: ByteArray,
            label: String,
            freshUntilWall: Long,
        ): Boolean {
            debugQueued += Triple(payload, label, freshUntilWall)
            return true
        }

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

        var holdersMoved = 0

        override fun holdersMoved() {
            holdersMoved++
        }
    }

    private var now = 10_000L
    private val ops = Ops()
    private val core = Core()
    private val logs = mutableListOf<String>()
    private val keys = mutableListOf<Pair<ByteArray?, String?>>()

    /** The bind's signature each claim edit carried, beside [keys]. */
    private val sigs = mutableListOf<ByteArray?>()

    private val scope = TestScope(UnconfinedTestDispatcher())

    /** The posts the hooks handed the Public room (Stage 6). */
    private val heard = mutableListOf<MeshcorePublicPost>()
    private val hooks = hooks(ops, core)

    private fun hooks(
        ops: Ops,
        core: Core,
    ) = MeshcoreHooks(
        ops,
        core,
        scope,
        clock = { now },
        log = { logs += it },
        wallClock = { WALL_NOW },
        onBoardKey = { k, sig, d ->
            keys +=
                k to d
            sigs += sig
        },
        onPublicPost = { heard += it },
    )

    private val boardKey = ByteArray(32) { (0xA0 + it).toByte() }

    private fun detail(
        multiAcks: Int = 1,
        freqKhz: Long? = 869_525,
        version: String? = "v1.18.0",
        key: ByteArray = boardKey,
        capacity: Int = 100,
        dialled: String = "DD:DD",
        namedForKnit: Boolean = true,
        publicSlot: MeshcorePublicSlot? = PUBLIC,
        sharesPosition: Boolean = false,
    ) = MeshcoreDetail(
        publicKey = key,
        versionCode = 11,
        version = version,
        defaultScope = false,
        multiAcks = multiAcks,
        clientRepeat = false,
        freqKhz = freqKhz,
        contactCapacity = capacity,
        slots = emptyList(),
        namedForKnit = namedForKnit,
        manualAdd = 1,
        // The session's own address, deliberately not the plane's configured one ("AA:1"): the key goes out under this.
        dialled = dialled,
        publicSlot = publicSlot,
        sharesPosition = sharesPosition,
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
        // An unbound fragment is refused (Stage 5c-1 reads bound parts: [aBoundPartIsKnitsAndAnUnboundOneIsNot]).
        assertEquals(PacketRoute.DROPPED, hooks.route(packet(channel, payload = byteArrayOf(FastFrameCodec.TAG_FRAG, 1, 2)), KNIT_SLOT))
        assertEquals(1L, hooks.counters.fragmentsRefused.get())
        // Everything else on the public Knit key, or anywhere else, is not a Knit frame.
        assertEquals("another data type on the Knit slot", PacketRoute.DROPPED, hooks.route(packet(channel, dataType = 0x1234), KNIT_SLOT))
        assertEquals("another slot", PacketRoute.DROPPED, hooks.route(packet(channel, slot = 2), KNIT_SLOT))
        assertEquals("no Knit slot on the board", PacketRoute.DROPPED, hooks.route(packet(channel), null))
        assertEquals("nothing in it", PacketRoute.DROPPED, hooks.route(packet(channel, payload = ByteArray(0)), KNIT_SLOT))
        assertEquals(4L, hooks.counters.foreignPosts.get())
        // Public's data is counted and discarded; its text is the kind's, for the room (Stage 6, the next block).
        assertEquals(PacketRoute.DROPPED, hooks.route(packet(channel, slot = 0), KNIT_SLOT))
        assertEquals(PacketRoute.KIND, hooks.route(packet(MeshcorePacket.Type.CHANNEL_TEXT, slot = 0, dataType = null), KNIT_SLOT))
        assertEquals(1L, hooks.counters.publicDiscarded.get())
        assertEquals(
            PacketRoute.DROPPED,
            hooks.route(packet(MeshcorePacket.Type.CHANNEL_TEXT, slot = KNIT_SLOT, dataType = null), KNIT_SLOT),
        )
        assertEquals(5L, hooks.counters.foreignPosts.get())
    }

    /**
     * Stage 5c-1: a bound part (`0x07`, part 0 committing to part 1) is Knit's, for the core's candidates
     * (`LoraFragCandidates`); an unbound `0x04` — the form a stranger on the public key can crowd, which no MeshCore phone
     * sends — and a bound part that does not parse are refused and counted. A whole elided frame (`0x06`) is Knit's too.
     */
    @Test
    fun aBoundPartIsKnitsAndAnUnboundOneIsNot() {
        val channel = MeshcorePacket.Type.CHANNEL_DATA

        fun part(
            tag: Byte,
            part: Int,
            count: Int = 2,
        ) = byteArrayOf(tag, 0, 7, ((part shl 4) or count).toByte()) + ByteArray(12) { 1 }
        assertEquals(PacketRoute.KNIT, hooks.route(packet(channel, payload = part(FastFrameCodec.TAG_BOUND_FRAG, 0)), KNIT_SLOT))
        assertEquals(PacketRoute.KNIT, hooks.route(packet(channel, payload = part(FastFrameCodec.TAG_BOUND_FRAG, 1)), KNIT_SLOT))
        assertEquals(
            PacketRoute.DROPPED,
            hooks.route(packet(channel, payload = part(FastFrameCodec.TAG_BOUND_FRAG, 0, count = 3)), KNIT_SLOT),
        )
        assertEquals(PacketRoute.DROPPED, hooks.route(packet(channel, payload = part(FastFrameCodec.TAG_FRAG, 0)), KNIT_SLOT))
        assertEquals(2L, hooks.counters.fragmentsRefused.get())
        assertEquals(PacketRoute.KNIT, hooks.route(packet(channel, payload = byteArrayOf(FastFrameCodec.TAG_ELIDED, 0, 0, 1)), KNIT_SLOT))
        // And only on the Knit slot under Knit's data type, as every Knit frame.
        assertEquals(
            PacketRoute.DROPPED,
            hooks.route(packet(channel, slot = 2, payload = part(FastFrameCodec.TAG_BOUND_FRAG, 0)), KNIT_SLOT),
        )
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

    /** Part 2: a sender Knit taught and pinned zero-hop is answered along that path, so only the path's acks are booked. */
    @Test
    fun aDirectDmFromATaughtPinnedSenderBooksOnlyTheAcksAlongItsPath() {
        hooks.beforeReady(ready(detail = detail(multiAcks = 3)))
        val air = core.airtime

        fun ms(
            payload: Int,
            flooded: Boolean,
        ) = air.airMsOfPhy(MeshcoreTraits.ackPhyBytes(payload, flooded))
        ops.pinned = true
        hooks.route(packet(MeshcorePacket.Type.CONTACT_TEXT, slot = null, payload = seal, cobs = true, pathLen = 0xFF), null)
        assertEquals(ms(7, flooded = false) + ms(6, flooded = false), air.usedMs(now))
        assertEquals(1L, hooks.counters.acksBookedDirect.get())
    }

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

    // --- the consent, observed from the session (Stage 5b-2, second part) ---

    private fun consent(granted: Boolean) = MeshcoreConsent(BOARD, MeshcoreHooks.digest(boardKey), granted, boardKey)

    @Test
    fun setupAndRestoreRunThroughTheSessionWhichOwnsTheRecord() =
        runTest {
            val record = MeshcoreSetupRecord(KNIT_SLOT, slotWritten = true, priorName = null, priorManualAdd = 0, priorAutoadd = null)
            ops.setupResult =
                MeshcoreSetupResult.Done(KNIT_SLOT, alreadyPresent = false, record = record, erasedContacts = 3, clearedSlots = 1)
            assertEquals(ProvisionResult.Provisioned(KNIT_SLOT, false), hooks.provision(ProvisionMode.Setup, null))
            assertTrue(logs.any { it == "lora setup kind=meshcore slot=$KNIT_SLOT erased contacts=3 slots=1" })
            assertEquals(ProvisionResult.Restored, hooks.provision(ProvisionMode.Restore, null))
            ops.restoreResult = MeshcoreRestoreResult.NoRecord
            assertTrue(hooks.provision(ProvisionMode.Restore, null) is ProvisionResult.Failed)
            ops.restoreResult = MeshcoreRestoreResult.Mismatch
            assertTrue(hooks.provision(ProvisionMode.Restore, null) is ProvisionResult.Failed)
            assertTrue(hooks.provision(ProvisionMode.SetupDedicated, null) is ProvisionResult.Failed)
            assertFalse(hooks.provisioning)
        }

    /** The maintainer's decision (2026-11-13): the claim, the offers and the taught set follow the actor's consent, in order. */
    @Test
    fun theClaimTheOffersAndTheTaughtSetFollowTheSessionsConsentInItsOrder() {
        val bob = edKey()
        val map = holders("bob" to bob)
        hooks.onBoardMap(map)
        assertTrue("nothing offered before any consent", hooks.offered(map).values.none { it.direct })
        assertTrue("no claim before any consent", keys.isEmpty())
        ops.consent.value = consent(granted = true)
        assertTrue(keys.last().first!!.contentEquals(boardKey))
        assertEquals(BOARD, keys.last().second)
        assertTrue(hooks.offered(map).values.all { it.direct })
        assertEquals(1, ops.taught.last().size)
        val moved = core.holdersMoved
        ops.consent.value = consent(granted = false)
        assertNull("withdrawn", keys.last().first)
        assertTrue(hooks.offered(map).values.none { it.direct })
        assertTrue("nothing taught without consent", ops.taught.last().isEmpty())
        assertTrue("and said so: no taught set the reconcile could list against (lora L1)", ops.withdrawals.last())
        assertEquals(KeyTeach.Refused, hooks.teachKey("bob", map.getValue("bob"), checkNotNull(MeshcorePins.addrOf(boardKey)), 0))
        assertEquals(moved + 1, core.holdersMoved)
    }

    /**
     * Stage 5d: the bind's signature reaches the claim store only off the consent, with its key, as one edit — and a
     * consent without one (a bind that failed this connection) claims the key alone, which the store's same-key rule
     * makes harmless; a withdrawal forgets both.
     */
    @Test
    fun theClaimCarriesTheConsentsBindAndAWithdrawalForgetsBoth() {
        val sig = ByteArray(64) { 7 }
        ops.consent.value = MeshcoreConsent(BOARD, MeshcoreHooks.digest(boardKey), true, boardKey, sig = sig)
        assertTrue(keys.last().first!!.contentEquals(boardKey))
        assertTrue("the signature rides with its key", sigs.last()!!.contentEquals(sig))
        ops.consent.value = consent(granted = true)
        assertTrue(keys.last().first!!.contentEquals(boardKey))
        assertNull("no bind this connection: the key alone", sigs.last())
        ops.consent.value = MeshcoreConsent(BOARD, MeshcoreHooks.digest(boardKey), granted = false, key = null, sig = sig)
        assertNull(keys.last().first)
        assertNull("a withdrawal forgets the signature too", sigs.last())
        assertNull("a consent that does not grant holds no signature", ops.consent.value?.sig)
    }

    /** The maintainer's decision (2026-11-14), the belt: a consent for any board but the bound one is never applied. */
    @Test
    fun aConsentForAnotherAddressThanTheBoundBoardIsIgnored() {
        val map = holders("bob" to edKey())
        hooks.onBoardMap(map)
        ops.consent.value = consent(granted = true)
        ops.consent.value = MeshcoreConsent("ZZ:ZZ", "00", granted = false, key = null)
        assertTrue("the bound board's claim stands", keys.last().first!!.contentEquals(boardKey))
        assertTrue(hooks.offered(map).values.all { it.direct })
        assertEquals(1, ops.taught.last().size)
    }

    /** Security N2 (the hooks' half): an unread consent leaves the claim as it was and closes the offers. */
    @Test
    fun anUnreadConsentKeepsTheClaimAndClosesTheOffers() {
        val map = holders("bob" to edKey())
        hooks.onBoardMap(map)
        ops.consent.value = consent(granted = true)
        ops.consent.value = MeshcoreConsent(BOARD, MeshcoreHooks.digest(boardKey), granted = false, key = null, unread = true)
        assertTrue("the claim is left as it was", keys.last().first!!.contentEquals(boardKey))
        assertTrue(hooks.offered(map).values.none { it.direct })
    }

    /** Security (the 6-item round's Blocking): a finished Restore or unbind is never unread, so the claim store hears null. */
    @Test
    fun aRestoreOrUnbindConsentWithdrawsTheClaimFromTheStore() {
        ops.consent.value = consent(granted = true)
        ops.consent.value = MeshcoreConsent(BOARD, MeshcoreHooks.digest(boardKey), granted = false, key = null, unread = false)
        assertNull("onBoardKey(null, …) reached the claim store", keys.last().first)
        assertEquals(BOARD, keys.last().second)
    }

    /** Security N2: a withdrawal whose claim edit throws is retried until it lands. */
    @Test
    fun aWithdrawalWhoseClaimEditFailsIsRetried() {
        var failures = 1
        val edits = mutableListOf<ByteArray?>()
        val h =
            MeshcoreHooks(ops, core, scope, clock = { now }, log = { logs += it }, wallClock = { WALL_NOW }, onBoardKey = { k, _, _ ->
                if (k == null && failures-- > 0) throw java.io.IOException("DataStore")
                edits += k
            })
        ops.consent.value = consent(granted = true)
        assertTrue(edits.last()!!.contentEquals(boardKey))
        ops.consent.value = MeshcoreConsent(BOARD, MeshcoreHooks.digest(boardKey), granted = false, key = null)
        assertTrue("the first edit threw", edits.last() != null)
        assertEquals(1L, h.counters.consentApplyFailures.get())
        scope.testScheduler.advanceTimeBy(MeshcoreHooks.CLAIM_RETRY_FIRST_MS + 1)
        scope.testScheduler.runCurrent()
        assertNull("the withdrawal landed on the retry", edits.last())
    }

    /** 2: claim edits are serialised: a stale grant retry paused mid-edit never lands after a newer withdrawal. */
    @Test
    fun aStaleGrantRetryNeverLandsAfterANewerWithdrawal() {
        var grantFails = 1
        val pause = kotlinx.coroutines.CompletableDeferred<Unit>()
        val edits = mutableListOf<ByteArray?>()
        MeshcoreHooks(ops, core, scope, clock = { now }, log = { logs += it }, wallClock = { WALL_NOW }, onBoardKey = { k, _, _ ->
            if (k != null) {
                if (grantFails-- > 0) throw java.io.IOException("DataStore")
                pause.await() // the retry is in its edit
            }
            edits += k
        })
        ops.consent.value = consent(granted = true) // G throws: owed, retried
        scope.testScheduler.advanceTimeBy(MeshcoreHooks.CLAIM_RETRY_FIRST_MS + 1)
        scope.testScheduler.runCurrent() // the retry holds the edit, paused
        ops.consent.value = MeshcoreConsent(BOARD, MeshcoreHooks.digest(boardKey), granted = false, key = null) // W, waits
        pause.complete(Unit)
        scope.testScheduler.runCurrent()
        assertNull("the last edit applied is the withdrawal ($edits)", edits.last())
    }

    /** 3 (M2b): a stale retry is dropped by the check inside the claim mutex, its one check. */
    @Test
    fun aStaleRetryIsDroppedByTheCheckInsideTheMutex() {
        var grantFails = 1
        val edits = mutableListOf<ByteArray?>()
        MeshcoreHooks(ops, core, scope, clock = { now }, log = { logs += it }, wallClock = { WALL_NOW }, onBoardKey = { k, _, _ ->
            if (k != null && grantFails-- > 0) throw java.io.IOException("DataStore")
            edits += k
        })
        ops.consent.value = consent(granted = true) // G throws: owed, its retry due in a second
        ops.consent.value = MeshcoreConsent(BOARD, MeshcoreHooks.digest(boardKey), granted = false, key = null) // W lands
        assertNull(edits.last())
        scope.testScheduler.advanceTimeBy(MeshcoreHooks.CLAIM_RETRY_FIRST_MS * 4)
        scope.testScheduler.runCurrent()
        assertTrue("the stale grant never reaches the store ($edits)", edits.none { it != null })
    }

    @Test
    fun onlyTaughtHoldersAreOfferedAndACapacityCutMovesTheOffers() {
        val keys = List(3) { edKey() }
        val map = holders("a" to keys[0], "b" to keys[1], "c" to keys[2])
        hooks.onBoardMap(map)
        ops.consent.value = consent(granted = true)
        assertTrue(hooks.offered(map).values.all { it.direct })
        val moved = core.holdersMoved
        hooks.beforeReady(ready(detail = detail(capacity = 2)))
        assertEquals("the capacity moved: the core re-asks", moved + 1, core.holdersMoved)
        assertEquals("one cut at the capacity is never offered", 1, hooks.offered(map).values.count { !it.direct })
        hooks.beforeReady(ready(detail = detail(capacity = 2)))
        assertEquals("no move, no re-ask", moved + 1, core.holdersMoved)
    }

    @Test
    fun theReadyLineSaysTheInboxIsKnits() {
        hooks.beforeReady(ready())
        hooks.onReady(ready())
        assertTrue(logs.any { it.startsWith("lora ready kind=meshcore ") && it.contains("inbox=knit-only") })
    }

    // --- the taught set (Stage 5b-2) ---

    private fun edKey(): ByteArray =
        com.google.crypto.tink.subtle.Ed25519Sign.KeyPair
            .newKeyPair()
            .publicKey

    private fun holders(vararg peers: Pair<String, ByteArray>): Map<String, LoraBoardHolder> =
        MeshcoreContacts.holdersOf(
            peers.associate { (peer, key) ->
                peer to
                    MeshcoreBoardHolder(
                        java.util.Base64
                            .getEncoder()
                            .encodeToString(key),
                        MeshcoreRank.ACCEPTED,
                        1,
                        false,
                    )
            },
        )

    /**
     * The interim placeholder (`di/MeshModule` feeds a map that never emits until 5b-2's second part): with no board map,
     * a Ready hands the session no taught set at all — so its reconcile, which waits for one, never runs.
     */
    @Test
    fun noTaughtSetIsHandedOverWhileNoBoardMapHasBeenEmitted() {
        hooks.onIdentified(boardKey)
        hooks.beforeReady(ready())
        hooks.onReady(ready())
        assertTrue("no setKnitContacts before a map", ops.taught.isEmpty())
        hooks.onBoardMap(emptyMap())
        assertEquals("an emitted empty map is a taught set of nothing", 1, ops.taught.size)
    }

    @Test
    fun theBoardMapDecidesTheTaughtSetAndABlockedContactIsRemovedFromTheBoard() {
        val bob = edKey()
        val carol = edKey()
        val map = holders("bob" to bob, "carol" to carol)
        hooks.onBoardMap(map)
        assertEquals(2, ops.taught.last().size)
        hooks.onBoardMap(map - "bob")
        assertEquals(listOf(carol.toList()), ops.taught.last().map { it.toList() })
        assertEquals("removed from the board once taught (ADR 2026-09.adgd)", listOf(bob.toList()), ops.removals.map { it.toList() })
        assertEquals(1L, hooks.counters.removedUntaught.get())
    }

    @Test
    fun theTaughtSetIsCutToTheBoardsCapacityAndNeverHoldsOurOwnKey() {
        // A real point, so the "us" holder survives the point check and only the own-key skip can drop it.
        val own = edKey()
        hooks.onIdentified(own)
        val keys = List(3) { edKey() }
        hooks.onBoardMap(holders("a" to keys[0], "b" to keys[1], "c" to keys[2], "us" to own))
        assertEquals(3, ops.taught.last().size)
        assertEquals("our own key is the one skipped", 1L, hooks.counters.keysSkipped.get())
        hooks.beforeReady(ready(detail = detail(capacity = 2, key = own)))
        assertEquals(2, ops.taught.last().size)
        assertTrue(ops.taught.flatten().none { it.contentEquals(own) })
    }

    @Test
    fun aRefusedDmIsTaughtOnlyForAWantedKeyAndOncePerBoardUntilADmToItIsAcked() =
        runTest {
            hooks.onIdentified(boardKey)
            val own = checkNotNull(MeshcorePins.addrOf(boardKey))
            val bob = edKey()
            val map = holders("bob" to bob)
            hooks.onBoardMap(map)
            val teach = hooks.teachKey("bob", map.getValue("bob"), own, 0) as KeyTeach.Write
            assertEquals(KeyWrite.WRITTEN, teach.write())
            assertTrue(ops.teaches.single().contentEquals(bob))
            assertEquals("written already, no DM acked since", KeyTeach.Refused, hooks.teachKey("bob", map.getValue("bob"), own, 0))
            hooks.onDelivered(checkNotNull(MeshcorePins.addrOf(bob)))
            assertTrue(hooks.teachKey("bob", map.getValue("bob"), own, 0) is KeyTeach.Write)
            val stranger = holders("eve" to edKey()).getValue("eve")
            assertEquals("not in the taught set", KeyTeach.Refused, hooks.teachKey("eve", stranger, own, 0))
            ops.teachResult = ContactWrite.NO_FREE_SLOT
            assertEquals(KeyWrite.REFUSED, (hooks.teachKey("bob", map.getValue("bob"), own, 0) as KeyTeach.Write).write())
        }

    @Test
    fun noLineOfTheTeachingNamesAKeyAPrefixOrAKnitName() =
        runTest {
            hooks.onIdentified(boardKey)
            val own = checkNotNull(MeshcorePins.addrOf(boardKey))
            val bob = edKey()
            val map = holders("bob" to bob)
            hooks.onBoardMap(map)
            (hooks.teachKey("bob", map.getValue("bob"), own, 0) as KeyTeach.Write).write()
            hooks.teachKey("bob", map.getValue("bob"), own, 0)
            hooks.onBoardMap(emptyMap())
            val everything =
                logs.joinToString("\n") + hooks.counters.toString() + map.values.joinToString() + own.toString() +
                    MeshcoreContacts().wanted(map, null, null, 0).toString()
            val base64 = java.util.Base64.getEncoder()
            for (k in listOf(bob, boardKey)) {
                assertFalse(everything.contains(hex(k).take(12)))
                assertFalse(everything.contains(base64.encodeToString(k).take(12)))
                assertFalse(everything.contains(MeshcoreNames.forKey(k)))
            }
        }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(Locale.ROOT, it) }

    // --- the Public mirror (Stage 6, ADR 2026-11.gsqu's Stage 6 amendment) ---

    private fun publicText(
        text: ByteArray,
        live: Boolean = true,
        slot: Int = MeshcoreHooks.PUBLIC_SLOT,
        stamp: Long? = POST_STAMP,
    ) = BoardPacket(
        from = null,
        toUs = false,
        channel = slot,
        payload = text,
        rxSnr = 5f,
        rxRssi = null,
        hopsAway = 0,
        rxTimeMs = null,
        kind = MeshcorePacket(MeshcorePacket.Type.CHANNEL_TEXT, slot, 0, MeshcoreProto.TXT_TYPE_PLAIN, null, stamp, live, false, text),
    )

    /** What the core does with a packet: the route, and the kind's own hook for a [PacketRoute.KIND]. */
    private fun land(packet: BoardPacket): PacketRoute =
        hooks.route(packet, KNIT_SLOT).also { if (it == PacketRoute.KIND) hooks.onKindPacket(packet) }

    /** A Ready board set up for Knit on this phone: named `Knit a0a1`, consented, slot 0 Public, its band 10 %. */
    private fun knitBoardReady(
        detail: MeshcoreDetail = detail(),
        consented: Boolean = true,
    ) {
        val r = ready(detail = detail)
        hooks.beforeReady(r)
        ops.ready = r
        ops.consent.value = consent(consented)
    }

    @Test
    fun aPublicTextIsTheKindsAndNeverAKnitFrame() {
        val text = "Knit 1a2b: north gate at noon".encodeToByteArray()
        assertEquals(PacketRoute.KIND, land(publicText(text)))
        // Bytes that are a Knit frame's tag are words on slot 0: judged, never routed as a frame.
        val frameLike = byteArrayOf(FastFrameCodec.TAG_ELIDED, 1, 2, 3)
        assertEquals(PacketRoute.KIND, land(publicText(frameLike)))
        // Data on slot 0, and a text anywhere else, are not the room's: discarded as before.
        assertEquals(PacketRoute.DROPPED, land(packet(MeshcorePacket.Type.CHANNEL_DATA, slot = 0)))
        assertEquals(PacketRoute.DROPPED, land(publicText(text, slot = 2)))
        assertEquals(1L, hooks.counters.publicDiscarded.get())
        assertEquals(1L, hooks.counters.foreignPosts.get())
        assertEquals(2, heard.size)
        assertEquals("Knit 1a2b", heard[0].name)
        assertEquals("north gate at noon", heard[0].body)
        assertEquals(PublicPostKey(POST_STAMP, text), heard[0].key)
        assertEquals(PublicPostKey(POST_STAMP, frameLike), heard[1].key)
        assertEquals(2L, hooks.counters.publicDelivered.get())
        assertEquals(2L, hooks.counters.publicHeard.get())
    }

    /**
     * Part 6c: a drain reaches the room in the order the board handed it over. The room takes the first post slowest, so
     * a launch per post would land the later ones first; one at a time, each waits for the one before.
     */
    @Test
    fun postsReachTheRoomInTheOrderTheBoardHandedThemOver() {
        val delivered = mutableListOf<String>()
        val ordered =
            MeshcoreHooks(
                ops,
                core,
                scope,
                clock = { now },
                log = { logs += it },
                wallClock = { WALL_NOW },
                onBoardKey = { _, _, _ -> },
                onPublicPost = { post ->
                    delay((DRAIN - post.body.substringAfterLast(' ').toInt()) * SLOW_ROOM_MS)
                    delivered += post.body
                },
            )
        val texts = (1..DRAIN).map { "stock: drained $it" }
        texts.forEach { text ->
            val packet = publicText(text.encodeToByteArray(), live = false)
            assertEquals(PacketRoute.KIND, ordered.route(packet, KNIT_SLOT))
            ordered.onKindPacket(packet)
        }
        scope.testScheduler.advanceUntilIdle()
        assertEquals(texts.map { it.removePrefix("stock: ") }, delivered)
        assertEquals(DRAIN.toLong(), ordered.counters.publicDelivered.get())
        assertEquals(0L, ordered.counters.publicBacklogDropped.get())
    }

    /** Security N13: the room switch is this plane's own config (`meshcoreRoomEnabled`), read where the post lands. */
    @Test
    fun theRoomSwitchDropsAPublicTextWhereItLandsAndAReplayIsDelivered() {
        core.config = LoraConfig(BOARD, 0, room = false)
        land(publicText("bob: one".encodeToByteArray()))
        assertTrue(heard.isEmpty())
        assertEquals(1L, hooks.counters.publicRoomOff.get())
        core.config = null
        land(publicText("bob: two".encodeToByteArray()))
        assertEquals("no plane config: no room", 2L, hooks.counters.publicRoomOff.get())
        core.config = LoraConfig(BOARD, 0, room = true)
        // The board's offline queue replays what it heard while the phone was away: delivered, marked replayed.
        land(publicText("bob: three".encodeToByteArray(), live = false))
        assertEquals(1, heard.size)
        assertFalse(heard.single().live)
        // An empty body, and a drain with no stamp to key it on, are refused and counted.
        land(publicText("bob:  ".encodeToByteArray()))
        land(publicText("bob: four".encodeToByteArray(), stamp = null))
        assertEquals(2L, hooks.counters.publicRefused.get())
        // A slot 0 that is Knit's own (a hand-built board) has no public channel to mirror.
        ops.ready = ready(detail = detail(publicSlot = PUBLIC.copy(knit = true)))
        land(publicText("bob: five".encodeToByteArray()))
        assertEquals(3L, hooks.counters.publicRefused.get())
        assertEquals(1, heard.size)
    }

    @Test
    fun aPostIsRefusedInTheBriefsOrderEachCountedAndNamed() {
        run {
            fun post(text: String = "hello") = runBlockingPost(text)
            // Everything wrong at once: each fix uncovers the next refusal, in order.
            core.config = LoraConfig(BOARD, 0, room = false)
            assertEquals(PublicPostRefusal.ROOM_OFF, post())
            core.config = LoraConfig(BOARD, 0)
            assertEquals(PublicPostRefusal.NOT_READY, post())
            // A board whose name is not Knit's: the firmware would put that name, maybe the user's own, in front.
            knitBoardReady(detail(namedForKnit = false, publicSlot = PUBLIC.copy(empty = true)))
            assertEquals(PublicPostRefusal.NOT_SET_UP, post())
            // Named Knit's, but this connection holds no consent for it.
            knitBoardReady(detail(publicSlot = PUBLIC.copy(empty = true)), consented = false)
            assertEquals(PublicPostRefusal.NOT_SET_UP, post())
            knitBoardReady(detail(publicSlot = PUBLIC.copy(empty = true)))
            assertEquals("slot 0 cleared: it would air under the null key", PublicPostRefusal.NO_CHANNEL, post())
            knitBoardReady(detail(publicSlot = null))
            assertEquals(PublicPostRefusal.NO_CHANNEL, post())
            knitBoardReady(detail(publicSlot = PUBLIC.copy(knit = true)))
            assertEquals(PublicPostRefusal.KNIT_ON_PRIMARY, post())
            knitBoardReady()
            assertNull(post("first"))
            // The floor claimed: slot 0 cleared again, the channel is refused before the floor is asked.
            knitBoardReady(detail(publicSlot = PUBLIC.copy(empty = true)))
            assertEquals("the channel before the floor", PublicPostRefusal.NO_CHANNEL, post())
            knitBoardReady()
            assertEquals("the floor before the size", PublicPostRefusal.TOO_SOON, post("x".repeat(150)))
            now += LoraMeshTransport.PUBLIC_POST_FLOOR_MS
            assertEquals("150 B: over 160 less `Knit a0a1: `", PublicPostRefusal.TOO_LARGE, post("x".repeat(150)))
            assertEquals(PublicPostRefusal.TOO_LARGE, post("\u0000"))
            // The floods' share spent by Knit's own room posts: no air now, the floor not claimed.
            repeat(
                200,
            ) { core.airtime.record(MeshcoreProto.CHANNEL_DATA_CAP, now, core.airtime.price(AirPurpose.ROOM_FLOOD), Ceiling.FLOOD) }
            assertEquals("the size before the air", PublicPostRefusal.TOO_LARGE, post("x".repeat(150)))
            assertEquals(PublicPostRefusal.NO_AIR, post("x".repeat(149)))
            assertEquals(1, core.publicQueued.size)
            assertEquals("every refusal counted", 13L, hooks.counters.publicPostsRefused.get())
            assertEquals(1L, hooks.counters.publicPostsQueued.get())
            assertTrue(logs.any { it == "lora public post refused: NOT_SET_UP" })
        }
    }

    /** Drives [MeshcoreHooks.postToPublicChannel] to its refusal, or null once queued. */
    private fun runBlockingPost(text: String): PublicPostRefusal? = kotlinx.coroutines.runBlocking { hooks.postToPublicChannel(text) }

    /**
     * Lora N3: a post the flood ceiling of an empty window can never carry is too large, not owed air — on a 1 % band a 149-B
     * post is past the floods' 0.15; the size the snapshot says fits is admitted.
     */
    @Test
    fun aPostTheFloodCeilingCanNeverCarryIsTooLarge() {
        knitBoardReady(detail(freqKhz = 868_100))
        val fits = core.airtime.publicPostBytes()
        assertTrue("a 1 % band carries less than the cap: $fits", fits in 1 until MeshcoreTraits.PUBLIC_TEXT_CAP)
        assertEquals(PublicPostRefusal.TOO_LARGE, runBlockingPost("x".repeat(fits + 1)))
        assertNull(runBlockingPost("x".repeat(fits)))
    }

    /**
     * Lora N4 / security N5: the timestamp is fixed at enqueue, rides the frame, and every write of it — a `Busy` retry
     * included — airs that one; the answer names the post as receivers read it, `Knit a0a1: ` and the text.
     */
    @Test
    fun theStampIsFixedAtEnqueueAndEveryWriteOfTheFrameCarriesIt() =
        runTest {
            knitBoardReady()
            val queued = hooks.postPublic("north gate") as PublicPostResult.Queued
            val (text, ceiling, stamp) = core.publicQueued.single()
            assertEquals("admitted and booked with Knit's floods", Ceiling.FLOOD, ceiling)
            assertEquals(WALL_NOW / 1_000, stamp)
            assertEquals(PublicPostKey(stamp, "Knit a0a1: north gate".encodeToByteArray()), queued.key)
            val frame =
                OutboundFrame(listOf(text), "public", FrameClass.ROOM, ceiling, Destination.Public, timestampSecs = stamp)
            assertEquals("priced as what flies: the prefix included", AirPurpose.PUBLIC_POST, frame.purpose)
            val written = mutableListOf<SendPurpose>()
            val answers = ArrayDeque(listOf(FragmentOutcome.OWED, FragmentOutcome.SENT))
            repeat(2) {
                hooks.sendKindFrame(frame) { bytes, purpose ->
                    assertArrayEquals(text, bytes)
                    written += purpose
                    answers.removeFirst()
                }
            }
            assertEquals(listOf<SendPurpose>(SendPurpose.PublicPost(stamp), SendPurpose.PublicPost(stamp)), written)
            assertEquals(1L, hooks.counters.publicPostsSent.get())
            assertTrue(logs.contains("lora tx public ${text.size}B"))
            // A frame with no stamp is never written: it is no post of this kind's.
            hooks.sendKindFrame(OutboundFrame(listOf(text), "public", destination = Destination.Public)) { _, _ ->
                error("an unstamped post is never written")
            }
        }

    /**
     * The 6a review: a post past its `freshUntil` is never written (kind frames skip the core's stale check), and every end
     * without airing — stale, or given up at the write — is reported under the key the enqueue answered; one the board took
     * is not.
     */
    @Test
    fun aStalePostIsNeverWrittenAndEveryEndWithoutAiringIsReportedByItsKey() =
        runTest {
            knitBoardReady()
            val ends = mutableListOf<PublicPostEnded>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { hooks.publicPostsEnded.collect { ends += it } }

            fun frameOf(
                queued: Triple<ByteArray, Ceiling, Long>,
                freshUntil: Long?,
            ) = OutboundFrame(
                listOf(queued.first),
                "public",
                destination = Destination.Public,
                gate = RideGate(freshUntil = freshUntil),
                timestampSecs = queued.third,
            )
            val stale = hooks.postPublic("one") as PublicPostResult.Queued
            hooks.sendKindFrame(frameOf(core.publicQueued.last(), WALL_NOW - 1)) { _, _ -> error("a stale post is never written") }
            now += LoraMeshTransport.PUBLIC_POST_FLOOR_MS
            val refused = hooks.postPublic("two") as PublicPostResult.Queued
            hooks.sendKindFrame(frameOf(core.publicQueued.last(), WALL_NOW + 1)) { _, _ -> FragmentOutcome.ENDED }
            now += LoraMeshTransport.PUBLIC_POST_FLOOR_MS
            hooks.postPublic("three")
            hooks.sendKindFrame(frameOf(core.publicQueued.last(), WALL_NOW + 1)) { _, _ -> FragmentOutcome.SENT }
            assertEquals(
                listOf(
                    PublicPostEnded(requireNotNull(stale.key), PublicPostEnded.Reason.STALE),
                    PublicPostEnded(requireNotNull(refused.key), PublicPostEnded.Reason.ENDED_AT_WRITE),
                ),
                ends,
            )
            assertEquals(2L, hooks.counters.publicPostsEnded.get())
            assertTrue(logs.contains("lora public post stale at the write"))
        }

    @Test
    fun aQueueThatWillNotTakeThePostLeavesTheFloorUnclaimed() {
        knitBoardReady()
        core.queueTakes = false
        assertEquals(PublicPostRefusal.NO_AIR, runBlockingPost("one"))
        core.queueTakes = true
        assertNull("the floor was given back", runBlockingPost("two"))
    }

    /** Lora B2 / N5: the face reads slot 0 as found, and a post may leave only from a board set up for Knit. */
    @Test
    fun theFaceReadsSlotZeroAsFoundAndPostsOnlyFromABoardSetUpForKnit() {
        val r = ready(detail = detail(publicSlot = MeshcorePublicSlot("friends", keyIsPublic = false, empty = false, knit = false)))
        assertNull("no consent yet", hooks.statusOf(r, LoraStatus()).publicChannel?.takeIf { it.postable })
        ops.consent.value = consent(true)
        assertTrue("consent moves the face", core.faces > 0)
        val face = requireNotNull(hooks.statusOf(r, LoraStatus()).publicChannel)
        assertEquals("friends", face.name)
        assertFalse("a re-keyed slot 0 is a private group's", face.keyIsPublic)
        assertTrue(face.postable)
        assertFalse(requireNotNull(hooks.statusOf(ready(detail = detail(namedForKnit = false)), LoraStatus()).publicChannel).postable)
        assertFalse(
            requireNotNull(hooks.statusOf(ready(detail = detail(publicSlot = PUBLIC.copy(empty = true))), LoraStatus()).publicChannel)
                .postable,
        )
        assertNull(
            hooks.statusOf(BoardState.Down(TransportHealth.Degraded, MeshcoreLinkState.Connecting), LoraStatus()).publicChannel,
        )
    }

    /** Security N8: nothing a heard post or our own carries — name, text, timestamp — reaches a line or a counter. */
    @Test
    fun noLineNamesAPublicPostsNameTextOrTime() =
        runTest {
            land(publicText("Mallory Example: meet at the bridge".encodeToByteArray()))
            knitBoardReady()
            hooks.postPublic("my secret plan")
            val stamp = core.publicQueued.single().third
            hooks.sendKindFrame(
                OutboundFrame(
                    listOf("my secret plan".encodeToByteArray()),
                    "public",
                    destination = Destination.Public,
                    timestampSecs = stamp,
                ),
            ) { _, _ -> FragmentOutcome.ENDED }
            runBlockingPost("again")
            val everything = logs.joinToString("\n") + hooks.counters.toString() + heard.toString() + hooks.lawState().toString()
            for (word in listOf("Mallory", "bridge", "secret", "$POST_STAMP", "$stamp", "a0a1")) {
                assertFalse("$word in: $everything", everything.contains(word))
            }
            assertEquals(1L, hooks.counters.publicPostsEnded.get())
        }

    // --- Stage 7: the radio screen's face, the Forget's fence, the bridge's raw send ---

    /** The face (Stage 7): the Ready board's facts, the setup state off the record (away too), never a name or a key. */
    @Test
    fun theFaceCarriesTheBoardsFactsAndTheSetupStateAwayToo() {
        ops.setupState.value = MeshcoreSetupView(BOARD, BoardSetupState.SET_UP)
        val r = ready(detail = detail(sharesPosition = true))
        hooks.beforeReady(r)
        val face = checkNotNull(hooks.statusOf(r, LoraStatus(battery = BoardBattery(null, 3.712f, false))).meshcore)
        assertEquals("v1.18.0", face.firmware)
        assertEquals(869_525L, face.freqKhz)
        assertEquals(8, face.spreadFactor)
        assertEquals(62_500, face.bandwidthHz)
        assertEquals(5, face.codingRate)
        assertEquals(false, face.defaultScope)
        assertEquals(3_712, face.batteryMv)
        assertEquals(true, face.sharesPosition)
        assertEquals(BoardSetupState.SET_UP, face.setup)
        assertFalse("no key in the face", face.toString().contains("a0a1"))
        // Away: the Ready's facts go, the position and the setup state stay for the same address.
        val down = BoardState.Down(TransportHealth.Degraded, MeshcoreLinkState.Connecting)
        val away = checkNotNull(hooks.statusOf(down, LoraStatus()).meshcore)
        assertNull(away.firmware)
        assertNull(away.freqKhz)
        assertNull(away.batteryMv)
        assertEquals(true, away.sharesPosition)
        assertEquals(BoardSetupState.SET_UP, away.setup)
        // Another address's state is not the bound board's, and nothing read yet is unknown, never "none".
        ops.setupState.value = MeshcoreSetupView("ZZ:ZZ", BoardSetupState.NONE)
        assertEquals(BoardSetupState.UNKNOWN, hooks.statusOf(down, LoraStatus()).meshcore?.setup)
        core.config = LoraConfig("ZZ:ZZ", 0)
        assertNull("never read for this address", hooks.statusOf(down, LoraStatus()).meshcore?.sharesPosition)
    }

    /** Stage 7: a setup state the session moves republishes the face, so the screen follows it while the board is away. */
    @Test
    fun aSetupStateMoveRepublishesTheFace() {
        val before = core.faces
        ops.setupState.value = MeshcoreSetupView(BOARD, BoardSetupState.PENDING)
        assertTrue(core.faces > before)
    }

    /**
     * Stage 7 (security B2/B3): a grant made before a Forget never reaches the claim store after it — not a retry of a claim
     * edit that threw, not a consent the collector applies late — while a grant made after it (a new setup) does.
     */
    @Test
    fun aForgetFencesOffEveryGrantMadeBeforeIt() {
        var failing = true
        val edits = mutableListOf<ByteArray?>()
        val h =
            MeshcoreHooks(ops, core, scope, clock = { now }, log = { logs += it }, wallClock = { WALL_NOW }, onBoardKey = { k, _, _ ->
                if (k != null && failing) throw java.io.IOException("DataStore")
                edits += k
            })
        // A grant whose edit throws: owed, and retried after a backoff.
        ops.consent.value = consent(granted = true)
        assertTrue(edits.isEmpty())
        // A grant made before the Forget, which the collector will apply only after it.
        val late = consent(granted = true)
        val forgot = mutableListOf<String>()
        kotlinx.coroutines.runBlocking { h.forgetClaim(BOARD) { forgot += "edit" } }
        assertEquals(listOf("edit"), forgot)
        failing = false
        scope.testScheduler.advanceTimeBy(MeshcoreHooks.CLAIM_RETRY_MAX_MS * 2)
        scope.testScheduler.runCurrent()
        ops.consent.value = late
        assertTrue("no key written after the Forget: $edits", edits.none { it != null })
        assertTrue(h.counters.claimsFenced.get() >= 1)
        // A new setup's grant, made after the Forget, is the user's again.
        ops.consent.value = consent(granted = true)
        assertTrue(edits.last()!!.contentEquals(boardKey))
    }

    /**
     * Both reviewers (Stage 7a): a Forget whose own edit threw owes the claim's withdrawal, fenced at the seq it took then. When
     * the user sets the same board up again before a retry lands, the new setup's claim stands: the retry withdraws nothing and
     * fences no later grant. Without a re-setup, the retry withdraws.
     */
    @Test
    fun anOwedWithdrawalNeverUndoesASetupMadeAfterItsForget() {
        val edits = mutableListOf<ByteArray?>()
        val h =
            MeshcoreHooks(ops, core, scope, clock = { now }, log = { logs += it }, wallClock = { WALL_NOW }, onBoardKey = { k, _, _ ->
                edits += k
            })
        ops.consent.value = consent(granted = true)

        fun forget() =
            MeshcoreForget(
                scope = scope,
                boundAddress = { BOARD },
                unbind = { MeshcoreUnbind.CLEARED },
                fenceClaim = { address, edit -> h.forgetClaim(address, edit) },
                clearBinding = { throw java.io.IOException("DataStore") },
                withdrawClaim = { edits += null },
                log = { logs += it },
                fenceClaimUpTo = { address, upTo, edit -> h.forgetClaimUpTo(address, upTo, edit) },
            )
        assertEquals(MeshcoreForgetResult.FAILED, kotlinx.coroutines.runBlocking { forget().forget() })
        // The same board set up again before the retry: its grant is newer than the Forget, and lands.
        ops.consent.value = consent(granted = true)
        assertTrue(edits.last()!!.contentEquals(boardKey))
        val fenced = h.counters.claimsFenced.get()
        scope.testScheduler.advanceTimeBy(MeshcoreHooks.CLAIM_RETRY_MAX_MS * 2)
        scope.testScheduler.runCurrent()
        assertTrue("the new setup's claim is still published: $edits", edits.last()!!.contentEquals(boardKey))
        assertTrue(logs.any { it == "lora forget kind=meshcore retry dropped: the board was set up again" })
        // And a later grant is not fenced by the retry.
        ops.consent.value = consent(granted = true)
        assertEquals(fenced, h.counters.claimsFenced.get())
        assertTrue(edits.last()!!.contentEquals(boardKey))
        // No re-setup this time: the retry withdraws.
        assertEquals(MeshcoreForgetResult.FAILED, kotlinx.coroutines.runBlocking { forget().forget() })
        scope.testScheduler.advanceTimeBy(MeshcoreHooks.CLAIM_RETRY_FIRST_MS + 1)
        scope.testScheduler.runCurrent()
        assertNull("withdrawn on the retry", edits.last())
    }

    /** Stage 7: a claim edit already running when the Forget comes lands first; the Forget's edit is after it, never before. */
    @Test
    fun aForgetWaitsBehindAClaimEditAlreadyRunning() =
        runTest {
            val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
            val order = mutableListOf<String>()
            val h =
                MeshcoreHooks(ops, core, backgroundScope, clock = { now }, log = { logs += it }, wallClock = { WALL_NOW }, onBoardKey = {
                    k,
                    _,
                    _,
                    ->
                    if (k != null) gate.await()
                    order += if (k != null) "grant" else "withdraw"
                })
            ops.consent.value = consent(granted = true)
            runCurrent()
            val forget = async { h.forgetClaim(BOARD) { order += "forget" } }
            runCurrent()
            assertTrue("the Forget waits on the stalled edit: $order", order.isEmpty())
            gate.complete(Unit)
            forget.await()
            assertEquals(listOf("grant", "forget"), order.take(2))
        }

    /**
     * The bridge's raw send (Stage 7, lora L3, security N5): through the core's queue (priced as a room post, the Knit slot's
     * debug purpose at the write), answered with its outcome, its slot and its booked milliseconds — and refused up front
     * without consent, off a board that is not Ready, or while the law holds. The payload is in no line.
     */
    @Test
    fun aDebugSendGoesThroughTheQueueAndIsRefusedWithoutConsentOrUnderTheLaw() =
        runTest {
            assertEquals(MeshcoreRawSend.NOT_READY, hooks.debugSend("probe".encodeToByteArray()).result)
            knitBoardReady(consented = false)
            assertEquals(MeshcoreRawSend.NO_CONSENT, hooks.debugSend("probe".encodeToByteArray()).result)
            ops.consent.value = consent(granted = true)
            val answer = async { hooks.debugSend("secret probe".encodeToByteArray()) }
            runCurrent()
            val (payload, label, fresh) = core.debugQueued.single()
            assertEquals("secret probe", payload.decodeToString())
            assertEquals(WALL_NOW + MeshcoreRawSend.WAIT_MS, fresh)
            assertEquals("one at a time", MeshcoreRawSend.BUSY, hooks.debugSend("again".encodeToByteArray()).result)
            val purposes = mutableListOf<SendPurpose>()
            hooks.sendKindFrame(
                OutboundFrame(listOf(payload), label, destination = Destination.Debug, gate = RideGate(freshUntil = fresh)),
            ) {
                _,
                purpose,
                ->
                purposes += purpose
                FragmentOutcome.SENT
            }
            val sent = answer.await()
            assertEquals(FragmentOutcome.SENT.name, sent.result)
            assertEquals(KNIT_SLOT, sent.slot)
            assertEquals(listOf<SendPurpose>(SendPurpose.Debug(KNIT_SLOT)), purposes)
            assertTrue("booked at its price", (sent.bookedMs ?: 0) > 0)
            assertFalse(sent.lawHolding)
            assertFalse(logs.joinToString().contains("secret"))
            // The law holds: refused before the queue.
            hooks.onIdentified(boardKey)
            hooks.beforeReady(ready(detail = detail(freqKhz = 868_100), radio = RadioParams(8, 125_000, 5)))
            ops.ready = ready(detail = detail(freqKhz = 868_100), radio = RadioParams(8, 125_000, 5))
            hooks.onRadioStats(WALL_NOW, uptimeSecs = 100, txAirSecs = 40)
            val held = hooks.debugSend("probe".encodeToByteArray())
            assertEquals(MeshcoreRawSend.LAW_HOLDS, held.result)
            assertTrue(held.lawHolding)
            assertEquals(1, core.debugQueued.size)
        }

    /** Stage 7: a raw payload past its freshness is never written, and its caller hears so. */
    @Test
    fun aStaleDebugSendIsNeverWritten() =
        runTest {
            knitBoardReady()
            val answer = async { hooks.debugSend("probe".encodeToByteArray()) }
            runCurrent()
            val (payload, label, _) = core.debugQueued.single()
            var wrote = false
            hooks.sendKindFrame(OutboundFrame(listOf(payload), label, destination = Destination.Debug, gate = RideGate(freshUntil = 0))) {
                _,
                _,
                ->
                wrote = true
                FragmentOutcome.SENT
            }
            assertFalse(wrote)
            assertEquals(MeshcoreRawSend.STALE, answer.await().result)
        }

    private companion object {
        /** A drain's length, and how much slower the room is with each earlier post of it (part 6c's ordering case). */
        const val DRAIN = 8
        const val SLOW_ROOM_MS = 10L

        /** Slot 0 as a companion boots it: `Public`, under the published key. */
        val PUBLIC = MeshcorePublicSlot("Public", keyIsPublic = true, empty = false, knit = false)
        const val BOARD = "DD:DD"
        const val POST_STAMP = 1_790_000_123L
        const val WALL_NOW = 1_800_000_000_000L
        const val KNIT_SLOT = 1
        const val SIGNED_PLAIN = 2
        const val FRAME_TAG: Byte = 0x05
    }
}
