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
 * [BlindTag]'s slotted, owner-bound construction on plain JVM (ADR 2026-11.xp6d, revised 2026-11-02). The pinned tags were
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
    private val owner = ByteArray(NodeId.BYTES) { (it + 0x20).toByte() }
    private val otherOwner = ByteArray(NodeId.BYTES) { (it + 0x30).toByte() }

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
        // Python: m = hmac.new(key, b"knit/advert/v1/slot" + owner + struct.pack(">q", slot) + bytes([restart]), sha256)
        //         .digest(); tag = bytes([0xCE, 0x73, 0x90 | (m[0] >> 4)]) + m[1:14]
        // owner = bytes(range(0x20, 0x30)); otherOwner = bytes(range(0x30, 0x40)).
        assertArrayEquals(hex("ce7393899a7ccecda1f704358f78550f"), BlindTag.tag(key, owner, 1_987_654L, 0))
        assertArrayEquals(hex("ce739f046d5df7d91e32b941aca8f5b6"), BlindTag.tag(key, owner, 1_987_654L, 1))
        assertArrayEquals(hex("ce7397818b0f13e6908d532e993729c4"), BlindTag.tag(key, otherOwner, 1_987_654L, 0))
        // A negative slot is two's complement on the wire, and the last restart index.
        assertArrayEquals(
            hex("ce739ebc030e3bb566907fe9d843dafc"),
            BlindTag.tag(ByteArray(BlindTag.KEY_BYTES) { 0xA5.toByte() }, owner, -1L, 3),
        )
    }

    @Test
    fun oneKeyUnderTwoOwnersGivesDifferentTags() {
        // A contact re-sending another's key as its own: the two lists share no tag, in the builder as in [BlindTag.tag].
        val slots = 100L..140L
        val mine =
            BlindTag
                .Expected(key, owner)
                .tags(slots)
                .map { it.tag.toList() }
                .toSet()
        val theirs =
            BlindTag
                .Expected(key, otherOwner)
                .tags(slots)
                .map { it.tag.toList() }
                .toSet()
        assertEquals(slots.count() * BlindTag.RESTARTS, mine.size)
        assertEquals(slots.count() * BlindTag.RESTARTS, theirs.size)
        assertTrue(mine.intersect(theirs).isEmpty())
        assertFalse(BlindTag.tag(key, owner, 7L, 0).contentEquals(BlindTag.tag(key, otherOwner, 7L, 0)))
    }

    @Test
    fun slotsRestartsAndKeysEachGiveADifferentTag() {
        val tags =
            (100L..140L).flatMap { slot ->
                (0 until BlindTag.RESTARTS).flatMap { restart ->
                    listOf(BlindTag.tag(key, owner, slot, restart).toList(), BlindTag.tag(otherKey, owner, slot, restart).toList())
                }
            }
        assertEquals(tags.size, tags.toSet().size)
    }

    @Test
    fun aTagIsDeterministic() {
        assertArrayEquals(BlindTag.tag(key, owner, 42L, 2), BlindTag.tag(key.copyOf(), owner, 42L, 2))
    }

    @Test
    fun everyTagCarriesThePrefixStartsZzzzAndRoundTrips() {
        val random = SeededRandom(4)
        val tags =
            List(256) { BlindTag.tag(BlindTag.newKey(random), owner, random.nextLong(), random.nextInt(BlindTag.RESTARTS)) } +
                BlindTag.tag(key, owner, Long.MIN_VALUE, 0) + BlindTag.tag(key, owner, Long.MAX_VALUE, 3) + BlindTag.tag(key, owner, 0L, 0)
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
        val tag = BlindTag.tag(key, owner, 7L, 0)
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
    fun tagRefusesABadKeyOwnerOrRestart() {
        val keys = listOf(ByteArray(0), ByteArray(31), ByteArray(33))
        for (bad in keys) {
            assertTrue("key ${bad.size}", runCatching { BlindTag.tag(bad, owner, 1L, 0) }.exceptionOrNull() is IllegalArgumentException)
        }
        for (bad in listOf(ByteArray(0), ByteArray(NodeId.BYTES - 1), ByteArray(NodeId.BYTES + 1))) {
            assertTrue("owner ${bad.size}", runCatching { BlindTag.tag(key, bad, 1L, 0) }.exceptionOrNull() is IllegalArgumentException)
        }
        for (restart in listOf(-1, BlindTag.RESTARTS, Int.MIN_VALUE, Int.MAX_VALUE)) {
            val thrown = runCatching { BlindTag.tag(key, owner, 1L, restart) }.exceptionOrNull()
            assertTrue("restart $restart", thrown is IllegalArgumentException)
        }
    }

    @Test
    fun expectedIsTotal() {
        // key + one trailing 0x00 is the same HMAC key as key (RFC 2104 zero-pads short keys), so only the size guard
        // can refuse it: it is the case that pins "a key of the wrong size lists nothing".
        val keys = listOf(ByteArray(0), ByteArray(31), ByteArray(33), key.copyOf(BlindTag.KEY_BYTES + 1))
        for (bad in keys) {
            val expected = BlindTag.Expected(bad, owner)
            assertEquals(0, expected.tags(0L..3L).count())
            assertNull(expected.tag(0L, 0))
        }
        // owner + one trailing 0x00 is the case a truncating read would let through.
        for (bad in listOf(null, ByteArray(0), owner.copyOf(NodeId.BYTES - 1), owner.copyOf(NodeId.BYTES + 1))) {
            val expected = BlindTag.Expected(key, bad)
            assertEquals(0, expected.tags(0L..3L).count())
            assertNull(expected.tag(0L, 0))
        }
        val good = BlindTag.Expected(key, owner)
        for (restart in listOf(-1, BlindTag.RESTARTS, Int.MIN_VALUE, Int.MAX_VALUE)) assertNull(good.tag(1L, restart))
        assertEquals(0, good.tags(LongRange.EMPTY).count())
    }

    @Test
    fun expectedAgreesWithTagSlotMajor() {
        val expected = BlindTag.Expected(key, owner)
        val listed = expected.tags(-3L..5L).toList()
        assertEquals(9 * BlindTag.RESTARTS, listed.size)
        var i = 0
        for (slot in -3L..5L) {
            for (restart in 0 until BlindTag.RESTARTS) {
                val entry = listed[i++]
                assertEquals(slot, entry.slot)
                assertEquals(restart, entry.restart)
                assertArrayEquals(BlindTag.tag(key, owner, slot, restart), entry.tag)
                assertArrayEquals(entry.tag, expected.tag(slot, restart))
            }
        }
        assertArrayEquals(hex("ce7393899a7ccecda1f704358f78550f"), expected.tag(1_987_654L, 0))
    }

    @Test
    fun interleavedSequencesOfOneBuilderStayCorrect() {
        val expected = BlindTag.Expected(otherKey, owner)
        val a = expected.tags(0L..9L).iterator()
        val b = expected.tags(100L..109L).iterator()
        while (a.hasNext()) {
            val x = a.next()
            val y = b.next()
            assertArrayEquals(BlindTag.tag(otherKey, owner, x.slot, x.restart), x.tag)
            assertArrayEquals(BlindTag.tag(otherKey, owner, y.slot, y.restart), y.tag)
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
