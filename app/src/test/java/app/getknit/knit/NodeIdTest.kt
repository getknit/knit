package app.getknit.knit

import app.getknit.knit.identity.NodeId
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeIdTest {
    private val format = Regex("^[a-z2-7]{26}$")

    @Test
    fun deriveIsDeterministic() {
        // Same device seed -> same node id on every call (and therefore on every install/device).
        repeat(100) {
            val seed = "seed-$it"
            assertEquals(NodeId.derive(seed), NodeId.derive(seed))
        }
    }

    @Test
    fun goldenIdsAreStable() {
        // Regression guard: any change to the salt, the digest, or the byte->base32 mapping moves these.
        // 128-bit ids = base32 (lowercase, unpadded) of the first 16 bytes of SHA-256("knit-node-id-v2:" + seed).
        assertEquals("ffbbh6thbepahqxsv2gqog45m4", NodeId.derive("android-id-123"))
        assertEquals("4sdvhqyrkpj3vccencfotsiiua", NodeId.derive("0123456789abcdef"))
        assertEquals("ugtomq2rcpjfgs2o5co7ckukky", NodeId.derive("aaaaaaaaaaaaaaaa"))
        assertEquals("wnuon3pfxkl6auzh2ngvinfc3i", NodeId.derive(""))
    }

    @Test
    fun idIsAlways26CharBase32() {
        // 128 bits base32-encoded to the [a-z2-7]{26} shape. Filesystem/delimiter-safe (no `:` `/` `-`), so
        // it stays disjoint from the `g-…` group-id namespace and survives avatar filenames / the verify payload.
        repeat(5_000) {
            val id = NodeId.derive("device-$it")
            assertTrue("'$id' must be 26 chars of [a-z2-7]", format.matches(id))
        }
    }

    @Test
    fun distinctSeedsYieldDistinctIds() {
        val ids = (1..5_000).map { NodeId.derive("device-$it") }.toSet()
        // No collisions expected across a few thousand distinct seeds (128-bit space).
        assertEquals(5_000, ids.size)
        // A single-character change must produce a different id.
        assertNotEquals(NodeId.derive("aaaaaaaaaaaaaaaa"), NodeId.derive("aaaaaaaaaaaaaaab"))
    }

    @Test
    fun idIsNotTheRawSeed() {
        // Salting means the id is never just the (truncated) raw seed itself.
        val androidIdShaped = "9774d56d682e549c"
        assertNotEquals(androidIdShaped, NodeId.derive(androidIdShaped))
        assertNotEquals(androidIdShaped.take(NodeId.LENGTH), NodeId.derive(androidIdShaped))
    }

    @Test
    fun rawBytesRoundTripThroughTheAdvertHelpers() {
        // The BLE advert carries the id as its raw 16 bytes; toBytes/fromBytes must be exact inverses so the
        // full 128-bit id survives the advert (not a truncated prefix).
        repeat(1_000) {
            val id = NodeId.derive("advert-$it")
            val bytes = NodeId.toBytes(id)
            assertEquals("id decodes to exactly ${NodeId.BYTES} bytes", NodeId.BYTES, bytes.size)
            assertEquals("bytes re-encode to the same id", id, NodeId.fromBytes(bytes))
        }
        // ...and every 16-byte value encodes to a canonical id that decodes back byte-for-byte.
        val raw = ByteArray(NodeId.BYTES) { (it * 37 + 11).toByte() }
        assertArrayEquals(raw, NodeId.toBytes(NodeId.fromBytes(raw)))
    }

    @Test
    fun fromPublicKeyBundleIsDeterministicAndShaped() {
        // The self-certifying id is a deterministic, [a-z2-7]{26}-shaped function of the bundle string,
        // so both sides of a conversation compute the same id from the same advertised bundle.
        repeat(1_000) {
            val bundle = "bundle-$it"
            val id = NodeId.fromPublicKeyBundle(bundle)
            assertEquals(id, NodeId.fromPublicKeyBundle(bundle))
            assertTrue("'$id' must be 26 chars of [a-z2-7]", format.matches(id))
        }
    }

    @Test
    fun distinctBundlesYieldDistinctIds() {
        // The anti-impersonation property at the derivation level: a different key bundle (e.g. an
        // attacker's) maps to a different nodeId, so it can never claim a victim's id.
        val ids = (1..5_000).map { NodeId.fromPublicKeyBundle("bundle-$it") }.toSet()
        assertEquals(5_000, ids.size)
    }
}
