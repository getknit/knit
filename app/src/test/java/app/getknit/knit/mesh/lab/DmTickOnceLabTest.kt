package app.getknit.knit.mesh.lab

import app.getknit.knit.data.message.DeliveryPlane
import app.getknit.knit.mesh.DropReason
import app.getknit.knit.mesh.lora.FakeMeshtasticAir
import app.getknit.knit.mesh.lora.MeshtasticProto
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * One DM, one signed ✓✓ (#300, ADR 2026-10.8hn6's 2026-11-06 amendment), whichever way its two copies reach the
 * recipient. Bob and alice each hold a board, carol carries between them on a line, and bob's DM reaches alice twice:
 * compact over the board and signed through carol. Each scenario pins one order of those copies against the
 * coalescer's flush, counts alice's seals, and shows the compact ✓✓ still crossing.
 *
 * The hold is five minutes and each scenario ends it itself ([endTheHold]): the flush then lands exactly where the order
 * wants it, and no real-time wake reaches it. Five stays inside the ledger's 15 min floor, so a jump past the hold is
 * never a jump past the answer. Every node names `air`, so a boards sweep leaves that hold alone.
 */
@RunWith(RobolectricTestRunner::class)
class DmTickOnceLabTest {
    private lateinit var lab: MeshLab

    @get:Rule
    val chaos = LabChaos.rule()

    @Before
    fun setUp() {
        lab = MeshLab()
    }

    @After
    fun tearDown() {
        lab.close()
    }

    private val held = LabLimits(dmAckHoldMs = HOLD_MS, loraLingerSweepMs = 200L)

    private class Line(
        val air: FakeMeshtasticAir,
        val alice: LabNode,
        val bob: LabNode,
        val carol: LabNode,
    )

    /** Alice and bob confirmed on one session, carol holding what they said, then all three apart. */
    private suspend fun line(): Line {
        val air = MeshLab.quickAir()
        val alice = lab.node("alice", held, air = air).apply { setDisplayName("Alice") }
        val bob = lab.node("bob", held, air = air).apply { setDisplayName("Bob") }
        val carol = lab.node("carol", held, board = false).apply { setDisplayName("Carol") }
        lab.linkAll(alice to bob, alice to carol, bob to carol)
        lab.awaitAcquainted(alice, bob, carol)
        lab.await(1) { alice.boardReadyFor(bob) }
        lab.await(1) { bob.boardReadyFor(alice) }
        assertTrue(alice.sendDm(bob, "hello"))
        lab.await(1) { if (bob.session(alice)?.confirmed == true) 1 else 0 }
        assertTrue(bob.sendDm(alice, "hi"))
        lab.assertConverged(listOf(alice, bob), atLeast = 2, carriers = listOf(carol)) { it.dmWith(if (it === alice) bob else alice) }
        listOf(alice to bob, alice to carol, bob to carol).forEach { (a, b) -> lab.unlink(a, b) }
        return Line(air, alice, bob, carol)
    }

    /** Bob's DM, its signed copy left in carol's custody and carol gone from bob: only she can bring it to alice. */
    private suspend fun Line.bobSends(body: String): String {
        lab.link(carol, bob)
        lab.awaitAcquainted(carol, bob)
        assertTrue(bob.sendDm(alice, body))
        val dm = bob.ownMessageId(bob.dmWith(alice), body)
        lab.await(1) { if (dm in carol.custodyIds()) 1 else 0 }
        lab.unlink(carol, bob)
        return dm
    }

    /**
     * Waits for alice's coalescer to hold bob's DM. The row alone says nothing of it: `deliverChat` acks after its commit,
     * and a hold stamped after [endTheHold]'s jump would not fall due at that heal. The count moves after the hold.
     */
    private suspend fun Line.awaitTickHeld(deferredBefore: Long) {
        lab.await(1) { (alice.metrics.lora().loraTickDeferred - deferredBefore).toInt() }
    }

    /** Carol brings alice the signed copy; [settled] is what shows alice is done with it, and she leaves. */
    private suspend fun Line.signedCopyLands(settled: suspend () -> Int) {
        lab.link(carol, alice)
        lab.await(1) { settled() }
        lab.unlink(carol, alice)
    }

    /** Runs the hold out on the clock; [LabNode.heal]'s basket flushes it and returns once the flush did. */
    private suspend fun Line.endTheHold() {
        lab.clock.advance(HOLD_MS + 1)
        alice.heal()
    }

    private fun LabNode.sealed(): Long = metrics.receipts().receiptsSealed

    private fun LabNode.skipped(): Long = metrics.receipts().receiptsReAckSkipped

    private suspend fun holds(
        node: LabNode,
        author: LabNode,
        id: String,
    ): Int = node.decrypted(node.dmWith(author)).count { it.first == id }

    private suspend fun Line.converge() {
        lab.linkAll(alice to bob, alice to carol, bob to carol)
        lab.assertConverged(listOf(alice, bob), atLeast = 3, carriers = listOf(carol)) { it.dmWith(if (it === alice) bob else alice) }
    }

    /**
     * Compact, then signed, then the flush: the signed copy's re-ack seals at once, the coalescer is holding the same id,
     * and its flush sends the compact ✓✓ alone. Bob is apart from everyone, so a ✓✓ he reads came over the board, and the
     * flush reached its compact offer only past the point where it would have sealed.
     */
    @Test
    fun aCompactCopyThenItsSignedCopyDrawOneSignedReceipt() =
        runBlocking {
            val line = line()
            val (alice, bob) = line.alice to line.bob
            val before = alice.sealed()
            val deferred = alice.metrics.lora().loraTickDeferred
            val dm = line.bobSends("over both")
            lab.await(1) { holds(alice, bob, dm) }
            line.awaitTickHeld(deferred)
            assertEquals("the board's copy waits in the hold", before, alice.sealed())

            line.signedCopyLands { (alice.sealed() - before).toInt() }
            assertTrue("the signed copy ran its step", alice.loraSeen(dm)?.ratchetDone == true)

            line.endTheHold()
            assertEquals("the compact ✓✓ still crossed", DeliveryPlane.LoRa, lab.awaitReceipt(bob, dm, alice))
            assertEquals("one signed ✓✓ for $dm\n${lab.report(listOf(alice, bob))}", before + 1, alice.sealed())
            line.converge()
            assertEquals(before + 1, alice.sealed())
        }

    /**
     * Compact, the flush, then signed: the flush seals and files its tick, so the signed copy's re-ack finds the id
     * answered. Its ratchet step still runs: only the receipt is spared.
     */
    @Test
    fun aFlushThenTheSignedCopyDrawOneSignedReceipt() =
        runBlocking {
            val line = line()
            val (alice, bob) = line.alice to line.bob
            val before = alice.sealed()
            val deferred = alice.metrics.lora().loraTickDeferred
            val dm = line.bobSends("flushed first")
            lab.await(1) { holds(alice, bob, dm) }
            line.awaitTickHeld(deferred)

            line.endTheHold()
            assertEquals("the compact ✓✓ crossed", DeliveryPlane.LoRa, lab.awaitReceipt(bob, dm, alice))
            assertEquals("the flush sealed it", before + 1, alice.sealed())

            val skipped = alice.skipped()
            line.signedCopyLands { (alice.skipped() - skipped).toInt() }
            assertTrue("the signed copy ran its step", alice.loraSeen(dm)?.ratchetDone == true)
            assertEquals("one signed ✓✓ for $dm\n${lab.report(listOf(alice, bob))}", before + 1, alice.sealed())
            line.converge()
            assertEquals(before + 1, alice.sealed())
        }

    /**
     * Signed, then compact: the board loses the first airing, the signed copy delivers and seals its instant receipt, and
     * the compact copy then lands as a 2.8 board replays it, into the coalescer. Its flush adds the compact ✓✓ alone.
     */
    @Test
    fun aSignedCopyThenItsCompactCopyDrawOneSignedReceipt() =
        runBlocking {
            val line = line()
            val (alice, bob) = line.alice to line.bob
            val before = alice.sealed()
            val aired = checkNotNull(bob.boardLink).sent.size
            line.air.lossy = { from, to -> from == bob.boardNum && to == alice.boardNum }
            val dm = line.bobSends("signed first")
            lab.await(1) { checkNotNull(bob.boardLink).sent.drop(aired).count { it[0] == LORA_DIRECT_TAG_BYTE } }
            val packet = checkNotNull(bob.boardLink).sent.drop(aired).first { it[0] == LORA_DIRECT_TAG_BYTE }

            line.signedCopyLands { (alice.sealed() - before).toInt() }
            assertEquals("the signed copy delivered it", 1, holds(alice, bob, dm))

            val delivered = "lora direct delivered $dm from ${bob.nodeId}"
            checkNotNull(alice.boardLink).deliver(
                bob.boardNum,
                0,
                MeshtasticProto.PORT_PRIVATE_APP,
                packet,
                to = alice.boardNum,
                wantAck = true,
                pkiEncrypted = true,
            )
            lab.await(1) { alice.loraLog.count { it == delivered } }

            line.endTheHold()
            assertEquals("the compact ✓✓ crossed", DeliveryPlane.LoRa, lab.awaitReceipt(bob, dm, alice))
            assertEquals("one signed ✓✓ for $dm\n${lab.report(listOf(alice, bob))}", before + 1, alice.sealed())
            line.air.lossy = { _, _ -> false }
            line.converge()
            assertEquals(before + 1, alice.sealed())
        }

    /**
     * Declined, then the root moves. Alice's own reset is unanswered, so the signed copy's re-ack is declined into the
     * hold while the coalescer holds the same id. Bob's answer moves the root: the held ✓✓ seals, on the collector that
     * move woke, beside whatever the re-served copies draw on the pipeline, and the coalescer's flush airs compact alone.
     */
    @Test
    fun aDeclinedReAckAndTheCoalescerDrawOneSignedReceiptOnceTheRootMoves() =
        runBlocking {
            val line = line()
            val (alice, bob) = line.alice to line.bob
            assertNull("alice's reset went out", alice.resetSession(bob))
            val before = alice.sealed()
            val deferred = alice.metrics.lora().loraTickDeferred
            val dm = line.bobSends("while you reset")
            lab.await(1) { holds(alice, bob, dm) }
            line.awaitTickHeld(deferred)

            val heldBefore = alice.metrics.receipts().receiptsHeld
            line.signedCopyLands {
                val stepped = alice.loraSeen(dm)?.ratchetDone == true
                val judged = stepped || alice.drops(DropReason.LORA_DIRECT_RATCHET_MISSED) > 0
                if (judged) (alice.metrics.receipts().receiptsHeld - heldBefore).toInt() else 0
            }
            assertEquals("nothing sealed on her unanswered reset", before, alice.sealed())

            lab.link(alice, bob)
            lab.await(1) { (alice.sealed() - before).toInt() }
            assertTrue("bob answered the reset", alice.session(bob)?.confirmed == true)
            lab.unlink(alice, bob)

            val airedBefore = line.air.knitAirings(alice.boardNum)
            line.endTheHold()
            lab.await(airedBefore + 1) { line.air.knitAirings(alice.boardNum) }
            assertEquals("one signed ✓✓ for $dm\n${lab.report(listOf(alice, bob))}", before + 1, alice.sealed())
            line.converge()
            assertEquals(before + 1, alice.sealed())
        }

    private companion object {
        /** Under the ledger's 15 min floor, and past any real time a scenario runs for. */
        const val HOLD_MS = 5 * 60_000L

        /** `LORA_DIRECT_TAG`, the compact DM's first byte, as the fake board's sent payload starts. */
        const val LORA_DIRECT_TAG_BYTE: Byte = 0x11
    }
}
