package app.getknit.knit.demo

import app.getknit.knit.data.settings.KnitBoardSetup
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.mesh.DemoLoraPlane
import app.getknit.knit.mesh.MeshManager
import app.getknit.knit.mesh.spool.ScopeStatus
import app.getknit.knit.mesh.spool.SpoolStatus
import org.koin.core.Koin

/**
 * Arms the two planes an emulator cannot actually run — the Internet relays and the LoRa board — for the
 * demo builds. Shared by the static seeder ([DemoSeeder]) and the animated trailer ([DemoDirector]) for the
 * same reason [DemoWriter] is: both need a chat header wearing the globe and the board glyph, and a
 * settings screen with something in it, and neither may open a socket or dial a board to get there.
 *
 * The state is *reported*, never real. Each plane has two halves and they are armed differently on purpose:
 *
 * - The **settings** (relay list, consent, the bound board and its setup record) are genuine `SettingsStore`
 *   writes, so the two settings screens photograph their real controls in their real states.
 * - The **live status** is pinned — spools through [MeshManager.seedDemoSpools], the board through
 *   [app.getknit.knit.mesh.DemoLoraPlane] — because a demo build holds no `ScopeSync` session and no board
 *   link, and both report "nothing here", which is exactly the state a capture must not be of.
 *
 * Theme-independent, like [DemoSeeder.ONLINE_NODE_IDS]: a relay URL and a Heltec's node number read the
 * same at a trailhead and on the playa, so neither belongs in a [DemoScenario].
 */
object DemoPlanes {
    /**
     * Arms both planes. [coveredLabels] are the conversation ids that get a live scope on the connected
     * relays — a scope's label *is* its conversation id (`ScopeFrames.Scope.label`, see `RelayReach`), so
     * these are exactly the threads whose header says the Internet is carrying them. Pass the accepted
     * DM peers and the group id; leaving the request threads out is deliberate, because a stranger you
     * have not answered has no scope in the real plane either.
     */
    suspend fun arm(
        koin: Koin,
        coveredLabels: List<String>,
    ) {
        armRelays(koin, coveredLabels)
        armBoard(koin.get())
    }

    /**
     * Consents to, enables and populates the relay plane, then pins one status per relay.
     *
     * The three seeded relays cover the three row states the settings screen can draw, so all of them can
     * be photographed from one seed: connected and carrying photos, connected but frames-only (no
     * attachment budget advertised — spec §7.3), and refused because the spool is at its connection cap.
     */
    private suspend fun armRelays(
        koin: Koin,
        coveredLabels: List<String>,
    ) {
        val settings = koin.get<SettingsStore>()
        settings.acceptSpoolConsent() // records consent AND enables the plane, in one write
        RELAYS.forEach { settings.addSpoolUrl(it) }

        val scopes = coveredLabels.mapIndexed { i, label -> scope(label, i) }
        koin.get<MeshManager>().seedDemoSpools(
            listOf(
                SpoolStatus(
                    url = RELAYS[0],
                    connected = true,
                    powBits = POW_BITS,
                    lastError = null,
                    scopes = scopes,
                    maxAttachBytes = MAX_ATTACH_BYTES,
                ),
                SpoolStatus(
                    url = RELAYS[1],
                    connected = true,
                    powBits = POW_BITS,
                    lastError = null,
                    scopes = scopes,
                    maxAttachBytes = null, // advertised no attachment support → "carries frames, not photos"
                ),
                SpoolStatus(
                    url = RELAYS[2],
                    connected = false,
                    powBits = 0,
                    // A spool at its connection cap: "busy, it will come back", not "broken, fix the URL".
                    lastError = "busy",
                    scopes = emptyList(),
                ),
            ),
        )
    }

    /** One converged scope for [label]; [i] only varies the counts so the rows are not identically dull. */
    private fun scope(
        label: String,
        i: Int,
    ) = ScopeStatus(
        // Not a real scope id — nothing reads it but the debug bridge, and deriving one would need the
        // ratchet state a demo build has never negotiated.
        scopeHex = "demo" + label.take(SCOPE_HEX_CHARS),
        label = label,
        localCount = SCOPE_BASE_COUNT + i,
        spoolCount = SCOPE_BASE_COUNT + i,
        converged = true,
        invalidCount = 0,
        retiring = false,
        accountedCount = SCOPE_BASE_COUNT + i,
    )

    /**
     * Binds the demo board and marks it set up for Knit, so the LoRa radio screen photographs as a working
     * board rather than "pair one first", and the chat header, the DM notice and the Profile row have a
     * live plane to describe. The link state, signal, battery and airtime come from [DemoLoraPlane]; this
     * is only the settings it is read alongside.
     */
    private suspend fun armBoard(settings: SettingsStore) {
        settings.setLoraDevice(address = DemoLoraPlane.ADDRESS, name = DemoLoraPlane.BONDED_NAME)
        settings.setLoraChannelIndex(DemoLoraPlane.CHANNEL_INDEX)
        settings.setLoraEnabled(true)
        settings.setLoraDmEnabled(true)
        settings.setLoraBridgeEnabled(true)
        // The record a real setup leaves behind (ADR 045): without it the screen offers "Set up this board"
        // over a board that is already set up, and Restore has none of the user's own values to put back.
        settings.setLoraBoardSetup(
            KnitBoardSetup(
                address = DemoLoraPlane.ADDRESS,
                nodeInfoSecs = PRIOR_NODE_INFO_SECS,
                positionSecs = PRIOR_POSITION_SECS,
                smartPosition = true,
                telemetrySecs = PRIOR_TELEMETRY_SECS,
                rebroadcastMode = 0, // ALL, the firmware default a restore puts back
                longName = "Meshtastic 9f2c",
                shortName = "9f2c",
            ),
        )
    }

    /**
     * The relay list the demo seeds. The first entry is **the shipped default verbatim**
     * (`res/values/spools.xml`), because a fresh install seeds that one itself — a different string here
     * would leave a fourth row on the screen with no pinned status behind it. The other two are
     * illustrative hosts under a domain we own, so a screenshot can show a multi-relay list without
     * publishing somebody else's endpoint — or a bearer token, which for a private relay rides the URL
     * and is the whole of its access control.
     */
    val RELAYS =
        listOf(
            "wss://public-1.spool.getknit.app/spool/v1",
            "wss://fra.spool.getknit.app/spool/v1",
            "wss://syd.spool.getknit.app/spool/v1",
        )

    private const val POW_BITS = 20
    private const val MAX_ATTACH_BYTES = 4 * 1024 * 1024
    private const val SCOPE_BASE_COUNT = 12
    private const val SCOPE_HEX_CHARS = 8

    // The housekeeping intervals a real setup records before quieting the board (ADR 045).
    private const val PRIOR_NODE_INFO_SECS = 10_800
    private const val PRIOR_POSITION_SECS = 900
    private const val PRIOR_TELEMETRY_SECS = 1_800
}
