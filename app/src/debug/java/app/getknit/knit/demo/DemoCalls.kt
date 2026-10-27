@file:Suppress("MagicNumber") // a demo's pacing: one hop, one ICE settle, a frame of audio

package app.getknit.knit.demo

import android.content.Context
import android.util.Log
import app.getknit.knit.BuildConfig
import app.getknit.knit.call.CallCaptioner
import app.getknit.knit.call.CallKind
import app.getknit.knit.call.CallManager
import app.getknit.knit.call.CallMedia
import app.getknit.knit.call.CallPhase
import app.getknit.knit.call.CallSession
import app.getknit.knit.call.CallSignals
import app.getknit.knit.call.CallState
import app.getknit.knit.call.CallWalkie
import app.getknit.knit.call.MediaEvent
import app.getknit.knit.call.TalkChunk
import app.getknit.knit.call.TalkChunker
import app.getknit.knit.call.TalkReport
import app.getknit.knit.call.TalkWire
import app.getknit.knit.call.TurnServer
import app.getknit.knit.call.TurnSource
import app.getknit.knit.call.WalkiePolicy
import app.getknit.knit.data.settings.TranscriptStore
import app.getknit.knit.identity.Identity
import app.getknit.knit.ui.call.TalkRecorder
import app.getknit.knit.ui.call.WalkieTalk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.Koin
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The call plane's demo stand-in, for the screenshot build and the trailer director (`-PseedDemo` / `-PdemoDirector`,
 * calls wired): the app's own [CallManager] — and with it the call screen, the ring and its full-screen intent, the
 * Telecom call, the microphone's foreground service, the call bar and the line left in the thread — dials, and is
 * dialled by, scripted peers over loopback. An emulator has no radio and no second phone; nothing above the seams is
 * staged.
 *
 * Each peer is a second, headless [CallManager] under the peer's node id, running the protocol both ends of a real call
 * run, so every phase, timer and record is the real one. Their [CallSignals] hand each frame to the other side in order,
 * one [HOP_MS] later. The media is [DemoCallMedia]: an SDP `SdpGuard` accepts, ICE "connected" a moment after the
 * exchange, and the selected pair a neighbour's call reports, over its own Wi-Fi Direct group ([ROUTE], §3.10). No
 * sound flows, with two exceptions that are the point:
 *
 * - a live call's peer can [say] a line: its PCM goes, paced as it would play, into the captions' tap
 *   ([CallCaptioner.source]), so the captions on screen are the phone's own model hearing a voice (C-3.7-5);
 * - in a walkie session the peer's line is a burst: encoded by a [TalkRecorder] reading the file in place of a
 *   microphone, chunked as `WalkieTalk` chunks it, and handed to [CallManager.onTalk], so the screen's player plays it
 *   and its transcript writes it out (C-3.11-9). The screen's own bursts ([talkSend]) reach the peer, which reports
 *   each one back (C-3.11-10) as a phone that heard it would; [talk] speaks one of them from a line.
 *
 * The lines are synthetic voices under `demo/voice/<theme>/<line>.pcm` (`.private/scripts/make-demo-voices.py`). A peer
 * the screen rings answers after [answerAfterMs]; null leaves it ringing. Nothing here is compiled into release.
 */
object DemoCalls {
    private const val TAG = "DemoCalls"

    /** One hop of the loopback "air": long enough that Calling, Ringing and Connecting each show for a beat. */
    const val HOP_MS = 220L

    /** From the SDP exchange to ICE "connected". */
    private const val ICE_MS = 650L

    /** The sealed `talk` frame's envelope over a chunk, for the frame size the diagnostics read. */
    private const val SEAL_BYTES = 60

    /** A frame of the voice fed to the captions: 20 ms at the walkie's 16 kHz, 16-bit mono. */
    private const val FRAME_MS = 20L
    private const val FRAME_BYTES = WalkiePolicy.SAMPLE_RATE / 50 * 2
    private const val PCM_BYTES_PER_MS = WalkiePolicy.SAMPLE_RATE * 2 / 1000

    /** What [DemoCallMedia] reports as the selected pair: host candidates over the call's own Wi-Fi Direct group. */
    const val ROUTE = "host/host/wifi-direct"

