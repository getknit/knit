@file:Suppress("MagicNumber") // the timeline is a hand-tuned list of millisecond beat delays; naming each would only add noise

package app.getknit.knit.demo

import android.content.Context
import android.content.Intent
import android.util.Log
import app.getknit.knit.BuildConfig
import app.getknit.knit.call.CallKind
import app.getknit.knit.call.CallManager
import app.getknit.knit.call.CallPhase
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.DeliveryPlane
import app.getknit.knit.di.CALLS_WIRED
import app.getknit.knit.identity.Identity
import app.getknit.knit.mesh.MeshManager
import app.getknit.knit.mesh.Peer
import app.getknit.knit.ui.call.CallActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.Koin

/**
 * Debug-only trailer director (`-PdemoDirector=true`). Plays a scripted, animated [DemoScene] so a screen recording
 * becomes a promo film. The **Nearby** scene is the trailer proper: the local user types + sends a message, peers
 * reply, more nodes join (the "connected to N" header climbs), and the room fills with photos, an animated GIF, a voice
 * note, a link-preview card, reactions, quoted replies, @-mentions, "typing…" cues, and a line that arrives over a LoRa
 * board rather than the radios.
 *
 * The four **call** scenes are one phone each of a two-phone call film (`.private/scripts/build-call-promo.sh`): a live
 * call with the verified contact (`call-out`, this phone dials; `call-in`, this phone is the one dialled) and a mesh
 * call, the walkie-talkie, with Sam (`walkie-out`, `walkie-in`). They run the app's own call stack against a scripted
 * peer over loopback ([DemoCalls]), so the call screen, the ring, the captions, the transcript and the record left in
 * the thread are the real ones. A callee take is the same cast seen from the other phone ([DemoScenario.mirrored]),
 * and both takes of a pair share their beats to the millisecond from the cue, so laid side by side they ring, connect
 * and speak in step: each phone feeds its captions and its transcript the other side's line.
 *
 * It writes through [DemoWriter] (the same repository primitives the static [DemoSeeder] uses), so every
 * change animates into the live UI with no bespoke rendering — peer messages/reactions land via reactive
 * Room flows, the header climbs off the shared [roster] flow the no-op [app.getknit.knit.mesh.DemoTransport]
 * reports, typing rides [MeshManager.seedDemoTyping]/[MeshManager.clearDemoTyping], and the local "type +
 * send" beats drive the real composer via [DemoComposer]. Nothing here compiles into release
 * ([BuildConfig.DEMO_DIRECTOR] is a compile-time false there and the class lives in `src/debug`).
 */
