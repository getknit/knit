package app.getknit.knit.mesh.spool

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C-7.6-27 and C-7.6-28 (ADR 2026-10.3sk7) on plain JVM: [TicketRefreshPolicy] row by row over a fake clock, then
 * [TicketRefresher.runOnce] against an in-memory key store, the real [SpoolTickets] over [SpoolTicketsTest.MemoryJournal]
 * and a scripted issuer — what it asks, when, with what, what it holds, and that no log line carries a key or a ticket.
 */
class TicketRefresherTest {
    /** The key store in memory, with [TicketKeyJournal]'s replace and stale-answer rules. */
    class MemoryKeyJournal(
        private val tickets: SpoolTicketsTest.MemoryJournal? = null,
    ) : TicketKeyJournal {
        val held = MutableStateFlow<Map<String, HeldKey>>(emptyMap())
        val states = MutableStateFlow<Map<String, KeyState>>(emptyMap())
        override val keys: Flow<Map<String, HeldKey>> = held
        override val keyStates: Flow<Map<String, KeyState>> = states

        override suspend fun putKey(
            host: String,
            key: HeldKey,
        ) {
            if (held.value[host] == key) return
            held.value += host to key
            states.value -= host
        }

        override suspend fun putKeyState(
            host: String,
            key: HeldKey,
            state: KeyState,
        ) {
            if (held.value[host] == key) states.value += host to state
        }

        override suspend fun forgetHost(host: String) {
            held.value -= host
            states.value -= host
            tickets?.let { it.state.value -= host }
        }
    }

    /** An issuer that answers from a script, recording every ask and the device key it was handed. */
    private class ScriptedIssuer : TicketIssuer {
        val asks = mutableListOf<Pair<String, String>>()
        val signers = mutableListOf<DeviceSigner?>()
        var next: () -> IssuerAnswer = { IssuerAnswer.NoVerdict("unscripted") }
        val moves = mutableListOf<Pair<String, String>>()
        val audiences = mutableListOf<String>()
        val moveSigners = mutableListOf<DeviceSigner?>()
        var moveNext: () -> IssuerAnswer = { IssuerAnswer.NoVerdict("unscripted") }

        override suspend fun swap(
            origin: String,
            key: String,
            device: DeviceSigner?,
        ): IssuerAnswer {
            asks += origin to key
            signers += device
            return next()
        }

        override suspend fun swap(
            origin: String,
            key: String,
            aud: String,
            device: DeviceSigner?,
        ): IssuerAnswer {
            audiences += aud
            return swap(origin, key, device)
        }

        override suspend fun move(
            origin: String,
            key: String,
            aud: String,
            device: DeviceSigner?,
        ): IssuerAnswer {
            audiences += aud
            moves += origin to key
            moveSigners += device
            return moveNext()
        }
    }

    private var now = 1_800_000_100_000L
    private val ticketJournal = SpoolTicketsTest.MemoryJournal()
    private val tickets = SpoolTickets(ticketJournal) { now }
    private val keys = MemoryKeyJournal(ticketJournal)
    private val issuer = ScriptedIssuer()
    private val urls = MutableStateFlow(setOf(URL))
    private var online = true
    private val logs = mutableListOf<String>()

    private fun refresher(allowCleartext: Boolean = false) =
        TicketRefresher(
            keys = keys,
            tickets = tickets,
            issuer = issuer,
            urls = urls,
            online = { online },
            allowCleartext = allowCleartext,
            clock = { now },
            log = { logs += it },
        )

    private fun issued(
        aud: String = HOST,
        expS: Long = now / 1_000 + 25 * 3_600,
        endsAt: Long = now + 30 * DAY_MS,
        sigByte: Byte = 7,
    ) = IssuerAnswer.Issued(SpoolTicketsTest.ticket(aud = aud, nbf = now / 1_000, exp = expS, sigByte = sigByte).text, endsAt)

    // ── the schedule, pure ────────────────────────────────────────────────────────────────────────────

