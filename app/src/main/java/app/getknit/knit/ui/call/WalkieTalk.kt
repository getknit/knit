package app.getknit.knit.ui.call

import app.getknit.knit.call.CallManager
import app.getknit.knit.call.CallPhase
import app.getknit.knit.call.CallState
import app.getknit.knit.call.HeardLedger
import app.getknit.knit.call.OpusPackets
import app.getknit.knit.call.TalkChunk
import app.getknit.knit.call.TalkChunker
import app.getknit.knit.call.TalkCue
import app.getknit.knit.call.TalkIn
import app.getknit.knit.call.TalkLink
import app.getknit.knit.call.TalkQueue
import app.getknit.knit.call.TalkReport
import app.getknit.knit.call.TalkRoute
import app.getknit.knit.call.TalkWire
import app.getknit.knit.call.WalkieMeter
import app.getknit.knit.call.WalkiePolicy
import app.getknit.knit.call.WalkieStats
import app.getknit.knit.call.WalkieTranscriber
import app.getknit.knit.call.fieldEvent
import app.getknit.knit.fieldlog.FieldEvent
import app.getknit.knit.fieldlog.FieldRecorder
import app.getknit.knit.fieldlog.TalkDirection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger

/** Where the talk button stands; [Idle.note] is what the last press came to. */
internal sealed interface Talk {
    data class Idle(
        val note: Note? = null,
    ) : Talk

    data class Recording(
        val elapsedMs: Long,
        val level: Float,
    ) : Talk

    /** The burst has stopped recording and goes out; [cue] says how it stopped, felt and heard on this phone. */
    data class Sending(
        val cue: TalkCue = TalkCue.RELEASED,
    ) : Talk

    data object Hearing : Talk

    /** [CAPPED] is [SENT] for a burst that ran to [WalkiePolicy.MAX_BURST_MS] with the button still held. */
    enum class Note { SENT, CAPPED, TOO_SHORT, NO_MIC, NOT_SENT, NO_LINK }
}

/** The mesh as a walkie call sees its peer (§3.11): the link a chunk would take, and the call's hold on a Wi-Fi Aware one. */
internal interface TalkLinks {
    /** [TalkLink] to [peerId], as it changes; [peerTicketed] is whether its walkie signal said it holds a cast ticket. */
    fun linkTo(
        peerId: String,
        peerTicketed: () -> Boolean,
    ): Flow<TalkLink>

    /** Holds the Wi-Fi Aware link to [peerId] open, or raises one, for a while; false when none will come now. */
    fun raise(peerId: String): Boolean

    /**
     * The way a chunk to [peerId] goes now, by the links `MeshManager.sendCallTalk` sends on: a peer heard through a
     * link lately gets its chunks flooded through the links there are, and one with no radio path at all gets them cast
     * through a relay when one carries them ([peerTicketed] as for [linkTo]), as does a linked peer whose link fades, and
     * any peer when the session's reports said the radios lose its chunks ([castToo]), beside the radio copy.
     */
    fun pathTo(
        peerId: String,
        peerTicketed: Boolean,
        castToo: Boolean = false,
    ): TalkPath
}

/** The way a walkie chunk goes to the peer, as the diagnostics card names it. */
internal enum class TalkPath(
    val label: String,
) {
    WIFI_AWARE("Wi-Fi Aware · direct"),
    BLUETOOTH_CODED("Bluetooth Coded · direct"),
    BLUETOOTH("Bluetooth · direct"),
    FADING("Bluetooth · fading, relay too"),

    // The peer's reports said the radios lose chunks (C-3.11-10): each goes over them and through the relay.
    LOSSY("Radio losing chunks, relay too"),
    FLOODED("Through a neighbour · flooded"),
    SPOOL("Through the relay · cast"),

    // Cast through a relay the peer was never lately seen on (C-3.3-5): it goes out, to nobody we know of.
    CAST_UNSEEN("Relay cast · peer not seen on it"),
    NONE("No link"),
}

