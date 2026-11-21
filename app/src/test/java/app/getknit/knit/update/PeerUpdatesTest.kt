package app.getknit.knit.update

import app.getknit.knit.data.message.UpdateRequestPhase
import app.getknit.knit.data.message.UpdateRequestRecord
import app.getknit.knit.mesh.protocol.AppPin
import app.getknit.knit.mesh.protocol.AppUpdatePayload
import app.getknit.knit.mesh.protocol.FrameId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The peer-update coordinator (ADR 2026-11.29m7) under virtual time: what it announces and when, which frames it acts
 * on, and how an ask binds the one offer that answers it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PeerUpdatesTest {
    private val old = AppBuild(21_300_900L, "2.13.0", CERT, 120_000_000L, 29, slim = false)
    private val new = AppBuild(21_400_900L, "2.14.0", CERT, 124_000_000L, 29, slim = false)

    private class Rig(
        val updates: PeerUpdates,
        val sent: MutableList<Pair<String, AppUpdatePayload>>,
        val neighbors: MutableStateFlow<Set<String>>,
        // Every card write, as (peer, record, the time the ask arrived).
        val rows: MutableList<Triple<String, UpdateRequestRecord, Long>>,
    )

    private fun TestScope.rig(
        mine: AppBuild,
        canServe: Boolean = true,
        canInstall: Boolean = true,
        candidate: (String) -> Boolean = { true },
        sendOk: () -> Boolean = { true },
    ): Rig {
        val sent = mutableListOf<Pair<String, AppUpdatePayload>>()
        val rows = mutableListOf<Triple<String, UpdateRequestRecord, Long>>()
        val neighbors = MutableStateFlow<Set<String>>(emptySet())
        val updates =
            PeerUpdates(
                local = { LocalBuild(mine, canServe, canInstall) },
                signals = { peer, payload -> sendOk().also { if (it) sent += peer to payload } },
                neighbors = neighbors,
                candidate = { candidate(it) },
                scope = backgroundScope,
                sdkInt = 34,
                clock = { testScheduler.currentTime + EPOCH },
                timings = TIMINGS,
                rows = { peer, record, at -> rows += Triple(peer, record, at) },
            )
        updates.start()
        return Rig(updates, sent, neighbors, rows)
    }

    private fun announce(b: AppBuild) =
        AppUpdatePayload(
            phase = AppUpdatePayload.PHASE_ANNOUNCE,
            versionCode = b.versionCode,
            versionName = b.versionName,
            certPin = b.certPin,
            size = b.size,
            minSdk = b.minSdk,
        )

    @Test
    fun aContactThatStaysOnALinkIsAnnouncedToOnceADay() =
        runTest {
            val r = rig(new)
            r.neighbors.value = setOf("bob")
            runCurrent()
            assertTrue("nothing before the dwell", r.sent.isEmpty())
            advanceTimeBy(TIMINGS.dwellMs + 1)
            assertEquals(listOf("bob" to AppUpdatePayload.PHASE_ANNOUNCE), r.sent.map { it.first to it.second.phase })
            assertEquals(announce(new), r.sent.single().second)
            // The link flaps; a second meeting the same day says nothing again.
            r.neighbors.value = emptySet()
            runCurrent()
            r.neighbors.value = setOf("bob")
            advanceTimeBy(TIMINGS.dwellMs + 1)
            assertEquals(1, r.sent.size)
            // A day on, it is said again.
            r.neighbors.value = emptySet()
            runCurrent()
            advanceTimeBy(TIMINGS.announceEveryMs)
            r.neighbors.value = setOf("bob")
            advanceTimeBy(TIMINGS.dwellMs + 1)
            assertEquals(2, r.sent.size)
        }

    @Test
    fun anAnnounceThatCouldNotGoIsTriedAgainWhileTheContactStays() =
        runTest {
            // Not yet a candidate at the dwell (the thread not yet accepted, the session not yet confirmed): a contact who
            // stays is told once it is one, and one who left is not chased.
            var ready = false
            val r = rig(new, candidate = { ready })
            r.neighbors.value = setOf("bob")
            advanceTimeBy(TIMINGS.dwellMs + 1)
            assertTrue(r.sent.isEmpty())
            ready = true
            advanceTimeBy(TIMINGS.announceRetryMs)
            assertEquals(1, r.sent.size)

            // A seal that failed (no confirmed session yet) is tried again the same way.
            var sealable = false
            val unsealed = rig(new, sendOk = { sealable })
            unsealed.neighbors.value = setOf("dave")
            advanceTimeBy(TIMINGS.dwellMs + 1)
            assertTrue(unsealed.sent.isEmpty())
            sealable = true
            advanceTimeBy(TIMINGS.announceRetryMs)
            assertEquals(listOf("dave"), unsealed.sent.map { it.first })

            var never = false
            val gone = rig(new, candidate = { never })
            gone.neighbors.value = setOf("carol")
            advanceTimeBy(TIMINGS.dwellMs + 1)
            gone.neighbors.value = emptySet()
            runCurrent()
            never = true
            advanceTimeBy(TIMINGS.announceRetryMs * 2)
            assertTrue(gone.sent.isEmpty())
        }

    @Test
    fun aPassingLinkANonCandidateOrAPhoneThatCannotServeAnnouncesNothing() =
        runTest {
            val passing = rig(new)
            passing.neighbors.value = setOf("bob")
            advanceTimeBy(TIMINGS.dwellMs / 2)
            passing.neighbors.value = emptySet()
            advanceTimeBy(TIMINGS.dwellMs)
            assertTrue(passing.sent.isEmpty())

            val stranger = rig(new, candidate = { false })
            stranger.neighbors.value = setOf("eve")
            advanceTimeBy(TIMINGS.dwellMs + 1)
            assertTrue(stranger.sent.isEmpty())

            val play = rig(new, canServe = false)
            play.neighbors.value = setOf("bob")
            advanceTimeBy(TIMINGS.dwellMs + 1)
            assertTrue(play.sent.isEmpty())
        }

    @Test
    fun aNewerAnnounceFromANearContactRaisesTheHintAndAnOlderOneDoesNot() =
        runTest {
            val r = rig(old)
            r.neighbors.value = setOf("alice", "carol")
            r.updates.onSignal("alice", announce(new), fromOwnLink = true)
            r.updates.onSignal("carol", announce(old.copy(versionCode = 21_200_900L, versionName = "2.12.0")), fromOwnLink = true)
            assertEquals(
                new,
                r.updates
                    .hintFor("alice")
                    .first()
                    ?.build,
            )
            assertNull(r.updates.hintFor("carol").first())
            // Out of range, the hint goes; back in range, it is back.
            r.neighbors.value = setOf("carol")
            assertNull(r.updates.hintFor("alice").first())
            r.neighbors.value = setOf("alice")
            assertNotNull(r.updates.hintFor("alice").first())
        }

    @Test
    fun aFrameNotFromItsSendersLinkFromAfarOrFromANonCandidateIsDropped() =
        runTest {
            val r = rig(old)
            r.neighbors.value = setOf("alice")
            r.updates.onSignal("alice", announce(new), fromOwnLink = false)
            assertNull("flooded", r.updates.hintFor("alice").first())
            r.updates.onSignal("dave", announce(new), fromOwnLink = true)
            r.neighbors.value = setOf("alice", "dave")
            assertNull("heard from afar", r.updates.hintFor("dave").first())
            val strict = rig(old, candidate = { it != "eve" })
            strict.neighbors.value = setOf("eve")
            strict.updates.onSignal("eve", announce(new), fromOwnLink = true)
            assertNull("not a candidate (blocked, unproven, no direct transfers)", strict.updates.hintFor("eve").first())
        }

    @Test
    fun aBlockForgetsTheContactAtOnce() =
        runTest {
            // Bob is both: this phone heard his newer build and asked him for it, and he asked this phone for its own.
            val blocked = MutableStateFlow<Set<String>>(emptySet())
            val neighbors = MutableStateFlow(setOf("bob"))
            var asked: AppUpdatePayload? = null
            val updates =
                PeerUpdates(
                    local = { LocalBuild(old, canServe = true, canInstall = true) },
                    signals = { _, payload ->
                        if (payload.phase == AppUpdatePayload.PHASE_REQUEST) asked = payload
                        true
                    },
                    neighbors = neighbors,
                    candidate = { true },
                    scope = backgroundScope,
                    sdkInt = 34,
                    clock = { testScheduler.currentTime + EPOCH },
                    timings = TIMINGS,
                    blocked = blocked,
                )
            updates.start()
            runCurrent()
            updates.onSignal("bob", announce(new), fromOwnLink = true)
            assertEquals(AskOutcome.Sent, updates.ask("bob"))
            assertNotNull(updates.onSignal("bob", request(version = old.versionCode), fromOwnLink = true))
            val pin = AppPin(checkNotNull(asked?.requestId), new.versionCode, ByteArray(AppPin.SHA256_BYTES))

            blocked.value = setOf("bob")
            runCurrent()
            assertTrue(updates.requests.value.isEmpty())
            assertTrue(updates.asks.value.isEmpty())
            assertNull(updates.hintFor("bob").first())
            // The ask is gone, so an offer naming it no longer accepts itself.
            assertFalse(updates.expects("bob", pin, new.size))
        }

    @Test
    fun anAnnounceWhoseNameDoesNotDeriveItsCodeOrTrackIsDropped() =
        runTest {
            val r = rig(old)
            r.neighbors.value = setOf("alice")
            r.updates.onSignal("alice", announce(new).copy(versionName = "9.9.9"), fromOwnLink = true)
            assertNull(r.updates.hintFor("alice").first())
            r.updates.onSignal("alice", announce(new).copy(slim = true), fromOwnLink = true)
            assertNull(r.updates.hintFor("alice").first())
            r.updates.onSignal("alice", announce(new).copy(size = null), fromOwnLink = true)
            assertNull(r.updates.hintFor("alice").first())
            r.updates.onSignal("alice", announce(new).copy(versionName = "2.14.0".padEnd(40, '0')), fromOwnLink = true)
            assertNull(r.updates.hintFor("alice").first())
        }

    @Test
    fun anAskBindsTheOneOfferThatAnswersItOnce() =
        runTest {
            val r = rig(old)
            r.neighbors.value = setOf("alice")
            r.updates.onSignal("alice", announce(new), fromOwnLink = true)
            assertEquals(AskOutcome.Sent, r.updates.ask("alice"))
            val request = r.sent.single().second
            assertEquals(AppUpdatePayload.PHASE_REQUEST, request.phase)
            assertEquals(new.versionCode, request.versionCode)
            val rid = checkNotNull(request.requestId)
            assertNotNull(FrameId.toBytesOrNull(rid))
            assertEquals(AskOutcome.Already, r.updates.ask("alice"))
            val pin = AppPin(rid, new.versionCode, ByteArray(AppPin.SHA256_BYTES))
            assertFalse("another contact", r.updates.expects("bob", pin, new.size))
            assertFalse("another request", r.updates.expects("alice", AppPin(FrameId.new(), new.versionCode, pin.sha256), new.size))
            assertFalse("another version", r.updates.expects("alice", AppPin(rid, new.versionCode + 1, pin.sha256), new.size))
            assertFalse("larger than announced", r.updates.expects("alice", pin, new.size + 1))
            assertTrue(r.updates.expects("alice", pin, new.size))
            assertFalse("spent", r.updates.expects("alice", pin, new.size))
            assertTrue(
                r.updates
                    .hintFor("alice")
                    .first()
                    ?.ask is AskState.Answered,
            )
        }

    @Test
    fun aTransferThatEndsShortOfDoneFreesTheAskAtOnce() =
        runTest {
            // The 2026-11-20 trial: a join that failed left "is sending" up for the whole ask time, with no Ask again.
            val r = rig(old)
            r.neighbors.value = setOf("alice")
            r.updates.onSignal("alice", announce(new), fromOwnLink = true)
            r.updates.ask("alice")
            val rid =
                checkNotNull(
                    r.sent
                        .single()
                        .second.requestId,
                )
            val pin = AppPin(rid, new.versionCode, ByteArray(AppPin.SHA256_BYTES))
            assertTrue(r.updates.expects("alice", pin, new.size))
            // An end naming another request, or one that landed, leaves it answered.
            r.updates.ended("alice", AppPin(FrameId.new(), new.versionCode, pin.sha256), done = false)
            r.updates.ended("alice", pin, done = true)
            assertTrue(
                r.updates
                    .hintFor("alice")
                    .first()
                    ?.ask is AskState.Answered,
            )
            r.updates.ended("alice", pin, done = false)
            assertEquals(
                AskState.Failed(new),
                r.updates
                    .hintFor("alice")
                    .first()
                    ?.ask,
            )
            // No waiting out the ask's time: Ask again goes now, as a new request.
            assertEquals(AskOutcome.Sent, r.updates.ask("alice"))
            assertTrue(
                r.sent
                    .last()
                    .second.requestId != rid,
            )
        }

    @Test
    fun anAskNobodyAnswersLapsesAndCanBeAskedAgain() =
        runTest {
            val r = rig(old)
            r.neighbors.value = setOf("alice")
            r.updates.onSignal("alice", announce(new), fromOwnLink = true)
            r.updates.ask("alice")
            val rid =
                checkNotNull(
                    r.sent
                        .single()
                        .second.requestId,
                )
            advanceTimeBy(TIMINGS.askTtlMs + 1)
            assertTrue(
                r.updates
                    .hintFor("alice")
                    .first()
                    ?.ask is AskState.Unanswered,
            )
            assertFalse("too late", r.updates.expects("alice", AppPin(rid, new.versionCode, ByteArray(32)), new.size))
            assertEquals(AskOutcome.Sent, r.updates.ask("alice"))
        }

    @Test
    fun aDeclineReadsOnTheHintOnlyForTheAskItAnswers() =
        runTest {
            val r = rig(old)
            r.neighbors.value = setOf("alice")
            r.updates.onSignal("alice", announce(new), fromOwnLink = true)
            r.updates.ask("alice")
            val rid =
                checkNotNull(
                    r.sent
                        .single()
                        .second.requestId,
                )
            val decline = AppUpdatePayload(AppUpdatePayload.PHASE_DECLINE, new.versionCode, requestId = FrameId.new(), reason = 1)
            r.updates.onSignal("alice", decline, fromOwnLink = true)
            assertTrue(
                r.updates
                    .hintFor("alice")
                    .first()
                    ?.ask is AskState.Waiting,
            )
            r.updates.onSignal("alice", decline.copy(requestId = rid, reason = AppUpdatePayload.REASON_BUSY), fromOwnLink = true)
            assertEquals(
                AskState.Declined(new, AppUpdatePayload.REASON_BUSY),
                r.updates
                    .hintFor("alice")
                    .first()
                    ?.ask,
            )
        }

    private fun request(
        rid: String = FrameId.new(),
        version: Long = new.versionCode,
    ) = AppUpdatePayload(AppUpdatePayload.PHASE_REQUEST, version, requestId = rid)

    @Test
    fun eachAdmittedAskKeepsOneCardThatMovesWithIt() =
        runTest {
            // ADR 2026-11.29m7's amendment: the newer phone's card, one row per ask, at the time the ask arrived.
            val r = rig(new)
            r.neighbors.value = setOf("bob")
            val at = testScheduler.currentTime + EPOCH

            fun phases(rid: String) = r.rows.filter { it.second.requestId == rid }.map { it.second.phase }

            val sent = FrameId.new()
            r.updates.onSignal("bob", request(sent), fromOwnLink = true)
            assertEquals(Triple("bob", UpdateRequestRecord(sent, new.versionCode, UpdateRequestPhase.Waiting), at), r.rows.single())
            r.updates.claim("bob")
            assertEquals(listOf(UpdateRequestPhase.Waiting, UpdateRequestPhase.Sent), phases(sent))
            // A replay inside the request's time is no second card, nor is an ask for a version this phone does not run.
            r.updates.onSignal("bob", request(sent), fromOwnLink = true)
            r.updates.onSignal("bob", request(version = new.versionCode + 1), fromOwnLink = true)
            assertEquals(2, r.rows.size)
            assertEquals(
                AppUpdatePayload.REASON_GONE,
                r.sent
                    .last()
                    .second.reason,
            )

            val busy = FrameId.new()
            r.updates.onSignal("bob", request(busy), fromOwnLink = true)
            val claimed = checkNotNull(r.updates.claim("bob"))
            r.updates.decline("bob", AppUpdatePayload.REASON_BUSY, claimed)
            assertEquals(listOf(UpdateRequestPhase.Waiting, UpdateRequestPhase.Sent, UpdateRequestPhase.Unsent), phases(busy))

            // An ask that lapses: its Expired card keeps the time the ask arrived, not the time it lapsed.
            advanceTimeBy(1_000)
            val lapsedAt = testScheduler.currentTime + EPOCH
            val lapsed = FrameId.new()
            r.updates.onSignal("bob", request(lapsed), fromOwnLink = true)
            advanceTimeBy(TIMINGS.requestTtlMs + 1)
            assertEquals(listOf(UpdateRequestPhase.Waiting, UpdateRequestPhase.Expired), phases(lapsed))
            assertTrue(r.rows.filter { it.second.requestId == lapsed }.all { it.third == lapsedAt })
            assertTrue(
                "the earlier asks' cards stayed at their own time",
                r.rows.filter { it.second.requestId == sent }.all {
                    it.third ==
                        at
                },
            )

            // Send tapped after the ask's time is up: claim() records it Expired itself.
            val late = FrameId.new()
            r.updates.onSignal("bob", request(late), fromOwnLink = true)
            // Exactly its time later: the lapse is due this instant but has not run (advanceTimeBy stops short of tasks
            // at its end), so the tap gets there first, as one racing it would.
            advanceTimeBy(TIMINGS.requestTtlMs)
            assertNull(r.updates.claim("bob"))
            assertEquals(listOf(UpdateRequestPhase.Waiting, UpdateRequestPhase.Expired), phases(late))

            val notNow = FrameId.new()
            r.updates.onSignal("bob", request(notNow), fromOwnLink = true)
            r.updates.decline("bob")
            assertEquals(listOf(UpdateRequestPhase.Waiting, UpdateRequestPhase.Declined), phases(notNow))

            // Within the quiet hour a new ask is answered without the person, so it gets no card at all.
            val quiet = FrameId.new()
            r.updates.onSignal("bob", request(quiet), fromOwnLink = true)
            assertTrue(phases(quiet).isEmpty())
        }

    @Test
    fun aForgottenContactsAskWritesNoCardAfterwards() =
        runTest {
            // A removal deletes the thread right after forget(): nothing still in flight may write a row back into it.
            val r = rig(new)
            r.neighbors.value = setOf("bob")
            r.updates.onSignal("bob", request(), fromOwnLink = true)
            assertEquals(1, r.rows.size)
            r.updates.forget("bob")
            advanceTimeBy(TIMINGS.requestTtlMs + 1)
            assertNull(r.updates.claim("bob"))
            r.updates.decline("bob")
            assertEquals("only the card written before the forget", 1, r.rows.size)

            // The write only `forgottenAt` stops: one that already holds the request. Send was tapped (claimed), the
            // removal runs, then the transfer could not start and the decline would record Unsent.
            advanceTimeBy(1)
            r.updates.onSignal("bob", request(), fromOwnLink = true)
            val claimed = checkNotNull(r.updates.claim("bob"))
            val before = r.rows.size
            r.updates.forget("bob")
            r.updates.decline("bob", AppUpdatePayload.REASON_BUSY, claimed)
            assertEquals("no Unsent card after the forget", before, r.rows.size)

            // An ask after the forget, even in its own millisecond, is a new card.
            r.updates.onSignal("bob", request(), fromOwnLink = true)
            assertEquals(before + 1, r.rows.size)
        }

    @Test
    fun aRequestWaitsForThePersonOnceAndAReplayIsNotASecond() =
        runTest {
            val r = rig(new)
            r.neighbors.value = setOf("bob")
            val rid = FrameId.new()
            assertNotNull(r.updates.onSignal("bob", request(rid), fromOwnLink = true))
            assertNull("a replay", r.updates.onSignal("bob", request(rid), fromOwnLink = true))
            assertNull("a second while one waits", r.updates.onSignal("bob", request(), fromOwnLink = true))
            assertEquals(
                rid,
                r.updates.requests.value["bob"]
                    ?.requestId,
            )
            assertTrue(r.sent.isEmpty())
            val claimed = checkNotNull(r.updates.claim("bob"))
            assertEquals(rid, claimed.requestId)
            assertNull(r.updates.claim("bob"))
        }

    @Test
    fun notNowTellsTheAskerAndKeepsThemQuietForAnHour() =
        runTest {
            val r = rig(new)
            r.neighbors.value = setOf("bob")
            val rid = FrameId.new()
            r.updates.onSignal("bob", request(rid), fromOwnLink = true)
            r.updates.decline("bob")
            assertEquals(
                AppUpdatePayload(
                    AppUpdatePayload.PHASE_DECLINE,
                    new.versionCode,
                    requestId = rid,
                    reason = AppUpdatePayload.REASON_NOT_NOW,
                ),
                r.sent.single().second,
            )
            // Within the hour, a new ask is answered at once, without the person.
            val again = FrameId.new()
            assertNull(r.updates.onSignal("bob", request(again), fromOwnLink = true))
            assertEquals(
                again,
                r.sent
                    .last()
                    .second.requestId,
            )
            assertTrue(
                r.updates.requests.value
                    .isEmpty(),
            )
            advanceTimeBy(TIMINGS.quietMs + 1)
            assertNotNull(r.updates.onSignal("bob", request(), fromOwnLink = true))
        }

    @Test
    fun aRequestForAnotherVersionOrToAPhoneThatCannotServeIsAnsweredGone() =
        runTest {
            val r = rig(new)
            r.neighbors.value = setOf("bob")
            assertNull(r.updates.onSignal("bob", request(version = new.versionCode - 1), fromOwnLink = true))
            assertEquals(
                AppUpdatePayload.REASON_GONE,
                r.sent
                    .single()
                    .second.reason,
            )
            val play = rig(new, canServe = false)
            play.neighbors.value = setOf("bob")
            assertNull(play.updates.onSignal("bob", request(), fromOwnLink = true))
            assertEquals(
                AppUpdatePayload.REASON_GONE,
                play.sent
                    .single()
                    .second.reason,
            )
        }

    @Test
    fun aRequestWithACraftedIdIsIgnoredAndForgetEndsEverything() =
        runTest {
            val r = rig(new)
            r.neighbors.value = setOf("bob")
            assertNull(r.updates.onSignal("bob", request(rid = "../../x"), fromOwnLink = true))
            assertTrue(r.sent.isEmpty())
            r.updates.onSignal("bob", request(), fromOwnLink = true)
            r.updates.forget("bob")
            assertTrue(
                r.updates.requests.value
                    .isEmpty(),
            )
        }

    private companion object {
        const val CERT = 0x1234_5678_9abc_def0L

        // Virtual time starts at zero; a wall clock never reads that, and a stale check against it would underflow.
        const val EPOCH = 1_700_000_000_000L
        val TIMINGS = UpdateTimings()
    }
}
