package app.getknit.knit.call

/**
 * The STUN Binding request a phone measures a TURN server with, and the one check its answer gets (C-7.6-52; RFC 8489
 * §5, §6, §14.2). A request is 20 bytes: type 0x0001, length 0, the magic cookie and 12 random bytes of transaction ID,
 * with no attribute — coturn answers it without a credential, on the port a call's media will use.
 *
 * An answer counts only as a Binding success (0x0101) carrying the request's cookie and transaction ID and an
 * XOR-MAPPED-ADDRESS. That attribute is found and never read: the mapped address is this phone's own, and nothing here
 * logs, keeps or sends it. Pure, so the probe's socket stays a thin shell around it.
 */
object StunBinding {
    /** The transaction ID's length (RFC 8489 §5). */
    const val TRANSACTION_BYTES = 12

    private const val HEADER_BYTES = 20
    private const val ATTRIBUTE_HEADER_BYTES = 4
    private const val BINDING_REQUEST = 0x0001
    private const val BINDING_SUCCESS = 0x0101
    private const val MAGIC_COOKIE = 0x2112A442
    private const val XOR_MAPPED_ADDRESS = 0x0020

    // Family, port and an IPv4 address: the shortest XOR-MAPPED-ADDRESS there is (RFC 8489 §14.2).
    private const val MIN_MAPPED_BYTES = 8
    private const val COOKIE_AT = 4
    private const val TRANSACTION_AT = 8
    private const val BYTE_MASK = 0xFF
    private const val BYTE_BITS = 8

    /** A Binding request under [transaction], [TRANSACTION_BYTES] long. */
    fun request(transaction: ByteArray): ByteArray {
        require(transaction.size == TRANSACTION_BYTES) { "a transaction ID is $TRANSACTION_BYTES bytes" }
        val out = ByteArray(HEADER_BYTES)
        putShort(out, 0, BINDING_REQUEST)
        // Bytes 2-3, the attributes' length, stay zero: the request carries none.
        putInt(out, COOKIE_AT, MAGIC_COOKIE)
        transaction.copyInto(out, TRANSACTION_AT)
        return out
    }

    /**
     * Whether the first [length] bytes of [packet] answer the request under [transaction]: a Binding success with its
     * cookie and transaction ID, whose attributes fit the packet and include an XOR-MAPPED-ADDRESS.
     */
    @Suppress("ReturnCount") // a ladder of refusals, each a reason the packet is not the answer
    fun accepts(
        packet: ByteArray,
        length: Int,
        transaction: ByteArray,
    ): Boolean {
        if (length < HEADER_BYTES || length > packet.size || transaction.size != TRANSACTION_BYTES) return false
        if (short(packet, 0) != BINDING_SUCCESS) return false
        val attributes = short(packet, 2)
        if (attributes % ATTRIBUTE_HEADER_BYTES != 0 || HEADER_BYTES + attributes > length) return false
        if (int(packet, COOKIE_AT) != MAGIC_COOKIE) return false
        for (i in 0 until TRANSACTION_BYTES) if (packet[TRANSACTION_AT + i] != transaction[i]) return false
        val end = HEADER_BYTES + attributes
        var at = HEADER_BYTES
        while (at + ATTRIBUTE_HEADER_BYTES <= end) {
            val type = short(packet, at)
            val size = short(packet, at + 2)
            val value = at + ATTRIBUTE_HEADER_BYTES
            if (value + size > end) return false
            if (type == XOR_MAPPED_ADDRESS) return size >= MIN_MAPPED_BYTES
            // Each value is padded to a multiple of four (RFC 8489 §14).
            at = value + (size + ATTRIBUTE_HEADER_BYTES - 1) / ATTRIBUTE_HEADER_BYTES * ATTRIBUTE_HEADER_BYTES
        }
        return false
    }

    private fun short(
        bytes: ByteArray,
        at: Int,
    ): Int = (bytes[at].toInt() and BYTE_MASK shl BYTE_BITS) or (bytes[at + 1].toInt() and BYTE_MASK)

    private fun int(
        bytes: ByteArray,
        at: Int,
    ): Int = (short(bytes, at) shl (2 * BYTE_BITS)) or short(bytes, at + 2)

    private fun putShort(
        bytes: ByteArray,
        at: Int,
        value: Int,
    ) {
        bytes[at] = (value shr BYTE_BITS).toByte()
        bytes[at + 1] = value.toByte()
    }

    private fun putInt(
        bytes: ByteArray,
        at: Int,
        value: Int,
    ) {
        putShort(bytes, at, value shr (2 * BYTE_BITS))
        putShort(bytes, at + 2, value)
    }
}
