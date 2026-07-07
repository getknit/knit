package app.getknit.knit.demo

import android.util.Log
import app.getknit.knit.BuildConfig
import app.getknit.knit.identity.Identity
import app.getknit.knit.mesh.MeshManager
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
 */
class DemoSeeder(
    private val koin: Koin,
) {
    suspend fun seed() {
        runCatching { seedInternal() }
            .onFailure { Log.e("DemoSeeder", "demo seeding failed", it) }
    }

    private suspend fun seedInternal() {
        val me = koin.get<Identity>().nodeId()
        val scenario = demoScenarioFor(BuildConfig.DEMO_THEME)
        val msgById =
            (scenario.nearby + scenario.dms.flatMap { it.messages } + scenario.groupMessages)
                .associateBy { it.id }
        val writer = DemoWriter(koin, scenario, me, msgById)
        val now = System.currentTimeMillis()

        writer.seedProfileAndPeers(now)
        writer.seedNearby(now)
        writer.seedDms(now)
        writer.seedGroup(now)

        // Pin one persistent "now typing" cue for the dm-sam marketing shot. A real cue is TTL'd (12s) and
        // would race a static capture; this bypasses the TTL. For a DM the conversationId is the peer's
        // nodeId (see seedDms), so both args are the same slot.
        koin.get<MeshManager>().seedDemoTyping(conversationId = SAM, senderId = SAM)
    }

    companion object {
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

        /** The subset of demo peers reported as connected by [app.getknit.knit.mesh.DemoTransport]. */
        val ONLINE_NODE_IDS: Set<String> = setOf(SAM, DANI, PRIYA)
    }
}
