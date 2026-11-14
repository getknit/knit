package app.getknit.knit.mesh.lora.meshcore

import app.getknit.knit.mesh.bluetooth.BackoffConfig
import app.getknit.knit.mesh.lora.AckProof
import app.getknit.knit.mesh.lora.BoardAddr
import app.getknit.knit.mesh.lora.BoardPacket
import app.getknit.knit.mesh.lora.BoardState
import app.getknit.knit.mesh.lora.GattResult
import app.getknit.knit.mesh.lora.KnitChannel
import app.getknit.knit.mesh.lora.PacketIdSource
import app.getknit.knit.mesh.lora.PacketOutcome
import app.getknit.knit.mesh.lora.RadioParams
import app.getknit.knit.mesh.lora.RefusalClass
import app.getknit.knit.mesh.lora.SendPurpose
import app.getknit.knit.mesh.lora.SendResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives [MeshcoreSession] against [FakeNusChannel] on a [FakeMeshcoreAir] under virtual time (ADR 2026-11.gsqu, Stage
 * 3b): the handshake, matching under another client's answers, the lossy drain, the pin, the retries, the ack table,
 * the headroom, setup and restore, and the reconnect. Never `advanceUntilIdle`: the ticker runs forever.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("LargeClass") // one session's whole contract over one rig
class MeshcoreSessionTest {
    private val epochMs = 1_790_000_000_000L

    private class Rig(
        val air: FakeMeshcoreAir,
        val alice: FakeMeshcoreBoard,
        val bob: FakeMeshcoreBoard,
        val dialer: FakeMeshcoreDialer,
        val session: MeshcoreSession,
        val outcomes: MutableList<PacketOutcome>,
        val packets: MutableList<BoardPacket>,
        val airs: MutableList<MeshcoreAir>,
        val headroom: MutableList<Int>,
        val logs: MutableList<String>,
    ) {
        val ch: FakeNusChannel get() = dialer.channel
        val bobAddr = BoardAddr.MeshCore(bob.key.copyOf(6))
        val ready: BoardState.Ready get() = session.state.value as BoardState.Ready
    }

    private fun TestScope.rig(
        pinned: Boolean = true,
        knitSlot: Boolean = true,
        // Set up for Knit (its slot and `Knit abcd`): nothing is sent, pinned or taught on any other board (5b-2 review B1).
        knitBoard: Boolean = knitSlot,
        taught: Boolean = true,
        collectPackets: Boolean = true,
        callDeadlineMs: Long = 30_000,
        setupDeadlineMs: Long = 300_000,
    ): Rig {
        val air = FakeMeshcoreAir(backgroundScope) { testScheduler.currentTime }
        val alice = air.board("alice")
        val bob = air.board("bob")
        air.link("alice", "bob")
        alice.addContact(bob, if (pinned) 0 else MeshcoreProto.PATH_UNKNOWN)
        bob.addContact(alice, 0)
        if (knitSlot) {
            alice.setChannel(1, KnitChannel.NAME, KnitChannel.PSK)
            bob.setChannel(1, KnitChannel.NAME, KnitChannel.PSK)
        }
        if (knitBoard) alice.advertName = MeshcoreNames.forKey(alice.key).encodeToByteArray()
        val dialer = FakeMeshcoreDialer(alice)
        val logs = mutableListOf<String>()
        val session =
            MeshcoreSession(
                dialer = dialer,
                scope = backgroundScope,
                backoff = BackoffConfig(baseMs = 5_000, maxMs = 180_000, jitterFraction = 0.0),
                now = { testScheduler.currentTime },
                wallClock = { epochMs + testScheduler.currentTime },
                rand = { 0.5 },
                ids = PacketIdSource(1000L),
                log = { logs += it },
                callDeadlineMs = callDeadlineMs,
                setupDeadlineMs = setupDeadlineMs,
            )
        if (taught) session.setKnitContacts(listOf(bob.key))
        val outcomes = mutableListOf<PacketOutcome>()
        val packets = mutableListOf<BoardPacket>()
        val airs = mutableListOf<MeshcoreAir>()
        val headroom = mutableListOf<Int>()
        val collect = UnconfinedTestDispatcher(testScheduler)
        backgroundScope.launch(collect) { session.outcomes.collect { outcomes += it } }
        if (collectPackets) backgroundScope.launch(collect) { session.packets.collect { packets += it } }
        backgroundScope.launch(collect) { session.air.collect { airs += it } }
        backgroundScope.launch(collect) { session.headroom.collect { headroom += it } }
        return Rig(air, alice, bob, dialer, session, outcomes, packets, airs, headroom, logs)
    }

    private fun TestScope.up(r: Rig) {
        r.session.start("AA:BB")
        runCurrent()
        advanceTimeBy(5_000) // the Ready re-assert's pins, one per tick
        runCurrent()
    }

    /** A sealed compact DM's stand-in: the `0x11` tag and zeros, which COBS must carry. */
    private val seal = byteArrayOf(0x11, 0, 1, 2, 0, 0, 3) + ByteArray(40) { (it % 5).toByte() }

    @Suppress("RedundantSuspendModifier") // a false positive: BoardLink.send suspends, and detekt does not see it through the rig
    private suspend fun Rig.dm(payload: ByteArray = seal): SendResult = session.send(payload, SendPurpose.DirectDm(1, bobAddr)).result

    /** The text frames alice's board was handed for bob, as it wrote them. */
    private fun Rig.sendTexts(): List<ByteArray> = ch.writes.filter { it[0].toInt() == MeshcoreProto.CMD_SEND_TXT_MSG }

    private fun Rig.textFromBob(text: ByteArray) {
        FakeForeignClient(bob).send(requireNotNull(MeshcoreProto.sendText(alice.key.copyOf(6), 0, 1_790_000_500L, text)))
    }

    // --- handshake ---

    @Test
    fun theHandshakeReachesReadyWithTheBoardsOwnFacts() =
        runTest {
            val r = rig()
            up(r)
            val ready = r.ready
            assertEquals(BoardAddr.MeshCore(r.alice.key.copyOf(6)), ready.own)
            assertEquals(164, ready.payloadCap)
            assertEquals(RadioParams(11, 250_000, 5), ready.radio)
            assertEquals(1, ready.channel)
            val detail = ready.detail as MeshcoreDetail
            assertEquals(14, detail.versionCode)
            assertEquals(100, detail.contactCapacity)
            assertEquals(869_525L, detail.freqKhz)
            assertArrayEquals(r.alice.key, detail.publicKey)
            assertEquals(listOf(MeshcoreSlot(0, false, false), MeshcoreSlot(1, false, true)), detail.slots.take(2))
            // DEVICE_QUERY v3, APP_START, the slots, the clock forward, the scope override cleared, the default scope read.
            assertEquals(listOf(22, 1, 31, 31, 31, 31, 6, 54, 64), r.ch.codes.take(9))
            assertArrayEquals(byteArrayOf(22, 3), r.ch.writes[0])
            assertArrayEquals(byteArrayOf(54, 0), r.ch.writes[7])
            assertTrue("the drain ran at Ready", MeshcoreProto.CMD_SYNC_NEXT_MESSAGE in r.ch.codes)
            assertEquals(1_790_000_000L, r.alice.clockSecs)
            assertNotNull(r.session.battery.value)
        }

    @Test
    fun theReadyCarriesTheAddressItsSessionDialled() =
        runTest {
            // The `dialled` race (ADR 2026-11.gsqu's Stage 5a-2 amendment): the board's key is written under this address.
            val r = rig()
            up(r)
            assertEquals("AA:BB", (r.ready.detail as MeshcoreDetail).dialled)
            r.session.start("CC:DD")
            runCurrent()
            advanceTimeBy(5_000)
            runCurrent()
            assertEquals("a rebind's Ready names the new address", "CC:DD", (r.ready.detail as MeshcoreDetail).dialled)
        }

    @Test
    fun aBoardWhoseClockIsAheadRefusesTheTimeAndIsStillReady() =
        runTest {
            val r = rig()
            r.alice.clockSecs = 1_900_000_000L
            up(r)
            assertTrue(r.session.state.value is BoardState.Ready)
            assertEquals(1_900_000_000L, r.alice.clockSecs)
        }

    @Test
    fun firmwareBelowTheFloorNeverReadsReady() =
        runTest {
            val r = rig()
            r.alice.versionCode = 10
            up(r)
            val st = r.session.state.value as BoardState.Down
            assertEquals(MeshcoreLinkState.FirmwareTooOld(10), st.detail)
            assertEquals(listOf(22), r.ch.codes)
        }

    @Test
    fun anInsaneRadioIsReadAsNoneAndPricedConservatively() =
        runTest {
            val r = rig()
            r.alice.sf = 20
            up(r)
            assertNull(r.ready.radio)
            assertNull((r.ready.detail as MeshcoreDetail).freqKhz)
        }

    // --- matching under another client's answers (binding rule "client" 2) ---

