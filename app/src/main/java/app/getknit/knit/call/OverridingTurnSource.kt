package app.getknit.knit.call

/**
 * The person's own ICE servers over the spools' (docs/VOICE_CALLS.md §3.14, ADR 2026-10.mr88), one kind at a time.
 *
 * An own TURN server **replaces** the spools' credentials: [spool]'s [TurnSource.turnServers] is never called, so no
 * `turnreq` leaves the phone and no spool learns a call is starting. Own STUN URLs replace the HELLOs' list the same
 * way. Each kind is independent: own STUN alone still asks the spools for TURN. [custom] is read on every call and every
 * ICE restart, so a change on the Calls screen applies from the next one.
 */
class OverridingTurnSource(
    private val custom: () -> CustomIce,
    private val spool: TurnSource,
) : TurnSource {
    override suspend fun turnServers(peerId: String): List<TurnServer> = custom().turn?.let(::listOf) ?: spool.turnServers(peerId)

    override suspend fun turnServers(
        peerId: String,
        pick: (List<String>) -> String?,
    ): List<TurnServer> = custom().turn?.let(::listOf) ?: spool.turnServers(peerId, pick)

    // An own TURN server is never left for a spool's: with one set, a refusal has no next server here (C-7.6-54).
    override suspend fun turnServerFor(
        peerId: String,
        entry: String,
    ): TurnServer? = if (custom().turn != null) null else spool.turnServerFor(peerId, entry)

    override fun stunUrls(): List<String> = custom().stun.ifEmpty { spool.stunUrls() }

    override fun turnAvailable(): Boolean = custom().turn != null || spool.turnAvailable()
}