    @Test
    fun aKeyThatJustArrivedIsDueAtOnceWhateverTicketIsHeld() {
        assertEquals(0L, TicketRefreshPolicy.dueAt(KeyState.FRESH, presentableExp = null))
        assertEquals(0L, TicketRefreshPolicy.dueAt(KeyState.FRESH, presentableExp = now / 1_000 + 20 * 3_600))
    }

    @Test
    fun anOkKeyIsDueSixHoursBeforeItsTicketsExpOrAtOnceWithNoPresentableTicket() {
        val ok = KeyState(KeyAnswer.OK, notBefore = now - 1)
        val exp = now / 1_000 + 20 * 3_600
        assertEquals(exp * 1_000 - TicketRefreshPolicy.REFRESH_MARGIN_MS, TicketRefreshPolicy.dueAt(ok, exp))
        assertEquals(now - 1, TicketRefreshPolicy.dueAt(ok, presentableExp = null))
    }

    @Test
    fun theHourFloorAfterA200HoldsEvenWhenTheTicketIsCappedShort() {
        // An account a day from its end gets an exp capped at that end: inside the 6 h margin from the start, so
        // without the floor the phone would ask again the moment the answer landed.
        val issued = IssuerAnswer.Issued("knit-ticket:v1:x", now + 3_600_000)
        val after = TicketRefreshPolicy.afterAnswer(KeyState.FRESH, issued, held = true, now = now)
        assertEquals(KeyAnswer.OK, after.answer)
        assertEquals(now + TicketRefreshPolicy.OK_FLOOR_MS, TicketRefreshPolicy.dueAt(after, presentableExp = now / 1_000 + 3_600))
    }

    @Test
    fun anUnknownKeyIsNeverAskedAgain() {
        val after = TicketRefreshPolicy.afterAnswer(KeyState(KeyAnswer.OK, endsAt = 5), IssuerAnswer.UnknownKey, held = false, now = now)
        assertEquals(KeyAnswer.UNKNOWN_KEY, after.answer)
        assertEquals(5L, after.endsAt)
        assertNull(TicketRefreshPolicy.dueAt(after, presentableExp = null))
    }

    @Test
    fun anEndedAccountIsRecordedAndAskedAgainNoSoonerThanADayLater() {
        val after = TicketRefreshPolicy.afterAnswer(KeyState.FRESH, IssuerAnswer.Ended(now - DAY_MS), held = false, now = now)
        assertEquals(KeyAnswer.ENDED, after.answer)
        assertEquals(now - DAY_MS, after.endsAt)
        assertEquals(now + TicketRefreshPolicy.ENDED_RETRY_MS, TicketRefreshPolicy.dueAt(after, presentableExp = null))
    }

    @Test
    fun noVerdictBacksOffFromAMinuteDoublingToAnHourAndKeepsTheLastVerdict() {
        assertEquals(listOf(1L, 2, 4, 8, 16, 32, 60, 60, 60).map { it * 60_000 }, (1..9).map(TicketRefreshPolicy::backoff))
        var state = KeyState(KeyAnswer.OK, endsAt = 9)
        val waits = mutableListOf<Long>()
        repeat(8) {
            state = TicketRefreshPolicy.afterAnswer(state, IssuerAnswer.NoVerdict("timeout"), held = false, now = now)
            waits += state.notBefore - now
        }
        assertEquals(listOf(1L, 2, 4, 8, 16, 32, 60, 60).map { it * 60_000 }, waits)
        assertEquals(KeyAnswer.OK, state.answer)
        assertEquals(9L, state.endsAt)
        val recovered = TicketRefreshPolicy.afterAnswer(state, IssuerAnswer.Issued("t", 10), held = true, now = now)
        assertEquals(0, recovered.failures)
    }

    @Test
    fun a200WhoseTicketWasNotHeldIsNoVerdictButWaitsOutTheFloor() {
        // Contract revision 1: asking again at once would fetch the same unusable ticket, and a free key would loop.
        val after = TicketRefreshPolicy.afterAnswer(KeyState.FRESH, IssuerAnswer.Issued("t", 10), held = false, now = now)
        assertNull(after.answer)
        assertEquals(1, after.failures)
        assertEquals(now + TicketRefreshPolicy.OK_FLOOR_MS, TicketRefreshPolicy.dueAt(after, null))
    }

