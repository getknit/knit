package app.getknit.knit

import app.getknit.knit.mesh.wifiaware.NanLinkHolds
import app.getknit.knit.mesh.wifiaware.NanLinkLife
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A live call's hold on its Wi-Fi Aware link (docs/VOICE_CALLS.md §3.11): how long it lasts, and what it overrides. */
class NanLinkHoldTest {
    private var now = 1_000L

    @Test
    fun aHoldLastsItsTtlAndANewAskSlidesIt() {
        val holds = NanLinkHolds(clock = { now }, ttlMs = 25_000)
        assertFalse(holds.isHeld("p"))
        holds.note("p")
        now += 24_999
        assertTrue(holds.isHeld("p"))
        // The call's next ask, ten seconds apart, keeps it.
        holds.note("p")
        now += 20_000
        assertTrue(holds.isHeld("p"))
        // No ask since: it lapses on its own.
        now += 5_000
        assertFalse(holds.isHeld("p"))
    }

    @Test
    fun anyHeldIsTrueWhileOneHoldIsLive() {
        // Wi-Fi Aware dormancy's reason to stay attached (NanDormancyPolicy): a call wants its link.
        val holds = NanLinkHolds(clock = { now }, ttlMs = 25_000)
        assertFalse(holds.anyHeld())
        holds.note("p")
        assertTrue(holds.anyHeld())
        now += 25_000
        assertFalse("a lapsed hold holds nothing", holds.anyHeld())
        holds.note("q")
        holds.clearAll()
        assertFalse(holds.anyHeld())
    }

    @Test
    fun holdsAreCappedOldestFirstAndClearedWithTheTransport() {
        val holds = NanLinkHolds(clock = { now }, ttlMs = 25_000, maxEntries = 2)
        holds.note("a")
        now += 1
        holds.note("b")
        now += 1
        holds.note("c")
        assertFalse(holds.isHeld("a"))
        assertTrue(holds.isHeld("b") && holds.isHeld("c"))
        holds.clearAll()
        assertFalse(holds.isHeld("c"))
    }

    @Test
    fun aHeldLinkOutlastsItsIdleTeardownButNotItsWindow() {
        // Unheld: idle past quiescence ends it, as every sync link ends.
        assertTrue(NanLinkLife.done(idleMs = 5_000, ageMs = 6_000, quiescenceMs = 5_000, windowMs = 30_000, held = false))
        assertFalse(NanLinkLife.done(idleMs = 4_999, ageMs = 6_000, quiescenceMs = 5_000, windowMs = 30_000, held = false))
        // Held: a quiet minute between bursts is not the end of it...
        assertFalse(NanLinkLife.done(idleMs = 20_000, ageMs = 25_000, quiescenceMs = 5_000, windowMs = 30_000, held = true))
        // ...but its window is, so a dead peer on the far side costs at most that long.
        assertTrue(NanLinkLife.done(idleMs = 0, ageMs = 30_000, quiescenceMs = 5_000, windowMs = 30_000, held = true))
    }
}
