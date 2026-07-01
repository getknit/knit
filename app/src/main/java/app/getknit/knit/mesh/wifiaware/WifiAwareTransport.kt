package app.getknit.knit.mesh.wifiaware

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.ScanResult
import android.net.wifi.aware.AttachCallback
import android.net.wifi.aware.DiscoverySessionCallback
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.PublishConfig
import android.net.wifi.aware.PublishDiscoverySession
import android.net.wifi.aware.SubscribeConfig
import android.net.wifi.aware.SubscribeDiscoverySession
import android.net.wifi.aware.WifiAwareManager
import android.net.wifi.aware.WifiAwareNetworkInfo
import android.net.wifi.aware.WifiAwareNetworkSpecifier
import android.net.wifi.aware.WifiAwareSession
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import app.getknit.knit.identity.Identity
import app.getknit.knit.mesh.DropReason
import app.getknit.knit.mesh.FileKind
import app.getknit.knit.mesh.FileMeta
import app.getknit.knit.mesh.InboundFrame
import app.getknit.knit.mesh.MeshMetrics
import app.getknit.knit.mesh.MeshTransport
import app.getknit.knit.mesh.Peer
import app.getknit.knit.mesh.ReceivedFile
import app.getknit.knit.mesh.TransportHealth
import app.getknit.knit.mesh.isValidBlobHash
import app.getknit.knit.mesh.power.PowerStateSource
import app.getknit.knit.mesh.protocol.Protocol
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [MeshTransport] over **Wi-Fi Aware (NAN)** — the direct replacement for the old Nearby Connections
 * transport. All `android.net.wifi.aware.*` usage is confined to this package so the rest of the app
 * stays transport-independent (talks only to [MeshTransport]).
 *
 * ## Two channels
 * The tiny (~255 B), unreliable discovery channel (`serviceSpecificInfo`) carries only the compact advert
 * ([Protocol.advertise]: `nodeId|version|caps`). The heavy lifting rides a **data-path (NDP) TCP socket**
 * over link-local IPv6 — reliable and high-bandwidth — framed by [AwareFraming].
 *
 * ## Connection model — persistent accept-any responder
 * Every node publishes and subscribes the same service. Each node runs **one persistent accept-any-peer
 * responder**: a single [ServerSocket] plus one NAN network request built from its *publish* session
 * ([WifiAwareNetworkSpecifier.Builder] with no peer handle) that accepts a data path from **any**
 * initiator. This is essential — per-peer responders don't compose (a device already serving one peer
 * can't stand up a second, which stranded a third phone joining a pair), whereas one accept-any responder
 * serves all clients over shared data interfaces.
 *
 * A deterministic tie-break keeps one link per pair: the **larger** nodeId is the client (subscriber-side
 * initiator), the smaller is the server (responder). The client requests a NAN network to the discovered
 * peer, connects a TCP socket to the responder's advertised IPv6+port, and sends its advert as the first
 * [AwareFraming.Type.HELLO] record so the accept-any server learns who connected. The [discoveryLoop]
 * drives client initiations to every eligible discovered peer (serialized, one handshake at a time), so a
 * connected device still links to additional peers — a real multi-node mesh. Once a socket is up the peer
 * becomes a [neighbors] entry, driving the (unchanged) store-and-forward / key-exchange hooks upstream.
 *
 * ## Stability
 * Publish (and its responder) stay up for the life of the session; only *subscribe* is re-armed, and only
 * while isolated — because closing a discovery session tears down the NDPs anchored to it on real
 * chipsets. A per-link [AwareFraming.Type.KEEPALIVE] keeps an idle NDP from timing out. Instant
 * Communication Mode (API 33) speeds discovery + data-path bring-up for brief encounters.
 *
 * Permissions are gated by onboarding; the mesh starts regardless and degrades if a permission/hardware
 * is missing, so the Aware calls are marked [SuppressLint] for "MissingPermission".
 */
