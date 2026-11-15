package app.getknit.knit.mesh.lora.meshcore

import app.getknit.knit.mesh.bluetooth.BackoffConfig
import app.getknit.knit.mesh.lora.AckProof
import app.getknit.knit.mesh.lora.BoardAddr
import app.getknit.knit.mesh.lora.BoardPacket
import app.getknit.knit.mesh.lora.BoardState
import app.getknit.knit.mesh.lora.BondState
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
        val records: MeshcoreSetupJournal,
        val consents: MutableList<MeshcoreConsent>,
        val nonces: MutableList<ByteArray>,
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
        // This phone's setup record for the board (part 2's consent gate): the record, not the board's own word, is consent.
        consented: Boolean = knitBoard,
        // The session's setup records: a fresh journal holding a record of alice's board when [consented].
        records: MeshcoreSetupJournal = MemoryMeshcoreSetupJournal(),
        taught: Boolean = true,
        collectPackets: Boolean = true,
        callDeadlineMs: Long = 30_000,
        setupDeadlineMs: Long = 300_000,
    ): Rig {
        val air = FakeMeshcoreAir(backgroundScope) { testScheduler.currentTime }
        val alice = air.board("alice")
        val bob = air.board("bob")
        val nonces = mutableListOf<ByteArray>()
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
                records = records,
                nonce = { ByteArray(32) { n -> (nonces.size * 31 + n).toByte() }.also { nonces += it } },
            )
        // This phone set the board up before: its record, which the handshake reads as consent (one owner, part 2).
        if (consented) {
            kotlinx.coroutines.runBlocking {
                records.put(
                    "AA:BB",
                    MeshcoreHooks.digest(alice.key),
                    SETUP_RECORD,
                    pending = false,
                )
            }
        }
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
        val consents = mutableListOf<MeshcoreConsent>()
        backgroundScope.launch(collect) { session.consent.collect { it?.let(consents::add) } }
        return Rig(air, alice, bob, dialer, session, outcomes, packets, airs, headroom, logs, records, consents, nonces)
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
    fun aRetryHeldBehindAFullAckTableRepinsOnceNotEveryTick() =
        runTest {
            // 5c-2a round 2: an attempt whose ack window lapses re-pins before the next — once. Held behind a full table, the
            // retry waits on every tick, and none of those ticks reads the record again.
            val r = rig()
            up(r)
            r.air.loseAck = { true }
            val reads = {
                r.ch.writes.count {
                    it[0].toInt() == MeshcoreProto.CMD_GET_CONTACT_BY_KEY &&
                        it
                            .copyOfRange(
                                1,
                                33,
                            ).contentEquals(r.bob.key)
                }
            }
            assertTrue(r.dm() is SendResult.Queued)
            advanceTimeBy(500)
            runCurrent()
            // Another client's eight sends, each owed for a minute: the board's table is full past our attempt's window.
            r.air.estDirectMs = 60_000
            val app = FakeForeignClient(r.alice)
            repeat(8) {
                app.send(requireNotNull(MeshcoreProto.sendText(r.bob.key.copyOf(6), 0, 1_790_000_700L + it, "x$it".encodeToByteArray())))
            }
            runCurrent()
            val before = reads()
            advanceTimeBy(20_000)
            runCurrent()
            assertEquals("one re-pin for the lapsed attempt", before + 1, reads())
            assertEquals("and no retry while the table is full", 1, r.sendTexts().size)
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

            val back = r.session.restore() as MeshcoreRestoreResult.Done
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
            val back = r.session.restore() as MeshcoreRestoreResult.Done
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
            assertTrue(r.session.setup() is MeshcoreSetupResult.Done)
            advanceTimeBy(30_000)
            runCurrent()
            val back = r.session.restore() as MeshcoreRestoreResult.Done
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
            assertTrue(r.session.setup() is MeshcoreSetupResult.Done)
            advanceTimeBy(30_000)
            runCurrent()
            assertTrue(
                (r.session.restore() as MeshcoreRestoreResult.Done).failed.isEmpty(),
            )
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

    /**
     * Part 2's consent gate: a board another client (or another Knit install) dressed up with Knit's slot and `Knit abcd`
     * name, which this phone holds no setup record for, is never touched — no removal, overwrite, pin, path reset or DM, the
     * user's records byte for byte; the same board with the record is reconciled as a Knit board.
     */
    @Test
    fun aDressedUpBoardWithNoSetupRecordIsNeverTouchedAndTheRecordMakesItKnits() =
        runTest {
            val r = rig(consented = false)
            val carol = r.air.board("carol")
            r.alice.addContact(carol, name = "Carol Smith", flags = 0)
            r.alice.contacts.removeAll { it.key.contentEquals(r.bob.key) }
            r.alice.addContact(r.bob, outPathLen = 3, name = "Bob", flags = 0)
            val before = r.alice.contacts.map { listOf(it.key.toList(), it.type, it.flags, it.outPathLen, it.name.toList(), it.lastmod) }
            up(r)
            advanceTimeBy(30_000)
            runCurrent()
            assertTrue(r.dm().isNoRecord())
            r.ch.inject(byteArrayOf(0x81.toByte()) + r.bob.key)
            advanceTimeBy(120_000)
            runCurrent()
            assertEquals(ContactWrite.NOT_SET_UP, r.session.teach(r.bob.key))
            assertEquals(ContactWrite.NOT_SET_UP, r.session.resetPath(r.bob.key))
            assertEquals(ContactWrite.NOT_SET_UP, r.session.removeContact(carol.key))
            val after = r.alice.contacts.map { listOf(it.key.toList(), it.type, it.flags, it.outPathLen, it.name.toList(), it.lastmod) }
            assertEquals("the user's records, byte for byte", before, after)
            assertTrue(r.noContactWrites(0))
            assertEquals("no listing without consent", 0, r.listings())
            assertTrue(r.sendTexts().isEmpty())
            // The record arrives (this phone set the board up before): read at the next connection, the board is Knit's.
            r.records.put("AA:BB", MeshcoreHooks.digest(r.alice.key), SETUP_RECORD, pending = false)
            r.ch.disconnect()
            advanceTimeBy(30_000)
            runCurrent()
            assertEquals(1, r.listings())
            assertNull("carol is not taught: removed", r.alice.contact(carol.key))
            assertTrue(
                "bob is overwritten to Knit's record",
                MeshcoreNames.forKey(r.bob.key) ==
                    r.alice
                        .contact(r.bob.key)
                        ?.name
                        ?.decodeToString(),
            )
        }

    // --- consent has one owner: the session's actor (the maintainer's decision, 2026-11-13) ---

    /** A journal that counts its reads, and can be made to fail them. */
    private class CountingJournal : MeshcoreSetupJournal {
        val inner = MemoryMeshcoreSetupJournal()
        var reads = 0
        var failReads = false
        var failFirstReads = 0
        var slowReads = false
        var failWrites = false
        var failClears = false
        var failPuts = false
        var failMarks = false

        /** Puts that succeed before [failPuts] behaviour starts (a write-ahead lands, the grant after it fails); -1: none. */
        var putsBeforeFailing = -1

        /** Reads that suspend on [gate] (after reading the inner entry) until it completes; [holding] while one waits. */
        var holdReads = 0
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        var holding = false

        override suspend fun entryOf(address: String): MeshcoreSetupEntry? {
            reads++
            if (failReads) error("SQLCipher is not open")
            if (failFirstReads > 0) {
                failFirstReads--
                error("SQLCipher is not open")
            }
            if (slowReads) delay(60_000)
            val entry = inner.entryOf(address)
            if (holdReads > 0) {
                holdReads--
                holding = true
                gate.await()
                holding = false
            }
            return entry
        }

        /** The put [holdPut] counts down to suspends on [putGate], after its row landed when [holdLanded]; [putHolding] meanwhile. */
        var holdPut = -1
        var holdLanded = false
        val putGate = kotlinx.coroutines.CompletableDeferred<Unit>()
        var putHolding = false

        override suspend fun put(
            address: String,
            digest: String,
            record: MeshcoreSetupRecord,
            pending: Boolean,
        ) {
            if (putsBeforeFailing == 0) error("SQLCipher is not open")
            if (putsBeforeFailing > 0) putsBeforeFailing--
            if (failWrites || failPuts) error("SQLCipher is not open")
            val hold = holdPut == 0
            if (holdPut >= 0) holdPut--
            if (hold && !holdLanded) holdOnGate()
            inner.put(address, digest, record, pending)
            if (hold && holdLanded) holdOnGate()
        }

        private suspend fun holdOnGate() {
            putHolding = true
            putGate.await()
            putHolding = false
        }

        override suspend fun markPending(address: String): Boolean {
            if (failWrites || failMarks) error("SQLCipher is not open")
            return inner.markPending(address)
        }

        override suspend fun clear(address: String) {
            if (failWrites || failClears) error("SQLCipher is not open")
            inner.clear(address)
        }
    }

    @Test
    fun aRestoreClearsTheRecordAndTheConsentAndANewReadyNeverGrantsItAgain() =
        runTest {
            val r = rig(knitSlot = false)
            up(r)
            r.session.setup()
            assertTrue(r.consents.last().granted)
            assertTrue((r.session.restore() as MeshcoreRestoreResult.Done).failed.isEmpty())
            assertNull(r.records.entryOf("AA:BB"))
            assertFalse(r.consents.last().granted)
            r.ch.disconnect()
            advanceTimeBy(30_000)
            runCurrent()
            assertFalse("the next Ready reads no record", r.consents.last().granted)
            assertEquals("never granted again", 1, r.consents.count { it.granted })
            assertEquals(MeshcoreRestoreResult.NoRecord, r.session.restore())
        }

    @Test
    fun anUnbindClearsTheRecordWithTheBoardUpAndLeavesItPendingWithTheBoardAway() =
        runTest {
            val r = rig()
            up(r)
            assertEquals(MeshcoreUnbind.CLEARED, r.session.unbind("AA:BB"))
            assertNull(r.records.entryOf("AA:BB"))
            assertFalse(r.consents.last().granted)
            assertEquals(MeshcoreUnbind.NOTHING, r.session.unbind("AA:BB"))
            // Away: the session is stopped, the record of the board stays as a pending cleanup.
            r.records.put("AA:BB", MeshcoreHooks.digest(r.alice.key), SETUP_RECORD, pending = false)
            r.session.stop()
            assertEquals(MeshcoreUnbind.PENDING, r.session.unbind("AA:BB"))
            assertTrue(checkNotNull(r.records.entryOf("AA:BB")).pending)
            // The board comes back: no consent, and the cleanup runs as the connection's first job.
            up(r)
            advanceTimeBy(30_000)
            runCurrent()
            assertFalse(r.consents.last().granted)
            assertTrue(r.logs.any { it.startsWith("lora cleanup kind=meshcore done=true") })
            assertNull(r.records.entryOf("AA:BB"))
        }

    @Test
    fun aRecordReadThatKeepsFailingPublishesReadyWithNoConsent() =
        runTest {
            val journal = CountingJournal().apply { failReads = true }
            val r = rig(records = journal)
            kotlinx.coroutines.runBlocking { journal.inner.put("AA:BB", MeshcoreHooks.digest(r.alice.key), SETUP_RECORD, false) }
            up(r)
            assertTrue("the plane is not stalled", r.session.state.value is BoardState.Ready)
            assertFalse(r.consents.single().granted)
            assertEquals(
                1L,
                r.session.counters.consentReadFailures
                    .get(),
            )
            assertEquals("three tries at start, three in the handshake", 6, journal.reads)
            assertTrue(
                "a room post never waits on consent",
                r.session.send(ByteArray(40) { 1 }, SendPurpose.KnitFrame(1)).result is SendResult.Queued,
            )
        }

    /** The maintainer's decision (2026-11-13): an unbonded board is inert for Knit — no consent, no Restore, no cleanup. */
    @Test
    fun anUnbondedBoardIsInertForKnit() =
        runTest {
            val r = rig()
            r.dialer.bond = BondState.NONE
            r.records.put("AA:BB", MeshcoreHooks.digest(r.alice.key), SETUP_RECORD, pending = true)
            up(r)
            advanceTimeBy(30_000)
            runCurrent()
            assertFalse(r.consents.last().granted)
            assertFalse("no cleanup", r.logs.any { it.startsWith("lora cleanup") })
            assertEquals(MeshcoreRestoreResult.Mismatch, r.session.restore())
            assertTrue(
                r.session.counters.unbondedInert
                    .get() >= 1,
            )
            assertTrue(checkNotNull(r.records.entryOf("AA:BB")).pending)
        }

    /** Security S1: a record of another board at this address is no consent, no Restore and no cleanup. */
    @Test
    fun aRecordForAnotherBoardAtTheAddressIsNeverActedOn() =
        runTest {
            val r = rig(consented = false)
            val other = MeshcoreHooks.digest(FakeMeshcoreAir.keyOf("someone else"))
            r.records.put("AA:BB", other, SETUP_RECORD, pending = true)
            up(r)
            advanceTimeBy(30_000)
            runCurrent()
            assertFalse(r.consents.last().granted)
            assertFalse(r.logs.any { it.startsWith("lora cleanup") })
            assertEquals(MeshcoreRestoreResult.Mismatch, r.session.restore())
            assertEquals(MeshcoreNames.forKey(r.alice.key), r.alice.advertName.decodeToString())
            assertTrue(checkNotNull(r.records.entryOf("AA:BB")).pending)
            assertEquals(
                1L,
                r.session.counters.consentMismatches
                    .get(),
            )
        }

    /** Lora L1: a second setup keeps the first's priors, so Restore puts back the original name. */
    @Test
    fun aSecondSetupKeepsTheFirstsPriorsAndRestorePutsBackTheOriginal() =
        runTest {
            val r = rig(knitSlot = false)
            up(r)
            r.session.setup()
            val again = r.session.setup() as MeshcoreSetupResult.Done
            assertFalse("the re-run changed nothing", again.record.changedAnything)
            val kept = checkNotNull(r.records.entryOf("AA:BB")).record
            assertEquals("alice", kept.priorName?.decodeToString())
            assertTrue(kept.slotWritten)
            assertTrue((r.session.restore() as MeshcoreRestoreResult.Done).failed.isEmpty())
            assertEquals("alice", r.alice.advertName.decodeToString())
        }

    // --- the signed challenge, and one writer of the record (the maintainer's decision, 2026-11-13; security B1, B2) ---

    /** Security B2: a clone that reports the real board's key (it is in its adverts) but cannot sign with it is inert. */
    @Test
    fun aCloneReportingTheRealKeyButUnableToSignGetsNoConsentRestoreCleanupOrTaughtSet() =
        runTest {
            val r = rig()
            r.alice.canSign = false
            val carol = r.air.board("carol")
            r.alice.addContact(carol, name = "Carol Smith", flags = 0)
            up(r)
            advanceTimeBy(30_000)
            runCurrent()
            assertFalse(r.consents.last().granted)
            assertTrue(
                r.session.counters.keyUnproven
                    .get() >= 1,
            )
            assertTrue("no taught set reaches it", r.dm().isNoRecord())
            val restore = r.session.restore()
            assertTrue(
                "a signature that fails is no answer, never a Restore: $restore",
                restore is MeshcoreRestoreResult.Done && "proof" in restore.failed,
            )
            assertTrue("never a setup on an unproven link", r.session.setup() is MeshcoreSetupResult.Failed)
            assertNotNull("nothing removed", r.alice.contact(carol.key))
            assertEquals(
                "alice's name is still Knit's: no prior name written to the clone",
                MeshcoreNames.forKey(r.alice.key),
                r.alice.advertName.decodeToString(),
            )
            // A pending cleanup is not run against it either.
            r.records.markPending("AA:BB")
            r.ch.disconnect()
            advanceTimeBy(30_000)
            runCurrent()
            assertFalse(r.logs.any { it.startsWith("lora cleanup") })
            assertTrue(checkNotNull(r.records.entryOf("AA:BB")).pending)
        }

    /** Each B1 interleaving ends the same: no consent, the claim withdrawn, and no contact write after the unbind returns. */
    private suspend fun TestScope.assertUnbound(r: Rig) {
        assertFalse(r.consents.last().granted)
        val after = r.ch.writes.size
        advanceTimeBy(120_000)
        runCurrent()
        val writes =
            r.ch.writes
                .drop(after)
                .map { it[0].toInt() }
        assertTrue("$writes", writes.none { it in setOf(MeshcoreProto.CMD_ADD_UPDATE_CONTACT, MeshcoreProto.CMD_REMOVE_CONTACT) })
        assertTrue(r.dm().isNoRecord() || r.session.state.value !is BoardState.Ready)
    }

    @Test
    fun anUnbindDuringASlowSetupWaitsForItAndThenUndoesIt() =
        runTest {
            val r = rig(knitSlot = false, setupDeadlineMs = 5_000)
            up(r)
            r.ch.delayFrame = { f -> if (f[0].toInt() == MeshcoreProto.RESP_CHANNEL_INFO) 2_500L else 0L }
            val setup = async { r.session.setup() }
            runCurrent()
            val unbind = async { r.session.unbind("AA:BB") }
            advanceTimeBy(120_000)
            runCurrent()
            assertTrue(setup.await() is MeshcoreSetupResult.Done)
            assertEquals("the actor ran it after the setup, held past the setup deadline", MeshcoreUnbind.CLEARED, unbind.await())
            assertNull(r.records.entryOf("AA:BB"))
            r.ch.delayFrame = { 0L }
            assertUnbound(r)
        }

    @Test
    fun anUnbindWithTheBoardAwayWithdrawsTheConsentAtOnceAndLeavesTheCleanupPending() =
        runTest {
            val r = rig()
            up(r)
            assertTrue(r.consents.last().granted)
            r.session.stop()
            assertEquals(MeshcoreUnbind.PENDING, r.session.unbind("AA:BB"))
            assertTrue(checkNotNull(r.records.entryOf("AA:BB")).pending)
            val last = r.consents.last()
            assertFalse(last.granted)
            assertNull("the claim's key is withdrawn with it", last.key)
            assertEquals("AA:BB", last.address)
        }

    /** Security N2: a failed read keeps the claim as it was, closes the offers, and is read again on the actor. */
    @Test
    fun aFailedRecordReadIsReadAgainLaterAndNeverWithdrawsTheClaim() =
        runTest {
            val journal = CountingJournal()
            val r = rig(records = journal)
            kotlinx.coroutines.runBlocking { journal.inner.put("AA:BB", MeshcoreHooks.digest(r.alice.key), SETUP_RECORD, false) }
            journal.failReads = true
            up(r)
            assertTrue("the claim is left as it was", r.consents.last().unread)
            assertFalse(r.consents.last().granted)
            journal.failReads = false
            advanceTimeBy(60_000)
            runCurrent()
            assertTrue("read again on the actor", r.consents.last().granted)
            assertFalse(r.consents.last().unread)
        }

    // --- withdraw only the bound board (the maintainer's decision, 2026-11-14), and the batch's follow-ups ---

    @Test
    fun anUnbindOfAnotherBoardsAddressLeavesTheReadyBoardsConsentAlone() =
        runTest {
            val r = rig()
            up(r)
            assertTrue(r.consents.last().granted)
            // Switched to board B (another address), set up there.
            r.session.start("CC:DD")
            runCurrent()
            advanceTimeBy(10_000)
            runCurrent()
            assertTrue(r.session.setup() is MeshcoreSetupResult.Done)
            val before = r.consents.last()
            assertEquals("CC:DD", before.address)
            assertTrue(before.granted)
            assertEquals(MeshcoreUnbind.PENDING, r.session.unbind("AA:BB"))
            assertTrue("A's record is pending", checkNotNull(r.records.entryOf("AA:BB")).pending)
            assertTrue("nothing published for A", r.consents.last() === before)
            assertTrue("B's DMs still go", r.dm() is SendResult.Queued)
        }

    /** Lora N1: a proof nobody answered is asked again later, never read as a clone. */
    @Test
    fun anUnansweredProofIsAskedAgainLaterAndNeverMakesTheBoardInert() =
        runTest {
            val r = rig()
            r.alice.dropSignFinish = 1
            up(r)
            assertTrue(r.consents.first().unread)
            assertFalse("an unanswered proof is never a consent", r.consents.first().granted)
            assertEquals(
                1L,
                r.session.counters.proofUnanswered
                    .get(),
            )
            assertEquals(
                0L,
                r.session.counters.keyUnproven
                    .get(),
            )
            advanceTimeBy(60_000)
            runCurrent()
            assertTrue("granted at the re-ask", r.consents.last().granted)
        }

    /** Lora N3: an unbind whose mark did not land says so, so the binding stays and it is asked again. */
    @Test
    fun anUnbindWhoseMarkFailsAnswersFailed() =
        runTest {
            val journal = CountingJournal()
            val r = rig(records = journal)
            kotlinx.coroutines.runBlocking { journal.inner.put("AA:BB", MeshcoreHooks.digest(r.alice.key), SETUP_RECORD, false) }
            up(r)
            r.session.stop()
            journal.failWrites = true
            assertEquals(MeshcoreUnbind.FAILED, r.session.unbind("AA:BB"))
            assertFalse("still withdrawn for the bound board", r.consents.last().granted)
        }

    /** Security S1: a board that never answers the proof is never treated as proven, whatever path it takes. */
    @Test
    fun aBoardThatNeverAnswersTheProofGetsNoConsentRestoreSetupOrCleanup() =
        runTest {
            val r = rig()
            r.alice.dropSignFinish = Int.MAX_VALUE
            val carol = r.air.board("carol")
            r.alice.addContact(carol, name = "Carol Smith", flags = 0)
            up(r)
            advanceTimeBy(30_000)
            runCurrent()
            assertFalse(r.consents.last().granted)
            assertTrue("no taught set reaches it", r.dm().isNoRecord())
            val restore = r.session.restore()
            assertTrue("no answer, never a Restore: $restore", restore is MeshcoreRestoreResult.Done && "proof" in restore.failed)
            assertTrue(r.session.setup() is MeshcoreSetupResult.Failed)
            assertNotNull(r.alice.contact(carol.key))
            r.records.markPending("AA:BB")
            r.ch.disconnect()
            advanceTimeBy(30_000)
            runCurrent()
            assertFalse(r.logs.any { it.startsWith("lora cleanup") })
            assertTrue(checkNotNull(r.records.entryOf("AA:BB")).pending)
        }

    /** Lora L1: an empty set handed over for want of consent is no taught set; the re-read's reconcile waits for the real one. */
    @Test
    fun aReconcileAfterAnUnansweredProofWaitsForTheRealTaughtSet() =
        runTest {
            val r = rig()
            r.alice.dropSignFinish = 1
            up(r)
            assertTrue(r.consents.last().unread)
            // What the hooks do for want of consent.
            r.session.setKnitContacts(emptyList(), withdrawn = true)
            advanceTimeBy(60_000)
            runCurrent()
            assertTrue("granted at the re-ask", r.consents.last().granted)
            assertEquals("no listing against nothing", 0, r.listings())
            assertNotNull("bob, Knit's, is kept", r.alice.contact(r.bob.key))
            // The hooks' real set lands: the reconcile runs, and bob stays.
            r.session.setKnitContacts(listOf(r.bob.key))
            advanceTimeBy(30_000)
            runCurrent()
            assertEquals(1, r.listings())
            assertNotNull(r.alice.contact(r.bob.key))
            assertEquals(
                0L,
                r.session.counters.reconcileRemoved
                    .get(),
            )
        }

    // --- the unit-test review's round (2026-11-14) ---

    @Test
    fun everyReadyCarriesAKnownConsentReadInTheHandshakeBeforeTheReady() =
        runTest {
            val r = rig()
            val events = mutableListOf<String>()
            val collect = UnconfinedTestDispatcher(testScheduler)
            backgroundScope.launch(collect) { r.session.consent.collect { if (it != null) events += "consent:${it.granted}" } }
            backgroundScope.launch(collect) { r.session.state.collect { if (it is BoardState.Ready) events += "ready" } }
            up(r)
            assertEquals("the consent precedes the Ready it rides ($events)", listOf("consent:true", "ready"), events.take(2))
            val none = rig(consented = false)
            up(none)
            // A bound board with no record is withdrawn at start too (security N2), then its Ready's consent: never granted.
            assertTrue(none.consents.none { it.granted })
            assertTrue(none.dm().isNoRecord())
        }

    @Test
    fun theChallengeIsTheConsentTagAndAFreshNonceAndEveryProofHasItsOwn() =
        runTest {
            val r = rig()
            up(r)
            assertTrue((r.session.restore() as MeshcoreRestoreResult.Done).failed.isEmpty())
            val data =
                r.ch.writes
                    .filter { it[0].toInt() == MeshcoreProto.CMD_SIGN_DATA }
                    .map { it.copyOfRange(1, it.size) }
            assertEquals("the handshake's proof and the Restore's", 2, data.size)
            assertEquals(2, r.nonces.size)
            data.forEachIndexed { i, sent -> assertArrayEquals(MeshcoreSession.CONSENT_DOMAIN + r.nonces[i], sent) }
            assertFalse("a fresh nonce each", r.nonces[0].contentEquals(r.nonces[1]))
        }

    /** U1 (a): the handshake forced slow; the unbind marks the record, and the cleanup is the connection's first job. */
    @Test
    fun anUnbindWhileTheBoardHandshakesMarksTheRecordAndTheCleanupRunsFirst() =
        runTest {
            val r = rig()
            r.dialer.onOpen = { ch -> ch.delayFrame = { f -> if (f[0].toInt() == MeshcoreProto.RESP_CHANNEL_INFO) 2_500L else 0L } }
            r.session.start("AA:BB")
            runCurrent()
            assertEquals(MeshcoreLinkState.Handshaking, (r.session.state.value as BoardState.Down).detail)
            val unbind = async { r.session.unbind("AA:BB") }
            runCurrent()
            assertEquals(MeshcoreUnbind.PENDING, unbind.await())
            advanceTimeBy(120_000)
            runCurrent()
            assertTrue(r.logs.any { it.startsWith("lora cleanup kind=meshcore done=true") })
            assertNull(r.records.entryOf("AA:BB"))
            assertTrue(r.consents.none { it.granted })
            val writes = r.ch.writes.map { it[0].toInt() }
            assertTrue(
                "no teach before the cleanup",
                writes.takeWhile { it != MeshcoreProto.CMD_REMOVE_CONTACT }.none {
                    it ==
                        MeshcoreProto.CMD_ADD_UPDATE_CONTACT
                },
            )
        }

    /** U1 (b): the handshake held inside its consent read, under the lock: the unbind waits for the Ready, then the actor runs it. */
    @Test
    fun anUnbindWaitsForAHandshakeHeldInItsConsentReadThenUndoesItOnTheActor() =
        runTest {
            val journal = CountingJournal().apply { holdReads = 1 }
            val r = rig(records = journal)
            kotlinx.coroutines.runBlocking { journal.inner.put("AA:BB", MeshcoreHooks.digest(r.alice.key), SETUP_RECORD, false) }
            r.session.start("AA:BB")
            runCurrent()
            assertTrue("the handshake is held in its read", journal.holding)
            val unbind = async { r.session.unbind("AA:BB") }
            runCurrent()
            assertFalse("the unbind waits on the lock", unbind.isCompleted)
            val before = r.consents.size
            journal.gate.complete(Unit)
            runCurrent()
            advanceTimeBy(30_000)
            runCurrent()
            assertEquals(MeshcoreUnbind.CLEARED, unbind.await())
            // The handshake's consent (granted), then the unbind's withdrawal: never a grant after the first withdrawal.
            val after = r.consents.drop(before)
            val firstNo = after.indexOfFirst { !it.granted }
            assertTrue("the unbind withdrew it ($after)", firstNo >= 0)
            assertTrue("no grant after the withdrawal", after.drop(firstNo).none { it.granted })
        }

    /**
     * U2: the production order — a pending cleanup owed by a backoff re-read and a setup queued while that read is held.
     * The setup runs first and supersedes the cleanup: no cleanup after the setup's line, the record kept and not pending.
     */
    @Test
    fun aSetupQueuedBehindAReReadSupersedesTheCleanupItFound() =
        runTest {
            // The start's own read (three tries) and the handshake's (three more) all fail.
            val journal = CountingJournal().apply { failFirstReads = 6 }
            val r = rig(knitSlot = false, records = journal)
            val digest = MeshcoreHooks.digest(r.alice.key)
            kotlinx.coroutines.runBlocking { journal.inner.put("AA:BB", digest, SETUP_RECORD, pending = true) }
            up(r)
            assertTrue("the handshake's reads failed", r.consents.last().unread)
            journal.holdReads = 1
            repeat(120) { if (!journal.holding) advanceTimeBy(1_000) }
            assertTrue("the backoff re-read is held", journal.holding)
            val setup = async { r.session.setup() }
            runCurrent()
            journal.gate.complete(Unit)
            runCurrent()
            advanceTimeBy(60_000)
            runCurrent()
            assertTrue(setup.await() is MeshcoreSetupResult.Done)
            val setupAt = r.logs.indexOfFirst { it.startsWith("meshcore setup slot=") }
            assertTrue("no cleanup after the setup (${r.logs})", r.logs.drop(setupAt).none { it.startsWith("lora cleanup") })
            val kept = checkNotNull(journal.inner.entryOf("AA:BB"))
            assertFalse(kept.pending)
            assertTrue(r.consents.last().granted)
        }

    @Test
    fun aRecordReadThatHangsIsBoundedAndReadAgainLater() =
        runTest {
            val journal = CountingJournal().apply { slowReads = true }
            val r = rig(records = journal)
            kotlinx.coroutines.runBlocking { journal.inner.put("AA:BB", MeshcoreHooks.digest(r.alice.key), SETUP_RECORD, false) }
            up(r)
            advanceTimeBy(10_000)
            runCurrent()
            assertTrue(r.session.state.value is BoardState.Ready)
            assertEquals(
                1L,
                r.session.counters.consentReadFailures
                    .get(),
            )
            val reads = journal.reads
            journal.slowReads = false
            advanceTimeBy(70_000)
            runCurrent()
            assertTrue("read again later", journal.reads > reads)
            assertTrue(r.consents.last().granted)
        }

    /**
     * Security S2 and F1 in one: a key that changes on the connection ends the session (the setup answers `NotReady`), and
     * reconnected as the new key, which the board cannot sign as, the setup is `Failed`. No record is written for either key.
     * F1's own guard in `runSetup` stays as a belt, reached by nothing while [takeSelf] ends the session first.
     */
    @Test
    fun aKeyChangedOnTheConnectionEndsTheSessionAndTheNewKeyCannotSetUp() =
        runTest {
            val r = rig(knitSlot = false)
            up(r)
            val other = FakeMeshcoreAir.keyOf("imported")
            r.alice.reportedKey = other
            assertEquals(MeshcoreSetupResult.NotReady, r.session.setup())
            assertTrue(
                r.session.counters.keyChanged
                    .get() >= 1,
            )
            advanceTimeBy(30_000)
            runCurrent()
            assertTrue(r.session.setup() is MeshcoreSetupResult.Failed)
            assertNull(r.records.entryOf("AA:BB"))
            assertTrue(r.consents.none { it.granted })
            assertTrue(r.consents.none { it.key?.contentEquals(other) == true })
        }

    /** Security (the round's Blocking): a Restore or unbind after a lost proof answers the claim now, never unread. */
    @Test
    fun aRestoreAfterALostProofWithdrawsTheClaimAtOnce() =
        runTest {
            val r = rig()
            r.alice.dropSignFinish = 1
            up(r)
            assertTrue(r.consents.last().unread)
            assertTrue((r.session.restore() as MeshcoreRestoreResult.Done).failed.isEmpty())
            assertFalse(r.consents.last().unread)
            assertFalse(r.consents.last().granted)
            assertNull(r.consents.last().key)
        }

    @Test
    fun anUnbindAfterALostProofWithdrawsTheClaimAtOnce() =
        runTest {
            val r = rig()
            r.alice.dropSignFinish = 1
            up(r)
            assertTrue(r.consents.last().unread)
            assertEquals(MeshcoreUnbind.CLEARED, r.session.unbind("AA:BB"))
            assertFalse(r.consents.last().unread)
            assertNull(r.consents.last().key)
        }

    /** Lora L: a signature that fails on a board re-keyed mid-connection ends the session; the next identify withdraws the claim. */
    @Test
    fun aRefusedProofOnABoardReKeyedMidConnectionEndsTheSession() =
        runTest {
            val r = rig()
            r.alice.canSign = false
            r.alice.reportedKey = FakeMeshcoreAir.keyOf("imported")
            r.alice.reportedKeyFrom = 2 // identify's APP_START reports the real key, the proof's re-read the new one
            up(r)
            advanceTimeBy(30_000)
            runCurrent()
            assertTrue(
                r.session.counters.keyChanged
                    .get() >= 1,
            )
            assertTrue(
                r.session.counters.consentMismatches
                    .get() >= 1,
            )
            assertFalse(r.consents.last().unread)
            assertFalse(r.consents.last().granted)
        }

    /** U10: no line names the record's prior name or its digest, across setup, Restore, unbind and cleanup. */
    @Test
    fun noLineNamesTheRecordsPriorNameOrDigest() =
        runTest {
            val r = rig(knitSlot = false)
            up(r)
            r.session.setup()
            r.session.restore()
            r.session.setup()
            r.records.markPending("AA:BB")
            r.ch.disconnect()
            advanceTimeBy(30_000)
            runCurrent()
            r.session.setup()
            r.session.unbind("AA:BB")
            assertTrue(r.logs.any { it.startsWith("lora cleanup") })
            val digest = MeshcoreHooks.digest(r.alice.key)
            assertTrue(r.logs.none { "alice" in it || digest in it })
            val entry = MeshcoreSetupEntry(SETUP_RECORD, digest, pending = true)
            // The exact custom lines: a ByteArray would print as [B@… even unredacted, so a name check alone proves nothing.
            assertEquals("MeshcoreSetupEntry(pending=true)", entry.toString())
            assertEquals("MeshcoreSetupRecord(slot=1, slotWritten=false, name=true, manualAdd=0, autoadd=null)", SETUP_RECORD.toString())
            assertFalse(SETUP_RECORD.toString().contains("priorName"))
        }

    // --- the record write rule (the maintainer's decision, 2026-11-14) ---

    /**
     * The security Blocking: a Restore whose record clear does not land is no success. It answers `failed: record`, the
     * record is pending, consent is withdrawn at once, an unbind is `PENDING`, and no reconnect grants it again.
     */
    @Test
    fun aWithdrawWriteThatFailsLeavesTheRecordPendingAndNeverReGrants() =
        runTest {
            val journal = CountingJournal()
            val r = rig(records = journal)
            kotlinx.coroutines.runBlocking { journal.inner.put("AA:BB", MeshcoreHooks.digest(r.alice.key), SETUP_RECORD, false) }
            up(r)
            assertTrue(r.consents.last().granted)
            val granted = r.consents.size
            journal.failClears = true
            val restore = r.session.restore() as MeshcoreRestoreResult.Done
            assertTrue("never a success: ${restore.failed}", "record" in restore.failed)
            assertTrue(checkNotNull(journal.inner.entryOf("AA:BB")).pending)
            assertFalse(r.consents.last().granted)
            assertNull(r.consents.last().key)
            assertEquals(MeshcoreUnbind.PENDING, r.session.unbind("AA:BB"))
            r.ch.disconnect()
            advanceTimeBy(30_000)
            runCurrent()
            assertTrue(
                "never granted again, and the key never published again",
                r.consents.drop(granted).none {
                    it.granted ||
                        it.key != null
                },
            )
            assertTrue(checkNotNull(journal.inner.entryOf("AA:BB")).pending)
        }

    /** The rule for a grant write: a setup whose record does not land is Failed, with no consent and no record. */
    @Test
    fun aGrantWriteThatFailsLeavesNoConsentAndTheSetupFails() =
        runTest {
            val journal = CountingJournal().apply { failPuts = true }
            val r = rig(knitSlot = false, records = journal)
            up(r)
            assertTrue(r.session.setup() is MeshcoreSetupResult.Failed)
            assertNull(journal.inner.entryOf("AA:BB"))
            assertTrue(r.consents.none { it.granted })
        }

    /** The rule for a withdraw write whose pending mark fails too: `FAILED`, so the caller keeps the binding; consent no. */
    @Test
    fun aWithdrawWriteAndItsMarkFailingIsFailed() =
        runTest {
            val journal = CountingJournal()
            val r = rig(records = journal)
            kotlinx.coroutines.runBlocking { journal.inner.put("AA:BB", MeshcoreHooks.digest(r.alice.key), SETUP_RECORD, false) }
            up(r)
            journal.failClears = true
            journal.failMarks = true
            assertEquals(MeshcoreUnbind.FAILED, r.session.unbind("AA:BB"))
            assertFalse(r.consents.last().granted)
            assertNull(r.consents.last().key)
        }

    // --- write the intent before acting (the security enumeration, 2026-11-14) ---

    /** B1: an unbind cut off in the middle of its Restore leaves a pending cleanup, never a consent, at the next start. */
    @Test
    fun anUnbindCutOffMidRestoreLeavesAPendingCleanupNeverAConsent() =
        runTest {
            val r = rig()
            up(r)
            r.ch.swallow = { it[0].toInt() == MeshcoreProto.CMD_REMOVE_CONTACT } // the Restore stops at its first removal
            val unbind = async { r.session.unbind("AA:BB") }
            runCurrent()
            assertTrue("the intent is written before any board write", checkNotNull(r.records.entryOf("AA:BB")).pending)
            assertFalse(r.consents.last().granted)
            // The process dies: the actor is dropped mid-Restore, and the unbind's call with it — only the journal crosses over.
            unbind.cancel()
            r.session.stop()
            val logs = mutableListOf<String>()
            val fresh =
                MeshcoreSession(
                    dialer = r.dialer,
                    scope = backgroundScope,
                    now = { testScheduler.currentTime },
                    wallClock = { epochMs + testScheduler.currentTime },
                    log = { logs += it },
                    records = r.records,
                )
            val consents = mutableListOf<MeshcoreConsent>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { fresh.consent.collect { it?.let(consents::add) } }
            fresh.start("AA:BB")
            runCurrent()
            advanceTimeBy(60_000)
            runCurrent()
            assertTrue("no consent granted, no key published", consents.none { it.granted || it.key != null })
            assertTrue(logs.any { it.startsWith("lora cleanup kind=meshcore done=true") })
            assertNull(r.records.entryOf("AA:BB"))
        }

    /** N3: a plain Restore that fails partway is a pending cleanup with consent no; a refused proof answers `failed: proof`. */
    @Test
    fun aRestoreThatFailsPartwayIsAPendingCleanupAndARefusedProofIsNoAnswer() =
        runTest {
            val r = rig()
            up(r)
            r.ch.swallow = { it[0].toInt() == MeshcoreProto.CMD_REMOVE_CONTACT }
            val failed = r.session.restore() as MeshcoreRestoreResult.Done
            assertTrue("${failed.failed}", failed.failed.isNotEmpty())
            assertTrue(checkNotNull(r.records.entryOf("AA:BB")).pending)
            assertFalse(r.consents.last().granted)
            assertNull(r.consents.last().key)
            r.ch.swallow = { false }
            r.alice.canSign = false
            val refused = r.session.restore() as MeshcoreRestoreResult.Done
            assertTrue("a signature that fails is no answer here: ${refused.failed}", "proof" in refused.failed)
        }

    /** N1: a first setup whose grant write fails leaves the write-ahead's pending record; the next connection restores. */
    @Test
    fun aSetupWhoseGrantFailsLeavesAPendingRecordThatTheNextConnectionRestores() =
        runTest {
            val journal = CountingJournal().apply { putsBeforeFailing = 1 }
            val r = rig(knitSlot = false, records = journal)
            up(r)
            assertTrue(r.session.setup() is MeshcoreSetupResult.Failed)
            // The setup's board writes all landed before its grant failed: Knit's name and Knit's slot.
            assertEquals(MeshcoreNames.forKey(r.alice.key), r.alice.advertName.decodeToString())
            assertEquals(
                KnitChannel.NAME,
                r.alice.channels[1]
                    .first
                    .decodeToString(),
            )
            val kept = checkNotNull(journal.inner.entryOf("AA:BB"))
            assertTrue(kept.pending)
            assertEquals("alice", kept.record.priorName?.decodeToString())
            journal.putsBeforeFailing = -1
            r.ch.disconnect()
            advanceTimeBy(60_000)
            runCurrent()
            assertTrue(r.logs.any { it.startsWith("lora cleanup kind=meshcore done=true") })
            assertEquals("alice", r.alice.advertName.decodeToString())
            assertTrue(
                "the slot is cleared",
                r.alice.channels[1]
                    .first
                    .isEmpty(),
            )
            assertTrue(r.consents.none { it.granted })
        }

    /** N1: a setup cut off by a disconnect after its name write is put back by the next connection's cleanup. */
    @Test
    fun aSetupCutOffAfterItsNameWriteIsPutBackAtTheNextConnection() =
        runTest {
            val r = rig(knitSlot = false)
            up(r)
            r.ch.writeResult = { f ->
                // The link drops the moment the name is written: the setup is cut off before its channel and its grant.
                if (f[0].toInt() == MeshcoreProto.CMD_SET_ADVERT_NAME) r.ch.disconnect()
                GattResult.Ok(Unit)
            }
            assertTrue(r.session.setup() !is MeshcoreSetupResult.Done)
            assertEquals("the name was written before the cut", MeshcoreNames.forKey(r.alice.key), r.alice.advertName.decodeToString())
            assertTrue(checkNotNull(r.records.entryOf("AA:BB")).pending)
            advanceTimeBy(60_000)
            runCurrent()
            assertTrue(r.logs.any { it.startsWith("lora cleanup kind=meshcore done=true") })
            assertEquals("alice", r.alice.advertName.decodeToString())
            assertNull(r.records.entryOf("AA:BB"))
        }

    /** N2: an unbind of the bound board with no record still publishes a withdrawal; a mark of no row is not a mark. */
    @Test
    fun anUnbindWithNoRecordStillWithdrawsAndAMarkOfNoRowIsNoMark() =
        runTest {
            val r = rig(consented = false)
            up(r)
            r.session.stop()
            val before = r.consents.size
            assertEquals(MeshcoreUnbind.NOTHING, r.session.unbind("AA:BB"))
            assertTrue("a withdrawal published", r.consents.size > before)
            assertFalse(r.consents.last().granted)
            assertNull(r.consents.last().key)
            assertFalse(MemoryMeshcoreSetupJournal().markPending("AA:BB"))
        }

    // --- the 5b-2 tail (2026-11-14) ---

    /** 6c: the write-ahead comes before the erase — a setup cut at the erase's first removal leaves a pending record. */
    @Test
    fun aSetupCutAtItsFirstErasureLeavesAPendingRecord() =
        runTest {
            val r = rig(knitSlot = false)
            r.alice.addContact(r.air.board("carol"), name = "Carol Smith", flags = 0)
            up(r)
            r.ch.writeResult = { f ->
                if (f[0].toInt() == MeshcoreProto.CMD_REMOVE_CONTACT) r.ch.disconnect()
                GattResult.Ok(Unit)
            }
            assertTrue(r.session.setup() !is MeshcoreSetupResult.Done)
            assertTrue("the intent was written before the first erasure", checkNotNull(r.records.entryOf("AA:BB")).pending)
        }

    /** 1: a re-setup of a board this connection consents for keeps the consent, so the taught set survives the erase. */
    @Test
    fun aReSetupOfAConsentedBoardKeepsItsConsentAndItsTaughtContacts() =
        runTest {
            val r = rig()
            up(r)
            assertTrue(r.consents.last().granted)
            val before = r.consents.size
            val writes = r.ch.writes.size
            assertTrue(r.session.setup() is MeshcoreSetupResult.Done)
            assertTrue("never withdrawn on the way (${r.consents.drop(before)})", r.consents.drop(before).all { it.granted })
            assertNotNull("bob, taught and Knit's, is kept", r.alice.contact(r.bob.key))
            assertTrue(
                r.ch.writes.drop(writes).none {
                    it[0].toInt() == MeshcoreProto.CMD_REMOVE_CONTACT && it.copyOfRange(1, 33).contentEquals(r.bob.key)
                },
            )
            assertFalse(checkNotNull(r.records.entryOf("AA:BB")).pending)
        }

    /** 3: a start's withdrawal is for the board still bound: a quick start of another board makes it moot. */
    @Test
    fun aStartsWithdrawalIsDroppedWhenAnotherBoardWasStartedSince() =
        runTest {
            val r = rig(consented = false)
            r.session.start("AA:BB")
            r.session.start("CC:DD")
            runCurrent()
            advanceTimeBy(10_000)
            runCurrent()
            assertTrue(
                "nothing published for the board started first (${r.consents.map { it.address }})",
                r.consents.none {
                    it.address ==
                        "AA:BB"
                },
            )
        }

    // --- the tail's review (2026-11-14): a kept consent never outlives a setup that did not finish ---

    /** 1 (b): a consented re-setup cut at its first erasure ends unconsented, its record pending. */
    @Test
    fun aConsentedReSetupCutMidEraseEndsUnconsented() =
        runTest {
            val r = rig()
            r.alice.addContact(r.air.board("carol"), name = "Carol Smith", flags = 0)
            r.session.setKnitContacts(listOf(r.bob.key))
            up(r)
            advanceTimeBy(30_000)
            runCurrent()
            r.alice.addContact(r.air.board("dave"), name = "Dave", flags = 0) // something left for the erase to remove
            assertTrue(r.consents.last().granted)
            r.ch.writeResult = { f ->
                if (f[0].toInt() == MeshcoreProto.CMD_REMOVE_CONTACT) r.ch.disconnect()
                GattResult.Ok(Unit)
            }
            assertTrue(r.session.setup() !is MeshcoreSetupResult.Done)
            assertFalse("revoked on the way out", r.consents.last().granted)
            assertNull(r.consents.last().key)
            assertTrue(checkNotNull(r.records.entryOf("AA:BB")).pending)
        }

    /** 1 (a): a consented re-setup whose write-ahead put fails ends unconsented. */
    @Test
    fun aConsentedReSetupWhoseWriteAheadFailsEndsUnconsented() =
        runTest {
            val journal = CountingJournal()
            val r = rig(records = journal)
            kotlinx.coroutines.runBlocking { journal.inner.put("AA:BB", MeshcoreHooks.digest(r.alice.key), SETUP_RECORD, false) }
            up(r)
            assertTrue(r.consents.last().granted)
            journal.failPuts = true
            assertTrue(r.session.setup() is MeshcoreSetupResult.Failed)
            assertFalse(r.consents.last().granted)
            assertNull(r.consents.last().key)
        }

    /** 2 (M5): a consented re-setup that fails before any board change puts its granted prior back as a grant. */
    @Test
    fun aConsentedReSetupThatFailsBeforeAnyChangeKeepsItsConsent() =
        runTest {
            val r = rig()
            up(r)
            assertTrue(r.consents.last().granted)
            val before = r.consents.size
            // The erase's listing never answers: Erase.Failed, nothing on the board changed.
            r.ch.swallow = { it[0].toInt() == MeshcoreProto.CMD_GET_CONTACTS && MeshcoreProto.u32(it, 1) == 0L }
            assertTrue(r.session.setup() is MeshcoreSetupResult.Failed)
            assertTrue("granted throughout (${r.consents.drop(before)})", r.consents.drop(before).all { it.granted })
            assertFalse(checkNotNull(r.records.entryOf("AA:BB")).pending)
        }

    /** 2 (M6): the same with the record pending first: the pending cleanup never comes back as a consent. */
    @Test
    fun aReSetupOverAPendingRecordThatFailsBeforeAnyChangeLeavesItPending() =
        runTest {
            val r = rig()
            up(r)
            r.records.markPending("AA:BB")
            val before = r.consents.size
            r.ch.swallow = { it[0].toInt() == MeshcoreProto.CMD_GET_CONTACTS && MeshcoreProto.u32(it, 1) == 0L }
            assertTrue(r.session.setup() is MeshcoreSetupResult.Failed)
            assertTrue(r.consents.drop(before).none { it.granted })
            assertTrue(checkNotNull(r.records.entryOf("AA:BB")).pending)
        }

    // --- the 5b-2 tail's reviews (2026-11-14): the two cancellation windows a kept consent could stand over ---

    /** Runs the session's clock until [journal]'s held put is suspended, or a few minutes pass. */
    private fun TestScope.untilPutHeld(journal: CountingJournal) {
        repeat(SETTLE_ROUNDS) {
            if (journal.putHolding) return
            advanceTimeBy(500)
            runCurrent()
        }
    }

    /** (2): a consented re-setup cancelled inside its write-ahead, its row already pending on disk, ends unconsented. */
    @Test
    fun aConsentedReSetupCancelledInsideItsWriteAheadEndsUnconsented() =
        runTest {
            val journal = CountingJournal()
            val r = rig(records = journal)
            kotlinx.coroutines.runBlocking { journal.inner.put("AA:BB", MeshcoreHooks.digest(r.alice.key), SETUP_RECORD, false) }
            up(r)
            assertTrue(r.consents.last().granted)
            journal.holdPut = 0 // the write-ahead, held once its pending row landed
            journal.holdLanded = true
            val setup = async { r.session.setup() }
            untilPutHeld(journal)
            assertTrue("held inside the write-ahead", journal.putHolding)
            assertTrue("the consent was kept over it", r.consents.last().granted)
            assertTrue(checkNotNull(journal.inner.entryOf("AA:BB")).pending)
            r.session.stop() // the actor is cancelled inside the write
            runCurrent()
            assertFalse("revoked on the way out", r.consents.last().granted)
            assertNull(r.consents.last().key)
            assertTrue(checkNotNull(journal.inner.entryOf("AA:BB")).pending)
            setup.cancel()
        }

    /** (1): a consented re-setup cancelled inside the grant that puts its prior back ends unconsented, the record pending. */
    @Test
    fun aConsentedReSetupCancelledInsideItsPutBackEndsUnconsented() =
        runTest {
            val journal = CountingJournal()
            val r = rig(records = journal)
            kotlinx.coroutines.runBlocking { journal.inner.put("AA:BB", MeshcoreHooks.digest(r.alice.key), SETUP_RECORD, false) }
            up(r)
            assertTrue(r.consents.last().granted)
            // The erase's listing never answers: nothing on the board changed, so the prior record goes back as a grant.
            r.ch.swallow = { it[0].toInt() == MeshcoreProto.CMD_GET_CONTACTS && MeshcoreProto.u32(it, 1) == 0L }
            journal.holdPut = 1 // the write-ahead lands; the put-back's grant is held before its row
            val setup = async { r.session.setup() }
            untilPutHeld(journal)
            assertTrue("held inside the put-back's grant", journal.putHolding)
            assertTrue("the write-ahead's row is pending", checkNotNull(journal.inner.entryOf("AA:BB")).pending)
            r.session.stop() // the actor is cancelled inside the grant
            runCurrent()
            assertFalse("never a consent over a pending record", r.consents.last().granted)
            assertNull(r.consents.last().key)
            assertTrue(checkNotNull(journal.inner.entryOf("AA:BB")).pending)
            setup.cancel()
        }

    private companion object {
        /** Clock steps [untilPutHeld] takes at most. */
        const val SETTLE_ROUNDS = 600

        /** `CMD_FACTORY_RESET` (`MyMesh.cpp`): formats the board, a new identity and the radio reset (ADR 045). */
        const val CMD_FACTORY_RESET = 51

        /** A setup record as this phone would have kept it for alice's board. */
        val SETUP_RECORD =
            MeshcoreSetupRecord(1, slotWritten = false, priorName = "alice".encodeToByteArray(), priorManualAdd = 0, priorAutoadd = null)
    }
}
