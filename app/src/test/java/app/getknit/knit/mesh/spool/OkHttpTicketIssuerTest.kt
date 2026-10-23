package app.getknit.knit.mesh.spool

import app.getknit.knit.badge.BadgeAnswer
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * The key swap on a real socket (C-7.6-28, C-7.6-29, ADR 2026-10.3sk7). The three answers are copied from
 * knit-spool-coordinator `main` @ `889bca6` — `apps/anywhere/src/app/api/v1/ticket/route.ts` (the bodies, statuses and
 * headers it writes) and `route.db.test.ts` (the values its tests expect) — so a drift there fails here rather than on
 * a phone. The client is the production base with no network binding (there is no `Network` on the JVM), and the
 * origin is `http://` loopback, which only a debug build's cleartext rule admits.
 */
class OkHttpTicketIssuerTest {
    private val server = MockWebServer()
    private lateinit var origin: String

    private val issuer =
        OkHttpTicketIssuer(
            clientFor = {
                OkHttpTicketIssuer
                    .baseClient()
                    .newBuilder()
                    .callTimeout(3, TimeUnit.SECONDS)
                    .build()
            },
            allowCleartext = true,
        )

    @Before
    fun start() {
        // 127.0.0.1, not "localhost": some CI runners cannot bind ::1.
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        origin = "http://127.0.0.1:${server.port}"
    }

    @After
    fun stop() = server.close()

    private fun swap(
        key: String = KEY,
        at: String = origin,
    ) = runBlocking { issuer.swap(at, key) }

    private fun json(
        status: Int,
        body: String,
        vararg headers: Pair<String, String>,
    ): MockResponse {
        val builder =
            MockResponse
                .Builder()
                .code(status)
                .addHeader("content-type", "application/json")
                .addHeader("cache-control", "no-store")
                .body(body)
        headers.forEach { (name, value) -> builder.addHeader(name, value) }
        return builder.build()
    }

    @Test
    fun theRequestIsAPostWithTheKeyInTheHeaderAndNowhereElse() {
        server.enqueue(json(401, UNKNOWN_KEY_BODY, "www-authenticate" to "Bearer"))
        swap()
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/ticket", request.target)
        assertEquals("Bearer $KEY", request.headers["Authorization"])
        assertEquals(0L, request.bodySize)
        assertFalse(request.requestLine.contains(KEY.removePrefix(CustomerKey.PREFIX)))
        assertNull(request.url.query)
        assertNull(request.headers["Cookie"])
    }

    @Test
    fun aSwapForAHostNamesItAsTheAudienceOutsideTheProof() {
        // ADR 2026-10.qpja: the issuer names the ticket's `aud` from the query; the proof signs the path alone.
        server.enqueue(json(200, okBody(SpoolTicketsTest.TEXT)))
        assertTrue(runBlocking { clocked.swap(origin, KEY, AUD, signer) } is IssuerAnswer.Issued)
        val request = server.takeRequest()
        assertEquals("${IssuerOrigin.TICKET_PATH}?aud=$AUD", request.target)
        assertProof(request, IssuerOrigin.TICKET_PATH)
        assertEquals(0L, request.bodySize)
    }

    @Test
    fun aBadAudienceIsNamedAndIsNoVerdict() {
        server.enqueue(json(400, """{"error":"bad-audience"}"""))
        assertEquals(IssuerAnswer.NoVerdict("bad-audience"), runBlocking { issuer.swap(origin, KEY, AUD, null) })
    }

    @Test
    fun a200IsTheTicketTextAndTheAccountsEnd() {
        server.enqueue(json(200, okBody(SpoolTicketsTest.TEXT)))
        val answer = swap() as IssuerAnswer.Issued
        assertEquals(SpoolTicketsTest.TEXT, answer.ticket)
        assertEquals(Instant.parse("2026-11-09T12:00:00.000Z").toEpochMilli(), answer.endsAt)
        assertFalse(answer.toString().contains(SpoolTicketsTest.TEXT.removePrefix(HeldTicket.PREFIX).take(16)))
    }

    @Test
    fun a401WithItsWordIsAnUnknownKey() {
        server.enqueue(json(401, UNKNOWN_KEY_BODY, "www-authenticate" to "Bearer"))
        assertEquals(IssuerAnswer.UnknownKey, swap())
    }

