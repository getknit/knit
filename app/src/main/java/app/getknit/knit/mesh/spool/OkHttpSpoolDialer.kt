package app.getknit.knit.mesh.spool

import android.util.Log
import app.getknit.knit.net.InternetGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit

/**
 * The one place in the app that speaks OkHttp (`rules/mesh.md`): it turns a spool URL into the
 * [SpoolSocket] seam the pure record layer consumes. Everything protocol-shaped — hello, `q`, the heal
 * loop — lives above this in [SpoolConnection]/[ScopeSync] and is unit-tested without a socket.
 *
 * [allowCleartext] gates plain `ws://`. Release builds pass false, so a release APK cannot be pointed at
 * a plaintext relay however its settings are edited; debug builds pass true so a LAN daemon (which
 * terminates no TLS of its own — that is a reverse-proxy job) can be driven straight from the bench.
 */
class OkHttpSpoolDialer(
    private val allowCleartext: Boolean,
    // The client for the route the phone is on right now, chosen per dial — see [clientFor]. A test that
    // wants one fixed client uses the secondary constructor.
    private val clientFor: () -> OkHttpClient,
) : SpoolDialer {
    constructor(
        allowCleartext: Boolean,
        client: OkHttpClient = defaultClient(),
    ) : this(allowCleartext, clientFor = { client })

    override suspend fun dial(url: String): SpoolSocket? {
        if (!SpoolUrl.isAcceptable(url, allowCleartext)) {
            Log.w(TAG, "refusing spool url (scheme not allowed): ${SpoolUrl.redact(url)}")
            return null
        }
        val request = runCatching { Request.Builder().url(url).build() }.getOrNull()
        if (request == null) {
            Log.w(TAG, "unparseable spool url: ${SpoolUrl.redact(url)}")
            return null
        }
        // Bounded rather than unlimited: a hostile spool must not be able to grow our heap by talking
        // faster than the reader drains. A dropped record costs one request timeout, and §9.1's heal
        // loop is the recovery path for exactly this kind of loss.
        val channel = Channel<ByteArray>(INBOX_CAPACITY)
        val socket = OkHttpSpoolSocket(channel)
        socket.attach(clientFor().newWebSocket(request, socket.listener))
        return socket
    }

    /**
     * One plain GET of the spool's `/source` document, on the same client as the socket.
     *
     * The token is stripped by [SpoolUrl.sourceUrl] before the request is built: the route is
     * unauthenticated by design (a private spool's users are still owed the offer), so sending the
     * credential would put it in a reverse proxy's access log for nothing. Every failure — an unreachable
     * host, a proxy answering 404, a body that is not ours — is the same null, because a missing build
     * string is a missing diagnostics row and never a reason to treat the relay as unhealthy.
     */
    override suspend fun fetchSoftware(url: String): SpoolSoftware? {
        val request =
            SpoolUrl
                .sourceUrl(url, allowCleartext)
                ?.let { source -> runCatching { Request.Builder().url(source).build() }.getOrNull() }
                ?: return null
        return withContext(Dispatchers.IO) {
            runCatching {
                clientFor().newCall(request).execute().use { response ->
                    // Bounded like every other read from a spool: this body is written by a machine we do
                    // not run, and `peekBody` is what stops an endless one being materialized to render a
                    // single row.
                    response.takeIf { it.isSuccessful }?.peekBody(MAX_SOURCE_BYTES)?.string()
                }
            }.getOrNull()?.let(::parseSpoolSoftware)
        }
    }

    private class OkHttpSpoolSocket(
        private val channel: Channel<ByteArray>,
    ) : SpoolSocket {
        @Volatile
        private var socket: WebSocket? = null

        @Volatile
        override var closeReason: String? = null
            private set

        @Volatile
        override var retryAfterMs: Long? = null
            private set

        override val incoming: ReceiveChannel<ByteArray> get() = channel

        val listener =
            object : WebSocketListener() {
                override fun onMessage(
                    webSocket: WebSocket,
                    bytes: ByteString,
                ) {
                    // Records are binary by definition (§7.1); a text frame is not one of ours.
                    // Cap before the copy: OkHttp has already buffered the message, but nothing above
                    // this line bounds one, so an arbitrarily large "record" would be materialized into
                    // our heap before the record layer ever saw a discriminator.
                    if (bytes.size > MAX_INBOUND_RECORD) {
                        Log.w(TAG, "oversize spool record dropped (${bytes.size} B) — the heal loop will recover")
                        return
                    }
                    if (channel.trySend(bytes.toByteArray()).isFailure) {
                        Log.w(TAG, "spool inbox full, dropped a record — the heal loop will recover")
                    }
                }

                override fun onClosing(
                    webSocket: WebSocket,
                    code: Int,
                    reason: String,
                ) {
                    Log.i(TAG, "spool closing: $code $reason")
                    closeReason = "close $code${if (reason.isBlank()) "" else " $reason"}"
                    channel.close()
                }

                override fun onClosed(
                    webSocket: WebSocket,
                    code: Int,
                    reason: String,
                ) {
                    channel.close()
                }

                override fun onFailure(
                    webSocket: WebSocket,
                    t: Throwable,
                    response: Response?,
                ) {
                    Log.i(TAG, "spool socket failed: ${t.javaClass.simpleName} ${response?.code ?: ""}")
                    // A refused upgrade arrives as an HTTP status, never a close code: §7.1 defines only
                    // four and none of them means "come back later" (4003 would accuse a client that did
                    // nothing wrong). OkHttp reports the refusal as a bare ProtocolException, so without
                    // the status a spool at capacity and a spool that is simply broken reach the UI as
                    // the same word, and they want opposite reactions from the user.
                    closeReason = failureReason(t.javaClass.simpleName, response?.code)
                    retryAfterMs = retryAfterMillis(response?.header(RETRY_AFTER))
                    channel.close()
                }
            }

        fun attach(webSocket: WebSocket) {
            socket = webSocket
        }

        override fun send(bytes: ByteArray): Boolean = socket?.send(bytes.toByteString()) ?: false

        override fun close(
            code: Int,
            reason: String,
        ) {
            // OkHttp only accepts 1000 and 3000-4999 here; our protocol codes are all in 4000-4003.
            socket?.close(code, reason)
            channel.close()
        }
    }

    companion object {
        private const val TAG = "ScopeSync"
        private const val INBOX_CAPACITY = 256
        private const val RETRY_AFTER = "Retry-After"

        fun defaultClient(): OkHttpClient =
            OkHttpClient
                .Builder()
                .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
                // Client-side keepalive: pinging outward is what detects a silently-dead link (a NAT
                // dropping an idle connection) instead of waiting for the next request to time out. The
                // reference daemon pings on its own too (not a spec clause — its choice) and OkHttp answers
                // that automatically; the cellular client below only slows OUR half, so the modem still
                // wakes for the daemon's until knit-spool takes a keepalive hint.
                .pingInterval(PING_INTERVAL_S, TimeUnit.SECONDS)
                .build()

        /**
         * Two clients off one base — one pool, one dispatcher — differing only in how often they ping, picked
         * by the route the phone is on at dial time. On Wi-Fi a 25 s ping is free; on cellular it keeps the
         * modem in its connected state around the clock (LTE's inactivity timers are 10–20 s), so that client
         * pings every four minutes and lets a dead link be noticed by the next request instead. A live
         * session is not re-dialled on a route switch: the OS tears the old network down and the socket dies
         * within a ping, and the reconnect picks the right client.
         */
        fun clientFor(routeKind: () -> InternetGate.RouteKind): () -> OkHttpClient {
            val base = defaultClient()
            val cellular = base.newBuilder().pingInterval(CELLULAR_PING_S, TimeUnit.SECONDS).build()
            return { if (routeKind() == InternetGate.RouteKind.CELLULAR) cellular else base }
        }

        const val CONNECT_TIMEOUT_S = 15L
        const val PING_INTERVAL_S = 25L
        const val CELLULAR_PING_S = 240L

        /** Room for the five short strings `/source` answers with, and nothing like room for a page. */
        const val MAX_SOURCE_BYTES = 4L * 1024
    }
}