    // ── the refresher, against the stores ─────────────────────────────────────────────────────────────

    @Test
    fun aNewKeyIsAskedAtOnceAndItsTicketIsHeldForTheRelaysHost() =
        runTest {
            keys.putKey(HOST, HeldKey(ISSUER, KEY))
            issuer.next = { issued() }
            val next = refresher().runOnce()
            assertEquals(listOf(ISSUER to KEY), issuer.asks)
            assertTrue(tickets.presentable(URL) != null)
            assertEquals(
                KeyAnswer.OK,
                keys.states.value
                    .getValue(HOST)
                    .answer,
            )
            assertEquals(
                now + 30 * DAY_MS,
                keys.states.value
                    .getValue(HOST)
                    .endsAt,
            )
            // Next: 6 h before a 25 h ticket's exp.
            assertEquals(now + 19 * 3_600_000L, next)
        }

    @Test
    fun nothingIsAskedBeforeItIsDueAndAgainOnceTheTicketIsSixHoursFromExp() =
        runTest {
            keys.putKey(HOST, HeldKey(ISSUER, KEY))
            issuer.next = { issued(sigByte = 1) }
            val r = refresher()
            r.runOnce()
            now += 18 * 3_600_000L
            r.runOnce()
            assertEquals(1, issuer.asks.size)
            now += 3_600_000L + 1
            issuer.next = { issued(sigByte = 2) }
            r.runOnce()
            assertEquals(2, issuer.asks.size)
        }

    @Test
    fun aRefusedTicketIsReplacedButNoSoonerThanTheHourFloor() =
        runTest {
            keys.putKey(HOST, HeldKey(ISSUER, KEY))
            issuer.next = { issued(sigByte = 1) }
            val r = refresher()
            r.runOnce()
            val held = tickets.presentable(URL)!!
            tickets.answered(URL, held, refusal = "signature")
            assertNull(tickets.presentable(URL))
            now += 30 * 60_000L
            r.runOnce()
            assertEquals(1, issuer.asks.size)
            now += 30 * 60_000L
            issuer.next = { issued(sigByte = 2) }
            r.runOnce()
            assertEquals(2, issuer.asks.size)
            assertTrue(tickets.presentable(URL) != null)
        }

    @Test
    fun aTicketForAnotherHostIsNeverHeld() =
        runTest {
            keys.putKey(HOST, HeldKey(ISSUER, KEY))
            issuer.next = { issued(aud = "elsewhere.example") }
            refresher().runOnce()
            assertNull(tickets.view(URL))
            assertTrue(ticketJournal.state.value.isEmpty())
            assertEquals(
                1,
                keys.states.value
                    .getValue(HOST)
                    .failures,
            )
        }

    @Test
    fun anUnknownKeyParksUntilADifferentKeyArrives() =
        runTest {
            keys.putKey(HOST, HeldKey(ISSUER, KEY))
            issuer.next = { IssuerAnswer.UnknownKey }
            val r = refresher()
            assertNull(r.runOnce())
            now += 7 * DAY_MS
            r.runOnce()
            assertEquals(1, issuer.asks.size)
            keys.putKey(HOST, HeldKey(ISSUER, KEY)) // the same key again: still parked
            r.runOnce()
            assertEquals(1, issuer.asks.size)
            keys.putKey(HOST, HeldKey(ISSUER, OTHER_KEY))
            issuer.next = { issued() }
            r.runOnce()
            assertEquals(ISSUER to OTHER_KEY, issuer.asks.last())
        }

    @Test
    fun anEndedAccountKeepsItsKeyAndIsAskedDaily() =
        runTest {
            keys.putKey(HOST, HeldKey(ISSUER, KEY))
            issuer.next = { IssuerAnswer.Ended(now - 1) }
            val r = refresher()
            assertEquals(now + DAY_MS, r.runOnce())
            assertEquals(HeldKey(ISSUER, KEY), keys.held.value[HOST])
            now += DAY_MS - 1
            r.runOnce()
            assertEquals(1, issuer.asks.size)
            now += 1
            r.runOnce()
            assertEquals(2, issuer.asks.size)
        }

