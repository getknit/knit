package app.getknit.knit.mesh.lab

import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.mesh.protocol.WireCodec
import kotlinx.coroutines.flow.first
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
 * A profile edit after first contact. Two writers land on one peer row — the cleartext `profile` reflood
 * that every neighbor hears, and the sealed `CTL_PROFILE` update (ADR 020) that reaches an established
 * contact inside the DM session — and both order on the owner's profile version, never on the carrying
 * frame's clock (`rules/mesh.md`). Every case ends in the oracle, whose profile check compares every node's
 * row for a peer against that peer's own settings.
 */
@RunWith(RobolectricTestRunner::class)
class ProfileUpdateLabTest {
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

    /** Waits for the owner's profile version to move past [previous] — the republish the edit triggers. */
    private suspend fun awaitRepublish(
        node: LabNode,
        previous: Long,
    ) {
        assertTrue(
            "${node.name}'s profile version never moved past $previous",
            lab.tryAwait(1) {
                if (node.settings.profileVersion.first() >
                    previous
                ) {
                    1
                } else {
                    0
                }
            },
        )
    }

    /**
     * Alice and Bob have a session (the sealed path exists); Carol only shares the room with Alice (the
     * cleartext path is all she has). Alice renames: both rows move, and Bob's DM thread carries the rename
     * notice.
     */
    @Test
    fun aRenameReachesAContactAndAStrangerAlike() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            val carol = lab.node("carol").apply { setDisplayName("Carol") }
            lab.linkAll(alice to bob, bob to carol, alice to carol)
            lab.awaitAcquainted(alice, bob, carol)
            assertTrue(alice.sendDm(bob, "hi bob"))
            lab.assertConverged(listOf(alice, bob), atLeast = 1) { it.dmWith(if (it === alice) bob else alice) }
            assertTrue(alice.sendRoom("hello room"))
            lab.assertConverged(listOf(alice, bob, carol), atLeast = 1) { Conversations.NEARBY }

            val before = alice.settings.profileVersion.first()
            alice.setDisplayName("Alice the Second")
            awaitRepublish(alice, before)

            lab.assertConverged(listOf(alice, bob, carol), atLeast = 1) { Conversations.NEARBY }
            assertEquals("Alice the Second", bob.peer(alice)?.name)
            assertEquals("Alice the Second", carol.peer(alice)?.name)
            assertTrue("bob's thread shows the rename", MessageEntity.KIND_PEER_RENAMED in bob.notices(bob.dmWith(alice)))
        }

    /**
     * Waits until [from]'s held pipe to [to] parks [count] of [from]'s profile frames. The version moves
     * *before* the edit's frame reaches the transport (`broadcastProfile` stamps, signs, then floods), so
     * a scenario that releases the pipe on the version alone releases one frame short on a slow core.
     */
    private suspend fun awaitHeldProfiles(
        from: LabNode,
        to: LabNode,
        count: Int,
    ) {
        assertTrue(
            "$count profile frames were never parked on ${from.name}→${to.name}; held " +
                from.transport.held(to.transport).map { WireCodec.decodeEnvelope(it.signed)?.let { e -> "${e.type} ${e.id}" } },
            lab.tryAwait(count) { from.transport.held(to.transport).count { it.isProfileFrom(from.nodeId) } },
        )
    }

    /**
     * Two renames in a row while the link holds, released oldest-last: the stale profile lands after the
     * newer one and must not revert it — `handleProfile` orders on the profile version, not on arrival.
     */
    @Test
    fun aReServedOlderProfileNeverRevertsANewerName() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)

            alice.transport.hold(bob.transport)
            val v0 = alice.settings.profileVersion.first()
            alice.setDisplayName("Alice Two")
            awaitRepublish(alice, v0)
            awaitHeldProfiles(alice, bob, 1)
            val v1 = alice.settings.profileVersion.first()
            alice.setDisplayName("Alice Three")
            awaitRepublish(alice, v1)
            awaitHeldProfiles(alice, bob, 2)
            val released = alice.transport.release(bob.transport) { it.reversed() }
            assertTrue("two profile frames were held, got ${released.size}", released.size >= 2)

            assertTrue(alice.sendRoom("hello"))
            lab.assertConverged(listOf(alice, bob), atLeast = 1) { Conversations.NEARBY }
            assertEquals("Alice Three", bob.peer(alice)?.name)
        }

    /**
     * The status line and the open-to-chat flag ride every profile layout (`docs/WIRE_COMPAT.md`'s
     * `openToChat` precedent); a later sealed update that carried only some of them would revert the rest
     * on an established contact. Set both, then rename, and the contact keeps all three.
     */
    @Test
    fun statusAndOpenToChatSurviveTheNextSealedUpdate() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)
            assertTrue(alice.sendDm(bob, "hi"))
            lab.await(1) { bob.decrypted(bob.dmWith(alice)).size }
            assertTrue(bob.sendDm(alice, "hi back"))
            lab.assertConverged(listOf(alice, bob), atLeast = 2) { it.dmWith(if (it === alice) bob else alice) }

            val v0 = alice.settings.profileVersion.first()
            alice.setStatus("out walking")
            awaitRepublish(alice, v0)
            val v1 = alice.settings.profileVersion.first()
            alice.setOpenToChat(true)
            awaitRepublish(alice, v1)
            val v2 = alice.settings.profileVersion.first()
            alice.setDisplayName("Alice Outdoors")
            awaitRepublish(alice, v2)

            lab.assertConverged(listOf(alice, bob), atLeast = 2) { it.dmWith(if (it === alice) bob else alice) }
            val row = checkNotNull(bob.peer(alice))
            assertEquals("Alice Outdoors", row.name)
            assertEquals("out walking", row.status)
            assertEquals(true, row.openToChat)
        }

    /** A new avatar: the hash rides the profile, the bytes follow as a file, and the contact holds both. */
    @Test
    fun anAvatarCrossesWithTheProfile() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)

            val v0 = alice.settings.profileVersion.first()
            val picture = Random(7).nextBytes(2_048)
            alice.setAvatar(picture)
            awaitRepublish(alice, v0)

            assertTrue(alice.sendRoom("new face"))
            lab.assertConverged(listOf(alice, bob), atLeast = 1) { Conversations.NEARBY }
            val hash = checkNotNull(bob.peer(alice)?.avatarHash)
            // The oracle's profile check is the row (name, version, hash); the bytes are a `blobreq` round
            // trip Bob starts when the row lands, still in flight when the row is first readable.
            assertTrue("bob never pulled the avatar bytes", lab.tryAwait(1) { if (bob.blobs.exists(hash)) 1 else 0 })
            assertTrue(picture.contentEquals(bob.blobs.bytes(hash)))
        }
}
