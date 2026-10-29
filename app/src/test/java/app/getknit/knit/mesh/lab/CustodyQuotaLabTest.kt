package app.getknit.knit.mesh.lab

import app.getknit.knit.data.message.Conversations
import app.getknit.knit.mesh.DropReason
import app.getknit.knit.mesh.SeenSet
import app.getknit.knit.mesh.protocol.WireEnvelope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The custody store's bounds, shrunk through [LabLimits] so a handful of frames is a full store. A sender, group or
 * room bucket keeps its newest by the frame-global `(sentAt, id)`, and the global cap takes the oldest frame of the
 * largest sender bucket (ADR 2026-10.7mqm), on every node alike — ADR 006, the rule whose breach (evicting by a
 * per-node key, exempting our own sends) held one field node's digest at 117 frames against its peers' 100 for good.
 * The quota also cuts holes in a ratchet chain (the frames evicted before the recipient came back), which the
 * recipient must read past. Every node gets the same numbers: the digest is folded over what these keep.
 */
@RunWith(RobolectricTestRunner::class)
class CustodyQuotaLabTest {
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

    /**
     * Five frames per sender everywhere. Alice and Carol already hold a session (the init frame is not what
     * gets evicted); Alice writes seven DMs to an absent Carol, every store keeps her newest five, so Carol
     * comes back to exactly those, opens them past the hole with the ratchet's skipped keys — no reset — and
     * the three stores agree.
     */
    @Test
    fun aSendersOverQuotaDmsAreEvictedIdenticallyAndTheRecipientReadsPastTheHole() =
        runBlocking {
            val limits = LabLimits(custodyMaxPerSender = 5)
            val alice = lab.node("alice", limits).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", limits).apply { setDisplayName("Bob") }
            val carol = lab.node("carol", limits).apply { setDisplayName("Carol") }
            lab.linkAll(alice to bob, bob to carol)
            lab.awaitAcquainted(alice, bob, carol)
            assertTrue(alice.sendDm(carol, "hello"))
            lab.await(1) { carol.decrypted(carol.dmWith(alice)).size }
            assertTrue(carol.sendDm(alice, "hi"))
            lab.assertConverged(listOf(alice, carol), atLeast = 2, carriers = listOf(bob)) { it.dmWith(if (it === alice) carol else alice) }

            lab.unlink(bob, carol)
            (1..7).forEach { assertTrue(alice.sendDm(carol, "apart $it")) }
            val apart = (1..7).map { alice.ownMessageId(alice.dmWith(carol), "apart $it") }
            awaitCustody(bob, of = apart, holding = apart.drop(2))
            assertEquals("bob keeps alice's newest five DMs, not seven", 5, bob.custodiedChatsFrom(alice, carol))
            assertEquals("alice's own store keeps the same five", 5, alice.custodiedChatsFrom(alice, carol))

            linkForCustodyOnly(bob, carol)
            lab.assertConverged(listOf(carol), atLeast = 7, carriers = listOf(alice, bob)) { it.dmWith(alice) }
            assertEquals(setOf("hello", "hi") + (3..7).map { "apart $it" }, carol.decrypted(carol.dmWith(alice)).map { it.second }.toSet())
            assertEquals("nothing failed to decrypt past the hole", 0L, carol.drops(DropReason.DECRYPT_FAILED))
            assertEquals("no reset was needed", 0L, carol.session(alice)?.lastResetSentAt)
        }

    /**
     * The same walk with no session beforehand — the very first frames of the conversation are the ones
     * evicted. Every sealed frame carries the epoch key, so Carol opens what survives without the first
     * two, asks for no reset, and simply never sees them: the quota is loss for an absent recipient, by
     * design, never a wedge.
     */
    @Test
    fun anAbsentRecipientLosesWhatTheQuotaEvictedAndNeedsNoReset() =
        runBlocking {
            val limits = LabLimits(custodyMaxPerSender = 5)
            val alice = lab.node("alice", limits).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", limits).apply { setDisplayName("Bob") }
            val carol = lab.node("carol", limits).apply { setDisplayName("Carol") }
            lab.linkAll(alice to bob, bob to carol)
            lab.awaitAcquainted(alice, bob, carol)

            lab.unlink(bob, carol)
            (1..7).forEach { assertTrue(alice.sendDm(carol, "apart $it")) }
            val apart = (1..7).map { alice.ownMessageId(alice.dmWith(carol), "apart $it") }
            awaitCustody(bob, of = apart, holding = apart.drop(2))

            linkForCustodyOnly(bob, carol)
            lab.assertConverged(listOf(carol), atLeast = 5, carriers = listOf(alice, bob)) { it.dmWith(alice) }
            assertEquals((3..7).map { "apart $it" }.toSet(), carol.decrypted(carol.dmWith(alice)).map { it.second }.toSet())
            assertEquals(0L, carol.drops(DropReason.DECRYPT_FAILED))
            assertEquals("no reset was needed", 0L, carol.session(alice)?.lastResetSentAt)
        }

