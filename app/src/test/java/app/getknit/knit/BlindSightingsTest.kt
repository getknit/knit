package app.getknit.knit

import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.bluetooth.AdvertAliases
import app.getknit.knit.mesh.bluetooth.AdvertAliases.Resolution
import app.getknit.knit.mesh.bluetooth.AdvertAliases.Source
import app.getknit.knit.mesh.bluetooth.AdvertOrder
import app.getknit.knit.mesh.bluetooth.BlePresenceTracker.Suppressor
import app.getknit.knit.mesh.bluetooth.BlindSightings
import app.getknit.knit.mesh.bluetooth.BlindSightings.History
import app.getknit.knit.mesh.bluetooth.ReplayMarks
import app.getknit.knit.mesh.crypto.BlindTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BlindSightings] (ADR 2026-11.xp6d, step 5a obligations 8 and 11): where a resolved blinded sighting lands, whether it
 * may suppress a dial, and what becomes of the history its form gathered unresolved.
 */
class BlindSightingsTest {
    private val handle = "~zzzz…0"

    private fun attribute(
        r: Resolution,
        replayed: Boolean = false,
    ) = BlindSightings.attribute(r, handle, replayed)

    @Test
    fun anUnresolvedFormStaysUnderItsHandleAndNeverSuppresses() {
        assertEquals(BlindSightings.Attribution(handle, Suppressor.None, History.NONE, full = true), attribute(Resolution.Unresolved))
    }

    @Test
    fun aKeysAnswerCarriesTheHistoryAndSuppressesOnlyThroughAProvenLinkReadAtTheDial() {
        val r = Resolution.Resolved("bbb", Source.Key)
        val keyed = attribute(r)!!
        assertEquals("bbb", keyed.key)
        assertEquals(History.CARRY, keyed.history)
        assertEquals("nothing counts as linked to X without X's proof", Suppressor.Proven, keyed.suppressor)
    }

    @Test
    fun aReplayedTagAttributesAndCarriesNothing() {
        assertNull(attribute(Resolution.Resolved("bbb", Source.Key), replayed = true))
    }

    @Test
    fun aClaimAttributesFromNowOnAndNeverBackwards() {
        val link = Any()
        val claim = attribute(Resolution.Resolved("bbb", Source.Claim, claimant = link))!!
        assertEquals(History.DROP, claim.history)
        assertEquals("the claimant's own link, read when the dial is decided", Suppressor.Claim(link), claim.suppressor)
        assertEquals(Suppressor.None, attribute(Resolution.Resolved("bbb", Source.Claim))!!.suppressor)
    }

    @Test
    fun aHintFeedsPresenceOnlyAndNeverSuppresses() {
        val hint = attribute(Resolution.Resolved("bbb", Source.Hint))!!
        assertEquals(BlindSightings.Attribution("bbb", Suppressor.None, History.DROP, full = false), hint)
    }

    @Test
    fun aHandshakeEventAfterItsLinkEndedNeverReachesTheResolver() {
        // The security review of step 5a: the end is enqueued only after the link leaves the table, so the guard orders
        // every event it lets through before that end; one it stops would have held a dead link and a hint for good.
        var now = 0L
        val aliases = AdvertAliases<Any>(nowMs = { now }, wallMs = { 1_800_000_000_000L })
        val tag = NodeId.fromBytes(BlindTag.tag(ByteArray(BlindTag.KEY_BYTES) { 3 }, slot = 1, restart = 0))
        val link = Any()
        var registered: Any? = link
        // The fork proves and closes at once: its end runs on the worker before its `onAnswered`.
        registered = null
        aliases.onLinkEnded(link)
        if (BlindSightings.current(registered, link)) aliases.onAnswered(link, tag, "fork")
        now += 24 * 60 * 60_000L
        assertEquals("no hint stands for the dead link", Resolution.Unresolved, aliases.resolve(AdvertOrder.Form(tag, blinded = true)))
        // The positive control: the same order unguarded leaves a hint that never lapses (why the guard exists).
        val unguarded = AdvertAliases<Any>(nowMs = { now }, wallMs = { 1_800_000_000_000L })
        val dead = Any()
        unguarded.onLinkEnded(dead)
        unguarded.onAnswered(dead, tag, "fork")
        now += 24 * 60 * 60_000L
        assertEquals(
            "a dead link's hint",
            Resolution.Resolved("fork", Source.Hint),
            unguarded.resolve(AdvertOrder.Form(tag, blinded = true)),
        )
        // While the link is the registered one, the event goes through and its end follows it.
        val live = Any()
        assertTrue(BlindSightings.current(live, live))
        assertFalse("a replacement under the same id is another link", BlindSightings.current(Any(), live))
    }

    @Test
    fun aLateSightingReachesPresenceOnlyAndAHintNotesOnlyItsOwnLink() {
        fun reach(
            full: Boolean,
            onTime: Boolean,
            ownLink: Boolean,
        ) = BlindSightings.reach(full, onTime, ownLink)
        assertEquals(
            BlindSightings.Reach(devices = true, reach = true, linkNotes = true),
            reach(full = true, onTime = true, ownLink = false),
        )
        assertEquals(
            BlindSightings.Reach(devices = true, reach = false, linkNotes = true),
            reach(full = false, onTime = true, ownLink = true),
        )
        assertEquals(
            BlindSightings.Reach(devices = true, reach = false, linkNotes = false),
            reach(full = false, onTime = true, ownLink = false),
        )
        listOf(true, false).forEach { full ->
            listOf(true, false).forEach { own ->
                assertEquals("late", BlindSightings.Reach(false, false, false), reach(full, onTime = false, ownLink = own))
            }
        }
    }

    @Test
    fun myOwnTagAndAMalformedOneAttributeNothing() {
        assertNull(attribute(Resolution.Own))
        assertNull(attribute(Resolution.Malformed))
    }

    @Test
    fun aReplayIsThePairOfTagAndAddress() {
        val marks = ReplayMarks(cap = 2)
        marks.mark("t1", "AA")
        assertTrue(marks.replayed("t1", "AA"))
        assertFalse("the owner's own address", marks.replayed("t1", "BB"))
        assertFalse(marks.replayed("t1", null))
        marks.mark("t2", "AA")
        marks.mark("t3", "AA")
        assertFalse("bounded, the oldest out first", marks.replayed("t1", "AA"))
    }
}
