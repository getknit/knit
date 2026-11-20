package app.getknit.knit.update

import app.getknit.knit.mesh.protocol.AppPin
import app.getknit.knit.mesh.protocol.AppUpdatePayload
import app.getknit.knit.mesh.protocol.FrameId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

/** The mesh seam of a peer update: one link-local sealed `CTL_APP_UPDATE` (ADR 2026-11.29m7). */
fun interface UpdateSignals {
    /** Seals [payload] to [peerId] under a confirmed session and hands it to that contact's live link; false when it could not. */
    suspend fun sendAppUpdate(
        peerId: String,
        payload: AppUpdatePayload,
    ): Boolean
}

/** This phone as a peer update sees it: its [build], and whether it may hand that build out or take a newer one. */
data class LocalBuild(
    val build: AppBuild,
    val canServe: Boolean,
    val canInstall: Boolean,
)

/** Every timer of a peer update, in one place so a test can shrink them. Production uses the defaults. */
data class UpdateTimings(
    /** How long a contact must stay on a live link before this phone announces to it: a passing link is not a meeting. */
    val dwellMs: Long = HALF_MINUTE_MS,
    /** At most one announce per contact in this long, for as long as this process runs this build. */
    val announceEveryMs: Long = DAY_MS,
    /**
     * How long a contact still on the link waits before an announce that could not go is tried again: the contact was
     * not yet accepted, its session not yet confirmed, or the seal failed. Ends when the link does.
     */
    val announceRetryMs: Long = TEN_MINUTES_MS,
    /** How long an ask waits for its answer, and an offer may still answer it. */
    val askTtlMs: Long = TEN_MINUTES_MS,
    /** How long a request waits for the person on the newer phone. */
    val requestTtlMs: Long = TEN_MINUTES_MS,
    /** How long a contact this phone said Not now to stays quiet. */
    val quietMs: Long = HOUR_MS,
    /** How long an announce heard stays good. */
    val heardTtlMs: Long = DAY_MS,
)

private const val SECOND_MS = 1_000L
private const val HALF_MINUTE_MS = 30 * SECOND_MS
private const val TEN_MINUTES_MS = 600 * SECOND_MS
private const val HOUR_MS = 6 * TEN_MINUTES_MS
private const val DAY_MS = 24 * HOUR_MS

/** Where this phone's ask of one contact stands. */
sealed interface AskState {
    val build: AppBuild

    /** Sent; the contact has not answered. */
    data class Waiting(
        override val build: AppBuild,
        val requestId: String,
        val at: Long,
    ) : AskState

    /** The contact's offer answered it at [at]; the transfer card carries it from here. */
    data class Answered(
        override val build: AppBuild,
        val at: Long,
    ) : AskState

    /** The contact turned it down, for [reason] (an `AppUpdatePayload.REASON_*`). */
    data class Declined(
        override val build: AppBuild,
        val reason: Int,
    ) : AskState

    /** Nothing came back in time. */
    data class Unanswered(
        override val build: AppBuild,
    ) : AskState
}

/** What the older phone's chat shows: [build] is newer than this phone's and can install here; [ask] how far it got. */
data class UpdateHint(
    val peerId: String,
    val build: AppBuild,
    val ask: AskState?,
)

/** A contact's ask for this phone's build, waiting for the person here. */
data class UpdateRequest(
    val peerId: String,
    val requestId: String,
    val versionCode: Long,
    val at: Long,
)

/** What [PeerUpdates.ask] did. */
enum class AskOutcome { Sent, Already, NotWorthAsking, NotNearby, NotSent }

/**
 * The peer-update coordinator (ADR 2026-11.29m7): pure Kotlin, one instance for the app, JVM-tested against fakes of
 * its seams (`PeerUpdatesTest`). It announces this phone's build to contacts that stay on a live link, keeps what
 * contacts announced, sends this phone's asks and holds contacts' asks of it. The bytes themselves go over a direct
 * transfer (`transfer/TransferManager.offerApp`); [expects] is how that transfer knows an update offer was asked for.
 *
 * Everything here is memory only and ends with the process: an update restarts it, so a new build announces afresh.
 * Every value a contact sent is bounded here before anything reads it, and a frame counts only when its sender's own
 * link carried it ([onSignal]'s `fromOwnLink`), recently, while that contact is near.
 */
