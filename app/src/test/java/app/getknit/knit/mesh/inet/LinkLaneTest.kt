package app.getknit.knit.mesh.inet

import app.getknit.knit.identity.NodeId
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

/** A direct link's sealed datagrams (ADR 2026-10.a5yj): reorder-tolerant, replay-proof, bound to the pair and link. */
class LinkLaneTest {
    private val rng = Random(7)
    private val alice = NodeId.derive("alice")
    private val bob = NodeId.derive("bob")
    private val linkId = rng.nextBytes(16)
    private val aToB = rng.nextBytes(32)
    private val bToA = rng.nextBytes(32)
    private val aad = LinkLane.aad(linkId, alice, bob)

    private val aliceLane = LinkLane(sendKey = aToB, recvKey = bToA, aad = aad)
    private val bobLane = LinkLane(sendKey = bToA, recvKey = aToB, aad = aad)

    @Test
    fun bothDirectionsOpenAndTheOverheadIsTwentyFiveBytes() {
        val hello = "hello".toByteArray()
        val datagram = aliceLane.seal(hello)
        assertEquals(hello.size + LinkLane.OVERHEAD_BYTES, datagram.size)
        assertArrayEquals(hello, bobLane.open(datagram))
        assertArrayEquals(byteArrayOf(9), aliceLane.open(bobLane.seal(byteArrayOf(9))))
    }

    @Test
    fun bothEndsComputeTheSameAadWhicheverWayRoundTheyNameThePair() {
        assertArrayEquals(LinkLane.aad(linkId, alice, bob), LinkLane.aad(linkId, bob, alice))
    }

    @Test
    fun reorderedDatagramsOpenAndARepeatNeverDoes() {
        val sealed = (0 until 5).map { aliceLane.seal(byteArrayOf(it.toByte())) }
        for (i in listOf(3, 0, 4, 1, 2)) assertArrayEquals(byteArrayOf(i.toByte()), bobLane.open(sealed[i]))
        sealed.forEach { assertNull(bobLane.open(it)) }
    }

    @Test
    fun aDatagramFallenBehindTheWindowIsRefused() {
        val first = aliceLane.seal(byteArrayOf(1))
        repeat(LinkLane.WINDOW) { aliceLane.seal(byteArrayOf(0)) }
        val last = aliceLane.seal(byteArrayOf(2))
        assertArrayEquals(byteArrayOf(2), bobLane.open(last))
        assertNull(bobLane.open(first))
    }

    @Test
    fun aJumpPastTheWindowStillRefusesTheCountersItSkippedOnlyOnceTheyAreOld() {
        val sealed = (0..LinkLane.WINDOW + 10).map { aliceLane.seal(byteArrayOf(it.toByte())) }
        assertArrayEquals(byteArrayOf(sealed.lastIndex.toByte()), bobLane.open(sealed.last()))
        // Inside the window below the top: fresh, opens once.
        val near = sealed[sealed.lastIndex - 5]
        assertArrayEquals(byteArrayOf((sealed.lastIndex - 5).toByte()), bobLane.open(near))
        assertNull(bobLane.open(near))
        // Beyond it: refused.
        assertNull(bobLane.open(sealed[0]))
    }

    @Test
    fun aTamperedTruncatedOrForeignDatagramIsRefusedAndBurnsNoCounter() {
        val datagram = aliceLane.seal(byteArrayOf(1, 2, 3))
        val tampered = datagram.copyOf().also { it[it.lastIndex] = (it[it.lastIndex].toInt() xor 1).toByte() }
        assertNull(bobLane.open(tampered))
        assertNull(bobLane.open(datagram, LinkLane.OVERHEAD_BYTES - 1))
        assertNull(bobLane.open(datagram.copyOf().also { it[0] = 2 }))
        // A forged counter that failed its tag did not burn the real one.
        assertArrayEquals(byteArrayOf(1, 2, 3), bobLane.open(datagram))
    }

    @Test
    fun aDatagramOfAnotherLinkOrPairOrDirectionNeverOpens() {
        val datagram = aliceLane.seal(byteArrayOf(5))
        val otherLink = LinkLane(bToA, aToB, LinkLane.aad(rng.nextBytes(16), alice, bob))
        val otherPair = LinkLane(bToA, aToB, LinkLane.aad(linkId, alice, NodeId.derive("carol")))
        assertNull(otherLink.open(datagram))
        assertNull(otherPair.open(datagram))
        // Alice's own lane reads with the other direction's key: her datagram reflected back never opens.
        assertNull(aliceLane.open(datagram))
        assertArrayEquals(byteArrayOf(5), bobLane.open(datagram))
    }
}
