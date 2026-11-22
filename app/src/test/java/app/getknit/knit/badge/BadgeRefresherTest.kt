package app.getknit.knit.badge

import app.getknit.knit.badge.BadgeFixtures.HOLDER
import app.getknit.knit.badge.BadgeFixtures.NBF
import app.getknit.knit.mesh.spool.DeviceKey
import app.getknit.knit.mesh.spool.DeviceSigner
import app.getknit.knit.mesh.spool.FakeDeviceKeyring
import app.getknit.knit.mesh.spool.HeldKey
import app.getknit.knit.mesh.spool.KeyAnswer
import app.getknit.knit.mesh.spool.KeyState
import app.getknit.knit.mesh.spool.TicketRefresherTest.MemoryKeyJournal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * The holder's side of docs/SUPPORTER_BADGE.md §5 against a fake issuer that signs with the RFC 8032 TEST 1 key, as
 * `npm run anywhere:dev` does: when it asks, with what, what each answer leaves, and that no log line carries a key,
 * a `sub`, an `r` or a credential.
 */
class BadgeRefresherTest {
    class MemoryBadgeJournal : BadgeJournal {
        val on = MutableStateFlow(false)
        val badges = MutableStateFlow<List<HeldBadge>>(emptyList())
        val state = MutableStateFlow(BadgeAskState())
        override val show: Flow<Boolean> = on
        override val held: Flow<List<HeldBadge>> = badges
        override val askState: Flow<BadgeAskState> = state

        override suspend fun setShow(on: Boolean) {
            this.on.value = on
        }

        override suspend fun putHeld(
            badges: List<HeldBadge>,
            state: BadgeAskState,
        ) {
            this.badges.value = badges
            this.state.value = state
        }

        override suspend fun putAskState(state: BadgeAskState) {
            this.state.value = state
        }

        override val sealedVersion: Flow<Long> = MutableStateFlow(0L)
        override val publishedFingerprint: Flow<String?> = MutableStateFlow(null)

        override suspend fun putSealed(
            version: Long,
            fingerprint: String,
        ) {
            (sealedVersion as MutableStateFlow).value = version
            (publishedFingerprint as MutableStateFlow).value = fingerprint
        }
    }

    /**
     * Answers from [script], or from [byOrigin]'s entry for the issuer asked; a [Signed] answer signs each listed badge id
     * over the `sub` it was sent.
     */
    class FakeIssuer(
        var script: Script = Signed(listOf("supporter")),
        val byOrigin: MutableMap<String, Script> = mutableMapOf(),
    ) : BadgeIssuer {
        sealed interface Script

        data class Signed(
            val ids: List<String>,
            val nbf: Long = NBF,
            val exp: Long = NBF + 30 * DAY_S,
        ) : Script

        data class Fixed(
            val answer: BadgeAnswer,
        ) : Script

        val subs = mutableListOf<ByteArray>()
        val asks = mutableListOf<Pair<String, String>>()
        val signers = mutableListOf<DeviceSigner?>()

        override suspend fun issueBadge(
            origin: String,
            key: String,
            sub: ByteArray,
            device: DeviceSigner?,
        ): BadgeAnswer {
            subs += sub
            signers += device
            asks += origin to key
            return when (val s = byOrigin[origin] ?: script) {
                is Fixed -> {
                    s.answer
                }

                is Signed -> {
                    BadgeAnswer.Issued(
                        s.ids.map { b ->
                            val w = BadgeFixtures.wrapper(BadgeFixtures.bc(b = b, sub = sub, nbf = s.nbf, exp = s.exp))
                            Badges.TEXT_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(w)
                        },
                    )
                }
            }
        }
    }

    private val journal = MemoryBadgeJournal()
    private val keys = MemoryKeyJournal()
    private val issuer = FakeIssuer()
    private var now = (NBF + 3_600) * 1_000
    private val logs = mutableListOf<String>()
    private var draws = 0

    private fun refresher(online: Boolean = true) =
        BadgeRefresher(
            journal = journal,
            keys = keys,
            issuer = issuer,
            selfId = { HOLDER },
            pins = listOf(BadgeKeys.RFC8032_TEST1),
            online = { online },
            clock = { now },
            random = { n -> ByteArray(n) { (draws * 16 + it).toByte() }.also { draws++ } },
            log = { logs += it },
        )

    private suspend fun holdKey(
        host: String = "relay.example",
        answer: KeyAnswer? = null,
        issuer: String = "https://anywhere.example",
        endsAt: Long? = null,
    ) {
        val key = HeldKey(issuer, "kak1_" + "A".repeat(43))
        keys.putKey(host, key)
        if (answer != null || endsAt != null) keys.putKeyState(host, key, KeyState(answer, endsAt))
    }

