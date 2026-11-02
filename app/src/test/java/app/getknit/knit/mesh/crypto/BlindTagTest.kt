package app.getknit.knit.mesh.crypto

import app.getknit.knit.identity.NodeId
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import java.util.Random

/**
 * [BlindTag]'s slotted construction on plain JVM (ADR 2026-11.xp6d, revised 2026-11-02). The pinned tags were
 * computed outside this code, with Python's `hmac` over the layout the KDoc states, so they check the layout and not
 * just that [BlindTag.tag] and [BlindTag.Expected] agree. Every random draw comes from a seeded [SeededRandom], so a
 * run is the same on any runner; nothing reads a clock.
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
    fun theConstantsAreTheAdrs() {
        assertEquals(NodeId.BYTES, BlindTag.BYTES)
        assertEquals(32, BlindTag.KEY_BYTES)
        assertEquals(BlindTag.KEY_BYTES, BlindTag.newKey(SeededRandom(1)).size)
        assertEquals("knit/advert/v1/slot", BlindTag.LABEL)
        assertEquals(15 * 60 * 1000L, BlindTag.SLOT_MS)
        assertEquals(4, BlindTag.RESTARTS)
    }

    @Test
    fun aFixedKeySlotAndRestartGiveThePinnedBytes() {
        // Python: m = hmac.new(key, b"knit/advert/v1/slot" + struct.pack(">q", slot) + bytes([restart]), sha256)
        //         .digest(); tag = bytes([0xCE, 0x73, 0x90 | (m[0] >> 4)]) + m[1:14]
        assertArrayEquals(hex("ce739c6b1fd53af7ea51124550ff2d2d"), BlindTag.tag(key, 1_987_654L, 0))
        assertArrayEquals(hex("ce7399123ab2077518cfcedd51dac4e7"), BlindTag.tag(key, 1_987_654L, 1))
        // A negative slot is two's complement on the wire, and the last restart index.
        assertArrayEquals(
            hex("ce73971414c80cdddd555fe3ae5a606d"),
            BlindTag.tag(ByteArray(BlindTag.KEY_BYTES) { 0xA5.toByte() }, -1L, 3),
        )
    }

    @Test
    fun slotsRestartsAndKeysEachGiveADifferentTag() {
        val tags =
            (100L..140L).flatMap { slot ->
                (0 until BlindTag.RESTARTS).flatMap { restart ->
                    listOf(BlindTag.tag(key, slot, restart).toList(), BlindTag.tag(otherKey, slot, restart).toList())
                }
            }
        assertEquals(tags.size, tags.toSet().size)
    }

    @Test
    fun aTagIsDeterministic() {
        assertArrayEquals(BlindTag.tag(key, 42L, 2), BlindTag.tag(key.copyOf(), 42L, 2))
    }

    @Test
    fun everyTagCarriesThePrefixStartsZzzzAndRoundTrips() {
        val random = SeededRandom(4)
        val tags =
            List(256) { BlindTag.tag(BlindTag.newKey(random), random.nextLong(), random.nextInt(BlindTag.RESTARTS)) } +
                BlindTag.tag(key, Long.MIN_VALUE, 0) + BlindTag.tag(key, Long.MAX_VALUE, 3) + BlindTag.tag(key, 0L, 0)
        for (tag in tags) {
            assertEquals(BlindTag.BYTES, tag.size)
            assertTrue(BlindTag.hasPrefix(tag))
            val id = NodeId.fromBytes(tag)
            assertTrue(id, id.startsWith("zzzz"))
            assertArrayEquals(id, tag, NodeId.toBytesOrNull(id)) // null when the id is not canonical
        }
    }

    @Test
    fun hasPrefixReadsOnlyTheTwentyFixedBits() {
        val tag = BlindTag.tag(key, 7L, 0)
        for (bit in 0 until BlindTag.BYTES * 8) {
            val flipped = tag.copyOf()
            flipped[bit / 8] = (flipped[bit / 8].toInt() xor (0x80 ushr (bit % 8))).toByte()
            assertEquals("bit $bit", bit >= 20, BlindTag.hasPrefix(flipped))
        }
        for (bad in listOf(ByteArray(0), tag.copyOf(15), tag.copyOf(17), ByteArray(BlindTag.BYTES))) {
            assertFalse(BlindTag.hasPrefix(bad))
        }
    }

    @Test
    fun tagRefusesABadKeyOrRestart() {
        val keys = listOf(ByteArray(0), ByteArray(31), ByteArray(33))
        for (bad in keys) {
            assertTrue("key ${bad.size}", runCatching { BlindTag.tag(bad, 1L, 0) }.exceptionOrNull() is IllegalArgumentException)
        }
        for (restart in listOf(-1, BlindTag.RESTARTS, Int.MIN_VALUE, Int.MAX_VALUE)) {
            val thrown = runCatching { BlindTag.tag(key, 1L, restart) }.exceptionOrNull()
            assertTrue("restart $restart", thrown is IllegalArgumentException)
        }
    }

    @Test
    fun expectedIsTotal() {
        // key + one trailing 0x00 is the same HMAC key as key (RFC 2104 zero-pads short keys), so only the size guard
        // can refuse it: it is the case that pins "a key of the wrong size lists nothing".
        val keys = listOf(ByteArray(0), ByteArray(31), ByteArray(33), key.copyOf(BlindTag.KEY_BYTES + 1))
        for (bad in keys) {
            val expected = BlindTag.Expected(bad)
            assertEquals(0, expected.tags(0L..3L).count())
            assertNull(expected.tag(0L, 0))
        }
        val good = BlindTag.Expected(key)
        for (restart in listOf(-1, BlindTag.RESTARTS, Int.MIN_VALUE, Int.MAX_VALUE)) assertNull(good.tag(1L, restart))
        assertEquals(0, good.tags(LongRange.EMPTY).count())
    }

    @Test
    fun expectedAgreesWithTagSlotMajor() {
        val expected = BlindTag.Expected(key)
        val listed = expected.tags(-3L..5L).toList()
        assertEquals(9 * BlindTag.RESTARTS, listed.size)
        var i = 0
        for (slot in -3L..5L) {
            for (restart in 0 until BlindTag.RESTARTS) {
                val entry = listed[i++]
                assertEquals(slot, entry.slot)
                assertEquals(restart, entry.restart)
                assertArrayEquals(BlindTag.tag(key, slot, restart), entry.tag)
                assertArrayEquals(entry.tag, expected.tag(slot, restart))
            }
        }
        assertArrayEquals(hex("ce739c6b1fd53af7ea51124550ff2d2d"), expected.tag(1_987_654L, 0))
    }

    @Test
    fun interleavedSequencesOfOneBuilderStayCorrect() {
        val expected = BlindTag.Expected(otherKey)
        val a = expected.tags(0L..9L).iterator()
        val b = expected.tags(100L..109L).iterator()
        while (a.hasNext()) {
            val x = a.next()
            val y = b.next()
            assertArrayEquals(BlindTag.tag(otherKey, x.slot, x.restart), x.tag)
            assertArrayEquals(BlindTag.tag(otherKey, y.slot, y.restart), y.tag)
        }
    }

    @Test
    fun slotOfFloorsAtBoundariesAndBelowZero() {
        val slot = BlindTag.SLOT_MS
        assertEquals(0L, BlindTag.slotOf(0L))
        assertEquals(0L, BlindTag.slotOf(slot - 1))
        assertEquals(1L, BlindTag.slotOf(slot))
        assertEquals(1L, BlindTag.slotOf(2 * slot - 1))
        assertEquals(-1L, BlindTag.slotOf(-1L))
        assertEquals(-1L, BlindTag.slotOf(-slot))
        assertEquals(-2L, BlindTag.slotOf(-slot - 1))
        // 2026-11-02T00:00:00Z
        assertEquals(1_793_577_600_000L / slot, BlindTag.slotOf(1_793_577_600_000L))
        assertEquals(Math.floorDiv(Long.MIN_VALUE, slot), BlindTag.slotOf(Long.MIN_VALUE))
    }

    @Test
    fun theWindowIsTheLastEightHoursAndTheNextHalfHour() {
        val now = 1_793_577_600_000L + 123_456L
        val window = BlindTag.window(now)
        val current = BlindTag.slotOf(now)
        assertEquals(current - 32, window.first)
        assertEquals(current + 2, window.last)
        assertEquals(35, window.count())
    }
}
