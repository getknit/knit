package app.getknit.knit

import app.getknit.knit.mesh.BlobExchange
import app.getknit.knit.mesh.BlobStore
import app.getknit.knit.mesh.FakeLoopTransport
import app.getknit.knit.mesh.MeshRouter
import app.getknit.knit.mesh.Peer
import app.getknit.knit.mesh.protocol.BlobReqContent
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.WireCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

@OptIn(ExperimentalCoroutinesApi::class)
class BlobExchangeTest {
    /** In-memory, content-addressed [BlobStore] backed by a temp directory. */
    private class FakeBlobStore(
        private val dir: File,
    ) : BlobStore {
        private val mimes = ConcurrentHashMap<String, String>()

        /**
         * What the real `MeshBlobStore` does on ingest: resolve the type from local state rather than the
         * serving peer's header. Set it to make the stored mime differ from the wire's, which is the only
         * way to see which of the two [BlobExchange.onReceived] hands the next hop.
         */
        var normalizeTo: String? = null

        fun seed(
            hash: String,
            mime: String,
            bytes: ByteArray,
        ) {
            File(dir, hash).writeBytes(bytes)
            mimes[hash] = mime
        }

        /** Bytes held with no row naming their type — a blob whose message row went before it did. */
        fun seedWithoutMime(
            hash: String,
            bytes: ByteArray,
        ) {
            File(dir, hash).writeBytes(bytes)
        }

        override suspend fun has(hash: String): Boolean = File(dir, hash).exists()

        override suspend fun fileFor(hash: String): File? = File(dir, hash).takeIf { it.exists() }

        override suspend fun mimeFor(hash: String): String? = mimes[hash]

        override suspend fun saveIncoming(
            hash: String,
            mime: String,
            srcPath: String,
        ): File {
            val dest = File(dir, hash)
            File(srcPath).copyTo(dest, overwrite = true)
            mimes[hash] = normalizeTo ?: mime
            return dest
        }
    }

    /** A node: its transport, its store, and a [BlobExchange] wired to route requests + incoming files. */
    private class Node(
        val id: String,
        scope: CoroutineScope,
    ) {
        val transport = FakeLoopTransport(id)
        val store = FakeBlobStore(Files.createTempDirectory("blob-$id").toFile())
        val exchange =
            BlobExchange(
                transport = transport,
                store = store,
                selfId = { id },
                onObtained = { _, _ -> },
            )
        private val router =
            MeshRouter(transport, scope) { _, env, fromNodeId, _ ->
                if (env.type == FrameType.BLOB_REQ) {
                    WireCodec.decodePayload<BlobReqContent>(env.payload)?.let { exchange.onRequest(it.hash, fromNodeId) }
                }
            }

        fun start(scope: CoroutineScope) {
            router.start()
            scope.launch {
                transport.incomingFiles.collect {
                    exchange.onReceived(it.key, it.mime, it.path)
                }
            }
        }
    }

    @Test
    fun repeatedRequestWithinTheServeMemoShipsOneCopy() =
        runTest(UnconfinedTestDispatcher()) {
            // Server a linked to requester b; count the copies b actually receives.
            val server = FakeLoopTransport("a")
            val requester = FakeLoopTransport("b")
            server.connect(requester)
            val store = FakeBlobStore(Files.createTempDirectory("blob-serve").toFile())
            store.seed("H", "image/jpeg", "img".toByteArray())
            var now = 0L
            val exchange =
                BlobExchange(
                    transport = server,
                    store = store,
                    selfId = { "a" },
                    onObtained = { _, _ -> },
                    now = { now },
                )
            val received = mutableListOf<String>()
            backgroundScope.launch { requester.incomingFiles.collect { received += it.key } }

            exchange.onRequest("H", "b")
            exchange.onRequest("H", "b") // the re-ask storm around a slow transfer (re-offer / new link)
            assertEquals("second ask within the memo is a no-op", listOf("H"), received)

            now = BlobExchange.SERVE_MEMO_MS
            exchange.onRequest("H", "b")
            assertEquals("memo expired → the periodic re-ask is served again", listOf("H", "H"), received)
        }

