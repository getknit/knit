package app.getknit.knit.mesh.inet

import app.getknit.knit.fieldlog.FieldEvent
import app.getknit.knit.fieldlog.FieldRecorder
import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.crypto.LinkSignals
import app.getknit.knit.mesh.inet.FakeLinkNet.Nat
import app.getknit.knit.mesh.protocol.LinkSignal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Two phones' direct links against each other (ADR 2026-10.a5yj, Phase 0): the offer and answer sent between them as
 * sigs (a CBOR round trip after a sig's delay), the punch over [FakeLinkNet], the backoff, crossing offers and the answer
 * budget, on virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class) // the test scheduler's clock and advanceTimeBy
class DirectLinksTest {
    private val stunAt = byteArrayOf(198.toByte(), 51, 100, 7)
    private val aliceId = NodeId.derive("alice")
    private val bobId = NodeId.derive("bob")

    private class Phone(
        val id: String,
        val net: LinkDatagrams,
    ) {
        lateinit var links: DirectLinks
        val lines = mutableListOf<FieldEvent>()
        var enabled = true
        var declining = false
        var qualifies = true
        var signalOk = true
        var signals = 0
        var stun = listOf("stun:stun.example")
    }

    private fun TestScope.pair(
        aNet: LinkDatagrams,
        bNet: LinkDatagrams,
        scope: CoroutineScope = backgroundScope,
    ): Pair<Phone, Phone> {
        val a = Phone(aliceId, aNet)
        val b = Phone(bobId, bNet)
        for ((me, them) in listOf(a to b, b to a)) {
            val peers =
                object : LinkPeers {
                    override suspend fun mayOffer(peerId: String) = me.qualifies

                    override fun canSignal(peerId: String) = me.signalOk

                    override fun stunUrls() = me.stun

                    override suspend fun send(
                        peerId: String,
                        signal: LinkSignal,
                    ): Boolean {
                        if (!me.signalOk) return false
                        me.signals++
                        val bytes = checkNotNull(LinkSignals.encode(signal)) { "a well-formed signal" }
                        scope.launch {
                            delay(SIG_MS)
                            them.links.onSignal(me.id, checkNotNull(LinkSignals.decode(bytes)))
                        }
                        return true
                    }
                }
            me.links =
                DirectLinks(
                    selfId = { me.id },
                    datagrams = me.net,
                    peers = peers,
                    enabled = { me.enabled },
                    scope = scope,
                    declining = { me.declining },
                    clock = { testScheduler.currentTime },
                    fieldLog = FieldRecorder { me.lines += it },
                    random = java.util.Random(me.id.hashCode().toLong()),
                )
        }
        return a to b
    }

