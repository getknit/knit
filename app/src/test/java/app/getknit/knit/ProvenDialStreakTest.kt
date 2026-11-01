package app.getknit.knit

import app.getknit.knit.mesh.bluetooth.ProvenDialStreak
import app.getknit.knit.mesh.bluetooth.ProvenDialStreak.Companion.CONFIRM_BY_AGE_MS
import app.getknit.knit.mesh.bluetooth.ProvenDialStreak.End
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When a dialed proven link resets its peer's redial streak (ADR 2026-11.264w, the bluetooth-reviewer's design pass). */
class ProvenDialStreakTest {
    @Test
    fun aLinkThatSettledAtRegistrationEndsAsAFlap() {
        // Accepted links and dialed legacy links: the streak reset at registration, as before.
        val streak = ProvenDialStreak(pending = false)
        assertFalse(streak.pending)
        assertFalse(streak.confirm())
        assertEquals(End.FLAP, streak.end(lifetimeMs = 100))
    }

    @Test
    fun theRespondersFirstRecordResetsTheStreakOnce() {
        val streak = ProvenDialStreak(pending = true)
        assertTrue(streak.pending)
        assertTrue(streak.confirm())
        assertFalse(streak.confirm())
        assertFalse(streak.pending)
        assertEquals(End.FLAP, streak.end(lifetimeMs = 100))
    }

    @Test
    fun anEndBeforeAnyRecordIsAFailedHandshakeOnTheSavedStreak() {
        // The responder refused our PROOF and closed: no reset, and one failed dial, never a reset plus a flap.
        val streak = ProvenDialStreak(pending = true)
        assertEquals(End.HANDSHAKE, streak.end(lifetimeMs = CONFIRM_BY_AGE_MS - 1))
        // A record read after the end (the other loop reported first) resets nothing.
        assertFalse(streak.confirm())
        assertEquals(End.FLAP, streak.end(lifetimeMs = CONFIRM_BY_AGE_MS - 1))
    }

    @Test
    fun aLongLivedLinkWithNoRecordWasKeptAndEndsAsToday() {
        // Past the responder's 5 s PROOF wait and its close, it kept the link: reset, then the flap.
        val streak = ProvenDialStreak(pending = true)
        assertEquals(End.RESET_THEN_FLAP, streak.end(lifetimeMs = CONFIRM_BY_AGE_MS))
    }
}
