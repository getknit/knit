package app.getknit.knit.mesh.lab

import app.getknit.knit.mesh.DropReason
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.ScopeConfigPayload
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import app.getknit.knit.mesh.spool.ScopeConfig
import app.getknit.knit.mesh.spool.ScopeConfigPolicy
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The scope-config ctl between real stacks (`CTL_SCOPE_CONFIG`, docs/SPOOL_PROTOCOL.md §5): an ordinary sealed ctl,
 * custodied and flooded, that every member applies last-writer-wins on `(version, issuer)`. These nodes hold no home,
 * so their issuer never runs (`HomeSpoolsLabTest` is the one that does): each scenario issues configs by hand and
 * compares the config stores node by node; `assertConverged` covers the custody and the threads around them.
 */
@RunWith(RobolectricTestRunner::class)
class ScopeConfigLabTest {
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

    private fun payload(
        version: Int,
        relay: String,
        groupId: String? = null,
    ) = ScopeConfigPayload(
        groupId = groupId,
        spools = listOf("wss://$relay.spool.getknit.app/spool/v1"),
        maxFrames = 400,
        ttlMs = 48 * 60 * 60_000L,
        maxBlob = 65_536,
        version = version,
    )

    /** A DM both sides have answered under, so each holds a confirmed session — what a config is sealed under. */
    private suspend fun confirmed(
        a: LabNode,
        b: LabNode,
    ) {
        assertTrue(a.sendDm(b, "hello ${b.name}"))
        lab.await(1) { b.decrypted(b.dmWith(a)).count { it.second == "hello ${b.name}" } }
        assertTrue(b.sendDm(a, "hello ${a.name}"))
        lab.await(1) { a.decrypted(a.dmWith(b)).count { it.second == "hello ${a.name}" } }
        lab.await(1) { if (a.session(b)?.confirmed == true && b.session(a)?.confirmed == true) 1 else 0 }
    }

    /** 1 once [node] holds [expected] for [conversationId], else 0. */
    private suspend fun holds(
        node: LabNode,
        conversationId: String,
        expected: ScopeConfig?,
    ): Int = if (node.scopeConfig(conversationId) == expected) 1 else 0

    private fun config(
        issuer: LabNode,
        payload: ScopeConfigPayload,
    ) = ScopeConfig("", payload.groupId, issuer.nodeId, payload.spools, payload.maxFrames, payload.ttlMs, payload.maxBlob, payload.version)