@Suppress("TooManyFunctions")
class PeerUpdates(
    private val local: suspend () -> LocalBuild?,
    private val signals: UpdateSignals,
    /** The contacts on a short-range link now. */
    private val neighbors: StateFlow<Set<String>>,
    /** Whether [neighbors]' id may be announced to: an accepted contact that takes direct transfers, on a proven link. */
    private val candidate: suspend (String) -> Boolean,
    private val scope: CoroutineScope,
    private val sdkInt: Int,
    private val clock: () -> Long = System::currentTimeMillis,
    private val timings: UpdateTimings = UpdateTimings(),
    private val log: (String) -> Unit = {},
    /** The block list: a contact on it is forgotten at once ([forget]), whatever was under way with it. */
    private val blocked: Flow<Set<String>> = emptyFlow(),
) {
    private class Heard(
        val build: AppBuild,
        val at: Long,
    )

    private val lock = Any()
    private val dwell = HashMap<String, Job>()
    private val announcedAt = HashMap<String, Long>()
    private val quietUntil = HashMap<String, Long>()

    // Request ids this phone has seen from each contact, kept for as long as a request can live, so a replay is never a
    // second request.
    private val seenRequests = HashMap<String, Long>()
    private val heardBuilds = MutableStateFlow<Map<String, Heard>>(emptyMap())
    private val _asks = MutableStateFlow<Map<String, AskState>>(emptyMap())
    private val _requests = MutableStateFlow<Map<String, UpdateRequest>>(emptyMap())

    /** Contacts' asks of this phone, keyed by contact, while each is waiting for the person here. */
    val requests: StateFlow<Map<String, UpdateRequest>> = _requests.asStateFlow()

    /** This phone's asks, keyed by contact. */
    val asks: StateFlow<Map<String, AskState>> = _asks.asStateFlow()

    private var watching: Job? = null

    /** Starts announcing to contacts as they stay on a link. Idempotent. */
    fun start() {
        synchronized(lock) {
            if (watching != null) return
            watching = scope.launch { neighbors.collect { onNeighbors(it) } }
            scope.launch { blocked.collect { ids -> ids.forEach(::forget) } }
        }
    }

    private fun onNeighbors(now: Set<String>) {
        synchronized(lock) {
            dwell.keys.filter { it !in now }.forEach { dwell.remove(it)?.cancel() }
            now.filter { it !in dwell }.forEach { peer -> dwell[peer] = scope.launch { dwellThenAnnounce(peer) } }
        }
    }

    /**
     * Announces to [peer] once it has stayed on the link [UpdateTimings.dwellMs], trying again every
     * [UpdateTimings.announceRetryMs] while it stays and the announce could not go. [onNeighbors] cancels it when the link
     * goes. A failed round (a store read that threw) is one more try, never the end of the loop.
     */
    private suspend fun dwellThenAnnounce(peer: String) {
        delay(timings.dwellMs)
        while (peer in neighbors.value) {
            val done = runCatching { announceOnce(peer) }.getOrElse { if (it is CancellationException) throw it else false }
            if (done) return
            delay(timings.announceRetryMs)
        }
    }

    /** One announce to [peer]: true when nothing more is owed (it went, it was not due, or this phone cannot serve). */
    private suspend fun announceOnce(peer: String): Boolean {
        val mine = local()?.takeIf { it.canServe } ?: return true
        val due =
            synchronized(lock) {
                val last = announcedAt[peer]
                last == null || clock() - last >= timings.announceEveryMs
            }
        if (!due) return true
        if (!candidate(peer)) return false
        val b = mine.build
        val payload =
            AppUpdatePayload(
                phase = AppUpdatePayload.PHASE_ANNOUNCE,
                versionCode = b.versionCode,
                versionName = b.versionName,
                certPin = b.certPin,
                size = b.size,
                minSdk = b.minSdk,
                slim = b.slim.takeIf { it },
            )
        if (!signals.sendAppUpdate(peer, payload)) return false
        synchronized(lock) { announcedAt[peer] = clock() }
        log("update: announced ${b.versionCode} to ${peer.take(ID_CHARS)}")
        return true
    }

    // ---- the older phone ----

    /**
     * The hint for [peerId]'s chat: what that contact announced, while it is newer than this phone's build and could
     * install here, and the contact is still near. Null otherwise.
     */
    fun hintFor(peerId: String): Flow<UpdateHint?> =
        combine(heardBuilds, _asks, neighbors) { heard, asks, near ->
            val build = heard[peerId]?.takeIf { clock() - it.at < timings.heardTtlMs && peerId in near }?.build
            val mine = local()?.takeIf { it.canInstall }
            if (build == null || mine == null || !UpdateEligibility.worthAsking(mine.build, build, sdkInt)) {
                null
            } else {
                UpdateHint(peerId, build, asks[peerId]?.let { expire(it) })
            }
        }.distinctUntilChanged()

    /**
     * [ask] as it reads now: a wait past its time is unanswered, and an answered ask is spent once that time has passed
     * too, so a transfer that failed can be asked for again.
     */
    private fun expire(ask: AskState): AskState? {
        val now = clock()
        return when {
            ask is AskState.Waiting && now - ask.at >= timings.askTtlMs -> AskState.Unanswered(ask.build)
            ask is AskState.Answered && now - ask.at >= timings.askTtlMs -> null
            else -> ask
        }
    }

    private fun setAsk(
        peerId: String,
        ask: AskState?,
    ) {
        _asks.value = if (ask == null) _asks.value - peerId else _asks.value + (peerId to ask)
    }

    /** Asks [peerId] for the build it announced. The caller has run the consent, the install grant and the space check. */
    suspend fun ask(peerId: String): AskOutcome {
        val build = synchronized(lock) { heardBuilds.value[peerId]?.build } ?: return AskOutcome.NotWorthAsking
        val mine = local() ?: return AskOutcome.NotWorthAsking
        if (!mine.canInstall || !UpdateEligibility.worthAsking(mine.build, build, sdkInt)) return AskOutcome.NotWorthAsking
        if (peerId !in neighbors.value) return AskOutcome.NotNearby
        val requestId = FrameId.new()
        val now = clock()
        val claimed =
            synchronized(lock) {
                val live = _asks.value[peerId]?.let { expire(it) }
                if (live is AskState.Waiting || live is AskState.Answered) {
                    false
                } else {
                    setAsk(peerId, AskState.Waiting(build, requestId, now))
                    true
                }
            }
        if (!claimed) return AskOutcome.Already
        val sent =
            signals.sendAppUpdate(
                peerId,
                AppUpdatePayload(phase = AppUpdatePayload.PHASE_REQUEST, versionCode = build.versionCode, requestId = requestId),
            )
        if (!sent) {
            synchronized(lock) { setAsk(peerId, null) }
            return AskOutcome.NotSent
        }
        scope.launch {
            delay(timings.askTtlMs)
            synchronized(lock) { _asks.value[peerId]?.let { setAsk(peerId, expire(it)) } }
        }
        log("update: asked ${peer(peerId)} for ${build.versionCode}")
        return AskOutcome.Sent
    }

    /**
     * Whether an app-update offer from [peerId] naming [pin], of [size] bytes, answers this phone's ask: the contact
     * asked, the request id and version asked for, no larger than announced, in time. True spends the ask, so a second
     * offer naming it is refused. Asked by the transfer before it admits the offer, never by anything else.
     */
    fun expects(
        peerId: String,
        pin: AppPin,
        size: Long,
    ): Boolean =
        synchronized(lock) {
            val ask = _asks.value[peerId] as? AskState.Waiting ?: return false
            val ok = ask.names(pin, size) && clock() - ask.at < timings.askTtlMs
            if (ok) setAsk(peerId, AskState.Answered(ask.build, clock()))
            ok
        }

    // The request and the version this ask sent, and no more bytes than its announce said.
    private fun AskState.Waiting.names(
        pin: AppPin,
        size: Long,
    ): Boolean = requestId == pin.requestId && build.versionCode == pin.versionCode && size in 1..build.size

    // ---- the newer phone ----

    /**
     * Takes [peerId]'s waiting request for the person's Send, or null when it is gone (expired, already answered, or
     * this phone no longer runs the build asked for). The caller then offers the APK (`TransferManager.offerApp`), and
     * calls [decline] with [AppUpdatePayload.REASON_BUSY] when the offer cannot start.
     */
    suspend fun claim(peerId: String): UpdateRequest? {
        val mine = local()
        val request =
            synchronized(lock) {
                val r = _requests.value[peerId] ?: return@synchronized null
                _requests.value -= peerId
                r.takeIf { clock() - it.at < timings.requestTtlMs }
            } ?: return null
        if (mine == null || !mine.canServe || mine.build.versionCode != request.versionCode) {
            answerDecline(peerId, request, AppUpdatePayload.REASON_GONE)
            return null
        }
        return request
    }

    /** Turns down [peerId]'s waiting request ([reason] Not now keeps that contact quiet for a while). */
    suspend fun decline(
        peerId: String,
        reason: Int = AppUpdatePayload.REASON_NOT_NOW,
        request: UpdateRequest? = null,
    ) {
        val r =
            request ?: synchronized(lock) {
                _requests.value[peerId]?.also { _requests.value -= peerId }
            } ?: return
        answerDecline(peerId, r, reason)
    }

    private suspend fun answerDecline(
        peerId: String,
        request: UpdateRequest,
        reason: Int,
    ) {
        if (reason == AppUpdatePayload.REASON_NOT_NOW) synchronized(lock) { quietUntil[peerId] = clock() + timings.quietMs }
        sendDecline(peerId, request, reason)
    }

    private suspend fun sendDecline(
        peerId: String,
        request: UpdateRequest,
        reason: Int,
    ) {
        signals.sendAppUpdate(
            peerId,
            AppUpdatePayload(
                phase = AppUpdatePayload.PHASE_DECLINE,
                versionCode = request.versionCode,
                requestId = request.requestId,
                reason = reason,
            ),
        )
        log("update: declined ${peer(peerId)} ($reason)")
    }

    // ---- the mesh's side ----

    /**
     * A sealed `CTL_APP_UPDATE` from [senderId] opened. [fromOwnLink] says it came in on a link whose peer is its
     * sender, not flooded (`relay = false`): anything else is dropped before this is called, and false here drops it
     * too. The frame's signed time is never read: it is the sender's clock, and phones offline for weeks drift (ADR 026).
     * A replay never gets here, since the ratchet refuses a consumed index as a duplicate; the own-clock TTLs and the
     * answered request ids bound the rest. Returns the request worth a notification, or null.
     */
    suspend fun onSignal(
        senderId: String,
        payload: AppUpdatePayload,
        fromOwnLink: Boolean,
    ): UpdateRequest? {
        val now = clock()
        val why =
            when {
                !fromOwnLink -> "not from its sender's link"

                senderId !in neighbors.value -> "not near"

                // The send side's test as well: an accepted contact taking direct transfers, on a proven link.
                !candidate(senderId) -> "not a candidate"

                else -> null
            }
        if (why != null) {
            log("update: dropped phase ${payload.phase} from ${peer(senderId)}: $why")
            return null
        }
        return when (payload.phase) {
            AppUpdatePayload.PHASE_ANNOUNCE -> onAnnounce(senderId, payload, now).let { null }
            AppUpdatePayload.PHASE_REQUEST -> onRequest(senderId, payload, now)
            AppUpdatePayload.PHASE_DECLINE -> onDecline(senderId, payload).let { null }
            else -> null
        }
    }

    private fun onAnnounce(
        senderId: String,
        p: AppUpdatePayload,
        now: Long,
    ) {
        val build = buildOf(p)
        if (build == null) {
            log("update: dropped a malformed announce from ${peer(senderId)}")
            return
        }
        synchronized(lock) { heardBuilds.value += senderId to Heard(build, now) }
        log("update: ${peer(senderId)} runs ${build.versionCode}")
    }

    private suspend fun onRequest(
        senderId: String,
        p: AppUpdatePayload,
        now: Long,
    ): UpdateRequest? {
        val requestId = p.requestId?.takeIf { FrameId.toBytesOrNull(it) != null } ?: return null
        val mine = local()
        val request = UpdateRequest(senderId, requestId, p.versionCode, now)
        val quiet =
            synchronized(lock) {
                seenRequests.entries.removeAll { now - it.value >= timings.requestTtlMs }
                val key = "$senderId/$requestId"
                // A replay, or a second ask while one waits for the person here: neither is a request of its own.
                if (key in seenRequests || _requests.value[senderId]?.let { now - it.at < timings.requestTtlMs } == true) {
                    return null
                }
                seenRequests[key] = now
                (quietUntil[senderId] ?: 0L) > now
            }
        val reason =
            when {
                mine == null || !mine.canServe || mine.build.versionCode != p.versionCode -> AppUpdatePayload.REASON_GONE

                // Told again, without asking the person again or starting the hour over.
                quiet -> AppUpdatePayload.REASON_NOT_NOW

                else -> null
            }
        if (reason != null) {
            sendDecline(senderId, request, reason)
            return null
        }
        val admitted = request
        synchronized(lock) { _requests.value += senderId to admitted }
        scope.launch {
            delay(timings.requestTtlMs)
            synchronized(lock) { if (_requests.value[senderId] == admitted) _requests.value -= senderId }
        }
        log("update: ${peer(senderId)} asked for ${p.versionCode}")
        return admitted
    }

    private fun onDecline(
        senderId: String,
        p: AppUpdatePayload,
    ) {
        synchronized(lock) {
            val ask = _asks.value[senderId] as? AskState.Waiting ?: return
            if (ask.requestId != p.requestId) return
            setAsk(senderId, AskState.Declined(ask.build, p.reason ?: AppUpdatePayload.REASON_NOT_NOW))
        }
    }

    /** Drops everything held about [peerId]: a block or a removal ends whatever was under way with it. */
    fun forget(peerId: String) {
        synchronized(lock) {
            dwell.remove(peerId)?.cancel()
            announcedAt.remove(peerId)
            quietUntil.remove(peerId)
            seenRequests.keys.removeAll { it.startsWith("$peerId/") }
            heardBuilds.value -= peerId
            _asks.value -= peerId
            _requests.value -= peerId
        }
    }

    private fun peer(id: String) = id.take(ID_CHARS)

    companion object {
        private const val ID_CHARS = 8
        private const val MAX_MIN_SDK = 1_000
        private const val MAX_NAME_CHARS = 32

        /**
         * [p] read as an announced build, or null when any field is missing or out of bounds: a name that does not
         * derive its code, a track that disagrees with the name, a size past [UpdateEligibility.MAX_APK_BYTES].
         */
        internal fun buildOf(p: AppUpdatePayload): AppBuild? {
            val name = p.versionName?.takeIf { it.length <= MAX_NAME_CHARS } ?: return null
            val pin = p.certPin ?: return null
            val size = p.size?.takeIf { it in 1..UpdateEligibility.MAX_APK_BYTES } ?: return null
            val minSdk = p.minSdk?.takeIf { it in 1..MAX_MIN_SDK } ?: return null
            val slim = p.slim == true
            if (VersionNames.codeOf(name) != p.versionCode || VersionNames.isSlim(name) != slim) return null
            return AppBuild(p.versionCode, name, pin, size, minSdk, slim)
        }
    }
}