@SuppressLint("MissingPermission")
@Suppress("TooManyFunctions") // a transport is inherently many small methods (lifecycle, discovery, sockets, files)
class WifiAwareTransport(
    context: Context,
    private val identity: Identity,
    private val scope: CoroutineScope,
    private val metrics: MeshMetrics,
    private val powerState: PowerStateSource,
) : MeshTransport {

    private val appContext = context.applicationContext
    private val awareManager = appContext.getSystemService(Context.WIFI_AWARE_SERVICE) as WifiAwareManager?
    private val connectivity =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /** False on hardware without Wi-Fi Aware — the transport stays [TransportHealth.Degraded] and the UI gates. */
    private val hasHardware =
        awareManager != null && appContext.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_WIFI_AWARE)

    private val _neighbors = MutableStateFlow<Set<Peer>>(emptySet())
    override val neighbors = _neighbors.asStateFlow()

    private val _health = MutableStateFlow(TransportHealth.Healthy)
    override val health = _health.asStateFlow()

    private val _inbound = MutableSharedFlow<InboundFrame>(extraBufferCapacity = 256)
    override val inbound = _inbound.asSharedFlow()

    private val _incomingFiles = MutableSharedFlow<ReceivedFile>(extraBufferCapacity = 32)
    override val incomingFiles = _incomingFiles.asSharedFlow()

    // Aware + connectivity callbacks are delivered on this dedicated thread's handler.
    private val callbackThread = HandlerThread("wifi-aware-cb").apply { start() }
    private val handler = Handler(callbackThread.looper)

    private lateinit var localNodeId: String
    private var instantSupported = false

    @Volatile private var session: WifiAwareSession? = null
    @Volatile private var publishSession: PublishDiscoverySession? = null
    @Volatile private var subscribeSession: SubscribeDiscoverySession? = null

    // The one accept-any responder: its ServerSocket, network callback, and the accept loop.
    @Volatile private var responderSocket: ServerSocket? = null
    @Volatile private var responderCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var acceptJob: Job? = null

    // Established links, keyed by peer nodeId. A node is a "neighbor" once its socket is up.
    private val peers = ConcurrentHashMap<String, PeerConn>()

    // Connection bookkeeping guarded by [lock]: peers we've discovered (with a live PeerHandle), the
    // client handshake in flight (serialized: at most one), and per-peer failure backoff.
    private val lock = Any()
    private val inFlight = HashSet<String>()
    private val discovered = HashMap<String, DiscoveredPeer>()
    private val retryAfter = HashMap<String, Long>()

    // Wakes the discovery loop immediately (a peer discovered, a link came up/down, heal(), screen-on)
    // instead of waiting out its idle.
    private val healSignal = Channel<Unit>(Channel.CONFLATED)

    private var powerJob: Job? = null
    private var loopJob: Job? = null
    private var availabilityRegistered = false

    private data class DiscoveredPeer(val advert: Protocol.PeerWire, val peerHandle: PeerHandle)

    override fun start() {
        if (!hasHardware) {
            _health.value = TransportHealth.Degraded
            Log.w(TAG, "Wi-Fi Aware unsupported on this device; mesh disabled")
            return
        }
        scope.launch {
            localNodeId = identity.nodeId()
            instantSupported = runCatching {
                awareManager?.characteristics?.isInstantCommunicationModeSupported() == true
            }.getOrDefault(false)
            registerAvailability()
            attach()
            loopJob = scope.launch { discoveryLoop() }
            powerJob = scope.launch { powerState.state.drop(1).collect { healSignal.trySend(Unit) } }
        }
    }

    override fun stop() {
        loopJob?.cancel()
        powerJob?.cancel()
        unregisterAvailability()
        peers.keys.toList().forEach { teardownPeer(it) }
        stopResponder()
        runCatching { publishSession?.close() }
        runCatching { subscribeSession?.close() }
        runCatching { session?.close() }
        publishSession = null
        subscribeSession = null
        session = null
        synchronized(lock) { inFlight.clear(); discovered.clear(); retryAfter.clear() }
        _neighbors.value = emptySet()
    }

    override fun heal() {
        if (!hasHardware) return
        healSignal.trySend(Unit)
    }

    override suspend fun send(wire: WireEnvelope, to: Peer?) {
        val bytes = WireCodec.encodeWire(wire)
        val targets = if (to == null) peers.values.toList() else listOfNotNull(peers[to.nodeId])
        targets.forEach { it.outbound.trySend(Outbound.Frame(bytes)) }
        if (targets.isNotEmpty()) metrics.onBytesSent(bytes.size.toLong() * targets.size)
    }

    override suspend fun sendFile(file: File, to: Peer, meta: FileMeta) {
        peers[to.nodeId]?.outbound?.trySend(Outbound.FileSend(file, meta))
    }

    // --- Attach / discovery ---

    private fun attach() {
        val mgr = awareManager ?: return
        if (!mgr.isAvailable) {
            _health.value = TransportHealth.Degraded
            return
        }
        if (session != null) return
        mgr.attach(object : AttachCallback() {
            override fun onAttached(newSession: WifiAwareSession) {
                session = newSession
                _health.value = TransportHealth.Healthy
                startPublish()
                startSubscribe()
            }

            override fun onAttachFailed() {
                _health.value = TransportHealth.Degraded
                Log.w(TAG, "Wi-Fi Aware attach failed")
            }

            override fun onAwareSessionTerminated() {
                session = null
                publishSession = null
                subscribeSession = null
                stopResponder()
                _health.value = TransportHealth.Degraded
            }
        }, handler)
    }

    private fun startPublish() {
        val builder = PublishConfig.Builder()
            .setServiceName(SERVICE_NAME)
            .setServiceSpecificInfo(Protocol.advertise(localNodeId).encodeToByteArray())
        if (instantSupported) builder.setInstantCommunicationModeEnabled(true, INSTANT_BAND)
        session?.publish(builder.build(), publishCallback, handler)
    }

    private fun startSubscribe() {
        val builder = SubscribeConfig.Builder().setServiceName(SERVICE_NAME)
        if (instantSupported) builder.setInstantCommunicationModeEnabled(true, INSTANT_BAND)
        session?.subscribe(builder.build(), subscribeCallback, handler)
    }

    /**
     * Re-fires Wi-Fi Aware's one-shot discovery by restarting **only the subscribe** session (publish and
     * its responder stay up, so we remain discoverable/serviceable and no live NDP anchored to publish is
     * disturbed). Called by [discoveryLoop] **only while isolated** — closing the subscribe session tears
     * down any client-side NDP anchored to it, and while isolated there are none to lose.
     */
    private fun rearmSubscribe() {
        if (session == null) return
        synchronized(lock) { discovered.clear() }
        runCatching { subscribeSession?.close() }
        startSubscribe()
    }

    /**
     * The connection engine: drives client-side initiations and self-heals discovery, without ever
     * disturbing a live link. Each tick it initiates to the next eligible discovered peer (serialized —
     * one handshake at a time), and only when isolated with nothing to initiate does it re-arm subscribe
     * to re-discover. Woken early by [healSignal] (peer discovered, link up/down, heal(), screen-on).
     */
    private suspend fun discoveryLoop() {
        while (scope.isActive) {
            withTimeoutOrNull(rediscoverDelayMs()) { healSignal.receive() }
            when {
                !hasHardware -> Unit
                session == null -> attach() // radio came back / first attach failed
                // driveInitiations() runs (and may initiate) as part of the guard; re-arm only when it
                // found nothing to do and we're isolated (restarting subscribe is safe with no live link).
                !driveInitiations() && peers.isEmpty() -> rearmSubscribe()
                else -> Unit
            }
        }
    }

    /**
     * Initiates to the next discovered peer we're the client for and aren't linked to. Returns true if a
     * handshake is in flight or we started one (so the loop shouldn't re-arm), false if there's nothing to
     * initiate to. Serialized: only one client handshake at a time (NAN data interfaces are scarce).
     */
    private fun driveInitiations(): Boolean {
        val now = SystemClock.elapsedRealtime()
        val target = synchronized(lock) {
            if (inFlight.isNotEmpty()) return true
            discovered.entries.firstOrNull { (nodeId, _) ->
                localNodeId > nodeId && nodeId !in peers.keys &&
                    (retryAfter[nodeId]?.let { now >= it } ?: true)
            }?.let { it.key to it.value }
        } ?: return false
        initiateTo(target.first, target.second.advert, target.second.peerHandle)
        return true
    }

    private fun rediscoverDelayMs(): Long {
        val base = if (peers.isEmpty()) REDISCOVER_LONELY_MS else REDISCOVER_IDLE_MS
        val power = powerState.state.value
        return if (power.interactive || power.charging) base else base * 2
    }

    private val publishCallback = object : DiscoverySessionCallback() {
        override fun onPublishStarted(s: PublishDiscoverySession) {
            publishSession = s
            startResponder() // one accept-any responder for the life of this publish session
        }

        override fun onSessionConfigFailed() {
            Log.w(TAG, "publish config failed")
        }
    }

    private val subscribeCallback = object : DiscoverySessionCallback() {
        override fun onSubscribeStarted(s: SubscribeDiscoverySession) {
            subscribeSession = s
        }

        override fun onServiceDiscovered(
            peerHandle: PeerHandle,
            serviceSpecificInfo: ByteArray,
            matchFilter: List<ByteArray>,
        ) {
            onDiscovered(peerHandle, serviceSpecificInfo)
        }

        override fun onServiceLost(peerHandle: PeerHandle, reason: Int) {
            synchronized(lock) { discovered.entries.removeAll { it.value.peerHandle == peerHandle } }
        }

        override fun onSessionConfigFailed() {
            Log.w(TAG, "subscribe config failed")
        }
    }

    /** Record a discovered peer and wake the loop, which decides (tie-break) whether to initiate. */
    private fun onDiscovered(peerHandle: PeerHandle, ssi: ByteArray) {
        if (ssi.isEmpty()) return
        val advert = Protocol.parse(ssi.decodeToString())
        val peerNodeId = advert.nodeId
        if (peerNodeId == localNodeId) return
        synchronized(lock) { discovered[peerNodeId] = DiscoveredPeer(advert, peerHandle) }
        healSignal.trySend(Unit)
    }

    // --- Client side (initiator) ---

    private fun initiateTo(peerNodeId: String, advert: Protocol.PeerWire, peerHandle: PeerHandle) {
        val sub = subscribeSession ?: return
        if (!beginConnect(peerNodeId)) return
        Log.i(TAG, "initiating to $peerNodeId")
        val specifier = WifiAwareNetworkSpecifier.Builder(sub, peerHandle).setPskPassphrase(PSK).build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
            .setNetworkSpecifier(specifier)
            .build()
        val done = AtomicBoolean(false)
        lateinit var cb: ConnectivityManager.NetworkCallback
        cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val info = caps.transportInfo as? WifiAwareNetworkInfo ?: return
                val ip = info.peerIpv6Addr ?: return
                if (!done.compareAndSet(false, true)) return
                val port = info.port
                scope.launch(Dispatchers.IO) { connectAndRegister(peerNodeId, advert, network, ip, port, cb) }
            }

            override fun onUnavailable() {
                // Timeout overload fired: the NDP never came up. Release so the interface + inFlight slot
                // are freed (without this the request leaks and eventually exhausts NAN interfaces).
                if (done.compareAndSet(false, true)) failConnect(peerNodeId, cb)
            }

            override fun onLost(network: Network) {
                teardownPeer(peerNodeId)
            }
        }
        runCatching { connectivity.requestNetwork(request, cb, handler, HANDSHAKE_TIMEOUT_MS) }.onFailure {
            failConnect(peerNodeId, cb)
            Log.w(TAG, "client requestNetwork failed for $peerNodeId", it)
        }
    }

    private fun connectAndRegister(
        peerNodeId: String,
        advert: Protocol.PeerWire,
        network: Network,
        ip: java.net.Inet6Address,
        port: Int,
        cb: ConnectivityManager.NetworkCallback,
    ) {
        val socket = runCatching { network.socketFactory.createSocket(ip, port) }.getOrNull()
            ?: return failConnect(peerNodeId, cb)
        // Send our identity first so the accept-any responder on the other side knows who connected.
        val sent = runCatching {
            val out = socket.getOutputStream()
            out.write(AwareFraming.encode(AwareFraming.Type.HELLO, helloBytes()))
            out.flush()
        }.isSuccess
        if (!sent) {
            runCatching { socket.close() }
            return failConnect(peerNodeId, cb)
        }
        registerConn(peerNodeId, advert, socket, BufferedInputStream(socket.getInputStream()), cb)
    }

    // --- Server side (persistent accept-any responder) ---

    private fun startResponder() {
        val pub = publishSession ?: return
        stopResponder()
        val ss = runCatching { ServerSocket(0) }.getOrElse {
            Log.w(TAG, "responder ServerSocket bind failed", it)
            return
        }
        responderSocket = ss
        val specifier = WifiAwareNetworkSpecifier.Builder(pub).setPskPassphrase(PSK).setPort(ss.localPort).build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
            .setNetworkSpecifier(specifier)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (acceptJob?.isActive != true) acceptJob = scope.launch(Dispatchers.IO) { acceptLoop(ss) }
            }

            override fun onLost(network: Network) {
                // The shared responder network dropped; server-side links on it will end via their read
                // loops. Re-arm after a beat so we can serve clients again.
                Log.i(TAG, "responder network lost; re-arming")
                handler.postDelayed({ if (session != null && publishSession != null) startResponder() }, RESPONDER_REARM_MS)
            }
        }
        responderCallback = cb
        runCatching { connectivity.requestNetwork(request, cb, handler) }.onFailure {
            Log.w(TAG, "responder requestNetwork failed", it)
            stopResponder()
        }
        Log.i(TAG, "responder listening on port ${ss.localPort}")
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (scope.isActive && !ss.isClosed) {
            val socket = runCatching { ss.accept() }.getOrNull() ?: break
            scope.launch(Dispatchers.IO) { handleAcceptedSocket(socket) }
        }
    }

    /** A client connected to our accept-any responder: read its identity (HELLO), then register the link. */
    private fun handleAcceptedSocket(socket: Socket) {
        val input = BufferedInputStream(socket.getInputStream())
        runCatching { socket.soTimeout = HANDSHAKE_TIMEOUT_MS } // bound the initial identity read
        val first = runCatching { AwareFraming.read(input) }.getOrNull()
        if (first?.type != AwareFraming.Type.HELLO) {
            runCatching { socket.close() }
            return
        }
        val advert = Protocol.parse(first.payload.decodeToString())
        val clientNodeId = advert.nodeId
        // Enforce the tie-break server side (we must be the smaller id) and don't double-link.
        if (clientNodeId == localNodeId || localNodeId >= clientNodeId || clientNodeId in peers.keys) {
            runCatching { socket.close() }
            return
        }
        runCatching { socket.soTimeout = 0 } // back to blocking reads for the normal loop
        Log.i(TAG, "accepted client $clientNodeId")
        registerConn(clientNodeId, advert, socket, input, callback = null) // shared responder: no per-peer callback
    }

    private fun stopResponder() {
        acceptJob?.cancel()
        acceptJob = null
        responderCallback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        responderCallback = null
        responderSocket?.let { runCatching { it.close() } } // unblocks acceptLoop's accept()
        responderSocket = null
    }

    // --- Link registration / teardown ---

    private fun registerConn(
        peerNodeId: String,
        advert: Protocol.PeerWire,
        socket: Socket,
        input: BufferedInputStream,
        callback: ConnectivityManager.NetworkCallback?,
    ) {
        val conn = PeerConn(peerNodeId, advert, socket, callback)
        val prev = peers.put(peerNodeId, conn)
        prev?.close() // a stale link to the same peer (shouldn't happen, but never leak it)
        synchronized(lock) { inFlight.remove(peerNodeId); retryAfter.remove(peerNodeId) }
        conn.readerJob = scope.launch(Dispatchers.IO) { readLoop(conn, input) }
        conn.writerJob = scope.launch(Dispatchers.IO) { writeLoop(conn) }
        conn.keepAliveJob = scope.launch {
            while (scope.isActive) {
                delay(KEEPALIVE_INTERVAL_MS)
                if (conn.outbound.trySend(Outbound.KeepAlive).isFailure) break // link torn down
            }
        }
        refreshNeighbors()
        Log.i(TAG, "link up: $peerNodeId (${peers.size} neighbor(s))")
        healSignal.trySend(Unit) // there may be more discovered peers to initiate to
    }

    private fun failConnect(peerNodeId: String, callback: ConnectivityManager.NetworkCallback) {
        runCatching { connectivity.unregisterNetworkCallback(callback) }
        synchronized(lock) {
            inFlight.remove(peerNodeId)
            retryAfter[peerNodeId] = SystemClock.elapsedRealtime() + CONNECT_BACKOFF_MS
        }
        Log.i(TAG, "handshake with $peerNodeId ended without a link (timed out / peer didn't respond)")
    }

    private fun teardownPeer(peerNodeId: String) {
        val conn = peers.remove(peerNodeId) ?: return
        conn.close()
        // Only client links own a per-peer network callback; server links share the responder — leave it.
        conn.networkCallback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        refreshNeighbors()
        healSignal.trySend(Unit) // wake the loop to re-discover + reconnect
        Log.i(TAG, "link down: $peerNodeId (${peers.size} neighbor(s))")
    }

    // --- Socket I/O ---

    private suspend fun readLoop(conn: PeerConn, input: BufferedInputStream) {
        try {
            while (scope.isActive) {
                val msg = AwareFraming.read(input) ?: break
                when (msg.type) {
                    AwareFraming.Type.FRAME -> handleFrame(conn, msg.payload)
                    AwareFraming.Type.FILE_HEADER -> conn.beginRxFile(msg.payload)
                    AwareFraming.Type.FILE_CHUNK -> conn.appendRxFile(msg.payload)
                    AwareFraming.Type.FILE_END -> endRxFile(conn)
                    AwareFraming.Type.KEEPALIVE -> Unit // just liveness; nothing to deliver
                    AwareFraming.Type.HELLO -> Unit // identity already consumed at accept; ignore any stray
                }
            }
        } catch (e: IOException) {
            Log.d(TAG, "read loop ended for ${conn.nodeId}: ${e.message}")
        } finally {
            teardownPeer(conn.nodeId)
        }
    }

    private fun handleFrame(conn: PeerConn, bytes: ByteArray) {
        val wire = WireCodec.decodeWire(bytes)
        if (wire == null) {
            metrics.onDropped(DropReason.DECODE_FAILED)
            return
        }
        val envelope = WireCodec.decodeEnvelope(wire.signed)
        if (envelope == null) {
            metrics.onDropped(DropReason.DECODE_FAILED)
            return
        }
        _inbound.tryEmit(InboundFrame(wire, envelope, conn.nodeId))
    }

    /**
     * Drains a peer's outbound queue to its socket. Frames are written immediately; a file is streamed
     * as header→chunks→end, but pending frames are flushed *between* chunks so a large blob never stalls
     * live traffic. Only one file streams at a time (later files wait in [PeerConn.stash]).
     */
    private suspend fun writeLoop(conn: PeerConn) {
        try {
            val out = BufferedOutputStream(conn.socket.getOutputStream())
            while (scope.isActive) {
                val item = conn.nextOutbound() ?: break
                when (item) {
                    is Outbound.Frame -> {
                        AwareFraming.write(out, AwareFraming.Type.FRAME, item.bytes)
                        out.flush()
                    }
                    is Outbound.FileSend -> streamFile(conn, out, item)
                    is Outbound.KeepAlive -> {
                        AwareFraming.write(out, AwareFraming.Type.KEEPALIVE)
                        out.flush()
                    }
                }
            }
        } catch (e: IOException) {
            Log.d(TAG, "write loop ended for ${conn.nodeId}: ${e.message}")
            teardownPeer(conn.nodeId)
        }
    }

    private fun streamFile(conn: PeerConn, out: OutputStream, item: Outbound.FileSend) {
        val header = FileHeaderWire(item.meta.kind.name, item.meta.key, item.meta.mime)
        AwareFraming.write(out, AwareFraming.Type.FILE_HEADER, AwareFraming.encodeFileHeader(header))
        item.file.inputStream().use { input ->
            val buf = ByteArray(AwareFraming.FILE_CHUNK_BYTES)
            while (true) {
                conn.drainFramesInto(out) // interleave live frames between chunks
                val n = input.read(buf)
                if (n == -1) break
                AwareFraming.write(out, AwareFraming.Type.FILE_CHUNK, if (n == buf.size) buf else buf.copyOf(n))
            }
        }
        AwareFraming.write(out, AwareFraming.Type.FILE_END)
        out.flush()
    }

    private fun endRxFile(conn: PeerConn) {
        val (temp, meta) = conn.finishRxFile() ?: return
        scope.launch(Dispatchers.IO) { finalizeIncomingFile(conn.nodeId, temp, meta) }
    }

    /** Moves a fully-received file into the cache under a safe name and announces it (avatar by node, attachment by hash). */
    private fun finalizeIncomingFile(nodeId: String, temp: File, meta: FileMeta) {
        // [meta.key] is peer-supplied and interpolated into the filename: reject anything but a 64-hex
        // content hash so a "../" can't escape the cache dir (path traversal → arbitrary in-sandbox write).
        if (!isValidBlobHash(meta.key)) {
            temp.delete()
            Log.w(TAG, "Rejecting ${meta.kind} from $nodeId: malformed blob key")
            return
        }
        val dest = when (meta.kind) {
            FileKind.AVATAR -> {
                appContext.cacheDir.listFiles { f -> f.name.startsWith("avatar-$nodeId-") }?.forEach { it.delete() }
                File(appContext.cacheDir, "avatar-$nodeId-${meta.key}.jpg")
            }
            FileKind.ATTACHMENT -> File(appContext.cacheDir, "attach-${meta.key}.${extForMime(meta.mime)}")
        }
        val cacheRoot = appContext.cacheDir.canonicalPath + File.separator
        if (!dest.canonicalPath.startsWith(cacheRoot)) {
            temp.delete()
            Log.w(TAG, "Rejecting ${meta.kind} from $nodeId: path escapes cache dir")
            return
        }
        runCatching {
            temp.copyTo(dest, overwrite = true)
            temp.delete()
        }.onSuccess {
            _incomingFiles.tryEmit(ReceivedFile(nodeId, dest.absolutePath, meta.kind, meta.key, meta.mime))
        }.onFailure {
            temp.delete()
            dest.delete()
            Log.w(TAG, "Failed saving ${meta.kind} from $nodeId", it)
        }
    }

    // --- Connection admission ---

    private fun beginConnect(peerNodeId: String): Boolean = synchronized(lock) {
        if (peerNodeId in peers.keys || peerNodeId in inFlight) return false
        // Serialize client handshakes: one at a time (NAN has only 1–2 data interfaces; firing several
        // requestNetwork at once exhausts them and wedges the radio).
        if (inFlight.isNotEmpty()) return false
        retryAfter[peerNodeId]?.let { if (SystemClock.elapsedRealtime() < it) return false } // backing off
        if (peers.size + inFlight.size >= MAX_LINKS) {
            Log.i(TAG, "deferring link to $peerNodeId: at MAX_LINKS")
            return false
        }
        inFlight.add(peerNodeId)
        true
    }

    private fun refreshNeighbors() {
        _neighbors.value = peers.values.map { Peer(it.nodeId, it.advert.protoVersion, it.advert.capabilities) }.toSet()
    }

    private fun helloBytes(): ByteArray = Protocol.advertise(localNodeId).encodeToByteArray()

    private fun extForMime(mime: String): String = when (mime.lowercase()) {
        "image/gif" -> "gif"
        "image/png" -> "png"
        "image/webp" -> "webp"
        else -> "jpg"
    }

    // --- Availability ---

    private val availabilityReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val mgr = awareManager ?: return
            if (mgr.isAvailable) {
                if (session == null) scope.launch { attach() }
            } else {
                _health.value = TransportHealth.Degraded
                peers.keys.toList().forEach { teardownPeer(it) }
                stopResponder()
                runCatching { publishSession?.close() }
                runCatching { subscribeSession?.close() }
                runCatching { session?.close() }
                session = null
                publishSession = null
                subscribeSession = null
            }
        }
    }

    private fun registerAvailability() {
        if (availabilityRegistered) return
        appContext.registerReceiver(
            availabilityReceiver,
            IntentFilter(WifiAwareManager.ACTION_WIFI_AWARE_STATE_CHANGED),
        )
        availabilityRegistered = true
    }

    private fun unregisterAvailability() {
        if (!availabilityRegistered) return
        runCatching { appContext.unregisterReceiver(availabilityReceiver) }
        availabilityRegistered = false
    }

    /** One live data-path link to a peer: the socket, its outbound queue, I/O jobs, and inbound-file state. */
    private inner class PeerConn(
        val nodeId: String,
        val advert: Protocol.PeerWire,
        val socket: Socket,
        // Non-null for a client link (its own per-peer network request); null for a server link (shares
        // the accept-any responder network — which must NOT be unregistered when one client leaves).
        val networkCallback: ConnectivityManager.NetworkCallback?,
    ) {
        val outbound = Channel<Outbound>(Channel.UNLIMITED)
        var readerJob: Job? = null
        var writerJob: Job? = null
        var keepAliveJob: Job? = null

        // Files queued behind an in-progress file transfer (only one streams at a time).
        private val stash = ArrayDeque<Outbound.FileSend>()

        // Inbound file reassembly (one active file per socket, so no per-file id needed).
        private var rxOut: OutputStream? = null
        private var rxTemp: File? = null
        private var rxMeta: FileMeta? = null
        private var rxBytes = 0L
        private var rxAborted = false

        /** Next outbound item: a file stashed during a prior transfer, else the channel head. */
        suspend fun nextOutbound(): Outbound? =
            stash.removeFirstOrNull() ?: outbound.receiveCatching().getOrNull()

        /** Write any queued frames now (called between file chunks); stash any files for later. */
        fun drainFramesInto(out: OutputStream) {
            while (true) {
                val item = outbound.tryReceive().getOrNull() ?: break
                when (item) {
                    is Outbound.Frame -> AwareFraming.write(out, AwareFraming.Type.FRAME, item.bytes)
                    is Outbound.FileSend -> stash.addLast(item)
                    is Outbound.KeepAlive -> Unit // file chunks are already keeping the link alive
                }
            }
        }

        fun beginRxFile(headerPayload: ByteArray) {
            closeRx()
            val header = AwareFraming.decodeFileHeader(headerPayload) ?: run { rxAborted = true; return }
            val temp = File.createTempFile("nan-rx-", ".tmp", appContext.cacheDir)
            rxTemp = temp
            rxOut = BufferedOutputStream(temp.outputStream())
            rxMeta = FileMeta(
                kind = runCatching { FileKind.valueOf(header.kind) }.getOrDefault(FileKind.ATTACHMENT),
                key = header.key,
                mime = header.mime,
            )
            rxBytes = 0L
            rxAborted = false
        }

        fun appendRxFile(chunk: ByteArray) {
            if (rxAborted) return
            val out = rxOut ?: return
            rxBytes += chunk.size
            if (rxBytes > MAX_INCOMING_FILE_BYTES) {
                Log.w(TAG, "incoming file from $nodeId exceeds ceiling; aborting")
                abortRx()
                return
            }
            runCatching { out.write(chunk) }.onFailure { abortRx() }
        }

        /** Finish the active file, returning (temp, meta) to finalize, or null if none/aborted. */
        fun finishRxFile(): Pair<File, FileMeta>? {
            val out = rxOut
            val temp = rxTemp
            val meta = rxMeta
            rxOut = null; rxTemp = null; rxMeta = null
            runCatching { out?.close() }
            if (rxAborted || temp == null || meta == null) {
                temp?.delete()
                return null
            }
            return temp to meta
        }

        private fun abortRx() {
            rxAborted = true
            runCatching { rxOut?.close() }
            rxOut = null
            rxTemp?.delete()
            rxTemp = null
        }

        private fun closeRx() {
            runCatching { rxOut?.close() }
            rxOut = null
            rxTemp?.delete()
            rxTemp = null
            rxMeta = null
        }

        fun close() {
            readerJob?.cancel()
            writerJob?.cancel()
            keepAliveJob?.cancel()
            outbound.close()
            closeRx()
            runCatching { socket.close() }
        }
    }

    private sealed interface Outbound {
        class Frame(val bytes: ByteArray) : Outbound
        class FileSend(val file: File, val meta: FileMeta) : Outbound
        data object KeepAlive : Outbound
    }

    private companion object {
        const val TAG = "WifiAwareTransport"

        // The NAN service both nodes publish/subscribe. Reuses the ".v2" wire-break marker so a build
        // that predates a future coordinated wire break hard-partitions rather than silently misdecodes.
        const val SERVICE_NAME = "app.getknit.knit.MESH.v2"

        // Fixed app-wide passphrase for link-layer (NDP) encryption. Real authentication is the per-frame
        // Ed25519 signature + E2E layer above the transport; this only keeps the data path off open air.
        const val PSK = "knit-mesh-nan-psk-v1"

        // 2.4 GHz for instant mode: better range than 5 GHz (range is priority #2), at some throughput cost.
        const val INSTANT_BAND = ScanResult.WIFI_BAND_24_GHZ

        // Cap on total links; client handshakes are also serialized one-at-a-time.
        const val MAX_LINKS = 6

        // Timeout for a client handshake (its requestNetwork timeout overload fires onUnavailable) and for
        // the server's initial identity read, so an attempt that can't complete frees its slot vs. leaking.
        const val HANDSHAKE_TIMEOUT_MS = 15_000

        // After a failed handshake, skip re-initiating to that peer for this long, so a different
        // discovered peer gets a turn rather than storming the same failing one.
        const val CONNECT_BACKOFF_MS = 10_000L

        // Delay before re-arming the responder after its network drops, to avoid a tight retry loop.
        const val RESPONDER_REARM_MS = 1_000L

        // Re-discovery cadence for the discovery loop (restarts subscribe to re-fire Wi-Fi Aware's one-shot
        // discovery — only while isolated). Aggressive when alone so a dropped link reconnects in seconds;
        // long once we have a neighbor (a change wakes us via healSignal). Doubled when screen-off on battery.
        const val REDISCOVER_LONELY_MS = 8_000L
        const val REDISCOVER_IDLE_MS = 120_000L

        // Idle keepalive over each live socket, so an inactive NDP between two stationary phones isn't torn
        // down by NAN's inactivity timeout. Small record, comfortably under any plausible teardown window.
        const val KEEPALIVE_INTERVAL_MS = 15_000L

        // Receive-side ceiling on a file, matching the send cap (AttachmentStore.MAX_BYTES = 8 MiB) plus
        // headroom for E2E framing (GCM IV+tag) — refuses an unbounded malicious stream that exhausts disk.
        const val MAX_INCOMING_FILE_BYTES = 8L * 1024 * 1024 + 64 * 1024
    }
}
