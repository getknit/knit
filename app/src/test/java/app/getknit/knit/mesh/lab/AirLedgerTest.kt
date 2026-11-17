package app.getknit.knit.mesh.lab

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [AirLedger]'s arithmetic on a clock the test hands it: each link and each radio a queue of its own at its own rate,
 * and a record through once every one it uses has served it. Plain JVM: no lab, no wait.
 */
class AirLedgerTest {
    /**
     * Alice's radio is booked a second ahead by a long record to Carol. Bob's short one to Carol waits for Carol, as it
     * must; his next, to Dan, whose radio is idle like his, airs at Bob's own pace, not once Carol is free.
     */
    @Test
    fun aRadioIsNotHeldForANeighboursBusyOne() {
        val alice = NodeAir(RADIO_BPS)
        val bob = NodeAir(RADIO_BPS)
        val carol = NodeAir(RADIO_BPS)
        val dan = NodeAir(RADIO_BPS)

        val longToCarol = AirLedger.reserve(LinkClock(), FAST_LINK_BPS, alice, carol, RADIO_BPS, nowNanos = 0)
        val bobToCarol = AirLedger.reserve(LinkClock(), FAST_LINK_BPS, bob, carol, SHORT_BYTES, nowNanos = 0)
        val bobToDan = AirLedger.reserve(LinkClock(), FAST_LINK_BPS, bob, dan, SHORT_BYTES, nowNanos = 0)

        assertEquals(SECOND, longToCarol)
        assertEquals("Carol's radio serves Bob after Alice", SECOND + TENTH, bobToCarol)
        assertEquals("Bob's radio serves his two records in turn, Dan's at once", 2 * TENTH, bobToDan)
    }

    /** One radio bounds the sum of its links, in and out: Carol's takes a record from Alice and one to Bob in turn. */
    @Test
    fun aRadioServesEveryLinkItHasInAndOutInTurn() {
        val alice = NodeAir(RADIO_BPS)
        val bob = NodeAir(RADIO_BPS)
        val carol = NodeAir(RADIO_BPS)

        val fromAlice = AirLedger.reserve(LinkClock(), FAST_LINK_BPS, alice, carol, RADIO_BPS, nowNanos = 0)
        val toBob = AirLedger.reserve(LinkClock(), FAST_LINK_BPS, carol, bob, RADIO_BPS, nowNanos = 0)

        assertEquals(SECOND, fromAlice)
        assertEquals(2 * SECOND, toBob)
    }

    /**
     * A link with no radio behind it airs at its own rate, back to back while its queue is full, and a record handed to
     * it once it has gone idle starts when it is handed over, never in the idle time before.
     */
    @Test
    fun aLinkAirsAtItsOwnRateAndIdleTimeIsNotBanked() {
        val link = LinkClock()

        val first = AirLedger.reserve(link, SLOW_LINK_BPS, null, null, SLOW_LINK_BPS, nowNanos = 0)
        val second = AirLedger.reserve(link, SLOW_LINK_BPS, null, null, SLOW_LINK_BPS, nowNanos = 0)
        val afterIdle = AirLedger.reserve(link, SLOW_LINK_BPS, null, null, SLOW_LINK_BPS, nowNanos = 5 * SECOND)

        assertEquals(SECOND, first)
        assertEquals(2 * SECOND, second)
        assertEquals(6 * SECOND, afterIdle)
        assertEquals(3 * SECOND, link.reservedNanos.get())
    }

    private companion object {
        const val SECOND = 1_000_000_000L
        const val TENTH = SECOND / 10
        const val RADIO_BPS = 1_000L
        const val SHORT_BYTES = RADIO_BPS / 10

        // So fast its own airtime vanishes beside the radios': a nanosecond a byte.
        const val FAST_LINK_BPS = 1_000_000_000L
        const val SLOW_LINK_BPS = 100L
    }
}
