package app.getknit.knit.mesh.lab

import app.getknit.knit.mesh.lora.FakeMeshtasticAir
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
 * Read receipts across the pair (ADR 2026-11.z6jw): a reader tells an author it read their DM or group message, sealed,
 * the way the ✓✓ travels — riding a receipt or a DM going that way anyway, else one answer-only `CTL_RECEIPT` of its own
 * at its deadline, custodied. Each scenario ends with the thread converged and the author's `readAt` recorded for the
 * reads that went (or for none, with the setting off).
 *
 * Deadlines run on the lab clock, lengthened ([LINKED_MS], [ABSENT_MS]) so a slow run's wake never ends one: a scenario
 * advances it past the deadline and heals the reader, whose basket runs `ReadSync.flushDue`.
 */
@RunWith(RobolectricTestRunner::class)
class ReadReceiptLabTest {
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

    /** When [reader]'s read of [id] reached this author, null while it has not. */
    private suspend fun LabNode.readAtBy(
        id: String,
        reader: LabNode,
    ): Long? =
        db
            .messageReceiptDao()
            .forMessages(listOf(id))
            .firstOrNull { it.ackerNodeId == reader.nodeId }
            ?.readAt

    private suspend fun awaitRead(
        author: LabNode,
        id: String,
        reader: LabNode,
        timeoutMs: Long = MeshLab.AWAIT_MS,
    ) = lab.await(1, timeoutMs) { if (author.readAtBy(id, reader) != null) 1 else 0 }

    /** [reader] saw [ids]: the UI's call. */
    private suspend fun LabNode.reads(vararg ids: String) = manager.onMessagesRead(ids.toList())

    /** Runs a deadline out on the lab clock and heals [reader], whose basket sends what is due. */
    private suspend fun pastDeadline(
        reader: LabNode,
        ms: Long,
    ) {
        // lab-lint: allow clock-jump-over-48h — every caller passes a deadline of 15 min or less, a few per scenario
        lab.clock.advance(ms + 1)
        reader.heal()
    }

    private suspend fun pair(): Pair<LabNode, LabNode> {
        val alice = lab.node("alice", READS).apply { setDisplayName("Alice") }
        val bob = lab.node("bob", READS).apply { setDisplayName("Bob") }
        lab.link(alice, bob)
        lab.awaitAcquainted(alice, bob)
        // Accepted, not message requests: a request's reads are never told.
        lab.befriend(alice, bob)
        return alice to bob
    }

    /** Alice's DM [body] to bob, once it has landed with him; its id. */
    private suspend fun dmLands(
        alice: LabNode,
        bob: LabNode,
        body: String,
    ): String {
        assertTrue(alice.sendDm(bob, body))
        val id = alice.ownMessageId(alice.dmWith(bob), body)
        lab.await(1) { bob.decrypted(bob.dmWith(alice)).count { it.first == id } }
        return id
    }

    /** 1. A DM read over a live link lands on the linked deadline, and a link cut right after the flush still converges. */
    @Test
    fun aDmReadOverALiveLinkReachesItsAuthor() =
        runBlocking {
            val (alice, bob) = pair()
            val first = dmLands(alice, bob, "read me")
            // Its ✓✓ already with alice: the read cannot ride it, so the deadline's own frame carries it.
            lab.awaitReceipt(alice, first, bob)
            bob.reads(first)
            pastDeadline(bob, LINKED_MS)
            lab.await(1) {
                bob.metrics
                    .receipts()
                    .readsCustodied
                    .toInt()
            }
            awaitRead(alice, first, bob)

            val second = dmLands(alice, bob, "and me")
            lab.awaitReceipt(alice, second, bob)
            bob.reads(second)
            pastDeadline(bob, LINKED_MS)
            lab.unlink(alice, bob)
            lab.link(alice, bob)
            lab.assertConverged(listOf(alice, bob), atLeast = 2) { it.dmWith(if (it === alice) bob else alice) }
            awaitRead(alice, second, bob)
            // One per deadline, and one more per batch a root move owed again (a first-contact race).
            val r = bob.metrics.receipts()
            assertTrue(
                "one standalone frame per deadline at most (${r.readsCustodied}, re-owed ${r.readsReOwed})",
                r.readsCustodied <= 2 + r.readsReOwed,
            )
        }