    @Test
    fun refusedServeIsRetriableImmediately() =
        runTest(UnconfinedTestDispatcher()) {
            // No link to b yet: sendFile reports false, so the memo must not swallow the next ask.
            val server = FakeLoopTransport("a")
            val requester = FakeLoopTransport("b")
            val store = FakeBlobStore(Files.createTempDirectory("blob-refuse").toFile())
            store.seed("H", "image/jpeg", "img".toByteArray())
            val exchange =
                BlobExchange(
                    transport = server,
                    store = store,
                    selfId = { "a" },
                    onObtained = { _, _ -> },
                    now = { 0L },
                )
            val received = mutableListOf<String>()
            backgroundScope.launch { requester.incomingFiles.collect { received += it.key } }

            exchange.onRequest("H", "b") // no live link — nothing went out
            assertTrue(received.isEmpty())

            server.connect(requester)
            exchange.onRequest("H", "b") // same instant: un-stamped refusal ⇒ served now
            assertEquals(listOf("H"), received)
        }

    @Test
    fun blobPropagatesHopByHopToOutOfRangeRequester() =
        runTest(UnconfinedTestDispatcher()) {
            // Topology: a — b — c. Only a holds the blob; c (out of a's direct range) requests it.
            val a = Node("a", backgroundScope)
            val b = Node("b", backgroundScope)
            val c = Node("c", backgroundScope)
            a.transport.connect(b.transport)
            b.transport.connect(c.transport)
            a.start(backgroundScope)
            b.start(backgroundScope)
            c.start(backgroundScope)

            val bytes = "an-image-blob".toByteArray()
            a.store.seed("H", "image/jpeg", bytes)

            c.exchange.want("H")

            // b pulled it from a on c's behalf — and did not push it on: b cannot tell an asker that still
            // lacks the bytes from one whose copy is already arriving from somebody else (#79).
            assertTrue("b should have cached the blob in transit", b.store.has("H"))
            assertTrue("c is served on its next ask, never pushed", !c.store.has("H"))

            c.exchange.onNeighborAdded(Peer("b")) // the 60 s re-offer tick

            assertTrue("c should have obtained the blob", c.store.has("H"))
            assertArrayEquals(bytes, c.store.fileFor("H")!!.readBytes())
        }

    @Test
    fun aRelayedBlobIsForwardedUnderTheMimeTheCarrierStoredNotTheOneItWasServed() =
        runTest(UnconfinedTestDispatcher()) {
            // The header mime is unauthenticated and any holder writes it, so a carrier must not pass the
            // claim along: b re-serves what its own store resolved (knit/knit-next#30).
            val a = Node("a", backgroundScope)
            val b = Node("b", backgroundScope)
            val c = Node("c", backgroundScope)
            a.transport.connect(b.transport)
            b.transport.connect(c.transport)
            a.start(backgroundScope)
            b.start(backgroundScope)
            c.start(backgroundScope)

            a.store.seed("H", "audio/aac", "an-image-blob".toByteArray()) // a's claim: not what it is
            b.store.normalizeTo = "image/webp" // what b's own row says once the bytes land

            c.exchange.want("H")
            c.exchange.onNeighborAdded(Peer("b")) // the re-ask that b now holds an answer to

            assertEquals("b must forward its resolved type, not a's header", "image/webp", c.store.mimeFor("H"))
        }

    @Test
    fun aBlobObtainedOffTheMeshIsNotReRequestedOnALinkUp() =
        runTest(UnconfinedTestDispatcher()) {
            // onReceived clears its own mark, but it is not the only way a want is satisfied: the spool saves
            // an attachment in ScopeSync.fetchAttachment and a direct avatar push is ingested by
            // InboundPipeline.onAvatarReceived, neither of which routes through it. A neighbor joining after
            // one of those must not be asked to re-serve bytes we already hold.
            val r = FakeLoopTransport("r")
            val n = FakeLoopTransport("n")
            val store = FakeBlobStore(Files.createTempDirectory("blob-offmesh").toFile())
            val exchange =
                BlobExchange(
                    transport = r,
                    store = store,
                    selfId = { "r" },
                    onObtained = { _, _ -> },
                    now = { 0L },
                )
            val asked = CopyOnWriteArrayList<String>() // hashes n was asked for
            backgroundScope.launch {
                n.inbound.collect { f ->
                    if (f.envelope.type == FrameType.BLOB_REQ) {
                        WireCodec.decodePayload<BlobReqContent>(f.envelope.payload)?.let { asked += it.hash }
                    }
                }
            }

            exchange.want("spooled") // no neighbors yet — both recorded as fetching, nothing broadcast
            exchange.want("stillMissing")
            store.seed("spooled", "image/jpeg", "bytes".toByteArray()) // obtained over the Internet plane

            r.connect(n)
            exchange.onNeighborAdded(Peer("n"))

            assertEquals("only the blob we still lack is asked for", listOf("stillMissing"), asked)
        }

