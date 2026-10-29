package app.getknit.knit.mesh.lab

import app.getknit.knit.mesh.BlobExchange
import org.junit.Assert.assertTrue
import kotlin.random.Random

/**
 * What the #195 scenarios share ([StrandedAttachmentLabTest], [PullBudgetLabTest]): the lab, the querying nodes whose
 * wall-clock timers never fire in a run, Sam's stranded DM picture, and the steps that move the calendar and the ticks
 * together. Not a scenario class of its own.
 */
abstract class StrandedLabBase {
    // Made and closed by each scenario class around its runs, under the chaos rule each declares itself.
    protected lateinit var lab: MeshLab

    // Every node queries. A step of a want also runs off timers on the wall clock (`BlobExchange.armTimer`): the
    // recheck of a fetching or pulled neighbour, the wait for answers, the first ask's grace, a holder's floor, the widen
    // gate, the stream poll. One left over from a scenario's setup fires between a clock jump and the next read, and
    // decides at the calendar's new reading. The two that always arm — the recheck and the answer wait — are an hour
    // here, longer than any step a scenario takes and than a cycle, so they never fire in a run and a recheck is never
    // due inside a cycle: what is counted is a cycle's own queries, from a tick the scenario pokes. The others arm only
    // around a holder or a blind ask, and decide what the next tick would (each case's step counts carry one step for it).
    protected val querying = LabLimits(blobQuery = true, fetchingRecheckMs = RECHECK_MS, queryWaitMs = RECHECK_MS)

    protected class Stranded(
        val sam: LabNode,
        val alice: LabNode,
        val bob: LabNode,
        val carol: LabNode,
        val id: String,
        val hash: String,
    )

    /** Waits until each node has run its link-up blob re-ask toward each of its peers: its digest goes after it. */
    protected suspend fun awaitLinkUpHooks(vararg pairs: Pair<LabNode, LabNode>) =
        lab.await(1) { if (pairs.all { (node, peer) -> peer.nodeId in node.transport.digestsSent }) 1 else 0 }

    /** Sam's DM picture reaches Alice as a row and a header, never as bytes, and Sam is gone. */
    protected suspend fun strand(limits: LabLimits): Stranded {
        val sam = lab.node("sam", limits).apply { setDisplayName("Sam") }
        val alice = lab.node("alice", limits).apply { setDisplayName("Alice") }
        val bob = lab.node("bob", limits).apply { setDisplayName("Bob") }
        val carol = lab.node("carol", limits).apply { setDisplayName("Carol") }
        lab.linkAll(alice to bob, alice to carol, bob to carol)
        lab.awaitAcquainted(alice, bob, carol)
        awaitLinkUpHooks(alice to bob, alice to carol, bob to alice, bob to carol, carol to alice, carol to bob)
        lab.link(sam, alice) { sam.transport.holdFiles(alice.transport) }
        lab.awaitAcquainted(sam, alice)
        awaitLinkUpHooks(sam to alice, alice to sam)

        assertTrue(sam.sendImage(Random(195).nextBytes(4_096), "never lands", to = alice))
        val id = sam.ownMessageId(sam.dmWith(alice), "never lands")
        val hash = checkNotNull(sam.attachmentHash(sam.dmWith(alice), id))
        lab.await(1) { if (alice.attachmentHash(alice.dmWith(sam), id) != null) 1 else 0 }
        lab.await(1) { sam.transport.heldFiles(alice.transport).count { it == hash } }
        // Bob and Carol carry the frame before anything moves: Alice's relay fires after the router's jitter, which no
        // drain sees, and one in flight across a clock jump lands on the far side of it.
        lab.await(1) { if (listOf(bob, carol).all { id in it.custodyIds() }) 1 else 0 }
        lab.retire(sam) // signed out with the only copy
        lab.await(1) { if (sam.nodeId !in alice.manager.linksActedOn) 1 else 0 }
        // Alice's next holder, once Sam's link went: Bob and Carol, asked to pull it through, and their echoes.
        quiesce(listOf(alice, bob, carol))
        return Stranded(sam, alice, bob, carol, id, hash)
    }

    /**
     * Until a whole pass over [nodes] has handed nobody a new frame: everything a tick set moving — a query, its answer,
     * a pull, the echo's own queries and pulls — has been handled.
     */
    protected suspend fun quiesce(nodes: List<LabNode>) {
        var before: Int
        do {
            before = nodes.sumOf { it.transport.sent.size }
            nodes.forEach { it.transport.awaitInboundDrained() }
        } while (nodes.sumOf { it.transport.sent.size } != before)
    }

