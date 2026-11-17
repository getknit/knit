package app.getknit.knit.mesh.lab

import app.getknit.knit.mesh.lora.BoardState
import app.getknit.knit.mesh.lora.meshcore.FakeMeshcoreAir
import app.getknit.knit.mesh.lora.meshcore.FakeNusChannel
import app.getknit.knit.mesh.lora.meshcore.MeshcoreForgetResult
import app.getknit.knit.mesh.lora.meshcore.MeshcoreNames
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Forgetting a MeshCore board, end to end (ADR 2026-11.gsqu's Stage 7 amendment): two MeshCore nodes that are each other's
 * contacts, and alice forgets her board through the radio screen's one path (`MeshcoreForget`, wired as `di/LoraWiring`
 * wires it) — with the board in range, where its Restore runs and the binding goes, and out of range, where the binding is
 * kept for the cleanup and the claim goes now. Either way bob's view of alice's sealed profile drops `meshcoreKey` and his
 * plane stops offering compact DMs to her board; a profile never rides the board, so the withdrawal reaches him over a
 * link, and every case ends converged over it.
 */
@RunWith(RobolectricTestRunner::class)
class MeshcoreForgetLabTest {
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

    /** The DM ✓✓ batches a second; a board back in range is redialled in half a second, not the field's five. */
    private val quick = LabLimits(dmAckHoldMs = 1_000L, loraLingerSweepMs = 200L, meshcoreBackoffMs = 500L)

    /** Alice and bob on [air], each in the other's range, met as contacts and parted: only the air is left. */
    private suspend fun pair(air: FakeMeshcoreAir): Pair<LabNode, LabNode> {
        air.meshcoreEdit { link("alice", "bob") }
        val alice = lab.node("alice", quick, meshcore = air).apply { setDisplayName("Alice") }
        val bob = lab.node("bob", quick, meshcore = air).apply { setDisplayName("Bob") }
        lab.meetOverMeshcore(alice, bob)
        assertNotNull("bob holds alice's board key before the Forget", bob.peer(alice)?.meshcoreKey)
        assertEquals(true, bob.meshcore?.boardHolderOf(alice.nodeId)?.direct)
        return alice to bob
    }

    /** Bob's view of alice's sealed profile names no board, and his plane offers her nothing direct. */
    private suspend fun awaitWithdrawn(
        bob: LabNode,
        alice: LabNode,
    ) {
        val ok =
            lab.tryAwait(1) {
                val keyless = bob.peer(alice)?.meshcoreKey == null
                val closed = bob.meshcore?.boardHolderOf(alice.nodeId)?.direct != true
                if (keyless && closed) 1 else 0
            }
        assertTrue(
            "bob still holds alice's board: key=${bob.peer(alice)?.meshcoreKey != null}, " +
                "direct=${bob.meshcore?.boardHolderOf(alice.nodeId)?.direct}\n${lab.report(listOf(alice, bob))}",
            ok,
        )
    }

    /** In range: the board's Restore runs, the binding and the claim go in one edit, the plane switches off. */
    @Test
    fun aForgetWithTheBoardInRangeRestoresItAndBobDropsTheKey() =
        runBlocking {
            val air = lab.meshcoreAir()
            val (alice, bob) = pair(air)
            val board = checkNotNull(alice.meshcoreBoard)

            assertEquals(MeshcoreForgetResult.CLEARED, alice.forgetMeshcore())
            assertNull("the binding is gone", alice.settings.meshcoreDeviceAddress.first())
            assertFalse("the plane is off", alice.settings.loraEnabled.first())
            assertNull("no claim is published", alice.meshcoreClaims.boardKey.first())
            val name = air.snapshot { board.advertName.decodeToString() }
            assertFalse("the board is no longer named for Knit", name == MeshcoreNames.forKey(board.key))

            lab.link(alice, bob)
            awaitWithdrawn(bob, alice)
            assertNull("the claim never came back", alice.meshcoreClaims.boardKey.first())
            assertTrue(alice.sendDm(bob, "after the forget"))
            lab.assertConverged(listOf(alice, bob), atLeast = 3) { it.dmWith(if (it === alice) bob else alice) }
        }

    /**
     * Out of range: the binding is kept so the next connection cleans the board, and the claim goes now, so bob drops the key
     * while the board is away; back in range the cleanup runs, no claim returns, and a second Forget finds nothing to clean.
     */
    @Test
    fun aForgetWithTheBoardAwayKeepsTheBindingAndWithdrawsTheClaim() =
        runBlocking {
            val air = lab.meshcoreAir()
            val (alice, bob) = pair(air)
            val board = checkNotNull(alice.meshcoreBoard)
            val session = checkNotNull(alice.meshcoreSession)
            air.meshcoreEdit {
                alice.meshcoreOutOfRange(true)
                // The link is lost under the session: it reads the drop at once, and nothing more can be written.
                board.ifaces.filterIsInstance<FakeNusChannel>().forEach {
                    it.disconnect()
                    it.close()
                }
            }
            lab.await(1) { if (session.state.value is BoardState.Ready) 0 else 1 }

            assertEquals(MeshcoreForgetResult.PENDING, alice.forgetMeshcore())
            assertEquals("the binding is kept for the cleanup", alice.meshcoreAddress, alice.settings.meshcoreDeviceAddress.first())
            assertNull("the claim is withdrawn now", alice.meshcoreClaims.boardKey.first())

            lab.link(alice, bob)
            awaitWithdrawn(bob, alice)

            air.meshcoreEdit { alice.meshcoreOutOfRange(false) }
            lab.await(1) { alice.loraLog.count { it.contains("lora cleanup kind=meshcore done=true") } }
            // The cleanup's consent applied whole — its claim edit landed or owed — before the claim is read: a regression that
            // re-granted would publish the key by then.
            lab.await(1) {
                val latest = session.consent.value?.seq ?: 0
                if (alice.meshcoreHooks.counters.consentApplied
                        .get() >= latest
                ) {
                    1
                } else {
                    0
                }
            }
            assertNull("the cleanup grants nothing", alice.meshcoreClaims.boardKey.first())
            assertEquals("nothing left to clean", MeshcoreForgetResult.CLEARED, alice.forgetMeshcore())
            assertNull(alice.settings.meshcoreDeviceAddress.first())
            assertNull(bob.peer(alice)?.meshcoreKey)
            assertTrue(alice.sendDm(bob, "after the forget"))
            lab.assertConverged(listOf(alice, bob), atLeast = 3) { it.dmWith(if (it === alice) bob else alice) }
        }
}
