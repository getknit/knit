package app.getknit.knit.mesh.lab

import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.mesh.ProfileRepublishCause
import app.getknit.knit.mesh.SeenSet
import app.getknit.knit.mesh.protocol.FrameId
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.ui.theme.ThemeMode
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
            // The row is not the notice: the cleartext writer commits the row, then writes the notice after its
            // transaction (`applyPresentationFollowUps`), and the oracle has nothing left to wait on in between —
            // the sealed copy, if it lands then, finds the new name already stored and writes none.
            val renamed = lab.tryAwait(1) { if (MessageEntity.KIND_PEER_RENAMED in bob.notices(bob.dmWith(alice))) 1 else 0 }
            assertTrue("bob's thread never showed the rename: ${bob.notices(bob.dmWith(alice))}", renamed)
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
            // A photo landing while Bob's start-up collection is pending can lose its bytes before the face takes them, for
            // the next database re-arm to fetch again (#256's window, ADR 2026-11.4ckq): past this oracle's wait, and not
            // this scenario's subject. Both starts' sweeps are done before the photo is set.
            lab.await(2) { listOf(alice, bob).count { it.manager.startSweepsDone } }

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

    /**
     * #335: custody's cap takes from the largest sender's bucket (ADR 2026-10.7mqm), so Alice's posts evict her own
     * profile everywhere: on her store it is the oldest of her bucket and no post is more widely held, and on Bob's and
     * Carol's it came from a neighbour as every post did. A settings write after that is not an edit: her version and
     * stamp stay, and nothing is republished or sealed. Before the fix it minted a version, flooded it and sealed it to
     * Bob, and in a crowd those copies filled every store. A re-link past the routers' seen window re-serves her profile
     * from the frame she kept, the same bytes under the same id; a rename then publishes once.
     *
     * The cap binds on every store, and its choice of row reads each node's carry ledger (ADR 2026-11.bmks): after the
     * re-link Bob's digest lists Alice's posts and not her profile, so her store, taking the kept frame back, trims a post
     * more widely held than it, while Bob's and Carol's trim by their own holders. The stores may then keep different rows
     * for good, so the oracle past the first eviction reads delivery, profiles and every store at or under its cap, not
     * custody parity.
     */
    @Test
    fun anEvictedOwnProfileIsNotRepublishedByASettingsWrite() =
        runBlocking {
            val limits = LabLimits(custodyMaxRows = 20)
            val alice = lab.node("alice", limits).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", limits).apply { setDisplayName("Bob") }
            val carol = lab.node("carol", limits).apply { setDisplayName("Carol") }
            val three = listOf(alice, bob, carol)
            lab.linkAll(alice to bob, bob to carol, alice to carol)
            lab.awaitAcquainted(alice, bob, carol)
            // A session for the sealed fan-out to reach: the copy an eviction used to cost.
            assertTrue(alice.sendDm(bob, "hi bob"))
            lab.assertConverged(listOf(alice, bob), atLeast = 1) { it.dmWith(if (it === alice) bob else alice) }

            val version = alice.settings.profileVersion.first()
            val stamp = alice.settings.profilePublishedAt.first()
            val profileId = FrameId.profileId(alice.nodeId, stamp)
            // Setting the name at the start was an edit, counted as one: what is counted after it is the case's.
            val republished = alice.metrics.seals().profileRepublishedBy

            fun changedSince() =
                (alice.metrics.seals().profileRepublishedBy[ProfileRepublishCause.PRESENTATION_CHANGED] ?: 0L) -
                    (republished[ProfileRepublishCause.PRESENTATION_CHANGED] ?: 0L)

            // Twenty posts into a 20-row store: Alice's bucket is the largest everywhere, and her profile its oldest frame.
            (1..20).forEach { assertTrue(alice.sendRoom("alice $it")) }
            lab.assertConverged(three, atLeast = 20, custody = false) { Conversations.NEARBY }
            three.forEach { assertTrue("${it.name} still holds alice's profile", profileId !in it.custodyIds()) }

            alice.settings.setThemeMode(ThemeMode.Dark) // a write to a key the profile does not carry
            // An early read only: a watcher that judged the write late still fails the version check after the re-link.
            lab.settle() // lab-lint: allow sleep-as-sync — a pause before checking no version, stamp or republish followed the write
            assertEquals("an eviction is not an edit: no new version", version, alice.settings.profileVersion.first())
            assertEquals("and no new stamp", stamp, alice.settings.profilePublishedAt.first())
            assertEquals("and nothing republished", republished, alice.metrics.seals().profileRepublishedBy)

            // Past the seen window, the re-link's push re-serves the kept frame and Bob takes it in again, and still holds
            // Alice at the version she shows; which row each full store trims for it is its own (see the KDoc).
            // The push is held on the way, so what it carries is read: the bytes signed at the stamp, not a re-signing.
            val kept = checkNotNull(alice.settings.ownProfileFrame.first()) { "alice kept no profile frame" }
            lab.unlink(alice, bob)
            lab.clock.advance(SeenSet.DEFAULT_TTL_MS + 60_000L)
            lab.link(alice, bob, arm = { alice.transport.hold(bob.transport) })
            lab.await(1) { alice.transport.held(bob.transport).count { it.isProfileFrom(alice.nodeId) } }
            val pushed = alice.transport.held(bob.transport).first { it.isProfileFrom(alice.nodeId) }
            assertEquals(profileId, WireCodec.decodeEnvelope(pushed.signed)?.id)
            assertTrue("the push re-signed alice's profile", pushed.signed.contentEquals(kept.signed) && pushed.sig.contentEquals(kept.sig))
            alice.transport.release(bob.transport)
            lab.assertConverged(three, atLeast = 20, custody = false) { Conversations.NEARBY }
            assertEquals(version, alice.settings.profileVersion.first())

            alice.setDisplayName("Alice the Second")
            lab.assertConverged(three, atLeast = 20, custody = false) { Conversations.NEARBY }
            assertEquals("Alice the Second", bob.peer(alice)?.name)
            assertEquals("Alice the Second", carol.peer(alice)?.name)
            assertEquals("the rename published once", 1L, changedSince())
            assertEquals(null, alice.metrics.seals().profileRepublishedBy[ProfileRepublishCause.NO_CUSTODY_ROW])
        }
}
