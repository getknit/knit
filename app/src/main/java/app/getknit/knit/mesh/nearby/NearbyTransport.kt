package app.getknit.knit.mesh.nearby

import android.annotation.SuppressLint
import android.content.Context
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
import app.getknit.knit.mesh.power.PowerPolicy
import app.getknit.knit.mesh.power.PowerStateSource
import app.getknit.knit.mesh.protocol.Protocol
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * [MeshTransport] over Google Nearby Connections using the [Strategy.P2P_CLUSTER] many-to-many
 * topology. Encapsulates all GMS specifics so the rest of the app stays transport-independent.
 *
 * Mirrors the proven mechanics of the legacy app: advertise continuously, discover in periodic
 * bursts (Nearby can't reliably advertise and discover at once), serialize connection requests,
 * auto-accept every peer (open mesh), and back off discovery as the neighbor count grows. The node
 * id is carried as the Nearby endpoint name so peers recognize each other and reject self-discovery.
 *
 * For battery: advertising and discovery run in BLE-only low-power mode, and the discovery duty cycle
 * is driven by [PowerPolicy] from the current [PowerStateSource] (screen on / charging → scan often;
 * screen off on battery → scan rarely). Advertising stays on regardless so a backgrounded device can
 * still relay. A change in power state wakes the discovery loop early via [healSignal].
 *
 * Permissions are gated by the onboarding flow; the mesh starts regardless and degrades if a
 * permission was denied, so Nearby calls are marked [SuppressLint] for "MissingPermission".
 */
@SuppressLint("MissingPermission")
class NearbyTransport(
    context: Context,
    private val identity: Identity,
    private val scope: CoroutineScope,
    private val metrics: MeshMetrics,
    private val powerState: PowerStateSource,
) : MeshTransport {

    private val appContext = context.applicationContext
    private val client: ConnectionsClient = Nearby.getConnectionsClient(appContext)

    private val _neighbors = MutableStateFlow<Set<Peer>>(emptySet())
    override val neighbors = _neighbors.asStateFlow()

    // Radio health, driven by the discovery loop and advertising callbacks. Goes Degraded when a
    // start-advertise/discover fails (the symptom of Quick Share seizing the radios) and back to
    // Healthy once the radio is usable again. Surfaced to the Diagnostics screen.
    private val _health = MutableStateFlow(TransportHealth.Healthy)
    override val health = _health.asStateFlow()

    private val _inbound = MutableSharedFlow<InboundFrame>(extraBufferCapacity = 256)
    override val inbound = _inbound.asSharedFlow()

    private val _incomingFiles = MutableSharedFlow<ReceivedFile>(extraBufferCapacity = 32)
    override val incomingFiles = _incomingFiles.asSharedFlow()

    // In-flight FILE payloads: payloadId -> the source endpoint + destination content Uri.
    private val pendingFiles = ConcurrentHashMap<Long, PendingFile>()

    // A FILE payload is described by a tiny header BYTES sent just ahead of it (keyed by the file's
    // payload id, which Nearby preserves end-to-end). The file is finalized once both the header has
    // arrived and the transfer has completed — either order is tolerated.
    private val fileHeaders = ConcurrentHashMap<Long, FileMeta>()
    private val completedFiles = ConcurrentHashMap.newKeySet<Long>()

    private data class PendingFile(val endpointId: String, val uri: android.net.Uri)

    // endpointId <-> nodeId (the Nearby endpoint name encodes the peer's nodeId; see [Protocol.parse])
    private val endpointToNode = ConcurrentHashMap<String, String>()
    private val nodeToEndpoint = ConcurrentHashMap<String, String>()
    // endpointId -> the peer's advertised protocol version + capabilities (parsed from endpoint-info).
    private val peerWire = ConcurrentHashMap<String, Protocol.PeerWire>()
    private val connected = ConcurrentHashMap.newKeySet<String>() // connected endpointIds
    private val connecting = ConcurrentHashMap.newKeySet<String>() // in-flight connection requests

    // Per-endpoint connection backoff. A failed connect (often a transient BLE GATT-bootstrap failure
    // when the peer's radio is busy) widens the next-attempt delay exponentially, so an unreachable
    // peer isn't re-attempted every discovery burst — which would waste BLE airtime that the working
    // links and discovery need. Cleared on a successful connection so a later drop reconnects promptly.
    private data class Retry(val attemptAt: Long, val delayMs: Long)
    private val retry = ConcurrentHashMap<String, Retry>()

    private lateinit var localNodeId: String
    private var discoveryJob: Job? = null
    private var powerJob: Job? = null

    // Signaled by heal() to wake the discovery loop early (re-scan now instead of after backoff).
    private val healSignal = Channel<Unit>(Channel.CONFLATED)

    // Serializes connection requests onto a single thread — Nearby fails if several are issued at once.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val connectDispatcher = Dispatchers.IO.limitedParallelism(1)

    override fun start() {
        scope.launch {
            localNodeId = identity.nodeId()
            startAdvertising()
            discoveryJob = scope.launch { discoveryLoop() }
            // Wake the discovery loop whenever power state changes so it re-evaluates the duty cycle
            // immediately (e.g. screen-on / charging) instead of waiting out a long idle backoff.
            powerJob = scope.launch { powerState.state.drop(1).collect { healSignal.trySend(Unit) } }
        }
    }

    override fun stop() {
        discoveryJob?.cancel()
        powerJob?.cancel()
        runCatching { client.stopAllEndpoints() }
        runCatching { client.stopAdvertising() }
        runCatching { client.stopDiscovery() }
        endpointToNode.clear(); nodeToEndpoint.clear(); peerWire.clear()
        connected.clear(); connecting.clear()
        retry.clear()
        _neighbors.value = emptySet()
    }

    override suspend fun send(wire: WireEnvelope, to: Peer?) {
        val bytes = WireCodec.encodeWire(wire)
        val payload = Payload.fromBytes(bytes)
        val targets = if (to == null) {
            connected.toList()
        } else {
            listOfNotNull(nodeToEndpoint[to.nodeId]).filter { it in connected }
        }
        targets.forEach { endpointId -> client.sendPayload(endpointId, payload) }
        metrics.onBytesSent(bytes.size.toLong() * targets.size)
    }

    override suspend fun sendFile(file: File, to: Peer, meta: FileMeta) {
        val endpointId = nodeToEndpoint[to.nodeId] ?: return
        val payload = Payload.fromFile(file)
        // Announce the file (its payload id + kind/key/mime) just before the bytes so the receiver
        // can tell an avatar from an attachment and name it correctly.
        val header = FileHeaderWire(payload.id, meta.kind.name, meta.key, meta.mime)
        client.sendPayload(endpointId, Payload.fromBytes(encodeFileHeader(header)))
        client.sendPayload(endpointId, payload)
    }

    override fun heal() {
        // Wake the discovery loop now, and retry any known endpoints we're not connected to. heal() is
        // an explicit recovery signal (heartbeat / motion / Bluetooth recovery) that fires rarely, so
        // it clears each disconnected peer's backoff to force an immediate fresh attempt.
        healSignal.trySend(Unit)
        endpointToNode.keys
            .filter { it !in connected }
            .forEach { endpointId ->
                retry.remove(endpointId)
                connectTo(endpointId)
            }
    }

    // --- Advertising / discovery ---

    private fun startAdvertising() {
        // Low power keeps advertising on BLE only (no Bluetooth-Classic) — the connection still
        // upgrades to a faster medium after a peer connects.
        val options = AdvertisingOptions.Builder()
            .setStrategy(STRATEGY)
            .setLowPower(true)
            .build()
        client.startAdvertising(Protocol.advertise(localNodeId), SERVICE_ID, lifecycleCallback, options)
            .addOnSuccessListener { _health.value = TransportHealth.Healthy }
            .addOnFailureListener { e ->
                // STATUS_ALREADY_ADVERTISING is benign — we're still advertising, nothing died. Any
                // other failure means the advertiser isn't up (e.g. the radio is held by Quick Share);
                // mark degraded so the discovery loop re-asserts it once the radio frees.
                val already = (e as? ApiException)?.statusCode ==
                    ConnectionsStatusCodes.STATUS_ALREADY_ADVERTISING
                if (!already) {
                    _health.value = TransportHealth.Degraded
                    Log.w(TAG, "startAdvertising failed", e)
                }
            }
    }

    /**
     * Repeatedly discovers for a window, connects to what it found, then backs off. The scan window
     * and base idle interval come from [PowerPolicy] (screen/charge/battery aware); the idle is
     * further multiplied by the neighbor count so a denser mesh scans less.
     *
     * The loop also self-heals the advertiser: a failed `startDiscovery` is the tell-tale of the radio
     * being seized by another app (e.g. Quick Share), which also silently kills our advertising with no
     * callback. So we treat a discovery failure as "degraded", and on the first discovery that succeeds
     * again we re-assert advertising (stop + start) — recovering discoverability without a manual
     * restart. `heal()` wakes this loop immediately (heartbeat / motion / app resume), so recovery is
     * prompt once the radio is free.
     */
    private suspend fun discoveryLoop() {
        var degraded = false
        while (scope.isActive) {
            val duty = PowerPolicy.dutyCycle(powerState.state.value)
            val options = DiscoveryOptions.Builder()
                .setStrategy(STRATEGY)
                .setLowPower(true)
                .build()
            val discovering = runCatching {
                client.startDiscovery(SERVICE_ID, discoveryCallback, options)
            }.onFailure { Log.w(TAG, "startDiscovery failed", it) }.isSuccess

            if (discovering) {
                if (degraded) {
                    // Radio came back. Advertising most likely died while contended (Nearby gives no
                    // callback for that), so re-assert it cleanly now.
                    runCatching { client.stopAdvertising() }
                    startAdvertising()
                    degraded = false
                }
            } else {
                degraded = true
            }
            _health.value = if (degraded) TransportHealth.Degraded else TransportHealth.Healthy

            delay(duty.scanWindowMs)
            runCatching { client.stopDiscovery() }

            // Back off as the mesh grows (more neighbors → scan less often), but wake early when
            // heal() is signaled (heartbeat / motion / Bluetooth recovery) or power state changes.
            withTimeoutOrNull(duty.baseIntervalMs * (1 + connected.size)) {
                healSignal.receive()
            }
        }
    }

    private fun connectTo(endpointId: String) {
        if (endpointId in connected) return
        // Honor the per-endpoint backoff window: a peer that just failed isn't re-attempted until its
        // next-attempt time, even though discovery keeps re-finding it and calling connectTo.
        retry[endpointId]?.let { if (SystemClock.elapsedRealtime() < it.attemptAt) return }
        if (!connecting.add(endpointId)) return // a request is already in flight for this endpoint
        // Requests run one-at-a-time on connectDispatcher; await() lets each finish before the next.
        scope.launch(connectDispatcher) {
            runCatching {
                client.requestConnection(Protocol.advertise(localNodeId), endpointId, lifecycleCallback).await()
            }.onFailure { e ->
                connecting.remove(endpointId)
                scheduleRetry(endpointId)
                Log.w(TAG, "requestConnection to $endpointId failed; backing off", e)
            }
        }
    }

    /** Widen this endpoint's retry delay (exponential, capped) and stamp its next eligible attempt. */
    private fun scheduleRetry(endpointId: String) {
        val prev = retry[endpointId]?.delayMs ?: 0L
        val next = if (prev <= 0L) RETRY_BASE_MS else (prev * 2).coerceAtMost(RETRY_MAX_MS)
        retry[endpointId] = Retry(SystemClock.elapsedRealtime() + next, next)
    }

    private val discoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            val wire = Protocol.parse(info.endpointName)
            if (wire.nodeId == localNodeId) return // discovered self
            mapEndpoint(endpointId, wire)
            connectTo(endpointId)
        }

        override fun onEndpointLost(endpointId: String) {
            // Keep the id->node mapping; the connection callback manages connected state.
        }
    }

    private val lifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            mapEndpoint(endpointId, Protocol.parse(info.endpointName))
            // Open mesh: accept every connection (Nearby's authenticationDigits are ignored).
            client.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            connecting.remove(endpointId)
            if (resolution.status.statusCode == ConnectionsStatusCodes.STATUS_OK) {
                connected.add(endpointId)
                retry.remove(endpointId) // reset backoff so a later drop reconnects without delay
                refreshNeighbors()
            } else {
                scheduleRetry(endpointId)
            }
        }

        override fun onDisconnected(endpointId: String) {
            connecting.remove(endpointId)
            connected.remove(endpointId)
            refreshNeighbors()
        }
    }

    /** Records the endpoint↔node mapping and the peer's advertised version/capabilities. */
    private fun mapEndpoint(endpointId: String, wire: Protocol.PeerWire) {
        endpointToNode[endpointId] = wire.nodeId
        nodeToEndpoint[wire.nodeId] = endpointId
        peerWire[endpointId] = wire
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            when (payload.type) {
                Payload.Type.BYTES -> {
                    val bytes = payload.asBytes() ?: return
                    // A BYTES payload is either a file header (announcing the FILE that follows) or a
                    // CBOR-serialized mesh frame; the header magic prefix tells them apart (a CBOR
                    // frame never starts with the magic bytes).
                    val header = decodeFileHeader(bytes)
                    if (header != null) {
                        fileHeaders[header.payloadId] = FileMeta(
                            kind = runCatching { FileKind.valueOf(header.kind) }.getOrDefault(FileKind.ATTACHMENT),
                            key = header.key,
                            mime = header.mime,
                        )
                        tryFinalizeFile(header.payloadId)
                        return
                    }
                    val wire = WireCodec.decodeWire(bytes)
                    if (wire == null) {
                        metrics.onDropped(DropReason.DECODE_FAILED)
                        Log.w(TAG, "drop payload from $endpointId: undecodable wrapper (${bytes.size}B)")
                        return
                    }
                    val envelope = WireCodec.decodeEnvelope(wire.signed)
                    if (envelope == null) {
                        metrics.onDropped(DropReason.DECODE_FAILED)
                        Log.w(TAG, "drop payload from $endpointId: undecodable envelope")
                        return
                    }
                    val fromNode = endpointToNode[endpointId]
                    if (fromNode == null) {
                        metrics.onDropped(DropReason.UNKNOWN_ENDPOINT)
                        return
                    }
                    _inbound.tryEmit(InboundFrame(wire, envelope, fromNode))
                }
                Payload.Type.FILE -> {
                    // Capture the destination Uri now; the bytes arrive by onPayloadTransferUpdate.
                    val uri = payload.asFile()?.asUri() ?: return
                    pendingFiles[payload.id] = PendingFile(endpointId, uri)
                }
                else -> Log.d(TAG, "Ignoring payload type ${payload.type}")
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            if (update.status != PayloadTransferUpdate.Status.SUCCESS) return
            if (!pendingFiles.containsKey(update.payloadId)) return // not a FILE we're tracking
            completedFiles.add(update.payloadId)
            tryFinalizeFile(update.payloadId)
        }
    }

    /** Once a FILE has both fully transferred and had its header announced, save and emit it. */
    private fun tryFinalizeFile(payloadId: Long) {
        if (payloadId !in completedFiles) return
        val meta = fileHeaders[payloadId] ?: return
        val pending = pendingFiles[payloadId] ?: return
        // Win the race to finalize exactly once, then drop all tracking state for this payload.
        if (!completedFiles.remove(payloadId)) return
        pendingFiles.remove(payloadId)
        fileHeaders.remove(payloadId)
        val nodeId = endpointToNode[pending.endpointId] ?: return
        scope.launch(Dispatchers.IO) { saveIncomingFile(nodeId, pending.uri, meta) }
    }

    /** Copies a completed FILE payload into the cache (avatar by node, attachment by hash) and announces it. */
    private fun saveIncomingFile(nodeId: String, uri: android.net.Uri, meta: FileMeta) {
        // [meta.key] is an untrusted, peer-supplied content address that is interpolated into the cache
        // filename below. Reject anything that isn't a 64-hex content hash so a malicious "../" can't
        // escape the cache directory (path traversal → arbitrary in-sandbox write).
        if (!isValidBlobHash(meta.key)) {
            Log.w(TAG, "Rejecting ${meta.kind} file from $nodeId: malformed blob key")
            return
        }
        val dest = when (meta.kind) {
            FileKind.AVATAR -> {
                // Stage the received avatar by node+hash; MeshManager ingests it into the encrypted blob
                // store and deletes this file. Clear any earlier staging files for this peer first so a
                // failed ingest can't leave them accumulating.
                appContext.cacheDir
                    .listFiles { f -> f.name.startsWith("avatar-$nodeId-") }
                    ?.forEach { it.delete() }
                File(appContext.cacheDir, "avatar-$nodeId-${meta.key}.jpg")
            }
            FileKind.ATTACHMENT -> File(appContext.cacheDir, "attach-${meta.key}.${extForMime(meta.mime)}")
        }
        // Defence in depth: the avatar name also embeds [nodeId] (a self-asserted endpoint name), so
        // confirm the resolved path really lands inside the cache dir before writing to it.
        val cacheRoot = appContext.cacheDir.canonicalPath + File.separator
        if (!dest.canonicalPath.startsWith(cacheRoot)) {
            Log.w(TAG, "Rejecting ${meta.kind} file from $nodeId: path escapes cache dir")
            return
        }
        runCatching {
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> copyBounded(input, output, MAX_INCOMING_FILE_BYTES) }
            }
        }.onSuccess {
            _incomingFiles.tryEmit(ReceivedFile(nodeId, dest.absolutePath, meta.kind, meta.key, meta.mime))
        }.onFailure {
            dest.delete() // drop any partial copy (decode failure or over-size abort)
            Log.w(TAG, "Failed saving ${meta.kind} file from $nodeId", it)
        }
    }

    /**
     * Copies [input] to [output], aborting once more than [max] bytes have been read. Bounds an inbound
     * FILE payload so a malicious peer can't stream an unbounded file and exhaust disk — the send side
     * already caps attachments (see `AttachmentStore.MAX_BYTES`); this enforces the matching ceiling on
     * receipt, which Nearby's file channel otherwise leaves unbounded.
     */
    private fun copyBounded(input: InputStream, output: OutputStream, max: Long) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            total += read
            if (total > max) throw IOException("incoming file exceeds $max bytes")
            output.write(buffer, 0, read)
        }
    }

    private fun refreshNeighbors() {
        _neighbors.value = connected.mapNotNull { endpointId ->
            val nodeId = endpointToNode[endpointId] ?: return@mapNotNull null
            val wire = peerWire[endpointId]
            Peer(nodeId, wire?.protoVersion ?: 0, wire?.capabilities ?: 0L)
        }.toSet()
    }

    private fun encodeFileHeader(header: FileHeaderWire): ByteArray =
        FILE_HEADER_MAGIC + headerJson.encodeToString(header).encodeToByteArray()

    /** Decodes a magic-prefixed file header, or null if [bytes] is an ordinary mesh frame. */
    private fun decodeFileHeader(bytes: ByteArray): FileHeaderWire? {
        if (bytes.size <= FILE_HEADER_MAGIC.size) return null
        if (!FILE_HEADER_MAGIC.indices.all { bytes[it] == FILE_HEADER_MAGIC[it] }) return null
        val json = bytes.copyOfRange(FILE_HEADER_MAGIC.size, bytes.size).decodeToString()
        return runCatching { headerJson.decodeFromString<FileHeaderWire>(json) }.getOrNull()
    }

    private fun extForMime(mime: String): String = when (mime.lowercase()) {
        "image/gif" -> "gif"
        "image/png" -> "png"
        "image/webp" -> "webp"
        else -> "jpg"
    }

    private companion object {
        const val TAG = "NearbyTransport"
        // Bumped to ".v2" with the layered wire-format break: old (pre-layer) builds advertise the
        // unversioned id, so they hard-partition off this mesh rather than connect and silently drop
        // each other's frames. Keep in lockstep with any future coordinated wire break.
        const val SERVICE_ID = "app.getknit.knit.MESH.v2"
        val STRATEGY: Strategy = Strategy.P2P_CLUSTER

        // Connection retry backoff: first retry after 5s, doubling to a 5-minute ceiling.
        const val RETRY_BASE_MS = 5_000L
        const val RETRY_MAX_MS = 5 * 60_000L

        // Receive-side ceiling on a FILE payload. The send side bounds attachments to 8 MiB
        // (AttachmentStore.MAX_BYTES); the extra headroom covers E2E framing (GCM IV+tag) and avatar
        // transfer so a legitimate max-size attachment isn't rejected, while still refusing an
        // unbounded malicious stream that would exhaust disk.
        const val MAX_INCOMING_FILE_BYTES = 8L * 1024 * 1024 + 64 * 1024

        // "KFH1" — distinguishes a file-header BYTES payload from a CBOR mesh frame (which never
        // begins with these bytes; a guard test in WireSerializationTest pins this down).
        val FILE_HEADER_MAGIC = byteArrayOf(0x4B, 0x46, 0x48, 0x31)
        val headerJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}

/** Transport-internal announcement sent ahead of a FILE payload so the receiver can route it. */
@Serializable
private data class FileHeaderWire(
    val payloadId: Long,
    val kind: String,
    val key: String,
    val mime: String,
)
