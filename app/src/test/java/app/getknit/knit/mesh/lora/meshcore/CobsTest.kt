package app.getknit.knit.mesh.lora.meshcore

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class CobsTest {
    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun theStandardVectorsEncodeAsPublished() {
        // Cheshire and Baker's examples, without the trailing delimiter.
        assertArrayEquals(b(0x01), Cobs.encode(b()))
        assertArrayEquals(b(0x01, 0x01), Cobs.encode(b(0x00)))
        assertArrayEquals(b(0x01, 0x01, 0x01), Cobs.encode(b(0x00, 0x00)))
        assertArrayEquals(b(0x03, 0x11, 0x22, 0x02, 0x33), Cobs.encode(b(0x11, 0x22, 0x00, 0x33)))
        assertArrayEquals(b(0x05, 0x11, 0x22, 0x33, 0x44), Cobs.encode(b(0x11, 0x22, 0x33, 0x44)))
        assertArrayEquals(b(0x02, 0x11, 0x01, 0x01, 0x01), Cobs.encode(b(0x11, 0x00, 0x00, 0x00)))
        val run254 = ByteArray(254) { (it + 1).toByte() }
        assertArrayEquals(b(0xFF) + run254 + b(0x01), Cobs.encode(run254))
        val run255 = ByteArray(255) { (it % 255 + 1).toByte() }
        assertArrayEquals(b(0xFF) + run255.copyOf(254) + b(0x02, run255[254].toInt()), Cobs.encode(run255))
    }

    @Test
    fun everyInputRoundTripsAndItsEncodingHasNoZero() {
        // Q11: a zero byte cuts a contact text silently and is still acked, so the encoded body must never carry one.
        val rng = Random(11)
        repeat(5_000) {
            val size = rng.nextInt(0, 600)
            val data =
                ByteArray(size) {
                    when (rng.nextInt(4)) {
                        0 -> 0
                        else -> rng.nextInt().toByte()
                    }
                }
            val enc = Cobs.encode(data)
            assertTrue("no zero in the encoding of $size bytes", enc.none { b -> b.toInt() == 0 })
            assertTrue("at most ${Cobs.overhead(size)} bytes added", enc.size <= size + Cobs.overhead(size))
            assertArrayEquals(data, Cobs.decode(enc))
        }
    }

    @Test
    fun aFullSealFitsTheDirectTextCap() {
        val worst = ByteArray(MeshcoreProto.DIRECT_SEAL_CAP) { 0x11 }
        assertEquals(MeshcoreProto.DIRECT_TEXT_CAP, Cobs.encode(worst).size)
        val zeros = ByteArray(MeshcoreProto.DIRECT_SEAL_CAP)
        assertEquals(MeshcoreProto.DIRECT_TEXT_CAP, Cobs.encode(zeros).size)
        assertEquals(157, MeshcoreProto.DIRECT_TEXT_CAP)
        assertEquals(156, MeshcoreProto.DIRECT_SEAL_CAP)
    }

    @Test
    fun whatNoEncoderWroteDecodesToNull() {
        assertNull(Cobs.decode(b()))
        assertNull("a zero code", Cobs.decode(b(0x00)))
        assertNull("a zero inside a block", Cobs.decode(b(0x03, 0x11, 0x00)))
        assertNull("a code past the end", Cobs.decode(b(0x05, 0x11, 0x22)))
        // A full 254-byte run left open decodes to the same bytes as the canonical form: refused, one text one frame.
        assertNull("non-canonical", Cobs.decode(b(0xFF) + ByteArray(254) { 1 }))
        assertArrayEquals(ByteArray(254) { 1 }, Cobs.decode(b(0xFF) + ByteArray(254) { 1 } + b(0x01)))
    }

    @Test
    fun randomBytesNeverThrow() {
        val rng = Random(42)
        repeat(10_000) { Cobs.decode(ByteArray(rng.nextInt(0, 300)) { rng.nextInt().toByte() }) }
    }
}
