package app.getknit.knit

import app.getknit.knit.mesh.bluetooth.AdvertRotation
import app.getknit.knit.mesh.bluetooth.LinkFormNotes
import app.getknit.knit.mesh.bluetooth.LinkSilence
import app.getknit.knit.mesh.bluetooth.TagNextSends
import app.getknit.knit.mesh.bluetooth.TagNextSends.Kind
import app.getknit.knit.mesh.bluetooth.TagNextSends.Outcome
import app.getknit.knit.mesh.crypto.BlindTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TagNextSends] (ADR 2026-11.xp6d's writer, step 5b's W5): the predicate table (proven, tag-aware, heard by the
 * session's predicate, an own key held), the receiver's per-link limit for a post-rotation send and a resend, a resend
 * kept out of the window before a due announcement, an announcement that never moves for a resend, and when a received
 * `TAG_NEXT` becomes the link's form.
 */
class TagNextSendsTest {
    private val minInterval = TagNextSends.MIN_INTERVAL_MS
    private val sends = TagNextSends<String>(minInterval)
    private val t0 = 1_800_000_000_000L

    @Suppress("LongParameterList") // the send's facts, named per case
    private fun send(
        kind: Kind,
        at: Long,
        link: String = "x",
        eligible: Boolean = true,
        hasKey: Boolean = true,
        announceDueAt: Long? = null,
        announcing: Boolean = false,
    ) = sends.decide(link, kind, eligible, hasKey, at, announceDueAt, announcing)

    @Test
    fun thePredicateTable() {
        assertTrue(TagNextSends.eligible(proven = true, readsTags = true, hears = true))
        assertFalse("unproven", TagNextSends.eligible(proven = false, readsTags = true, hears = true))
        assertFalse("no CAP_BLIND_ADVERT", TagNextSends.eligible(proven = true, readsTags = false, hears = true))
        assertFalse("blocked or departed", TagNextSends.eligible(proven = true, readsTags = true, hears = false))
        for (kind in Kind.entries) {
            assertEquals("a random tag is never announced", Outcome.NO_KEY, send(kind, t0, hasKey = false, eligible = true))
            assertEquals(Outcome.INELIGIBLE, send(kind, t0, eligible = false))
        }
    }

    @Test
    fun aResendKeepsTheReceiversLimitAndTheAnnouncementNeverMoves() {
        assertEquals(Outcome.SENT, send(Kind.RESEND, t0))
        assertEquals("inside the receiver's limit: it would be dropped", Outcome.THROTTLED, send(Kind.RESEND, t0 + minInterval - 1))
        assertEquals(Outcome.SENT, send(Kind.RESEND, t0 + minInterval))
        // An announcement due 90 s on: a resend now would be the one the receiver keeps, and the announcement dropped.
        val due = t0 + 3 * minInterval + 90_000
        assertEquals(Outcome.BEFORE_ANNOUNCE, send(Kind.RESEND, due - 90_000, announceDueAt = due))
        assertEquals("the announcement goes as due, whatever was resent", Outcome.SENT, send(Kind.ANNOUNCE, due))
        // A post-rotation send keeps the limit too, but not the announcement's window (the next one is a slot away).
        assertEquals(Outcome.THROTTLED, send(Kind.CURRENT, due + 1_000))
        assertEquals(Outcome.SENT, send(Kind.CURRENT, due + minInterval))
    }

    @Test
    fun eachLinkHasItsOwnLimitAndAnEndedLinkForgetsIt() {
        assertEquals(Outcome.SENT, send(Kind.CURRENT, t0, link = "a"))
        assertEquals(Outcome.SENT, send(Kind.CURRENT, t0, link = "b"))
        sends.forget("a")
        assertEquals(Outcome.SENT, send(Kind.CURRENT, t0 + 1, link = "a"))
        sends.clear()
        assertEquals(Outcome.SENT, send(Kind.RESEND, t0 + 2, link = "b"))
    }

    @Test
    fun anAnnouncementAppliesAtItsEdgeAndAResendAtOnce() {
        val slotStart = BlindTag.slotOf(t0) * BlindTag.SLOT_MS
        val edge = slotStart + BlindTag.SLOT_MS
        val announcedAt = edge - AdvertRotation.LEAD_MS
        assertEquals(edge, TagNextSends.appliesAt(announcedAt))
        assertEquals("a late announcement too", edge, TagNextSends.appliesAt(edge - 5_000))
        val midSlot = slotStart + 60_000
        assertEquals("a resend names the form on the air now", midSlot, TagNextSends.appliesAt(midSlot))
    }

    @Test
    fun nothingButTheAnnouncementGoesWhileItStandsOrJustBeforeIt() {
        // The bluetooth review: the receiver holds the announced tag pending, and another form would replace it there.
        assertEquals(Outcome.SENT, send(Kind.ANNOUNCE, t0, announcing = true))
        assertEquals(Outcome.BEFORE_ANNOUNCE, send(Kind.RESEND, t0 + 5 * minInterval, link = "y", announcing = true))
        assertEquals(Outcome.BEFORE_ANNOUNCE, send(Kind.CURRENT, t0 + 5 * minInterval, link = "y", announcing = true))
        // A post-rotation send inside the receiver's limit before a due announcement is refused too.
        val due = t0 + 10 * minInterval
        assertEquals(Outcome.BEFORE_ANNOUNCE, send(Kind.CURRENT, due - minInterval + 1, link = "z", announceDueAt = due))
        assertEquals(Outcome.SENT, send(Kind.CURRENT, due - minInterval - 1, link = "z", announceDueAt = due))
    }

    @Test
    fun aThousandRecordsInOneSecondLeaveOneNoteThatAppliesOnce() {
        // The security review's flood: one note (and so one wait) per link, the newest record's.
        val notes = LinkFormNotes<String>()
        val slotStart = BlindTag.slotOf(t0) * BlindTag.SLOT_MS
        val edge = slotStart + BlindTag.SLOT_MS
        val start = edge - 60_000 // inside the announcement window
        var lastAt: Long? = null
        repeat(1_000) { i -> lastAt = notes.offer("fork", "tag$i", start + i) }
        assertEquals(1, notes.size)
        assertEquals(edge, lastAt)
        assertFalse("not before its edge", notes.due("fork", "tag999", edge - 1))
        assertFalse("a replaced record never applies", notes.due("fork", "tag0", edge))
        assertTrue(notes.due("fork", "tag999", edge))
        assertFalse("taken once", notes.due("fork", "tag999", edge))
        assertEquals(0, notes.size)
        // A record that applies at once drops the note, and a later send replaces an older one.
        assertEquals(edge, notes.offer("fork", "a", start))
        assertEquals(null, notes.offer("fork", "b", slotStart + 1_000))
        assertEquals(0, notes.size)
    }

    @Test
    fun forgedDialsOnceASecondForTenMinutesBuyFiveResendsAndOneProbePerAnswerWait() {
        // The security review's trial on the JVM: the held link's peer is alive and answers each probe at once.
        val silence = LinkSilence(exempt = false, uptime = { 0L })
        silence.onKeepaliveIn() // the peer answers keepalives
        var rxAt = 0L
        var resent = 0
        var probes = 0
        for (sec in 0 until 600) {
            val now = 1_000L + sec * 1_000L
            if (send(Kind.RESEND, t0 + sec * 1_000L, link = "held") == Outcome.SENT) resent++
            if (silence.onHeldDial(now)) {
                val action = silence.step(now, rxAt, null, null)
                assertTrue(action is LinkSilence.Action.Probe)
                probes++
                silence.onKeepaliveWritten(now)
                rxAt = now + 10 // its answer
                silence.onKeepaliveIn()
                silence.step(now + 20, rxAt, null, null)
            }
        }
        assertEquals(5, resent)
        assertTrue("one probe per answer wait at most: $probes", probes <= (600_000L / LinkSilence.ANSWER_MS).toInt() + 1)
        assertTrue(probes >= 1)
    }
}
