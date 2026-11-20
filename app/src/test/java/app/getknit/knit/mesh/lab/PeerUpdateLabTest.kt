package app.getknit.knit.mesh.lab

import app.getknit.knit.mesh.DropReason
import app.getknit.knit.mesh.protocol.AppPin
import app.getknit.knit.mesh.protocol.AppUpdatePayload
import app.getknit.knit.update.AppBuild
import app.getknit.knit.update.AskOutcome
import app.getknit.knit.update.LocalBuild
import kotlinx.coroutines.flow.first
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
 * Peer updates between real stacks (ADR 2026-11.29m7): the newer phone tells a direct contact on its link which Knit it
 * runs, the older one asks over that link, and the ask binds the one offer that answers it. Every frame is a sealed
 * `CTL_APP_UPDATE` handed to the live link, so a carrier holds none of them, and a copy marked for relay is dropped
 * rather than acted on. The bytes themselves cross over a direct transfer, which the lab has no radio for:
 * `TransferAppUpdateTest` takes them from the offer on.
 */
@RunWith(RobolectricTestRunner::class)
class PeerUpdateLabTest {
    private lateinit var lab: MeshLab

    @get:Rule
    val chaos = LabChaos.rule()

    private val older = AppBuild(21_300_900L, "2.13.0", CERT, 120_000_000L, 29, slim = false)
    private val newer = AppBuild(21_400_900L, "2.14.0", CERT, 124_000_000L, 29, slim = false)

    @Before
    fun setUp() {
        lab = MeshLab()
    }

    @After
    fun tearDown() {
        lab.close()
    }

    /**
     * Alice runs 2.14.0 and Bob 2.13.0, contacts on one link; Carol, Bob's other neighbour, is no contact of Alice's.
     * Bob hears Alice's build and asks; Alice holds the ask for her person; the ask names the one offer that answers it.
     * Carol carries the pair's DMs, and custody converges across all three: no update frame is ever custodied.
     */
    @Test
    fun theOlderContactHearsTheNewerBuildAsksAndTheAskBindsOneOffer() =
        runBlocking {
            val alice = lab.node("alice").apply { appBuild = LocalBuild(newer, canServe = true, canInstall = true) }
            val bob = lab.node("bob").apply { appBuild = LocalBuild(older, canServe = true, canInstall = true) }
            val carol = lab.node("carol").apply { appBuild = LocalBuild(older, canServe = true, canInstall = true) }
            lab.linkAll(alice to bob, bob to carol)
            lab.awaitAcquainted(alice, bob, carol)
            olderWritesFirst(older = bob, newer = alice)

            awaitHint(bob, alice)
            // Alice hears Bob's older build and offers him nothing: only the older side asks.
            assertNull(alice.peerUpdates.hintFor(bob.nodeId).first())

            assertEquals(AskOutcome.Sent, bob.peerUpdates.ask(alice.nodeId))
            lab.await(1) { alice.peerUpdates.requests.value.size }
            val request = checkNotNull(alice.peerUpdates.requests.value[bob.nodeId])
            assertEquals(newer.versionCode, request.versionCode)
            val claimed = checkNotNull(alice.peerUpdates.claim(bob.nodeId))
            val pin = AppPin(claimed.requestId, claimed.versionCode, ByteArray(AppPin.SHA256_BYTES))
            assertTrue(bob.peerUpdates.expects(alice.nodeId, pin, newer.size))
            assertTrue("spent", !bob.peerUpdates.expects(alice.nodeId, pin, newer.size))

            assertEquals(0L, bob.drops(DropReason.APP_UPDATE_NOT_FROM_LINK))
            assertEquals(0L, carol.drops(DropReason.APP_UPDATE_NOT_FROM_LINK))
            lab.assertConverged(listOf(alice, bob), atLeast = 2, carriers = listOf(carol)) {
                it.dmWith(if (it === alice) bob else alice)
            }
        }

