@file:Suppress("MagicNumber") // the timeline is a hand-tuned list of millisecond beat delays; naming each would only add noise

package app.getknit.knit.demo

import android.util.Log
import app.getknit.knit.BuildConfig
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.identity.Identity
import app.getknit.knit.mesh.MeshManager
import app.getknit.knit.mesh.Peer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.koin.core.Koin

/**
 * Debug-only trailer director (`-PdemoDirector=true`). Plays a scripted, animated conversation on the
 * **Nearby** room so a screen recording becomes the promo trailer: the local user types + sends a message,
 * peers reply, more nodes join (the "connected to N" header climbs), and the room fills with photos, an
 * animated GIF, reactions, quoted replies, @-mentions, and "typing…" cues.
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
) {
    private val mesh = koin.get<MeshManager>()
    private val composer = koin.get<DemoComposer>()
    private val nearby = Conversations.NEARBY

    private lateinit var writer: DemoWriter

    suspend fun run() {
        runCatching { play() }
            .onFailure { Log.e("DemoDirector", "trailer playback failed", it) }
    }

    private suspend fun play() {
        val me = koin.get<Identity>().nodeId()
        val scenario = demoScenarioFor(BuildConfig.DEMO_THEME)
        val script = timeline()
        // Every message the writer might resolve a reply against: the scenario's own history (its DM/group
        // replies), plus this timeline's ambient + live messages.
        val timelineMsgs = script.mapNotNull { (it as? Beat.PeerMessage)?.msg }
        val msgById =
            (scenario.nearby + scenario.dms.flatMap { it.messages } + scenario.groupMessages + AMBIENT + timelineMsgs)
                .associateBy { it.id }
        writer = DemoWriter(koin, scenario, me, msgById)

        val now = System.currentTimeMillis()
        // Baseline: profile + cast, a couple of ambient Nearby lines so the room isn't empty, and the full
        // DM + group threads so the trailer's cutaways (chat/danich01, the group) have real history.
        writer.seedProfileAndPeers(now)
        AMBIENT.forEach { writer.write(it, nearby, dmPeer = null, now) }
        writer.seedDms(now)
        writer.seedGroup(now)

        // Give the cold-started Nearby screen a beat to compose and subscribe to DemoComposer before the
        // first local-type beat (its command flow has no replay, so a too-early emit would be missed).
        delay(1_500)
        for (beat in script) {
            delay(beat.delayMs)
            apply(beat)
        }
    }

    private suspend fun apply(beat: Beat) {
        when (beat) {
            is Beat.PeerJoin -> {
                roster.update { it + Peer(writer.nodeId(beat.slot)) }
            }

            is Beat.Typing -> {
                mesh.seedDemoTyping(nearby, writer.nodeId(beat.slot))
            }

            is Beat.PeerMessage -> {
                // Resolve the peer's typing bubble the instant their message lands.
                mesh.clearDemoTyping(nearby, writer.nodeId(beat.msg.from))
                writer.write(beat.msg, nearby, dmPeer = null, System.currentTimeMillis())
            }

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

    private companion object {
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
}