    @Test
    fun anotherClientsAnswersInterleavedWithOursGiveNoFalseAckAndLoseNoMessage() =
        runTest {
            val r = rig()
            up(r)
            val carol = FakeMeshcoreAir.keyOf("carol")
            r.ch.writeResult = { frame ->
                if (frame[0].toInt() == MeshcoreProto.CMD_SEND_TXT_MSG) {
                    // Ahead of the board's own answer: another client's ERR NOT_FOUND and ERR TABLE_FULL, its SENT
                    // (flooded), a confirmation of its code, a NO_MORE, and the message its SYNC drew.
                    r.ch.inject(byteArrayOf(1, 2))
                    r.ch.inject(byteArrayOf(1, 3))
                    r.ch.inject(byteArrayOf(6, 1) + MeshcoreProto.u32(0x01020304) + MeshcoreProto.u32(2_000))
                    r.ch.inject(byteArrayOf(0x82.toByte()) + MeshcoreProto.u32(0x01020304) + MeshcoreProto.u32(400))
                    r.ch.inject(byteArrayOf(10))
                    r.ch.inject(
                        byteArrayOf(16, 32, 0, 0) + carol.copyOf(6) + byteArrayOf(0xFF.toByte(), 0) + MeshcoreProto.u32(5) +
                            "hi".encodeToByteArray(),
                    )
                }
                GattResult.Ok(Unit)
            }
            val result = r.dm()
            assertTrue("a foreign ERR is never taken for our SENT: $result", result is SendResult.Queued)
            assertEquals("one write, never a second airing", 1, r.sendTexts().size)
            assertTrue("no false ack for another client's confirmation", r.outcomes.isEmpty())
            assertEquals("the stray TABLE_FULL holds the headroom: the pool is full whoever drew it", 0, r.headroom.last())
            assertEquals(
                "another client's flood is not our breach",
                0L,
                r.session.counters.directFlooded
                    .get(),
            )
            assertEquals("its message became a packet", 1, r.packets.count { it.from == BoardAddr.MeshCore(carol.copyOf(6)) })
            advanceTimeBy(1_000)
            runCurrent()
            val acked = r.outcomes.single() as PacketOutcome.Acked
            assertEquals((result as SendResult.Queued).id, acked.id)
            assertTrue(acked.byDestination)
            assertEquals(AckProof.VALID, acked.proof)
            assertEquals(1, acked.flown)
        }

    @Test
    fun aGarbledFrameIsCountedAndTheSessionGoesOn() =
        runTest {
            val r = rig()
            up(r)
            r.ch.inject(byteArrayOf(27, 1, 2))
            r.ch.inject(byteArrayOf(6, 0))
            r.ch.inject(byteArrayOf(0x8A.toByte()) + ByteArray(147))
            runCurrent()
            assertEquals(
                2L,
                r.session.counters.rejected
                    .get(),
            )
            assertEquals(
                1L,
                r.session.counters.adverts
                    .get(),
            )
            assertTrue(r.dm() is SendResult.Queued)
        }

    // --- the drain (binding rules "client" 1 and 3) ---

    @Test
    fun theOfflineQueueIsReplayedNotLiveAndWhatFollowsIsLive() =
        runTest {
            val r = rig()
            r.textFromBob(Cobs.encode(seal))
            r.textFromBob("replayed".encodeToByteArray())
            up(r)
            assertEquals(2, r.packets.size)
            assertTrue(r.packets.all { (it.kind as MeshcorePacket).live.not() })
            r.textFromBob("now".encodeToByteArray())
            runCurrent()
            val live = r.packets.last()
            assertTrue((live.kind as MeshcorePacket).live)
            assertEquals("now", live.payload.decodeToString())
            assertEquals("a direct DM brings no PATH return to book", 0, r.airs.size)
        }

    @Test
    fun theSessionNeverDiscardsADrainedMessage() =
        runTest {
            val r = rig()
            val secret = ByteArray(16) { 9 }
            r.alice.setChannel(2, "friends", secret)
            r.bob.setChannel(2, "friends", secret)
            up(r)
            val bobApp = FakeForeignClient(r.bob)
            r.textFromBob("lunch?".encodeToByteArray()) // the user's own DM, not Knit's
            r.textFromBob(Cobs.encode(seal)) // a compact DM
            bobApp.send(requireNotNull(MeshcoreProto.sendChannelText(0, 1L, "hello public".encodeToByteArray(), r.bob.advertName)))
            bobApp.send(requireNotNull(MeshcoreProto.sendChannelData(2, "theirs".encodeToByteArray(), dataType = 0x1234)))
            bobApp.send(requireNotNull(MeshcoreProto.sendChannelData(1, "knit".encodeToByteArray())))
            runCurrent()
            val kinds = r.packets.map { it.kind as MeshcorePacket }
            assertEquals(5, r.packets.size)
            val plain = r.packets[0]
            assertEquals(r.bobAddr, plain.from)
            assertFalse((plain.kind as MeshcorePacket).cobs)
            assertEquals("lunch?", plain.payload.decodeToString())
            val compact = r.packets[1]
            assertTrue((compact.kind as MeshcorePacket).cobs)
            assertArrayEquals("COBS undone below the transport", seal, compact.payload)
            assertEquals(MeshcorePacket.Type.CHANNEL_TEXT, kinds[2].type)
            assertEquals(0, kinds[2].slot)
            assertEquals("bob: hello public", r.packets[2].payload.decodeToString())
            assertEquals(MeshcorePacket.Type.CHANNEL_DATA, kinds[3].type)
            assertEquals(2, kinds[3].slot)
            assertEquals(0x1234, kinds[3].dataType)
            assertEquals(1, kinds[4].slot)
            assertEquals(MeshcoreProto.KNIT_DATA_TYPE, kinds[4].dataType)
            assertNull("channel data names no sender", r.packets[4].from)
            assertEquals("a zero-hop flood: path_len 0", 0, kinds[4].pathLen)
            assertEquals(0, r.packets[4].hopsAway)
        }

    @Test
    fun aReplyLostOnTheWayIsLostAndTheRestStillComeOnTheNextPoll() =
        runTest {
            val r = rig()
            var dropped = 0
            // Queued before we connected, they are V1 frames (7): the board composes each at reception.
            r.dialer.onOpen = { ch -> ch.dropFrame = { f -> f[0].toInt() in setOf(7, 16) && dropped++ == 0 } }
            repeat(3) { r.textFromBob("m$it".encodeToByteArray()) }
            up(r)
            assertTrue("the round ended on the lost reply's timeout", r.packets.isEmpty())
            advanceTimeBy(60_000) // the next poll runs a drain round
            runCurrent()
            assertEquals(
                "the first reply is gone: the firmware popped it before replying (Q3)",
                listOf("m1", "m2"),
                r.packets.map {
                    it.payload.decodeToString()
                },
            )
        }

    @Test
    fun aSyncReplyLandingAfterItsWaitStillYieldsThePacket() =
        runTest {
            val r = rig()
            var held: ByteArray? = null
            r.dialer.onOpen = { ch -> ch.dropFrame = { f -> (f[0].toInt() in setOf(7, 16) && held == null).also { if (it) held = f } } }
            r.textFromBob("late".encodeToByteArray())
            up(r)
            assertTrue(r.packets.isEmpty())
            r.ch.inject(requireNotNull(held))
            runCurrent()
            assertEquals(listOf("late"), r.packets.map { it.payload.decodeToString() })
        }

    @Test
    fun anotherClientsSyncInTheMiddleOfOurDrainStillYieldsEveryMessage() =
        runTest {
            val r = rig()
            val app = FakeForeignClient(r.alice)
            var once = true
            r.dialer.onOpen = { ch ->
                ch.writeResult = { f ->
                    if (f[0].toInt() == MeshcoreProto.CMD_SYNC_NEXT_MESSAGE && once) {
                        once = false
                        app.send(MeshcoreProto.syncNextMessage()) // pops m0 first; its reply reaches us too
                    }
                    GattResult.Ok(Unit)
                }
            }
            repeat(3) { r.textFromBob("m$it".encodeToByteArray()) }
            up(r)
            assertEquals(listOf("m0", "m1", "m2"), r.packets.map { it.payload.decodeToString() })
        }

    @Test
    fun aFloodedDmToUsIsReportedAsOneFloodedPathReturn() =
        runTest {
            val r = rig()
            r.bob.driftPath(r.alice)
            up(r)
            r.textFromBob("flooded".encodeToByteArray())
            runCurrent()
            val p = r.packets.single()
            assertEquals(0, (p.kind as MeshcorePacket).pathLen)
            assertTrue(r.airs.single() is MeshcoreAir.PathReturn)
        }

    @Test
    fun theOldReceiveCodesAnotherClientSwitchedUsToStillBecomePackets() =
        runTest {
            val r = rig()
            up(r)
            FakeForeignClient(r.alice).send(byteArrayOf(22, 1)) // app_target_ver is the board's, not ours
            r.textFromBob("v1 frame".encodeToByteArray())
            runCurrent()
            assertEquals(
                "v1 frame",
                r.packets
                    .single()
                    .payload
                    .decodeToString(),
            )
            assertNull(r.packets.single().rxSnr)
        }

    // --- the pin (binding rule "sends" 1) ---