    /**
     * Bob's clock runs twenty minutes behind Alice's when he asks, as phones offline for weeks drift (ADR 026). The ask's
     * signed time is never read, so Alice holds it for her person all the same, and the pair still converges once the
     * clocks agree again.
     */
    @Test
    fun anAskFromAContactWhoseClockLagsIsStillHeard() =
        runBlocking {
            val alice = lab.node("alice").apply { appBuild = LocalBuild(newer, canServe = true, canInstall = true) }
            val bob = lab.node("bob").apply { appBuild = LocalBuild(older, canServe = true, canInstall = true) }
            lab.linkAll(alice to bob)
            lab.awaitAcquainted(alice, bob)
            olderWritesFirst(older = bob, newer = alice)
            awaitHint(bob, alice)

            lab.clock.skew("bob", -LAG_MS)
            assertEquals(AskOutcome.Sent, bob.peerUpdates.ask(alice.nodeId))
            lab.await(1) { alice.peerUpdates.requests.value.size }
            lab.clock.skew("bob", 0L)
            // While Bob ran slow his custody turned away Alice's frames, dated in his future; with his clock back a fresh
            // link's digest exchange carries them over (`ScopeConfigLabTest`'s heal). The 11-minute step is a margin: his
            // router's seen entries were stamped on his slow clock.
            lab.clock.advance(SEEN_WINDOW_LAPSED_MS)
            lab.unlink(alice, bob)
            lab.link(alice, bob)
            lab.assertConverged(listOf(alice, bob), atLeast = 2) { it.dmWith(if (it === alice) bob else alice) }
        }

    /**
     * Alice marks a sealed announce for relay and floods it, as a contact or a device in the middle of the link could,
     * `relay` being outside the signed bytes. Bob, her linked contact, drops the copy and counts it rather than show a hint;
     * Carol only carries a frame addressed to Bob, and custody still converges across all three.
     */
    @Test
    fun aFloodedCopyIsDroppedAndCountedNotActedOn() =
        runBlocking {
            val alice = lab.node("alice")
            val bob = lab.node("bob").apply { appBuild = LocalBuild(older, canServe = true, canInstall = true) }
            val carol = lab.node("carol").apply { appBuild = LocalBuild(older, canServe = true, canInstall = true) }
            lab.linkAll(alice to bob, bob to carol)
            lab.awaitAcquainted(alice, bob, carol)
            lab.converse(alice, bob)

            val announce =
                AppUpdatePayload(
                    phase = AppUpdatePayload.PHASE_ANNOUNCE,
                    versionCode = newer.versionCode,
                    versionName = newer.versionName,
                    certPin = newer.certPin,
                    size = newer.size,
                    minSdk = newer.minSdk,
                )
            // A seal goes only under a confirmed session; Alice's confirms once Bob's reply opened.
            lab.await(1) { if (alice.session(bob)?.confirmed == true) 1 else 0 }
            assertTrue(alice.manager.floodAppUpdateForLab(bob.nodeId, announce))
            lab.await(1) { bob.drops(DropReason.APP_UPDATE_NOT_FROM_LINK).toInt() }
            assertNull(bob.peerUpdates.hintFor(alice.nodeId).first())
            lab.assertConverged(listOf(alice, bob), atLeast = 2, carriers = listOf(carol)) {
                it.dmWith(if (it === alice) bob else alice)
            }
        }

    /**
     * The two meet as contacts, the older phone writing first. Order matters: a DM's initiator confirms its session on
     * the first frame the other side seals back, the instant ✓✓ included, which can come before the other side has
     * written its reply and so accepted the thread. An announce sent in that gap is dropped by a receiver that has not
     * accepted its sender, and owes nothing more for a day. With the older side first, the newer side accepts the thread
     * by replying before it can pass its own announce gate, and the older side has accepted it already.
     */
    private suspend fun olderWritesFirst(
        older: LabNode,
        newer: LabNode,
    ) = lab.converse(older, newer)

    /** [older] shows the hint for [newer]'s build. */
    private suspend fun awaitHint(
        older: LabNode,
        newer: LabNode,
    ) = lab.await(1) {
        if (older.peerUpdates
                .hintFor(newer.nodeId)
                .first()
                ?.build == this.newer
        ) {
            1
        } else {
            0
        }
    }

    private companion object {
        const val CERT = 0x0123_4567_89ab_cdefL

        // Past the five-minute bound an earlier draft read a frame's signed time against.
        const val LAG_MS = 20 * 60_000L
        const val SEEN_WINDOW_LAPSED_MS = 11 * 60_000L
    }
}