    /** 2. The author is gone when the read is made: a third node's custody carries it home. */
    @Test
    fun aReadForAnAbsentAuthorRidesCustody() =
        runBlocking {
            val alice = lab.node("alice", READS).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", READS).apply { setDisplayName("Bob") }
            val carol = lab.node("carol", READS).apply { setDisplayName("Carol") }
            lab.linkAll(alice to bob, alice to carol, bob to carol)
            lab.awaitAcquainted(alice, bob, carol)
            // Accepted, not message requests: a request's reads are never told.
            lab.befriend(alice, bob, carol)
            val id = dmLands(alice, bob, "while you were out")
            // Its own ✓✓ is already with alice, so the read cannot ride it: nothing goes alice's way but a frame of its own.
            lab.awaitReceipt(alice, id, bob)
            lab.unlink(alice, bob)
            lab.unlink(alice, carol)

            val carried = carol.custodyIdsFrom(bob)
            bob.reads(id)
            pastDeadline(bob, ABSENT_MS)
            // The standalone frame, into custody: a heal that found a basket already running leaves it to that one.
            lab.await(1) {
                bob.metrics
                    .receipts()
                    .readsCustodied
                    .toInt()
            }
            // Carol holds a frame of bob's she did not before the read: the read's own.
            lab.await(1) { (carol.custodyIdsFrom(bob) - carried).size }
            lab.unlink(bob, carol)
            assertNull(alice.readAtBy(id, bob))

            lab.link(carol, alice)
            awaitRead(alice, id, bob)
            lab.linkAll(alice to bob, bob to carol)
            lab.assertConverged(listOf(alice, bob), atLeast = 1, carriers = listOf(carol)) { it.dmWith(if (it === alice) bob else alice) }
        }

    /** 3. A group of three: the author records both readers, each by their own read. */
    @Test
    fun aGroupsAuthorRecordsEveryReader() =
        runBlocking {
            val alice = lab.node("alice", READS).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", READS).apply { setDisplayName("Bob") }
            val carol = lab.node("carol", READS).apply { setDisplayName("Carol") }
            lab.linkAll(alice to bob, alice to carol, bob to carol)
            lab.awaitAcquainted(alice, bob, carol)
            // Accepted, not message requests: a request's reads are never told.
            lab.befriend(alice, bob, carol)
            val group = alice.createGroup(bob, carol)
            assertTrue(alice.sendGroup(group, "read by all"))
            val id = alice.ownMessageId(group, "read by all")
            listOf(bob, carol).forEach { m -> lab.await(1) { m.decrypted(group).count { it.first == id } } }

            bob.reads(id)
            pastDeadline(bob, LINKED_MS)
            awaitRead(alice, id, bob)
            assertNull("carol has not read it", alice.readAtBy(id, carol))
            carol.reads(id)
            pastDeadline(carol, LINKED_MS)
            awaitRead(alice, id, carol)
            lab.assertConverged(listOf(alice, bob, carol), atLeast = 1) { group }
        }

    /** 4. The setting off: nothing read leaves the reader, and the author records no read. */
    @Test
    fun withTheSettingOffNothingIsSent() =
        runBlocking {
            val (alice, bob) = pair()
            bob.settings.setReadReceiptsEnabled(false)
            val id = dmLands(alice, bob, "unread as far as you know")
            bob.reads(id)
            pastDeadline(bob, ABSENT_MS)
            lab.assertConverged(listOf(alice, bob), atLeast = 1) { it.dmWith(if (it === alice) bob else alice) }
            val r = bob.metrics.receipts()
            assertEquals(0L, r.readsSealed + r.readsCustodied + r.readsSpooled + r.readsRidden)
            assertNull(alice.readAtBy(id, bob))
        }