    /** The group form: three frames per group everywhere, five sent while Carol was out, the newest three land. */
    @Test
    fun aGroupsOverQuotaFramesAreEvictedIdenticallyAndTheAbsentMemberReadsPastTheHole() =
        runBlocking {
            val limits = LabLimits(custodyMaxPerGroup = 3)
            val alice = lab.node("alice", limits).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", limits).apply { setDisplayName("Bob") }
            val carol = lab.node("carol", limits).apply { setDisplayName("Carol") }
            lab.linkAll(alice to bob, bob to carol)
            lab.awaitAcquainted(alice, bob, carol)
            val groupId = alice.createGroup(bob, carol)
            assertTrue(alice.sendGroup(groupId, "founded"))
            lab.assertConverged(listOf(alice, bob, carol), atLeast = 1) { groupId }

            lab.unlink(bob, carol)
            (1..5).forEach { assertTrue(alice.sendGroup(groupId, "apart $it")) }
            val apart = (1..5).map { alice.ownMessageId(groupId, "apart $it") }
            awaitCustody(bob, of = apart, holding = apart.drop(2))

            linkForCustodyOnly(bob, carol)
            assertTrue(
                "carol never read the newest three",
                lab.tryAwait(1) {
                    if (carol.decrypted(groupId).map { it.second }.containsAll((3..5).map { "apart $it" })) 1 else 0
                },
            )
            assertEquals(
                "the evicted two never arrive",
                (3..5)
                    .map {
                        "apart $it"
                    }.toSet() + "founded",
                carol.decrypted(groupId).map { it.second }.toSet(),
            )
            assertEquals(0L, carol.drops(DropReason.DECRYPT_FAILED))
            // The oracle needs one message set: alice and bob hold what carol never got, so they ride as carriers.
            lab.assertConverged(listOf(carol), atLeast = 4, carriers = listOf(alice, bob)) { groupId }
        }

    /**
     * A full store: twenty rows everywhere, and Alice posting twelve times to the others' two. The cap takes from the largest sender
     * bucket (ADR 2026-10.7mqm), on every node alike, so Bob's and Carol's frames — their posts and their profiles, the
     * oldest frames in the store — are all still carried, and only Alice's are gone. Under the old rule (the globally
     * oldest frame) their profiles went first.
     */
    @Test
    fun aFullStoreEvictsFromTheLargestSenderOnEveryNodeAlike() =
        runBlocking {
            val limits = LabLimits(custodyMaxRows = 20)
            val alice = lab.node("alice", limits).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", limits).apply { setDisplayName("Bob") }
            val carol = lab.node("carol", limits).apply { setDisplayName("Carol") }
            lab.linkAll(alice to bob, bob to carol, alice to carol)
            lab.awaitAcquainted(alice, bob, carol)
            val three = listOf(alice, bob, carol)

            listOf(bob, carol).forEach { n -> (1..2).forEach { assertTrue(n.sendRoom("${n.name} $it")) } }
            lab.assertConverged(three, atLeast = 4) { Conversations.NEARBY }
            val before = listOf(bob, carol).associate { it.name to alice.custodiedFrom(it.nodeId) }
            (1..12).forEach { assertTrue(alice.sendRoom("alice $it")) }
            lab.assertConverged(three, atLeast = 16) { Conversations.NEARBY }
            three.forEach { n ->
                assertEquals("${n.name} holds a full store", 20, n.custodyIds().size)
                assertEquals(
                    "${n.name} still carries all of bob's and carol's",
                    before,
                    listOf(bob, carol).associate {
                        it.name to
                            n.custodiedFrom(it.nodeId)
                    },
                )
            }
        }

