package app.getknit.knit

import app.getknit.knit.identity.NodeId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeIdTest {

    private val format = Regex("^[a-z0-9]{8}$")

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
        // Regression guard: any change to the salt, the digest, or the byte->alphabet mapping moves these.
        assertEquals("y8ycfg91", NodeId.derive("android-id-123"))
        assertEquals("vbcyo74s", NodeId.derive("0123456789abcdef"))
        assertEquals("p1ontb6b", NodeId.derive("aaaaaaaaaaaaaaaa"))
        assertEquals("u8cxx1o7", NodeId.derive(""))
    }

    @Test
    fun idIsAlwaysEightLowercaseAlphanumerics() {
        // Stays in the historical [a-z0-9]{8} shape so every consumer (mesh endpoint-info advert, avatar
        // filenames, the friendly alias, the profile-frame id) is unaffected.
        repeat(5_000) {
            val id = NodeId.derive("device-$it")
            assertTrue("'$id' must be 8 chars of [a-z0-9]", format.matches(id))
        }
    }

    @Test
    fun distinctSeedsYieldDistinctIds() {
        val ids = (1..5_000).map { NodeId.derive("device-$it") }.toSet()
        // No collisions expected across a few thousand distinct seeds (~41-bit space).
        assertEquals(5_000, ids.size)
        // A single-character change must produce a different id.
        assertNotEquals(NodeId.derive("aaaaaaaaaaaaaaaa"), NodeId.derive("aaaaaaaaaaaaaaab"))
    }

    @Test
    fun idIsNotTheRawSeed() {
        // Salting means the id is never just the (truncated) ANDROID_ID itself.
        val androidIdShaped = "9774d56d682e549c"
        assertNotEquals(androidIdShaped, NodeId.derive(androidIdShaped))
        assertNotEquals(androidIdShaped.take(NodeId.LENGTH), NodeId.derive(androidIdShaped))
    }

    @Test
    fun fromPublicKeyBundleIsDeterministicAndShaped() {
        // The self-certifying id is a deterministic, [a-z0-9]{8}-shaped function of the bundle string,
        // so both sides of a conversation compute the same id from the same advertised bundle.
        repeat(1_000) {
            val bundle = "bundle-$it"
            val id = NodeId.fromPublicKeyBundle(bundle)
            assertEquals(id, NodeId.fromPublicKeyBundle(bundle))
            assertTrue("'$id' must be 8 chars of [a-z0-9]", format.matches(id))
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
