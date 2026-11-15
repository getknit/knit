package app.getknit.knit.mesh.lora.meshcore

import app.getknit.knit.mesh.lora.KnitChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FakeMeshcoreAir]'s channel (ADR 2026-11.gsqu's Stage 5c-2a amendment), each modelled behaviour on its own against the
 * firmware source it is written from; [MeshcoreAirPhysicsTest] runs the real stack over it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FakeMeshcoreAirTest {
    /** Every frame a board writes, and what its offline queue holds for the phone (nobody drains it here). */
    private class Recorder(
        private val board: FakeMeshcoreBoard,
    ) : FakeIface {
        val frames = mutableListOf<ByteArray>()
        override val connected = true

        init {
            board.ifaces += this
        }

        override fun deliver(frame: ByteArray) {
            frames += frame
        }

        private fun queued(): List<McFrame> = board.offline.map { MeshcoreProto.decode(it) }

        fun data(): List<McFrame.ChannelData> = queued().filterIsInstance<McFrame.ChannelData>()

        fun dms(): List<McFrame.ContactMessage> = queued().filterIsInstance<McFrame.ContactMessage>()
    }

    private fun TestScope.air(channel: MeshcoreChannel = MeshcoreChannel()) =
        FakeMeshcoreAir(backgroundScope, channel) {
            testScheduler.currentTime
        }

    private fun FakeMeshcoreAir.knitBoard(name: String): FakeMeshcoreBoard =
        board(name).also {
            it.setChannel(1, KnitChannel.NAME, KnitChannel.PSK)
        }

    private fun FakeMeshcoreBoard.post(data: ByteArray) = handle(requireNotNull(MeshcoreProto.sendChannelData(1, data)))

    private fun TestScope.settle(ms: Long = 10_000) {
        runCurrent()
        advanceTimeBy(ms)
        runCurrent()
    }

    @Test
    fun anAiringLandsAtItsEndAndTakesTheFormulasTime() =
        runTest {
            val air = air()
            val a = air.knitBoard("alice")
            val b = air.knitBoard("bob")
            air.link("alice", "bob")
            val rx = Recorder(b)
            a.post(ByteArray(161) { 1 })
            runCurrent()
            val airing = air.airings.single()
            assertEquals("Q15's 181 B on the air", 181, airing.bytes)
            assertEquals(air.channel!!.airtimeMs(181), airing.airMs)
            advanceTimeBy(airing.airMs - 1)
            runCurrent()
            assertTrue("not before its end", rx.data().isEmpty())
            advanceTimeBy(2)
            runCurrent()
            assertEquals(1, rx.data().size)
        }

    @Test
    fun aSendersPoolAirsOnePacketAtATime() =
        runTest {
            val air = air(MeshcoreChannel(spreadFactor = 7))
            val a = air.knitBoard("alice")
            air.knitBoard("bob")
            air.link("alice", "bob")
            a.post(ByteArray(161) { 1 })
            a.post(ByteArray(161) { 2 })
            settle()
            val (p0, p1) = air.airings
            // Q15: 606 ms start to start in the simulator, whose radio takes a 16-symbol preamble; the firmware's is 32 at
            // SF ≤ 8, and Q5 measured 633.
            assertEquals("back to back, one airtime apart", p0.atMs + p0.airMs, p1.atMs)
            assertTrue(p0.airMs in 616L..650L)
        }

    @Test
    fun anOverlapAtAReceiverCorruptsBothPacketsUnlessACaptureIsStated() =
        runTest {
            fun overlap(capture: Boolean): Pair<FakeMeshcoreAir, Recorder> {
                val air = air()
                val a = air.knitBoard("alice")
                val c = air.knitBoard("carol")
                val b = air.knitBoard("bob")
                // Hidden terminals: alice and carol cannot hear each other, so neither listens before the other talks.
                air.link("alice", "bob")
                air.link("carol", "bob")
                if (capture) air.channel!!.capture = { _, locked, _ -> locked == "alice" }
                val rx = Recorder(b)
                a.post(ByteArray(40) { 1 })
                c.post(ByteArray(40) { 2 })
                settle()
                return air to rx
            }
            val (air, rx) = overlap(capture = false)
            assertTrue("an equal-power, same-SF overlap loses both", rx.data().isEmpty())
            assertEquals(2, air.radio("bob").collisions)
            assertTrue(air.airings.all { "bob" in it.lostAt })
            val (captured, crx) = overlap(capture = true)
            assertEquals("a stated capture keeps the one locked", listOf<Byte>(1), crx.data().map { it.data[0] })
            assertEquals(listOf("bob"), captured.airings.last().lostAt)
        }

    @Test
    fun aRadioDecodingACorruptedPacketIsStillBusyToItsCad() =
        runTest {
            val air = air()
            val a = air.knitBoard("alice")
            val c = air.knitBoard("carol")
            val b = air.knitBoard("bob")
            air.link("alice", "bob")
            air.link("carol", "bob")
            a.post(ByteArray(160) { 1 })
            runCurrent()
            advanceTimeBy(100)
            c.post(ByteArray(160) { 2 })
            runCurrent()
            advanceTimeBy(100)
            b.post(ByteArray(10) { 3 })
            settle()
            val (first, second, third) = air.airings
            assertTrue("bob waited for the later end of the garbled air", third.atMs >= second.atMs + second.airMs)
            assertTrue(first.atMs + first.airMs <= third.atMs)
        }

    @Test
    fun aRadioListensBeforeItTalks() =
        runTest {
            val air = air()
            val a = air.knitBoard("alice")
            val c = air.knitBoard("carol")
            air.link("alice", "carol")
            a.post(ByteArray(161) { 1 })
            runCurrent()
            c.post(ByteArray(10) { 2 })
            settle()
            val (first, second) = air.airings
            assertTrue("carol waited for alice's packet to end", second.atMs >= first.atMs + first.airMs)
        }

    @Test
    fun theRingDropsAByteIdenticalCopyUntil160NewerPacketsEvictIt() =
        runTest {
            fun replayAfter(newer: Int): Int {
                val air = air()
                val a = air.knitBoard("alice")
                val b = air.knitBoard("bob")
                air.link("alice", "bob")
                b.offlineCap = 1_000
                val rx = Recorder(b)
                val original = ByteArray(20) { 9 }
                a.post(original)
                settle()
                repeat(newer) { i ->
                    a.post(ByteArray(20) { (i % 100).toByte() } + byteArrayOf((i / 100).toByte()))
                    settle(1_000)
                }
                // Another board airs the same bytes: the path is not in the hash (`Packet::calculatePacketHash`).
                val m = air.knitBoard("mallory")
                air.link("mallory", "bob")
                m.post(original)
                settle()
                return rx.data().count { it.data.contentEquals(original) }
            }
            assertEquals("159 newer: still in the ring", 1, replayAfter(159))
            assertEquals("160 newer: evicted", 2, replayAfter(160))
        }

    @Test
    fun aRepeaterKeepsARingTooSoAReplayOnAFreshChainIsNotReAired() =
        runTest {
            val air = air()
            val a = air.knitBoard("alice")
            val m = air.knitBoard("mallory")
            air.knitBoard("bob")
            air.repeater("relay", "alice", "mallory", "bob")
            val data = ByteArray(20) { 3 }
            a.post(data)
            settle()
            m.post(data)
            settle()
            assertEquals("the relay re-aired the first only", 1, air.airings.count { it.from == "relay" })
            assertEquals("the replay was a dup at the relay", 1, air.radio("relay").dups)
        }

    @Test
    fun aRebootClearsTheRingTheQueueAndTheAckTableAndKeepsTheContacts() =
        runTest {
            val air = air()
            val a = air.knitBoard("alice")
            val b = air.knitBoard("bob")
            air.link("alice", "bob")
            b.addContact(a)
            val data = ByteArray(20) { 4 }
            a.post(data)
            settle()
            assertEquals(1, b.offline.size)
            b.reboot()
            assertTrue("the offline queue is RAM", b.offline.isEmpty())
            assertEquals("contacts are flash", 1, b.contacts.size)
            val rx = Recorder(b)
            air.knitBoard("mallory").also { air.link("mallory", "bob") }.post(data)
            settle()
            assertEquals("the ring is RAM: the copy is heard again", 1, rx.data().size)
        }

    @Test
    fun aBoardWithNoPowerIsDeafAndMuteAndDropsItsLinkAndPowerOnIsAReboot() =
        runTest {
            val air = air()
            val a = air.knitBoard("alice")
            val b = air.knitBoard("bob")
            air.link("alice", "bob")
            val ch = FakeNusChannel(b)
            b.powered = false
            a.post(ByteArray(10) { 1 })
            b.post(ByteArray(10) { 2 })
            settle()
            assertEquals("only alice aired", listOf("alice"), air.airings.map { it.from })
            assertTrue("bob heard nothing", b.offline.isEmpty())
            val disconnected = ch.frames.tryReceive().getOrNull()
            assertTrue("the link dropped: $disconnected", disconnected is NusEvent.Disconnected)
            val before = testScheduler.currentTime
            assertEquals(
                "no board answers a dial",
                NusDialResult.Timeout,
                FakeMeshcoreDialer(b).dial("x", app.getknit.knit.mesh.lora.DialMode.Direct, 3_000),
            )
            assertEquals("after the dial's window", before + 3_000, testScheduler.currentTime)
            val upAt = testScheduler.currentTime
            b.powered = true
            assertEquals("uptime restarts", 0L, b.uptimeSecs)
            assertEquals(upAt, b.uptimeBaseMs)
        }

    @Test
    fun theClockTicksWithTheAirAndTxAirCountsEveryAiringInMilliseconds() =
        runTest {
            val air = air()
            val a = air.knitBoard("alice")
            val b = air.knitBoard("bob")
            air.link("alice", "bob")
            val t0 = a.clockSecs
            settle(90_000)
            assertEquals("a jump moves the RTC", t0 + 90, a.clockSecs)
            repeat(4) { a.post(ByteArray(161) { n -> (n + it).toByte() }) }
            settle()
            val ms = air.airings.sumOf { it.airMs }
            assertEquals(ms, air.radio("alice").txAirMs)
            assertEquals("reported in seconds", ms / 1_000, a.txAirSecs)
            assertEquals(ms / 1_000, b.rxAirSecs)
            assertEquals("the source default", 16, b.offlineCap)
        }

    @Test
    fun holdParksANodesAiringsAndReleaseAirsThemInTheOrderAsked() =
        runTest {
            val air = air()
            val a = air.knitBoard("alice")
            val b = air.knitBoard("bob")
            air.link("alice", "bob")
            val rx = Recorder(b)
            air.hold("alice")
            a.post(byteArrayOf(1) + ByteArray(19))
            a.post(byteArrayOf(2) + ByteArray(19))
            settle()
            assertTrue("parked before anyone heard them", air.airings.isEmpty() && rx.data().isEmpty())
            assertEquals(2, air.parked("alice").size)
            air.release("alice", stayHeld = true) { it.takeLast(1) }
            settle()
            assertEquals(listOf<Byte>(2), rx.data().map { it.data[0] })
            air.release("alice")
            settle()
            assertEquals(listOf<Byte>(2, 1), rx.data().map { it.data[0] })
        }

    @Test
    fun aLossyLinkLosesOneDirectionOnly() =
        runTest {
            val air = air()
            val a = air.knitBoard("alice")
            val b = air.knitBoard("bob")
            air.link("alice", "bob")
            air.lossy("alice", "bob")
            val toBob = Recorder(b)
            val toAlice = Recorder(a)
            a.post(ByteArray(10) { 1 })
            settle()
            b.post(ByteArray(10) { 2 })
            settle()
            assertTrue(toBob.data().isEmpty())
            assertEquals(1, toAlice.data().size)
        }

    @Test
    fun aRepeaterDrawsEachDelayFromItsOwnSeedAndLogsIt() =
        runTest {
            fun draws(extra: Boolean): List<FakeMeshcoreAir.Draw> {
                val air = air(MeshcoreChannel(runSeed = 42))
                val a = air.knitBoard("alice")
                air.knitBoard("bob")
                air.repeater("relay", "alice", "bob")
                if (extra) {
                    // Another node's traffic first: the draws for alice's floods must not move.
                    air.knitBoard("carol").also { air.link("carol", "relay") }.post(ByteArray(10) { 7 })
                    settle()
                }
                repeat(3) {
                    a.post(ByteArray(30) { n -> (n * it).toByte() })
                    settle()
                }
                return air.draws.filter { it.origin == "alice" }
            }
            val plain = draws(extra = false)
            assertEquals(3, plain.size)
            assertEquals("a seed replays the same delays whatever else aired", plain, draws(extra = true))
            assertTrue(plain.all { it.ms in 0..it.maxMs && !it.direct })
            assertNotEquals("each packet its own draw", 1, plain.map { it.ms }.distinct().size)
            // `t` = airtime(path + payload + 2) × 0.5 for a 1-hop flood of 30 B of data: 5·t is the inclusive maximum.
            val ch = MeshcoreChannel(runSeed = 42)
            assertEquals(5 * (ch.airtimeMs(1 + 3 + MeshcoreChannel.aes(33) + 2) * 0.5).toLong(), plain.first().maxMs)
        }

    @Test
    fun aDirectPacketFollowsItsPathAndTheDestinationInRangeDropsItUntilItsLastHop() =
        runTest {
            val air = air()
            val a = air.board("alice")
            val b = air.board("bob")
            air.link("alice", "bob")
            air.repeater("relay", "alice", "bob")
            a.addContact(b)
            b.addContact(a)
            b.driftPath(a, "relay")
            val rx = Recorder(a)
            FakeForeignClient(b).send(requireNotNull(MeshcoreProto.sendText(a.key.copyOf(6), 0, 1_790_000_500L, "hi".encodeToByteArray())))
            settle()
            val own = air.airings.first { it.from == "bob" }
            assertTrue("alice heard bob's own airing", "alice" in own.heardBy)
            assertEquals("and took only the relay's", 1, rx.dms().size)
            assertEquals("reported direct", MeshcoreProto.PATH_UNKNOWN, rx.dms().single().pathLen)
            assertTrue(air.airings.any { it.from == "relay" && it.type == "msg" && !it.flood })
            assertTrue(air.draws.single { it.repeater == "relay" }.direct)
        }

    @Test
    fun aPathHashModeRidesTheTopBitsOfPathLen() =
        runTest {
            val air = air()
            val a = air.knitBoard("alice")
            val b = air.knitBoard("bob")
            air.repeater("relay", "alice", "bob")
            a.pathHashMode = 1
            val rx = Recorder(b)
            a.post(ByteArray(10) { 1 })
            settle()
            assertEquals("two-byte hashes, one hop", 0x41, rx.data().single().pathLen)
            assertEquals("the hop count is the low six bits", 1, rx.data().single().pathLen and MeshcoreProto.PATH_HOPS_MASK)
        }

    @Test
    fun aRepeatersPoolHolds32() =
        runTest {
            val air = air()
            air.repeater("relay")
            val a = air.knitBoard("alice")
            air.link("alice", "relay")
            air.hold("relay")
            repeat(40) {
                a.post(ByteArray(10) { n -> (n + it).toByte() })
                settle(2_000)
            }
            assertEquals(FakeMeshcoreAir.REPEATER_POOL, air.parked("relay").size)
        }

    @Test
    fun withoutAChannelNothingChanges() =
        runTest {
            val air = FakeMeshcoreAir(backgroundScope) { testScheduler.currentTime }
            val a = air.knitBoard("alice")
            assertTrue(air.radios.isEmpty())
            assertEquals("the measured board's 256", 256, a.offlineCap)
            val t0 = a.clockSecs
            advanceTimeBy(90_000)
            assertEquals("a still RTC, as Stage 3b", t0, a.clockSecs)
            assertEquals(11, a.sf)
        }

    @Test
    fun quickAirCompressesTheFiguresInProportionAboveTheSessionsFloor() =
        runTest {
            val quick = quickMeshcoreAir(backgroundScope, { testScheduler.currentTime })
            assertEquals(47L, quick.confirmDelayMs)
            assertEquals(60L, quick.airMs)
            assertEquals(145L, quick.repeatDelayMs)
            assertEquals(500L, quick.estDirectMs)
            assertFalse(quick.channel != null)
        }

    /** What four boards' writers, two hundred writes each from `Dispatchers.Default`, left behind once the air is quiet. */
    private class Concurrent(
        val maxInside: Int,
        val oks: List<Int>,
        val aired: List<Int>,
        val sent: List<Long>,
        val outbound: List<Int>,
        val relayed: Int,
    )

    private fun concurrentWriters(serial: kotlin.coroutines.CoroutineContext?): Concurrent {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val air = FakeMeshcoreAir(owner, serial = serial) { System.nanoTime() / 1_000_000 }
            air.repeatDelayMs = 1
            air.airMs = 1
            val boards = List(4) { air.knitBoard("n$it") }
            for (i in boards.indices) for (j in boards.indices) if (i < j) air.link("n$i", "n$j")
            air.repeater("relay", *boards.map { it.name }.toTypedArray())
            val channels = boards.map { FakeNusChannel(it) }
            return runBlocking {
                channels
                    .mapIndexed { i, ch ->
                        owner.async {
                            repeat(200) { n ->
                                ch.write(
                                    requireNotNull(
                                        MeshcoreProto.sendChannelData(1, byteArrayOf(i.toByte(), (n shr 8).toByte(), n.toByte())),
                                    ),
                                    1_000,
                                )
                            }
                        }
                    }.awaitAll()
                // Quiet: every own airing re-aired by the relay and every pool slot freed — polled, never a fixed sleep.
                val own = { air.airings.count { it.from != "relay" } }
                val quiet = {
                    air.airings.count { it.from == "relay" } == own() && boards.all { it.outbound == 0 }
                }
                val settled = withTimeoutOrNull(QUIET_DEADLINE_MS) { while (!air.snapshot(quiet)) kotlinx.coroutines.delay(5) }
                val relayed = air.snapshot { air.airings.count { it.from == "relay" } }
                checkNotNull(settled) { "the air never went quiet: ${air.snapshot(own)} own airings, $relayed relayed" }
                // A pool still full refuses a write (`TABLE_FULL`): each board's OKs are what it took.
                val oks =
                    channels.map { ch ->
                        generateSequence { ch.frames.tryReceive().getOrNull() }
                            .count { it is NusEvent.Frame && it.bytes.size == 1 && it.bytes[0].toInt() == 0 }
                    }
                air.snapshot {
                    Concurrent(
                        air.maxInside.get(),
                        oks,
                        boards.map { b -> air.airings.count { it.from == b.name } },
                        boards.map { it.sent },
                        boards.map { it.outbound },
                        relayed,
                    )
                }
            }
        } finally {
            owner.cancel()
        }
    }

    @Test
    fun onASerialContextConcurrentWritersNeverOverlapAndLoseNoCount() {
        // Design pass B6: the lab's sessions write from Dispatchers.Default. Confined to one serial context, no two of the
        // air's bodies (a write, a timer, a launched re-air) ever run at once — so the boards' plain counters and queues
        // (`outbound`, `sent`, `offline`) are never torn: every pool slot taken is freed and every OK is counted sent.
        val r = concurrentWriters(Dispatchers.Default.limitedParallelism(1))
        assertEquals("never two bodies at once", 1, r.maxInside)
        assertTrue("every board took some writes: ${r.oks}", r.oks.all { it > 0 })
        assertEquals("each board aired the writes it answered OK", r.oks, r.aired)
        assertEquals("and counted each sent", r.oks.map { it.toLong() }, r.sent)
        assertEquals("every pool slot freed", listOf(0, 0, 0, 0), r.outbound)
        assertEquals("the relay re-aired each once", r.aired.sum(), r.relayed)
    }

    private companion object {
        const val QUIET_DEADLINE_MS = 30_000L
    }
}
