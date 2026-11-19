package app.getknit.knit.mesh.lab

import app.getknit.knit.mesh.BlobExchange
import app.getknit.knit.mesh.BulkRoute
import app.getknit.knit.mesh.SeenSet
import app.getknit.knit.mesh.protocol.FrameId
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.TypingContent
import app.getknit.knit.mesh.protocol.WireCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
 * A link with airtime ([LinkAir]): what a node sends it queues, as a phone's `FramedLink` does, and a writer airs the queue
 * at the link's rate, sharing each end's radio ([NodeAir]) with that node's other links. A send no longer means the far
 * end has the frame, so the lab's drain waits out what is still on the air, and a link cut with frames queued loses them
 * with it, as a torn-down link does: custody re-serves them on the next link, and neither end's crossing memo may hold
 * them back. The mobility simulator gives every link a radio's rate; until it did, the lab had none.
 */
@RunWith(RobolectricTestRunner::class)
class LinkAirLabTest {
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

    /**
     * Bob hands Alice a burst of typing cues at once. They reach her no faster than their bytes air at the link's rate, in
     * the order he sent them, and her drain waits for the last of them: none is delivered when his send returns.
     */
    @Test
    fun aBurstAirsAtTheLinksRateInOrderAndTheDrainWaitsForIt() =
        runBlocking {
            val (alice, bob) = friendsWithASession(AIR)
            val cues = List(BURST) { typingCue(bob, alice) }
            val bytes = cues.sumOf(::airBytesAtLeast)

            val started = System.nanoTime()
            bob.burstSigned(alice, cues)
            alice.transport.awaitInboundDrained()
            val tookMs = (System.nanoTime() - started) / NANOS_PER_MS

            val minMs = bytes * MS_PER_S / AIR.bytesPerSecond
            assertTrue("$bytes bytes at ${AIR.bytesPerSecond} B/s took $tookMs ms, under $minMs", tookMs >= minMs - SLACK_MS)
            val ids = cues.map { it.id }
            assertEquals("every cue delivered by the drain, in the order sent", ids, crossed(bob, ids))
        }

    /**
     * Alice and Bob each send Carol a burst at once, over two links whose own rate is her radio's. Her radio carries both,
     * so the two take as long as their bytes at its one rate, and it serves them in turn: when Alice's last cue lands,
     * Bob's link has aired a fair share of his. Both bursts are held and released back to back, so neither link has her
     * radio to itself while the other is still being signed.
     *
     * lab-lint: allow missing-oracle — its subject is the airtime of typing cues, which no store keeps and the oracle cannot see
     */
    @Test
    fun twoLinksShareTheRadioOfTheNodeBothReach() =
        runBlocking {
            val radio = LabLimits(nodeAirBytesPerSecond = AIR.bytesPerSecond)
            val alice = lab.node("alice")
            val bob = lab.node("bob")
            val carol = lab.node("carol", radio)
            lab.linkAll(alice to carol, bob to carol, air = AIR)
            lab.awaitAcquainted(alice, bob, carol)
            val fromAlice = List(BURST) { typingCue(alice, carol) }
            val fromBob = List(BURST) { typingCue(bob, carol) }
            val bytes = (fromAlice + fromBob).sumOf(::airBytesAtLeast)
            val aliceBefore =
                alice.transport.aired.records
                    .get()
            val aliceBytesBefore =
                alice.transport.aired.bytes
                    .get()
            val bobBefore =
                bob.transport.aired.bytes
                    .get()

            alice.transport.hold(carol.transport)
            bob.transport.hold(carol.transport)
            alice.burstSigned(carol, fromAlice)
            bob.burstSigned(carol, fromBob)
            // Both queues filled at once: a held frame is queued for the air as its release sends it, so the clock starts
            // before the first release, never after the air has begun.
            val started = System.nanoTime()
            alice.transport.release(carol.transport)
            bob.transport.release(carol.transport)
            lab.await(1, timeoutMs = BURST_AWAIT_MS) {
                if (alice.transport.aired.records
                        .get() - aliceBefore >= BURST
                ) {
                    1
                } else {
                    0
                }
            }
            val bobShare =
                bob.transport.aired.bytes
                    .get() - bobBefore
            val aliceBytes =
                alice.transport.aired.bytes
                    .get() - aliceBytesBefore
            carol.transport.awaitInboundDrained()
            val tookMs = (System.nanoTime() - started) / NANOS_PER_MS

            val minMs = bytes * MS_PER_S / AIR.bytesPerSecond
            assertTrue("$bytes bytes through one radio took $tookMs ms, under $minMs", tookMs >= minMs - SLACK_MS)
            assertTrue("Bob's link aired $bobShare bytes while Alice's aired $aliceBytes", bobShare * 2 >= aliceBytes)
        }

