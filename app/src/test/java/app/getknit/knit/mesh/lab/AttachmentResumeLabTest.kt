package app.getknit.knit.mesh.lab

import app.getknit.knit.mesh.BlobExchange
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.random.Random

/**
 * A picture cut off by a link drop resumes from the bytes the receiver kept instead of starting over (work item
 * #116, ADR 2026-10.wtyc). The cuts are `LabTransport.streamFiles` plus `lab.unlink`: the holder's serve crosses
 * into the receiver's real `FileIntake` up to a mark and parks, and the unlink is `FramedLink.close()` — the
 * receiver's `PartialBlobs` keeps the prefix, its next ask names the length, and a holder streams only the rest,
 * spliced by the same intake. The oracle is the holders' `files` lines (a resume ends ` from <offset>`, as the
 * phone's `file …` line does), the asks that crossed, and the plaintext on the receiver.
 */
@RunWith(RobolectricTestRunner::class)
class AttachmentResumeLabTest {
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

    /** Waits until each node has run its link-up hooks toward each of its peers (its digest goes last). */
    private suspend fun awaitLinkUpHooks(vararg pairs: Pair<LabNode, LabNode>) =
        lab.await(1) { if (pairs.all { (node, peer) -> peer.nodeId in node.transport.digestsSent }) 1 else 0 }

    /**
     * 1 once [node] holds [hash] and has dropped the prefix it kept: the landing's last step. The blob row alone is
     * not it — the store writes the row before `BlobExchange.onReceived` drops the prefix.
     */
    private suspend fun landedAndDropped(
        node: LabNode,
        hash: String,
    ): Int = if (node.blobs.exists(hash) && node.partials.length(hash) == 0L) 1 else 0

    /**
     * The offsets of [node]'s asks for [hash] that crossed, a run of equal ones counted once: the rounds' asks, not how
     * many went. A want step that lands after a link comes up and before the node's link-up pass asks the newcomer, and
     * the pass's `BlobExchange.linked` forgets that ask (one made before may have died with the old link) and asks it
     * again (ADR 2026-10.f7gg). The holder's serve memo answers the repeat with nothing, so the `files` lines, not the
     * asks, count the copies.
     */
    private fun askedOffsets(
        node: LabNode,
        hash: String,
    ): List<Long?> =
        node.transport.blobAsks
            .filter { it.hash == hash }
            .map { it.offset }
            .fold(mutableListOf()) { runs, offset -> runs.apply { if (isEmpty() || last() != offset) add(offset) } }

    /** What [holder] streamed [to] [hash], as the phone's `file …` lines name it. */
    private fun served(
        holder: LabNode,
        to: LabNode,
        hash: String,
    ) = holder.transport.files.filter { it.startsWith("${to.nodeId} ATTACHMENT $hash") }

    /**
     * The issue's scenario: a 1 MB picture whose link to Bob is cut at 40 %, served on by a second holder and cut
     * again at 80 %, and finished by the first — two holders, two cuts, and not one byte sent twice.
     */
    @Test
    fun aPictureCutTwiceIsFinishedByTwoHoldersWithoutResendingAByte() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            val carol = lab.node("carol").apply { setDisplayName("Carol") }
            lab.linkAll(alice to bob, alice to carol)
            lab.awaitAcquainted(alice, bob)
            lab.awaitAcquainted(alice, carol)
            awaitLinkUpHooks(bob to alice, carol to alice)

            // Alice's link to Bob drops 400 000 bytes in. Carol carries the frame and pulls the bytes whole.
            alice.transport.streamFiles(bob.transport, untilBytes = FIRST_CUT)
            val picture = Random(16).nextBytes(PICTURE_BYTES)
            check(alice.sendImage(picture, "for bob", to = bob))
            val id = alice.ownMessageId(alice.dmWith(bob), "for bob")
            val hash = checkNotNull(alice.attachmentHash(alice.dmWith(bob), id))
            lab.await(1) { alice.transport.heldFiles(bob.transport).count { it == hash } }
            // A carried attachment's ask leaves from the custody step, before Bob opens the DM: the parked serve is no
            // evidence he handled it. His tick is the last thing that handling sends — the link may go once it is in.
            lab.awaitReceipt(alice, id, bob)
            lab.await(1) { if (carol.blobs.exists(hash)) 1 else 0 }
            assertEquals("Bob's ring reads the stream's real count", FIRST_CUT, bob.transport.arrivingFiles()[hash]?.bytes)
            lab.unlink(alice, bob)
            assertEquals("the cut keeps what came", FIRST_CUT, bob.partials.length(hash))

            // Carol comes into range: Bob's link-up re-ask names the 400 000 he kept, and her link drops at 800 000.
            lab.link(carol, bob) { carol.transport.streamFiles(bob.transport, untilBytes = SECOND_CUT) }
            lab.await(1) { carol.transport.heldFiles(bob.transport).count { it == hash } }
            assertEquals(listOf("${bob.nodeId} ATTACHMENT $hash from $FIRST_CUT"), served(carol, bob, hash))
            assertEquals(SECOND_CUT, bob.transport.arrivingFiles()[hash]?.bytes)
            lab.unlink(carol, bob)
            assertEquals("the second cut got further, onto the first", SECOND_CUT, bob.partials.length(hash))

            // Alice is back once her serve memo has lapsed: she streams only the last fifth, and it lands whole.
            lab.clock.advance(BlobExchange.SERVE_MEMO_MS)
            lab.link(alice, bob)
            // The store writes the blob row before `onReceived` drops the prefix: wait for the drop, not the row.
            lab.await(1) { landedAndDropped(bob, hash) }
            assertEquals(
                "Alice's two serves: the whole file she was cut off from, then only its tail",
                listOf("${bob.nodeId} ATTACHMENT $hash", "${bob.nodeId} ATTACHMENT $hash from $SECOND_CUT"),
                served(alice, bob, hash),
            )
            assertEquals(listOf(null, FIRST_CUT, SECOND_CUT), askedOffsets(bob, hash))

