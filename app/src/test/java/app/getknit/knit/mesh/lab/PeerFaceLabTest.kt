package app.getknit.knit.mesh.lab

import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.peer.PeerEntity
import app.getknit.knit.mesh.BlobExchange
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.sha256Hex
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
 * A peer's photo lost in its push or its pull is asked for again from the database (#258, ADR 2026-11.4ckq). The
 * receiver records the photo each profile advertises at once, and the 60 s tick, a link-up and the start want every
 * advertised photo whose bytes are not held; a linked author is asked alone, since it pushes the photo itself and its
 * push may be in its stack buffer, and the author's push stamps its serve memo, so a lost push is served again once the
 * memo lapses. Each scenario ends in the full oracle, profiles included.
 */
@RunWith(RobolectricTestRunner::class)
class PeerFaceLabTest {
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
     * Waits for [nodes]' start-up collection. A push whose profile has not landed is kept unadopted (#253), so a start-up
     * orphan collection still pending takes its bytes; the next re-arm asks for them again, which is #256's heal and
     * `BlockAndRequestLabTest`'s subject, not these scenarios'.
     */
    private suspend fun startSwept(vararg nodes: LabNode) = lab.await(nodes.size) { nodes.count { it.manager.startSweepsDone } }

    /** Copies of [hash] [from] sent [to], pushed or served: the phone's `file …` lines. */
    private fun copies(
        from: LabNode,
        to: LabNode,
        hash: String,
    ) = from.transport.files.count { it.startsWith("${to.nodeId} ") && it.endsWith(" $hash") }

    private fun LabNode.asksFor(
        holder: LabNode,
        hash: String,
    ) = transport.blobAsks.count { it.to == holder.nodeId && it.hash == hash }

    /** [node] shows [hash] as [peer]'s face, with the bytes held whole. */
    private suspend fun showsFace(
        node: LabNode,
        peer: LabNode,
        hash: String,
        bytes: ByteArray,
    ): Boolean = node.peer(peer)?.avatarHash == hash && bytes.contentEquals(node.blobs.bytes(hash))

