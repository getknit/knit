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
        silenceMs: Long = LinkSession.SILENCE_MS,
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
                silenceMs = silenceMs,
            )
        val b =
            LinkSession(
                alice,
                bPort,
                LinkLane(bToA, aToB, aad),
                PunchPolicy.targets(aCands, localV6 = true, localV4 = true),
                scope,
                clock,
                silenceMs = silenceMs,
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
    fun keepalivesHoldAnIdleLinkAndAByeEndsBothEnds() =
        runTest {
            val net = FakeLinkNet()
            val pair = link(net, net.phone(Nat.PORT_RESTRICTED, v4(81, 2, 3, 4)), net.phone(Nat.PORT_RESTRICTED, v4(91, 5, 6, 7)))
            pair.a.awaitOutcome()
            pair.b.awaitOutcome()
            // Five minutes with nothing to say: the keepalives alone keep both ends up.
            testScheduler.advanceTimeBy(5 * 60_000L)
            assertEquals(State.UP, pair.a.state.value)
            assertEquals(State.UP, pair.b.state.value)
            pair.a.close()
            pair.b.state.first { it == State.CLOSED }
            assertEquals(EndReason.BYE, pair.b.endReason)
        }

    @Test
    fun aPeerThatVanishesEndsTheLinkSilent() =
        runTest {
            val net = FakeLinkNet()
            val pair = link(net, net.phone(Nat.PORT_RESTRICTED, v4(81, 2, 3, 4)), net.phone(Nat.PORT_RESTRICTED, v4(91, 5, 6, 7)))
            pair.a.awaitOutcome()
            pair.b.awaitOutcome()
            pair.b.close(sayBye = false)
            pair.a.state.first { it == State.CLOSED }
            assertEquals(EndReason.SILENT, pair.a.endReason)
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