    // A minimal audio description `SdpGuard` takes: an Opus m-line keyed by a DTLS fingerprint.
    private val SDP =
        listOf(
            "v=0",
            "o=- 4611731400430051336 2 IN IP4 127.0.0.1",
            "s=-",
            "t=0 0",
            "a=group:BUNDLE 0",
            "m=audio 9 UDP/TLS/RTP/SAVPF 111",
            "c=IN IP4 0.0.0.0",
            "a=mid:0",
            "a=setup:actpass",
            "a=fingerprint:sha-256 " + (1..32).joinToString(":") { "%02X".format(it * 7) },
            "a=rtpmap:111 opus/48000/2",
        ).joinToString("\r\n", postfix = "\r\n")

    @Volatile
    private var koin: Koin? = null

    /** How long a peer the screen rings waits before it answers; null leaves it ringing (the Ringing still). */
    @Volatile
    var answerAfterMs: Long? = 2_400L

    /** Hands the demo its graph; called once, at process start, before anything can place a call. */
    fun attach(koin: Koin) {
        this.koin = koin
    }

    private val graph: Koin get() = checkNotNull(koin) { "DemoCalls used before attach" }
    private val scope: CoroutineScope get() = graph.get()
    private val screen: CallManager get() = graph.get()

    private val peers = ConcurrentHashMap<String, Peer>()

    // Every frame both ways, in the order it was sent, one hop late.
    private val wire = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val wireUp = AtomicBoolean(false)

    private fun post(frame: suspend () -> Unit) {
        if (wireUp.compareAndSet(false, true)) {
            scope.launch {
                for (next in wire) {
                    delay(HOP_MS)
                    runCatching { next() }.onFailure { Log.w(TAG, "a demo frame failed", it) }
                }
            }
        }
        wire.trySend(frame)
    }

    /** The screen's [CallSignals]: each frame goes to the peer it names, which is stood up the first time. */
    val signals =
        CallSignals { peerId, payload ->
            post {
                val me = graph.get<Identity>().nodeId()
                peer(peerId).manager.onSignal(me, payload, System.currentTimeMillis())
            }
            true
        }

    /** The screen's media: no audio, ICE that connects, and the captions fed by the peer's [say]. */
    val media = DemoCallMedia(captions = { koin?.getOrNull<CallCaptioner>() })

    /** The screen's walkie send (`WalkieTalk`'s `send`): the chunk reaches the peer, and the "frame" it rode is sized. */
    val talkSend: suspend (String, ByteArray, Boolean, Boolean) -> Int? = { peerId, chunk, _, _ ->
        peer(peerId).hear(chunk)
        chunk.size + SEAL_BYTES
    }

    /** The peer [peerId] dials the screen: a live call, or a walkie session. */
    suspend fun ring(
        peerId: String,
        walkie: Boolean = false,
    ) {
        val me = graph.get<Identity>().nodeId()
        val refusal = peer(peerId).manager.start(me, if (walkie) CallKind.WALKIE else CallKind.LIVE)
        if (refusal != null) Log.w(TAG, "peer $peerId could not ring: $refusal")
    }

    /** The peer in the screen's call hangs up. */
    suspend fun peerHangsUp() {
        val call = screen.state.value?.takeIf { it.phase != CallPhase.Ended } ?: return
        peers[call.peerId]?.manager?.hangUp()
    }

    /**
     * The peer in the screen's connected call says [line]: into the captions of a live call, or as a burst in a walkie
     * session. Returns once it has been said; false when no call is up to say it in.
     */
    suspend fun say(line: String): Boolean {
        val call = screen.state.value?.takeIf { it.phase == CallPhase.Connected } ?: return false
        val pcm = voice(line) ?: return false
        return if (call.walkie) {
            peer(call.peerId).talk(pcm, call)
            true
        } else {
            media.speak(pcm)
        }
    }

    /** The screen's user holds the talk button for [line], said through the recorder in place of the microphone. */
    suspend fun talk(line: String): Boolean {
        val walkie = graph.getOrNull<WalkieTalk>() ?: return false
        val pcm = voice(line) ?: return false
        graph.get<TalkRecorder>().nextFrom = pcm
        if (!walkie.press()) return false
        delay(pcm.length() / PCM_BYTES_PER_MS + 400)
        walkie.release()
        return true
    }