    private fun answerAt(host: String): KeyAnswer? = keys.states.value[host]?.answer

    private fun shown(): List<VerifiedBadge> =
        journal.badges.value.mapNotNull { Badges.verify(it.wrapper, it.r, HOLDER, now / 1_000, listOf(BadgeKeys.RFC8032_TEST1)) }

    @Test
    fun `with the toggle off nothing is ever asked`() =
        runTest {
            holdKey()
            assertNull(refresher().runOnce())
            assertTrue(issuer.asks.isEmpty())
        }

    @Test
    fun `with no live key nothing is asked`() =
        runTest {
            journal.on.value = true
            assertNull(refresher().runOnce())
            holdKey(answer = KeyAnswer.ENDED)
            assertNull(refresher().runOnce())
            holdKey(answer = KeyAnswer.UNKNOWN_KEY)
            assertNull(refresher().runOnce())
            assertTrue(issuer.asks.isEmpty())
        }

    @Test
    fun `turned on, it asks the key's issuer and holds a badge that verifies for this phone`() =
        runTest {
            journal.on.value = true
            holdKey()
            val next = refresher().runOnce()
            assertEquals(listOf("https://anywhere.example" to "kak1_" + "A".repeat(43)), issuer.asks)
            assertEquals(listOf(VerifiedBadge("supporter", NBF + 30 * DAY_S)), shown())
            // Next due seven days before it lapses, well past the hour floor.
            assertEquals((NBF + 30 * DAY_S) * 1_000 - BadgeRefreshPolicy.RENEW_MARGIN_MS, next)
        }

    @Test
    fun `each ask commits to a fresh r`() =
        runTest {
            journal.on.value = true
            holdKey()
            val refresher = refresher()
            refresher.runOnce()
            now += 24 * DAY_S * 1_000 // inside the seven-day margin
            refresher.runOnce()
            assertEquals(2, issuer.subs.size)
            assertFalse(issuer.subs[0].contentEquals(issuer.subs[1]))
            assertFalse(
                journal.badges.value
                    .single()
                    .r
                    .contentEquals(ByteArray(16) { it.toByte() }),
            )
        }

    @Test
    fun `never more than one ask an hour`() =
        runTest {
            journal.on.value = true
            holdKey()
            issuer.script = FakeIssuer.Fixed(BadgeAnswer.NoVerdict("timeout"))
            val refresher = refresher()
            refresher.runOnce()
            now += 59 * 60 * 1_000
            refresher.runOnce()
            assertEquals(1, issuer.asks.size)
            now += 60 * 1_000
            refresher.runOnce()
            assertEquals(2, issuer.asks.size)
        }

    @Test
    fun `a 200 keeps only the ids this build knows, and an empty list holds nothing`() =
        runTest {
            journal.on.value = true
            holdKey()
            issuer.script = FakeIssuer.Signed(listOf("gold", "supporter"))
            refresher().runOnce()
            assertEquals(listOf("supporter"), shown().map { it.b })
            issuer.script = FakeIssuer.Fixed(BadgeAnswer.Issued(emptyList()))
            now += 24 * DAY_S * 1_000
            refresher().runOnce()
            assertTrue(journal.badges.value.isEmpty())
        }

    @Test
    fun `ended drops the held badges and takes the key out until a ticket answer brings it back`() =
        runTest {
            journal.on.value = true
            holdKey()
            val refresher = refresher()
            refresher.runOnce()
            now += 24 * DAY_S * 1_000
            issuer.script = FakeIssuer.Fixed(BadgeAnswer.Ended(now - DAY_S * 1_000))
            assertEquals(now + BadgeRefreshPolicy.FLOOR_MS, refresher.runOnce())
            assertTrue(journal.badges.value.isEmpty())
            // Recorded as the ticket swap's 403 is (C-5-7): the ticket refresher's daily retry is the key's own now.
            val state = keys.states.value.getValue("relay.example")
            assertEquals(KeyAnswer.ENDED, state.answer)
            assertEquals(now - DAY_S * 1_000, state.endsAt)
            now += 7 * DAY_S * 1_000
            assertNull(refresher.runOnce())
            assertEquals(2, issuer.asks.size)
            // `account extend`: the ticket swap's 200 says the account is live again, and the badge is asked for.
            keys.putKeyState("relay.example", keys.held.value.getValue("relay.example"), KeyState(KeyAnswer.OK, now + 30 * DAY_S * 1_000))
            issuer.script = FakeIssuer.Signed(listOf("supporter"), nbf = now / 1_000, exp = now / 1_000 + 30 * DAY_S)
            refresher.runOnce()
            assertEquals(3, issuer.asks.size)
            assertEquals(listOf("supporter"), shown().map { it.b })
        }

