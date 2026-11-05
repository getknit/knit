package app.getknit.knit

import app.getknit.knit.mesh.BlobExchange
import app.getknit.knit.mesh.BlobStore
import app.getknit.knit.mesh.BulkRoute
import app.getknit.knit.mesh.CompositeMeshTransport
import app.getknit.knit.mesh.FakeLoopTransport
import app.getknit.knit.mesh.FileKind
import app.getknit.knit.mesh.FileMeta
import app.getknit.knit.mesh.MeshRouter
import app.getknit.knit.mesh.PairView
import app.getknit.knit.mesh.PartialBlobs
import app.getknit.knit.mesh.protocol.BlobHasContent
import app.getknit.knit.mesh.protocol.BlobHasItem
import app.getknit.knit.mesh.protocol.BlobReqContent
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.Protocol
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.sha256Hex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

@Suppress("LargeClass") // one exchange's whole contract: the serve, the walk, the round of asks and the resume
@OptIn(ExperimentalCoroutinesApi::class)
class BlobExchangeTest {
    /** A store for the prefixes of cut transfers, kept from a single byte so a test needs no 16 KiB file. */
    private fun partials(): PartialBlobs = PartialBlobs(Files.createTempDirectory("parts").toFile(), minBytes = 1)

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

        /** Runs while [has] is being read, after it has looked: a slow store read the bytes move on under (#138). */
        var whileReadingHas: (suspend (String) -> Unit)? = null

        override suspend fun has(hash: String): Boolean = File(dir, hash).exists().also { whileReadingHas?.invoke(hash) }

        /** What the real store reports while a blob is past the link's end but screened and not yet held. */
        val storingNow: MutableSet<String> = ConcurrentHashMap.newKeySet()

        override fun storing(hash: String): Boolean = hash in storingNow

        override suspend fun fileFor(hash: String): File? = File(dir, hash).takeIf { it.exists() }

        override suspend fun mimeFor(hash: String): String? = mimes[hash]

        /** What the real `MeshBlobStore` does with bytes that do not hash to their address: refuse them. */
        var checkHashes = false

