package app.getknit.knit.call

import app.getknit.knit.call.VoxGate.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** A hands-free walkie's gate (docs/VOICE_CALLS.md C-3.11-11): when a burst opens, is sent, ends and rolls over. */
class VoxGateTest {
    @Test
    fun aQuietRoomNeverOpens() {
        val gate = VoxGate()
        assertTrue(gate.run(QUIET, SECOND * 10).all { it == Action.None })
        assertEquals(VoxGate.State.SHUT, gate.state)
    }

    @Test
    fun speechOpensAfterItsAttackWithThePreRollBeforeIt() {
        val gate = VoxGate()
        gate.run(QUIET, SECOND)
        assertEquals(listOf(Action.None, Action.None), gate.run(SPEECH, ATTACK - 1))
        // The two attack frames before this one, and 300 ms before them.
        assertEquals(Action.Open(preRoll = ATTACK - 1 + PRE_ROLL), gate.step(SPEECH))
        assertEquals(VoxGate.State.OPEN, gate.state)
    }

    @Test
    fun aClickShorterThanTheAttackOpensNothing() {
        val gate = VoxGate()
        gate.run(QUIET, SECOND)
        repeat(SECOND) {
            assertEquals(Action.None, gate.step(SPEECH))
            assertEquals(Action.None, gate.step(QUIET))
        }
    }

    @Test
    fun theBurstIsSentOnlyOnceItHoldsEnoughSpeech() {
        val gate = VoxGate()
        gate.run(QUIET, SECOND)
        val actions = gate.run(SPEECH, COMMIT)
        assertEquals(Action.Open(ATTACK - 1 + PRE_ROLL), actions[ATTACK - 1])
        assertEquals(Action.Commit, actions.last())
        assertEquals(1, actions.count { it == Action.Commit })
        assertEquals(VoxGate.State.SENDING, gate.state)
    }

    @Test
    fun aCoughShorterThanTheCommitIsDroppedUnsent() {
        val gate = VoxGate()
        gate.run(QUIET, SECOND)
        val actions = gate.run(SPEECH, COMMIT - 2) + gate.run(QUIET, HANG)
        assertEquals(0, actions.count { it == Action.Commit })
        assertEquals(Action.Close(kept = false), actions.last())
        assertEquals(VoxGate.State.SHUT, gate.state)
    }

    @Test
    fun aSentBurstEndsAfterItsHangAndAPauseShorterKeepsItOpen() {
        val gate = VoxGate()
        gate.run(QUIET, SECOND)
        gate.run(SPEECH, SECOND)
        assertTrue(gate.run(QUIET, HANG - 1).all { it == Action.None })
        gate.run(SPEECH, 5)
        assertEquals(VoxGate.State.SENDING, gate.state)
        val end = gate.run(QUIET, HANG)
        assertTrue(end.dropLast(1).all { it == Action.None })
        assertEquals(Action.Close(kept = true), end.last())
    }

    @Test
    fun theNextBurstsPreRollStartsAfterTheLastOneEnded() {
        val gate = VoxGate()
        gate.run(QUIET, SECOND)
        gate.run(SPEECH, SECOND)
        gate.run(QUIET, HANG)
        // Two quiet frames since it shut, then the attack: nothing the last burst sent opens this one.
        gate.run(QUIET, 2)
        assertEquals(Action.Open(preRoll = 2 + ATTACK - 1), gate.run(SPEECH, ATTACK).last())
    }

    @Test
    fun aLongTurnRollsIntoTheNextBurstAtTheLimit() {
        val gate = VoxGate(maxBurstMs = 1_000)
        gate.run(QUIET, SECOND)
        val actions = gate.talk(140)
        val opened = actions.indexOfFirst { it is Action.Open }
        val rolls = actions.indices.filter { actions[it] == Action.Roll }
        // Opened with 17 frames of pre-roll: past 1 s at its 34th frame, then at every roll's 51st.
        assertEquals(listOf(opened + 33, opened + 83, opened + 133), rolls)
        assertEquals(VoxGate.State.SENDING, gate.state)
        assertTrue(actions.none { it is Action.Close })
    }

    @Test
    fun thePeersVoiceNeverOpensTheGateNorTeachesTheFloor() {
        val gate = VoxGate()
        gate.run(QUIET, SECOND)
        // Three seconds of the peer's burst on the loudspeaker: learned, it would have made itself the floor.
        assertTrue(gate.run(SPEECH, SECOND * 3, hearing = true).all { it == Action.None })
        assertEquals(VoxGate.State.SHUT, gate.state)
        assertTrue(gate.run(SPEECH, ATTACK).last() is Action.Open)
    }

    @Test
    fun thePreRollReachesBackOnlyToThePeersVoice() {
        val gate = VoxGate()
        gate.run(QUIET, SECOND)
        gate.run(SPEECH, 10, hearing = true)
        gate.run(QUIET, 4)
        assertEquals(Action.Open(preRoll = 4 + ATTACK - 1), gate.run(SPEECH, ATTACK).last())
    }

