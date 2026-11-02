package app.getknit.knit.mesh.lab

import app.getknit.knit.data.forward.ForwardRepository
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.mesh.BlobExchange
import app.getknit.knit.mesh.protocol.ProfileContent
import app.getknit.knit.mesh.protocol.WireCodec
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
 * The local presentation decisions the mesh must never observe (ADR 009, ADR 010, ADR 2026-09.adgd): a
 * message request that was never accepted is custodied and relayed like any DM, a blocked sender's room post
 * is still acked — blocking stays invisible to the blocked party — while never surfacing on the blocker, and
 * a removed contact is a stranger again on the remover alone. Each ADR says the decision is "never folded
 * into custody/relay"; the full oracle is what checks that.
 */
@RunWith(RobolectricTestRunner::class)
class BlockAndRequestLabTest {
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
     * Finding #45 (2026-09-14, first run), decided the same day as ADR 2026-09.bts9: the delivery half always
     * held — Bob's room never shows the post and Alice still gets Bob's tick — but `InboundPipeline.canCarry`
     * refused a blocked author, so Bob's live set lacked every frame Alice sent while Carol's held them, and
     * the push-based digest exchange re-served them to Bob every round for as long as the block stood. The
     * block list left the carry gate; Bob rides the custody oracle as a carrier and must hold what Carol holds.
     */
    @Test
    fun aBlockedSendersRoomPostIsStillAckedAndBlockingStaysInvisible() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            val carol = lab.node("carol").apply { setDisplayName("Carol") }
            lab.linkAll(alice to bob, bob to carol, alice to carol)
            lab.awaitAcquainted(alice, bob, carol)

            bob.block(alice)
            assertTrue(alice.sendRoom("from someone bob blocked"))
            val post = alice.ownMessageId(Conversations.NEARBY, "from someone bob blocked")
            lab.awaitReceipt(alice, post, bob)
            lab.awaitReceipt(alice, post, carol)
            assertNull("bob's room never shows the post", bob.roomPosts()[alice.nodeId])