    /**
     * The next burst this phone records says [line], read in place of the microphone however long the talk button is
     * held — for a film of a real thumb on the button (the Play declaration take). False when the line is not bundled.
     */
    suspend fun micSays(line: String): Boolean {
        val pcm = voice(line) ?: return false
        graph.get<TalkRecorder>().nextFrom = pcm
        return true
    }

    /** Turns a live call's captions on or off, as the call screen's CC button does (remembered from call to call). */
    suspend fun captions(on: Boolean) {
        graph.getOrNull<TranscriptStore>()?.setLiveCaptions(on)
    }

    /** Waits until the screen's call reaches [phase] (or [timeoutMs] runs out); the state it reached, or null. */
    suspend fun await(
        phase: CallPhase,
        timeoutMs: Long = 15_000L,
    ): CallState? = withTimeoutOrNull(timeoutMs) { screen.state.first { it?.phase == phase } }

    /** The bundled line [name] as a file the recorder and the captions can read, or null when it is not bundled. */
    private suspend fun voice(name: String): File? =
        withContext(Dispatchers.IO) {
            runCatching {
                val context = graph.get<Context>()
                val out = File(File(context.cacheDir, "demo-voice").apply { mkdirs() }, "${BuildConfig.DEMO_THEME}-$name.pcm")
                if (!out.exists()) {
                    context.assets.open("demo/voice/${BuildConfig.DEMO_THEME}/$name.pcm").use { input ->
                        out.outputStream().use { input.copyTo(it) }
                    }
                }
                out
            }.onFailure { Log.w(TAG, "no demo line '$name'", it) }.getOrNull()
        }

    private fun peer(id: String): Peer = peers.getOrPut(id) { Peer(id).also { it.start() } }

    /** A scripted peer: a headless [CallManager] under [id], answering the screen's rings by itself. */
    private class Peer(
        val id: String,
    ) {
        val manager =
            CallManager(
                signals =
                    { _, payload ->
                        post { screen.onSignal(id, payload, System.currentTimeMillis()) }
                        true
                    },
                turn = TurnSource.None,
                media = DemoCallMedia(captions = { null }),
                scope = scope,
                selfId = { id },
                log = { Log.i(TAG, "peer $id: $it") },
                walkie = PeerWalkie,
            )

        // The peer's own next burst, and the chunks it heard of each of the screen's.
        private var bursts = 0
        private val heard = ConcurrentHashMap<Int, Int>()

        fun start() {
            scope.launch collector@{
                manager.state.distinctUntilChangedBy { it?.id to it?.phase }.collect { state ->
                    if (state?.phase != CallPhase.Alerting) return@collect
                    val after = answerAfterMs ?: return@collect
                    this@collector.launch {
                        delay(after)
                        manager.answer(state.id)
                    }
                }
            }
        }

        /** One of the screen's chunks, heard; its burst's last one is reported back as a phone that heard it would. */
        fun hear(bytes: ByteArray) {
            val (tag, chunk) = TalkWire.decode(bytes) ?: return
            val count = heard.merge(chunk.b, 1, Int::plus) ?: 1
            if (!chunk.e) return
            heard.remove(chunk.b)
            post { screen.onTalk(id, TalkWire.encodeReport(tag, TalkReport(b = chunk.b, radio = count, relay = 0))) }
        }

        /** Says [pcm] as one burst into the screen's walkie session [call], as it is "spoken". */
        suspend fun talk(
            pcm: File,
            call: CallState,
        ) {
            val tag = TalkWire.tagOf(call.id)
            val b = bursts++
            val packets = Channel<ByteArray>(Channel.UNLIMITED)
            val recorder = TalkRecorder(scope).also { it.nextFrom = pcm }
            if (!recorder.start(headset = false) { packets.trySend(it) }) {
                Log.w(TAG, "peer $id: the encoder would not open")
                return
            }
            val sender =
                scope.launch {
                    val chunker = TalkChunker()
                    var q = 0

                    suspend fun out(
                        audio: ByteArray,
                        end: Boolean,
                    ) {
                        val bytes = TalkWire.encode(tag, TalkChunk(b = b, q = q++, a = audio, e = end))
                        screen.onTalk(id, bytes, sentAt = System.currentTimeMillis(), frameBytes = bytes.size + SEAL_BYTES)
                    }
                    for (packet in packets) chunker.add(packet)?.let { out(it, end = false) }
                    out(chunker.flush(), end = true)
                }
            delay(pcm.length() / PCM_BYTES_PER_MS + 300)
            recorder.stop()
            packets.close()
            sender.join()
        }
    }

