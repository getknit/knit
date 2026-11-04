package app.getknit.knit

import app.getknit.knit.mesh.BleRotation
import app.getknit.knit.mesh.OwnPresenceKey
import app.getknit.knit.mesh.bluetooth.AdvertRotation
import app.getknit.knit.mesh.bluetooth.AdvertRotation.Cause
import app.getknit.knit.mesh.bluetooth.AdvertRotation.Step
import app.getknit.knit.mesh.bluetooth.StaleLatch
import app.getknit.knit.mesh.crypto.BlindTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AdvertRotation] (ADR 2026-11.xp6d's writer, step 5b's W4): the slot turn on the wall clock, the announcement's lead,
 * a late wake, the hold bounded from the due time (a fresh window every half hold does not move it), the ACL settle, a
 * clock set back, and which own-key change restarts the advert. [StaleLatch]: one pass request per generation.
 */
class AdvertRotationTest {
    private val slot = 2_000_000L
    private val slotStart = slot * BlindTag.SLOT_MS
    private val edge = slotStart + BlindTag.SLOT_MS
    private val hold = 180_000L
    private val rotation = AdvertRotation()

    private fun next(
        wall: Long,
        holding: Boolean = false,
        settle: Long = 0,
    ): Step = rotation.next(wall, holding, settle, hold)

    @Test
    fun nothingTurnsUntilABlindedFormIsOnTheAir() {
        assertEquals(Step.Wait(Long.MAX_VALUE), next(edge + 1))
        assertFalse(rotation.turning)
    }

    @Test
    fun theTurnAnnouncesAtTheLeadThenRotatesAtTheEdgeOnTheAnnouncedTag() {
        rotation.onRotated(slotStart + 60_000)
        assertEquals("waits for the lead", Step.Wait(edge - AdvertRotation.LEAD_MS), next(slotStart + 61_000))
        val announceAt = edge - AdvertRotation.LEAD_MS
        assertEquals(Step.Announce, next(announceAt))
        rotation.onAnnounced(announceAt)
        assertEquals("the lead is past the 30 s minimum: the edge itself", Step.Wait(edge), next(announceAt + 1))
        assertEquals(Step.Rotate(announced = true), next(edge))
    }

    @Test
    fun anAnnouncementLateInTheLeadStillGetsItsThirtySeconds() {
        rotation.onRotated(slotStart)
        val late = edge - 10_000 // the first wake in the lead came 10 s before the edge
        assertEquals(Step.Announce, next(late))
        rotation.onAnnounced(late)
        assertEquals(Step.Wait(late + AdvertRotation.MIN_LEAD_MS), next(edge))
        assertEquals(Step.Rotate(announced = true), next(late + AdvertRotation.MIN_LEAD_MS))
    }

    @Test
    fun aWakePastTheEdgeWithNothingAnnouncedRotatesAtOnceUnannounced() {
        rotation.onRotated(slotStart)
        // Asleep through the whole lead: the first wake is 40 minutes past the edge.
        assertEquals(Step.Rotate(announced = false), next(edge + 40 * 60_000L))
    }

    @Test
    fun anAnnouncementASlotLateIsNotTakenAndTheRotationIsUnannounced() {
        rotation.onRotated(slotStart)
        val announceAt = edge - AdvertRotation.LEAD_MS
        rotation.onAnnounced(announceAt)
        assertEquals(
            "the reserved slot has passed: next(), and the current form resent",
            Step.Rotate(false),
            next(edge + BlindTag.SLOT_MS + 1),
        )
    }

    @Test
    fun aDroppedAnnouncementRotatesUnannounced() {
        rotation.onRotated(slotStart)
        rotation.onAnnounced(edge - AdvertRotation.LEAD_MS)
        rotation.dropAnnouncement() // a key change dropped the reservation
        assertEquals(Step.Rotate(announced = false), next(edge))
    }

    @Test
    fun aRotationIsHeldWhileAHoldRunsAndRotatesWhenItEnds() {
        rotation.onRotated(slotStart)
        val held = next(edge, holding = true)
        assertTrue("held, looking again before the bound", held is Step.Wait && held.atWallMs <= edge + hold)
        assertEquals(Step.Rotate(announced = false, held = true, bounded = false), next(edge + 20_000, holding = false))
    }

    @Test
    fun theHoldIsBoundedFromTheDueTimeWhateverWindowsOpenAfterIt() {
        rotation.onRotated(slotStart)
        // A peer links weakly and drops under a fresh id every half hold from T: a fresh fast window each time, so some hold
        // is always on. The rotation still lands at T + fastHoldMs.
        var wall = edge
        var rotatedAt: Long? = null
        while (wall <= edge + 2 * hold) {
            val step = next(wall, holding = true)
            if (step is Step.Rotate) {
                rotatedAt = wall
                assertTrue(step.held)
                assertTrue("rotated with the hold still on", step.bounded)
                break
            }
            wall = (step as Step.Wait).atWallMs
        }
        assertEquals(edge + hold, rotatedAt)
    }

    @Test
    fun anAnnouncedRotationsBoundRunsFromItsOwnDueTime() {
        rotation.onRotated(slotStart)
        val late = edge - 10_000
        rotation.onAnnounced(late)
        val due = late + AdvertRotation.MIN_LEAD_MS
        assertTrue(next(due, holding = true) is Step.Wait)
        assertTrue(next(due + hold - 1, holding = true) is Step.Wait)
        assertTrue(next(due + hold, holding = true) is Step.Rotate)
    }

