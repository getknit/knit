package app.getknit.knit.mesh.spool

import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * [OkHttpSpoolDialer] on a real socket. [OkHttpSpoolDialerTest] pins the two pure functions (`failureReason`,
 * `retryAfterMillis`); this is the wiring around them — the one place OkHttp's listener becomes the
 * [SpoolSocket] seam every connection-state verdict above it (ADR 2026-09.vej5) is read from. What a dead
 * route, a refused upgrade and a protocol close each leave in `closeReason` / `retryAfterMs`, the inbound
 * caps, and the `/source` fetch's token hygiene are only observable here.
 */
class OkHttpSpoolDialerSocketTest {
    private val server = MockWebServer()
    private val dialer = OkHttpSpoolDialer(allowCleartext = true)

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    private fun spoolUrl(query: String = ""): String = server.url("/spool/v1").toString().replaceFirst("http://", "ws://") + query

    private fun dial(url: String = spoolUrl()): SpoolSocket = runBlocking { checkNotNull(dialer.dial(url)) }

    private fun SpoolSocket.next(): ChannelResult<ByteArray> = runBlocking { withTimeout(WAIT_MS) { incoming.receiveCatching() } }

    /** The server's half of an accepted upgrade, once it is open. */
    private class ServerEnd : WebSocketListener() {
        private val opened = CountDownLatch(1)
        private lateinit var socket: WebSocket
        val received = mutableListOf<ByteString>()

        override fun onOpen(
            webSocket: WebSocket,
            response: Response,
        ) {
            socket = webSocket
            opened.countDown()
        }

        override fun onMessage(
            webSocket: WebSocket,
            bytes: ByteString,
        ) {
            synchronized(received) { received += bytes }
        }

        // A close is a handshake: the side that hears one answers it, as the daemon does.
        override fun onClosing(
            webSocket: WebSocket,
            code: Int,
            reason: String,
        ) {
            webSocket.close(code, reason)
        }

        fun await(): WebSocket {
            assertTrue("the upgrade never completed", opened.await(WAIT_MS, TimeUnit.MILLISECONDS))
            return socket
        }
    }

    private fun accept(): ServerEnd = ServerEnd().also { server.enqueue(MockResponse.Builder().webSocketUpgrade(it).build()) }

    @Test
    fun `a scheme the build may not dial, and a url that is not one, are refused without a request`() =
        runBlocking {
            val release = OkHttpSpoolDialer(allowCleartext = false)
            assertNull("plain ws:// is refused by a release build", release.dial(spoolUrl()))
            assertNull("an https:// is not a spool", dialer.dial(server.url("/spool/v1").toString()))
            assertNull("an unparseable url is refused", dialer.dial("wss://[not-a-host/spool/v1"))
            assertEquals("and none of them reached the socket", 0, server.requestCount)
        }

    @Test
    fun `the host a relay is titled with is the host the socket dials`() {
        // ADR 2026-11.ut8g (#340): SpoolUrl splits an authority by hand, OkHttp splits it as a browser does. Every URL the
        // shape rule passes must name one host to both; the spoof forms it refuses must too, so a row stored before the
        // rule is titled with the host it would reach and a ticket never names another (SpoolConnection.present).
        val passed =
            listOf(
                "wss://relay.example.org/spool/v1",
                "wss://Relay.Example.ORG:8443/spool/v1?k=secret-token",
                "ws://10.0.2.2:9470/spool/v1",
                "wss://[::1]:9470/spool/v1",
                "wss://relay_1.example.org?k=t",
                "wss://evil.example?@public-1.spool.getknit.app/spool/v1",
            )
        val refused =
            listOf(
                "wss://public-1.spool.getknit.app@evil.example/spool/v1",
                "wss://public-1.spool.getknit.app:443@evil.example:8443/spool/v1",
                "wss://evil.example\\@public-1.spool.getknit.app/spool/v1",
                "wss://evil.example:8443\\@public-1.spool.getknit.app/spool/v1",
                "wss://evil.example#@public-1.spool.getknit.app/spool/v1",
            )
        for (url in passed) assertTrue(url, SpoolUrl.isAcceptable(url, allowCleartext = true))
        for (url in refused) assertFalse(url, SpoolUrl.isAcceptable(url, allowCleartext = true))
        for (url in passed + refused) {
            // What OkHttpSpoolDialer.dial hands the socket.
            val dialled =
                Request
                    .Builder()
                    .url(url)
                    .build()
                    .url
            val hostname = SpoolUrl.hostname(url)
            assertEquals(url, dialled.host, hostname?.removeSurrounding("[", "]"))
            // The port is part of what is dialled: no URL here writes out its scheme's default, so a label shows one
            // exactly when OkHttp dials a port other than the default.
            val expected = if (dialled.port == HttpUrl.defaultPort(dialled.scheme)) hostname else "$hostname:${dialled.port}"
            assertEquals(url, expected, SpoolUrl.host(url).lowercase())
        }
    }

    @Test
    fun `a stored url of the wrong shape never reaches the socket`() =
        runBlocking {
            // A row an invite stored before the shape rule: its label read the house relay, its socket reached this one.
            val spoofed = "ws://public-1.spool.getknit.app@${server.hostName}:${server.port}/spool/v1"
            assertNull(dialer.dial(spoofed))
            assertEquals(0, server.requestCount)
        }