    /**
     * The room's quota and the global cap bind together, and the three stores reach them from different frames: Carol
     * posts alone while Alice and Bob post and DM between them. When they meet, each serves the others what they lack,
     * and a frame one store has evicted and another still holds is refused as seen for the router's ten-minute window.
     * Past the window the next exchange serves it again. With both bounds binding, what the fair cap keeps depends on
     * the order the frames came in and not only on which (ADR 2026-10.7mqm): it can take a sender's DM while a post of
     * theirs still counts in their bucket, and a newer post's room trim then drops that post as well. So the exchange
     * past one window can itself leave two stores a frame apart, each refusing the other's as seen, and only the next
     * window settles it. The ADR's simulation settled within about two windows and `ForwardRepositoryTest`'s exchange
     * case bounds it at three, as [pastSeenWindowsUntilCustodyAgrees] does here through the real stacks (a chaos sweep
     * of 50 seeds, #141: agreed past the first window 33 times, past the second 17, never needed a third). The pairs
     * meet their DM sessions before the partition, so the meeting mints no custodied frame of its own. Messages differ
     * by design — a post the quotas evicted before it crossed never reaches the other side — so only Carol's thread is
     * the oracle's, and the others are carriers.
     */
    @Test
    fun theRoomQuotaAndTheCapBindingTogetherConvergePastTheSeenWindow() =
        runBlocking {
            val limits = LabLimits(custodyMaxRows = 12, custodyMaxBroadcast = 5)
            val alice = lab.node("alice", limits).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", limits).apply { setDisplayName("Bob") }
            val carol = lab.node("carol", limits).apply { setDisplayName("Carol") }
            lab.linkAll(alice to bob, bob to carol, alice to carol)
            lab.awaitAcquainted(alice, bob, carol)
            // Every pair's DM session first, while the three are linked: a reader's tick to Carol's post carries the
            // X3DH init, and her answer is a sealed DM custody carries. First met at the meeting instead, the answers
            // are minted after its exchange (#141) and land on each store in its own order, which with the cap
            // binding decides what each keeps; no window is left to settle that.
            assertTrue(carol.sendRoom("carol 0"))
            awaitSessionsSettled(alice to carol, bob to carol)
            lab.awaitCustodyParity(alice, bob, carol)

            lab.unlink(alice, carol)
            lab.unlink(bob, carol)
            (1..3).forEach { assertTrue(alice.sendRoom("alice $it")) }
            (1..2).forEach { assertTrue(bob.sendRoom("bob $it")) }
            (1..2).forEach { assertTrue(alice.sendDm(bob, "dm $it")) }
            lab.await(2) { bob.decrypted(bob.dmWith(alice)).size }
            awaitSessionsSettled(alice to bob) // Bob's answer is in their island's custody before Carol meets either
            (1..3).forEach { assertTrue(carol.sendRoom("carol $it")) }

            val bobsLast = bob.ownMessageId(Conversations.NEARBY, "bob 2")
            lab.link(bob, carol)
            lab.await(1) { if (bobsLast in carol.custodyIds()) 1 else 0 }
            lab.unlink(bob, carol)
            lab.unlink(alice, bob)
            pastSeenWindowsUntilCustodyAgrees(alice, bob, carol)

            lab.assertConverged(listOf(carol), atLeast = 3, carriers = listOf(alice, bob)) { Conversations.NEARBY }
            listOf(alice, bob, carol).forEach { assertTrue("${it.name}: the cap held", it.custodyIds().size <= 12) }
        }

