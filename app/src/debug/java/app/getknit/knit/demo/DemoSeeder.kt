package app.getknit.knit.demo

import android.os.Build
import android.util.Log
import app.getknit.knit.BuildConfig
import app.getknit.knit.crash.CrashStore
import app.getknit.knit.crash.currentCrashEnvironment
import app.getknit.knit.data.settings.ModelLoadState
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.Identity
import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.MeshManager
import app.getknit.knit.moderation.ModelLoadGuard
import app.getknit.knit.moderation.ModelLoadPolicy
import app.getknit.knit.moderation.modelGuardStamp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import org.koin.core.Koin

/**
 * Populates the database with a believable conversation history so the app renders fully on an
 * emulator — used only by the static demo-screenshot build (`-PseedDemo=true`; the field defaults false,
 * so this never runs in normal/release builds, and it is skipped when the animated `-PdemoDirector` is on,
 * which seeds its own baseline). The concrete content (cast, messages, group) comes from a [DemoScenario]
 * chosen by `-PdemoTheme` (see [demoScenarioFor]), so we can shoot multiple marketing themes from one code.
 *
 * The actual writes go through [DemoWriter], the shared primitives the animated [DemoDirector] also uses,
 * so both stay in lockstep. Paired with [app.getknit.knit.mesh.DemoTransport], which reports
 * [ONLINE_NODE_IDS] as connected so the "connected" header and contact "online" dots light up. All writes
 * are idempotent upserts keyed by stable ids, so a relaunch re-seeds deterministically.
 *
 * Beyond the conversation history it arms the two **planes** the emulator cannot actually run — the
 * Internet relays and the LoRa board, via [DemoPlanes] — because both are now visible all over the UI (a
 * globe or a board glyph on a chat header, a bubble and a ✓✓ tick, two settings screens), and without them
 * every capture of that surface would be of its off state. Binding the board also brings up the **Meshtastic
 * room**, whose history is seeded here for the same reason ([DemoWriter.seedMeshRoom]).
 */
