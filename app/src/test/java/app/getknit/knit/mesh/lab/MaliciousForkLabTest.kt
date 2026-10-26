package app.getknit.knit.mesh.lab

import app.getknit.knit.data.group.toGroupInfo
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.mesh.BlobExchange
import app.getknit.knit.mesh.DropReason
import app.getknit.knit.mesh.Peer
import app.getknit.knit.mesh.protocol.BlobReqContent
import app.getknit.knit.mesh.protocol.ChatContent
import app.getknit.knit.mesh.protocol.DEFAULT_TTL
import app.getknit.knit.mesh.protocol.FrameId
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.Protocol
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import app.getknit.knit.mesh.sha256Hex
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.random.Random

/**
 * What a modified client that ignores the send-side rules can make honest phones do (the malicious-fork review): each
 * case is one frame no build sends, and what the mesh must not do with it. Every case ends in `assertConverged`.
 */
@RunWith(RobolectricTestRunner::class)
class MaliciousForkLabTest : StrandedLabBase() {
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
     * A blob request marked for relay, with the whole pull budget: honoured, it would have Bob pull Carol's picture
     * through for Mallory, and every node it was flooded to serve or pull it for the neighbour that handed it on. Bob
     * refuses it at the door, so nobody serves, wants, asks or queries for it. The same ask point-to-point is pulled
     * through and served, so the line could have carried the flood. That the router never floods it is pinned by
     * `MeshRouterTest.neverFloodsTheUnsignedBlobTrafficWhateverItsWrapperSays`: a relay waits out the router's real-time
     * jitter, which the lab cannot pin, so the reads of Bob's `sent` here back that test up and do not prove it.
     */
    @Test
    fun aBlobRequestMarkedForRelayIsNeitherHonouredNorFlooded() =
        runBlocking {
            val mallory = lab.node("mallory", querying).apply { setDisplayName("Mallory") }
            val bob = lab.node("bob", querying).apply { setDisplayName("Bob") }
            val carol = lab.node("carol", querying).apply { setDisplayName("Carol") }
            val dave = lab.node("dave", querying).apply { setDisplayName("Dave") }
            lab.linkAll(mallory to bob, bob to carol, carol to dave)
            lab.awaitAcquainted(mallory, bob)
            lab.awaitAcquainted(bob, carol)
            lab.awaitAcquainted(carol, dave)
            awaitLinkUpHooks(mallory to bob, bob to mallory, bob to carol, carol to bob, carol to dave, dave to carol)
            val picture = Random(1_001).nextBytes(4_096)
            val hash = sha256Hex(picture)
            carol.blobs.insert(hash, "image/jpeg", picture)
            val four = listOf(mallory, bob, carol, dave)

            val flood = ask(mallory, hash, relay = true)
            val floodId = checkNotNull(WireCodec.decodeEnvelope(flood.signed)).id
            mallory.transport.send(flood, Peer(bob.nodeId))
            lab.await(1) { bob.drops(DropReason.POINT_TO_POINT_FLOODED).toInt() }
            quiesce(four)
            assertTrue("Bob has not relayed it: ${bob.transport.sent}", bob.transport.sent.none { floodId in it })
            assertTrue("nobody wants it", four.none { it.wants(hash) })
            assertTrue("nobody asked for it but Mallory", (four - mallory).none { it.asksOf(hash) > 0 })
            assertTrue("nobody queried for it", four.none { n -> n.transport.blobQueries.any { hash in it.hashes } })
            assertTrue("nobody sent it", four.none { n -> n.transport.files.any { hash in it } })

            mallory.transport.send(ask(mallory, hash, relay = false), Peer(bob.nodeId))
            lab.await(1) { if (bob.holdsBlob(hash)) 1 else 0 }
            mallory.transport.send(ask(mallory, hash, relay = false), Peer(bob.nodeId))
            lab.await(1) { bob.transport.files.count { it == "${mallory.nodeId} ATTACHMENT $hash" } }
            quiesce(four)
            assertTrue("and never later: ${bob.transport.sent}", bob.transport.sent.none { floodId in it })
            assertFalse("Dave, past the hop that took it, never wanted it", dave.wants(hash))
            assertEquals("only Bob counted it", 0L, (four - bob).sumOf { it.drops(DropReason.POINT_TO_POINT_FLOODED) })
            lab.assertConverged(four, atLeast = 0) { Conversations.NEARBY }
        }

