package app.getknit.knit.mesh.lab

import app.getknit.knit.data.message.Conversations
import app.getknit.knit.mesh.sha256Hex
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
import kotlin.random.Random

/**
 * One plaintext, one identity (ADR 2026-10.mptm): a picture passed along goes out under the identity it arrived
 * under, so whoever already holds it holds it under that name and pulls nothing — and a room picture a phone holds
 * sealed is copied out of its own store. The forwards go through the real picker door ([LabNode.forwardImage]),
 * and the oracle's attachment check reads the bytes back on every node.
 */
@RunWith(RobolectricTestRunner::class)
class AttachmentReuseLabTest {
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

    private suspend fun trio(): Triple<LabNode, LabNode, LabNode> {
        val alice = lab.node("alice").apply { setDisplayName("Alice") }
        val bob = lab.node("bob").apply { setDisplayName("Bob") }
        val carol = lab.node("carol").apply { setDisplayName("Carol") }
        lab.linkAll(alice to bob, bob to carol, alice to carol)
        lab.awaitAcquainted(alice, bob, carol)
        return Triple(alice, bob, carol)
    }

    private fun LabNode.asksFor(hash: String) = transport.blobAsks.count { it.hash == hash }

    /** Bob passes a group's picture on to Carol, who has it from the group: the DM names the group's copy. */
    @Test
    fun aGroupPicturePassedOnInADmGoesOutAsTheGroupsCopy() =
        runBlocking {
            val (alice, bob, carol) = trio()
            lab.befriend(alice, bob, carol)
            val groupId = alice.createGroup(bob, carol)
            val picture = Random(11).nextBytes(4_096)
            assertTrue(alice.sendImage(picture, "for the group", groupId = groupId))
            lab.assertConverged(listOf(alice, bob, carol), atLeast = 1) { groupId }
            val groupHash = checkNotNull(alice.attachmentHash(groupId, alice.ownMessageId(groupId, "for the group")))
            // Bob's row learns the plaintext's name once the bytes are opened, after they are held (onObtained).
            lab.await(1) { if (bob.holdsIdentityFor(picture)) 1 else 0 }
            val carolsAsks = carol.asksFor(groupHash)

            assertTrue(bob.forwardImage(picture, "passing it on", to = carol))
            val id = bob.ownMessageId(bob.dmWith(carol), "passing it on")
            lab.assertConverged(listOf(bob, carol), atLeast = 1, carriers = listOf(alice)) {
                it.dmWith(if (it === bob) carol else bob)
            }

            assertEquals("the forward is the group's copy, not a fresh seal", groupHash, bob.attachmentHash(bob.dmWith(carol), id))
            assertEquals(groupHash, carol.attachmentHash(carol.dmWith(bob), id))
            assertNotNull("still sealed, under the group copy's key", carol.attachmentKey(carol.dmWith(bob), id))
            assertTrue(picture.contentEquals(carol.attachmentPlain(carol.dmWith(bob), id)))
            assertEquals("Carol held it already, so she asked nobody for it again", carolsAsks, carol.asksFor(groupHash))
        }

    /** Bob DMs Carol a room picture she already has: it goes out under the room's own key-less name. */
    @Test
    fun aRoomPictureForwardedToSomeoneWhoHasItIsNotPulledAgain() =
        runBlocking {
            val (alice, bob, carol) = trio()
            val picture = Random(12).nextBytes(4_096)
            assertTrue(alice.sendImage(picture, "room picture"))
            lab.assertConverged(listOf(alice, bob, carol), atLeast = 1) { Conversations.NEARBY }
            val roomHash = sha256Hex(picture)
            lab.await(1) { if (bob.holdsIdentityFor(picture)) 1 else 0 }
            val carolsAsks = carol.asksFor(roomHash)

            assertTrue(bob.forwardImage(picture, "did you see this", to = carol))
            val id = bob.ownMessageId(bob.dmWith(carol), "did you see this")
            lab.assertConverged(listOf(bob, carol), atLeast = 1, carriers = listOf(alice)) {
                it.dmWith(if (it === bob) carol else bob)
            }

            assertEquals("named as the room names it", roomHash, carol.attachmentHash(carol.dmWith(bob), id))
            assertNull("no key: the bytes are the room's plaintext", carol.attachmentKey(carol.dmWith(bob), id))
            assertTrue(picture.contentEquals(carol.attachmentPlain(carol.dmWith(bob), id)))
            assertEquals("Carol held it already, so she asked nobody for it again", carolsAsks, carol.asksFor(roomHash))
        }