    @Test
    fun anUnsentBurstGivesWayToThePeersVoice() {
        val gate = VoxGate()
        gate.run(QUIET, SECOND)
        gate.run(SPEECH, ATTACK)
        assertEquals(Action.Close(kept = false), gate.step(SPEECH, hearing = true))
    }

    @Test
    fun aSentBurstHoldsThePlayerSoTheMicrophoneIsStillTheUsers() {
        val gate = VoxGate()
        gate.run(QUIET, SECOND)
        gate.run(SPEECH, SECOND)
        // Counted as silence without learning: the hang ends it as ever.
        val end = gate.run(SPEECH, HANG, hearing = true)
        assertEquals(Action.Close(kept = true), end.last())
    }

    @Test
    fun mutedItNeverOpensAndEndsWhatItWasSending() {
        val gate = VoxGate()
        gate.run(QUIET, SECOND)
        assertTrue(gate.run(SPEECH, SECOND, muted = true).all { it == Action.None })
        gate.run(QUIET, SECOND)
        gate.run(SPEECH, SECOND)
        assertEquals(Action.Close(kept = true), gate.step(SPEECH, muted = true))
        gate.run(QUIET, SECOND)
        gate.run(SPEECH, ATTACK)
        assertEquals(Action.Close(kept = false), gate.step(SPEECH, muted = true))
    }

    // What was said while muted is never a later burst's pre-roll, however soon the voice comes after the unmute.
    @Test
    fun nothingSaidWhileMutedRidesTheNextBurst() {
        val gate = VoxGate()
        gate.run(QUIET, SECOND)
        gate.run(SPEECH, SECOND, muted = true)
        assertEquals(Action.Open(preRoll = ATTACK - 1), gate.run(SPEECH, ATTACK).last())
    }

    @Test
    fun aSteadyHumBecomesTheFloorAndSpeechOverItStillOpens() {
        val gate = VoxGate()
        assertTrue(gate.run(HUM, SECOND * 5).all { it == Action.None })
        // 20 dB over the hum, past Medium's 12.
        assertTrue(gate.run(SPEECH, ATTACK).last() is Action.Open)
    }

    @Test
    fun aHumThatStartsUpIsHeardAsSpeechOnlyUntilItIsTheFloor() {
        val gate = VoxGate()
        gate.run(QUIET, SECOND * 2)
        val actions = gate.run(HUM, SECOND * 5)
        assertEquals(Action.Close(kept = true), actions.last { it is Action.Close })
        assertEquals(VoxGate.State.SHUT, gate.state)
    }

    // A rustle's one quiet frame is not the room's floor: over it, the room itself read as speech (the first trial).
    @Test
    fun aDipInAnUnevenNoiseDoesNotBecomeTheFloor() {
        val gate = VoxGate()
        val noise = listOf(-42.0, -44.0, -40.0, -43.0, -41.0)
        val actions = List(SECOND * 10) { gate.step(if (it % 25 == 24) -75.0 else noise[it % noise.size]) }
        assertTrue(actions.all { it == Action.None })
        assertEquals(VoxGate.State.SHUT, gate.state)
    }

    // Learning from the room, the gain climbed on its noise all session (the first trial).
    @Test
    fun theGainLearnsOnlyFromWhatIsSentOrPressed() {
        val gate = VoxGate()
        gate.run(QUIET, SECOND)
        assertFalse(gate.steersGain)
        gate.run(SPEECH, ATTACK)
        assertEquals(VoxGate.State.OPEN, gate.state)
        assertFalse(gate.steersGain)
        gate.run(SPEECH, COMMIT)
        assertEquals(VoxGate.State.SENDING, gate.state)
        assertTrue(gate.steersGain)
        gate.run(QUIET, HANG)
        assertFalse(gate.steersGain)
        gate.hold()
        assertTrue(gate.steersGain)
        gate.release()
        assertFalse(gate.steersGain)
    }

    // A talker who pauses on just over a tenth of the frames still sets the floor: the burst never ends mid-turn.
    @Test
    fun aTurnWithFewPausesStaysOpen() {
        val gate = VoxGate()
        gate.run(QUIET, SECOND)
        val actions = List(SECOND * 10) { gate.step(if (it % 8 == 7) QUIET else SPEECH) }
        assertTrue(actions.none { it is Action.Close || it == Action.Roll })
        assertEquals(VoxGate.State.SENDING, gate.state)
    }

