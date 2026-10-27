package app.getknit.knit.mesh.crypto

import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.crypto.LinkKeys.Role
import app.getknit.knit.mesh.inet.LinkLane
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.SecureRandom

/**
 * A direct link's lane keys (ADR 2026-10.a5yj): both ends derive the same pair from what the OFFER and the ANSWER
 * carried, each direction's key is the other end's receive key, and every input — both k's, the link id, both ids and
 * who offered — moves every key.
 */
class LinkKeysTest {
    private val rng = SecureRandom()
    private val alice = NodeId.derive("alice")
    private val bob = NodeId.derive("bob")
    private val kOffer = LinkKeys.fresh(rng)
    private val kAnswer = LinkKeys.fresh(rng)
    private val linkId = ByteArray(16).also(rng::nextBytes)

    private fun lane(
        role: Role,
        kO: ByteArray = kOffer,
        kA: ByteArray = kAnswer,
        id: ByteArray = linkId,
        offerer: String = alice,
        answerer: String = bob,
    ) = LinkKeys.lane(role, kO, kA, id, offerer, answerer)

    private fun keysOf(pair: Pair<ByteArray, ByteArray>) = listOf(pair.first.toList(), pair.second.toList())

    @Test
    fun bothEndsDeriveMatchingDirectionKeys() {
        val (offerSend, offerRecv) = lane(Role.OFFERER)
        val (answerSend, answerRecv) = lane(Role.ANSWERER)
        assertArrayEquals("the offerer's send key is the answerer's receive key", offerSend, answerRecv)
        assertArrayEquals(answerSend, offerRecv)
        assertFalse("the two directions never share a key", offerSend.contentEquals(offerRecv))
        assertEquals(LinkKeys.KEY_BYTES, offerSend.size)

        // And they drive a lane end to end, each way.
        val aad = LinkLane.aad(linkId, alice, bob)
        val offerer = LinkLane(offerSend, offerRecv, aad)
        val answerer = LinkLane(answerSend, answerRecv, aad)
        assertArrayEquals(byteArrayOf(1, 2), answerer.open(offerer.seal(byteArrayOf(1, 2))))
        assertArrayEquals(byteArrayOf(3), offerer.open(answerer.seal(byteArrayOf(3))))
    }

    @Test
    fun theKeysAreBoundToTheRoles() {
        val base = keysOf(lane(Role.OFFERER))
        val swappedIds = keysOf(lane(Role.OFFERER, offerer = bob, answerer = alice))
        assertFalse("swapping who offered moves both keys", base.intersect(swappedIds.toSet()).isNotEmpty())
        val swappedKs = keysOf(lane(Role.OFFERER, kO = kAnswer, kA = kOffer))
        assertFalse("each k counts in its own slot", base.intersect(swappedKs.toSet()).isNotEmpty())
        val carol = keysOf(lane(Role.OFFERER, answerer = NodeId.derive("carol")))
        assertFalse("another answerer, other keys", base.intersect(carol.toSet()).isNotEmpty())
    }

    @Test
    fun anotherLinkIdOrEitherKMovesEveryKey() {
        val base = keysOf(lane(Role.OFFERER))
        for (other in listOf(
            lane(Role.OFFERER, id = ByteArray(16).also(rng::nextBytes)),
            lane(Role.OFFERER, kO = LinkKeys.fresh(rng)),
            lane(Role.OFFERER, kA = LinkKeys.fresh(rng)),
        )) {
            assertFalse(base.intersect(keysOf(other).toSet()).isNotEmpty())
        }
    }

    @Test
    fun malformedInputsAreRefused() {
        assertThrows(IllegalArgumentException::class.java) { lane(Role.OFFERER, kO = ByteArray(31)) }
        assertThrows(IllegalArgumentException::class.java) { lane(Role.OFFERER, kA = ByteArray(33)) }
        assertThrows(IllegalArgumentException::class.java) { lane(Role.OFFERER, id = ByteArray(15)) }
        assertThrows(IllegalArgumentException::class.java) { lane(Role.OFFERER, offerer = "not-a-node-id") }
        assertThrows(IllegalArgumentException::class.java) { lane(Role.OFFERER, answerer = alice) }
    }

    @Test
    fun aFreshKIsThirtyTwoNewBytes() {
        val a = LinkKeys.fresh(rng)
        assertEquals(32, a.size)
        assertFalse(a.contentEquals(LinkKeys.fresh(rng)))
    }
}
