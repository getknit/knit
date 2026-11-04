package app.getknit.knit.mesh.lab

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.random.Random

/**
 * The notification's Pause and Resume (ADR 2026-09.wz99): `MeshService` stops the manager and later starts the same one
 * again, in a process that keeps everything it held in memory. A [LabNode.restart] is process death and builds a new
 * stack, so it never sees what one process carries from one mesh start to the next. [LabNode.pauseMesh] and
 * [LabNode.resumeMesh] model the pause itself. Every case ends in the oracle.
 */
@RunWith(RobolectricTestRunner::class)
class MeshPauseLabTest {
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
     * A picture waits in Alice's composer across a pause: one picked before it, one picked while paused. No message
     * names either yet, so the resumed start's orphan collection (`BlobRepository.deleteOrphans`) finds both unreferenced.
     * It must leave them alone because this process stored them (#288): the in-memory mark survives a pause, where a
     * restart would start without it. Both then send and land with their bytes.
     */
    @Test
    fun picturesStagedAcrossAPauseSendAfterTheResume() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)
            lab.befriend(alice, bob)

            val before = Random(1).nextBytes(4_096)
            val during = Random(2).nextBytes(4_096)
            val early = alice.pickImage(before)
            alice.pauseMesh()
            val late = alice.pickImage(during)
            alice.resumeMesh()
            // The resume reset the flag before it returned, so this waits for this start's collection, not the boot's.
            lab.await(1) { if (alice.manager.startSweepsDone) 1 else 0 }
            assertTrue("the resume's collection took the picture picked before the pause", alice.holdsBlob(early.hash))
            assertTrue("the resume's collection took the picture picked while paused", alice.holdsBlob(late.hash))

            lab.link(alice, bob)
            assertTrue(alice.sendPicked(early, "picked before the pause", to = bob))
            assertTrue(alice.sendPicked(late, "picked while paused", to = bob))
            val earlyId = alice.ownMessageId(alice.dmWith(bob), "picked before the pause")
            val lateId = alice.ownMessageId(alice.dmWith(bob), "picked while paused")

            lab.assertConverged(listOf(alice, bob), atLeast = 2) { it.dmWith(if (it === alice) bob else alice) }
            assertTrue(before.contentEquals(bob.attachmentPlain(bob.dmWith(alice), earlyId)))
            assertTrue(during.contentEquals(bob.attachmentPlain(bob.dmWith(alice), lateId)))
        }

    /**
     * Bob writes to Alice while her mesh is paused. His DM waits in his custody, and the re-link's digest exchange hands
     * it over once she resumes. Her answer then goes out on the resumed session. Nothing the manager kept in memory about
     * Bob from before the pause may stop either.
     */
    @Test
    fun aDmSentDuringAPauseLandsAfterTheResume() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)
            assertTrue(alice.sendDm(bob, "hi bob"))
            lab.assertConverged(listOf(alice, bob), atLeast = 1) { it.dmWith(if (it === alice) bob else alice) }

            alice.pauseMesh()
            assertTrue(bob.sendDm(alice, "while you were paused"))
            alice.resumeMesh()
            lab.link(alice, bob)
            lab.assertConverged(listOf(alice, bob), atLeast = 2) { it.dmWith(if (it === alice) bob else alice) }

            assertTrue(alice.sendDm(bob, "back now"))
            lab.assertConverged(listOf(alice, bob), atLeast = 3) { it.dmWith(if (it === alice) bob else alice) }
        }
}
