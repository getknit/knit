package app.getknit.knit.mesh.protocol

import app.getknit.knit.mesh.link.FastFrameCodec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * The MeshCore plane's bound two-part fragment (`vectors/lora-frag-v1.json`, tag `0x07`, ADR 2026-11.gsqu's Stage 5c-1
 * amendment, bound both ways): one fixed 250-byte frame split at MeshCore's 164-byte channel-data cap under a fixed id —
 * both packets, the commitment each carries (the first 8 bytes of SHA-256 over the other part's slice), and the frame they
 * join back to. Rebuilt on every run and compared; the pinned packets must also parse, name each other and join. A
 * MeshCore-only form with no fielded reader, so this file is additive: no other vector moved. The iOS port reads it once
 * MeshCore reaches iOS.
 *
 * Write mode (`KNIT_WRITE_VECTORS=1`) rewrites the file; only an intended change of the form does that.
 */
class LoraFragVectorTest {
    private val frame = fixtureBytes(FRAME_BYTES, 61)

    private fun sha8(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes).copyOf(FastFrameCodec.COMMITMENT_BYTES)

    private fun build(): JsonObject {
        val (part0, part1) = checkNotNull(FastFrameCodec.fragmentBound(frame, CAP, FRAG_ID))
        val sliceAt = FastFrameCodec.FRAG_HEADER_BYTES + FastFrameCodec.COMMITMENT_BYTES
        val slice0 = part0.copyOfRange(sliceAt, part0.size)
        val slice1 = part1.copyOfRange(sliceAt, part1.size)
        return buildJsonObject {
            put("about", ABOUT)
            put("cap", CAP)
            put("fragId", FRAG_ID)
            put("frame", frame.toHex())
            put("commitment", sha8(slice1).toHex())
            put("commitmentBack", sha8(slice0).toHex())
            put("part0", part0.toHex())
            put("part1", part1.toHex())
        }
    }

    @Test
    fun `the bound parts rebuild byte for byte`() {
        assumeFalse(VectorFiles.writing)
        val file = VectorFiles.read(FILE)
        val built = build()
        for (key in listOf("cap", "fragId", "frame", "commitment", "commitmentBack", "part0", "part1")) {
            assertEquals("lora-frag vector '$key' drifted", file.getValue(key), built.getValue(key))
        }
    }

    @Test
    fun `the pinned parts parse, name each other and join back to the frame`() {
        assumeFalse(VectorFiles.writing)
        val file = VectorFiles.read(FILE)

        fun hex(key: String): ByteArray =
            file
                .getValue(key)
                .jsonPrimitive.content
                .fromHex()
        val head = checkNotNull(FastFrameCodec.parseBoundFragment(hex("part0")))
        val tail = checkNotNull(FastFrameCodec.parseBoundFragment(hex("part1")))
        assertEquals(file.getValue("fragId").jsonPrimitive.int, head.fragId)
        assertArrayEquals(hex("commitment"), head.commitment)
        assertArrayEquals(FastFrameCodec.commitmentOf(tail.payload), head.commitment)
        assertArrayEquals(hex("commitmentBack"), tail.commitment)
        assertArrayEquals(FastFrameCodec.commitmentOf(head.payload), tail.commitment)
        assertArrayEquals(hex("frame"), head.payload + tail.payload)
    }

    @Test
    fun `KNIT_WRITE_VECTORS=1 rewrites the lora-frag vectors`() {
        assumeTrue(VectorFiles.writing)
        VectorFiles.write(FILE, build())
    }

    private companion object {
        const val FILE = "lora-frag-v1.json"
        const val CAP = 164
        const val FRAG_ID = 0x4B1D
        const val FRAME_BYTES = 250
        const val ABOUT =
            "MeshCore bound two-part fragment (ADR 2026-11.gsqu Stage 5c-1, bound both ways), tag 0x07, for a frame longer " +
                "than one packet (cap bytes) and at most 2 * (cap - 12). part0 = 0x07 || fragId (u16 BE) || 0x02 (part 0 of 2) " +
                "|| commitment (8) || frame[0 : cap - 12]; part1 = 0x07 || fragId || 0x12 (part 1 of 2) || commitmentBack (8) " +
                "|| frame[cap - 12 :]. commitment = SHA-256(part 1's slice)[0:8], commitmentBack = SHA-256(part 0's slice)" +
                "[0:8]. A receiver pairs a part 0 and a part 1 only when each names the other, and joins part 0's slice || part " +
                "1's slice into a 0x03, 0x05 or 0x06 frame it then verifies. frame here is fixture bytes, not a real frame: " +
                "the vector pins the split alone."
    }
}
