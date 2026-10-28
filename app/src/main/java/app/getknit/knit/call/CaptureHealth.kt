package app.getknit.knit.call

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.coroutines.cancellation.CancellationException

/**
 * Which microphone path a live call records from (docs/VOICE_CALLS.md C-3.13-12, ADR 2026-10.kw29). A walkie records
 * through its own recorder (C-3.13-7), whose phone microphone picks its source by this too (`TalkCapture`, C-3.11-5).
 */
enum class CaptureProfile {
    /** `VOICE_COMMUNICATION` with the platform's echo canceller and noise suppressor: the call-tuned path. */
    STANDARD,

    /**
     * `VOICE_RECOGNITION` with the platform's effects off, so WebRTC runs its own echo canceller, noise suppressor and
     * gain. It skips the vendor's VoIP record route, which a generic system image over an older vendor lacks. A
     * Bluetooth headset's microphone may not follow it; the phone's own does.
     */
    FALLBACK,
}

/** Why a phone's calls record from where they do. */
enum class CaptureReason {
    /** A generic system image over an older vendor (`device/SystemImage`): known before the first call. */
    GENERIC_IMAGE,

    /** A call on [CaptureProfile.STANDARD] captured nothing but zeros, and switched (C-3.13-13). */
    SILENT_CALL,

    /** Set by hand (the debug bridge), for a trial. */
    CHOSEN,
}

/** The profile calls open on, and why; [reason] is null for the default. */
data class CaptureChoice(
    val profile: CaptureProfile,
    val reason: CaptureReason?,
)

/**
 * What survives a process about this phone's call microphone: a verdict under [stamp] (the app version + OS build, so
 * an update tries the standard path again), or [NONE].
 */
data class CaptureRecord(
    val stamp: String,
    val profile: CaptureProfile,
    val reason: CaptureReason?,
    val at: Long,
) {
    companion object {
        val NONE = CaptureRecord(stamp = "", profile = CaptureProfile.STANDARD, reason = null, at = 0L)
    }
}

/** The store `data/settings/CaptureStore` keeps [CaptureRecord] in. */
interface CaptureJournal {
    suspend fun read(): CaptureRecord

    suspend fun write(record: CaptureRecord)
}

/** Which profile a call opens on (C-3.13-12). Pure. */
object CapturePolicy {
    fun choose(
        record: CaptureRecord,
        stamp: String,
        genericImage: Boolean,
    ): CaptureChoice =
        when {
            // A verdict this build and OS reached, learned or chosen, outranks the image: a trial can try STANDARD on a GSI.
            record.stamp == stamp && record.reason != null -> CaptureChoice(record.profile, record.reason)

            genericImage -> CaptureChoice(CaptureProfile.FALLBACK, CaptureReason.GENERIC_IMAGE)

            else -> CaptureChoice(CaptureProfile.STANDARD, null)
        }
}

/**
 * This phone's call-microphone verdict, held as a state the media layer reads synchronously as each call opens (its
 * audio module is built per profile). Fails open: an unreadable store reads as the image alone. Pure: the journal, the
 * stamp and the clock are handed in.
 */
class CaptureHealth(
    private val journal: CaptureJournal,
    private val stamp: String,
    private val genericImage: Boolean,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _choice = MutableStateFlow(CapturePolicy.choose(CaptureRecord.NONE, stamp, genericImage))

    /** The profile the next call opens on. The image's alone until [load] has read the store. */
    val choice: StateFlow<CaptureChoice> = _choice.asStateFlow()

    suspend fun load() {
        val record = tolerating { journal.read() } ?: return
        _choice.value = CapturePolicy.choose(record, stamp, genericImage)
    }

    /** A call on [CaptureProfile.STANDARD] captured only zeros: the next calls open on the fallback. */
    suspend fun learnedSilent() = set(CaptureProfile.FALLBACK, CaptureReason.SILENT_CALL)

    /** A trial's choice (the debug bridge). */
    suspend fun choose(profile: CaptureProfile) = set(profile, CaptureReason.CHOSEN)

    /** Forgets every verdict: the next call opens where the image says. */
    suspend fun reset() {
        tolerating { journal.write(CaptureRecord.NONE.copy(stamp = stamp)) }
        _choice.value = CapturePolicy.choose(CaptureRecord.NONE, stamp, genericImage)
    }

    private suspend fun set(
        profile: CaptureProfile,
        reason: CaptureReason,
    ) {
        val record = CaptureRecord(stamp, profile, reason, now())
        tolerating { journal.write(record) }
        _choice.value = CapturePolicy.choose(record, stamp, genericImage)
    }

    private suspend fun <T> tolerating(block: suspend () -> T): T? =
        try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            null
        }
}

