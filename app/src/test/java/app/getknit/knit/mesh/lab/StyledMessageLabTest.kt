package app.getknit.knit.mesh.lab

import app.getknit.knit.data.message.Conversations
import app.getknit.knit.mesh.protocol.BodyStyle
import app.getknit.knit.mesh.protocol.EncEnvelope
import app.getknit.knit.mesh.protocol.StyleSpan
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A message's formatting rides beside its body in every form a message is sent in (ADR 2026-10.p95v): the room's
 * cleartext, a v3 DM's compact plaintext, a v2 DM's named one, a DM parked on a key and sealed when it lands, the
 * re-seal a reset asks for, a sender-key group frame and a v1 group fallback. Each receiver stores the spans as sent,
 * normalised against the body, and every scenario ends in the oracle.
 */
@RunWith(RobolectricTestRunner::class)
class StyledMessageLabTest {
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

    @Test
    fun aRoomPostsStylesReachEveryReader() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            val carol = lab.node("carol").apply { setDisplayName("Carol") }
            val dave = lab.node("dave").apply { setDisplayName("Dave") }
            // Every reader a neighbour of the author: a room tick finds its way home only over a hop to her (ADR 2026-09.y5f3).
            lab.linkAll(alice to bob, alice to carol, alice to dave, bob to carol, carol to dave)
            lab.awaitAcquainted(alice, bob, carol, dave)

            assertTrue(alice.sendRoom(TEXT, styles = SPANS))
            val id = alice.ownMessageId(Conversations.NEARBY, TEXT)
            val readers = listOf(alice, bob, carol, dave)
            lab.assertConverged(readers, atLeast = 1) { Conversations.NEARBY }

            for (reader in readers) assertEquals(reader.name, STORED, reader.row(id)?.styles)
        }

    @Test
    fun aV3DmsStylesLand() =
        runBlocking {
            val (alice, bob) = pair()

            assertTrue(alice.sendDm(bob, TEXT, styles = SPANS))
            val id = alice.ownMessageId(alice.dmWith(bob), TEXT)
            lab.assertConverged(listOf(alice, bob), atLeast = 1) { it.dmWith(if (it === alice) bob else alice) }

            assertEquals(EncEnvelope.VERSION_DM_V3, alice.custodiedSeal(id)?.v)
            assertEquals(STORED, bob.row(id)?.styles)
        }

    /** Toward a build without crypto scheme v3 the same content seals in the named layout, `"s"` beside the body. */
    @Test
    fun aV2DmsStylesLand() =
        runBlocking {
            val (alice, bob) = pair()

            alice.sealToAsOlderBuild(bob)
            assertTrue(alice.sendDm(bob, TEXT, styles = SPANS))
            val id = alice.ownMessageId(alice.dmWith(bob), TEXT)
            alice.sealToAsOlderBuild(bob, v3 = true)
            lab.assertConverged(listOf(alice, bob), atLeast = 1) { it.dmWith(if (it === alice) bob else alice) }

            assertEquals("the named layout", EncEnvelope.VERSION_RATCHET, alice.custodiedSeal(id)?.v)
            assertEquals(STORED, bob.row(id)?.styles)
        }

    /** A DM composed before the key is known parks on `pendingKey`; the seal made when the key lands carries its styles. */
    @Test
    fun aDmParkedOnItsKeyKeepsItsStylesWhenItIsSealed() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val carol = lab.node("carol").apply { setDisplayName("Carol") }
            assertTrue(alice.sendDm(carol, TEXT, styles = SPANS))
            val id = alice.ownMessageId(alice.dmWith(carol), TEXT)
            assertNull("nothing sealed yet", alice.custodiedSeal(id))

            lab.link(alice, carol)
            lab.assertConverged(listOf(alice, carol), atLeast = 1) { it.dmWith(if (it === alice) carol else alice) }

            assertEquals(STORED, carol.row(id)?.styles)
        }

    /**
     * Bob's DM is sealed under the session Alice's restore wiped, so the only copy she can open is the one he re-seals
     * on her reset, under the new root: its styles are the ones read back off his row.
     */
    @Test
    fun aDmReSealedOnAResetKeepsItsStyles() =
        runBlocking {
            val (alice, bob) = pair()
            assertTrue(alice.sendDm(bob, "before"))
            lab.assertConverged(listOf(alice, bob), atLeast = 1) { it.dmWith(if (it === alice) bob else alice) }
            val root = alice.session(bob)?.rootHash

            alice.restoreFromBackup()
            assertTrue(bob.sendDm(alice, TEXT, styles = SPANS))
            val id = bob.ownMessageId(bob.dmWith(alice), TEXT)

            lab.link(alice, bob)
            lab.assertConverged(listOf(alice, bob), atLeast = 2) { it.dmWith(if (it === alice) bob else alice) }
            assertTrue("a new session, not the old one", root != alice.session(bob)?.rootHash)
            assertEquals("the hooks ran once", false, alice.settings.restorePending.first())
            assertEquals(STORED, alice.row(id)?.styles)
        }

    /** A sender-key group frame, then a v1 fallback while one member reads as not ratchet-eligible: both land styled. */
    @Test
    fun aGroupsStylesLandInTheSenderKeyFormAndTheV1Fallback() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            val carol = lab.node("carol").apply { setDisplayName("Carol") }
            lab.linkAll(alice to bob, bob to carol, alice to carol)
            lab.awaitAcquainted(alice, bob, carol)
            val groupId = alice.createGroup(bob, carol)
            val members = listOf(alice, bob, carol)

            assertTrue(alice.sendGroup(groupId, TEXT, styles = SPANS))
            val ratchet = alice.ownMessageId(groupId, TEXT)
            lab.assertConverged(members, atLeast = 1) { groupId }
            assertNotNull("the sender-key form", alice.custodiedSeal(ratchet)?.g)

            // `groupRatchetEligible` reads Carol's pinned bit: without it the whole group falls back to v1.
            alice.answerWithCleartextReceipts(carol)
            // As long as [TEXT], so the same spans normalise to the same stored triples.
            val fallbackText = "make it BOLD"
            assertTrue(alice.sendGroup(groupId, fallbackText, styles = SPANS))
            val fallback = alice.ownMessageId(groupId, fallbackText)
            alice.answerWithCleartextReceipts(carol, cleartext = false)
            lab.assertConverged(members, atLeast = 2) { groupId }
            val v1 = checkNotNull(alice.custodiedSeal(fallback))
            assertEquals("the v1 wrap", 1, v1.v)
            assertNull(v1.g)

            for (reader in listOf(bob, carol)) {
                assertEquals("${reader.name}, sender-key", STORED, reader.row(ratchet)?.styles)
                assertEquals("${reader.name}, v1", STORED, reader.row(fallback)?.styles)
            }
        }

    private suspend fun pair(): Pair<LabNode, LabNode> {
        val alice = lab.node("alice").apply { setDisplayName("Alice") }
        val bob = lab.node("bob").apply { setDisplayName("Bob") }
        lab.link(alice, bob)
        lab.awaitAcquainted(alice, bob)
        return alice to bob
    }

    private companion object {
        /** Bold over "make", italic over "it bold" overlapping it, and a monospace span past the end that is clipped. */
        const val TEXT = "make it bold"
        val SPANS =
            listOf(
                StyleSpan(0, 4, BodyStyle.BOLD),
                StyleSpan(2, 12, BodyStyle.ITALIC),
                StyleSpan(8, 40, BodyStyle.MONOSPACE),
            )
        const val STORED = "[[0,4,0],[2,10,1],[8,4,3]]"
    }
}