class DemoDirector(
    private val koin: Koin,
    // Shared with DemoTransport (built in DemoWiring): mutating it grows the reported neighbor set, so the
    // "connected to N" header climbs live. Starts at one node (see DemoWiring).
    private val roster: MutableStateFlow<Set<Peer>>,
    // Which scene this launch asked for — completed by MainActivity from its intent, which starts long before the
    // director needs it (see DemoWiring's demoScene).
    private val scene: Deferred<DemoScene>,
    // The `…debug.DEMOGO` cue a call scene holds for before its first beat (see DemoWiring's demoGo).
    private val go: Deferred<Unit>,
) {
    private val mesh = koin.get<MeshManager>()
    private val composer = koin.get<DemoComposer>()
    private val nearby = Conversations.NEARBY

    private lateinit var writer: DemoWriter

    suspend fun run() {
        runCatching { play() }
            .onFailure { Log.e(TAG, "trailer playback failed", it) }
    }

    private suspend fun play() {
        val me = koin.get<Identity>().nodeId()
        // A launch that never reached MainActivity (nothing completes the request) plays the trailer.
        val which = withTimeoutOrNull(SCENE_WAIT_MS) { scene.await() } ?: DemoScene.NEARBY
        val base = demoScenarioFor(BuildConfig.DEMO_THEME)
        // A callee take is the same cast from the other phone: its profile is the peer's, and the peer is us.
        val scenario = which.mirror?.let(base::mirrored) ?: base
        // Every message the writer might resolve a reply against: the scenario's own history (its DM/group
        // replies), plus this timeline's ambient + live messages.
        val timelineMsgs = timeline().mapNotNull { (it as? Beat.PeerMessage)?.msg }
        val msgById =
            (
                scenario.nearby +
                    scenario.dms.flatMap { it.messages } +
                    scenario.group.messages +
                    scenario.requests.flatMap { it.messages } +
                    scenario.requestGroup?.messages.orEmpty() +
                    AMBIENT +
                    timelineMsgs
            ).associateBy { it.id }
        val faces = which.mirror?.let { slot -> DemoSeeder.idOf(slot).let { mapOf("me" to it, it to "me") } }.orEmpty()
        writer = DemoWriter(koin, scenario, me, msgById, faces)

        val now = System.currentTimeMillis()
        // Baseline: profile + cast, a couple of ambient Nearby lines so the room isn't empty, and the full
        // DM + group threads so the trailer's cutaways (chat/danich01, the group) have real history.
        writer.seedProfileAndPeers(now)
        AMBIENT.forEach { writer.write(it, nearby, dmPeer = null, now) }
        writer.seedDms(now)
        val groupId = writer.seedGroup(scenario.group, now)
        writer.seedRequests(now)
        writer.seedCallRecords(now)
        // The Meshtastic room's history: the trailer cuts to it, and binding the board below is what makes
        // its row exist at all.
        writer.seedMeshRoom(now)
        // The trailer's cutaways are of chat screens, whose headers carry the relay globe and the board
        // glyph — so the director arms both planes exactly as the still seeder does.
        DemoPlanes.arm(koin, coveredLabels = scenario.dms.map { writer.nodeId(it.peer) } + groupId)

        // Give the cold-started screen a beat to compose and subscribe to DemoComposer before the first
        // local-type beat (its command flow has no replay, so a too-early emit would be missed).
        delay(1_500)
        val stage = stageFor(which)
        // A cued scene holds, settled and still, until the recording is rolling and the cue lands; a run nobody
        // cues (a hand launch) plays anyway once the wait runs out.
        if (stage.cued) withTimeoutOrNull(GO_WAIT_MS) { go.await() }
        Log.i(TAG, "scene ${which.key}: playing ${stage.script.size} beats")
        for (beat in stage.script) {
            delay(beat.delayMs)
            // Each beat's wall-clock, for a recording to be aligned against (the first one is its frame zero).
            Log.i(TAG, "beat ${beat::class.simpleName}")
            apply(beat, stage)
        }
    }

    /** The scene's conversation and beats. A call scene also readies its peer: in range, and the CC button set. */
    private suspend fun stageFor(which: DemoScene): Stage {
        val peer = which.peer ?: return Stage(nearby, dmPeer = null, timeline())
        check(CALLS_WIRED) { "scene ${which.key} needs a build with calls (-Pcalls=true)" }
        val peerId = writer.nodeId(peer)
        // The call's peer is a neighbour: the header says so, and the call's reach rule finds them.
        roster.update { it + Peer(peerId) }
        // The scripted peer answers this phone's ring as the callee take answers its own: the same wait, so the pair
        // connects in step. Captions on for a live call, from the first frame (the CC button's remembered choice).
        DemoCalls.answerAfterMs = if (which.walkie) WALKIE_ANSWER_MS else LIVE_ANSWER_MS
        if (!which.walkie) DemoCalls.captions(true)
        return Stage(peerId, dmPeer = peerId, callScript(which), cued = true)
    }

    private suspend fun apply(
        beat: Beat,
        stage: Stage,
    ) {
        when (beat) {
            is Beat.PeerJoin -> {
                roster.update { it + Peer(writer.nodeId(beat.slot)) }
            }

            is Beat.Typing -> {
                mesh.seedDemoTyping(stage.conversation, writer.nodeId(beat.slot))
            }

            is Beat.PeerMessage -> {
                // Resolve the peer's typing bubble the instant their message lands.
                mesh.clearDemoTyping(stage.conversation, writer.nodeId(beat.msg.from))
                writer.write(beat.msg, stage.conversation, stage.dmPeer, System.currentTimeMillis())
            }

            is Beat.Dial -> {
                // The call screen opens on its own for an outgoing call (CallController), as it does for the Call button.
                val refusal = koin.get<CallManager>().start(checkNotNull(stage.dmPeer), beat.kind)
                if (refusal != null) Log.e(TAG, "dial refused: $refusal")
            }

            is Beat.Ring -> {
                // Off the beat's clock, as a dial is: the ring lands a hop later, as the caller's take sees it ring.
                val peerId = checkNotNull(stage.dmPeer)
                koin.get<CoroutineScope>().launch {
                    DemoCalls.ring(peerId, walkie = beat.walkie)
                    // The phone is in use, so the ring would be a heads-up; the take shows the call screen it opens.
                    DemoCalls.await(CallPhase.Alerting)?.let { ringing ->
                        val context = koin.get<Context>()
                        context.startActivity(CallActivity.intent(context, ringing.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            }

            is Beat.Answer -> {
                val calls = koin.get<CallManager>()
                calls.state.value
                    ?.takeIf { it.phase == CallPhase.Alerting }
                    ?.let { calls.answer(it.id) }
                    ?: Log.e(TAG, "nothing to answer")
            }

            is Beat.PeerSays -> {
                // Seconds of audio: the beats after it keep their own clock.
                koin.get<CoroutineScope>().launch { if (!DemoCalls.say(beat.line)) Log.e(TAG, "the peer could not say ${beat.line}") }
            }

            is Beat.Talk -> {
                koin.get<CoroutineScope>().launch { if (!DemoCalls.talk(beat.line)) Log.e(TAG, "could not talk ${beat.line}") }
            }

            is Beat.HangUp -> {
                if (beat.peer) DemoCalls.peerHangsUp() else koin.get<CallManager>().hangUp()
            }

            is Beat.Pause -> {}

            is Beat.Reaction -> {
                writer.react(beat.targetId, beat.reactor, beat.emoji, System.currentTimeMillis())
            }

            is Beat.LocalType -> {
                composer.type(beat.text, beat.perCharMs)
                // Hold until the field has finished filling, so the following LocalSend sends the whole line.
                delay(beat.text.length * beat.perCharMs + 250)
            }

            is Beat.LocalSend -> {
                composer.send()
            }
        }
    }

    /** What a scene plays on: its conversation (the DM peer, for a DM), its beats, and whether it waits to be cued. */
    private class Stage(
        val conversation: String,
        val dmPeer: String?,
        val script: List<Beat>,
        val cued: Boolean = false,
    )

    private companion object {
        const val TAG = "DemoDirector"

        /** How long to wait for MainActivity's scene before defaulting to the trailer. */
        const val SCENE_WAIT_MS = 3_000L

        /** How long a cued scene holds for `…debug.DEMOGO` before playing on its own. */
        const val GO_WAIT_MS = 120_000L

        /** How long the callee lets a live call ring, and a walkie one: long enough to read the ring. */
        const val LIVE_ANSWER_MS = 2_400L
        const val WALKIE_ANSWER_MS = 2_000L

        /**
         * One phone of a call pair. Every beat is timed from the cue and the two takes of a pair share them, so the
         * caller's phone and the callee's ring, connect and speak in step: the first beat dials (or is dialled), the
         * callee answers [LIVE_ANSWER_MS] after its ring (the scripted peer on the caller's take, this phone on the
         * callee's), and each line is said at the same instant on both — heard on the phone that is not saying it.
         * Then the caller hangs up, and the call screen gives way to the thread with the call's line in it.
         */
        private fun callScript(scene: DemoScene): List<Beat> {
            val out = scene.mirror == null
            val open: Beat =
                if (out) {
                    Beat.Dial(
                        if (scene.walkie) CallKind.WALKIE else CallKind.LIVE,
                        delayMs = 800,
                    )
                } else {
                    Beat.Ring(scene.walkie, delayMs = 800)
                }
            // The callee's answer, its ring's length after the ring lands (one hop after the first beat): by the scripted
            // peer on the caller's take, where this phone only waits the same while, and by this phone on the callee's.
            val answerAt = (if (scene.walkie) WALKIE_ANSWER_MS else LIVE_ANSWER_MS) + DemoCalls.HOP_MS
            val ring = if (out) Beat.Pause(answerAt) else Beat.Answer(answerAt)

            // A line said on this phone is the user's own voice (captioned nowhere, or a burst it records); one said on
            // the other phone is heard here.
            fun says(
                line: String,
                byCaller: Boolean,
                delayMs: Long,
            ): Beat =
                when (byCaller) {
                    out if scene.walkie -> Beat.Talk(line, delayMs)
                    out -> Beat.Pause(delayMs)
                    else -> Beat.PeerSays(line, delayMs)
                }
            val hangUp = Beat.HangUp(peer = !out, delayMs = if (scene.walkie) 7_000 else 8_000)
            return if (scene.walkie) {
                listOf(
                    open,
                    ring,
                    says("walkie-caller", byCaller = true, delayMs = 1_600),
                    says("walkie-callee", byCaller = false, delayMs = 5_200),
                    hangUp,
                    // The thread with the session's line in it, before the take ends.
                    Beat.Pause(3_000),
                )
            } else {
                listOf(
                    open,
                    ring,
                    says("live-caller", byCaller = true, delayMs = 2_000),
                    says("live-callee", byCaller = false, delayMs = 3_800),
                    hangUp,
                    Beat.Pause(3_000),
                )
            }
        }

        // Ambient Nearby history so the room reads as "already happening" when the recording opens.
        private val AMBIENT =
            listOf(
                DemoMsg("dir-amb-1", Slot.THEO, "Sunrise set at the temple in 20 🌅", minsAgo = 6),
                DemoMsg("dir-amb-2", Slot.SAM, "Dusty Rhino's parked at center camp for now 🚐", minsAgo = 3),
            )

        // The festival trailer script (~42 s of motion). Beats apply after their `delayMs`; the pacing is
        // deliberately unhurried — a quiet beat after the opening send, then typing cues that LINGER ~2 s
        // before each reply, and a read-pause before reactions — so it feels like a real conversation
        // building. Peer messages use minsAgo = 0 → sentAt = now, so they animate in live. The roster opens
        // at one node (Kai / SAM, seeded in DemoWiring) and climbs to 5. build-trailer.sh's mild SPEED trims
        // the wall-clock without erasing the suspense.
        private fun timeline(): List<Beat> =
            listOf(
                // ME types + sends on the real composer (opens at "connected to 1"), deliberately slow.
                Beat.LocalType("just got to center camp — where's everyone at?", delayMs = 500, perCharMs = 65),
                Beat.LocalSend(delayMs = 400),
                // A held beat of quiet — nobody's answered yet — then Kai starts typing and it lingers.
                Beat.Typing(Slot.SAM, delayMs = 2_400),
                Beat.PeerMessage(DemoMsg("dir-1", Slot.SAM, "Right here! Rolling the art car your way 🚐 hop on", 0), delayMs = 2_200),
                // Echo joins (2) and calls out the sunrise set.
                Beat.PeerJoin(Slot.THEO, delayMs = 1_300),
                Beat.Typing(Slot.THEO, delayMs = 800),
                Beat.PeerMessage(
                    DemoMsg("dir-2", Slot.THEO, "Sunrise set's starting — follow the sound to the temple 🔊✨", 0),
                    delayMs = 2_100,
                ),
                // Sage joins (3) with an inline PHOTO; reactions land after a beat to read it.
                Beat.PeerJoin(Slot.PRIYA, delayMs = 1_400),
                Beat.Typing(Slot.PRIYA, delayMs = 800),
                Beat.PeerMessage(
                    DemoMsg("dir-3", Slot.PRIYA, "Water + electrolytes by the big dragon 💧 stay hydrated fam", 0, image = "dragon"),
                    delayMs = 2_300,
                ),
                Beat.Reaction("dir-3", Slot.ME, "😍", delayMs = 1_500),
                Beat.Reaction("dir-3", Slot.SAM, "🔥", delayMs = 800),
                // Luna joins (4, verified) and @-mentions Zara; ME replies on the real composer.
                Beat.PeerJoin(Slot.DANI, delayMs = 1_400),
                Beat.Typing(Slot.DANI, delayMs = 900),
                Beat.PeerMessage(
                    DemoMsg("dir-4", Slot.DANI, "@Zara Vance saved you a cushion up top 🛋️ don't be late", 0, mentionsMe = true),
                    delayMs = 2_200,
                ),
                Beat.LocalType("omw Luna! 🙌", delayMs = 1_300, perCharMs = 65),
                Beat.LocalSend(delayMs = 400),
                // Ravi joins (5) with a quoted reply back to Echo's nav call.
                Beat.PeerJoin(Slot.LENA, delayMs = 1_400),
                Beat.Typing(Slot.LENA, delayMs = 900),
                Beat.PeerMessage(
                    DemoMsg("dir-6", Slot.LENA, "Dome's lit green if you get lost — aim for the laser 🟢", 0, replyTo = "dir-2"),
                    delayMs = 2_200,
                ),
                Beat.Reaction("dir-6", Slot.SAM, "🙌", delayMs = 1_400),
                // A link, and the card its sender fetched and attached with it: everyone in the room sees the
                // page's title and picture without a single one of them touching the site.
                Beat.Typing(Slot.PRIYA, delayMs = 1_100),
                Beat.PeerMessage(
                    DemoMsg(
                        "dir-6a",
                        Slot.PRIYA,
                        "full sunrise line-up: https://playa.getknit.app/sunrise-sets",
                        0,
                        link =
                            DemoLink(
                                url = "https://playa.getknit.app/sunrise-sets",
                                title = "Sunrise Sets — the whole week",
                                description = "Every dawn set on the playa, camp by camp, with the walk time from center camp.",
                                image = "sunrise-card",
                            ),
                    ),
                    delayMs = 2_100,
                ),
                // A voice note lands and holds its own beat — the bubble is wide and the waveform reads at
                // a glance, so it does not need the dwell a photo does.
                Beat.Typing(Slot.SAM, delayMs = 1_100),
                Beat.PeerMessage(DemoMsg("dir-6b", Slot.SAM, "", 0, voiceSeconds = 7), delayMs = 1_900),
                // Then one from far outside radio range, carried by a LoRa board: the bubble wears the board
                // glyph, which is the only place in the trailer the long-range plane is visible.
                Beat.PeerMessage(
                    DemoMsg(
                        "dir-6c",
                        Slot.THEO,
                        "way out past the trash fence — this one's coming over the board 📻",
                        0,
                        via = DeliveryPlane.LoRa,
                    ),
                    delayMs = 2_000,
                ),
                // The animated GIF gets its own moment — a lingering typing cue, the clip, then reactions
                // dwell on it (and it stays on screen through the two-peer typing finale below).
                Beat.Typing(Slot.PRIYA, delayMs = 1_300),
                Beat.PeerMessage(
                    DemoMsg("dir-7", Slot.PRIYA, "the playa is pure magic tonight ✨", 0, image = "playamagic", imageMime = "image/webp"),
                    delayMs = 2_300,
                ),
                Beat.Reaction("dir-7", Slot.ME, "🤩", delayMs = 1_700),
                Beat.Reaction("dir-7", Slot.SAM, "🔥", delayMs = 900),
                // Finale: two people start typing at once — the conversation keeps going.
                Beat.Typing(Slot.THEO, delayMs = 1_400),
                Beat.Typing(Slot.SAM, delayMs = 300),
            )
    }
}

/** One step of the trailer timeline. [delayMs] is the wait AFTER the previous beat, BEFORE this one applies. */
private sealed interface Beat {
    val delayMs: Long

    /** Add one node to the reported neighbor roster (grows "connected to N"). */
    data class PeerJoin(
        val slot: Slot,
        override val delayMs: Long,
    ) : Beat

    /** Show "<slot> is typing…" in Nearby (cleared automatically by that slot's next [PeerMessage]). */
    data class Typing(
        val slot: Slot,
        override val delayMs: Long,
    ) : Beat

    /** Insert an inbound peer message (sentAt = now → sorts to the bottom, animates in live). */
    data class PeerMessage(
        val msg: DemoMsg,
        override val delayMs: Long,
    ) : Beat

    /** Pop a reaction onto an existing message id (an ambient or timeline peer message). */
    data class Reaction(
        val targetId: String,
        val reactor: Slot,
        val emoji: String,
        override val delayMs: Long,
    ) : Beat

    /** The local user types [text] into the REAL Nearby composer, char-by-char. */
    data class LocalType(
        val text: String,
        override val delayMs: Long,
        val perCharMs: Long = 55,
    ) : Beat

    /** The local user fires the real send button on whatever is in the composer. */
    data class LocalSend(
        override val delayMs: Long,
    ) : Beat

    /** Nothing: the next beat's wait, kept in step with the other take of a pair. */
    data class Pause(
        override val delayMs: Long,
    ) : Beat

    /** This phone calls the scene's peer, as the chat's Call (or Mesh call) button does. */
    data class Dial(
        val kind: CallKind,
        override val delayMs: Long,
    ) : Beat

    /** The scene's peer calls this phone, and the call screen comes up for the ring. */
    data class Ring(
        val walkie: Boolean,
        override val delayMs: Long,
    ) : Beat

    /** This phone answers the call that is ringing, as the call screen's Answer does. */
    data class Answer(
        override val delayMs: Long,
    ) : Beat

    /** The peer says [line] (`demo/voice/<theme>/<line>.pcm`): into the captions, or as a walkie burst. */
    data class PeerSays(
        val line: String,
        override val delayMs: Long,
    ) : Beat

    /** This phone's walkie burst says [line], the talk button held while it lasts. */
    data class Talk(
        val line: String,
        override val delayMs: Long,
    ) : Beat

    /** The call ends: hung up here, or by the [peer]. */
    data class HangUp(
        val peer: Boolean,
        override val delayMs: Long,
    ) : Beat
}

/**
 * What the director plays, named by the `demo_scene` launch extra: `nearby` (or none) for the trailer's Nearby room, and
 * one phone each of the two call pairs — `call-out` / `call-in`, a live call with the verified contact, and
 * `walkie-out` / `walkie-in`, a mesh call with Sam. Cold-start a call scene into `chat/<peer>` (see `record-trailer.sh`);
 * an `-in` scene plays the [mirror] cast, so its phone is the peer's.
 */
enum class DemoScene(
    val key: String,
    /** The DM peer a call scene calls or is called by; null for the room. */
    val peer: Slot? = null,
    val walkie: Boolean = false,
    /** The slot whose phone this take is, for a callee take; null when it is the local user's own. */
    val mirror: Slot? = null,
) {
    NEARBY("nearby"),
    CALL_OUT("call-out", peer = Slot.DANI),
    CALL_IN("call-in", peer = Slot.DANI, mirror = Slot.DANI),
    WALKIE_OUT("walkie-out", peer = Slot.SAM, walkie = true),
    WALKIE_IN("walkie-in", peer = Slot.SAM, walkie = true, mirror = Slot.SAM),
    ;

    companion object {
        /** The scene [key] names; the trailer for null or anything unknown, so a typo films the default rather than nothing. */
        fun of(key: String?): DemoScene = entries.firstOrNull { it.key == key } ?: NEARBY
    }
}
