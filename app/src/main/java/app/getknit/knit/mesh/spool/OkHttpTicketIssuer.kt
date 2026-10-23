package app.getknit.knit.mesh.spool

import app.getknit.knit.badge.BadgeAnswer
import app.getknit.knit.badge.BadgeIssuer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import java.io.IOException
import java.io.InterruptedIOException
import java.net.UnknownHostException
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * The one place the key swap speaks OkHttp (`rules/mesh.md`, ADR 2026-10.3sk7): a single `POST <origin>/api/v1/ticket`
 * with `Authorization: Bearer <key>` and an empty body, read into [IssuerAnswer.read], carrying `?aud=<relay host>`
 * when the caller names one (ADR 2026-10.qpja). The badge issue
 * ([issueBadge], ADR 2026-10.r99s) is the same `POST` to `/api/v1/badge` with a `{"sub": …}` body, and the move
 * ([move], docs/VOICE_CALLS.md C-7.6-44) the swap's request at `/api/v1/ticket/move`, under every rule below; each of
 * the three carries the customer key's device proof when it is handed a signer (C-7.6-40, ADR 2026-10.wk3m). So is
 * the free account's route (docs/VOICE_CALLS.md §2.6, [accountParams] and [createAccount]): a `GET` and a `POST` to
 * `/api/v1/account` that carry no key at all.
 *
 * - **Bound to the validated network**, as the link-preview fetch is: [clientFor] hands back a client whose sockets and
 *   lookups belong to the default network `net/AndroidInternetGate` vouched for, or null when there is none
 *   ([IssuerAnswer.NoVerdict] `offline`, no socket). Unlike the preview fetch there is no private-address guard: the
 *   origin is one the person confirmed on the invite sheet, as they confirmed the relay beside it, and the spool dialer
 *   applies none to that either. A debug build's bench issuer on a LAN or behind `adb reverse` needs exactly that.
 * - **No redirect is followed.** The key is a bearer credential tied to the origin it arrived with (C-7.6-29), and
 *   OkHttp would carry the `Authorization` header to a same-host redirect target; a 3xx is just another status.
 * - **Short timeouts, no retries, no cache** (none is configured; every answer is `no-store` anyway), and the body is
 *   read through a [MAX_BODY_BYTES] cap — an honest answer is a few hundred bytes.
 * - **Nothing secret is logged or thrown.** Failures become a short reason word; the key, the header and the body
 *   never reach one.
 *
 * The cleartext rule is enforced here as well as by [TicketRefresher]: with [allowCleartext] off, an `http://` origin
 * is refused before any socket opens.
 */