    /**
     * #176 / #235: a non-member short of a member's posts keeps that member's DM after the members' quota evicted it.
     * Five frames per sender everywhere. Carol, outside Alice and Bob's group, takes Alice's DM to Bob and is cut; Alice
     * then posts six times to the group, so Alice's and Bob's buckets keep her newest five and drop the DM. Carol holds
     * it still. When the three meet, the members back-fill Carol the posts (ADR 2026-10.hr9f), her own bucket evicts
     * the DM, and she sends it over each of her links at most once — the meeting's diff, before the posts land. Served
     * to members only, she never got the posts: her digest listed the DM at every exchange, it crossed both links once
     * per seen window, each copy was evicted in the write that stored it (and drew Bob a fresh sealed receipt), and
     * custody never agreed. Each pass of [pastSeenWindowsUntilCustodyAgrees] lets the crossing memo lapse, so the old
     * rule crosses once per pass.
     */
    @Test
    fun aNonMemberBackFilledAGroupDropsTheDmTheMembersEvicted() =
        runBlocking {
            val limits = LabLimits(custodyMaxPerSender = 5)
            val alice = lab.node("alice", limits).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", limits).apply { setDisplayName("Bob") }
            val carol = lab.node("carol", limits).apply { setDisplayName("Carol") }
            lab.linkAll(alice to bob, bob to carol)
            lab.awaitAcquainted(alice, bob, carol)
            val group = alice.createGroup(bob)

            // Bob relays the DM on to Carol: she custodies it, and his sealed receipt for it, before the cut.
            assertTrue(alice.sendDm(bob, "d"))
            val d = alice.ownMessageId(alice.dmWith(bob), "d")
            lab.await(1) { bob.decrypted(bob.dmWith(alice)).count { it.second == "d" } }
            lab.await(1) { if (d in carol.custodyIds()) 1 else 0 }
            lab.awaitCustodyParity(bob, carol)
            lab.unlink(bob, carol)

            (1..6).forEach { assertTrue(alice.sendGroup(group, "post $it")) }
            val posts = (1..6).map { alice.ownMessageId(group, "post $it") }
            lab.await(6) { bob.decrypted(group).size }
            listOf(alice, bob).forEach { awaitCustody(it, of = posts + d, holding = posts.drop(1)) }
            assertTrue("carol, cut before the posts, still holds the DM", d in carol.custodyIds())
            assertTrue("and none of the posts", posts.none { it in carol.custodyIds() })

            // Bob's ticks go to the linked Alice point to point, kept nowhere: each is home before the link is cut, or the
            // tick the cut catches in flight is the scenario's loss, not custody's.
            posts.forEach { lab.awaitReceipt(alice, it, bob) }
            lab.unlink(alice, bob)
            pastSeenWindowsUntilCustodyAgrees(alice, bob, carol)

            listOf(bob, alice).forEach { to ->
                assertTrue(
                    "carol sent the DM toward ${to.name} more than once: ${carol.transport.sent}",
                    carol.copiesTo(to, d) <= 1,
                )
            }
            lab.assertConverged(listOf(alice, bob), atLeast = 6, carriers = listOf(carol)) { group }
            assertTrue("the evicted DM is held nowhere", listOf(alice, bob, carol).none { d in it.custodyIds() })
        }

    /** Frames [this] handed [to] under [id], each line "#seq <receiver> <type> <id> …" one delivered write. */
    private fun LabNode.copiesTo(
        to: LabNode,
        id: String,
    ): Int =
        transport.sent.count { line ->
            val cols = line.split(' ')
            cols.getOrNull(3) == id && to.nodeId.startsWith(cols[1])
        }

    /** Waits until each pair holds one confirmed session on both sides: the same root and era, opposite roles. */
    private suspend fun awaitSessionsSettled(vararg pairs: Pair<LabNode, LabNode>) {
        val settled =
            lab.tryAwait(1) {
                val all =
                    pairs.all { (a, b) ->
                        val x = a.session(b)
                        val y = b.session(a)
                        x != null && y != null && x.hasSession && y.hasSession && x.confirmed && y.confirmed &&
                            x.rootHash == y.rootHash && x.establishedAt == y.establishedAt && x.weAreInitiator != y.weAreInitiator
                    }
                if (all) 1 else 0
            }
        assertTrue("sessions never settled: ${pairs.map { (a, b) -> "${a.name}↔${b.name}" }}", settled)
    }

