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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            )
        val b =
            LinkSession(
                alice,
                bPort,
                LinkLane(bToA, aToB, aad),
                PunchPolicy.targets(aCands, localV6 = true, localV4 = true),
                scope,
                clock,
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
    fun anIdleLinkSendsNothingAndClosesAfterAMinuteWithABye() =
        runTest {
            val net = FakeLinkNet()
            val pair = link(net, net.phone(Nat.PORT_RESTRICTED, v4(81, 2, 3, 4)), net.phone(Nat.PORT_RESTRICTED, v4(91, 5, 6, 7)))
            pair.a.awaitOutcome()
            pair.b.awaitOutcome()
            val sent = pair.a.bytes().second
            testScheduler.advanceTimeBy(LinkSession.IDLE_MS - 5_000L)
            assertEquals(State.UP, pair.a.state.value)
            assertEquals("no keepalive while idle", sent, pair.a.bytes().second)
            pair.a.state.first { it == State.CLOSED }
            pair.b.state.first { it == State.CLOSED }
            // Whichever end idles out first says so with a BYE, which closes the other.
            val reasons = setOf(pair.a.endReason, pair.b.endReason)
            assertTrue("$reasons", EndReason.IDLE in reasons && reasons.all { it == EndReason.IDLE || it == EndReason.BYE })
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
            // Data keeps the link from idling out, but nothing comes back: past the silence bound it is gone.
            while (pair.a.state.value == State.UP) {
                pair.a.sendData(byteArrayOf(1))
                testScheduler.advanceTimeBy(10_000L)
            }
            assertEquals(EndReason.SILENT, pair.a.endReason)
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
