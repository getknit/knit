package app.getknit.knit.mesh.crypto

import app.getknit.knit.identity.NodeId
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import java.util.Random

/**
 * [BlindTag] on plain JVM (ADR 2026-11.xp6d). The pinned tags were computed outside this code, with Python's `hmac`
 * over the layout the ADR states, so they check the layout and not just that mint and match agree. Every random draw
 * comes from a seeded [SeededRandom], so a run is the same on any runner.
 */
class BlindTagTest {
    /** A [SecureRandom] whose bytes are a seeded [Random]'s: deterministic, for tests only. */
    private class SeededRandom(
        seed: Long,
    ) : SecureRandom() {
        private val inner = Random(seed)

        override fun nextBytes(bytes: ByteArray) = inner.nextBytes(bytes)
    }

    private val key = ByteArray(BlindTag.KEY_BYTES) { it.toByte() }
    private val otherKey = ByteArray(BlindTag.KEY_BYTES) { (it + 1).toByte() }

    private fun hex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun aTagFillsTheNodeIdSlot() {
        assertEquals(NodeId.BYTES, BlindTag.BYTES)
        assertEquals(32, BlindTag.KEY_BYTES)
        assertEquals(BlindTag.KEY_BYTES, BlindTag.newKey(SeededRandom(1)).size)
    }

    @Test
    fun aMintedTagMatchesItsOwnKey() {
        val random = SeededRandom(2)
        repeat(64) {
            val tag = BlindTag.mint(key, random)
            assertEquals(BlindTag.BYTES, tag.size)
            assertTrue(BlindTag.hasPrefix(tag))
            assertTrue(BlindTag.matches(key, tag))
        }
    }

    @Test
    fun aTagDoesNotMatchAnotherKey() {
        val random = SeededRandom(3)
        repeat(64) { assertFalse(BlindTag.matches(otherKey, BlindTag.mint(key, random))) }
    }

    @Test
    fun everyTagReadAsANodeIdStartsZzzzAndRoundTrips() {
        val random = SeededRandom(4)
        val tags =
            List(256) { BlindTag.mint(BlindTag.newKey(random), random) } + BlindTag.mint(key, 0L) +
                BlindTag.mint(key, BlindTag.NONCE_MASK)
        for (tag in tags) {
            val id = NodeId.fromBytes(tag)
            assertTrue(id, id.startsWith("zzzz"))
            assertArrayEquals(id, tag, NodeId.toBytesOrNull(id)) // null when the id is not canonical
        }
    }

    @Test
    fun twoMintsUnderOneKeyDiffer() {
        val random = SeededRandom(5)
        val tags = List(256) { BlindTag.mint(key, random).toList() }
        assertEquals(tags.size, tags.toSet().size)
    }

    @Test
    fun aFixedKeyAndNonceGiveThePinnedBytes() {
        // Python: hmac.new(bytes(range(32)), b"knit/advert/v1/tag" + head, sha256).digest()[:8]
        assertArrayEquals(hex("ce739123456789abb3c9b0047fdb1824"), BlindTag.mint(key, 0x123456789ABL))
        // The all-ones nonce: every nonce bit lands below the prefix and none spills into it.
        assertArrayEquals(
            hex("ce739fffffffffff360ba7f461b761d3"),
            BlindTag.mint(ByteArray(BlindTag.KEY_BYTES) { 0xA5.toByte() }, BlindTag.NONCE_MASK),
        )
    }

    @Test
    fun aNonceOutsideFortyFourBitsIsRefusedAtMint() {
        for (nonce in listOf(-1L, BlindTag.NONCE_MASK + 1)) {
            val thrown = runCatching { BlindTag.mint(key, nonce) }.exceptionOrNull()
            assertTrue("nonce $nonce", thrown is IllegalArgumentException)
        }
    }

    @Test
    fun oneFlippedBitAnywhereStopsTheMatch() {
        val tag = BlindTag.mint(key, 0x0F0F0F0F0F0L)
        for (bit in 0 until BlindTag.BYTES * 8) {
            val flipped = tag.copyOf()
            flipped[bit / 8] = (flipped[bit / 8].toInt() xor (0x80 ushr (bit % 8))).toByte()
            assertFalse("bit $bit", BlindTag.matches(key, flipped))
            // Bits 0..19 are the prefix: flipping one of them is refused before the MAC, and only those.
            assertEquals("bit $bit", bit >= 20, BlindTag.hasPrefix(flipped))
        }
    }

    @Test
    fun matchesIsTotal() {
        val tag = BlindTag.mint(key, 7L)
        // key + one trailing 0x00 is the same HMAC key as key (RFC 2104 zero-pads short keys), so only the size guard
        // can refuse it: it is the case that pins "a key of the wrong size answers false".
        val keys = listOf(ByteArray(0), ByteArray(31), ByteArray(33), key.copyOf(BlindTag.KEY_BYTES + 1))
        for (bad in keys) {
            assertFalse(BlindTag.matches(bad, tag))
            assertFalse(BlindTag.Matcher(bad).matches(tag))
        }
        val tags =
            listOf(
                ByteArray(0),
                tag.copyOf(15),
                tag.copyOf(17),
                ByteArray(BlindTag.BYTES), // no prefix
                key.copyOf(BlindTag.BYTES), // a clear id's shape
            )
        val matcher = BlindTag.Matcher(key)
        for (bad in tags) {
            assertFalse(BlindTag.matches(key, bad))
            assertFalse(matcher.matches(bad))
            assertFalse(BlindTag.matches(ByteArray(0), bad))
        }
    }

    @Test
    fun theMatcherAgreesWithMatchesAndHoldsAcrossManyTags() {
        val random = SeededRandom(6)
        val mine = BlindTag.Matcher(key)
        val theirs = BlindTag.Matcher(otherKey)
        repeat(64) {
            val tag = BlindTag.mint(if (it % 2 == 0) key else otherKey, random)
            assertEquals(BlindTag.matches(key, tag), mine.matches(tag))
            assertEquals(BlindTag.matches(otherKey, tag), theirs.matches(tag))
            assertTrue(mine.matches(tag) != theirs.matches(tag))
        }
    }
}