    @Test
    fun noRouteAsksNobodyAndWaitsForOne() =
        runTest {
            keys.putKey(HOST, HeldKey(ISSUER, KEY))
            online = false
            assertEquals(now + TicketRefresher.MAX_SLEEP_MS, refresher().runOnce())
            assertTrue(issuer.asks.isEmpty())
            assertEquals(null, keys.states.value[HOST])
        }

    @Test
    fun withThePlaneOffOrTheRelayParkedNothingIsAsked() =
        runTest {
            keys.putKey(HOST, HeldKey(ISSUER, KEY))
            urls.value = emptySet()
            assertNull(refresher().runOnce())
            assertTrue(issuer.asks.isEmpty())
        }

    @Test
    fun aKeyGoesOnlyToAnHttpsOriginUnlessTheBuildAllowsCleartext() =
        runTest {
            keys.putKey(HOST, HeldKey("http://127.0.0.1:3000", KEY))
            issuer.next = { issued() }
            refresher(allowCleartext = false).runOnce()
            assertTrue(issuer.asks.isEmpty())
            refresher(allowCleartext = true).runOnce()
            assertEquals(listOf("http://127.0.0.1:3000" to KEY), issuer.asks)
        }

    @Test
    fun forgettingTheHostForgetsTheKeyItsStateAndItsTicket() =
        runTest {
            keys.putKey(HOST, HeldKey(ISSUER, KEY))
            issuer.next = { issued() }
            refresher().runOnce()
            keys.forgetHost(HOST)
            assertTrue(keys.held.value.isEmpty())
            assertTrue(keys.states.value.isEmpty())
            assertNull(tickets.view(URL))
        }

    @Test
    fun noLogLineCarriesTheKeyOrTheTicket() =
        runTest {
            keys.putKey(HOST, HeldKey(ISSUER, KEY))
            val answer = issued()
            val answers = ArrayDeque(listOf(answer, IssuerAnswer.NoVerdict("timeout"), IssuerAnswer.Ended(1), IssuerAnswer.UnknownKey))
            issuer.next = { answers.removeFirst() }
            val r = refresher()
            r.runOnce()
            repeat(3) {
                tickets.clear(URL)
                now += 2 * DAY_MS
                r.runOnce()
            }
            assertEquals(4, issuer.asks.size)
            val body = answer.ticket.removePrefix(HeldTicket.PREFIX)
            for (line in logs) {
                assertFalse(line, line.contains(KEY.removePrefix(CustomerKey.PREFIX).take(12)))
                assertFalse(line, line.contains(body.take(16)))
                assertFalse(line, line.contains("fixture-token"))
            }
            assertFalse(answer.toString().contains(body.take(16)))
            assertFalse(HeldKey(ISSUER, KEY).toString().contains(KEY.removePrefix(CustomerKey.PREFIX).take(12)))
        }

    // ── device binding (docs/VOICE_CALLS.md C-7.6-39 to C-7.6-46, ADR 2026-10.wk3m) ─────────────────────────────

    private val devices = FakeDeviceKeyring()

    private fun bound() =
        TicketRefresher(
            keys = keys,
            tickets = tickets,
            issuer = issuer,
            urls = urls,
            online = { online },
            clock = { now },
            log = { logs += it },
            devices = devices,
        )

    private fun state() = keys.states.value.getValue(HOST)

    @Test
    fun aCustomerKeysSwapIsHandedItsDeviceKey() =
        runTest {
            keys.putKey(HOST, HeldKey(ISSUER, KEY))
            issuer.next = { issued() }
            bound().runOnce()
            assertTrue(issuer.signers.single() === devices.signers.getValue(HOST to KEY))
        }