            lab.link(bob, carol)
            lab.assertConverged(listOf(alice, bob), atLeast = 1, carriers = listOf(carol)) { it.dmWith(if (it === alice) bob else alice) }
            assertArrayEquals(picture, bob.attachmentPlain(bob.dmWith(alice), id))
        }

    /**
     * A holder on an older build (or the iOS port before its companion change) skips the offset and streams the
     * whole file: Bob takes it from byte 0, as every transfer did before, and the kept prefix goes when it lands.
     */
    @Test
    fun aHolderThatIgnoresTheOffsetStillFinishesThePicture() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)
            awaitLinkUpHooks(bob to alice)

            alice.transport.streamFiles(bob.transport, untilBytes = FIRST_CUT)
            val picture = Random(17).nextBytes(PICTURE_BYTES)
            check(alice.sendImage(picture, "for bob", to = bob))
            val id = alice.ownMessageId(alice.dmWith(bob), "for bob")
            val hash = checkNotNull(alice.attachmentHash(alice.dmWith(bob), id))
            lab.await(1) { alice.transport.heldFiles(bob.transport).count { it == hash } }
            lab.awaitReceipt(alice, id, bob) // Bob handled the DM: its ask went out before he opened it
            lab.unlink(alice, bob)
            assertEquals(FIRST_CUT, bob.partials.length(hash))

            alice.transport.ignoreOffsets = true
            lab.clock.advance(BlobExchange.SERVE_MEMO_MS)
            lab.link(alice, bob)
            lab.await(1) { landedAndDropped(bob, hash) }
            assertEquals(
                "Bob asked to resume",
                FIRST_CUT,
                bob.transport.blobAsks
                    .last { it.hash == hash }
                    .offset,
            )
            assertEquals(
                "and was sent the whole file, twice over",
                listOf("${bob.nodeId} ATTACHMENT $hash", "${bob.nodeId} ATTACHMENT $hash"),
                served(alice, bob, hash),
            )
            lab.assertConverged(listOf(alice, bob), atLeast = 1) { it.dmWith(if (it === alice) bob else alice) }
            assertArrayEquals(picture, bob.attachmentPlain(bob.dmWith(alice), id))
        }

    /**
     * The first holder's bytes were wrong, so the prefix Bob kept is: the spliced file fails the store's hash
     * check, the prefix goes, and Bob asks for the whole picture from byte 0 at once. Alice's serve memo holds that
     * ask (she served him a moment ago); her next round serves the whole file, and it lands.
     */
    @Test
    fun aSpliceThatFailsItsHashDropsThePrefixAndThePictureStartsOver() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)
            awaitLinkUpHooks(bob to alice)

            alice.transport.corruptFiles = true
            alice.transport.streamFiles(bob.transport, untilBytes = FIRST_CUT)
            val picture = Random(18).nextBytes(PICTURE_BYTES)
            check(alice.sendImage(picture, "for bob", to = bob))
            val id = alice.ownMessageId(alice.dmWith(bob), "for bob")
            val hash = checkNotNull(alice.attachmentHash(alice.dmWith(bob), id))
            lab.await(1) { alice.transport.heldFiles(bob.transport).count { it == hash } }
            lab.awaitReceipt(alice, id, bob) // Bob handled the DM: its ask went out before he opened it
            lab.unlink(alice, bob)
            assertEquals("a bad prefix, kept like any other", FIRST_CUT, bob.partials.length(hash))

            alice.transport.corruptFiles = false
            lab.clock.advance(BlobExchange.SERVE_MEMO_MS)
            lab.link(alice, bob)
            lab.await(1) {
                bob.metrics
                    .files()
                    .splicesRefused
                    .toInt()
            }
            // The from-0 ask follows the refusal at once; the memo answers it with nothing. Awaited as the ask itself (a
            // whole-file ask after the resume), not as a count: the link-up may have asked the resume twice, and a repeat
            // whose send stalled can even cross after the from-0 ask (built before the drop; the memo answers it too).
            lab.await(1) { if (askedOffsets(bob, hash).size >= 3) 1 else 0 }
            assertEquals(listOf(null, FIRST_CUT, null), askedOffsets(bob, hash).take(3))
            assertEquals("the prefix may have been what was wrong", 0L, bob.partials.length(hash))

            // Alice's next round: the link-up re-ask, once her memo has lapsed.
            lab.clock.advance(BlobExchange.SERVE_MEMO_MS)
            lab.unlink(alice, bob)
            lab.link(alice, bob)
            lab.await(1) { if (bob.blobs.exists(hash)) 1 else 0 }
            assertEquals(
                listOf(
                    "${bob.nodeId} ATTACHMENT $hash",
                    "${bob.nodeId} ATTACHMENT $hash from $FIRST_CUT",
                    "${bob.nodeId} ATTACHMENT $hash",
                ),
                served(alice, bob, hash),
            )
            lab.assertConverged(listOf(alice, bob), atLeast = 1) { it.dmWith(if (it === alice) bob else alice) }
            assertArrayEquals(picture, bob.attachmentPlain(bob.dmWith(alice), id))
        }

    private companion object {
        /** A megabyte: well past the 128 KiB bulk gate, well inside the 2 MB CursorWindow a blob row is read through. */
        const val PICTURE_BYTES = 1_000_000
        const val FIRST_CUT = 400_000L
        const val SECOND_CUT = 800_000L
    }
}