    @Test
    fun a403WithItsWordIsAnEndedAccountAndWhenItEnded() {
        server.enqueue(json(403, """{"error":"ended","endsAt":"2026-10-09T12:00:00.000Z"}"""))
        assertEquals(IssuerAnswer.Ended(Instant.parse("2026-10-09T12:00:00.000Z").toEpochMilli()), swap())
    }

    @Test
    fun aStatusWithoutItsOwnWordIsNoVerdict() {
        // A proxy or a firewall in front of the issuer answers 401 and 403 too, and says nothing about the key.
        server.enqueue(json(401, """{"message":"Unauthorized"}"""))
        server.enqueue(json(403, "<html>blocked</html>"))
        server.enqueue(json(502, "bad gateway"))
        assertEquals(IssuerAnswer.NoVerdict("http 401"), swap())
        assertEquals(IssuerAnswer.NoVerdict("http 403"), swap())
        assertEquals(IssuerAnswer.NoVerdict("http 502"), swap())
    }

    @Test
    fun a200ItCannotReadIsNoVerdict() {
        server.enqueue(json(200, "not json"))
        server.enqueue(json(200, """{"ticket":"not-a-ticket","endsAt":"2026-11-09T12:00:00.000Z"}"""))
        server.enqueue(json(200, """{"ticket":"${SpoolTicketsTest.TEXT}"}"""))
        server.enqueue(json(200, """{"ticket":"${SpoolTicketsTest.TEXT}","endsAt":"next tuesday"}"""))
        repeat(4) { assertEquals(IssuerAnswer.NoVerdict("unparseable"), swap()) }
    }

