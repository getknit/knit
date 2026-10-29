package app.getknit.knit.mesh

import app.getknit.knit.mesh.protocol.WireEnvelope

/**
 * Supplies our own carried DMs worth re-airing to a peer a long-range plane
 * ([app.getknit.knit.mesh.lora.LoraMeshTransport]) has just heard for the first time (ADR 039), or is re-probing
 * while it stays silent (ADR 2026-10.rp3s). That plane has no custody sync — `neighbors` is always empty, so
 * `ForwardSync`'s digest exchange never runs over it — and a DM sent while the peer's board was off is otherwise
 * lost to it until radio or spool contact. Since ADR 2026-10.a4pm it is also the delivery path for a DM the LoRa
 * reach gate held because its recipient was not heard. Implemented by [MeshManager] as our newest few unacked DMs
 * to the peer, chosen by id (`MeshManager.FAR_REOFFER_OWN_LIMIT`), re-wrapped verbatim like a custody re-serve; the
 * frames others wrote that it used to add are gone with the pocket bridge (ADR 2026-10.rp3s). Late-bound (the
 * transport is constructed before [MeshManager]); resolved at call time.
 */
fun interface FarPeerFrameSource {
    /** Our own signed DMs to re-air to [nodeId], newest first; empty when nothing is worth an airslot. */
    suspend fun framesFor(nodeId: String): List<WireEnvelope>
}
