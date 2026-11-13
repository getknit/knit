package app.getknit.knit.mesh.lora.meshcore

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [FakeMeshcoreAir] checked against the real `companion-v1.18.0` firmware (ADR 2026-11.gsqu, Stage 3b): each simulator run
 * of bench Q11–Q15 is replayed command for command into the fake — the bench's own app-side frames, with its throwaway
 * keys swapped for the fake's — and every board must answer with the frame codes the firmware answered, in its order,
 * with the same flood flags, receive `path_len`s, texts, datagrams and routes; and the air must carry, node by node, the
 * airings the simulator's `<run>.events.ndjson` recorded (written out here, see the resources' `NOTICE.txt`).
 *
 * Behaviours this checks against the firmware: the zero-hop pinned DM and its direct ack (Q12); the ack that floods
 * when the receiver's record is UNKNOWN, re-aired once by a repeater (Q13); the flooded DM, its flooded PATH return, the
 * route rewritten and `0x81` on both boards, and the sender's one direct PATH (Q13); the byte-identical retry dropped
 * without a second ack and the next attempt delivered and acked again (Q14); the zero byte cut and still confirmed, and
 * 161 B refused `TABLE_FULL` (Q11); a two-part flood heard at `path_len` 0 in range and 1 through the repeater (Q15); the
 * drain popping in order to `NO_MORE`. Not checked here, because the simulator has none: the BLE side (Q8), the pool
 * and `TABLE_FULL` past it (Q4, a desk-board measurement), the offline queue's eviction (Q6) and adverts.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MeshcoreFakeVsSimTest {
    /** Which nodes each run had, which repeat, and their links (`<run>.config.json`). */
    private class Topology(
        val companions: List<String>,
        val repeater: Pair<String, List<String>>? = null,
        val links: List<Pair<String, String>>,
    )

    private val twoBoards = Topology(listOf("alice", "bob"), links = listOf("alice" to "bob"))
    private val withRelay = Topology(listOf("alice", "bob"), "relay" to listOf("alice", "bob"), listOf("alice" to "bob"))
    private val q15Topology =
        Topology(listOf("alice", "bob", "carol"), "relay" to listOf("alice", "bob", "carol"), listOf("alice" to "bob"))

    private class Replay(
        val sim: List<SimFrame>,
        val fakeRx: Map<String, List<ByteArray>>,
        val airings: List<FakeMeshcoreAir.Airing>,
    )

    /** Records every frame a fake board writes, as the bench recorded the real one's. */
    private class Recorder : FakeIface {
        val frames = mutableListOf<ByteArray>()
        override val connected = true

        override fun deliver(frame: ByteArray) {
            frames += frame
        }
    }

    private fun TestScope.replay(
        run: String,
        topology: Topology,
    ): Replay {
        val sim = SimFrames.load(run)
        val air = FakeMeshcoreAir(backgroundScope) { testScheduler.currentTime }
        air.confirmDelayMs = 300
        air.repeatDelayMs = 150
        val boards = topology.companions.associateWith { air.board(it) }
        topology.links.forEach { (a, b) -> air.link(a, b) }
        topology.repeater?.let { (name, neighbours) -> air.repeater(name, *neighbours.toTypedArray()) }
        val recorders = boards.mapValues { (_, b) -> Recorder().also { b.ifaces += it } }
        // The bench's keys, off each node's SELF_INFO, become the fake's: in every frame the bench wrote.
        val simKeys = sim.filter { !it.tx && it.code == MeshcoreProto.RESP_SELF_INFO }.associate { it.node to it.bytes.copyOfRange(4, 36) }
        val swaps = simKeys.map { (node, key) -> key to boards.getValue(node).key }
        val t0 = testScheduler.currentTime
        for (f in sim.filter { it.tx }) {
            if (t0 + f.ms > testScheduler.currentTime) advanceTimeBy(t0 + f.ms - testScheduler.currentTime)
            runCurrent()
            var bytes = f.bytes
            for ((from, to) in swaps) {
                bytes = swap(bytes, from, to)
                bytes = swap(bytes, from.copyOf(6), to.copyOf(6))
            }
            boards.getValue(f.node).handle(bytes)
        }
        advanceTimeBy(5_000)
        runCurrent()
        return Replay(sim, recorders.mapValues { it.value.frames }, air.airings)
    }

    private fun swap(
        bytes: ByteArray,
        from: ByteArray,
        to: ByteArray,
    ): ByteArray {
        val out = bytes.copyOf()
        var i = 0
        while (i + from.size <= out.size) {
            if ((from.indices).all { out[i + it] == from[it] }) {
                to.copyInto(out, i)
                i += from.size
            } else {
                i++
            }
        }
        return out
    }

    /** Every frame code a node was written, in order, but `LOG_RX_DATA` (the simulator logs raw RX; the fake does not). */
    private fun codes(frames: List<ByteArray>): List<Int> =
        frames.map { it[0].toInt() and 0xFF }.filter {
            it !=
                MeshcoreProto.PUSH_LOG_RX_DATA
        }

    private fun assertSameAnswers(r: Replay) {
        for ((node, fake) in r.fakeRx) {
            val sim = r.sim.filter { !it.tx && it.node == node }.map { it.bytes }
            assertEquals("$node's answer codes", codes(sim), codes(fake))
            val simDecoded = sim.map { MeshcoreProto.decode(it) }
            val fakeDecoded = fake.map { MeshcoreProto.decode(it) }
            assertEquals(
                "$node's SENT flood flags",
                simDecoded.filterIsInstance<McFrame.Sent>().map { it.flood },
                fakeDecoded.filterIsInstance<McFrame.Sent>().map { it.flood },
            )
            val simDms = simDecoded.filterIsInstance<McFrame.ContactMessage>()
            val fakeDms = fakeDecoded.filterIsInstance<McFrame.ContactMessage>()
            assertEquals("$node's DM path_len", simDms.map { it.pathLen }, fakeDms.map { it.pathLen })
            simDms.zip(fakeDms).forEach { (s, f) -> assertArrayEquals("$node's DM text", s.text, f.text) }
            val simData = simDecoded.filterIsInstance<McFrame.ChannelData>()
            val fakeData = fakeDecoded.filterIsInstance<McFrame.ChannelData>()
            assertEquals("$node's datagram path_len", simData.map { it.pathLen }, fakeData.map { it.pathLen })
            simData.zip(fakeData).forEach { (s, f) -> assertArrayEquals("$node's datagram", s.data, f.data) }
            assertEquals(
                "$node's routes as read back",
                simDecoded.filterIsInstance<McFrame.Contact>().map { it.record.outPathLen },
                fakeDecoded.filterIsInstance<McFrame.Contact>().map { it.record.outPathLen },
            )
        }
    }

    /** The airings, node by node, as `<node> <type> <route>`. */
    private fun airings(
        r: Replay,
        node: String,
    ): List<String> = r.airings.filter { it.from == node }.map { "${it.type} ${if (it.flood) "flood" else "direct"}" }

    @Test
    fun q11EveryNonZeroByteArrivesAZeroCutsTheTextAndPast160IsTableFull() =
        runTest {
            val r = replay("q11", twoBoards)
            assertSameAnswers(r)
        }

    @Test
    fun q12TwoPinnedRecordsAirTheDmAndItsAckDirectAndTheRepeaterAirsNothing() =
        runTest {
            val r = replay("q12", withRelay)
            assertSameAnswers(r)
            // q12.events.ndjson: alice msg direct, bob ack direct; the relay airs nothing.
            assertEquals(listOf("msg direct"), airings(r, "alice"))
            assertEquals(listOf("ack direct"), airings(r, "bob"))
            assertEquals(emptyList<String>(), airings(r, "relay"))
        }

    @Test
    fun q13TheReceiversUnknownRecordFloodsItsAckAndTheRepeaterReAirsIt() =
        runTest {
            val r = replay("q13-receiver_unknown", withRelay)
            assertSameAnswers(r)
            // q13-receiver_unknown.events.ndjson: alice msg direct, bob ack flood, relay ack flood.
            assertEquals(listOf("msg direct"), airings(r, "alice"))
            assertEquals(listOf("ack flood"), airings(r, "bob"))
            assertEquals(listOf("ack flood"), airings(r, "relay"))
        }

    @Test
    fun q13TheSendersUnknownRecordFloodsTheDmAndThePathReturnRewritesItOnBothBoards() =
        runTest {
            val r = replay("q13-sender_unknown", withRelay)
            assertSameAnswers(r)
            // q13-sender_unknown.events.ndjson: alice msg flood, relay msg flood, bob path flood, relay path flood,
            // alice path direct.
            assertEquals(listOf("msg flood", "path direct"), airings(r, "alice"))
            assertEquals(listOf("path flood"), airings(r, "bob"))
            assertEquals(listOf("msg flood", "path flood"), airings(r, "relay"))
        }

    @Test
    fun q14ASameAttemptRetryIsDroppedUnackedAndTheNextAttemptIsDeliveredAgain() =
        runTest {
            val same = replay("q14-same_attempt", twoBoards)
            assertSameAnswers(same)
            // q14-same_attempt.events.ndjson: msg, ack, msg (the second copy dropped by bob, no second ack).
            assertEquals(listOf("msg direct", "msg direct"), airings(same, "alice"))
            assertEquals(listOf("ack direct"), airings(same, "bob"))
            val next = replay("q14-next_attempt", twoBoards)
            assertSameAnswers(next)
            assertEquals(listOf("ack direct", "ack direct"), airings(next, "bob"))
        }

    @Test
    fun q15ATwoPartFloodArrivesWholeInRangeAndThroughTheRepeater() =
        runTest {
            val r = replay("q15", q15Topology)
            assertSameAnswers(r)
            // q15.events.ndjson: alice grp_data flood ×2, relay grp_data flood ×2.
            assertEquals(listOf("grp_data flood", "grp_data flood"), airings(r, "alice"))
            assertEquals(listOf("grp_data flood", "grp_data flood"), airings(r, "relay"))
        }
}
