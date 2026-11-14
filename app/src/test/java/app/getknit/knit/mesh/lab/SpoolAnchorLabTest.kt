package app.getknit.knit.mesh.lab

import app.getknit.knit.mesh.spool.FakeSpool
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.random.Random

/**
 * The heal round's anchor against the spool's digests (ADR 2026-09.wa79's 2026-11-14 amendment): what a pair does
 * when the spool says something new while a member's round is waiting on its LIST.
 */
@RunWith(RobolectricTestRunner::class)
class SpoolAnchorLabTest {
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

    private fun LabNode.dmThreadWith(other: LabNode): (LabNode) -> String = { if (it === this) dmWith(other) else it.dmWith(this) }

    /**
     * #322, forced: both members' heal rounds have a LIST in flight when the garbage lands and the spool says so,
     * and each LIST is then answered with the live set from before the plant. A round that re-anchored on that
     * listing over the newer digest left both members' anchors equal to their own folds, and nothing — the 60 s
     * tick included — listed the scope again: neither ever pulled the garbage (CI medic case 1114-7c6d).
     */
    @Test
    fun aDigestThatCrossesAListingIsNotReanchoredAway() =
        runBlocking {
            val spool = FakeSpool()
            val alice = lab.node("alice", spool = spool).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", spool = spool).apply { setDisplayName("Bob") }
            lab.meetOnTheRelay(alice, bob)
            val scope = checkNotNull(alice.dmScopeStatus(bob)).scopeHex

            spool.holdLists(scope)
            spool.gossipDigest(scope, 0x5eedL) // both anchors move, so both members' next rounds list
            assertTrue(
                "both members' LISTs never went out: ${spool.heldLists()} held",
                lab.tryAwait(2, timeoutMs = MeshLab.SPOOL_AWAIT_MS) { spool.heldLists() },
            )
            val id = spool.plantGarbage(scope, Random(22).nextBytes(200))
            spool.announce(scope)
            spool.releaseLists() // each answered with its pre-plant snapshot, behind the digest that counts the plant

            assertTrue(
                "the garbage was never quarantined; pulled=${spool.pulled.count { it == id }}, " +
                    "alice=${alice.dmScopeStatus(bob)?.invalidCount} bob=${bob.dmScopeStatus(alice)?.invalidCount}",
                lab.tryAwait(2, timeoutMs = MeshLab.SPOOL_AWAIT_MS) {
                    (alice.metrics.spool().spoolInvalid + bob.metrics.spool().spoolInvalid).toInt()
                },
            )
            assertEquals("asked for exactly once per member", 2, spool.pulled.count { it == id })

            assertTrue(alice.sendDm(bob, "still fine"))
            lab.assertConverged(listOf(alice, bob), atLeast = 3, timeoutMs = MeshLab.SPOOL_AWAIT_MS) { alice.dmThreadWith(bob)(it) }
            assertEquals("still asked for exactly once", 2, spool.pulled.count { it == id })
        }
}