    @Test
    fun aKeystoreThatRefusesAsksNothingAndBacksOff() =
        runTest {
            keys.putKey(HOST, HeldKey(ISSUER, KEY))
            devices.next = { DeviceKey.Refused }
            val next = bound().runOnce()
            assertTrue(issuer.asks.isEmpty())
            // No verdict: the ordinary first backoff, never a park.
            assertNull(state().answer)
            assertEquals(1, state().failures)
            assertEquals(now + TicketRefreshPolicy.FIRST_BACKOFF_MS, next)
        }

    @Test
    fun aPhoneThatCannotHoldADeviceKeySwapsWithoutOne() =
        runTest {
            keys.putKey(HOST, HeldKey(ISSUER, KEY))
            devices.next = { DeviceKey.Unmade }
            issuer.next = { issued() }
            bound().runOnce()
            assertEquals(listOf<DeviceSigner?>(null), issuer.signers)
            assertEquals(KeyAnswer.OK, state().answer)
        }

    @Test
    fun boundElsewhereParksTheKeyAndKeepsTheHeldTicket() =
        runTest {
            keys.putKey(HOST, HeldKey(ISSUER, KEY))
            issuer.next = { issued() }
            val r = bound()
            r.runOnce()
            // Six hours from exp, the renewal is answered 409.
            now += 20 * 3_600_000L
            issuer.next = { IssuerAnswer.BoundElsewhere(now + 3 * DAY_MS) }
            assertNull(r.runOnce())
            assertEquals(KeyAnswer.BOUND_ELSEWHERE, state().answer)
            assertEquals(now + 3 * DAY_MS, state().movableAt)
            assertFalse(state().parkedUnsigned)
            assertTrue(tickets.presentable(URL) != null)

            // Nothing on the schedule, past the ticket's exp, nor for a waiting upload.
            now += 2 * DAY_MS
            r.uploadWanted(URL)
            assertNull(r.runOnce())
            assertEquals(2, issuer.asks.size)
        }

    @Test
    fun aDifferentKeyLiftsThePark() =
        runTest {
            keys.putKey(HOST, HeldKey(ISSUER, KEY))
            issuer.next = { IssuerAnswer.BoundElsewhere(null) }
            val r = bound()
            r.runOnce()
            assertNull(r.runOnce())
            keys.putKey(HOST, HeldKey(ISSUER, OTHER_KEY))
            issuer.next = { issued() }
            r.runOnce()
            assertEquals(listOf(ISSUER to KEY, ISSUER to OTHER_KEY), issuer.asks)
            assertEquals(KeyAnswer.OK, state().answer)
        }

    @Test
    fun aParkAnsweredUnsignedEarnsOneSignedAskOnceTheKeystoreAnswers() =
        runTest {
            keys.putKey(HOST, HeldKey(ISSUER, KEY))
            // The Keystore could not make the pair this time: the swap went unsigned and a bound account answered 409.
            devices.next = { DeviceKey.Unmade }
            issuer.next = { IssuerAnswer.BoundElsewhere(null) }
            val r = bound()
            r.runOnce()
            assertTrue(state().parkedUnsigned)
            // Still unable to sign: nothing more is asked.
            assertNull(r.runOnce())
            assertEquals(1, issuer.asks.size)
            // It can sign now, and this phone's own pair is the bound one: the 200 lifts the park.
            devices.next = null
            issuer.next = { issued() }
            r.runOnce()
            assertEquals(2, issuer.asks.size)
            assertEquals(KeyAnswer.OK, state().answer)
        }

    @Test
    fun aSignedBoundElsewhereIsFinalUntilAMove() =
        runTest {
            keys.putKey(HOST, HeldKey(ISSUER, KEY))
            devices.next = { DeviceKey.Unmade }
            issuer.next = { IssuerAnswer.BoundElsewhere(null) }
            val r = bound()
            r.runOnce()
            devices.next = null
            r.runOnce()
            assertFalse(state().parkedUnsigned)
            now += 7 * DAY_MS
            assertNull(r.runOnce())
            assertEquals(2, issuer.asks.size)
        }