    // The first trial: the gate judged the level after a gain that learned from the whole room, so the room's noise
    // lifted the gain all session and the gate opened on the room. Through TalkGain.take, neither happens.
    @Test
    fun theRoomNeitherLiftsTheGainNorOpensTheGateThroughIt() {
        val gain = TalkGain()
        val gate = VoxGate()
        val noise = listOf(-52.0, -50.0, -54.0, -51.0, -53.0)
        val room = { i: Int -> if (i % 20 == 19) -80.0 else noise[i % noise.size] }
        val feed = { db: Double -> gate.through(gain, db) }
        repeat(SECOND * 2) { feed(room(it)) }
        val said = List(SECOND) { feed(if (it % 5 == 0) room(it) else -25.0) }
        assertTrue(said.any { it == Action.Commit })
        val after = List(SECOND * 2) { feed(room(it)) }
        assertEquals(Action.Close(kept = true), after.last { it is Action.Close })
        val settled = gain.gainDb
        val late = List(SECOND * 30) { feed(room(it)) }
        assertTrue(late.all { it == Action.None })
        assertEquals(settled, gain.gainDb, 0.0)
    }

    // Under Medium's least level as the microphone gives it, over it after the gain's 12 dB start: it stays shut.
    @Test
    fun theGateJudgesTheLevelBeforeTheGain() {
        val gain = TalkGain()
        val gate = VoxGate()
        repeat(SECOND) { gate.through(gain, QUIET) }
        assertTrue(List(SECOND) { gate.through(gain, -50.0) }.all { it == Action.None })
        assertEquals(VoxGate.State.SHUT, gate.state)
    }

    @Test
    fun sensitivityMovesTheMarginOverTheFloor() {
        // 10 dB over a hum: High's 8 dB margin opens on it, Medium's 12 does not.
        assertTrue(opens(VoxSensitivity.HIGH, floorDb = HUM, voiceDb = HUM + 10))
        assertTrue(!opens(VoxSensitivity.MEDIUM, floorDb = HUM, voiceDb = HUM + 10))
        // A quiet voice in a quiet room: over Medium's least level, under Low's.
        assertTrue(opens(VoxSensitivity.MEDIUM, floorDb = QUIET, voiceDb = -45.0))
        assertTrue(!opens(VoxSensitivity.LOW, floorDb = QUIET, voiceDb = -45.0))
    }

    @Test
    fun aPressHoldsTheGateAndItsReleaseStartsAfresh() {
        val gate = VoxGate()
        gate.run(QUIET, SECOND)
        gate.hold()
        assertTrue(gate.talk(SECOND * 2).all { it == Action.None })
        assertEquals(VoxGate.State.HELD, gate.state)
        gate.release()
        // What the press said is no pre-roll: only the attack's own frames open the next.
        assertEquals(Action.Open(preRoll = ATTACK - 1), gate.run(SPEECH, ATTACK).last())
    }

    private fun opens(
        sensitivity: VoxSensitivity,
        floorDb: Double,
        voiceDb: Double,
    ): Boolean {
        val gate = VoxGate().also { it.sensitivity = sensitivity }
        gate.run(floorDb, SECOND * 3)
        return gate.run(voiceDb, ATTACK).last() is Action.Open
    }

    // [frames] of speech, every fifth frame a pause between words, which keeps the floor the room's.
    private fun VoxGate.talk(frames: Int): List<Action> = List(frames) { step(if (it % 5 == 0) QUIET else SPEECH) }

    private fun VoxGate.step(
        db: Double,
        hearing: Boolean = false,
        muted: Boolean = false,
    ): Action = frame(db, hearing, muted)

    private fun VoxGate.run(
        db: Double,
        frames: Int,
        hearing: Boolean = false,
        muted: Boolean = false,
    ): List<Action> = List(frames) { step(db, hearing, muted) }

    // A frame at [db] through the gain as the armed microphone's are, then the gate.
    private fun VoxGate.through(
        gain: TalkGain,
        db: Double,
    ): Action = frame(gain.take(pcmAt(db), FRAME_BYTES, this, hearing = false), hearing = false, muted = false)

    // One 20 ms frame of a 16 kHz sine at [db] dBFS RMS.
    private fun pcmAt(db: Double): ByteArray {
        val amplitude = 10.0.pow(db / 20.0) * sqrt(2.0) * 32_768.0
        val pcm = ByteArray(FRAME_BYTES)
        for (i in 0 until FRAME_BYTES / 2) {
            val s = (amplitude * sin(2 * PI * 440.0 * i / 16_000.0)).toInt()
            pcm[i * 2] = s.toByte()
            pcm[i * 2 + 1] = (s shr 8).toByte()
        }
        return pcm
    }

    private companion object {
        const val FRAME_BYTES = 640
        const val QUIET = -70.0
        const val HUM = -40.0
        const val SPEECH = -20.0
        const val SECOND = 50
        val ATTACK = (VoxPolicy.ATTACK_MS / VoxPolicy.FRAME_MS).toInt()
        val PRE_ROLL = (VoxPolicy.PRE_ROLL_MS / VoxPolicy.FRAME_MS).toInt()
        val COMMIT = (VoxPolicy.COMMIT_SPEECH_MS / VoxPolicy.FRAME_MS).toInt()
        val HANG = (VoxPolicy.HANG_MS / VoxPolicy.FRAME_MS).toInt()
    }
}