    /**
     * Bob sends Alice a run of DMs over a slow link, and the link is cut while some are still queued. The cut loses them,
     * at once: nothing reaches her after the cut returns, and nothing counts as on the air toward her. The next link's
     * custody exchange re-serves them, well inside the crossing window, and no write of one is skipped as already crossed.
     */
    @Test
    fun aCutLosesItsQueueAndTheNextLinkReServesIt() =
        runBlocking {
            val (alice, bob) = friendsWithASession(SLOW_AIR)
            val from = lab.clock.now()
            val airedBefore =
                bob.transport.aired.records
                    .get()
            val cutBefore =
                bob.transport.aired.cut
                    .get()
            repeat(DM_BURST) { assertTrue(bob.sendDm(alice, "queued $it")) }
            lab.await(1) {
                val aired =
                    bob.transport.aired.records
                        .get() - airedBefore
                if (aired >= AIRED_BEFORE_CUT && alice.transport.airInboundNow > 0) 1 else 0
            }

            // lab-lint: allow direct-disconnect — the cut itself is the subject, and `lab.unlink` refuses an air link
            bob.transport.disconnect(alice.transport)
            val inAtCut = alice.transport.framesReceived
            assertEquals("records still counted on the air after the cut", 0L, alice.transport.airInboundNow)
            assertTrue(
                "the cut found nothing queued",
                bob.transport.aired.cut
                    .get() > cutBefore,
            )
            val cutKeys =
                bob.transport.aired.cutKeys
                    .toList()
            // Longer than a record takes to air: a writer that outlived the cut would have delivered by now.
            // lab-lint: allow sleep-as-sync — a pause before a check that nothing more arrives, never a sync point
            withContext(Dispatchers.Default) { delay(AFTER_CUT_MS) }
            assertEquals("a frame landed after the cut returned", inAtCut, alice.transport.framesReceived)
            assertTrue("every DM landed though the cut lost some", alice.decrypted(alice.dmWith(bob)).size < 2 + DM_BURST)
            // What `lab.unlink` waits for after its cut: both collectors handed the departure, so the relink is a newcomer
            // to each and its custody exchange runs.
            alice.transport.awaitNeighborsObserved()
            bob.transport.awaitNeighborsObserved()

            lab.link(alice, bob, air = SLOW_AIR)
            lab.assertConverged(listOf(alice, bob), atLeast = 2 + DM_BURST, timeoutMs = BURST_AWAIT_MS) {
                it.dmWith(if (it === alice) bob else alice)
            }
            assertTrue("the lab clock passed the crossing window", lab.clock.now() - from < SeenSet.DEFAULT_TTL_MS)
            val skipped = cutKeys.sumOf { bob.transport.skipsToward(alice.nodeId, it) }
            assertEquals("writes of a cut frame skipped as already crossed", 0, skipped)
        }

    /**
     * A cut racing the writer, again and again under the lab's scheduling noise: whenever it lands, nothing reaches the far
     * end once it has returned, and nothing is left counted on the air.
     */
    @Test
    fun noRecordLandsAfterTheCutReturns() =
        runBlocking {
            val (alice, bob) = friendsWithASession(AIR)
            repeat(CUTS) { round ->
                val airedBefore =
                    bob.transport.aired.records
                        .get()
                bob.burstSigned(alice, List(CUT_BURST) { typingCue(bob, alice) })
                // Cut a little later each round, so some cuts land while a record airs and some while it is delivered.
                lab.await(1) {
                    if (bob.transport.aired.records
                            .get() - airedBefore > round
                    ) {
                        1
                    } else {
                        0
                    }
                }
                // lab-lint: allow direct-disconnect — the cut racing the writer is the subject; `lab.unlink` refuses an air link
                bob.transport.disconnect(alice.transport)
                val inAtCut = alice.transport.framesReceived
                assertEquals(0L, alice.transport.airInboundNow)
                // lab-lint: allow sleep-as-sync — a pause before a check that nothing more arrives, never a sync point
                withContext(Dispatchers.Default) { delay(AFTER_CUT_MS) }
                assertEquals("round $round: a frame landed after the cut returned", inAtCut, alice.transport.framesReceived)
                alice.transport.awaitNeighborsObserved()
                bob.transport.awaitNeighborsObserved()
                lab.link(alice, bob, air = AIR)
                alice.transport.awaitInboundDrained()
            }
        }

