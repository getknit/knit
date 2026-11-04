package app.getknit.knit.mesh.lab

import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.settings.BluetoothDownloadLimit
import app.getknit.knit.mesh.BulkRoute
import app.getknit.knit.mesh.protocol.VideoInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * Ask before large Bluetooth-only downloads (ADR 2026-10.js2p). An attachment its sender declared over the recipient's
 * limit (1 MB by default, as shipped — the lab keeps the default) is fetched on its own only over Wi-Fi Aware; with
 * Bluetooth alone in reach nothing is asked until the user taps "Tap to download" (`MeshController.downloadAttachment`).
 * A Nearby-room post declares its size too since ADR 2026-10.hmfe, so a large room video is held the same way.
 * The lab has no Wi-Fi Aware: `LabTransport.bulkRoutes` declares which plane a file between two nodes would ride, on
 * each end, as the composite's `bulkRouteTo` reports it. The `blobAsks` recorder is the oracle for who was asked, and
 * whether the ask said fast-only.
 */
@RunWith(RobolectricTestRunner::class)
class LargeAttachmentLabTest {
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

    /** The asks for [hash] this node sent that reached a peer. */
    private fun LabNode.asksFor(hash: String) = transport.blobAsks.filter { it.hash == hash }

    private fun LabNode.filesTo(
        to: LabNode,
        hash: String,
    ) = transport.files.count { it == "${to.nodeId} ATTACHMENT $hash" }

    /** Two acquainted nodes on one Bluetooth link, with Alice's 2 MB file DM on its way to Bob, and its id and hash. */
    private suspend fun largeFileToBob(
        alice: LabNode,
        bob: LabNode,
        seed: Int,
    ): Pair<String, String> {
        val file = Random(seed).nextBytes(LARGE_BYTES)
        assertTrue(alice.sendFile(file, "survey.zip", "application/zip", text = "the survey", to = bob))
        val id = alice.ownMessageId(alice.dmWith(bob), "the survey")
        return id to checkNotNull(alice.attachmentHash(alice.dmWith(bob), id))
    }

    private fun dm(
        alice: LabNode,
        bob: LabNode,
    ): (LabNode) -> String = { it.dmWith(if (it === alice) bob else alice) }

