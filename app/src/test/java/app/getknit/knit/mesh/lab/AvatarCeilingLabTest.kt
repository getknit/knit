package app.getknit.knit.mesh.lab

import app.getknit.knit.mesh.BlobExchange
import app.getknit.knit.mesh.link.FileIntake
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import app.getknit.knit.mesh.sha256Hex
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.random.Random

/**
 * A profile photo is fetched under the photo ceiling (#234): Bob learns Alice's face from a profile relayed to him, so he
 * pulls it from whoever holds it among his neighbours. A holder whose stream or size answer passes the ceiling is passed
 * over for the session — one copy on its link at most, never asked again — and only the author's own signal would end
 * the fetch, so one neighbour misstating a size cannot keep an honest photo from him.
 *
 * Alice — Relay — Bob, and three holders linked to Bob alone: one that cannot answer a holder query (the iOS port), one
 * that misstates sizes ([LabTransport.claimsHeldBytes]) and an honest one. Each streams through Bob's real `FileIntake`
 * ([LabTransport.filesThroughIntake]). The holders never hear Alice's profile (their pipes from Bob lose it): a holder
 * that did would itself refuse to keep an over-ceiling photo it was told is her face, and nothing would be left to ask.
 * They sit outside the oracle for the same reason; Relay carries custody.
 */
@RunWith(RobolectricTestRunner::class)
class AvatarCeilingLabTest {
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

    private val querying = LabLimits(blobQuery = true)

    private class Mesh(
        val alice: LabNode,
        val relay: LabNode,
        val bob: LabNode,
        val ios: LabNode,
        val liar: LabNode,
        val honest: LabNode,
    ) {
        val holders get() = listOf(ios, liar, honest)
    }

    /** The six nodes linked and acquainted, the holders holding [photo] and losing Alice's profile from Bob. */
    private suspend fun mesh(
        photo: ByteArray,
        lie: Long,
    ): Mesh {
        val m =
            Mesh(
                alice = lab.node("alice").apply { setDisplayName("Alice") },
                relay = lab.node("relay").apply { setDisplayName("Relay") },
                bob = lab.node("bob", querying).apply { setDisplayName("Bob") },
                ios = lab.node("ios", LabLimits(answersHolderQueries = false)).apply { setDisplayName("Ios") },
                liar = lab.node("liar").apply { setDisplayName("Liar") },
                honest = lab.node("honest").apply { setDisplayName("Honest") },
            )
        lab.linkAll(m.alice to m.relay, m.relay to m.bob, m.bob to m.ios, m.bob to m.liar, m.bob to m.honest)
        lab.awaitAcquainted(m.alice, m.relay, m.bob, m.ios, m.liar, m.honest)
        val hash = sha256Hex(photo)
        for (holder in m.holders) {
            holder.blobs.insert(hash, IMAGE_MIME, photo)
            holder.transport.filesThroughIntake = true
            m.bob.transport.lossy(holder.transport) { isProfileOf(it, m.alice) }
        }
        m.liar.transport.claimsHeldBytes = lie
        return m
    }

    private fun isProfileOf(
        wire: WireEnvelope,
        author: LabNode,
    ): Boolean = WireCodec.decodeEnvelope(wire.signed)?.let { it.type == FrameType.PROFILE && it.senderId == author.nodeId } == true

    /** Copies of [hash] [holder] sent [to]: the phone's `file …` line, one per (hash, link). */
    private fun copies(
        holder: LabNode,
        to: LabNode,
        hash: String,
    ) = holder.transport.files.count { it == "${to.nodeId} ATTACHMENT $hash" }

    private fun LabNode.asksTo(
        holder: LabNode,
        hash: String,
    ) = transport.blobAsks.count { it.to == holder.nodeId && it.hash == hash }

    /** Alice sets [photo] as her face; returns once Bob holds the profile naming it. */
    private suspend fun publishFace(
        m: Mesh,
        photo: ByteArray,
    ) {
        m.alice.setAvatar(photo)
        val version =
            m.alice.settings.profileVersion
                .first()
        lab.await(1) { if ((m.bob.peer(m.alice)?.updatedAt ?: 0L) >= version) 1 else 0 }
    }

    /**
     * One fetch cycle: the calendar moves a round on, and Bob's 60 s re-offer runs. Before and after, every holder has
     * handled what it was sent — an ask served, its stream through Bob's link and the mark it earned — before the next jump: a phone's
     * header lands long before its asker's next cycle, and a jump that outran it would ask the same holder again.
     */
    private suspend fun cycle(m: Mesh) {
        settle(m)
        lab.clock.advance(BlobExchange.CYCLE_MS)
        m.bob.reoffer()
        settle(m)
    }