    @Test
    fun anAclEdgesSettleDelaysTheRotationButNeverPastTheBound() {
        rotation.onRotated(slotStart)
        assertEquals(Step.Wait(edge + 2_500), next(edge, settle = 2_500))
        assertEquals(Step.Rotate(announced = false, held = true, bounded = false), next(edge + 2_500))

        val again = AdvertRotation()
        again.onRotated(slotStart)
        assertTrue(again.next(edge, false, 2_500, hold) is Step.Wait)
        // Edges every two seconds from then on: the settle never ends, and the bound still rotates.
        assertTrue(again.next(edge + hold, false, 2_500, hold) is Step.Rotate)
    }

    @Test
    fun aClockSetBackBelowTheSlotOnTheAirRotatesAtOnce() {
        rotation.onRotated(slotStart + 1)
        assertEquals(Step.Rotate(announced = false), next(slotStart - 2 * 3_600_000L))
    }

    @Test
    fun aRotationStartsTheNextTurnFromItsOwnSlot() {
        rotation.onRotated(slotStart)
        rotation.onRotated(edge + 5_000) // the rotation at the edge
        val nextEdge = edge + BlindTag.SLOT_MS
        assertEquals(Step.Wait(nextEdge - AdvertRotation.LEAD_MS), next(edge + 6_000))
    }

    @Test
    fun aStoppedWriterHasNothingToTurn() {
        rotation.onRotated(slotStart)
        rotation.onStopped()
        assertEquals(Step.Wait(Long.MAX_VALUE), next(edge + 1))
    }

    private fun key(
        g: Long,
        b: Byte = g.toByte(),
    ) = OwnPresenceKey(g, ByteArray(BlindTag.KEY_BYTES) { b })

    @Test
    fun whichOwnKeyChangeRestartsTheAdvert() {
        // An equal value is no change; the generation already on the air (a bring-up that read it) is no change.
        assertNull(AdvertRotation.causeOfKeyChange(key(5), key(5), airedGeneration = 5, sessionHadKey = true))
        assertNull(AdvertRotation.causeOfKeyChange(null, key(5), airedGeneration = 5, sessionHadKey = false))
        assertNull(AdvertRotation.causeOfKeyChange(null, null, airedGeneration = null, sessionHadKey = false))
        // The session's first key after a bring-up that aired a random tag for want of one completes that bring-up.
        assertEquals(Cause.BRINGUP, AdvertRotation.causeOfKeyChange(null, key(5), airedGeneration = null, sessionHadKey = false))
        // Every other change of the generation, a null among them, is a fresh restart (never held).
        assertEquals(Cause.FRESH, AdvertRotation.causeOfKeyChange(key(5), key(6), airedGeneration = 5, sessionHadKey = true))
        assertEquals(Cause.FRESH, AdvertRotation.causeOfKeyChange(key(5), null, airedGeneration = 5, sessionHadKey = true))
        assertEquals(Cause.FRESH, AdvertRotation.causeOfKeyChange(null, key(6), airedGeneration = null, sessionHadKey = true))
        // The same generation with other bytes cannot come from the engine; it is still not the key on the air: a restart.
        assertEquals(Cause.FRESH, AdvertRotation.causeOfKeyChange(key(5), key(5, 9), airedGeneration = 4, sessionHadKey = true))
    }

    @Test
    fun aStaleMarkAsksOncePerGeneration() {
        val latch = StaleLatch()
        assertFalse("not stale", latch.offer(7, stale = false))
        assertTrue(latch.offer(7, stale = true))
        assertFalse("latched for the generation", latch.offer(7, stale = true))
        assertFalse(latch.offer(7, stale = false))
        assertFalse("still latched: the episode ends only with the generation", latch.offer(7, stale = true))
        assertTrue("a new generation clears the latch", latch.offer(8, stale = true))
        assertFalse(latch.offer(8, stale = true))
    }

    @Test
    fun everyCauseHasItsCounter() {
        // The wiring counts a rotation by its cause's name (`bleRotationsByCause`).
        assertEquals(Cause.entries.map { it.name }, BleRotation.entries.map { it.name })
    }

    @Test
    fun theNextAnnouncementIsDueAtTheLeadUntilItIsMade() {
        assertNull("nothing on the air", rotation.announceDueAt())
        rotation.onRotated(slotStart)
        assertEquals(edge - AdvertRotation.LEAD_MS, rotation.announceDueAt())
        rotation.onAnnounced(edge - AdvertRotation.LEAD_MS)
        assertNull("made: a resend is kept out by the receiver's limit instead", rotation.announceDueAt())
        rotation.onRotated(edge)
        assertEquals(edge + BlindTag.SLOT_MS - AdvertRotation.LEAD_MS, rotation.announceDueAt())
    }

    @Test
    fun theBridgesViewNamesTheEdgeTheAnnouncementAndAHold() {
        assertEquals(AdvertRotation.View(nextEdgeAtMs = null, announced = false, holding = false), rotation.view())
        rotation.onRotated(slotStart + 1_000)
        assertEquals(AdvertRotation.View(edge, announced = false, holding = false), rotation.view())
        rotation.onAnnounced(edge - AdvertRotation.LEAD_MS)
        assertEquals(AdvertRotation.View(edge, announced = true, holding = false), rotation.view())
        // Past the edge and the 30 s lead, a hold applies: the due rotation waits, and the view says so.
        assertTrue(next(edge + AdvertRotation.MIN_LEAD_MS, holding = true) is Step.Wait)
        assertEquals(AdvertRotation.View(edge, announced = true, holding = true), rotation.view())
        rotation.dropAnnouncement()
        assertFalse(rotation.view().announced)
        rotation.onRotated(edge + AdvertRotation.MIN_LEAD_MS)
        assertEquals(AdvertRotation.View(edge + BlindTag.SLOT_MS, announced = false, holding = false), rotation.view())
        rotation.onStopped()
        assertNull(rotation.view().nextEdgeAtMs)
    }
}