/**
 * The walkie-talkie's half of a call (docs/VOICE_CALLS.md §3.11, ADR 2026-10.79yb): while [CallManager]'s call is a
 * connected walkie session, a held button streams a burst to the peer as it is spoken — a chunk of Opus packets every
 * [WalkiePolicy.CHUNK_MS] ([TalkWire]), each a sealed `talk` frame nobody custodies — and the peer's bursts play as their chunks
 * land, put back in order by [TalkQueue]. Nothing is stored and nothing reaches a thread: a chunk that is lost is
 * skipped.
 *
 * Half duplex at this end: talking holds whatever is playing, and what arrives meanwhile plays after the release.
 *
 * Lives as long as the process (a Koin single, started with the call surfaces), so a session keeps talking with the call
 * screen off; `CallService` holds the microphone's foreground state meanwhile.
 *
 * [transcript], where wired, writes each burst heard out as text (C-3.11-9): this loop tells it where a burst begins and
 * ends, and the player's tap hands it the audio between. Each burst spoken goes the same way from the recorder's tap,
 * opened at the press and ended at the release, so the sender's line shows what the model makes of the voice before
 * Opus.
 *
 * Each burst each way, and each session's totals as it ends, go into the field log ([field], ADR 2026-10.a239's
 * amendment): counts, times and the way to the peer, never a sound.
 */
