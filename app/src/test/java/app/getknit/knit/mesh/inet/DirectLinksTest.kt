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
        var sendRefused = false
        var queued: Long? = null
        var busy = false // a transport moving a file over the link
        var metered = false // this phone's network
        var radio = false // a radio links it to the other phone
        val sent = mutableListOf<LinkSignal>() // every signal it handed the spools
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

                    override fun queuedAhead(peerId: String) = me.queued

                    override suspend fun send(
                        peerId: String,
                        signal: LinkSignal,
                    ): Boolean {
                        if (!me.signalOk || me.sendRefused) return false
                        me.signals++
                        me.sent += signal
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
                    metered = { me.metered },
                    radioLinked = { me.radio },
                    clock = { testScheduler.currentTime },
                    linkBusy = { me.busy },
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

    private fun ends(phone: Phone) = phone.lines.filterIsInstance<FieldEvent.LinkEnd>()

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

    // --- Beside a radio, a link only free (L-2-7, L-7-11, #236) ---

    @Test
    fun aLinkIsFreeAtBothEndsOnlyWhenNeitherEndsNetworkIsMeteredAndEachSignalSaysWhich() =
        runTest {
            val net = FakeLinkNet().withStun()
            val (a, b) = pair(net.phone(Nat.PORT_RESTRICTED, v4(81, 2, 3, 4)), net.phone(Nat.ADDRESS_RESTRICTED, v4(91, 5, 6, 7)))
            assertEquals(LinkOutcome.UP, a.links.offer(b.id))
            b.links.links.first { a.id in it }
            assertTrue(a.links.freeLinkTo(b.id) && b.links.freeLinkTo(a.id))
            assertEquals(listOf(false), (a.sent + b.sent).map { it.m }.distinct())
        }

    @Test
    fun withNoRadioBetweenThemALinkToAMeteredContactIsMadeAsBeforeButIsNotFree() =
        runTest {
            val net = FakeLinkNet().withStun()
            val (a, b) = pair(net.phone(Nat.PORT_RESTRICTED, v4(81, 2, 3, 4)), net.phone(Nat.ADDRESS_RESTRICTED, v4(91, 5, 6, 7)))
            b.metered = true
            assertEquals(LinkOutcome.UP, a.links.offer(b.id))
            b.links.links.first { a.id in it }
            assertFalse(a.links.freeLinkTo(b.id))
            assertFalse(b.links.freeLinkTo(a.id))
            assertEquals(listOf(LinkSignal.PHASE_ANSWER to true), b.sent.map { it.ph to it.m })
            // An attachment's raise finds a link up that is not free: it raises no other, and the radio carries the file.
            assertFalse(a.links.raiseBulk(b.id))
            assertEquals(1, a.signals)
        }

    @Test
    fun besideARadioAMeteredContactDeclinesWithMAtOnceAndItsLinksStayOnForAnythingElse() =
        runTest {
            val net = FakeLinkNet().withStun()
            val home = v4(81, 2, 3, 4)
            val away = v4(91, 5, 6, 7)
            val (a, b) = pair(net.phone(Nat.PORT_RESTRICTED, home), net.phone(Nat.PORT_RESTRICTED, away))
            a.radio = true
            b.radio = true
            b.metered = true
            val started = testScheduler.currentTime
            assertEquals(LinkOutcome.METERED, a.links.offer(b.id, why = LinkWhy.BULK))
            assertTrue("declined only after the answer wait", testScheduler.currentTime - started < DirectLinks.ANSWER_WAIT_MS)
            assertEquals(LinkSignal.PHASE_DECLINE to true, b.sent.single().let { it.ph to it.m })
            assertEquals(LinkOutcome.METERED to LinkWhy.BULK, tries(a).single().let { it.outcome to it.why })
            assertEquals(LinkOutcome.METERED, tries(b).single().outcome)
            assertNotNull(a.links.meteredAt(b.id))
            assertEquals("not a decline: its links are on", null, a.links.declinedAt(b.id))
            assertEquals("nothing punched", 0 to 0, net.sentOut(home) to net.sentOut(away))
            // The radio gone, its links answer as before.
            a.radio = false
            b.radio = false
            assertEquals(LinkOutcome.UP, a.links.offer(b.id))
            assertFalse(a.links.freeLinkTo(b.id))
        }

    @Test
    fun besideARadioAnOffererDropsAMeteredAnswerBeforeItsPunch() =
        runTest {
            val net = FakeLinkNet().withStun()
            val home = v4(81, 2, 3, 4)
            val (a, b) = pair(net.phone(Nat.PORT_RESTRICTED, home), net.phone(Nat.PORT_RESTRICTED, v4(91, 5, 6, 7)))
            // Only the offerer sees the radio yet: the contact answers, metered, and the offerer stops there.
            a.radio = true
            b.metered = true
            assertEquals(LinkOutcome.METERED, a.links.offer(b.id))
            assertNotNull(a.links.meteredAt(b.id))
            assertEquals("no probe left this end", 0, net.sentOut(home))
            assertTrue(b.id !in a.links.links.value)
        }

    @Test
    fun besideARadioAnOfferFromAMeteredNetworkEndsBeforeItsPunchAndBlamesNoContact() =
        runTest {
            val net = FakeLinkNet().withStun()
            val home = v4(81, 2, 3, 4)
            val (a, b) = pair(net.phone(Nat.PORT_RESTRICTED, home), net.phone(Nat.PORT_RESTRICTED, v4(91, 5, 6, 7)))
            a.radio = true
            a.metered = true
            // Refused before it gathers, a forced try too: nothing is signed, and the demand rests the contact.
            assertEquals(LinkOutcome.METERED, a.links.offer(b.id, force = true))
            assertEquals(LinkOutcome.METERED, a.links.offer(b.id, why = LinkWhy.WANT))
            assertTrue(a.sent.isEmpty())
            assertEquals(null, a.links.meteredAt(b.id))
            assertEquals(0, net.sentOut(home))
            // Off the radio, the same network offers as before.
            a.radio = false
            assertEquals(LinkOutcome.UP, a.links.offer(b.id))
            assertEquals(listOf(true), a.sent.map { it.m })
        }

    @Test
    fun aBulkRaiseStartsOneFreeLinkAndRefusesWhileMeteredOrSoonAfterAMeteredRefusal() =
        runTest {
            val net = FakeLinkNet().withStun()
            val (a, b) = pair(net.phone(Nat.PORT_RESTRICTED, v4(81, 2, 3, 4)), net.phone(Nat.PORT_RESTRICTED, v4(91, 5, 6, 7)))
            a.radio = true
            b.radio = true
            a.metered = true
            assertFalse("this phone metered", a.links.raiseBulk(b.id))
            assertTrue("the pair qualifies, whatever this end's network: an asker waits on it", a.links.mayRaiseBulk(b.id))
            a.metered = false
            a.qualifies = false
            assertFalse("not a contact a link may be offered", a.links.raiseBulk(b.id))
            assertFalse(a.links.mayRaiseBulk(b.id))
            a.qualifies = true
            assertEquals(0, a.signals)
            assertFalse(a.links.bulkUnderWay(b.id))

            b.metered = true
            assertTrue(a.links.raiseBulk(b.id))
            assertTrue("under way before the attempt is placed", a.links.bulkUnderWay(b.id))
            assertTrue("one attempt under way", a.links.raiseBulk(b.id))
            testScheduler.advanceTimeBy(DirectLinks.ANSWER_WAIT_MS)
            assertEquals(1, a.signals)
            assertEquals(LinkOutcome.METERED to LinkWhy.BULK, tries(a).single().let { it.outcome to it.why })
            assertFalse("ended with no link", a.links.bulkUnderWay(b.id))
            assertFalse("a burst sends one offer", a.links.raiseBulk(b.id))

            b.metered = false
            testScheduler.advanceTimeBy(DirectLinks.METERED_RETRY_MS)
            assertTrue(a.links.raiseBulk(b.id))
            a.links.links.first { b.id in it }
            assertTrue(a.links.freeLinkTo(b.id))
            assertEquals(null, a.links.meteredAt(b.id))
            assertTrue("up and free: no new offer", a.links.raiseBulk(b.id))
            assertEquals(2, a.signals)
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
            assertTrue("declined only after the answer wait", testScheduler.currentTime - started < DirectLinks.ANSWER_WAIT_MS)
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
    fun anOfferNoSpoolTookIsOnTheRecordWithTheQueueItMetAndEveryOfferSaysItsQueue() =
        runTest {
            val net = FakeLinkNet().withStun()
            val (a, b) = pair(net.phone(Nat.PORT_RESTRICTED, v4(81, 2, 3, 4)), net.phone(Nat.PORT_RESTRICTED, v4(91, 5, 6, 7)))
            // An upload fills the spool socket: the lane is there, the offer gathers, and the spool refuses it at the send.
            a.sendRefused = true
            a.queued = 70_000L
            assertEquals(LinkOutcome.NO_SIG, a.links.offer(b.id, why = LinkWhy.FILE))
            val refused = tries(a).single()
            assertEquals(LinkOutcome.NO_SIG to 70_000L, refused.outcome to refused.queuedBytes)
            assertEquals(LinkWhy.FILE, refused.why)
            // The queue drained: the next offer goes, and its line says what it waited behind.
            a.sendRefused = false
            a.queued = 1_200L
            assertEquals(LinkOutcome.UP, a.links.offer(b.id))
            assertEquals(1_200L, tries(a).last().queuedBytes)
            assertNotNull(tries(a).last().sentMs)
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
                b.links.links.first { ids -> a.id in ids }
                assertTrue(
                    a.links.links.value
                        .getValue(b.id)
                        .sendData(byteArrayOf(it.toByte())),
                )
                a.links.close(b.id)
                b.links.links.first { ids -> a.id !in ids }
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
                b.links.links.first { ids -> a.id in ids }
                a.links.close(b.id)
                b.links.links.first { ids -> a.id !in ids }
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
    fun aMoveToAnotherNetworkClosesTheLinkLeftOnTheOldOneAndAFileItCutIsOfferedForAtOnce() =
        runTest {
            val net = FakeLinkNet().withStun()
            val aNet = net.phone(Nat.PORT_RESTRICTED, v4(81, 2, 3, 4))
            val (a, b) = pair(aNet, net.phone(Nat.PORT_RESTRICTED, v4(91, 5, 6, 7)))
            assertEquals(LinkOutcome.UP, a.links.offer(b.id))
            b.links.links.first { a.id in it }
            // A route change that leaves the link's network the default one closes nothing.
            a.links.onRouteChanged()
            testScheduler.advanceTimeBy(10)
            assertTrue(b.id in a.links.links.value)

            a.busy = true
            aNet.move(Nat.PORT_RESTRICTED, v4(82, 2, 3, 4))
            a.links.onRouteChanged()
            a.links.links.first { it.isEmpty() }
            testScheduler.runCurrent()
            assertEquals(LinkSession.EndReason.ROUTE, ends(a).single().reason)
            // It had no chance at the file it was moving: not counted as up, and no backoff on the new network.
            assertEquals(null, a.links.lastUpAt(b.id))
            // Its BYE went out on the network the phone left, so Bob still holds his end; the next offer replaces it.
            assertTrue(a.id in b.links.links.value)
            assertEquals(LinkOutcome.UP, a.links.offer(b.id))
            b.links.links.first { a.id in it && it.getValue(a.id).state.value == LinkSession.State.UP }

            // The control: a link the move closes with no file crossing it had its chance, and counts.
            a.busy = false
            aNet.move(Nat.PORT_RESTRICTED, v4(83, 2, 3, 4))
            a.links.onRouteChanged()
            a.links.links.first { it.isEmpty() }
            testScheduler.runCurrent()
            assertEquals(LinkSession.EndReason.ROUTE, ends(a).last().reason)
            assertEquals(testScheduler.currentTime, a.links.lastUpAt(b.id))
        }

    @Test
    fun aLinkThatGoesSilentMidFileIsOfferedForAgainAfterABackoffThatGrowsWhileLinksKeepDyingSo() =
        runTest {
            val net = FakeLinkNet().withStun()
            val bNet = net.phone(Nat.PORT_RESTRICTED, v4(91, 5, 6, 7))
            val (a, b) = pair(net.phone(Nat.PORT_RESTRICTED, v4(81, 2, 3, 4)), bNet)
            var moves = 0

            // A link up with a file crossing it, whose path goes dark under it: Alice's end goes silent mid-file. Bob then
            // moves on, so the next offer finds a path.
            suspend fun dieMidFile() {
                a.busy = true
                assertEquals(LinkOutcome.UP, a.links.offer(b.id))
                bNet.darkAfter(0)
                a.links.links.first { it.isEmpty() }
                testScheduler.runCurrent()
                assertEquals(LinkSession.EndReason.SILENT, ends(a).last().reason)
                bNet.move(Nat.PORT_RESTRICTED, v4(92 + moves++, 5, 6, 7))
                b.links.onRouteChanged()
                b.links.links.first { it.isEmpty() }
            }

            dieMidFile()
            assertEquals(null, a.links.lastUpAt(b.id))
            assertEquals(LinkOutcome.BACKOFF, a.links.offer(b.id))
            testScheduler.advanceTimeBy(PunchPolicy.backoffMs(1))
            dieMidFile()
            // Two in a row: the second backoff is the doubled one.
            testScheduler.advanceTimeBy(PunchPolicy.backoffMs(1))
            assertEquals(LinkOutcome.BACKOFF, a.links.offer(b.id))
            testScheduler.advanceTimeBy(PunchPolicy.backoffMs(2) - PunchPolicy.backoffMs(1))

            // A link that ends once its file is through starts the count over, and is counted as up.
            assertEquals(LinkOutcome.UP, a.links.offer(b.id))
            a.busy = false
            a.links.close(b.id)
            a.links.links.first { it.isEmpty() }
            testScheduler.runCurrent()
            assertEquals(testScheduler.currentTime, a.links.lastUpAt(b.id))
            dieMidFile()
            testScheduler.advanceTimeBy(PunchPolicy.backoffMs(1))
            assertEquals(LinkOutcome.UP, a.links.offer(b.id))
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