    /**
     * Bob sends Alice a picture over a link with airtime. Its bytes reach her no faster than the link airs them: she holds
     * the picture whole no sooner than its size at the link's rate after the send, and the oracle then agrees.
     */
    @Test
    fun aPictureAirsAtTheLinksRate() =
        runBlocking {
            val (alice, bob) = friendsWithASession(AIR)
            val picture = Random(1).nextBytes(PICTURE_BYTES)
            val started = System.nanoTime()
            assertTrue(bob.sendImage(picture, "a picture", to = alice))
            val id = bob.ownMessageId(bob.dmWith(alice), "a picture")
            lab.assertConverged(listOf(alice, bob), atLeast = 3, timeoutMs = BURST_AWAIT_MS) { it.dmWith(if (it === alice) bob else alice) }
            awaitPicture(alice, bob, id, picture)
            val tookMs = (System.nanoTime() - started) / NANOS_PER_MS

            val minMs = PICTURE_BYTES.toLong() * MS_PER_S / AIR.bytesPerSecond
            assertTrue("$PICTURE_BYTES bytes at ${AIR.bytesPerSecond} B/s landed in $tookMs ms, under $minMs", tookMs >= minMs - SLACK_MS)
            assertTrue(
                "the file's bytes were never counted as aired",
                bob.transport.aired.bytes
                    .get() >= PICTURE_BYTES,
            )
        }

    /**
     * The link is cut while Bob's picture airs to Alice. The file is lost whole and counted: she never has it, nothing
     * counts as arriving any more, and nothing lands after the cut. The next link brings it to her whole.
     */
    @Test
    fun aFileCutMidAirIsLostAndTheNextLinkBringsItWhole() =
        runBlocking {
            val (alice, bob) = friendsWithASession(SLOW_AIR)
            val picture = Random(2).nextBytes(PICTURE_BYTES)
            val cutBefore =
                bob.transport.aired.filesCut
                    .get()
            assertTrue(bob.sendImage(picture, "a slow picture", to = alice))
            val id = bob.ownMessageId(bob.dmWith(alice), "a slow picture")
            // The file is on the air once its header is in: Alice counts it as arriving.
            lab.await(1, timeoutMs = BURST_AWAIT_MS) { if (alice.transport.arrivingFiles().isNotEmpty()) 1 else 0 }

            // lab-lint: allow direct-disconnect — the cut itself is the subject, and `lab.unlink` refuses an air link
            bob.transport.disconnect(alice.transport)
            assertEquals(
                "the cut lost no file",
                cutBefore + 1,
                bob.transport.aired.filesCut
                    .get(),
            )
            assertEquals("a cut file still counts as arriving", emptyMap<String, Any>(), alice.transport.arrivingFiles())
            // lab-lint: allow sleep-as-sync — a pause before a check that nothing more arrives, never a sync point
            withContext(Dispatchers.Default) { delay(AFTER_CUT_MS) }
            assertEquals("the cut file landed", null, alice.attachmentPlain(alice.dmWith(bob), id))
            alice.transport.awaitNeighborsObserved()
            bob.transport.awaitNeighborsObserved()
            // Past Bob's serve memo, which refuses a repeat ask for the copy the cut lost: every ask that crossed is
            // handled first, both ends drained, so none is answered after the jump and stamps the memo again.
            withContext(Dispatchers.Default) {
                alice.transport.awaitInboundDrained()
                bob.transport.awaitInboundDrained()
            }
            val asked = alice.transport.blobAsks.count { it.to == bob.nodeId }
            lab.await(1) { if (bob.metrics.files().blobAsksHandled >= asked) 1 else 0 }
            lab.clock.advance(BlobExchange.SERVE_MEMO_MS)

            lab.link(alice, bob, air = AIR)
            lab.assertConverged(listOf(alice, bob), atLeast = 3, timeoutMs = BURST_AWAIT_MS) { it.dmWith(if (it === alice) bob else alice) }
            awaitPicture(alice, bob, id, picture)
        }