class OkHttpTicketIssuer(
    private val clientFor: () -> OkHttpClient?,
    private val allowCleartext: Boolean,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : TicketIssuer,
    BadgeIssuer,
    AccountIssuer {
    // Issuer origin → how far its clock is from this phone's (millis), learnt from a `bad-proof`'s `Date` (C-7.6-43).
    // In memory only: a restart re-learns it from the next `bad-proof`.
    private val offsets = ConcurrentHashMap<String, Long>()

    override suspend fun swap(
        origin: String,
        key: String,
        device: DeviceSigner?,
    ): IssuerAnswer = swapAt(origin, IssuerOrigin.ticketUrl(origin), key, device)

    /** [swap] with `?aud=<aud>` (ADR 2026-10.qpja); the proof still signs the path alone. */
    override suspend fun swap(
        origin: String,
        key: String,
        aud: String,
        device: DeviceSigner?,
    ): IssuerAnswer = swapAt(origin, withAudience(IssuerOrigin.ticketUrl(origin), aud) ?: return refusedOrigin(), key, device)

    private suspend fun swapAt(
        origin: String,
        url: String,
        key: String,
        device: DeviceSigner?,
    ): IssuerAnswer =
        call(
            origin,
            url,
            key,
            ByteArray(0).toRequestBody(null),
            IssuerAnswer::NoVerdict,
            Proof.of(IssuerOrigin.TICKET_PATH, device),
        ) { code, body, retryAfter ->
            IssuerAnswer.read(code, body, retryAfter)
        }

    /**
     * The move (docs/VOICE_CALLS.md C-7.6-44): the swap's request at [IssuerOrigin.MOVE_PATH], its proof signed for that
     * path, read by the same reader. Sent only because the person asked; nothing here retries it.
     */
    override suspend fun move(
        origin: String,
        key: String,
        aud: String,
        device: DeviceSigner?,
    ): IssuerAnswer =
        call(
            origin,
            withAudience(IssuerOrigin.moveUrl(origin), aud) ?: return refusedOrigin(),
            key,
            ByteArray(0).toRequestBody(null),
            IssuerAnswer::NoVerdict,
            Proof.of(IssuerOrigin.MOVE_PATH, device),
        ) { code, body, retryAfter ->
            IssuerAnswer.read(code, body, retryAfter)
        }

    /**
     * The badge issue (docs/SUPPORTER_BADGE.md §5, ADR 2026-10.r99s): the same origin, header and rules as [swap], with
     * `{"sub": <base64url>}` as the body. The `sub` is a commitment, not the node id, and still never reaches a log line.
     */
    override suspend fun issueBadge(
        origin: String,
        key: String,
        sub: ByteArray,
        device: DeviceSigner?,
    ): BadgeAnswer {
        val body = """{"sub":"${Base64.getUrlEncoder().withoutPadding().encodeToString(sub)}"}"""
        return call(
            origin,
            IssuerOrigin.badgeUrl(origin),
            key,
            body.toRequestBody(JSON.toMediaType()),
            BadgeAnswer::NoVerdict,
            Proof.of(IssuerOrigin.BADGE_PATH, device),
        ) { code, bytes, _ ->
            BadgeAnswer.read(code, bytes)
        }
    }

    /** `GET <origin>/api/v1/account?aud=<aud>`: the stamp's parameters, with no key and no body. */
    override suspend fun accountParams(
        origin: String,
        aud: String,
    ): AccountAnswer {
        val url = withAudience(IssuerOrigin.accountUrl(origin), aud) ?: return AccountAnswer.NoAnswer(REFUSED_ORIGIN)
        return call(origin, url, key = null, body = null, AccountAnswer::NoAnswer) { code, bytes, retryAfter ->
            AccountAnswer.read(code, bytes, retryAfter)
        }
    }

    /** `POST <origin>/api/v1/account` with `{ aud, day, nonce, n }`: a free account, with no key in the request. */
    override suspend fun createAccount(
        origin: String,
        aud: String,
        day: Long,
        nonce: ByteArray,
        n: Long,
    ): AccountAnswer {
        val body =
            buildJsonObject {
                put("aud", aud)
                put("day", day)
                put("nonce", Base64.getUrlEncoder().withoutPadding().encodeToString(nonce))
                put("n", n)
            }.toString()
        return call(origin, IssuerOrigin.accountUrl(origin), key = null, body.toRequestBody(JSON.toMediaType()), AccountAnswer::NoAnswer) {
            code,
            bytes,
            retryAfter,
            ->
            AccountAnswer.read(code, bytes, retryAfter)
        }
    }

    /** [url] with `?aud=<aud>`, or null for a URL OkHttp cannot read. */
    private fun withAudience(
        url: String,
        aud: String,
    ): String? =
        url
            .toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter(AUD, aud)
            ?.build()
            ?.toString()

    private fun refusedOrigin(): IssuerAnswer = IssuerAnswer.NoVerdict(REFUSED_ORIGIN)

    /**
     * One request: a `POST` of [body], or a `GET` without one, with [key] in `Authorization` when there is one and, with
     * a [proof], the three device headers (C-7.6-40) signed afresh for each send. [read] gets the status, the capped body
     * and a numeric `retry-after` in seconds.
     *
     * A signer that refuses sends nothing ([noVerdict] `keystore`): an unsigned request to a bound account would be
     * answered 409 and park the key (ADR 2026-10.wk3m). A 400 `bad-proof` whose `Date` is more than
     * [DeviceProof.MAX_SKEW_MS] from this phone's clock is re-signed once on the issuer's clock, and that offset is kept
     * in memory for [origin] (C-7.6-43); any other answer, a second `bad-proof` included, goes to [read].
     */
    @Suppress("LongParameterList") // one request's parts; a holder class would only rename them
    private suspend fun <A> call(
        origin: String,
        url: String,
        key: String?,
        body: RequestBody?,
        noVerdict: (String) -> A,
        proof: Proof? = null,
        read: (Int, ByteArray, Long?) -> A,
    ): A {
        if (!IssuerOrigin.isAcceptable(origin, allowCleartext)) return noVerdict(REFUSED_ORIGIN)
        val client = clientFor() ?: return noVerdict("offline")
        var resigned = false
        while (true) {
            val headers =
                if (proof != null && key != null) {
                    proofHeaders(origin, proof, key) ?: return noVerdict(KEYSTORE)
                } else {
                    emptyList()
                }
            // The time the proof was signed at, as it went out (the header, not a second read of the clock).
            val signed =
                headers
                    .firstOrNull { it.first == DeviceProof.TIME_HEADER }
                    ?.second
                    ?.toLongOrNull()
                    ?.times(MS_PER_S) ?: clock()
            val exchange = exchange(client, request(url, key, headers, body))
            if (exchange !is Exchange.Answered) return noVerdict((exchange as Exchange.Failed).reason)
            val reSign = proof != null && !resigned && learnClock(origin, exchange, signed)
            if (!reSign) return read(exchange.code, exchange.bytes, exchange.retryAfterS)
            resigned = true
        }
    }

    /**
     * Reads the issuer's clock off [exchange]'s `Date` (C-7.6-43): drops [origin]'s offset once the clocks agree again
     * (the phone's was corrected), and says whether a `bad-proof` is to be re-signed, learning the offset when the
     * phone's clock is the one that is off.
     */
    private fun learnClock(
        origin: String,
        exchange: Exchange.Answered,
        signed: Long,
    ): Boolean {
        val date = exchange.dateMs ?: return false
        val agree = abs(date - clock()) <= DeviceProof.MAX_SKEW_MS
        if (agree) offsets.remove(origin)
        if (!farFromSigned(exchange, date, signed)) return false
        if (!agree) offsets[origin] = date - clock()
        return true
    }

    /**
     * Whether [exchange] is a `bad-proof` C-7.6-43 re-signs: the issuer's [date] is more than [DeviceProof.MAX_SKEW_MS]
     * from the time the proof was signed at — compared with what was sent, so an offset gone stale is caught too.
     */
    private fun farFromSigned(
        exchange: Exchange.Answered,
        date: Long,
        signed: Long,
    ): Boolean = abs(date - signed) > DeviceProof.MAX_SKEW_MS && IssuerAnswer.isBadProof(exchange.code, exchange.bytes)

    private fun request(
        url: String,
        key: String?,
        headers: List<Pair<String, String>>,
        body: RequestBody?,
    ): Request =
        Request
            .Builder()
            .url(url)
            .apply { if (key != null) header(AUTHORIZATION, "Bearer $key") }
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .header(ACCEPT, JSON)
            .apply { if (body != null) post(body) else get() }
            .build()

    /** The three device headers for one send, or null when the signer refused. Names nothing in a log. */
    private suspend fun proofHeaders(
        origin: String,
        proof: Proof,
        key: String,
    ): List<Pair<String, String>>? {
        val t = (clock() + (offsets[origin] ?: 0L)) / MS_PER_S
        val spki = proof.signer.publicKey() ?: return null
        val signature = proof.signer.sign(DeviceProof.message(proof.path, t, key)) ?: return null
        return listOf(
            DeviceProof.KEY_HEADER to DeviceProof.b64(spki),
            DeviceProof.TIME_HEADER to t.toString(),
            DeviceProof.SIGNATURE_HEADER to DeviceProof.b64(signature),
        )
    }

    private suspend fun exchange(
        client: OkHttpClient,
        request: Request,
    ): Exchange =
        withContext(Dispatchers.IO) {
            try {
                client.newCall(request).execute().use { response ->
                    val bytes = readCapped(response) ?: return@use Exchange.Failed("too large")
                    Exchange.Answered(
                        response.code,
                        bytes,
                        response.header(RETRY_AFTER)?.trim()?.toLongOrNull(),
                        response.headers.getDate(DATE)?.time,
                    )
                }
            } catch (_: UnknownHostException) {
                Exchange.Failed("dns")
            } catch (_: InterruptedIOException) {
                Exchange.Failed("timeout")
            } catch (e: IOException) {
                Exchange.Failed(e.javaClass.simpleName)
            }
        }

    /** What one send came back with: an answer to read, or a reason word for no verdict. */
    private sealed interface Exchange {
        class Answered(
            val code: Int,
            val bytes: ByteArray,
            val retryAfterS: Long?,
            val dateMs: Long?,
        ) : Exchange

        class Failed(
            val reason: String,
        ) : Exchange
    }

    /** A request's device proof: the [path] it is signed for and the [signer]. */
    private class Proof(
        val path: String,
        val signer: DeviceSigner,
    ) {
        companion object {
            fun of(
                path: String,
                signer: DeviceSigner?,
            ): Proof? = signer?.let { Proof(path, it) }
        }
    }

    /** The body, or null when it is past [MAX_BODY_BYTES]: read one byte past the cap so a body that fits is told apart. */
    private fun readCapped(response: Response): ByteArray? {
        val source = response.body.source()
        val buffer = Buffer()
        var remaining = MAX_BODY_BYTES + 1
        while (remaining > 0) {
            val read = source.read(buffer, remaining)
            if (read == -1L) break
            remaining -= read
        }
        return buffer.readByteArray().takeIf { it.size <= MAX_BODY_BYTES }
    }

    companion object {
        /** Room for the 200's six fields and a ~200-byte ticket many times over. */
        const val MAX_BODY_BYTES = 8L * 1024
        const val CONNECT_TIMEOUT_S = 10L
        const val READ_TIMEOUT_S = 10L
        const val CALL_TIMEOUT_S = 20L

        private const val AUTHORIZATION = "Authorization"
        private const val RETRY_AFTER = "Retry-After"
        private const val DATE = "Date"
        private const val KEYSTORE = "keystore"
        private const val REFUSED_ORIGIN = "refused origin"
        private const val AUD = "aud"
        private const val MS_PER_S = 1_000L
        private const val ACCEPT = "Accept"
        private const val JSON = "application/json"

        /** The client every ask starts from: short timeouts, no redirects, no retries, no cache, no cookies. */
        fun baseClient(): OkHttpClient =
            OkHttpClient
                .Builder()
                .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
                .writeTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
                .callTimeout(CALL_TIMEOUT_S, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .build()

        /**
         * [base] bound to [network]: its sockets come from the network's own factory and its lookups from the network's
         * resolver, so an ask can never ride the Wi-Fi Aware link. OkHttp keys pooled connections by address — socket
         * factory and resolver included — so a connection made for one network is never reused for another.
         */
        fun bound(
            base: OkHttpClient,
            network: android.net.Network,
        ): OkHttpClient =
            base
                .newBuilder()
                .socketFactory(network.socketFactory)
                .dns { host -> network.getAllByName(host).toList() }
                .build()
    }
}
