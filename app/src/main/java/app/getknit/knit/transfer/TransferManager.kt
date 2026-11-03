package app.getknit.knit.transfer

import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.message.TransferPhase
import app.getknit.knit.data.message.TransferRecord
import app.getknit.knit.mesh.protocol.FrameId
import app.getknit.knit.mesh.protocol.TransferPayload
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** One transfer as the UI sees it: the record's facts plus live progress. Never carries credentials. */
data class TransferState(
    val id: String,
    val peerId: String,
    val outgoing: Boolean,
    val name: String,
    val size: Long,
    val mime: String?,
    val phase: TransferPhase,
    val bytes: Long = 0L,
    val savedUri: String? = null,
    val reason: Int? = null,
    val expiresAt: Long = 0L,
    /** The pins of a translation-model bundle (ADR 2026-11.ejn8): offered, then the subset an ACCEPT took. Null for a file. */
    val models: List<Long>? = null,
)

/** What [TransferManager.offer] did. */
sealed interface OfferOutcome {
    data class Started(
        val id: String,
    ) : OfferOutcome

    data class Refused(
        val refusal: TransferRefusal,
    ) : OfferOutcome
}

/** Every timer in a transfer, in one place so a test can shrink them. Production uses the defaults. */
data class TransferTimings(
    val offerTtlMs: Long = 180_000L,
    val readyWaitMs: Long = 45_000L,
    val groupUpMs: Long = 15_000L,
    val hostWindowMs: Long = 90_000L,
    /**
     * How long the receiver sits on a READY before it reaches for the radio. It exists so the client does not
     * scan for a group the host has not stood up yet, and it is sized against what the host actually costs:
     * its own settle plus ~650 ms of `createGroup`. This delay and the settle are serial on the receiver, so
     * together they should land it just *after* the host is up, not seconds later.
     */
    val joinStartDelayMs: Long = 1_000L,
    val joinWindowMs: Long = 75_000L,
    val joinAttemptMs: Long = 20_000L,
    val joinRetryDelayMs: Long = 2_000L,
    /**
     * The pause between sending READY and lending the radio out to host the group.
     *
     * It exists because "sent" is not "delivered": the fast plane returns once a frame is queued, and
     * hosting pauses Wi-Fi Aware, which closes the very socket the frame is draining through. Observed on a
     * Pixel 7 — the READY (two fragments) was written at 23:38:46.377 and the Aware link went down at
     * 23:38:46.394, seventeen milliseconds later; the receiver never saw it, sat out its READY wait and both
     * ends failed on a timeout. It survived eleven runs before it lost the twelfth. Bluetooth cannot cover
     * it, since a frame the fast plane has already accepted is not re-queued for another plane.
     */
    val readyGraceMs: Long = 500L,
    /**
     * How often the host sends READY again while it waits for the receiver's connection, for as long as the
     * receiver could still be waiting for it ([readyWaitMs]). The receiver ignores every READY after the first.
     *
     * One READY is a single point of failure, and the grace above covers only one way of losing it. On
     * 2026-10-05 a Pixel 8 could not open the Pixel 9's READY at all: it was the first frame of a ratchet epoch
     * whose number collided with keys a previous session had left behind (#135). The pair's session reset 17 s
     * later and healed the ratchet, but nothing sent READY again. A reset re-seals only stored DMs, and a
     * `CTL_TRANSFER` is never stored. The receiver timed out with the group already up and waiting for it. A
     * repeat goes out under whatever session the pair holds by then, and over Bluetooth while hosting has Wi-Fi
     * Aware paused.
     */
    val readyResendMs: Long = 5_000L,
    val tcpConnectMs: Int = 5_000,
    val tcpConnectTries: Int = 5,
    val tcpConnectRetryMs: Long = 1_000L,
    val soTimeoutMs: Int = 30_000,
    val verdictWaitMs: Int = 20_000,
    val progressEveryMs: Long = 200L,
)