    /**
     * A slow link moved onto a fast plane where it stands ([LabTransport.setAir]): a picture sent after the move airs at
     * the new rate, far sooner than the old one would carry it, and the link never went down.
     */
    @Test
    fun aLinkMovedToAFastPlaneAirsAtItsRate() =
        runBlocking {
            val (alice, bob) = friendsWithASession(SLOW_AIR)
            val linksBefore = alice.transport.neighbors.value.size
            bob.transport.setAir(alice.transport, FAST_AIR)
            val picture = Random(3).nextBytes(PICTURE_BYTES)
            val started = System.nanoTime()
            assertTrue(bob.sendImage(picture, "a fast picture", to = alice))
            val id = bob.ownMessageId(bob.dmWith(alice), "a fast picture")
            awaitPicture(alice, bob, id, picture)
            val tookMs = (System.nanoTime() - started) / NANOS_PER_MS
            lab.assertConverged(listOf(alice, bob), atLeast = 3, timeoutMs = BURST_AWAIT_MS) { it.dmWith(if (it === alice) bob else alice) }

            val slowMs = PICTURE_BYTES.toLong() * MS_PER_S / SLOW_AIR.bytesPerSecond
            assertTrue("the picture took $tookMs ms, as long as the slow link's $slowMs", tookMs < slowMs / 2)
            assertEquals("the move took the link down", linksBefore, alice.transport.neighbors.value.size)
        }

    /**
     * Bob holds a large picture for Alice while the fast plane sees her but holds no link ([BulkRoute.Reachable]): he arms
     * it and waits, the file queued on no link, as the composite waits for its data path. When the data path comes up the
     * file goes at once, at its rate.
     */
    @Test
    fun aLargeFileWaitsForTheFastLinkItWasPromised() =
        runBlocking {
            val (alice, bob) = friendsWithASession(AIR)
            alice.transport.bulkRoutes[bob.nodeId] = BulkRoute.Reachable
            bob.transport.bulkRoutes[alice.nodeId] = BulkRoute.Reachable
            val picture = Random(4).nextBytes(LARGE_PICTURE_BYTES)
            assertTrue(bob.sendImage(picture, "a large picture", to = alice))
            val id = bob.ownMessageId(bob.dmWith(alice), "a large picture")
            lab.await(1, timeoutMs = BURST_AWAIT_MS) { if (alice.nodeId in bob.transport.bulkArmed) 1 else 0 }
            val hash = checkNotNull(bob.attachmentHash(bob.dmWith(alice), id))
            assertTrue("the armed file is not in flight", bob.transport.fileInFlightTo(alice.nodeId, hash))
            assertEquals("the armed file went on the slow link", emptyMap<String, Any>(), alice.transport.arrivingFiles())

            bob.transport.setAir(alice.transport, FAST_AIR)
            alice.transport.bulkRoutes[bob.nodeId] = BulkRoute.Linked
            bob.transport.bulkRoutes[alice.nodeId] = BulkRoute.Linked
            val started = System.nanoTime()
            awaitPicture(alice, bob, id, picture)
            val tookMs = (System.nanoTime() - started) / NANOS_PER_MS
            val slowMs = LARGE_PICTURE_BYTES.toLong() * MS_PER_S / AIR.bytesPerSecond
            assertTrue("the picture took $tookMs ms once the fast link was up, as long as the slow link's $slowMs", tookMs < slowMs / 2)
            lab.assertConverged(listOf(alice, bob), atLeast = 3, timeoutMs = BURST_AWAIT_MS) { it.dmWith(if (it === alice) bob else alice) }
        }

    /** On a link with no airtime a send still means the far end has the frame, and nothing counts as aired. */
    @Test
    fun aLinkWithNoAirDeliversAsItSends() =
        runBlocking {
            val (alice, bob) = friendsWithASession(air = null)
            val cues = List(BURST) { typingCue(bob, alice) }
            bob.burstSigned(alice, cues)
            val ids = cues.map { it.id }
            assertEquals("delivered when the send returned", ids, crossed(bob, ids))
            assertEquals(
                0L,
                bob.transport.aired.records
                    .get(),
            )
            assertEquals(0L, alice.transport.airInboundNow)
        }

    /**
     * Two custody digests sent while the first still waits behind a burst: the second replaces the first's ids, as a link
     * keeps one pending digest, so one digest airs and it carries the newer ids.
     */
    @Test
    fun aDigestSentWhileOneWaitsReplacesIt() =
        runBlocking {
            val (alice, bob) = friendsWithASession(AIR, LabLimits(reofferIntervalMs = QUIET_MS))
            val ids = bob.custodyIds().sorted()
            val digestsBefore = alice.transport.airDigestsIn.size
            val toAlice =
                bob.transport.neighbors.value
                    .first { it.nodeId == alice.nodeId }

            bob.burstSigned(alice, List(BURST) { typingCue(bob, alice) })
            bob.transport.sendDigest(toAlice, ids.dropLast(1))
            bob.transport.sendDigest(toAlice, ids)
            bob.transport.awaitAirDrained(alice.transport, timeoutMs = BURST_AWAIT_MS)

            assertEquals(listOf(ids), alice.transport.airDigestsIn.drop(digestsBefore))
        }