    /** A peer runs the walkie and takes every kind of call. */
    private object PeerWalkie : CallWalkie {
        override val enabled = true

        override suspend fun capable(peerId: String) = true

        override suspend fun liveLikely(peerId: String) = true
    }

    /**
     * Media that moves no audio: the offer and answer `SdpGuard` takes, ICE "connected" [ICE_MS] after the exchange, and
     * [ROUTE] for the pair. [captions] is the screen's captioner: begun when the first session opens and ended when the
     * last closes, as `WebRtcCallMedia` does, and fed from "connected" on as libwebrtc's playout feeds it — a frame every
     * [FRAME_MS], silence between the lines [speak] queues, so the model hears each line end and closes its run.
     */
    class DemoCallMedia(
        private val captions: () -> CallCaptioner?,
    ) : CallMedia {
        private val open = CopyOnWriteArraySet<Session>()

        override fun open(
            servers: List<TurnServer>,
            relayOnly: Boolean,
            direct: Boolean,
            events: (MediaEvent) -> Unit,
        ): CallSession {
            if (open.isEmpty()) captions()?.begin()
            return Session(events).also(open::add)
        }

        /** Plays [pcm] as the peer's voice into the captions, returning once it has played; false with no session up. */
        suspend fun speak(pcm: File): Boolean {
            val session = open.lastOrNull() ?: return false
            return session.play(withContext(Dispatchers.IO) { pcm.readBytes() })
        }

        inner class Session(
            private val events: (MediaEvent) -> Unit,
        ) : CallSession {
            @Volatile
            private var closed = false

            // What the peer is saying, a frame at a time, and when the last frame of each line has played.
            private val voice = Channel<ByteArray>(Channel.UNLIMITED)
            private val played = Channel<Unit>(Channel.UNLIMITED)

            @Volatile
            private var playout: Job? = null

            override suspend fun offer(): String = SDP

            override suspend fun answer(remoteOffer: String): String = SDP.also { connectSoon() }

            override suspend fun accept(remoteAnswer: String): Boolean = true.also { connectSoon() }

            override suspend fun restart(servers: List<TurnServer>): String = SDP

            override suspend fun reanswer(
                remoteOffer: String,
                servers: List<TurnServer>,
            ): String = SDP.also { connectSoon() }

            override fun addCandidates(lines: List<String>) = Unit

            override fun setMuted(muted: Boolean) = Unit

            override suspend fun route(): String = ROUTE

            override fun close() {
                if (closed) return
                closed = true
                playout?.cancel()
                voice.close()
                played.close()
                open.remove(this)
                if (open.isEmpty()) captions()?.end()
            }

            /** Queues [bytes] for the playout and waits until it has played; false when the call is over first. */
            suspend fun play(bytes: ByteArray): Boolean {
                if (playout == null) return false
                var at = 0
                while (at < bytes.size) {
                    val n = minOf(FRAME_BYTES, bytes.size - at)
                    voice.trySend(bytes.copyOfRange(at, at + n))
                    at += n
                }
                // An empty frame marks the line's end for the playout to report.
                voice.trySend(ByteArray(0))
                return played.receiveCatching().isSuccess
            }

            private fun connectSoon() {
                scope.launch {
                    delay(ICE_MS)
                    if (closed) return@launch
                    events(MediaEvent.Connected)
                    startPlayout()
                }
            }

            // The peer's audio as it plays, from "connected" until the session closes: a line's frames while one is
            // queued, silence otherwise. Only the screen's media has captions to feed.
            private fun startPlayout() {
                val tap = captions()?.source() ?: return
                val silence = ByteArray(FRAME_BYTES)
                playout =
                    scope.launch(Dispatchers.Default) {
                        var next = System.nanoTime()
                        while (!closed) {
                            val frame = voice.tryReceive().getOrNull() ?: silence
                            if (frame.isEmpty()) {
                                played.trySend(Unit)
                                continue
                            }
                            tap.pcm(ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN), WalkiePolicy.SAMPLE_RATE, 1)
                            next += FRAME_MS * 1_000_000L
                            delay(((next - System.nanoTime()) / 1_000_000L).coerceAtLeast(0L))
                        }
                    }
            }
        }
    }
}