    /**
     * Bluetooth alone: Bob holds the message and asks for nothing — not on delivery, not on the tick — until he taps,
     * and then the file crosses the Bluetooth link once.
     */
    @Test
    fun aLargeFileOverBluetoothAloneWaitsForTheTapThenCrossesOnce() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)
            lab.befriend(alice, bob)

            val (_, hash) = largeFileToBob(alice, bob, seed = 2001)
            lab.await(1) { bob.decrypted(bob.dmWith(alice)).size }
            // Delivery stepped the want and held it; the tick re-arms it from the database and holds it again.
            lab.await(1) { if (bob.metrics.files().blobFastOnlyHeld > 0) 1 else 0 }
            val heldBefore = bob.metrics.files().blobFastOnlyHeld
            bob.reoffer()
            lab.await(1) { if (bob.metrics.files().blobFastOnlyHeld > heldBefore) 1 else 0 }
            assertTrue("nothing asked over Bluetooth before the tap: ${bob.asksFor(hash)}", bob.asksFor(hash).isEmpty())
            assertEquals(0, alice.filesTo(bob, hash))

            bob.manager.downloadAttachment(hash)
            lab.assertConverged(listOf(alice, bob), atLeast = 1, conversation = dm(alice, bob))
            assertTrue("the tap's ask is plain: ${bob.asksFor(hash)}", bob.asksFor(hash).run { isNotEmpty() && none { it.fastOnly } })
            assertEquals("one copy", 1, alice.filesTo(bob, hash))
        }

    /**
     * The tap racing the database re-arm (ADR 2026-10.js2p's review). A re-arm reads the rows before the user taps and
     * is parked there, holding the re-arm lock, until the approval is written; it then wants by its stale read. The tap's
     * want waits on the lock and lands after it, so the stale fast-only verdict never outlives the tap, nothing withdraws
     * the tap's ask, and one copy lands. (Which of the lock and the exchange's sticky tap saves it is not told apart here:
     * `BlobExchangeTest.aTapIsStickyAgainstAStaleWantWithItsAskOutstanding` pins the bit alone.)
     */
    @Test
    fun aReArmParkedAcrossTheTapNeverUndoesIt() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)
            lab.befriend(alice, bob)

            val (id, hash) = largeFileToBob(alice, bob, seed = 2006)
            lab.await(1) { bob.decrypted(bob.dmWith(alice)).size }
            lab.await(1) { if (bob.metrics.files().blobFastOnlyHeld > 0) 1 else 0 }

            val parked = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            bob.manager.beforeRewantApplies = {
                bob.manager.beforeRewantApplies = null
                parked.complete(Unit)
                release.await()
            }
            // The copy is held on the link, so the withdrawals are read while the tap's ask stands, before any bytes land:
            // a step around the landing is the store's business, not the race's.
            alice.transport.holdFiles(bob.transport)
            coroutineScope {
                val tick = launch { bob.reoffer() } // reads the rows untapped, then parks under the lock
                parked.await()
                bob.manager.downloadAttachment(hash)
                lab.await(1) { if (bob.row(id)?.attachmentFetchApproved == true) 1 else 0 } // the tap is written
                release.complete(Unit) // the stale re-arm applies its fast-only verdict; then the tap's want
                tick.join()
            }
            lab.await(1) { bob.asksFor(hash).count { !it.fastOnly } } // the tap's plain ask reached Alice
            assertTrue("nothing withdrew the tap's ask: ${bob.transport.blobCancels}", bob.transport.blobCancels.none { it.second == hash })
            alice.transport.releaseFiles(bob.transport)
            lab.assertConverged(listOf(alice, bob), atLeast = 1, conversation = dm(alice, bob))
            assertEquals("one copy", 1, alice.filesTo(bob, hash))
        }

    /** Wi-Fi Aware in reach — linked, or reachable for the holder to raise — fetches it without a tap, asking fast-only. */
    @Test
    fun aLargeFileWithWifiAwareInReachIsFetchedWithoutATap() =
        runBlocking {
            for ((route, seed) in listOf(BulkRoute.Linked to 2002, BulkRoute.Reachable to 2003)) {
                val alice = lab.node("alice-$route").apply { setDisplayName("Alice") }
                val bob = lab.node("bob-$route").apply { setDisplayName("Bob") }
                alice.transport.bulkRoutes[bob.nodeId] = route
                bob.transport.bulkRoutes[alice.nodeId] = route
                lab.link(alice, bob)
                lab.awaitAcquainted(alice, bob)
                lab.befriend(alice, bob)

                val (_, hash) = largeFileToBob(alice, bob, seed)
                lab.assertConverged(listOf(alice, bob), atLeast = 1, conversation = dm(alice, bob))
                assertTrue("$route: asked fast-only: ${bob.asksFor(hash)}", bob.asksFor(hash).run { isNotEmpty() && all { it.fastOnly } })
                assertEquals("$route: one copy", 1, alice.filesTo(bob, hash))
            }
        }

    /** Alice's 2 MB video posted to the Nearby room, and its id and hash: the post declares its size (ADR 2026-10.hmfe). */
    private suspend fun largeRoomVideo(
        alice: LabNode,
        seed: Int,
    ): Pair<String, String> {
        assertTrue(alice.sendVideo(Random(seed).nextBytes(LARGE_BYTES), CLIP, text = "the clip"))
        val id = alice.ownMessageId(Conversations.NEARBY, "the clip")
        return id to checkNotNull(alice.attachmentHash(Conversations.NEARBY, id))
    }

    /**
     * A room post is every node's, and every node that hears it is a carrier too: custody's want runs ahead of the
     * delivery's, and the tick re-arms the carried frame as well as the row. Over Bluetooth alone neither Bob nor Carol
     * asks anyone for Alice's large video until Bob taps; then it crosses to Bob once, and Carol, who never tapped,
     * still holds it to Wi-Fi Aware (ADR 2026-10.hmfe).
     */
    @Test
    fun aLargeRoomVideoOverBluetoothAloneWaitsForTheTapThenCrossesOnce() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            val carol = lab.node("carol").apply { setDisplayName("Carol") }
            lab.link(alice, bob)
            lab.link(alice, carol)
            lab.link(bob, carol)

            val (id, hash) = largeRoomVideo(alice, seed = 2007)
            for (n in listOf(bob, carol)) {
                lab.await(1) { if (n.row(id) != null) 1 else 0 }
                assertEquals("${n.name}: the post declares its size", LARGE_BYTES.toLong(), n.row(id)?.attachmentSize)
                lab.await(1) { if (n.metrics.files().blobFastOnlyHeld > 0) 1 else 0 }
                val heldBefore = n.metrics.files().blobFastOnlyHeld
                n.reoffer()
                lab.await(1) { if (n.metrics.files().blobFastOnlyHeld > heldBefore) 1 else 0 }
            }
            for (n in listOf(bob, carol)) {
                assertTrue("${n.name}: nothing asked over Bluetooth before the tap: ${n.asksFor(hash)}", n.asksFor(hash).isEmpty())
                assertEquals("${n.name}: no copy before the tap", 0, alice.filesTo(n, hash))
            }

            bob.manager.downloadAttachment(hash)
            lab.assertConverged(listOf(alice, bob), atLeast = 1, carriers = listOf(carol)) { Conversations.NEARBY }
            assertTrue("the tap's ask is plain: ${bob.asksFor(hash)}", bob.asksFor(hash).run { isNotEmpty() && none { it.fastOnly } })
            assertEquals("one copy", 1, alice.filesTo(bob, hash) + carol.filesTo(bob, hash))
            carol.reoffer()
            assertTrue("Carol never tapped: ${carol.asksFor(hash)}", carol.asksFor(hash).none { !it.fastOnly })
            assertEquals("Carol took no copy", 0, alice.filesTo(carol, hash) + bob.filesTo(carol, hash))
        }

    /**
     * Two acquainted nodes on one Bluetooth link with Alice's large room video delivered to Bob and held to Wi-Fi Aware,
     * his row read back untapped: the setup of the two re-arm races below, which move that row inside a re-arm.
     */
    private suspend fun heldRoomVideo(
        alice: LabNode,
        bob: LabNode,
        seed: Int,
    ): Triple<String, String, MessageEntity> {
        lab.link(alice, bob)
        lab.awaitAcquainted(alice, bob)
        val (id, hash) = largeRoomVideo(alice, seed)
        lab.await(1) { if (bob.row(id) != null) 1 else 0 }
        lab.await(1) { if (bob.metrics.files().blobFastOnlyHeld > 0) 1 else 0 }
        val row = checkNotNull(bob.row(id))
        assertEquals("the post declares its size", LARGE_BYTES.toLong(), row.attachmentSize)
        assertEquals("untapped", false, row.attachmentFetchApproved)
        return Triple(id, hash, row)
    }

    /**
     * Bob's re-arm — the one that runs the hook which sets [fired], if any — ending in no plain `blobreq` and no `blobhave`
     * for [hash] over Bluetooth, no copy, and one more step held fast-only. Then the tap, and the video crosses once.
     */
    private suspend fun reArmHoldsThenTheTapFetchesOnce(
        alice: LabNode,
        bob: LabNode,
        hash: String,
        fired: AtomicBoolean?,
    ) {
        val heldBefore = bob.metrics.files().blobFastOnlyHeld
        bob.reoffer()
        if (fired != null) assertTrue("the hook ran inside a re-arm", fired.get())
        lab.settle() // lab-lint: allow sleep-as-sync — a pause before checking nothing went over Bluetooth
        assertTrue("no plain ask over Bluetooth before the tap: ${bob.asksFor(hash)}", bob.asksFor(hash).none { !it.fastOnly })
        assertTrue("no holder query before the tap: ${bob.transport.blobQueries}", bob.transport.blobQueries.none { hash in it.hashes })
        assertEquals("no copy before the tap", 0, alice.filesTo(bob, hash))
        lab.await(1) { if (bob.metrics.files().blobFastOnlyHeld > heldBefore) 1 else 0 }

        bob.manager.downloadAttachment(hash)
        lab.assertConverged(listOf(alice, bob), atLeast = 1) { Conversations.NEARBY }
        assertTrue("the tap's ask is plain: ${bob.asksFor(hash)}", bob.asksFor(hash).run { isNotEmpty() && none { it.fastOnly } })
        assertEquals("one copy", 1, alice.filesTo(bob, hash))
    }

    /**
     * A room post's row written in the middle of the database re-arm (#141's finding 6). Custody's read of the carried
     * frames loads a room post's declared size only while no row names its hash (ADR 2026-10.hmfe), so a row the re-arm's
     * row read missed and custody's read then found left the post judged by neither: wanted plain, the hold lifted and
     * the video asked for over Bluetooth before the tap. A row landing between the reads is judged now: custody is read
     * first, and a row-covered post with no size is held until the next re-arm (ADR 2026-10.js2p). This scenario passes
     * on either half alone; the swept-row scenario below pins the second.
     */
    @Test
    fun aRoomPostRowWrittenBetweenTheReArmsReadsKeepsItsHold() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            val (id, hash, row) = heldRoomVideo(alice, bob, seed = 2009)

            bob.db.messageDao().deleteById(id)
            val fired = AtomicBoolean(false)
            bob.manager.betweenRewantReads = {
                bob.manager.betweenRewantReads = null
                bob.db.messageDao().insertIfAbsent(row) // the delivery's row, landing between the reads
                fired.set(true)
            }
            reArmHoldsThenTheTapFetchesOnce(alice, bob, hash, fired)
        }

    /**
     * The mirror: the row goes between the reads (a room cap's sweep, while custody keeps the frame). Custody read the
     * frame row-covered, so it loaded no size, and the row read then found no row: the post is held fast-only until the
     * next re-arm loads its size, never wanted plain on a guess (ADR 2026-10.js2p).
     */
    @Test
    fun aRoomPostRowSweptBetweenTheReArmsReadsKeepsItsHold() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            val (id, hash, row) = heldRoomVideo(alice, bob, seed = 2010)

            val fired = AtomicBoolean(false)
            bob.manager.betweenRewantReads = {
                bob.manager.betweenRewantReads = null
                bob.db.messageDao().deleteById(id)
                fired.set(true)
            }
            val heldBefore = bob.metrics.files().blobFastOnlyHeld
            bob.reoffer()
            assertTrue("the hook ran inside a re-arm", fired.get())
            lab.settle() // lab-lint: allow sleep-as-sync — a pause before checking nothing went over Bluetooth
            assertTrue("no plain ask over Bluetooth: ${bob.asksFor(hash)}", bob.asksFor(hash).none { !it.fastOnly })
            assertTrue("no holder query: ${bob.transport.blobQueries}", bob.transport.blobQueries.none { hash in it.hashes })
            assertEquals("no copy", 0, alice.filesTo(bob, hash))
            lab.await(1) { if (bob.metrics.files().blobFastOnlyHeld > heldBefore) 1 else 0 }

            // The row comes back (as a re-served post would bring it), and the next re-arm judges it by the row again.
            bob.db.messageDao().insertIfAbsent(row)
            reArmHoldsThenTheTapFetchesOnce(alice, bob, hash, fired = null)
        }

    /** Wi-Fi Aware linked: the large room video is fetched without a tap, asking fast-only, one copy. */
    @Test
    fun aLargeRoomVideoWithWifiAwareLinkedIsFetchedWithoutATap() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            alice.transport.bulkRoutes[bob.nodeId] = BulkRoute.Linked
            bob.transport.bulkRoutes[alice.nodeId] = BulkRoute.Linked
            lab.link(alice, bob)

            val (_, hash) = largeRoomVideo(alice, seed = 2008)
            lab.assertConverged(listOf(alice, bob), atLeast = 1) { Conversations.NEARBY }
            assertTrue("asked fast-only: ${bob.asksFor(hash)}", bob.asksFor(hash).run { isNotEmpty() && all { it.fastOnly } })
            assertEquals("one copy", 1, alice.filesTo(bob, hash))
        }

    /**
     * A room picture under the declaring floor says nothing of its size, as a post from an older build or the iOS port
     * says nothing of any, so it is fetched over Bluetooth as it always was.
     */
    @Test
    fun aRoomPictureThatDeclaresNoSizeIsFetchedAsBefore() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)

            assertTrue(alice.sendImage(Random(2004).nextBytes(UNDECLARED_BYTES), "room picture"))
            val id = alice.ownMessageId(Conversations.NEARBY, "room picture")
            val hash = checkNotNull(alice.attachmentHash(Conversations.NEARBY, id))
            lab.assertConverged(listOf(alice, bob), atLeast = 1) { Conversations.NEARBY }
            assertEquals("no size declared", null, bob.row(id)?.attachmentSize)
            assertTrue(bob.asksFor(hash).none { it.fastOnly })
            assertEquals(1, alice.filesTo(bob, hash))
        }

    /** "Never ask": a large file over Bluetooth alone is fetched as it always was. */
    @Test
    fun withTheLimitSetToNeverALargeFileOverBluetoothIsFetched() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            bob.settings.setBluetoothDownloadLimit(BluetoothDownloadLimit.NEVER)
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)
            lab.befriend(alice, bob)

            val (_, hash) = largeFileToBob(alice, bob, seed = 2005)
            lab.assertConverged(listOf(alice, bob), atLeast = 1, conversation = dm(alice, bob))
            assertTrue(bob.asksFor(hash).none { it.fastOnly })
            assertEquals(1, alice.filesTo(bob, hash))
        }

    private companion object {
        // Past the 1 MB default limit: its sender declares it, and its recipient holds it to Wi-Fi Aware.
        const val LARGE_BYTES = 2_000_000

        // Under `BluetoothFetchGate.DECLARE_SIZE_FLOOR`: its sender declares no size, so no limit can hold it.
        const val UNDECLARED_BYTES = 900_000

        // The sender's label for the clip; the lab's bytes hold no video, so nothing derives over it.
        val CLIP = VideoInfo(durationMs = 30_000L, width = 480, height = 854, poster = byteArrayOf(1, 2, 3))
    }
}
