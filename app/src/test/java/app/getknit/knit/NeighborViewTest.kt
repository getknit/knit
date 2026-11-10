package app.getknit.knit

import app.getknit.knit.mesh.InboundFrame
import app.getknit.knit.mesh.LinkSecurity
import app.getknit.knit.mesh.MeshMetrics
import app.getknit.knit.mesh.PartialBlobs
import app.getknit.knit.mesh.Peer
import app.getknit.knit.mesh.ReceivedDigest
import app.getknit.knit.mesh.ReceivedFile
import app.getknit.knit.mesh.bluetooth.NeighborView
import app.getknit.knit.mesh.crypto.LinkProof
import app.getknit.knit.mesh.crypto.LinkSeal
import app.getknit.knit.mesh.link.FramedLink
import app.getknit.knit.mesh.link.LinkCallbacks
import app.getknit.knit.mesh.link.LinkSocket
import app.getknit.knit.mesh.link.SealedLinkSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * [NeighborView], what the Bluetooth transport's `refreshNeighbors` publishes from one snapshot of its links: the
 * neighbour set and each link's [FramedLink.security] by node id (ADR 2026-11.8mhs), the Diagnostics chip. The
 * transport has no JVM rig, so the test drives a `links` map the way `registerLink` and `teardownLink` do — a put, a put
 * over the same id (the R3 swap), a remove — and reads the view after each. The links are never started.
 */
class NeighborViewTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() {
        scope.cancel()
    }

    private object NoCallbacks : LinkCallbacks {
        override fun onInbound(frame: InboundFrame) = Unit

        override fun onDigest(digest: ReceivedDigest) = Unit

        override fun onFile(file: ReceivedFile) = Unit

        override fun onLinkDown(nodeId: String) = Unit
    }

    private class RawLink : LinkSocket {
        override val input = ByteArrayInputStream(ByteArray(0))
        override val output = ByteArrayOutputStream()

        override fun close() = Unit
    }

    private val keys: LinkSeal.Keys by lazy {
        val d = LinkSeal.Ephemeral.fixed(ByteArray(32) { (0x40 + it).toByte() })
        val r = LinkSeal.Ephemeral.fixed(ByteArray(32) { (0x60 + it).toByte() })
        LinkSeal.derive(LinkProof.Role.RESPONDER, r, d.pub)
    }

    private fun link(
        nodeId: String,
        sealed: Boolean,
        proven: Boolean,
    ): FramedLink =
        FramedLink(
            nodeId = nodeId,
            peer = Peer(nodeId),
            socket = if (sealed) SealedLinkSocket(RawLink(), keys, LinkProof.Role.RESPONDER) else RawLink(),
            scope = scope,
            cacheDir = tmp.root,
            partials = PartialBlobs(File(tmp.root, "partials"), minBytes = 1),
            metrics = MeshMetrics(),
            callbacks = NoCallbacks,
            now = { 0L },
            proven = proven,
        )

    @Test
    fun eachLinkReadsItsOwnSecurity() {
        assertEquals(LinkSecurity.SEALED, link(A, sealed = true, proven = true).security)
        assertEquals(LinkSecurity.PROVEN, link(A, sealed = false, proven = true).security)
        assertEquals(LinkSecurity.LEGACY, link(A, sealed = false, proven = false).security)
    }

    @Test
    fun securityFollowsRegisterReplaceAndTeardown() {
        val links = ConcurrentHashMap<String, FramedLink>()
        assertEquals(emptyMap<String, LinkSecurity>(), NeighborView.of(links).security)

        // Register: an older build's unproven link to A, a proven one to B.
        links[A] = link(A, sealed = false, proven = false)
        links[B] = link(B, sealed = false, proven = true)
        NeighborView.of(links).let {
            assertEquals(setOf(Peer(A), Peer(B)), it.neighbors)
            assertEquals(mapOf(A to LinkSecurity.LEGACY, B to LinkSecurity.PROVEN), it.security)
        }

        // Replace under the same id (R3: A proves itself on a sealed link): the neighbour set stays, A's state moves.
        links[A] = link(A, sealed = true, proven = true)
        NeighborView.of(links).let {
            assertEquals(setOf(Peer(A), Peer(B)), it.neighbors)
            assertEquals(mapOf(A to LinkSecurity.SEALED, B to LinkSecurity.PROVEN), it.security)
        }

        // Teardown: B's entry goes with its link.
        links.remove(B)
        NeighborView.of(links).let {
            assertEquals(setOf(Peer(A)), it.neighbors)
            assertEquals(mapOf(A to LinkSecurity.SEALED), it.security)
        }

        links.remove(A)
        NeighborView.of(links).let {
            assertEquals(emptySet<Peer>(), it.neighbors)
            assertEquals(emptyMap<String, LinkSecurity>(), it.security)
        }
    }

    private companion object {
        const val A = "a1"
        const val B = "b2"
    }
}