    /**
     * The custody wipe (C2, ADR 2026-10.7mqm): ten free identities flood eight DMs each, stamped just inside the five-minute
     * future window, into stores whose cap is sixty. Under the old global rule — the globally oldest frame goes — every
     * honest frame was older than the flood and went, on every node alike, Alice's DM to an absent Dave among them. Now
     * the cap takes from the largest sender bucket, which is always a Sybil's: the DM stays on Bob, and Dave reads it when
     * he comes back. The numbers are the field's shape scaled down (a cap five buckets deep, ten Sybils).
     */
    @Test
    fun aSybilFloodLeavesAliceDmToAnAbsentDaveInCustody() =
        runBlocking {
            val limits = LabLimits(custodyMaxRows = 60, custodyMaxPerSender = 8)
            val alice = lab.node("alice", limits).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", limits).apply { setDisplayName("Bob") }
            val dave = lab.node("dave", limits).apply { setDisplayName("Dave") }
            val mallory = lab.node("mallory", limits).apply { setDisplayName("Mallory") }
            lab.linkAll(alice to bob, bob to dave, mallory to bob)
            lab.awaitAcquainted(alice, bob, dave, mallory)
            lab.unlink(bob, dave)
            assertTrue(alice.sendDm(dave, "while you were out"))
            val dm = alice.ownMessageId(alice.dmWith(dave), "while you were out")
            lab.await(1) { if (dm in bob.custodyIds()) 1 else 0 }

            val sybils = (1..SYBILS).map { LabSybil(seed = 7_000 + it, name = "sybil $it") }
            sybils.forEach { mallory.flood(it.profile(lab.clock.now())) }
            val edge = lab.clock.now() + Protocol.MAX_FUTURE_SKEW_MS - 60_000L
            // Oldest first, so the last one flooded is the newest of its bucket and is never the one evicted.
            val frames =
                sybils.flatMapIndexed { n, sybil ->
                    (FLOOD_PER_SYBIL downTo 1).map { i -> sybil.dm(to = sybils[(n + 1) % SYBILS].nodeId, sentAt = edge - i) }
                }
            frames.forEach { mallory.flood(it) }
            val last = checkNotNull(WireCodec.decodeEnvelope(frames.last().signed)).id
            lab.await(1) { if (listOf(alice, bob).all { last in it.custodyIds() && it.custodyIds().size == 60 }) 1 else 0 }
            lab.unlink(mallory, bob)

            val held = bob.custodyIds()
            assertTrue("bob still carries alice's DM under the flood: holds ${held.size}", dm in held)
            val perSybil = sybils.map { s -> bob.custodiedFrom(s.nodeId) }
            assertTrue("the flood paid for the cap from its own buckets: $perSybil", perSybil.all { it < FLOOD_PER_SYBIL })

            bob.transport.connect(dave.transport, lossy = { it.hops > 0 })
            lab.await(1) { dave.decrypted(dave.dmWith(alice)).count { it.second == "while you were out" } }
            lab.assertConverged(listOf(alice, dave), atLeast = 1, carriers = listOf(bob)) { it.dmWith(if (it === alice) dave else alice) }
        }

    /**
     * The group form of the wipe, from outside the group: Mallory knows a group's id and roster (both ride every group
     * frame in the clear) and floods five frames naming it, stamped at the window's edge, into stores that keep three of
     * a group. Every node used to carry them, and they were the group's newest, so Carol, away, came back to Mallory's
     * three and never saw Alice's two. The roster derives to the id but does not name Mallory, so custody refuses them
     * on every node alike (`groupCustodyRefused`), delivery refuses them as it always did, and Carol reads both.
     */
    @Test
    fun aNonMembersFloodNamingAGroupIsRefusedAndTheMembersConverge() =
        runBlocking {
            val limits = LabLimits(custodyMaxPerGroup = 3)
            val alice = lab.node("alice", limits).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", limits).apply { setDisplayName("Bob") }
            val carol = lab.node("carol", limits).apply { setDisplayName("Carol") }
            val mallory = lab.node("mallory", limits).apply { setDisplayName("Mallory") }
            lab.linkAll(alice to bob, bob to carol, mallory to bob)
            lab.awaitAcquainted(alice, bob, carol, mallory)
            val groupId = alice.createGroup(bob, carol)
            assertTrue(alice.sendGroup(groupId, "founded"))
            lab.assertConverged(listOf(alice, bob, carol), atLeast = 1, carriers = listOf(mallory)) { groupId }

            lab.unlink(bob, carol)
            (1..2).forEach { assertTrue(alice.sendGroup(groupId, "apart $it")) }
            val apart = (1..2).map { alice.ownMessageId(groupId, "apart $it") }
            lab.await(1) { if (bob.custodyIds().containsAll(apart)) 1 else 0 }

            val roster = checkNotNull(alice.group(groupId)).toGroupInfo()
            val edge = lab.clock.now() + Protocol.MAX_FUTURE_SKEW_MS - 60_000L
            val flood =
                (1..5).map { i ->
                    RelayEnvelope(
                        type = FrameType.CHAT,
                        id = FrameId.new(),
                        senderId = mallory.nodeId,
                        sentAt = edge - i,
                        group = roster,
                        payload = WireCodec.encodePayload(ChatContent(enc = LabSybil.sealedNoise(Random(i)))),
                    )
                }
            flood.forEach { mallory.floodSigned(it) }
            lab.await(5) {
                bob.metrics
                    .groups()
                    .groupCustodyRefused
                    .toInt()
            }
            lab.await(5) {
                alice.metrics
                    .groups()
                    .groupCustodyRefused
                    .toInt()
            }
            val floodIds = flood.map { it.id }.toSet()
            assertTrue("no store carries the flood", listOf(alice, bob, mallory).none { n -> n.custodyIds().any { it in floodIds } })
            assertTrue("bob still carries alice's two", bob.custodyIds().containsAll(apart))

            bob.transport.connect(carol.transport, lossy = { it.hops > 0 })
            lab.assertConverged(listOf(alice, bob, carol), atLeast = 3, carriers = listOf(mallory)) { groupId }
            assertEquals(setOf("founded", "apart 1", "apart 2"), carol.decrypted(groupId).map { it.second }.toSet())
        }

