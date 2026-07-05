package app.getknit.knit

import app.getknit.knit.mesh.BlobExchange
import app.getknit.knit.mesh.BlobStore
import app.getknit.knit.mesh.FakeLoopTransport
import app.getknit.knit.mesh.MeshRouter
import app.getknit.knit.mesh.MeshTransport
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

        fun seed(
            hash: String,
            mime: String,
            bytes: ByteArray,
        ) {
            File(dir, hash).writeBytes(bytes)
            mimes[hash] = mime
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
            mimes[hash] = mime
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
            MeshRouter(transport, scope) { _, env, fromNodeId ->
                if (env.type == FrameType.BLOB_REQ) {
                    WireCodec.decodePayload<BlobReqContent>(env.payload)?.let { exchange.onRequest(it.hash, fromNodeId) }
                }
            }

        fun start(scope: CoroutineScope) {
            router.start()
            scope.launch {
                transport.incomingFiles.collect {
                    exchange.onReceived(it.key, it.mime, it.path, it.fromNodeId)
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

            // c pulled it from b, which pulled it from a — all over direct-neighbor file transfer.
            assertTrue("b should have cached the blob in transit", b.store.has("H"))
            assertTrue("c should have obtained the blob", c.store.has("H"))
            assertArrayEquals(bytes, c.store.fileFor("H")!!.readBytes())
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
    fun wantersKeyCapEvictsOldest() =
        runTest(UnconfinedTestDispatcher()) {
            // r holds none of the hashes, so each onRequest records a wanter (and recurses want() to raw peers
            // that never answer — no loopback). Over the cap, the oldest wanter key is evicted and never served.
            val r = FakeLoopTransport("r")
            val store = FakeBlobStore(Files.createTempDirectory("blob-wantcap").toFile())
            val exchange =
                BlobExchange(
                    transport = r,
                    store = store,
                    selfId = { "r" },
                    onObtained = { _, _ -> },
                    now = { 0L },
                    maxWanters = 2,
                )
            val peers =
                listOf("p1", "p2", "p3").associateWith { pid ->
                    val t = FakeLoopTransport(pid)
                    val rx = CopyOnWriteArrayList<String>()
                    r.connect(t)
                    backgroundScope.launch { t.incomingFiles.collect { rx += it.key } }
                    rx
                }

            exchange.onRequest("h1", "p1")
            exchange.onRequest("h2", "p2")
            exchange.onRequest("h3", "p3") // over the cap → wanters["h1"] (oldest) evicted

            val src = Files.createTempFile("blob-src", ".bin").toFile().apply { writeBytes("x".toByteArray()) }
            exchange.onReceived("h1", "image/jpeg", src.absolutePath, "someoneElse")
            exchange.onReceived("h2", "image/jpeg", src.absolutePath, "someoneElse")
            exchange.onReceived("h3", "image/jpeg", src.absolutePath, "someoneElse")

            assertTrue("h1's wanter was evicted → p1 not forwarded a copy", peers.getValue("p1").none { it == "h1" })
            assertTrue("h2 survived the cap → forwarded to p2", peers.getValue("p2").any { it == "h2" })
            assertTrue("h3 survived the cap → forwarded to p3", peers.getValue("p3").any { it == "h3" })
        }
}
