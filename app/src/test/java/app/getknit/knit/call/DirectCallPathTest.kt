package app.getknit.knit.call

import app.getknit.knit.fieldlog.FieldEvent
import app.getknit.knit.fieldlog.FieldRecorder
import app.getknit.knit.mesh.protocol.CallPayload
import app.getknit.knit.mesh.protocol.DirectInvite
import app.getknit.knit.transfer.DirectWifiLease
import app.getknit.knit.transfer.FakeDirectWifi
import app.getknit.knit.transfer.SideLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import java.util.Collections

/**
 * Two [DirectCallPath]s over loopback (§3.10): [FakeDirectWifi] "forms" every group on 127.0.0.1, so the lane is a
 * real socket pair and its seal, framing and timers run for real, on short [TIMINGS]. Real time, like
 * `TransferManagerTest`: the legs block on sockets.
 */
class DirectCallPathTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private class Side(
        name: String,
        scope: CoroutineScope,
        timings: DirectTimings = TIMINGS,
    ) {
        val log = SideLog(name)
        val wifi = FakeDirectWifi(log)
        val lease = DirectWifiLease(wifi)
        val lines = mutableListOf<String>()
        val field: MutableList<FieldEvent> = Collections.synchronizedList(mutableListOf())

        @Volatile var enabled = true
        val path =
            DirectCallPath(
                leaseOf = { lease },
                scope = scope,
                nearby = { true },
                capable = { true },
                mayJoinWhileRinging = { true },
                enabled = { enabled },
                timings = timings,
                log = { synchronized(lines) { lines += it } },
                field = FieldRecorder { field += it },
            )

        fun radioCalls(what: String): Int = log.events.count { it == what }

        /** The field log's leg lines; written after the radio goes back, so a test waits for them, not the release. */
        fun legs(): List<FieldEvent.CallDirectLeg> = synchronized(field) { field.filterIsInstance<FieldEvent.CallDirectLeg>() }
    }

    private val alice = Side("alice", scope)
    private val bob = Side("bob", scope)

    @After
    fun tearDown() = scope.cancel()

    private fun invite(secret: Byte = 1): DirectInvite =
        DirectInvite(
            s = "DIRECT-kn-abcdefgh",
            p = "abcdefghijklmnopqrstuvwx",
            o = FakeDirectWifi.freeLoopbackPort(),
            k = Base64.getEncoder().encodeToString(ByteArray(DIRECT_SECRET_BYTES) { secret }),
        )

    private suspend fun until(
        what: String,
        timeoutMs: Long = WAIT_MS,
        check: () -> Boolean,
    ) = withTimeout(timeoutMs) {
        while (!check()) delay(POLL_MS)
    }.also { assertTrue(what, check()) }

    @Test
    fun theLaneCarriesBothWaysAndReplaysWhatWasQueuedBeforeItCameUp() =
        runBlocking {
            val invite = invite()
            val atAlice = scope.async(start = CoroutineStart.UNDISPATCHED) { alice.path.inbound.first() }
            val atBob = scope.async(start = CoroutineStart.UNDISPATCHED) { bob.path.inbound.first() }
            bob.path.join(ID, ALICE, invite)
            // Queued before there is any lane: the RINGING the mesh carried while the radio changed hands.
            bob.path.mirror(ID, CallPayload(id = ID, ph = CallPayload.PHASE_RINGING, dx = CallPayload.DIRECT_JOINING))
            alice.path.host(ID, BOB, invite)
            assertTrue(alice.path.pending(ID))

            val ringing = withTimeout(WAIT_MS) { atAlice.await() } as LaneEvent.Signal
            assertEquals(BOB, ringing.peerId)
            assertEquals(CallPayload.PHASE_RINGING, ringing.payload.ph)
            until("both legs up") { !alice.path.pending(ID) && !bob.path.pending(ID) }
            assertNotNull(alice.path.link.value)
            assertNotNull(bob.path.link.value)

            alice.path.candidates(ID, listOf(GROUP_CAND))
            val cands = withTimeout(WAIT_MS) { atBob.await() } as LaneEvent.Candidates
            assertEquals(ALICE, cands.peerId)
            assertEquals(listOf(GROUP_CAND), cands.lines)
            // The joiner took an IPv4 lease: libwebrtc never gathers on an IPv6 link-local.
            assertEquals(listOf(true), bob.wifi.joinIpv4)
        }

    @Test
    fun anOfferNeverRidesTheLane() =
        runBlocking {
            val invite = invite()
            bob.path.join(ID, ALICE, invite)
            alice.path.host(ID, BOB, invite)
            until("both legs up") { !alice.path.pending(ID) && !bob.path.pending(ID) }
            val atAlice = scope.async(start = CoroutineStart.UNDISPATCHED) { alice.path.inbound.first() }
            bob.path.mirror(ID, CallPayload(id = ID, ph = CallPayload.PHASE_OFFER, sdp = "v=0"))
            bob.path.mirror(ID, CallPayload(id = ID, ph = CallPayload.PHASE_ANSWER, sdp = "v=0"))
            val first = withTimeout(WAIT_MS) { atAlice.await() } as LaneEvent.Signal
            assertEquals(CallPayload.PHASE_ANSWER, first.payload.ph)
        }

    @Test
    fun anEndSendsItsFrameFirstThenBothRadiosGoBackOnce() =
        runBlocking {
            val invite = invite()
            bob.path.join(ID, ALICE, invite)
            alice.path.host(ID, BOB, invite)
            until("both legs up") { !alice.path.pending(ID) && !bob.path.pending(ID) }
            val atBob = scope.async(start = CoroutineStart.UNDISPATCHED) { bob.path.inbound.first() }
            alice.path.end(ID, CallPayload(id = ID, ph = CallPayload.PHASE_END, rsn = CallPayload.REASON_USER))
            val told = withTimeout(WAIT_MS) { atBob.await() } as LaneEvent.Signal
            assertEquals(CallPayload.PHASE_END, told.payload.ph)
            until("both released") { alice.radioCalls("release") == 1 && bob.radioCalls("release") == 1 }
            assertNull(alice.lease.owner)
            assertNull(bob.lease.owner)
            assertNull(alice.path.link.value)
            // Bob's own end, when his call hears the END, finds nothing left to give back.
            bob.path.end(ID, null)
            delay(SETTLE_MS)
            assertEquals(1, bob.radioCalls("release"))
        }

    @Test
    fun aHostNobodyJoinsGivesTheRadioBack() =
        runBlocking {
            alice.path.host(ID, BOB, invite())
            until("released", TIMINGS.hostWindowMs + WAIT_MS) { alice.radioCalls("release") == 1 }
            assertFalse(alice.path.pending(ID))
            assertNull(alice.lease.owner)
            // Its field-log line: the group came up, no lane ever did.
            until("written") { alice.legs().isNotEmpty() }
            val leg = alice.legs().single()
            assertEquals(DirectLegEnd.NO_LANE, leg.end)
            assertTrue(leg.hosting)
            assertNotNull(leg.groupMs)
            assertNull(leg.laneMs)
        }

    @Test
    fun aJoinThatNeverFormsRetriesThenGivesUp() =
        runBlocking {
            bob.wifi.joinFailures = Int.MAX_VALUE
            bob.path.join(ID, ALICE, invite())
            until("released", TIMINGS.joinWindowMs + WAIT_MS) { bob.radioCalls("release") == 1 }
            assertTrue(bob.radioCalls("join") > 1)
            assertFalse(bob.path.pending(ID))
            until("written") { bob.legs().isNotEmpty() }
            val leg = bob.legs().single()
            assertEquals(DirectLegEnd.RADIO, leg.end)
            assertEquals(bob.radioCalls("join"), leg.joinTries)
            assertNull(leg.groupMs)
        }

    @Test
    fun aLegItsCallEndsIsTheCallsAndThePeersLegHearsItsBye() =
        runBlocking {
            // A flush with all the time it needs: the bye always leaves before the sockets close, so Bob's lane reads
            // it rather than the close behind it (the shipped flush is a best effort under 300 ms). And no silence
            // window a stalled runner could open first, which would end either lane before the call does.
            val patient = TIMINGS.copy(flushMs = WAIT_MS, silenceMs = WAIT_MS)
            val host = Side("alice", scope, patient)
            val joiner = Side("bob", scope, patient)
            val invite = invite()
            joiner.path.join(ID, ALICE, invite)
            host.path.host(ID, BOB, invite)
            until("both legs up") { !host.path.pending(ID) && !joiner.path.pending(ID) }
            host.path.end(ID, null)
            until("both written") { host.legs().isNotEmpty() && joiner.legs().isNotEmpty() }
            val hosted = host.legs().single()
            assertEquals(DirectLegEnd.CALL, hosted.end)
            assertNotNull(hosted.groupMs)
            assertNotNull(hosted.laneMs)
            assertEquals(DirectLegEnd.BYE, joiner.legs().single().end)
            assertFalse(joiner.legs().single().hosting)
        }

    @Test
    fun anEndDuringTheGraceNeverTouchesTheRadio() =
        runBlocking {
            val slow = Side("carol", scope)
            val graceful =
                DirectCallPath(
                    leaseOf = { slow.lease },
                    scope = scope,
                    nearby = { true },
                    capable = { true },
                    mayJoinWhileRinging = { true },
                    timings = TIMINGS.copy(graceMs = 500L),
                )
            graceful.join(ID, ALICE, invite())
            graceful.end(ID, null)
            delay(1_000L)
            assertEquals(0, slow.radioCalls("join"))
            assertEquals(0, slow.radioCalls("release"))
            assertNull(slow.lease.owner)
        }

    @Test
    fun aLostGroupEndsBothLegsAndGivesBothRadiosBack() =
        runBlocking {
            val invite = invite()
            bob.path.join(ID, ALICE, invite)
            alice.path.host(ID, BOB, invite)
            until("both legs up") { !alice.path.pending(ID) && !bob.path.pending(ID) }
            assertTrue(alice.path.dropGroup())
            until("both released") { alice.radioCalls("release") == 1 && bob.radioCalls("release") == 1 }
            assertNull(alice.lease.owner)
            assertNull(bob.lease.owner)
        }

    @Test
    fun aJoinerWithoutTheSecretIsNeverAdmitted() =
        runBlocking {
            val invite = invite(secret = 1)
            val impostor = invite.copy(k = invite(secret = 2).k)
            alice.path.host(ID, BOB, invite)
            bob.path.join(ID, ALICE, impostor)
            until("both released", TIMINGS.hostWindowMs + WAIT_MS) {
                alice.radioCalls("release") == 1 && bob.radioCalls("release") == 1
            }
            assertTrue(alice.lines.none { it.contains("lane up") })
        }

    @Test
    fun theDebugSwitchKeepsBothSidesOffTheGroupUntilItIsBackOn() =
        runBlocking {
            alice.enabled = false
            bob.enabled = false
            assertNull(alice.path.invite(ID, BOB))
            assertFalse(bob.path.canJoin(ALICE, prewarm = false))
            assertTrue(synchronized(alice.lines) { alice.lines.any { "switched off" in it } })
            assertNull(alice.lease.owner)

            alice.enabled = true
            bob.enabled = true
            assertNotNull(alice.path.invite(ID, BOB))
            assertTrue(bob.path.canJoin(ALICE, prewarm = false))
        }

    @Test
    fun aCallerThatMayNotHostOffersNoLegButAsACalleeStillJoins() =
        runBlocking {
            // ADR 2026-10.w3dw: the caller hosts (C-3.10-3), and hosting takes this phone down, so it offers nothing.
            alice.wifi.cannotHost = true
            assertNull(alice.path.invite(ID, BOB))
            assertTrue(synchronized(alice.lines) { alice.lines.any { "CannotHost" in it } })
            assertTrue(alice.path.canJoin(BOB, prewarm = true))
            assertEquals(0, alice.radioCalls("host"))
        }

    @Test
    fun aRadioSomeoneElseHoldsIsNeitherJoinedNorReleased() =
        runBlocking {
            // A transfer holds the radio: the call's leg is refused, and its end gives back nothing of the transfer's.
            val transfer = bob.lease.leasedTo(DirectWifiLease.TRANSFER)
            transfer.host(
                app.getknit.knit.transfer.GroupCredentials
                    .random(),
                1_000L,
            )
            assertFalse(bob.path.canJoin(ALICE, prewarm = true))
            assertNull(bob.path.invite(ID, ALICE))
            bob.path.join(ID, ALICE, invite())
            until("given up") { !bob.path.pending(ID) }
            assertEquals(0, bob.radioCalls("join"))
            assertEquals(0, bob.radioCalls("release"))
            assertEquals(DirectWifiLease.TRANSFER, bob.lease.owner)
        }

    private companion object {
        const val ID = "call-1"
        const val ALICE = "alice"
        const val BOB = "bob"
        const val GROUP_CAND = "candidate:9 1 udp 2122260223 192.168.49.1 50000 typ host"
        const val WAIT_MS = 10_000L
        const val POLL_MS = 20L
        const val SETTLE_MS = 300L

        val TIMINGS =
            DirectTimings(
                graceMs = 0L,
                joinDelayMs = 50L,
                joinWindowMs = 1_000L,
                joinAttemptMs = 300L,
                joinRetryDelayMs = 50L,
                groupUpMs = 1_000L,
                hostWindowMs = 1_500L,
                tcpConnectMs = 500,
                tcpConnectTries = 10,
                tcpConnectRetryMs = 100L,
                handshakeMs = 2_000,
                pingMs = 100L,
                silenceMs = 800L,
                flushMs = 300L,
            )
    }
}