    @Test
    fun aDmToAnUnpinnedContactIsPinnedReadBackAndThenSent() =
        runTest {
            val r = rig(pinned = false, taught = false)
            up(r)
            assertTrue(r.session.readContact(r.bob.key) is ContactRead.Found)
            // Taught after the Ready, so its re-assert has not pinned it: only a taught key is ever pinned (security B1).
            r.session.setKnitContacts(listOf(r.bob.key))
            val before = r.ch.writes.size
            assertTrue(r.dm() is SendResult.Queued)
            val codes =
                r.ch.writes
                    .drop(before)
                    .map { it[0].toInt() }
            // The reconcile the taught set woke lists after it (a poll, then GET_CONTACTS).
            assertEquals("read, write back zero-hop, read back, send", listOf(30, 9, 30, 2), codes.take(4))
            assertEquals("the 144-B form, no lastmod", 144, r.ch.writes[before + 1].size)
            assertEquals(0, r.alice.contact(r.bob.key)?.outPathLen)
            assertEquals(
                0L,
                r.session.counters.directFlooded
                    .get(),
            )
        }

    @Test
    fun aPinThatTimesOutWritesNoDmAndIsAMissingKey() =
        runTest {
            val r = rig(pinned = false, taught = false)
            up(r)
            r.session.setKnitContacts(listOf(r.bob.key))
            r.ch.swallow = { it[0].toInt() == MeshcoreProto.CMD_GET_CONTACT_BY_KEY }
            val result = r.dm()
            assertTrue("$result", result is SendResult.Rejected && result.res == MeshcoreRefusal.UNPINNED)
            assertEquals(RefusalClass.OWN_NO_KEY, MeshcoreRefusal.classOf(result))
            assertTrue("nothing written", r.sendTexts().isEmpty())
            assertEquals(
                1L,
                r.session.counters.pinFailures
                    .get(),
            )
        }

    @Test
    fun aDmToABoardNoPlumbingTaughtIsRefusedUnwritten() =
        runTest {
            val r = rig(taught = false)
            up(r)
            val result = r.dm()
            assertTrue("$result", result.isNoRecord())
            assertEquals(RefusalClass.OWN_NO_KEY, MeshcoreRefusal.classOf(result))
            assertTrue(r.sendTexts().isEmpty())
        }

    @Test
    fun aFloodedAttemptCancelsTheNextUntilAFreshPinIsReadBack() =
        runTest {
            val r = rig()
            up(r)
            r.air.loseAck = { true }
            r.ch.writeResult = { f ->
                // Between our pin and our send, another client resets the route: the board floods this attempt.
                if (f[0].toInt() == MeshcoreProto.CMD_SEND_TXT_MSG) r.alice.driftPath(r.bob)
                GattResult.Ok(Unit)
            }
            val result = r.dm()
            assertTrue(result is SendResult.Queued)
            assertEquals(
                1L,
                r.session.counters.directFlooded
                    .get(),
            )
            val flooded = r.airs.single() as MeshcoreAir.DirectFlooded
            assertEquals((result as SendResult.Queued).id, flooded.handle)
            r.ch.writeResult = { GattResult.Ok(Unit) }
            r.ch.swallow = { it[0].toInt() == MeshcoreProto.CMD_GET_CONTACT_BY_KEY }
            advanceTimeBy(30_000)
            runCurrent()
            assertEquals("no attempt 1 without a fresh pin", 1, r.sendTexts().size)
            assertEquals(PacketOutcome.Unreachable(result.id, flown = 1), r.outcomes.single())
        }

    @Test
    fun aPathPushDuringTheFlightRepinsBeforeTheNextAttempt() =
        runTest {
            val r = rig()
            up(r)
            r.air.loseAck = { it == 0 }
            assertTrue(r.dm() is SendResult.Queued)
            r.alice.driftPath(r.bob)
            r.ch.inject(byteArrayOf(0x81.toByte()) + r.bob.key)
            runCurrent()
            val before = r.ch.writes.size
            advanceTimeBy(5_000)
            runCurrent()
            val codes =
                r.ch.writes
                    .drop(before)
                    .map { it[0].toInt() }
                    .filter { it in setOf(30, 9, 2) }
            assertTrue("re-pinned before attempt 1: $codes", codes.indexOf(9) in 0 until codes.indexOf(2))
            assertEquals(0, r.alice.contact(r.bob.key)?.outPathLen)
            assertEquals(2, (r.outcomes.single() as PacketOutcome.Acked).flown)
        }

    // --- retries and outcomes (binding rules "sends" 3 and 4) ---

    @Test
    fun aConfirmationIsAckedByTheDestinationAndNoRetryFollows() =
        runTest {
            val r = rig()
            up(r)
            val result = r.dm() as SendResult.Queued
            advanceTimeBy(30_000)
            runCurrent()
            assertEquals(PacketOutcome.Acked(result.id, byDestination = true, proof = AckProof.VALID, flown = 1), r.outcomes.single())
            assertEquals(1, r.sendTexts().size)
            // The bob board's app got the compact DM, COBS-framed, zero-free.
            val text = (MeshcoreProto.decode(r.bob.offline.single()) as McFrame.ContactMessage).text
            assertTrue(text.none { it.toInt() == 0 })
            assertArrayEquals(seal, Cobs.decode(text))
        }

    @Test
    fun everyRetryKeepsTheTimestampChangesTheAttemptAndTheLastTimeoutIsUnreachable() =
        runTest {
            val r = rig()
            up(r)
            r.air.loseAck = { true }
            val result = r.dm() as SendResult.Queued
            advanceTimeBy(30_000)
            runCurrent()
            val sends = r.sendTexts()
            assertEquals(listOf(0, 1, 2), sends.map { it[2].toInt() })
            assertEquals(1, sends.map { it.copyOfRange(3, 7).toList() }.toSet().size)
            assertEquals(1, sends.map { it.copyOfRange(13, it.size).toList() }.toSet().size)
            assertEquals("each attempt is new bytes on air, so the receiver's app sees each (Q14)", 3, r.bob.offline.size)
            assertEquals(PacketOutcome.Unreachable(result.id, flown = 3), r.outcomes.single())
        }

    @Test
    fun aConfirmationAfterUnreachableIsALateAckUnderTheSameHandle() =
        runTest {
            val r = rig()
            up(r)
            r.air.loseAck = { true }
            val result = r.dm() as SendResult.Queued
            advanceTimeBy(30_000)
            runCurrent()
            val code =
                MeshcoreProto.expectedAck(
                    MeshcoreProto.u32(r.sendTexts()[1], 3),
                    1,
                    r.sendTexts()[1].copyOfRange(13, r.sendTexts()[1].size),
                    r.alice.key,
                )
            r.ch.inject(byteArrayOf(0x82.toByte()) + MeshcoreProto.u32(code.toLong()) + MeshcoreProto.u32(9_000))
            runCurrent()
            assertEquals(PacketOutcome.Acked(result.id, byDestination = true, proof = AckProof.VALID), r.outcomes.last())
        }

    @Test
    fun eightUnsettledSendsFillTheAckTableAndTheNinthIsBusy() =
        runTest {
            val r = rig()
            val others = (1..9).map { r.air.board("c$it") }
            others.forEach { r.alice.addContact(it) }
            r.session.setKnitContacts(others.map { it.key })
            up(r)
            advanceTimeBy(20_000)
            runCurrent()
            val results = others.map { r.session.send(seal, SendPurpose.DirectDm(1, BoardAddr.MeshCore(it.key.copyOf(6)))).result }
            assertEquals(8, results.count { it is SendResult.Queued })
            assertEquals(SendResult.Busy, results.last())
        }

    @Test
    fun anotherClientsSendsFillTheBoardsAckTableToo() =
        runTest {
            val r = rig()
            up(r)
            val app = FakeForeignClient(r.alice)
            r.air.loseAck = { true } // before the sends: no confirmation ever frees a slot
            repeat(
                8,
            ) { app.send(requireNotNull(MeshcoreProto.sendText(r.bob.key.copyOf(6), 0, 1_790_000_700L + it, "x$it".encodeToByteArray()))) }
            runCurrent()
            assertEquals("the board's table holds eight codes still owed", SendResult.Busy, r.dm())
        }

    @Test
    fun aBoardRebootDropsTheAcksItCanNoLongerSend() =
        runTest {
            val r = rig()
            up(r)
            r.air.loseAck = { true }
            val result = r.dm() as SendResult.Queued
            val sent = r.sendTexts().single()
            val code = MeshcoreProto.expectedAck(MeshcoreProto.u32(sent, 3), 0, sent.copyOfRange(13, sent.size), r.alice.key)
            advanceTimeBy(1_000)
            r.alice.reboot()
            advanceTimeBy(3_000) // a poll sees the uptime go backwards
            runCurrent()
            r.ch.inject(byteArrayOf(0x82.toByte()) + MeshcoreProto.u32(code.toLong()) + MeshcoreProto.u32(10))
            runCurrent()
            assertTrue(r.outcomes.none { it is PacketOutcome.Acked && it.id == result.id })
        }

    // --- headroom (binding rule "sends" 5) ---

