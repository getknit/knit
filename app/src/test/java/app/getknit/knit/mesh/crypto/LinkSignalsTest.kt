package app.getknit.knit.mesh.crypto

import app.getknit.knit.mesh.protocol.LinkCand
import app.getknit.knit.mesh.protocol.LinkSignal
import app.getknit.knit.mesh.protocol.WireCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** The one shape check both ends of a `link` frame apply (ADR 2026-10.a5yj): what a sender seals is what a reader takes. */
class LinkSignalsTest {
    private fun signal(
        ph: Int = LinkSignal.PHASE_OFFER,
        id: ByteArray = ByteArray(LinkSignal.ID_BYTES) { 1 },
        k: ByteArray = ByteArray(LinkSignal.KEY_BYTES) { 2 },
        c: List<LinkCand> = listOf(LinkCand(ByteArray(16) { 3 }, 40_000, 0), LinkCand(byteArrayOf(192.toByte(), 0, 2, 1), 40_001, 3)),
    ) = LinkSignal(ph = ph, id = id, k = k, c = c, nt = LinkSignal.NET_WIFI, ct = true)

    @Test
    fun aWellFormedSignalRoundTrips() {
        val plaintext = checkNotNull(LinkSignals.encode(signal()))
        val back = checkNotNull(LinkSignals.decode(plaintext))
        assertEquals(LinkSignal.PHASE_OFFER, back.ph)
        assertArrayEquals(ByteArray(LinkSignal.KEY_BYTES) { 2 }, back.k)
        assertEquals(2, back.c.size)
        assertNotNull(LinkSignals.encode(signal(ph = LinkSignal.PHASE_ANSWER, c = emptyList())))
    }

    @Test
    fun aMalformedSignalIsNeitherSealedNorTaken() {
        val bad =
            listOf(
                signal(ph = 3),
                signal(id = ByteArray(15)),
                signal(k = ByteArray(31)),
                signal(c = listOf(LinkCand(ByteArray(5), 1, 0))),
                signal(c = List(LinkSignals.MAX_CANDIDATES + 1) { LinkCand(ByteArray(4), it, 2) }),
            )
        for (s in bad) {
            assertNull(LinkSignals.encode(s))
            assertNull("the reader refuses what a careless sender would seal", LinkSignals.decode(WireCodec.encodePayload(s)))
        }
        assertNull("not a signal at all", LinkSignals.decode(byteArrayOf(1, 2, 3)))
    }
}
