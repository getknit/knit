package app.getknit.knit.mesh.inet

import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.FileKind
import app.getknit.knit.mesh.FileMeta
import app.getknit.knit.mesh.MeshMetrics
import app.getknit.knit.mesh.PartialBlobs
import app.getknit.knit.mesh.Peer
import app.getknit.knit.mesh.TransportKind
import app.getknit.knit.mesh.link.LinkClaims
import app.getknit.knit.mesh.link.LinkFraming
import app.getknit.knit.mesh.link.LinkHandshake
import app.getknit.knit.mesh.protocol.BlobReqContent
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.Protocol
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import app.getknit.knit.mesh.spool.groupChatFrame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Collections

/**
 * A contact's direct link as a composite child (ADR 2026-10.a5yj, Phase 1): the HELLO each way over the link's stream,
 * `FramedLink` over it, what counts as use, and how a link ends. Real threads: `FramedLink`'s loops and the stream's
 * blocking adapters park IO threads, as they do on a phone; every wait is bounded.
 */
class DirectLinkTransportTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val alice = NodeId.derive("alice")
    private val bob = NodeId.derive("bob")
    private val clock = { System.nanoTime() / 1_000_000 }

    // Every stream a test made: ended at teardown, which returns the IO threads `FramedLink`'s loops park in their blocking
    // reads. Cancelling the scope cannot reach a thread inside runBlocking, so without this each test leaks them into the
    // next class in the JVM.
    private val streams = Collections.synchronizedList(ArrayList<LinkArq>())

    @After
    fun tearDown() {
        synchronized(streams) { streams.forEach { it.end() } }
        scope.cancel()
    }

    // One end of a link: its transport, its stream, and the peers it noted use for.
    private inner class End(
        val self: String,
        handshakeMs: Long = DirectLinkTransport.HANDSHAKE_MS,
        lookups: LinkScope = Lookups,
        now: () -> Long = clock,
    ) {
        val uses: MutableList<String> = Collections.synchronizedList(ArrayList())

        // The peers a link was adopted to, in order ([DirectLinkTransport]'s `onLinkUp`).
        val ups: MutableList<String> = Collections.synchronizedList(ArrayList())
        val upLate =
            java.util.concurrent.atomic
                .AtomicBoolean(false)
        val closed = CompletableDeferred<Unit>()
        lateinit var stream: LinkArq
        val metrics = MeshMetrics()
        val claims = LinkClaims(metrics)
        val transport: DirectLinkTransport =
            DirectLinkTransport(
                selfId = { self },
                linkScope = lookups,
                scope = scope,
                cacheDir = tmp.newFolder(),
                partials = PartialBlobs(tmp.newFolder()),
                metrics = metrics,
                now = now,
                onUse = { uses += it },
                onLinkUp = {
                    ups += it
                    // Whether the peer was already a neighbour when the link was reported: it must not be yet.
                    if (transport.neighbors.value.any { n -> n.nodeId == it }) upLate.set(true)
                },
                handshakeMs = handshakeMs,
                claims = claims,
            )
    }

    // Two streams wired to each other, each datagram delivered on another coroutine as a path would; closing either
    // ends both, as a BYE ends the link at both ends.
    private fun connect(
        a: End,
        b: End,
    ) {
        lateinit var toB: LinkArq
        lateinit var toA: LinkArq
        val aStream =
            LinkArq({ bytes -> true.also { scope.launch { toB.take(bytes) } } }, scope, clock, onClose = {
                a.closed.complete(Unit)
                toB.end()
            })
        val bStream =
            LinkArq({ bytes -> true.also { scope.launch { toA.take(bytes) } } }, scope, clock, onClose = {
                b.closed.complete(Unit)
                toA.end()
            })
        toB = bStream
        toA = aStream
        streams += aStream
        streams += bStream
        a.stream = aStream
        b.stream = bStream
    }

    // What a link may look up: no group shared, one attachment addressed to the pair, and alice's own avatar.
    private object Lookups : LinkScope {
        override suspend fun sharedGroups(peerId: String): Map<String, Set<String>> = emptyMap()

        override suspend fun addresseeSetsOf(hash: String): List<Set<String>> =
            if (hash == PAIRS) listOf(setOf(NodeId.derive("alice"), NodeId.derive("bob"))) else emptyList()

        override suspend fun ownAvatarKey(): String? = AVATAR

        // Alice's custody: her DM to bob, and a room post. An id it does not hold has no envelope.
        override suspend fun custodyEnvelopes(ids: List<String>): Map<String, RelayEnvelope> {
            val alice = NodeId.derive("alice")
            val held =
                mapOf(
                    "m1-QQ" to
                        RelayEnvelope(
                            type = FrameType.CHAT,
                            id = "m1-QQ",
                            senderId = alice,
                            recipientId = NodeId.derive("bob"),
                            payload = ByteArray(0),
                        ),
                    "r1-QQ" to RelayEnvelope(type = FrameType.CHAT, id = "r1-QQ", senderId = alice, payload = ByteArray(0)),
                )
            return ids.mapNotNull { id -> held[id]?.let { id to it } }.toMap()
        }
    }

    // A frame from [from], to [to] (null: the room).
    private fun frame(
        id: String,
        from: String = alice,
        to: String? = bob,
    ): WireEnvelope {
        val env = RelayEnvelope(type = FrameType.CHAT, id = id, senderId = from, recipientId = to, payload = ByteArray(0))
        // A signature of its own per frame: a link writes a frame once, keyed on its signature (`FrameKey`).
        return WireEnvelope(sig = id.encodeToByteArray(), signed = WireCodec.encodeEnvelope(env))
    }

    private fun linked(
        a: End,
        b: End,
    ) = runBlocking {
        a.transport.start()
        b.transport.start()
        connect(a, b)
        a.transport.adopt(bob, a.stream, offerer = true)
        b.transport.adopt(alice, b.stream, offerer = false)
        withTimeout(WAIT_MS) {
            a.transport.neighbors.first { set -> set.any { it.nodeId == bob } }
            b.transport.neighbors.first { set -> set.any { it.nodeId == alice } }
        }
    }

    /**
     * A lane is proven before its HELLO is read (ADR 2026-11.264w, contract §6): its keys bind both ids. A peer already
     * proof-capable here is never refused on it (R1 never runs on this plane), the link counts as proven, and the radios
     * are told, so they close an unproven link claiming that peer (R3).
     */
    @Test
    fun aLaneLinkCountsAsProvenAndIsNeverRefusedAsAnUnprovenClaim() =
        runBlocking<Unit> {
            val a = End(alice)
            val b = End(bob)
            a.claims.seed(listOf(bob))
            val told = Collections.synchronizedList(ArrayList<String>())
            a.claims.onCapable { told += it }

            linked(a, b)

            val frames = a.metrics.frames()
            assertEquals(1L, frames.linksProven)
            assertEquals(0L, frames.linkClaimRefused)
            assertEquals(0L, frames.linkProofFailed)
            assertEquals(listOf(bob), told.toList())
            assertEquals(1L, b.metrics.frames().linksProven)
            assertTrue(b.claims.isProofCapable(alice))
        }

    /**
     * A lane peer on a build without link proof (its HELLO lacks the bit) links as ever, proven for this plane, but
     * tells the radios nothing: made proof-capable by it, its honest unproven radio links would be refused (R1).
     */
    @Test
    fun aLanePeerWhoseHelloLacksTheBitLinksButFeedsNoClaim() =
        runBlocking<Unit> {
            val a = End(alice)
            val b = End(bob)
            a.transport.start()
            connect(a, b)
            a.transport.adopt(bob, a.stream, offerer = false)
            // Bob's end by hand, as a build without the bit writes its HELLO: three fields, caps 0x9.
            LinkFraming.write(b.stream.output, LinkFraming.Type.HELLO, "$bob|${Protocol.VERSION}|9".encodeToByteArray())
            b.stream.output.flush()
            val reply = LinkHandshake.receiveHello(b.stream.input)
            assertTrue("alice answered with the bit", reply?.offersProof == true)
            withTimeout(WAIT_MS) { a.transport.neighbors.first { set -> set.any { it.nodeId == bob } } }

            assertEquals(0L, a.metrics.frames().linksProven)
            assertFalse(a.claims.isProofCapable(bob))
        }

    @Test
    fun aLinksStreamIsFramedAndAFrameCrossingItIsUseAtBothEnds() =
        runBlocking<Unit> {
            val a = End(alice)
            val b = End(bob)
            linked(a, b)
            assertEquals(TransportKind.DirectLink, a.transport.kind)
            assertFalse("never a short-range plane", a.transport.shortRange)
            val got = async(start = CoroutineStart.UNDISPATCHED) { b.transport.inbound.first() }
            a.transport.send(frame("m1-QQ"), Peer(bob))
            val frame = withTimeout(WAIT_MS) { got.await() }
            assertEquals("m1-QQ", frame.envelope.id)
            assertEquals(alice, frame.fromNodeId)
            assertEquals(listOf(bob), a.uses.toList())
            assertEquals(listOf(alice), b.uses.toList())
        }

    /**
     * A link adopted is said so at each end, ahead of its peer joining the neighbour set: custody answers that peer's
     * next digest in full, which a link replaced under a peer still linked could not show otherwise (ADR 2026-10.a5yj).
     */
    @Test
    fun aLinkAdoptedIsReportedAtBothEndsBeforeItsPeerIsANeighbour() =
        runBlocking<Unit> {
            val a = End(alice)
            val b = End(bob)
            linked(a, b)
            assertEquals(listOf(bob), a.ups.toList())
            assertEquals(listOf(alice), b.ups.toList())
            assertFalse("reported before its peer is a neighbour", a.upLate.get() || b.upLate.get())
        }

    @Test
    fun onlyThePairsOwnTrafficCrossesEitherWay() =
        runBlocking<Unit> {
            val a = End(alice)
            val b = End(bob)
            linked(a, b)
            val got = async(start = CoroutineStart.UNDISPATCHED) { b.transport.inbound.first() }
            // The room, and a stranger's DM alice carries for bob, never leave her end.
            a.transport.send(frame("r1-QQ", to = null), Peer(bob))
            a.transport.send(frame("s1-QQ", from = NodeId.derive("carol")), Peer(bob))
            assertEquals(2L, a.transport.refused.get())
            a.transport.send(frame("m2-QQ"), Peer(bob))
            assertEquals("m2-QQ", withTimeout(WAIT_MS) { got.await() }.envelope.id)
            // A file goes only when it is addressed to the peer, or alice's own avatar.
            val file = tmp.newFile()
            assertFalse(a.transport.sendFile(file, Peer(bob), FileMeta(FileKind.ATTACHMENT, key = ROOM_PHOTO, mime = "image/jpeg")))
            assertFalse(a.transport.sendFile(file, Peer(bob), FileMeta(FileKind.AVATAR, key = CAROLS_AVATAR, mime = "image/webp")))
            assertTrue(a.transport.sendFile(file, Peer(bob), FileMeta(FileKind.ATTACHMENT, key = PAIRS, mime = "image/jpeg")))
            assertTrue(a.transport.sendFile(file, Peer(bob), FileMeta(FileKind.AVATAR, key = AVATAR, mime = "image/webp")))
            assertEquals(4L, a.transport.refused.get())
        }

    @Test
    fun aFrameTheFarEndWouldNotSendIsDroppedOnTheWayInToo() =
        runBlocking<Unit> {
            // Alice believes she shares a group with bob; bob's end knows no such group (it left, or never was).
            val inGroup =
                object : LinkScope by Lookups {
                    override suspend fun sharedGroups(peerId: String) = mapOf("g1" to setOf(alice, bob))
                }
            val a = End(alice, lookups = inGroup)
            val b = End(bob)
            linked(a, b)
            val post = groupChatFrame("g1-QQ", alice, "g1", listOf(alice, bob))
            val dm = frame("m3-QQ")
            val got = async(start = CoroutineStart.UNDISPATCHED) { b.transport.inbound.first() }
            a.transport.send(WireEnvelope(sig = post.sig, signed = post.signed), Peer(bob))
            a.transport.send(dm, Peer(bob))
            // In order on bob's link: the group post was refused, and the DM behind it is the first frame bob takes.
            assertEquals("m3-QQ", withTimeout(WAIT_MS) { got.await() }.envelope.id)
            assertEquals(1L, b.transport.refused.get())
            assertEquals(0L, a.transport.refused.get())
        }

    @Test
    fun aGroupMadeSinceTheLastReadCrossesWithItsFirstPost() =
        runBlocking<Unit> {
            // Alice's groups as her database holds them: none until she makes one with bob. Bob's end knows it throughout.
            val made =
                java.util.concurrent.atomic
                    .AtomicBoolean(false)
            val reads =
                java.util.concurrent.atomic
                    .AtomicInteger()
            val growing =
                object : LinkScope by Lookups {
                    override suspend fun sharedGroups(peerId: String): Map<String, Set<String>> {
                        reads.incrementAndGet()
                        return if (made.get()) mapOf("g1" to setOf(alice, bob)) else emptyMap()
                    }
                }
            val inGroup =
                object : LinkScope by Lookups {
                    override suspend fun sharedGroups(peerId: String) = mapOf("g1" to setOf(alice, bob))
                }
            // Alice's link clock (her cached groups' age, and her FramedLink's bookkeeping, which message frames and one 2 s
            // step leave untouched), moved by hand.
            val t =
                java.util.concurrent.atomic
                    .AtomicLong(1_000_000L)
            val a = End(alice, lookups = growing, now = { t.get() })
            val b = End(bob, lookups = inGroup)
            linked(a, b)
            // A room post reads alice's groups and is refused; a second, inside the re-read floor, is refused on that read.
            a.transport.send(frame("r2-QQ", to = null), Peer(bob))
            val first = reads.get()
            a.transport.send(frame("r3-QQ", to = null), Peer(bob))
            assertEquals("a refusal inside the floor reads nothing", first, reads.get())
            // Two seconds on — past the floor, well inside the cache's life — alice makes the group with bob. Its first post
            // is refused by the cached read, decided again on a fresh one, and crosses.
            made.set(true)
            t.addAndGet(DirectLinkTransport.GROUPS_REREAD_MS * 2)
            val got = async(start = CoroutineStart.UNDISPATCHED) { b.transport.inbound.first() }
            val post = groupChatFrame("g2-QQ", alice, "g1", listOf(alice, bob))
            a.transport.send(WireEnvelope(sig = post.sig, signed = post.signed), Peer(bob))
            assertEquals("g2-QQ", withTimeout(WAIT_MS) { got.await() }.envelope.id)
            assertEquals(first + 1, reads.get())
            assertEquals(2L, a.transport.refused.get())
        }

    @Test
    fun aDigestNamesOnlyThePairsOwnFramesAndIsNotUse() =
        runBlocking<Unit> {
            val a = End(alice)
            val b = End(bob)
            linked(a, b)
            val got = async(start = CoroutineStart.UNDISPATCHED) { b.transport.incomingDigests.first() }
            // The DM to bob crosses; the room post, and an id alice's custody does not hold, never reach bob.
            a.transport.sendDigest(Peer(bob), listOf("m1-QQ", "r1-QQ", "gone-QQ"))
            assertEquals(listOf("m1-QQ"), withTimeout(WAIT_MS) { got.await() }.ids)
            assertTrue(a.uses.isEmpty() && b.uses.isEmpty())
        }

    @Test
    fun aHelloNamingAnotherNodeEndsTheLinkAtBothEnds() =
        runBlocking<Unit> {
            val a = End(alice)
            val b = End(bob)
            a.transport.start()
            b.transport.start()
            connect(a, b)
            a.transport.adopt(bob, a.stream, offerer = true)
            // Bob was told the link is to someone else: alice's HELLO does not name them.
            b.transport.adopt(NodeId.derive("carol"), b.stream, offerer = false)
            withTimeout(WAIT_MS) {
                // Bob closed the link; alice's stream ended under her, her HELLO read came back empty, and she framed
                // nothing.
                b.closed.await()
                assertEquals(-1, a.stream.read(ByteArray(1), 0, 1))
            }
            assertTrue(
                a.transport.neighbors.value
                    .isEmpty(),
            )
            assertTrue(
                b.transport.neighbors.value
                    .isEmpty(),
            )
        }

    @Test
    fun aHandshakeThePeerNeverAnswersIsClosedByItsWatchdog() =
        runBlocking<Unit> {
            val a = End(alice)
            val b = End(bob, handshakeMs = 300)
            b.transport.start()
            connect(a, b)
            // Alice never adopts her end: bob waits for a HELLO that never comes.
            b.transport.adopt(alice, b.stream, offerer = false)
            withTimeout(WAIT_MS) { b.closed.await() }
            assertTrue(
                b.transport.neighbors.value
                    .isEmpty(),
            )
        }

    @Test
    fun aStreamHandedToAStoppedPlaneIsClosedAtOnce() =
        runBlocking<Unit> {
            val a = End(alice)
            val b = End(bob)
            connect(a, b)
            a.transport.adopt(bob, a.stream, offerer = true)
            withTimeout(WAIT_MS) { a.closed.await() }
            assertTrue(
                a.transport.neighbors.value
                    .isEmpty(),
            )
        }

    @Test
    fun stoppingThePlaneClosesItsLinksAndALinkThatEndsLeavesTheOtherEnd() =
        runBlocking<Unit> {
            val a = End(alice)
            val b = End(bob)
            linked(a, b)
            a.transport.stop()
            assertTrue(
                a.transport.neighbors.value
                    .isEmpty(),
            )
            withTimeout(WAIT_MS) {
                a.closed.await()
                // The link's end reached bob's stream, whose framing ended and took the link out of bob's neighbours.
                b.transport.neighbors.first { it.isEmpty() }
            }
        }

    @Test
    fun aFileComesInOnlyWhenTheFarEndAddressesItToThePairAndTakingItIsUse() =
        runBlocking<Unit> {
            // Alice believes a second photo is addressed to the pair; bob's end does not (its message never reached him).
            val aliceSees =
                object : LinkScope by Lookups {
                    override suspend fun addresseeSetsOf(hash: String) =
                        if (hash == ROOM_PHOTO) listOf(setOf(alice, bob)) else Lookups.addresseeSetsOf(hash)
                }
            val a = End(alice, lookups = aliceSees)
            val b = End(bob)
            linked(a, b)
            val got = async(start = CoroutineStart.UNDISPATCHED) { b.transport.incomingFiles.first() }
            val file = tmp.newFile().apply { writeBytes(ByteArray(64) { it.toByte() }) }
            assertTrue(a.transport.sendFile(file, Peer(bob), FileMeta(FileKind.ATTACHMENT, key = ROOM_PHOTO, mime = "image/jpeg")))
            assertTrue(a.transport.sendFile(file, Peer(bob), FileMeta(FileKind.ATTACHMENT, key = PAIRS, mime = "image/jpeg")))
            // Bob takes the pair's photo, and refuses the one his own view does not address to both ends.
            assertEquals(PAIRS, withTimeout(WAIT_MS) { got.await() }.key)
            withTimeout(WAIT_MS) { while (b.transport.refused.get() < 1L) delay(10) }
            assertEquals(listOf(alice), b.uses.toList())
        }

    @Test
    fun aBlobControlFrameCrossesButIsNotUse() =
        runBlocking<Unit> {
            val a = End(alice)
            val b = End(bob)
            linked(a, b)
            val got =
                async(start = CoroutineStart.UNDISPATCHED) {
                    b.transport.inbound
                        .take(2)
                        .toList()
                }
            val ask =
                RelayEnvelope(
                    type = FrameType.BLOB_REQ,
                    id = "q1-QQ",
                    senderId = alice,
                    payload = WireCodec.encodePayload(BlobReqContent(PAIRS)),
                )
            a.transport.send(WireEnvelope(relay = false, sig = ByteArray(0), signed = WireCodec.encodeEnvelope(ask)), Peer(bob))
            // A DM behind it, in order on the link: once it is in, the ask was taken first.
            a.transport.send(frame("m4-QQ"), Peer(bob))
            assertEquals(listOf("q1-QQ", "m4-QQ"), withTimeout(WAIT_MS) { got.await() }.map { it.envelope.id })
            // Only the DM was use, at either end: the exchange's re-asks keep no link open.
            assertEquals(listOf(bob), a.uses.toList())
            assertEquals(listOf(alice), b.uses.toList())
        }

    /**
     * Custody hands a phone back its own sends when its digest lacks them; the frame crosses, but it is not use at either
     * end, or an author that does not keep the copy would hold the link open a round at a time.
     */
    @Test
    fun aFrameHandedBackToItsAuthorCrossesButIsNotUse() =
        runBlocking<Unit> {
            val a = End(alice)
            val b = End(bob)
            linked(a, b)
            val got =
                async(start = CoroutineStart.UNDISPATCHED) {
                    b.transport.inbound
                        .take(2)
                        .toList()
                }
            a.transport.send(frame("m7-QQ", from = bob, to = alice), Peer(bob))
            // A DM of Alice's behind it, in order on the link: once it is in, Bob's own frame was taken first.
            a.transport.send(frame("m8-QQ"), Peer(bob))
            assertEquals(listOf("m7-QQ", "m8-QQ"), withTimeout(WAIT_MS) { got.await() }.map { it.envelope.id })
            assertEquals(listOf(bob), a.uses.toList())
            assertEquals(listOf(alice), b.uses.toList())
        }

    /**
     * The router's flood and the DM fast path both hand a DM to the link to its addressee: it crosses once, and is use
     * once, as a Bluetooth link writes it once.
     */
    @Test
    fun aFrameHandedToALinkTwiceCrossesItOnce() =
        runBlocking<Unit> {
            val a = End(alice)
            val b = End(bob)
            linked(a, b)
            val got =
                async(start = CoroutineStart.UNDISPATCHED) {
                    b.transport.inbound
                        .take(2)
                        .toList()
                }
            a.transport.send(frame("m9-QQ"), null)
            a.transport.send(frame("m9-QQ"), Peer(bob))
            // A DM behind it, in order on the link: had the second copy gone, it would be the second frame in.
            a.transport.send(frame("m10-QQ"), Peer(bob))
            assertEquals(listOf("m9-QQ", "m10-QQ"), withTimeout(WAIT_MS) { got.await() }.map { it.envelope.id })
            assertEquals(listOf(bob, bob), a.uses.toList())
        }

    /** A frame that came in over a link has crossed it: relayed on, it never goes back to the peer it came from. */
    @Test
    fun aFrameThatCameInOverALinkIsNeverSentBackOverIt() =
        runBlocking<Unit> {
            val a = End(alice)
            val b = End(bob)
            linked(a, b)
            val toAlice = async(start = CoroutineStart.UNDISPATCHED) { a.transport.inbound.first() }
            b.transport.send(frame("m11-QQ", from = bob, to = alice), Peer(alice))
            assertEquals("m11-QQ", withTimeout(WAIT_MS) { toAlice.await() }.envelope.id)
            val toBob = async(start = CoroutineStart.UNDISPATCHED) { b.transport.inbound.first() }
            a.transport.send(frame("m11-QQ", from = bob, to = alice), null)
            a.transport.send(frame("m12-QQ"), Peer(bob))
            assertEquals("m12-QQ", withTimeout(WAIT_MS) { toBob.await() }.envelope.id)
        }

    /**
     * A new link to the same peer starts with nothing crossed: the peer may have restarted with an empty seen set, and a
     * frame that crossed the old link (custody's re-serve after a restart, say) must cross the new one.
     */
    @Test
    fun aFrameThatCrossedTheOldLinkCrossesTheNewOne() =
        runBlocking<Unit> {
            val a = End(alice)
            val b = End(bob)
            linked(a, b)
            val first = async(start = CoroutineStart.UNDISPATCHED) { b.transport.inbound.first() }
            a.transport.send(frame("m13-QQ"), Peer(bob))
            assertEquals("m13-QQ", withTimeout(WAIT_MS) { first.await() }.envelope.id)

            // A new link replaces the old at both ends; each end reports it adopted, which it does once framed.
            connect(a, b)
            a.transport.adopt(bob, a.stream, offerer = true)
            b.transport.adopt(alice, b.stream, offerer = false)
            withTimeout(WAIT_MS) { while (a.ups.size < 2 || b.ups.size < 2) delay(POLL_MS) }

            val again =
                async(start = CoroutineStart.UNDISPATCHED) {
                    b.transport.inbound
                        .take(2)
                        .toList()
                }
            a.transport.send(frame("m13-QQ"), Peer(bob))
            a.transport.send(frame("m14-QQ"), Peer(bob))
            assertEquals(listOf("m13-QQ", "m14-QQ"), withTimeout(WAIT_MS) { again.await() }.map { it.envelope.id })
        }

    @Test
    fun aLookupThatThrowsDropsThatFrameAndNoneAfterIt() =
        runBlocking<Unit> {
            // Bob's first group lookup throws (the database busy, say); every one after answers.
            val calls =
                java.util.concurrent.atomic
                    .AtomicInteger()
            val flaky =
                object : LinkScope by Lookups {
                    override suspend fun sharedGroups(peerId: String): Map<String, Set<String>> {
                        if (calls.getAndIncrement() == 0) error("busy")
                        return emptyMap()
                    }
                }
            val a = End(alice)
            val b = End(bob, lookups = flaky)
            linked(a, b)
            val got = async(start = CoroutineStart.UNDISPATCHED) { b.transport.inbound.first() }
            a.transport.send(frame("m5-QQ"), Peer(bob))
            a.transport.send(frame("m6-QQ"), Peer(bob))
            // The frame the throw met is gone; the one behind it still comes in, on the same link.
            assertEquals("m6-QQ", withTimeout(WAIT_MS) { got.await() }.envelope.id)
        }

    @Test
    fun aFileThatLandedButWasNeverTakenIsForgottenWhenThePlaneStops() =
        runBlocking<Unit> {
            val a = End(alice)
            val b = End(bob)
            linked(a, b)
            // A collector that never says it took the file: it stays landing, as one does across a session's end. The file is
            // landed before it is handed over, so once the collector holds it, it is landing, not streaming.
            val subscribed = CompletableDeferred<Unit>()
            val handed = CompletableDeferred<Unit>()
            scope.launch {
                b.transport.incomingFiles
                    .onSubscription { subscribed.complete(Unit) }
                    .collect { handed.complete(Unit) }
            }
            subscribed.await()
            val file = tmp.newFile().apply { writeBytes(ByteArray(64) { it.toByte() }) }
            assertTrue(a.transport.sendFile(file, Peer(bob), FileMeta(FileKind.ATTACHMENT, key = PAIRS, mime = "image/jpeg")))
            withTimeout(WAIT_MS) { handed.await() }
            assertTrue(PAIRS in b.transport.arrivingFiles())
            b.transport.stop()
            assertTrue("a stopped plane holds nothing arriving", b.transport.arrivingFiles().isEmpty())
        }

    private companion object {
        const val WAIT_MS = 10_000L
        const val POLL_MS = 10L

        // Blob keys are 64-hex content hashes: the link's intake refuses anything else before the rule is asked.
        val PAIRS = "a".repeat(64)
        val ROOM_PHOTO = "b".repeat(64)
        val AVATAR = "c".repeat(64)
        val CAROLS_AVATAR = "d".repeat(64)
    }
}
