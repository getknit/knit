package app.getknit.knit.call

import app.getknit.knit.mesh.protocol.CallPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** A tone this phone plays itself: the system plays none for a self-managed call (§3.13, C-3.13-14). */
enum class CallTone {
    // The callee's phone is ringing: its RINGING came back. Plays until the call moves on.
    RINGBACK,

    // The callee declined, or was busy, before the call connected.
    BUSY,

    // The call never connected and nothing the callee did ended it: no route, the media, an error.
    CONGESTION,

    // A connected call the peer or the path ended.
    ENDED,

    // A walkie burst this phone spoke ran to its limit with the button still held (TalkCue.CAPPED): heard by the
    // speaker alone, never sent.
    TALK_CAPPED,
}

/** Plays one [CallTone] at a time: a new one, or [stop], cuts the last. A one-shot tone stops by itself. */
interface TonePlayer {
    fun play(tone: CallTone)

    fun stop()
}

/** Which [CallTone] a call's step calls for (C-3.13-14). */
object CallTonePolicy {
    /**
     * The tone [state] calls for on its way from [previous]; null for silence. Ringback is the caller's while the
     * callee rings — never while it only dials, since a call with no RINGING has no evidence the other phone alerts.
     * An end's tone is heard once, on the step into [CallPhase.Ended]. A walkie plays none: it is heard aloud.
     */
    fun toneFor(
        previous: CallState?,
        state: CallState?,
    ): CallTone? {
        if (state == null || state.walkie) return null
        if (state.phase == CallPhase.Ringing) return if (state.outgoing) CallTone.RINGBACK else null
        if (state.phase != CallPhase.Ended || previous == null || previous.phase == CallPhase.Ended) return null
        return endTone(state)
    }

    // The tone an [ended] call is heard to end with.
    private fun endTone(ended: CallState): CallTone? {
        if (ended.connectedAt != null) return if (ended.endedByPeer || ended.reason in LOST) CallTone.ENDED else null
        if (!ended.outgoing) return null
        return when {
            ended.reason == CallPayload.REASON_BUSY -> CallTone.BUSY
            ended.endedByPeer && ended.reason == CallPayload.REASON_USER -> CallTone.BUSY
            !ended.endedByPeer && ended.reason in LOST -> CallTone.CONGESTION
            else -> null
        }
    }

    // What ends a call that nobody hung up.
    private val LOST = setOf(CallPayload.REASON_NO_ROUTE, CallPayload.REASON_MEDIA, CallPayload.REASON_ERROR)
}

/**
 * Plays the caller's tones (C-3.13-14) as [CallManager.state] moves, on [scope] as the other call surfaces do, never on
 * the inbound path. The tones go out on the voice-call stream, so they follow the route Telecom picked; nothing here
 * sets one (C-3.13-7).
 */
class CallToneSync(
    private val calls: CallControl,
    private val player: TonePlayer,
    private val scope: CoroutineScope,
) {
    private var last: CallState? = null
    private var playing: CallTone? = null

    fun start() {
        scope.launch { calls.state.collect { step(it) } }
    }

    internal fun step(state: CallState?) {
        val previous = last
        last = state
        // A settled end leaves its tone to finish.
        if (state?.phase == CallPhase.Ended && previous?.phase == CallPhase.Ended && previous.id == state.id) return
        val tone = CallTonePolicy.toneFor(previous, state)
        if (tone == playing) return
        playing = tone
        if (tone == null) player.stop() else player.play(tone)
    }
}
