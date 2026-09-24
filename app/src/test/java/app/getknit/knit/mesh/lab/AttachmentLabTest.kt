package app.getknit.knit.mesh.lab

import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.DeliveryPlane
import app.getknit.knit.mesh.lora.FakeMeshtasticAir
import app.getknit.knit.mesh.protocol.FrameType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.random.Random

/**
 * Bytes that ride beside a frame: the `blobreq` / file pull (`BlobExchange`, `MeshBlobStore`), the sealed
 * attachment whose frame names only the ciphertext hash (ADR 035), the carrier that eager-pulls what it
 * custodies so an absent recipient can fetch it later, and screening on the receiver (knit-next#30). The
 * oracle's attachment check reads the bytes back on every node and compares the plaintext.
 */
@RunWith(RobolectricTestRunner::class)
class AttachmentLabTest {
    private lateinit var lab: MeshLab

    @Before
    fun setUp() {
        lab = MeshLab()
    }

    @After
    fun tearDown() {
        lab.close()
    }

    @Test
    fun anImageDmArrivesWithItsBytesAndAScreenedVerdict() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)

            val picture = Random(1).nextBytes(4_096)
            assertTrue(alice.sendImage(picture, "a picture", to = bob))
            val id = alice.ownMessageId(alice.dmWith(bob), "a picture")

            lab.assertConverged(listOf(alice, bob), atLeast = 1) { it.dmWith(if (it === alice) bob else alice) }
            assertTrue(picture.contentEquals(bob.attachmentPlain(bob.dmWith(alice), id)))
            assertTrue("bob screened what he received", bob.attachmentScreened(bob.dmWith(alice), id))
            assertEquals(
                "the frame names the ciphertext, never the picture",
                false,
                alice.attachmentHash(alice.dmWith(bob), id) ==
                    app.getknit.knit.mesh
                        .sha256Hex(picture),
            )
        }

    /**
     * Carol is away when Alice sends her a picture; Bob custodies the frame and pulls its bytes at once so
     * that, with Alice gone too, Carol can fetch both from him alone.
     */
    @Test
    fun anImageForSomeoneAwayIsCarriedWithItsBytesAndPulledFromTheCarrier() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            val carol = lab.node("carol").apply { setDisplayName("Carol") }
            lab.linkAll(alice to bob, bob to carol)
            lab.awaitAcquainted(alice, bob, carol)

            lab.unlink(bob, carol)
            val picture = Random(2).nextBytes(4_096)
            assertTrue(alice.sendImage(picture, "for carol", to = carol))
            val id = alice.ownMessageId(alice.dmWith(carol), "for carol")
            val hash = checkNotNull(alice.attachmentHash(alice.dmWith(carol), id))
            assertTrue("bob never pulled the bytes he carries the frame for", lab.tryAwait(1) { if (bob.blobs.exists(hash)) 1 else 0 })
            lab.unlink(alice, bob)

            lab.link(bob, carol)
            lab.assertConverged(listOf(carol), atLeast = 1, carriers = listOf(bob)) { it.dmWith(alice) }
            assertTrue(picture.contentEquals(carol.attachmentPlain(carol.dmWith(alice), id)))

            lab.link(alice, bob)
            lab.assertConverged(listOf(alice, carol), atLeast = 1, carriers = listOf(bob)) { it.dmWith(if (it === alice) carol else alice) }
        }

    /** The room is the one cleartext surface: every receiver pulls the picture and screens it itself. */
    @Test
    fun aRoomImageIsScreenedOnEveryReceiver() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            val carol = lab.node("carol").apply { setDisplayName("Carol") }
            lab.linkAll(alice to bob, bob to carol, alice to carol)
            lab.awaitAcquainted(alice, bob, carol)

            val picture = Random(3).nextBytes(4_096)
            assertTrue(alice.sendImage(picture, "room picture"))
            val id = alice.ownMessageId(Conversations.NEARBY, "room picture")

            lab.assertConverged(listOf(alice, bob, carol), atLeast = 1) { Conversations.NEARBY }
            listOf(bob, carol).forEach { n ->
                assertTrue("${n.name} screened the room picture", n.attachmentScreened(Conversations.NEARBY, id))
                assertTrue(picture.contentEquals(n.attachmentPlain(Conversations.NEARBY, id)))
            }
        }

    @Test
    fun aGroupPhotoCrossesAsABlob() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            val carol = lab.node("carol").apply { setDisplayName("Carol") }
            lab.linkAll(alice to bob, bob to carol, alice to carol)
            lab.awaitAcquainted(alice, bob, carol)
            val groupId = alice.createGroup(bob, carol)
            assertTrue(alice.sendGroup(groupId, "founded"))
            lab.assertConverged(listOf(alice, bob, carol), atLeast = 1) { groupId }

            val photo = Random(4).nextBytes(4_096)
            alice.setGroupPhoto(groupId, photo)

            lab.assertConverged(listOf(alice, bob, carol), atLeast = 1) { groupId }
            val hash = checkNotNull(bob.group(groupId)?.photoHash)
            listOf(bob, carol).forEach { n ->
                assertTrue("${n.name} never pulled the group photo", lab.tryAwait(1) { if (n.blobs.exists(hash)) 1 else 0 })
                assertTrue(photo.contentEquals(n.blobs.bytes(hash)))
            }
        }

    /**
     * The 2026-09-15 field trial (ADR 2026-09.ptv8): a picture posted in the room reaches a phone that only
     * the board can hear, so its row arrives with a hash and no bytes, and the want it parks has nobody to
     * go to. The phone stays alone past `BlobExchange`'s 30-min fetch TTL — a real walk — and the sweep the
     * prune loop and the heartbeat run reclaims the want. When a neighbour finally links, the re-ask must
     * still name the picture: the database, not the in-memory memo, is what says it is missing. Before the
     * fix the row spun until the app was restarted; here Bob's re-link is the whole cure.
     */
    @Test
    fun aPictureHeardOverTheBoardWhileAloneIsStillFetchedAfterALongIsolation() =
        runBlocking {
            val air = FakeMeshtasticAir()
            val alice = lab.node("alice", air = air).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", air = air).apply { setDisplayName("Bob") }
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)
            lab.awaitCustodyParity(alice, bob)
            lab.unlink(alice, bob)

            val picture = Random(5).nextBytes(4_096)
            assertTrue(alice.sendImage(picture, "from the trail"))
            val id = alice.ownMessageId(Conversations.NEARBY, "from the trail")
            assertTrue("bob never heard the post", lab.tryAwait(1) { bob.roomPosts()[alice.nodeId].orEmpty().size })
            assertEquals(DeliveryPlane.LoRa, bob.receivedVia(Conversations.NEARBY, id))
            assertFalse("a board carries a frame, never its bytes", bob.attachmentHeld(Conversations.NEARBY, id))

            // Half an hour alone, then the sweep that reclaims a want nobody could be asked for.
            lab.clock.advance(WANT_TTL_LAPSED_MS)
            bob.sweepExpired()
            assertFalse(bob.attachmentHeld(Conversations.NEARBY, id))

            lab.link(alice, bob)
            assertTrue(
                "the picture was never fetched once a holder linked",
                lab.tryAwait(1) { if (bob.attachmentHeld(Conversations.NEARBY, id)) 1 else 0 },
            )
            assertTrue(picture.contentEquals(bob.attachmentPlain(Conversations.NEARBY, id)))
            lab.assertConverged(listOf(alice, bob), atLeast = 1) { Conversations.NEARBY }
        }

    /**
     * Work item #79 (ADR 2026-09.4tx5): three phones linked over BLE, a 700 KB picture Alice sends Bob, and
     * the Moto G's slow controller — Bob's copy takes minutes to land. In that window the 60 s re-offer
     * re-asked every neighbour for it, Alice served it again, and Carol — who had Bob's first ask on file as
     * a *wanter* and pulled the bytes for it — pushed him a third copy the moment hers landed; Bob then pushed
     * Carol one she held. Here Alice's link to Bob is the slow one (her serve is parked with its header
     * across), and Alice's frame to Carol is parked too, so Bob's ask is the first Carol hears of the picture
     * and she pulls her copy *for* it: the wanter shape, pinned rather than raced. Left to the race, Carol's
     * own pull can land before Bob's ask reaches her, and her serve of that fresh ask — correct under the ADR —
     * reads as the push (CI job 4939, one slow core). The re-link Bob ↔ Carol is the tick, and the file
     * recorder on every transport is the phone's `file …` line: exactly one copy per (hash, link), asked for once.
     */
    @Test
    fun aPictureAlreadyStreamingInIsNeitherAskedForAgainNorServedTwice() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            val carol = lab.node("carol").apply { setDisplayName("Carol") }
            lab.linkAll(alice to bob, bob to carol, alice to carol)
            lab.awaitAcquainted(alice, bob, carol)

            alice.transport.holdFiles(bob.transport) // Bob's link is the Moto's: the header lands, the bytes take their time
            alice.transport.hold(carol.transport) // Carol hears of the picture from Bob, never from Alice's frame
            val picture = Random(7).nextBytes(4_096)
            assertTrue(alice.sendImage(picture, "for bob", to = bob))
            val id = alice.ownMessageId(alice.dmWith(bob), "for bob")
            val hash = checkNotNull(alice.attachmentHash(alice.dmWith(bob), id))
            val fileToBob = "${bob.nodeId} ATTACHMENT $hash"

            // Alice's serve to Bob is on the link. Bob's ask is emitted before his relay is even scheduled, so it
            // is the first frame at Carol to name the hash: she processes it holding nothing, asks for the picture
            // herself, and pulls her copy whole and at once — Bob's ask on file behind it.
            lab.await(1) { alice.transport.heldFiles(bob.transport).count { it == hash } }
            lab.await(1) { if (carol.blobs.exists(hash)) 1 else 0 }
            assertTrue("the header across is what Bob reads as arriving", hash in bob.transport.arrivingFiles())
            assertTrue("Carol holds the bytes and had Bob's ask — and pushes nothing", carol.transport.files.none { it == fileToBob })
            // Carol's own ask reached Bob too, and Bob handles frames one at a time: it can sit behind the tail of
            // his work on Alice's frame. Answered only after his bytes land, it is a fresh ask he rightly serves —
            // which reads as the push this scenario pins (CI job 5268). Answered now, it finds the bytes arriving.
            lab.await(1) { if (bob.metrics.snapshot().blobAsksHandled > 0) 1 else 0 }
            alice.transport.release(carol.transport) // Alice's copy of the frame lands on Carol, behind Bob's relay of it

            // The 60 s re-offer, for the link that is not busy: Bob re-arms from the database and re-asks each
            // neighbour for what he still lacks — unless its bytes are already on the way.
            val digests = bob.transport.digestsSent.count { it == carol.nodeId }
            lab.unlink(bob, carol)
            lab.link(bob, carol)
            lab.await(digests + 1) { bob.transport.digestsSent.count { it == carol.nodeId } } // the batch's last hook
            val asks = bob.transport.sent.filter { it.contains(" ${FrameType.BLOB_REQ} ") }
            assertEquals("one ask per neighbour, and none while the bytes stream in:\n$asks", 2, asks.size)
            assertEquals(1, asks.count { it.contains(" ${alice.nodeId.take(6)} ") })
            assertEquals(1, asks.count { it.contains(" ${carol.nodeId.take(6)} ") })
            assertEquals("Alice served Bob once", 1, alice.transport.files.count { it == fileToBob })

            alice.transport.releaseFiles(bob.transport) // the bytes land
            lab.assertConverged(listOf(alice, bob), atLeast = 1, carriers = listOf(carol)) { it.dmWith(if (it === alice) bob else alice) }
            assertTrue(picture.contentEquals(bob.attachmentPlain(bob.dmWith(alice), id)))
            assertEquals("Alice served Bob once", 1, alice.transport.files.count { it == fileToBob })
            assertTrue("Carol never pushed Bob a copy", carol.transport.files.none { it == fileToBob })
            val bobToCarol = bob.transport.files.filter { it.startsWith(carol.nodeId) }
            assertTrue("Bob never pushed Carol the copy she holds: $bobToCarol", bobToCarol.isEmpty())
        }

    private companion object {
        /** Past `BlobExchange.FETCH_TTL_MS` (30 min), well inside the 48 h a lab clock jump may span. */
        const val WANT_TTL_LAPSED_MS = 31 * 60_000L
    }
}