        override suspend fun saveIncoming(
            hash: String,
            mime: String,
            srcPath: String,
        ): File? {
            if (checkHashes && sha256Hex(File(srcPath).readBytes()) != hash) return null
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
        val partials = PartialBlobs(Files.createTempDirectory("parts-$id").toFile(), minBytes = 1)
        var now = 0L
        val exchange =
            BlobExchange(
                transport = transport,
                store = store,
                selfId = { id },
                onObtained = { _, _ -> },
                now = { now },
                partials = partials,
            )
        private val router =
            MeshRouter(transport, scope) { _, env, fromNodeId, _, _ ->
                if (env.type == FrameType.BLOB_REQ) {
                    WireCodec.decodePayload<BlobReqContent>(env.payload)?.let { exchange.onRequest(it.hash, fromNodeId, it.offset) }
                }
            }

        fun start(scope: CoroutineScope) {
            router.start()
            scope.launch {
                transport.incomingFiles.collect {
                    exchange.onReceived(it.key, it.mime, it.path, it.resumedFrom)
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
                    partials = partials(),
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
                    partials = partials(),
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

            c.now += BlobExchange.ROUND_MS
            c.exchange.reask() // the 60 s re-offer tick, a round later

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
            c.now += BlobExchange.ROUND_MS
            c.exchange.reask() // the re-ask that b now holds an answer to

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
                    partials = partials(),
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
            exchange.reask()

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
                    partials = partials(),
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
                    partials = partials(),
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
            exchange.reask()

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
                    partials = partials(),
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
            exchange.reask()
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
                    partials = partials(),
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
            exchange.reask()
            assertTrue("the tick stays quiet while the bytes stream in", asked.isEmpty())

            r.arriving.clear() // the link died mid-stream
            exchange.reask()
            assertEquals("the next tick asks again", listOf("H"), asked)
        }

    @Test
    fun aBlobTheStoreIsStillScreeningIsNeitherAskedForNorReAsked() =
        runTest(UnconfinedTestDispatcher()) {
            // #109: the store screens a blob before it holds it, so past the link's end — no longer arriving — a
            // whole classification can pass before `has`. A tick or a want in that gap bought a second copy.
            val r = FakeLoopTransport("r")
            val n = FakeLoopTransport("n")
            val store = FakeBlobStore(Files.createTempDirectory("blob-storing").toFile())
            val exchange =
                BlobExchange(
                    transport = r,
                    store = store,
                    selfId = { "r" },
                    onObtained = { _, _ -> },
                    now = { 0L },
                    partials = partials(),
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
            store.storingNow += "H" // its copy came in and is being screened
            exchange.reask()
            store.storingNow += "G"
            exchange.want("G")
            assertTrue("nothing is asked for a blob the store is storing", asked.isEmpty())

            store.storingNow.clear() // the store refused both copies
            exchange.reask()
            assertEquals("the memo still holds H, so the next tick asks again; G was never marked", listOf("H"), asked)
        }

    @Test
    fun aPeerAskedBeforeIsNotAskedAgainNorPassedOverWhileItsFileStreamsIn() =
        runTest(UnconfinedTestDispatcher()) {
            // #121: n was asked for H and is streaming us G, and H may already sit in the stack's buffer behind it. H is
            // not arriving and n no longer counts it in flight, so asking n again — or asking m, who holds it too —
            // would buy a second copy. The round waits for n's stream, then gives H its grace to show up.
            val r = FakeLoopTransport("r")
            val n = FakeLoopTransport("n")
            val m = FakeLoopTransport("m")
            r.connect(n)
            r.connect(m)
            var now = 0L
            val exchange =
                BlobExchange(
                    transport = r,
                    store = FakeBlobStore(Files.createTempDirectory("blob-busy").toFile()),
                    selfId = { "r" },
                    onObtained = { _, _ -> },
                    now = { now },
                    partials = partials(),
                )
            val askedOfN = backgroundScope.asksArrivingAt(n)
            val askedOfM = backgroundScope.asksArrivingAt(m)

            exchange.want("H", author = "n")
            assertEquals(listOf("H" to null), askedOfN)
            r.arrivingFrom += "n"
            now += 3 * BlobExchange.FIRST_ASK_GRACE_MS
            exchange.reask()
            assertTrue("nobody else is asked while the first holder streams to us: $askedOfM", askedOfM.isEmpty())

            r.arrivingFrom.clear() // G landed, and H did not follow it
            exchange.reask()
            assertTrue("the grace starts again when the stream ends", askedOfM.isEmpty())
            now += BlobExchange.FIRST_ASK_GRACE_MS
            exchange.reask()
            assertEquals("then the round widens", listOf("H" to null), askedOfM)

            r.arrivingFrom += "n" // streaming again when the next round opens
            now += BlobExchange.ROUND_MS
            exchange.reask()
            assertEquals("a peer asked before is not asked while its file streams in", listOf("H" to null), askedOfN)
            assertEquals("the quiet neighbour opens the round", listOf("H" to null, "H" to null), askedOfM)

            r.arrivingFrom.clear()
            now += BlobExchange.FIRST_ASK_GRACE_MS
            exchange.reask()
            assertEquals("the first step after the stream asks it", listOf("H" to null, "H" to null), askedOfN)
        }

    @Test
    fun aRoundAsksTheAuthorAloneThenEveryOtherNeighbourOnceItsGraceIsUp() =
        runTest(UnconfinedTestDispatcher()) {
            // #136: every holder answers a fresh ask, so asking all of them at once bought a whole copy from each.
            val r = FakeLoopTransport("r")
            val peers = listOf("a", "b", "c").map { FakeLoopTransport(it).also(r::connect) }
            var now = 0L
            val exchange =
                BlobExchange(
                    transport = r,
                    store = FakeBlobStore(Files.createTempDirectory("blob-round").toFile()),
                    selfId = { "r" },
                    onObtained = { _, _ -> },
                    now = { now },
                    partials = partials(),
                )
            val asked = peers.associate { it.nodeId to backgroundScope.asksArrivingAt(it) }

            fun counts() = asked.mapValues { it.value.size }

            exchange.want("H", author = "c")
            exchange.want("H", author = "c") // the frame again, re-served by custody
            exchange.reask()
            assertEquals("the author alone, once", mapOf("a" to 0, "b" to 0, "c" to 1), counts())

            now += BlobExchange.FIRST_ASK_GRACE_MS - 1
            exchange.reask()
            assertEquals("still inside its grace", mapOf("a" to 0, "b" to 0, "c" to 1), counts())

            now += 1
            exchange.reask()
            assertEquals("then everyone else at once", mapOf("a" to 1, "b" to 1, "c" to 1), counts())
            exchange.reask()
            assertEquals("and nobody twice in a round", mapOf("a" to 1, "b" to 1, "c" to 1), counts())

            now += BlobExchange.ROUND_MS
            exchange.reask()
            assertEquals("the next round opens with the author again", mapOf("a" to 1, "b" to 1, "c" to 2), counts())
        }

    @Test
    fun aFaceIsAskedOfItsLinkedAuthorAloneUntilItsFloorAndNeverPastTheFirstAskGrace() =
        runTest(UnconfinedTestDispatcher()) {
            // ADR 2026-11.4ckq, #258: the author pushes its photo itself, and the push may sit in its stack buffer where
            // neither end sees it. Every other holder asked after the 5 s grace would serve a copy of its own (#79, #121).
            val r = FakeLoopTransport("r")
            val peers = listOf("a", "b", "c").map { FakeLoopTransport(it).also(r::connect) }
            var now = 0L
            val exchange =
                BlobExchange(
                    transport = r,
                    store = FakeBlobStore(Files.createTempDirectory("blob-face").toFile()),
                    selfId = { "r" },
                    onObtained = { _, _ -> },
                    now = { now },
                    partials = partials(),
                )
            val asked = peers.associate { it.nodeId to backgroundScope.asksArrivingAt(it) }

            fun counts() = asked.mapValues { it.value.size }

            exchange.wantFaces(listOf("H" to "c"), ceiling = 1_000L)
            assertEquals("the author alone", mapOf("a" to 0, "b" to 0, "c" to 1), counts())

            now += 3 * BlobExchange.FIRST_ASK_GRACE_MS
            exchange.reask()
            assertEquals("its grace widens nothing", mapOf("a" to 0, "b" to 0, "c" to 1), counts())

            r.arrivingFrom += "c" // a file streams in from the author: the push may be queued behind it
            now = BlobExchange.SERVE_MEMO_MS + 1
            exchange.reask()
            assertEquals("not while it streams to us, floor or not", mapOf("a" to 0, "b" to 0, "c" to 1), counts())

            r.arrivingFrom.clear()
            exchange.reask()
            // Past the floor the round is the ordinary one: the author first again, its push's memo lapsed, so a lost push
            // is served afresh; then, past that ask's grace, everyone else.
            assertEquals("past the floor, a fresh round opens on the author", mapOf("a" to 0, "b" to 0, "c" to 2), counts())
            now += BlobExchange.FIRST_ASK_GRACE_MS
            exchange.reask()
            assertEquals("and widens past its grace", mapOf("a" to 1, "b" to 1, "c" to 2), counts())
        }

    @Test
    fun aFacesAuthorWhoseLinkGoesReleasesTheOthersAtOnceEvenWithNoFetchStandingOnIt() =
        runTest(UnconfinedTestDispatcher()) {
            val r = FakeLoopTransport("r")
            val a = FakeLoopTransport("a").also(r::connect)
            val c = FakeLoopTransport("c").also(r::connect)
            var now = 0L
            val exchange =
                BlobExchange(
                    transport = r,
                    store = FakeBlobStore(Files.createTempDirectory("blob-face-unlink").toFile()),
                    selfId = { "r" },
                    onObtained = { _, _ -> },
                    now = { now },
                    partials = partials(),
                )
            val askedOfA = backgroundScope.asksArrivingAt(a)
            val askedOfC = backgroundScope.asksArrivingAt(c)

            exchange.want("H", author = "c", ceiling = 1_000L, pushedBy = "c")
            assertEquals(1, askedOfC.size)
            // The first ask's grace ends: its fetch is over, the hold stands, nobody else is asked.
            now = BlobExchange.FIRST_ASK_GRACE_MS + 1
            exchange.reask()
            assertTrue(askedOfA.isEmpty())

            r.disconnect(c)
            exchange.unlinked(listOf("c"))
            assertEquals("the author's link went: the others are asked at once", 1, askedOfA.size)
        }

    @Test
    fun aFacesAuthorThatLinksAgainIsHeldOnAfreshPastAnEarlierFloor() =
        runTest(UnconfinedTestDispatcher()) {
            // A new link, a new ask of the author: its old push may have died with the old link, so the hold starts over
            // even though it ended on the last link.
            val r = FakeLoopTransport("r")
            val a = FakeLoopTransport("a").also(r::connect)
            val c = FakeLoopTransport("c").also(r::connect)
            var now = 0L
            val exchange =
                BlobExchange(
                    transport = r,
                    store = FakeBlobStore(Files.createTempDirectory("blob-face-relink").toFile()),
                    selfId = { "r" },
                    onObtained = { _, _ -> },
                    now = { now },
                    partials = partials(),
                )
            val askedOfA = backgroundScope.asksArrivingAt(a)
            val askedOfC = backgroundScope.asksArrivingAt(c)

            exchange.want("H", author = "c", ceiling = 1_000L, pushedBy = "c")
            now = BlobExchange.SERVE_MEMO_MS + 1 // the floor: the hold on this link ends
            exchange.reask()
            now = 46_000L
            r.disconnect(c)
            exchange.unlinked(listOf("c")) // a round opens without the author: a is asked first
            assertEquals(1, askedOfA.size)
            now = 50_000L
            exchange.reask()
            now = 56_000L
            exchange.reask() // a's grace ends: no fetch stands when the author links again

            now = 60_000L
            val asksOfC = askedOfC.size
            r.connect(c)
            exchange.linked(listOf("c"))
            exchange.reask()
            assertEquals("the new link asks the author", asksOfC + 1, askedOfC.size)
            val asksOfA = askedOfA.size
            // The next round opens on the author at 91 s, and that ask's grace ends at 96.5 s, before the new link's
            // floor (105 s): nobody else is asked. Without the hold starting over, the round widens to a there.
            now = 91_002L
            exchange.reask()
            now = 91_500L
            exchange.reask()
            now = 96_600L
            exchange.reask()
            assertEquals("nobody else asked inside the new link's floor", asksOfA, askedOfA.size)
        }

    @Test
    fun aFaceFetchStandingOnACarrierIsNotDroppedWhenItsAuthorLinks() =
        runTest(UnconfinedTestDispatcher()) {
            // The round-1 review's finding: the hold took the carrier out of the pool, the fetch read its link as gone and
            // ended with no `blobcancel`, and the carrier's queued copy came beside the author's (#79's shape).
            val r = FakeLoopTransport("r")
            val c = FakeLoopTransport("c").also(r::connect)
            val b = FakeLoopTransport("b")
            var now = 0L
            val exchange =
                BlobExchange(
                    transport = r,
                    store = FakeBlobStore(Files.createTempDirectory("blob-face-carrier").toFile()),
                    selfId = { "r" },
                    onObtained = { _, _ -> },
                    now = { now },
                    partials = partials(),
                )
            val askedOfC = backgroundScope.asksArrivingAt(c)
            val askedOfB = backgroundScope.asksArrivingAt(b)
            exchange.want("H", author = "b", ceiling = 1_000L, pushedBy = "b")
            assertEquals(1, askedOfC.size)

            r.connect(b)
            exchange.linked(listOf("b"))
            exchange.reask()
            assertTrue("the author is not asked past the carrier's standing fetch", askedOfB.isEmpty())
            assertEquals("nor is the carrier asked twice", 1, askedOfC.size)
        }

    @Test
    fun aReArmedFaceIsAFetchOnlyWantAndLeavesAProfilesOwnPullAsItIs() =
        runTest(UnconfinedTestDispatcher()) {
            // The round-1 review's finding: 64 roots re-recorded every tick, each pulled through with budget 3, filled the
            // neighbours' want tables with echoes (#195). A re-armed face asks with no pull; one a profile made keeps its own.
            val r = FakeLoopTransport("r")
            val n = FakeLoopTransport("n").also(r::connect)
            var now = 0L
            val exchange =
                BlobExchange(
                    transport = r,
                    store = FakeBlobStore(Files.createTempDirectory("blob-face-reach").toFile()),
                    selfId = { "r" },
                    onObtained = { _, _ -> },
                    now = { now },
                    partials = partials(),
                )
            val pulls = CopyOnWriteArrayList<Pair<String, Int?>>()
            backgroundScope.launch {
                n.inbound.collect { f ->
                    if (f.envelope.type == FrameType.BLOB_REQ) {
                        WireCodec.decodePayload<BlobReqContent>(f.envelope.payload)?.let { pulls += it.hash to it.pull }
                    }
                }
            }
            exchange.wantFaces(listOf("F" to "p"), ceiling = 1_000L)
            assertEquals(listOf("F" to 0), pulls)
            // Each tick re-arms the face and steps it: still fetch only, its budget never lifted by the re-arm.
            repeat(3) {
                now += 60_000L
                exchange.wantFaces(listOf("F" to "p"), ceiling = 1_000L)
                exchange.reask()
            }
            val faceAsks = pulls.filter { it.first == "F" }
            assertTrue("asked again: $faceAsks", faceAsks.size >= 2)
            assertTrue("every ask fetch only: $faceAsks", faceAsks.all { it.second == 0 })

            // An attachment held to Wi-Fi Aware over the user's limit (ADR 2026-10.js2p) stays so when a peer advertises its
            // hash as a photo: the face re-arm lifts the flag only on a want photos alone make.
            exchange.wantAll(listOf("A" to null), fastOnly = setOf("A"))
            exchange.wantFaces(listOf("A" to "p"), ceiling = 1_000L)
            exchange.reask()
            assertTrue("an uncapped fast-only attachment asked over Bluetooth", pulls.none { it.first == "A" })

            exchange.want("G", author = "p", ceiling = 1_000L, pushedBy = "p") // the profile's own pull
            exchange.wantFaces(listOf("G" to "p"), ceiling = 1_000L)
            exchange.reask()
            assertEquals("the profile's pull keeps its budget", BlobExchange.PULL_BUDGET, pulls.last { it.first == "G" }.second)
        }

    @Test
    fun aFaceTakesNoPlaceFromAnotherWantAndGivesUpItsOwnToOne() =
        runTest(UnconfinedTestDispatcher()) {
            // ADR 2026-11.4ckq: a crowd of faces never evicts an attachment's want, which the re-arm would mint again every
            // tick (#195). The table here holds 8: faces take at most 6 places, and give one up to any other want.
            val r = FakeLoopTransport("r")
            val exchange =
                BlobExchange(
                    transport = r,
                    store = FakeBlobStore(Files.createTempDirectory("blob-face-room").toFile()),
                    selfId = { "r" },
                    onObtained = { _, _ -> },
                    partials = partials(),
                    maxFetching = 8,
                )
            // An attachment older than every face: the table's own eviction would take it first.
            exchange.wantAll(listOf("A0" to null))
            val skipped = exchange.wantFaces((1..10).map { "F$it" to "p$it" }, ceiling = 1_000L)
            assertEquals("faces fill three quarters of the table, no more", 5, skipped)
            assertEquals(setOf("A0") + (1..5).map { "F$it" }, exchange.wantedHashes())

            exchange.wantAll((1..3).map { "A$it" to null })
            assertEquals(
                "every attachment stands, the eldest included",
                (0..3).map { "A$it" }.toSet(),
                exchange.wantedHashes().filter { it.startsWith("A") }.toSet(),
            )
            assertEquals(8, exchange.wantedHashes().size)
            assertEquals(
                "the eldest face gave its place up",
                setOf("F2", "F3", "F4", "F5"),
                exchange
                    .wantedHashes()
                    .filter {
                        it.startsWith("F")
                    }.toSet(),
            )

            assertEquals("a full table takes no new face", 1, exchange.wantFaces(listOf("F9" to "p9"), ceiling = 1_000L))
        }

    @Test
    fun aPushStampsTheServeMemoSoAnAskThatCrossesItIsNotServedASecondCopy() =
        runTest(UnconfinedTestDispatcher()) {
            // ADR 2026-11.4ckq: the push is a serve, and the asker's re-arm may ask while it is still on its way.
            val server = FakeLoopTransport("a")
            val requester = FakeLoopTransport("b")
            server.connect(requester)
            val store = FakeBlobStore(Files.createTempDirectory("blob-push").toFile())
            store.seed("H", "image/jpeg", byteArrayOf(1, 2, 3))
            val exchange = BlobExchange(server, store, selfId = { "a" }, onObtained = { _, _ -> }, partials = partials())

            assertTrue(exchange.claimPush("H", "b"))
            exchange.onRequest("H", "b")
            assertEquals("the ask inside the push's memo ships nothing", 0, server.filesSent.size)
            assertFalse("a second push is refused too", exchange.claimPush("H", "b"))

            exchange.forgetPush("H", "b") // the push found no route
            exchange.onRequest("H", "b")
            assertEquals("the next ask is served at once", 1, server.filesSent.size)
            assertFalse("and that serve keeps a push from following it", exchange.claimPush("H", "b"))
        }

    @Test
    fun theFirstAskGoesToTheAuthorThenTheFastPlaneThenTheSourceHop() =
        runTest(UnconfinedTestDispatcher()) {
            val r = FakeLoopTransport("r")
            val byId = listOf("author", "fast", "reach", "hop", "other").associateWith { FakeLoopTransport(it).also(r::connect) }
            r.bulkRoutes["fast"] = BulkRoute.Linked
            r.bulkRoutes["reach"] = BulkRoute.Reachable
            r.bulkRoutes["author"] = BulkRoute.None
            val exchange =
                BlobExchange(
                    transport = r,
                    store = FakeBlobStore(Files.createTempDirectory("blob-rank").toFile()),
                    selfId = { "r" },
                    onObtained = { _, _ -> },
                    now = { 0L },
                    partials = partials(),
                )
            val asked = byId.mapValues { (_, t) -> backgroundScope.asksArrivingAt(t) }

            fun firstAskOf(hash: String) = asked.filterValues { list -> list.any { it.first == hash } }.keys

            exchange.want("H1", author = "author", via = "hop")
            exchange.want("H2", author = "gone", via = "hop") // an author who is not a neighbour ranks nobody
            r.disconnect(byId.getValue("fast"))
            exchange.want("H3", via = "hop")
            r.disconnect(byId.getValue("reach"))
            exchange.want("H4", via = "hop")
            exchange.want("H5")

            assertEquals("the author, over a Bluetooth link", setOf("author"), firstAskOf("H1"))
            assertEquals("a neighbour linked over the fast plane", setOf("fast"), firstAskOf("H2"))
            assertEquals("one the fast plane can reach", setOf("reach"), firstAskOf("H3"))
            assertEquals("where the frame came from", setOf("hop"), firstAskOf("H4"))
            assertEquals("else the lowest id, so the choice is stable", setOf("author"), firstAskOf("H5"))
            assertEquals("only a reachable first choice is armed", listOf("reach"), r.bulkArmed)
        }

    @Test
    fun aFirstChoiceWhoseLinkIsGoneWidensTheRoundAtOnce() =
        runTest(UnconfinedTestDispatcher()) {
            // It can serve nothing on a link that is gone; waiting out its grace only delays the holders still in reach.
            val r = FakeLoopTransport("r")
            val a = FakeLoopTransport("a").also(r::connect)
            val b = FakeLoopTransport("b").also(r::connect)
            val exchange =
                BlobExchange(
                    transport = r,
                    store = FakeBlobStore(Files.createTempDirectory("blob-gone").toFile()),
                    selfId = { "r" },
                    onObtained = { _, _ -> },
                    now = { 0L },
                    partials = partials(),
                )
            backgroundScope.asksArrivingAt(a)
            val askedOfB = backgroundScope.asksArrivingAt(b)

            exchange.want("H", author = "a")
            assertTrue(askedOfB.isEmpty())
            r.disconnect(a)
            exchange.reask() // the link-down's next step, still inside the grace
            assertEquals(listOf("H" to null), askedOfB)
        }

    @Test
    fun aNeighbourThatLinksAgainIsAskedAgainAtOnce() =
        runTest(UnconfinedTestDispatcher()) {
            // An ask sent before the link dropped may have died with it. Before #136 every link-up re-asked its
            // newcomer; a round that still counts the old ask would wait out a grace, then the round, for nothing.
            val r = FakeLoopTransport("r")
            val a = FakeLoopTransport("a").also(r::connect)
            val b = FakeLoopTransport("b").also(r::connect)
            val exchange =
                BlobExchange(
                    transport = r,
                    store = FakeBlobStore(Files.createTempDirectory("blob-relink").toFile()),
                    selfId = { "r" },
                    onObtained = { _, _ -> },
                    now = { 0L },
                    partials = partials(),
                )
            val askedOfA = backgroundScope.asksArrivingAt(a)
            val askedOfB = backgroundScope.asksArrivingAt(b)

            exchange.want("H", author = "a")
            r.disconnect(a)
            r.connect(a) // the link drops and comes back inside the first ask's grace
            exchange.linked(listOf("a"))
            exchange.reask()
            assertEquals("asked again on the new link", listOf("H" to null, "H" to null), askedOfA)
            assertTrue("and alone: the round's first ask starts again", askedOfB.isEmpty())

            exchange.want("G", author = "a")
            r.disconnect(b) // b is asked once the round widens; then its link drops and comes back
            r.connect(b)
            exchange.linked(listOf("b"))
            exchange.reask()
            assertTrue("a link-up inside a first ask's grace waits for the widening", askedOfB.none { it.first == "G" })
        }

    @Test
    fun aReachableFirstChoiceGetsTheBulkGraceOnTopBeforeTheRoundWidens() =
        runTest(UnconfinedTestDispatcher()) {
            // A holder that can raise the Wi-Fi Aware link waits up to the composite's bulk grace for it before it falls
            // back to Bluetooth; widening inside that wait would buy a second copy over Bluetooth.
            val r = FakeLoopTransport("r")
            val reach = FakeLoopTransport("reach").also(r::connect)
            val other = FakeLoopTransport("other").also(r::connect)
            r.bulkRoutes["reach"] = BulkRoute.Reachable
            var now = 0L
            val exchange =
                BlobExchange(
                    transport = r,
                    store = FakeBlobStore(Files.createTempDirectory("blob-reach").toFile()),
                    selfId = { "r" },
                    onObtained = { _, _ -> },
                    now = { now },
                    partials = partials(),
                )
            val askedOfReach = backgroundScope.asksArrivingAt(reach)
            val askedOfOther = backgroundScope.asksArrivingAt(other)

            exchange.want("H")
            assertEquals(listOf("H" to null), askedOfReach)
            now += BlobExchange.FIRST_ASK_GRACE_MS
            exchange.reask()
            assertTrue("the fast link may still be coming up", askedOfOther.isEmpty())
            now += CompositeMeshTransport.BULK_GRACE_MS
            exchange.reask()
            assertEquals(listOf("H" to null), askedOfOther)
        }

    @Test
    fun aNeighbourThatLinksWaitsOutAFirstAsksGraceButIsAskedAtOnceInAWidenedRound() =
        runTest(UnconfinedTestDispatcher()) {
            val r = FakeLoopTransport("r")
            val a = FakeLoopTransport("a").also(r::connect)
            val b = FakeLoopTransport("b")
            val c = FakeLoopTransport("c")
            var now = 0L
            val exchange =
                BlobExchange(
                    transport = r,
                    store = FakeBlobStore(Files.createTempDirectory("blob-join").toFile()),
                    selfId = { "r" },
                    onObtained = { _, _ -> },
                    now = { now },
                    partials = partials(),
                )
            backgroundScope.asksArrivingAt(a)
            val askedOfB = backgroundScope.asksArrivingAt(b)
            val askedOfC = backgroundScope.asksArrivingAt(c)

            exchange.want("H", author = "a")
            r.connect(b)
            exchange.reask() // the link-up
            assertTrue("a's ask still has its grace", askedOfB.isEmpty())
            now += BlobExchange.FIRST_ASK_GRACE_MS
            exchange.reask()
            assertEquals(listOf("H" to null), askedOfB)

            r.connect(c)
            exchange.reask()
            assertEquals("the round is already wide", listOf("H" to null), askedOfC)
        }

    @Test
    fun theGraceIsTimedOnTheSessionSoTheRoundWidensWithoutATick() =
        runTest {
            val r = FakeLoopTransport("r")
            val a = FakeLoopTransport("a").also(r::connect)
            val b = FakeLoopTransport("b").also(r::connect)
            val exchange =
                BlobExchange(
                    transport = r,
                    store = FakeBlobStore(Files.createTempDirectory("blob-timer").toFile()),
                    selfId = { "r" },
                    onObtained = { _, _ -> },
                    now = { testScheduler.currentTime },
                    partials = partials(),
                    timers = { backgroundScope },
                )
            val askedOfA = backgroundScope.asksArrivingAt(a)
            val askedOfB = backgroundScope.asksArrivingAt(b)
            runCurrent()

            exchange.want("H", author = "a")
            runCurrent()
            assertEquals(listOf("H" to null), askedOfA)
            advanceTimeBy(BlobExchange.FIRST_ASK_GRACE_MS - 1)
            runCurrent()
            assertTrue(askedOfB.isEmpty())
            advanceTimeBy(2)
            runCurrent()
            assertEquals("widened by the timer alone", listOf("H" to null), askedOfB)
        }

    @Test
    fun aTimerArmedOnAStoppedSessionDoesNotBlockTheNextOne() =
        runTest {
            // A timer launched on a cancelled scope never runs its body; the want must still get a timer next time.
            val r = FakeLoopTransport("r")
            val a = FakeLoopTransport("a").also(r::connect)
            val b = FakeLoopTransport("b").also(r::connect)
            var session: CoroutineScope = CoroutineScope(Job().apply { cancel() })
            val exchange =
                BlobExchange(
                    transport = r,
                    store = FakeBlobStore(Files.createTempDirectory("blob-dead-timer").toFile()),
                    selfId = { "r" },
                    onObtained = { _, _ -> },
                    now = { testScheduler.currentTime },
                    partials = partials(),
                    timers = { session },
                )
            backgroundScope.asksArrivingAt(a)
            val askedOfB = backgroundScope.asksArrivingAt(b)
            runCurrent()

            exchange.want("H", author = "a") // its grace timer lands on the stopped session
            session = backgroundScope // the mesh starts again
            exchange.reask() // still inside the grace: arms a timer on the live session
            advanceTimeBy(BlobExchange.FIRST_ASK_GRACE_MS + 1)
            runCurrent()
            assertEquals("widened by the new session's timer", listOf("H" to null), askedOfB)
        }

    @Test
    fun aBlobPulledForANeighbourIsNotAskedOfItThatRoundButIsTheNext() =
        runTest(UnconfinedTestDispatcher()) {
            // It asked us because it lacks the bytes, so asking it back costs the round a grace for nothing. By the next
            // round it may hold them, and it may be the one path they have to us (a relay, or the only link).
            val r = FakeLoopTransport("r")
            val asker = FakeLoopTransport("a").also(r::connect)
            val other = FakeLoopTransport("z").also(r::connect)
            var now = 0L
            val exchange =
                BlobExchange(
                    transport = r,
                    store = FakeBlobStore(Files.createTempDirectory("blob-asker").toFile()),
                    selfId = { "r" },
                    onObtained = { _, _ -> },
                    now = { now },
                    partials = partials(),
                )
            val askedOfAsker = backgroundScope.asksArrivingAt(asker)
            val askedOfOther = backgroundScope.asksArrivingAt(other)

            exchange.onRequest("H", "a")
            exchange.want("H", author = "a") // its frame lands too: still not this round
            now += BlobExchange.FIRST_ASK_GRACE_MS
            exchange.reask()
            assertTrue("not asked back the round we pull for it: $askedOfAsker", askedOfAsker.isEmpty())
            assertEquals(listOf("H" to null), askedOfOther)

            now += BlobExchange.ROUND_MS
            exchange.reask()
            assertEquals("the next round opens with it, the author", listOf("H" to null), askedOfAsker)
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
                    partials = partials(),
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
                    partials = partials(),
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

    // --- A blob that lands while it is being read (#138) ---

    @Test
    fun aBlobThatLandsWhileItsReadsRunIsNotAskedForAgain() =
        runTest(UnconfinedTestDispatcher()) {
            // The bytes move link → store → held, and every read goes the other way round: a blob that moves on between
            // two reads is seen by the later one. Read store-first, the tick below saw it neither held (the store's
            // answer predates the landing) nor arriving (it had left the link), and asked the holder for a second copy.
            val r = FakeLoopTransport("r")
            val n = FakeLoopTransport("n")
            r.connect(n)
            val store = FakeBlobStore(Files.createTempDirectory("blob-landing").toFile())
            var now = 0L
            val exchange =
                BlobExchange(
                    transport = r,
                    store = store,
                    selfId = { "r" },
                    onObtained = { _, _ -> },
                    now = { now },
                    partials = partials(),
                )
            val asked = backgroundScope.asksArrivingAt(n)
            exchange.want(PICTURE_HASH)
            assertEquals(listOf(PICTURE_HASH to null), asked)

            r.arriving += PICTURE_HASH // n's copy streams in
            now += BlobExchange.ROUND_MS // and the next round is due
            store.whileReadingHas = { hash ->
                store.whileReadingHas = null
                store.seed(hash, "image/jpeg", PICTURE) // it lands, and is held,
                r.arriving -= hash // as it leaves the link
            }
            exchange.reask()
            exchange.reask()
            assertEquals("asked once, while it was the only way to the bytes", listOf(PICTURE_HASH to null), asked)
        }

    @Test
    fun aFileIsTakenOffTheLinkOnceTheStoreHasAnsweredSoARefusedSpliceStillAsksFromZero() =
        runTest(UnconfinedTestDispatcher()) {
            val r = FakeLoopTransport("r")
            val n = FakeLoopTransport("n")
            r.connect(n)
            val store = FakeBlobStore(Files.createTempDirectory("blob-taken").toFile()).apply { checkHashes = true }
            val partials = partials()
            val exchange =
                BlobExchange(
                    transport = r,
                    store = store,
                    selfId = { "r" },
                    onObtained = { _, _ -> },
                    partials = partials,
                )
            val asked = backgroundScope.asksArrivingAt(n)
            exchange.want(PICTURE_HASH)
            assertTrue(partials.keep(PICTURE_HASH, cutFile(ByteArray(100)), basedOn = null)) // a bad prefix
            asked.clear()

            r.arriving += PICTURE_HASH // the spliced file, landing: the link counts it until it is taken
            exchange.onReceived(PICTURE_HASH, "image/jpeg", cutFile(ByteArray(PICTURE.size)).absolutePath, resumedFrom = 100, from = "n")
            assertEquals("taken off n's link once the store refused it", listOf("n" to PICTURE_HASH), r.taken)
            assertEquals("so its own landing never kept the from-0 ask quiet", listOf(PICTURE_HASH to null), asked)

            r.arriving += PICTURE_HASH
            exchange.onReceived(PICTURE_HASH, "image/jpeg", cutFile(PICTURE).absolutePath, from = "n")
            assertTrue(store.has(PICTURE_HASH))
            assertEquals("and once it held it", listOf("n" to PICTURE_HASH, "n" to PICTURE_HASH), r.taken)
            assertTrue(PICTURE_HASH !in r.arrivingFiles())
        }

    // --- Resuming a transfer a link drop cut off (#116, ADR 2026-10.wtyc) ---

    /** Collects every blob request [peer] receives as (hash, offset). */
    private fun CoroutineScope.asksArrivingAt(peer: FakeLoopTransport): MutableList<Pair<String, Long?>> {
        val asked = CopyOnWriteArrayList<Pair<String, Long?>>()
        launch {
            peer.inbound.collect { f ->
                if (f.envelope.type == FrameType.BLOB_REQ) {
                    WireCodec.decodePayload<BlobReqContent>(f.envelope.payload)?.let { asked += it.hash to it.offset }
                }
            }
        }
        return asked
    }

    /** A temp file holding [bytes], as a link's cut would leave it. */
    private fun cutFile(bytes: ByteArray): File = Files.createTempFile("cut", ".bin").toFile().apply { writeBytes(bytes) }

    @Test
    fun theFirstAskAndTheTickBothCarryTheOffset() =
        runTest(UnconfinedTestDispatcher()) {
            val r = FakeLoopTransport("r")
            val n = FakeLoopTransport("n")
            r.connect(n)
            val partials = partials()
            var now = 0L
            val exchange =
                BlobExchange(
                    transport = r,
                    store = FakeBlobStore(Files.createTempDirectory("blob-resume-tick").toFile()),
                    selfId = { "r" },
                    onObtained = { _, _ -> },
                    now = { now },
                    partials = partials,
                )
            val asked = backgroundScope.asksArrivingAt(n)
            exchange.want(PICTURE_HASH)
            assertEquals("nothing kept: an ask for the whole file, byte-identical to an older build's", listOf(PICTURE_HASH to null), asked)
            assertTrue(partials.keep(HASH_H, cutFile(ByteArray(300)), basedOn = null)) // a link was cut 300 B in

            exchange.want(HASH_H)
            now += BlobExchange.ROUND_MS
            exchange.reask() // the 60 s tick, a round later
            assertEquals(listOf(PICTURE_HASH to null, HASH_H to 300L, PICTURE_HASH to null, HASH_H to 300L), asked)
        }

    @Test
    fun aServeStreamsFromTheAskersOffsetUpToTheWholeLength() =
        runTest(UnconfinedTestDispatcher()) {
            val server = FakeLoopTransport("a")
            val requester = FakeLoopTransport("b")
            server.connect(requester)
            val store = FakeBlobStore(Files.createTempDirectory("blob-resume-serve").toFile())
            store.seed("H", "image/jpeg", "image".toByteArray()) // five bytes
            var now = 0L
            val exchange =
                BlobExchange(
                    transport = server,
                    store = store,
                    selfId = { "a" },
                    onObtained = { _, _ -> },
                    now = { now },
                    partials = partials(),
                )
            listOf(2L, 5L, 6L, 0L, -1L, null).forEach { asked ->
                exchange.onRequest("H", "b", asked)
                now += BlobExchange.SERVE_MEMO_MS // each ask a fresh one
            }
            assertEquals(
                "a resume, an empty tail for a prefix already complete, and the peer's nonsense as the whole file",
                listOf(2L, 5L, 0L, 0L, 0L, 0L),
                server.filesSent.map { it.offset },
            )
            assertEquals(FileMeta(FileKind.ATTACHMENT, "H", "image/jpeg", offset = 2L), server.filesSent.first())
        }

    @Test
    fun aSpliceThatFailsItsHashDropsThePrefixAndOpensARoundForTheWholeBlobAtOnce() =
        runTest(UnconfinedTestDispatcher()) {
            val r = FakeLoopTransport("r")
            val n1 = FakeLoopTransport("n1")
            val n2 = FakeLoopTransport("n2")
            r.connect(n1)
            r.connect(n2)
            val store = FakeBlobStore(Files.createTempDirectory("blob-splice-bad").toFile()).apply { checkHashes = true }
            val partials = partials()
            var refused = 0
            var now = 0L
            val exchange =
                BlobExchange(
                    transport = r,
                    store = store,
                    selfId = { "r" },
                    onObtained = { _, _ -> },
                    now = { now },
                    partials = partials,
                    onSpliceRefused = { refused++ },
                )
            val asked1 = backgroundScope.asksArrivingAt(n1)
            val asked2 = backgroundScope.asksArrivingAt(n2)
            exchange.want(PICTURE_HASH)
            assertTrue(partials.keep(PICTURE_HASH, cutFile(ByteArray(100)), basedOn = null)) // a bad prefix
            asked1.clear()
            asked2.clear()

            exchange.onReceived(PICTURE_HASH, "image/jpeg", cutFile(ByteArray(PICTURE.size)).absolutePath, resumedFrom = 100)

            assertEquals(1, refused)
            assertEquals("the prefix may be what was wrong", 0L, partials.length(PICTURE_HASH))
            assertEquals("asked again from byte 0, now, inside the first ask's grace", listOf(PICTURE_HASH to null), asked1)
            assertTrue("a new round opens with one ask", asked2.isEmpty())
            now += BlobExchange.FIRST_ASK_GRACE_MS
            exchange.reask()
            assertEquals(listOf(PICTURE_HASH to null), asked2)
            assertTrue("still wanted", !store.has(PICTURE_HASH))
        }

    @Test
    fun aFailedSpliceStaysQuietWhileTheBlobIsArrivingOrWhenNoPrefixWasLeft() =
        runTest(UnconfinedTestDispatcher()) {
            val r = FakeLoopTransport("r")
            val n = FakeLoopTransport("n")
            r.connect(n)
            val store = FakeBlobStore(Files.createTempDirectory("blob-splice-quiet").toFile()).apply { checkHashes = true }
            val partials = partials()
            val exchange =
                BlobExchange(
                    transport = r,
                    store = store,
                    selfId = { "r" },
                    onObtained = { _, _ -> },
                    partials = partials,
                )
            val asked = backgroundScope.asksArrivingAt(n)
            val bad = { cutFile(ByteArray(PICTURE.size)).absolutePath }

            partials.keep(PICTURE_HASH, cutFile(ByteArray(100)), basedOn = null)
            r.arriving += PICTURE_HASH // another link is streaming it in right now (ADR 2026-09.4tx5)
            exchange.onReceived(PICTURE_HASH, "image/jpeg", bad(), resumedFrom = 100)
            assertEquals("the prefix still goes", 0L, partials.length(PICTURE_HASH))
            assertTrue("but nobody is asked while it arrives", asked.isEmpty())

            r.arriving.clear()
            exchange.onReceived(PICTURE_HASH, "image/jpeg", bad(), resumedFrom = 100)
            assertTrue("no live prefix dropped, so no ask a peer could trigger at will", asked.isEmpty())
        }

    @Test
    fun aWholeFileThatFailsItsHashLeavesTheKeptPrefixAlone() =
        runTest(UnconfinedTestDispatcher()) {
            val r = FakeLoopTransport("r")
            val n = FakeLoopTransport("n")
            r.connect(n)
            val store = FakeBlobStore(Files.createTempDirectory("blob-whole-bad").toFile()).apply { checkHashes = true }
            val partials = partials()
            val exchange =
                BlobExchange(
                    transport = r,
                    store = store,
                    selfId = { "r" },
                    onObtained = { _, _ -> },
                    partials = partials,
                )
            val asked = backgroundScope.asksArrivingAt(n)
            partials.keep(PICTURE_HASH, cutFile(PICTURE.copyOf(100)), basedOn = null)

            exchange.onReceived(PICTURE_HASH, "image/jpeg", cutFile(ByteArray(PICTURE.size)).absolutePath)
            assertEquals("the holder's bytes were wrong, not ours", 100L, partials.length(PICTURE_HASH))
            assertTrue("the tick asks again, as it always has", asked.isEmpty())
        }

    @Test
    fun aStoredBlobOrOneObtainedOffMeshDropsItsKeptPrefix() =
        runTest(UnconfinedTestDispatcher()) {
            val r = FakeLoopTransport("r")
            val n = FakeLoopTransport("n")
            r.connect(n)
            val store = FakeBlobStore(Files.createTempDirectory("blob-landed").toFile()).apply { checkHashes = true }
            val partials = partials()
            val exchange =
                BlobExchange(
                    transport = r,
                    store = store,
                    selfId = { "r" },
                    onObtained = { _, _ -> },
                    partials = partials,
                )

            partials.keep(PICTURE_HASH, cutFile(PICTURE.copyOf(100)), basedOn = null)
            exchange.onReceived(PICTURE_HASH, "image/jpeg", cutFile(PICTURE).absolutePath, resumedFrom = 100)
            assertTrue(store.has(PICTURE_HASH))
            assertEquals("landed: nothing left to resume", 0L, partials.length(PICTURE_HASH))

            // The spool delivered another one while a prefix was kept: the next re-ask finds it held.
            val other = "photo two".toByteArray()
            val otherHash = sha256Hex(other)
            exchange.want(otherHash)
            partials.keep(otherHash, cutFile(other.copyOf(4)), basedOn = null)
            store.seed(otherHash, "image/jpeg", other)
            exchange.reask()
            assertEquals(0L, partials.length(otherHash))
        }

    // --- Fast-only wants: Wi-Fi Aware or nothing until the user's tap (ADR 2026-10.js2p) ---

    /** Collects every blob request [peer] receives, whole. */
    private fun CoroutineScope.requestsArrivingAt(peer: FakeLoopTransport): MutableList<BlobReqContent> {
        val asked = CopyOnWriteArrayList<BlobReqContent>()
        launch {
            peer.inbound.collect { f ->
                if (f.envelope.type == FrameType.BLOB_REQ) WireCodec.decodePayload<BlobReqContent>(f.envelope.payload)?.let { asked += it }
            }
        }
        return asked
    }

    private fun exchangeOn(
        t: FakeLoopTransport,
        store: FakeBlobStore = FakeBlobStore(Files.createTempDirectory("blob-fast").toFile()),
        now: () -> Long = { 0L },
        metered: () -> Boolean = { false },
        onEvent: (BlobExchange.BlobEvent) -> Unit = {},
    ) = BlobExchange(
        transport = t,
        store = store,
        selfId = { t.nodeId },
        onObtained = { _, _ -> },
        now = now,
        partials = partials(),
        onEvent = onEvent,
        metered = metered,
    )

    @Test
    fun aFastOnlyWantAsksNoNeighbourThatWouldSendOverBluetoothUntilAPlainWantLiftsIt() =
        runTest(UnconfinedTestDispatcher()) {
            val r = FakeLoopTransport("r")
            val author = FakeLoopTransport("author").also(r::connect)
            val other = FakeLoopTransport("other").also(r::connect)
            var now = 0L
            val exchange = exchangeOn(r, now = { now })
            val toAuthor = backgroundScope.requestsArrivingAt(author)
            val toOther = backgroundScope.requestsArrivingAt(other)

            exchange.want("H", author = "author", fastOnly = true)
            now += BlobExchange.FIRST_ASK_GRACE_MS + CompositeMeshTransport.BULK_GRACE_MS
            exchange.reask()
            now += BlobExchange.ROUND_MS
            exchange.reask()
            assertTrue("no blind ask, no widening, no pull over Bluetooth", toAuthor.isEmpty() && toOther.isEmpty())
            assertTrue("nothing armed", r.bulkArmed.isEmpty())

            exchange.want("H", author = "author") // the user's tap
            assertEquals("asked at once, of the author, plain", listOf(BlobReqContent("H", pull = BlobExchange.PULL_BUDGET)), toAuthor)
            assertTrue(toOther.isEmpty())
        }

    @Test
    fun aFastOnlyWantAsksOnlyTheNeighboursWhoseCopyWouldRideWifiAwareAndSaysSo() =
        runTest(UnconfinedTestDispatcher()) {
            val r = FakeLoopTransport("r")
            val author = FakeLoopTransport("author").also(r::connect)
            val fast = FakeLoopTransport("fast").also(r::connect)
            val reach = FakeLoopTransport("reach").also(r::connect)
            r.bulkRoutes["fast"] = BulkRoute.Linked
            r.bulkRoutes["reach"] = BulkRoute.Reachable
            var now = 0L
            val exchange = exchangeOn(r, now = { now })
            val toAuthor = backgroundScope.requestsArrivingAt(author)
            val toFast = backgroundScope.requestsArrivingAt(fast)
            val toReach = backgroundScope.requestsArrivingAt(reach)

            exchange.want("H", author = "author", fastOnly = true)
            assertEquals(
                "the fast-linked neighbour first, the author passed over",
                listOf(BlobReqContent("H", fastOnly = true, pull = BlobExchange.PULL_BUDGET)),
                toFast,
            )
            assertTrue(toReach.isEmpty())

            now += BlobExchange.FIRST_ASK_GRACE_MS
            exchange.reask()
            assertEquals(
                "widened to the reachable one, fast-only",
                listOf(BlobReqContent("H", fastOnly = true, pull = BlobExchange.PULL_BUDGET)),
                toReach,
            )
            assertEquals("and armed toward it: no Bluetooth copy to fall back on", listOf("reach"), r.bulkArmed)
            now += BlobExchange.ROUND_MS
            exchange.reask()
            assertTrue("the Bluetooth-only author is never asked", toAuthor.isEmpty())
        }

    @Test
    fun aNeighboursPlainAskNeverLiftsOurFastOnlyWant() =
        runTest(UnconfinedTestDispatcher()) {
            val r = FakeLoopTransport("r")
            val author = FakeLoopTransport("author").also(r::connect)
            val asker = FakeLoopTransport("asker").also(r::connect)
            val exchange = exchangeOn(r)
            val toAuthor = backgroundScope.requestsArrivingAt(author)

            exchange.want("H", author = "author", fastOnly = true)
            exchange.onRequest("H", "asker") // a neighbour asks us for it, plain; we lack it
            exchange.reask()
            assertTrue(toAuthor.isEmpty())
        }

    @Test
    fun aPullThroughForAFastOnlyAskIsFastOnlyItself() =
        runTest(UnconfinedTestDispatcher()) {
            val h = FakeLoopTransport("h")
            val source = FakeLoopTransport("source").also(h::connect)
            val asker = FakeLoopTransport("asker").also(h::connect)
            h.bulkRoutes["asker"] = BulkRoute.Linked
            val exchange = exchangeOn(h)
            val toSource = backgroundScope.requestsArrivingAt(source)

            exchange.onRequest("H", "asker", fastOnly = true, pull = BlobExchange.PULL_BUDGET) // we lack it: pull it through
            exchange.reask()
            assertTrue("the Bluetooth-only source is not asked on its behalf", toSource.isEmpty())

            h.bulkRoutes["source"] = BulkRoute.Linked
            exchange.reask()
            assertEquals(
                "one hop less of the asker's budget",
                listOf(
                    BlobReqContent(
                        "H",
                        fastOnly = true,
                        pull =
                            BlobExchange.PULL_BUDGET - 1,
                    ),
                ),
                toSource,
            )
        }

    @Test
    fun aHolderServesAFastOnlyAskOnlyOverAWifiAwareRouteAndForgetsACopyTheTransportDropped() =
        runTest(UnconfinedTestDispatcher()) {
            val h = FakeLoopTransport("h")
            val n = FakeLoopTransport("n").also(h::connect)
            val store =
                FakeBlobStore(
                    Files.createTempDirectory("blob-fast-serve").toFile(),
                ).apply { seed(PICTURE_HASH, "image/jpeg", PICTURE) }
            val exchange = exchangeOn(h, store)

            exchange.onRequest(PICTURE_HASH, "n", fastOnly = true)
            assertTrue("no Wi-Fi Aware route to the asker: refused, nothing handed to the link", h.filesSent.isEmpty())

            h.bulkRoutes["n"] = BulkRoute.Reachable
            exchange.onRequest(PICTURE_HASH, "n", fastOnly = true)
            val meta = h.filesSent.single()
            assertTrue("marked for the transport, which never hands it to Bluetooth", meta.fastOnly)
            exchange.onRequest(PICTURE_HASH, "n", fastOnly = true)
            assertEquals("a re-ask inside the memo ships nothing", 1, h.filesSent.size)

            checkNotNull(meta.onDropped).invoke() // the fast link never came up: the composite dropped it
            exchange.onRequest(PICTURE_HASH, "n", fastOnly = true)
            assertEquals("forgotten, so the next ask is served at once", 2, h.filesSent.size)

            exchange.onRequest(PICTURE_HASH, "n", offset = null, fastOnly = false)
            assertEquals("a plain ask meets the same memo", 2, h.filesSent.size)
        }

    // --- A contact's direct link is a fast route from an unmetered network (ADR 2026-10.js2p, a5yj) ---

    @Test
    fun aFastOnlyWantWhoseOnlyHolderIsOnAnUnmeteredDirectLinkAsksItFastOnly() =
        runTest(UnconfinedTestDispatcher()) {
            val r = FakeLoopTransport("r")
            val link = FakeLoopTransport("l").also(r::connect)
            val bt = FakeLoopTransport("bt").also(r::connect)
            r.internetOnly += "l"
            var now = 10L
            val exchange = exchangeOn(r, now = { now })
            val toLink = backgroundScope.requestsArrivingAt(link)
            val toBt = backgroundScope.requestsArrivingAt(bt)

            exchange.want("H", author = "l", fastOnly = true)
            assertEquals(listOf(BlobReqContent("H", fastOnly = true, pull = BlobExchange.PULL_BUDGET)), toLink)
            assertTrue("the Bluetooth neighbour is never asked", toBt.isEmpty())
            assertTrue("a link carries it: nothing armed", r.bulkArmed.isEmpty())
            now = 20L // read later than the step, so the read's own first look cannot be what dated it
            assertEquals("linkable from its first step", 10L, exchange.linkableSince("H"))
        }

    /**
     * The blobs-reviewer's finding: a link-only holder that Wi-Fi Aware also sights (`Reachable`) serves a fast-only ask
     * over the link (`CompositeMeshTransport.sendFastOnly` takes the link before it arms), so a metered asker must not ask
     * it, and nobody arms Wi-Fi Aware toward it either way.
     */
    @Test
    fun aLinkOnlyHolderWifiAwareSightsIsAskedOnlyUnmeteredAndNeverArmed() =
        runTest(UnconfinedTestDispatcher()) {
            for (metered in listOf(true, false)) {
                val r = FakeLoopTransport("r")
                val link = FakeLoopTransport("l").also(r::connect)
                r.internetOnly += "l"
                r.bulkRoutes["l"] = BulkRoute.Reachable
                var now = 0L
                val exchange = exchangeOn(r, now = { now }, metered = { metered })
                val toLink = backgroundScope.requestsArrivingAt(link)

                exchange.want("H", author = "l", fastOnly = true)
                now += BlobExchange.FIRST_ASK_GRACE_MS + CompositeMeshTransport.BULK_GRACE_MS
                exchange.reask()
                if (metered) {
                    assertTrue("metered: no ask over the link", toLink.isEmpty())
                } else {
                    assertEquals(BlobReqContent("H", fastOnly = true, pull = BlobExchange.PULL_BUDGET), toLink.first())
                }
                assertTrue("metered=$metered: nothing armed", r.bulkArmed.isEmpty())
            }
        }

    /**
     * A receiver with no neighbour at all gets no step (the re-offer tick re-arms and re-asks only with neighbours), yet it
     * is the one whose want must raise its own link (L-7-9): the read itself is the first unmetered look (the coordinator's
     * `LinkDownloadLimitLabTest.aWantHeldWhileMeteredRaisesItsOwnLinkOnceTheNetworkIsUnmetered`).
     */
    @Test
    fun aFastOnlyWantReadUnmeteredWithNoStepIsLinkableFromThatRead() =
        runTest(UnconfinedTestDispatcher()) {
            val r = FakeLoopTransport("r") // no neighbours
            var now = 0L
            var metered = true
            val exchange = exchangeOn(r, now = { now }, metered = { metered })

            exchange.want("H", author = "a", fastOnly = true)
            now = 1_000L
            assertEquals("metered: none", null, exchange.linkableSince("H"))

            now = 2_000L
            metered = false // no step follows
            now = 3_000L
            assertEquals("the first unmetered read sets it", 3_000L, exchange.linkableSince("H"))
            now = 4_000L
            assertEquals("and later reads keep it", 3_000L, exchange.linkableSince("H"))

            metered = true
            assertEquals("a metered read clears it", null, exchange.linkableSince("H"))
        }

    @Test
    fun aMeteredFastOnlyWantIsHeldUntilTheTapWhichAsksTheLinkPeerPlain() =
        runTest(UnconfinedTestDispatcher()) {
            val r = FakeLoopTransport("r")
            val link = FakeLoopTransport("l").also(r::connect)
            r.internetOnly += "l"
            var now = 0L
            val events = CopyOnWriteArrayList<BlobExchange.BlobEvent>()
            val exchange = exchangeOn(r, now = { now }, metered = { true }, onEvent = { events += it })
            val toLink = backgroundScope.requestsArrivingAt(link)

            exchange.want("H", author = "l", fastOnly = true)
            now += BlobExchange.ROUND_MS
            exchange.reask()
            assertTrue("nobody asked over a metered link", toLink.isEmpty())
            assertTrue(BlobExchange.BlobEvent.FastOnlyHeld in events)
            assertEquals("held: no link may carry it", null, exchange.linkableSince("H"))

            now += 1_000
            exchange.want("H", author = "l", approved = true) // the tap
            assertEquals("asked at once, plain", listOf(BlobReqContent("H", pull = BlobExchange.PULL_BUDGET)), toLink)
            assertEquals("linkable from the tap", now, exchange.linkableSince("H"))
        }

    @Test
    fun anOutstandingFetchOnALinkPeerIsNotLetGoWhenThePhoneTurnsMetered() =
        runTest(UnconfinedTestDispatcher()) {
            val r = FakeLoopTransport("r")
            val link = FakeLoopTransport("l").also(r::connect)
            r.internetOnly += "l"
            var now = 0L
            var metered = false
            val events = CopyOnWriteArrayList<BlobExchange.BlobEvent>()
            val exchange = exchangeOn(r, now = { now }, metered = { metered }, onEvent = { events += it })
            val toLink = backgroundScope.requestsArrivingAt(link)

            exchange.want("H", author = "l", fastOnly = true)
            assertEquals(1, toLink.size)

            metered = true // the phone moved to cellular while the ask stands
            now += 1_000
            exchange.reask()
            assertTrue("not let go for the metered read: $events", BlobExchange.BlobEvent.FastOnlyHeld !in events)

            r.arrivingFrom += "l" // its file is streaming in
            now += BlobExchange.FIRST_ASK_GRACE_MS + CompositeMeshTransport.BULK_GRACE_MS
            exchange.reask()
            assertTrue("runs to its end, never held: $events", BlobExchange.BlobEvent.FastOnlyHeld !in events)
            assertTrue(BlobExchange.BlobEvent.CancelSent !in events && BlobExchange.BlobEvent.Fallback !in events)
            assertEquals("asked once", 1, toLink.size)
        }

    @Test
    fun aHolderServesAFastOnlyAskFromALinkPeerWhateverItsOwnMeteringButNotOneFromBluetooth() =
        runTest(UnconfinedTestDispatcher()) {
            val h = FakeLoopTransport("h")
            FakeLoopTransport("l").also(h::connect)
            FakeLoopTransport("b").also(h::connect)
            h.internetOnly += "l"
            val store =
                FakeBlobStore(
                    Files.createTempDirectory("blob-link-serve").toFile(),
                ).apply { seed(PICTURE_HASH, "image/jpeg", PICTURE) }
            val events = CopyOnWriteArrayList<BlobExchange.BlobEvent>()
            val exchange = exchangeOn(h, store, metered = { true }, onEvent = { events += it })

            exchange.onRequest(PICTURE_HASH, "l", fastOnly = true)
            assertTrue("served over the link, marked fast-only", h.filesSent.single().fastOnly)

            exchange.onRequest(PICTURE_HASH, "b", fastOnly = true)
            assertEquals("a Bluetooth-only asker is still refused", 1, h.filesSent.size)
            assertEquals(listOf(BlobExchange.BlobEvent.FastOnlyRefused), events.filter { it == BlobExchange.BlobEvent.FastOnlyRefused })
        }

    @Test
    fun linkableSinceIsTheRecordTheTapTheFirstUnmeteredStepAndTheReArmAfterASweep() =
        runTest(UnconfinedTestDispatcher()) {
            val r = FakeLoopTransport("r")
            var now = 100L
            var metered = false
            val exchange = exchangeOn(r, now = { now }, metered = { metered })

            // Each time is read later than what set it, so the read's own first look (`linkableSince` notes it too) never
            // stands in for the record, the tap or the step under test.
            exchange.want("P", author = "a") // plain
            now = 150L
            assertEquals("a plain want: its record", 100L, exchange.linkableSince("P"))
            now = 200L
            exchange.wantAll(listOf("P" to "a")) // the tick's re-arm of a standing want moves nothing
            assertEquals(100L, exchange.linkableSince("P"))

            metered = true
            exchange.want("F", author = "a", fastOnly = true)
            assertEquals("fast-only and metered: none", null, exchange.linkableSince("F"))
            now = 300L
            exchange.want("F", author = "a", approved = true)
            now = 350L
            assertEquals("the tap", 300L, exchange.linkableSince("F"))

            exchange.want("G", author = "a", fastOnly = true)
            assertEquals(null, exchange.linkableSince("G"))
            now = 400L
            metered = false
            exchange.reask() // the first ordinary step that reads the network unmetered
            now = 450L
            assertEquals("fast-only: the first step that sees this phone unmetered", 400L, exchange.linkableSince("G"))
            now = 500L
            exchange.reask()
            assertEquals("later steps keep it", 400L, exchange.linkableSince("G"))

            now = 500L + BlobExchange.FETCH_TTL_MS + 1
            exchange.sweepExpired()
            assertEquals("swept: not wanted", null, exchange.linkableSince("P"))
            exchange.wantAll(listOf("P" to "a")) // the database re-arm makes it afresh
            val rearmedAt = now
            now += 1
            assertEquals("set again at the re-arm", rearmedAt, exchange.linkableSince("P"))
        }

    @Test
    fun aTapIsNotUndoneByAWantThatReadItsRowsBeforeIt() =
        runTest(UnconfinedTestDispatcher()) {
            // ADR 2026-10.js2p's review: a re-arm or a delivery that read the rows before the tap, and wants after it,
            // must not hold the fetch again — that ended the tap's ask and withdrew it.
            val r = FakeLoopTransport("r")
            val author = FakeLoopTransport("author").also(r::connect)
            var now = 0L
            val exchange = exchangeOn(r, now = { now })
            val toAuthor = backgroundScope.requestsArrivingAt(author)

            exchange.want("H", author = "author", fastOnly = true)
            exchange.want("H", author = "author", approved = true) // the tap
            assertEquals(listOf(BlobReqContent("H", pull = BlobExchange.PULL_BUDGET)), toAuthor)

            exchange.wantAll(listOf("H" to "author"), fastOnly = setOf("H")) // stale: read before the tap
            exchange.want("H", author = "author", fastOnly = true) // a delivery that read its row before the tap
            now += BlobExchange.FIRST_ASK_GRACE_MS + BlobExchange.ROUND_MS
            exchange.reask()
            assertEquals(
                "still fetched plain, over Bluetooth",
                listOf(BlobReqContent("H", pull = BlobExchange.PULL_BUDGET), BlobReqContent("H", pull = BlobExchange.PULL_BUDGET)),
                toAuthor,
            )
        }

    @Test
    fun aTapIsStickyAgainstAStaleWantWithItsAskOutstanding() =
        runTest(UnconfinedTestDispatcher()) {
            // The mesh-lab review: with the tap's ask standing on a holder that said it holds the blob, a stale fast-only
            // want — a re-arm or a delivery that read its rows before the tap — once ended that ask and withdrew it.
            val r = FakeLoopTransport("r")
            val author = FakeLoopTransport("author").apply { advertises = Protocol.CAP_BLOB_HAVE }.also(r::connect)
            val events = CopyOnWriteArrayList<BlobExchange.BlobEvent>()
            val exchange =
                BlobExchange(
                    transport = r,
                    store = FakeBlobStore(Files.createTempDirectory("blob-sticky").toFile()),
                    selfId = { "r" },
                    onObtained = { _, _ -> },
                    now = { 0L },
                    partials = partials(),
                    holderQuery = BlobExchange.HolderQuery(enabled = true),
                    onEvent = { events += it },
                )
            val queries = CopyOnWriteArrayList<String>()
            backgroundScope.launch { author.inbound.collect { if (it.envelope.type == FrameType.BLOB_HAVE) queries += it.envelope.id } }
            val toAuthor = backgroundScope.requestsArrivingAt(author)

            exchange.want("H", author = "author", fastOnly = true) // over the limit, Bluetooth only: held
            exchange.want("H", author = "author", approved = true) // the tap: the author is queried
            exchange.onHaveAnswer(
                BlobHasContent(listOf(BlobHasItem("H", BlobHasItem.HAVE, 2_000_000L)), load = 0, re = queries.single()),
                "author",
            )
            assertEquals("the tap's ask, standing on the holder", listOf(BlobReqContent("H", pull = BlobExchange.PULL_BUDGET)), toAuthor)

            exchange.wantAll(listOf("H" to "author"), fastOnly = setOf("H"))
            exchange.want("H", author = "author", fastOnly = true)
            assertTrue("nothing withdrew it: $events", BlobExchange.BlobEvent.CancelSent !in events)
            assertTrue("and nothing ended it", BlobExchange.BlobEvent.Fallback !in events)
        }

    @Test
    fun aNonHoldersFlappingFastLinkNeverResetsTheBackoffOnAReachableHolder() =
        runTest(UnconfinedTestDispatcher()) {
            // Round 3 of the review: Wi-Fi Aware links flap on every custody sync. A neighbour's data path coming up is an
            // edge for that neighbour alone — it must not open a fresh cycle that re-asks, and re-arms toward, a reachable
            // holder whose link never comes up.
            val r = FakeLoopTransport("r")
            val reach = FakeLoopTransport("reach").also(r::connect)
            val sync = FakeLoopTransport("sync").also(r::connect)
            r.bulkRoutes["reach"] = BulkRoute.Reachable
            var now = 0L
            val exchange = exchangeOn(r, now = { now })
            val toReach = backgroundScope.requestsArrivingAt(reach)

            exchange.want("H", author = "reach", fastOnly = true)
            repeat(10) {
                now += 30_000L
                r.bulkRoutes["sync"] = BulkRoute.Linked // a custody sync's data path up…
                exchange.fastLinkUp(listOf("sync"))
                now += 30_000L
                r.bulkRoutes.remove("sync") // …and down again, then the tick
                exchange.reask()
            }
            assertTrue("asked within the backoff over 10 min of flapping: ${toReach.size}", toReach.size <= 4)

            val before = toReach.size
            r.bulkRoutes["reach"] = BulkRoute.Linked // the holder itself links
            exchange.fastLinkUp(listOf("reach"))
            assertEquals("asked at once over it", before + 1, toReach.size)
        }

    @Test
    fun aFastLinkThatDropsAndReturnsBetweenTwoStepsIsStillAnEdge() =
        runTest(UnconfinedTestDispatcher()) {
            // Round 4 of the review: links flap per custody sync, so a down→up between two steps leaves both snapshots
            // linked. The watcher's own edge must still ask the holder again.
            val r = FakeLoopTransport("r")
            val h = FakeLoopTransport("h").also(r::connect)
            r.bulkRoutes["h"] = BulkRoute.Linked
            var now = 0L
            val exchange = exchangeOn(r, now = { now })
            val toH = backgroundScope.requestsArrivingAt(h)

            exchange.want("H", author = "h", fastOnly = true)
            now += BlobExchange.FIRST_ASK_GRACE_MS + 1
            exchange.reask() // h brought nothing over its link: spent for this cycle
            exchange.reask()
            assertEquals(1, toH.size)

            r.bulkRoutes.remove("h") // the link drops…
            r.bulkRoutes["h"] = BulkRoute.Linked // …and comes back, with no step between
            exchange.fastLinkUp(setOf("h"))
            assertEquals("asked again within that step", 2, toH.size)
        }

    @Test
    fun aLandedFileTakesItsWantBeforeLeavingTheLinkSoARacingStepWithdrawsNothing() =
        runTest(UnconfinedTestDispatcher()) {
            // Round 3 of the review (R4): a step between the file leaving the link's landing ones and the want's removal
            // found the blob held and no longer arriving, took the want itself and withdrew every ask — the serving
            // holder's included, a stray `blobcancel` after the blob was held.
            val r = FakeLoopTransport("r")
            val h = FakeLoopTransport("h").apply { advertises = Protocol.CAP_BLOB_HAVE }.also(r::connect)
            val events = CopyOnWriteArrayList<BlobExchange.BlobEvent>()
            val exchange =
                BlobExchange(
                    transport = r,
                    store = FakeBlobStore(Files.createTempDirectory("blob-landed-order").toFile()).apply { checkHashes = true },
                    selfId = { "r" },
                    onObtained = { _, _ -> },
                    now = { 0L },
                    partials = partials(),
                    holderQuery = BlobExchange.HolderQuery(enabled = true),
                    onEvent = { events += it },
                )
            val queries = CopyOnWriteArrayList<String>()
            backgroundScope.launch { h.inbound.collect { if (it.envelope.type == FrameType.BLOB_HAVE) queries += it.envelope.id } }
            val toH = backgroundScope.requestsArrivingAt(h)

            exchange.want(PICTURE_HASH, author = "h")
            exchange.onHaveAnswer(
                BlobHasContent(listOf(BlobHasItem(PICTURE_HASH, BlobHasItem.HAVE, PICTURE.size.toLong())), re = queries.single()),
                "h",
            )
            assertEquals(1, toH.size)

            r.arriving += PICTURE_HASH // the copy streams in, then lands
            r.afterTaken = { backgroundScope.launch { exchange.reask() } } // a tick right after it leaves the link
            exchange.onReceived(PICTURE_HASH, "image/jpeg", cutFile(PICTURE).absolutePath, from = "h")

            assertTrue("nothing withdrawn from the holder that served it: $events", BlobExchange.BlobEvent.CancelSent !in events)
            assertEquals("and nothing asked again", 1, toH.size)
        }

    @Test
    fun aHolderThatGainsTheFastLinkIsAskedAgainAtOnceWithinTheCycle() =
        runTest(UnconfinedTestDispatcher()) {
            // The blobs review: a holder already linked over Bluetooth that gains the Wi-Fi Aware link changes no
            // neighbour set, so only the edge itself ([BlobExchange.fastLinkUp]) can ask it again before the cycle ends.
            val r = FakeLoopTransport("r")
            val a = FakeLoopTransport("a").also(r::connect)
            val b = FakeLoopTransport("b").also(r::connect)
            r.bulkRoutes["a"] = BulkRoute.Reachable
            r.bulkRoutes["b"] = BulkRoute.Reachable
            var now = 0L
            val exchange = exchangeOn(r, now = { now })
            val toB = backgroundScope.requestsArrivingAt(b)

            exchange.want("H", author = "a", fastOnly = true) // a first, alone
            now += BlobExchange.FIRST_ASK_GRACE_MS + CompositeMeshTransport.BULK_GRACE_MS + 1
            exchange.reask() // a brought nothing: the round widens to b
            assertEquals(1, toB.size)
            exchange.reask()
            assertEquals("asked once this cycle", 1, toB.size)

            r.bulkRoutes["b"] = BulkRoute.Linked // b's data path came up, between ticks
            exchange.fastLinkUp(listOf("b"))
            assertEquals("asked again over it, at once", 2, toB.size)
            assertTrue(toB.all { it.fastOnly == true })
        }

    @Test
    fun aFastOnlyWantWhoseReachableHolderNeverLinksBacksOffUntilTheLinkComesUp() =
        runTest(UnconfinedTestDispatcher()) {
            // ADR 2026-10.js2p's review: a reachable holder whose Wi-Fi Aware link never comes up was asked, armed and
            // raised every cycle until the tap. A fast-only cycle that asked and still wants the blob is dead: 45 s,
            // then 90 s, 3 min, 6 min. A link coming up opens a fresh cycle at once.
            val r = FakeLoopTransport("r")
            val reach = FakeLoopTransport("reach").also(r::connect)
            r.bulkRoutes["reach"] = BulkRoute.Reachable
            var now = 0L
            val exchange = exchangeOn(r, now = { now })
            val toReach = backgroundScope.requestsArrivingAt(reach)

            exchange.want("H", author = "reach", fastOnly = true)
            repeat(11) {
                now += 60_000L // the re-offer tick
                exchange.reask()
            }
            assertEquals("asked at 0 s, 60 s, 180 s and 360 s — not every tick", 4, toReach.size)
            assertTrue(toReach.all { it.fastOnly == true })

            r.bulkRoutes["reach"] = BulkRoute.Linked // the link came up
            exchange.reask()
            assertEquals("asked at once over it", 5, toReach.size)
        }

    @Test
    fun liftingAFastOnlyWantAsksAgainAtOnceTheHolderItGaveUpOn() =
        runTest(UnconfinedTestDispatcher()) {
            val r = FakeLoopTransport("r")
            val reach = FakeLoopTransport("reach").also(r::connect)
            r.bulkRoutes["reach"] = BulkRoute.Reachable
            var now = 0L
            val exchange = exchangeOn(r, now = { now })
            val toReach = backgroundScope.requestsArrivingAt(reach)

            exchange.want("H", author = "reach", fastOnly = true)
            repeat(4) {
                now += 60_000L
                exchange.reask()
            }
            val before = toReach.size
            exchange.want("H", author = "reach", approved = true) // the tap, deep in a backoff
            assertEquals("a fresh plain cycle, at once", before + 1, toReach.size)
            assertEquals(BlobReqContent("H", pull = BlobExchange.PULL_BUDGET), toReach.last())
        }

    // --- A free direct link beside a radio (L-7-11, #236) ---

    /** A direct link's rule for blobs, as `LinkPairFilter` reads it: admitted when it is one of [blobs]. */
    private class BlobsView(
        private val blobs: Set<String>,
    ) : PairView {
        override fun admits(env: RelayEnvelope): Boolean = true

        override suspend fun admitsBlob(hash: String): Boolean = hash in blobs
    }

    /**
     * The design pass's first finding: a Bluetooth holder with no Wi-Fi Aware route may raise a free link for a large copy
     * and wait up to the composite's bulk grace for it. An asker that widened at the plain grace bought a carrier's
     * Bluetooth copy and then the holder's own, late (#79's shape). The blind round waits that out when a free link may
     * come, and only then.
     */
    @Test
    fun aBlindAskOfAHolderThatMayRaiseAFreeLinkWaitsOutItsLinkWaitBeforeTheRoundWidens() =
        runTest(UnconfinedTestDispatcher()) {
            for (may in listOf(true, false)) {
                val r = FakeLoopTransport("r")
                val h = FakeLoopTransport("h").also(r::connect)
                val c = FakeLoopTransport("c").also(r::connect)
                if (may) r.freeLinkMays += "h"
                var now = 0L
                val exchange = exchangeOn(r, now = { now })
                val toH = backgroundScope.requestsArrivingAt(h)
                val toC = backgroundScope.requestsArrivingAt(c)

                exchange.want("H", author = "h")
                assertEquals("may=$may: the author first, alone", 1, toH.size)
                assertTrue(toC.isEmpty())
                now += BlobExchange.FIRST_ASK_GRACE_MS + 1
                exchange.reask()
                assertEquals("may=$may: widened at the plain grace only with no link to wait for", if (may) 0 else 1, toC.size)
                now += CompositeMeshTransport.BULK_GRACE_MS
                exchange.reask()
                assertEquals("may=$may: widened past the link wait", 1, toC.size)
            }
        }

    /**
     * The blobs-reviewer's full pass: the wait is the pair's to decide, never the asker's own state. An asker whose own
     * network is metered (or whose links are off, or backing off) still gives a holder the pair lets raise a free link its
     * link wait, since that holder raises on its own reading and waits whether or not we answer; a holder whose link
     * would not carry this blob gets none, for this blob only.
     */
    @Test
    fun theLinkWaitIsThePairsPerBlobAndNeverTheAskersOwnState() =
        runTest(UnconfinedTestDispatcher()) {
            val r = FakeLoopTransport("r")
            val h = FakeLoopTransport("h").also(r::connect)
            val c = FakeLoopTransport("c").also(r::connect)
            r.freeLinkMays += "h"
            r.linkViews["h"] = BlobsView(setOf("H"))
            var now = 0L
            val exchange = exchangeOn(r, now = { now }, metered = { true })
            val toC = backgroundScope.requestsArrivingAt(c)

            exchange.want("H", author = "h")
            exchange.want("G", author = "h")
            assertEquals("read per blob", setOf("H", "G"), r.freeLinkMayReads.filter { it.first == "h" }.mapTo(HashSet()) { it.second })
            now += BlobExchange.FIRST_ASK_GRACE_MS + 1
            exchange.reask()
            assertEquals("only the blob the link would not carry widens at the plain grace", listOf("G"), toC.map { it.hash })
            now += CompositeMeshTransport.BULK_GRACE_MS
            exchange.reask()
            assertEquals("the carried one past the link wait, metered or not", setOf("G", "H"), toC.mapTo(HashSet()) { it.hash })
        }

    /**
     * The same wait on a holder that answered HAVE ([BlobExchange]'s `graceFor`): a large copy's fetch outlasts the
     * holder's free-link wait before its grace is up and the holder is confirmed (#140); a small one, which the composite
     * never raises a link for, does not.
     */
    @Test
    fun aFetchFromAHolderThatMayRaiseAFreeLinkOutlastsItsLinkWaitForALargeCopyOnly() =
        runTest(UnconfinedTestDispatcher()) {
            for (size in listOf(CompositeMeshTransport.BULK_MIN_BYTES, 1_000L)) {
                val r = FakeLoopTransport("r")
                val a = FakeLoopTransport("a").apply { advertises = Protocol.CAP_BLOB_HAVE }.also(r::connect)
                FakeLoopTransport("b").apply { advertises = Protocol.CAP_BLOB_HAVE }.also(r::connect)
                r.freeLinkMays += "a"
                var now = 0L
                val exchange =
                    BlobExchange(
                        transport = r,
                        store = FakeBlobStore(Files.createTempDirectory("blob-free-fetch").toFile()),
                        selfId = { "r" },
                        onObtained = { _, _ -> },
                        now = { now },
                        partials = partials(),
                        holderQuery = BlobExchange.HolderQuery(enabled = true),
                    )
                val toA = backgroundScope.requestsArrivingAt(a)
                val queriesToA = CopyOnWriteArrayList<String>()
                backgroundScope.launch { a.inbound.collect { if (it.envelope.type == FrameType.BLOB_HAVE) queriesToA += it.envelope.id } }

                exchange.want("H")
                now += 100
                exchange.onHaveAnswer(BlobHasContent(listOf(BlobHasItem("H", BlobHasItem.HAVE, size = size)), load = 0), "a")
                exchange.onHaveAnswer(BlobHasContent(listOf(BlobHasItem("H", BlobHasItem.HAVE, size = size)), load = 1), "b")
                assertEquals("size=$size: the idler holder asked", 1, toA.size)
                assertEquals(1, queriesToA.size)
                now += BlobExchange.FIRST_ASK_GRACE_MS + 1
                exchange.reask()
                val large = size >= CompositeMeshTransport.BULK_MIN_BYTES
                assertEquals("size=$size: its grace is up only past the link wait of a large copy", if (large) 1 else 2, queriesToA.size)
                now += CompositeMeshTransport.BULK_GRACE_MS
                exchange.reask()
                assertEquals("size=$size: confirmed once its grace is up", 2, queriesToA.size)
            }
        }

    /**
     * The design pass's second finding: a free link beside a radio carries only blobs addressed to both ends. A carrier
     * holder whose free link refuses this blob is no fast route for it — asked fast-only, its link would refuse the copy
     * and drop it, and every link-up edge would re-ask it — so the want is held and nobody is asked.
     */
    @Test
    fun aFreeLinkThatRefusesTheBlobIsNoFastRouteForIt() =
        runTest(UnconfinedTestDispatcher()) {
            val r = FakeLoopTransport("r")
            val c = FakeLoopTransport("c").also(r::connect)
            r.freeLinks += "c"
            r.linkViews["c"] = BlobsView(setOf("other"))
            var now = 0L
            val events = CopyOnWriteArrayList<BlobExchange.BlobEvent>()
            val exchange = exchangeOn(r, now = { now }, onEvent = { events += it })
            val toC = backgroundScope.requestsArrivingAt(c)

            exchange.want("H", author = "a", fastOnly = true)
            now += BlobExchange.ROUND_MS
            exchange.fastLinkUp(listOf("c"))
            exchange.reask()
            assertTrue("the carrier's link refuses it: never asked fast-only", toC.isEmpty())
            assertTrue(BlobExchange.BlobEvent.FastOnlyHeld in events)
            assertTrue("held for a fast route", exchange.fastHeld("H"))
            assertTrue("no free-link wait read for a fast-only want", r.freeLinkMayReads.isEmpty())
        }

    /**
     * A free link coming up to the author a radio already links is a fast route for the blob it carries: an edge that asks
     * it fast-only at once, as a Wi-Fi Aware link coming up is — and for that blob only.
     */
    @Test
    fun aFreeLinkComingUpBesideARadioAsksItsPeerFastOnlyForTheBlobItCarries() =
        runTest(UnconfinedTestDispatcher()) {
            val r = FakeLoopTransport("r")
            val a = FakeLoopTransport("a").also(r::connect)
            r.linkViews["a"] = BlobsView(setOf("H"))
            var now = 0L
            val exchange = exchangeOn(r, now = { now })
            val toA = backgroundScope.requestsArrivingAt(a)

            exchange.want("H", author = "a", fastOnly = true)
            exchange.want("G", author = "a", fastOnly = true)
            assertTrue("Bluetooth alone: nobody asked", toA.isEmpty())

            now += 1_000
            r.freeLinks += "a"
            exchange.fastLinkUp(listOf("a"))
            val asked = BlobReqContent("H", fastOnly = true, pull = BlobExchange.PULL_BUDGET)
            assertEquals("asked at once over it, for its blob", listOf(asked), toA)
            now += 1_000
            exchange.reask()
            assertEquals("and only that one", 1, toA.size)
            assertTrue("the blob it refuses is still held", exchange.fastHeld("G"))
            assertTrue("the one it carries is not", !exchange.fastHeld("H"))
        }

    /**
     * A receiver's fast-only ask rides the radio, so it can reach the holder before the holder's end of the free link is
     * published (#295). The holder hands one fast-only copy to the transport, whose wait carries it over that link; the
     * serve memo covers a second ask, and a copy the wait gave up on leaves the next ask served at once.
     */
    @Test
    fun aFastOnlyAskAheadOfTheHoldersEndOfAFreeLinkIsServedOnce() =
        runTest(UnconfinedTestDispatcher()) {
            val h = FakeLoopTransport("h")
            FakeLoopTransport("n").also(h::connect)
            h.freeLinkComings += "n"
            val store =
                FakeBlobStore(
                    Files.createTempDirectory("blob-link-coming").toFile(),
                ).apply { seed(PICTURE_HASH, "image/jpeg", PICTURE) }
            val events = CopyOnWriteArrayList<BlobExchange.BlobEvent>()
            val exchange = exchangeOn(h, store, onEvent = { events += it })

            exchange.onRequest(PICTURE_HASH, "n", fastOnly = true)
            val meta = h.filesSent.single()
            assertTrue("marked for the transport, which never hands it to Bluetooth", meta.fastOnly)
            exchange.onRequest(PICTURE_HASH, "n", fastOnly = true)
            assertEquals("a second ask ships nothing", 1, h.filesSent.size)

            checkNotNull(meta.onDropped).invoke() // the link's handshake died: the composite dropped the copy
            exchange.onRequest(PICTURE_HASH, "n", fastOnly = true)
            assertEquals("forgotten, so the next ask is served at once", 2, h.filesSent.size)
            assertTrue("nothing refused: $events", BlobExchange.BlobEvent.FastOnlyRefused !in events)
        }

    /**
     * The asker's link about to be published passes the refusal ahead of the store read (#295), so a holder that lacks the
     * blob pulls it through as it would once the link is published: one fast-only ask to a fast-linked source, a hop less of
     * the asker's budget, and nothing refused.
     */
    @Test
    fun aFastOnlyAskAheadOfAFreeLinkForABlobWeLackIsPulledThroughFastOnly() =
        runTest(UnconfinedTestDispatcher()) {
            val h = FakeLoopTransport("h")
            val source = FakeLoopTransport("source").also(h::connect)
            FakeLoopTransport("n").also(h::connect)
            h.bulkRoutes["source"] = BulkRoute.Linked
            h.freeLinkComings += "n"
            val events = CopyOnWriteArrayList<BlobExchange.BlobEvent>()
            val exchange = exchangeOn(h, onEvent = { events += it })
            val toSource = backgroundScope.requestsArrivingAt(source)

            exchange.onRequest("H", "n", fastOnly = true, pull = BlobExchange.PULL_BUDGET)
            exchange.reask()
            assertEquals(
                "one fast-only ask, one hop less of the asker's budget",
                listOf(BlobReqContent("H", fastOnly = true, pull = BlobExchange.PULL_BUDGET - 1)),
                toSource,
            )
            assertTrue("nothing refused: $events", BlobExchange.BlobEvent.FastOnlyRefused !in events)
        }

    /**
     * A link about to be published is a fast route only for a blob it carries (L-7-11), and only to a peer a radio links,
     * whose wait is the one that carries the copy: any other ask is refused as before, with no serve, no echo and no memo.
     */
    @Test
    fun aFastOnlyAskIsStillRefusedWithNoLinkComingThatWouldCarryItToAPeerARadioLinks() =
        runTest(UnconfinedTestDispatcher()) {
            val h = FakeLoopTransport("h")
            FakeLoopTransport("n").also(h::connect)
            val source = FakeLoopTransport("source").also(h::connect)
            h.bulkRoutes["source"] = BulkRoute.Linked
            val store =
                FakeBlobStore(
                    Files.createTempDirectory("blob-link-coming-refused").toFile(),
                ).apply { seed(PICTURE_HASH, "image/jpeg", PICTURE) }
            val events = CopyOnWriteArrayList<BlobExchange.BlobEvent>()
            val exchange = exchangeOn(h, store, onEvent = { events += it })
            val toSource = backgroundScope.requestsArrivingAt(source)
            val refused = { events.count { it == BlobExchange.BlobEvent.FastOnlyRefused } }

            exchange.onRequest(PICTURE_HASH, "n", fastOnly = true)
            assertEquals("no link coming", 1, refused())

            h.freeLinkComings += "n"
            h.linkViews["n"] = BlobsView(setOf("other"))
            exchange.onRequest(PICTURE_HASH, "n", fastOnly = true)
            assertEquals("a link coming that does not carry it", 2, refused())
            exchange.onRequest("H", "n", fastOnly = true, pull = BlobExchange.PULL_BUDGET) // one we lack
            assertEquals("nor one we lack", 3, refused())
            exchange.reask()
            assertTrue("no echo: ${toSource.toList()}", toSource.isEmpty())

            h.freeLinkComings += "gone"
            exchange.onRequest(PICTURE_HASH, "gone", fastOnly = true)
            assertEquals("a link coming to a peer no radio links", 4, refused())
            assertTrue("nothing handed to the transport", h.filesSent.isEmpty())

            h.linkViews.remove("n")
            exchange.onRequest(PICTURE_HASH, "n", fastOnly = true)
            assertEquals("no memo stamped: served once it carries it", 1, h.filesSent.size)
        }

    /**
     * [BlobExchange.fastHeld], what `MeshManager.attachmentFastHeld` hands the link demand: true only for a fast-only want
     * its last step found no fast holder for — never a plain want, and not once the tap made it one.
     */
    @Test
    fun aWantIsFastHeldOnlyWhileItIsFastOnlyWithNoFastHolder() =
        runTest(UnconfinedTestDispatcher()) {
            val r = FakeLoopTransport("r")
            FakeLoopTransport("a").also(r::connect)
            var now = 0L
            val exchange = exchangeOn(r, now = { now })

            exchange.want("P", author = "a")
            exchange.want("H", author = "a", fastOnly = true)
            now += 1_000
            assertTrue("a plain want is never held", !exchange.fastHeld("P"))
            assertTrue("a fast-only want with only Bluetooth is", exchange.fastHeld("H"))
            assertTrue("no want, nothing held", !exchange.fastHeld("X"))

            now += 1_000
            exchange.want("H", author = "a", approved = true) // the tap
            now += 1_000
            assertTrue("the tap lifts it", !exchange.fastHeld("H"))
        }

    private companion object {
        val PICTURE = ByteArray(2_000) { (it % 97).toByte() }
        val PICTURE_HASH = sha256Hex(PICTURE)
        val HASH_H = "e".repeat(64)
    }
}