    @Test
    fun aHeldBlobWithNoStoredMimeIsStillServed() =
        runTest(UnconfinedTestDispatcher()) {
            // Gating the serve on the mime as well as the bytes refused an ask for a blob we hold: want()
            // returns at once for a hash the store has, so nothing was ever in flight for it.
            val server = FakeLoopTransport("a")
            val requester = FakeLoopTransport("b")
            server.connect(requester)
            val store = FakeBlobStore(Files.createTempDirectory("blob-nomime").toFile())
            store.seedWithoutMime("H", "img".toByteArray())
            val exchange =
                BlobExchange(
                    transport = server,
                    store = store,
                    selfId = { "a" },
                    onObtained = { _, _ -> },
                    now = { 0L },
                )
            val received = CopyOnWriteArrayList<Pair<String, String>>()
            backgroundScope.launch { requester.incomingFiles.collect { received += it.key to it.mime } }

            exchange.onRequest("H", "b")

            assertEquals("served under the fallback type", listOf("H" to "image/jpeg"), received)
        }

    @Test
    fun fetchingGlobalCapEvictsOldestFirst() =
        runTest(UnconfinedTestDispatcher()) {
            val r = FakeLoopTransport("r")
            val n = FakeLoopTransport("n")
            val store = FakeBlobStore(Files.createTempDirectory("blob-fetchcap").toFile())
            val exchange =
                BlobExchange(
                    transport = r,
                    store = store,
                    selfId = { "r" },
                    onObtained = { _, _ -> },
                    now = { 0L },
                    maxFetching = 2,
                )
            val asked = CopyOnWriteArrayList<String>() // hashes n was asked for
            backgroundScope.launch {
                n.inbound.collect { f ->
                    if (f.envelope.type == FrameType.BLOB_REQ) {
                        WireCodec.decodePayload<BlobReqContent>(f.envelope.payload)?.let { asked += it.hash }
                    }
                }
            }

            exchange.want("a") // no neighbors yet — recorded as fetching
            exchange.want("b")
            exchange.want("c") // over the cap → oldest ("a") evicted

            r.connect(n)
            exchange.onNeighborAdded(Peer("n"))

            assertEquals("oldest fetch evicted; the newest two are re-asked, oldest-first", listOf("b", "c"), asked)
        }

    @Test
    fun aWantForAnArrivingBlobIsSilent() =
        runTest(UnconfinedTestDispatcher()) {
            // Its FILE_HEADER is in and the chunks are streaming: asking every neighbor now buys a second full
            // copy from each holder while the first is still on the wire (#79). The hash is not even marked —
            // if the transfer dies, the database re-arms it on the next tick, never this memo.
            val r = FakeLoopTransport("r")
            val n = FakeLoopTransport("n")
            r.connect(n)
            val store = FakeBlobStore(Files.createTempDirectory("blob-arriving").toFile())
            val exchange =
                BlobExchange(
                    transport = r,
                    store = store,
                    selfId = { "r" },
                    onObtained = { _, _ -> },
                    now = { 0L },
                )
            val asked = CopyOnWriteArrayList<String>()
            backgroundScope.launch {
                n.inbound.collect { f ->
                    if (f.envelope.type == FrameType.BLOB_REQ) {
                        WireCodec.decodePayload<BlobReqContent>(f.envelope.payload)?.let { asked += it.hash }
                    }
                }
            }

            r.arriving += "H"
            exchange.want("H")
            assertTrue("a blob already streaming in is not asked for", asked.isEmpty())

            r.arriving.clear() // the transfer died
            exchange.onNeighborAdded(Peer("n"))
            assertTrue("it was never marked as fetching — the database re-arms it", asked.isEmpty())

            exchange.want("H") // rewantMissingBlobs on the next tick
            assertEquals(listOf("H"), asked)
        }

