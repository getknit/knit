package app.getknit.knit.mesh.inet

import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.protocol.BlobCancelContent
import app.getknit.knit.mesh.protocol.BlobHasContent
import app.getknit.knit.mesh.protocol.BlobHasItem
import app.getknit.knit.mesh.protocol.BlobHaveContent
import app.getknit.knit.mesh.protocol.BlobProgressContent
import app.getknit.knit.mesh.protocol.BlobReqContent
import app.getknit.knit.mesh.protocol.ChatContent
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.KeyReqContent
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.spool.dmFrame
import app.getknit.knit.mesh.spool.groupChatFrame
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What crosses a contact's direct link (ADR 2026-10.a5yj, `docs/DIRECT_LINKS.md` §7): the pair's own traffic and their
 * shared groups', never the room, a stranger's DM or a group they do not share — the same answer from either end.
 */
class LinkPairFilterTest {
    private val alice = NodeId.derive("alice")
    private val bob = NodeId.derive("bob")
    private val carol = NodeId.derive("carol")
    private val shared = "g-shared"
    private val notShared = "g-other"
    private val groups = mapOf(shared to setOf(alice, bob, carol))

    private fun admits(
        env: RelayEnvelope,
        self: String = alice,
        peer: String = bob,
        withGroups: Map<String, Set<String>> = groups,
    ) = LinkPairFilter.admits(env, self, peer, withGroups)

    private fun bare(
        type: String,
        from: String,
        to: String? = null,
        payload: ByteArray = ByteArray(0),
    ) = RelayEnvelope(type = type, id = "f-${type.take(4)}", senderId = from, recipientId = to, payload = payload)

    @Test
    fun theirDmsCrossEitherWayAndTheVerdictIsTheSameFromBothEnds() {
        val toBob = dmFrame("d1", alice, bob).envelope
        val toAlice = dmFrame("d2", bob, alice).envelope
        for (env in listOf(toBob, toAlice)) {
            assertTrue(admits(env))
            assertEquals(admits(env, alice, bob), admits(env, bob, alice))
        }
    }

    @Test
    fun aStrangersDmThisPhoneCarriesNeverCrosses() {
        // Carol's DM to bob, in alice's custody, with no group the three share: carol is not one of the pair.
        assertFalse(admits(dmFrame("d3", carol, bob).envelope, withGroups = emptyMap()))
        // Alice's DM to carol, asked for by bob: not his.
        assertFalse(admits(dmFrame("d4", alice, carol).envelope, withGroups = emptyMap()))
        assertFalse(admits(dmFrame("d4", alice, carol).envelope, self = bob, peer = alice, withGroups = emptyMap()))
    }

    @Test
    fun aFrameBetweenTwoMembersOfASharedGroupCrossesAndOneToAnOutsiderDoesNot() {
        // Carol's sender-key seed to bob (sealed, a DM to the eye), and bob's tick back to her post.
        val seed = dmFrame("s1", carol, bob).envelope
        val tick = dmFrame("t1", bob, carol).envelope
        for (env in listOf(seed, tick)) {
            assertTrue(admits(env))
            assertEquals(admits(env, alice, bob), admits(env, bob, alice))
        }
        val dave = NodeId.derive("dave")
        assertFalse("carol to someone outside the group", admits(dmFrame("s2", carol, dave).envelope))
        assertFalse("someone outside the group to bob", admits(dmFrame("s3", dave, bob).envelope))
        assertFalse("no shared group, no co-members", admits(seed, withGroups = emptyMap()))
    }

    @Test
    fun theRoomNeverCrosses() {
        val room =
            RelayEnvelope(
                type = FrameType.CHAT,
                id = "r1",
                senderId = alice,
                payload = WireCodec.encodePayload(ChatContent(body = "hi all")),
            )
        assertFalse(admits(room))
        assertFalse(admits(room, self = bob, peer = alice))
    }

    @Test
    fun aSharedGroupsFramesCrossAndAnotherGroupsDoNot() {
        val ours = groupChatFrame("g1", carol, shared, listOf(alice, bob, carol)).envelope
        assertTrue("carol's post in a group all three share", admits(ours))
        assertEquals(admits(ours, alice, bob), admits(ours, bob, alice))
        val theirs = groupChatFrame("g2", alice, notShared, listOf(alice, carol)).envelope
        assertFalse("a group bob is not in", admits(theirs))
        val outsider = groupChatFrame("g3", NodeId.derive("dave"), shared, listOf(alice, bob, carol)).envelope
        assertFalse("a sender outside the group's roster", admits(outsider))
        assertFalse("no shared group at all", admits(ours, withGroups = emptyMap()))
    }

    @Test
    fun profilesOfThePairAndOfTheirSharedGroupsMembersCrossNoOneElses() {
        // Two contacts who share no group: each one's own profile still crosses, a third's never does.
        assertTrue(admits(bare(FrameType.PROFILE, alice), withGroups = emptyMap()))
        assertTrue(admits(bare(FrameType.PROFILE, bob), withGroups = emptyMap()))
        assertFalse(admits(bare(FrameType.PROFILE, carol), withGroups = emptyMap()))
        assertTrue("a co-member's, whose prekey the other needs", admits(bare(FrameType.PROFILE, carol)))
        assertFalse(admits(bare(FrameType.PROFILE, NodeId.derive("dave"))))
    }

    // A link's lookups: one blob addressed to the pair, one to alice and carol only, and one forwarded (ADR 2026-10.mptm):
    // named first by a group bob is not in, then by the pair's DM.
    private val lookups =
        object : LinkScope {
            override suspend fun sharedGroups(peerId: String) = groups

