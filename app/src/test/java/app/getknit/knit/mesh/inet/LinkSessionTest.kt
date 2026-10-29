package app.getknit.knit.mesh.inet

import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.inet.FakeLinkNet.Nat
import app.getknit.knit.mesh.inet.LinkSession.EndReason
import app.getknit.knit.mesh.inet.LinkSession.Outcome
import app.getknit.knit.mesh.inet.LinkSession.State
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * A direct link's punch and life (ADR 2026-10.a5yj) over [FakeLinkNet], on virtual time: which NATs it gets through,
 * that it gives up in ten seconds when it cannot, and how it ends.
 */
@OptIn(ExperimentalCoroutinesApi::class) // the test scheduler's clock and advanceTimeBy
class LinkSessionTest {
    private val stunAt = byteArrayOf(198.toByte(), 51, 100, 7)
    private val alice = NodeId.derive("alice")
    private val bob = NodeId.derive("bob")

    private class Pair(
        val a: LinkSession,
        val b: LinkSession,
    )

    // Both ends gather, then start; returns the two sessions, already punching.
    private suspend fun TestScope.link(
        net: FakeLinkNet,
        aSide: LinkDatagrams,
        bSide: LinkDatagrams,
        scope: CoroutineScope = backgroundScope,
        // Both ends under a transport: only noteUse and a busy transport are use (L-7-4).
        dataIsUse: Boolean = true,
        busy: () -> Boolean = { false },
        onDataB: (ByteArray) -> Unit = {},
    ): Pair {
        net.stunServer("stun.example", stunAt)
        val clock = { testScheduler.currentTime }
        val aPort = aSide.open()!!
        val bPort = bSide.open()!!
        val stun = listOf("stun:stun.example:3478")
        val aCands = LinkGather.candidates(aPort, stun, clock)
        val bCands = LinkGather.candidates(bPort, stun, clock)
        val rng = Random(3)
        val aToB = rng.nextBytes(32)
        val bToA = rng.nextBytes(32)
        val aad = LinkLane.aad(rng.nextBytes(16), alice, bob)
        val a =
            LinkSession(
                bob,
                aPort,
                LinkLane(aToB, bToA, aad),
                PunchPolicy.targets(bCands, localV6 = true, localV4 = true),
                scope,
                clock,
                dataIsUse = dataIsUse,
                busy = busy,
            )
        val b =
            LinkSession(
                alice,
                bPort,
                LinkLane(bToA, aToB, aad),
                PunchPolicy.targets(aCands, localV6 = true, localV4 = true),
                scope,
                clock,
                dataIsUse = dataIsUse,
                busy = busy,
                onData = onDataB,
            )
        a.start()
        b.start()
        return Pair(a, b)
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

    @Test
    fun twoEndpointIndependentNatsMeetOnTheirReflexiveAddresses() =
        runTest {
            val net = FakeLinkNet()
            val pair = link(net, net.phone(Nat.PORT_RESTRICTED, v4(81, 2, 3, 4)), net.phone(Nat.PORT_RESTRICTED, v4(91, 5, 6, 7)))
            val a = pair.a.awaitOutcome()
            val b = pair.b.awaitOutcome()
            assertTrue(a is Outcome.Up && b is Outcome.Up)
            assertEquals(LinkCandidate.Kind.V4_REFLEXIVE, (a as Outcome.Up).kind)
            assertTrue("up within the fast probe phase, was ${a.ms} ms", a.ms < 2_000)
        }

    @Test
    fun anAddressRestrictedNatMeetsASymmetricOne() =
        runTest {
            val net = FakeLinkNet()
            val pair = link(net, net.phone(Nat.SYMMETRIC, v4(81, 2, 3, 4)), net.phone(Nat.ADDRESS_RESTRICTED, v4(91, 5, 6, 7)))
            assertTrue(pair.a.awaitOutcome() is Outcome.Up)
            assertTrue(pair.b.awaitOutcome() is Outcome.Up)
        }

    @Test
    fun twoSymmetricNatsGiveUpAfterTenSecondsWithNoPath() =
        runTest {
            val net = FakeLinkNet()
            val pair = link(net, net.phone(Nat.SYMMETRIC, v4(81, 2, 3, 4)), net.phone(Nat.SYMMETRIC, v4(91, 5, 6, 7)))
            val outcome = pair.a.awaitOutcome()
            assertTrue(outcome is Outcome.Failed)
            assertTrue(outcome.ms in PunchPolicy.GIVE_UP_MS..PunchPolicy.GIVE_UP_MS + 1_000)
            assertEquals(State.CLOSED, pair.a.state.value)
            assertEquals(EndReason.NO_PATH, pair.a.endReason)
        }

    @Test
    fun aPortRestrictedNatAndASymmetricOneGiveUp() =
        runTest {
            val net = FakeLinkNet()
            val pair = link(net, net.phone(Nat.SYMMETRIC, v4(81, 2, 3, 4)), net.phone(Nat.PORT_RESTRICTED, v4(91, 5, 6, 7)))
            assertTrue(pair.a.awaitOutcome() is Outcome.Failed)
            assertTrue(pair.b.awaitOutcome() is Outcome.Failed)
        }

    @Test
    fun twoIpv6FirewallsOpenBecauseBothEndsSendAtOnce() =
        runTest {
            val net = FakeLinkNet()
            // Symmetric IPv4 on both sides, so only IPv6 can carry the link.
            val pair =
                link(net, net.phone(Nat.SYMMETRIC, v4(81, 2, 3, 4), v6 = v6(1)), net.phone(Nat.SYMMETRIC, v4(91, 5, 6, 7), v6 = v6(2)))
            val a = pair.a.awaitOutcome()
            assertTrue(a is Outcome.Up)
            assertEquals(LinkCandidate.Kind.V6, (a as Outcome.Up).kind)
        }

    @Test
    fun twoPhonesBehindOneNatMeetOnTheLan() =
        runTest {
            val net = FakeLinkNet()
            val home = v4(81, 2, 3, 4)
            val pair = link(net, net.phone(Nat.SYMMETRIC, home), net.phone(Nat.SYMMETRIC, home))
            val a = pair.a.awaitOutcome()
            assertEquals(LinkCandidate.Kind.HOST, (a as Outcome.Up).kind)
        }

    @Test
    fun anIdleLinkKeepsItsPathWarmWithOneKeepaliveAndClosesAfterItsIdleWaitWithABye() =
        runTest {
            // NATs that forget a mapping 2 min after the last datagram out: the shortest idle-path life measured, which
            // the idle wait outlasts.
            val net = FakeLinkNet(mappingTtlMs = 120_000L, clock = { testScheduler.currentTime })
            val got = mutableListOf<String>()
            val pair =
                link(net, net.phone(Nat.PORT_RESTRICTED, v4(81, 2, 3, 4)), net.phone(Nat.PORT_RESTRICTED, v4(91, 5, 6, 7))) {
                    got += String(it)
                }
            pair.a.awaitOutcome()
            pair.b.awaitOutcome()
            val sent = pair.a.bytes().second
            testScheduler.advanceTimeBy(LinkSession.HOLD_KEEPALIVE_MS - 5_000L)
            assertEquals("a keepalive before its time", sent, pair.a.bytes().second)
            testScheduler.advanceTimeBy(10_000L)
            val keepalive = pair.a.bytes().second - sent
            assertTrue("no keepalive in the idle wait", keepalive > 0)
            testScheduler.advanceTimeBy(LinkSession.IDLE_MS - LinkSession.HOLD_KEEPALIVE_MS - 10_000L)
            assertEquals(State.UP, pair.a.state.value)
            assertEquals(State.UP, pair.b.state.value)
            assertEquals("more than one keepalive in the idle wait", keepalive, pair.a.bytes().second - sent)
            // Past the mappings' two minutes, the path is still open: the keepalives held them. The frame is answered (a
            // stream's ack), and it starts the idle wait over.
            assertTrue(pair.a.sendData("late in the wait".toByteArray()))
            testScheduler.advanceTimeBy(100)
            assertEquals(listOf("late in the wait"), got)
            assertTrue(pair.b.sendData(byteArrayOf(0)))
            pair.a.state.first { it == State.CLOSED }
            pair.b.state.first { it == State.CLOSED }
            // Whichever end idles out first says so with a BYE, which closes the other.
            val reasons = setOf(pair.a.endReason, pair.b.endReason)
            assertTrue("$reasons", EndReason.IDLE in reasons && reasons.all { it == EndReason.IDLE || it == EndReason.BYE })
        }

    @Test
    fun theFirstFrameAfterAQuietSpellWaitsOutItsOwnAnswer() =
        runTest {
            val net = FakeLinkNet()
            val pair = link(net, net.phone(Nat.PORT_RESTRICTED, v4(81, 2, 3, 4)), net.phone(Nat.PORT_RESTRICTED, v4(91, 5, 6, 7)))
            pair.a.awaitOutcome()
            pair.b.awaitOutcome()
            // Quiet past the silence bound since the last thing heard (the keepalives at 90 s), then a frame whose answer
            // takes a waking modem's 3 s: the question is the frame's, not the quiet's.
            testScheduler.advanceTimeBy(LinkSession.HOLD_KEEPALIVE_MS + LinkSession.SILENCE_MS + 10_000L)
            assertTrue(pair.a.sendData(byteArrayOf(1)))
            testScheduler.advanceTimeBy(3_000L)
            assertTrue(pair.b.sendData(byteArrayOf(2)))
            testScheduler.advanceTimeBy(1_000L)
            assertEquals(State.UP, pair.a.state.value)
            assertEquals(State.UP, pair.b.state.value)
        }

    @Test
    fun aLinkIsIdleSinceItsLastDataButNeverWhileHeldOrMovingAFile() =
        runTest {
            val net = FakeLinkNet()
            var moving = false
            val pair =
                link(
                    net,
                    net.phone(Nat.PORT_RESTRICTED, v4(81, 2, 3, 4)),
                    net.phone(Nat.PORT_RESTRICTED, v4(91, 5, 6, 7)),
                    busy = { moving },
                )
            pair.a.awaitOutcome()
            pair.b.awaitOutcome()
            testScheduler.advanceTimeBy(10_000L)
            assertTrue(pair.a.sendData(byteArrayOf(1)))
            val sent = testScheduler.currentTime
            testScheduler.advanceTimeBy(20_000L)
            assertEquals(sent, pair.a.idleSince())
            moving = true
            assertEquals(null, pair.a.idleSince())
            moving = false
            pair.a.holdFor(DirectLinks.HOLD_TTL_MS)
            assertEquals(null, pair.a.idleSince())
            // Its hold over, it is idle from the hold's end.
            testScheduler.advanceTimeBy(DirectLinks.HOLD_TTL_MS)
            assertEquals(sent + 20_000L + DirectLinks.HOLD_TTL_MS, pair.a.idleSince())
        }

    @Test
    fun underATransportDataAloneDoesNotKeepALinkButUseDoes() =
        runTest {
            val net = FakeLinkNet()
            val pair =
                link(
                    net,
                    net.phone(Nat.PORT_RESTRICTED, v4(81, 2, 3, 4)),
                    net.phone(Nat.PORT_RESTRICTED, v4(91, 5, 6, 7)),
                    dataIsUse = false,
                )
            pair.a.awaitOutcome()
            pair.b.awaitOutcome()
            // A transport's digests and acks: DATA every 20 s, both ways, for well past the idle wait. None of it is use.
            repeat((LinkSession.IDLE_MS / 20_000L).toInt() + 1) {
                pair.a.sendData(byteArrayOf(1))
                pair.b.sendData(byteArrayOf(2))
                testScheduler.advanceTimeBy(20_000L)
            }
            assertEquals(State.CLOSED, pair.a.state.value)
            assertFalse("a link that only kept itself in sync carried nothing", pair.a.carriedData)
        }

    @Test
    fun underATransportAFrameOrAFileIsUseAndKeepsTheLink() =
        runTest {
            val net = FakeLinkNet()
            val pair =
                link(
                    net,
                    net.phone(Nat.PORT_RESTRICTED, v4(81, 2, 3, 4)),
                    net.phone(Nat.PORT_RESTRICTED, v4(91, 5, 6, 7)),
                    dataIsUse = false,
                )
            pair.a.awaitOutcome()
            pair.b.awaitOutcome()
            // A frame each way every 30 s: its segments cross the link, and the transport notes it as use.
            repeat(4) {
                pair.a.sendData(byteArrayOf(1))
                pair.b.sendData(byteArrayOf(2))
                pair.a.noteUse()
                pair.b.noteUse()
                testScheduler.advanceTimeBy(30_000L)
            }
            assertEquals(State.UP, pair.a.state.value)
            assertTrue(pair.a.carriedData)
        }

    @Test
    fun aLinkWhoseTransportIsMovingAFileNeverIdlesOut() =
        runTest {
            val net = FakeLinkNet()
            var moving = true
            val pair =
                link(
                    net,
                    net.phone(Nat.PORT_RESTRICTED, v4(81, 2, 3, 4)),
                    net.phone(Nat.PORT_RESTRICTED, v4(91, 5, 6, 7)),
                    dataIsUse = false,
                    busy = { moving },
                )
            pair.a.awaitOutcome()
            pair.b.awaitOutcome()

            // The stream's segments, both ways every 10 s: heard, so never silent, but not use.
            suspend fun stream(ms: Long) =
                repeat((ms / 10_000L).toInt()) {
                    pair.a.sendData(byteArrayOf(1))
                    pair.b.sendData(byteArrayOf(2))
                    testScheduler.advanceTimeBy(10_000L)
                }
            // Three minutes of a file's stream, with no frame either way: the link holds.
            stream(3 * 60_000L)
            assertEquals(State.UP, pair.a.state.value)
            // The file is in, the stream goes on (acks, a digest): the idle wait starts now, and ends the link.
            moving = false
            stream(LinkSession.IDLE_MS - 20_000L)
            assertEquals(State.UP, pair.a.state.value)
            stream(30_000L)
            assertEquals(State.CLOSED, pair.a.state.value)
            // Whichever end idled out first said so with a BYE.
            assertTrue(EndReason.IDLE in setOf(pair.a.endReason, pair.b.endReason))
        }

    @Test
    fun dataKeepsALinkFromIdlingOutAndItsEndStartsTheMinute() =
        runTest {
            val net = FakeLinkNet()
            val pair = link(net, net.phone(Nat.PORT_RESTRICTED, v4(81, 2, 3, 4)), net.phone(Nat.PORT_RESTRICTED, v4(91, 5, 6, 7)))
            pair.a.awaitOutcome()
            pair.b.awaitOutcome()
            // Both ways, as a stream's data and its acks go: a sender that hears nothing back for a while is on a dead path.
            repeat(6) {
                testScheduler.advanceTimeBy(30_000L)
                assertTrue(pair.a.sendData(byteArrayOf(it.toByte())))
                assertTrue(pair.b.sendData(byteArrayOf(it.toByte())))
            }
            val lastData = testScheduler.currentTime
            assertEquals(State.UP, pair.a.state.value)
            assertEquals(State.UP, pair.b.state.value)
            pair.a.state.first { it == State.CLOSED }
            val idledAt = testScheduler.currentTime - lastData
            assertTrue("closed ${idledAt}ms after the last data", idledAt in LinkSession.IDLE_MS..LinkSession.IDLE_MS + 6_000L)
        }

    @Test
    fun aLinkThatSendsButHearsNothingBackEndsSilent() =
        runTest {
            val net = FakeLinkNet()
            val pair = link(net, net.phone(Nat.PORT_RESTRICTED, v4(81, 2, 3, 4)), net.phone(Nat.PORT_RESTRICTED, v4(91, 5, 6, 7)))
            pair.a.awaitOutcome()
            pair.b.awaitOutcome()
            pair.b.close(sayBye = false)
            // Data keeps the link from idling out, but nothing comes back: its first unanswered DATA starts the clock, and
            // the link is gone a silence bound later — long before the bound on hearing nothing at all.
            testScheduler.advanceTimeBy(LinkSession.ANSWER_GRACE_MS)
            val asked = testScheduler.currentTime
            while (pair.a.state.value == State.UP) {
                pair.a.sendData(byteArrayOf(1))
                testScheduler.advanceTimeBy(10_000L)
            }
            assertEquals(EndReason.SILENT, pair.a.endReason)
            val after = testScheduler.currentTime - asked
            assertTrue("ended ${after}ms after its first question", after <= LinkSession.SILENCE_MS + 10_000L)
        }

    @Test
    fun oneUnansweredFrameEndsTheLinkASilenceBoundAfterItWent() =
        runTest {
            val net = FakeLinkNet()
            val pair = link(net, net.phone(Nat.PORT_RESTRICTED, v4(81, 2, 3, 4)), net.phone(Nat.PORT_RESTRICTED, v4(91, 5, 6, 7)))
            pair.a.awaitOutcome()
            pair.b.awaitOutcome()
            pair.b.close(sayBye = false)
            testScheduler.advanceTimeBy(30_000L)
            assertTrue(pair.a.sendData(byteArrayOf(1)))
            val asked = testScheduler.currentTime
            pair.a.state.first { it == State.CLOSED }
            assertEquals(EndReason.SILENT, pair.a.endReason)
            val after = testScheduler.currentTime - asked
            assertTrue("ended ${after}ms after the frame", after in LinkSession.SILENCE_MS..LinkSession.SILENCE_MS + 6_000L)
        }

    @Test
    fun aHeldLinkOutlivesItsPausesOnKeepalivesThroughNatsThatForgetInTwoMinutes() =
        runTest {
            // NATs that forget a mapping 2 min after the last datagram out — the shortest idle-path life the lab measured.
            val net = FakeLinkNet(mappingTtlMs = 120_000L, clock = { testScheduler.currentTime })
            val got = mutableListOf<String>()
            val pair =
                link(net, net.phone(Nat.PORT_RESTRICTED, v4(81, 2, 3, 4)), net.phone(Nat.PORT_RESTRICTED, v4(91, 5, 6, 7))) {
                    got += String(it)
                }
            pair.a.awaitOutcome()
            pair.b.awaitOutcome()
            pair.a.hold(true)
            pair.b.hold(true)
            testScheduler.advanceTimeBy(10 * 60_000L)
            assertEquals(State.UP, pair.a.state.value)
            assertEquals(State.UP, pair.b.state.value)
            assertTrue(pair.a.sendData("after ten minutes".toByteArray()))
            testScheduler.advanceTimeBy(100)
            assertEquals("the path is still open: the keepalives held both mappings", listOf("after ten minutes"), got)
            // Released, it idles out a minute later.
            pair.a.hold(false)
            pair.b.hold(false)
            pair.a.state.first { it == State.CLOSED }
            pair.b.state.first { it == State.CLOSED }
        }

    @Test
    fun aTimedHoldOutlivesTheIdleCloseAndOnceItEndsTheLinkIdlesOutCountedFromThatEnd() =
        runTest {
            val net = FakeLinkNet()
            val pair = link(net, net.phone(Nat.PORT_RESTRICTED, v4(81, 2, 3, 4)), net.phone(Nat.PORT_RESTRICTED, v4(91, 5, 6, 7)))
            pair.a.awaitOutcome()
            pair.b.awaitOutcome()
            // A live session's hold, renewed every 10 s for three minutes with nothing sent but the held link's keepalives:
            // past three idle closes, neither end lets go.
            repeat(18) {
                pair.a.holdFor(DirectLinks.HOLD_TTL_MS)
                pair.b.holdFor(DirectLinks.HOLD_TTL_MS)
                testScheduler.advanceTimeBy(10_000L)
            }
            assertEquals(State.UP, pair.a.state.value)
            assertEquals(State.UP, pair.b.state.value)
            // The renewals stop. A shorter one never cuts a longer hold short.
            pair.a.holdFor(1_000L)
            val holdEnd = testScheduler.currentTime - 10_000L + DirectLinks.HOLD_TTL_MS
            val bound = DirectLinks.HOLD_TTL_MS + LinkSession.IDLE_MS + 30_000L
            val closed = withTimeoutOrNull(bound) { pair.a.state.first { it == State.CLOSED } }
            assertNotNull("still ${pair.a.state.value}, held past its renewals", closed)
            val past = testScheduler.currentTime - holdEnd
            assertTrue("closed ${past}ms past the hold's end", past in LinkSession.IDLE_MS..LinkSession.IDLE_MS + 6_000L)
            // Idle, not silent: the silence counts from the hold's end too, so the last keepalive heard before it is not
            // read as a dead path.
            val ends = setOf(pair.a.endReason, pair.b.endReason)
            assertTrue("$ends", EndReason.IDLE in ends && EndReason.SILENT !in ends)
        }

    @Test
    fun aHeldLinkWhosePeerVanishesEndsSilent() =
        runTest {
            val net = FakeLinkNet()
            val pair = link(net, net.phone(Nat.PORT_RESTRICTED, v4(81, 2, 3, 4)), net.phone(Nat.PORT_RESTRICTED, v4(91, 5, 6, 7)))
            pair.a.awaitOutcome()
            pair.b.awaitOutcome()
            pair.a.hold(true)
            val vanishedAt = testScheduler.currentTime
            pair.b.close(sayBye = false)
            pair.a.state.first { it == State.CLOSED }
            assertEquals(EndReason.SILENT, pair.a.endReason)
            assertTrue(testScheduler.currentTime - vanishedAt <= LinkSession.HOLD_SILENCE_MS + 6_000L)
        }

    @Test
    fun anEchoAskedForPastThePeersMappingLifeArrivesFromItsNewPortAndTheLinkFollowsIt() =
        runTest {
            // b's NAT forgets its mapping 15 s after b last sent; a sits on a public address that filters nothing. Every
            // wait stays inside the link's own idle minute.
            val net = FakeLinkNet(mappingTtlMs = 15_000L, clock = { testScheduler.currentTime })
            val pair = link(net, net.phone(Nat.NONE, v4(81, 2, 3, 4)), net.phone(Nat.PORT_RESTRICTED, v4(91, 5, 6, 7)))
            pair.a.awaitOutcome()
            pair.b.awaitOutcome()
            val inside = checkNotNull(pair.a.echoAfter(5_000L, graceMs = 5_000L)) { "5 s is inside b's mapping life" }
            assertFalse("same mapping, same port", inside.second)
            // 30 s of silence from b: its next datagram leaves on a fresh mapping, from a new port, which a still admits.
            val (afterMs, roamed) = checkNotNull(pair.a.echoAfter(30_000L, graceMs = 5_000L)) { "a filters nothing" }
            assertTrue("arrived ${afterMs}ms after the ask", afterMs >= 30_000L)
            assertTrue("from the new port", roamed)
            // a now sends to where b's echo came from, so the link goes on.
            assertTrue(checkNotNull(pair.a.echo(5_000L)) >= 0L)
        }

    @Test
    fun dataCrossesOnceTheLinkIsUp() =
        runTest {
            val net = FakeLinkNet()
            val got = mutableListOf<String>()
            val pair =
                link(net, net.phone(Nat.PORT_RESTRICTED, v4(81, 2, 3, 4)), net.phone(Nat.ADDRESS_RESTRICTED, v4(91, 5, 6, 7))) {
                    got += String(it)
                }
            pair.a.awaitOutcome()
            pair.b.awaitOutcome()
            assertTrue(pair.a.sendData("hi".toByteArray()))
            testScheduler.advanceTimeBy(100)
            assertEquals(listOf("hi"), got)
        }
}