    @Test
    fun theTickReAskSkipsAnArrivingBlob() =
        runTest(UnconfinedTestDispatcher()) {
            // The 60 s re-offer re-asks every linked neighbor for everything still in the memo. A hash whose
            // bytes are on the way stays in it (the transfer may still die) but is not asked for.
            val r = FakeLoopTransport("r")
            val n = FakeLoopTransport("n")
            val store = FakeBlobStore(Files.createTempDirectory("blob-tick").toFile())
            val exchange =
                BlobExchange(
                    transport = r,
                    store = store,
                    selfId = { "r" },
                    onObtained = { _, _ -> },
                    now = { 0L },
                )
            val asked = CopyOnWriteArrayList<String>()
            backgroundScope.launch {
                n.inbound.collect { f ->
                    if (f.envelope.type == FrameType.BLOB_REQ) {
                        WireCodec.decodePayload<BlobReqContent>(f.envelope.payload)?.let { asked += it.hash }
                    }
                }
            }

            exchange.want("H") // nobody linked yet — marked, nothing sent
            r.connect(n)
            r.arriving += "H" // n's serve has begun by the time the tick comes round
            exchange.onNeighborAdded(Peer("n"))
            assertTrue("the tick stays quiet while the bytes stream in", asked.isEmpty())

            r.arriving.clear() // the link died mid-stream
            exchange.onNeighborAdded(Peer("n"))
            assertEquals("the next tick asks again", listOf("H"), asked)
        }

    @Test
    fun aReAskWhileTheCopyIsQueuedOnTheLinkShipsNothing() =
        runTest(UnconfinedTestDispatcher()) {
            // The memo is 45 s from the enqueue; a serve queued behind a multi-minute blob to the same peer,
            // or an older build's 60 s re-ask against a slow transfer, outlives it. The link knows what it
            // still holds for that peer, and that is what refuses the second copy.
            val server = FakeLoopTransport("a")
            val requester = FakeLoopTransport("b")
            server.connect(requester)
            val store = FakeBlobStore(Files.createTempDirectory("blob-inflight").toFile())
            store.seed("H", "image/jpeg", "img".toByteArray())
            var now = 0L
            val exchange =
                BlobExchange(
                    transport = server,
                    store = store,
                    selfId = { "a" },
                    onObtained = { _, _ -> },
                    now = { now },
                )
            val received = CopyOnWriteArrayList<String>()
            backgroundScope.launch { requester.incomingFiles.collect { received += it.key } }

            exchange.onRequest("H", "b")
            assertEquals(listOf("H"), received)

            now = BlobExchange.SERVE_MEMO_MS // memo expired
            server.inFlight += "b" to "H" // ...but the copy is still queued or streaming on the link
            exchange.onRequest("H", "b")
            assertEquals("the in-flight copy is enough", listOf("H"), received)

            server.inFlight.clear() // the stream ended (or the link, with it)
            exchange.onRequest("H", "b")
            assertEquals("a fresh ask after the stream is served", listOf("H", "H"), received)
        }

    @Test
    fun aRequesterWeLackForIsServedOnItsNextAskNotPushed() =
        runTest(UnconfinedTestDispatcher()) {
            // A neighbor asked while we lacked the bytes; whichever plane hands them to us later, we do not
            // push: it may hold them by now, or be receiving them from the author — only its next ask says it
            // still lacks them (ADR 2026-09.4tx5, superseding the wanter drain of ADR 2026-09.ywzn).
            val server = FakeLoopTransport("a")
            val requester = FakeLoopTransport("b")
            server.connect(requester)
            val store = FakeBlobStore(Files.createTempDirectory("blob-nopush").toFile())
            val exchange =
                BlobExchange(
                    transport = server,
                    store = store,
                    selfId = { "a" },
                    onObtained = { _, _ -> },
                    now = { 0L },
                )
            val received = CopyOnWriteArrayList<String>()
            backgroundScope.launch { requester.incomingFiles.collect { received += it.key } }

            exchange.onRequest("H", "b") // we lack it: pulled on b's behalf, nothing remembered about b
            assertTrue("nothing to serve yet", received.isEmpty())

            val src = Files.createTempFile("blob-src", ".bin").toFile().apply { writeBytes("img".toByteArray()) }
            exchange.onReceived("H", "image/jpeg", src.absolutePath) // a neighbor served our pull
            assertTrue("obtaining the bytes pushes nothing", received.isEmpty())

            exchange.onRequest("H", "b") // b's own 60 s tick, while it still lacks them
            assertEquals(listOf("H"), received)
        }
}