    @Test
    fun tableFullHoldsHeadroomAtZeroUntilAPollShowsTheBoardDraining() =
        runTest {
            val r = rig()
            up(r)
            r.alice.outbound = MeshcoreHeadroom.POOL
            val result = r.session.send(ByteArray(40) { 1 }, SendPurpose.KnitFrame(1)).result
            assertEquals(SendResult.Busy, result)
            assertEquals(0, r.headroom.last())
            assertEquals(SendResult.Busy, r.session.send(ByteArray(40) { 1 }, SendPurpose.KnitFrame(1)).result)
            r.alice.outbound = 3
            advanceTimeBy(2_000)
            runCurrent()
            assertEquals(13, r.headroom.last())
            assertTrue(r.session.send(ByteArray(40) { 1 }, SendPurpose.KnitFrame(1)).result is SendResult.Queued)
        }

    @Test
    fun aRoomPostToASlotThatIsNoLongerKnitsIsRefused() =
        runTest {
            val r = rig()
            up(r)
            FakeForeignClient(r.alice).send(requireNotNull(MeshcoreProto.setChannel(1, "theirs", ByteArray(16) { 5 })))
            advanceTimeBy(61_000)
            runCurrent()
            val result = r.session.send(ByteArray(40) { 1 }, SendPurpose.KnitFrame(1)).result
            assertEquals(SendResult.Rejected(0u, MeshcoreRefusal.SLOT_NOT_KNIT), result)
            assertFalse(r.session.slotReading()!!.knit)
        }

    @Test
    fun aPublicPostTheFirmwareWouldTrimIsRefused() =
        runTest {
            val r = rig()
            up(r)
            val cap = MeshcoreProto.channelTextCap(r.alice.advertName)
            assertEquals(SendResult.TooLarge, r.session.send(ByteArray(cap + 1) { 0x41 }, SendPurpose.PublicPost).result)
            assertTrue(r.session.send(ByteArray(cap) { 0x41 }, SendPurpose.PublicPost).result is SendResult.Queued)
        }

    // --- targeted re-pins (binding rule "sends" 2) ---

    @Test
    fun aBurstOfPathPushesRepinsEachKnitKeyOnceAWindowAndSendsStillGo() =
        runTest {
            val r = rig()
            up(r)
            advanceTimeBy(60_000) // past the minute the Ready re-assert counted
            runCurrent()
            val before = r.ch.writes.size
            repeat(15) { r.ch.inject(byteArrayOf(0x81.toByte()) + randomKey(kotlin.random.Random(it))) }
            r.ch.inject(byteArrayOf(0x81.toByte()) + r.bob.key)
            runCurrent()
            advanceTimeBy(2_000) // the one re-pin runs
            runCurrent()
            repeat(15) { r.ch.inject(byteArrayOf(0x81.toByte()) + randomKey(kotlin.random.Random(100 + it))) }
            repeat(4) { r.ch.inject(byteArrayOf(0x81.toByte()) + r.bob.key) } // inside the minute: unverified only
            runCurrent()
            advanceTimeBy(10_000)
            runCurrent()
            assertTrue(r.dm() is SendResult.Queued)
            val reads =
                r.ch.writes
                    .drop(before)
                    .filter { it[0].toInt() == MeshcoreProto.CMD_GET_CONTACT_BY_KEY }
            assertTrue("only bob's key, never a foreign one", reads.all { it.copyOfRange(1, 33).contentEquals(r.bob.key) })
            assertEquals("one re-pin in the window, then the send's own pin", 2, reads.size)
            assertEquals(
                30L,
                r.session.counters.foreignPathPushes
                    .get(),
            )
            assertEquals(
                4L,
                r.session.counters.repinsCoalesced
                    .get(),
            )
        }

    // --- contacts and setup (binding rules "contacts" 1–3) ---

    @Test
    fun teachingIntoAFullTableNeverEvictsTheUsersContacts() =
        runTest {
            val r = rig(taught = false) // no taught set: the reconcile waits and removes nothing
            val carol = r.air.board("carol")
            r.alice.addContact(carol, flags = 0) // the user's own, not a favourite
            r.alice.maxContacts = 2
            r.alice.autoadd = MeshcoreProto.AUTO_ADD_OVERWRITE_OLDEST // the user's setting, before any setup
            up(r)
            val dave = FakeMeshcoreAir.keyOf("dave")
            assertEquals(ContactWrite.NO_FREE_SLOT, r.session.teach(dave))
            assertNotNull(r.alice.contact(carol.key))
            assertTrue(
                r.ch.writes.none { it[0].toInt() == MeshcoreProto.CMD_ADD_UPDATE_CONTACT && it.copyOfRange(1, 33).contentEquals(dave) },
            )
            // What the guard prevents: the same write unguarded evicts carol (MyMesh.cpp's allocateContactSlot).
            FakeForeignClient(r.alice).send(MeshcoreProto.addUpdateContact(ContactRecord.knit(dave, "Knit dave"), false))
            assertNull(r.alice.contact(carol.key))
        }

    @Test
    fun aTeachIntoAFreeSlotWritesAKnitNamedZeroHopFavourite() =
        runTest {
            val r = rig()
            up(r)
            val dave = FakeMeshcoreAir.keyOf("dave")
            assertEquals(ContactWrite.WRITTEN, r.session.teach(dave))
            val c = r.alice.contact(dave)!!
            assertEquals(0, c.outPathLen)
            assertEquals(MeshcoreProto.CONTACT_FLAG_FAVOURITE, c.flags)
            assertEquals(MeshcoreNames.forKey(dave), c.name.decodeToString())
            assertEquals(ContactWrite.ALREADY_PRESENT, r.session.teach(dave))
            assertEquals(ContactWrite.WRITTEN, r.session.removeContact(dave))
            assertNull(r.alice.contact(dave))
        }

    @Test
    fun setupAndRestoreRoundTripExactlyWhatWasThere() =
        runTest {
            val r = rig(knitSlot = false)
            r.alice.autoadd = MeshcoreProto.AUTO_ADD_OVERWRITE_OLDEST or MeshcoreProto.AUTO_ADD_CHAT or 0x04
            up(r)
            assertNull(r.ready.channel)
            val done = r.session.setup() as MeshcoreSetupResult.Done
            assertEquals(1, done.slot)
            assertFalse(done.alreadyPresent)
            assertEquals(1, r.ready.channel)
            assertEquals(
                "Knit",
                r.alice.channels[1]
                    .first
                    .decodeToString(),
            )
            assertArrayEquals(KnitChannel.PSK, r.alice.channels[1].second)
            assertEquals(MeshcoreNames.forKey(r.alice.key), r.alice.advertName.decodeToString())
            assertEquals(1, r.alice.manualAdd)
            assertEquals("chat auto-add and overwrite-oldest off, the rest kept", 0x04, r.alice.autoadd)
            assertArrayEquals(byteArrayOf(38, 1), r.ch.writes.single { it[0].toInt() == MeshcoreProto.CMD_SET_OTHER_PARAMS })
            assertEquals("alice", done.record.priorName?.decodeToString())
            assertEquals(0, done.record.priorManualAdd)
            assertEquals(0x07, done.record.priorAutoadd)
            assertTrue("the radio is never touched", r.ch.codes.none { it == 11 || it == 12 })

            val again = r.session.setup() as MeshcoreSetupResult.Done
            assertTrue("a re-run finds it set up and changes nothing", again.alreadyPresent && !again.record.changedAnything)

            val back = r.session.restore(done.record) as MeshcoreRestoreResult.Done
            assertTrue(back.slotCleared)
            assertTrue(back.leftAsFound.isEmpty() && back.failed.isEmpty())
            assertTrue(
                r.alice.channels[1]
                    .first
                    .isEmpty(),
            )
            assertEquals("alice", r.alice.advertName.decodeToString())
            assertEquals(0, r.alice.manualAdd)
            assertEquals(0x07, r.alice.autoadd)
            assertNull(r.ready.channel)
        }

    @Test
    fun restoreLeavesWhatSomeoneElseChangedSinceAndReportsIt() =
        runTest {
            val r = rig(knitSlot = false)
            up(r)
            val done = r.session.setup() as MeshcoreSetupResult.Done
            val app = FakeForeignClient(r.alice)
            app.send(requireNotNull(MeshcoreProto.setChannel(done.slot, "Hiking", ByteArray(16) { 3 })))
            app.send(requireNotNull(MeshcoreProto.setAdvertName("Mine")))
            val back = r.session.restore(done.record) as MeshcoreRestoreResult.Done
            assertFalse(back.slotCleared)
            assertEquals(listOf("slot", "name"), back.leftAsFound)
            assertEquals(
                "Hiking",
                r.alice.channels[done.slot]
                    .first
                    .decodeToString(),
            )
            assertEquals("Mine", r.alice.advertName.decodeToString())
            assertEquals("what was still Knit's went back", 0, r.alice.manualAdd)
        }