/**
 * What a dead socket is called in [SpoolSocket.closeReason]. A refused upgrade carries an HTTP status
 * and nothing else useful — `ProtocolException` names the layer that noticed, not what happened — so the
 * status becomes the reason. `101` is filtered out because it means the upgrade *succeeded* and the
 * socket died later; there the exception really is the whole story.
 *
 * With no response at all, a failure that names the network layer — a connect or read timeout, DNS, a
 * refused or reset connection, no route — is the client's own [ScopeSync.UNREACHABLE] verdict rather
 * than an exception name. That is the shape of a captive or filtered Wi-Fi the platform still calls
 * validated: every dial times out and nothing distinguishes it from "not connected yet" (work item 50).
 * A TLS or protocol failure keeps its name, because "the host answered and we refused it" is a different
 * diagnosis from "nothing answered". The exception's name still reaches logcat from `onFailure`.
 */
internal fun failureReason(
    throwableName: String,
    httpCode: Int?,
): String =
    when {
        httpCode != null && httpCode != HTTP_SWITCHING_PROTOCOLS -> "http $httpCode"
        httpCode == null && throwableName in NO_RESPONSE_FAILURES -> ScopeSync.UNREACHABLE
        else -> throwableName
    }

/** The `java.net` failures that mean no response of any kind came back from the route. */
private val NO_RESPONSE_FAILURES =
    setOf(
        "SocketTimeoutException",
        "ConnectException",
        "UnknownHostException",
        "NoRouteToHostException",
        "SocketException",
        "EOFException",
    )

/**
 * `Retry-After` as milliseconds, or null when absent or unusable. Delta-seconds only: the HTTP-date form
 * is legal but would make our backoff a function of the spool's clock against ours, and the value is a
 * hint we are free to ignore. Clamped because this feeds a `delay` — a hostile or fat-fingered header
 * must not park a worker for a week.
 */
internal fun retryAfterMillis(header: String?): Long? =
    header
        ?.trim()
        ?.toLongOrNull()
        ?.takeIf { it > 0 }
        ?.coerceAtMost(MAX_RETRY_AFTER_S)
        ?.let(TimeUnit.SECONDS::toMillis)

/**
 * Ceiling on an honoured `Retry-After`: the reconnect loop's first-tier ceiling. Its long tier waits up to
 * fifteen minutes of its own accord (`SpoolBackoffPolicy`); a spool's ask is a floor under that, never above.
 */
private const val MAX_RETRY_AFTER_S = 60L
private const val HTTP_SWITCHING_PROTOCOLS = 101