/**
 * The direct-transfer state machine — pure Kotlin, one instance for the app, JVM-tested against fakes of its
 * three seams ([DirectWifi], [TransferFiles], [TransferSignals]) and loopback sockets.
 *
 * A transfer is a sealed-DM conversation (OFFER → ACCEPT → READY, or DECLINE / CANCEL) followed by one TCP
 * stream over a one-shot Wi-Fi Direct group the sender hosts and the receiver joins by credentials. The
 * signaling rides the mesh; the bytes never do. Both sides keep a chat row ([TransferRecord]) that is
 * rewritten on every phase change and read back by the card; progress is live state only ([states]).
 *
 * Ordering that matters: READY is sent *before* the sender hosts, because hosting pauses this phone's Wi-Fi
 * Aware plane and a READY sent after it would strand on a pair with no Bluetooth link. Every terminal path
 * releases the radio ([DirectWifi.release]), and one transfer runs at a time per device.
 */
@Suppress("LongParameterList", "TooManyFunctions")
class TransferManager(
    private val messages: MessageRepository,
    private val signals: TransferSignals,
    private val wifi: DirectWifi,
    files: TransferFiles,
    private val scope: CoroutineScope,
    private val selfId: suspend () -> String,
    private val peerNearby: (String) -> Boolean,
    private val timings: TransferTimings = TransferTimings(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val credentials: () -> GroupCredentials = { GroupCredentials.random() },
    private val newKey: () -> ByteArray = { ByteArray(TransferStream.KEY_BYTES).also { SecureRandom().nextBytes(it) } },
    private val newPort: () -> Int = { PORT_MIN + SecureRandom().nextInt(PORT_MAX - PORT_MIN + 1) },
    private val log: (String) -> Unit = {},
    // A build that packages translation hands its models in (ADR 2026-11.ejn8); without it a models offer is a file.
    models: TransferModels? = null,
) {
    private val ends = TransferEnds(files, models)
    private val dialer = TransferDialer(wifi, timings, clock, log)

    private class TransferTimeout : IOException("timed out")

    private class NoSpace : IOException("no space for the file")

    private val live = ConcurrentHashMap<String, LiveTransfer>()
    private val mutex = Mutex()
    private val _states = MutableStateFlow<Map<String, TransferState>>(emptyMap())

    /** Every non-terminal transfer, keyed by id; a terminal one leaves the map once its row is written. */
    val states: StateFlow<Map<String, TransferState>> = _states.asStateFlow()

    @Volatile private var me: String? = null

    // ---- the sender's side ----

    /** Offers the file at [uri] to [peerId]. The peer must be nearby and carry the capability (the caller checks). */
    suspend fun offer(
        peerId: String,
        uri: String,
    ): OfferOutcome = start(peerId, ends.file(uri), sourceUri = uri, pins = null)

    /**
     * Offers the translation models [pins] to [peerId] as one archive (ADR 2026-11.ejn8). The peer must be nearby and
     * take direct transfers (the caller checks); every pin must be installed here. The offer is also an ordinary file
     * offer, so a build that cannot install models takes the archive as a file.
     */
    suspend fun offerModels(
        peerId: String,
        pins: List<Long>,
    ): OfferOutcome {
        if (!OfferReading.wellFormed(pins)) return OfferOutcome.Refused(TransferRefusal.Unreadable)
        return start(peerId, ends.bundle(pins), sourceUri = null, pins = pins)
    }

    private suspend fun start(
        peerId: String,
        source: TransferSource?,
        sourceUri: String?,
        pins: List<Long>?,
    ): OfferOutcome {
        OfferReading.refusal(wifi.hostRefusal(), peerNearby(peerId), source)?.let { return OfferOutcome.Refused(it) }
        checkNotNull(source)
        val now = clock()
        val id = FrameId.new()
        val state =
            TransferState(
                id = id,
                peerId = peerId,
                outgoing = true,
                name = source.name,
                size = source.size,
                mime = source.mime,
                phase = TransferPhase.Offered,
                expiresAt = now + timings.offerTtlMs,
                models = pins,
            )
        val l = LiveTransfer(state, offeredAt = now, sourceUri = sourceUri)
        val admitted =
            mutex.withLock {
                if (busy()) {
                    false
                } else {
                    live[id] = l
                    publish()
                    true
                }
            }
        if (!admitted) return OfferOutcome.Refused(TransferRefusal.Busy)
        // A models offer is a file offer with its pins beside it: a build that cannot install them sees the archive as a
        // file, and one that can reads names and sizes off its own table instead.
        val offer = TransferPayload(id, TransferPayload.PHASE_OFFER, source.name, source.size, source.mime, md = pins)
        val sent = signals.sendTransferSignal(peerId, offer)
        if (!sent) {
            mutex.withLock {
                live.remove(id)
                publish()
            }
            return OfferOutcome.Refused(TransferRefusal.NoSession)
        }
        writeRow(l)
        scheduleExpiry(l)
        log("xfer $id offered ${source.size}B '${source.name}' → $peerId")
        return OfferOutcome.Started(id)
    }

    /**
     * Whether this phone may offer a transfer at all: the sender hosts the group, and a phone where hosting takes the
     * phone down never does (ADR 2026-10.w3dw). A device fact, so the chat says it before the picker opens. Receiving
     * only joins, and is never refused for it.
     */
    fun mayOffer(): Boolean = wifi.mayHost()

    /**
     * Offers again the file an outgoing transfer [id] was reading, once it ended short of Done: the card's
     * Retry, with no second trip through the picker. It goes out as a new transfer with its own id and row, and
     * the old row is marked [TransferRecord.retried] once the new offer is out so its card stops offering it.
     * The source is read again from scratch, so a file that has moved or lost its grant refuses as
     * [TransferRefusal.Unreadable] exactly as a fresh pick would.
     */
    suspend fun retry(id: String): OfferOutcome {
        val row = messages.observeMessage(TransferRecord.rowId(id)).first()
        val record = row?.let { TransferRecord.decode(it.body) }
        if (row == null || record == null || !record.retryable(live = live.containsKey(id))) {
            return OfferOutcome.Refused(TransferRefusal.Gone)
        }
        val pins = record.models
        val outcome = if (pins != null) offerModels(row.conversationId, pins) else offer(row.conversationId, checkNotNull(record.sourceUri))
        if (outcome is OfferOutcome.Started) {
            messages.save(record.copy(retried = true).toEntity(peerId = row.conversationId, selfId = me(), sentAt = row.sentAt))
            log("xfer $id retried as ${outcome.id}")
        }
        return outcome
    }

    /**
     * Whether a Retry of [record] would find what it sends: its file, or every model still installed. The chat asks
     * before it draws the button, so a file that was moved, deleted or lost its grant hides Retry instead of failing on
     * the tap.
     */
    suspend fun retryReadable(record: TransferRecord): Boolean = ends.retryReadable(record)

    /** Ends a transfer from this side, whatever phase it is in. */
    suspend fun cancel(id: String) {
        val l = live[id] ?: return
        val ended =
            mutex.withLock {
                if (l.state.phase.terminal) return
                transitionLocked(l, TransferPhase.Cancelled, reason = TransferPayload.REASON_USER)
                true
            }
        if (ended) {
            abort(l)
            signals.sendTransferSignal(
                l.state.peerId,
                TransferPayload(id = id, phase = TransferPayload.PHASE_CANCEL, reason = TransferPayload.REASON_USER),
            )
        }
    }

    // ---- the receiver's side ----

    /** Accepts an incoming offer; null when the transfer is under way, else why it could not be. */
    suspend fun accept(id: String): TransferRefusal? {
        val l = live[id] ?: return TransferRefusal.Gone
        // A models offer is read again at the tap: what this phone lacks may have changed since it arrived.
        val take = l.state.models?.let { pins -> ends.wanted(pins) ?: return TransferRefusal.Gone }
        if (take != null && take.take.isEmpty()) return TransferRefusal.Gone
        val size = take?.bytes ?: l.state.size
        val refusal = wifi.refusal() ?: if (ends.freeBytes() < size) TransferRefusal.NoSpace else null
        if (refusal != null) return refusal
        val opened =
            mutex.withLock {
                val open = l.isOpenIncomingOffer(clock())
                if (open) {
                    if (take != null) l.state = l.state.copy(models = take.take, size = take.bytes, name = take.label)
                    transitionLocked(l, TransferPhase.Connecting)
                }
                open
            }
        if (!opened) return TransferRefusal.Gone
        val sent =
            signals.sendTransferSignal(
                l.state.peerId,
                TransferPayload(id = id, phase = TransferPayload.PHASE_ACCEPT, md = take?.take),
            )
        return if (sent) {
            l.job = scope.launch { joinAndReceive(l) }
            null
        } else {
            fail(l, TransferPayload.REASON_CONNECTION, tellPeer = false)
            TransferRefusal.NoSession
        }
    }

    /** Turns an incoming offer down. */
    suspend fun decline(id: String) {
        val l = live[id] ?: return
        val declined =
            mutex.withLock {
                if (!l.isOpenIncomingOffer(clock())) return
                transitionLocked(l, TransferPhase.Declined, reason = TransferPayload.REASON_USER)
                true
            }
        if (declined) {
            signals.sendTransferSignal(
                l.state.peerId,
                TransferPayload(id = id, phase = TransferPayload.PHASE_DECLINE, reason = TransferPayload.REASON_USER),
            )
        }
    }

    // ---- the mesh's side ----

    /**
     * A sealed `CTL_TRANSFER` from [senderId] landed. Non-null only for a live OFFER that was admitted and is worth
     * telling the person about — the caller's cue to notify. Never blocks the inbound collector: role work is launched
     * on [scope].
     *
     * The id is the peer's text and is refused unless it is a canonical [FrameId], whatever the phase: it keys the
     * chat row ([TransferRecord.rowId]) and must never name anything else (ADR 2026-11.ejn8).
     */
    suspend fun onSignal(
        senderId: String,
        payload: TransferPayload,
        sentAt: Long,
    ): TransferNotice? {
        if (FrameId.toBytesOrNull(payload.id) == null) {
            log("xfer refused a non-canonical id from $senderId (phase ${payload.phase})")
            return null
        }
        return when (payload.phase) {
            TransferPayload.PHASE_OFFER -> onOffer(senderId, payload, sentAt)
            TransferPayload.PHASE_ACCEPT -> onAccept(senderId, payload).let { null }
            TransferPayload.PHASE_READY -> onReady(senderId, payload).let { null }
            TransferPayload.PHASE_DECLINE -> onDecline(senderId, payload).let { null }
            TransferPayload.PHASE_CANCEL -> onCancel(senderId, payload).let { null }
            else -> null
        }
    }

    private suspend fun onOffer(
        senderId: String,
        payload: TransferPayload,
        sentAt: Long,
    ): TransferNotice? {
        val offered =
            OfferReading.read(payload, ends.models, nearby = { peerNearby(senderId) }) { why ->
                log("xfer ${payload.id} from $senderId refused: $why")
            } ?: return null
        val now = clock()
        if (now - sentAt > STALE_OFFER_MS) return null // a custody replay of an offer nobody can still host
        val l =
            LiveTransfer(
                TransferState(
                    id = payload.id,
                    peerId = senderId,
                    outgoing = false,
                    name = offered.name,
                    size = offered.size,
                    mime = offered.mime,
                    phase = TransferPhase.Offered,
                    expiresAt = now + timings.offerTtlMs,
                    models = offered.pins,
                ),
                offeredAt = minOf(sentAt, now),
            )
        val verdict =
            mutex.withLock {
                when {
                    live.containsKey(payload.id) -> {
                        null
                    }

                    busy() -> {
                        false
                    }

                    else -> {
                        live[payload.id] = l
                        publish()
                        true
                    }
                }
            }
        when (verdict) {
            null -> {
                return null
            }

            false -> {
                signals.sendTransferSignal(
                    senderId,
                    TransferPayload(id = payload.id, phase = TransferPayload.PHASE_DECLINE, reason = TransferPayload.REASON_BUSY),
                )
                return null
            }

            true -> {
                writeRow(l)
                scheduleExpiry(l)
                val what = offered.pins?.let { "models=${it.size}" } ?: "'${offered.name}'"
                log("xfer ${payload.id} offered by $senderId: ${offered.size}B $what")
                return if (offered.notify) TransferNotice(offered.name, offered.size) else null
            }
        }
    }

    private suspend fun onAccept(
        senderId: String,
        payload: TransferPayload,
    ) {
        val id = payload.id
        val l = live[id]
        val taken = l?.state?.models?.let { offered -> OfferReading.taken(offered, payload.md) }
        val bundle = taken?.let { ends.bundle(it) }
        var refused = false
        val ready =
            l != null &&
                mutex.withLock {
                    val open = l.isOurOpenOffer(senderId, clock()) && peerNearby(senderId)
                    refused = open && l.state.models != null && bundle == null
                    val ok = open && !refused
                    if (ok) {
                        if (bundle != null) l.state = l.state.copy(models = taken, size = bundle.size, name = bundle.name)
                        l.credentials = credentials()
                        l.key = newKey()
                        l.port = newPort()
                        transitionLocked(l, TransferPhase.Connecting)
                    }
                    ok
                }
        if (refused) {
            log("xfer $id refused an ACCEPT naming models it did not offer, or ones no longer installed")
            fail(checkNotNull(l), TransferPayload.REASON_CONNECTION, tellPeer = true)
            return
        }
        if (!ready) {
            // An answer to an offer that is no longer open (expired, cancelled, a custody replay): say so at
            // once rather than leaving the peer to wait out its READY window, and never host for nobody.
            signals.sendTransferSignal(
                senderId,
                TransferPayload(id = id, phase = TransferPayload.PHASE_CANCEL, reason = TransferPayload.REASON_TIMEOUT),
            )
            return
        }
        val sent = signals.sendTransferSignal(senderId, readyPayload(l))
        if (!sent) {
            fail(l, TransferPayload.REASON_CONNECTION, tellPeer = false)
            return
        }
        l.job = scope.launch { hostAndSend(l) }
    }

    /** The credentials, port and stream key [l]'s receiver joins with. The same payload every time it is sent. */
    private fun readyPayload(l: LiveTransfer): TransferPayload {
        val creds = checkNotNull(l.credentials)
        return TransferPayload(
            id = l.state.id,
            phase = TransferPayload.PHASE_READY,
            ssid = creds.ssid,
            passphrase = creds.passphrase,
            port = l.port,
            key = Base64.getEncoder().encodeToString(checkNotNull(l.key)),
        )
    }

    private suspend fun onReady(
        senderId: String,
        payload: TransferPayload,
    ) {
        val l = live[payload.id] ?: return
        val key = payload.key?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
        val port = payload.port ?: 0
        val credentialsOk = GroupCredentials.isValid(payload.ssid, payload.passphrase)
        val keyOk = key?.size == TransferStream.KEY_BYTES
        val valid = credentialsOk && keyOk && port in PORT_MIN_ANY..PORT_MAX
        mutex.withLock {
            val ours = !l.state.outgoing && l.state.peerId == senderId && l.state.phase == TransferPhase.Connecting
            if (!valid || !ours) return
            if (l.credentials != null) return // a repeat
            l.credentials = GroupCredentials(checkNotNull(payload.ssid), checkNotNull(payload.passphrase))
            l.key = key
            l.port = port
        }
        l.ready.complete(Unit)
    }

    private suspend fun onDecline(
        senderId: String,
        payload: TransferPayload,
    ) {
        val l = live[payload.id] ?: return
        mutex.withLock {
            if (!l.isOurOpenOffer(senderId, Long.MIN_VALUE)) return
            transitionLocked(l, TransferPhase.Declined, reason = payload.reason ?: TransferPayload.REASON_USER)
        }
    }

    private suspend fun onCancel(
        senderId: String,
        payload: TransferPayload,
    ) {
        val l = live[payload.id] ?: return
        val ended =
            mutex.withLock {
                if (l.state.peerId != senderId || l.state.phase.terminal) return
                // A deliberate cancel is one thing; a peer reporting that its side failed is a failure here too.
                val reason = payload.reason ?: TransferPayload.REASON_USER
                // Every byte is out: the verdict decides. A receiver that read the file intact and then could not keep
                // it (a failed install) says so on its own card; this side stays Done (ADR 2026-11.ejn8).
                if (l.state.outgoing && l.awaitingVerdict) {
                    l.peerCancel = reason
                    return
                }
                val phase = if (reason == TransferPayload.REASON_USER) TransferPhase.Cancelled else TransferPhase.Failed
                transitionLocked(l, phase, reason = reason)
                true
            }
        if (ended) abort(l)
    }

    // ---- the two roles ----

    private suspend fun hostAndSend(l: LiveTransfer) {
        var failure: Int? = null
        // The receiver's READY wait started when it sent ACCEPT, just before this job started. A repeat
        // sent after that wait has run out reaches a receiver that has already failed.
        val readyUntil = clock() + timings.readyWaitMs
        try {
            // Let the READY actually leave before the radio is taken away — see readyGraceMs. It waits here, on
            // the role's own job, so the accept that started it hands the inbound collector back at once, and
            // a cancel during the wait ends the job before it ever hosts.
            delay(timings.readyGraceMs)
            withContext(io) {
                val group = wifi.host(checkNotNull(l.credentials), timings.groupUpMs)
                log(
                    "xfer ${l.state.id} hosting ${l.credentials?.ssid} " +
                        "on ${group.addresses.joinToString { it.address.hostAddress.orEmpty() }} freq=${group.frequencyMhz}",
                )
                val listener =
                    TransferListener(group, l.port, io, clock, timings.hostWindowMs, timings.soTimeoutMs) {
                        log("xfer ${l.state.id} $it")
                    }
                l.listener = listener
                listener.bind(SOCKET_BUFFER_BYTES)
                val socket =
                    coroutineScope {
                        val resend = launch { resendReady(l, readyUntil) }
                        try {
                            listener.accept(checkNotNull(l.key), l.state.id)
                        } finally {
                            resend.cancel()
                        }
                    } ?: throw TransferTimeout()
                l.socket = socket
                transition(l, TransferPhase.Transferring)
                val source = ends.source(l.state, l.sourceUri) ?: throw IOException("source is gone")
                val startedAt = clock()
                source.open().use { input -> stream(l, socket, input) }
                socket.soTimeout = timings.verdictWaitMs
                val verdict = TransferStream.readVerdict(socket.getInputStream())
                if (verdict == true) {
                    log("xfer ${l.state.id} done bytes=${l.state.size} ms=${clock() - startedAt}")
                    transition(l, TransferPhase.Done)
                } else {
                    // No verdict at all: the receiver's CANCEL, if one came, says why.
                    failure = l.peerCancel.takeIf { verdict == null } ?: TransferPayload.REASON_CORRUPT
                }
            }
        } catch (_: TransferTimeout) {
            failure = TransferPayload.REASON_TIMEOUT
        } catch (e: DirectWifiException) {
            log("xfer ${l.state.id} host failed: ${e.message}")
            failure = reasonFor(e)
        } catch (e: IOException) {
            log("xfer ${l.state.id} send failed: $e")
            failure = l.peerCancel ?: TransferPayload.REASON_CONNECTION
        } finally {
            finish(l, failure)
        }
    }

    // Sends [input] whole. awaitingVerdict goes up as the last chunk leaves, before the trailer, so no verdict (and no
    // CANCEL after one) can reach this side first.
    private fun stream(
        l: LiveTransfer,
        socket: Socket,
        input: InputStream,
    ) {
        val report = progress(l)
        TransferStream.send(socket.getOutputStream(), input, l.state.id, l.state.size, checkNotNull(l.key)) { sent ->
            if (sent >= l.state.size) l.awaitingVerdict = true
            report(sent)
        }
    }

    /**
     * Sends READY again every [TransferTimings.readyResendMs] until [until], while the host waits for the
     * receiver's connection. The caller cancels it once the receiver connects or the listener gives up.
     */
    private suspend fun resendReady(
        l: LiveTransfer,
        until: Long,
    ) {
        var sent = 0
        while (true) {
            delay(timings.readyResendMs)
            if (clock() >= until) return
            sent += 1
            val ok = signals.sendTransferSignal(l.state.peerId, readyPayload(l))
            log("xfer ${l.state.id} READY again (#$sent${if (ok) "" else ", not sealed"})")
        }
    }

    private suspend fun joinAndReceive(l: LiveTransfer) {
        var failure: Int? = null
        try {
            withTimeoutOrNull(timings.readyWaitMs) { l.ready.await() } ?: throw TransferTimeout()
            delay(timings.joinStartDelayMs)
            withContext(io) {
                val group = dialer.join(l.state.id, checkNotNull(l.credentials))
                val socket = dialer.connect(group, l.port).also { l.socket = it }
                socket.soTimeout = timings.soTimeoutMs
                socket.getOutputStream().apply {
                    write(TransferStream.clientProof(checkNotNull(l.key), l.state.id))
                    flush()
                }
                val sink = (ends.sink(l.state) ?: throw NoSpace()).also { l.sink = it }
                transition(l, TransferPhase.Transferring)
                // A bundle's pins are checked before the verdict, so a refusal reads as corrupt on both cards.
                val ok = receive(l, socket.getInputStream(), sink) && sink.check()
                TransferStream.writeVerdict(socket.getOutputStream(), ok)
                if (ok) {
                    sink.commitOrNotSaved()
                    l.committed = true
                    log("xfer ${l.state.id} received bytes=${l.state.size} → ${sink.uri}")
                    // A bundle is installed, not saved: there is nothing for the card to open.
                    transition(l, TransferPhase.Done, savedUri = sink.uri.takeIf { l.state.models == null })
                } else {
                    failure = TransferPayload.REASON_CORRUPT
                }
            }
        } catch (_: TransferTimeout) {
            failure = TransferPayload.REASON_TIMEOUT
        } catch (_: NoSpace) {
            failure = TransferPayload.REASON_NO_SPACE
        } catch (e: NotSaved) {
            log("xfer ${l.state.id} arrived but not kept: ${e.cause}")
            failure = TransferPayload.REASON_NOT_SAVED
        } catch (e: DirectWifiException) {
            log("xfer ${l.state.id} join failed: ${e.message}")
            failure = reasonFor(e)
        } catch (e: IOException) {
            log("xfer ${l.state.id} receive failed: $e")
            failure = TransferPayload.REASON_CONNECTION
        } finally {
            finish(l, failure)
        }
    }

    /** A radio refusal the user can act on keeps its own outcome code; everything else is a failed link. */
    private fun reasonFor(e: DirectWifiException): Int =
        if (e.refusal == TransferRefusal.Background) TransferPayload.REASON_FOREGROUND else TransferPayload.REASON_JOIN_FAILED

    /** The stream into [sink]; whatever stops it short throws on, and [finish] throws the half-written file away. */
    private fun receive(
        l: LiveTransfer,
        input: InputStream,
        sink: TransferSink,
    ): Boolean = TransferStream.receive(input, sink.stream(), l.state.id, l.state.size, checkNotNull(l.key), progress(l))

    // ---- bookkeeping ----

    /** The end of a role coroutine: close what is open, hand the radio back, and record a failure if there was one. */
    private suspend fun finish(
        l: LiveTransfer,
        failure: Int?,
    ) {
        withContext(NonCancellable) {
            closeSockets(l)
            // Every end short of a commit throws the destination away: a failure, a corrupt stream, a cancel mid-stream
            // (whose CancellationException no catch above sees), and a commit that threw after the verdict.
            l.sink?.takeIf { !l.committed }?.let { sink ->
                runCatching { sink.discard() }.onFailure { log("xfer ${l.state.id} discard failed: $it") }
            }
            l.sink = null
            runCatching { wifi.release() }.onFailure { log("xfer ${l.state.id} release failed: $it") }
            // A peer that ended it after the last byte already said so: no CANCEL goes back.
            failure?.let { fail(l, it, tellPeer = l.peerCancel == null) }
        }
    }

    private suspend fun fail(
        l: LiveTransfer,
        reason: Int,
        tellPeer: Boolean,
    ) {
        val failed =
            mutex.withLock {
                if (l.state.phase.terminal) return
                // A peer's own cancel, kept while the verdict was due, ends it as onCancel would have.
                val phase = if (reason == TransferPayload.REASON_USER) TransferPhase.Cancelled else TransferPhase.Failed
                transitionLocked(l, phase, reason = reason)
                true
            }
        if (failed && tellPeer) {
            signals.sendTransferSignal(
                l.state.peerId,
                TransferPayload(id = l.state.id, phase = TransferPayload.PHASE_CANCEL, reason = reason),
            )
        }
    }

    private fun abort(l: LiveTransfer) {
        l.job?.cancel()
        closeSockets(l)
    }

    private fun closeSockets(l: LiveTransfer) {
        l.listener?.close()
        l.listener = null
        runCatching { l.socket?.close() }
    }

    private fun scheduleExpiry(l: LiveTransfer) {
        scope.launch {
            delay(timings.offerTtlMs)
            mutex.withLock {
                if (l.state.phase == TransferPhase.Offered) transitionLocked(l, TransferPhase.Expired)
            }
        }
    }

    private fun progress(l: LiveTransfer): (Long) -> Unit =
        { bytes ->
            l.bytes = bytes
            val now = clock()
            if (now - l.lastPublishAt >= timings.progressEveryMs) {
                l.lastPublishAt = now
                publish()
            }
        }

    private suspend fun transition(
        l: LiveTransfer,
        phase: TransferPhase,
        savedUri: String? = null,
    ) = mutex.withLock { transitionLocked(l, phase, savedUri = savedUri) }

    /** Under [mutex]: moves [l] to [phase], writes its row, and drops it from the live map once terminal. */
    private suspend fun transitionLocked(
        l: LiveTransfer,
        phase: TransferPhase,
        reason: Int? = null,
        savedUri: String? = null,
    ) {
        l.state = l.state.copy(phase = phase, reason = reason, savedUri = savedUri ?: l.state.savedUri, bytes = l.bytes)
        writeRow(l)
        if (phase.terminal) live.remove(l.state.id)
        publish()
    }

    private suspend fun writeRow(l: LiveTransfer) {
        val s = l.state
        val record =
            TransferRecord(
                id = s.id,
                outgoing = s.outgoing,
                name = s.name,
                size = s.size,
                mime = s.mime,
                phase = s.phase,
                savedUri = s.savedUri,
                reason = s.reason,
                sourceUri = l.sourceUri,
                models = s.models,
            )
        messages.save(record.toEntity(peerId = s.peerId, selfId = me(), sentAt = l.offeredAt))
    }

    private suspend fun me(): String = me ?: selfId().also { me = it }

    private fun busy(): Boolean = live.values.any { !it.state.phase.terminal }

    private fun publish() {
        _states.value = live.values.associate { it.state.id to it.state.copy(bytes = it.bytes) }
    }

    private fun LiveTransfer.isOurOpenOffer(
        senderId: String,
        now: Long,
    ): Boolean = state.outgoing && state.peerId == senderId && state.phase == TransferPhase.Offered && now < state.expiresAt

    private fun LiveTransfer.isOpenIncomingOffer(now: Long): Boolean =
        !state.outgoing && state.phase == TransferPhase.Offered && now < state.expiresAt

    companion object {
        /** A sanity bound on what an offer may name, not a promise about storage. */
        const val MAX_TRANSFER_BYTES = 8L shl 30

        /** An OFFER older than this on arrival is a custody replay — its sender has long stopped waiting. */
        const val STALE_OFFER_MS = 15 * 60_000L

        const val SOCKET_BUFFER_BYTES = 1 shl 20
        const val PORT_MIN = 40_000
        const val PORT_MAX = 60_000

        /** What a READY may name: any unprivileged port. */
        private const val PORT_MIN_ANY = 1024
    }
}
