package app.getknit.knit.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * [MeshPost]'s hand-written equality: the signed payload and the signature are byte arrays, which a data
 * class would compare by identity, so two hearings of one packet would never be equal. Content decides,
 * every scalar still counts, and a missing signature equals only a missing one.
 */
class MeshPostTest {
    private fun post(
        payload: ByteArray = "hello".encodeToByteArray(),
        signature: ByteArray? = ByteArray(64) { 7 },
        hops: Int? = 2,
        boardVerified: Boolean = true,
    ) = MeshPost(
        node = 0xFFFF_FFFEL,
        packetId = 42L,
        body = "hello",
        name = "base camp",
        channel = "LongFast",
        hops = hops,
        snrDeci = -35,
        viaMqtt = false,
        payload = payload,
        signature = signature,
        boardVerified = boardVerified,
    )

    @Test
    fun twoHearingsOfOnePacketAreEqualByContent() {
        val a = post()
        val b = post()
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertEquals(a, a)
    }

    @Test
    fun theBytesDecide() {
        assertNotEquals(post(), post(payload = "hellp".encodeToByteArray()))
        assertNotEquals(post(), post(signature = ByteArray(64) { 8 }))
        assertNotEquals(post(), post(signature = null))
        assertEquals(post(signature = null), post(signature = null))
        assertEquals(post(signature = null).hashCode(), post(signature = null).hashCode())
    }

    @Test
    fun theScalarsStillCount() {
        assertNotEquals(post(), post(hops = 3))
        assertNotEquals(post(), post(boardVerified = false))
        assertNotEquals(post(), "hello")
    }
}
