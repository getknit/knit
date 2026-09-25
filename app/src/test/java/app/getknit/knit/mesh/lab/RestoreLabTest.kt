package app.getknit.knit.mesh.lab

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A phone that comes back from a backup (`data/backup`, ADR: backup and restore). [LabNode.restoreFromBackup]
 * is the state the restore leaves — identity, contacts and history intact, custody and both ratchets
 * emptied, the restore mark set — and what these cases pin is the mesh's side of it: `MeshManager.finishRestore`
 * runs once, the peers it resets re-seal what the restored node never acked, and the conversation carries
 * on under a new session without anybody typing anything.
 */
@RunWith(RobolectricTestRunner::class)
class RestoreLabTest {
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
     * Alice restores while Bob's two DMs to her sit in his custody, sealed under the session she no longer
     * has. Her reset is what makes him re-seal them; without it they would drop as `RATCHET_NO_SESSION` on
     * every re-serve until his own heuristic fired hours later.
     */
    @Test
    fun aRestoredNodeGetsWhatWasSentWhileItWasAwayAndCarriesOnUnderANewSession() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)
            assertTrue(alice.sendDm(bob, "before"))
            lab.await(1) { bob.decrypted(bob.dmWith(alice)).size }
            assertTrue(bob.sendDm(alice, "before too"))
            lab.assertConverged(listOf(alice, bob), atLeast = 2) { it.dmWith(if (it === alice) bob else alice) }
            val root = alice.session(bob)?.rootHash
            val versionBefore = alice.settings.profileVersion.first()

            alice.restoreFromBackup()
            // The mesh still runs on Bob's side while Alice is down; two more DMs seal under the old session.
            assertTrue(bob.sendDm(alice, "while you were away"))
            assertTrue(bob.sendDm(alice, "and again"))

            lab.link(alice, bob)
            lab.assertConverged(listOf(alice, bob), atLeast = 4) { it.dmWith(if (it === alice) bob else alice) }
            assertNotEquals("a new session, not the old one", root, alice.session(bob)?.rootHash)
            assertTrue("the restore's profile bump", alice.settings.profileVersion.first() > versionBefore)
            assertEquals("the hooks ran once", false, alice.settings.restorePending.first())

            assertTrue(alice.sendDm(bob, "after"))
            assertTrue(bob.sendDm(alice, "after too"))
            lab.assertConverged(listOf(alice, bob), atLeast = 6) { it.dmWith(if (it === alice) bob else alice) }
        }

    /** The group's sender chain re-mints from one on the restored side; the members open it like any other epoch. */
    @Test
    fun aRestoredNodeReMintsItsGroupChainAndTheMembersFollow() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            val carol = lab.node("carol").apply { setDisplayName("Carol") }
            lab.linkAll(alice to bob, bob to carol, alice to carol)
            lab.awaitAcquainted(alice, bob, carol)
            val group = alice.createGroup(bob, carol)
            assertTrue(alice.sendGroup(group, "first"))
            lab.assertConverged(listOf(alice, bob, carol), atLeast = 1) { group }

            alice.restoreFromBackup()
            lab.linkAll(alice to bob, alice to carol)
            assertTrue(alice.sendGroup(group, "after the restore"))
            assertTrue(bob.sendGroup(group, "still here"))
            lab.assertConverged(listOf(alice, bob, carol), atLeast = 3) { group }
        }
}