    private fun v4(
        a: Int,
        b: Int,
        c: Int,
        d: Int,
    ) = byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte())

    private fun v6(last: Int) =
        ByteArray(16).also {
            it[0] = 0x24
            it[1] = 0x01
            it[15] = last.toByte()
        }

    private fun FakeLinkNet.withStun() = also { it.stunServer("stun.example", stunAt) }

    private fun tries(phone: Phone) = phone.lines.filterIsInstance<FieldEvent.LinkTry>()

    // The offers [phone] answered: its tries less the ones it held back.
    private fun answered(phone: Phone) = tries(phone).count { it.outcome != LinkOutcome.BUDGET && it.outcome != LinkOutcome.OFF }

    @Test
    fun anOfferIsAnsweredAndBothEndsHoldTheLink() =
        runTest {
            val net = FakeLinkNet().withStun()
            val (a, b) = pair(net.phone(Nat.PORT_RESTRICTED, v4(81, 2, 3, 4)), net.phone(Nat.ADDRESS_RESTRICTED, v4(91, 5, 6, 7)))
            assertEquals(LinkOutcome.UP, a.links.offer(b.id))
            b.links.links.first { a.id in it }
            assertTrue(b.id in a.links.links.value)
            val aTry = tries(a).single()
            assertEquals(LinkOutcome.UP, aTry.outcome)
            assertEquals(LinkPath.V4_REFLEXIVE, aTry.path)
            assertTrue(aTry.out && aTry.reflexive && !aTry.v6)
            assertEquals(LinkOutcome.UP, tries(b).single().outcome)
            assertFalse(tries(b).single().out)
        }

    @Test
    fun aContactInLowPowerOrPausedIsNeverAnsweredAndWaitsOutItsBackoff() =
        runTest {
            val net = FakeLinkNet().withStun()
            val (a, b) = pair(net.phone(Nat.PORT_RESTRICTED, v4(81, 2, 3, 4)), net.phone(Nat.PORT_RESTRICTED, v4(91, 5, 6, 7)))
            b.enabled = false
            assertEquals(LinkOutcome.NO_ANSWER, a.links.offer(b.id))
            assertEquals(LinkOutcome.BACKOFF, a.links.offer(b.id))
            assertEquals(1, a.signals)
            testScheduler.advanceTimeBy(PunchPolicy.backoffMs(1))
            assertEquals(LinkOutcome.NO_ANSWER, a.links.offer(b.id))
            assertEquals(2, a.signals)
            // Nothing went back, and each offer it held back is on its record.
            assertEquals(0, b.signals)
            assertEquals(listOf(LinkOutcome.OFF, LinkOutcome.OFF), tries(b).map { it.outcome })
            assertTrue(tries(b).none { it.out })
        }

    @Test
    fun aContactWithItsSwitchOffDeclinesAtOnceWithNoBackoffAndItsNextOfferClearsIt() =
        runTest {
            val net = FakeLinkNet().withStun()
            val (a, b) = pair(net.phone(Nat.PORT_RESTRICTED, v4(81, 2, 3, 4)), net.phone(Nat.PORT_RESTRICTED, v4(91, 5, 6, 7)))
            b.enabled = false
            b.declining = true
            val started = testScheduler.currentTime
            assertEquals(LinkOutcome.DECLINED, a.links.offer(b.id, why = LinkWhy.FILE))
            assertTrue("waited out the answer", testScheduler.currentTime - started < DirectLinks.ANSWER_WAIT_MS)
            assertNotNull(a.links.declinedAt(b.id))
            val mine = tries(a).single()
            assertEquals(LinkOutcome.DECLINED to LinkWhy.FILE, mine.outcome to mine.why)
            assertEquals(LinkOutcome.DECLINED, tries(b).single().outcome)
            assertFalse(tries(b).single().out)
            // No backoff: the next thing worth a link asks at once (the demand decides what is newer).
            assertEquals(LinkOutcome.DECLINED, a.links.offer(b.id))
            // Its person switches links on and offers: our record of its decline goes, and the link comes up.
            b.enabled = true
            b.declining = false
            assertEquals(LinkOutcome.UP, b.links.offer(a.id))
            assertEquals(null, a.links.declinedAt(b.id))
        }

    @Test
    fun aDeclinersOwnOfferClearsTheDeclineEvenWhenItsLinkNeverComesUp() =
        runTest {
            val net = FakeLinkNet().withStun()
            val (a, b) = pair(net.phone(Nat.SYMMETRIC, v4(81, 2, 3, 4)), net.phone(Nat.SYMMETRIC, v4(91, 5, 6, 7)))
            b.enabled = false
            b.declining = true
            assertEquals(LinkOutcome.DECLINED, a.links.offer(b.id))
            b.enabled = true
            b.declining = false
            assertEquals(LinkOutcome.NO_PATH, b.links.offer(a.id))
            assertEquals(null, a.links.declinedAt(b.id))
        }

    @Test
    fun aContactsDeclinesAreCappedAnHourAndItsOffersPastThatPassUnanswered() =
        runTest {
            val net = FakeLinkNet().withStun()
            val (a, b) = pair(net.phone(Nat.PORT_RESTRICTED, v4(81, 2, 3, 4)), net.phone(Nat.PORT_RESTRICTED, v4(91, 5, 6, 7)))
            b.enabled = false
            b.declining = true
            repeat(DirectLinks.DECLINES_PER_HOUR) { assertEquals(LinkOutcome.DECLINED, a.links.offer(b.id, force = true)) }
            assertEquals(LinkOutcome.NO_ANSWER, a.links.offer(b.id, force = true))
            assertEquals(DirectLinks.DECLINES_PER_HOUR, b.signals)
            testScheduler.advanceTimeBy(60 * 60_000L)
            assertEquals(LinkOutcome.DECLINED, a.links.offer(b.id, force = true))
        }

    @Test
    fun aWalkiesAsksOfferADeclinerOnceASessionAndTheNextSessionOnceMore() =
        runTest {
            val net = FakeLinkNet().withStun()
            val (a, b) = pair(net.phone(Nat.PORT_RESTRICTED, v4(81, 2, 3, 4)), net.phone(Nat.PORT_RESTRICTED, v4(91, 5, 6, 7)))
            b.enabled = false
            b.declining = true
            repeat(ASKS) {
                assertFalse(a.links.raise(b.id))
                testScheduler.advanceTimeBy(ASK_MS)
            }
            assertEquals("one offer the whole session", 1, a.signals)
            // The call ends; the next one, past a hold's gap, asks once more.
            testScheduler.advanceTimeBy(DirectLinks.HOLD_TTL_MS + ASK_MS)
            repeat(3) {
                a.links.raise(b.id)
                testScheduler.advanceTimeBy(ASK_MS)
            }
            assertEquals(2, a.signals)
            assertEquals(listOf(LinkWhy.WALKIE, LinkWhy.WALKIE), a.lines.filterIsInstance<FieldEvent.LinkTry>().map { it.why })
        }

    @Test
    fun twoSymmetricNatsFindNoPathAndBackOff() =
        runTest {
            val net = FakeLinkNet().withStun()
            val (a, b) = pair(net.phone(Nat.SYMMETRIC, v4(81, 2, 3, 4)), net.phone(Nat.SYMMETRIC, v4(91, 5, 6, 7)))
            assertEquals(LinkOutcome.NO_PATH, a.links.offer(b.id))
            assertEquals(LinkOutcome.BACKOFF, a.links.offer(b.id))
            assertEquals(LinkOutcome.NO_PATH, tries(a).single().outcome)
        }

    @Test
    fun aLiveSessionsAsksRaiseOneLinkHoldItThroughThePausesAndLetItIdleOutOnceTheyStop() =
        runTest {
            val net = FakeLinkNet().withStun()
            val (a, b) = pair(net.phone(Nat.PORT_RESTRICTED, v4(81, 2, 3, 4)), net.phone(Nat.PORT_RESTRICTED, v4(91, 5, 6, 7)))
            // No link yet: the ask starts an attempt and holds nothing; asked again while it runs, it holds nothing either.
            assertFalse(a.links.raise(b.id))
            testScheduler.runCurrent()
            assertFalse(a.links.raise(b.id))
            a.links.links.first { b.id in it }
            b.links.links.first { a.id in it }
            assertEquals(1, a.signals)
            // Both ends ask every 10 s, as a walkie call does from the ring to the hang-up: three silent minutes, three
            // times the idle close, and the link stays.
            repeat(ASKS) {
                assertTrue(a.links.raise(b.id))
                assertTrue(b.links.raise(a.id))
                testScheduler.advanceTimeBy(ASK_MS)
            }
            assertTrue(b.id in a.links.links.value && a.id in b.links.links.value)
            // The asks stop: the hold ends 25 s after the last, and the link idles out a whole minute after that.
            val holdEnd = testScheduler.currentTime - ASK_MS + DirectLinks.HOLD_TTL_MS
            val gone = withTimeoutOrNull(DirectLinks.HOLD_TTL_MS + LinkSession.IDLE_MS + 30_000L) { a.links.links.first { b.id !in it } }
            assertNotNull("still up: ${a.links.links.value[b.id]?.state?.value}", gone)
            val past = testScheduler.currentTime - holdEnd
            assertTrue("closed ${past}ms past the hold's end", past >= LinkSession.IDLE_MS)
            assertEquals(1, a.signals)
        }

    @Test
    fun anAskStartsNothingWhileLinksAreOffOrTheContactWaitsOutItsBackoff() =
        runTest {
            val net = FakeLinkNet().withStun()
            val (a, b) = pair(net.phone(Nat.SYMMETRIC, v4(81, 2, 3, 4)), net.phone(Nat.SYMMETRIC, v4(91, 5, 6, 7)))
            a.enabled = false
            assertFalse(a.links.raise(b.id))
            testScheduler.advanceTimeBy(ASK_MS)
            assertEquals(0, a.signals)
            a.enabled = true
            // The punch finds no path, and the asks that follow inside its backoff send nothing.
            assertFalse(a.links.raise(b.id))
            repeat(PUNCH_WAIT_S) { if (tries(a).isEmpty()) testScheduler.advanceTimeBy(1_000L) }
            assertEquals(LinkOutcome.NO_PATH, tries(a).single().outcome)
            repeat(3) {
                assertFalse(a.links.raise(b.id))
                testScheduler.advanceTimeBy(ASK_MS)
            }
            assertEquals(1, a.signals)
            assertEquals(1, tries(a).size)
        }

    @Test
    fun nothingIsSentToAContactThatDoesNotQualifyOrWithNoSigLaneToCarryIt() =
        runTest {
            val net = FakeLinkNet().withStun()
            val (a, b) = pair(net.phone(Nat.PORT_RESTRICTED, v4(81, 2, 3, 4)), net.phone(Nat.PORT_RESTRICTED, v4(91, 5, 6, 7)))
            a.qualifies = false
            assertEquals(LinkOutcome.NOT_QUALIFIED, a.links.offer(b.id))
            a.qualifies = true
            a.signalOk = false
            assertEquals(LinkOutcome.NO_SIG, a.links.offer(b.id))
            assertEquals(0, a.signals)
            assertEquals(LinkOutcome.OFF, a.links.offer(a.id))
        }

    @Test
    fun crossingOffersEndInOneLinkOnTheLowerIdsOffer() =
        runTest {
            val net = FakeLinkNet().withStun()
            val (a, b) = pair(net.phone(Nat.PORT_RESTRICTED, v4(81, 2, 3, 4)), net.phone(Nat.PORT_RESTRICTED, v4(91, 5, 6, 7)))
            val fromA = async { a.links.offer(b.id) }
            val fromB = async { b.links.offer(a.id) }
            val outcomes = setOf(fromA.await(), fromB.await())
            assertEquals(setOf(LinkOutcome.UP, LinkOutcome.SUPERSEDED), outcomes)
            val lower = if (a.id < b.id) a else b
            assertEquals(LinkOutcome.UP, if (lower === a) fromA.await() else fromB.await())
            // The answering end registers its link in its own coroutine, so wait for both rather than read them.
            a.links.links.first { b.id in it }
            b.links.links.first { a.id in it }
            assertEquals(1, tries(a).count { it.outcome == LinkOutcome.UP })
            assertEquals(1, tries(b).count { it.outcome == LinkOutcome.UP })
        }

    @Test
    fun anOfferSupersededWhileItGatheredIsNeverSentAndNoneStartsWhileThePeerIsAnswered() =
        runTest {
            val net = FakeLinkNet().withStun().also { it.silentStunServer("silent.example", v4(203, 0, 113, 9)) }
            // IPv6 at both ends, so the answer still finds a path with no reflexive address from its silent STUN.
            val (a, b) =
                pair(net.phone(Nat.PORT_RESTRICTED, v4(81, 2, 3, 4), v6(1)), net.phone(Nat.PORT_RESTRICTED, v4(91, 5, 6, 7), v6(2)))
            val (low, high) = if (a.id < b.id) a to b else b to a
            // The higher id's gather waits out a STUN server that never answers; the lower id's offer lands meanwhile.
            high.stun = listOf("stun:silent.example")
            val mine = async { high.links.offer(low.id) }
            testScheduler.runCurrent()
            val theirs = async { low.links.offer(high.id) }
            assertEquals(LinkOutcome.SUPERSEDED, mine.await())
            // Its answer to the lower id still gathers: no attempt of its own starts beside it.
            assertEquals(LinkOutcome.BUSY, high.links.offer(low.id))
            assertEquals(LinkOutcome.UP, theirs.await())
            high.links.links.first { low.id in it }
            assertEquals("the superseded offer went out beside the answer", 1, high.signals)
            assertEquals(1, tries(high).count { it.outcome == LinkOutcome.UP })
        }

    @Test
    fun anOfferFromTheHigherIdThatCrossedTheLowerIdsLinkNeverCostsItThatLinkButALaterOneReplacesIt() =
        runTest {
            val net = FakeLinkNet().withStun()
            val (a, b) = pair(net.phone(Nat.PORT_RESTRICTED, v4(81, 2, 3, 4)), net.phone(Nat.PORT_RESTRICTED, v4(91, 5, 6, 7)))
            val (low, high) = if (a.id < b.id) a to b else b to a
            assertEquals(LinkOutcome.UP, low.links.offer(high.id))
            high.links.links.first { low.id in it }
            val held =
                low.links.links.value
                    .getValue(high.id)
            // The higher id's own offer, sent before it answered ours, lands once our link is up: it crossed ours.
            val late = LinkSignal(LinkSignal.PHASE_OFFER, ByteArray(LinkSignal.ID_BYTES) { 1 }, ByteArray(LinkSignal.KEY_BYTES) { 2 })
            low.links.onSignal(high.id, late)
            testScheduler.advanceTimeBy(SIG_MS)
            assertTrue("the link it crossed was replaced", low.links.links.value[high.id] === held)
            assertEquals(LinkSession.State.UP, held.state.value)
            assertEquals("an answer went out", 1, low.signals)
            // Past the crossing window, the higher id's offer is a link it lost (its end closed without a word): answered,
            // and the link it lost gives way to the new one.
            testScheduler.advanceTimeBy(DirectLinks.ANSWER_WAIT_MS)
            high.links.links.value
                .getValue(low.id)
                .close(LinkSession.EndReason.LOCAL, sayBye = false)
            high.links.links.first { low.id !in it }
            assertEquals(LinkOutcome.UP, high.links.offer(low.id, force = true))
            low.links.links.first { it[high.id] !== held && high.id in it }
        }

    @Test
    fun aContactWhosePunchesFindNoPathIsAnsweredAtMostItsEmptyBudgetAnHour() =
        runTest {
            val net = FakeLinkNet().withStun()
            val (a, b) = pair(net.phone(Nat.SYMMETRIC, v4(81, 2, 3, 4)), net.phone(Nat.SYMMETRIC, v4(91, 5, 6, 7)))
            repeat(DirectLinks.EMPTY_ANSWERS_PER_HOUR + 2) { a.links.offer(b.id, force = true) }
            assertEquals(DirectLinks.EMPTY_ANSWERS_PER_HOUR, answered(b))
            assertEquals(2, tries(b).count { it.outcome == LinkOutcome.BUDGET })
        }

    @Test
    fun aContactWhoseLinksCarryDataIsAnsweredEveryTime() =
        runTest {
            val net = FakeLinkNet().withStun()
            val (a, b) = pair(net.phone(Nat.PORT_RESTRICTED, v4(81, 2, 3, 4)), net.phone(Nat.PORT_RESTRICTED, v4(91, 5, 6, 7)))
            val links = DirectLinks.EMPTY_ANSWERS_PER_HOUR + 3
            repeat(links) {
                assertEquals(LinkOutcome.UP, a.links.offer(b.id, force = true))
                b.links.links.first { a.id in it }
                assertTrue(
                    a.links.links.value
                        .getValue(b.id)
                        .sendData(byteArrayOf(it.toByte())),
                )
                a.links.close(b.id)
                b.links.links.first { a.id !in it }
            }
            assertEquals(links, tries(b).count { it.outcome == LinkOutcome.UP })
        }

    @Test
    fun linksThatCarryNothingCountLikeFailedPunchesAndAnHourLaterAreAnsweredAgain() =
        runTest {
            val net = FakeLinkNet().withStun()
            val (a, b) = pair(net.phone(Nat.PORT_RESTRICTED, v4(81, 2, 3, 4)), net.phone(Nat.PORT_RESTRICTED, v4(91, 5, 6, 7)))
            repeat(DirectLinks.EMPTY_ANSWERS_PER_HOUR) {
                assertEquals(LinkOutcome.UP, a.links.offer(b.id, force = true))
                b.links.links.first { a.id in it }
                a.links.close(b.id)
                b.links.links.first { a.id !in it }
            }
            assertEquals(LinkOutcome.NO_ANSWER, a.links.offer(b.id, force = true))
            assertEquals(DirectLinks.EMPTY_ANSWERS_PER_HOUR, answered(b))
            assertEquals(LinkOutcome.BUDGET, tries(b).last().outcome)
            testScheduler.advanceTimeBy(60 * 60_000L)
            assertEquals(LinkOutcome.UP, a.links.offer(b.id, force = true))
        }

    @Test
    fun aClosedLinkLeavesBothEndsAndRecordsItsEnd() =
        runTest {
            val net = FakeLinkNet().withStun()
            val (a, b) = pair(net.phone(Nat.PORT_RESTRICTED, v4(81, 2, 3, 4)), net.phone(Nat.PORT_RESTRICTED, v4(91, 5, 6, 7)))
            assertEquals(LinkOutcome.UP, a.links.offer(b.id))
            b.links.links.first { a.id in it }
            a.links.close(b.id)
            a.links.links.first { it.isEmpty() }
            b.links.links.first { it.isEmpty() }
            testScheduler.advanceTimeBy(10)
            assertEquals(
                LinkSession.EndReason.LOCAL,
                a.lines
                    .filterIsInstance<FieldEvent.LinkEnd>()
                    .single()
                    .reason,
            )
            assertEquals(
                LinkSession.EndReason.BYE,
                b.lines
                    .filterIsInstance<FieldEvent.LinkEnd>()
                    .single()
                    .reason,
            )
        }

    @Test
    fun aQuietLinkReportsEachGapItSurvivedAndTheFirstItDidNot() =
        runTest {
            // NATs that forget a mapping 45 s after the last datagram out through it.
            val net = FakeLinkNet(mappingTtlMs = 45_000L, clock = { testScheduler.currentTime }).withStun()
            val (a, b) = pair(net.phone(Nat.PORT_RESTRICTED, v4(81, 2, 3, 4)), net.phone(Nat.PORT_RESTRICTED, v4(91, 5, 6, 7)))
            a.links.quiet = true
            b.links.quiet = true
            assertEquals(LinkOutcome.UP, a.links.offer(b.id))
            b.links.links.first { a.id in it }
            // With b's link up, the quiet guard is the only one left to refuse it.
            b.links.quiet = false
            assertFalse("only a quiet link is measured", b.links.measureGaps(a.id, listOf(1_000L)))
            b.links.quiet = true
            assertTrue(a.links.measureGaps(b.id, listOf(30_000L, 60_000L)))
            assertFalse("one measurement at a time", a.links.measureGaps(b.id, listOf(1_000L)))

            val done = withTimeout(10 * 60_000L) { a.links.awaitGaps() }
            val (thirty, sixty) = done
            assertEquals(30_000L, thirty.gapMs)
            assertTrue("30 s of silence is inside the mapping's life both ways", thirty.outRttMs != null && thirty.inMs != null)
            assertFalse(thirty.roamed)
            assertEquals(60_000L, sixty.gapMs)
            assertEquals("past it, the peer's NAT dropped our echo", null, sixty.outRttMs)
            assertEquals("and the request for its echo with it", null, sixty.inMs)
            // Quiet: nothing idled out, sent a keepalive or closed for silence.
            assertEquals(
                LinkSession.State.UP,
                a.links.links.value
                    .getValue(b.id)
                    .state.value,
            )
        }

    @Test
    fun aLinksStreamCarriesABulkRunThatThePeerDrains() =
        runTest {
            val net = FakeLinkNet().withStun()
            val (a, b) = pair(net.phone(Nat.PORT_RESTRICTED, v4(81, 2, 3, 4)), net.phone(Nat.ADDRESS_RESTRICTED, v4(91, 5, 6, 7)))
            assertFalse("no link up yet", a.links.measureBulk(b.id, 1))
            assertEquals(LinkOutcome.UP, a.links.offer(b.id))
            b.links.links.first { a.id in it }
            // More than the peer's window and our send buffer hold: the run ends only if the peer's end drains.
            val bytes = 1_000_000
            assertTrue(a.links.measureBulk(b.id, bytes))
            assertFalse("one measurement at a time", a.links.measureBulk(b.id, 1))
            val run =
                withTimeoutOrNull(60_000L) {
                    while (a.links.bulk().first == null) delay(100L)
                    a.links.bulk().first
                }
            assertNotNull("the run never ended: ${a.links.bulk()}", run)
            assertTrue(run!!.done)
            assertEquals(bytes.toLong(), run.bytes)
            val drained = withTimeoutOrNull(60_000L) { while (b.links.bulk().second[a.id] != bytes.toLong()) delay(100L) }
            assertNotNull("the peer drained ${b.links.bulk().second[a.id]}", drained)
            // The stream's segments are the link's data: neither end's answer came to nothing.
            assertTrue(
                b.links.links.value
                    .getValue(a.id)
                    .carriedData,
            )
        }

    // Waits for a running gap measurement to end, and returns its gaps.
    private suspend fun DirectLinks.awaitGaps(): List<DirectLinks.Gap> {
        while (gaps().second) delay(1_000L)
        return gaps().first
    }

    private companion object {
        const val SIG_MS = 300L

        // A walkie call's asks for its link (`WalkiePolicy.LINK_ASK_MS`), and enough of them for three minutes.
        const val ASK_MS = 10_000L
        const val ASKS = 18

        // Seconds an attempt is given to record its try: the punch's give-up, the answer's wait and some.
        const val PUNCH_WAIT_S = 40
    }
}