    private suspend fun awaitFace(
        node: LabNode,
        peer: LabNode,
        hash: String,
        bytes: ByteArray,
    ) {
        if (lab.tryAwait(1) { if (showsFace(node, peer, hash, bytes)) 1 else 0 }) return
        val row = node.peer(peer)
        throw AssertionError(
            "${node.name} never showed ${peer.name}'s photo: face ${row?.avatarHash}, advertised ${row?.advertisedAvatar} " +
                "(known ${row?.advertisedKnown}), held ${node.holdsBlob(hash)}, wanted ${hash in node.manager.wantedBlobs()}, " +
                "arriving ${node.transport.arrivingFiles().keys.map {
                    it.take(
                        8,
                    )
                }}, asks ${node.transport.blobAsks.filter { it.hash == hash }}" +
                "\n${blobLines(hash)}\n${lab.report(listOf(node, peer))}",
        )
    }

    /** Every log line any node's stack wrote naming [hash]'s first characters: the asks, holds and serves around it. */
    private fun blobLines(hash: String): String =
        org.robolectric.shadows.ShadowLog
            .getLogs()
            .filter { hash.take(8) in it.msg }
            .joinToString("\n") { "  ${it.tag}: ${it.msg}" }

    /**
     * Heals [node]'s want of [peer]'s photo over its own ticks, with [holder] the neighbour it asks. The first ask is
     * refused by design — [holder]'s memo of the copy it sent before (a push, or a serve the restart or the start-up
     * collection lost) — so the scenario awaits that ask, moves the calendar past the memo and runs the 60 s tick. A face
     * whose bytes the start-up collection took (#256) costs one more round: a tick clears it and wants it again, and the
     * holder's memo of the copy it just served must lapse; at most [HEAL_ROUNDS] of those.
     */
    private suspend fun tickUntilFace(
        node: LabNode,
        peer: LabNode,
        holder: LabNode,
        hash: String,
        bytes: ByteArray,
    ) {
        // The copy may come at once all the same (a memo that lapsed on a slow run): then there is nothing to heal.
        // Or a face naming the hash with its bytes gone (#256: a copy landed while the start-up collection was pending):
        // the ticks below heal that too.
        val started: suspend () -> Boolean = { node.asksFor(holder, hash) > 0 || node.peer(peer)?.avatarHash == hash }
        if (!lab.tryAwait(1) { if (started()) 1 else 0 }) {
            val row = node.peer(peer)
            throw AssertionError(
                "${node.name} neither asked ${holder.name} nor showed ${peer.name}'s photo: " +
                    "wanted ${hash in node.manager.wantedBlobs()}, " +
                    "held ${node.holdsBlob(hash)}, face ${row?.avatarHash}, advertised ${row?.advertisedAvatar}, seen ${row?.lastSeenAt} " +
                    "at ${lab.clock.now()}, swept ${node.manager.startSweepsDone}\n${blobLines(hash)}\n${lab.report(listOf(node, holder))}",
            )
        }
        if (showsFace(node, peer, hash, bytes)) return
        holder.transport.awaitInboundDrained()
        repeat(HEAL_ROUNDS) {
            if (lab.tryAwait(1, SETTLE_WAIT_MS) { if (showsFace(node, peer, hash, bytes)) 1 else 0 }) return
            lab.clock.advance(BlobExchange.SERVE_MEMO_MS + 1)
            node.reoffer()
            if (lab.tryAwait(1, TICK_WAIT_MS) { if (showsFace(node, peer, hash, bytes)) 1 else 0 }) return
        }
        awaitFace(node, peer, hash, bytes)
    }

    /** Waits for [node] to ask [holder] for [hash]; fails naming what [node] holds and wants of it. */
    private suspend fun awaitAsk(
        node: LabNode,
        holder: LabNode,
        hash: String,
    ) {
        if (lab.tryAwait(1) { node.asksFor(holder, hash) }) return
        val row = node.peer(holder)
        throw AssertionError(
            "${node.name} never asked ${holder.name} for $hash: wanted ${hash in node.manager.wantedBlobs()}, held " +
                "${node.holdsBlob(hash)}, face ${row?.avatarHash}, advertised ${row?.advertisedAvatar}, seen ${row?.lastSeenAt} " +
                "at ${lab.clock.now()}, arriving ${node.transport.arrivingFiles().keys}\n${lab.report(listOf(node, holder))}",
        )
    }

    /** A DM each way, so the oracle has a thread to read across [a] and [b]. */
    private suspend fun converge(
        a: LabNode,
        b: LabNode,
        carriers: List<LabNode> = emptyList(),
    ) {
        assertTrue(a.sendDm(b, "hello"))
        lab.await(1) { b.decrypted(b.dmWith(a)).size }
        assertTrue(b.sendDm(a, "hello back"))
        lab.assertConverged(listOf(a, b), atLeast = 2, carriers = carriers) { it.dmWith(if (it === a) b else a) }
    }

    @Test
    fun aPushTheLinkLostWhileItStayedUpIsAskedOfItsAuthorAndServedOnceItsMemoLapses() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)
            startSwept(alice, bob)
            val photo = Random(2581).nextBytes(2_048)
            val hash = sha256Hex(photo)

            bob.transport.holdFiles(alice.transport)
            bob.setAvatar(photo)
            lab.await(1) { if (hash in bob.transport.heldFiles(alice.transport)) 1 else 0 }
            bob.transport.dropFiles(alice.transport)
            lab.await(1) { if (alice.peer(bob)?.advertisedAvatar == hash) 1 else 0 }

            // The tick's re-arm asks Bob alone; his push's serve memo refuses it (the push may be landing yet).
            alice.reoffer()
            awaitAsk(alice, bob, hash)
            bob.transport.awaitInboundDrained()
            assertFalse("served inside the push's memo", alice.holdsBlob(hash))

            lab.clock.advance(BlobExchange.SERVE_MEMO_MS + 1)
            alice.reoffer()
            awaitFace(alice, bob, hash, photo)
            assertEquals("the lost push and one serve", 2, copies(bob, alice, hash))
            converge(alice, bob)
        }

    @Test
    fun aPhotoWhosePushWasCutIsFetchedFromAHolderThatLinksLater() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            val carol = lab.node("carol").apply { setDisplayName("Carol") }
            lab.linkAll(alice to bob, alice to carol)
            lab.awaitAcquainted(alice, bob, carol)
            startSwept(alice, bob, carol)
            val photo = Random(2582).nextBytes(2_048)
            val hash = sha256Hex(photo)

            alice.transport.holdFiles(bob.transport)
            alice.setAvatar(photo)
            awaitFace(carol, alice, hash, photo)
            lab.await(1) { if (bob.peer(alice)?.advertisedAvatar == hash) 1 else 0 }
            lab.unlink(alice, bob) // the push goes with the link
            // Whatever Bob wanted lapses with nobody in reach; only the row remembers the photo.
            lab.clock.advance(BlobExchange.FETCH_TTL_MS + MINUTE_MS)
            bob.sweepExpired()
            assertNull(bob.peer(alice)?.avatarHash)

            lab.link(bob, carol)
            awaitFace(bob, alice, hash, photo)
            assertEquals("one copy from the holder that linked", 1, copies(carol, bob, hash))
            lab.link(alice, bob)
            converge(alice, bob, carriers = listOf(carol))
        }

    @Test
    fun aPhotoWhoseReceiverRestartedBetweenTheProfileAndTheBytesIsAskedForAfterTheRestart() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)
            startSwept(alice, bob)
            val photo = Random(2583).nextBytes(2_048)
            val hash = sha256Hex(photo)

            alice.transport.holdFiles(bob.transport)
            alice.setAvatar(photo)
            lab.await(1) { if (bob.peer(alice)?.advertisedAvatar == hash) 1 else 0 }
            bob.restart() // the parked push goes with the links
            // Alice's push memo still holds the hash: she will not push it again, and Bob's start-up and link-up re-arms
            // and ticks ask her alone; once her push's serve memo lapses, an ask is served. The link-up pass is awaited
            // before the calendar moves: it starts the hold on Alice afresh (a step before it may ask her first, #141). A
            // copy that lands while the start-up collection is pending may lose its bytes (#256): the ticks heal that too.
            lab.link(alice, bob)
            bob.transport.awaitNeighborsHandled()
            alice.transport.awaitNeighborsHandled()
            // Faces are re-wanted once the start's sweeps are done (`MeshManager.rewantFaces`); a loaded run can take a while.
            startSwept(bob)
            tickUntilFace(bob, alice, alice, hash, photo)
            converge(alice, bob)
        }

    /**
     * #253: Bob renames and then sets a photo, and the push lands before both profiles. The rename's profile names no
     * photo; it used to clear the face the push had brought and delete its bytes, and nothing asked again. The push now
     * waits for the profile naming it: no second transfer.
     */
    @Test
    fun aPushThatLandsBeforeARenameAndThePhotosProfileEndsShowingThePhoto() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)
            startSwept(alice, bob)
            val photo = Random(2584).nextBytes(2_048)
            val hash = sha256Hex(photo)

            bob.transport.hold(alice.transport)
            bob.setDisplayName("Bob Two")
            bob.setAvatar(photo)
            lab.await(1) { if (alice.holdsBlob(hash)) 1 else 0 }
            lab.await(2) { bob.transport.held(alice.transport).count { it.isProfileFrom(bob.nodeId) } }
            bob.transport.release(alice.transport)

            awaitFace(alice, bob, hash, photo)
            assertEquals("Bob Two", alice.peer(bob)?.name)
            assertEquals("the push alone", 1, copies(bob, alice, hash))
            converge(alice, bob)
        }

    /**
     * The linked author decides (ADR 2026-10.g24x's rule, applied to a face): Bob's new photo lands at Carol, while his
     * push to Alice sits fed but unseen in his stack buffer (#121). Alice's re-arm asks Bob alone and, past the 5 s grace
     * a round widens after, still asks nobody else, so Carol never serves a second copy while the push lands.
     */
    @Test
    fun aLinkedAuthorsFaceIsAskedOfItAloneWhileItsPushSitsInTheStackBuffer() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            val carol = lab.node("carol").apply { setDisplayName("Carol") }
            lab.linkAll(alice to bob, bob to carol, alice to carol)
            lab.awaitAcquainted(alice, bob, carol)
            startSwept(alice, bob, carol)
            val photo = Random(2585).nextBytes(2_048)
            val hash = sha256Hex(photo)

            bob.transport.holdFiles(alice.transport)
            bob.transport.stackFiles(alice.transport)
            bob.setAvatar(photo)
            awaitFace(carol, bob, hash, photo)
            lab.await(1) { if (alice.peer(bob)?.advertisedAvatar == hash) 1 else 0 }

            alice.reoffer()
            lab.await(1) { alice.asksFor(bob, hash) }
            lab.clock.advance(MeshLab.FIRST_ASK_GRACE_MS + 1)
            alice.reoffer()
            listOf(bob, carol).forEach { it.transport.awaitInboundDrained() }
            assertEquals("Carol is never asked while the author holds the choice", 0, alice.asksFor(carol, hash))

            bob.transport.releaseFiles(alice.transport)
            awaitFace(alice, bob, hash, photo)
            assertEquals("Bob's push alone", 1, copies(bob, alice, hash))
            assertEquals("no copy from Carol", 0, copies(carol, alice, hash))
            converge(alice, bob, carriers = listOf(carol))
        }

    /**
     * Blocking finding 1 of the design pass: a photo screening refused keeps its verdict while the row advertises it, so
     * the re-arm that now wants every advertised photo never fetches it again: one copy, the push, across ten ticks.
     */
    @Test
    fun aFaceScreeningRefusedIsNeverFetchedAgainAcrossTenReArms() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)
            startSwept(alice, bob)
            alice.picturesExplicit = true
            val photo = Random(2586).nextBytes(2_048)
            val hash = sha256Hex(photo)

            bob.setAvatar(photo)
            lab.await(1) { if (alice.peer(bob)?.advertisedAvatar == hash && alice.imageFlagged(hash)) 1 else 0 }
            lab.await(1) { if (alice.holdsBlob(hash)) 0 else 1 }
            repeat(REARMS) {
                lab.clock.advance(BlobExchange.CYCLE_MS)
                alice.reoffer()
            }
            bob.transport.awaitInboundDrained()
            assertEquals("never asked for", 0, alice.asksFor(bob, hash))
            assertEquals("the push alone", 1, copies(bob, alice, hash))
            assertNull(alice.peer(bob)?.avatarHash)
            converge(alice, bob)
        }

    /**
     * Blocking finding 2 of the design pass: the want table holds 256, and a re-arm that wanted every advertised photo
     * would evict attachments' wants every tick, for the next tick to mint again (#195's churn). Alice holds 220 missing
     * attachments and 100 peers whose photos she lacks; a re-arm's 64 faces would make 284 wants. The faces take only
     * what is left of three quarters of the table — nothing, here — so across three re-arms no attachment's want goes.
     */
    @Test
    fun aCrowdOfFacesNeverEvictsAnAttachmentsWant() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)
            startSwept(alice, bob)
            val now = lab.clock.now()
            val attachments = (1..ATTACHMENTS).map { sha256Hex("attachment $it".toByteArray()) }
            attachments.forEachIndexed { i, hash ->
                alice.messages.save(
                    MessageEntity(
                        id = "crowd-$i",
                        senderId = CROWD,
                        conversationId = CROWD,
                        body = "",
                        sentAt = now - i,
                        attachmentHash = hash,
                    ),
                )
            }
            val faces = (1..FACES).map { "face-$it" to sha256Hex("face $it".toByteArray()) }
            faces.forEach { (id, hash) ->
                alice.peers.upsert(PeerEntity(nodeId = id, lastSeenAt = now, advertisedAvatar = hash, advertisedKnown = true))
            }

            // Bob would only answer the 220 asks with nothing: lost on the pipe, they cost the scenario nothing.
            alice.transport.lossy(bob.transport) { WireCodec.decodeEnvelope(it.signed)?.type == FrameType.BLOB_REQ }
            repeat(3) {
                alice.reoffer()
                val wanted = alice.manager.wantedBlobs()
                assertTrue("an attachment's want went: ${attachments.count { it !in wanted }} missing", wanted.containsAll(attachments))
                assertTrue("faces took ${wanted.size - attachments.size} places", wanted.size <= MAX_WANTS)
            }
            // A background re-arm that read fewer rows (one racing the inserts) may have let faces in; they give their places
            // up to the attachments, so the table never holds more than its 256 and every attachment stands.
            val wanted = alice.manager.wantedBlobs()
            assertTrue(wanted.containsAll(attachments))
            assertTrue(
                "faces hold ${faces.count { it.second in wanted }} places",
                faces.count { it.second in wanted } <= MAX_WANTS - ATTACHMENTS,
            )
            alice.transport.lossy(bob.transport)
            converge(alice, bob)
        }

    /**
     * The round-1 review's blocking finding: ordered by `lastSeenAt` alone, 64 strangers heard today — a busy room, a cheap
     * set of identities — took every place under the cap, and a contact not heard for two days was never asked for again.
     * Alice holds 70 such strangers and a contact heard two days ago whose photo Carol, linked to her, holds: the
     * contact's face lands within one re-arm.
     */
    @Test
    fun aContactsFaceIsWantedAheadOfACrowdOfStrangersHeardToday() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val carol = lab.node("carol").apply { setDisplayName("Carol") }
            lab.link(alice, carol)
            lab.awaitAcquainted(alice, carol)
            startSwept(alice, carol)
            val now = lab.clock.now()
            val photo = Random(2587).nextBytes(2_048)
            val hash = sha256Hex(photo)
            carol.blobs.insert(hash, "image/jpeg", photo)
            alice.accept(CONTACT)
            alice.peers.upsert(
                PeerEntity(nodeId = CONTACT, lastSeenAt = now - 2 * DAY_MS, advertisedAvatar = hash, advertisedKnown = true),
            )
            (1..STRANGERS).forEach {
                val id = "stranger-$it"
                alice.peers.upsert(
                    PeerEntity(nodeId = id, lastSeenAt = now, advertisedAvatar = sha256Hex(id.toByteArray()), advertisedKnown = true),
                )
            }

            alice.reoffer()
            val shown =
                lab.tryAwait(1) {
                    if (alice.peers.find(CONTACT)?.avatarHash == hash && photo.contentEquals(alice.blobs.bytes(hash))) 1 else 0
                }
            assertTrue("the contact's face never landed: face ${alice.peers.find(CONTACT)?.avatarHash}", shown)
            assertEquals("one copy from the carrier", 1, copies(carol, alice, hash))
            converge(alice, carol)
        }

    /**
     * The round-1 review's finding 1: Alice is fetching Bob's face from Carol, whose copy sits fed but unseen behind the
     * stack, when Bob links. The hold on Bob must not take Carol out of the pool — the fetch would read her link as gone
     * and end with no `blobcancel`, and her copy would come beside Bob's — so Bob is not asked while that fetch stands, and
     * each link carries one copy.
     */
    @Test
    fun aFaceFetchStandingOnACarrierIsNotCutShortWhenTheAuthorLinks() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            val carol = lab.node("carol").apply { setDisplayName("Carol") }
            lab.linkAll(alice to carol, carol to bob)
            lab.awaitAcquainted(alice, bob, carol)
            startSwept(alice, bob, carol)
            val photo = Random(2588).nextBytes(2_048)
            val hash = sha256Hex(photo)

            carol.transport.holdFiles(alice.transport)
            carol.transport.stackFiles(alice.transport)
            bob.setAvatar(photo)
            awaitFace(carol, bob, hash, photo)
            lab.await(1) { if (alice.peer(bob)?.advertisedAvatar == hash) 1 else 0 }
            awaitAsk(alice, carol, hash)
            carol.transport.awaitInboundDrained()

            lab.link(alice, bob) {
                // Bob's link-up push sits in his stack too: neither copy is seen while the link-up acts.
                bob.transport.holdFiles(alice.transport)
                bob.transport.stackFiles(alice.transport)
            }
            alice.transport.awaitNeighborsHandled()
            bob.transport.awaitNeighborsHandled()
            alice.reoffer()
            bob.transport.awaitInboundDrained()
            assertEquals("Bob asked past Carol's standing fetch", 0, alice.asksFor(bob, hash))

            carol.transport.releaseFiles(alice.transport)
            bob.transport.releaseFiles(alice.transport)
            awaitFace(alice, bob, hash, photo)
            assertTrue("Carol sent ${copies(carol, alice, hash)} copies", copies(carol, alice, hash) <= 1)
            assertTrue("Bob sent ${copies(bob, alice, hash)} copies", copies(bob, alice, hash) <= 1)
            converge(alice, bob, carriers = listOf(carol))
        }

    /**
     * The round-1 review's finding 5: the stack-buffer shape with an author that cannot answer a holder query (the iOS
     * port's rules) and an asker that queries. The hold alone keeps Carol out: she is neither queried nor asked before
     * the floor, though she would answer HAVE, and the author's push is the one copy.
     */
    @Test
    fun aQueryingAskerHoldsOnAnAuthorThatCannotAnswerAndNeverQueriesTheCarrier() =
        runBlocking {
            val alice = lab.node("alice", LabLimits(blobQuery = true)).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", LabLimits(answersHolderQueries = false)).apply { setDisplayName("Bob") }
            val carol = lab.node("carol").apply { setDisplayName("Carol") }
            lab.linkAll(alice to bob, bob to carol, alice to carol)
            lab.awaitAcquainted(alice, bob, carol)
            startSwept(alice, bob, carol)
            val photo = Random(2589).nextBytes(2_048)
            val hash = sha256Hex(photo)

            bob.transport.holdFiles(alice.transport)
            bob.transport.stackFiles(alice.transport)
            bob.setAvatar(photo)
            awaitFace(carol, bob, hash, photo)
            lab.await(1) { if (alice.peer(bob)?.advertisedAvatar == hash) 1 else 0 }

            alice.reoffer()
            awaitAsk(alice, bob, hash)
            lab.clock.advance(MeshLab.FIRST_ASK_GRACE_MS + MeshLab.QUERY_WAIT_MS + 1)
            alice.reoffer()
            listOf(bob, carol).forEach { it.transport.awaitInboundDrained() }
            assertEquals(
                "Carol queried before the floor",
                0,
                alice.transport.blobQueries.count { it.to == carol.nodeId && hash in it.hashes },
            )
            assertEquals("Carol asked before the floor", 0, alice.asksFor(carol, hash))

            bob.transport.releaseFiles(alice.transport)
            awaitFace(alice, bob, hash, photo)
            assertEquals("Bob's push alone", 1, copies(bob, alice, hash))
            assertEquals("no copy from Carol", 0, copies(carol, alice, hash))
            converge(alice, bob, carriers = listOf(carol))
        }

    /**
     * The crypto review's finding 7: the photo arrives only by the sealed `CTL_PROFILE` — Bob, a contact Alice reaches
     * through Carol over a confirmed session, changes photo, and his cleartext profile never reaches her — and Alice
     * restarts before the bytes land. The row recorded the sealed profile's photo in its ctl commit, so her re-arm asks
     * for it after the restart.
     */
    @Test
    fun aPhotoOnlyASealedProfileNamedIsAskedForAfterARestart() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            val carol = lab.node("carol").apply { setDisplayName("Carol") }
            lab.linkAll(alice to carol, carol to bob)
            lab.awaitAcquainted(alice, bob, carol)
            startSwept(alice, bob, carol)
            assertTrue(alice.sendDm(bob, "hello"))
            lab.await(1) { bob.decrypted(bob.dmWith(alice)).size }
            assertTrue(bob.sendDm(alice, "hello back"))
            lab.await(1) { alice.decrypted(alice.dmWith(bob)).size }
            val photo = Random(2590).nextBytes(2_048)
            val hash = sha256Hex(photo)

            carol.transport.lossy(alice.transport) { it.isProfileFrom(bob.nodeId) }
            carol.transport.holdFiles(alice.transport)
            bob.setAvatar(photo)
            lab.await(1) { if (alice.peer(bob)?.advertisedAvatar == hash) 1 else 0 }
            // Alice carries the sealed frame naming the photo: after the restart, custody's re-arm wants that hash too,
            // and must want it as the photo its sender advertises (`MeshManager.sendersOwnPhotos`), never held to Wi-Fi
            // Aware behind the frame or fetched uncapped.
            lab.await(1) { if (alice.carriesUnheld(hash)) 1 else 0 }
            assertFalse(alice.holdsBlob(hash))
            alice.restart() // the parked copy goes with the links
            // The relinked pipe loses Bob's cleartext profile too: only the sealed profile's commit names the photo.
            lab.link(alice, carol) { carol.transport.lossy(alice.transport) { it.isProfileFrom(bob.nodeId) } }
            alice.transport.awaitNeighborsHandled()
            carol.transport.awaitNeighborsHandled()
            tickUntilFace(alice, bob, carol, hash, photo)

            // Custody heals the profile frame the pipe lost once it no longer loses it: a fresh link, since one link writes
            // a frame once per seen window and the lost copy counted.
            carol.transport.lossy(alice.transport)
            lab.unlink(alice, carol)
            lab.link(alice, carol)
            converge(alice, bob, carriers = listOf(carol))
        }

    private companion object {
        const val MINUTE_MS = 60_000L
        const val HEAL_ROUNDS = 6
        const val TICK_WAIT_MS = 10_000L
        const val SETTLE_WAIT_MS = 1_000L
        const val REARMS = 10
        const val CROWD = "crowd-sender"
        const val ATTACHMENTS = 220
        const val FACES = 100

        // `BlobExchange`'s want table, of which a face never takes the last quarter.
        const val MAX_WANTS = 256
        const val CONTACT = "contact-node"
        const val STRANGERS = 70
        const val DAY_MS = 24 * 60 * 60_000L
    }
}