    /** One step of the calendar: [CYCLE_STEP_MS] on, the sweep, then every node's 60 s tick. */
    protected suspend fun step(nodes: List<LabNode>) {
        lab.clock.advance(CYCLE_STEP_MS)
        nodes.forEach { it.sweepExpired() }
        nodes.forEach { it.reoffer() }
        quiesce(nodes)
    }

    protected fun LabNode.queriesTo(
        to: LabNode,
        hash: String,
    ) = transport.blobQueries.count { it.to == to.nodeId && hash in it.hashes }

    protected fun LabNode.asksOf(hash: String) = transport.blobAsks.count { it.hash == hash }

    protected fun LabNode.wants(hash: String) = hash in manager.wantedBlobs()

    companion object {
        const val MINUTE_MS = 60_000L
        const val HOUR_MS = 60 * MINUTE_MS

        // Past the longest dead-cycle gap a want reaches inside a day (6 min): every step opens one cycle.
        const val CYCLE_STEP_MS = 7 * MINUTE_MS

        // A day and an hour: past ForwardRepository.DEFAULT_TTL_MS, under the lab's 48 h bound.
        const val PAST_WINDOW_MS = 25 * HOUR_MS
        const val INSIDE_WINDOW_MS = HOUR_MS
        const val CYCLES = 5

        const val SHORT_CUSTODY_MS = 2 * HOUR_MS
        const val PAST_SHORT_CUSTODY_MS = 3 * HOUR_MS
        const val ECHO_CYCLES = 3

        // What was made from a want lives at most a TTL past the last ask that kept it, with a hop less (#195): a TTL in
        // steps (4: 28 min) and the step whose sweep finds it past, for each budget from ours down to 0, and one step
        // for a pull a timer can still make between a jump and the sweep.
        const val GENERATIONS_STEPS = ((BlobExchange.PULL_BUDGET + 1) * (BlobExchange.FETCH_TTL_MS / CYCLE_STEP_MS + 1) + 1).toInt()

        // An hour, past every gap a want reaches in a scenario and past any wall time one takes.
        const val RECHECK_MS = HOUR_MS

        // The frame's hour of custody in steps; then, once nothing re-arms the carriers' wants, the generations bound.
        const val CUSTODY_GONE_STEPS = (HOUR_MS / CYCLE_STEP_MS + 2).toInt()
        const val CARRIERS_GONE_STEPS = GENERATIONS_STEPS

        // A minute's tick, a quarter of an hour of them: f7gg's pace would ask every one.
        const val BLIND_TICKS = 15

        // (f): the link drops and re-forms every 10 min, for two hours.
        const val FLAP_MS = 10 * MINUTE_MS
        const val FLAP_ROUNDS = 12

        // (g): two steps take Bob past his echo's first two cycles; a cycle gap at most is 6 min, under a step.
        const val ECHO_SPENT_STEPS = 2

        // (h): a dead streak's first cycles, the day, and the long gaps after it: 12 min, then 24 (a step past each).
        const val STREAK_STEPS = 3
        const val A_DAY_MS = 24 * HOUR_MS
        const val LONG_GAP_MS = 13 * MINUTE_MS
        const val NEXT_LONG_GAP_MS = 25 * MINUTE_MS

        // (i): a stranger every 20 min for three hours, two minutes each.
        const val CHURN_EVERY_MS = 20 * MINUTE_MS
        const val CHURN_STAY_MS = 2 * MINUTE_MS
        const val CHURN_ROUNDS = 9

        // (j): the line between the root and the holder its budget reaches, and how many steps the bytes may take back
        // along it — a hop a step, at the next cycle of each, and a few for the pulls going out.
        const val PULL_BUDGET_HOPS = BlobExchange.PULL_BUDGET
        const val REACH_STEPS = 2 * (BlobExchange.PULL_BUDGET + 2)

        // (l): one TTL in steps and the step whose sweep finds it past; three of them watched. (m): a few cycles past the
        // delivery. (n): a picture big enough to cut, and where.
        const val TTL_STEPS = (BlobExchange.FETCH_TTL_MS / CYCLE_STEP_MS + 2).toInt()
        const val MIXED_STEPS = 3 * TTL_STEPS
        const val LATE_STEPS = 3
        const val SPLICE_BYTES = 200_000
        const val SPLICE_CUT = 80_000L

        // (k): a fetch-only want's TTL in steps, and the step whose sweep finds it past.
        const val ROOT_GONE_STEPS = (BlobExchange.FETCH_TTL_MS / CYCLE_STEP_MS + 2).toInt()
    }
}
