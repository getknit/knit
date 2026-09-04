package app.getknit.knit.linkpreview

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/** Boundary values for every range the fetch refuses, with a literal parser (no resolver) supplying the bytes. */
class PublicAddressPolicyTest {
    private fun bytesOf(literal: String): ByteArray = InetAddress.getByName(literal).address

    private fun refused(vararg literals: String) = literals.forEach { assertFalse(it, PublicAddressPolicy.isPublic(bytesOf(it))) }

    private fun allowed(vararg literals: String) = literals.forEach { assertTrue(it, PublicAddressPolicy.isPublic(bytesOf(it))) }

    @Test
    fun theIpv4PrivateAndSpecialRangesAreRefusedAtTheirEdges() {
        refused("0.0.0.0", "0.255.255.255", "10.0.0.0", "10.255.255.255", "127.0.0.1", "127.255.255.255")
        refused("100.64.0.0", "100.127.255.255", "169.254.0.1", "169.254.255.255")
        refused("172.16.0.0", "172.31.255.255", "192.0.0.1", "192.168.0.0", "192.168.255.255")
        refused("198.18.0.0", "198.19.255.255", "224.0.0.1", "239.255.255.255", "240.0.0.1", "255.255.255.255")
        allowed("11.0.0.0", "9.255.255.255", "100.63.255.255", "100.128.0.0", "169.253.255.255", "169.255.0.0")
        allowed("172.15.255.255", "172.32.0.0", "192.0.1.1", "192.167.255.255", "192.169.0.0", "198.17.255.255")
        allowed("198.20.0.0", "223.255.255.255", "8.8.8.8", "1.1.1.1")
    }

    @Test
    fun theIpv6LocalAndSpecialRangesAreRefused() {
        refused("::", "::1", "fc00::1", "fdff:ffff::1", "fe80::1", "febf::1", "fec0::1", "ff02::1", "ff00::")
        allowed("fe00::1", "fe7f::1", "2606:4700::1111", "2001:4860:4860::8888", "fbff::1")
    }

    @Test
    fun anEmbeddedIpv4IsJudgedByTheIpv4Rules() {
        // IPv4-mapped and IPv4-compatible forms, NAT64, and 6to4 all carry a v4 address that must not slip past.
        refused("::ffff:10.0.0.1", "::ffff:127.0.0.1", "::ffff:192.168.1.1", "::10.0.0.1")
        allowed("::ffff:8.8.8.8", "::ffff:1.1.1.1")
        refused("64:ff9b::a00:1", "64:ff9b::7f00:1")
        allowed("64:ff9b::808:808")
        refused("2002:0a00:1::", "2002:c0a8:101::")
        allowed("2002:808:808::")
    }

    @Test
    fun anAddressOfAnyOtherLengthIsRefused() {
        assertFalse(PublicAddressPolicy.isPublic(ByteArray(0)))
        assertFalse(PublicAddressPolicy.isPublic(ByteArray(3) { 8 }))
        assertFalse(PublicAddressPolicy.isPublic(ByteArray(5) { 8 }))
        assertFalse(PublicAddressPolicy.isPublic(ByteArray(17) { 0x20 }))
    }
}