    /**
     * The maintainer's Stage 5 decisions: setup erases every slot but Public and Knit's, so a board whose slots are all taken
     * is no longer refused (it was `NoFreeSlot`, nothing written, before Stage 5b-2): its first slot becomes Knit's.
     */
    @Test
    fun setupOnABoardWithEverySlotTakenErasesThemAndTakesTheFirst() =
        runTest {
            val r = rig(knitSlot = false)
            (1..3).forEach { r.alice.setChannel(it, "c$it", ByteArray(16) { _ -> it.toByte() }) }
            up(r)
            val done = r.session.setup() as MeshcoreSetupResult.Done
            assertEquals(1, done.slot)
            assertEquals(3, done.clearedSlots)
            assertEquals(
                "Knit",
                r.alice.channels[1]
                    .first
                    .decodeToString(),
            )
            assertTrue(
                (2..3).all {
                    r.alice.channels[it]
                        .first
                        .isEmpty()
                },
            )
            assertEquals(
                "Public stays",
                "Public",
                r.alice.channels[0]
                    .first
                    .decodeToString(),
            )
        }

    @Test
    fun setupWithNoSlotButPublicWritesNothing() =
        runTest {
            val air = FakeMeshcoreAir(backgroundScope) { testScheduler.currentTime }
            val alice = air.board("alice", maxChannels = 1)
            val carol = air.board("carol")
            alice.addContact(carol, name = "Carol Smith", flags = 0) // something an erase would remove
            val dialer = FakeMeshcoreDialer(alice)
            val session =
                MeshcoreSession(dialer = dialer, scope = backgroundScope, now = { testScheduler.currentTime }, wallClock = { epochMs })
            session.start("AA:BB")
            runCurrent()
            val before = dialer.channel.writes.size
            assertEquals(MeshcoreSetupResult.NoFreeSlot, session.setup())
            val writes =
                dialer.channel.writes
                    .drop(before)
                    .map { it[0].toInt() }
            assertTrue("$writes", writes.none { it in setOf(8, 15, 32, 38, 58) })
            assertEquals("alice", alice.advertName.decodeToString())
            assertNotNull("refused before any erase", alice.contact(carol.key))
        }

    @Test
    fun aSetupWhoseWriteDoesNotReadBackPutsBackWhatItWrote() =
        runTest {
            val r = rig(knitSlot = false)
            r.alice.autoadd = MeshcoreProto.AUTO_ADD_CHAT
            up(r)
            r.ch.swallow = { it[0].toInt() == MeshcoreProto.CMD_SET_ADVERT_NAME }
            val failed = r.session.setup() as MeshcoreSetupResult.Failed
            assertNull("everything written was put back", failed.left)
            assertEquals(MeshcoreProto.AUTO_ADD_CHAT, r.alice.autoadd)
            assertEquals(0, r.alice.manualAdd)
            assertTrue(
                r.alice.channels[1]
                    .first
                    .isEmpty(),
            )
        }

    // --- reconnect ---

    @Test
    fun aDropEndsTheFlightsAndTheSessionRedialsBackToReady() =
        runTest {
            val r = rig()
            up(r)
            r.air.loseAck = { true }
            val result = r.dm() as SendResult.Queued
            r.ch.disconnect()
            runCurrent()
            assertEquals(PacketOutcome.NoOutcome(result.id, flown = 1), r.outcomes.single())
            assertTrue((r.session.state.value as BoardState.Down).detail is MeshcoreLinkState.Disconnected)
            advanceTimeBy(6_000)
            runCurrent()
            assertTrue(r.session.state.value is BoardState.Ready)
            assertEquals(2, r.dialer.channels.size)
            assertTrue(
                r.dialer.channels
                    .first()
                    .closed,
            )
        }

    @Test
    fun anAuthRefusalOnABondedBoardIsAStaleBondAndStopsRetrying() =
        runTest {
            val r = rig()
            r.dialer.onOpen = { ch -> ch.writeResult = { GattResult.Failed(137) } }
            up(r)
            assertEquals(MeshcoreLinkState.StaleBond("AA:BB"), (r.session.state.value as BoardState.Down).detail)
            advanceTimeBy(200_000)
            runCurrent()
            assertEquals(1, r.dialer.channels.size)
        }

    @Test
    fun aWriteClosedUnderAnUnbondedPairingNeedsPairing() =
        runTest {
            val r = rig()
            r.dialer.bond = app.getknit.knit.mesh.lora.BondState.NONE
            r.dialer.onOpen = { ch -> ch.writeResult = { GattResult.Closed } }
            up(r)
            assertEquals(MeshcoreLinkState.NeedsPairing("AA:BB"), (r.session.state.value as BoardState.Down).detail)
        }

    // --- the second review's batch ---

    @Test
    fun aForeignNotFoundDuringATeachNeverOverwritesTheUsersContact() =
        runTest {
            val r = rig(taught = false) // no taught set: the reconcile waits and removes nothing
            val carol = r.air.board("carol")
            r.alice.addContact(carol, name = "Carol Smith", flags = 0)
            up(r)
            r.ch.writeResult = { frame ->
                if (frame[0].toInt() == MeshcoreProto.CMD_GET_CONTACT_BY_KEY) r.ch.inject(byteArrayOf(1, 2))
                GattResult.Ok(Unit)
            }
            assertEquals(ContactWrite.ALREADY_PRESENT, r.session.teach(carol.key))
            assertEquals(
                "Carol Smith",
                r.alice
                    .contact(carol.key)
                    ?.name
                    ?.decodeToString(),
            )
            assertTrue(r.ch.writes.none { it[0].toInt() == MeshcoreProto.CMD_ADD_UPDATE_CONTACT })
        }

    @Test
    fun aSlotReadingNeverOutlivesItsConnection() =
        runTest {
            val r = rig()
            up(r)
            assertEquals(1, r.session.slotReading()?.index)
            r.ch.disconnect()
            runCurrent()
            assertNull(r.session.slotReading())
            // While the link is down the user's own app moves Knit off slot 1 and puts a private channel there.
            FakeForeignClient(r.alice).send(requireNotNull(MeshcoreProto.setChannel(1, "family", ByteArray(16) { 7 })))
            advanceTimeBy(6_000) // redialled well inside the 60-s reading TTL
            runCurrent()
            assertTrue(r.session.state.value is BoardState.Ready)
            assertNull(r.ready.channel)
            assertNull(r.session.slotReading())
            val result = r.session.send(ByteArray(40) { 1 }, SendPurpose.KnitFrame(1)).result
            assertEquals(SendResult.Rejected(0u, MeshcoreRefusal.SLOT_NOT_KNIT), result)
            assertTrue(r.ch.writes.none { it[0].toInt() == MeshcoreProto.CMD_SEND_CHANNEL_DATA })
        }

    @Test
    fun aStrayOkBesideAPostsOwnIsAmbiguousAndNeverARefusal() =
        runTest {
            val r = rig()
            up(r)
            r.ch.writeResult = { frame ->
                if (frame[0].toInt() == MeshcoreProto.CMD_SEND_CHANNEL_TXT_MSG) r.ch.inject(byteArrayOf(0))
                GattResult.Ok(Unit)
            }
            val result = r.session.send("hi".encodeToByteArray(), SendPurpose.PublicPost).result
            assertTrue("$result", result is SendResult.Timeout)
            assertEquals(
                1L,
                r.session.counters.strayAnswers
                    .get(),
            )
            r.ch.writeResult = { GattResult.Ok(Unit) }
            assertTrue(r.session.send("hi".encodeToByteArray(), SendPurpose.PublicPost).result is SendResult.Queued)
        }

    @Test
    fun aCountRefusedWhileAnotherClientListsIsAskedAgain() =
        runTest {
            val r = rig(taught = false) // no listing this Ready: the teach reads the count
            up(r)
            var refusals = 1
            r.ch.swallow = { frame -> frame[0].toInt() == MeshcoreProto.CMD_GET_CONTACTS && refusals-- > 0 }
            r.ch.writeResult = { frame ->
                if (frame[0].toInt() == MeshcoreProto.CMD_GET_CONTACTS && refusals > 0) r.ch.inject(byteArrayOf(1, 4))
                GattResult.Ok(Unit)
            }
            val dave = FakeMeshcoreAir.keyOf("dave")
            assertEquals(ContactWrite.WRITTEN, r.session.teach(dave))
            assertEquals(2, r.ch.codes.count { it == MeshcoreProto.CMD_GET_CONTACTS })
        }

    @Test
    fun aFloodedSignedDmToUsIsAPathReturnToo() =
        runTest {
            val r = rig()
            up(r)
            // TXT_TYPE_SIGNED_PLAIN (2), flooded (path_len 0): its receive frame carries the signer's 4-byte prefix.
            r.ch.inject(
                byteArrayOf(16, 32, 0, 0) + r.bob.key.copyOf(6) + byteArrayOf(0, 2) + MeshcoreProto.u32(9) + byteArrayOf(1, 2, 3, 4) +
                    "signed".encodeToByteArray(),
            )
            runCurrent()
            assertEquals(
                "signed",
                r.packets
                    .single()
                    .payload
                    .decodeToString(),
            )
            assertTrue(r.airs.single() is MeshcoreAir.PathReturn)
        }

