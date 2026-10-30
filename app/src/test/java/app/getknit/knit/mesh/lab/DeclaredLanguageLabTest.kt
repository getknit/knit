package app.getknit.knit.mesh.lab

import app.getknit.knit.data.message.Conversations
import app.getknit.knit.mesh.protocol.DeclaredLangs
import app.getknit.knit.mesh.protocol.EncEnvelope
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
 * What a sender declares about a message's language rides with it in every form a message is sent in (ADR
 * 2026-10.t2h6's 2026-10-29 amendment): the room's cleartext, a v3 DM's compact plaintext, a v2 DM's named one, the
 * re-seal a reset asks for, a sender-key group frame and a v1 group fallback. Each receiver's row carries the
 * declaration as codes, an English message by an English reader carries none, and every scenario ends in the oracle.
 */
@RunWith(RobolectricTestRunner::class)
class DeclaredLanguageLabTest {
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
    fun aRoomPostsDeclarationReachesEveryReaderAndAnEnglishOneCarriesNone() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            val carol = lab.node("carol").apply { setDisplayName("Carol") }
            // Every reader a neighbour of the author: a room tick finds its way home only over a hop to her (ADR 2026-09.y5f3).
            lab.linkAll(alice to bob, alice to carol, bob to carol)
            lab.awaitAcquainted(alice, bob, carol)

            assertTrue(alice.sendRoom(SPANISH, declared = DeclaredLangs.of("es", "es")))
            assertTrue(alice.sendRoom(ENGLISH, declared = DeclaredLangs.of("en", "en")))
            val spanish = alice.ownMessageId(Conversations.NEARBY, SPANISH)
            val english = alice.ownMessageId(Conversations.NEARBY, ENGLISH)
            val readers = listOf(alice, bob, carol)
            lab.assertConverged(readers, atLeast = 2) { Conversations.NEARBY }

            // The author's own rows hold what was declared, as every reader's do.
            for (reader in readers) {
                assertEquals(reader.name, "es", reader.row(spanish)?.bodyLang)
                assertNull(reader.name, reader.row(spanish)?.authorReads)
                assertNull(reader.name, reader.row(english)?.bodyLang)
                assertNull(reader.name, reader.row(english)?.authorReads)
            }
        }

    /** An English DM from someone who reads Spanish, a translated draft: only what they read is declared. */
    @Test
    fun aV3DmsDeclarationLands() =
        runBlocking {
            val (alice, bob) = pair()

            assertTrue(alice.sendDm(bob, ENGLISH, declared = DeclaredLangs.of("en", "es")))
            val id = alice.ownMessageId(alice.dmWith(bob), ENGLISH)
            lab.assertConverged(listOf(alice, bob), atLeast = 1) { it.dmWith(if (it === alice) bob else alice) }

            assertEquals(EncEnvelope.VERSION_DM_V3, alice.custodiedSeal(id)?.v)
            for (node in listOf(alice, bob)) {
                assertNull(node.name, node.row(id)?.bodyLang)
                assertEquals(node.name, "es", node.row(id)?.authorReads)
            }
        }

    /** Toward a build without crypto scheme v3 the same content seals in the named layout, `"l"` and `"r"` beside the body. */
    @Test
    fun aV2DmsDeclarationLands() =
        runBlocking {
            val (alice, bob) = pair()

            alice.sealToAsOlderBuild(bob)
            assertTrue(alice.sendDm(bob, SPANISH, declared = DeclaredLangs.of("es", "en")))
            val id = alice.ownMessageId(alice.dmWith(bob), SPANISH)
            alice.sealToAsOlderBuild(bob, v3 = true)
            lab.assertConverged(listOf(alice, bob), atLeast = 1) { it.dmWith(if (it === alice) bob else alice) }

            assertEquals("the named layout", EncEnvelope.VERSION_RATCHET, alice.custodiedSeal(id)?.v)
            for (node in listOf(alice, bob)) {
                assertEquals(node.name, "es", node.row(id)?.bodyLang)
                assertEquals(node.name, "en", node.row(id)?.authorReads)
            }
        }

    /**
     * Bob's DM is sealed under the session Alice's restore wiped, so the only copy she can open is the one he re-seals
     * on her reset, under the new root: its declaration is the one read back off his row.
     */
    @Test
    fun aDmReSealedOnAResetKeepsItsDeclaration() =
        runBlocking {
            val (alice, bob) = pair()
            assertTrue(alice.sendDm(bob, "before"))
            lab.assertConverged(listOf(alice, bob), atLeast = 1) { it.dmWith(if (it === alice) bob else alice) }
            val root = alice.session(bob)?.rootHash

            alice.restoreFromBackup()
            assertTrue(bob.sendDm(alice, SPANISH, declared = DeclaredLangs.of("es", "es")))
            val id = bob.ownMessageId(bob.dmWith(alice), SPANISH)

            lab.link(alice, bob)
            lab.assertConverged(listOf(alice, bob), atLeast = 2) { it.dmWith(if (it === alice) bob else alice) }
            assertTrue("a new session, not the old one", root != alice.session(bob)?.rootHash)
            assertEquals("the hooks ran once", false, alice.settings.restorePending.first())
            assertEquals("es", alice.row(id)?.bodyLang)
        }

    /** A sender-key group frame, then a v1 fallback while one member reads as not ratchet-eligible: both declare. */
    @Test
    fun aGroupsDeclarationLandsInTheSenderKeyFormAndTheV1Fallback() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            val carol = lab.node("carol").apply { setDisplayName("Carol") }
            lab.linkAll(alice to bob, bob to carol, alice to carol)
            lab.awaitAcquainted(alice, bob, carol)
            val groupId = alice.createGroup(bob, carol)
            val members = listOf(alice, bob, carol)

            assertTrue(alice.sendGroup(groupId, SPANISH, declared = DeclaredLangs.of("es", "es")))
            val ratchet = alice.ownMessageId(groupId, SPANISH)
            lab.assertConverged(members, atLeast = 1) { groupId }
            assertNotNull("the sender-key form", alice.custodiedSeal(ratchet)?.g)

            // `groupRatchetEligible` reads Carol's pinned bit: without it the whole group falls back to v1.
            alice.answerWithCleartextReceipts(carol)
            val fallbackText = "nos vemos en la estación"
            assertTrue(alice.sendGroup(groupId, fallbackText, declared = DeclaredLangs.of("es", "es")))
            val fallback = alice.ownMessageId(groupId, fallbackText)
            alice.answerWithCleartextReceipts(carol, cleartext = false)
            lab.assertConverged(members, atLeast = 2) { groupId }
            val v1 = checkNotNull(alice.custodiedSeal(fallback))
            assertEquals("the v1 wrap", 1, v1.v)
            assertNull(v1.g)

            for (reader in members) {
                assertEquals("${reader.name}, sender-key", "es", reader.row(ratchet)?.bodyLang)
                assertEquals("${reader.name}, v1", "es", reader.row(fallback)?.bodyLang)
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
        const val SPANISH = "¿vienes al parque más tarde?"
        const val ENGLISH = "are you coming to the park later?"
    }
}