class DemoSeeder(
    private val koin: Koin,
) {
    suspend fun seed() {
        try {
            runCatching { seedInternal() }
                .onFailure { Log.e("DemoSeeder", "demo seeding failed", it) }
        } finally {
            completed.complete(Unit)
        }
    }

    private suspend fun seedInternal() {
        val me = koin.get<Identity>().nodeId()
        val scenario = demoScenarioFor(BuildConfig.DEMO_THEME)
        val msgById =
            (
                scenario.nearby +
                    scenario.dms.flatMap { it.messages } +
                    scenario.group.messages +
                    scenario.requests.flatMap { it.messages } +
                    scenario.requestGroup?.messages.orEmpty()
            ).associateBy { it.id }
        val writer = DemoWriter(koin, scenario, me, msgById)
        val now = System.currentTimeMillis()

        writer.seedProfileAndPeers(now)
        writer.seedNearby(now)
        writer.seedDms(now)
        val groupId = writer.seedGroup(scenario.group, now)
        writer.seedRequests(now)
        writer.seedBlocked()
        writer.seedYourMesh(now)
        // The paired board's own channel, mirrored locally. [DemoPlanes] binds the board below, and the room's
        // row appears the moment one is bound — so without this the chat list would carry an empty room.
        writer.seedMeshRoom(now)

        // Pin one persistent "now typing" cue for the dm-sam marketing shot. A real cue is TTL'd (12s) and
        // would race a static capture; this bypasses the TTL. For a DM the conversationId is the peer's
        // nodeId (see seedDms), so both args are the same slot.
        koin.get<MeshManager>().seedDemoTyping(conversationId = SAM, senderId = SAM)

        // The two planes an emulator cannot run, reported rather than dialled. Covered scopes are the
        // accepted threads only: a stranger you have not answered has no scope in the real plane either.
        DemoPlanes.arm(koin, coveredLabels = scenario.dms.map { nodeIdOf(it.peer) } + groupId)
        seedCrashReport()
        seedLatchedModel()
    }

    /**
     * Plants one synthetic crash report so the "Last crash" row and the crash screen have something to
     * render. Without it the accessibility audit would only ever see the empty state, and the dense
     * monospace trace plus the error-tinted destructive action — exactly what the checks exist for —
     * would ship unaudited. Goes through the real [CrashStore.record], so what is audited is what a real
     * crash produces, redaction included.
     */
    private fun seedCrashReport() {
        val store = koin.get<CrashStore>()
        if (store.latest() != null) return
        store.record(
            environment = currentCrashEnvironment(),
            threadName = "DefaultDispatcher-worker-3",
            throwable =
                IllegalStateException("hello reply ${NodeId.derive(SAM)} != expected ${NodeId.derive(DANI)}"),
        )
    }

    /**
     * Latches the toxicity model off, so the Diagnostics "Problem reports" section renders its
     * poison-pill row (ADR 037) and the accessibility audit covers it instead of only its absence. Goes
     * through the real journal, so what is audited is the real state a latched phone reaches.
     *
     * Side effect, accepted knowingly: the seeded build then runs lexical-only, which makes a text send
     * *faster* (no cold tflite load — see `.agents/context/testing.md`). Nothing asserts on an ML verdict;
     * `ModerationRevealUiAutomatorTest` drives the synthetic `FLAGMSG` seam instead.
     */
    private suspend fun seedLatchedModel() {
        koin.get<SettingsStore>().setModelLoadState(
            model = ModelLoadGuard.TOXICITY,
            state =
                ModelLoadState(
                    stamp = modelGuardStamp(BuildConfig.VERSION_CODE, Build.FINGERPRINT.orEmpty()),
                    pendingSince = 0L,
                    fails = ModelLoadPolicy.MAX_FAILS,
                ),
        )
    }

    /** The node id behind a scenario [Slot] — the seeder's own copy of [DemoWriter.nodeId] for ME-free slots. */
    private fun nodeIdOf(slot: Slot): String =
        when (slot) {
            Slot.SAM -> SAM
            Slot.DANI -> DANI
            Slot.THEO -> THEO
            Slot.PRIYA -> PRIYA
            Slot.JONAS -> JONAS
            Slot.LENA -> LENA
            Slot.JONAS_TWO -> JONAS_TWO
            Slot.RIVER -> RIVER
            Slot.NOAH -> NOAH
            Slot.MARLO -> MARLO
            Slot.ME -> error("ME has no fixed id — it is this device's runtime node id")
        }

    companion object {
        private val completed = CompletableDeferred<Unit>()

        /**
         * Completes when this process's [seed] returns, whether it succeeded or not. The seed runs detached on
         * the IO dispatcher, and it writes the peers well before the requests or the room's newest posts, so a
         * screen that reads fast enough can draw it half-written. The seeded UI suite waits on this before it
         * launches anything.
         */
        val seeded: Deferred<Unit> get() = completed

        // Stable, illustrative demo node ids — short fixed slots (NOT the real 26-char base32 [NodeId]
        // format; demo peers are seeded straight into the DB and never advertised over a radio, so any
        // opaque string works). Names/avatars/messages vary by theme, but the id slots stay constant so
        // ONLINE_NODE_IDS and the fake transport are theme-independent.
        const val SAM = "samr1v00"
        const val DANI = "danich01"
        const val THEO = "theob123"
        const val PRIYA = "priyan07"
        const val JONAS = "jonasw88"
        const val LENA = "lenaf042"

        /** The second "Jonas W." of the hiking cast: the seeded same-name collision (ADR 058). */
        const val JONAS_TWO = "jonas2w9"

        /** The stranger whose unanswered DM is the seeded message request. */
        const val RIVER = "river7x2"

        /** The stranger who added us to the seeded request group. */
        const val NOAH = "noahq415"

        /** The one blocked peer, so "Blocked users" has a row. */
        const val MARLO = "marlo9b3"

        /** The subset of demo peers reported as connected by [app.getknit.knit.mesh.DemoTransport]. */
        val ONLINE_NODE_IDS: Set<String> = setOf(SAM, DANI, PRIYA)
    }
}