    @Test
    fun whatIsDrainedBeforeAnyoneCollectsIsHeldForTheCollector() =
        runTest {
            val r = rig(collectPackets = false)
            repeat(3) { r.textFromBob("early$it".encodeToByteArray()) }
            up(r)
            assertTrue("drained already", r.alice.offline.isEmpty())
            val got =
                withTimeout(5_000) {
                    r.session.packets
                        .take(3)
                        .toList()
                } // a missing packet fails here, not on runTest's clock
            assertEquals(listOf("early0", "early1", "early2"), got.map { it.payload.decodeToString() })
        }

    @Test
    fun aBusyBandNeverStarvesASendOrItsRetries() =
        runTest {
            val r = rig()
            up(r)
            r.ch.latencyMs = 20
            r.air.airMs = 1 // bob's pool never holds the flood back
            r.air.loseAck = { true }
            val bobApp = FakeForeignClient(r.bob)
            // Public posts land faster than one SYNC round trip drains them: the drain alone would never end.
            val flood =
                backgroundScope.launch {
                    var n = 0
                    while (true) {
                        bobApp.send(
                            requireNotNull(MeshcoreProto.sendChannelText(0, n.toLong(), "p${n++}".encodeToByteArray(), r.bob.advertName)),
                        )
                        kotlinx.coroutines.delay(10)
                    }
                }
            advanceTimeBy(1_000)
            val t0 = testScheduler.currentTime
            val result = r.dm()
            assertTrue("$result", result is SendResult.Queued)
            assertTrue("answered in ${testScheduler.currentTime - t0} ms", testScheduler.currentTime - t0 < 5_000)
            advanceTimeBy(40_000)
            runCurrent()
            flood.cancel()
            assertEquals("every retry still fired", listOf(0, 1, 2), r.sendTexts().map { it[2].toInt() })
            assertTrue(r.outcomes.single() is PacketOutcome.Unreachable)
        }

    @Test
    fun theWriteRefusesEveryCommandOutsideTheAllowlist() {
        listOf(23, 24, 37, 11, 65, 66, 0x80).forEach { assertFalse("$it", MeshcoreSession.commandAllowed(byteArrayOf(it.toByte(), 0))) }
        assertFalse(MeshcoreSession.commandAllowed(ByteArray(0)))
        MeshcoreProto.ENCODED_COMMANDS.forEach { assertTrue("$it", MeshcoreSession.commandAllowed(byteArrayOf(it.toByte()))) }
    }

    // --- the third review's batch: a started op keeps its result ---

    @Test
    fun aSendWhosePinOutlastsTheCallDeadlineStillReturnsItsFlightsHandle() =
        runTest {
            val r = rig(pinned = false, taught = false, callDeadlineMs = 2_000)
            up(r)
            r.session.setKnitContacts(listOf(r.bob.key))
            // The pin's reads answer late: the op has started, so the caller waits for it rather than answering a timeout.
            r.ch.delayFrame = { f -> if (f[0].toInt() == MeshcoreProto.RESP_CONTACT) 2_500L else 0L }
            val result = r.dm()
            assertTrue("$result", result is SendResult.Queued)
            advanceTimeBy(5_000)
            runCurrent()
            val acked = r.outcomes.single() as PacketOutcome.Acked
            assertEquals("the core and the session share one id", (result as SendResult.Queued).id, acked.id)
        }

    /**
     * A send the actor never started by its call deadline is `Busy` (Stage 5a-1's carry-forward must-fix), so the core
     * requeues it: a `Timeout` would read as "likely aired" and wait out a DM that never left. `Timeout` is kept for a
     * started attempt whose `SENT` never came.
     */
    @Test
    fun aSendNotStartedByItsDeadlineIsBusyAndNeverRuns() =
        runTest {
            val r = rig(callDeadlineMs = 2_000)
            up(r)
            r.ch.delayFrame = { f -> if (f[0].toInt() == MeshcoreProto.RESP_CHANNEL_INFO) 2_500L else 0L }
            val setup = async { r.session.setup() }
            runCurrent() // the setup holds the actor, reading slots slowly
            val result = r.dm()
            assertEquals("never started: the core requeues it", SendResult.Busy, result)
            assertTrue(setup.await() is MeshcoreSetupResult.Done)
            runCurrent()
            assertTrue("never started late", r.sendTexts().isEmpty())
            assertTrue("no flight was opened for it", r.outcomes.isEmpty())
        }

    /**
     * A write the GATT client refuses as over `mtu − 3` refuses that one command (Stage 5a-1's carry-forward must-fix):
     * the send answers `TooLarge`, the session stays Ready and nothing is redialled — before, it ended the session into the
     * back-off loop.
     */
    @Test
    fun aWriteTooLongForTheLinkRefusesThatCommandAloneAndTheSessionStaysUp() =
        runTest {
            val r = rig()
            up(r)
            val dials = r.dialer.modes.size
            r.ch.writeResult = { f ->
                if (f[0].toInt() ==
                    MeshcoreProto.CMD_SEND_TXT_MSG
                ) {
                    GattResult.Failed(MeshcoreSession.WRITE_TOO_LONG)
                } else {
                    GattResult.Ok(Unit)
                }
            }
            assertEquals(SendResult.TooLarge, r.dm())
            assertEquals(
                1L,
                r.session.counters.commandsRefused
                    .get(),
            )
            advanceTimeBy(10_000)
            runCurrent()
            assertTrue("still Ready", r.session.state.value is BoardState.Ready)
            assertEquals("nothing redialled", dials, r.dialer.modes.size)
            r.ch.writeResult = { GattResult.Ok(Unit) }
            assertTrue("the next send goes", r.dm() is SendResult.Queued)
        }

    /**
     * `SEND_CHANNEL_DATA`'s `NOT_FOUND` is a slot index the board no longer has, never a missing contact: a room post
     * refused so reads `SLOT_NOT_KNIT` (classed `OTHER`, never `OWN_NO_KEY`), and the slot is read again before the next.
     */
    @Test
    fun aRoomPostsNotFoundIsTheSlotNeverAContact() =
        runTest {
            val r = rig()
            up(r)
            r.ch.swallow = { f ->
                (f[0].toInt() == MeshcoreProto.CMD_SEND_CHANNEL_DATA).also {
                    if (it) r.ch.inject(byteArrayOf(MeshcoreProto.RESP_ERR.toByte(), MeshcoreProto.ERR_NOT_FOUND.toByte()))
                }
            }
            val result = r.session.send(ByteArray(40) { 1 }, SendPurpose.KnitFrame(1)).result
            assertTrue("$result", result is SendResult.Rejected && result.res == MeshcoreRefusal.SLOT_NOT_KNIT)
            assertEquals(RefusalClass.OTHER, MeshcoreRefusal.classOf(result))
            assertNull("read again before the next post", r.session.slotReading())
        }

    @Test
    fun aSetupSlowedPastItsDeadlineStillReturnsItsRecord() =
        runTest {
            val r = rig(knitSlot = false, setupDeadlineMs = 5_000)
            up(r)
            r.ch.delayFrame = { f -> if (f[0].toInt() == MeshcoreProto.RESP_CHANNEL_INFO) 2_500L else 0L }
            val done = r.session.setup() as MeshcoreSetupResult.Done
            assertEquals("alice", done.record.priorName?.decodeToString())
            assertEquals(1, done.slot)
        }

    @Test
    fun aFullPacketBacklogDropsTheOldestAndCountsIt() =
        runTest {
            val r = rig(collectPackets = false)
            up(r)
            repeat(MeshcoreSession.PACKET_BACKLOG + 6) { n ->
                val data = MeshcoreProto.u32(n.toLong())
                r.ch.inject(byteArrayOf(27, 32, 0, 0, 1, 0, 0xFF.toByte(), 0xFF.toByte(), data.size.toByte()) + data)
            }
            runCurrent()
            assertEquals(
                6L,
                r.session.counters.packetsDropped
                    .get(),
            )
            val first =
                withTimeout(5_000) {
                    r.session.packets
                        .take(1)
                        .toList()
                }.single()
            assertEquals(6L, MeshcoreProto.u32(first.payload, 0))
        }

    @Test
    fun twoErrsAndNoSuccessBeforeTheBarrierAreAmbiguousNeverARefusal() =
        runTest {
            val r = rig(pinned = false, taught = false)
            up(r)
            r.session.readContact(r.bob.key)
            r.ch.swallow = { it[0].toInt() == MeshcoreProto.CMD_GET_CONTACT_BY_KEY }
            r.ch.writeResult = { f ->
                if (f[0].toInt() == MeshcoreProto.CMD_GET_CONTACT_BY_KEY) {
                    r.ch.inject(byteArrayOf(1, 2))
                    r.ch.inject(byteArrayOf(1, 2))
                }
                GattResult.Ok(Unit)
            }
            assertEquals(ContactRead.Failed, r.session.readContact(r.bob.key))
            assertEquals(
                "one judgement, one ambiguity",
                1L,
                r.session.counters.ambiguousAnswers
                    .get(),
            )
        }

    // --- the reconnect loop's dials (Stage 5a-2's bluetooth review) ---