    @Test
    fun upgradeRequiredAndBadProofOnlyBackOff() =
        runTest {
            keys.putKey(HOST, HeldKey(ISSUER, KEY))
            issuer.next = { IssuerAnswer.UpgradeRequired }
            val r = bound()
            assertEquals(now + TicketRefreshPolicy.FIRST_BACKOFF_MS, r.runOnce())
            now += TicketRefreshPolicy.FIRST_BACKOFF_MS
            issuer.next = { IssuerAnswer.BadProof }
            assertEquals(now + 2 * TicketRefreshPolicy.FIRST_BACKOFF_MS, r.runOnce())
            assertNull(state().answer)
            assertEquals(2, state().failures)
        }

    @Test
    fun theSwapAndTheMoveNameTheDialledHostAsTheAudience() =
        runTest {
            // ADR 2026-10.qpja: an account made under a relay's former name gets tickets for the name this phone dials.
            urls.value = setOf("wss://LAB.example.org:8443/spool/v1?k=fixture-token")
            keys.putKey(HOST, HeldKey(ISSUER, KEY))
            issuer.next = { issued() }
            val r = refresher()
            r.runOnce()
            issuer.moveNext = { issued() }
            r.move(HOST)
            assertEquals(listOf(HOST, HOST), issuer.audiences)
        }

    @Test
    fun aMoveImportsItsTicketAndLiftsThePark() =
        runTest {
            keys.putKey(HOST, HeldKey(ISSUER, KEY))
            issuer.next = { IssuerAnswer.BoundElsewhere(null) }
            val r = bound()
            r.runOnce()
            issuer.moveNext = { issued() }
            assertEquals(MoveResult.Moved, r.move(HOST))
            assertEquals(listOf(ISSUER to KEY), issuer.moves)
            assertTrue(issuer.moveSigners.single() === devices.signers.getValue(HOST to KEY))
            assertTrue(tickets.presentable(URL) != null)
            assertEquals(KeyAnswer.OK, state().answer)
            assertNull(state().movableAt)
        }

    @Test
    fun aMoveTooSoonKeepsTheParkWithTheIssuersTime() =
        runTest {
            keys.putKey(HOST, HeldKey(ISSUER, KEY))
            issuer.next = { IssuerAnswer.BoundElsewhere(null) }
            val r = bound()
            r.runOnce()
            issuer.moveNext = { IssuerAnswer.MoveTooSoon(now + DAY_MS) }
            assertEquals(MoveResult.TooSoon(now + DAY_MS), r.move(HOST))
            assertEquals(KeyAnswer.BOUND_ELSEWHERE, state().answer)
            assertEquals(now + DAY_MS, state().movableAt)
            // Never retried on its own.
            now += 2 * DAY_MS
            assertNull(r.runOnce())
            assertEquals(1, issuer.moves.size)
        }

    @Test
    fun aMoveWithARefusingKeystoreSendsNothingAndChangesNothing() =
        runTest {
            keys.putKey(HOST, HeldKey(ISSUER, KEY))
            issuer.next = { IssuerAnswer.BoundElsewhere(null) }
            val r = bound()
            r.runOnce()
            val parked = state()
            devices.next = { DeviceKey.Refused }
            assertEquals(MoveResult.Failed("keystore"), r.move(HOST))
            assertTrue(issuer.moves.isEmpty())
            assertEquals(parked, state())
        }

    @Test
    fun aMovesOtherRefusalsAreReadAsTheSwapReadsThem() =
        runTest {
            keys.putKey(HOST, HeldKey(ISSUER, KEY))
            issuer.next = { IssuerAnswer.BoundElsewhere(null) }
            val r = bound()
            r.runOnce()
            issuer.moveNext = { IssuerAnswer.NoVerdict("http 502") }
            assertEquals(MoveResult.Failed("no verdict (http 502)"), r.move(HOST))
            assertEquals(KeyAnswer.BOUND_ELSEWHERE, state().answer)
            issuer.moveNext = { IssuerAnswer.UnknownKey }
            assertEquals(MoveResult.UnknownKey, r.move(HOST))
            assertEquals(KeyAnswer.UNKNOWN_KEY, state().answer)
        }