    /**
     * 5a. A group read rides the next escalated group tick toward its author (AckSync's batch, custodied anyway): the
     * reader's custody rows grow by the ticks alone, never by a read frame of its own.
     */
    @Test
    fun aGroupReadRidesTheNextTickBatchAndAddsNoCustodyRow() =
        runBlocking {
            val alice = lab.node("alice", READS).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", READS).apply { setDisplayName("Bob") }
            val carol = lab.node("carol", READS).apply { setDisplayName("Carol") }
            lab.linkAll(alice to bob, alice to carol, bob to carol)
            lab.awaitAcquainted(alice, bob, carol)
            // Accepted, not message requests: a request's reads are never told.
            lab.befriend(alice, bob, carol)
            val group = alice.createGroup(bob, carol)
            assertTrue(alice.sendGroup(group, "warm up"))
            lab.assertConverged(listOf(alice, bob, carol), atLeast = 1) { group }
            // Alice a hop away from bob, through carol: his ticks for her posts batch and escalate into custody.
            lab.unlink(alice, bob)
            val first = groupPostLands(alice, bob, group, "first")
            lab.awaitReceipt(alice, first, bob) // its own tick escalated and crossed
            val rowsBefore = bob.custodyIdsFrom(bob).size
            bob.reads(first)
            val second = groupPostLands(alice, bob, group, "second")
            awaitRead(alice, first, bob)
            // It went on a tick going alice's way anyway: the next post's (as a rider) or a re-served first post's re-ack
            // (its own bit). Never a frame of its own.
            // Told after the hand-off returns, so alice may hold the read a moment before bob counts it.
            lab.await(1) {
                bob.metrics
                    .receipts()
                    .readsSealed
                    .toInt()
            }
            assertEquals("no read frame of its own", 0L, bob.metrics.receipts().readsCustodied)
            val rows = bob.custodyIdsFrom(bob).size - rowsBefore
            assertTrue("bob's new rows are ticks: the second post's, and a re-ack at most ($rows)", rows in 1..2)
            lab.link(alice, bob)
            lab.assertConverged(listOf(alice, bob, carol), atLeast = 3) { group }
            assertNull("the second is unread", alice.readAtBy(second, bob))
        }

    /** Alice's group post [body], once bob holds it; its id. */
    private suspend fun groupPostLands(
        alice: LabNode,
        bob: LabNode,
        group: String,
        body: String,
    ): String {
        assertTrue(alice.sendGroup(group, body))
        val id = alice.ownMessageId(group, body)
        lab.await(1) { bob.decrypted(group).count { it.first == id } }
        return id
    }

    /** 5b. A DM read with its chat open rides its own receipt or the next DM's: no frame of its own. */
    @Test
    fun aDmReadRidesTheNextReceipt() =
        runBlocking {
            val (alice, bob) = pair()
            val first = dmLands(alice, bob, "one")
            bob.reads(first)
            val second = dmLands(alice, bob, "two")
            awaitRead(alice, first, bob)
            // Its own receipt (a read made before it was sealed sets its bit) or the next DM's: never a frame of its own.
            assertEquals("no read frame of its own", 0L, bob.metrics.receipts().readsCustodied)
            bob.reads(second)
            pastDeadline(bob, LINKED_MS)
            awaitRead(alice, second, bob)
            // At most one, for the last read: it may also have ridden its own receipt, if read before that was sealed.
            assertTrue("one standalone frame at most", bob.metrics.receipts().readsCustodied <= 1L)
            lab.assertConverged(listOf(alice, bob), atLeast = 2) { it.dmWith(if (it === alice) bob else alice) }
        }