    @Test
    fun anUnbondedBoardNeverPresentGetsItsDirectDialsThenOnlyTheHourlyNetNeverABackgroundStandIn() =
        runTest {
            val r = rig()
            r.dialer.bond = app.getknit.knit.mesh.lora.BondState.NONE
            r.dialer.dialResult = { NusDialResult.Timeout }
            r.session.start("AA:BB")
            runCurrent()
            val hours = 3
            advanceTimeBy(hours * app.getknit.knit.mesh.lora.BoardDialPolicy.DIRECT_NET_MS)
            runCurrent()
            assertTrue(
                "never a background dial of an unbonded board",
                r.dialer.modes.all { it == app.getknit.knit.mesh.lora.DialMode.Direct },
            )
            val attempts = app.getknit.knit.mesh.lora.BoardDialPolicy.DIRECT_ATTEMPTS
            assertTrue("${r.dialer.modes.size} dials: at most the first three and one an hour", r.dialer.modes.size <= attempts + hours + 1)
            assertTrue("the hourly net kept dialling (${r.dialer.modes.size})", r.dialer.modes.size >= attempts + hours - 1)
        }

    @Test
    fun aBoardWithoutTheNusServiceTwiceInARowEndsTerminal() =
        runTest {
            val r = rig()
            r.dialer.dialResult = { NusDialResult.ServiceAbsent }
            r.session.start("AA:BB")
            runCurrent()
            advanceTimeBy(600_000)
            runCurrent()
            assertEquals("backed off once, then terminal", 2, r.dialer.modes.size)
            assertEquals(MeshcoreLinkState.StaleBond("AA:BB"), r.session.state.value.detail)
        }

    // --- Stage 5b-2: the taught set, the reconcile, the erase ---

    /** `NO_RECORD`, under the send's own handle (lora N2), so the core acts on every refusal. */
    private fun SendResult.isNoRecord(): Boolean = this is SendResult.Rejected && res == MeshcoreRefusal.NO_RECORD && id != 0u

    private fun Rig.knitBoard() {
        alice.advertName = MeshcoreNames.forKey(alice.key).encodeToByteArray()
    }

    private fun Rig.listings(): Int = ch.writes.count { it[0].toInt() == MeshcoreProto.CMD_GET_CONTACTS && MeshcoreProto.u32(it, 1) == 0L }

    @Test
    fun aContactTheBoardHoldsThatKnitDidNotTeachIsNeverPinnedOrSentTo() =
        runTest {
            val r = rig(pinned = false, taught = false)
            up(r)
            assertTrue("a read of it", r.session.readContact(r.bob.key) is ContactRead.Found)
            val result = r.dm()
            assertTrue("a read never feeds the send map (security B1): $result", result.isNoRecord())
            assertEquals("the user's own route stands", MeshcoreProto.PATH_UNKNOWN, r.alice.contact(r.bob.key)?.outPathLen)
            assertTrue(r.ch.writes.none { it[0].toInt() == MeshcoreProto.CMD_ADD_UPDATE_CONTACT })
            assertTrue(r.sendTexts().isEmpty())
        }

    @Test
    fun theReadyReconcileMakesAKnitBoardsTableTheTaughtSetFromOneListing() =
        runTest {
            val r = rig(taught = false)
            r.knitBoard()
            val carol = r.air.board("carol")
            val dave = r.air.board("dave")
            val erin = r.air.board("erin")
            r.alice.addContact(carol, name = "Carol Smith", flags = 0) // not taught: removed
            r.alice.addContact(dave, outPathLen = 3, name = "Dave") // taught, not Knit's record: overwritten
            r.session.setKnitContacts(listOf(r.bob.key, dave.key, erin.key)) // erin is not on the board: written
            up(r)
            advanceTimeBy(30_000)
            runCurrent()
            assertNull(r.alice.contact(carol.key))
            val d = requireNotNull(r.alice.contact(dave.key))
            assertEquals(MeshcoreNames.forKey(dave.key), d.name.decodeToString())
            assertEquals(0, d.outPathLen)
            assertEquals(MeshcoreProto.CONTACT_FLAG_FAVOURITE, d.flags)
            assertEquals(0, r.alice.contact(erin.key)?.outPathLen)
            assertNotNull("bob was Knit's already", r.alice.contact(r.bob.key))
            assertEquals("one listing a Ready, never one a key", 1, r.listings())
            assertEquals("no count read either", 1, r.ch.codes.count { it == MeshcoreProto.CMD_GET_CONTACTS })
            val lines = r.logs.joinToString("\n")
            for (board in listOf(r.bob, carol, dave, erin)) {
                assertFalse("no Knit name in a line", lines.contains(MeshcoreNames.forKey(board.key)))
                assertFalse("no prefix in a line", lines.contains(board.key.copyOf(6).joinToString("") { "%02x".format(it) }))
            }
            assertTrue(lines.contains("meshcore reconcile listed=3 "))
            val c = r.session.counters
            assertEquals(1L, c.reconcileRemoved.get())
            assertEquals(1L, c.reconcileOverwritten.get())
            assertEquals(1L, c.reconcileTaught.get())
            // bob read back as Knit's record in the listing: pinned as read, sent to with no further read.
            val before = r.ch.writes.size
            assertTrue(r.dm() is SendResult.Queued)
            val codes =
                r.ch.writes
                    .drop(before)
                    .map { it[0].toInt() }
            assertTrue(
                "$codes",
                codes.takeWhile { it != MeshcoreProto.CMD_SEND_TXT_MSG }.none { it == MeshcoreProto.CMD_GET_CONTACT_BY_KEY },
            )
        }

    @Test
    fun theReconcileWaitsForTheTaughtSetSoAnEarlyReadyRemovesNothing() =
        runTest {
            val r = rig(taught = false)
            r.knitBoard()
            val carol = r.air.board("carol")
            r.alice.addContact(carol, name = "Carol Smith", flags = 0)
            up(r)
            advanceTimeBy(30_000)
            runCurrent()
            assertNotNull("no taught set yet: nothing removed", r.alice.contact(carol.key))
            assertEquals(0, r.listings())
            r.session.setKnitContacts(listOf(r.bob.key))
            advanceTimeBy(30_000)
            runCurrent()
            assertNull(r.alice.contact(carol.key))
            assertEquals(1, r.listings())
        }

    @Test
    fun aBoardNotSetUpForKnitIsNeverReconciled() =
        runTest {
            val r = rig(knitBoard = false)
            val carol = r.air.board("carol")
            r.alice.addContact(carol, name = "Carol Smith", flags = 0)
            up(r)
            advanceTimeBy(30_000)
            runCurrent()
            assertNotNull(r.alice.contact(carol.key))
            assertEquals(0, r.listings())
            assertTrue(r.ch.writes.none { it[0].toInt() == MeshcoreProto.CMD_REMOVE_CONTACT })
        }

    @Test
    fun aListedContactSharingATaughtKeysPrefixThatWillNotGoKeepsThatPrefixFromSends() =
        runTest {
            val r = rig(taught = false)
            r.knitBoard()
            r.alice.contacts.clear()
            val mallory =
                r.air.board(
                    "mallory",
                    key =
                        r.bob.key
                            .copyOf()
                            .also { it[31] = (it[31] + 1).toByte() },
                )
            r.alice.addContact(mallory, name = "Mallory")
            up(r)
            // The reconcile waits for the taught set: mallory's removal never reads back.
            r.ch.swallow = { it[0].toInt() == MeshcoreProto.CMD_REMOVE_CONTACT }
            r.session.setKnitContacts(listOf(r.bob.key))
            advanceTimeBy(60_000)
            runCurrent()
            assertNotNull(r.alice.contact(mallory.key))
            assertNull("bob is not written beside a prefix sharer", r.alice.contact(r.bob.key))
            assertTrue(r.dm().isNoRecord())
            assertTrue(r.sendTexts().isEmpty())
            assertTrue(
                r.session.counters.prefixContests
                    .get() >= 1,
            )
        }

    @Test
    fun setupErasesTheBoardBeforeItsWritesAndNeverFactoryResets() =
        runTest {
            val r = rig(knitSlot = false)
            val carol = r.air.board("carol")
            r.alice.addContact(carol, name = "Carol Smith", flags = 0)
            r.alice.setChannel(2, "family", ByteArray(16) { 7 })
            up(r)
            val before = r.ch.writes.size
            val done = r.session.setup() as MeshcoreSetupResult.Done
            assertEquals("carol is erased; bob's contact is Knit's record for a taught key, and kept", 1, done.erasedContacts)
            assertEquals(1, done.clearedSlots)
            assertNull(r.alice.contact(carol.key))
            assertNotNull(r.alice.contact(r.bob.key))
            assertTrue(
                r.alice.channels[2]
                    .first
                    .isEmpty(),
            )
            assertEquals(
                "Public",
                r.alice.channels[0]
                    .first
                    .decodeToString(),
            )
            val codes =
                r.ch.writes
                    .drop(before)
                    .map { it[0].toInt() }
            val firstRemove = codes.indexOf(MeshcoreProto.CMD_REMOVE_CONTACT)
            val firstWrite = codes.indexOf(MeshcoreProto.CMD_SET_OTHER_PARAMS)
            assertTrue("$codes", firstRemove in 0 until firstWrite)
            assertTrue("the reads come first", codes.indexOf(MeshcoreProto.CMD_GET_AUTOADD_CONFIG) < firstRemove)
            assertTrue("$codes", codes.indexOf(MeshcoreProto.CMD_SET_CHANNEL) < firstWrite)
            assertFalse("CMD_FACTORY_RESET is never encodable", CMD_FACTORY_RESET in MeshcoreProto.ENCODED_COMMANDS)
            assertTrue(r.ch.codes.none { it == CMD_FACTORY_RESET })
        }

