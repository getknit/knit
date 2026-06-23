package app.getknit.knit.mesh.nearby

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import app.getknit.knit.identity.Identity
import app.getknit.knit.mesh.InboundFrame
import app.getknit.knit.mesh.MeshTransport
import app.getknit.knit.mesh.Peer
import app.getknit.knit.mesh.ReceivedFile
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
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
 * Permissions are gated by the onboarding flow; the mesh starts regardless and degrades if a
 * permission was denied, so Nearby calls are marked [SuppressLint] for "MissingPermission".
 */
@SuppressLint("MissingPermission")
class NearbyTransport(
    context: Context,
    private val identity: Identity,
    private val scope: CoroutineScope,
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
        }
    }

    override fun stop() {
        discoveryJob?.cancel()
        runCatching { client.stopAllEndpoints() }
        runCatching { client.stopAdvertising() }
        runCatching { client.stopDiscovery() }
        endpointToNode.clear(); nodeToEndpoint.clear(); connected.clear(); connecting.clear()
        _neighbors.value = emptySet()
    }

    override suspend fun send(frame: Frame, to: Peer?) {
        val payload = Payload.fromBytes(WireCodec.encode(frame))
        val targets = if (to == null) {
            connected.toList()
        } else {
            listOfNotNull(nodeToEndpoint[to.nodeId]).filter { it in connected }
        }
        targets.forEach { endpointId -> client.sendPayload(endpointId, payload) }
    }

    override suspend fun sendFile(file: File, to: Peer) {
        val endpointId = nodeToEndpoint[to.nodeId] ?: return
        val payload = Payload.fromFile(file)
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
        val options = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
        client.startAdvertising(localNodeId, SERVICE_ID, lifecycleCallback, options)
            .addOnFailureListener { Log.w(TAG, "startAdvertising failed", it) }
    }

    /** Repeatedly discovers for a window, connects to what it found, then backs off by peer count. */
    private suspend fun discoveryLoop() {
        while (scope.isActive) {
            val options = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
            runCatching {
                client.startDiscovery(SERVICE_ID, discoveryCallback, options)
            }.onFailure { Log.w(TAG, "startDiscovery failed", it) }

            delay(DISCOVERY_WINDOW_MS)
            runCatching { client.stopDiscovery() }

            // Back off as the mesh grows (more neighbors → scan less often), but wake early when
            // heal() is signaled (heartbeat / motion / Bluetooth recovery).
            withTimeoutOrNull(DISCOVERY_INTERVAL_MS * (1 + connected.size)) {
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
            val pending = pendingFiles.remove(update.payloadId) ?: return
            val nodeId = endpointToNode[pending.endpointId] ?: return
            scope.launch(Dispatchers.IO) { saveIncomingAvatar(nodeId, pending.uri) }
        }
    }

    /** Copies a completed FILE payload into the avatar cache and announces it. */
    private fun saveIncomingAvatar(nodeId: String, uri: android.net.Uri) {
        val dest = File(appContext.cacheDir, "$nodeId.jpg")
        runCatching {
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { input.copyTo(it) }
            }
        }.onSuccess {
            _incomingFiles.tryEmit(ReceivedFile(nodeId, dest.absolutePath))
        }.onFailure {
            Log.w(TAG, "Failed saving avatar from $nodeId", it)
        }
    }

    private fun refreshNeighbors() {
        _neighbors.value = connected.mapNotNull { endpointToNode[it] }.map { Peer(it) }.toSet()
    }

    private companion object {
        const val TAG = "NearbyTransport"
        const val SERVICE_ID = "app.getknit.knit.MESH"
        val STRATEGY: Strategy = Strategy.P2P_CLUSTER

        const val DISCOVERY_WINDOW_MS = 12_000L
        const val DISCOVERY_INTERVAL_MS = 30_000L
        const val MAX_ENDPOINT_ERRORS = 10
    }
}