    /**
     * Lets the router's seen window lapse, up to [windows] times, until the three stores agree. The first pass is the
     * meeting; the rest are the 60 s re-offer on the links they kept, which a calendar jump never wakes. Each pass
     * serves again what the last one refused as seen. The pipes' crossing memo (`LinkCrossings`) runs on the routers'
     * calendar, so a frame that came in over a link can go back over it once the window has lapsed.
     *
     * A pass reads the stores once its exchange has landed: every newcomer hook ran (the digests are out), every digest
     * is answered and every frame the answers carried is handled. Read any earlier, the stores can agree for a moment
     * while a reply computed before an eviction is still on its way — a frame every store has since evicted, to a node
     * that never saw it, which takes it in past the window and trims another (#141: a re-served post and Alice's
     * profile, each split one store off after the three had agreed, and no window was left to settle it). Waiting
     * longer for agreement buys nothing: once the exchange has landed, the stores that disagree refuse each other's
     * leftovers as seen until the next window. One round of the three awaits is enough: with the sessions settled
     * nothing the meeting handles sends inline onward a frame custody keeps. The oracle after this fails with every
     * store's listing if the last pass did not agree.
     */
    private suspend fun pastSeenWindowsUntilCustodyAgrees(
        alice: LabNode,
        bob: LabNode,
        carol: LabNode,
        windows: Int = 3,
    ) {
        val three = listOf(alice, bob, carol)
        repeat(windows) { pass ->
            lab.clock.advance(SeenSet.DEFAULT_TTL_MS + 60_000L)
            if (pass == 0) meetForCustodyOnly(alice to bob, bob to carol, alice to carol) else three.forEach { it.reoffer() }
            three.forEach { it.transport.awaitNeighborsHandled() }
            three.forEach { it.transport.awaitDigestsAnswered() }
            three.forEach { it.transport.awaitInboundDrained() }
            if (three.map { it.custodyFingerprint() }.distinct().size == 1) return
        }
    }

    /**
     * [MeshLab.linkAll] with the air losing every *relay* both ways ([linkForCustodyOnly]'s rule), for the meeting.
     * A frame a custody serve hands over is flooded on, 0–150 ms after its receiver handled it: past the drained check,
     * so the relay of a frame that store took in and then trimmed inside the exchange could reach a third store, never
     * served it, after the read. Each edge of the triangle is a direct link, so everything the meeting must carry
     * crosses as a hop-0 serve the awaits cover, and later passes reuse these pipes.
     */
    private fun meetForCustodyOnly(vararg links: Pair<LabNode, LabNode>) {
        val relay = { wire: WireEnvelope -> wire.hops > 0 }
        links.forEach { (a, b) ->
            a.transport.connect(b.transport, publish = false, lossy = relay, arm = { b.transport.lossy(a.transport, relay) })
        }
        links.flatMap { it.toList() }.distinct().forEach { it.transport.publishNeighbors() }
    }

    /**
     * Waits until [carrier]'s custody holds exactly [holding] of the frames [of] — every one of them handled, so
     * the quota has evicted the rest — and fails with what it holds instead. Custody only reaches that set once
     * the newest frame is in, which a count of the carrier's relays since a baseline cannot promise: a relay of
     * an earlier frame still in its 0–150 ms jitter when the baseline was read lands inside the count, which
     * then passes with a newer frame not yet handled.
     */
    private suspend fun awaitCustody(
        carrier: LabNode,
        of: List<String>,
        holding: List<String>,
    ) {
        val held = suspend { carrier.custodyIds().filterTo(HashSet()) { it in of } }
        val settled = lab.tryAwait(1) { if (held() == holding.toSet()) 1 else 0 }
        assertTrue("${carrier.name} never custodied exactly the newest ${holding.size}: holds ${held()} of $of", settled)
    }

    /**
     * Brings Bob back to Carol with the air losing his *relays* toward her: a copy he forwards carries
     * `hops > 0`, while what he serves from custody or originates is stamped fresh at 0
     * (`StrangerBacklogLabTest.linkForTheBacklogOnly`). A relay filed before the quota evicted its frame decides
     * its targets only when its jitter runs out — after this link, under load — and would hand Carol a frame
     * no store holds any more (the first runs flaked so, delivering six or seven). What she must get, custody
     * carries. A copy lost here is repaired only by the next digest exchange, at the next link-up or the 60 s
     * re-offer, so a scenario that has Alice send toward Carol after this link would wait out that tick.
     */
    private fun linkForCustodyOnly(
        bob: LabNode,
        carol: LabNode,
    ) = bob.transport.connect(carol.transport, lossy = { it.hops > 0 })
}
