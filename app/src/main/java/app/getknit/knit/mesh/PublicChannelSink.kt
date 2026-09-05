package app.getknit.knit.mesh

/**
 * Puts one line of text on the foreign mesh's public channel (the LongFast bridge's outbound half).
 *
 * The mirror of [MeshPostSink], pointing the other way: that seam exists so `mesh/lora/` can mint a Knit frame
 * without knowing how to sign one, and this exists so [InboundPipeline] can reach a radio without knowing one
 * is there. Implemented by `mesh/lora/LoraMeshTransport`, and injected as a lambda that defaults to doing
 * nothing — a build with no board, and every test that does not care, then needs no wiring at all.
 *
 * **Who calls it, and why it is the delivery path rather than the send path.** A post typed in the bridged
 * room floods over the short-range planes like any other frame, and the pocket's ACTIVE gateway may be a
 * different phone from the one that wrote it — very often it is, since the author may hold no radio. So the
 * gateway does not wait to be asked: it transmits the posts it sees arrive, which makes "any phone in the
 * pocket can post" one condition on a frame that was already going to reach it rather than a second protocol.
 *
 * The rule that condition states (§5 of work item #37) is that **only a post written here, or heard over a
 * short-range plane, may reach the air.** One that arrived over LoRa was already on that band; one off a spool
 * came from outside the neighbourhood entirely, and re-posting it would make every far pocket a repeater.
 */
interface PublicChannelSink {
    /**
     * Transmits one post on the board's public primary channel, or refuses.
     *
     * Returns false — having counted why — for every reason this device is not the one to do it: it is not
     * the pocket's ACTIVE gateway, its board is not ready, its primary is not the stock public channel, the
     * per-gateway floor has not elapsed, or the airtime bucket is spent. **A refusal is ordinary**, not an
     * error: on a two-board pocket exactly one device returns true for any given post.
     *
     * [name] and [body] are passed apart, and the line a stock client reads is composed behind this seam
     * (`mesh/lora/PublicPostPolicy.onAirText`). Both halves of that matter: the caller does not have to know
     * what a Meshtastic client's 200-byte convention is, and — because the rule lives in one place — two
     * gateways that both accepted the same post would put identical bytes on the air.
     */
    suspend fun postToPublicChannel(
        name: String?,
        body: String,
    ): Boolean
}
