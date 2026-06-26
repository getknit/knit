package app.getknit.knit.mesh.nearby

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import app.getknit.knit.identity.Identity
import app.getknit.knit.mesh.FileKind
import app.getknit.knit.mesh.FileMeta
import app.getknit.knit.mesh.InboundFrame
import app.getknit.knit.mesh.MeshMetrics
import app.getknit.knit.mesh.MeshTransport
import app.getknit.knit.mesh.Peer
import app.getknit.knit.mesh.ReceivedFile
import app.getknit.knit.mesh.power.PowerPolicy
import app.getknit.knit.mesh.power.PowerStateSource
import app.getknit.knit.mesh.protocol.Frame
import app.getknit.knit.mesh.protocol.WireCodec
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

    // endpointId <-> nodeId (the Nearby endpoint name is the peer's nodeId)
    private val endpointToNode = ConcurrentHashMap<String, String>()
    private val nodeToEndpoint = ConcurrentHashMap<String, String>()
    private val connected = ConcurrentHashMap.newKeySet<String>() // connected endpointIds
    private val connecting = ConcurrentHashMap.newKeySet<String>() // in-flight connection requests
    private val errorCounts = ConcurrentHashMap<String, Int>()
    private val blacklisted = ConcurrentHashMap.newKeySet<String>()

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
        endpointToNode.clear(); nodeToEndpoint.clear(); connected.clear(); connecting.clear()
        _neighbors.value = emptySet()
    }

    override suspend fun send(frame: Frame, to: Peer?) {
        val bytes = WireCodec.encode(frame)
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
        // Wake the discovery loop now, and retry any known endpoints we're not connected to.
        healSignal.trySend(Unit)
        endpointToNode.keys
            .filter { it !in connected && it !in blacklisted }
            .forEach { connectTo(it) }
    }

    // --- Advertising / discovery ---

    private fun startAdvertising() {
        // Low power keeps advertising on BLE only (no Bluetooth-Classic) — the connection still
        // upgrades to a faster medium after a peer connects.
        val options = AdvertisingOptions.Builder()
            .setStrategy(STRATEGY)
            .setLowPower(true)
            .build()
        client.startAdvertising(localNodeId, SERVICE_ID, lifecycleCallback, options)
            .addOnFailureListener { Log.w(TAG, "startAdvertising failed", it) }
    }

    /**
     * Repeatedly discovers for a window, connects to what it found, then backs off. The scan window
     * and base idle interval come from [PowerPolicy] (screen/charge/battery aware); the idle is
     * further multiplied by the neighbor count so a denser mesh scans less.
     */
    private suspend fun discoveryLoop() {
        while (scope.isActive) {
            val duty = PowerPolicy.dutyCycle(powerState.state.value)
            val options = DiscoveryOptions.Builder()
                .setStrategy(STRATEGY)
                .setLowPower(true)
                .build()
            runCatching {
                client.startDiscovery(SERVICE_ID, discoveryCallback, options)
            }.onFailure { Log.w(TAG, "startDiscovery failed", it) }

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
        if (endpointId in connected || endpointId in blacklisted) return
        if (!connecting.add(endpointId)) return // a request is already in flight for this endpoint
        // Requests run one-at-a-time on connectDispatcher; await() lets each finish before the next.
        scope.launch(connectDispatcher) {
            runCatching {
                client.requestConnection(localNodeId, endpointId, lifecycleCallback).await()
            }.onFailure { e ->
                connecting.remove(endpointId)
                val count = (errorCounts[endpointId] ?: 0) + 1
                errorCounts[endpointId] = count
                if (count >= MAX_ENDPOINT_ERRORS) blacklisted.add(endpointId)
                Log.w(TAG, "requestConnection to $endpointId failed ($count)", e)
            }
        }
    }

    private val discoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (info.endpointName == localNodeId) return // discovered self
            endpointToNode[endpointId] = info.endpointName
            nodeToEndpoint[info.endpointName] = endpointId
            connectTo(endpointId)
        }

        override fun onEndpointLost(endpointId: String) {
            // Keep the id->node mapping; the connection callback manages connected state.
        }
    }

    private val lifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            endpointToNode[endpointId] = info.endpointName
            nodeToEndpoint[info.endpointName] = endpointId
            // Open mesh: accept every connection (Nearby's authenticationDigits are ignored).
            client.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            connecting.remove(endpointId)
            if (resolution.status.statusCode == ConnectionsStatusCodes.STATUS_OK) {
                connected.add(endpointId)
                errorCounts.remove(endpointId)
                refreshNeighbors()
            }
        }

        override fun onDisconnected(endpointId: String) {
            connecting.remove(endpointId)
            connected.remove(endpointId)
            refreshNeighbors()
        }
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
                    val frame = WireCodec.decode(bytes) ?: return
                    val fromNode = endpointToNode[endpointId] ?: return
                    _inbound.tryEmit(InboundFrame(frame, fromNode))
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
        runCatching {
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { input.copyTo(it) }
            }
        }.onSuccess {
            _incomingFiles.tryEmit(ReceivedFile(nodeId, dest.absolutePath, meta.kind, meta.key, meta.mime))
        }.onFailure {
            Log.w(TAG, "Failed saving ${meta.kind} file from $nodeId", it)
        }
    }

    private fun refreshNeighbors() {
        _neighbors.value = connected.mapNotNull { endpointToNode[it] }.map { Peer(it) }.toSet()
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
        const val SERVICE_ID = "app.getknit.knit.MESH"
        val STRATEGY: Strategy = Strategy.P2P_CLUSTER

        const val MAX_ENDPOINT_ERRORS = 10

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