    /**
     * 5c. A read burst never evicts the reader's own frames from a carrier: bob's bucket at carol sits [BURST_BUDGET] rows
     * under her per-sender cap, his own undelivered DMs to dave among them, and a burst of reads toward more absent authors
     * than the budget allows costs exactly the budget in custody rows. The budget is what keeps the rest.
     */
    @Test
    fun aReadBurstLeavesTheReadersOwnDmsInCustody() =
        runBlocking {
            val bob = lab.node("bob", READS.copy(readBudget = BURST_BUDGET)).apply { setDisplayName("Bob") }
            val carol = lab.node("carol", READS.copy(custodyMaxPerSender = BURST_CAP)).apply { setDisplayName("Carol") }
            val dave = lab.node("dave", READS).apply { setDisplayName("Dave") }
            val authors = listOf("alice", "erin", "frank").map { lab.node(it, READS).apply { setDisplayName(it) } }
            val everyone = authors + listOf(bob, carol, dave)
            lab.linkAll(authors[0] to bob, authors[1] to bob, authors[2] to bob, bob to carol, bob to dave)
            lab.awaitAcquainted(*everyone.toTypedArray())
            // Accepted, not message requests: a request's reads are never told.
            lab.befriend(*everyone.toTypedArray())
            val read = authors.map { dmLands(it, bob, "from ${it.name}") }
            authors.zip(read).forEach { (a, id) -> lab.awaitReceipt(a, id, bob) }
            authors.forEach { lab.unlink(it, bob) }
            lab.unlink(bob, dave)
            // A heal first, with nothing read: what bob's basket originates by itself (a sealed profile to a session that
            // confirmed late, a re-send) lands in his bucket now, not inside the read burst's.
            pastDeadline(bob, ABSENT_MS)
            carol.transport.awaitInboundDrained()
            // Bob's own DMs to the absent dave fill his bucket at carol to the budget's slack under her cap.
            var sent = 0
            while (carol.custodyIdsFrom(bob).size < BURST_CAP - BURST_BUDGET) {
                val body = "for dave ${sent++}"
                assertTrue(bob.sendDm(dave, body))
                val dm = bob.ownMessageId(bob.dmWith(dave), body)
                lab.await(1) { if (dm in carol.custodyIds()) 1 else 0 }
            }
            val preset = carol.custodyIdsFrom(bob)
            assertEquals(BURST_CAP - BURST_BUDGET, preset.size)

            read.forEach { bob.reads(it) }
            pastDeadline(bob, ABSENT_MS)
            // The read frames, by id: what bob's bucket gained.
            lab.await(BURST_BUDGET) { (carol.custodyIdsFrom(bob) - preset).size }
            assertEquals("the budget's worth of frames", BURST_BUDGET.toLong(), bob.metrics.receipts().readsCustodied)
            assertTrue("the rest held by the budget", bob.metrics.receipts().readsBudgetHeld >= 1)
            carol.transport.awaitInboundDrained()
            val gone = preset.filterNot { it in carol.custodyIds() }
            assertTrue("nothing of bob's was evicted: $gone, gained ${carol.custodyIdsFrom(bob) - preset}", gone.isEmpty())

            authors.forEach { lab.link(it, carol) }
            val landed = authors.zip(read).count { (a, id) -> lab.tryAwait(1) { if (a.readAtBy(id, bob) != null) 1 else 0 } }
            assertEquals("the budget's reads landed through carol", BURST_BUDGET, landed)
            lab.link(carol, dave)
            lab.assertConverged(listOf(bob, dave), atLeast = sent, carriers = listOf(carol)) { it.dmWith(if (it === bob) dave else bob) }
        }

    /**
     * 6. The author resets after the read went out: the read is owed again under the new root and lands again — proven on
     * alice's side by a new application of it ([readsRecorded][app.getknit.knit.mesh.MeshMetrics.onReadsRecorded]), since
     * her `readAt` was already set and is fill-null-only.
     */
    @Test
    fun aResetAfterTheReadLeftOwesItAgain() =
        runBlocking {
            val (alice, bob) = pair()
            val id = dmLands(alice, bob, "before the reset")
            // Its ✓✓ with alice first, so no late tell of bob's instant receipt lands after the baselines below.
            lab.awaitReceipt(alice, id, bob)
            bob.reads(id)
            pastDeadline(bob, LINKED_MS)
            awaitRead(alice, id, bob)
            lab.await(1) {
                bob.metrics
                    .receipts()
                    .readsSealed
                    .toInt()
            }

            val reOwed = bob.metrics.receipts().readsReOwed
            val sealed = bob.metrics.receipts().readsSealed
            val recorded = alice.metrics.receipts().readsRecorded
            assertNull(alice.resetSession(bob))
            lab.await(1) { (bob.metrics.receipts().readsReOwed - reOwed).toInt() }
            pastDeadline(bob, LINKED_MS)
            lab.await(1) { (bob.metrics.receipts().readsSealed - sealed).toInt() }
            lab.await(1) { (alice.metrics.receipts().readsRecorded - recorded).toInt() }
            lab.assertConverged(listOf(alice, bob), atLeast = 1) { it.dmWith(if (it === alice) bob else alice) }
        }