    /**
     * The gate against the honest shapes it must never refuse (ADR 2026-10.7mqm): a frame sent after a departure names the
     * leaver as departed, the leaver's rejoin is a frame of their own listing them as a member again (ADR 2026-09.v6fu),
     * and Dave, outside the group, carries every one of them. No node refuses a frame, and the stores converge.
     */
    @Test
    fun aLeaveAndARejoinAreCarriedByEveryNodeUnderTheGroupGate() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            val carol = lab.node("carol").apply { setDisplayName("Carol") }
            val dave = lab.node("dave").apply { setDisplayName("Dave") }
            // Dave is linked to all three: hung off one of them, he would wait out that node's relays, which overhearing the
            // other two suppresses, until the 60 s re-offer.
            lab.linkAll(alice to bob, bob to carol, alice to carol, dave to alice, dave to bob, dave to carol)
            lab.awaitAcquainted(alice, bob, carol, dave)
            val groupId = alice.createGroup(bob, carol)
            assertTrue(alice.sendGroup(groupId, "founded"))
            lab.assertConverged(listOf(alice, bob, carol), atLeast = 1, carriers = listOf(dave)) { groupId }

            alice.leaveGroup(groupId)
            lab.await(1) { if (listOf(bob, carol).all { it.groupShape(groupId)?.departed == setOf(alice.nodeId) }) 1 else 0 }
            assertTrue(bob.sendGroup(groupId, "while alice was out"))
            val out = bob.ownMessageId(groupId, "while alice was out")
            lab.await(1) { if (out in dave.custodyIds()) 1 else 0 }

            assertEquals("the same people are the same group", groupId, alice.createGroup(bob, carol))
            assertTrue(alice.sendGroup(groupId, "i am back"))
            val back = alice.ownMessageId(groupId, "i am back")
            lab.awaitReceipt(alice, back, bob)
            lab.awaitReceipt(alice, back, carol)
            lab.await(1) { if (back in dave.custodyIds()) 1 else 0 }

            lab.assertConverged(listOf(bob, carol), atLeast = 3, carriers = listOf(alice, dave)) { groupId }
            val four = listOf(alice, bob, carol, dave)
            assertEquals(
                "no node refused a frame: ${four.map { it.metrics.groups().groupCustodyRefused }}",
                0L,
                four.sumOf {
                    it.metrics.groups().groupCustodyRefused
                },
            )
        }

    /** A `blobreq` for [hash] with the whole pull budget, as a current build's root sends it, but for [relay]. */
    private fun ask(
        from: LabNode,
        hash: String,
        relay: Boolean,
    ): WireEnvelope {
        val content = BlobReqContent(hash, pull = BlobExchange.PULL_BUDGET)
        val env =
            RelayEnvelope(type = FrameType.BLOB_REQ, id = FrameId.new(), senderId = from.nodeId, payload = WireCodec.encodePayload(content))
        return WireEnvelope(ttl = DEFAULT_TTL, relay = relay, sig = ByteArray(0), signed = WireCodec.encodeEnvelope(env))
    }

    private companion object {
        const val SYBILS = 10
        const val FLOOD_PER_SYBIL = 8
    }
}
