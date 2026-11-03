package app.getknit.knit.transfer

import app.getknit.knit.call.SttFile
import app.getknit.knit.mesh.protocol.TransferPayload
import app.getknit.knit.translate.ModelArchive
import app.getknit.knit.translate.ModelBundleSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * A shared, ordered log of what a side did — signals sent and radio calls — for ordering assertions. Copy-on-write, so
 * a test polling it (`count`, `none`) iterates a snapshot while a retry loop is still appending: a synchronized list
 * read without its lock threw ConcurrentModificationException from `DirectCallPathTest`'s join-retry poll.
 */
class SideLog(
    val name: String,
) {
    val events: MutableList<String> = CopyOnWriteArrayList()

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

    /** A phone that may not host (ADR 2026-10.w3dw): it refuses to send, and still receives. */
    var cannotHost = false

    override fun mayHost(): Boolean = !cannotHost

    override suspend fun host(
        credentials: GroupCredentials,
        timeoutMs: Long,
    ): HostedGroup {
        log.note("host")
        builderChecks(credentials)
        if (hostFails) throw DirectWifiException("host refused", hostRefusal)
        // Both loopback families, as a real group interface carries both — the listener binds each.
        return HostedGroup(
            listOf(GroupAddress(V4, prefixLength = 8), GroupAddress(V6, prefixLength = 128)),
            frequencyMhz = 5180,
        )
    }

    /** Whether each join asked for an IPv4 lease, in order. */
    val joinIpv4 = Collections.synchronizedList(mutableListOf<Boolean>())

    override suspend fun join(
        credentials: GroupCredentials,
        attemptMs: Long,
        ipv4: Boolean,
    ): JoinedGroup {
        log.note("join")
        joinIpv4 += ipv4
        builderChecks(credentials)
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

    /**
     * What `WifiP2pConfig.Builder` refuses before the framework sees a group, with the platform's own exception: a name
     * outside `setNetworkName`'s pattern (AOSP, verbatim here rather than `GroupCredentials`' copy, so the fake cannot
     * agree with the code it tests by construction).
     */
    private fun builderChecks(credentials: GroupCredentials) {
        require(credentials.ssid.matches(Regex("^DIRECT-[a-zA-Z0-9]{2}.*"))) { "network name must starts with the prefix DIRECT-xy." }
    }

    companion object {
        private val V4: InetAddress = InetAddress.getByName("127.0.0.1")
        private val V6: InetAddress = InetAddress.getByName("::1")

        /**
         * A port the host can bind on both loopbacks. The manager's own pick (40000-60000) sits inside Linux's
         * ephemeral range (32768-60999), and on loopback every outbound socket of every test fork draws from
         * that range — a CI run bound `127.0.0.1` into "Address already in use". A real group interface is
         * fresh, so this is the test's problem only: pick below the range and probe before handing it out.
         */
        fun freeLoopbackPort(): Int {
            repeat(50) {
                val port = Random.nextInt(20_000, 32_000)
                val free =
                    listOf(V4, V6).all { address ->
                        runCatching { ServerSocket().use { it.bind(InetSocketAddress(address, port)) } }
                            .fold({ true }, { address == V6 && it !is BindException })
                    }
                if (free) return port
            }
            error("no free loopback port in 20000-32000")
        }

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
        private val commitFails: Boolean = false,
    ) : TransferSink {
        val buffer = ByteArrayOutputStream()
        var committed = false

        @Volatile
        var discarded = false

        override fun stream(): OutputStream = buffer

        override suspend fun commit() {
            if (commitFails) throw IOException("the downloads folder refused it")
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

    /** When set, a sink's commit throws, as a save the downloads folder refused after every byte checked out. */
    var commitFails = false

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
        return Sink("fake://downloads/${sinks.size}/$name", commitFails).also { sinks += it }
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

/**
 * Translation models as pinned byte arrays (ADR 2026-11.ejn8), shared by both sides as one generated table would be:
 * each pin is a direction of two files, and a bundle is their [ModelArchive], as on a phone. The receiver's sink is the
 * real [ModelBundleSink] staged under [root], so the pins are checked as on a phone; its adopt records what landed.
 */
class FakeTransferModels(
    private val root: File,
    private val table: Map<Long, List<Pair<SttFile, ByteArray>>>,
) : TransferModels {
    /** What this side has installed; the sender sends from it, the receiver takes what it lacks. */
    val held: MutableSet<Long> = Collections.synchronizedSet(mutableSetOf())

    /** Pins whose bytes the sender serves with one byte flipped: a peer's copy that does not match the pins. */
    val corrupt: MutableSet<Long> = Collections.synchronizedSet(mutableSetOf())

    /** Pins put in place by a commit, in order. */
    val installed: MutableList<Long> = CopyOnWriteArrayList()

    /** Every staging directory a sink was given. */
    val stages: MutableList<File> = CopyOnWriteArrayList()

    var adoptFails = false

    /** When set, the sink's commit throws before it does anything, its own cleanup included: only the manager can discard. */
    var commitThrows = false

    /** When set, the receiver's check waits for this to open: the sender has sent every byte and awaits the verdict. */
    var checkGate: CountDownLatch? = null

    /** When set, the sender's stream stops after its first file until this opens: a transfer held mid-stream. */
    var hold: CountDownLatch? = null

    override fun wanted(pins: List<Long>): ModelsWanted {
        val take = pins.filter { it in table && it !in held }
        return ModelsWanted(take, archiveSize(take), label = "models:${take.size}")
    }

    /** What the archive of [pins] costs, as both ends work it out from the pins. */
    fun archiveSize(pins: List<Long>): Long = ModelArchive.size(plan(pins))

    /** The archive of [pins] as the sender writes it. */
    fun archive(pins: List<Long>): ByteArray =
        ModelArchive.stream(plan(pins)) { dir, file -> ByteArrayInputStream(bytesOf(dir, file)) }.readBytes()

    override fun bundle(pins: List<Long>): TransferSource? {
        if (!pins.all { it in held && it in table }) return null
        return TransferSource(ModelArchive.FILE_NAME, archiveSize(pins), ModelArchive.MIME) {
            val gate = hold
            var opened = 0
            ModelArchive.stream(plan(pins)) { dir, file ->
                val pin = table.keys.single { dirOf(it) == dir }
                val bytes = bytesOf(dir, file).let { if (pin in corrupt) flipped(it) else it }
                if (opened++ == 1 && gate != null) HeldInput(ByteArrayInputStream(bytes), gate) else ByteArrayInputStream(bytes)
            }
        }
    }

    override suspend fun sink(pins: List<Long>): TransferSink? {
        val stage = File(root, "incoming/${UUID.randomUUID()}").also { stages += it }
        val sink = bundleSink(stage, pins)
        val gate = checkGate
        val throws = commitThrows
        if (!throws && gate == null) return sink
        return object : TransferSink by sink {
            override suspend fun check(): Boolean {
                // On the receiver's io dispatcher, so blocking here holds only this transfer.
                gate?.await(GATE_S, TimeUnit.SECONDS)
                return sink.check()
            }

            override suspend fun commit() {
                if (throws) throw IOException("commit failed before its own cleanup")
                sink.commit()
            }
        }
    }

    private fun bundleSink(
        stage: File,
        pins: List<Long>,
    ): TransferSink =
        ModelBundleSink(
            stage = stage,
            plan = plan(pins),
            adopt = { staged ->
                if (adoptFails) throw IOException("rename failed")
                pins.forEach { pin -> check(checkNotNull(staged[dirOf(pin)]).listFiles().orEmpty().size == 2) }
                installed += pins
                held += pins
            },
        )

    private fun plan(pins: List<Long>) = pins.map { pin -> dirOf(pin) to table.getValue(pin).map { it.first } }

    private fun bytesOf(
        dir: String,
        file: SttFile,
    ): ByteArray = table.getValue(table.keys.single { dirOf(it) == dir }).single { it.first == file }.second

    private fun flipped(bytes: ByteArray) = bytes.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }

    private class HeldInput(
        private val inner: InputStream,
        private val gate: CountDownLatch,
    ) : InputStream() {
        override fun read(): Int {
            gate.await(GATE_S, TimeUnit.SECONDS)
            return inner.read()
        }

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int {
            gate.await(GATE_S, TimeUnit.SECONDS)
            return inner.read(b, off, len)
        }
    }

    companion object {
        private const val GATE_S = 10L

        fun dirOf(pin: Long) = "dir-%08x".format(pin)

        /** A direction of two pinned files from [seed], the second short enough to end mid-chunk. */
        fun direction(seed: Int): List<Pair<SttFile, ByteArray>> =
            listOf(TransferStream.CHUNK_BYTES + 77, 1_003).mapIndexed { i, n ->
                val bytes = Random(seed * 10 + i).nextBytes(n)
                val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                SttFile("f$i.bin", n.toLong(), sha) to bytes
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

    /** What the peer receives in place of each signal sent: a sender's own build writing its fields differently. */
    var rewrite: (TransferPayload) -> TransferPayload = { it }
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
        val delivered = rewrite(payload)
        scope.launch { target.onSignal(log.name, delivered, sentAt) }
        return true
    }
}