    @Test
    fun `records cross both ways and the socket closes with the spool's code and reason`() {
        val end = accept()
        val socket = dial()
        val serverSocket = end.await()

        assertTrue(socket.send(byteArrayOf(1, 2, 3)))
        assertTrue(waitUntil { synchronized(end.received) { end.received.isNotEmpty() } })
        assertEquals(listOf(byteArrayOf(1, 2, 3).toByteString()), end.received)

        serverSocket.send(byteArrayOf(9, 8).toByteString())
        assertArrayEquals(byteArrayOf(9, 8), socket.next().getOrThrow())
        assertNull("open, so no reason yet", socket.closeReason)

        serverSocket.close(4001, "auth")
        assertTrue("the channel closes with the socket", socket.next().isClosed)
        assertEquals("close 4001 auth", socket.closeReason)
        assertNull("a protocol close is not a Retry-After", socket.retryAfterMs)
        socket.close(SpoolCloseCode.NORMAL, "done") // what ScopeSync's worker does on the way out
    }

    @Test
    fun `a close with no reason names the code alone`() {
        val end = accept()
        val socket = dial()
        end.await().close(4000, null)
        assertTrue(socket.next().isClosed)
        assertEquals("close 4000", socket.closeReason)
        socket.close(SpoolCloseCode.NORMAL, "done")
    }

    @Test
    fun `a text frame and an oversize record are dropped, the record after them still arrives`() {
        val end = accept()
        val socket = dial()
        val serverSocket = end.await()

        // Records are binary by definition (§7.1); a text frame is not one. An oversize record is dropped
        // before it is copied into our heap — the heal loop recovers it — and neither closes the socket.
        serverSocket.send("not a record")
        serverSocket.send(ByteArray(MAX_INBOUND_RECORD + 1) { 7 }.toByteString())
        serverSocket.send(ByteArray(MAX_INBOUND_RECORD) { 5 }.toByteString())
        serverSocket.send(byteArrayOf(42).toByteString())

        assertEquals("the largest record allowed still crosses", MAX_INBOUND_RECORD, socket.next().getOrThrow().size)
        assertArrayEquals(byteArrayOf(42), socket.next().getOrThrow())
        assertNull("still open", socket.closeReason)
        socket.close(SpoolCloseCode.NORMAL, "done")
    }

    @Test
    fun `a refused upgrade is reported as its status with the spool's Retry-After as a floor`() {
        server.enqueue(
            MockResponse
                .Builder()
                .code(503)
                .addHeader("Retry-After", "7")
                .build(),
        )
        val socket = dial()

        assertTrue("nothing arrives on a refused socket", socket.next().isClosed)
        assertEquals("http 503", socket.closeReason)
        assertEquals(7_000L, socket.retryAfterMs)
    }

    @Test
    fun `a refused upgrade without Retry-After asks for nothing`() {
        server.enqueue(MockResponse.Builder().code(401).build())
        val socket = dial()
        assertTrue(socket.next().isClosed)
        assertEquals("http 401", socket.closeReason)
        assertNull(socket.retryAfterMs)
    }

    @Test
    fun `a route nothing answers on is unreachable, not an exception name`() {
        // A port that was listening a moment ago and is not now: the connect is refused outright, which is
        // the same `java.net` shape as the black-holed Wi-Fi of work item 50, only faster.
        val port = ServerSocket(0).use { it.localPort }
        val socket = dial("ws://127.0.0.1:$port/spool/v1")

        assertTrue(socket.next().isClosed)
        assertEquals(ScopeSync.UNREACHABLE, socket.closeReason)
        assertNull(socket.retryAfterMs)
    }

    @Test
    fun `closing from our side ends the inbox and sends nothing more`() {
        val end = accept()
        val socket = dial()
        end.await()

        socket.close(SpoolCloseCode.NORMAL, "done")

        assertTrue(socket.next().isClosed)
        assertFalse("a send after close is reported, not thrown", socket.send(byteArrayOf(1)))
    }

    @Test
    fun `fetchSoftware reads the source document with the token stripped`() =
        runBlocking {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("""{"name":"knit-spool","version":"0.3.0","commit":"e8a7790abcdef0123","source":"https://x"}""")
                    .build(),
            )

            val software = checkNotNull(dialer.fetchSoftware(spoolUrl(query = "?k=s3cret")))

            assertEquals("knit-spool 0.3.0 (e8a7790)", software.label)
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("the route is unauthenticated: the credential never leaves the phone", "/source", request.target)
        }

    @Test
    fun `fetchSoftware is null for a missing, non-JSON or refused document`() =
        runBlocking {
            server.enqueue(MockResponse.Builder().code(404).build())
            assertNull("a proxy answering 404", dialer.fetchSoftware(spoolUrl()))
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("<html>not ours</html>")
                    .build(),
            )
            assertNull("a body that is not ours", dialer.fetchSoftware(spoolUrl()))
            assertEquals(2, server.requestCount)
            val release = OkHttpSpoolDialer(allowCleartext = false)
            assertNull("a scheme the build may not dial makes no request", release.fetchSoftware(spoolUrl()))
            assertEquals(2, server.requestCount)
        }

    private fun waitUntil(condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MS)
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(POLL_MS)
        }
        return condition()
    }

    private companion object {
        const val WAIT_MS = 5_000L
        const val POLL_MS = 10L
    }
}