    @Test
    fun restoreRemovesKnitsContactsAndPutsBackTheRecordButNotWhatSetupErased() =
        runTest {
            val r = rig(knitSlot = false)
            val carol = r.air.board("carol")
            r.alice.addContact(carol, name = "Carol Smith", flags = 0)
            r.alice.setChannel(2, "family", ByteArray(16) { 7 })
            up(r)
            val done = r.session.setup() as MeshcoreSetupResult.Done
            advanceTimeBy(30_000)
            runCurrent()
            val back = r.session.restore(done.record) as MeshcoreRestoreResult.Done
            assertTrue("${back.failed}", back.failed.isEmpty())
            assertEquals(1, back.contactsRemoved)
            assertTrue("no contact is left", r.alice.contacts.isEmpty())
            assertEquals("alice", r.alice.advertName.decodeToString())
            assertTrue(
                "the slot is cleared",
                r.alice.channels[1]
                    .first
                    .isEmpty(),
            )
            assertTrue(
                "what the erase removed stays removed",
                r.alice.channels[2]
                    .first
                    .isEmpty(),
            )
            // Set up again with no new taught set: had the Restore kept the old one, the reconcile would teach bob back.
            assertTrue(r.session.setup() is MeshcoreSetupResult.Done)
            advanceTimeBy(30_000)
            runCurrent()
            assertNull("the taught set is forgotten", r.alice.contact(r.bob.key))
            assertTrue(r.dm().isNoRecord())
        }

    /** Alice's own contact for bob as the firmware writes it: its frame, to compare byte for byte. */
    private fun Rig.bobRecord(): List<Any> {
        val c = requireNotNull(alice.contact(bob.key))
        return listOf(c.type, c.flags, c.outPathLen, c.name.toList(), c.lastmod)
    }

    private fun Rig.noContactWrites(from: Int) =
        ch.writes.drop(from).none {
            it[0].toInt() in setOf(MeshcoreProto.CMD_ADD_UPDATE_CONTACT, MeshcoreProto.CMD_REMOVE_CONTACT, MeshcoreProto.CMD_RESET_PATH)
        }

    /**
     * The 5b-2 review's Blocking finding: "taught" is the policy's wish, so on a board Knit never set up (no Knit slot and
     * name) the user's own contact for a taught key must survive a DM, the Ready re-assert, a `0x81` re-pin, a reactive teach
     * and the key leaving the taught set — byte for byte, with no contact write at all.
     */
    @Test
    fun aBoardNotSetUpForKnitKeepsTheUsersContactForATaughtKeyByteForByte() =
        runTest {
            val r = rig(knitSlot = false)
            r.alice.contacts.clear()
            r.alice.addContact(r.bob, outPathLen = 3, name = "Bob", flags = 0)
            val before = r.bobRecord()
            up(r) // taught bob: the Ready re-assert
            advanceTimeBy(30_000)
            runCurrent()
            assertTrue("a DM is refused unwritten", r.dm().isNoRecord())
            r.ch.inject(byteArrayOf(0x81.toByte()) + r.bob.key) // a path push for the taught key
            advanceTimeBy(120_000)
            runCurrent()
            assertEquals(ContactWrite.NOT_SET_UP, r.session.teach(r.bob.key))
            r.session.setKnitContacts(emptyList())
            assertEquals(ContactWrite.NOT_SET_UP, r.session.removeUntaught(r.bob.key))
            advanceTimeBy(30_000)
            runCurrent()
            assertEquals("the user's record, byte for byte", before, r.bobRecord())
            assertTrue("no contact write of Knit's", r.noContactWrites(0))
            assertTrue(r.sendTexts().isEmpty())
        }

    @Test
    fun afterARestoreAndANewSessionTheUsersContactIsNeverTouched() =
        runTest {
            val r = rig(knitSlot = false)
            up(r)
            val done = r.session.setup() as MeshcoreSetupResult.Done
            advanceTimeBy(30_000)
            runCurrent()
            assertTrue((r.session.restore(done.record) as MeshcoreRestoreResult.Done).failed.isEmpty())
            // The user adds bob back by hand, then the process restarts: a new session, taught bob again.
            r.alice.addContact(r.bob, outPathLen = 3, name = "Bob", flags = 0)
            val before = r.bobRecord()
            r.session.stop()
            val fresh =
                MeshcoreSession(
                    dialer = r.dialer,
                    scope = backgroundScope,
                    now = { testScheduler.currentTime },
                    wallClock = { epochMs + testScheduler.currentTime },
                )
            fresh.setKnitContacts(listOf(r.bob.key))
            fresh.start("AA:BB")
            runCurrent()
            advanceTimeBy(30_000)
            runCurrent()
            val from = r.ch.writes.size
            val dm = fresh.send(seal, SendPurpose.DirectDm(1, r.bobAddr)).result
            assertTrue("$dm", dm.isNoRecord())
            r.ch.inject(byteArrayOf(0x81.toByte()) + r.bob.key)
            advanceTimeBy(120_000)
            runCurrent()
            assertEquals(ContactWrite.NOT_SET_UP, fresh.teach(r.bob.key))
            fresh.setKnitContacts(emptyList())
            assertEquals(ContactWrite.NOT_SET_UP, fresh.removeUntaught(r.bob.key))
            assertEquals(before, r.bobRecord())
            assertTrue(r.noContactWrites(from))
        }

    /** A `CONTACT` frame as the board writes it, for a key of its own. */
    private fun contactFrame(n: Int): ByteArray {
        val out = ByteArray(MeshcoreProto.CONTACT_FULL_SIZE)
        out[0] = MeshcoreProto.RESP_CONTACT.toByte()
        FakeMeshcoreAir.keyOf("stray$n").copyInto(out, 1)
        out[33] = MeshcoreProto.ADV_TYPE_CHAT.toByte()
        return out
    }

    /** A Knit board whose listing (`GET_CONTACTS` since 0) answers only `CONTACTS_START` [count]; the test feeds the rest. */
    private fun TestScope.listingRig(count: Long): Rig {
        val r = rig(taught = false)
        r.alice.addContact(r.air.board("carol"), name = "Carol Smith", flags = 0)
        up(r)
        val listing = { f: ByteArray -> f[0].toInt() == MeshcoreProto.CMD_GET_CONTACTS && MeshcoreProto.u32(f, 1) == 0L }
        r.ch.swallow = listing
        r.ch.writeResult = { f ->
            if (listing(f)) r.ch.inject(byteArrayOf(MeshcoreProto.RESP_CONTACTS_START.toByte()) + MeshcoreProto.u32(count))
            GattResult.Ok(Unit)
        }
        return r
    }

    private fun Rig.assertListingCutAndNothingRemoved() {
        assertEquals(1L, session.counters.listingsShort.get())
        assertEquals(1L, session.counters.reconcilesSkipped.get())
        assertEquals(0L, session.counters.reconciles.get())
        assertTrue(ch.writes.none { it[0].toInt() == MeshcoreProto.CMD_REMOVE_CONTACT })
        assertNotNull(alice.contacts.firstOrNull { it.name.decodeToString() == "Carol Smith" })
    }

    /** Security's 5b-2 follow-up: a board that streams `CONTACT` frames past its count and never ends the listing. */
    @Test
    fun aListingPastItsCountAndTheSlackEndsShortAndRemovesNothing() =
        runTest {
            val r = listingRig(count = 1)
            r.ch.writeResult.let { inner ->
                r.ch.writeResult = { f ->
                    val res = inner(f)
                    if (f[0].toInt() == MeshcoreProto.CMD_GET_CONTACTS) repeat(40) { r.ch.inject(contactFrame(it)) }
                    res
                }
            }
            r.session.setKnitContacts(listOf(r.bob.key))
            advanceTimeBy(30_000)
            runCurrent()
            r.assertListingCutAndNothingRemoved()
        }

    /** The same, the frames trickling in under the per-frame timeout for longer than the listing's deadline. */
    @Test
    fun aListingThatTricklesPastItsDeadlineEndsShortAndRemovesNothing() =
        runTest {
            val r = listingRig(count = 1_000)
            val trickle =
                backgroundScope.launch {
                    // A period that does not divide the 60-s deadline: the listing's last wait is clipped below the gap.
                    repeat(70) { n ->
                        delay(1_700)
                        r.ch.inject(contactFrame(n))
                    }
                }
            r.session.setKnitContacts(listOf(r.bob.key))
            advanceTimeBy(90_000)
            runCurrent()
            trickle.cancel()
            r.assertListingCutAndNothingRemoved()
        }

    private companion object {
        /** `CMD_FACTORY_RESET` (`MyMesh.cpp`): formats the board, a new identity and the radio reset (ADR 045). */
        const val CMD_FACTORY_RESET = 51
    }
}
