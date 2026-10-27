package app.getknit.knit.mesh.inet

import app.getknit.knit.call.StunBinding

/**
 * The address a STUN server saw a direct link's socket send from (ADR 2026-10.a5yj): the XOR-MAPPED-ADDRESS of a
 * Binding success (RFC 8489 §14.2), read only where [StunBinding.accepts] took the answer. It is this phone's own
 * reflexive address, learnt on the very socket the link then punches from, so the mapping a peer is told is the one its
 * datagrams meet. [StunBinding] itself never reads the attribute — a TURN probe has no use for it — so the decoding
 * lives here, beside the one caller that hands the address on, sealed, to a contact. Pure, so `StunMappedTest` pins it
 * on RFC 5769's vectors.
 */
object StunMapped {
    /** A reflexive transport address: [address] is 4 bytes (IPv4) or 16 (IPv6), [port] 1..65535. */
    class Mapped(
        val address: ByteArray,
        val port: Int,
    )

    private const val HEADER_BYTES = 20
    private const val ATTRIBUTE_HEADER_BYTES = 4
    private const val XOR_MAPPED_ADDRESS = 0x0020
    private const val FAMILY_V4 = 0x01
    private const val FAMILY_V6 = 0x02
    private const val V4_BYTES = 4
    private const val V6_BYTES = 16
    private const val COOKIE_AT = 4
    private const val BYTE_MASK = 0xFF
    private const val BYTE_BITS = 8

    /**
     * The mapped address in the first [length] bytes of [packet], the answer to the request under [transaction], or null
     * when [StunBinding.accepts] refuses it or its XOR-MAPPED-ADDRESS names no family this reads, or port zero.
     */
    @Suppress("ReturnCount") // a ladder of refusals ahead of the one decode
    fun mapped(
        packet: ByteArray,
        length: Int,
        transaction: ByteArray,
    ): Mapped? {
        if (!StunBinding.accepts(packet, length, transaction)) return null
        val end = HEADER_BYTES + short(packet, 2)
        var at = HEADER_BYTES
        while (at + ATTRIBUTE_HEADER_BYTES <= end) {
            val type = short(packet, at)
            val size = short(packet, at + 2)
            val value = at + ATTRIBUTE_HEADER_BYTES
            if (type == XOR_MAPPED_ADDRESS) return decode(packet, value, size)
            at = value + (size + ATTRIBUTE_HEADER_BYTES - 1) / ATTRIBUTE_HEADER_BYTES * ATTRIBUTE_HEADER_BYTES
        }
        return null
    }

    // The value is: a reserved byte, the family, the port XOR the cookie's top half, the address XOR the cookie (IPv4) or
    // the cookie and the transaction ID (IPv6). The header's bytes 4..19 are exactly that key.
    private fun decode(
        packet: ByteArray,
        value: Int,
        size: Int,
    ): Mapped? {
        val bytes =
            when (packet[value + 1].toInt() and BYTE_MASK) {
                FAMILY_V4 -> V4_BYTES
                FAMILY_V6 -> V6_BYTES
                else -> return null
            }
        if (size < V4_BYTES + bytes) return null
        val port = short(packet, value + 2) xor short(packet, COOKIE_AT)
        if (port == 0) return null
        val address = ByteArray(bytes) { (packet[value + V4_BYTES + it].toInt() xor packet[COOKIE_AT + it].toInt()).toByte() }
        return Mapped(address, port)
    }

    private fun short(
        bytes: ByteArray,
        at: Int,
    ): Int = (bytes[at].toInt() and BYTE_MASK shl BYTE_BITS) or (bytes[at + 1].toInt() and BYTE_MASK)
}
