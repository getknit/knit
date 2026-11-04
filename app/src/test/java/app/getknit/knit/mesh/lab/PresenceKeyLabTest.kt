package app.getknit.knit.mesh.lab

import app.getknit.knit.mesh.DropReason
import app.getknit.knit.mesh.crypto.PresenceKeyPolicy
import app.getknit.knit.mesh.protocol.Protocol
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The presence key between real stacks (ADR 2026-11.xp6d, "The presence key"; knit-next #285): each phone hands its
 * current and next keys, sealed, over a live link to its direct contacts only, and stores the keys its contacts hand it.
 * Nodes read tags through [LabLimits.blindAdvert] (`CAP_BLIND_ADVERT` in the profile and the HELLO), which no shipped
 * build advertises yet. What each case pins is the pair: who holds whose keys after a removal, a block, a restore, a
 * weekly turnover, a clock that ran ahead, a first contact made while linked and a capability gained mid-link — and
 * that nothing about the keys reaches a carrier or a flood.
 *
 * The oracle beside [MeshLab.assertConverged] is [holdsKeysOf]: the holder stores the owner's current and next keys,
 * byte for byte, and the read side lists them.
 */
@RunWith(RobolectricTestRunner::class)
class PresenceKeyLabTest {
    private lateinit var lab: MeshLab

    @get:Rule
    val chaos = LabChaos.rule()

    private val blind = LabLimits(blindAdvert = true)

    @Before
    fun setUp() {
        lab = MeshLab()
    }

    @After
    fun tearDown() {
        lab.close()
    }

    /**
     * Alice's direct contact Bob gets her current and next keys over their link; nobody else she is linked to and
     * shares a confirmed session with gets anything: Carol, a stranger whose request she never accepted; Dave, a member
     * of a group they share; Erin, whom a commons accepted for her.
     */
    @Test
    fun aLinkedDirectContactGetsTheKeysAndAStrangerAGroupCoMemberAndACommonsPosterNever() =
        runBlocking {
            val alice = lab.node("alice", limits = blind).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", limits = blind).apply { setDisplayName("Bob") }
            val carol = lab.node("carol", limits = blind).apply { setDisplayName("Carol") }
            val dave = lab.node("dave", limits = blind).apply { setDisplayName("Dave") }
            val erin = lab.node("erin", limits = blind).apply { setDisplayName("Erin") }
            lab.linkAll(alice to bob, alice to carol, alice to dave, alice to erin)
            lab.awaitAcquainted(alice, bob, carol, dave, erin)

            lab.converse(alice, bob)
            // Carol and Erin each write to Alice, who never writes back: a confirmed session, a request thread.
            assertTrue(carol.sendDm(alice, "hello from carol"))
            alice.settings.acceptFromCommons(erin.nodeId) // what a commons sighting writes (MeshManager.onCommonsMember)
            assertTrue(erin.sendDm(alice, "hello from the commons"))
            lab.await(2) { alice.decrypted(alice.dmWith(carol)).size + alice.decrypted(alice.dmWith(erin)).size }
            val group = alice.createGroup(bob, dave)
            // A group post: Dave's live-link tick for it answers the seed's init, so his session with Alice confirms.
            assertTrue(alice.sendGroup(group, "hi both"))
            lab.await(3) { listOf(carol, dave, erin).count { alice.session(it)?.confirmed == true } }

            lab.awaitHoldsKeysOf(bob, alice)
            lab.awaitHoldsKeysOf(alice, bob)
            assertHandedNothing(from = alice, to = listOf(carol, dave, erin))
            lab.assertConverged(listOf(alice, bob), atLeast = 2, carriers = listOf(carol, dave, erin)) {
                it.dmWith(
                    if (it ===
                        alice
                    ) {
                        bob
                    } else {
                        alice
                    },
                )
            }
        }

    /**
     * An accept a build before the commons mark made for a commons poster carries no mark: the first reader marks every
     * accepted id still in a commons room's member list (the backfill), so Frank, accepted only that way, gets nothing.
     */
    @Test
    fun anOlderBuildsCommonsAcceptIsMarkedByTheBackfillAndNeverHandedAKey() =
        runBlocking {
            val alice = lab.node("alice", limits = blind).apply { setDisplayName("Alice") }
            val frank = lab.node("frank", limits = blind).apply { setDisplayName("Frank") }
            lab.link(alice, frank)
            lab.awaitAcquainted(alice, frank)
            assertTrue(frank.sendDm(alice, "seen you in the room"))
            lab.await(1) { alice.decrypted(alice.dmWith(frank)).size }

            alice.restart { acceptAsAnOlderBuildsCommons(frank) }
            lab.link(alice, frank)
            lab.awaitAcquainted(alice, frank)
            lab.await(1) { if (frank.nodeId in alice.settings.commonsAccepts.first()) 1 else 0 }
            assertHandedNothing(from = alice, to = listOf(frank))
            lab.assertConverged(listOf(alice, frank), atLeast = 1) { it.dmWith(if (it === alice) frank else alice) }
        }

    /**
     * Each of a removal, a block and a restore mints a fresh pair. The contact who stayed gets it over the link; the one
     * removed or blocked never does, and Alice no longer stores the keys they handed her.
     */
    @Test
    fun aRemovalABlockAndARestoreEachMintAFreshPairAndTheLeaversKeysAreDeleted() =
        runBlocking {
            val alice = lab.node("alice", limits = blind).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", limits = blind).apply { setDisplayName("Bob") }
            val carol = lab.node("carol", limits = blind).apply { setDisplayName("Carol") }
            val dave = lab.node("dave", limits = blind).apply { setDisplayName("Dave") }
            lab.linkAll(alice to bob, alice to carol, alice to dave)
            lab.awaitAcquainted(alice, bob, carol, dave)
            lab.converse(alice, bob)
            lab.converse(alice, carol)
            lab.converse(alice, dave)
            listOf(bob, carol, dave).forEach { lab.awaitHoldsKeysOf(it, alice) }
            listOf(bob, carol, dave).forEach { lab.awaitHoldsKeysOf(alice, it) }

            val first = alice.ownPair()
            alice.removeContact(carol)
            lab.await(1) { if (alice.ownPair().first() > first.last()) 1 else 0 }
            lab.awaitHoldsKeysOf(bob, alice)
            lab.awaitHoldsKeysOf(dave, alice)
            assertTrue("carol's keys are deleted", alice.presenceKeys.heldBy(carol.nodeId).isEmpty())
            assertHoldsNoneOf(carol, alice, alice.ownPair())

            val second = alice.ownPair()
            alice.block(dave)
            lab.await(1) { if (alice.ownPair().first() > second.last()) 1 else 0 }
            lab.awaitHoldsKeysOf(bob, alice)
            assertTrue("dave's keys are deleted", alice.presenceKeys.heldBy(dave.nodeId).isEmpty())
            assertHoldsNoneOf(dave, alice, alice.ownPair())

            // Ticks first: a receipt held across a reset is lost by design (ADR 2026-10.8hn6), which is not what this case tests.
            lab.assertConverged(listOf(alice, bob), atLeast = 2) { it.dmWith(if (it === alice) bob else alice) }
            val third = alice.ownPair()
            alice.restoreFromBackup()
            lab.link(alice, bob)
            lab.await(1) { if (alice.ownPair().firstOrNull()?.let { it > third.last() } == true) 1 else 0 }
            lab.awaitHoldsKeysOf(bob, alice)
            lab.awaitHoldsKeysOf(alice, bob)
            lab.assertConverged(listOf(alice, bob), atLeast = 2) { it.dmWith(if (it === alice) bob else alice) }
        }

    /**
     * The weekly turnover is seamless for a contact that got the next key: with the pair apart, the key Alice airs after
     * the turnover is one Bob already holds. Her engine's clock moves a week; the node clock, which every frame and the
     * ratchet sweep read, does not (a week-long lab jump would reap the ratchet, [LabClock]).
     */
    @Test
    fun aWeeklyTurnoverIsSeamlessForAContactThatGotTheNextKey() =
        runBlocking {
            val alice = lab.node("alice", limits = blind).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", limits = blind).apply { setDisplayName("Bob") }
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)
            lab.converse(alice, bob)
            lab.awaitHoldsKeysOf(bob, alice)
            val before = alice.ownPair()

            lab.unlink(alice, bob)
            alice.presenceSkewMs = PresenceKeyPolicy.WEEK_MS
            alice.heal()
            val after = alice.ownPair()
            assertEquals("the key aired now is the next one handed over a week ago", before.last(), after.first())
            assertTrue("bob already holds it, with no link", heldKey(bob, alice, after.first()) contentEquals ownKey(alice, after.first()))

            lab.link(alice, bob)
            lab.awaitHoldsKeysOf(bob, alice)
            lab.assertConverged(listOf(alice, bob), atLeast = 2) { it.dmWith(if (it === alice) bob else alice) }
        }

    /**
     * B3: Alice's key clock runs two years ahead and she hands Bob a next key from the future; she is restored while it
     * still runs ahead — her carried last generation keeps the new keys above the old, and the restore's new root makes
     * them a new era at Bob — and then her clock is corrected, which rotates once, in the same era.
     */
    @Test
    fun aClockAheadMintThenARestoreThenACorrectedClockEndWithBobHoldingAlicesKeys() =
        runBlocking {
            val (alice, bob) = aheadPair()
            val ahead = alice.ownPair()

            alice.restoreFromBackup()
            lab.link(alice, bob)
            lab.awaitHoldsKeysOf(bob, alice)
            lab.awaitHoldsKeysOf(alice, bob)
            assertTrue("the carried generation keeps the restore above what it minted", alice.ownPair().first() > ahead.last())

            alice.presenceSkewMs = 0L
            val stale = alice.ownPair()
            alice.heal()
            lab.await(1) { if (alice.ownPair() != stale) 1 else 0 }
            assertTrue("one rotation, in order", alice.ownPair().first() > stale.first())
            lab.awaitHoldsKeysOf(bob, alice)
            val settled = alice.ownPair()
            alice.heal()
            assertEquals("then quiet", settled, alice.ownPair())
            assertEquals(0L, bob.drops(DropReason.PRESENCE_KEY_REFUSED))
            lab.assertConverged(listOf(alice, bob), atLeast = 2) { it.dmWith(if (it === alice) bob else alice) }
        }

    /**
     * B3's other order: the clock is corrected before the restore, so the backup's last generation sits more than a year
     * past it and is clamped. Alice's new keys are below the one from the future Bob holds of hers, and Bob takes them in
     * place of every key he held: they open under the root her restore's reset made, a new era, so the key from the
     * future is not kept to outrank them.
     */
    @Test
    fun aRestoredGenerationFarAheadIsClampedAndTheLowerKeysAreTakenAsANewEra() =
        runBlocking {
            val (alice, bob) = aheadPair()
            val ahead = alice.ownPair()

            alice.presenceSkewMs = 0L
            alice.restoreFromBackup()
            lab.link(alice, bob)
            lab.awaitHoldsKeysOf(bob, alice)
            lab.awaitHoldsKeysOf(alice, bob)
            assertTrue("clamped: below the key from the future Bob held of hers", alice.ownPair().last() < ahead.last())
            assertEquals(1L, alice.metrics.keys().presenceKeyGenerationClamped)
            assertFalse(
                "a new era replaced the old keys, the one from the future with them",
                ahead.last() in bob.presenceKeys.heldBy(alice.nodeId).map { it.generation },
            )
            assertEquals(0L, bob.drops(DropReason.PRESENCE_KEY_REFUSED))
            lab.assertConverged(listOf(alice, bob), atLeast = 2) { it.dmWith(if (it === alice) bob else alice) }
        }

    /** Alice and Bob, contacts holding each other's keys, after Alice's key clock ran two years ahead and turned over. */
    private suspend fun aheadPair(): Pair<LabNode, LabNode> {
        val alice = lab.node("alice", limits = blind).apply { setDisplayName("Alice") }
        val bob = lab.node("bob", limits = blind).apply { setDisplayName("Bob") }
        lab.link(alice, bob)
        lab.awaitAcquainted(alice, bob)
        lab.converse(alice, bob)
        // Ticks first: a receipt held across a reset is lost by design (ADR 2026-10.8hn6), which is not what this case tests.
        lab.assertConverged(listOf(alice, bob), atLeast = 2) { it.dmWith(if (it === alice) bob else alice) }
        lab.awaitHoldsKeysOf(bob, alice)
        lab.awaitHoldsKeysOf(alice, bob)
        val before = alice.ownPair()
        alice.presenceSkewMs = 2 * PresenceKeyPolicy.CLAMP_AHEAD_MS
        alice.heal()
        lab.await(1) { if (alice.ownPair() != before) 1 else 0 }
        lab.awaitHoldsKeysOf(bob, alice)
        assertTrue("a next key from the future", alice.ownPair().last() > lab.clock.now() + PresenceKeyPolicy.CLAMP_AHEAD_MS)
        return alice to bob
    }

    /**
     * B2, with the link up throughout: Carol is a card-only contact of Alice's (and Alice an accept-only one of Carol's) —
     * no DM thread, no shared group — so Alice's restore
     * sends her reset over their link and not the flood (Dave, a passive carrier beside them, never holds a frame from
     * Alice to Carol it did not hold before); Carol answers it over the same link, and both keys follow on the new root,
     * with no link re-formed.
     */
    @Test
    fun aRestoreSendsACardOnlyContactsResetOverTheLinkAndTheKeysFollowItsAnswer() =
        runBlocking {
            val alice = lab.node("alice", limits = blind).apply { setDisplayName("Alice") }
            val carol = lab.node("carol", limits = blind).apply { setDisplayName("Carol") }
            val dave = lab.node("dave", limits = blind).apply { setDisplayName("Dave") }
            lab.linkAll(alice to carol, alice to dave, carol to dave)
            lab.awaitAcquainted(alice, carol, dave)
            // One side initiates, so no race replaces a session inside the floor the restore's reset then meets (a both-import
            // race did, chaos seeds 1004/1010).
            cardOnly(holder = alice, of = carol)

            alice.restoreFromBackup()
            val floodedBefore = dave.custodiedChatsFrom(alice, carol)
            lab.linkAll(alice to carol, alice to dave)
            lab.await(1) {
                alice.metrics
                    .keys()
                    .linkOwedResetsSent
                    .toInt()
            }
            lab.awaitHoldsKeysOf(carol, alice)
            lab.awaitHoldsKeysOf(alice, carol)
            lab.await(1) { if (alice.presenceKeys.linkOwedResets().isEmpty()) 1 else 0 }
            lab.assertConverged(listOf(alice, carol), atLeast = 0, carriers = listOf(dave)) {
                it.dmWith(
                    if (it ===
                        alice
                    ) {
                        carol
                    } else {
                        alice
                    },
                )
            }
            // Read once custody converged: a flooded reset would be in Dave's store by now.
            assertEquals("the reset never rode the flood", floodedBefore, dave.custodiedChatsFrom(alice, carol))
        }

    /** A first contact made while linked: each side's keys follow its own accept and the session's confirmation, over the one link. */
    @Test
    fun aFirstContactAcceptedWhileLinkedGetsTheKeysWithNoLinkReformed() =
        runBlocking {
            val alice = lab.node("alice", limits = blind).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", limits = blind).apply { setDisplayName("Bob") }
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)
            alice.heal()
            assertHandedNothing(from = alice, to = listOf(bob))

            assertTrue(alice.sendDm(bob, "hello stranger")) // Bob is Alice's direct contact now: she wrote to him
            lab.awaitHoldsKeysOf(bob, alice)
            assertHandedNothing(from = bob, to = listOf(alice))
            bob.accept(bob.dmWith(alice)) // and Alice is Bob's, once he accepts the request
            lab.awaitHoldsKeysOf(alice, bob)
            lab.assertConverged(listOf(alice, bob), atLeast = 1) { it.dmWith(if (it === alice) bob else alice) }
        }

    /**
     * A phone built to send no keys of its own beside one that sends them (ADR 2026-11.xp6d's staging,
     * `BuildConfig.PRESENCE_KEYS`): Bob's build reads tags and advertises it, so Alice's key reaches him, stored and
     * listed; it mints and sends no key of its own, so Alice holds none of his and no ctl 11 of his crosses their link.
     */
    @Test
    fun aNodeThatSendsNoKeysStoresItsContactsKeysAndMintsAndSendsNoneOfItsOwn() =
        runBlocking {
            val alice = lab.node("alice", limits = blind).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", limits = blind.copy(sendsPresenceKeys = false)).apply { setDisplayName("Bob") }
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)
            lab.converse(alice, bob)

            lab.awaitHoldsKeysOf(bob, alice)
            assertHandedNothing(from = bob, to = listOf(alice))
            assertTrue("bob minted keys of his own: ${bob.ownPair()}", bob.presenceKeys.ownKeys().isEmpty())
            assertEquals("bob sealed no key", 0L, bob.metrics.keys().presenceKeysSent)
            lab.assertConverged(listOf(alice, bob), atLeast = 2) { it.dmWith(if (it === alice) bob else alice) }
        }

    /**
     * Bob's next build reads tags, and his HELLO does not say so: the only news is his profile. Alice's link-up reconcile
     * is held off for this case, so what hands him her keys is his profile gaining the bit while the link is up.
     */
    @Test
    fun aProfileGainingTheBitMidLinkIsHandedTheKeys() =
        runBlocking {
            val alice = lab.node("alice", limits = blind.copy(presenceLinkUpDelayMs = HELD_OFF_MS)).apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)
            lab.converse(alice, bob)
            alice.heal()
            assertHandedNothing(from = alice, to = listOf(bob))

            lab.unlink(alice, bob)
            bob.restart { blindAdvert = true }
            bob.transport.advertises = bob.transport.advertises and Protocol.CAP_BLIND_ADVERT.inv()
            // Bob's frames wait until Alice's neighbour pass has registered the link, so his profile lands mid-link.
            lab.link(alice, bob) { bob.transport.hold(alice.transport) }
            alice.transport.awaitNeighborsHandled()
            bob.transport.release(alice.transport)
            lab.awaitHoldsKeysOf(bob, alice)
            lab.assertConverged(listOf(alice, bob), atLeast = 2) { it.dmWith(if (it === alice) bob else alice) }
        }

    /**
     * Keys cross a link and nothing else: on a line through Dave, Alice and Bob — direct contacts — hold none of each
     * other's, and Dave carries every DM between them; once they link, the keys cross that link alone, as point-to-point
     * frames Dave neither receives nor holds.
     */
    @Test
    fun aPassiveCarrierHoldsNothingAndKeysCrossOnlyTheirOwnLink() =
        runBlocking {
            val alice = lab.node("alice", limits = blind).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", limits = blind).apply { setDisplayName("Bob") }
            val dave = lab.node("dave", limits = blind).apply { setDisplayName("Dave") }
            lab.linkAll(alice to dave, dave to bob)
            lab.awaitAcquainted(alice, bob, dave)
            lab.converse(alice, bob)
            lab.assertConverged(listOf(alice, bob), atLeast = 2, carriers = listOf(dave)) { it.dmWith(if (it === alice) bob else alice) }
            alice.heal()
            bob.heal()
            assertHandedNothing(from = alice, to = listOf(bob, dave))
            assertHandedNothing(from = bob, to = listOf(alice, dave))
            val pointToPoint = { pointToPointChatIds(alice, dave) + pointToPointChatIds(bob, dave) }
            val before = pointToPoint()

            lab.link(alice, bob)
            lab.awaitHoldsKeysOf(bob, alice)
            lab.awaitHoldsKeysOf(alice, bob)
            lab.assertConverged(listOf(alice, bob), atLeast = 2, carriers = listOf(dave)) { it.dmWith(if (it === alice) bob else alice) }
            assertEquals("no point-to-point frame between them crossed to dave", before, pointToPoint())
            // The frames the keys crossed in, by id. A count of Dave's custody against a baseline charged the link with a
            // sealed frame of Bob's originated just after it came up (chaos seed 286559637): a late frame, not an extra one,
            // sealed by a path not pinned.
            val keyFrames = listOf(pointToPointChatIds(alice, bob), pointToPointChatIds(bob, alice))
            assertTrue("each side's keys crossed point to point: $keyFrames", keyFrames.none { it.isEmpty() })
            val taken = keyFrames.flatten().intersect(dave.custodyIds())
            assertTrue("dave took on $taken\n${lab.report(listOf(alice, bob, dave))}", taken.isEmpty())
        }

    /**
     * Crypto review item 1: restored Carol's link-owed reset R2 is lost on her first link to Alice, a card-only contact;
     * she then re-roots to R3 by her own route (the flood, Dave carrying it) while Alice is linked to nobody. At the relink
     * Carol never resends R2 — a kept reset another one replaced — so Alice takes R3 from Carol's custody and the pair ends
     * on one root, Carol's entry dropped.
     */
    @Test
    fun aLostLinkResetAnotherResetReplacedIsNeverResentAndThePairEndsOnTheNewRoot() =
        runBlocking {
            val limits = blind.copy(resetFloorMs = SHORT_FLOOR_MS)
            val alice = lab.node("alice", limits = limits).apply { setDisplayName("Alice") }
            val carol = lab.node("carol", limits = limits).apply { setDisplayName("Carol") }
            val dave = lab.node("dave", limits = limits).apply { setDisplayName("Dave") }
            lab.linkAll(alice to carol, carol to dave)
            lab.awaitAcquainted(alice, carol, dave)
            cardOnly(holder = carol, of = alice)

            carol.restoreFromBackup()
            lab.link(carol, dave)
            lab.link(alice, carol) { carol.transport.lossy(alice.transport) { !it.relay } }
            lab.await(1) {
                carol.metrics
                    .keys()
                    .linkOwedResetsSent
                    .toInt()
            }
            val keptTag = checkNotNull(carol.presenceKeys.linkOwedResets()[alice.nodeId]?.rootTag) { "carol kept no R2" }
            lab.unlink(alice, carol)
            val sentBeforeRelink = carol.metrics.keys().linkOwedResetsSent
            // A new root, not a marker under the held one (a null from resetSession can be either; a marker makes RESEND right).
            lab.await(1) {
                if (carol.rootTagFor(alice) != keptTag) {
                    1
                } else {
                    carol.resetSession(alice)
                    0
                }
            }

            // Alice's answer to R3 is held until Carol's link-up pass has judged the kept R2, so that pass meets an
            // unconfirmed session: the superseded branch is what runs, whatever the link-up wait picks.
            lab.link(alice, carol) { alice.transport.hold(carol.transport) }
            lab.await(1) {
                carol.metrics
                    .keys()
                    .linkOwedResetsSuperseded
                    .toInt()
            }
            alice.transport.release(carol.transport)
            lab.await(1) { if (carol.presenceKeys.linkOwedResets().isEmpty()) 1 else 0 }
            lab.awaitHoldsKeysOf(alice, carol)
            lab.awaitHoldsKeysOf(carol, alice)
            // With every node's report (the engine's decision lines among its warnings), so a failure names the step taken.
            val nodes = listOf(alice, carol, dave)
            assertEquals("R2 was never resent\n${lab.report(nodes)}", sentBeforeRelink, carol.metrics.keys().linkOwedResetsSent)
            assertEquals("dropped as superseded, once\n${lab.report(nodes)}", 1L, carol.metrics.keys().linkOwedResetsSuperseded)
            lab.assertConverged(listOf(alice, carol), atLeast = 0, carriers = listOf(dave)) {
                it.dmWith(
                    if (it ===
                        alice
                    ) {
                        carol
                    } else {
                        alice
                    },
                )
            }
        }

    /**
     * A reset while a presence key is on its way, each direction its own case at the production floor (a second root
     * inside a fresh reset's minute is declined in the field, so one scenario cannot reset both ways): the key sealed under
     * the old root lands after the receiver took the reset (a drain open, ordered against what it holds, never a new era),
     * and the sender's reconcile on the new root seals the pair again. Each ends holding the other's current and next keys
     * under one root.
     */
    @Test
    fun aResetByTheReceiverWhileAKeyIsInFlightEndsWithBothKeysUnderTheLiveRoot() = keyInFlightAcrossAReset(senderIsAlice = true)

    /** The case above the other way round: Alice resets while Bob's key is in flight. */
    @Test
    fun aResetByTheReceiverTheOtherWayRoundEndsWithBothKeysUnderTheLiveRoot() = keyInFlightAcrossAReset(senderIsAlice = false)

    private fun keyInFlightAcrossAReset(senderIsAlice: Boolean) =
        runBlocking {
            val alice = lab.node("alice", limits = blind).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", limits = blind).apply { setDisplayName("Bob") }
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)
            lab.converse(alice, bob)
            lab.awaitHoldsKeysOf(bob, alice)
            lab.awaitHoldsKeysOf(alice, bob)

            if (senderIsAlice) {
                keyInFlightAcrossAReset(
                    sender = alice,
                    resetter = bob,
                )
            } else {
                keyInFlightAcrossAReset(sender = bob, resetter = alice)
            }
            lab.awaitHoldsKeysOf(bob, alice)
            lab.awaitHoldsKeysOf(alice, bob)
            for ((holder, owner) in listOf(bob to alice, alice to bob)) {
                awaitKeysUnderTheLiveRoot(holder, owner)
                assertEquals(0L, holder.drops(DropReason.PRESENCE_KEY_REFUSED))
            }
            lab.assertConverged(listOf(alice, bob), atLeast = 2) { it.dmWith(if (it === alice) bob else alice) }
        }

    /** [sender]'s turned-over keys are held on the link while [resetter] resets their session; then both cross. */
    private suspend fun keyInFlightAcrossAReset(
        sender: LabNode,
        resetter: LabNode,
    ) {
        // Ticks first: a receipt held across a reset is lost by design (ADR 2026-10.8hn6), which is not what this case tests.
        lab.assertConverged(listOf(sender, resetter), atLeast = 2) { it.dmWith(if (it === sender) resetter else sender) }
        sender.transport.hold(resetter.transport)
        sender.presenceSkewMs += PresenceKeyPolicy.WEEK_MS
        sender.heal()
        lab.await(1) { sender.transport.held(resetter.transport).count { !it.relay } }
        lab.await(1) { if (resetter.resetSession(sender) == null) 1 else 0 }
        // The reset reaches the sender, which answers it and seals its keys again under the new root: both held. The one
        // sealed under the root the resetter replaced does not open there (its own re-root purged that era's receive state).
        lab.await(2) { sender.transport.held(resetter.transport).count { !it.relay } }
        sender.transport.release(resetter.transport)
        lab.awaitHoldsKeysOf(resetter, sender)
        lab.await(2) { listOf(sender.session(resetter), resetter.session(sender)).count { it?.confirmed == true } }
    }

    /**
     * The drain, where it lives: at the receiver of someone else's reset. Alice turns her keys over with the link held,
     * then resets the session; Bob takes the reset first and the key sealed under the old root after it — it opens on his
     * draining `prevRoot` and is filed under the old root's tag, never as a new era. Alice's reconcile on the confirmed new
     * root then seals the pair again, which Bob takes as the new era: every key under the live root, nothing refused.
     */
    @Test
    fun aKeyInFlightAcrossItsSendersOwnResetDrainsUnderTheOldTagThenMovesToTheNewRoot() =
        runBlocking {
            val alice = lab.node("alice", limits = blind).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", limits = blind).apply { setDisplayName("Bob") }
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)
            lab.converse(alice, bob)
            lab.awaitHoldsKeysOf(bob, alice)
            lab.awaitHoldsKeysOf(alice, bob)
            // Ticks first: a receipt held across a reset is lost by design (ADR 2026-10.8hn6), which is not what this case tests.
            lab.assertConverged(listOf(alice, bob), atLeast = 2) { it.dmWith(if (it === alice) bob else alice) }
            val oldTag =
                bob.presenceKeys
                    .heldBy(alice.nodeId)
                    .first()
                    .rootTag

            alice.transport.hold(bob.transport)
            alice.presenceSkewMs += PresenceKeyPolicy.WEEK_MS
            alice.heal()
            val turnedOver = alice.ownPair()
            lab.await(1) { alice.transport.held(bob.transport).count { !it.relay } }
            val floodedBefore = alice.transport.held(bob.transport).count { it.relay }
            lab.await(1) { if (alice.resetSession(bob) == null) 1 else 0 }
            lab.await(floodedBefore + 1) { alice.transport.held(bob.transport).count { it.relay } }
            // The flooded reset first, the point-to-point key after it; keep holding what follows (her re-seal).
            alice.transport.release(bob.transport, keepHolding = true) { batch -> batch.sortedBy { !it.relay } }
            bob.transport.awaitInboundDrained()
            val drained = bob.presenceKeys.heldBy(alice.nodeId).firstOrNull { it.generation == turnedOver.last() }
            assertEquals("the in-flight key lands on the drain, under the old root's tag", oldTag, drained?.rootTag)

            alice.transport.release(bob.transport)
            lab.awaitHoldsKeysOf(bob, alice)
            awaitKeysUnderTheLiveRoot(bob, alice)
            awaitKeysUnderTheLiveRoot(alice, bob)
            assertEquals(0L, bob.drops(DropReason.PRESENCE_KEY_REFUSED))
            assertEquals(0L, alice.drops(DropReason.PRESENCE_KEY_REFUSED))
            lab.assertConverged(listOf(alice, bob), atLeast = 2) { it.dmWith(if (it === alice) bob else alice) }
        }

    /** Awaits [holder] filing every key of [owner]'s under the session root it holds with [owner] now. */
    private suspend fun awaitKeysUnderTheLiveRoot(
        holder: LabNode,
        owner: LabNode,
    ) {
        val live =
            lab.tryAwait(1) {
                val tags =
                    holder.presenceKeys
                        .heldBy(owner.nodeId)
                        .map { it.rootTag }
                        .distinct()
                if (tags == listOf(holder.rootTagFor(owner))) 1 else 0
            }
        assertTrue("${holder.name} never filed ${owner.name}'s keys under the live root\n${lab.report(listOf(holder, owner))}", live)
    }

    /**
     * A card-only contact who posts in a commons Alice shares stays her direct contact: a commons sighting marks only an
     * accept it made itself, never one the user made (`SettingsStore.acceptFromCommons`), so Bob keeps getting her keys.
     */
    @Test
    fun aCardOnlyContactWhoPostsInASharedCommonsStaysADirectContact() =
        runBlocking {
            val alice = lab.node("alice", limits = blind, commons = true).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", limits = blind).apply { setDisplayName("Bob") }
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)
            cardOnly(holder = alice, of = bob)

            alice.manager.onCommonsMember("c-shared", bob.nodeId)
            assertFalse(bob.nodeId in alice.settings.commonsAccepts.first())
            assertTrue(bob.nodeId in alice.directContacts.read())
            val before = alice.ownPair()
            alice.presenceSkewMs = PresenceKeyPolicy.WEEK_MS
            alice.heal()
            lab.await(1) { if (alice.ownPair() != before) 1 else 0 }
            lab.awaitHoldsKeysOf(bob, alice)
            lab.assertConverged(listOf(alice, bob), atLeast = 0) { it.dmWith(if (it === alice) bob else alice) }
        }

    /**
     * [holder] imports [of]'s card and [of] accepts the request — one side initiates, so no race replaces a session inside
     * a reset floor — and each holds the other's keys: a contact with no DM thread and no shared group.
     */
    private suspend fun cardOnly(
        holder: LabNode,
        of: LabNode,
    ) {
        holder.importCard(of.mintCard())
        lab.await(1) { if (of.session(holder)?.hasSession == true) 1 else 0 }
        of.accept(of.dmWith(holder))
        lab.await(2) { listOf(holder.session(of), of.session(holder)).count { it?.confirmed == true } }
        lab.awaitHoldsKeysOf(holder, of)
        lab.awaitHoldsKeysOf(of, holder)
    }

    // --- helpers ---

    private companion object {
        /** A link-up's presence-key wait no scenario sits out: the case's other triggers have to do the work. */
        const val HELD_OFF_MS = 600_000L

        /**
         * The reset floor (wall clock) where a scenario genuinely needs two roots from one node inside a minute: the lost
         * link-owed reset and the re-root that replaces it. Every other scenario keeps the field's minute.
         */
        const val SHORT_FLOOR_MS = 1_000L
    }
}
