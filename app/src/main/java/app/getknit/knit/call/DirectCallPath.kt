package app.getknit.knit.call

import app.getknit.knit.fieldlog.FieldEvent
import app.getknit.knit.fieldlog.FieldRecorder
import app.getknit.knit.mesh.protocol.CallPayload
import app.getknit.knit.mesh.protocol.DirectInvite
import app.getknit.knit.transfer.DirectWifi
import app.getknit.knit.transfer.DirectWifiException
import app.getknit.knit.transfer.DirectWifiLease
import app.getknit.knit.transfer.GroupAddress
import app.getknit.knit.transfer.GroupCredentials
import app.getknit.knit.transfer.JoinedGroup
import app.getknit.knit.transfer.TransferListener
import app.getknit.knit.transfer.TransferManager
import app.getknit.knit.transfer.TransferStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Every timer a call's direct leg runs on (docs/VOICE_CALLS.md §3.10); injectable so tests run them short. */
data class DirectTimings(
    // The callee's RINGING has to leave before the join takes the Wi-Fi radio from the mesh — the transfer's
    // readyGraceMs, for the same reason: "sent" on the fast plane means queued.
    val graceMs: Long = 500L,
    // The caller hosts when that RINGING lands; this gives its group a head start on the join.
    val joinDelayMs: Long = 1_000L,
    val joinWindowMs: Long = 20_000L,
    val joinAttemptMs: Long = 10_000L,
    val joinRetryDelayMs: Long = 2_000L,
    val groupUpMs: Long = 15_000L,
    // How long the host listens for the joiner's lane before it gives the radio back.
    val hostWindowMs: Long = 15_000L,
    val tcpConnectMs: Int = 3_000,
    val tcpConnectTries: Int = 5,
    val tcpConnectRetryMs: Long = 1_000L,
    // The lane's handshake: the joiner's proof, then the host's salt.
    val handshakeMs: Int = 5_000,
    val pingMs: Long = 1_000L,
    // A lane that hears nothing for this long has lost its group: the leg ends and the radio goes back.
    val silenceMs: Long = 5_000L,
    // How long the terminal frame gets to leave down the lane before the group comes down under it.
    val flushMs: Long = 300L,
)

/**
 * A call's direct leg over Wi-Fi Direct (docs/VOICE_CALLS.md §3.10, ADR 2026-10.kxnw) — the [CallDirect] the app runs.
 * Pure: the radio is [DirectWifi] through the shared lease (owner = the call id, so a transfer is refused while a
 * call holds it and neither releases the other's group), the lane is plain sockets, and every step runs on the
 * leg's own job off [scope].
 *
 * The caller hosts and the callee joins with an IPv4 lease (libwebrtc never gathers on an IPv6 link-local), both by
 * the credentials the caller's sealed OFFER carried — the transfer's credentials-only shape, so no discovery and no
 * dialog. Once the group is up its [link] goes to the media layer, and the joiner dials the host's lane, proving the
 * call's secret. The lane then carries what [mirror] and [candidates] queued — from the moment the leg began, so the
 * signals the mesh carried while the radio changed hands reach the peer again — plus a ping each second; silence for
 * [DirectTimings.silenceMs] ends the leg. However a leg ends, the radio goes back to the mesh, once, and the leg's
 * line goes into the field log ([field]): how it ended and how long its group and its lane took to come up.
 */