    /**
     * 7. LoRa, no packet of its own: a read inside the coalescer's hold rides the compact ✓✓ and reaches the author with
     * every other path cut, with the same packet count as a DM nobody read; a read after the hold airs nothing and lands
     * later over custody.
     */
    @Test
    fun overLoraAReadRidesTheCompactTickOrWaits() =
        runBlocking {
            val held = READS.copy(dmAckHoldMs = HOLD_MS, loraLingerSweepMs = 200L)
            val air = MeshLab.quickAir()
            val alice = lab.node("alice", held, air = air).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", held, air = air).apply { setDisplayName("Bob") }
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)
            // Accepted, not message requests: a request's reads are never told.
            lab.befriend(alice, bob)
            lab.await(1) { alice.boardReadyFor(bob) }
            lab.await(1) { bob.boardReadyFor(alice) }
            val warm = dmLands(alice, bob, "warm up")
            lab.await(1) { if (bob.session(alice)?.confirmed == true) 1 else 0 }
            // Its ✓✓ in hand, so no re-offer of it airs into the counts below.
            lab.awaitReceipt(alice, warm, bob)
            lab.unlink(alice, bob)

            // A DM nobody reads, for the count.
            val plainAired = bob.airedFor(air, alice) { dmLands(alice, bob, "not read") }
            // A DM read inside the hold.
            var readId = ""
            val readAired =
                bob.airedFor(air, alice) {
                    readId = dmLands(alice, bob, "read in the hold")
                    bob.reads(readId)
                    readId
                }
            assertEquals("the read cost no packet", plainAired, readAired)
            awaitRead(alice, readId, bob)
            assertEquals(1L, bob.metrics.receipts().readsLoraOffered)

            // A read after the hold: no packet, owed until custody carries it.
            val late = dmLands(alice, bob, "read late")
            bob.awaitHeld(alice, late)
            bob.endHold()
            lab.awaitReceipt(alice, late, bob)
            val before = air.knit(bob)
            val offered = bob.metrics.receipts().readsLoraOffered
            bob.reads(late)
            pastDeadline(bob, ABSENT_MS)
            // Synchronous: nothing read was offered to the plane, so nothing can air later.
            assertEquals("a read after the hold is never offered to LoRa", offered, bob.metrics.receipts().readsLoraOffered)
            assertNull("no path but LoRa, and the read is not on it", alice.readAtBy(late, bob))

            lab.link(alice, bob)
            awaitRead(alice, late, bob)
            assertEquals("and it never aired", before, air.knit(bob))
            lab.assertConverged(listOf(alice, bob), atLeast = 4) { it.dmWith(if (it === alice) bob else alice) }
        }

    /** The ✓✓ for [author]'s DM [id] is held after the row commits: end the hold only once it is in it. */
    private suspend fun LabNode.awaitHeld(
        author: LabNode,
        id: String,
    ) = lab.await(1) { if (id in manager.dmAcks.pending(author.nodeId)) 1 else 0 }

    /** Ends bob's coalescer hold and heals, so its flush (signed tick plus compact ✓✓) goes now. */
    private suspend fun LabNode.endHold() {
        lab.clock.advance(HOLD_MS + 1)
        heal()
    }

    /** What this node aired on [air] for the DM [block] delivered to it and its ✓✓, once [author] holds that ✓✓. */
    private suspend fun LabNode.airedFor(
        air: FakeMeshtasticAir,
        author: LabNode,
        block: suspend () -> String,
    ): Int {
        val before = air.knit(this)
        val id = block()
        awaitHeld(author, id)
        endHold()
        lab.awaitReceipt(author, id, this)
        return air.knit(this) - before
    }

    /** The Knit packets [node]'s board aired, as `LoraDirectLabTest` counts them. */
    private fun FakeMeshtasticAir.knit(node: LabNode): Int = knitAirings(node.boardNum)

    private companion object {
        /**
         * The read deadlines here: longer than the field's, so only a scenario's own clock jump ends one and a slow run's
         * real-time wake never sends a read a ride was meant to carry.
         */
        const val LINKED_MS = 10 * 60_000L
        const val ABSENT_MS = 30 * 60_000L
        val READS = LabLimits(readLinkedDeadlineMs = LINKED_MS, readAbsentBaseMs = ABSENT_MS)

        /** The coalescer's hold in the LoRa scenario: long enough that only the scenario ends it. */
        const val HOLD_MS = 5 * 60_000L

        /** 5c: carol's per-sender cap, and bob's budget of standalone read frames — the slack under it. */
        const val BURST_CAP = 24
        const val BURST_BUDGET = 2
    }
}