/**
 * Watches one call's capture for a microphone that hands WebRTC nothing (C-3.13-13): a vendor route that will not
 * open is not an error the platform reports — the record starts, and the framework fills it with zeros. Fed each
 * buffer's loudest sample from the record thread; pure, and allocation-free per frame.
 *
 * A run of [deadAfterMs] of digital silence (no sample louder than [DIGITAL_SILENCE]) while the call is not muted, or
 * a record error, is a dead microphone, unless [Excuse] says otherwise when the run completes: a muted microphone
 * (Telecom's mute can zero the stream at the platform) or one the system silenced (a call with no foreground service,
 * C-3.13-10). A dead microphone on the standard path asks once to [Health.SWITCHED] to the fallback; dead there too,
 * it is [Health.DEAD]. A real microphone never reads exact zeros for seconds: its noise floor alone is louder.
 */
class CaptureWatch(
    // False when the call already records from the fallback: there is nowhere further to switch.
    private val canSwitch: Boolean,
    private val deadAfterMs: Long = DEAD_AFTER_MS,
) {
    enum class Health {
        WATCHING,
        LIVE,

        /** The system silenced the microphone: not this phone's fault, and nothing is learned from it. */
        SILENCED,

        /** The standard path was dead; the call now records from the fallback, watched afresh. */
        SWITCHED,

        /** No path this call can try hands WebRTC any sound. */
        DEAD,
    }

    enum class Excuse { MUTED, SILENCED }

    @Volatile
    var health: Health = Health.WATCHING
        private set

    /** Whether the call moved to the fallback during this call. */
    @Volatile
    var switched: Boolean = false
        private set

    private var silentMs = 0L

    /**
     * One buffer of [frameMs] whose loudest sample was [peak]. [muted] is the call's own mute; [excuse] is asked only
     * as a run completes (it may cost a binder call). Returns the new health when it changes, else null.
     */
    fun frame(
        peak: Int,
        frameMs: Int,
        muted: Boolean,
        excuse: () -> Excuse?,
    ): Health? {
        if (muted) return null
        if (peak > DIGITAL_SILENCE) {
            silentMs = 0L
            // Sound again after a verdict (a silenced call reopened, a route back): the path it came on is live.
            return moveTo(if (switched) Health.SWITCHED else Health.LIVE)
        }
        silentMs += frameMs
        if (silentMs < deadAfterMs) return null
        silentMs = 0L
        return dead(excuse)
    }

    /** The record failed outright (init, start or read): dead now, unless [excuse] says otherwise. */
    fun failed(excuse: () -> Excuse?): Health? = dead(excuse)

    private fun dead(excuse: () -> Excuse?): Health? =
        when (excuse()) {
            Excuse.MUTED -> {
                null
            }

            Excuse.SILENCED -> {
                moveTo(Health.SILENCED)
            }

            null -> {
                if (canSwitch && !switched) {
                    switched = true
                    moveTo(Health.SWITCHED)
                } else {
                    moveTo(Health.DEAD)
                }
            }
        }

    private fun moveTo(next: Health): Health? {
        if (health == next) return null
        health = next
        return next
    }

    companion object {
        /** Three seconds: past any route change's gap, well short of a caller giving up. */
        const val DEAD_AFTER_MS = 3_000L

        /** A sample this loud or quieter is digital silence; a real microphone's floor is well above it. */
        const val DIGITAL_SILENCE = 1
    }
}
