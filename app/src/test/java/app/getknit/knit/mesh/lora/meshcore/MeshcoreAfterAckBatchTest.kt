package app.getknit.knit.mesh.lora.meshcore

import app.getknit.knit.mesh.lora.LoraConfig
import app.getknit.knit.mesh.lora.LoraMeshTransport
import app.getknit.knit.mesh.lora.LoraReprobe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The design pass's B2 on ADR 2026-10.x9fa's 2026-11-19 amendment, on the real MeshCore stack: a fresh DM's ack sends every
 * held DM no board took at once ([LoraMeshTransport.AFTER_ACK_LIMIT]), but a MeshCore board's flight table holds two
 * ([MeshcoreTraits.maxFlights]) — a third launch would push the oldest live send out as no outcome, marking the board just
 * proven unreachable and losing its ack. The pacer holds them while the table is full, so every one is acked on time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MeshcoreAfterAckBatchTest {
    private fun TestScope.rig(): MeshcoreAirRig =
        MeshcoreAirRig(this, FakeMeshcoreAir(backgroundScope, MeshcoreChannel()) { testScheduler.currentTime })

    @Test
    fun fiveHeldDmsBehindOneFreshAckAreEachAckedWithNoEvictionOrNoOutcome() =
        runTest {
            val rig = rig()
            val a = rig.board("alice")
            val b = rig.board("bob")
            rig.air.link("alice", "bob")
            val alice = rig.node(a, listOf("bob"))
            val bob = rig.node(b, listOf("alice"))
            // Five DMs a live link carried, so none aired; the link goes, and none has its ✓✓.
            alice.transport.suppressDataPath(setOf(bob.id))
            (1..5).forEach { rig.dm(alice, bob, "held-$it") }
            rig.settle(1_000)
            alice.transport.suppressDataPath(emptySet())
            rig.settle(1_000)
            assertEquals("nothing aired while linked", 0L, alice.metrics.lora().loraDirectSent)

            rig.dm(alice, bob, "fresh")
            rig.settle(120_000)
            val lora = alice.metrics.lora()
            assertEquals("the fresh DM and the five behind it: ${alice.logs.takeLast(30)}", 6, bob.delivered.size)
            assertEquals("no send pushed out of the table, none unanswered", 0L, lora.loraDirectNoOutcome)
            assertEquals(0L, lora.loraDirectUnreachable)
            assertEquals("every one acked by bob's board", 6L, lora.loraDirectAcked)
            assertEquals(
                "every one reached his radio",
                (listOf("fresh") + (1..5).map { "held-$it" }).map { "${bob.id}/$it" }.toSet(),
                alice.reached.toSet(),
            )
            assertEquals(6, alice.reached.size)
            assertTrue(alice.logs.any { it.startsWith("lora resend ${bob.id}/held-1 -> ${bob.id} (after ack, +4 with it)") })
        }

    /**
     * lora-reviewer's round 2 (blocking): attempts at absent boards end only at their deadlines, minutes on, so on a
     * two-flight table they may hold one place, never both. Bob's and Carol's boards are out of range and marked
     * unreachable; alice types to both, then to Dave beside her: Bob's attempt goes, Carol's waits behind it, and Dave's
     * DM goes at once and lands.
     */
    @Test
    fun attemptsAtAbsentBoardsNeverTakeTheLastFlightFromAPeerWhoIsThere() =
        runTest {
            val rig = rig()
            val a = rig.board("alice")
            rig.board("bob")
            rig.board("carol")
            val d = rig.board("dave")
            rig.air.link("alice", "dave") // bob's and carol's boards are out of range
            val alice = rig.node(a, listOf("bob", "carol", "dave"))
            val dave = rig.node(d, listOf("alice"))
            val bob = rig.idOf("bob")
            val carol = rig.idOf("carol")

            rig.dm(alice, dave, "d1")
            rig.settle(20_000)
            assertEquals("dave's board took it: reachable", 1, dave.delivered.size)
            alice.unacked.remove("${dave.id}/d1") // its ✓✓, so no re-probe comes into it
            rig.dm(alice, bob, "b1")
            rig.dm(alice, carol, "c1")
            rig.settle(MeshcoreSession.MAX_FLIGHT_MS + 60_000)
            assertEquals("both absent boards given up on", 2, alice.transport.status.value.direct.unreachable)

            rig.dm(alice, bob, "b2")
            rig.dm(alice, carol, "c2")
            rig.settle(2_000)
            val tx = "lora tx direct:message:"
            assertTrue("bob's attempt went: ${alice.logs.takeLast(20)}", alice.logs.any { it.startsWith("$tx$bob/b2") })
            assertTrue("carol's waits for a place", alice.logs.none { it.startsWith("$tx$carol/c2") })
            rig.dm(alice, dave, "d2")
            rig.settle(20_000)
            assertEquals("dave's DM went at once and landed", 2, dave.delivered.size)
            assertTrue(alice.logs.none { it.startsWith("$tx$carol/c2") })
            assertEquals("nothing pushed out of the table", 0L, alice.metrics.lora().loraDirectNoOutcome)
        }

    /**
     * lora-reviewer's round 3 (blocking): the marks go with the board, the re-probe schedule does not, so after a reconnect
     * (or a restored launch) two re-probes to absent boards read as unknown peers. A re-probe is capped by the reserve
     * whatever its peer's mark: one of the two goes, the other waits, and a DM typed to Dave beside her flies at once.
     */
    @Test
    fun reprobesToAbsentBoardsWithNoMarksNeverTakeTheLastFlightFromAPeerWhoIsThere() =
        runTest {
            val rig = rig()
            val a = rig.board("alice")
            rig.board("bob")
            rig.board("carol")
            val d = rig.board("dave")
            rig.air.link("alice", "dave") // bob's and carol's boards are out of range
            val alice = rig.node(a, listOf("bob", "carol", "dave"))
            val dave = rig.node(d, listOf("alice"))
            val bob = rig.idOf("bob")
            val carol = rig.idOf("carol")
            rig.dm(alice, bob, "b1")
            rig.dm(alice, carol, "c1")
            rig.settle(MeshcoreSession.MAX_FLIGHT_MS + 60_000)
            assertEquals(2, alice.transport.status.value.direct.unreachable)

            // The board goes and comes back: every mark is gone, the re-probe schedule stays.
            alice.config.value = null
            rig.settle(1_000)
            alice.config.value = LoraConfig("AA:alice", MeshcoreHooks.NO_CONFIGURED_SLOT, dms = true)
            rig.settle(20_000)
            assertEquals("no marks after the reconnect", 0, alice.transport.status.value.direct.unreachable)

            val tx = "lora tx direct:message:"

            fun reaired() = alice.logs.count { it.startsWith("$tx$bob/b1") || it.startsWith("$tx$carol/c1") } - 2
            val reprobed = alice.metrics.lora().loraReprobed
            var waited = 0L
            while (alice.metrics.lora().loraReprobed < reprobed + 2 && waited < LoraReprobe.BASE_MS + 120_000) {
                rig.settle(LoraMeshTransport.LINGER_SWEEP_MS)
                waited += LoraMeshTransport.LINGER_SWEEP_MS
            }
            assertEquals("both re-probes came due: ${alice.logs.takeLast(20)}", reprobed + 2, alice.metrics.lora().loraReprobed)
            assertEquals("one re-probe flies, the other waits for a place", 1, reaired())

            rig.dm(alice, dave, "d1")
            rig.settle(20_000)
            assertEquals("dave's DM went at once and landed", 1, dave.delivered.size)
            // The second re-probe goes only once the first one's flight has ended, never beside it.
            val lines = alice.logs.filter { it.startsWith("lora ") }
            val reairs = lines.withIndex().filter { (_, l) -> l.startsWith("$tx$bob/b1") || l.startsWith("$tx$carol/c1") }.drop(2)
            val ends = lines.withIndex().filter { (_, l) -> l.startsWith("lora direct unreachable ") || l.startsWith("lora direct acked ") }
            val first = reairs.first()
            val firstEnd = ends.firstOrNull { (i, l) -> i > first.index && l.contains(first.value.removePrefix(tx).substringBefore(' ')) }
            val daveTx = lines.indexOfFirst { it.startsWith("$tx${dave.id}/d1") }
            assertTrue("dave's DM went while the re-probe was still out: $lines", firstEnd == null || daveTx < firstEnd.index)
            reairs.getOrNull(1)?.let { second ->
                assertTrue(
                    "the second re-probe flew beside the first: $lines",
                    ends.any { (i, l) -> i in first.index..second.index && l.contains(first.value.removePrefix(tx).substringBefore(' ')) },
                )
            }
            assertEquals("nothing pushed out of the table", 0L, alice.metrics.lora().loraDirectNoOutcome)
        }

    /**
     * lora-reviewer's round 4 (blocking): with one re-probe out to an absent board, a friend's board acks a fresh DM and
     * the two DMs held for him go behind it, not behind the re-probe: a peer whose board acked this session is never
     * capped by the reserve, so both are acked before the re-probe ends.
     */
    @Test
    fun aFriendsAfterAckBatchNeverWaitsBehindAReprobeToAnAbsentBoard() =
        runTest {
            val rig = rig()
            val a = rig.board("alice")
            rig.board("bob")
            val d = rig.board("dave")
            rig.air.link("alice", "dave") // bob's board is out of range
            val alice = rig.node(a, listOf("bob", "dave"))
            val dave = rig.node(d, listOf("alice"))
            val bob = rig.idOf("bob")
            rig.dm(alice, bob, "b1")
            rig.settle(MeshcoreSession.MAX_FLIGHT_MS + 60_000)
            assertEquals(1, alice.transport.status.value.direct.unreachable)

            // Second by second to the re-probe of b1, so the scenario starts the moment it flies.
            val reprobed = alice.metrics.lora().loraReprobed
            var waited = 0L
            while (alice.metrics.lora().loraReprobed == reprobed && waited < LoraReprobe.BASE_MS) {
                rig.settle(1_000)
                waited += 1_000
            }
            assertEquals("the re-probe came due", reprobed + 1, alice.metrics.lora().loraReprobed)

            // Two DMs to dave a link carried, so neither aired; the link goes, then a fresh one and its ack.
            alice.transport.suppressDataPath(setOf(dave.id))
            rig.dm(alice, dave, "held-1")
            rig.dm(alice, dave, "held-2")
            rig.settle(100)
            alice.transport.suppressDataPath(emptySet())
            rig.dm(alice, dave, "fresh")
            rig.settle(60_000)

            val lines = alice.logs.filter { it.startsWith("lora ") }
            val reprobeTx = lines.indexOfLast { it.startsWith("lora tx direct:message:$bob/b1") }
            val reprobeEnd = lines.withIndex().firstOrNull { (i, l) -> i > reprobeTx && l.startsWith("lora direct unreachable $bob/b1") }
            val heldAcks = lines.withIndex().filter { (_, l) -> l.startsWith("lora direct acked ${dave.id}/held-") }
            assertEquals("both held DMs acked: $lines", 2, heldAcks.size)
            assertTrue(
                "behind the fresh ack, not behind the re-probe: $lines",
                reprobeEnd == null || heldAcks.all { it.index < reprobeEnd.index },
            )
            assertEquals("nothing pushed out of the table", 0L, alice.metrics.lora().loraDirectNoOutcome)
        }
}