    @Test
    fun aRedirectIsNotFollowedSoTheKeyNeverLeavesItsOrigin() {
        server.enqueue(
            MockResponse
                .Builder()
                .code(307)
                .addHeader("Location", "$origin/elsewhere")
                .build(),
        )
        assertEquals(IssuerAnswer.NoVerdict("http 307"), swap())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun aBodyPastTheCapIsNoVerdict() {
        server.enqueue(json(200, "x".repeat(OkHttpTicketIssuer.MAX_BODY_BYTES.toInt() + 1)))
        assertEquals(IssuerAnswer.NoVerdict("too large"), swap())
    }

    @Test
    fun aCleartextOriginIsRefusedBeforeAnySocketWhereTheBuildDoesNotAllowIt() {
        val release = OkHttpTicketIssuer(clientFor = { OkHttpTicketIssuer.baseClient() }, allowCleartext = false)
        assertEquals(IssuerAnswer.NoVerdict("refused origin"), runBlocking { release.swap(origin, KEY) })
        assertEquals(0, server.requestCount)
    }

    @Test
    fun noRouteIsNoVerdictWithoutASocket() {
        val offline = OkHttpTicketIssuer(clientFor = { null }, allowCleartext = true)
        assertEquals(IssuerAnswer.NoVerdict("offline"), runBlocking { offline.swap(origin, KEY) })
        assertEquals(0, server.requestCount)
    }

    @Test
    fun aDeadOriginIsNoVerdictAndNamesNoSecret() {
        val port = server.port
        server.close()
        val answer = swap(at = "http://127.0.0.1:$port")
        assertTrue(answer is IssuerAnswer.NoVerdict)
        assertFalse(answer.toString().contains(KEY.removePrefix(CustomerKey.PREFIX).take(12)))
    }

    // ── the free account's route (docs/VOICE_CALLS.md §2.6; knit-spool-coordinator feat/free-tickets @ 5883035) ──

    @Test
    fun theParametersAreAGetForTheAudienceWithNoKey() {
        server.enqueue(json(200, """{"aud":"lab.lax.spool.getknit.app","bits":22,"day":20743}"""))
        val answer = runBlocking { issuer.accountParams(origin, "lab.lax.spool.getknit.app") }
        assertEquals(AccountAnswer.Params("lab.lax.spool.getknit.app", 22, 20_743), answer)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/v1/account?aud=lab.lax.spool.getknit.app", request.target)
        assertNull(request.headers["Authorization"])
    }

    @Test
    fun anAccountIsAPostOfTheStampWithNoKey() {
        server.enqueue(json(201, """{"key":"$KEY","aud":"lab.lax.spool.getknit.app"}"""))
        val nonce = ByteArray(16) { it.toByte() }
        val answer = runBlocking { issuer.createAccount(origin, "lab.lax.spool.getknit.app", 20_743, nonce, 174_854) }
        assertEquals(KEY, (answer as AccountAnswer.Created).key)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/account", request.target)
        assertNull(request.headers["Authorization"])
        assertTrue(request.headers["Content-Type"]!!.startsWith("application/json"))
        assertEquals(
            """{"aud":"lab.lax.spool.getknit.app","day":20743,"nonce":"AAECAwQFBgcICQoLDA0ODw","n":174854}""",
            request.body!!.utf8(),
        )
    }

    @Test
    fun everyRefusalOfTheAccountRouteIsRead() {
        server.enqueue(json(400, """{"error":"bad-stamp","bits":22,"day":20743}"""))
        server.enqueue(json(429, """{"error":"rate-limited"}""", "retry-after" to "3600"))
        // The cap: retry-after to the next UTC midnight. A tier that is off sends none.
        server.enqueue(json(503, """{"error":"unavailable"}""", "retry-after" to "41000"))
        server.enqueue(json(503, """{"error":"unavailable"}"""))
        val nonce = ByteArray(16)
        val post = { runBlocking { issuer.createAccount(origin, "lab.example", 20_743, nonce, 1) } }
        assertEquals(AccountAnswer.BadStamp(22, 20_743), post())
        assertEquals(AccountAnswer.RateLimited(3_600_000), post())
        assertEquals(AccountAnswer.Unavailable(41_000_000), post())
        assertEquals(AccountAnswer.Unavailable(null), post())
    }

    @Test
    fun aFreeKeysSwapReadsTheCapsRetryAfter() {
        server.enqueue(json(503, """{"error":"unavailable"}""", "retry-after" to "41000"))
        assertEquals(IssuerAnswer.Unavailable(41_000_000), swap())
    }

    @Test
    fun theAccountRouteFollowsNoRedirectAndKeepsTheCleartextRule() {
        server.enqueue(
            MockResponse
                .Builder()
                .code(307)
                .addHeader("Location", "$origin/elsewhere")
                .build(),
        )
        assertEquals(AccountAnswer.NoAnswer("http 307"), runBlocking { issuer.accountParams(origin, "lab.example") })
        assertEquals(1, server.requestCount)
        val release = OkHttpTicketIssuer(clientFor = { OkHttpTicketIssuer.baseClient() }, allowCleartext = false)
        assertEquals(AccountAnswer.NoAnswer("refused origin"), runBlocking { release.accountParams(origin, "lab.example") })
        assertEquals(1, server.requestCount)
    }

    // ── the device proof (docs/VOICE_CALLS.md C-7.6-40 to C-7.6-45, ADR 2026-10.wk3m; coordinator ADR 2026-10.473c) ──

    private var phoneNow = 1_800_000_000_000L
    private val signer = SoftwareDeviceSigner()

    private val clocked =
        OkHttpTicketIssuer(
            clientFor = {
                OkHttpTicketIssuer
                    .baseClient()
                    .newBuilder()
                    .callTimeout(3, TimeUnit.SECONDS)
                    .build()
            },
            allowCleartext = true,
            clock = { phoneNow },
        )

    private fun signedSwap() = runBlocking { clocked.swap(origin, KEY, signer) }

    private fun httpDate(epochMillis: Long): String =
        DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneOffset.UTC))

    /** Checks [request] carries a proof by [signer] over its own [path], [key] and time, and returns that time. */
    private fun assertProof(
        request: RecordedRequest,
        path: String,
        key: String = KEY,
    ): Long {
        val spki = Base64.getUrlDecoder().decode(request.headers[DeviceProof.KEY_HEADER]!!)
        val t = request.headers[DeviceProof.TIME_HEADER]!!.toLong()
        val signature = Base64.getUrlDecoder().decode(request.headers[DeviceProof.SIGNATURE_HEADER]!!)
        assertTrue(SoftwareDeviceSigner.verifies(spki, DeviceProof.message(path, t, key), signature))
        assertArrayEquals(signer.pair.public.encoded, spki)
        listOf(DeviceProof.KEY_HEADER, DeviceProof.SIGNATURE_HEADER).forEach { assertFalse(request.headers[it]!!.contains('=')) }
        return t
    }

    @Test
    fun aCustomerKeysSwapCarriesTheThreeHeadersSignedForItsPath() {
        server.enqueue(json(200, okBody(SpoolTicketsTest.TEXT)))
        assertTrue(signedSwap() is IssuerAnswer.Issued)
        val request = server.takeRequest()
        assertEquals(phoneNow / 1_000, assertProof(request, IssuerOrigin.TICKET_PATH))
        assertEquals("Bearer $KEY", request.headers["Authorization"])
    }

    @Test
    fun aSwapHandedNoSignerCarriesNoProof() {
        server.enqueue(json(401, UNKNOWN_KEY_BODY))
        swap()
        val request = server.takeRequest()
        listOf(DeviceProof.KEY_HEADER, DeviceProof.TIME_HEADER, DeviceProof.SIGNATURE_HEADER).forEach { assertNull(request.headers[it]) }
    }

    @Test
    fun eachRequestIsSignedAfresh() {
        server.enqueue(json(401, UNKNOWN_KEY_BODY))
        server.enqueue(json(401, UNKNOWN_KEY_BODY))
        signedSwap()
        phoneNow += 1_000
        signedSwap()
        val first = server.takeRequest().headers[DeviceProof.SIGNATURE_HEADER]
        val second = server.takeRequest()
        assertEquals(phoneNow / 1_000, assertProof(second, IssuerOrigin.TICKET_PATH))
        assertFalse(first == second.headers[DeviceProof.SIGNATURE_HEADER])
        assertEquals(2, signer.signed)
    }

    @Test
    fun a409BoundElsewhereIsReadWithAndWithoutMovableAt() {
        server.enqueue(json(409, """{"error":"bound-elsewhere","movableAt":"2026-10-28T12:00:00.000Z"}"""))
        server.enqueue(json(409, """{"error":"bound-elsewhere","movableAt":null}"""))
        server.enqueue(json(409, """{"error":"bound-elsewhere"}"""))
        assertEquals(IssuerAnswer.BoundElsewhere(Instant.parse("2026-10-28T12:00:00.000Z").toEpochMilli()), signedSwap())
        assertEquals(IssuerAnswer.BoundElsewhere(null), signedSwap())
        assertEquals(IssuerAnswer.BoundElsewhere(null), signedSwap())
    }

    @Test
    fun a426UpgradeRequiredIsItsOwnAnswerAndBareStatusesAreNot() {
        server.enqueue(json(426, """{"error":"upgrade-required"}"""))
        server.enqueue(json(409, """{"message":"conflict"}"""))
        server.enqueue(json(426, "upgrade"))
        assertEquals(IssuerAnswer.UpgradeRequired, signedSwap())
        assertEquals(IssuerAnswer.NoVerdict("http 409"), signedSwap())
        assertEquals(IssuerAnswer.NoVerdict("http 426"), signedSwap())
    }

    @Test
    fun aBadProofFromAFarClockIsReSignedOnceOnTheIssuersClock() {
        val issuerNow = phoneNow + 10 * 60_000L
        server.enqueue(json(400, """{"error":"bad-proof"}""", "Date" to httpDate(issuerNow)))
        server.enqueue(json(200, okBody(SpoolTicketsTest.TEXT), "Date" to httpDate(issuerNow)))
        assertTrue(signedSwap() is IssuerAnswer.Issued)
        assertEquals(2, server.requestCount)
        assertEquals(phoneNow / 1_000, assertProof(server.takeRequest(), IssuerOrigin.TICKET_PATH))
        assertEquals(issuerNow / 1_000, assertProof(server.takeRequest(), IssuerOrigin.TICKET_PATH))
        // The offset is kept for the origin: the next request starts on the issuer's clock.
        server.enqueue(json(401, UNKNOWN_KEY_BODY))
        signedSwap()
        assertEquals(issuerNow / 1_000, assertProof(server.takeRequest(), IssuerOrigin.TICKET_PATH))
    }

    @Test
    fun aSecondBadProofIsBackedOffFromNotReSignedAgain() {
        val issuerNow = phoneNow + 10 * 60_000L
        repeat(3) { server.enqueue(json(400, """{"error":"bad-proof"}""", "Date" to httpDate(issuerNow))) }
        assertEquals(IssuerAnswer.BadProof, signedSwap())
        assertEquals(2, server.requestCount)
    }

    @Test
    fun aBadProofWithTheClocksAgreedIsNotReSigned() {
        server.enqueue(json(400, """{"error":"bad-proof"}""", "Date" to httpDate(phoneNow + 30_000L)))
        server.enqueue(json(400, """{"error":"bad-proof"}"""))
        assertEquals(IssuerAnswer.BadProof, signedSwap())
        assertEquals(IssuerAnswer.BadProof, signedSwap())
        assertEquals(2, server.requestCount)
    }

    @Test
    fun aKeystoreThatRefusesSendsNothing() {
        // An unsigned request to a bound account would be answered 409 and park the key (ADR 2026-10.wk3m).
        signer.refuse = true
        assertEquals(IssuerAnswer.NoVerdict("keystore"), signedSwap())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun theMoveIsAnEmptyPostWithTheKeyAndAProofForItsOwnPath() {
        server.enqueue(json(200, okBody(SpoolTicketsTest.TEXT)))
        val answer = runBlocking { clocked.move(origin, KEY, AUD, signer) }
        assertTrue(answer is IssuerAnswer.Issued)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("${IssuerOrigin.MOVE_PATH}?aud=$AUD", request.target)
        assertEquals("Bearer $KEY", request.headers["Authorization"])
        assertEquals(0L, request.bodySize)
        assertProof(request, IssuerOrigin.MOVE_PATH)
    }

    @Test
    fun theMovesRefusalsAreRead() {
        server.enqueue(json(409, """{"error":"move-too-soon","movableAt":"2026-10-28T12:00:00.000Z"}"""))
        server.enqueue(json(401, UNKNOWN_KEY_BODY))
        server.enqueue(json(403, """{"error":"ended","endsAt":"2026-10-09T12:00:00.000Z"}"""))
        server.enqueue(
            MockResponse
                .Builder()
                .code(307)
                .addHeader("Location", "$origin/elsewhere")
                .build(),
        )
        val move = { runBlocking { clocked.move(origin, KEY, AUD, signer) } }
        assertEquals(IssuerAnswer.MoveTooSoon(Instant.parse("2026-10-28T12:00:00.000Z").toEpochMilli()), move())
        assertEquals(IssuerAnswer.UnknownKey, move())
        assertEquals(IssuerAnswer.Ended(Instant.parse("2026-10-09T12:00:00.000Z").toEpochMilli()), move())
        assertEquals(IssuerAnswer.NoVerdict("http 307"), move())
        assertEquals(4, server.requestCount)
    }

    @Test
    fun theBadgeRequestCarriesAProofSignedForTheBadgePath() {
        server.enqueue(json(409, """{"error":"bound-elsewhere","movableAt":null}"""))
        val answer = runBlocking { clocked.issueBadge(origin, KEY, ByteArray(32), signer) }
        assertEquals(BadgeAnswer.BoundElsewhere(null), answer)
        assertProof(server.takeRequest(), IssuerOrigin.BADGE_PATH)
    }

    @Test
    fun aStaleOffsetIsCaughtAndDroppedOnceTheClocksAgree() {
        // An offset learnt while the phone's clock was 10 min slow; then the phone's clock is corrected.
        val issuerNow = phoneNow + 10 * 60_000L
        server.enqueue(json(400, """{"error":"bad-proof"}""", "Date" to httpDate(issuerNow)))
        server.enqueue(json(401, UNKNOWN_KEY_BODY, "Date" to httpDate(issuerNow)))
        signedSwap()
        phoneNow = issuerNow
        // Signed on the stale offset, 10 min ahead: the issuer's Date is near the phone's clock but far from what was sent.
        server.enqueue(json(400, """{"error":"bad-proof"}""", "Date" to httpDate(phoneNow)))
        server.enqueue(json(401, UNKNOWN_KEY_BODY, "Date" to httpDate(phoneNow)))
        assertEquals(IssuerAnswer.UnknownKey, signedSwap())
        repeat(2) { server.takeRequest() }
        assertEquals((phoneNow + 10 * 60_000L) / 1_000, assertProof(server.takeRequest(), IssuerOrigin.TICKET_PATH))
        assertEquals(phoneNow / 1_000, assertProof(server.takeRequest(), IssuerOrigin.TICKET_PATH))
    }

    private companion object {
        const val KEY = "kak1_FBsiKTA3PkVMU1phaG92fYSLkpmgp661vMPK0djf5u0"
        const val AUD = "public-1.spool.getknit.app"

        // route.ts's `{ error: "unknown-key" }` and route.db.test.ts's expected 200 values (now 2026-10-10T12:00Z,
        // exp now + 25 h, flags TURN | cast, ends 30 days on), with the vector ticket standing in for the signed one.
        const val UNKNOWN_KEY_BODY = """{"error":"unknown-key"}"""

        fun okBody(ticket: String) =
            """{"ticket":"$ticket","aud":"lab.lax.getknit.app","nbf":1791633600,"exp":1791723600,""" +
                """"flags":{"turn":true,"cast":true},"endsAt":"2026-11-09T12:00:00.000Z"}"""
    }
}