internal class WalkieTalk(
    private val calls: CallManager,
    private val recorder: TalkRecorder,
    private val player: TalkPlayer,
    // `MeshManager.sendCallTalk`: one chunk's plaintext sealed into a `talk` frame of the bytes returned; null when
    // nothing could be sealed. The first flag is the peer's cast ticket, from its walkie signal (§2.7); the second casts
    // it beside the radios, because the peer's reports said they lose chunks (C-3.11-10).
    private val send: suspend (peerId: String, chunk: ByteArray, peerTicketed: Boolean, castToo: Boolean) -> Int?,
    private val scope: CoroutineScope,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val log: (String) -> Unit = {},
    private val links: TalkLinks? = null,
    // Whether a headset has the call's audio now (§3.13): a burst is then spoken into its microphone.
    private val headset: () -> Boolean = { false },
    private val transcript: WalkieTranscriber? = null,
    private val field: FieldRecorder = FieldRecorder.NONE,
    // How a burst spoken ended (C-3.11-5), as a tone on this phone; the call screen adds the haptic. Called with the
    // microphone already stopped, so the tone is never part of the burst.
    private val cue: (TalkCue) -> Unit = {},
) {
    private val _talk = MutableStateFlow<Talk>(Talk.Idle())
    val talk: StateFlow<Talk> = _talk.asStateFlow()

    // For the debug bridge: bursts this phone sent and played, chunks out and in, and chunks skipped as never come.
    // Process-lifetime totals; the card's per-session counts are the meter's.
    val sent = AtomicInteger()
    val heard = AtomicInteger()
    val chunksOut = AtomicInteger()
    val chunksIn = AtomicInteger()
    val lost = AtomicInteger()

    // The session's numbers for the diagnostics card ([stats]).
    private val meter = WalkieMeter()

    // Whether the Wi-Fi Aware link to the walkie call's peer is held now (`TalkLinks.raise`).
    @Volatile
    private var holding = false

    // The session's verdict on its radio path, from the peer's reports (C-3.11-10); null when the peer sends none.
    @Volatile
    private var route: TalkRoute? = null

    // Whether chunks going out now go through a relay beside the radios at the session's own word.
    private fun castToo(): Boolean = route?.castToo == true

    /** The burst being spoken: its bytes on their way to [streamer], and whether its end sends ([kept]) or drops it. */
    private class Outgoing(
        val burst: Int,
        val ticker: Job,
        val bytes: Channel<ByteArray>,
        val kept: CompletableDeferred<Boolean>,
        val streamer: Deferred<Talk.Note>,
    )

    // The recorder is single-threaded; every press, release and cancel takes this.
    private val lock = Mutex()
    private var outgoing: Outgoing? = null

    // This session's next burst number.
    private var burst = 0

    @Volatile
    private var lastActivity = 0L

    private val _link = MutableStateFlow<TalkLink?>(null)

    /** Where a chunk to the walkie call's peer would go now; null outside a walkie call. */
    val link: StateFlow<TalkLink?> = _link.asStateFlow()

    fun start() {
        scope.launch {
            calls.state
                .map { it?.takeIf(::isSession) }
                .distinctUntilChanged { a, b -> a?.id == b?.id }
                .collectLatest { state ->
                    if (state != null) session(state) else idle()
                }
        }
        scope.launch {
            calls.state
                .map { it?.takeIf { s -> s.walkie && s.phase != CallPhase.Ended } }
                .distinctUntilChanged { a, b -> a?.id == b?.id }
                .collectLatest { call -> if (call != null) linking(call) else _link.value = null }
        }
    }

    /**
     * From the ring to the hang-up of a walkie call: track the link to its peer, and keep holding a Wi-Fi Aware one
     * ([WalkiePolicy.LINK_ASK_MS]), which the radio would otherwise close between bursts. Bluetooth can take minutes to
     * link two phones that Wi-Fi Aware already sees, and with no link at all a chunk goes nowhere.
     */
    private suspend fun linking(call: CallState) {
        val mesh = links ?: return
        coroutineScope {
            launch { mesh.linkTo(call.peerId) { peerTicketed() }.collect { _link.value = it } }
            var armed: Boolean? = null
            while (true) {
                val now = mesh.raise(call.peerId)
                if (now != armed) log("walkie: Wi-Fi Aware link to ${call.peerId} ${if (now) "asked for" else "not available now"}")
                armed = now
                holding = now
                delay(WalkiePolicy.LINK_ASK_MS)
            }
        }
    }

    /** The session's numbers now, for the call screen's diagnostics card; read once a second while it shows. */
    fun stats(): WalkieStats {
        val peerId =
            calls.state.value
                ?.takeIf { it.walkie }
                ?.peerId
        return meter.snapshot().copy(path = peerId?.let(::pathTo))
    }

    // Whether the walkie call's peer said it holds a cast ticket (§2.7): then a relay that sells casts carries ours.
    private fun peerTicketed(): Boolean =
        calls.state.value
            ?.takeIf { it.walkie }
            ?.peerCastTicketed == true

    // Where a chunk to the peer goes now ("Wi-Fi Aware · direct · held"), and whether the call holds that link.
    private fun pathTo(peerId: String): String? {
        val path = links?.pathTo(peerId, peerTicketed(), castToo()) ?: return null
        return path.label + if (holding && path == TalkPath.WIFI_AWARE) " · held" else ""
    }

    /** Starts a burst; false when no walkie session is connected, or the microphone would not open. */
    suspend fun press(): Boolean =
        lock.withLock {
            if (outgoing != null) return@withLock false
            val call = calls.state.value?.takeIf(::isSession) ?: return@withLock false
            // Half duplex: what plays waits for the release.
            hold(true)
            val bytes = Channel<ByteArray>(Channel.UNLIMITED)
            val b = burst
            // Open before the microphone, whose first read comes on its own thread.
            transcript?.begin(b, mine = true)
            if (!recorder.start(headset()) { bytes.trySend(it) }) {
                transcript?.abandon(mine = true)
                hold(false)
                _talk.value = Talk.Idle(Talk.Note.NO_MIC)
                return@withLock false
            }
            burst++
            lastActivity = clock()
            val kept = CompletableDeferred<Boolean>()
            val streamer = scope.async(Dispatchers.Default) { stream(call, b, bytes, kept) }
            val ticker =
                scope.launch {
                    while (true) {
                        val elapsed = recorder.elapsedMs()
                        if (elapsed >= WalkiePolicy.MAX_BURST_MS) {
                            scope.launch { release(TalkCue.CAPPED) }
                            break
                        }
                        _talk.value = Talk.Recording(elapsed, recorder.amplitude())
                        delay(TICK_MS)
                    }
                }
            outgoing = Outgoing(b, ticker, bytes, kept, streamer)
            true
        }

    /**
     * Ends the burst: its last chunk goes out, unless it ended before its first did. [how] is the user letting go, or the
     * burst reaching [WalkiePolicy.MAX_BURST_MS] while the button is still held.
     */
    suspend fun release(how: TalkCue = TalkCue.RELEASED) {
        val o =
            lock.withLock {
                val o = outgoing ?: return
                outgoing = null
                o.ticker.cancel()
                _talk.value = Talk.Sending(how)
                // Joins the drain, so every byte recorded has reached the streamer before its channel closes.
                recorder.stop()
                // Every sample spoken has been through the tap: sent or not, the model hears it.
                transcript?.end(o.burst, mine = true)
                o
            }
        // After the stop: the microphone never takes the tone into the burst.
        runCatching { cue(how) }.onFailure { log("walkie: cue $how not played: ${it.javaClass.simpleName}") }
        o.bytes.close()
        o.kept.complete(true)
        val note = o.streamer.await()
        hold(false)
        lastActivity = clock()
        _talk.value = Talk.Idle(if (how == TalkCue.CAPPED && note == Talk.Note.SENT) Talk.Note.CAPPED else note)
    }

    /** Drops the burst being spoken; a peer already hearing it is told it ended. */
    suspend fun cancel() {
        val o =
            lock.withLock {
                val o = outgoing ?: return@withLock null
                outgoing = null
                o.ticker.cancel()
                recorder.cancel()
                transcript?.abandon(mine = true)
                o
            }
        if (o != null) {
            o.bytes.close()
            o.kept.complete(false)
            o.streamer.await()
            hold(false)
        }
        _talk.value = Talk.Idle()
    }

    // Talking holds the player, and the meter's reckoning of what it has left to play with it.
    private fun hold(on: Boolean) {
        player.hold(on)
        meter.held(on, clock())
    }

    private fun isSession(state: CallState): Boolean = state.walkie && state.phase == CallPhase.Connected

    private suspend fun idle() {
        cancel()
        _talk.value = Talk.Idle()
    }

    /**
     * One burst out: packets as the encoder makes them, a chunk each time [WalkiePolicy.CHUNK_MS] of audio is in hand,
     * and the rest with the end mark. Ended before its first chunk, it was a slipped thumb, and nothing goes at all.
     */
    private suspend fun stream(
        call: CallState,
        b: Int,
        packets: ReceiveChannel<ByteArray>,
        kept: Deferred<Boolean>,
    ): Talk.Note {
        val chunker = TalkChunker()
        val tag = TalkWire.tagOf(call.id)
        var q = 0
        var delivered = 0
        var bytesOut = 0
        // For the burst's log line: when each chunk was cut and when its send returned, from the press.
        val began = clock()
        val timings = mutableListOf<String>()
        meter.burstOut()

        suspend fun out(
            audio: ByteArray,
            end: Boolean,
        ) {
            val cut = clock() - began
            val chunk = TalkWire.encode(tag, TalkChunk(b = b, q = q++, a = audio, e = end))
            bytesOut += chunk.size
            // No link at this instant (a held link coming round again, say) is worth a moment's wait; the chunks
            // behind it queue meanwhile and go in order.
            var frame: Int? = null
            for (attempt in 0 until SEND_TRIES) {
                frame = send(call.peerId, chunk, peerTicketed(), castToo())
                if (frame != null || attempt == SEND_TRIES - 1) break
                delay(SEND_RETRY_MS)
            }
            if (frame != null) {
                delivered++
                chunksOut.incrementAndGet()
            }
            val handed = clock() - began
            meter.chunkOut(audio, frame, handed - cut)
            timings += "$cut>$handed"
        }
        for (packet in packets) chunker.add(packet)?.let { out(it, end = false) }
        val keep = kept.await()
        if (q == 0) return Talk.Note.TOO_SHORT
        out(if (keep) chunker.flush() else ByteArray(0), end = true)
        awaitReport(b, chunks = q, delivered = delivered)
        recordOut(call.peerId, chunks = q, audioMs = chunker.totalMs, sent = delivered)
        log(
            "walkie: burst $b of ${chunker.totalMs}ms to ${call.peerId} in $q chunks ($bytesOut B before the seal), $delivered sealed, " +
                "via ${pathTo(call.peerId) ?: "?"}; " +
                "cut>sent ms ${timings.joinToString(
                    " ",
                )}",
        )
        if (delivered == 0) return if (_link.value == TalkLink.NONE) Talk.Note.NO_LINK else Talk.Note.NOT_SENT
        sent.incrementAndGet()
        meter.burstSent()
        return Talk.Note.SENT
    }

    // Burst [b] is judged by the peer's report of it, or its absence (C-3.11-10); a burst nothing took has nothing to judge.
    private fun awaitReport(
        b: Int,
        chunks: Int,
        delivered: Int,
    ) {
        if (delivered > 0) route?.sent(b, chunks, at = clock())
    }

    /** A burst out's field-log line: its chunks, those a link took, and the way to [peerId] as it ended. */
    private fun recordOut(
        peerId: String,
        chunks: Int,
        audioMs: Long,
        sent: Int,
    ) {
        val path = links?.pathTo(peerId, peerTicketed(), castToo())
        field.record(
            FieldEvent.Talk(
                TalkDirection.OUT,
                chunks = chunks,
                audioMs = audioMs,
                sent = sent,
                path = path,
                held = holding && path == TalkPath.WIFI_AWARE,
            ),
        )
    }

    /** One connected session with [call]'s peer: play what it streams, hang up when it goes quiet. */
    private suspend fun session(call: CallState) {
        burst = 0
        lastActivity = clock()
        meter.reset()
        transcript?.reset()
        // C-3.11-10: reports go only to a peer that said it reads them, and only its reports judge our path.
        val reports = call.peerReportsHeard
        route = if (reports) TalkRoute(judged = meter::judged) else null
        val ledger = if (reports) HeardLedger() else null
        log("walkie: session ${call.id} with ${call.peerId}${if (call.degraded) " (degraded from a live call)" else ""}")
        try {
            coroutineScope {
                launch { hangUpWhenIdle() }
                if (ledger != null) launch { reporting(call, ledger) }
                hear(call.id, ledger)
            }
        } finally {
            route = null
            field.record(meter.snapshot().fieldEvent())
            // The model's native heap goes with the call, not two idle minutes later.
            transcript?.release()
        }
    }

    /** Plays the peer's bursts as their chunks land, in order, until the session ends. */
    @Suppress("CyclomaticComplexMethod") // one loop over every way a chunk can land: first, in order, late, last
    private suspend fun hear(
        callId: String,
        ledger: HeardLedger?,
    ) = coroutineScope {
        val inbox = calls.talk.filter { it.callId == callId }.produceIn(this)
        val queue = TalkQueue()
        var skipped = 0
        var cutShort = 0
        // Per burst, for its log line: when each chunk came, the chunks skipped, and when the burst began to play.
        val arrivals = mutableMapOf<Int, MutableList<Long>>()
        var burstSkipped = 0
        var playedAt = 0L

        fun take(t: TalkIn) {
            // Counted before the queue, which drops the second copy of a chunk that came both ways.
            ledger?.note(t.chunk, t.viaRelay, t.at)
            if (queue.offer(t.chunk, t.at)) {
                chunksIn.incrementAndGet()
                arrivals.getOrPut(t.chunk.b) { mutableListOf() } += t.at
                meter.chunkIn(t.chunk, t.at, t.sentAt, t.frameBytes)
            }
            lastActivity = clock()
        }
        try {
            while (true) {
                when (val step = queue.poll(clock())) {
                    is TalkQueue.Step.Play -> {
                        // Skips happen only in the burst playing, so those since the last piece are this burst's.
                        val skippedNow = queue.skipped - skipped
                        skipped = queue.skipped
                        burstSkipped = if (step.first) skippedNow else burstSkipped + skippedNow
                        if (step.first) {
                            playedAt = clock()
                            arrivals[step.burst]?.minOrNull()?.let { meter.started(it, playedAt) }
                            if (_talk.value is Talk.Idle) _talk.value = Talk.Hearing
                            transcript?.begin(step.burst)
                        }
                        meter.played(step.audio, step.first, clock())
                        withContext(Dispatchers.IO) { player.play(OpusPackets.split(step.audio), step.first, step.last) }
                        if (step.last) {
                            // Played out: every sample has been through the tap, so the burst is whole.
                            transcript?.end(step.burst)
                            logHeard(step.burst, arrivals.remove(step.burst).orEmpty(), playedAt, burstSkipped, step.cutShort)
                            heard.incrementAndGet()
                            meter.burstHeard()
                            if (_talk.value == Talk.Hearing) _talk.value = Talk.Idle()
                        }
                        lost.addAndGet(skippedNow)
                        meter.skipped(skippedNow)
                        lastActivity = clock()
                    }

                    is TalkQueue.Step.Wait -> {
                        withTimeoutOrNull(step.ms) { inbox.receive() }?.let(::take)
                    }

                    TalkQueue.Step.Idle -> {
                        take(inbox.receive())
                    }
                }
                // A burst given up on before it played anything is cut short too, with no step of its own.
                if (queue.cutShort > cutShort) {
                    meter.cutShort(queue.cutShort - cutShort)
                    cutShort = queue.cutShort
                }
                // Whatever else came meanwhile goes into the queue before it is asked again.
                while (true) take(inbox.tryReceive().getOrNull() ?: break)
            }
        } finally {
            player.close()
            transcript?.abandon()
            if (_talk.value == Talk.Hearing) _talk.value = Talk.Idle()
        }
    }

    // One line per burst heard, in the log and the field log: when each chunk came after the first, whether its last
    // never came, and when the burst began to play.
    private fun logHeard(
        b: Int,
        at: List<Long>,
        playedAt: Long,
        skipped: Int,
        cutShort: Boolean,
    ) {
        val first = at.minOrNull() ?: return
        val offsets = at.joinToString("/") { "${it - first}" }
        val cut = if (cutShort) ", cut short" else ""
        log("walkie: heard burst $b: ${at.size} chunks, $skipped skipped$cut, arrivals +$offsets ms, began +${playedAt - first} ms")
        field.record(
            FieldEvent.Talk(
                TalkDirection.IN,
                chunks = at.size,
                lost = skipped,
                waitMs = playedAt - first,
                spreadMs = at.max() - first,
            ),
        )
    }

    /**
     * C-3.11-10, both halves: each of the peer's bursts reported back to it once [ledger] says it is due, the same way a
     * chunk goes; and the peer's reports of ours (with the overdue ones) judged into [route], whose verdict the next
     * chunk out goes by.
     */
    private suspend fun reporting(
        call: CallState,
        ledger: HeardLedger,
    ) = coroutineScope {
        val tag = TalkWire.tagOf(call.id)
        launch {
            calls.talkReports.filter { it.callId == call.id }.collect { judge(call.peerId) { report(it.report) } }
        }
        while (true) {
            for (report in ledger.due(clock())) report(call.peerId, tag, report)
            judge(call.peerId) { expire(clock()) }
            delay(REPORT_TICK_MS)
        }
    }

    // One report of the peer's burst, sent as its chunks came; lost like a chunk when nothing carries it.
    private suspend fun report(
        peerId: String,
        tag: Int,
        report: TalkReport,
    ) {
        send(peerId, TalkWire.encodeReport(tag, report), peerTicketed(), castToo())
        log("walkie: reported burst ${report.b}: ${report.radio} over the radios, ${report.relay} through the relay")
    }

    // Applies [step] to the session's route, and logs a change of its verdict.
    private inline fun judge(
        peerId: String,
        step: TalkRoute.() -> Unit,
    ) {
        val r = route ?: return
        val before = r.castToo
        r.step()
        if (r.castToo == before) return
        val verdict =
            if (r.castToo) "radios losing chunks to $peerId; casting through the relay too" else "radios whole to $peerId again; radio only"
        log("walkie: $verdict")
    }

    private suspend fun hangUpWhenIdle() {
        while (true) {
            val left = lastActivity + WalkiePolicy.IDLE_END_MS - clock()
            if (left <= 0) {
                log("walkie: no burst either way for ${WalkiePolicy.IDLE_END_MS / MS_PER_MIN} min; hanging up")
                calls.hangUp()
                return
            }
            delay(left)
        }
    }

    private companion object {
        const val TICK_MS = 60L

        // A chunk that finds no link tries again this often, this many times: 2.5 s, past a held Wi-Fi Aware link's
        // recycle (about 1.9 s from its window's teardown to the next link-up, device-measured).
        const val SEND_RETRY_MS = 250L
        const val SEND_TRIES = 11

        // How often the session looks for reports due out and reports overdue in (C-3.11-10).
        const val REPORT_TICK_MS = 250L
        const val MS_PER_MIN = 60_000L
    }
}
