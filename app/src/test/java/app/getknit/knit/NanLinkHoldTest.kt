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

    @Test
    fun aLiveCustodyAnswerHoldsTheLinkOnlyWhileItDrainsAndWithinTheSyncWindow() {
        val window = 30_000L
        val span = 5_000L // the initiator's own quiescence, QUIESCENCE_MS
        // Rounds are cut as the link drains: an answer whose link drained a moment ago is between rounds...
        assertTrue(NanLinkLife.answerHeld(answerLive = true, sinceDrainMs = 0, spanMs = span, ageMs = 1_000, windowMs = window))
        assertTrue(NanLinkLife.answerHeld(answerLive = true, sinceDrainMs = 4_999, spanMs = span, ageMs = 29_999, windowMs = window))
        // ...so the initiator's contended linger (2 s) doesn't cut it there.
        assertFalse(
            "a draining answer outlasts the contended linger",
            NanLinkLife.done(
                idleMs = 3_000,
                ageMs = 10_000,
                quiescenceMs = 2_000,
                windowMs = window,
                held = NanLinkLife.answerHeld(answerLive = true, sinceDrainMs = 3_000, spanMs = span, ageMs = 10_000, windowMs = window),
            ),
        )
        // A link that stopped draining (a peer reading slowly or not at all) gets no hold: its answer sends it nothing more.
        assertFalse(NanLinkLife.answerHeld(answerLive = true, sinceDrainMs = span, spanMs = span, ageMs = 10_000, windowMs = window))
        assertTrue(
            "a link that stopped draining closes at its quiescence, answer or not",
            NanLinkLife.done(
                idleMs = 9_000,
                ageMs = 20_000,
                quiescenceMs = 8_000,
                windowMs = 45_000,
                held = NanLinkLife.answerHeld(answerLive = true, sinceDrainMs = 9_000, spanMs = 8_000, ageMs = 20_000, windowMs = window),
            ),
        )
        // A fresh path whose first round isn't cut yet (answerCarrier names Aware from the digest's landing): held up to one
        // span, so the contended linger doesn't close it before its answer starts...
        assertTrue(NanLinkLife.answerHeld(answerLive = true, sinceDrainMs = null, spanMs = span, ageMs = 1_000, windowMs = window))
        assertFalse(
            "a fresh path the contended linger would close is held",
            NanLinkLife.done(
                idleMs = 2_500,
                ageMs = 2_500,
                quiescenceMs = 2_000,
                windowMs = window,
                held = NanLinkLife.answerHeld(answerLive = true, sinceDrainMs = null, spanMs = span, ageMs = 2_500, windowMs = window),
            ),
        )
        // ...and no longer: a path that never drained in a span is not held.
        assertFalse(
            "never drained within a span, not held",
            NanLinkLife.answerHeld(answerLive = true, sinceDrainMs = null, spanMs = span, ageMs = span, windowMs = window),
        )
        // No longer than a sync may last, the responder's longer backstop included, and never without a live answer.
        assertFalse(NanLinkLife.answerHeld(answerLive = true, sinceDrainMs = 0, spanMs = span, ageMs = window, windowMs = window))
        assertFalse(NanLinkLife.answerHeld(answerLive = false, sinceDrainMs = 0, spanMs = span, ageMs = 0, windowMs = window))
    }
}