    /**
     * Both sides issue version 1 at once; the tie goes to the higher node id on both phones (C-5-4). The other side then
     * issues on top of it and both move to that. The two stores read the same config at each step.
     */
    @Test
    fun twoSidesIssuingAtOnceConvergeOnOneConfigAndThenOnTheNextVersion() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)
            confirmed(alice, bob)

            val ours = payload(1, "public-2")
            val theirs = payload(1, "public-3")
            assertTrue("alice's config was sealed", alice.sendScopeConfig(bob.nodeId, ours))
            assertTrue("bob's config was sealed", bob.sendScopeConfig(alice.nodeId, theirs))
            val (high, low) = if (alice.nodeId > bob.nodeId) alice to bob else bob to alice
            val tie = config(high, if (high === alice) ours else theirs)
            // Each phone wrote its own row first (`reserve`), so the high phone reads the tie at once: what proves the
            // tie-break there is the low phone's row landing beside it and the reading not moving.
            lab.await(1) { if (1 in high.heldScopeConfigs(low.nodeId, issuer = low)) 1 else 0 }
            assertEquals("the higher id keeps the tie", tie, high.scopeConfig(low.nodeId))
            lab.await(1) { holds(low, high.nodeId, tie) }

            val next = payload(ScopeConfigPolicy.nextVersion(tie, lab.clock.now()), "public-4")
            assertTrue("the lower id issued on top", low.sendScopeConfig(high.nodeId, next))
            lab.await(1) { holds(alice, bob.nodeId, config(low, next)) }
            lab.await(1) { holds(bob, alice.nodeId, config(low, next)) }
            assertEquals(0L, alice.drops(DropReason.SCOPE_CONFIG_REFUSED) + bob.drops(DropReason.SCOPE_CONFIG_REFUSED))
            lab.assertConverged(listOf(alice, bob), atLeast = 2) { it.dmWith(if (it === alice) bob else alice) }
        }

    /**
     * Alice issues a group config and leaves straight after. Carol hears the leave first and refuses the config; Bob
     * hears the config first and applies it, and then the leave drops it again. Both end on Bob's earlier config — the
     * order-independence the per-issuer rows exist for, since nothing re-sends a config.
     */
    @Test
    fun aDepartedMembersConfigIsRefusedInEitherOrderAndTheRemainingMembersAgree() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            val carol = lab.node("carol").apply { setDisplayName("Carol") }
            lab.linkAll(alice to bob, bob to carol, alice to carol)
            lab.awaitAcquainted(alice, bob, carol)
            confirmed(alice, bob)
            confirmed(alice, carol)
            confirmed(bob, carol)
            val groupId = alice.createGroup(bob, carol)
            assertTrue(alice.sendGroup(groupId, "founded"))
            // Bob writes only once the founding post gave him the group.
            lab.assertConverged(listOf(alice, bob, carol), atLeast = 1) { groupId }
            assertTrue(bob.sendGroup(groupId, "here"))
            lab.assertConverged(listOf(alice, bob, carol), atLeast = 2) { groupId }

            val standing = payload(1, "public-2", groupId)
            assertTrue("bob's config was sealed", bob.sendScopeConfig(groupId, standing))
            for (node in listOf(alice, bob, carol)) lab.await(1) { holds(node, groupId, config(bob, standing)) }

            // Everything Alice sends from here waits on both pipes, and Carol's onward copies to Bob wait too, so each
            // member hears Alice's two frames only in the order this scenario hands them over.
            alice.transport.hold(bob.transport)
            alice.transport.hold(carol.transport)
            carol.transport.hold(bob.transport)
            val leaving = payload(2, "public-3", groupId)
            val custodied = alice.custodyIds()
            assertTrue("alice's config was sealed", alice.sendScopeConfig(groupId, leaving))
            // The config is the one frame Alice's custody gained: a ctl's row is its custody copy alone.
            val sent = (alice.custodyIds() - custodied).single()
            alice.leaveGroup(groupId)
            for (pipe in listOf(carol.transport, bob.transport)) {
                lab.await(1) {
                    val held = alice.transport.held(pipe)
                    if (held.any { it.idIs(sent) } && held.any { it.isGroupLeave() }) 1 else 0
                }
            }

            // Carol: the leave, then the config, which now comes from a departed member.
            alice.transport.release(carol.transport) { batch -> batch.sortedBy { !it.isGroupLeave() } }
            lab.await(1) { if (carol.groupShape(groupId)?.departed == setOf(alice.nodeId)) 1 else 0 }
            lab.await(1) { carol.drops(DropReason.SCOPE_CONFIG_REFUSED).toInt() }

            // Bob: the config while Alice is still a member, then the leave.
            alice.transport.release(bob.transport) { batch -> batch.sortedBy { it.isGroupLeave() } }
            lab.await(1) { if (bob.groupShape(groupId)?.departed == setOf(alice.nodeId)) 1 else 0 }
            assertEquals("bob took alice's config before her leave", 0L, bob.drops(DropReason.SCOPE_CONFIG_REFUSED))
            carol.transport.release(bob.transport)

            for (node in listOf(bob, carol)) lab.await(1) { holds(node, groupId, config(bob, standing)) }
            assertTrue(bob.sendGroup(groupId, "after alice left"))
            lab.assertConverged(listOf(bob, carol), atLeast = 3, carriers = listOf(alice)) { groupId }
            for (node in listOf(bob, carol)) assertEquals(config(bob, standing), node.scopeConfig(groupId))
        }

    /**
     * A config, then a reset across it: Bob issues a newer config under the old session while Alice resets it, both
     * apart. On the merge Alice reads Bob's config under the root her reset retired, the pair confirms on the new
     * session, and Alice issues the next version under it. Every old-era copy custody re-serves (a ctl has no row to
     * stop it) opens or drops as a duplicate, and neither side resets again: no `RATCHET_*` drop counted as evidence.
     */
    @Test
    fun aConfigAcrossAResetLandsOnBothSidesAndNeitherResetsAgain() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)
            confirmed(alice, bob)
            val first = payload(1, "public-2")
            assertTrue(alice.sendScopeConfig(bob.nodeId, first))
            lab.await(1) { holds(bob, alice.nodeId, config(alice, first)) }

            lab.unlink(alice, bob)
            val apart = payload(2, "public-3")
            assertTrue("bob's session is still confirmed on his side", bob.sendScopeConfig(alice.nodeId, apart))
            assertNull("alice's reset went out", alice.resetSession(bob))
            val resetAt = checkNotNull(alice.session(bob)).lastResetSentAt
            assertFalse("alice's config waits for the reset's answer", alice.sendScopeConfig(bob.nodeId, payload(3, "public-4")))

            lab.link(alice, bob)
            lab.await(1) { holds(alice, bob.nodeId, config(bob, apart)) }
            lab.await(1) { if (alice.session(bob)?.confirmed == true) 1 else 0 }
            val after = payload(3, "public-4")
            assertTrue("alice issues under the new session", alice.sendScopeConfig(bob.nodeId, after))
            lab.await(1) { holds(alice, bob.nodeId, config(alice, after)) }
            lab.await(1) { holds(bob, alice.nodeId, config(alice, after)) }

            assertTrue(alice.sendDm(bob, "after the reset"))
            lab.assertConverged(listOf(alice, bob), atLeast = 3) { it.dmWith(if (it === alice) bob else alice) }
            assertEquals("bob never reset back", 0L, bob.session(alice)?.lastResetSentAt)
            assertEquals("alice reset once", resetAt, alice.session(bob)?.lastResetSentAt)
            assertEquals(0L, alice.drops(DropReason.SCOPE_CONFIG_REFUSED) + bob.drops(DropReason.SCOPE_CONFIG_REFUSED))
        }

    /**
     * The version is a clock, so nothing a phone holds makes an honest config unacceptable. Bob loses every row (a
     * restore from before them) and takes Alice's next config with none to judge it by; then Alice loses her own and,
     * a few minutes on, issues on top of nothing she remembers — and still outranks what Bob holds of hers. Both stores
     * read the same config at each step. A bound against the held winner refused both for good.
     */
    @Test
    fun aPhoneEmptiedOfItsRowsAndAnIssuerRestoredStaleBothConvergeOnTheNextConfig() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)
            confirmed(alice, bob)

            val first = payload(ScopeConfigPolicy.nextVersion(null, lab.clock.now()), "public-2")
            assertTrue(alice.sendScopeConfig(bob.nodeId, first))
            lab.await(1) { holds(bob, alice.nodeId, config(alice, first)) }

            bob.forgetScopeConfigs(alice.nodeId)
            val second = payload(ScopeConfigPolicy.nextVersion(alice.scopeConfig(bob.nodeId), lab.clock.now()), "public-3")
            assertTrue(alice.sendScopeConfig(bob.nodeId, second))
            lab.await(1) { holds(bob, alice.nodeId, config(alice, second)) }
            assertEquals(config(alice, second), alice.scopeConfig(bob.nodeId))

            alice.forgetScopeConfigs(bob.nodeId)
            lab.clock.advance(2 * 60_000L)
            val third = payload(ScopeConfigPolicy.nextVersion(alice.scopeConfig(bob.nodeId), lab.clock.now()), "public-4")
            assertTrue("a minute on, the clock outruns what she issued before", third.version > second.version)
            assertTrue(alice.sendScopeConfig(bob.nodeId, third))
            lab.await(1) { holds(bob, alice.nodeId, config(alice, third)) }
            assertEquals(config(alice, third), alice.scopeConfig(bob.nodeId))
            assertEquals(0L, alice.drops(DropReason.SCOPE_CONFIG_REFUSED) + bob.drops(DropReason.SCOPE_CONFIG_REFUSED))
            lab.assertConverged(listOf(alice, bob), atLeast = 2) { it.dmWith(if (it === alice) bob else alice) }
        }

    /**
     * C-5-4's hold-and-defer, on the lab's clock seam. Bob's clock runs 20 minutes slow. As a receiver he holds Alice's
     * config, whose version is her minute, without reading it — nothing is refused, so nothing is lost; as an issuer he
     * still sends, at his own slow minute, and Alice takes it and keeps her own, the later one, as the winner. Once
     * Bob's clock agrees he reads her config too, from the row he held all along, and both stores end on it.
     */
    @Test
    fun aNodeTwentyMinutesSlowHoldsWhatItCannotReadYetSendsAnywayAndConvergesOnceItsClockAgrees() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)
            confirmed(alice, bob)
            lab.clock.skew("bob", -20 * 60_000L)

            val hers = payload(ScopeConfigPolicy.nextVersion(alice.scopeConfig(bob.nodeId), alice.now()), "public-2")
            assertTrue(alice.sendScopeConfig(bob.nodeId, hers))
            lab.await(1) { if (hers.version in bob.heldScopeConfigs(alice.nodeId)) 1 else 0 }
            assertNull("held, not yet read on bob's slow clock", bob.scopeConfig(alice.nodeId))
            assertEquals(0L, bob.drops(DropReason.SCOPE_CONFIG_REFUSED))

            val his = payload(ScopeConfigPolicy.nextVersion(bob.scopeConfig(alice.nodeId), bob.now()), "public-3")
            assertTrue("a slow issuer still sends", his.version < hers.version)
            assertTrue(bob.sendScopeConfig(alice.nodeId, his))
            lab.await(1) { if (his.version in alice.heldScopeConfigs(bob.nodeId)) 1 else 0 }
            assertEquals("the later config wins on the correct clock", config(alice, hers), alice.scopeConfig(bob.nodeId))

            lab.clock.skew("bob", 0L)
            lab.await(1) { holds(bob, alice.nodeId, config(alice, hers)) }
            assertEquals(config(alice, hers), alice.scopeConfig(bob.nodeId))
            assertEquals(0L, alice.drops(DropReason.SCOPE_CONFIG_REFUSED) + bob.drops(DropReason.SCOPE_CONFIG_REFUSED))
            // While Bob ran slow his custody turned away Alice's frames, dated in his future; with his clock back they
            // pass its future-skew check, and a fresh link's digest exchange carries them over. The 11-minute step is
            // only a margin: his router's seen entries were stamped on his slow clock and have already lapsed.
            lab.clock.advance(SEEN_WINDOW_LAPSED_MS)
            lab.unlink(alice, bob)
            lab.link(alice, bob)
            lab.assertConverged(listOf(alice, bob), atLeast = 2) { it.dmWith(if (it === alice) bob else alice) }
        }

    private companion object {
        /** A margin past the router's 10-minute seen window (`SeenSet.DEFAULT_TTL_MS`), as `TimeLabTest` steps it. */
        const val SEEN_WINDOW_LAPSED_MS = 11 * 60_000L
    }

    private fun WireEnvelope.isGroupLeave(): Boolean = WireCodec.decodeEnvelope(signed)?.type == FrameType.GROUP_LEAVE

    private fun WireEnvelope.idIs(id: String): Boolean = WireCodec.decodeEnvelope(signed)?.id == id
}
