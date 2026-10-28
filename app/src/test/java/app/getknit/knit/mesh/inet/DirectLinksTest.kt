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
        var qualifies = true
        var signalOk = true
        var signals = 0
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

                    override fun stunUrls() = listOf("stun:stun.example")

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

    private fun FakeLinkNet.withStun() = also { it.stunServer("stun.example", stunAt) }

    private fun tries(phone: Phone) = phone.lines.filterIsInstance<FieldEvent.LinkTry>()

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
    fun aContactWithLinksOffIsNeverAnsweredAndWaitsOutItsBackoff() =
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
            assertTrue(tries(b).isEmpty())
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
    fun aContactWhosePunchesFindNoPathIsAnsweredAtMostItsEmptyBudgetAnHour() =
        runTest {
            val net = FakeLinkNet().withStun()
            val (a, b) = pair(net.phone(Nat.SYMMETRIC, v4(81, 2, 3, 4)), net.phone(Nat.SYMMETRIC, v4(91, 5, 6, 7)))
            repeat(DirectLinks.EMPTY_ANSWERS_PER_HOUR + 2) { a.links.offer(b.id, force = true) }
            assertEquals(DirectLinks.EMPTY_ANSWERS_PER_HOUR, tries(b).size)
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
            assertEquals(DirectLinks.EMPTY_ANSWERS_PER_HOUR, tries(b).size)
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
    }
}
