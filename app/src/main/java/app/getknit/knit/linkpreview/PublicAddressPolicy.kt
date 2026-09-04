package app.getknit.knit.linkpreview

/**
 * Whether an IP address is one a link-preview fetch may connect to. A link in a chat is chosen by whoever
 * typed it, so a fetch is a request to open a socket toward an address of the sender's choosing — and the
 * addresses that must never be reachable that way are the ones behind this phone's own interfaces: the
 * loopback, the LAN it sits on, the Wi-Fi Aware link-local subnet the mesh itself runs over, a carrier's
 * CGNAT range. Applied to **every** address a hostname resolves to, on every redirect hop, from inside the
 * HTTP client's own resolver (`OkHttpPreviewFetcher`), so a name that resolves privately is refused before a
 * connection is attempted rather than after.
 *
 * Byte-level on purpose: it takes the raw address so the JVM tests need no resolver, and so the embedded
 * IPv4 in a mapped, NAT64 or 6to4 IPv6 address is judged by the IPv4 rules instead of slipping past them.
 * Pure Kotlin, no Android.
 */
object PublicAddressPolicy {
    /** True when [address] (4 or 16 raw bytes) is globally routable; anything else, including an odd length, is refused. */
    fun isPublic(address: ByteArray): Boolean =
        when (address.size) {
            V4_BYTES -> isPublicV4(address, 0)
            V6_BYTES -> isPublicV6(address)
            else -> false
        }

    @Suppress("MagicNumber", "CyclomaticComplexMethod") // the IANA special-purpose ranges, one line each
    private fun isPublicV4(
        a: ByteArray,
        at: Int,
    ): Boolean {
        val b0 = a[at].toInt() and 0xFF
        val b1 = a[at + 1].toInt() and 0xFF
        val b2 = a[at + 2].toInt() and 0xFF
        return when {
            b0 == 0 -> false

            // 0.0.0.0/8 "this network"
            b0 == 10 -> false

            // 10/8 private
            b0 == 100 && b1 in 64..127 -> false

            // 100.64/10 carrier-grade NAT
            b0 == 127 -> false

            // 127/8 loopback
            b0 == 169 && b1 == 254 -> false

            // 169.254/16 link-local
            b0 == 172 && b1 in 16..31 -> false

            // 172.16/12 private
            b0 == 192 && b1 == 0 && b2 == 0 -> false

            // 192.0.0/24 IETF protocol assignments
            b0 == 192 && b1 == 168 -> false

            // 192.168/16 private
            b0 == 198 && b1 in 18..19 -> false

            // 198.18/15 benchmarking
            b0 >= 224 -> false

            // 224/4 multicast, 240/4 reserved, 255.255.255.255 broadcast
            else -> true
        }
    }

    @Suppress("MagicNumber", "CyclomaticComplexMethod") // the IANA special-purpose ranges, one line each
    private fun isPublicV6(a: ByteArray): Boolean {
        val b0 = a[0].toInt() and 0xFF
        val b1 = a[1].toInt() and 0xFF
        return when {
            a.all { it == 0.toByte() } -> false

            // :: unspecified
            zeroThrough(a, 15) && a[15] == 1.toByte() -> false

            // ::1 loopback
            isV4Mapped(a) -> isPublicV4(a, 12)

            // ::ffff:a.b.c.d
            zeroThrough(a, 12) -> isPublicV4(a, 12)

            // ::a.b.c.d (deprecated IPv4-compatible form)
            isNat64(a) -> isPublicV4(a, 12)

            // 64:ff9b::/96 NAT64
            b0 == 0x20 && b1 == 0x02 -> isPublicV4(a, 2)

            // 2002::/16 6to4
            b0 and 0xFE == 0xFC -> false

            // fc00::/7 unique local
            b0 == 0xFE && b1 and 0xC0 == 0x80 -> false

            // fe80::/10 link-local (the Wi-Fi Aware NDI subnet)
            b0 == 0xFE && b1 and 0xC0 == 0xC0 -> false

            // fec0::/10 site-local (deprecated, still routable)
            b0 == 0xFF -> false

            // ff00::/8 multicast
            else -> true
        }
    }

    private fun zeroThrough(
        a: ByteArray,
        end: Int,
    ): Boolean = (0 until end).all { a[it] == 0.toByte() }

    @Suppress("MagicNumber")
    private fun isV4Mapped(a: ByteArray): Boolean = zeroThrough(a, 10) && a[10] == 0xFF.toByte() && a[11] == 0xFF.toByte()

    @Suppress("MagicNumber")
    private fun isNat64(a: ByteArray): Boolean =
        a[0] == 0.toByte() &&
            a[1] == 0x64.toByte() &&
            a[2] == 0xFF.toByte() &&
            a[3] == 0x9B.toByte() &&
            (4 until 12).all { a[it] == 0.toByte() }

    private const val V4_BYTES = 4
    private const val V6_BYTES = 16
}