    @Test
    fun aFreeKeyIsNeverMoved() =
        runTest {
            keys.putKey(HOST, HeldKey(ISSUER, KEY, free = true))
            assertEquals(MoveResult.Unavailable, bound().move(HOST))
            assertTrue(issuer.moves.isEmpty())
        }

    @Test
    fun aFreeKeysSwapCarriesNoDeviceKey() =
        runTest {
            // C-7.6-37: decided here, by `key.free`, whatever the keyring would hand out.
            keys.putKey(HOST, HeldKey(ISSUER, KEY, free = true))
            issuer.next = { issued() }
            val r = bound()
            r.uploadWanted(URL)
            r.runOnce()
            assertEquals(listOf<DeviceSigner?>(null), issuer.signers)
            assertTrue(devices.signers.isEmpty())
        }

    @Test
    fun a200ToAProofRecordsThePairAsAccepted() =
        runTest {
            keys.putKey(HOST, HeldKey(ISSUER, KEY))
            issuer.next = { issued() }
            bound().runOnce()
            assertEquals(listOf(FakeDeviceKeyring.ID), devices.acceptedIds)
        }

    @Test
    fun aParkAnsweredToOnePairIsAskedOnceMoreWithAnotherThePhoneHolds() =
        runTest {
            // A Keystore blip left two pairs: the issuer bound the old one, and the new one's proof was refused.
            val old = SoftwareDeviceSigner()
            val newer = SoftwareDeviceSigner()
            keys.putKey(HOST, HeldKey(ISSUER, KEY))
            // The keyring prefers the new pair until a 409 refuses it, then hands the old one.
            devices.next = { avoid -> if ("new" in avoid) DeviceKey.Ready(old, "old") else DeviceKey.Ready(newer, "new") }
            issuer.next = { IssuerAnswer.BoundElsewhere(null) }
            val r = bound()
            r.runOnce()
            assertEquals(setOf("new"), state().parkedBy)
            issuer.next = { issued() }
            r.runOnce()
            assertTrue(issuer.signers.last() === old)
            assertEquals(KeyAnswer.OK, state().answer)
            assertEquals(listOf("old"), devices.acceptedIds)
        }

    @Test
    fun everyPairRefusedParksForGood() =
        runTest {
            keys.putKey(HOST, HeldKey(ISSUER, KEY))
            devices.next = { avoid -> DeviceKey.Ready(SoftwareDeviceSigner(), if ("a" in avoid) "b" else "a") }
            issuer.next = { IssuerAnswer.BoundElsewhere(null) }
            val r = bound()
            r.runOnce()
            r.runOnce()
            assertEquals(setOf("a", "b"), state().parkedBy)
            // The keyring hands back a refused pair now: nothing new to try.
            devices.next = { DeviceKey.Ready(SoftwareDeviceSigner(), "a") }
            now += 7 * DAY_MS
            assertNull(r.runOnce())
            assertEquals(2, issuer.asks.size)
        }

    @Test
    fun noLogLineNamesTheProof() =
        runTest {
            keys.putKey(HOST, HeldKey(ISSUER, KEY))
            issuer.next = { IssuerAnswer.BoundElsewhere(null) }
            val r = bound()
            r.runOnce()
            issuer.moveNext = { IssuerAnswer.MoveTooSoon(null) }
            r.move(HOST)
            val spki =
                DeviceProof.b64(
                    devices.signers
                        .getValue(HOST to KEY)
                        .pair.public.encoded,
                )
            assertTrue(logs.isNotEmpty())
            assertTrue(logs.none { it.contains(spki.take(16)) || it.contains(KEY.takeLast(20)) || it.contains(DeviceKeys.ALIAS_PREFIX) })
        }

    private companion object {
        const val HOST = "lab.example.org"
        const val URL = "wss://lab.example.org/spool/v1?k=fixture-token"
        const val ISSUER = "https://anywhere.example.org"
        const val KEY = "kak1_FBsiKTA3PkVMU1phaG92fYSLkpmgp661vMPK0djf5u0"
        const val OTHER_KEY = "kak1_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        const val DAY_MS = 86_400_000L
    }
}