    @Test
    fun `401, 503 and no answer keep what is held`() =
        runTest {
            journal.on.value = true
            holdKey()
            refresher().runOnce()
            val held = journal.badges.value
            now += 24 * DAY_S * 1_000 // inside the seven-day margin, so every row below is due
            // The 401 last: it takes the key out (C-5-7), so nothing after it would be asked.
            for ((i, answer) in listOf(BadgeAnswer.Unavailable, BadgeAnswer.NoVerdict("offline"), BadgeAnswer.UnknownKey).withIndex()) {
                now += BadgeRefreshPolicy.FLOOR_MS
                issuer.script = FakeIssuer.Fixed(answer)
                refresher().runOnce()
                assertEquals(i + 2, issuer.asks.size)
                assertEquals(held, journal.badges.value)
            }
            assertEquals(KeyAnswer.UNKNOWN_KEY, answerAt("relay.example"))
        }

    // ── a dead key never holds up a live one (#358) ──────────────────────────────────────────────────────────────

    @Test
    fun `an ended key that sorts first is marked, and the next live key is asked after the hour floor`() =
        runTest {
            journal.on.value = true
            holdKey("a.relay.example", issuer = A)
            holdKey("b.relay.example", issuer = B)
            issuer.byOrigin[A] = FakeIssuer.Fixed(BadgeAnswer.Ended(null))
            val refresher = refresher()
            assertEquals(now + BadgeRefreshPolicy.FLOOR_MS, refresher.runOnce())
            assertEquals(listOf(A), issuer.asks.map { it.first })
            assertEquals(KeyAnswer.ENDED, answerAt("a.relay.example"))
            assertTrue(journal.badges.value.isEmpty())
            now += BadgeRefreshPolicy.FLOOR_MS
            refresher.runOnce()
            assertEquals(listOf(A, B), issuer.asks.map { it.first })
            assertEquals(listOf("supporter"), shown().map { it.b })
            assertNull(answerAt("b.relay.example"))
        }

    @Test
    fun `an unknown key that sorts first is marked and keeps what is held, and the next live key is asked`() =
        runTest {
            journal.on.value = true
            holdKey("a.relay.example", issuer = A)
            holdKey("b.relay.example", issuer = B)
            val refresher = refresher()
            refresher.runOnce()
            val held = journal.badges.value
            assertEquals(1, held.size)
            now += 24 * DAY_S * 1_000 // inside the seven-day margin
            issuer.byOrigin[A] = FakeIssuer.Fixed(BadgeAnswer.UnknownKey)
            assertEquals(now + BadgeRefreshPolicy.FLOOR_MS, refresher.runOnce())
            assertEquals(KeyAnswer.UNKNOWN_KEY, answerAt("a.relay.example"))
            assertEquals(held, journal.badges.value)
            now += BadgeRefreshPolicy.FLOOR_MS
            refresher.runOnce()
            assertEquals(listOf(A, A, B), issuer.asks.map { it.first })
            assertTrue(journal.badges.value != held)
            assertEquals(listOf("supporter"), shown().map { it.b })
        }

    @Test
    fun `a key whose recorded end has passed is asked after a live one, and still asked when it is the only one`() =
        runTest {
            journal.on.value = true
            holdKey("a.relay.example", answer = KeyAnswer.OK, issuer = A, endsAt = now - DAY_S * 1_000)
            holdKey("b.relay.example", issuer = B)
            refresher().runOnce()
            assertEquals(listOf(B), issuer.asks.map { it.first })
            keys.forgetHost("b.relay.example")
            journal.state.value = BadgeAskState()
            journal.badges.value = emptyList()
            refresher().runOnce()
            assertEquals(listOf(B, A), issuer.asks.map { it.first })
        }

    @Test
    fun `a badge under an unpinned key is not held`() =
        runTest {
            journal.on.value = true
            holdKey()
            BadgeRefresher(journal, keys, issuer, { HOLDER }, pins = emptyList(), clock = { now }).runOnce()
            assertEquals(1, issuer.asks.size)
            assertTrue(journal.badges.value.isEmpty())
        }

    @Test
    fun `offline, a due ask waits for a route`() =
        runTest {
            journal.on.value = true
            holdKey()
            assertEquals(now + BadgeRefresher.MAX_SLEEP_MS, refresher(online = false).runOnce())
            assertTrue(issuer.asks.isEmpty())
        }