            override suspend fun addresseeSetsOf(hash: String): List<Set<String>> =
                when (hash) {
                    "ours" -> listOf(setOf(alice, bob))
                    "carols" -> listOf(setOf(alice, carol))
                    "forwarded" -> listOf(setOf(alice, carol, NodeId.derive("dave")), setOf(alice, bob))
                    else -> emptyList()
                }

            override suspend fun ownAvatarKey(): String? = null

            override suspend fun custodyEnvelopes(ids: List<String>): Map<String, RelayEnvelope> = emptyMap()
        }

    @Test
    fun aBlobControlFrameGoesOutOnlyWhenEveryHashIsAddressedToBothEndsAndAlwaysComesIn() =
        runBlocking<Unit> {
            val view = LinkView(alice, bob, groups, lookups)

            fun have(vararg hashes: String) =
                bare(FrameType.BLOB_HAVE, alice, payload = WireCodec.encodePayload(BlobHaveContent(hashes.toList())))
            assertTrue(view.admitsFrame(have("ours"), outbound = true))
            assertFalse("one hash the peer is not addressed", view.admitsFrame(have("ours", "carols"), outbound = true))
            assertFalse("one nobody here is addressed", view.admitsFrame(have("unknown"), outbound = true))
            assertTrue("an ask that comes in serves nothing by itself", view.admitsFrame(have("ours", "carols"), outbound = false))
            assertFalse("never by the frame rule alone", view.admits(have("ours")))
        }

    @Test
    fun aBlobIsTheLinksOnlyWhenItsConversationIsAddressedToBothEnds() =
        runBlocking<Unit> {
            assertTrue(LinkView(alice, bob, groups, lookups).admitsBlob("ours"))
            // Addressed to the peer but not to this end, and to this end but not to the peer.
            assertFalse(LinkView(carol, alice, groups, lookups).admitsBlob("ours"))
            assertFalse(LinkView(alice, bob, groups, lookups).admitsBlob("carols"))
            assertFalse(LinkView(alice, bob, groups, lookups).admitsBlob("unknown"))
        }

    /**
     * A picture forwarded under its sealed identity (ADR 2026-10.mptm) is named by two conversations: a group bob is not
     * in, first, and then the pair's DM. It is the pair's — its proof query, its ask and its file cross — because a rule
     * that read the first conversation alone refused it, and the sender's proof round waited on a question that never left.
     */
    @Test
    fun aBlobNamedByTwoConversationsCrossesWhenEitherIsThePairs() =
        runBlocking<Unit> {
            val view = LinkView(alice, bob, groups, lookups)
            assertTrue(view.admitsBlob("forwarded"))
            assertTrue(
                view.admitsFrame(
                    bare(FrameType.BLOB_HAVE, alice, payload = WireCodec.encodePayload(BlobHaveContent(listOf("forwarded")))),
                    outbound = true,
                ),
            )
            val erin = NodeId.derive("erin")
            assertFalse("still never to someone neither conversation names", LinkView(alice, erin, groups, lookups).admitsBlob("forwarded"))
        }

    @Test
    fun aKeyRequestCrossesOnlyWhenItNamesThePairOrTheirCoMembers() {
        fun keyReq(vararg ids: String) = bare(FrameType.KEY_REQ, alice, payload = WireCodec.encodePayload(KeyReqContent(ids.toList())))
        assertTrue(admits(keyReq(bob)))
        assertTrue(admits(keyReq(bob, carol)))
        assertFalse(admits(keyReq(bob, NodeId.derive("dave"))))
        assertFalse(admits(keyReq()))
    }

    @Test
    fun anythingAddressedBetweenThePairCrossesWhateverItsType() {
        assertTrue(admits(bare(FrameType.TYPING, bob, to = alice)))
        assertTrue(admits(bare(FrameType.TALK, alice, to = bob)))
        assertFalse("dave is in no group the pair shares", admits(bare(FrameType.TYPING, NodeId.derive("dave"), to = alice)))
    }

    @Test
    fun blobControlFramesNameTheirHashesAndAreNeverAdmittedOnTheirOwn() {
        fun ctl(
            type: String,
            payload: ByteArray,
        ) = bare(type, alice, payload = payload)
        val cases =
            mapOf(
                ctl(FrameType.BLOB_REQ, WireCodec.encodePayload(BlobReqContent("h1"))) to listOf("h1"),
                ctl(FrameType.BLOB_HAVE, WireCodec.encodePayload(BlobHaveContent(listOf("h1", "h2")))) to listOf("h1", "h2"),
                ctl(FrameType.BLOB_HAS, WireCodec.encodePayload(BlobHasContent(listOf(BlobHasItem("h3", 1))))) to listOf("h3"),
                ctl(FrameType.BLOB_PROGRESS, WireCodec.encodePayload(BlobProgressContent("h4", 10, 20))) to listOf("h4"),
                ctl(FrameType.BLOB_CANCEL, WireCodec.encodePayload(BlobCancelContent("h5"))) to listOf("h5"),
            )
        for ((env, hashes) in cases) {
            assertEquals(hashes, LinkPairFilter.blobHashes(env))
            // The transport checks the hashes' addressees; the frame rule alone never lets one through.
            assertFalse(admits(env))
        }
        assertNull(LinkPairFilter.blobHashes(dmFrame("d5", alice, bob).envelope))
    }
}
