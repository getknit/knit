package app.getknit.knit.call

import kotlin.math.log10
import kotlin.math.min
import kotlin.math.sqrt

/**
 * How readily a hands-free walkie opens a burst on a voice (docs/VOICE_CALLS.md C-3.11-11): a frame is speech when it
 * is [marginDb] over the noise floor and at least [minDb], both on the level after `TalkGain`, which brings speech
 * toward −18 dBFS on every microphone path. The Calls screen's "Hands-free sensitivity"; [MEDIUM] by default.
 */
enum class VoxSensitivity(
    val marginDb: Double,
    val minDb: Double,
) {
    LOW(marginDb = 18.0, minDb = -40.0),
    MEDIUM(marginDb = 12.0, minDb = -48.0),
    HIGH(marginDb = 8.0, minDb = -55.0),
    ;

    companion object {
        val DEFAULT = MEDIUM

        /** The sensitivity stored as [name], or [DEFAULT] for none or one this build does not know. */
        fun of(name: String?): VoxSensitivity = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/** A hands-free walkie's numbers (C-3.11-11), tuned on the phones. */
object VoxPolicy {
    /** One frame of the capture: an Opus packet's 20 ms. */
    const val FRAME_MS = 20L

    /** Speech this long, unbroken, opens a burst: a click or a knock is shorter. */
    const val ATTACK_MS = 60L

    /** Audio from before the voice crossed the line that a burst opens with, so its first syllable is whole. */
    const val PRE_ROLL_MS = 300L

    /** Speech a burst must hold before anything of it is sent: a cough or a door is dropped unsent. */
    const val COMMIT_SPEECH_MS = 200L

    /** Not speech for this long ends a burst: a breath between sentences does not. */
    const val HANG_MS = 800L

    /** After the peer's burst plays out, the gate stays shut this long: the loudspeaker's tail is not the user's voice. */
    const val ECHO_GUARD_MS = 500L

    /** Frames of audio the capture keeps for a burst to open with: the pre-roll and the attack before it. */
    const val RING_FRAMES = ((PRE_ROLL_MS + ATTACK_MS) / FRAME_MS).toInt()
}

/**
 * The noise floor under a microphone and whether a frame stands out of it as speech: the quietest frame of the last
 * [frames] (2 s of 20 ms frames: longer than the gap between words, shorter than a room's change), so a steady hum
 * becomes the floor. Shared by `TalkGain`, which steers only on speech, and [VoxGate], which opens on it. Pure; not
 * thread-safe.
 */
class SpeechFloor(
    private val frames: Int = FRAMES,
) {
    private val recent = DoubleArray(frames)
    private var filled = 0
    private var next = 0

    /** Takes [rmsDb] into the floor; whether it is speech against the floor it makes: [minDb] or more, [marginDb] over. */
    fun take(
        rmsDb: Double,
        marginDb: Double,
        minDb: Double,
    ): Boolean {
        recent[next] = rmsDb
        next = (next + 1) % frames
        filled = min(filled + 1, frames)
        return isSpeech(rmsDb, marginDb, minDb)
    }

    /** Whether [rmsDb] is speech against the floor as it stands, without taking it in. */
    fun isSpeech(
        rmsDb: Double,
        marginDb: Double,
        minDb: Double,
    ): Boolean {
        var floorDb = rmsDb
        for (i in 0 until filled) floorDb = min(floorDb, recent[i])
        return rmsDb >= minDb && rmsDb >= floorDb + marginDb
    }

    companion object {
        const val FRAMES = 100
        private const val FULL_SCALE = 32_768.0
        private const val BYTES = 2
        private const val BYTE = 0xFF
        private const val BYTE_BITS = 8
        private const val DB_PER_DECADE = 20.0
        private const val SILENT_DB = -120.0

        /** The RMS of the [size] bytes of 16-bit little-endian mono [pcm], in dBFS; [SILENT_DB] for silence. */
        fun rmsDb(
            pcm: ByteArray,
            size: Int,
        ): Double {
            val samples = size / BYTES
            if (samples == 0) return SILENT_DB
            var sumSquares = 0.0
            for (i in 0 until samples) {
                val s = ((pcm[i * BYTES].toInt() and BYTE) or (pcm[i * BYTES + 1].toInt() shl BYTE_BITS)).toShort().toDouble()
                sumSquares += s * s
            }
            return toDb(sqrt(sumSquares / samples) / FULL_SCALE)
        }

        internal fun toDb(level: Double): Double = if (level <= 0.0) SILENT_DB else DB_PER_DECADE * log10(level)
    }
}

/**
 * A hands-free walkie's gate (docs/VOICE_CALLS.md C-3.11-11): fed every 20 ms frame the armed microphone hears, it says
 * when a burst opens, when it is speech enough to send, and when it ends. Pure and clocked by its frames
 * (`VoxGateTest`); not thread-safe: the capture loop owns it.
 *
 * - **Opening.** [VoxPolicy.ATTACK_MS] of unbroken speech opens a burst with up to [VoxPolicy.PRE_ROLL_MS] of the audio
 *   before it ([Action.Open.preRoll]), counted only back to the last frame the gate was shut for the peer's voice.
 * - **Sending.** Nothing of a burst is sent until it holds [VoxPolicy.COMMIT_SPEECH_MS] of speech ([Action.Commit]); one
 *   that ends before that is dropped ([Action.Close] with `kept = false`), at no cost to the mesh.
 * - **Ending.** [VoxPolicy.HANG_MS] without speech ends it; at [WalkiePolicy.MAX_BURST_MS] still speaking, it ends and
 *   the next opens on the same frame, already sent ([Action.Roll]), so a long turn loses nothing to the limit.
 * - **The peer's voice.** While the peer's burst plays ([frame]'s `hearing`), a shut gate stays shut and learns no
 *   floor, and an unsent burst is dropped. Muted, it never opens, and a burst it was sending ends.
 * - **A press.** While the button holds the microphone ([hold]), the gate stands aside; [release] shuts it afresh.
 */
class VoxGate(
    private val maxBurstMs: Long = WalkiePolicy.MAX_BURST_MS,
) {
    /** What the capture does with the frame just given. */
    sealed interface Action {
        data object None : Action

        /** A burst opens: encode the [preRoll] frames before this one, then this one. */
        data class Open(
            val preRoll: Int,
        ) : Action

        /** The open burst is speech: send it, and what it holds so far. */
        data object Commit : Action

        /** The burst ends before this frame: its end goes out when [kept], else it is dropped unsent. */
        data class Close(
            val kept: Boolean,
        ) : Action

        /** The burst reached its limit still speaking: it ends kept before this frame, and the next, sent, opens with it. */
        data object Roll : Action
    }

    enum class State { SHUT, OPEN, SENDING, HELD }

    /** Read at every frame, so the Calls screen's choice takes at once. */
    @Volatile
    var sensitivity: VoxSensitivity = VoxSensitivity.DEFAULT

    var state = State.SHUT
        private set

    private val floor = SpeechFloor()

    // While shut: the speech frames in a row so far, and the frames since the gate last shut or heard the peer.
    private var attack = 0
    private var clean = 0

    // While open: frames since it opened (or rolled), the audio it opened with, its speech and its trailing silence.
    private var frames = 0
    private var preRoll = 0
    private var speechMs = 0L
    private var silentMs = 0L

    /** Takes one frame at [rmsDb]; [hearing]: the peer's burst may be on the loudspeaker; [muted]: the call is muted. */
    fun frame(
        rmsDb: Double,
        hearing: Boolean,
        muted: Boolean,
    ): Action =
        when {
            state == State.HELD -> {
                if (!hearing) floor.take(rmsDb, sensitivity.marginDb, sensitivity.minDb)
                Action.None
            }

            muted -> {
                if (!hearing) floor.take(rmsDb, sensitivity.marginDb, sensitivity.minDb)
                if (state == State.SHUT) {
                    attack = 0
                    clean = 0
                    Action.None
                } else {
                    shut(kept = state == State.SENDING)
                }
            }

            hearing -> {
                heard()
            }

            else -> {
                spoken(floor.take(rmsDb, sensitivity.marginDb, sensitivity.minDb))
            }
        }

    /** A press takes the microphone: whatever burst the gate had is the press's now, or dropped by the caller. */
    fun hold() {
        state = State.HELD
        attack = 0
        clean = 0
    }

    /** The press let go: the gate is shut, and what the press said is no burst's pre-roll. */
    fun release() {
        state = State.SHUT
        attack = 0
        clean = 0
    }

    // The peer's voice may be in the frame: nothing opens on it, nothing learns from it, and an unsent burst goes.
    private fun heard(): Action =
        when (state) {
            State.SHUT -> {
                attack = 0
                clean = 0
                Action.None
            }

            State.OPEN -> {
                shut(kept = false)
            }

            // Sending holds the player, so the loudspeaker is quiet: the frame is the user's, counted without learning.
            else -> {
                step(speech = false)
            }
        }

    private fun spoken(speech: Boolean): Action {
        if (state != State.SHUT) return step(speech)
        clean++
        attack = if (speech) attack + 1 else 0
        if (attack * VoxPolicy.FRAME_MS < VoxPolicy.ATTACK_MS) return Action.None
        state = State.OPEN
        // The attack before this frame, and the pre-roll before the attack, where the gate saw that much clean.
        preRoll = min(clean - 1, (attack - 1) + PRE_ROLL_FRAMES)
        frames = 1
        speechMs = attack * VoxPolicy.FRAME_MS
        silentMs = 0L
        return Action.Open(preRoll)
    }

    private fun step(speech: Boolean): Action {
        frames++
        if (speech) {
            speechMs += VoxPolicy.FRAME_MS
            silentMs = 0L
        } else {
            silentMs += VoxPolicy.FRAME_MS
        }
        return when {
            silentMs >= VoxPolicy.HANG_MS -> {
                shut(kept = state == State.SENDING)
            }

            (preRoll + frames) * VoxPolicy.FRAME_MS > maxBurstMs -> {
                if (state == State.SENDING) {
                    // This frame opens the next burst.
                    frames = 1
                    preRoll = 0
                    Action.Roll
                } else {
                    shut(kept = false)
                }
            }

            state == State.OPEN && speechMs >= VoxPolicy.COMMIT_SPEECH_MS -> {
                state = State.SENDING
                Action.Commit
            }

            else -> {
                Action.None
            }
        }
    }

    // The next burst's pre-roll starts after this one: nothing it sent is sent again.
    private fun shut(kept: Boolean): Action {
        state = State.SHUT
        attack = 0
        clean = 0
        return Action.Close(kept)
    }

    private companion object {
        const val PRE_ROLL_FRAMES = (VoxPolicy.PRE_ROLL_MS / VoxPolicy.FRAME_MS).toInt()
    }
}