    // Linked over [air], contacts, and one DM each way delivered: what follows is ordinary ratchet traffic.
    private suspend fun friendsWithASession(
        air: LinkAir?,
        limits: LabLimits = LabLimits(),
    ): Pair<LabNode, LabNode> {
        val alice = lab.node("alice", limits)
        val bob = lab.node("bob", limits)
        lab.link(alice, bob, air = air)
        lab.awaitAcquainted(alice, bob)
        lab.befriend(alice, bob)
        assertTrue(alice.sendDm(bob, "hello"))
        lab.assertConverged(listOf(alice, bob), atLeast = 1, timeoutMs = BURST_AWAIT_MS) { it.dmWith(if (it === alice) bob else alice) }
        assertTrue(bob.sendDm(alice, "hello back"))
        lab.assertConverged(listOf(alice, bob), atLeast = 2, timeoutMs = BURST_AWAIT_MS) { it.dmWith(if (it === alice) bob else alice) }
        return alice to bob
    }

    // Waits until [at] holds the picture [from] sent it as message [id], whole.
    private suspend fun awaitPicture(
        at: LabNode,
        from: LabNode,
        id: String,
        picture: ByteArray,
    ) = lab.await(1, timeoutMs = BURST_AWAIT_MS) {
        if (at.attachmentPlain(at.dmWith(from), id)?.contentEquals(picture) == true) 1 else 0
    }

    // The [ids] that crossed from [from] so far, in the order its send log has them.
    private fun crossed(
        from: LabNode,
        ids: List<String>,
    ): List<String> {
        val wanted = ids.toSet()
        return from.transport.sent.mapNotNull { line -> line.split(' ').getOrNull(SENT_ID)?.takeIf { it in wanted } }
    }

    // At least what [env] costs on the air: its signed bytes and the record header, without the signature and the wire
    // envelope's own fields, so a time bound read from it is a lower bound.
    private fun airBytesAtLeast(env: RelayEnvelope): Long = WireCodec.encodeEnvelope(env).size.toLong() + LinkAir.RECORD_HEADER_BYTES

    // A "now typing" cue: read, shown and kept nowhere, so a burst of them leaves nothing for the oracle to weigh.
    private fun typingCue(
        from: LabNode,
        to: LabNode,
    ): RelayEnvelope =
        RelayEnvelope(
            type = FrameType.TYPING,
            id = FrameId.new(),
            senderId = from.nodeId,
            sentAt = from.now(),
            recipientId = to.nodeId,
            payload = WireCodec.encodePayload(TypingContent()),
        )

    private companion object {
        /** Fast enough that a scenario takes seconds, slow enough that a burst queues. */
        val AIR = LinkAir(bytesPerSecond = 10_000)

        /** A Wi-Fi Aware data path's rate, on a radio of its own. */
        val FAST_AIR = LinkAir(bytesPerSecond = 1_000_000, sharesNodeAir = false)

        /** Slow enough that a run of sealed DMs is still queued when the cut comes. */
        val SLOW_AIR = LinkAir(bytesPerSecond = 2_000)

        /** About 15 KB of cues: over a second and a half at [AIR]. */
        const val BURST = 100

        /** Four seconds at [AIR], twenty at [SLOW_AIR]: a cut finds it airing. */
        const val PICTURE_BYTES = 40_000

        /** Over `CompositeMeshTransport.BULK_MIN_BYTES`: a holder arms the fast plane for it. Twenty seconds at [AIR]. */
        const val LARGE_PICTURE_BYTES = 200_000
        const val DM_BURST = 20
        const val AIRED_BEFORE_CUT = 3
        const val CUTS = 5
        const val CUT_BURST = 40

        /** Several records' airtime at [SLOW_AIR]: what a writer that outlived its cut would need to deliver one. */
        const val AFTER_CUT_MS = 1_000L

        /** No custody re-offer inside the scenario: a digest of the manager's own would replace the one under test. */
        const val QUIET_MS = 3_600_000L
        const val BURST_AWAIT_MS = 120_000L

        /** A record's slot is computed in nanoseconds and slept in whole milliseconds: at most one short at the end. */
        const val SLACK_MS = 5L
        const val NANOS_PER_MS = 1_000_000L
        const val MS_PER_S = 1_000L

        /** `#seq receiver type id relay=… via=…`, as [LabTransport.sent] logs a crossing. */
        const val SENT_ID = 3
    }
}
