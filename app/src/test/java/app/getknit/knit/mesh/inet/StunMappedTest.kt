package app.getknit.knit.mesh.inet

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A direct link's reflexive address, read off RFC 5769's sample Binding responses (§2.2 IPv4, §2.3 IPv6). */
class StunMappedTest {
    private val tx = hex("b7e7a701bc34d686fa87dfae")

    // SOFTWARE "test vector", then the XOR-MAPPED-ADDRESS, then MESSAGE-INTEGRITY and FINGERPRINT (not checked here).
    private val software = hex("8022000b 74657374 20766563 746f7220")

    private val v4Response =
        hex("0101003c 2112a442") + tx + software +
            hex("00200008 0001a147 e112a643") +
            hex("00080014 2b91f599 fd9e90c3 8c7489f9 2af9ba53 f06be7d7") +
            hex("80280004 c07d4c96")

    private val v6Response =
        hex("01010048 2112a442") + tx + software +
            hex("00200014 0002a147 0113a9fa a5d3f179 bc25f4b5 bed2b9d9") +
            hex("00080014 a382954e 4be67bf1 1784c97c 8292c275 bfe3ed41") +
            hex("80280004 c8fb0b4c")

    @Test
    fun theIpv4SampleMapsTo192_0_2_1Port32853() {
        val mapped = checkNotNull(StunMapped.mapped(v4Response, v4Response.size, tx)) { "the IPv4 sample maps" }
        assertArrayEquals(byteArrayOf(192.toByte(), 0, 2, 1), mapped.address)
        assertEquals(32853, mapped.port)
    }

    @Test
    fun theIpv6SampleMapsToItsDocumentationAddressPort32853() {
        val mapped = checkNotNull(StunMapped.mapped(v6Response, v6Response.size, tx)) { "the IPv6 sample maps" }
        assertArrayEquals(hex("20010db8 12345678 00112233 44556677"), mapped.address)
        assertEquals(32853, mapped.port)
    }

    @Test
    fun anAnswerToAnotherTransactionMapsNothing() {
        val other = tx.copyOf().also { it[0] = 0 }
        assertNull(StunMapped.mapped(v4Response, v4Response.size, other))
    }

    @Test
    fun anUnknownFamilyOrAShortValueMapsNothing() {
        val family = v4Response.copyOf().also { it[HEADER + 16 + 4 + 1] = 0x03 }
        assertNull(StunMapped.mapped(family, family.size, tx))
        // An IPv6 family in an 8-byte value: the address would run past the attribute.
        val short = v4Response.copyOf().also { it[HEADER + 16 + 4 + 1] = 0x02 }
        assertNull(StunMapped.mapped(short, short.size, tx))
    }

    @Test
    fun aTruncatedPacketMapsNothing() {
        assertNull(StunMapped.mapped(v4Response, 40, tx))
    }

    private companion object {
        const val HEADER = 20

        fun hex(s: String): ByteArray =
            s
                .replace(" ", "")
                .chunked(2)
                .map { it.toInt(16).toByte() }
                .toByteArray()
    }
}