    /**
     * Bob posts a group's picture to the room. Alice (who sent it) and Carol (who got it from the group) hold it
     * sealed, so each makes the room's plaintext copy from her own — no node is asked for it or sends it.
     */
    @Test
    fun aGroupPicturePostedToTheRoomIsCopiedLocallyByEveryoneWhoHasItSealed() =
        runBlocking {
            val (alice, bob, carol) = trio()
            lab.befriend(alice, bob, carol)
            val groupId = alice.createGroup(bob, carol)
            val picture = Random(13).nextBytes(4_096)
            assertTrue(alice.sendImage(picture, "for the group", groupId = groupId))
            lab.assertConverged(listOf(alice, bob, carol), atLeast = 1) { groupId }
            // The room frame must find each holder's plaintext name recorded, or it is pulled the old way.
            lab.await(3) { listOf(alice, bob, carol).count { it.holdsIdentityFor(picture) } }

            assertTrue(bob.forwardImage(picture, "everyone should see this"))
            val id = bob.ownMessageId(Conversations.NEARBY, "everyone should see this")
            lab.assertConverged(listOf(alice, bob, carol), atLeast = 1) { Conversations.NEARBY }

            val roomHash = sha256Hex(picture)
            assertEquals(roomHash, alice.attachmentHash(Conversations.NEARBY, id))
            listOf(alice, carol).forEach { n ->
                assertTrue(picture.contentEquals(n.attachmentPlain(Conversations.NEARBY, id)))
                assertEquals("${n.name} asked nobody for the room copy", 0, n.asksFor(roomHash))
            }
            val served = listOf(alice, bob, carol).flatMap { n -> n.transport.files.filter { it.endsWith(" ATTACHMENT $roomHash") } }
            assertTrue("no node sent the room copy as a file: $served", served.isEmpty())
            // Made locally, it entered through ingest like any arrival: screened before it was held (#109).
            lab.await(2) { listOf(alice, carol).count { it.attachmentScreened(Conversations.NEARBY, id) } }
        }

    /**
     * Dave, who was never in the group, posts the same picture to the room. Carol pulls it as she would any room
     * picture: copying it out of her sealed one would show Dave — or anyone who holds a file and posts it to watch
     * who answers without asking — that she holds it privately.
     */
    @Test
    fun aStrangersPostOfAPictureWeHoldSealedIsPulledNotCopied() =
        runBlocking {
            val (alice, bob, carol) = trio()
            lab.befriend(alice, bob, carol) // Dave stays a stranger: his room post is the point
            val dave = lab.node("dave").apply { setDisplayName("Dave") }
            lab.linkAll(dave to alice, dave to bob, dave to carol)
            lab.awaitAcquainted(alice, bob, carol, dave)
            val groupId = alice.createGroup(bob, carol)
            val picture = Random(14).nextBytes(4_096)
            assertTrue(alice.sendImage(picture, "for the group", groupId = groupId))
            lab.assertConverged(listOf(alice, bob, carol), atLeast = 1, carriers = listOf(dave)) { groupId }
            lab.await(1) { if (carol.holdsIdentityFor(picture)) 1 else 0 }

            assertTrue(dave.sendImage(picture, "found this"))
            lab.assertConverged(listOf(alice, bob, carol, dave), atLeast = 1) { Conversations.NEARBY }

            val roomHash = sha256Hex(picture)
            assertTrue("Carol asked for Dave's picture like anyone would", carol.asksFor(roomHash) > 0)
            assertTrue(
                picture.contentEquals(carol.attachmentPlain(Conversations.NEARBY, dave.ownMessageId(Conversations.NEARBY, "found this"))),
            )
        }
}