class DirectCallPath(
    // Late-bound: the lease wraps the Wi-Fi Direct radio, which holds the mesh transport — the service's to build.
    leaseOf: () -> DirectWifiLease,
    private val scope: CoroutineScope,
    // Whether the peer is a mesh neighbour now — "directly linked", the transfer's own gate.
    private val nearby: (peerId: String) -> Boolean,
    // Whether the peer's pinned profile says it can do Wi-Fi Direct at all (an iPhone cannot).
    private val capable: suspend (peerId: String) -> Boolean,
    // Whether this phone may join while it only rings: API 29-32 refuses Wi-Fi Direct to an app off screen.
    private val mayJoinWhileRinging: () -> Boolean,
    // The debug build's Diagnostics switch (`SettingsStore.debugDirectCallsOff`): false sends every call over ICE as
    // if no neighbour were near, so a lab trial reaches STUN/TURN. Either side refusing keeps the call off the group.
    private val enabled: () -> Boolean = { true },
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val timings: DirectTimings = DirectTimings(),
    private val rng: SecureRandom = SecureRandom(),
    private val subnetsOf: (JoinedGroup) -> List<GroupAddress> = ::joinedSubnets,
    private val log: (String) -> Unit = {},
    private val field: FieldRecorder = FieldRecorder.NONE,
) : CallDirect {
    private enum class Stage { Starting, Up, Over }

    private inner class Leg(
        val callId: String,
        val peerId: String,
        val hosting: Boolean,
        val credentials: GroupCredentials,
        val port: Int,
        val secret: ByteArray,
    ) {
        val wifi: DirectWifi = lease.leasedTo(callId)

        // What the lane sends, in order — queued from the leg's start, which is what replays the call's signals.
        val frames = Channel<LaneFrame>(Channel.UNLIMITED)

        @Volatile var stage = Stage.Starting

        @Volatile var lane: CallLane? = null

        @Volatile var socket: Socket? = null

        @Volatile var listener: TransferListener? = null

        @Volatile var link: DirectLink? = null

        @Volatile var heardAt = 0L

        var job: Job? = null
        val released = AtomicBoolean(false)

        // For the leg's field-log line: when it began, its group and its lane came up, the joiner's attempts, how it
        // ended on its own, and whether the call ended it first (which then names the end, whatever the lane saw).
        val startedAt = clock()

        @Volatile var groupAt: Long? = null

        @Volatile var laneAt: Long? = null

        @Volatile var joinTries = 0

        @Volatile var end: DirectLegEnd? = null

        @Volatile var ended = false
    }

    private val lease by lazy(leaseOf)
    private val legs = ConcurrentHashMap<String, Leg>()
    private val _inbound = MutableSharedFlow<LaneEvent>(extraBufferCapacity = INBOUND_BUFFER)
    private val _link = MutableStateFlow<DirectLink?>(null)

    override val inbound: Flow<LaneEvent> = _inbound.asSharedFlow()
    override val link: StateFlow<DirectLink?> = _link.asStateFlow()

    /** Who holds the radio, and the live leg's stage — what the debug bridge reports. */
    fun describe(): String =
        "owner=${lease.owner ?: "none"} legs=" +
            legs.values.joinToString(prefix = "[", postfix = "]") {
                "${it.callId.take(ID_SHOWN)}:${if (it.hosting) "host" else "join"}:${it.stage}"
            } + " link=${_link.value?.interfaceName ?: "none"}"

    /** Drops the live group as if the peer walked out of range: the lane closes, the radio goes back (failover trials). */
    fun dropGroup(): Boolean {
        val leg = legs.values.firstOrNull { it.stage == Stage.Up } ?: return false
        closeSockets(leg)
        return true
    }

    override suspend fun invite(
        callId: String,
        peerId: String,
    ): DirectInvite? {
        if (!enabled()) {
            log("call $callId: no direct leg (switched off)")
            return null
        }
        if (lease.owner != null || !nearby(peerId) || !capable(peerId)) return null
        // The caller hosts (C-3.10-3), so a phone that may not host offers no leg (ADR 2026-10.w3dw); it still joins.
        lease.leasedTo(callId).hostRefusal()?.let {
            log("call $callId: no direct leg ($it)")
            return null
        }
        val credentials = GroupCredentials.random(rng)
        return DirectInvite(
            s = credentials.ssid,
            p = credentials.passphrase,
            o = TransferManager.PORT_MIN + rng.nextInt(TransferManager.PORT_MAX - TransferManager.PORT_MIN),
            k = Base64.getEncoder().encodeToString(ByteArray(DIRECT_SECRET_BYTES).also(rng::nextBytes)),
        )
    }

    // invite's own gates, read without minting or logging. A caller's radio that is switched off (#63), lacks
    // permission or may never host refuses here, so a call with no other route goes out as a walkie.
    override suspend fun canOffer(peerId: String): Boolean =
        enabled() &&
            lease.owner == null &&
            nearby(peerId) &&
            capable(peerId) &&
            lease.leasedTo(PROBE).hostRefusal() == null

    override fun canJoin(
        peerId: String,
        prewarm: Boolean,
    ): Boolean =
        enabled() &&
            lease.owner == null &&
            nearby(peerId) &&
            (!prewarm || mayJoinWhileRinging()) &&
            lease.leasedTo(PROBE).refusal() == null

    override fun join(
        callId: String,
        peerId: String,
        invite: DirectInvite,
    ) {
        val leg = begin(callId, peerId, invite, hosting = false) ?: return
        run(leg) {
            delay(timings.graceMs + timings.joinDelayMs)
            val group = joinWithRetries(leg)
            up(leg, DirectLink(group.interfaceName, subnetsOf(group)))
            val socket = connectWithRetries(leg, group)
            socket.soTimeout = timings.handshakeMs
            socket.getOutputStream().apply {
                write(TransferStream.clientProof(CallLane.proofKey(leg.secret), callId))
                flush()
            }
            val lane = CallLane.join(socket.getInputStream(), socket.getOutputStream(), leg.secret, callId)
            socket.soTimeout = 0
            carry(leg, lane)
        }
    }

    override fun host(
        callId: String,
        peerId: String,
        invite: DirectInvite,
    ) {
        val leg = begin(callId, peerId, invite, hosting = true) ?: return
        run(leg) {
            val group = leg.wifi.host(leg.credentials, timings.groupUpMs)
            up(leg, DirectLink(group.interfaceName, group.addresses))
            val listener =
                TransferListener(group, leg.port, io, clock, timings.hostWindowMs, timings.handshakeMs) {
                    log("call $callId lane: $it")
                }
            leg.listener = listener
            listener.bind(LANE_BUFFER_BYTES)
            val socket = listener.accept(CallLane.proofKey(leg.secret), callId)
            if (socket == null) {
                leg.end = DirectLegEnd.NO_LANE
                log("call $callId: no lane within ${timings.hostWindowMs}ms; giving the radio back")
                return@run
            }
            leg.socket = socket
            socket.soTimeout = timings.handshakeMs
            val lane = CallLane.host(socket.getInputStream(), socket.getOutputStream(), leg.secret, callId, rng)
            socket.soTimeout = 0
            carry(leg, lane)
        }
    }

    override fun mirror(
        callId: String,
        payload: CallPayload,
    ) {
        // An OFFER never rides a lane: there is none until an offer was answered.
        if (payload.ph == CallPayload.PHASE_OFFER) return
        legs[callId]?.takeIf { it.stage != Stage.Over }?.frames?.trySend(LaneFrame.Signal(payload))
    }

    override fun candidates(
        callId: String,
        lines: List<String>,
    ) {
        if (lines.isEmpty()) return
        legs[callId]?.takeIf { it.stage != Stage.Over }?.frames?.trySend(LaneFrame.Cands(callId, lines))
    }

    override fun pending(callId: String): Boolean = legs[callId]?.stage == Stage.Starting

    override fun end(
        callId: String,
        terminal: CallPayload?,
    ) {
        val leg = legs.remove(callId) ?: return
        // Before a socket closes: the read that fails under it is the call's doing, not the lane's.
        leg.ended = true
        scope.launch(io) {
            withContext(NonCancellable) {
                leg.lane?.takeIf { leg.stage == Stage.Up }?.let { lane ->
                    // A best effort, bounded: the frame that tells the peer, then a bye, before the group goes.
                    val flushed =
                        async(io) {
                            runCatching {
                                terminal?.let { lane.send(LaneFrame.Signal(it)) }
                                lane.send(LaneFrame.Bye)
                            }
                        }
                    withTimeoutOrNull(timings.flushMs) { flushed.await() }
                }
                leg.stage = Stage.Over
                // Closing the sockets is what unblocks a read or a write the job is parked in.
                closeSockets(leg)
                leg.job?.cancelAndJoin()
                release(leg)
            }
        }
    }

    // ---- the leg's job ----

    /** A new leg for [callId], or null when the invite is unusable or the call already has one. */
    private fun begin(
        callId: String,
        peerId: String,
        invite: DirectInvite,
        hosting: Boolean,
    ): Leg? {
        val credentials = invite.credentials() ?: return null
        val secret = invite.secret() ?: return null
        val port = invite.o ?: return null
        val leg = Leg(callId, peerId, hosting, credentials, port, secret)
        return leg.takeIf { legs.putIfAbsent(callId, it) == null }
    }

    /**
     * Runs [body] as [leg]'s job, and gives the radio back however it ends. Started lazily, so an [end] that lands
     * before the job exists cancels it before its first step rather than racing it.
     */
    private fun run(
        leg: Leg,
        body: suspend () -> Unit,
    ) {
        val job =
            scope.launch(io, start = CoroutineStart.LAZY) {
                try {
                    body()
                } catch (e: DirectWifiException) {
                    leg.end = DirectLegEnd.RADIO
                    log("call ${leg.callId}: direct leg failed: ${e.message}")
                } catch (e: IOException) {
                    leg.end = DirectLegEnd.LANE_FAILED
                    log("call ${leg.callId}: lane failed: ${e.javaClass.simpleName}")
                } finally {
                    withContext(NonCancellable) {
                        leg.stage = Stage.Over
                        closeSockets(leg)
                        release(leg)
                    }
                }
            }
        leg.job = job
        if (legs[leg.callId] === leg) job.start() else job.cancel()
    }

    private fun up(
        leg: Leg,
        link: DirectLink,
    ) {
        leg.link = link
        leg.groupAt = clock()
        _link.value = link
        log("call ${leg.callId}: group up on ${link.interfaceName ?: "?"} (${if (leg.hosting) "hosting" else "joined"})")
    }

    /** The lane, until it ends: frames out in order, a ping each second, frames in, and a watchdog on silence. */
    private suspend fun carry(
        leg: Leg,
        lane: CallLane,
    ) = coroutineScope {
        leg.lane = lane
        leg.heardAt = clock()
        leg.laneAt = leg.heardAt
        leg.stage = Stage.Up
        log("call ${leg.callId}: lane up")
        val writer =
            launch(io) {
                try {
                    for (frame in leg.frames) lane.send(frame)
                } catch (_: IOException) {
                    // The socket closed under it; the reader or the watchdog says why.
                }
            }
        val pinger =
            launch {
                while (true) {
                    delay(timings.pingMs)
                    leg.frames.trySend(LaneFrame.Ping)
                }
            }
        val reader = async(io) { read(leg, lane) }
        val watchdog =
            async {
                while (clock() - leg.heardAt <= timings.silenceMs) delay(timings.pingMs)
                "silent for ${timings.silenceMs}ms" to DirectLegEnd.SILENT
            }
        val (why, end) =
            select {
                reader.onAwait { it }
                watchdog.onAwait { it }
            }
        leg.end = end
        log("call ${leg.callId}: lane over ($why)")
        closeSockets(leg)
        pinger.cancel()
        writer.cancel()
        watchdog.cancel()
        reader.cancel()
    }

    /** Frames in until the lane ends; why it ended, for the log and for the field log. */
    private suspend fun read(
        leg: Leg,
        lane: CallLane,
    ): Pair<String, DirectLegEnd> {
        while (true) {
            val frame =
                try {
                    lane.receive() ?: return "closed by the peer" to DirectLegEnd.CLOSED
                } catch (e: IOException) {
                    return "read failed: ${e.javaClass.simpleName}" to DirectLegEnd.CLOSED
                }
            leg.heardAt = clock()
            when (frame) {
                is LaneFrame.Signal -> {
                    if (frame.payload.id == leg.callId) _inbound.emit(LaneEvent.Signal(leg.peerId, frame.payload))
                }

                is LaneFrame.Cands -> {
                    if (frame.callId == leg.callId) _inbound.emit(LaneEvent.Candidates(leg.peerId, leg.callId, frame.lines))
                }

                LaneFrame.Ping -> {
                    // Liveness only: heardAt above is all it is for.
                }

                LaneFrame.Bye -> {
                    return "bye" to DirectLegEnd.BYE
                }
            }
        }
    }

    private suspend fun joinWithRetries(leg: Leg): JoinedGroup {
        val deadline = clock() + timings.joinWindowMs
        while (true) {
            try {
                leg.joinTries += 1
                return leg.wifi.join(leg.credentials, timings.joinAttemptMs, ipv4 = true)
            } catch (e: DirectWifiException) {
                if (clock() >= deadline || e.refusal != null) throw e
                log("call ${leg.callId}: join attempt failed (${e.message}); retrying")
                delay(timings.joinRetryDelayMs)
            }
        }
    }

    private suspend fun connectWithRetries(
        leg: Leg,
        group: JoinedGroup,
    ): Socket {
        var last: IOException? = null
        repeat(timings.tcpConnectTries) {
            val socket = Socket()
            leg.socket = socket
            try {
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(group.ownerAddress, leg.port), timings.tcpConnectMs)
                return socket
            } catch (e: IOException) {
                runCatching { socket.close() }
                last = e
                delay(timings.tcpConnectRetryMs)
            }
        }
        throw last ?: IOException("could not reach the lane")
    }

    private fun closeSockets(leg: Leg) {
        leg.frames.close()
        leg.listener?.close()
        leg.socket?.let { runCatching { it.close() } }
    }

    /** Gives [leg]'s radio back to the mesh, once; the lease makes it a no-op when the leg never took it. */
    private suspend fun release(leg: Leg) {
        if (!leg.released.compareAndSet(false, true)) return
        leg.link?.let { _link.compareAndSet(it, null) }
        runCatching { leg.wifi.release() }.onFailure { log("call ${leg.callId}: release failed: ${it.javaClass.simpleName}") }
        log("call ${leg.callId}: direct leg over; radio back to the mesh")
        field.record(
            FieldEvent.CallDirectLeg(
                hosting = leg.hosting,
                end = if (leg.ended) DirectLegEnd.CALL else leg.end ?: DirectLegEnd.CALL,
                groupMs = leg.groupAt?.let { it - leg.startedAt },
                laneMs = leg.laneAt?.let { it - leg.startedAt },
                lifeMs = clock() - leg.startedAt,
                joinTries = leg.joinTries,
            ),
        )
    }

    private companion object {
        const val INBOUND_BUFFER = 64

        // The owner a radio check is asked under: it takes nothing, so any name that is not a call id will do.
        const val PROBE = "call-probe"
        const val LANE_BUFFER_BYTES = 64 * 1024
        const val ID_SHOWN = 8
        const val IPV4_PREFIX = 24
        const val IPV6_PREFIX = 64

        /** The joined group's subnets: its interface's own, else the owner's address under a conventional prefix. */
        fun joinedSubnets(group: JoinedGroup): List<GroupAddress> {
            val read =
                group.interfaceName
                    ?.let { name -> runCatching { NetworkInterface.getByName(name)?.interfaceAddresses }.getOrNull() }
                    .orEmpty()
                    .mapNotNull { entry -> entry.address?.let { GroupAddress(it, entry.networkPrefixLength.toInt()) } }
            if (read.isNotEmpty()) return read
            val owner = group.ownerAddress
            return listOf(GroupAddress(owner, if (owner is Inet4Address) IPV4_PREFIX else IPV6_PREFIX))
        }
    }
}
