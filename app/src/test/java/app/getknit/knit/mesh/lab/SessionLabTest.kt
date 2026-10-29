package app.getknit.knit.mesh.lab

import app.getknit.knit.data.ratchet.RatchetRepository
import app.getknit.knit.mesh.DropReason
import app.getknit.knit.mesh.protocol.ChatContent
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The pairwise DM session between real stacks: the both-initiate race resolving to one root whichever init
 * lands first, a forced reset healing rather than storming (ADR 023/024/026 — a reset strands a tail of
 * old-era custody whose re-serves must not trip the other side's heuristic), and the group key request a
 * member sends after three unreadable frames (`docs/GROUP_FORWARD_SECRECY.md` §7). The oracle's session
 * check reads both sides' root and era after every case.
 */
@RunWith(RobolectricTestRunner::class)
class SessionLabTest {
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

    /** Both send a first DM while neither can hear the other; the inits cross in [aliceFirst] order. */
    private suspend fun bothInitiate(aliceFirst: Boolean) {
        val alice = lab.node("alice").apply { setDisplayName("Alice") }
        val bob = lab.node("bob").apply { setDisplayName("Bob") }
        lab.link(alice, bob)
        lab.awaitAcquainted(alice, bob)

        alice.transport.hold(bob.transport)
        bob.transport.hold(alice.transport)
        assertTrue(alice.sendDm(bob, "alice opens"))
        assertTrue(bob.sendDm(alice, "bob opens"))
        if (aliceFirst) {
            alice.transport.release(bob.transport)
            bob.transport.release(alice.transport)
        } else {
            bob.transport.release(alice.transport)
            alice.transport.release(bob.transport)
        }
        lab.assertConverged(listOf(alice, bob), atLeast = 2) { it.dmWith(if (it === alice) bob else alice) }

        // A second round under whatever root won, then the oracle reads both sides' session once more.
        assertTrue(alice.sendDm(bob, "alice again"))
        assertTrue(bob.sendDm(alice, "bob again"))
        lab.assertConverged(listOf(alice, bob), atLeast = 4) { it.dmWith(if (it === alice) bob else alice) }
        assertEquals(alice.session(bob)?.rootHash, bob.session(alice)?.rootHash)
    }

    @Test
    fun bothSidesInitiateAtOnceAndAlicesInitLandsFirst() = runBlocking { bothInitiate(aliceFirst = true) }

    @Test
    fun bothSidesInitiateAtOnceAndBobsInitLandsFirst() = runBlocking { bothInitiate(aliceFirst = false) }

    /**
     * Both initiate while apart, and the race's loser (the higher node id adopts the lower's root) answers the
     * winner's init — its tick, its sealed-profile answer — before its own opening DM reaches the winner: the
     * frames of one link, crossing in the order a stalled digest collector on the loser produces (found by
     * chaos seeds 1003/1010 on `CustodyLabTest.bothSidesSendWhileApartAndMerge`). The winner confirms on the
     * post-adoption frames, and the loser's init then arrives on a confirmed session.
     */
    @Test
    fun theLosersOpeningDmLandingAfterItsAnswersStillOpens() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)
            val (winner, loser) = if (alice.nodeId < bob.nodeId) alice to bob else bob to alice

            winner.transport.hold(loser.transport)
            loser.transport.hold(winner.transport)
            assertTrue(winner.sendDm(loser, "winner opens"))
            assertTrue(loser.sendDm(winner, "loser opens"))
            val opening = loser.ownMessageId(loser.dmWith(winner), "loser opens")

            winner.transport.release(loser.transport)
            lab.await(1) { loser.decrypted(loser.dmWith(winner)).count { it.second == "winner opens" } }
            // The loser's answers under the adopted root — its tick and its sealed-profile intro — are parked
            // behind its opening DM. Distinct frames by id, not copies: a digest re-serve of the opening DM, or of
            // the tick, is a parked chat frame too.
            lab.await(2) {
                loser.transport
                    .held(winner.transport)
                    .filter { it.isChatFrom(loser.nodeId) }
                    .mapNotNull { WireCodec.decodeEnvelope(it.signed)?.id }
                    .filter { it != opening }
                    .distinct()
                    .size
            }
            loser.transport.release(winner.transport) { batch ->
                batch.sortedBy { WireCodec.decodeEnvelope(it.signed)?.id == opening } // the opening DM last
            }

            lab.assertConverged(listOf(alice, bob), atLeast = 2) { it.dmWith(if (it === alice) bob else alice) }
        }

    /**
     * #174: the initiator's session stays unconfirmed past a day — the responder's answers never reach it — while the
     * responder's weekly prekey rotation lands and its re-flooded profile does reach the initiator. The initiator's
     * next DM opens a new epoch, which used to be based on the newest pinned prekey under an init naming the old one:
     * the responder resolves the base by `init.pkid`, so every such frame failed as RATCHET_AEAD_FAIL, and when the
     * initiator held the smaller id it also won every race against the responder's resets, dark for days.
     *
     * Custody keeps frames three days here, not one: the responder's tick for the first DM is held back with the rest
     * of its answers, and only a TTL that outlives the day's jump lets it come home once the hold lifts — which is
     * what makes the ✓✓ half of the oracle honest. In the field that tick expires with the epoch (both are 24 h), and
     * the first DM goes without its ✓✓; the epoch age the bug needs is the ratchet's own 24 h either way.
     */
    private suspend fun anUnconfirmedSessionOutlivesThePeersPrekeyRotation(initiatorHasTheSmallerId: Boolean) {
        val limits = LabLimits(custodyTtlMs = 3 * DAY_MS)
        val one = lab.node("alice", limits).apply { setDisplayName("Alice") }
        val two = lab.node("bob", limits).apply { setDisplayName("Bob") }
        val (smaller, larger) = if (one.nodeId < two.nodeId) one to two else two to one
        val (initiator, responder) = if (initiatorHasTheSmallerId) smaller to larger else larger to smaller
        lab.link(initiator, responder)
        lab.awaitAcquainted(initiator, responder)
        val firstPrekey = checkNotNull(initiator.pinned(responder.nodeId)?.prekeyId)

        responder.transport.lossy(initiator.transport) { wire ->
            WireCodec.decodeEnvelope(wire.signed)?.let { it.type == FrameType.CHAT && it.senderId == responder.nodeId } == true
        }
        assertTrue(initiator.sendDm(responder, "before the rotation"))
        lab.await(1) { responder.decrypted(responder.dmWith(initiator)).size }

        // A day on, past the epoch's age, the responder's weekly rotation lands. It is driven by hand rather than by a
        // seven-day jump and a heal, whose retention sweep would judge everything by a calendar this scenario does not
        // need: the rotation mints the next prekey, and a profile edit re-floods the profile that carries it.
        lab.clock.advance(DAY_MS + 60_000L)
        assertTrue(responder.identity.rotatePrekeyIfDue(responder.now() + 7 * DAY_MS))
        responder.setStatus("rotated")
        lab.await(1) { if ((initiator.pinned(responder.nodeId)?.prekeyId ?: 0) > firstPrekey) 1 else 0 }
        assertEquals("still unconfirmed: the init rides every frame", false, initiator.session(responder)?.confirmed)

        assertTrue(initiator.sendDm(responder, "after the rotation"))
        lab.await(2) { responder.decrypted(responder.dmWith(initiator)).size }
        assertEquals(0L, responder.drops(DropReason.RATCHET_AEAD_FAIL))
        assertEquals(0L, responder.drops(DropReason.RATCHET_EPOCH_GONE))

        // The hold lifts the way a field link's loss does: the link drops and comes back, and the digest exchange of
        // the new link re-serves the responder's custodied answers — the first DM's tick among them.
        responder.transport.lossy(initiator.transport)
        lab.unlink(initiator, responder)
        lab.link(initiator, responder)
        lab.assertConverged(listOf(initiator, responder), atLeast = 2) { it.dmWith(if (it === initiator) responder else initiator) }
    }

    @Test
    fun theSmallerIdsUnconfirmedSessionOutlivesThePeersPrekeyRotation() =
        runBlocking { anUnconfirmedSessionOutlivesThePeersPrekeyRotation(initiatorHasTheSmallerId = true) }

    @Test
    fun theLargerIdsUnconfirmedSessionOutlivesThePeersPrekeyRotation() =
        runBlocking { anUnconfirmedSessionOutlivesThePeersPrekeyRotation(initiatorHasTheSmallerId = false) }

    /**
     * Bob sends while apart; Alice forces a reset while apart. On the merge Bob gets the reset and re-seals
     * his unacked DM under the fresh session, Alice reads it, and — the storm check — Bob never resets back
     * on the old-era copy custody also served him.
     */
    @Test
    fun aForcedResetHealsAndTheOtherSidesUnackedDmsAreResealed() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)
            assertTrue(alice.sendDm(bob, "hello"))
            lab.await(1) { bob.decrypted(bob.dmWith(alice)).size }
            assertTrue(bob.sendDm(alice, "hi"))
            lab.assertConverged(listOf(alice, bob), atLeast = 2) { it.dmWith(if (it === alice) bob else alice) }

            lab.unlink(alice, bob)
            assertTrue(bob.sendDm(alice, "sent while apart"))
            assertNull("alice's reset went out", alice.resetSession(bob))

            lab.link(alice, bob)
            lab.assertConverged(listOf(alice, bob), atLeast = 3) { it.dmWith(if (it === alice) bob else alice) }
            assertTrue(alice.sendDm(bob, "after the reset"))
            lab.assertConverged(listOf(alice, bob), atLeast = 4) { it.dmWith(if (it === alice) bob else alice) }
            assertTrue("alice sent the reset", (alice.session(bob)?.lastResetSentAt ?: 0L) > 0L)
            assertEquals("bob never reset back", 0L, bob.session(alice)?.lastResetSentAt)
        }

    /**
     * The previous scenario with the crossing it leaves to chance pinned (chaos seeds 2001/2003/2005): Alice reads
     * Bob's while-apart DMs under the root her reset retired, and her tick for them reaches Bob *before* her
     * custodied reset does. The open under the kept `prevRoot` must not confirm the replacement session (#87): a
     * tick that carries no init, under a root Bob has never seen, is one he cannot read — and the reseal his reset
     * handling sends back reuses the DM's id, so Alice's router dedups it and never ticks again. Carrying the init,
     * the tick is what Bob adopts the reset from; Bob's answer to that new init is what confirms Alice.
     */
    private suspend fun tickAfterReset(apart: Int) {
        val alice = lab.node("alice").apply { setDisplayName("Alice") }
        val bob = lab.node("bob").apply { setDisplayName("Bob") }
        lab.link(alice, bob)
        lab.awaitAcquainted(alice, bob)
        assertTrue(alice.sendDm(bob, "hello"))
        lab.await(1) { bob.decrypted(bob.dmWith(alice)).size }
        assertTrue(bob.sendDm(alice, "hi"))
        lab.assertConverged(listOf(alice, bob), atLeast = 2) { it.dmWith(if (it === alice) bob else alice) }

        lab.unlink(alice, bob)
        val whileApart = (1..apart).map { "sent while apart $it" }
        whileApart.forEach { assertTrue(bob.sendDm(alice, it)) }
        val apartIds = whileApart.map { bob.ownMessageId(bob.dmWith(alice), it) }
        val custodied = alice.custodyIds()
        assertNull("alice's reset went out", alice.resetSession(bob))
        val reset = (alice.custodyIds() - custodied).single()

        // The holds go on while the pipes exist and neither end lists the link (`arm`): nothing either sends the other
        // crosses before its hold, not even a re-offer timer's digest firing between a connect and a hold.
        lab.link(alice, bob) {
            alice.transport.hold(bob.transport)
            bob.transport.hold(alice.transport)
        }
        // Bob's digest serve walks custody newest first, so his DMs are released oldest first: the first one derives
        // the epoch under the kept `prevRoot`, and the rest of that epoch opens on the live chain it stored.
        lab.await(apart) {
            bob.transport
                .held(alice.transport)
                .mapNotNull { WireCodec.decodeEnvelope(it.signed)?.id }
                .filter { it in apartIds }
                .distinct()
                .size
        }
        bob.transport.release(alice.transport) { batch ->
            val (dms, rest) = batch.partition { WireCodec.decodeEnvelope(it.signed)?.id in apartIds }
            rest + dms.sortedBy { apartIds.indexOf(WireCodec.decodeEnvelope(it.signed)?.id) }
        }
        lab.await(apart) { alice.decrypted(alice.dmWith(bob)).count { it.second in whileApart } }
        // Her ticks for them — one sealed receipt per DM — parked beside the reset her digest exchange serves Bob. A
        // DM receipt floods like the reset (sealed, custodied), so it is told apart by id; with no pages and no board
        // nothing else of Alice's crosses this pipe as a chat frame in this window.
        lab.await(apart) {
            alice.transport
                .held(bob.transport)
                .filter { it.isChatFrom(alice.nodeId) }
                .mapNotNull { WireCodec.decodeEnvelope(it.signed)?.id }
                .filter { it != reset }
                .distinct()
                .size
        }
        alice.transport.release(bob.transport) { batch ->
            batch.sortedBy { WireCodec.decodeEnvelope(it.signed)?.id == reset } // the reset last
        }

        lab.assertConverged(listOf(alice, bob), atLeast = 2 + apart) { it.dmWith(if (it === alice) bob else alice) }
    }

    /**
     * #135 (ADR 2026-10.gcrd). Alice's DMs written while Bob was away ride her old era's epoch; she resets, which
     * restarts her numbering. Bob adopts the reset *first* (pinned: the reset leads her served batch) and only then
     * reads those DMs, under the root the reset retired — so his stored chain for that epoch number belongs to the old
     * era. Alice's new era then climbs back to the same number, and everything she seals there has to reach Bob:
     * judged against the old era's chain it read as DUPLICATE or failed its AEAD, and Bob reset back to recover.
     */
    @Test
    fun oldEraDmsReadAfterTheResetNeverStrandTheNewEraAtTheirEpochNumber() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)
            assertTrue(alice.sendDm(bob, "hello"))
            lab.await(1) { bob.decrypted(bob.dmWith(alice)).count { it.second == "hello" } }
            assertTrue(bob.sendDm(alice, "hi"))
            lab.assertConverged(listOf(alice, bob), atLeast = 2) { it.dmWith(if (it === alice) bob else alice) }

            lab.unlink(alice, bob)
            val apart = (1..3).map { "sent while apart $it" }
            apart.forEach { assertTrue(alice.sendDm(bob, it)) }
            val apartIds = apart.map { alice.ownMessageId(alice.dmWith(bob), it) }
            val oldEpoch = checkNotNull(alice.session(bob)).sendEpoch
            assertTrue("precondition: the apart DMs sit past the epoch a reset restarts at", oldEpoch >= 2)
            val custodied = alice.custodyIds()
            assertNull("alice's reset went out", alice.resetSession(bob))
            val reset = (alice.custodyIds() - custodied).single()

            // The hold goes on while the pipes exist and neither end lists the link (`arm`): nothing Alice serves Bob
            // crosses before it.
            lab.link(alice, bob) { alice.transport.hold(bob.transport) }
            lab.await(1 + apartIds.size) {
                alice.transport
                    .held(bob.transport)
                    .mapNotNull { WireCodec.decodeEnvelope(it.signed)?.id }
                    .filter { it == reset || it in apartIds }
                    .distinct()
                    .size
            }
            alice.transport.release(bob.transport) { batch ->
                batch.sortedByDescending { WireCodec.decodeEnvelope(it.signed)?.id == reset } // the reset first
            }
            lab.await(apart.size) { bob.decrypted(bob.dmWith(alice)).count { it.second in apart } }
            assertEquals("bob read the apart DMs after adopting", alice.session(bob)?.rootHash, bob.session(alice)?.rootHash)

            // Turnarounds until Alice's restarted numbering has sealed past the old era's epoch, each DM awaited by text.
            var turn = 0
            while (checkNotNull(alice.session(bob)).sendEpoch <= oldEpoch) {
                val fromAlice = "alice, new era $turn"
                assertTrue(alice.sendDm(bob, fromAlice))
                lab.await(1) { bob.decrypted(bob.dmWith(alice)).count { it.second == fromAlice } }
                val fromBob = "bob, new era $turn"
                assertTrue(bob.sendDm(alice, fromBob))
                lab.await(1) { alice.decrypted(alice.dmWith(bob)).count { it.second == fromBob } }
                turn++
                assertTrue("alice's new era never passed epoch $oldEpoch", turn <= MAX_TURNS)
            }
            lab.assertConverged(listOf(alice, bob), atLeast = 5 + 2 * turn) { it.dmWith(if (it === alice) bob else alice) }
            assertEquals("bob never reset back", 0L, bob.session(alice)?.lastResetSentAt)
        }

    @Test
    fun aTickSealedAfterAResetStillReachesAPeerWhoHasNotSeenTheReset() = runBlocking { tickAfterReset(apart = 1) }

    @Test
    fun aTickSealedAfterAResetStillReachesAPeerWhoHasNotSeenTheResetEvenAfterTwoDmsFromTheSameEpoch() =
        runBlocking { tickAfterReset(apart = 2) }

    /**
     * The other direction: Alice sends three DMs to an absent Bob (Dave carries them), then forces a reset.
     * When Bob returns the three are sealed under an era the reset retired — pre-era to Bob, so no reset
     * storm — and they still have to reach him.
     */
    @Test
    fun dmsSealedUnderAnEraAResetRetiredStillArriveWithoutAStorm() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            val dave = lab.node("dave").apply { setDisplayName("Dave") }
            lab.linkAll(alice to bob, alice to dave, bob to dave)
            lab.awaitAcquainted(alice, bob, dave)
            assertTrue(alice.sendDm(bob, "hello"))
            lab.await(1) { bob.decrypted(bob.dmWith(alice)).size }
            assertTrue(bob.sendDm(alice, "hi"))
            lab.assertConverged(listOf(alice, bob), atLeast = 2, carriers = listOf(dave)) { it.dmWith(if (it === alice) bob else alice) }

            lab.unlink(alice, bob)
            lab.unlink(bob, dave)
            (1..3).forEach { assertTrue(alice.sendDm(bob, "apart $it")) }
            assertTrue("dave carries the three", lab.tryAwait(3) { dave.custodiedChatsFrom(alice, bob) })
            assertNull(alice.resetSession(bob))
            lab.unlink(alice, dave)

            lab.link(bob, dave)
            lab.link(alice, bob)
            lab.assertConverged(listOf(alice, bob), atLeast = 5, carriers = listOf(dave)) { it.dmWith(if (it === alice) bob else alice) }
            assertEquals("bob never reset back", 0L, bob.session(alice)?.lastResetSentAt)
        }

    /**
     * ADR 2026-10.9wvr, the field's shape of 2026-10-16. Alice reacts three times a day after the pair last spoke; the
     * three are the first frames of a fresh epoch of hers, based on Bob's newest, and the pipe loses every copy of
     * them until the end. Bob's epoch has aged out too, so the pair talks on through epochs that never touch that one,
     * until Bob's 16-row cap retires the epoch the three were sealed against. Bob restarts (the sweep runs, the seen
     * set goes) and Alice's custody re-serves the three: each fails as EPOCH_GONE on a key retention took, in the era
     * Bob still holds, behind epochs Alice has sealed against since. Before the rule, three distinct ids re-rooted a
     * session that was working.
     *
     * [bobInitiates] picks the era gate's half Bob judges them on. As responder it is exact; as initiator (the field
     * phone's side) it compares Alice's `sentAt` with Bob's own era stamp under the five-minute skew bound. Both pass
     * these in-era frames as live, which is the point: the 48 h escape only widened the field's case, the rule this
     * pins is the one that narrows it. (The escape itself cannot be driven here: the pipeline reads the wall clock,
     * not the lab calendar, and a jump past 48 h before a restart reaps receive state — lab-lint's own rule.)
     */
    private suspend fun framesSealedAgainstARetiredEpoch(bobInitiates: Boolean) {
        val alice = lab.node("alice").apply { setDisplayName("Alice") }
        val bob = lab.node("bob").apply { setDisplayName("Bob") }
        lab.link(alice, bob)
        lab.awaitAcquainted(alice, bob)
        val (first, second) = if (bobInitiates) bob to alice else alice to bob
        assertTrue(first.sendDm(second, "hello"))
        lab.await(1) { second.decrypted(second.dmWith(first)).count { it.second == "hello" } }
        assertTrue(second.sendDm(first, "hi"))
        lab.assertConverged(listOf(alice, bob), atLeast = 2) { it.dmWith(if (it === alice) bob else alice) }
        assertEquals("precondition: bob's half of the era gate", bobInitiates, checkNotNull(bob.session(alice)).weAreInitiator)
        val target = bob.ownMessageId(bob.dmWith(alice), if (bobInitiates) "hello" else "hi")

        // A day apart: Alice's epoch ages out, so her next seal opens a fresh one — based on Bob's newest, which he
        // will never see a frame of — and Bob's ages out too, so his next opens one that never touches it.
        lab.unlink(alice, bob)
        lab.clock.advance(A_DAY_AND_A_MINUTE_MS)
        val epochBefore = checkNotNull(alice.session(bob)).sendEpoch
        listOf("\uD83D\uDC4D", "\u2764\uFE0F", "\uD83D\uDE02").forEach { alice.react(alice.dmWith(bob), target, it) }
        val sealed = checkNotNull(alice.db.ratchetDao().session(bob.nodeId))
        val doomedEpoch = sealed.sendEpoch
        val base = sealed.sendEpochBaseEpoch
        assertEquals("precondition: the reactions opened a fresh epoch of Alice's", epochBefore + 1, doomedEpoch)

        lab.link(alice, bob) { alice.transport.lossy(bob.transport) { it.sealedInEpoch(alice.nodeId, doomedEpoch) } }
        assertTrue(bob.sendDm(alice, "a day later"))
        lab.await(1) { alice.decrypted(alice.dmWith(bob)).count { it.second == "a day later" } }
        var turn = 0
        while (checkNotNull(bob.session(alice)).sendEpoch < base + RatchetRepository.MAX_LOCAL_EPOCHS) {
            val fromAlice = "alice, turn $turn"
            assertTrue(alice.sendDm(bob, fromAlice))
            lab.await(1) { bob.decrypted(bob.dmWith(alice)).count { it.second == fromAlice } }
            val fromBob = "bob, turn $turn"
            assertTrue(bob.sendDm(alice, fromBob))
            lab.await(1) { alice.decrypted(alice.dmWith(bob)).count { it.second == fromBob } }
            turn++
            assertTrue("bob's epochs never climbed past the cap", turn <= MAX_CAP_TURNS)
        }
        lab.await(1) { if (bob.missingAcks(bob.dmWith(alice), listOf(alice, bob)).isEmpty()) 1 else 0 }
        assertTrue("no copy of the three reached bob", alice.transport.lost.any { it.sealedInEpoch(alice.nodeId, doomedEpoch) })

        bob.restart()
        // The boot sweep runs in a job the restart does not wait for: await the cap's work, not the boot.
        lab.await(1) { if (checkNotNull(bob.session(alice)).localEpochs.all { it.first > base }) 1 else 0 }
        val held = checkNotNull(bob.session(alice))
        assertTrue("precondition: alice has sealed past epoch $base", held.highestPeAcked > base)
        lab.link(alice, bob) { alice.transport.lossy(bob.transport) }
        lab.await(3) { bob.drops(DropReason.RATCHET_EPOCH_GONE).toInt() }

        alice.react(alice.dmWith(bob), target, "\uD83C\uDF89")
        lab.assertConverged(listOf(alice, bob), atLeast = 3 + 2 * turn) { it.dmWith(if (it === alice) bob else alice) }
        val after = checkNotNull(bob.session(alice))
        assertEquals("bob never reset", 0L, after.lastResetSentAt)
        assertEquals("the same root on bob", held.rootHash, after.rootHash)
        assertTrue("and still confirmed", after.confirmed)
    }

    @Test
    fun framesSealedAgainstAnEpochTheCapRetiredNeverResetAWorkingSession() =
        runBlocking {
            framesSealedAgainstARetiredEpoch(bobInitiates = false)
        }

    @Test
    fun framesSealedAgainstAnEpochTheCapRetiredNeverResetTheInitiatorsSession() =
        runBlocking {
            framesSealedAgainstARetiredEpoch(bobInitiates = true)
        }

    /**
     * §7: Bob holds the group (the roster rode the first frame) but never got the seed. After three frames
     * he cannot open he asks Alice for the key, Alice re-seals the current seed to him, and the three land.
     */
    @Test
    fun aMemberWhoMissedTheSeedAsksAfterThreeUnreadableFrames() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            lab.link(alice, bob)
            // Acquainted means the link-up's own seed planes have finished before the seed exists (#201). One that
            // landed after the first send (the newcomer hook's flush, or a profile still being handled) re-sent the
            // seed into the hold, which ate it with the first, and stamped the per-(group, member) floor under Alice's
            // root (GFS §7). When her id was the smaller she won the race with the init on Bob's request and kept that
            // root, so the request met the floor and nothing answered it (chaos seed 804399168).
            lab.awaitAcquainted(alice, bob)

            alice.transport.hold(bob.transport)
            val groupId = alice.createGroup(bob)
            (1..3).forEach { assertTrue(alice.sendGroup(groupId, "sealed $it")) }
            val released = alice.transport.release(bob.transport) { batch -> batch.filter { it.isGroupFrame() } }
            assertEquals("three group frames, no seed", 3, released.size)

            assertTrue(
                "bob never asked for the key",
                lab.tryAwait(1) {
                    bob.metrics
                        .groups()
                        .groupKeyRequestsSent
                        .toInt()
                },
            )
            val opened = lab.tryAwait(3) { bob.decrypted(groupId).size }
            assertTrue(
                "the three never opened: bob holds ${bob.decrypted(groupId).map { it.second }}\n" +
                    "  alice↔bob sessions: ${alice.session(bob)} / ${bob.session(alice)}\n${lab.report(listOf(alice, bob))}",
                opened,
            )
            assertEquals(1L, bob.metrics.groups().groupKeyRequestsSent)
            // A row is written before its tick is sealed, so three rows do not mean three ticks left: one still
            // being sealed when the link goes is lost for good (#96, its own repro below). Wait for the ticks.
            lab.await(1) { if (alice.missingAcks(groupId, listOf(alice, bob)).isEmpty()) 1 else 0 }
            // The seed the hold ate is still in Alice's custody and nowhere in Bob's; the digest exchange on
            // the next link-up (or the 60 s re-offer) is what serves it. Re-link, then the oracle.
            lab.unlink(alice, bob)
            lab.link(alice, bob)
            lab.assertConverged(listOf(alice, bob), atLeast = 3) { groupId }
        }

    /**
     * A group tick sent over a live link leaves `AckSync` the moment `send` returns: it is `relay = false`, so
     * it is never custodied, and the owed entry is dropped as delivered. A link that dies with the tick still in
     * its buffer loses it for good — the message is delivered and custodied on both sides, so no digest
     * re-serves it and nothing re-owes the tick; the author never sees this member's ✓✓. The lossy pipe is the
     * buffer the teardown loses, the unlink is the teardown. Found by the chaos soak (2026-09-28) as the
     * missed-seed scenario's lost third tick: a live send suspended past the scenario's unlink.
     */
    @Ignore("#96: a live-link group tick lost with its link is never sent again")
    @Test
    fun aGroupTickLostWithItsLinkIsSentAgainOnTheNextLink() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)
            val groupId = alice.createGroup(bob)
            assertTrue(alice.sendGroup(groupId, "first"))
            lab.assertConverged(listOf(alice, bob), atLeast = 1) { groupId }

            bob.transport.lossy(alice.transport) { it.isLiveTickFrom(bob.nodeId) }
            assertTrue(alice.sendGroup(groupId, "second"))
            lab.await(1) { bob.transport.lost.size }
            // The loss is recorded inside `send`, before AckSync drops the owed entry: a re-link that ran first
            // would find the tick still owed and send it again, and the repro would pass on HEAD.
            bob.transport.awaitInboundDrained()
            bob.transport.lossy(alice.transport)
            lab.unlink(alice, bob)
            lab.link(alice, bob)
            lab.assertConverged(listOf(alice, bob), atLeast = 2) { groupId }
        }

    /** A chat frame from [nodeId] sealed in its DM send epoch [epoch]. */
    private fun WireEnvelope.sealedInEpoch(
        nodeId: String,
        epoch: Int,
    ): Boolean {
        val env = WireCodec.decodeEnvelope(signed) ?: return false
        if (env.type != FrameType.CHAT || env.senderId != nodeId) return false
        return WireCodec
            .decodePayload<ChatContent>(env.payload)
            ?.enc
            ?.r
            ?.se == epoch
    }

    private fun WireEnvelope.isGroupFrame(): Boolean = WireCodec.decodeEnvelope(signed)?.group != null

    private fun WireEnvelope.isChatFrom(nodeId: String): Boolean =
        WireCodec.decodeEnvelope(signed)?.let { it.type == FrameType.CHAT && it.senderId == nodeId } == true

    /** A point-to-point (`relay = false`) chat frame [nodeId] sent: the live-link form of a delivery tick. */
    private fun WireEnvelope.isLiveTickFrom(nodeId: String): Boolean = !relay && isChatFrom(nodeId)

    private companion object {
        /** A turnaround moves each side's epoch by one, so this bounds the climb past a handful of old-era epochs. */
        const val MAX_TURNS = 12

        /** The turnarounds that take one side's epochs past the 16-row cap, and a margin. */
        const val MAX_CAP_TURNS = 20
        const val A_DAY_AND_A_MINUTE_MS = 24 * 60 * 60_000L + 60_000L
        const val DAY_MS = 24 * 60 * 60_000L
    }
}
