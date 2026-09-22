package app.getknit.knit.transfer

import app.getknit.knit.mesh.protocol.TransferPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections

/** A shared, ordered log of what a side did — signals sent and radio calls — for ordering assertions. */
class SideLog(
    val name: String,
) {
    val events: MutableList<String> = Collections.synchronizedList(mutableListOf())

    fun note(event: String) {
        events += event
    }
}

/** Wi-Fi Direct over loopback: hosting and joining both "form" on 127.0.0.1, so a real socket pair carries the bytes. */
class FakeDirectWifi(
    private val log: SideLog,
) : DirectWifi {
    var refusal: TransferRefusal? = null
    var hostFails = false

    /** How many join attempts fail before one succeeds (Int.MAX_VALUE = never). */
    var joinFailures = 0
    var joinDelayMs = 0L
    var hostRefusal: TransferRefusal = TransferRefusal.Hotspot

    /** Which loopback family the receiver dials — the stand-in for a client that took an IPv6 link-local. */
    var joinOverIpv6 = false
    val released = Collections.synchronizedList(mutableListOf<Unit>())
    val swept = Collections.synchronizedList(mutableListOf<Unit>())

    override fun refusal(): TransferRefusal? = refusal

    override suspend fun host(
        credentials: GroupCredentials,
        timeoutMs: Long,
    ): HostedGroup {
        log.note("host")
        if (hostFails) throw DirectWifiException("host refused", hostRefusal)
        // Both loopback families, as a real group interface carries both — the listener binds each.
        return HostedGroup(
            listOf(GroupAddress(V4, prefixLength = 8), GroupAddress(V6, prefixLength = 128)),
            frequencyMhz = 5180,
        )
    }

    override suspend fun join(
        credentials: GroupCredentials,
        attemptMs: Long,
    ): JoinedGroup {
        log.note("join")
        if (joinDelayMs > 0) delay(joinDelayMs)
        if (joinFailures > 0) {
            if (joinFailures != Int.MAX_VALUE) joinFailures -= 1
            throw DirectWifiException("no group formed")
        }
        return JoinedGroup(if (joinOverIpv6) V6 else V4)
    }

    override suspend fun release() {
        log.note("release")
        released += Unit
    }

    override suspend fun sweep() {
        log.note("sweep")
        swept += Unit
    }

    companion object {
        private val V4: InetAddress = InetAddress.getByName("127.0.0.1")
        private val V6: InetAddress = InetAddress.getByName("::1")

        /**
         * Whether this JVM can listen on IPv6 loopback at all. Some CI hosts boot with IPv6 off at the kernel,
         * and there `::1` binds with "Protocol family unavailable" — the host then only ever listens on IPv4
         * and a receiver dialling `::1` is refused, which is the environment, not the code. A bind alone is not
         * proof — a runner has bound `::1` and still stalled the transfer — so the probe makes a round trip.
         */
        fun ipv6LoopbackUsable(): Boolean =
            runCatching {
                ServerSocket().use { server ->
                    server.bind(InetSocketAddress(V6, 0))
                    server.soTimeout = 1_000
                    Socket().use { client ->
                        client.connect(InetSocketAddress(V6, server.localPort), 1_000)
                        server.accept().use { accepted ->
                            client.getOutputStream().write(1)
                            accepted.soTimeout = 1_000
                            check(accepted.getInputStream().read() == 1)
                        }
                    }
                }
            }.isSuccess
    }
}

/** In-memory sources and sinks. A source may be told to fail after [failAfter] bytes to simulate a dying link. */
class FakeTransferFiles : TransferFiles {
    class Entry(
        val name: String,
        val bytes: ByteArray,
        val mime: String? = "application/octet-stream",
        val failAfter: Int = -1,
    )

    class Sink(
        override val uri: String,
    ) : TransferSink {
        val buffer = ByteArrayOutputStream()
        var committed = false
        var discarded = false

        override fun stream(): OutputStream = buffer

        override suspend fun commit() {
            committed = true
        }

        override suspend fun discard() {
            discarded = true
        }
    }

    val sources = mutableMapOf<String, Entry>()
    val sinks = Collections.synchronizedList(mutableListOf<Sink>())
    var free = Long.MAX_VALUE
    var sinkFails = false

    override suspend fun openSource(uri: String): TransferSource? {
        val e = sources[uri] ?: return null
        return TransferSource(e.name, e.bytes.size.toLong(), e.mime) {
            if (e.failAfter < 0) ByteArrayInputStream(e.bytes) else FailingInput(e.bytes, e.failAfter)
        }
    }

    override suspend fun createSink(
        name: String,
        mime: String?,
        size: Long,
    ): TransferSink? {
        if (sinkFails) return null
        return Sink("fake://downloads/${sinks.size}/$name").also { sinks += it }
    }

    override fun freeBytes(): Long = free

    private class FailingInput(
        private val bytes: ByteArray,
        private val failAfter: Int,
    ) : InputStream() {
        private var pos = 0

        override fun read(): Int = throw IOException("link died")

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int {
            if (pos >= failAfter) throw IOException("link died at $pos")
            val n = minOf(len, failAfter - pos, bytes.size - pos)
            System.arraycopy(bytes, pos, b, off, n)
            pos += n
            return n
        }
    }
}

/** Delivers each signal to the peer manager on [scope], as the mesh would — asynchronously, in order. */
class FakeTransferSignals(
    private val log: SideLog,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
) : TransferSignals {
    var peer: TransferManager? = null
    var fail = false

    /** Signals matching this are lost on the air (never delivered). */
    var drop: (TransferPayload) -> Boolean = { false }
    val sent = Collections.synchronizedList(mutableListOf<TransferPayload>())

    override suspend fun sendTransferSignal(
        peerId: String,
        payload: TransferPayload,
    ): Boolean {
        if (fail) return false
        sent += payload
        log.note("signal:${payload.phase}")
        if (drop(payload)) return true
        val target = peer ?: return true
        val sentAt = clock()
        scope.launch { target.onSignal(log.name, payload, sentAt) }
        return true
    }
}