    private suspend fun settle(m: Mesh) {
        (m.holders + m.relay).forEach { it.transport.awaitInboundDrained() }
        m.bob.transport.awaitInboundDrained()
    }

    /** A DM each way, so the oracle has a thread to read across Alice and Bob, with Relay carrying it. */
    private suspend fun converge(m: Mesh) {
        assertTrue(m.alice.sendDm(m.bob, "hello"))
        lab.await(1) { m.bob.decrypted(m.bob.dmWith(m.alice)).size }
        assertTrue(m.bob.sendDm(m.alice, "hello back"))
        lab.assertConverged(listOf(m.alice, m.bob), atLeast = 2, carriers = listOf(m.relay)) {
            it.dmWith(
                if (it ===
                    m.alice
                ) {
                    m.bob
                } else {
                    m.alice
                },
            )
        }
    }

    @Test
    fun aPhotoOverTheCeilingCostsEachHolderOneCopyAtMostAndIsNeverAFace() =
        runBlocking {
            val photo = Random(234).nextBytes(OVERSIZE)
            val hash = sha256Hex(photo)
            val m = mesh(photo, lie = 3L * OVERSIZE)
            publishFace(m, photo)

            // Every holder is passed over: the two that answer by their size first, before the calendar moves (a jump past
            // the query wait would have Bob ask a holder whose answer is still in flight blind), then the iOS port by the
            // stream Bob's link refused. Its blind ask may wait on Relay, which said it is fetching the photo itself: the
            // cycles move that wait on the calendar, not the scenario's wall time.
            val answering = listOf(m.liar, m.honest)
            lab.await(answering.size) { answering.count { it.nodeId in m.bob.partials.markedHolders(hash) } }
            repeat(CYCLES) { cycle(m) }
            lab.await(m.holders.size) { m.holders.count { it.nodeId in m.bob.partials.markedHolders(hash) } }
            // From here every holder is marked: whatever was asked before, nothing more is.
            settle(m)
            val askedWhenMarked = m.holders.associateWith { m.bob.asksTo(it, hash) }
            cycle(m)
            m.alice.setStatus("a second receipt of the profile")
            val version =
                m.alice.settings.profileVersion
                    .first()
            lab.await(1) { if ((m.bob.peer(m.alice)?.updatedAt ?: 0L) >= version) 1 else 0 }
            cycle(m)

            for (holder in m.holders) {
                assertTrue("${holder.name} sent ${copies(holder, m.bob, hash)} copies", copies(holder, m.bob, hash) <= 1)
            }
            assertEquals("a holder that answered is never asked", 0, m.bob.asksTo(m.liar, hash) + m.bob.asksTo(m.honest, hash))
            for (holder in m.holders) {
                assertEquals("${holder.name} asked again once marked", askedWhenMarked.getValue(holder), m.bob.asksTo(holder, hash))
            }
            assertTrue(
                m.bob.metrics
                    .files()
                    .blobHoldersOverCeiling >= m.holders.size,
            )
            assertNull("never Alice's face on Bob", m.bob.peer(m.alice)?.avatarHash)
            assertFalse("nor held", m.bob.blobs.exists(hash))
            assertNull("nor on Relay, whose push was refused", m.relay.peer(m.alice)?.avatarHash)

            converge(m)
        }

    @Test
    fun aHolderMisstatingASizeDoesNotKeepAnHonestPhotoFromBob() =
        runBlocking {
            val photo = Random(235).nextBytes(UNDERSIZE)
            val hash = sha256Hex(photo)
            val m = mesh(photo, lie = 3L * OVERSIZE)
            publishFace(m, photo)

            lab.await(1) { if (m.bob.peer(m.alice)?.avatarHash == hash) 1 else 0 }
            assertTrue(photo.contentEquals(m.bob.blobs.bytes(hash)))
            assertTrue("the liar is passed over", m.liar.nodeId in m.bob.partials.markedHolders(hash))
            assertEquals("and never asked", 0, m.bob.asksTo(m.liar, hash))
            assertFalse("one neighbour's claim refuses no photo", m.bob.partials.isRefused(hash))
            for (holder in m.holders) {
                assertTrue("${holder.name} sent ${copies(holder, m.bob, hash)} copies", copies(holder, m.bob, hash) <= 1)
            }

            converge(m)
        }

    private companion object {
        const val IMAGE_MIME = "image/jpeg"

        // Half again the photo ceiling, and a photo well under it.
        val OVERSIZE = (FileIntake.MAX_AVATAR_BYTES * 3 / 2).toInt()
        const val UNDERSIZE = 200 * 1024

        // Fetch cycles the oversized photo is held to before the profile comes again.
        const val CYCLES = 3
    }
}