    @Test
    fun `no log line carries the key, the sub, r or a credential`() =
        runTest {
            journal.on.value = true
            holdKey()
            refresher().runOnce()
            val secrets =
                listOf("kak1_", Badges.TEXT_PREFIX) +
                    issuer.subs.map { Base64.getUrlEncoder().withoutPadding().encodeToString(it) } +
                    journal.badges.value.map { hexOf(it.r) }
            assertTrue(logs.isNotEmpty())
            logs.forEach { line -> secrets.forEach { assertFalse(line, line.contains(it)) } }
        }

    @Test
    fun `the route's answers read as the contract writes them`() {
        val ok = """{"badges":[{"badge":"knit-badge:v1:AAAA","b":"supporter","nbf":1,"exp":2}]}"""
        assertEquals(listOf("knit-badge:v1:AAAA"), (BadgeAnswer.read(200, ok.encodeToByteArray()) as BadgeAnswer.Issued).badges)
        assertEquals(BadgeAnswer.UnknownKey, BadgeAnswer.read(401, """{"error":"unknown-key"}""".encodeToByteArray()))
        assertEquals(
            BadgeAnswer.Ended(1_792_454_400_000),
            BadgeAnswer.read(403, """{"error":"ended","endsAt":"2026-10-20T00:00:00.000Z"}""".encodeToByteArray()),
        )
        assertEquals(BadgeAnswer.Unavailable, BadgeAnswer.read(503, """{"error":"unavailable"}""".encodeToByteArray()))
        assertTrue(BadgeAnswer.read(400, """{"error":"bad-sub"}""".encodeToByteArray()) is BadgeAnswer.NoVerdict)
        assertTrue(BadgeAnswer.read(401, "<html>".encodeToByteArray()) is BadgeAnswer.NoVerdict)
        assertTrue(BadgeAnswer.read(200, """{"error":"x"}""".encodeToByteArray()) is BadgeAnswer.NoVerdict)
    }

    // ── the device proof (C-5-3, C-5-8; docs/VOICE_CALLS.md C-7.6-40 to C-7.6-45, ADR 2026-10.wk3m) ────────────────

    private val devices = FakeDeviceKeyring()

    private fun signing() =
        BadgeRefresher(
            journal = journal,
            keys = keys,
            issuer = issuer,
            selfId = { HOLDER },
            pins = listOf(BadgeKeys.RFC8032_TEST1),
            clock = { now },
            random = { n -> ByteArray(n) { (draws * 16 + it).toByte() }.also { draws++ } },
            log = { logs += it },
            devices = devices,
        )

    @Test
    fun `the badge request is handed the key's device key, and a 200 records the pair as accepted`() =
        runTest {
            journal.on.value = true
            holdKey()
            signing().runOnce()
            val key = "kak1_" + "A".repeat(43)
            assertTrue(issuer.signers.single() === devices.signers.getValue("relay.example" to key))
            assertEquals(listOf(FakeDeviceKeyring.ID), devices.acceptedIds)
        }

    @Test
    fun `a 409 keeps the held badge and parks the key for the ticket refresher too`() =
        runTest {
            journal.on.value = true
            holdKey()
            val refresher = signing()
            refresher.runOnce()
            val held = journal.badges.value
            assertEquals(1, held.size)
            now += 24 * DAY_S * 1_000
            issuer.script = FakeIssuer.Fixed(BadgeAnswer.BoundElsewhere(now + DAY_S * 1_000))
            refresher.runOnce()
            assertEquals(held, journal.badges.value)
            val state = keys.states.value.getValue("relay.example")
            assertEquals(KeyAnswer.BOUND_ELSEWHERE, state.answer)
            assertEquals(now + DAY_S * 1_000, state.movableAt)
            assertEquals(setOf(FakeDeviceKeyring.ID), state.parkedBy)
            // Parked: no more badge asks either.
            now += 7 * DAY_S * 1_000
            assertNull(refresher.runOnce())
            assertEquals(2, issuer.asks.size)
        }

    @Test
    fun `a keystore that refuses asks nothing and keeps what is held`() =
        runTest {
            journal.on.value = true
            holdKey()
            devices.next = { DeviceKey.Refused }
            signing().runOnce()
            assertTrue(issuer.asks.isEmpty())
            assertEquals(1, journal.state.value.failures)
            assertTrue(keys.states.value["relay.example"]?.answer == null)
        }

    private companion object {
        const val DAY_S = 86_400L
        const val A = "https://a.anywhere.example"
        const val B = "https://b.anywhere.example"
    }
}