            lab.assertConverged(listOf(alice, carol), atLeast = 1, carriers = listOf(bob)) { Conversations.NEARBY }
        }

    /** A message request Carol never answers is still carried by Bob and ticked by Carol: presentation only. */
    @Test
    fun aMessageRequestIsCustodiedAndRelayedLikeAnyDm() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            val carol = lab.node("carol").apply { setDisplayName("Carol") }
            lab.linkAll(alice to bob, bob to carol)
            lab.awaitAcquainted(alice, bob, carol)

            assertTrue(alice.sendDm(carol, "a request"))
            lab.assertConverged(listOf(alice, carol), atLeast = 1, carriers = listOf(bob)) { it.dmWith(if (it === alice) carol else alice) }

            carol.accept(alice.nodeId)
            assertTrue(carol.sendDm(alice, "accepted"))
            lab.assertConverged(listOf(alice, carol), atLeast = 2, carriers = listOf(bob)) { it.dmWith(if (it === alice) carol else alice) }
        }

    /**
     * ADR 2026-09.adgd: Alice removes Bob. The thread goes from her phone only, and Bob — who is not told —
     * writes again: the DM lands as a request, and Alice still ticks it, as she ticks any request. Nothing the
     * mesh converges on moved: custody, profiles and the session hold across all three, which the room's full
     * oracle checks (the DM oracle cannot, since Alice legitimately dropped the old messages Bob still holds).
     */
    @Test
    fun aRemovedContactsNextDmIsARequestAndTheRemovalStaysInvisible() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            val carol = lab.node("carol").apply { setDisplayName("Carol") }
            lab.linkAll(alice to bob, bob to carol, alice to carol)
            lab.awaitAcquainted(alice, bob, carol)

            assertTrue(alice.sendDm(bob, "hi bob"))
            lab.awaitReceipt(alice, alice.ownMessageId(alice.dmWith(bob), "hi bob"), bob)
            assertTrue(bob.sendDm(alice, "hi alice"))
            lab.awaitReceipt(bob, bob.ownMessageId(bob.dmWith(alice), "hi alice"), alice)
            assertTrue("a DM she wrote in makes Bob a contact", alice.isContact(bob))

            alice.removeContact(bob)
            assertFalse(alice.isContact(bob))
            assertEquals(emptySet<Pair<String, String>>(), alice.decrypted(alice.dmWith(bob)))

            assertTrue(bob.sendDm(alice, "still there?"))
            lab.awaitReceipt(bob, bob.ownMessageId(bob.dmWith(alice), "still there?"), alice)
            assertEquals(setOf("still there?"), alice.decrypted(alice.dmWith(bob)).map { it.second }.toSet())
            assertTrue("the new DM is a message request", alice.isRequest(bob))
            assertFalse(alice.isContact(bob))
            assertTrue("Bob's side is untouched", bob.isContact(alice))

            assertTrue(alice.sendRoom("after the removal"))
            lab.assertConverged(listOf(alice, bob, carol), atLeast = 1) { Conversations.NEARBY }
        }

    /**
     * ADR 2026-11.nsbf (#249): Alice blocks Mallory, who then renames and sets a new photo while Bob relays and carries.
     * Alice keeps the name Mallory had at the block, draws the monogram and fetches nothing; Bob shows the new name and
     * photo; custody is identical on all three, Alice riding the oracle as a carrier because her presentation of Mallory
     * is the one thing allowed to differ. Unblocked, Alice applies the profile she held and fetches the photo, and the
     * full oracle — profiles included — holds across all three.
     */
    @Test
    fun aBlockHoldsTheBlockedPersonsNewNameAndPhotoUntilTheUnblock() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            val mallory = lab.node("mallory").apply { setDisplayName("Mallory") }
            lab.linkAll(alice to bob, bob to mallory)
            lab.awaitAcquainted(alice, bob, mallory)
            // A push that outruns the profile naming it is kept unadopted (#253, ADR 2026-11.4ckq), so a start-up collection
            // still pending would take it and leave the re-arm to ask again (#256's heal, the scenario below): not this one's.
            lab.await(1) { if (bob.manager.startSweepsDone) 1 else 0 }

            // The face a block clears is the neighbour scenario's below; here Mallory has none yet.
            awaitBlockWatch(alice)
            alice.block(mallory)
            assertEquals("Mallory", alice.peer(mallory)?.name)

            mallory.setDisplayName("Mallory Two")
            // No wait for Bob to hold the rename before the photo goes (#253, ADR 2026-11.4ckq): a push that outruns the
            // rename's profile waits for the profile naming it, and one lost on the way is asked for again.
            val second = Random(12).nextBytes(2_048)
            val secondHash = sha256Hex(second)
            mallory.setAvatar(second)
            val version = mallory.ownPresentation().version
            assertTrue(mallory.sendRoom("after the rename"))
            convergedOnThePush(mallory, bob, alice, version, secondHash)
            lab.await(1) { if (bob.peer(mallory)?.avatarHash == secondHash) 1 else 0 }
            // The profile reached Alice — its version moved her row's watermark — and its presentation did not: the name and
            // face asserts share that commit and carry the claim. The two absence checks after them are secondary: the pull a
            // regression would make runs after the commit, and Bob's relay jitter orders nothing, so on some schedules they
            // pass before it would have.
            lab.await(1) { if ((alice.peer(mallory)?.updatedAt ?: 0L) >= version) 1 else 0 }
            lab.await(1) { if (alice.heldPhotoOf(mallory) == secondHash) 1 else 0 }
            val held = checkNotNull(alice.peer(mallory))
            assertEquals("Mallory", held.name)
            assertNull(held.avatarHash)
            assertFalse("alice fetched the blocked person's photo", alice.holdsBlob(secondHash))
            assertFalse("alice wants the blocked person's photo", secondHash in alice.manager.wantedBlobs())

            alice.unblock(mallory)
            lab.await(1) { if (alice.peer(mallory)?.avatarHash == secondHash) 1 else 0 }
            assertEquals("Mallory Two", alice.peer(mallory)?.name)
            assertTrue(second.contentEquals(alice.blobs.bytes(secondHash)))
            // The room post sent during the block stays unseen (ADR 2026-09.bts9), so the full oracle runs over a DM.
            assertTrue(mallory.sendDm(alice, "unblocked?"))
            lab.assertConverged(listOf(alice, mallory), atLeast = 1, carriers = listOf(bob)) {
                it.dmWith(if (it === alice) mallory else alice)
            }
        }

    /**
     * Contract revision 2: the face an unblock owes outlives its want. Bob, a linked neighbour, pushes his photo once per
     * hash, so once Alice has blocked him and let the bytes go, and unblocks him while her mesh is stopped, nothing but
     * her own want brings the photo back — and that want lapses with the fetch TTL. The owed face is wanted again at the
     * relink, and Bob's push memo still sends nothing.
     */
    @Test
    fun anUnblockedNeighboursFaceComesBackAfterARestartAndALapsedWant() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)
            // Bob's push can land before the profile naming his photo, and is then kept unadopted until it does (#253, ADR
            // 2026-11.4ckq): a start-up collection still pending on Alice would take it, for the tick's re-arm to fetch
            // again, past this scenario's first oracle (the late face-null of the stage-2 sweep, seed 871921833).
            lab.await(2) { listOf(alice, bob).count { it.manager.startSweepsDone } }
            val picture = Random(21).nextBytes(2_048)
            val hash = sha256Hex(picture)
            bob.setAvatar(picture)
            assertTrue(bob.sendRoom("new face"))
            lab.assertConverged(listOf(alice, bob), atLeast = 1) { Conversations.NEARBY }
            lab.await(1) { if (alice.peer(bob)?.avatarHash == hash) 1 else 0 }
            awaitBlockWatch(alice)
            alice.block(bob)
            // Read after the wait, never as `assertTrue`'s message, which runs before its condition does.
            if (!lab.tryAwait(1) { if (alice.peer(bob)?.avatarHash == null) 1 else 0 }) {
                val report = lab.report(listOf(alice, bob))
                throw AssertionError("the block never cleared the face\n${blockState(alice)}\n${faceState(alice, bob, hash)}\n$report")
            }

            // Custody may keep a blocked person's photo as a carried attachment (ADR 2026-09.bts9). Once the frames naming it
            // expire, nothing does, and the start's orphan collection lets it go: the bytes are gone, as after any block.
            lab.unlink(alice, bob)
            // lab-lint: allow clock-jump-over-48h — 24 h here and 31 min below, under 48 h before the restarts' sweeps
            lab.clock.advance(ForwardRepository.DEFAULT_TTL_MS + MINUTE_MS)
            alice.sweepExpired()
            alice.restart()
            // The start's orphan collection runs on the session after the boot returns.
            assertTrue("the blocked person's photo outlived its custody", lab.tryAwait(1) { if (alice.holdsBlob(hash)) 0 else 1 })

            alice.restart { unblock(bob) }
            // The watch diffs its first value against the list it last acted on: the unblock made while down is acted on. The face
            // is recorded as owed after the unblock's own want, so the sweep below finds that want to take.
            assertTrue(
                "the unblock made while down was never acted on",
                lab.tryAwait(1) {
                    alice.settings.blockList.owedFaces
                        .first()
                        .size
                },
            )
            // The unblock's want lapses. The start's own re-arm may have wanted it again since — the owed face doing its job —
            // and the relink below is what has to bring it back either way.
            lab.clock.advance(BlobExchange.FETCH_TTL_MS + MINUTE_MS)
            alice.sweepExpired()
            // The start's orphan collection has run first. Without this wait, one still pending when the photo lands takes its
            // bytes before the face names them (#256), and the re-arm heals that (ADR 2026-11.4ckq; the owed face counts as
            // not shown while its bytes are gone, `InboundPipelineTest`'s dangling owed-face case, and
            // `PeerFaceLabTest`'s restart scenario reaches the state on its own). Here it failed the full oracle 2 runs in
            // 200 after the face had come back, a cause not pinned: the wait keeps this scenario to the owed face.
            lab.await(1) { if (alice.manager.startSweepsDone) 1 else 0 }

            lab.link(alice, bob)
            awaitOwedFaceBack(alice, bob, hash, picture)
            // The entry settles on the next database re-arm, the 60 s tick here.
            alice.reoffer()
            assertTrue(
                "the owed face never settled",
                alice.settings.blockList.owedFaces
                    .first()
                    .isEmpty(),
            )
            assertTrue(bob.sendRoom("back again"))
            try {
                lab.assertConverged(listOf(alice, bob), atLeast = 2) { Conversations.NEARBY }
            } catch (e: AssertionError) {
                val row = alice.peer(bob)
                throw AssertionError(
                    "${e.message}\n  alice: holds ${hash.take(8)} ${alice.holdsBlob(hash)}, face ${row?.avatarHash?.take(8)}, " +
                        "advertised ${row?.advertisedAvatar?.take(8)}\n" +
                        org.robolectric.shadows.ShadowLog
                            .getLogs()
                            .filter { hash.take(8) in it.msg }
                            .joinToString("\n") { "  ${it.tag}: ${it.msg}" },
                    e,
                )
            }
        }

    /**
     * Waits for [alice] to show [bob]'s owed face with its bytes, over her ticks if need be: a face whose bytes went (#256)
     * heals in a tick that clears it and wants it again, once Bob's memo of the copy he just sent has lapsed. The lab's
     * ticks and calendar are moved by hand.
     */
    private suspend fun awaitOwedFaceBack(
        alice: LabNode,
        bob: LabNode,
        hash: String,
        picture: ByteArray,
    ) {
        var back = false
        repeat(HEAL_TICKS) {
            back = back ||
                lab.tryAwait(1, TICK_WAIT_MS) {
                    if (alice.peer(bob)?.avatarHash == hash && picture.contentEquals(alice.blobs.bytes(hash))) 1 else 0
                }
            if (!back) {
                lab.clock.advance(BlobExchange.SERVE_MEMO_MS + 1)
                alice.reoffer()
            }
        }
        val held = alice.blobs.bytes(hash)
        assertTrue(
            "the owed face never came back: face ${alice.peer(bob)?.avatarHash}, bytes ${held?.size ?: "absent"} of ${picture.size}, " +
                "owed ${alice.settings.blockList.owedFaces.first()}, wanted ${hash in alice.manager.wantedBlobs()}, " +
                "bob files ${bob.transport.files.filter { hash in it }}, alice files ${alice.transport.files.filter { hash in it }}, " +
                "arriving ${alice.transport.arrivingFiles().keys.map {
                    it.take(
                        8,
                    )
                }}, linked ${alice.transport.neighbors.value.map { it.nodeId.take(6) }}\n" +
                org.robolectric.shadows.ShadowLog
                    .getLogs()
                    .filter { hash.take(8) in it.msg }
                    .joinToString("\n") { "  ${it.tag}: ${it.msg}" },
            back,
        )
    }

    /**
     * The full oracle over [author] and [neighbour], [carrier] carrying, once [neighbour] holds [author]'s profile at
     * [version], naming photo [hash]. A push that never reached [neighbour] is asked for by its tick's re-arm, of [author]
     * alone, and served once [author]'s push memo lapses (ADR 2026-11.4ckq): one tick covers it, so a push lost on a loaded
     * run fails nothing (seed 536960443: the neighbour held the new name with no face once in 200, not reproduced in 400
     * more). A failure names what the neighbour holds of the photo.
     */
    private suspend fun convergedOnThePush(
        author: LabNode,
        neighbour: LabNode,
        carrier: LabNode,
        version: Long,
        hash: String,
    ) {
        lab.await(1) { if ((neighbour.peer(author)?.updatedAt ?: 0L) >= version) 1 else 0 }
        if (!lab.tryAwait(1, TICK_WAIT_MS) { if (neighbour.peer(author)?.avatarHash == hash) 1 else 0 }) {
            lab.clock.advance(BlobExchange.SERVE_MEMO_MS + 1)
            neighbour.reoffer()
        }
        try {
            lab.assertConverged(listOf(author, neighbour), atLeast = 1, carriers = listOf(carrier)) { Conversations.NEARBY }
        } catch (e: AssertionError) {
            throw AssertionError("${e.message}\n${faceState(neighbour, author, hash)}", e)
        }
    }

    /** What [node] holds of [peer]'s photo [hash], and every log line naming it: an oracle failure's context. */
    private suspend fun faceState(
        node: LabNode,
        peer: LabNode,
        hash: String,
    ): String {
        val row = node.peer(peer)
        return "  ${node.name}: holds ${hash.take(8)} ${node.holdsBlob(hash)}, face ${row?.avatarHash?.take(8)}, " +
            "advertised ${row?.advertisedAvatar?.take(8)}; ${peer.name} files ${peer.transport.files.filter { hash in it }}\n" +
            org.robolectric.shadows.ShadowLog
                .getLogs()
                .filter { hash.take(8) in it.msg }
                .joinToString("\n") { "  ${it.tag}: ${it.msg}" }
    }

    /** What [node]'s block-list watch has seen and acted on, and every hook it failed: a block oracle's context. */
    private suspend fun blockState(node: LabNode): String =
        "  ${node.name}: watch ready ${node.manager.blockListWatch.ready.value}, " +
            "blocked ${node.settings.blockedNodeIds.first().map { it.take(6) }}, " +
            "acted on ${node.settings.blockList.actedOn.first()?.map { it.take(6) }}\n" +
            org.robolectric.shadows.ShadowLog
                .getLogs()
                .filter { "block-list" in it.msg }
                .joinToString("\n") { "  ${it.tag}: ${it.msg}" }

    /** Waits for [node]'s block-list watch to hold its first value, so a block made next is a diff it acts on. */
    private suspend fun awaitBlockWatch(node: LabNode) = lab.await(1) { if (node.manager.blockListWatch.ready.value) 1 else 0 }

    /** The photo the profile frame [this] holds for [peer] advertises (`peer_profiles`), null when none is held. */
    private suspend fun LabNode.heldPhotoOf(peer: LabNode): String? =
        peers
            .profileFrame(peer.nodeId)
            ?.let { WireCodec.decodeEnvelope(it.signed) }
            ?.let { WireCodec.decodePayload<ProfileContent>(it.payload) }
            ?.avatarHash

    private companion object {
        const val MINUTE_MS = 60_000L
        const val HEAL_TICKS = 8
        const val TICK_WAIT_MS = 10_000L
    }
}
