package app.getknit.knit.mesh.inet

import app.getknit.knit.mesh.inet.LinkCandidate.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The punch's targets, schedule and backoff (ADR 2026-10.a5yj). */
class PunchPolicyTest {
    @Test
    fun ipv6GoesFirstThenTheLanThenTheReflexiveAddress() {
        val remote = listOf(reflexive, host, v6, v6temp)
        val targets = PunchPolicy.targets(remote, localV6 = true, localV4 = true)
        assertEquals(listOf(Kind.V6_TEMPORARY, Kind.V6, Kind.HOST, Kind.V4_REFLEXIVE), targets.map { it.kind })
    }

    @Test
    fun aFamilyThisPhoneHoldsNoAddressInIsNeverSentTo() {
        val remote = listOf(reflexive, host, v6, v6temp)
        assertEquals(listOf(Kind.HOST, Kind.V4_REFLEXIVE), PunchPolicy.targets(remote, localV6 = false, localV4 = true).map { it.kind })
        assertEquals(listOf(Kind.V6_TEMPORARY, Kind.V6), PunchPolicy.targets(remote, localV6 = true, localV4 = false).map { it.kind })
    }

    @Test
    fun anAddressOutsideItsKindsSpaceIsRefused() {
        // A "host" that is public, a "reflexive" that is private, a v6 that is link-local or a ULA, a loopback.
        assertFalse(PunchPolicy.sane(LinkCandidate(v4(8, 8, 8, 8), 4000, Kind.HOST)))
        assertFalse(PunchPolicy.sane(LinkCandidate(v4(192, 168, 1, 5), 4000, Kind.V4_REFLEXIVE)))
        assertFalse(PunchPolicy.sane(LinkCandidate(v4(127, 0, 0, 1), 4000, Kind.V4_REFLEXIVE)))
        assertFalse(PunchPolicy.sane(LinkCandidate(v4(169, 254, 3, 4), 4000, Kind.V4_REFLEXIVE)))
        assertFalse(PunchPolicy.sane(LinkCandidate(v4(239, 1, 2, 3), 4000, Kind.V4_REFLEXIVE)))
        assertFalse(PunchPolicy.sane(LinkCandidate(v6(0xfe, 0x80), 4000, Kind.V6)))
        assertFalse(PunchPolicy.sane(LinkCandidate(v6(0xfd, 0x00), 4000, Kind.V6)))
        assertFalse(PunchPolicy.sane(LinkCandidate(v6(0xff, 0x02), 4000, Kind.V6)))
        // The wrong length for its kind, and a port outside 1..65535.
        assertFalse(PunchPolicy.sane(LinkCandidate(v4(8, 8, 8, 8), 4000, Kind.V6)))
        assertFalse(PunchPolicy.sane(LinkCandidate(v4(8, 8, 8, 8), 0, Kind.V4_REFLEXIVE)))
        assertFalse(PunchPolicy.sane(LinkCandidate(v4(8, 8, 8, 8), 70_000, Kind.V4_REFLEXIVE)))
        // CGNAT space is a host address, never a reflexive one.
        assertTrue(PunchPolicy.sane(LinkCandidate(v4(100, 64, 0, 9), 4000, Kind.HOST)))
        assertFalse(PunchPolicy.sane(LinkCandidate(v4(100, 64, 0, 9), 4000, Kind.V4_REFLEXIVE)))
        listOf(v6temp, v6, host, reflexive).forEach { assertTrue(PunchPolicy.sane(it)) }
    }

    @Test
    fun duplicatesFoldAndAPeerCannotNameMoreThanTheCap() {
        val many = (1..20).map { LinkCandidate(v4(8, 8, 4, it), 4000 + it, Kind.V4_REFLEXIVE) }
        assertEquals(PunchPolicy.MAX_TARGETS, PunchPolicy.targets(many + many, localV6 = true, localV4 = true).size)
        assertEquals(1, PunchPolicy.targets(listOf(host, host, host), localV6 = true, localV4 = true).size)
    }

    @Test
    fun probesAreQuickWhileBothEndsStartThenSlowerAndStopAtTenSeconds() {
        assertEquals(200L, PunchPolicy.probeIntervalMs(0))
        assertEquals(500L, PunchPolicy.probeIntervalMs(2_000))
        assertFalse(PunchPolicy.givesUp(9_999))
        assertTrue(PunchPolicy.givesUp(PunchPolicy.GIVE_UP_MS))
    }

    @Test
    fun backoffDoublesFromAMinuteAndStopsAtSixHours() {
        assertEquals(0L, PunchPolicy.backoffMs(0))
        assertEquals(60_000L, PunchPolicy.backoffMs(1))
        assertEquals(120_000L, PunchPolicy.backoffMs(2))
        assertEquals(PunchPolicy.MAX_BACKOFF_MS, PunchPolicy.backoffMs(10))
        assertEquals(PunchPolicy.MAX_BACKOFF_MS, PunchPolicy.backoffMs(Int.MAX_VALUE))
    }

    private companion object {
        val v6temp = LinkCandidate(v6(0x24, 0x01), 40_001, Kind.V6_TEMPORARY)
        val v6 = LinkCandidate(v6(0x2a, 0x02), 40_002, Kind.V6)
        val host = LinkCandidate(v4(192, 168, 1, 20), 40_003, Kind.HOST)
        val reflexive = LinkCandidate(v4(81, 2, 3, 4), 61_000, Kind.V4_REFLEXIVE)

        fun v4(
            a: Int,
            b: Int,
            c: Int,
            d: Int,
        ): ByteArray = byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte())

        fun v6(
            a: Int,
            b: Int,
        ): ByteArray =
            ByteArray(16).also {
                it[0] = a.toByte()
                it[1] = b.toByte()
                it[15] = 1
            }
    }
}
